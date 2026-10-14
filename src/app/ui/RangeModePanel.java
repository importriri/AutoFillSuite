package app.ui;

import app.config.SettingsManager;
import app.core.*;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Mode: REGISTRA · a intervallo — one scanned label defines a sequence, one lot
 * for the whole run. Operational only: label, lot, quantity, the lever.
 * Timing, coordinates and verification knobs live in ⚙.
 */
public class RangeModePanel extends JPanel {

    private final SettingsManager cfg = SettingsManager.getInstance();
    private final VerificationLog log = new VerificationLog(
        app.config.AppFiles.verificationLog().toPath());

    /** The robot's hands; a test puts fake ones here. */
    app.core.PairBurst.Hands hands = RobotEngine.getInstance();
    /** Answers the "already registered today" question; a test answers it. */
    java.util.function.Predicate<String> confirmer = this::askConfirm;
    private AutomationTask currentTask = null;
    private VerificationTask verifyTask = null;
    private volatile boolean robotRunning = false;
    private volatile boolean verifying = false;
    private RobotGate.Lease robotLease;   // held while this mode drives the robot (EDT only)

    private JTextField tfScan, tfLot;
    private final JTextField tfPrefix   = new JTextField();   // internal state
    private final JTextField tfStartSeq = new JTextField();   // internal state
    private JSpinner spCount;
    private JButton btnConfirm, btnReset, btnSwap, btnStart, btnStop, btnVerify, btnNewSession;
    private JLabel banner;
    private JProgressBar status;
    private final ResultsPanel results;
    private final RunContext ctx;
    private Counter counter;

    public RangeModePanel(ResultsPanel results, RunContext ctx) {
        this.results = results;
        this.ctx = ctx;
        setLayout(new BorderLayout());
        setBackground(AppTheme.BASE);
        setBorder(BorderFactory.createEmptyBorder(0, 9, 8, 9));
        add(buildCard(), BorderLayout.CENTER);
    }

