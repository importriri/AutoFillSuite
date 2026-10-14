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
    private static final String SCAN = "Scansione";   // the robot gate's name for this mode
    private static final int STUCK_MS = 5000;   // half-scanned fields holding the queue

    // banner states
    private static final int READY = 0, BUSY = 1, VERIFY = 2, PAUSED = 3,
                             ERROR = 4, COLLECT = 5, WARN = 6, STUCK = 7;

    // a scanned pair: the two QRs and its table row
    private static final class Pair {
        String q1, q2;
        int row;
        long id;    // the pair's identity in the queue journal
        Pair(String q1, String q2, int row, long id) { this.q1 = q1; this.q2 = q2; this.row = row; this.id = id; }
    }

    private final SettingsManager cfg   = SettingsManager.getInstance();
    private final RobotEngine     robot = RobotEngine.getInstance();
    PairBurst.Hands hands = robot;   // the tests pass fake hands
    private final VerificationLog log   = new VerificationLog(
        app.config.AppFiles.verificationLog().toPath());

    private final LinkedBlockingDeque<Pair> queue = new LinkedBlockingDeque<>();
    /** The queue on disk: every change is forced there first. null = cannot write. */
    private ScanQueueStore store;
    private String storeError = "";
    /** What the last run left in the queue journal, offered once at start. */
    private List<ScanQueueStore.Item> foundPending = new java.util.ArrayList<>();
    private List<ScanQueueStore.Item> foundUnsure = new java.util.ArrayList<>();
    private JPanel recoverBox;
    private JLabel recoverText;
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
    // pairs restored "DA VERIFICARE": IN_INVIO on disk until a verification covers them
    private final List<Long> toSettle = new java.util.ArrayList<>();
    private boolean sticky = false;               // a message that must not be wiped
    private volatile boolean blockRunning = false;   // a released block owns the HUD
    private int blockDone = 0;
    private int sent = 0;

    private VerificationTask verifyTask = null;
    /** Answers the "already registered today" question; a test answers it. */
    java.util.function.Predicate<String> confirmer = this::askConfirm;
    // "Nuova sessione" with work that would be thrown away (the tests answer)
    java.util.function.Predicate<String> sessionConfirmer =
        m -> SafeConfirm.ask(this, m, "Nuova sessione");
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
        openStore();
        add(buildCard(), BorderLayout.CENTER);
        offerRecovery();

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

        // the queue a closed app left behind: offered once, at start
        y++; g.gridy = y;
        recoverBox = new JPanel(new BorderLayout(0, 3));
        recoverBox.setOpaque(false);
        recoverText = new JLabel(" ");
        recoverText.setFont(AppTheme.F_BOLD);
        recoverText.setForeground(AppTheme.PEACH);
        JPanel rb = new JPanel(new GridLayout(1, 2, 6, 0));
        rb.setOpaque(false);
        JButton restore = AppTheme.secondary("Ripristina", Icons.retry(AppTheme.ICON, AppTheme.SUBTEXT));
        JButton discard = AppTheme.secondary("Scarta", Icons.cross(AppTheme.ICON, AppTheme.SUBTEXT));
        restore.addActionListener(e -> restoreQueue());
        discard.addActionListener(e -> discardQueue());
        rb.add(restore);
        rb.add(discard);
        recoverBox.add(recoverText, BorderLayout.NORTH);
        recoverBox.add(rb, BorderLayout.SOUTH);
        recoverBox.setVisible(false);
        card.add(recoverBox, g);
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
        // the scanner's TAB after QR 2 submits like ENTER: this field, by name
        ScanTabSupport.markSubmitOnTab(tfQr2);
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
            // another mode's job: the worker would slip its bursts between its cycles
            String other = paused || batchMode ? ctx.busyElsewhere(this) : null;
            if (other != null) { setState(ERROR, "Robot occupato: " + other); return; }
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
            app.core.Sounds.error();
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

        // another mode owns the robot and the results table right now: a pair
        // queued here would wait behind its job and point into its rows
        String other = ctx.busyElsewhere(this);
        if (other != null) {
            app.core.Sounds.error();
            setState(ERROR, "Coppia NON accodata: " + other);
            return;
        }

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
                app.core.Sounds.error();
                setState(WARN, "QR invertiti: premi Scambia (F2), poi INVIO");
                return;
            }
            String t = q1; q1 = q2; q2 = t;
            corrected = true;
        } else if (advice == ScanGuard.MISMATCH) {
            app.core.Sounds.error();
            setState(ERROR, "Formato non riconosciuto: controlla i due QR");
            return;
        }

        // 2. the same label twice is a scanner that fired twice
        if (cfg.getBool(SettingsManager.SCAN_DUP_GUARD, true) && alreadyKnown(q1)) {
            app.core.Sounds.error();
            setState(ERROR, "Etichetta gia' in sessione: " + q1);
            return;
        }

        // 2b. the same label registered EARLIER TODAY (another session, another
        // start): the portal keeps the last lot, so another lot is a correction
        String sameLotNote = null;
        String[] before = results.registeredToday().get(q1);
        if (before != null) {
            if (before[0].equals(q2)) {
                sameLotNote = "Già registrata oggi alle " + before[1] + " con lo stesso lotto: reinvio";
            } else if (!confirmer.test(q1 + " è già stata registrata oggi alle " + before[1]
                    + " con il lotto " + before[0] + ".\n\nÈ una correzione: sul portale vincerà "
                    + "questo lotto (" + q2 + ").\n\nRegistrare comunque?")) {
                setState(WARN, "Correzione non accodata: " + q1);
                return;
            }
        }

        // 3. the burst runs on a worker: check the window BEFORE the pair is
        // queued, while we are still on the EDT and can move the window if we must
        int cx = cfg.getInt(SettingsManager.SCAN_COORD_X, -1);
        int cy = cfg.getInt(SettingsManager.SCAN_COORD_Y, -1);
        if (cx >= 0 && cy >= 0) {
            String blocked = ctx.blockingCollision(targets(cx, cy));
            if (blocked != null) { setState(ERROR, blocked); return; }
        }

        // write-ahead: the pair is on disk before it is in the queue or the table
        ScanQueueStore.Item item = journalEnqueue(q1, q2);
        if (item == null) {
            app.core.Sounds.error();
            setState(ERROR, "Coda non salvabile su disco: coppia NON accodata — " + storeError);
            return;
        }
        // the table may hold another mode's run since: scan rows get a section of their own
        if (!sessionStarted || !results.isScanSession()) { results.beginSession(); sessionStarted = true; }
        int row = results.addQueuedPair(q1, q2);
        queue.addLast(new Pair(q1, q2, row, item.id));
        tfQr1.setText("");
        tfQr2.setText("");
        tfQr1.requestFocusInWindow();
        results.sessionCounters(sent, queue.size());
        refreshBanner();
        updateFireButton();
        if (corrected) setState(WARN, "Coppia invertita, corretta da sola: " + q1);
        else if (sameLotNote != null) setState(WARN, sameLotNote);
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
                app.core.Sounds.error();
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

            try {
                if (store == null) throw new java.io.IOException(storeError);
                store.edit(pair.id, q1, q2);
            } catch (java.io.IOException ex) {
                setState(ERROR, "Disco non scrive: modifica annullata — " + ex.getMessage());
                return;
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
        boolean yes = SafeConfirm.ask(this,
            "Rimuovere dalla coda?\n\nQR 1: " + pair.q1 + "\nQR 2: " + pair.q2,
            "Elimina coppia");
        if (!yes || !canEditQueued(row)) return;

        try {
            if (store == null) throw new java.io.IOException(storeError);
            store.remove(pair.id);
        } catch (java.io.IOException ex) {
            setState(ERROR, "Disco non scrive: coppia lasciata in coda — " + ex.getMessage());
            return;
        }
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
                if (!mayRun() || queue.isEmpty()) { Thread.sleep(20); continue; }
                // the robot first, the pair second: from the moment a pair
                // leaves the queue nobody else may click (a verification owed
                // by the previous pair waits for the gate, never for a flag).
                // The gate is taken only with a pair waiting: an idle worker
                // holding it would keep every verification out
                RobotGate.Lease lease = RobotGate.tryAcquire(SCAN);
                if (lease == null) { Thread.sleep(50); continue; }
                try {
                    if (!mayRun()) continue;   // a verification was asked for meanwhile
                    burstBusy = true;
                    Pair pair = queue.pollFirst();
                    if (pair == null) continue;
                    if (!mayRun()) { queue.addFirst(pair); continue; }   // flipped mid-poll
                    burst(pair);
                } finally {
                    burstBusy = false;
                    lease.release();
                }
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

        SwingUtilities.invokeLater(() -> setState(BUSY, null));
        Point back = appPoint;   // return focus to the app at once
        final long id = pair.id;
        PairBurst.Outcome outcome = PairBurst.run(hands, target,
            back == null ? null : new Point(back.x + 20, back.y + 10),
            pair.q1, pair.q2, new PairBurst.Timing(tFocus, tKey, tEnter), guarded,
            () -> {
                if (store == null) throw new java.io.IOException("coda non salvabile: " + storeError);
                store.sending(id);
            },
            () -> {
                try {
                    store.sent(id);
                } catch (java.io.IOException ex) {
                    System.err.println("[queue] INVIATA not recorded: " + ex.getMessage());
                }
            });
        switch (outcome) {
            case NOT_SENT:
                failSafe(pair);
                return;
            case SENT:
            case SENT_THEN_GRABBED: {
                final boolean grabbed = outcome == PairBurst.Outcome.SENT_THEN_GRABBED;
                SwingUtilities.invokeLater(() -> {
                    countSent(pair);
                    if (grabbed) haltAfterSend();
                    else maybeAutoVerify();
                });
                return;
            }
            default:
                paused = true;
                releasing = false;
                // the operator is told to scan it again: it must not come back at restart
                try {
                    if (store != null) store.remove(pair.id);
                } catch (java.io.IOException ignored) {
                    // a pair left IN_INVIO comes back "da verificare": still safe
                }
                SwingUtilities.invokeLater(() -> {
                    endBlock();
                    app.core.Sounds.error();
                    results.markNotSent(pair.row, "Coppia non inviata: " + pair.q1);
                    setState(ERROR, "Coppia NON inviata: " + pair.q1 + " — controlla e rispara");
                    results.sessionCounters(sent, queue.size());
                    stats.set(sent, queue.size());
                    updateFireButton();
                });
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
        app.core.Sounds.error();
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
        app.core.Sounds.error();
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
        if (RobotGate.owner() != null) return;   // someone is clicking: the heartbeat retries
        if (!queue.isEmpty() || fieldsBusy) return;
        if (System.currentTimeMillis() - lastInputAt <= QUIET_MS) return;
        verifySession(false);
    }

    /** manual = the button: short wait + fallback to the latest export;
     *  auto = full timeout. */
    private void verifySession(boolean manual) {
        if (verifying) return;
        if (session.isEmpty()) { setState(ERROR, "Sessione vuota: spara almeno una coppia"); return; }
        String other = RobotGate.owner();
        if (other == null || SCAN.equals(other)) other = ctx.busyElsewhere(this);
        else other = "robot al lavoro in " + other;
        if (other != null) { setState(ERROR, "Verifica rimandata: " + other); return; }

        int ex = cfg.getInt(SettingsManager.REG_EXPORT_COORD_X, -1);
        int ey = cfg.getInt(SettingsManager.REG_EXPORT_COORD_Y, -1);
        if (ex < 0 || ey < 0) { setState(ERROR, "Memorizza «Export CSV» nelle Impostazioni (tab Verifica)"); return; }

        final Map<String, String> snapshot = new LinkedHashMap<>(session);
        File dir = app.config.AppFiles.downloadDir();
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
        results.setLotFixer((row, code, oldLot, newLot) -> {
            ScanQueueStore.Item fix = journalEnqueue(code, newLot);
            if (fix == null) {
                setState(ERROR, "Coda non salvabile su disco: correzione NON accodata — " + storeError);
                return false;
            }
            queue.addLast(new Pair(code, newLot, row, fix.id));
            refreshBanner();
            updateFireButton();
            if (batchMode && !releasing) {
                setState(READY, "Correzione in coda — premi REGISTRA TUTTO");
            }
            return true;
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
                settleRestored(snapshot);
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
            RobotGate.Lease lease;
            try {
                lease = RobotGate.acquire(SCAN);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                verifyTask = new VerificationTask(exportClick, watcher, checker,
                    retriesF, retryMsF, manualF, listener);
                verifyTask.run();
            } finally {
                lease.release();
            }
        }, "dualscan-verify");
        starter.start();
    }

    // ── the queue on disk ──────────────────────────────────────────────────

    private void openStore() {
        File dir = app.config.AppFiles.reportDir();
        try {
            store = ScanQueueStore.open(dir, java.time.Clock.systemDefaultZone());
            foundPending = new java.util.ArrayList<>(store.pending());
            // to verify: a pair cut at ENTER, and a pair sent that no check
            // ever covered (the app closed, the PC went off before the check)
            foundUnsure = new java.util.ArrayList<>(store.unsure());
            foundUnsure.addAll(store.unverified());
        } catch (java.io.IOException ex) {
            store = null;
            storeError = ex.getMessage();
        }
    }

    private ScanQueueStore.Item journalEnqueue(String q1, String q2) {
        if (store == null) return null;
        try {
            return store.enqueue(q1, q2, batchMode ? "blocco" : "continuo");
        } catch (java.io.IOException ex) {
            storeError = ex.getMessage();
            return null;
        }
    }

    /** At start: what the last run left queued, or sending when it went away. */
    private void offerRecovery() {
        int n = foundPending.size(), u = foundUnsure.size();
        if (n == 0 && u == 0) return;
        recoverText.setText("Coda ritrovata: " + n + " coppie non inviate"
                            + (u > 0 ? " · " + u + " da verificare" : ""));
        recoverText.setToolTipText(store != null && store.salvaged()
            ? "Il giornale della coda era danneggiato: queste coppie vengono dalle righe intatte"
              + " (il file danneggiato resta salvato accanto)"
            : "La coda dell'ultima sessione era ancora salvata su disco");
        recoverBox.setVisible(true);
    }

    String recoveryText() {
        return recoverBox.isVisible() ? recoverText.getText() : null;
    }

    /**
     * Ripristina: the pairs go back in their order, IN CODA, in the mode they
     * were scanned in — and nothing fires until the operator says so. They are
     * already in the journal with their ids: nothing is written, so nothing can
     * fail half way or be lost by a crash in the middle (the journal used to
     * be emptied first and the pairs queued again one by one). A pair whose
     * ENTER may have gone is listed "DA VERIFICARE", stays IN_INVIO on disk
     * until a verification covers it, and is never queued again.
     */
    void restoreQueue() {
        if (store == null) return;
        List<ScanQueueStore.Item> pending = foundPending, unsure = foundUnsure;
        boolean block = false;
        if (!sessionStarted || !results.isScanSession()) { results.beginSession(); sessionStarted = true; }
        for (ScanQueueStore.Item it : unsure) {
            results.addFileRow(it.q1, it.q2, "DA VERIFICARE");
            session.put(it.q1, it.q2);
            toSettle.add(it.id);
            verifyDue = true;
        }
        for (ScanQueueStore.Item it : pending) {
            block |= "blocco".equals(it.mode);
            int row = results.addQueuedPair(it.q1, it.q2);
            queue.addLast(new Pair(it.q1, it.q2, row, it.id));
        }
        if (block && !batchMode) modeSelector.select(1);
        if (!batchMode) paused = true;   // continuo: never fire on its own after a restart
        foundPending = new java.util.ArrayList<>();
        foundUnsure = new java.util.ArrayList<>();
        recoverBox.setVisible(false);
        results.sessionCounters(sent, queue.size());
        refreshBanner();
        updateFireButton();
        revalidate();
    }

    /**
     * A verification covered these labels: on disk, their pairs are settled
     * (sent or cut at ENTER alike), so the next start does not offer them.
     */
    private void settleRestored(Map<String, String> covered) {
        if (store == null) return;
        try {
            store.verified(covered.keySet());
        } catch (java.io.IOException ex) {
            return;   // they come back to verify at the next start, which is safe
        }
        for (java.util.Iterator<Long> it = toSettle.iterator(); it.hasNext(); ) {
            ScanQueueStore.Item item = store.item(it.next());
            if (item == null || item.state == ScanQueueStore.State.VERIFIED) it.remove();
        }
    }

    /** Scarta, after a confirmation: the old journal is renamed and kept, the queue starts empty. */
    void discardQueue() {
        if (!SafeConfirm.ask(this,
                "Scartare " + foundPending.size() + " coppie non inviate?\n"
                + "Il giornale resta salvato (rinominato con la data), ma la coda riparte vuota.",
                "Scarta la coda")) return;
        try {
            if (store != null) store.newSession();
        } catch (java.io.IOException ex) {
            setState(ERROR, "Giornale della coda non ruotato: " + ex.getMessage());
            return;
        }
        foundPending = new java.util.ArrayList<>();
        foundUnsure = new java.util.ArrayList<>();
        recoverBox.setVisible(false);
        revalidate();
    }

    private boolean askConfirm(String message) {
        return SafeConfirm.ask(this, message, "Già registrata oggi");
    }

    /** What the banner says now (the tests read it). */
    String bannerText() {
        return banner.getText();
    }

    /** Why closing now would leave something behind, or null. */
    String closeReason() {
        String busy = busyReason();
        if (busy != null) return busy;
        int unchecked = sinceVerify + toSettle.size();
        if (unchecked > 0) {
            return unchecked + " coppie inviate non ancora verificate:"
                 + " alla riapertura le ritrovi da verificare.";
        }
        return null;
    }

    /** Why closing now would cut something short, or null. */
    String busyReason() {
        if (burstBusy) return "Il robot sta inviando una coppia.";
        if (verifying) return "C'è una verifica in corso.";
        if (!queue.isEmpty()) {
            return queue.size() + " coppie in coda non ancora inviate: restano salvate"
                 + " e le ritrovi alla riapertura.";
        }
        return null;
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
        // one click next to Verifica must not throw away a queue or an
        // unchecked trail: say what goes, and take No for an answer
        if (!queue.isEmpty() || sinceVerify > 0 || !toSettle.isEmpty()) {
            StringBuilder m = new StringBuilder("Iniziare una nuova sessione?\n");
            if (!queue.isEmpty()) {
                m.append("\n").append(queue.size()).append(" coppie in coda NON inviate verranno tolte")
                 .append(" (il giornale resta salvato, rinominato con la data).");
            }
            if (sinceVerify > 0 || !toSettle.isEmpty()) {
                m.append("\n").append(sinceVerify + toSettle.size())
                 .append(" coppie inviate non sono ancora state verificate.");
            }
            if (!sessionConfirmer.test(m.toString())) return;
        }
        endBlock();
        try {
            if (store != null) store.newSession();   // the old journal is renamed, never deleted
        } catch (java.io.IOException ex) {
            setState(ERROR, "Giornale della coda non ruotato: " + ex.getMessage());
            return;
        }
        queue.clear();
        session.clear();
        toSettle.clear();
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

}
