package app.ui;

import app.config.SettingsManager;
import app.core.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
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
 * QUEUE: a scanned
 * pair goes into the queue and the fields clear at once, so the operator works
 * at their own pace and nothing is lost. A worker drains the queue one short
 * burst at a time (~0.8s) and only starts when the scanner has been quiet for
 * a moment — never mid-scan.
 *
 * No per-item clipboard check: the CSV is the truth, like in Register. Every N
 * items the session is verified against the export; the 🔎 does it on demand.
 * The Export CSV coordinate is shared with the Register tab.
 *
 * Two modes:
 *  - Continuo (default): each pair is registered as it comes
 *  - A blocco: pairs pile up, "REGISTRA TUTTO" fires the whole queue at once
 */
public class ScanModePanel extends JPanel {

    private static final int QUIET_MS = 200;   // scanner idle for at least this

    // banner states
    private static final int READY = 0, BUSY = 1, VERIFY = 2, PAUSED = 3, ERROR = 4, COLLECT = 5;

    // a scanned pair: the two QRs and its table row
    private static final class Pair {
        final String q1, q2;
        final int row;
        Pair(String q1, String q2, int row) { this.q1 = q1; this.q2 = q2; this.row = row; }
    }

    private final SettingsManager cfg   = SettingsManager.getInstance();
    private final RobotEngine     robot = RobotEngine.getInstance();
    private final VerificationLog log   = new VerificationLog(
        new File(System.getProperty("user.home"), "AutoFillSuite_verifiche.txt").toPath());

    private final LinkedBlockingDeque<Pair> queue = new LinkedBlockingDeque<>();
    private final Map<String, String> session = new LinkedHashMap<>();   // EDT only

    private volatile boolean paused    = false;
    private volatile boolean batchMode = false;   // "a blocco": collect, then ▶
    private volatile boolean releasing = false;
    private volatile boolean burstBusy = false;
    private volatile boolean verifying = false;
    private volatile boolean fieldsBusy = false;
    private volatile long lastInputAt  = 0;
    private volatile Point appPoint    = null;
    private boolean sessionStarted = false;
    private int sent = 0;

    private VerificationTask verifyTask = null;
    private final ResultsPanel results;
    private final RunContext ctx;

    private JTextField tfQr1, tfQr2;
    private JLabel banner;
    private JButton btnFire, btnVerify, btnNewSession, btnClearFields;
    private Segmented modeSelector;
    private StatPair stats;

