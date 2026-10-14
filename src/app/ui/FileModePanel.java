package app.ui;

import app.config.SettingsManager;
import app.core.CsvImport;
import app.core.DownloadWatcher;
import app.core.ImportLedger;
import app.core.PairBurst;
import app.core.Preflight;
import app.core.RegistrationVerifier;
import app.core.RobotEngine;
import app.core.RobotGate;
import app.core.VerificationLog;
import app.core.VerificationResult;
import app.core.VerificationTask;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Mode: REGISTRA · da file — a CSV exported by Registro QR (a second line that
 * scans labels without a portal), registered by the robot in verified blocks.
 *
 * EVERYTHING is checked before the robot's first click: fingerprint and rows,
 * the register of files (new / already done / interrupted), the ORDER of the
 * station's exports, the shape of the codes, and — on the portal itself — the
 * pre-flight: what the portal holds now for each label. A red line stops;
 * "modified", "already done" and "out of order" can be overridden only with a
 * confirmation that says what is at risk.
 *
 * The robot is the scan mode's: PairBurst, the same fail-safe and point of no
 * return. Each sent pair is written to the import register right after ENTER,
 * before anything else — that line is what a resume trusts. After every block,
 * and never in the middle of one, the export is checked against every pair
 * sent from the file so far.
 */
public class FileModePanel extends JPanel {

    private final SettingsManager cfg = SettingsManager.getInstance();
    private final ResultsPanel results;
    private final RunContext ctx;
    private final VerificationLog log = new VerificationLog(
        app.config.AppFiles.verificationLog().toPath());

    /** The robot's hands; a test puts fake ones here. */
    PairBurst.Hands hands = RobotEngine.getInstance();
    /** The click on the portal's Export CSV; a test makes it write the file itself. */
    Runnable exportClick = null;
    /** Answers to the override questions; a test answers them. */
    java.util.function.Predicate<String> confirmer = this::askConfirm;

    // ── what is loaded ───────────────────────────────────────────────────

    /** One row of the file and what happens to it. */
    static final class Job {
        final CsvImport.CsvRow row;
        int tableRow = -1;
        boolean sent;
        boolean skip;          // already on the portal, or out of shape
        String word;           // the row's phase word in the table

        Job(CsvImport.CsvRow row) { this.row = row; }
    }

    private File file;
    private CsvImport.Result read;
    private String sha;
    private final List<Job> jobs = new ArrayList<>();
    private final List<Job> fixes = new ArrayList<>();   // double click on a lot
    private boolean hardBlock;
    private final List<String> needConfirm = new ArrayList<>();
    private boolean confirmed;
    private boolean preflightDone;
    private int conflicts;

    // ── what is running ──────────────────────────────────────────────────

    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile boolean verifying;
    private volatile Point appPoint;
    private RobotGate.Lease robotLease;   // held while this mode drives the robot (EDT only)
    private VerificationTask verifyTask;

    // ── widgets ──────────────────────────────────────────────────────────

    private JButton btnOpen;
    private final JPanel checks = new JPanel(new GridBagLayout());
    private JLabel banner;
    private Counter counter;
    private JButton btnStart, btnOverride, btnStop, btnVerify;
    private final JLabel lblLast = AppTheme.hint(" ");

    public FileModePanel(ResultsPanel results, RunContext ctx) {
        this.results = results;
        this.ctx = ctx;
        setLayout(new BorderLayout());
        setBackground(AppTheme.BASE);
        setBorder(BorderFactory.createEmptyBorder(0, 9, 8, 9));
        add(buildCard(), BorderLayout.CENTER);
        showEmpty();
    }

