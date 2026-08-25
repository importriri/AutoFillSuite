package app.ui;

import app.config.SettingsManager;
import app.core.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Mode: REGISTRA · a scansione — two QRs per item (label + lot), built around a
 * QUEUE: a scanned pair goes into the queue and the fields clear at once, so the
 * operator works at their own pace and nothing is lost. A worker drains the
 * queue one short burst at a time (~0.8s) and only starts when the scanner has
 * been quiet for a moment — never mid-scan.
 *
 * No per-item clipboard check: the CSV is the truth, like in Register. Every N
 * items the session is verified against the export; Verifica does it on demand.
 * The Export CSV coordinate is shared with the Register tab.
 *
 * Two modes:
 *  - Continuo (default): each pair is registered as it comes
 *  - A blocco: pairs pile up, "REGISTRA TUTTO" fires the whole queue at once
 *
 * THREE SAFETIES, because a burst types into a browser nobody is watching:
 *  1. mouse fail-safe — the robot leaves the pointer on the target; if it is
 *     somewhere else at the next step, the operator took the mouse back. Before
 *     ENTER the pair returns to the head of the queue and everything pauses;
 *     after ENTER the pair IS registered, so it counts, and only then we stop.
 *  2. inversion guard — ScanGuard reads the two codes against the operator's
 *     patterns and refuses (or fixes) a pair scanned into the wrong holes.
 *  3. duplicate guard — the same label twice in one session is a scanner that
 *     fired twice, not a second piece.
 */
public class ScanModePanel extends JPanel {

    private static final int QUIET_MS = 200;    // scanner idle for at least this
    private static final int STUCK_MS = 5000;   // half-scanned fields holding the queue

    // banner states
    private static final int READY = 0, BUSY = 1, VERIFY = 2, PAUSED = 3,
                             ERROR = 4, COLLECT = 5, WARN = 6, STUCK = 7;

    // a scanned pair: the two QRs and its table row
    private static final class Pair {
        String q1, q2;
        int row;
        Pair(String q1, String q2, int row) { this.q1 = q1; this.q2 = q2; this.row = row; }
    }

    private final SettingsManager cfg   = SettingsManager.getInstance();
    private final RobotEngine     robot = RobotEngine.getInstance();
    private final VerificationLog log   = new VerificationLog(
        new File(System.getProperty("user.home"), "AutoFillSuite_verifiche.txt").toPath());

    private final LinkedBlockingDeque<Pair> queue = new LinkedBlockingDeque<>();
    private final Map<String, String> session = new LinkedHashMap<>();   // EDT only

    private volatile boolean paused    = false;
    private volatile boolean batchMode = false;   // "a blocco": collect, then fire
    private volatile boolean releasing = false;
    private volatile boolean burstBusy = false;
    private volatile boolean verifying = false;
    private volatile boolean fieldsBusy = false;
    private volatile boolean verifyDue  = false;   // owed, not yet convenient
    private int sinceVerify = 0;                   // EDT only
    private volatile long lastInputAt  = 0;
    private volatile long fieldsBusyAt = 0;
    private volatile Point appPoint    = null;
    private boolean sessionStarted = false;
    private boolean sticky = false;               // a message that must not be wiped
    private volatile boolean blockRunning = false;   // a released block owns the HUD
    private int blockDone = 0;
    private int sent = 0;

    private VerificationTask verifyTask = null;
    private final ResultsPanel results;
    private final RunContext ctx;

    private JTextField tfQr1, tfQr2;
    private JLabel banner;
    private JButton btnFire, btnVerify, btnNewSession, btnClearFields, btnSwap;
    private Segmented modeSelector;
    private StatPair stats;

