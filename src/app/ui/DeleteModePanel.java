package app.ui;

import app.config.SettingsManager;
import app.core.*;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mode: CANCELLA — labels spoiled or thrown away, deleted from the portal.
 * Scan (or type) a label, ENTER: it joins the list and the field clears; with
 * a Quantità above 1 the label starts a range, like Intervallo (the last three
 * digits move). The big red lever deletes the whole list, after a countdown;
 * a setting deletes at every ENTER instead.
 *
 * The portal deletes when ENTER is pressed in its Delete field (it empties the
 * field too), no question asked, and the label disappears from its CSV export:
 * the check at the end asks exactly that. A
 * deleted label leaves the day's count, and fills its place in the numbering
 * (it is not a hole any more).
 *
 * The list lives in its own file, written at every change: a closed app
 * finds it again. The robot uses the same safeties as everywhere: the mouse
 * is checked between every step, and DELETE is the point of no return.
 */
public class DeleteModePanel extends JPanel {

    private static final String NAME = "Cancella";   // the robot gate's name for this mode

    private final SettingsManager cfg = SettingsManager.getInstance();
    private final ResultsPanel results;
    private final RunContext ctx;

    /** The robot's hands; a test puts fake ones here. */
    PairBurst.Hands hands = RobotEngine.getInstance();
    /** The export click of the check; a test writes the portal's CSV instead. */
    Runnable exportClick = null;
    /** Answers the questions (a label registered today, an empty list...); a test answers. */
    java.util.function.Predicate<String> confirmer = m -> SafeConfirm.ask(this, m, "Cancella dal portale");

    // the list, in the order it was scanned (EDT only)
    private final DefaultListModel<String> list = new DefaultListModel<>();
    // labels deleted in the CURRENT results session -> their row there (EDT only)
    private final Map<String, Integer> deletedRows = new LinkedHashMap<>();
    private int deletedCount = 0;

    private volatile boolean running = false;
    private volatile boolean verifying = false;
    private volatile boolean stopRequested = false;
    private RobotGate.Lease lease;

    private JTextField tfCode;
    private JSpinner spCount;
    private JList<String> jlist;
    private JLabel banner;
    private StatPair stats;
    private JButton btnStart, btnVerify, btnRemove, btnClear, btnClearField;
    private JProgressBar status;