    private JPanel buildCard() {
        JPanel card = AppTheme.card();
        card.setLayout(new GridBagLayout());
        card.setBorder(BorderFactory.createEmptyBorder(6, 10, 5, 10));
        GridBagConstraints g = AppTheme.gbc();
        g.gridx = 0; g.gridwidth = 1;
        g.insets = new Insets(2, 3, 2, 3);
        int y = 0;

        g.gridy = y++;
        btnOpen = AppTheme.secondary("Apri CSV...", null);
        btnOpen.setToolTipText("Apri un file esportato da Registro QR");
        btnOpen.addActionListener(e -> chooseFile());
        card.add(btnOpen, g);

        g.gridy = y++;
        checks.setOpaque(false);
        card.add(checks, g);

        g.gridy = y++;
        g.insets = new Insets(4, 3, 2, 3);
        banner = AppTheme.banner();
        card.add(banner, g);
        g.insets = new Insets(2, 3, 2, 3);

        g.gridy = y++;
        counter = new Counter("inviate");
        counter.set(0, 0);
        card.add(counter, g);

        g.gridy = y++;
        btnStart = AppTheme.primary("AVVIA", AppTheme.GREEN, Icons.play(AppTheme.ICON, AppTheme.ON_ACCENT));
        btnStart.addActionListener(e -> {
            if (needsClosingCheck()) verifyNow(); else start();
        });
        card.add(btnStart, g);

        g.gridy = y++;
        btnOverride = AppTheme.secondary("Registra comunque... (chiede conferma)", null);
        btnOverride.setForeground(AppTheme.RED);
        btnOverride.addActionListener(e -> override());
        card.add(btnOverride, g);

        g.gridy = y++;
        JPanel sec = new JPanel(new GridLayout(1, 2, 6, 0));
        sec.setOpaque(false);
        btnStop = AppTheme.secondary("Stop", Icons.stop(AppTheme.ICON, AppTheme.SUBTEXT));
        btnVerify = AppTheme.secondary("Verifica", Icons.search(AppTheme.ICON, AppTheme.SUBTEXT));
        btnStop.addActionListener(e -> stop());
        btnVerify.addActionListener(e -> verifyNow());
        sec.add(btnStop);
        sec.add(btnVerify);
        card.add(sec, g);

        g.gridy = y++;
        lblLast.setHorizontalAlignment(SwingConstants.CENTER);
        card.add(lblLast, g);
        return card;
    }

    // ── opening a file ───────────────────────────────────────────────────

    private void chooseFile() {
        if (running) return;
        JFileChooser ch = new JFileChooser(cfg.get(SettingsManager.FILE_LAST_DIR, System.getProperty("user.home")));
        ch.setDialogTitle("Apri un CSV di Registro QR");
        ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CSV di Registro QR", "csv"));
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = ch.getSelectedFile();
        cfg.set(SettingsManager.FILE_LAST_DIR, f.getParent());
        cfg.save();
        open(f);
    }

    File reportDir() {
        return app.config.AppFiles.reportDir();
    }

    ImportLedger ledger() {
        return ImportLedger.in(reportDir());
    }

    /** Reads the file and runs every check that needs no portal. */
    void open(File f) {
        if (running) return;
        // opening replaces the results table: never under another mode's job
        String other = ctx.busyElsewhere(this);
        if (other != null) { error("File non aperto: " + other); return; }
        file = f;
        jobs.clear();
        fixes.clear();
        needConfirm.clear();
        confirmed = false;
        preflightDone = false;
        conflicts = 0;
        hardBlock = false;
        checks.removeAll();
        try {
            read = CsvImport.read(Files.readAllBytes(f.toPath()));
        } catch (IOException e) {
            read = null;
            checkRow("file", "ILLEGGIBILE", AppTheme.RED);
            hardBlock = true;
            finishChecks("File non leggibile: " + e.getMessage());
            return;
        }
        read = CsvImport.inspectShapes(read,
            cfg.get(SettingsManager.SCAN_QR1_PATTERN, ""), cfg.get(SettingsManager.SCAN_QR2_PATTERN, ""));
        sha = read.computedSha;

        // 1-2-3: format, completeness, fingerprint
        String integrity = "impronta e righe (" + read.rows.size() + ")";
        switch (read.status) {
            case OK:
                checkRow(integrity, "OK", AppTheme.GREEN);
                break;
            case MODIFIED:
                checkRow(integrity, "MODIFICATO", AppTheme.RED);
                needConfirm.add("Il file è stato modificato fuori da Registro QR (per esempio risalvato "
                    + "in Excel): righe e lotti potrebbero non essere quelli sparati.");
                break;
            default:
                checkRow(integrity, statusWord(read.status), AppTheme.RED);
                hardBlock = true;
                finishChecks(read.detail);
                return;
        }

        // the register of files
        ImportLedger.FileState st = ledger().state(sha);
        if (st.completed) {
            checkRow("registro dei file", "GIÀ FATTO", AppTheme.RED);
            needConfirm.add("Questo file è già stato registrato il " + st.completedAt + " (esito "
                + st.outcome + "). Registrarlo di nuovo sporca lo storico del portale.");
        } else if (st.interrupted()) {
            checkRow("registro dei file", "INTERROTTO", AppTheme.PEACH);
        } else {
            checkRow("registro dei file", "NUOVO", AppTheme.GREEN);
        }

        // the order of the station's exports
        String station = read.station(), date = read.date();
        int k = read.exportNumber();
        String order = ledger().orderProblem(station, date, k);
        String orderLabel = "ordine (" + String.format("%02d", k) + " di " + station + ")";
        if (order == null) {
            checkRow(orderLabel, "OK", AppTheme.GREEN);
        } else {
            checkRow(orderLabel, order.contains("rimetterebbe") ? "VECCHIO" : "FUORI ORDINE", AppTheme.RED);
            needConfirm.add("Ordine: " + order + ". Un file fuori ordine può rimettere sul portale "
                + "lotti già corretti, in silenzio, perché vince l'ultima registrazione.");
        }

        // shapes
        int off = CsvImport.outOfShape(read);
        checkRow("forma dei codici", (read.rows.size() - off) + "/" + read.rows.size(),
                 off == 0 ? AppTheme.GREEN : AppTheme.PEACH);

        // the pre-flight, on the portal
        checkRow("pre-volo sul portale", preflightRequired() ? "da fare" : "spento",
                 preflightRequired() ? AppTheme.SUBTEXT : AppTheme.PEACH);

        // the table: every row, with what will happen to it
        results.beginFileSession(f.getName());
        for (CsvImport.CsvRow r : read.rows) {
            Job j = new Job(r);
            // only an interrupted file resumes: a completed one, confirmed again, starts over
            if (st.interrupted() && st.sent.contains(r.n)) {
                j.sent = true;
                j.word = "INVIATA";
            } else if (!r.inShape) {
                j.skip = true;
                j.word = "FUORI FORMA";
            } else {
                j.word = queuedWord(r);
            }
            j.tableRow = results.addFileRow(r.label, r.lot, j.word);
            jobs.add(j);
        }
        finishChecks(null);
    }