    private JPanel buildCard() {
        JPanel card = AppTheme.card();
        card.setLayout(new GridBagLayout());
        card.setBorder(BorderFactory.createEmptyBorder(6, 10, 5, 10));
        GridBagConstraints g = AppTheme.gbc();
        g.insets = new Insets(1, 3, 1, 3);
        int y = 0;

        // etichetta (scan) + ✔ + ↺
        g.gridx = 0; g.gridy = y; g.weightx = 0;
        card.add(AppTheme.label("Etichetta"), g);
        JPanel scanRow = new JPanel(new BorderLayout(6, 0));
        scanRow.setOpaque(false);
        tfScan = AppTheme.field();
        btnConfirm = AppTheme.button("", AppTheme.GREEN);
        btnConfirm.setIcon(Icons.check(AppTheme.ICON, AppTheme.ON_ACCENT));
        btnConfirm.setBorder(BorderFactory.createEmptyBorder(7, 11, 7, 11));
        btnReset = AppTheme.iconButton(
            Icons.reset(AppTheme.ICON, AppTheme.SUBTEXT), "Svuota i campi");
        btnReset.setBorder(BorderFactory.createEmptyBorder(7, 11, 7, 11));
        JPanel scanBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        scanBtns.setOpaque(false);
        scanBtns.add(btnConfirm);
        scanBtns.add(btnReset);
        scanRow.add(tfScan, BorderLayout.CENTER);
        scanRow.add(scanBtns, BorderLayout.EAST);
        g.gridx = 1; g.weightx = 1.0;
        card.add(scanRow, g);

        // lotto
        y++; g.gridx = 0; g.gridy = y; g.weightx = 0;
        card.add(AppTheme.label("Lotto"), g);
        tfLot = AppTheme.field();
        // the swap sits next to the lot, like QR 2 in the scan card
        btnSwap = AppTheme.iconButton(
            Icons.swap(AppTheme.ICON, AppTheme.SUBTEXT), "Scambia Etichetta e Lotto  (F2)");
        btnSwap.setBorder(BorderFactory.createEmptyBorder(7, 11, 7, 11));
        JPanel lotRow = new JPanel(new BorderLayout(6, 0));
        lotRow.setOpaque(false);
        lotRow.add(tfLot, BorderLayout.CENTER);
        lotRow.add(btnSwap, BorderLayout.EAST);
        g.gridx = 1; g.weightx = 1.0;
        card.add(lotRow, g);

        // quantità
        y++; g.gridx = 0; g.gridy = y; g.weightx = 0;
        card.add(AppTheme.label("Quantità"), g);
        spCount = AppTheme.spinnerInt(cfg.getInt(SettingsManager.REG_COUNT, 10), 1, 9999);
        g.gridx = 1; g.weightx = 1.0;
        card.add(spCount, g);

        // full width from here down: the reset used to set gridwidth=2 but
        // left gridx=1, so banner, counter and levers all skipped the label
        // column — lopsided next to the scan card's centered single column
        g.gridx = 0; g.gridwidth = 2;
        // contatore: lo strumento che l'operatore guarda
        y++; g.gridy = y;
        banner = AppTheme.banner();
        card.add(banner, g);

        y++; g.gridy = y;
        counter = new Counter("etichette registrate");
        counter.set(0, 0);
        card.add(counter, g);

        // leva primaria + azioni secondarie
        y++; g.gridy = y;
        btnStart = AppTheme.primary("AVVIA", AppTheme.GREEN,
            Icons.play(AppTheme.ICON, AppTheme.ON_ACCENT));
        card.add(btnStart, g);

        y++; g.gridy = y;
        JPanel sec = new JPanel(new GridLayout(1, 2, 6, 0));
        sec.setOpaque(false);
        btnStop   = AppTheme.secondary("Stop", Icons.stop(AppTheme.ICON, AppTheme.SUBTEXT));
        btnVerify = AppTheme.secondary("Verifica", Icons.search(AppTheme.ICON, AppTheme.SUBTEXT));
        btnStop.setEnabled(false);
        sec.add(btnStop);
        sec.add(btnVerify);
        card.add(sec, g);

        y++; g.gridy = y;
        btnNewSession = AppTheme.ghost("Nuova sessione");
        card.add(btnNewSession, g);

        // riga di stato, sottile
        y++; g.gridy = y;
        status = AppTheme.progressStatus();
        status.setString("Spara un codice per iniziare.");
        card.add(status, g);

        btnConfirm.addActionListener(e -> confirmScan());
        tfScan.addActionListener(e -> confirmScan());       // scanner sends ENTER
        btnReset.addActionListener(e -> resetFields());
        btnSwap.addActionListener(e -> swapFields());
        // F2 anywhere in the card — the scanner never sends it
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F2, 0), "swapRange");
        getActionMap().put("swapRange", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { swapFields(); }
        });
        spCount.addChangeListener(e -> updatePreview());
        btnStart.addActionListener(e -> start());
        btnStop.addActionListener(e -> {
            if (currentTask != null) currentTask.stop();
            status.setString("Interrotto.");
            state(AppTheme.PEACH, AppTheme.ON_ACCENT, "INTERROTTO");
        });
        btnVerify.addActionListener(e -> startVerification(true));
        btnNewSession.addActionListener(e -> newSession());
        state(AppTheme.SURFACE2, AppTheme.TEXT, "PRONTO — spara un codice");
        return card;
    }

    // ── scan → prefix + sequence ──────────────────────────────────────────

    private void confirmScan() {
        String raw = tfScan.getText().trim();
        // the lot scanned into Etichetta: only the configured patterns can tell
        String p1 = cfg.get(SettingsManager.SCAN_QR1_PATTERN, "");
        String p2 = cfg.get(SettingsManager.SCAN_QR2_PATTERN, "");
        if (ScanGuard.inspect(raw, "", p1, "") == ScanGuard.MISMATCH
                && ScanGuard.inspect("", raw, "", p2) == ScanGuard.OK
                && !p2.trim().isEmpty()) {
            app.core.Sounds.error();
            state(AppTheme.YELLOW, AppTheme.ON_ACCENT, "Lotto in Etichetta: codici invertiti");
            warn("Hai sparato il lotto in Etichetta: premi Scambia (F2)");
            tfPrefix.setText("");
            tfStartSeq.setText("");
            return;
        }
        if (raw.length() <= 3) { warn("Codice troppo corto."); return; }
        String seq = raw.substring(raw.length() - 3);
        for (int i = 0; i < seq.length(); i++) {
            if (!Character.isDigit(seq.charAt(i))) {
                warn("Le ultime 3 cifre devono essere numeriche.");
                return;
            }
        }
        tfPrefix.setText(raw.substring(0, raw.length() - 3));
        tfStartSeq.setText(seq);
        updatePreview();
    }

    private boolean askConfirm(String message) {
        return SafeConfirm.ask(this, message, "Già registrate oggi");
    }

    /** Etichetta and Lotto trade places, and the label is read again. */
    private void swapFields() {
        if (robotRunning) return;
        String a = tfScan.getText();
        tfScan.setText(tfLot.getText());
        tfLot.setText(a);
        state(AppTheme.SURFACE2, AppTheme.TEXT, "PRONTO — controlla e premi AVVIA");
        confirmScan();
    }

    private void resetFields() {
        if (robotRunning) return;
        tfScan.setText("");
        tfLot.setText("");
        tfPrefix.setText("");
        tfStartSeq.setText("");
        status.setString("Spara un codice per iniziare.");
        tfScan.requestFocusInWindow();
    }

    // preview lives in the status bar: zero extra rows
    private void updatePreview() {
        List<String> codes = buildExpected();
        if (codes == null || codes.isEmpty() || robotRunning || verifying) return;
        status.setString(rangePreview(codes.get(0), codes.get(codes.size() - 1)));
    }

    private List<String> buildExpected() {
        String prefix = tfPrefix.getText().trim();
        String seq    = tfStartSeq.getText().trim();
        if (prefix.isEmpty() || seq.isEmpty()) return null;
        try {
            return RegistrationVerifier.expectedCodes(
                prefix, Long.parseLong(seq), (int) spCount.getValue());
        } catch (NumberFormatException e) {
            return null;
        } catch (IllegalArgumentException overflow) {
            warn(overflow.getMessage());
            return null;
        }
    }

    // ── run ───────────────────────────────────────────────────────────────

    private void start() {
        if (robotRunning) return;
        try { spCount.commitEdit(); } catch (java.text.ParseException ignored) { }
        final List<String> codes = buildExpected();
        if (codes == null) {
            if (!statusText().contains("supera 999")) warn("Spara un'etichetta valida prima.");
            return;
        }
        final String lot = tfLot.getText().trim();
        if (lot.isEmpty()) { warn("Inserisci il lotto."); return; }
        // the same guard as the scan mode: Etichetta against QR 1, Lotto against QR 2
        int advice = ScanGuard.inspect(tfScan.getText().trim(), lot,
            cfg.get(SettingsManager.SCAN_QR1_PATTERN, ""), cfg.get(SettingsManager.SCAN_QR2_PATTERN, ""));
        if (advice == ScanGuard.INVERTED) {
            app.core.Sounds.error();
            state(AppTheme.YELLOW, AppTheme.ON_ACCENT, "Codici invertiti: premi Scambia (F2)");
            warn("Etichetta e Lotto sono al contrario");
            return;
        }
        if (advice == ScanGuard.MISMATCH) {
            app.core.Sounds.error();
            state(AppTheme.RED, AppTheme.ON_ACCENT, "Formato non riconosciuto");
            warn("Etichetta o Lotto non hanno la forma attesa: controlla i due codici");
            return;
        }

        // labels registered EARLIER TODAY: another lot is a correction on the
        // portal (the last one wins); ask once, with the list
        Map<String, String[]> today = results.registeredToday();
        List<String> changes = new java.util.ArrayList<>();
        int same = 0;
        for (String c : codes) {
            String[] b = today.get(c);
            if (b == null) continue;
            if (b[0].equals(lot)) same++;
            else changes.add(c + "  (alle " + b[1] + " con " + b[0] + ")");
        }
        if (!changes.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(changes.size()).append(" etichette sono già state registrate oggi con un altro lotto:\n\n");
            for (int i = 0; i < changes.size() && i < 10; i++) sb.append(changes.get(i)).append('\n');
            if (changes.size() > 10) sb.append("... e altre ").append(changes.size() - 10).append('\n');
            sb.append("\nÈ una correzione: sul portale vincerà il lotto ").append(lot).append(".\n\nAvviare comunque?");
            if (!confirmer.test(sb.toString())) { warn("Avvio annullato: etichette già registrate oggi."); return; }
        }

        int cx = cfg.getInt(SettingsManager.REG_COORD_X, -1);
        int cy = cfg.getInt(SettingsManager.REG_COORD_Y, -1);
        if (cx < 0 || cy < 0) { warn("Memorizza «Casella 1» nelle Impostazioni."); return; }
        if (same > 0) status.setString(same + " etichette già registrate oggi con lo stesso lotto: reinvio");

        Map<String, Point> targets = new LinkedHashMap<>();
        targets.put("Casella 1", new Point(cx, cy));
        int ecx = cfg.getInt(SettingsManager.REG_EXPORT_COORD_X, -1);
        int ecy = cfg.getInt(SettingsManager.REG_EXPORT_COORD_Y, -1);
        if (ecx >= 0 && ecy >= 0) targets.put("Export CSV", new Point(ecx, ecy));
        String blocked = ctx.blockingCollision(targets);
        if (blocked != null) { warn(blocked); return; }
        if (!takeRobot()) return;

        try { spCount.commitEdit(); } catch (java.text.ParseException ignored) { }
        cfg.set(SettingsManager.REG_COUNT, spCount.getValue());
        // remember what we are about to write: if the app dies mid-run, the
        // next start can offer to verify it instead of losing the trail
        cfg.set(SettingsManager.RUN_PENDING, true);
        cfg.set(SettingsManager.RUN_PREFIX, tfPrefix.getText().trim());
        cfg.set(SettingsManager.RUN_SEQ0,   tfStartSeq.getText().trim());
        cfg.set(SettingsManager.RUN_COUNT,  spCount.getValue());
        cfg.set(SettingsManager.RUN_LOT,    lot);
        cfg.save();

        final int total   = (int) spCount.getValue();
        final Point coord = new Point(cx, cy);
        final int delay   = cfg.getInt(SettingsManager.REG_WAIT, 3);
        final int tKey    = (int) (cfg.getDouble(SettingsManager.REG_FIELD_DELAY, 0.3) * 1000);
        final int tPost   = cfg.getInt(SettingsManager.REG_POST_ENTER, 500);
        final int rhythmMs = (int) (cfg.getDouble(SettingsManager.REG_FIXED_DELAY, 2.0) * 1000);
        final String prefix = tfPrefix.getText().trim();
        final long seq0     = Long.parseLong(tfStartSeq.getText().trim());

        robotRunning = true;
        refreshButtons();
        results.beginRun(total, lot);
        counter.set(0, total);
        counter.setTone(AppTheme.TEXT);
        status.setValue(0);
        status.setString("Avvio...");

        currentTask = new AutomationTask() {
            @Override protected int getTotal()      { return total; }
            @Override protected int getStartDelay() { return delay; }
            @Override protected void showCountdown(int sec) {
                SwingUtilities.invokeLater(() -> {
                    state(AppTheme.PEACH, AppTheme.ON_ACCENT, "AVVIO TRA " + sec + "s");
                    status.setString("Avvio tra " + sec + "s...");
                    ctx.jobProgress(AppTheme.PEACH, "AVVIO TRA " + sec + "s",
                                    "0 / " + total, "registrate", 0, true);
                });
            }
            @Override protected boolean executeCycle(int i) throws Exception {
                final int n = i + 1;
                // between cycles the pointer is where the robot left it: a move
                // since the last ENTER is the operator taking the mouse back
                if (i > 0 && hands.isMouseMoved(coord)) {
                    triggerFailSafe("Mouse mosso al ciclo " + n);
                    return false;
                }
                // the verifier's own list: robot and check can never disagree
                final String code = codes.get(i);
                // the scan mode's burst: the mouse is checked between EVERY step,
                // the first cycle included, and ENTER is the point of no return
                PairBurst.Outcome o = PairBurst.run(hands, coord, null, code, lot,
                    new PairBurst.Timing(tKey, tKey, tPost), true, null);
                if (o == PairBurst.Outcome.NOT_SENT) {
                    triggerFailSafe("Mouse mosso al ciclo " + n + ": " + code + " NON registrata");
                    return false;
                }
                if (o == PairBurst.Outcome.FAILED) {
                    throw new IllegalStateException("robot non disponibile al ciclo " + n + ": "
                                                    + code + " non registrata");
                }
                SwingUtilities.invokeLater(() -> {
                    results.addLiveRow(code, n);
                    counter.set(n, total);
                    state(AppTheme.GREEN, AppTheme.ON_ACCENT, "REGISTRAZIONE — non toccare");
                    status.setString("in corso...");
                    ctx.jobProgress(AppTheme.PEACH, "ROBOT AL LAVORO",
                                    n + " / " + total, "registrate", n * 100 / total, true);
                });
                if (o == PairBurst.Outcome.SENT_THEN_GRABBED) {
                    triggerFailSafe("Mouse mosso dopo INVIO al ciclo " + n + ": " + code
                                    + " registrata, robot fermo");
                    return false;
                }
                if (i < total - 1 && running) hands.sleep(rhythmMs);
                return true;
            }
            @Override protected void updateProgress(int cur, int tot) {
                SwingUtilities.invokeLater(() -> status.setValue((int) ((double) cur / tot * 100)));
            }
            @Override protected void onCompleted() {
                status.setValue(100);
                counter.set(total, total);
                counter.setTone(AppTheme.GREEN);
                status.setString("Completato");
                if (cfg.getBool(SettingsManager.REG_VERIFY_AUTO, true)) {
                    startVerification(false);
                } else {
                    state(AppTheme.GREEN, AppTheme.ON_ACCENT,
                          "RUN COMPLETATO — " + total + "/" + total);
                }
            }
            @Override protected void onFinally() {
                robotRunning = false;
                freeRobotIfIdle();
                refreshButtons();
                // the cockpit comes back unless a verification is actually
                // running. "auto-verify is on" is NOT the same thing: a
                // fail-safe or a STOP skips onCompleted, so no verification
                // ever starts — and the window used to stay collapsed in the
                // HUD, with the operator having to expand it by hand to read
                // why the robot stopped.
                if (!verifying) ctx.jobFinished();
            }
            @Override protected void onFailSafe(String message) {
                status.setString(message);
                state(AppTheme.PEACH, AppTheme.ON_ACCENT, "INTERROTTO — fail-safe");
                results.showFailSafe(message);
            }
            @Override protected void showError(String msg) {
                app.core.Sounds.error();
                status.setString("Errore: " + msg);
                state(AppTheme.RED, AppTheme.ON_ACCENT, "ERRORE ROBOT");
                results.showFailure("Errore robot: " + msg);
            }
        };
        ctx.jobStarted(() -> {
            currentTask.stop();
            status.setString("Interrotto.");
        });
        new Thread(currentTask).start();
    }

    /**
     * One correction burst at Casella 1: code, TAB, new lot, TAB, ENTER.
     * false when it cannot even start: the row goes back to the old lot.
     */
    boolean fixOne(int row, String code, String oldLot, String newLot) {
        if (robotRunning || verifying) {
            warn("Robot occupato — riprova a fine run");
            return false;
        }
        int cx = cfg.getInt(SettingsManager.REG_COORD_X, -1);
        int cy = cfg.getInt(SettingsManager.REG_COORD_Y, -1);
        if (cx < 0 || cy < 0) {
            warn("Memorizza «Casella 1» nelle Impostazioni.");
            return false;
        }
        Map<String, Point> targets = new LinkedHashMap<>();
        targets.put("Casella 1", new Point(cx, cy));
        String blocked = ctx.blockingCollision(targets);
        if (blocked != null) { warn(blocked); return false; }
        if (!takeRobot()) return false;

        final int tKey  = (int) (cfg.getDouble(SettingsManager.REG_FIELD_DELAY, 0.3) * 1000);
        final int tPost = cfg.getInt(SettingsManager.REG_POST_ENTER, 500);
        robotRunning = true;
        refreshButtons();
        status.setString("Correzione " + code + "...");
        new Thread(() -> {
            try {
                // the same burst and fail-safe as everywhere: before ENTER the
                // correction did not happen; after it, it did
                PairBurst.Outcome o = PairBurst.run(hands, new Point(cx, cy), null, code, newLot,
                    new PairBurst.Timing(tKey, tKey, tPost), true, null);
                SwingUtilities.invokeLater(() -> {
                    switch (o) {
                        case SENT:
                            results.markSent(row);
                            status.setString("Correzione inviata — riverifica per confermare");
                            ctx.focusHome(tfScan);
                            break;
                        case SENT_THEN_GRABBED:
                            results.markSent(row);
                            status.setString("Correzione inviata, poi mouse mosso — riverifica");
                            break;
                        case NOT_SENT:
                            app.core.Sounds.error();
                            status.setString("Mouse mosso: correzione di " + code + " NON inviata");
                            results.correctionNotSent(row, oldLot, "Correzione NON inviata: " + code);
                            break;
                        default:
                            app.core.Sounds.error();
                            status.setString("Robot non disponibile: correzione NON inviata");
                            results.correctionNotSent(row, oldLot, "Correzione NON inviata: " + code);
                    }
                });
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                SwingUtilities.invokeLater(() -> {
                    robotRunning = false;
                    freeRobotIfIdle();
                    refreshButtons();
                });
            }
        }, "lot-fix").start();
        return true;
    }

    /**
     * The robot gate, on the interface thread. A run hands it straight to its
     * own verification (onCompleted starts it before onFinally), so the lease
     * is kept while either is at work and given back when both are over.
     */
    private boolean takeRobot() {
        if (robotLease != null) return true;
        String other = ctx.busyElsewhere(this);
        if (other != null) { warn("Robot occupato: " + other); return false; }
        robotLease = RobotGate.tryAcquire("Intervallo");
        if (robotLease == null) { warn("Robot occupato: " + RobotGate.owner()); return false; }
        return true;
    }

    private void freeRobotIfIdle() {
        if (robotRunning || verifying || robotLease == null) return;
        robotLease.release();
        robotLease = null;
    }

    // one place decides what is clickable: never two robots at once
    private void refreshButtons() {
        btnStart.setEnabled(!robotRunning && !verifying);
        btnStop.setEnabled(robotRunning);
        btnVerify.setEnabled(!robotRunning && !verifying);
        btnConfirm.setEnabled(!robotRunning);
        btnReset.setEnabled(!robotRunning);
        btnSwap.setEnabled(!robotRunning);
        tfScan.setEnabled(!robotRunning);
    }

    // ── verification ──────────────────────────────────────────────────────

    /** manual = 🔎: short wait + fallback to the latest export; auto = full timeout. */
    private void startVerification(boolean manual) {
        if (verifying) return;
        final List<String> codes = buildExpected();
        if (codes == null) { warn("Spara un'etichetta valida prima."); return; }
        final String lot = tfLot.getText().trim();
        if (lot.isEmpty()) { warn("Inserisci il lotto."); return; }

        int ex = cfg.getInt(SettingsManager.REG_EXPORT_COORD_X, -1);
        int ey = cfg.getInt(SettingsManager.REG_EXPORT_COORD_Y, -1);
        if (ex < 0 || ey < 0) { warn("Memorizza «Export CSV» nelle Impostazioni."); return; }

        Map<String, Point> exportTarget = new LinkedHashMap<>();
        exportTarget.put("Export CSV", new Point(ex, ey));
        String blockedExport = ctx.blockingCollision(exportTarget);
        if (blockedExport != null) { warn(blockedExport); return; }

        File dir = app.config.AppFiles.downloadDir();
        int timeoutMs = manual ? 8000 : cfg.getInt(SettingsManager.REG_EXPORT_TIMEOUT_S, 30) * 1000;
        int retries   = manual ? 0 : cfg.getInt(SettingsManager.REG_VERIFY_RETRIES, 2);
        int retryMs   = cfg.getInt(SettingsManager.REG_VERIFY_RETRY_S, 2) * 1000;

        DownloadWatcher watcher = new DownloadWatcher(
            dir.toPath(),
            cfg.get(SettingsManager.REG_EXPORT_PREFIX, "demo-export"),
            timeoutMs,
            cfg.getInt(SettingsManager.REG_EXPORT_POLL_MS, 500),
            cfg.getInt(SettingsManager.REG_EXPORT_STABLE_MS, 800));

        final Point ec = new Point(ex, ey);
        Runnable exportClick = () -> {
            try {
                RobotEngine.getInstance().click(ec.x, ec.y);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        };
        final int total = codes.size();
        if (!takeRobot()) return;
        verifying = true;
        refreshButtons();
        results.ensureRows(codes, lot);   // may open a new run: the actions come after
        results.setActions(() -> startVerification(true), this::cancelVerification);
        results.setLotFixer(this::fixOne);
        // each label with the lot its row shows: a correction sent changed it,
        // and checking the run's lot would call it LOTTO ERRATO forever
        final Map<String, String> expected = results.expectedLots(codes, lot);
        final RegistrationVerifier verifier = new RegistrationVerifier();
        Function<List<String>, VerificationResult> checker = lines -> verifier.verify(lines, expected);
        results.showVerifying();

        verifyTask = new VerificationTask(exportClick, watcher, checker,
            retries, retryMs, manual, new VerificationTask.Listener() {
            @Override public void onStatus(String m) {
                status.setString(m);
                state(AppTheme.BLUE, AppTheme.ON_ACCENT, "VERIFICA IN CORSO...");
                ctx.jobProgress(AppTheme.BLUE, "VERIFICA", "-", "in corso", -1, false);
            }
            @Override public void onOutcome(VerificationResult r, Path f, int attempts, boolean fresh) {
                verifying = false;
                freeRobotIfIdle();
                refreshButtons();
                String fn = f.getFileName().toString();
                results.showOutcome(r, fn, attempts, fresh);
                counter.set(r.getMatched(), total);
                counter.setTone(r.isClean() ? AppTheme.GREEN : AppTheme.RED);
                status.setString(r.isClean()
                    ? "Verificato " + r.getMatched() + "/" + total
                    : r.totalProblems() + " problemi");
                state(r.isClean() ? AppTheme.GREEN : AppTheme.RED, AppTheme.ON_ACCENT,
                      r.isClean() ? "TUTTO OK — " + r.getMatched() + "/" + total
                                  : r.totalProblems() + " PROBLEMI su " + total);
                logAppend(VerificationLog.formatEntry(r, fn, attempts, total, "lotto=" + lot, fresh));
                results.offerReport(r);
                clearPendingRun();
                ctx.jobProgress(r.isClean() ? AppTheme.GREEN : AppTheme.RED,
                                r.isClean() ? "TUTTO OK" : r.totalProblems() + " PROBLEMI",
                                r.getMatched() + " / " + total, "verificate", 100, false);
                ctx.jobFinished();
                ctx.focusHome(tfScan);   // next scan lands HERE, not in the browser
            }
            @Override public void onFailure(String reason) {
                verifying = false;
                freeRobotIfIdle();
                refreshButtons();
                results.showFailure(reason);
                status.setString("Verifica fallita");
                state(AppTheme.RED, AppTheme.ON_ACCENT, "VERIFICA FALLITA");
                logAppend("ERRORE · " + reason);
                ctx.jobFinished();
                ctx.focusHome(tfScan);
            }
            @Override public void onCancelled() {
                verifying = false;
                freeRobotIfIdle();
                refreshButtons();
                results.showCancelled();
                status.setString("Verifica annullata");
                state(AppTheme.PEACH, AppTheme.ON_ACCENT, "VERIFICA ANNULLATA");
                logAppend("ANNULLATA");
                ctx.jobFinished();
                ctx.focusHome(tfScan);
            }
        });
        new Thread(verifyTask, "verification").start();
    }

    private void cancelVerification() {
        if (verifyTask != null) verifyTask.cancel();
    }

        /** The run finished its verification: nothing left hanging to recover. */
    private void clearPendingRun() {
        cfg.set(SettingsManager.RUN_PENDING, false);
        cfg.save();
    }

    /** Reload a run the app never got to verify — offered at startup. */
    public void restorePendingRun() {
        if (!cfg.getBool(SettingsManager.RUN_PENDING, false)) return;
        String prefix = cfg.get(SettingsManager.RUN_PREFIX, "");
        String seq    = cfg.get(SettingsManager.RUN_SEQ0, "");
        String lot    = cfg.get(SettingsManager.RUN_LOT, "");
        int count     = cfg.getInt(SettingsManager.RUN_COUNT, 0);
        if (prefix.isEmpty() || seq.isEmpty() || lot.isEmpty() || count <= 0) return;

        tfPrefix.setText(prefix);
        tfStartSeq.setText(seq);
        tfLot.setText(lot);
        tfScan.setText(prefix + seq);
        spCount.setValue(count);
        results.offerPendingVerification(count, () -> startVerification(true),
                                         this::clearPendingRun);
    }

    private void logAppend(String block) {
        if (!log.append(block)) status.setString("Log non scrivibile: " + log.getFile());
    }

    /** What the status line says now (the tests read it). */
    String statusText() {
        return status.getString();
    }

    /** What the banner says now (the tests read it). */
    String bannerText() {
        return AppTheme.bannerText(banner);
    }

    private void state(java.awt.Color bg, java.awt.Color fg, String text) {
        banner.setBackground(bg);
        banner.setForeground(fg);
        banner.setText(text);
    }

    /** Fresh start, operator's word: table, counters, banner and the pending
     *  flag all reset — the coordinates and the timings in ⚙ stay. */
    private void newSession() {
        if (robotRunning || verifying) {
            warn("Robot occupato — riprova a fine run");
            return;
        }
        resetFields();
        results.resetIdle();
        counter.set(0, 0);
        counter.setTone(AppTheme.TEXT);
        status.setValue(0);
        status.setString("Spara un codice per iniziare.");
        state(AppTheme.SURFACE2, AppTheme.TEXT, "PRONTO — spara un codice");
        cfg.set(SettingsManager.RUN_PENDING, false);
        cfg.save();
        tfScan.requestFocusInWindow();
    }

    /** The preview must FIT: the two ends of a range share their prefix, so
     *  the second code collapses to "...<tail>" — at least 4 chars, the ones
     *  that say where the range ENDS. Codes with nothing in common stay full
     *  and the bar's tail-fitting takes it from there. */
    static String rangePreview(String first, String last) {
        if (first.equals(last)) return "Da " + first;
        int cp = 0;
        int max = Math.min(first.length(), last.length());
        while (cp < max && first.charAt(cp) == last.charAt(cp)) cp++;
        if (cp == 0) return "Da " + first + " -> " + last;
        int from = Math.min(cp, Math.max(0, last.length() - 4));
        return "Da " + first + " -> ..." + last.substring(from);
    }

    private void warn(String msg) { status.setString(msg); }

    /** The robot or a verification is at work. */
    boolean isBusy() {
        return robotRunning || verifying;
    }

}