    public ScanModePanel(ResultsPanel results, RunContext ctx) {
        this.results = results;
        this.ctx = ctx;
        setLayout(new BorderLayout());
        setBackground(AppTheme.BASE);
        setBorder(BorderFactory.createEmptyBorder(0, 9, 8, 9));
        add(buildCard(), BorderLayout.CENTER);

        Thread worker = new Thread(this::drainLoop, "dualscan-worker");
        worker.setDaemon(true);
        worker.start();
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

        // QR2
        y++; g.gridx = 0; g.gridy = y; g.weightx = 0;
        card.add(AppTheme.label("QR 2"), g);
        tfQr2 = AppTheme.fieldQr();
        g.gridx = 1; g.weightx = 1.0;
        card.add(tfQr2, g);

        // banner
        y++; g.gridx = 0; g.gridy = y; g.gridwidth = 2;
        g.insets = new Insets(3, 3, 2, 3);
        banner = AppTheme.banner();
        card.add(banner, g);
        g.insets = new Insets(2, 3, 2, 3);

        // modalità: un segmento, non una checkbox di sistema
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
                fieldsBusy = !tfQr1.getText().isEmpty() || !tfQr2.getText().isEmpty();
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
                if (!tfQr2.getText().trim().isEmpty()) accept();
            }
        });

        btnClearFields.addActionListener(e -> {
            tfQr1.setText("");
            tfQr2.setText("");
            tfQr1.requestFocusInWindow();
        });
        btnFire.addActionListener(e -> {
            if (batchMode) {
                if (!queue.isEmpty()) { releasing = true; updateFireButton(); }
            } else {
                togglePause();
            }
        });
        btnVerify.addActionListener(e -> verifySession(true));
        btnNewSession.addActionListener(e -> newSession());

        refreshBanner();
        updateFireButton();
        return card;
    }

    // ── accept pairs: never lost, never blocking ──────────────────────────

    private void accept() {
        String q1 = tfQr1.getText().trim();
        String q2 = tfQr2.getText().trim();
        if (q1.isEmpty() || q2.isEmpty()) return;

        // the burst runs on a worker: check the window BEFORE the pair is queued,
        // while we are still on the EDT and can move the window if we must
        int cx = cfg.getInt(SettingsManager.SCAN_COORD_X, -1);
        int cy = cfg.getInt(SettingsManager.SCAN_COORD_Y, -1);
        if (cx >= 0 && cy >= 0) {
            Map<String, Point> targets = new LinkedHashMap<>();
            targets.put("Casella 1", new Point(cx, cy));
            String blocked = ctx.blockingCollision(targets);
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
                    SwingUtilities.invokeLater(() -> { refreshBanner(); updateFireButton(); });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // run only when: not paused, no verification running, scanner idle,
    // fields empty — and, in batch mode, only after ▶ was pressed
    private boolean mayRun() {
        boolean quiet = System.currentTimeMillis() - lastInputAt > QUIET_MS;
        boolean held  = batchMode && !releasing;
        return !paused && !verifying && !fieldsBusy && !held && quiet;
    }

    private void burst(Pair pair) throws InterruptedException {
        int cx = cfg.getInt(SettingsManager.SCAN_COORD_X, -1);
        int cy = cfg.getInt(SettingsManager.SCAN_COORD_Y, -1);
        if (cx < 0 || cy < 0) {
            queue.addFirst(pair);
            if (batchMode) releasing = false; else paused = true;
            SwingUtilities.invokeLater(() -> {
                setState(ERROR, "Manca la coordinata nelle Impostazioni — coppia rimessa in coda");
                updateFireButton();
            });
            return;
        }

        final int tFocus = cfg.getInt(SettingsManager.SCAN_FOCUS, 300);
        final int tKey   = cfg.getInt(SettingsManager.SCAN_KEY,   70);
        final int tEnter = cfg.getInt(SettingsManager.SCAN_ENTER, 150);

        try {
            SwingUtilities.invokeAndWait(() ->
                appPoint = tfQr1.isShowing() ? tfQr1.getLocationOnScreen() : null);
        } catch (Exception ignored) { }

        burstBusy = true;
        SwingUtilities.invokeLater(() -> setState(BUSY, null));
        try {
            robot.doubleClick(cx, cy);
            robot.sleep(tFocus);
            robot.pasteText(pair.q1); robot.sleep(tKey);
            robot.pressTab();         robot.sleep(tKey);
            robot.pasteText(pair.q2); robot.sleep(tKey);
            robot.pressTab();         robot.sleep(tKey);
            robot.pressEnter();       robot.sleep(tEnter);

            Point back = appPoint;   // return focus to the app at once
            if (back != null) robot.click(back.x + 20, back.y + 10);

            SwingUtilities.invokeLater(() -> onPairSent(pair));
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> {
                Toolkit.getDefaultToolkit().beep();
                setState(ERROR, "Coppia NON inviata: " + pair.q1 + "— rispara");
            });
        } finally {
            burstBusy = false;
        }
    }

    // ── outcomes (EDT) ─────────────────────────────────────────────────────

    private void onPairSent(Pair pair) {
        sent++;
        session.put(pair.q1, pair.q2);
        results.markSent(pair.row);
        results.sessionCounters(sent, queue.size());
        refreshBanner();
        updateFireButton();
        ctx.jobProgress(AppTheme.GREEN, "SPARA PURE", String.valueOf(sent),
                        "inviate · coda " + queue.size(), -1, false);

        int every = cfg.getInt(SettingsManager.SCAN_VERIFY_EVERY, 10);
        if (cfg.getBool(SettingsManager.SCAN_VERIFY_AUTO, true)
            && every > 0 && sent % every == 0) verifySession(false);
    }

    /** manual = 🔎: short wait + fallback to the latest export; auto = full timeout. */
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
            @Override public void onStatus(String m) { setState(VERIFY, m); }
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
                if (queue.isEmpty()) ctx.focusHome(tfQr1);
            }
            @Override public void onFailure(String reason) {
                verifying = false;
                results.showFailure(reason);
                setState(ERROR, "Verifica fallita — riprova col tasto Verifica");
                log.append("ERRORE · dual-scan · " + reason);
                if (queue.isEmpty()) ctx.focusHome(tfQr1);
            }
            @Override public void onCancelled() {
                verifying = false;
                results.showCancelled();
                refreshBanner();
                log.append("ANNULLATA · dual-scan");
                if (queue.isEmpty()) ctx.focusHome(tfQr1);
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
        refreshBanner();
        updateFireButton();
    }

    // the fire button changes job with the mode
    private void updateFireButton() {
        if (batchMode) {
            btnFire.setText(releasing ? "REGISTRO..." : "REGISTRA TUTTO (" + queue.size() + ")");
            btnFire.setIcon(Icons.play(AppTheme.ICON, AppTheme.ON_ACCENT));
            btnFire.setEnabled(!releasing && !queue.isEmpty());
        } else {
            btnFire.setText(paused ? "RIPRENDI" : "PAUSA");
            btnFire.setIcon(paused ? Icons.play(AppTheme.ICON, AppTheme.ON_ACCENT)
                                   : Icons.pause(AppTheme.ICON, AppTheme.ON_ACCENT));
            btnFire.setEnabled(true);
        }
    }

    private void newSession() {
        queue.clear();
        session.clear();
        sent = 0;
        releasing = false;
        sessionStarted = true;
        results.beginSession();
        refreshBanner();
        updateFireButton();
        tfQr1.requestFocusInWindow();
    }

    // ── banner ──────────────────────────────────────────────────────────────

    private void setState(int state, String text) {
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

    private void refreshBanner() {
        refreshCounter();
        if (burstBusy || verifying) return;
        if (paused)                       setState(PAUSED, null);
        else if (batchMode && !releasing) setState(COLLECT, null);
        else                              setState(READY, null);
    }

    private static String defaultDownloads() {
        return new File(System.getProperty("user.home"), "Downloads").getAbsolutePath();
    }
}