    static String queuedWord(CsvImport.CsvRow r) {
        if (r.isCorrection()) return "CORREZIONE IN CODA";
        return r.modified ? "IN CODA (a mano)" : "IN CODA";
    }

    private static String statusWord(CsvImport.Status s) {
        switch (s) {
            case TRUNCATED:      return "TRONCATO";
            case UNKNOWN_FORMAT: return "FORMATO NUOVO";
            case EMPTY:          return "VUOTO";
            default:             return "NON VALIDO";
        }
    }

    boolean preflightRequired() {
        return read != null && (read.reexport() || cfg.getBool(SettingsManager.FILE_PREFLIGHT, true));
    }

    private int checkRowY = 0;

    private void checkRow(String label, String value, Color tone) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = checkRowY++;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(1, 0, 1, 0);
        g.gridx = 0; g.weightx = 1;
        JLabel l = new JLabel(label);
        l.setFont(AppTheme.F_UI);
        l.setForeground(AppTheme.TEXT);
        checks.add(l, g);
        g.gridx = 1; g.weightx = 0;
        JLabel v = new JLabel(value, SwingConstants.RIGHT);
        v.setFont(AppTheme.F_BOLD);
        v.setForeground(tone);
        v.setName("check:" + label);
        checks.add(v, g);
    }

    /** Value of a check row, by its label prefix ("" when absent). */
    String checkValue(String labelPrefix) {
        for (Component c : checks.getComponents()) {
            if (c.getName() != null && c.getName().startsWith("check:" + labelPrefix)) {
                return ((JLabel) c).getText();
            }
        }
        return "";
    }

    private void setCheck(String labelPrefix, String value, Color tone) {
        for (Component c : checks.getComponents()) {
            if (c.getName() != null && c.getName().startsWith("check:" + labelPrefix)) {
                ((JLabel) c).setText(value);
                c.setForeground(tone);
            }
        }
    }

    private void finishChecks(String hardReason) {
        checkRowY = 0;
        String name = file == null ? "Apri CSV..." : "Apri CSV... " + file.getName();
        btnOpen.setText(AppTheme.fitTail(name, btnOpen.getFontMetrics(btnOpen.getFont()), 250));
        btnOpen.setToolTipText(file == null ? null : file.getAbsolutePath());
        checks.revalidate();
        refresh(hardReason);
    }

    // ── state → screen ───────────────────────────────────────────────────

    int toSend() {
        int n = 0;
        for (Job j : jobs) if (!j.sent && !j.skip) n++;
        return n + fixes.size();
    }

    int sentCount() {
        int n = 0;
        for (Job j : jobs) if (j.sent) n++;
        return n;
    }

    private Job firstPending() {
        for (Job j : jobs) if (!j.sent && !j.skip) return j;
        return null;
    }

    /** Text of the state banner (for the tests too). */
    String bannerText() {
        return AppTheme.bannerText(banner);
    }

    private void refresh(String hardReason) {
        int total = jobs.size();
        counter.set(sentCount(), total);
        btnStop.setEnabled(running);
        btnVerify.setEnabled(!running && !verifying && sentCount() > 0);
        btnOpen.setEnabled(!running);
        boolean blocked = hardBlock || (!needConfirm.isEmpty() && !confirmed) || conflicts > 0;
        btnOverride.setVisible(!needConfirm.isEmpty() && !confirmed && !hardBlock && !running);
        Job next = firstPending();
        boolean resume = sentCount() > 0 && next != null;
        // two views in one card, like the mockup's 07 and 08: the checks before
        // the first click, the progress once pairs went out. Never both — the
        // column has the height of the other cards, and both would clip.
        boolean progress = running || sentCount() > 0;
        checks.setVisible(!progress);
        counter.setVisible(progress);
        lblLast.setVisible(progress);
        if (!progress) lblLast.setText(" ");
        boolean closing = needsClosingCheck();
        btnStart.setText(running ? "REGISTRO..."
                       : closing ? "VERIFICA E CHIUDI"
                       : preflightRequired() && !preflightDone ? "PRE-VOLO E AVVIA"
                       : resume ? "Riprendi da " + next.row.n : "AVVIA");
        btnStart.setEnabled(!running && !verifying && read != null
                            && (closing || (!blocked && toSend() > 0)));
        if (running || verifying) return;
        if (read == null) {
            state(AppTheme.SURFACE2, AppTheme.TEXT, hardReason == null ? "Apri un CSV di Registro QR" : hardReason);
        } else if (hardBlock) {
            state(AppTheme.RED, AppTheme.ON_ACCENT, "BLOCCATO — " + (hardReason == null ? read.detail : hardReason));
        } else if (conflicts > 0) {
            state(AppTheme.RED, AppTheme.ON_ACCENT, "CONFLITTO su " + conflicts + " righe: decidi tu");
        } else if (!needConfirm.isEmpty() && !confirmed) {
            ImportLedger.FileState st = ledger().state(sha);
            state(AppTheme.RED, AppTheme.ON_ACCENT, st.completed
                ? "GIÀ REGISTRATO " + st.completedAt.substring(0, 16) : "BLOCCATO — serve una conferma");
        } else if (closing) {
            state(AppTheme.PEACH, AppTheme.ON_ACCENT, "TUTTO INVIATO — manca la verifica finale");
        } else if (toSend() == 0) {
            state(AppTheme.GREEN, AppTheme.ON_ACCENT, "Niente da inviare: tutto già sul portale");
        } else if (resume) {
            ImportLedger.FileState st = ledger().state(sha);
            state(AppTheme.PEACH, AppTheme.ON_ACCENT, "FERMATO — alla riga " + next.row.n
                + " (" + sentCount() + " / " + total + ")");
            lblLast.setText("riga " + next.row.n + " inviata? " + lastRowWord(st));
        } else {
            state(AppTheme.SURFACE2, AppTheme.TEXT, "PRONTO — " + toSend() + " da inviare · blocchi " + blockSize());
        }
    }

    private String lastRowWord(ImportLedger.FileState st) {
        String why = "";
        for (ImportLedger.Entry e : ledger().read()) {
            if (sha.equals(e.get("sha")) && ImportLedger.INTERROTTO.equals(e.event)) why = e.get("motivo");
        }
        if (why.contains("dopo INVIO")) return "sì (dopo INVIO)";
        if (why.isEmpty()) return st.started ? "da verificare (chiusura a metà)" : "no";
        return "no (prima di INVIO)";
    }

    private void state(Color bg, Color fg, String text) {
        banner.setBackground(bg);
        banner.setForeground(fg);
        banner.setText(text);
        banner.setToolTipText(text);
    }

    int blockSize() {
        return Math.max(1, cfg.getInt(SettingsManager.FILE_BLOCK_SIZE, 50));
    }

    private void showEmpty() {
        read = null;
        refresh(null);
    }

    // ── overrides ────────────────────────────────────────────────────────

    private void override() {
        StringBuilder sb = new StringBuilder("Registrare comunque questo file?\n\n");
        for (String s : needConfirm) sb.append("- ").append(s).append("\n\n");
        sb.append("Il pre-volo sul portale resta acceso.");
        if (confirmer.test(sb.toString())) {
            confirmed = true;
            refresh(null);
        }
    }

    private boolean askConfirm(String message) {
        JTextArea area = new JTextArea(message);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setColumns(42);
        area.setRows(Math.min(14, 3 + message.length() / 50));
        area.setFocusable(false);   // the keyboard stays on No: see SafeConfirm
        return SafeConfirm.ask(this, new JScrollPane(area), "Conferma");
    }

    // ── run ──────────────────────────────────────────────────────────────

    /** The lever: pre-flight first when required, then the blocks. */
    void start() {
        if (running || verifying || read == null) return;
        int cx = cfg.getInt(SettingsManager.SCAN_COORD_X, -1);
        int cy = cfg.getInt(SettingsManager.SCAN_COORD_Y, -1);
        if (!cfg.isSet(SettingsManager.SCAN_COORD_X, SettingsManager.SCAN_COORD_Y)) {
            error("Memorizza la coordinata della Scansione nelle Impostazioni");
            return;
        }
        int ex = cfg.getInt(SettingsManager.REG_EXPORT_COORD_X, -1);
        int ey = cfg.getInt(SettingsManager.REG_EXPORT_COORD_Y, -1);
        if (!cfg.isSet(SettingsManager.REG_EXPORT_COORD_X, SettingsManager.REG_EXPORT_COORD_Y)
                && exportClick == null) {
            error("Memorizza «Export CSV» nelle Impostazioni (tab Verifica): serve per pre-volo e verifica");
            return;
        }
        String blocked = ctx.blockingCollision(targets(cx, cy));
        if (blocked != null) { error(blocked); return; }
        if (!takeRobot()) return;

        running = true;
        stopRequested = false;
        refresh(null);
        ctx.jobStarted(this::stop);
        Thread worker = new Thread(this::work, "file-worker");
        worker.setDaemon(true);
        worker.start();
    }

    /** The robot gate for a run or a verification by hand (interface thread only). */
    private boolean takeRobot() {
        String other = ctx.busyElsewhere(this);
        if (other != null) { error("Robot occupato: " + other); return false; }
        robotLease = RobotGate.tryAcquire("Da file");
        if (robotLease == null) { error("Robot occupato: " + RobotGate.owner()); return false; }
        return true;
    }

    private void freeRobot() {
        if (robotLease != null) robotLease.release();
        robotLease = null;
    }

    private void error(String msg) {
        app.core.Sounds.error();
        state(AppTheme.RED, AppTheme.ON_ACCENT, msg);
    }

    void stop() {
        stopRequested = true;
        if (verifyTask != null) verifyTask.cancel();
    }

    private static Map<String, Point> targets(int cx, int cy) {
        Map<String, Point> t = new LinkedHashMap<>();
        t.put("Casella 1", new Point(cx, cy));
        return t;
    }

    /** The worker: pre-flight, then blocks of bursts, each closed by a verification. */
    private void work() {
        String stopWhy = null;
        try {
            if (preflightRequired() && !preflightDone) {
                String problem = preflight();
                if (problem != null) { stopWhy = problem; return; }
                if (conflicts > 0) { stopWhy = "CONFLITTO"; return; }
            }
            ImportLedger led = ledger();
            ImportLedger.archive(reportDir(), file, sha);
            led.start(sha, file.getName(), read.station(), read.date(), read.exportNumber(),
                      read.rows.size(), read.reexport());

            int cx = cfg.getInt(SettingsManager.SCAN_COORD_X, -1);
            int cy = cfg.getInt(SettingsManager.SCAN_COORD_Y, -1);
            PairBurst.Timing timing = new PairBurst.Timing(
                cfg.getInt(SettingsManager.SCAN_FOCUS, 300), cfg.getInt(SettingsManager.SCAN_KEY, 70),
                cfg.getInt(SettingsManager.SCAN_ENTER, 150));
            boolean guarded = cfg.getBool(SettingsManager.SCAN_FAILSAFE, true);
            int block = blockSize();
            int inBlock = 0;

            List<Job> todo = new ArrayList<>(fixes);
            for (Job j : jobs) if (!j.sent && !j.skip) todo.add(j);
            fixes.clear();
            for (Job j : todo) {
                if (stopRequested) { stopWhy = "STOP"; return; }
                // the window and the way back, asked again for every pair: once
                // the HUD takes over, the card is off screen and there is no
                // way back to click (the old point is now portal page)
                final String[] blocked = { null };
                SwingUtilities.invokeAndWait(() -> {
                    blocked[0] = ctx.blockingCollision(targets(cx, cy));
                    appPoint = btnStart.isShowing() ? btnStart.getLocationOnScreen() : null;
                });
                if (blocked[0] != null) { stopWhy = blocked[0]; return; }
                Point back = appPoint;
                final Job job = j;
                PairBurst.Outcome o = PairBurst.run(hands, new Point(cx, cy),
                    back == null ? null : new Point(back.x + 20, back.y + 10),
                    j.row.label, j.row.lot, timing, guarded, () -> {
                        try {
                            led.sent(sha, job.row.n, job.row.label, job.row.lot);
                        } catch (IOException e) {
                            System.err.println("[file] register: " + e.getMessage());
                        }
                    });
                if (o == PairBurst.Outcome.NOT_SENT) {
                    stopWhy = "mouse mosso prima di INVIO alla riga " + j.row.n;
                    return;
                }
                if (o == PairBurst.Outcome.FAILED) {
                    SwingUtilities.invokeLater(() -> results.markNotSent(job.tableRow, "Riga non inviata: " + job.row.label));
                    stopWhy = "robot non disponibile prima di INVIO alla riga " + j.row.n;
                    return;
                }
                j.sent = true;
                inBlock++;
                SwingUtilities.invokeLater(() -> {
                    results.markSent(job.tableRow);
                    counter.set(sentCount(), jobs.size());
                    int done = sentCount();
                    ctx.jobProgress(AppTheme.PEACH, "ROBOT AL LAVORO", done + " / " + jobs.size(),
                                    "registrate", jobs.isEmpty() ? 100 : done * 100 / jobs.size(), true);
                });
                if (o == PairBurst.Outcome.SENT_THEN_GRABBED) {
                    stopWhy = "mouse mosso dopo INVIO alla riga " + j.row.n;
                    return;
                }
                boolean lastOne = j == todo.get(todo.size() - 1);
                if (inBlock >= block || lastOne) {
                    inBlock = 0;
                    Boolean clean = verifyBlock();
                    if (clean == null) { stopWhy = "verifica non riuscita"; return; }
                    led.blockVerified(sha, sentCount(), clean);
                    if (!clean) { stopWhy = "VERIFICA ROSSA"; return; }
                }
            }
            led.completed(sha, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopWhy = "interrotto";
        } catch (Exception e) {
            stopWhy = "errore: " + e.getMessage();
        } finally {
            finish(stopWhy);
        }
    }

    private void finish(String why) {
        if (why != null && read != null && sha != null && ledger().state(sha).started) {
            try {
                ledger().interrupted(sha, why);
            } catch (IOException ignored) {
                // the register stays without the reason: the resume asks the portal
            }
        }
        SwingUtilities.invokeLater(() -> {
            running = false;
            freeRobot();
            ctx.jobFinished();
            refresh(null);
            if (why == null) {
                state(AppTheme.GREEN, AppTheme.ON_ACCENT, "FATTO — " + sentCount() + " / " + jobs.size() + " verificate");
            } else if ("CONFLITTO".equals(why)) {
                refresh(null);
            } else if (!"VERIFICA ROSSA".equals(why)) {
                app.core.Sounds.error();
                Job next = firstPending();
                state(AppTheme.PEACH, AppTheme.ON_ACCENT, "FERMATO — " + why
                    + (next == null ? "" : " (" + sentCount() + " / " + jobs.size() + ")"));
            } else {
                state(AppTheme.RED, AppTheme.ON_ACCENT, "VERIFICA ROSSA: controlla i risultati");
            }
        });
    }

    // ── portal: pre-flight and verification ──────────────────────────────

    private DownloadWatcher watcher(int timeoutMs) {
        File dir = app.config.AppFiles.downloadDir();
        return new DownloadWatcher(dir.toPath(), cfg.get(SettingsManager.REG_EXPORT_PREFIX, "demo-export"),
            timeoutMs, cfg.getInt(SettingsManager.REG_EXPORT_POLL_MS, 500),
            cfg.getInt(SettingsManager.REG_EXPORT_STABLE_MS, 800));
    }

    private Runnable exportClicker() {
        if (exportClick != null) return exportClick;
        final Point ec = new Point(cfg.getInt(SettingsManager.REG_EXPORT_COORD_X, -1),
                                   cfg.getInt(SettingsManager.REG_EXPORT_COORD_Y, -1));
        return () -> {
            try {
                RobotEngine.getInstance().click(ec.x, ec.y);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /** Off the EDT: export, read the portal's last lot per label, judge every row. null = done. */
    private String preflight() throws Exception {
        SwingUtilities.invokeLater(() -> {
            state(AppTheme.BLUE, AppTheme.ON_ACCENT, "PRE-VOLO: leggo il portale...");
            setCheck("pre-volo", "in corso", AppTheme.BLUE);
        });
        DownloadWatcher w = watcher(cfg.getInt(SettingsManager.REG_EXPORT_TIMEOUT_S, 30) * 1000);
        long t0 = System.currentTimeMillis();
        exportClicker().run();
        Path f = w.awaitNewExport(t0);
        if (f == null) return "pre-volo: export dal portale non arrivato";
        Map<String, String> last = new RegistrationVerifier().lastLots(DownloadWatcher.readLines(f));
        List<CsvImport.CsvRow> rows = new ArrayList<>();
        for (Job j : jobs) if (!j.sent && !j.skip) rows.add(j.row);
        List<Preflight.Item> items = Preflight.check(rows, last);
        final int[] counts = new int[3];
        SwingUtilities.invokeAndWait(() -> {
            for (Preflight.Item it : items) {
                Job j = jobOf(it.row.n);
                if (j == null) continue;
                switch (it.verdict) {
                    case GIA_PRESENTE:
                        j.skip = true;
                        j.word = "GIÀ PRESENTE";
                        counts[0]++;
                        break;
                    case CONFLITTO:
                        j.word = "CONFLITTO: trovato " + (it.found.isEmpty() ? "niente" : it.found);
                        counts[1]++;
                        break;
                    default:
                        counts[2]++;
                }
                results.setRowOutcome(j.tableRow, j.word);
            }
            conflicts = counts[1];
            preflightDone = true;
            setCheck("pre-volo", conflicts > 0 ? "CONFLITTO " + conflicts
                     : counts[0] + " già present" + (counts[0] == 1 ? "e" : "i"),
                     conflicts > 0 ? AppTheme.RED : AppTheme.GREEN);
        });
        return null;
    }

    private Job jobOf(int n) {
        for (Job j : jobs) if (j.row.n == n) return j;
        return null;
    }

    /** Every pair this file has on the portal so far: sent now, sent before, already there. */
    private Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Job j : jobs) {
            if (j.sent || "GIÀ PRESENTE".equals(j.word)) m.put(j.row.label, j.row.lot);
        }
        return m;
    }

    /** Runs a verification on THIS thread and waits for its outcome. null = no answer. */
    private Boolean verifyBlock() throws InterruptedException {
        final Map<String, String> snap = snapshot();
        final CountDownLatch done = new CountDownLatch(1);
        final Boolean[] clean = { null };
        RegistrationVerifier verifier = new RegistrationVerifier();
        verifying = true;
        try {
            SwingUtilities.invokeAndWait(() -> {
                // RIPROVA and ANNULLA are this file's while its check is on screen
                results.setActions(this::verifyNow, () -> {
                    VerificationTask t = verifyTask;
                    if (t != null) t.cancel();
                });
                results.setLotFixer(this::fixLot);
                results.setSessionTotal(snap.size());
                results.showVerifying();
                state(AppTheme.BLUE, AppTheme.ON_ACCENT, "VERIFICA dopo " + sentCount() + " inviate...");
                ctx.jobProgress(AppTheme.BLUE, "VERIFICA", "-", "in corso", -1, false);
            });
        } catch (java.lang.reflect.InvocationTargetException e) {
            verifying = false;
            return null;
        }
        verifyTask = new VerificationTask(exportClicker(),
            watcher(cfg.getInt(SettingsManager.REG_EXPORT_TIMEOUT_S, 30) * 1000),
            lines -> verifier.verify(lines, snap),
            cfg.getInt(SettingsManager.REG_VERIFY_RETRIES, 2),
            cfg.getInt(SettingsManager.REG_VERIFY_RETRY_S, 2) * 1000, false,
            new VerificationTask.Listener() {
                @Override public void onStatus(String m) { state(AppTheme.BLUE, AppTheme.ON_ACCENT, m); }
                @Override public void onOutcome(VerificationResult r, Path f, int attempts, boolean fresh) {
                    outcome(r, f.getFileName().toString(), attempts, fresh, snap.size());
                    clean[0] = r.isClean();
                    done.countDown();
                }
                @Override public void onFailure(String reason) {
                    results.showFailure(reason);
                    log.append("ERRORE · file · " + reason);
                    done.countDown();
                }
                @Override public void onCancelled() {
                    results.showCancelled();
                    done.countDown();
                }
            });
        verifyTask.run();
        done.await();
        verifying = false;
        return clean[0];
    }

    private void outcome(VerificationResult r, String fileName, int attempts, boolean fresh, int total) {
        results.showOutcome(r, fileName, attempts, fresh);
        results.offerReport(r);
        results.setLotFixer(this::fixLot);
        // a verification touches only the rows it covers; the others keep their word
        java.util.Set<String> covered = new java.util.HashSet<>(r.getMissing());
        covered.addAll(r.getNotRegistered());
        covered.addAll(r.getWrongLots().keySet());
        covered.addAll(r.getRegistrationCounts().keySet());
        for (Job j : jobs) {
            if (!covered.contains(j.row.label)) results.setRowOutcome(j.tableRow, j.sent ? "INVIATA" : j.word);
        }
        log.append(VerificationLog.formatEntry(r, fileName, attempts, total, "file " + file.getName(), fresh));
        ctx.jobProgress(r.isClean() ? AppTheme.GREEN : AppTheme.RED,
                        r.isClean() ? "TUTTO OK" : r.totalProblems() + " PROBLEMI",
                        r.getMatched() + " / " + total, "verificate", 100, false);
    }

    /**
     * Every row went out, but the file was never completed: the run stopped
     * after the last ENTER, or its last check timed out or came back red.
     * Nothing is left to send, so the lever becomes the closing check.
     */
    boolean needsClosingCheck() {
        if (read == null || sha == null || toSend() > 0 || sentCount() == 0) return false;
        ImportLedger.FileState st = ledger().state(sha);
        return st.started && !st.completed;
    }

    /** Verifica, by hand: everything sent from this file so far. A clean check
     *  with nothing left to send completes the file. */
    private void verifyNow() {
        if (running || verifying || sentCount() == 0) return;
        if (!takeRobot()) return;
        running = true;
        refresh(null);
        Thread t = new Thread(() -> {
            String why = null;
            try {
                Boolean clean = verifyBlock();
                if (clean == null) why = "verifica non riuscita";
                else if (!clean) why = "VERIFICA ROSSA";
                else if (needsClosingCheck()) {
                    ledger().blockVerified(sha, sentCount(), true);
                    ledger().completed(sha, true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                why = "interrotto";
            } catch (IOException e) {
                why = "registro non scrivibile: " + e.getMessage();
            }
            final String w = why;
            SwingUtilities.invokeLater(() -> {
                running = false;
                freeRobot();
                refresh(null);
                if (w != null) state(AppTheme.RED, AppTheme.ON_ACCENT, w);
                else if (sha != null && ledger().state(sha).completed && toSend() == 0) {
                    state(AppTheme.GREEN, AppTheme.ON_ACCENT, "FATTO — " + sentCount() + " / " + jobs.size() + " verificate");
                }
            });
        }, "file-verify");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Double click on a lot: register that row again. A file's rows are
     * corrected in Registro QR, never here: another lot is refused (the next
     * export of that line carries the correction, and every check of this file
     * expects the file's lot). The same lot on a row already sent goes out
     * once more; on a row not sent yet there is nothing to add, it is still
     * on its way.
     */
    private boolean fixLot(int row, String code, String oldLot, String newLot) {
        for (Job j : jobs) {
            if (j.tableRow != row) continue;
            if (!j.row.lot.equals(newLot)) {
                error("Le righe di un file si correggono in Registro QR, poi si importa il nuovo export");
                return false;
            }
            if (!j.sent) {
                results.setRowOutcome(row, j.word == null ? "IN CODA" : j.word);
                state(AppTheme.PEACH, AppTheme.ON_ACCENT, "Riga " + j.row.n + " ancora da inviare: parte con AVVIA");
                return true;
            }
            for (Job f : fixes) if (f.tableRow == row) return true;   // already waiting
            Job fix = new Job(j.row);
            fix.tableRow = row;
            fixes.add(fix);
            refresh(null);
            state(AppTheme.PEACH, AppTheme.ON_ACCENT, "Reinvio in coda — premi AVVIA");
            return true;
        }
        return false;
    }

    // ── restart ──────────────────────────────────────────────────────────

    /**
     * At startup: a file left interrupted (STOP, fail-safe, the app closed
     * mid-run) is loaded from AutoFillSuite's own copy, ready to resume.
     * true when one was found.
     */
    public boolean restoreInterrupted() {
        Map<String, ImportLedger.FileState> states = ledger().states();
        ImportLedger.FileState last = null;
        String lastSha = null;
        for (Map.Entry<String, ImportLedger.FileState> e : states.entrySet()) {
            if (e.getValue().interrupted()) { last = e.getValue(); lastSha = e.getKey(); }
        }
        if (last == null) return false;
        // by the fingerprint of the content, like the archive itself: the
        // file may have been imported from the archived copy, or renamed
        File copy = ImportLedger.findArchived(reportDir(), lastSha);
        if (copy == null) return false;
        open(copy);
        return true;
    }

    boolean isRunning() {
        return running;
    }
}