    public ScanModePanel(ResultsPanel results, RunContext ctx) {
        this.results = results;
        this.ctx = ctx;
        results.setQueueEditor(new ResultsPanel.QueueEditor() {
            @Override public boolean canEdit(int row) { return canEditQueued(row); }
            @Override public void edit(int row) { editQueued(row); }
            @Override public void delete(int row) { deleteQueued(row); }
        });
        setLayout(new BorderLayout());
        setBackground(AppTheme.BASE);
        setBorder(BorderFactory.createEmptyBorder(0, 9, 8, 9));
        add(buildCard(), BorderLayout.CENTER);

        Thread worker = new Thread(this::drainLoop, "dualscan-worker");
        worker.setDaemon(true);
        worker.start();

        // a pair left half-scanned holds the whole queue: after a few seconds
        // the banner says so instead of sitting on a cheerful green
        Timer heartbeat = new Timer(1000, e -> {
            watchdogBlock();
            maybeAutoVerify();
            nudgeBanner();
        });
        heartbeat.setRepeats(true);
        heartbeat.start();
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private JPanel buildCard() {
        JPanel card = AppTheme.card();
        card.setLayout(new GridBagLayout());
        card.setBorder(BorderFactory.createEmptyBorder(6, 10, 5, 10));
        GridBagConstraints g = AppTheme.gbc();
        g.insets = new Insets(1, 3, 1, 3);
        int y = 0;

        // QR1 + clear
        g.gridx = 0; g.gridy = y; g.weightx = 0;
        card.add(AppTheme.label("QR 1"), g);
        JPanel r1 = new JPanel(new BorderLayout(6, 0));
        r1.setOpaque(false);
        tfQr1 = AppTheme.fieldQr();
        btnClearFields = AppTheme.iconButton(
            Icons.cross(AppTheme.ICON, AppTheme.SUBTEXT), "Svuota i campi");
        r1.add(tfQr1, BorderLayout.CENTER);
        r1.add(btnClearFields, BorderLayout.EAST);
        g.gridx = 1; g.weightx = 1.0;
        card.add(r1, g);

        // QR2 + swap
        y++; g.gridx = 0; g.gridy = y; g.weightx = 0;
        card.add(AppTheme.label("QR 2"), g);
        JPanel r2 = new JPanel(new BorderLayout(6, 0));
        r2.setOpaque(false);
        tfQr2 = AppTheme.fieldQr();
        btnSwap = AppTheme.iconButton(
            Icons.swap(AppTheme.ICON, AppTheme.SUBTEXT), "Scambia QR 1 e QR 2  (F2)");
        r2.add(tfQr2, BorderLayout.CENTER);
        r2.add(btnSwap, BorderLayout.EAST);
        g.gridx = 1; g.weightx = 1.0;
        card.add(r2, g);

        // banner
        y++; g.gridx = 0; g.gridy = y; g.gridwidth = 2;
        g.insets = new Insets(3, 3, 2, 3);
        banner = AppTheme.banner();
        card.add(banner, g);
        g.insets = new Insets(2, 3, 2, 3);

        // modalita': un segmento, non una checkbox di sistema
        y++; g.gridy = y;
        batchMode = cfg.getBool(SettingsManager.SCAN_BATCH, false);
        modeSelector = new Segmented(new String[] { "Continuo", "A blocco" },
                                     false, batchMode ? 1 : 0, idx -> {
            batchMode = idx == 1;
            releasing = false;
            cfg.set(SettingsManager.SCAN_BATCH, batchMode);
            cfg.save();
            refreshBanner();
            updateFireButton();
        });
        card.add(modeSelector, g);

        // due numeri: quante dentro, quante ancora in attesa
        y++; g.gridy = y;
        stats = new StatPair("inviate", "in coda");
        stats.set(0, 0);
        card.add(stats, g);

        // leva primaria + secondarie
        y++; g.gridy = y;
        btnFire = AppTheme.primary("PAUSA", AppTheme.PEACH,
            Icons.pause(AppTheme.ICON, AppTheme.ON_ACCENT));
        card.add(btnFire, g);

        y++; g.gridy = y;
        JPanel sec = new JPanel(new GridLayout(1, 2, 6, 0));
        sec.setOpaque(false);
        btnVerify     = AppTheme.secondary("Verifica", Icons.search(AppTheme.ICON, AppTheme.SUBTEXT));
        btnNewSession = AppTheme.secondary("Nuova sessione", null);
        sec.add(btnVerify);
        sec.add(btnNewSession);
        card.add(sec, g);

        // ── listeners ─────────────────────────────────────────────────────
        DocumentListener typing = new DocumentListener() {
            private void touched() {
                lastInputAt = System.currentTimeMillis();
                boolean busy = !tfQr1.getText().isEmpty() || !tfQr2.getText().isEmpty();
                if (busy && !fieldsBusy) fieldsBusyAt = lastInputAt;
                fieldsBusy = busy;
            }
            @Override public void insertUpdate(DocumentEvent e)  { touched(); }
            @Override public void removeUpdate(DocumentEvent e)  { touched(); }
            @Override public void changedUpdate(DocumentEvent e) { touched(); }
        };
        tfQr1.getDocument().addDocumentListener(typing);
        tfQr2.getDocument().addDocumentListener(typing);

        tfQr1.addActionListener(e -> {
            if (!tfQr1.getText().trim().isEmpty()) tfQr2.requestFocusInWindow();
        });
        tfQr2.addActionListener(e -> accept());
        tfQr2.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                // a scanner whose suffix is TAB never fires ENTER: the pair is
                // complete and focus jumps INSIDE our own panel. Anything else
                // (alt-tab to the browser, a popup, the gear) is not a scan and
                // must not queue a pair behind the operator's back.
                if (e.isTemporary()) return;
                Component next = e.getOppositeComponent();
                if (next == null || !SwingUtilities.isDescendingFrom(next, ScanModePanel.this)) return;
                if (!tfQr2.getText().trim().isEmpty()) accept();
            }
        });

        btnClearFields.addActionListener(e -> clearFields());
        btnSwap.addActionListener(e -> swapFields());
        btnFire.addActionListener(e -> {
            if (paused) {                       // one lever: whatever stopped us, resume
                paused = false;
                refreshBanner();
                updateFireButton();
            } else if (batchMode) {
                if (!queue.isEmpty()) startBlock();
            } else {
                togglePause();
            }
        });
        btnVerify.addActionListener(e -> verifySession(true));
        btnNewSession.addActionListener(e -> newSession());

        // F2 while the focus is anywhere in this card — the scanner never sends it
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "swapQr");
        getActionMap().put("swapQr", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { swapFields(); }
        });

        refreshBanner();
        updateFireButton();
        return card;
    }

    private void clearFields() {
        tfQr1.setText("");
        tfQr2.setText("");
        refreshBanner();
        tfQr1.requestFocusInWindow();
    }

    /** The correction for the classic mis-scan: the lot went into QR 1. */
    private void swapFields() {
        String a = tfQr1.getText();
        String b = tfQr2.getText();
        tfQr1.setText(b);
        tfQr2.setText(a);
        refreshBanner();
        // land where the next scan belongs: the empty field, or QR 2 to confirm
        if (tfQr1.getText().trim().isEmpty()) tfQr1.requestFocusInWindow();
        else {
            tfQr2.requestFocusInWindow();
            tfQr2.setCaretPosition(tfQr2.getText().length());
        }
    }

    // ── the released block owns the HUD ───────────────────────────────────

    /**
     * "A blocco" is the ONLY scan situation where the window may collapse to
     * the HUD: the operator pressed a lever and now waits, exactly like a range
     * run. In continuo he is still scanning, and a window that shrinks and
     * reopens under his hands every couple of seconds is worse than no HUD at
     * all — so continuo never calls this.
     */
    private void startBlock() {
        // the worker refuses to run while a half pair sits in the fields
        // (mayRun), so releasing here would collapse the window onto a block
        // that cannot move: a bar reading 0 / n forever, with the explanation
        // hidden behind it. Same rule, said out loud instead.
        if (fieldsBusy) {
            Toolkit.getDefaultToolkit().beep();
            setState(ERROR, "Coppia incompleta: completa o svuota i campi");
            return;
        }
        blockDone = 0;
        releasing = true;
        blockRunning = true;
        updateFireButton();
        ctx.jobStarted(this::stopBlock);
        ctx.jobProgress(AppTheme.PEACH, "ROBOT AL LAVORO",
                        "0 / " + queue.size(), "registrate", 0, true);
    }

    /**
     * Give the cockpit back. Called at the end of the block, but ALSO by every
     * way a block can stop early: a banner nobody can see is not a message, and
     * the operator must not have to expand the bar by hand to find out why the
     * robot went quiet.
     */
    private boolean endBlock() {
        if (!blockRunning) return false;
        blockRunning = false;
        ctx.jobFinished();
        ctx.focusHome(tfQr1);   // the cockpit is back: the caret goes home with it
        return true;
    }

    /** STOP, from the HUD. */
    private void stopBlock() {
        paused = true;
        releasing = false;
        endBlock();
        refreshBanner();
        updateFireButton();
    }

    // ── accept pairs: never lost, never blocking ──────────────────────────

    private void accept() {
        String q1 = tfQr1.getText().trim();
        String q2 = tfQr2.getText().trim();
        if (q1.isEmpty() || q2.isEmpty()) return;

        // a missing Robot is NOT a reason to refuse a scan: the queue is the
        // safe place, and the burst holds the pair with a message until the
        // machine can type again. Refusing here would lose the piece instead.

        // 1. the two codes in the right holes
        boolean corrected = false;
        int advice = ScanGuard.inspect(q1, q2,
            cfg.get(SettingsManager.SCAN_QR1_PATTERN, ""),
            cfg.get(SettingsManager.SCAN_QR2_PATTERN, ""));
        if (advice == ScanGuard.INVERTED) {
            if (!cfg.getBool(SettingsManager.SCAN_AUTOSWAP, false)) {
                Toolkit.getDefaultToolkit().beep();
                setState(WARN, "QR invertiti: premi Scambia (F2), poi INVIO");
                return;
            }
            String t = q1; q1 = q2; q2 = t;
            corrected = true;
        } else if (advice == ScanGuard.MISMATCH) {
            Toolkit.getDefaultToolkit().beep();
            setState(ERROR, "Formato non riconosciuto: controlla i due QR");
            return;
        }

        // 2. the same label twice is a scanner that fired twice
        if (cfg.getBool(SettingsManager.SCAN_DUP_GUARD, true) && alreadyKnown(q1)) {
            Toolkit.getDefaultToolkit().beep();
            setState(ERROR, "Etichetta gia' in sessione: " + q1);
            return;
        }

        // 3. the burst runs on a worker: check the window BEFORE the pair is
        // queued, while we are still on the EDT and can move the window if we must
        int cx = cfg.getInt(SettingsManager.SCAN_COORD_X, -1);
        int cy = cfg.getInt(SettingsManager.SCAN_COORD_Y, -1);
        if (cx >= 0 && cy >= 0) {
            String blocked = ctx.blockingCollision(targets(cx, cy));
            if (blocked != null) { setState(ERROR, blocked); return; }
        }

        if (!sessionStarted) { results.beginSession(); sessionStarted = true; }
        int row = results.addQueuedPair(q1, q2);
        queue.addLast(new Pair(q1, q2, row));
        tfQr1.setText("");
        tfQr2.setText("");
        tfQr1.requestFocusInWindow();
        results.sessionCounters(sent, queue.size());
        refreshBanner();
        updateFireButton();
        if (corrected) setState(WARN, "Coppia invertita, corretta da sola: " + q1);
    }

    /** Same label already sent in this session, or already waiting in the queue. */
    private boolean alreadyKnown(String code) {
        return alreadyKnown(code, null);
    }

    private boolean alreadyKnown(String code, Pair except) {
        if (session.containsKey(code)) return true;
        for (Pair p : queue) {
            if (p != except && p.q1.equals(code)) return true;
        }
        return false;
    }

    private Pair queuedAt(int row) {
        for (Pair p : queue) if (p.row == row) return p;
        return null;
    }

    private boolean canEditQueued(int row) {
        return batchMode && !releasing && !blockRunning && !burstBusy
            && !verifying && queuedAt(row) != null;
    }

    private void editQueued(int row) {
        Pair pair = queuedAt(row);
        if (pair == null || !canEditQueued(row)) return;

        JTextField q1Field = new JTextField(pair.q1, 34);
        JTextField q2Field = new JTextField(pair.q2, 34);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        form.add(new JLabel("QR 1"));
        form.add(q1Field);
        form.add(new JLabel("QR 2"));
        form.add(q2Field);

        for (;;) {
            int choice = JOptionPane.showConfirmDialog(
                this, form, "Modifica coppia in coda",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
            if (!canEditQueued(row)) {
                Toolkit.getDefaultToolkit().beep();
                setState(ERROR, "La coda e' gia' partita: modifica annullata");
                return;
            }

            String q1 = q1Field.getText().trim();
            String q2 = q2Field.getText().trim();
            if (q1.isEmpty() || q2.isEmpty()) {
                JOptionPane.showMessageDialog(this, "QR 1 e QR 2 sono obbligatori.",
                    "Coppia incompleta", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            int advice = ScanGuard.inspect(q1, q2,
                cfg.get(SettingsManager.SCAN_QR1_PATTERN, ""),
                cfg.get(SettingsManager.SCAN_QR2_PATTERN, ""));
            if (advice == ScanGuard.INVERTED) {
                if (cfg.getBool(SettingsManager.SCAN_AUTOSWAP, false)) {
                    String t = q1; q1 = q2; q2 = t;
                    q1Field.setText(q1);
                    q2Field.setText(q2);
                } else {
                    JOptionPane.showMessageDialog(this,
                        "I due QR sembrano invertiti. Correggili o usa Scambia.",
                        "QR invertiti", JOptionPane.WARNING_MESSAGE);
                    continue;
                }
            } else if (advice == ScanGuard.MISMATCH) {
                JOptionPane.showMessageDialog(this,
                    "Il formato dei QR non corrisponde alle regole configurate.",
                    "Formato non riconosciuto", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            if (cfg.getBool(SettingsManager.SCAN_DUP_GUARD, true)
                    && alreadyKnown(q1, pair)) {
                JOptionPane.showMessageDialog(this,
                    "Questa etichetta e' gia' presente nella sessione o nella coda.",
                    "Duplicato", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            pair.q1 = q1;
            pair.q2 = q2;
            results.updateQueuedPair(row, q1, q2);
            results.sessionCounters(sent, queue.size());
            refreshBanner();
            updateFireButton();
            return;
        }
    }

    private void deleteQueued(int row) {
        Pair pair = queuedAt(row);
        if (pair == null || !canEditQueued(row)) return;
        int choice = JOptionPane.showConfirmDialog(
            this,
            "Rimuovere dalla coda?\n\nQR 1: " + pair.q1 + "\nQR 2: " + pair.q2,
            "Elimina coppia",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION || !canEditQueued(row)) return;

        if (!queue.remove(pair)) return;
        if (!results.removeQueuedPair(row)) {
            queue.addLast(pair);
            return;
        }
        for (Pair p : queue) if (p.row > row) p.row--;
        results.sessionCounters(sent, queue.size());
        refreshBanner();
        updateFireButton();
    }

    private static Map<String, Point> targets(int cx, int cy) {
        Map<String, Point> t = new LinkedHashMap<>();
        t.put("Casella 1", new Point(cx, cy));
        return t;
    }

    // ── worker: drains the queue one burst at a time ──────────────────────

    private void drainLoop() {
        for (;;) {
            try {
                // never take before we may run: a held pair made the queue lie
                if (!mayRun()) { Thread.sleep(50); continue; }
                Pair pair = queue.pollFirst(50, TimeUnit.MILLISECONDS);
                if (pair == null) continue;
                if (!mayRun()) { queue.addFirst(pair); continue; }   // flipped mid-poll
                burst(pair);
                if (batchMode && queue.isEmpty()) {
                    releasing = false;
                    SwingUtilities.invokeLater(() -> {
                        refreshBanner();
                        updateFireButton();
                        maybeAutoVerify();          // may keep the HUD a little longer
                        if (!verifying) endBlock();
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // run only when: not paused, no verification running, scanner idle,
    // fields empty — and, in batch mode, only after the lever was pressed
    private boolean mayRun() {
        boolean quiet = System.currentTimeMillis() - lastInputAt > QUIET_MS;
        boolean held  = batchMode && !releasing;
        return !paused && !verifying && !fieldsBusy && !held && quiet;
    }

    private void burst(Pair pair) throws InterruptedException {
        int cx = cfg.getInt(SettingsManager.SCAN_COORD_X, -1);
        int cy = cfg.getInt(SettingsManager.SCAN_COORD_Y, -1);
        if (cx < 0 || cy < 0) {
            hold(pair, "Manca la coordinata nelle Impostazioni — coppia rimessa in coda");
            return;
        }
        if (!robot.isAvailable()) {
            hold(pair, "Robot non disponibile — coppia rimessa in coda");
            return;
        }

        final int tFocus = cfg.getInt(SettingsManager.SCAN_FOCUS, 300);
        final int tKey   = cfg.getInt(SettingsManager.SCAN_KEY,   70);
        final int tEnter = cfg.getInt(SettingsManager.SCAN_ENTER, 150);
        final boolean guarded = cfg.getBool(SettingsManager.SCAN_FAILSAFE, true);
        final Point target = new Point(cx, cy);

        // the window may have moved since the pair was scanned: ask again, and
        // take the return point in the same hop onto the EDT
        final String[] blocked = { null };
        try {
            SwingUtilities.invokeAndWait(() -> {
                appPoint = tfQr1.isShowing() ? tfQr1.getLocationOnScreen() : null;
                blocked[0] = ctx.blockingCollision(targets(target.x, target.y));
            });
        } catch (InterruptedException ie) {
            queue.addFirst(pair);
            throw ie;
        } catch (Exception ignored) {
            // an EDT that cannot answer is not a reason to type blind
            hold(pair, "Finestra non interrogabile — coppia rimessa in coda");
            return;
        }
        if (blocked[0] != null) { hold(pair, blocked[0]); return; }

        burstBusy = true;
        SwingUtilities.invokeLater(() -> setState(BUSY, null));
        try {
            robot.doubleClick(cx, cy);
            robot.sleep(tFocus);
            if (guarded && robot.isMouseMoved(target)) { failSafe(pair); return; }

            robot.pasteText(pair.q1); robot.sleep(tKey);
            if (guarded && robot.isMouseMoved(target)) { failSafe(pair); return; }

            robot.pressTab();         robot.sleep(tKey);
            if (guarded && robot.isMouseMoved(target)) { failSafe(pair); return; }

            robot.pasteText(pair.q2); robot.sleep(tKey);
            if (guarded && robot.isMouseMoved(target)) { failSafe(pair); return; }

            robot.pressTab();         robot.sleep(tKey);
            if (guarded && robot.isMouseMoved(target)) { failSafe(pair); return; }

            // point of no return: after ENTER the pair IS registered, so it can
            // never go back in the queue — a re-send would double-register it
            robot.pressEnter();       robot.sleep(tEnter);

            final boolean grabbed = guarded && robot.isMouseMoved(target);
            if (!grabbed) {
                Point back = appPoint;   // return focus to the app at once
                if (back != null) robot.click(back.x + 20, back.y + 10);
            }
            SwingUtilities.invokeLater(() -> {
                countSent(pair);
                if (grabbed) haltAfterSend();
                else maybeAutoVerify();
            });
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Exception ex) {
            paused = true;
            releasing = false;
            SwingUtilities.invokeLater(() -> {
                endBlock();
                Toolkit.getDefaultToolkit().beep();
                results.markNotSent(pair.row, "Coppia non inviata: " + pair.q1);
                setState(ERROR, "Coppia NON inviata: " + pair.q1 + " — controlla e rispara");
                results.sessionCounters(sent, queue.size());
                stats.set(sent, queue.size());
                updateFireButton();
            });
        } finally {
            burstBusy = false;
        }
    }

    /** Nothing was typed: the pair goes back to the head and the line stops. */
    private void hold(Pair pair, String why) {
        queue.addFirst(pair);
        paused = true;
        releasing = false;
        SwingUtilities.invokeLater(() -> {
            endBlock();
            setState(ERROR, why);
            updateFireButton();
        });
    }

    /** The operator took the mouse back mid-burst, before the save. */
    private void failSafe(Pair pair) {
        queue.addFirst(pair);
        paused = true;
        releasing = false;
        Toolkit.getDefaultToolkit().beep();
        SwingUtilities.invokeLater(() -> {
            endBlock();
            setState(ERROR, "Mouse mosso: robot fermo. Controlla il form, poi RIPRENDI");
            results.showFailSafe("Coppia " + pair.q1
                + " rimessa in coda: il form sul sito puo' essere incompleto");
            results.sessionCounters(sent, queue.size());
            stats.set(sent, queue.size());
            updateFireButton();
        });
    }

    /** Mouse grabbed AFTER the save: the pair counts, the robot still stops. */
    private void haltAfterSend() {
        paused = true;
        releasing = false;
        endBlock();
        Toolkit.getDefaultToolkit().beep();
        setState(ERROR, "Mouse mosso dopo il salvataggio: coppia inviata, robot in pausa");
        updateFireButton();
    }

    // ── outcomes (EDT) ─────────────────────────────────────────────────────

    private void countSent(Pair pair) {
        sent++;
        sinceVerify++;
        session.put(pair.q1, pair.q2);
        results.markSent(pair.row);
        results.sessionCounters(sent, queue.size());
        refreshBanner();
        updateFireButton();
        if (blockRunning) {
            // the total is recomputed, not remembered: pairs scanned while the
            // block runs join it, and a fixed denominator would read 7 / 5
            blockDone++;
            int total = blockDone + queue.size();
            ctx.jobProgress(AppTheme.PEACH, "ROBOT AL LAVORO",
                            blockDone + " / " + total, "registrate",
                            total > 0 ? blockDone * 100 / total : 100, true);
        } else {
            ctx.jobProgress(AppTheme.GREEN, "SPARA PURE", String.valueOf(sent),
                            "inviate · coda " + queue.size(), -1, false);
        }

        int every = cfg.getInt(SettingsManager.SCAN_VERIFY_EVERY, 10);
        if (cfg.getBool(SettingsManager.SCAN_VERIFY_AUTO, true)
            && every > 0 && sinceVerify >= every) verifyDue = true;
    }

    /**
     * The automatic check is OWED at the Nth piece and TAKEN at the first lull:
     * queue empty, nothing half-scanned, robot still. Firing it on the count
     * alone cut a block in half — the robot walked off to click Export with
     * pairs still waiting, and the operator watched a full queue do nothing.
     * The debt is never dropped: the heartbeat retries until the lull arrives.
     */
    private void maybeAutoVerify() {
        if (!verifyDue || verifying || burstBusy || paused) return;
        if (!queue.isEmpty() || fieldsBusy) return;
        if (System.currentTimeMillis() - lastInputAt <= QUIET_MS) return;
        verifySession(false);
    }

    /** manual = the button: short wait + fallback to the latest export;
     *  auto = full timeout. */
    private void verifySession(boolean manual) {
        if (verifying) return;
        if (session.isEmpty()) { setState(ERROR, "Sessione vuota: spara almeno una coppia"); return; }

        int ex = cfg.getInt(SettingsManager.REG_EXPORT_COORD_X, -1);
        int ey = cfg.getInt(SettingsManager.REG_EXPORT_COORD_Y, -1);
        if (ex < 0 || ey < 0) { setState(ERROR, "Memorizza «Export CSV» nelle Impostazioni (tab Verifica)"); return; }

        final Map<String, String> snapshot = new LinkedHashMap<>(session);
        File dir = new File(cfg.get(SettingsManager.REG_DOWNLOAD_DIR, defaultDownloads()));
        int timeoutMs = manual ? 8000 : cfg.getInt(SettingsManager.REG_EXPORT_TIMEOUT_S, 30) * 1000;
        int retries   = manual ? 0 : cfg.getInt(SettingsManager.REG_VERIFY_RETRIES, 2);
        int retryMs   = cfg.getInt(SettingsManager.REG_VERIFY_RETRY_S, 2) * 1000;

        final DownloadWatcher watcher = new DownloadWatcher(
            dir.toPath(),
            cfg.get(SettingsManager.REG_EXPORT_PREFIX, "demo-export"),
            timeoutMs,
            cfg.getInt(SettingsManager.REG_EXPORT_POLL_MS, 500),
            cfg.getInt(SettingsManager.REG_EXPORT_STABLE_MS, 800));

        final Point ec = new Point(ex, ey);
        final Runnable exportClick = () -> {
            try {
                RobotEngine.getInstance().click(ec.x, ec.y);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        };
        final RegistrationVerifier verifier = new RegistrationVerifier();
        final Function<List<String>, VerificationResult> checker =
            lines -> verifier.verify(lines, snapshot);

        verifyDue = false;
        sinceVerify = 0;
        verifying = true;
        setState(VERIFY, "Verifica sessione in corso...");
        results.setActions(() -> verifySession(true), this::cancelVerify);
        results.setLotFixer((row, code, newLot) -> {
            queue.addLast(new Pair(code, newLot, row));
            refreshBanner();
            updateFireButton();
            if (batchMode && !releasing) {
                setState(READY, "Correzione in coda — premi REGISTRA TUTTO");
            }
        });
        results.setSessionTotal(snapshot.size());
        results.showVerifying();

        final VerificationTask.Listener listener = new VerificationTask.Listener() {
            @Override public void onStatus(String m) {
                setState(VERIFY, m);
                if (blockRunning) {
                    ctx.jobProgress(AppTheme.BLUE, "VERIFICA", "-", "in corso", -1, false);
                }
            }
            @Override public void onOutcome(VerificationResult r, Path f, int attempts, boolean fresh) {
                verifying = false;
                String fn = f.getFileName().toString();
                results.showOutcome(r, fn, attempts, fresh);
                results.offerReport(r);
                stats.setTone(r.isClean() ? AppTheme.GREEN : AppTheme.RED);
                refreshBanner();
                if (!log.append(VerificationLog.formatEntry(r, fn, attempts,
                        snapshot.size(), "dual-scan", fresh))) {
                    setState(ERROR, "Log non scrivibile: " + log.getFile());
                }
                if (blockRunning) {
                    ctx.jobProgress(r.isClean() ? AppTheme.GREEN : AppTheme.RED,
                                    r.isClean() ? "TUTTO OK" : r.totalProblems() + " PROBLEMI",
                                    r.getMatched() + " / " + snapshot.size(),
                                    "verificate", 100, false);
                }
                if (!endBlock() && queue.isEmpty()) ctx.focusHome(tfQr1);
            }
            @Override public void onFailure(String reason) {
                verifying = false;
                results.showFailure(reason);
                setState(ERROR, "Verifica fallita — riprova col tasto Verifica");
                log.append("ERRORE · dual-scan · " + reason);
                if (!endBlock() && queue.isEmpty()) ctx.focusHome(tfQr1);
            }
            @Override public void onCancelled() {
                verifying = false;
                results.showCancelled();
                refreshBanner();
                log.append("ANNULLATA · dual-scan");
                if (!endBlock() && queue.isEmpty()) ctx.focusHome(tfQr1);
            }
        };

        final int retriesF = retries, retryMsF = retryMs;
        final boolean manualF = manual;
        // start only after any burst finishes: never two robots on one mouse
        Thread starter = new Thread(() -> {
            try {
                while (burstBusy) Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            verifyTask = new VerificationTask(exportClick, watcher, checker,
                retriesF, retryMsF, manualF, listener);
            verifyTask.run();
        }, "dualscan-verify");
        starter.start();
    }

    private void cancelVerify() {
        if (verifyTask != null) verifyTask.cancel();
    }

    private void togglePause() {
        paused = !paused;
        if (paused) endBlock();
        refreshBanner();
        updateFireButton();
    }

    // the fire button changes job with the mode — and a stop always resumes here
    private void updateFireButton() {
        if (paused) {
            btnFire.setText("RIPRENDI");
            btnFire.setIcon(Icons.play(AppTheme.ICON, AppTheme.ON_ACCENT));
            btnFire.setEnabled(true);
        } else if (batchMode) {
            btnFire.setText(releasing ? "REGISTRO..." : "REGISTRA TUTTO (" + queue.size() + ")");
            btnFire.setIcon(Icons.play(AppTheme.ICON, AppTheme.ON_ACCENT));
            btnFire.setEnabled(!releasing && !queue.isEmpty());
        } else {
            btnFire.setText("PAUSA");
            btnFire.setIcon(Icons.pause(AppTheme.ICON, AppTheme.ON_ACCENT));
            btnFire.setEnabled(true);
        }
        results.queueActionsChanged();
    }

    private void newSession() {
        // a burst mid-flight would land in the session we just emptied
        if (burstBusy || verifying) {
            setState(ERROR, "Attendi la fine del ciclo in corso");
            return;
        }
        endBlock();
        queue.clear();
        session.clear();
        sent = 0;
        blockDone = 0;
        sinceVerify = 0;
        verifyDue = false;
        releasing = false;
        paused = false;
        sessionStarted = true;
        results.beginSession();
        clearFields();
        refreshBanner();
        updateFireButton();
        tfQr1.requestFocusInWindow();
    }

    // ── banner ──────────────────────────────────────────────────────────────

    private void setState(int state, String text) {
        // a warning or an error stays on screen until something real replaces it
        sticky = state == ERROR || state == WARN;
        switch (state) {
            case BUSY:
                banner.setBackground(AppTheme.PEACH);
                banner.setForeground(AppTheme.ON_ACCENT);
                banner.setText("ROBOT AL LAVORO — non sparare");
                break;
            case VERIFY:
                banner.setBackground(AppTheme.BLUE);
                banner.setForeground(AppTheme.ON_ACCENT);
                banner.setText(text != null ? text : "VERIFICA IN CORSO...");
                break;
            case PAUSED:
                banner.setBackground(AppTheme.SURFACE2);
                banner.setForeground(AppTheme.TEXT);
                banner.setText("IN PAUSA");
                break;
            case ERROR:
                banner.setBackground(AppTheme.RED);
                banner.setForeground(AppTheme.ON_ACCENT);
                banner.setText(text != null ? text : "Errore");
                break;
            case WARN:
                banner.setBackground(AppTheme.YELLOW);
                banner.setForeground(AppTheme.ON_ACCENT);
                banner.setText(text != null ? text : "Controlla la coppia");
                break;
            case STUCK:
                banner.setBackground(AppTheme.SURFACE2);
                banner.setForeground(AppTheme.TEXT);
                banner.setText("COPPIA INCOMPLETA — la coda aspetta");
                break;
            case COLLECT:
                banner.setBackground(AppTheme.SURFACE2);
                banner.setForeground(AppTheme.TEXT);
                banner.setText("RACCOLTA — premi REGISTRA");
                break;
            default:
                banner.setBackground(AppTheme.GREEN);
                banner.setForeground(AppTheme.ON_ACCENT);
                banner.setText("SPARA PURE");
        }
    }

    private void refreshCounter() {
        stats.set(sent, queue.size());
    }

    /**
     * A block can stop being able to drain after it started: one QR scanned
     * mid-block leaves a half pair in the fields and the queue waits for it.
     * The bar has no room to explain that, so the cockpit comes back and the
     * banner does the talking. The block is NOT cancelled — it resumes by
     * itself as soon as the pair is completed or the fields are cleared.
     */
    private void watchdogBlock() {
        if (!blockRunning || burstBusy || verifying || paused) return;
        if (!fieldsBusy) return;
        if (System.currentTimeMillis() - fieldsBusyAt < STUCK_MS) return;
        endBlock();
    }

    /** The heartbeat: it may only ADD information, never erase a message. */
    private void nudgeBanner() {
        if (burstBusy || verifying || sticky) return;
        refreshBanner();
    }

    private void refreshBanner() {
        sticky = false;
        refreshCounter();
        if (burstBusy || verifying) return;
        boolean halfScanned = fieldsBusy && !queue.isEmpty()
            && System.currentTimeMillis() - fieldsBusyAt > STUCK_MS;
        if (paused)                       setState(PAUSED, null);
        else if (halfScanned)             setState(STUCK, null);
        else if (batchMode && !releasing) setState(COLLECT, null);
        else                              setState(READY, null);
    }

    private static String defaultDownloads() {
        return new File(System.getProperty("user.home"), "Downloads").getAbsolutePath();
    }
}