    public DeleteModePanel(ResultsPanel results, RunContext ctx) {
        this.results = results;
        this.ctx = ctx;
        setLayout(new BorderLayout());
        setBackground(AppTheme.BASE);
        setBorder(BorderFactory.createEmptyBorder(0, 9, 8, 9));
        add(buildCard(), BorderLayout.CENTER);
        loadList();
        refresh();
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private JPanel buildCard() {
        JPanel card = AppTheme.card();
        card.setLayout(new GridBagLayout());
        card.setBorder(BorderFactory.createEmptyBorder(6, 10, 5, 10));
        GridBagConstraints g = AppTheme.gbc();
        g.insets = new Insets(1, 3, 1, 3);
        int y = 0;

        g.gridx = 0; g.gridy = y; g.weightx = 0;
        card.add(AppTheme.label("Etichetta"), g);
        JPanel r1 = new JPanel(new BorderLayout(6, 0));
        r1.setOpaque(false);
        tfCode = AppTheme.fieldQr();
        tfCode.setToolTipText("Spara l'etichetta rovinata: INVIO la mette in lista");
        btnClearField = AppTheme.iconButton(Icons.cross(AppTheme.ICON, AppTheme.SUBTEXT), "Svuota il campo");
        r1.add(tfCode, BorderLayout.CENTER);
        r1.add(btnClearField, BorderLayout.EAST);
        g.gridx = 1; g.weightx = 1.0;
        card.add(r1, g);

        // quantità: the label is the first of a range, like Intervallo
        y++; g.gridx = 0; g.gridy = y; g.weightx = 0;
        card.add(AppTheme.label("Quantità"), g);
        spCount = AppTheme.spinnerInt(1, 1, 999);
        spCount.setToolTipText("Quante etichette consecutive da cancellare, a partire da quella sparata"
                               + " (torna a 1 dopo ogni INVIO)");
        spCount.addChangeListener(e -> preview());
        g.gridx = 1; g.weightx = 1.0;
        card.add(spCount, g);

        g.gridx = 0; g.gridwidth = 2;
        y++; g.gridy = y;
        g.insets = new Insets(3, 3, 2, 3);
        banner = AppTheme.banner();
        card.add(banner, g);
        g.insets = new Insets(2, 3, 2, 3);

        y++; g.gridy = y;
        jlist = new JList<>(list);
        jlist.setFont(AppTheme.F_MONO);
        jlist.setBackground(AppTheme.MANTLE);
        jlist.setForeground(AppTheme.TEXT);
        jlist.setSelectionBackground(AppTheme.SURFACE1);
        jlist.setSelectionForeground(AppTheme.TEXT);
        jlist.setVisibleRowCount(4);
        // an empty list asks for no height at all: a fixed row keeps the box
        // four rows tall, so the operator sees where the labels will go
        jlist.setFixedCellHeight(18);
        jlist.setFocusable(false);
        jlist.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) refresh(); });
        JScrollPane sc = new JScrollPane(jlist);
        sc.setBorder(BorderFactory.createLineBorder(AppTheme.SURFACE1));
        AppTheme.styleScroll(sc);
        card.add(sc, g);

        y++; g.gridy = y;
        stats = new StatPair("in lista", "cancellate");
        card.add(stats, g);

        y++; g.gridy = y;
        btnStart = AppTheme.primary("CANCELLA DAL PORTALE", AppTheme.RED,
            Icons.cross(AppTheme.ICON, AppTheme.ON_ACCENT));
        btnStart.setFocusable(false);
        card.add(btnStart, g);

        y++; g.gridy = y;
        JPanel sec = new JPanel(new GridLayout(1, 3, 6, 0));
        sec.setOpaque(false);
        btnRemove = AppTheme.secondary("Togli", null);
        btnRemove.setToolTipText("Togli dalla lista l'etichetta selezionata (non tocca il portale)");
        btnClear  = AppTheme.secondary("Svuota", null);
        btnClear.setToolTipText("Svuota la lista (non tocca il portale)");
        btnVerify = AppTheme.secondary("Verifica", Icons.search(AppTheme.ICON, AppTheme.SUBTEXT));
        btnVerify.setToolTipText("Controlla sull'export del portale che le cancellate non ci siano più");
        for (JButton b : new JButton[] { btnRemove, btnClear, btnVerify }) b.setFocusable(false);
        sec.add(btnRemove);
        sec.add(btnClear);
        sec.add(btnVerify);
        card.add(sec, g);

        y++; g.gridy = y;
        status = AppTheme.progressStatus();
        status.setString("Spara un'etichetta da cancellare.");
        card.add(status, g);

        tfCode.addActionListener(e -> accept());
        ScanTabSupport.markSubmitOnTab(tfCode);
        btnClearField.addActionListener(e -> { tfCode.setText(""); tfCode.requestFocusInWindow(); });
        btnStart.addActionListener(e -> {
            if (running) stop(); else start();
        });
        btnRemove.addActionListener(e -> removeSelected());
        btnClear.addActionListener(e -> clearList());
        btnVerify.addActionListener(e -> verifyByHand());
        state(AppTheme.SURFACE2, AppTheme.TEXT, "PRONTO — spara un'etichetta");
        return card;
    }

    // ── the list ──────────────────────────────────────────────────────────

    /** ENTER on the field: the label (or its range) joins the list; in immediate mode, a single one goes. */
    void accept() {
        String code = tfCode.getText().trim();
        if (code.isEmpty()) return;
        String problem = shapeProblem(code);
        if (problem != null) {
            Sounds.error();
            state(AppTheme.RED, AppTheme.ON_ACCENT, problem);
            return;
        }
        try { spCount.commitEdit(); } catch (java.text.ParseException ignored) { }
        int count = (Integer) spCount.getValue();
        List<String> codes;
        try {
            codes = range(code, count);
        } catch (IllegalArgumentException past999) {
            Sounds.error();
            state(AppTheme.RED, AppTheme.ON_ACCENT, "L'intervallo supera 999: dividilo in due");
            return;
        }
        List<String> fresh = new ArrayList<>();
        for (String c : codes) if (!list.contains(c)) fresh.add(c);
        if (fresh.isEmpty()) {
            Sounds.error();
            state(AppTheme.YELLOW, AppTheme.ON_ACCENT, "Già in lista: " + code);
            tfCode.setText("");
            return;
        }
        // a label registered today with a lot is a GOOD piece on the portal:
        // deleting it is the expensive mistake, so this is the one question
        Map<String, String[]> today = results.registeredToday();
        List<String> good = new ArrayList<>();
        for (String c : fresh) {
            String[] reg = today.get(c);
            if (reg != null) good.add(c + "  (alle " + reg[1] + " con " + reg[0] + ")");
        }
        if (!good.isEmpty()) {
            StringBuilder m = new StringBuilder();
            if (good.size() == 1 && fresh.size() == 1) {
                String[] reg = today.get(fresh.get(0));
                m.append(fresh.get(0)).append(" è registrata oggi alle ").append(reg[1])
                 .append(" con il lotto ").append(reg[0]).append(".\n\n");
            } else {
                m.append(good.size()).append(good.size() == 1 ? " etichetta è registrata" : " etichette sono registrate")
                 .append(" oggi con un lotto:\n\n");
                for (int i = 0; i < good.size() && i < 10; i++) m.append(good.get(i)).append('\n');
                if (good.size() > 10) m.append("... e altre ").append(good.size() - 10).append('\n');
                m.append('\n');
            }
            m.append("È davvero rovinata? Verrà CANCELLATA dal portale.");
            if (!confirmer.test(m.toString())) {
                state(AppTheme.YELLOW, AppTheme.ON_ACCENT, "Non messa in lista: " + code);
                tfCode.setText("");
                return;
            }
        }
        for (String c : fresh) list.addElement(c);
        tfCode.setText("");
        spCount.setValue(1);              // never leave a range armed for the next scan
        tfCode.requestFocusInWindow();
        saveList();
        refresh();
        state(AppTheme.SURFACE2, AppTheme.TEXT, fresh.size() == 1 ? "IN LISTA: " + fresh.get(0)
              : "IN LISTA: " + fresh.size() + " etichette, " + RangeModePanel.rangePreview(
                    fresh.get(0), fresh.get(fresh.size() - 1)).replaceFirst("^Da ", "da "));
        // a range waits for the lever even in immediate mode: one look first
        if (fresh.size() == 1 && cfg.getBool(SettingsManager.DEL_IMMEDIATE, false)
                && !running && !verifying) start();
    }

    /** The label and the ones after it: the last three digits move, as in Intervallo. */
    static List<String> range(String first, int count) {
        if (count <= 1) {
            List<String> one = new ArrayList<>();
            one.add(first);
            return one;
        }
        String prefix = first.substring(0, first.length() - 3);
        long seq = Long.parseLong(first.substring(first.length() - 3));
        return RegistrationVerifier.expectedCodes(prefix, seq, count);
    }

    /** The range in the status bar while the operator sets the quantity. */
    private void preview() {
        String code = tfCode.getText().trim();
        int count = (Integer) spCount.getValue();
        if (code.isEmpty() || count <= 1 || shapeProblem(code) != null) return;
        try {
            List<String> r = range(code, count);
            status.setString(count + " da cancellare: " + RangeModePanel.rangePreview(r.get(0), r.get(r.size() - 1)));
        } catch (IllegalArgumentException past999) {
            status.setString("L'intervallo supera 999");
        }
    }

    /** Labels from elsewhere (a hole of the day): into the list, checked like a scan. */
    public void addAll(List<String> codes) {
        int added = 0;
        for (String c : codes) {
            String code = c == null ? "" : c.trim();
            if (code.isEmpty() || list.contains(code) || shapeProblem(code) != null) continue;
            list.addElement(code);
            added++;
        }
        saveList();
        refresh();
        state(AppTheme.SURFACE2, AppTheme.TEXT, added + (added == 1 ? " etichetta" : " etichette")
              + " in lista: premi CANCELLA DAL PORTALE");
    }

    /** A lot in the label's place, or a code that is no label: refused, said plainly. */
    private String shapeProblem(String code) {
        String p1 = cfg.get(SettingsManager.SCAN_QR1_PATTERN, "");
        String p2 = cfg.get(SettingsManager.SCAN_QR2_PATTERN, "");
        if (ScanGuard.inspect(code, "", p1, "") == ScanGuard.MISMATCH) {
            if (!p2.trim().isEmpty() && ScanGuard.inspect("", code, "", p2) == ScanGuard.OK) {
                return "Questo è un LOTTO: spara l'etichetta";
            }
            return "Non sembra un'etichetta: " + code;
        }
        int i = code.length();
        while (i > 0 && Character.isDigit(code.charAt(i - 1))) i--;
        if (code.length() - i < 3) return "Un'etichetta finisce con le cifre: " + code;
        return null;
    }

    private void removeSelected() {
        if (running) return;
        int i = jlist.getSelectedIndex();
        if (i < 0) { state(AppTheme.YELLOW, AppTheme.ON_ACCENT, "Seleziona un'etichetta della lista"); return; }
        String code = list.get(i);
        list.remove(i);
        saveList();
        refresh();
        state(AppTheme.SURFACE2, AppTheme.TEXT, "Tolta dalla lista: " + code);
    }

    private void clearList() {
        if (running || list.isEmpty()) return;
        if (!confirmer.test("Svuotare la lista (" + list.size() + " etichette)?\n\n"
                + "Il portale non viene toccato.")) return;
        list.clear();
        saveList();
        refresh();
        state(AppTheme.SURFACE2, AppTheme.TEXT, "Lista svuotata");
    }

    private void saveList() {
        StringBuilder sb = new StringBuilder("# AutoFillSuite - etichette da cancellare dal portale\n");
        for (int i = 0; i < list.size(); i++) sb.append(list.get(i)).append('\n');
        try {
            File f = app.config.AppFiles.deleteList();
            if (list.isEmpty() && !f.exists()) return;
            AtomicFile.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            status.setString("Lista non salvata su disco: " + ex.getMessage());
        }
    }

    private void loadList() {
        File f = app.config.AppFiles.deleteList();
        if (!f.isFile()) return;
        try {
            for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String c = l.trim();
                if (c.isEmpty() || c.startsWith("#") || list.contains(c)) continue;
                list.addElement(c);
            }
        } catch (IOException ex) {
            status.setString("Lista non leggibile: " + ex.getMessage());
            return;
        }
        if (!list.isEmpty()) {
            status.setString("Lista ritrovata: " + list.size() + " da cancellare");
        }
    }

    // ── the robot ─────────────────────────────────────────────────────────

    /** The lever: countdown, then every label of the list, then the check. */
    void start() {
        if (running || verifying) return;
        if (list.isEmpty()) { warn("La lista è vuota: spara prima le etichette da cancellare"); return; }
        boolean byClick = cfg.getBool(SettingsManager.DEL_BY_CLICK, false);
        if (!cfg.isSet(SettingsManager.DEL_COORD_X, SettingsManager.DEL_COORD_Y)
                || (byClick && !cfg.isSet(SettingsManager.DEL_BUTTON_X, SettingsManager.DEL_BUTTON_Y))) {
            warn(byClick ? "Memorizza «Casella Cancella» e «Tasto DELETE» nelle Impostazioni (tab Cancella)"
                         : "Memorizza «Casella Cancella» nelle Impostazioni (tab Cancella)");
            ctx.openSettings("Cancella");
            return;
        }
        final Point field = new Point(cfg.getInt(SettingsManager.DEL_COORD_X, -1),
                                      cfg.getInt(SettingsManager.DEL_COORD_Y, -1));
        // null: ENTER in the field deletes (the portal empties it too)
        final Point button = !byClick ? null : new Point(cfg.getInt(SettingsManager.DEL_BUTTON_X, -1),
                                                         cfg.getInt(SettingsManager.DEL_BUTTON_Y, -1));
        String blocked = ctx.blockingCollision(targets(field, button));
        if (blocked != null) { warn(blocked); return; }
        if (!takeRobot()) return;

        running = true;
        stopRequested = false;
        if (!results.isDeleteSession()) {
            results.beginDeleteSession();
            deletedRows.clear();
        }
        refresh();
        ctx.jobStarted(this::stop);
        Thread worker = new Thread(() -> work(field, button), "delete-worker");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        stopRequested = true;
    }

    private void work(Point field, Point button) {
        String stopWhy = null;
        int doneHere = 0;
        try {
            int secs = Math.max(0, Math.min(10, cfg.getInt(SettingsManager.DEL_WAIT, 3)));
            for (int left = secs; left > 0; left--) {
                if (stopRequested) { stopWhy = "STOP"; return; }
                final int n = left;
                SwingUtilities.invokeLater(() -> {
                    state(AppTheme.PEACH, AppTheme.ON_ACCENT, "PARTO TRA " + n + "s — togli la mano dal mouse");
                    btnStart.setText("ANNULLA (" + n + ")");
                    ctx.jobProgress(AppTheme.PEACH, "PARTO TRA " + n + "s", "0 / " + list.size(),
                                    "togli la mano dal mouse", 0, true);
                });
                hands.sleep(1000);
            }
            DeleteBurst.Timing t = new DeleteBurst.Timing(
                cfg.getInt(SettingsManager.DEL_FOCUS, 300), cfg.getInt(SettingsManager.SCAN_KEY, 70),
                cfg.getInt(SettingsManager.DEL_AFTER, 600));
            int pause = cfg.getInt(SettingsManager.DEL_PAUSE, 300);
            boolean guarded = cfg.getBool(SettingsManager.SCAN_FAILSAFE, true);
            boolean first = true;
            for (;;) {
                if (stopRequested) { stopWhy = "STOP"; return; }
                final String[] next = { null };
                final String[] hit = { null };
                SwingUtilities.invokeAndWait(() -> {
                    next[0] = list.isEmpty() ? null : list.get(0);
                    hit[0] = ctx.blockingCollision(targets(field, button));
                });
                if (next[0] == null) break;
                if (hit[0] != null) { stopWhy = hit[0]; return; }
                if (!first) hands.sleep(pause);
                first = false;
                final String code = next[0];
                SwingUtilities.invokeLater(() -> state(AppTheme.RED, AppTheme.ON_ACCENT,
                    "CANCELLO " + code + " — non toccare"));
                DeleteBurst.Outcome o = DeleteBurst.run(hands, field, button, code, t, guarded, null);
                if (o == DeleteBurst.Outcome.NOT_DELETED) {
                    stopWhy = "mouse mosso prima di INVIO: " + code + " NON cancellata";
                    return;
                }
                if (o == DeleteBurst.Outcome.FAILED) {
                    stopWhy = "robot non disponibile: " + code + " NON cancellata";
                    return;
                }
                doneHere++;
                SwingUtilities.invokeAndWait(() -> deleted(code));
                if (o == DeleteBurst.Outcome.DELETED_THEN_GRABBED) {
                    stopWhy = "mouse mosso dopo INVIO: " + code + " cancellata, robot fermo";
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopWhy = "interrotto";
        } catch (Exception e) {
            stopWhy = "errore: " + e.getMessage();
        } finally {
            final String why = stopWhy;
            final int done = doneHere;
            final boolean check = done > 0 && why == null
                && cfg.getBool(SettingsManager.DEL_VERIFY_AUTO, true) && exportReady();
            if (check) {
                // the check keeps the robot and the HUD, and says the last word
                runCheck(false);
                SwingUtilities.invokeLater(() -> {
                    running = false;
                    refresh();
                });
            } else {
                SwingUtilities.invokeLater(() -> {
                    running = false;
                    freeRobot();
                    refresh();
                    if (why == null) {
                        state(AppTheme.GREEN, AppTheme.ON_ACCENT, "FATTO — " + deletedCount + " cancellate");
                        Sounds.ok();
                    } else if ("STOP".equals(why) && done == 0) {
                        state(AppTheme.YELLOW, AppTheme.ON_ACCENT, "ANNULLATO — la lista è com'era");
                    } else {
                        Sounds.error();
                        state(AppTheme.PEACH, AppTheme.ON_ACCENT, "FERMATO — " + why);
                        if (!"STOP".equals(why)) results.showFailSafe(why);
                    }
                    ctx.jobFinished();
                    ctx.focusHome(tfCode);
                });
            }
        }
    }

    /** DELETE was clicked on this label: off the list, into the day as deleted (EDT). */
    private void deleted(String code) {
        list.removeElement(code);
        saveList();
        if (!results.isDeleteSession()) { results.beginDeleteSession(); deletedRows.clear(); }
        int row = results.addDeleteRow(code);
        results.markDeleted(row);
        deletedRows.put(code, row);
        deletedCount++;
        results.deleteCounters(list.size(), deletedCount);
        refresh();
        int total = deletedCount + list.size();
        ctx.jobProgress(AppTheme.PEACH, "CANCELLO", deletedCount + " / " + total, "cancellate",
                        total == 0 ? 100 : deletedCount * 100 / total, true);
    }

    // ── the check: a deleted label is gone from the export ────────────────

    private boolean exportReady() {
        return exportClick != null
            || cfg.isSet(SettingsManager.REG_EXPORT_COORD_X, SettingsManager.REG_EXPORT_COORD_Y);
    }

    private void verifyByHand() {
        if (running || verifying) return;
        if (deletedRows.isEmpty() || !results.isDeleteSession()) {
            warn("Nessuna cancellazione di questa sessione da controllare");
            return;
        }
        if (!exportReady()) {
            warn("Memorizza «Export CSV» nelle Impostazioni (tab Verifica)");
            ctx.openSettings("Verifica");
            return;
        }
        if (!takeRobot()) return;
        ctx.jobStarted(() -> { });
        Thread t = new Thread(() -> runCheck(true), "delete-check");
        t.setDaemon(true);
        t.start();
    }

    /** Off the EDT, holding the robot: export, then every deleted label must be absent. */
    private void runCheck(boolean byHand) {
        verifying = true;
        final Map<String, Integer> rows = new LinkedHashMap<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                rows.putAll(deletedRows);
                refresh();
                results.showDeleteChecking();
                results.setActions(this::verifyByHand, () -> { });
                state(AppTheme.BLUE, AppTheme.ON_ACCENT, "CONTROLLO SUL PORTALE...");
                ctx.jobProgress(AppTheme.BLUE, "CONTROLLO", "-", "sul portale", -1, false);
            });
        } catch (Exception e) {
            verifying = false;
            return;
        }
        String failure = null;
        Set<String> still = new LinkedHashSet<>();
        String fileName = "-";
        int attempts = 0;
        try {
            int retries = byHand ? 0 : cfg.getInt(SettingsManager.REG_VERIFY_RETRIES, 2);
            int retryMs = cfg.getInt(SettingsManager.REG_VERIFY_RETRY_S, 2) * 1000;
            int timeoutMs = (byHand ? 8 : cfg.getInt(SettingsManager.REG_EXPORT_TIMEOUT_S, 30)) * 1000;
            for (;;) {
                attempts++;
                DownloadWatcher w = new DownloadWatcher(app.config.AppFiles.downloadDir().toPath(),
                    cfg.get(SettingsManager.REG_EXPORT_PREFIX, "demo-export"), timeoutMs,
                    cfg.getInt(SettingsManager.REG_EXPORT_POLL_MS, 500),
                    cfg.getInt(SettingsManager.REG_EXPORT_STABLE_MS, 800));
                long t0 = System.currentTimeMillis();
                clickExport();
                Path f = w.awaitNewExport(t0);
                if (f == null) { failure = "export dal portale non arrivato"; break; }
                fileName = f.getFileName().toString();
                Set<String> present = new RegistrationVerifier().presentCodes(DownloadWatcher.readLines(f));
                still.clear();
                for (String c : rows.keySet()) if (present.contains(c)) still.add(c);
                // a slow server may still list a label it is deleting: ask again
                if (still.isEmpty() || attempts > retries) break;
                Thread.sleep(retryMs);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            failure = "interrotto";
        } catch (IOException ex) {
            failure = ex.getMessage();
        }
        final String why = failure;
        final String fn = fileName;
        final int att = attempts;
        SwingUtilities.invokeLater(() -> {
            verifying = false;
            freeRobot();
            if (why != null) {
                results.showDeleteFailure(why);
                state(AppTheme.RED, AppTheme.ON_ACCENT, "CONTROLLO NON RIUSCITO — " + why);
            } else {
                Map<Integer, Boolean> checked = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> e : rows.entrySet()) {
                    checked.put(e.getValue(), !still.contains(e.getKey()));
                }
                results.showDeleteOutcome(checked, still, fn, att);
                if (still.isEmpty()) {
                    state(AppTheme.GREEN, AppTheme.ON_ACCENT, "CANCELLATE E CONTROLLATE — " + rows.size());
                } else {
                    Sounds.error();
                    state(AppTheme.RED, AppTheme.ON_ACCENT, still.size() + " ANCORA SUL PORTALE: rimettile in lista");
                }
            }
            refresh();
            ctx.jobFinished();
            ctx.focusHome(tfCode);
        });
    }

    private void clickExport() {
        if (exportClick != null) { exportClick.run(); return; }
        try {
            RobotEngine.getInstance().click(cfg.getInt(SettingsManager.REG_EXPORT_COORD_X, -1),
                                            cfg.getInt(SettingsManager.REG_EXPORT_COORD_Y, -1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private boolean takeRobot() {
        String other = ctx.busyElsewhere(this);
        if (other != null) { warn("Robot occupato: " + other); return false; }
        lease = RobotGate.tryAcquire(NAME);
        if (lease == null) { warn("Robot occupato: " + RobotGate.owner()); return false; }
        return true;
    }

    private void freeRobot() {
        if (lease != null) lease.release();
        lease = null;
    }

    private static Map<String, Point> targets(Point field, Point button) {
        Map<String, Point> t = new LinkedHashMap<>();
        t.put("Casella Cancella", field);
        if (button != null) t.put("Tasto DELETE", button);
        return t;
    }

    /** One place decides what is clickable and what the lever says. */
    private void refresh() {
        stats.set(list.size(), deletedCount);
        boolean busy = running || verifying;
        if (running) {
            btnStart.setText("STOP");
            btnStart.setIcon(Icons.stop(AppTheme.ICON, AppTheme.ON_ACCENT));
            btnStart.setEnabled(true);
        } else {
            btnStart.setText("CANCELLA DAL PORTALE (" + list.size() + ")");
            btnStart.setIcon(Icons.cross(AppTheme.ICON, AppTheme.ON_ACCENT));
            btnStart.setEnabled(!verifying && !list.isEmpty());
        }
        btnRemove.setEnabled(!busy && jlist.getSelectedIndex() >= 0);
        btnClear.setEnabled(!busy && !list.isEmpty());
        spCount.setEnabled(!verifying);
        btnVerify.setEnabled(!busy && !deletedRows.isEmpty());
        tfCode.setEnabled(!verifying);
    }

    private void state(Color bg, Color fg, String text) {
        banner.setBackground(bg);
        banner.setForeground(fg);
        banner.setText(text);
    }

    private void warn(String msg) {
        Sounds.error();
        status.setString(msg);
        state(AppTheme.YELLOW, AppTheme.ON_ACCENT, msg);
    }

    /** The robot or a check is at work. */
    boolean isBusy() {
        return running || verifying;
    }

    /** What the banner says now (the tests read it). */
    String bannerText() {
        return AppTheme.bannerText(banner);
    }

    /** The list as it stands (the tests read it). */
    List<String> listed() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) out.add(list.get(i));
        return out;
    }

    /** The quantity (the tests set it). */
    JSpinner count() {
        return spCount;
    }

    /** The scan field, for focusHome when the tab is shown. */
    JTextField field() {
        return tfCode;
    }
}
