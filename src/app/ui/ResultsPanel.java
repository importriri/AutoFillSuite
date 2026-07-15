package app.ui;

import app.config.SettingsManager;
import app.core.RunReport;
import app.core.VerificationResult;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * The right half of the cockpit: results always in sight, never a window to
 * chase. A full-color banner states what is happening, every row carries a
 * state spine and a tint, and only the buttons that make sense right now show.
 *
 * One panel serves every mode: the active mode installs its retry/cancel
 * actions before starting a verification.
 */
public class ResultsPanel extends JPanel {

    private static final int SPINE = 3;
    private static final int RESULTS_MIN_W = 380;

    private final SettingsManager cfg = SettingsManager.getInstance();
    private final RunTableModel model = new RunTableModel();

    // daily file, one section per run; the start time is the section's identity
    private String runStamp = newStamp();
    private String runDate  = today();
    private String runMode  = "-";
    private String runLot   = "-";
    private boolean reportWritten = false;
    private String lastFile = "-";
    private int lastAttempts = 0;
    private boolean lastFresh = true;

    /** Installed by the mode panel: re-registers ONE label with a new lot. */
    public interface LotFixer { void fix(int row, String code, String newLot); }
    private LotFixer lotFixer;
    public void setLotFixer(LotFixer f) { lotFixer = f; }
    private final JTable table = new JTable(model);
    private final JLabel banner    = AppTheme.banner();
    private final JLabel lblHint   = new JLabel("", SwingConstants.CENTER);
    private final JLabel lblFooter = new JLabel("", SwingConstants.CENTER);
    private final JButton btnRetry  = AppTheme.primary("RIPROVA", AppTheme.BLUE,
        Icons.retry(AppTheme.ICON, AppTheme.ON_ACCENT));
    private final JButton btnCancel = AppTheme.primary("ANNULLA", AppTheme.RED,
        Icons.stop(AppTheme.ICON, AppTheme.ON_ACCENT));
    private final JButton btnReport = AppTheme.secondary("Report CSV", null);
    private final JButton btnPending = AppTheme.primary("VERIFICA ORA", AppTheme.PEACH,
        Icons.search(AppTheme.ICON, AppTheme.ON_ACCENT));
    private final JButton btnPendingNo = AppTheme.secondary("Ignora", null);

    private Runnable onRetry  = () -> {};
    private Runnable onCancel = () -> {};
    private int total = 0;

    public ResultsPanel(Runnable onClose) {
        setLayout(new BorderLayout());
        setBackground(AppTheme.MANTLE);
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, AppTheme.SURFACE1));

        add(buildHead(onClose), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(buildFoot(), BorderLayout.SOUTH);

        btnRetry.addActionListener(e -> onRetry.run());
        btnCancel.addActionListener(e -> onCancel.run());
        btnRetry.setVisible(false);
        btnCancel.setVisible(false);
        btnReport.setVisible(false);
        btnPending.setVisible(false);
        btnPendingNo.setVisible(false);
        idle();
    }

    /** The active mode owns these: it knows what a retry means right now. */
    public void setActions(Runnable retry, Runnable cancel) {
        this.onRetry  = retry;
        this.onCancel = cancel;
    }

    private JPanel buildHead(Runnable onClose) {
        JPanel head = new JPanel(new BorderLayout(6, 0));
        head.setBackground(AppTheme.MANTLE);
        head.setBorder(BorderFactory.createEmptyBorder(6, 9, 5, 7));

        JButton close = AppTheme.iconButton(
            Icons.chevron(AppTheme.ICON, AppTheme.SUBTEXT, true), "Chiudi i risultati");
        close.setToolTipText("Chiudi il pannello risultati");
        close.addActionListener(e -> onClose.run());

        JPanel bannerBox = new JPanel(new BorderLayout(6, 0));
        bannerBox.setOpaque(false);
        bannerBox.add(banner, BorderLayout.CENTER);
        bannerBox.add(close, BorderLayout.EAST);
        head.add(bannerBox, BorderLayout.NORTH);

        boolean day = cfg.getBool(SettingsManager.UI_RESULTS_DAY, false);
        model.setDayView(day);
        Segmented view = new Segmented(
            new String[] { "Ultimo giro", "Oggi" }, false, day ? 1 : 0, i -> {
                model.setDayView(i == 1);
                cfg.set(SettingsManager.UI_RESULTS_DAY, i == 1);
                cfg.save();
                scrollToLast();
            });
        JPanel viewRow = new JPanel(new BorderLayout());
        viewRow.setOpaque(false);
        viewRow.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        viewRow.add(view, BorderLayout.CENTER);
        head.add(viewRow, BorderLayout.SOUTH);
        return head;
    }

    private JScrollPane buildTable() {
        table.setRowHeight(21);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setBackground(AppTheme.BASE);
        table.setForeground(AppTheme.TEXT);
        table.setSelectionBackground(AppTheme.SURFACE1);
        table.setSelectionForeground(AppTheme.TEXT);
        table.setFont(AppTheme.F_UI);

        JTableHeader h = table.getTableHeader();
        h.setFont(AppTheme.F_SMALL.deriveFont(Font.BOLD));
        h.setBackground(AppTheme.MANTLE);
        h.setForeground(AppTheme.OVERLAY);
        h.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, AppTheme.SURFACE1));
        h.setReorderingAllowed(false);

        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(130);
        table.setDefaultRenderer(Object.class, new RowRenderer(model));

        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int r = table.rowAtPoint(e.getPoint());
                if (r < 0) return;
                int row = table.convertRowIndexToModel(r);
                int col = table.convertColumnIndexToModel(table.columnAtPoint(e.getPoint()));
                if (col == 2 && lotFixer != null) { fixLot(row); return; }
                String code = model.codeAt(row);
                Toolkit.getDefaultToolkit().getSystemClipboard()
                       .setContents(new StringSelection(code), null);
                lblHint.setText("Copiata negli appunti: " + code);
            }
        });

        JScrollPane sc = new JScrollPane(table);
        sc.setPreferredSize(new Dimension(RESULTS_MIN_W, 110));
        sc.setBorder(BorderFactory.createEmptyBorder(0, 9, 0, 9));
        sc.getViewport().setBackground(AppTheme.BASE);
        AppTheme.styleScroll(sc);
        return sc;
    }

    private JPanel buildFoot() {
        lblHint.setFont(AppTheme.F_SMALL);
        lblHint.setForeground(AppTheme.OVERLAY);
        lblFooter.setFont(AppTheme.F_SMALL);
        lblFooter.setForeground(AppTheme.SUBTEXT);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        btns.setOpaque(false);
        btns.add(btnRetry);
        btns.add(btnCancel);
        btns.add(btnReport);
        btns.add(btnPending);
        btns.add(btnPendingNo);

        JPanel foot = new JPanel(new GridLayout(3, 1, 0, 1));
        foot.setBackground(AppTheme.MANTLE);
        foot.setBorder(BorderFactory.createEmptyBorder(4, 9, 5, 9));
        foot.add(lblFooter);
        foot.add(lblHint);
        foot.add(btns);
        return foot;
    }

    // ── states (all called on the EDT) ─────────────────────────────────────

    public void idle() {
        setBanner(AppTheme.SURFACE2, AppTheme.TEXT, "IN ATTESA");
        lblHint.setText("I risultati appaiono qui durante il lavoro");
        lblFooter.setText("");
        buttons(false, false);
    }

    private static String newStamp() {
        return java.time.LocalTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static String today() {
        return java.time.LocalDate.now().toString();   // yyyy-MM-dd
    }

    public void beginRun(int total, String lot) {
        flushJournal();
        runStamp = newStamp();
        runDate  = today();
        runMode  = "intervallo";
        runLot   = lot;
        reportWritten = false;
        this.total = total;
        model.beginRun(lot);
        setBanner(AppTheme.SURFACE2, AppTheme.TEXT, "RUN IN CORSO — 0/" + total);
        lblHint.setText("Non toccare mouse e tastiera durante il run");
        lblFooter.setText("");
        buttons(false, false);
    }

    public void addLiveRow(String code, int done) {
        touchJournal();
        model.addRow(code);
        model.markSentNow(model.getRowCount() - 1);
        setBanner(AppTheme.SURFACE2, AppTheme.TEXT, "RUN IN CORSO — " + done + "/" + total);
        scrollToLast();
    }

    // ── dual scan session ──────────────────────────────────────────────────

    public void beginSession() {
        flushJournal();
        runStamp = newStamp();
        runDate  = today();
        runMode  = "scansione";
        runLot   = "-";
        reportWritten = false;
        total = 0;
        model.beginRun("");
        sessionCounters(0, 0);
        lblHint.setText("Doppio click: etichetta = copia · lotto = registra di nuovo");
        lblFooter.setText("");
        buttons(false, false);
    }

    /** Fresh session by the operator's hand: table and state back to idle. */
    public void resetIdle() {
        flushJournal();
        runStamp = newStamp();
        runDate  = today();
        runMode  = "-";
        runLot   = "-";
        reportWritten = false;
        total = 0;
        model.beginRun("");
        setBanner(AppTheme.SURFACE2, AppTheme.TEXT, "IN ATTESA");
        lblHint.setText("I risultati appaiono qui durante il lavoro");
        lblFooter.setText("");
        buttons(false, false);
    }

    /** The pair shows up the instant it is scanned, waiting for the robot. */
    public int addQueuedPair(String code, String lot) {
        int row = model.addRow(code, lot);
        model.updateOutcome(row, "IN CODA");
        scrollToLast();
        return row;
    }

    public void markSent(int row) {
        touchJournal();
        model.markSentNow(row);
        model.updateOutcome(row, "INVIATA");
    }

    public void sessionCounters(int sent, int queued) {
        total = sent;
        setBanner(AppTheme.SURFACE2, AppTheme.TEXT,
                  "SESSIONE — inviate: " + sent + " · in coda: " + queued);
    }

    public void setSessionTotal(int n) {
        total = n;
    }

    // ── verification ───────────────────────────────────────────────────────

    public void ensureRows(List<String> codes, String lot) {
        total = codes.size();
        model.ensureRows(codes, lot);
    }

    public void showVerifying() {
        model.clearOutcome();
        setBanner(AppTheme.BLUE, AppTheme.ON_ACCENT, "VERIFICA IN CORSO...");
        lblHint.setText("Attendo l'export dal sito...");
        buttons(false, true);
    }

    public void showOutcome(VerificationResult r, String fileName, int attempts, boolean fresh) {
        lastFile = fileName; lastAttempts = attempts; lastFresh = fresh;
        model.applyResult(r);
        if (r.isClean()) {
            setBanner(AppTheme.GREEN, AppTheme.ON_ACCENT,
                      "TUTTO OK — " + r.getMatched() + "/" + total);
        } else {
            setBanner(AppTheme.RED, AppTheme.ON_ACCENT,
                      r.totalProblems() + " PROBLEMI su " + total);
        }
        String extra = (r.totalRegistrations() > r.getMatched())
            ? " · registrazioni: " + r.totalRegistrations() : "";
        String note = fresh ? "" : " · FILE NON NUOVO";
        lblFooter.setText("file: " + fileName + " · tentativi: " + attempts + extra + note);
        lblHint.setText("Doppio click: etichetta = copia · lotto = registra di nuovo");
        buttons(true, false);
    }

    public void showFailure(String reason) {
        setBanner(AppTheme.RED, AppTheme.ON_ACCENT, "VERIFICA FALLITA");
        lblFooter.setText(reason);
        lblHint.setText("");
        buttons(true, false);
    }

    public void showCancelled() {
        setBanner(AppTheme.PEACH, AppTheme.ON_ACCENT, "VERIFICA ANNULLATA");
        buttons(true, false);
    }

    /** The run stopped by itself — say why, and what to do about it. */
    public void showFailSafe(String message) {
        setBanner(AppTheme.PEACH, AppTheme.ON_ACCENT, "INTERROTTO");
        lblFooter.setText(message);
        lblHint.setText("Premi AVVIA per ripartire");
        buttons(false, false);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void setBanner(Color bg, Color fg, String text) {
        banner.setBackground(bg);
        banner.setForeground(fg);
        banner.setText(text);
    }

    private void buttons(boolean retry, boolean cancel) {
        btnRetry.setVisible(retry);
        btnCancel.setVisible(cancel);
        btnPending.setVisible(false);
        btnPendingNo.setVisible(false);
    }

    // ── report ─────────────────────────────────────────────────────────────

    /** File the run in the daily report; once filed, later verifications refresh it.
     *  Rows, lot, mode and stamp all live HERE — the caller only brings the verdict. */
    public void offerReport(VerificationResult r) {
        for (java.awt.event.ActionListener a : btnReport.getActionListeners()) {
            btnReport.removeActionListener(a);
        }
        btnReport.addActionListener(e -> writeReport(r));
        btnReport.setVisible(true);
        writeReport(r);   // the run is already journaled: verdicts always land
    }

    /** Wrong lot? Fix THAT label: new lot in, one burst out, next verify seals it. */
    private void fixLot(int row) {
        // the table shows VISUAL indexes; mutations live in RUN space. Rows in
        // the day archive are history: readable, copyable, never editable.
        int runRow = row - model.dayOffset();
        if (runRow < 0) {
            lblHint.setText("Storico di giornata: sola lettura");
            return;
        }
        String code = model.codeAt(row);
        String cur  = model.lotAt(row);
        String input = (String) JOptionPane.showInputDialog(this,
            "Lotto per " + code + " (OK = registra di nuovo):", "Registra di nuovo",
            JOptionPane.PLAIN_MESSAGE, null, null, cur);
        if (input == null) return;              // Cancel: nothing happens
        String lot = input.trim();
        if (lot.isEmpty()) return;              // empty lot is never valid
        if (lot.equals(cur)) {
            // SAME lot on purpose: a second pass over the same label. The portal
            // appends, so this is a legitimate re-registration, not a no-op.
            model.updateOutcome(runRow, "REINVIO IN CODA");
            lblHint.setText("Reinvio: " + code);
        } else {
            model.setLot(runRow, lot);
            model.updateOutcome(runRow, "CORREZIONE IN CODA");
            lblHint.setText("Correzione: " + code + " -> " + lot);
        }
        lotFixer.fix(runRow, code, lot);
        touchJournal();
    }

    private File reportFile() {
        File dir = new File(cfg.get(SettingsManager.REPORT_DIR,
                                    System.getProperty("user.home")));
        return new File(dir, "AutoFillSuite_report_" + today() + ".csv");
    }

    /** The ONLY way a section reaches the daily file. Synchronized so the
     *  journal thread and a verification write can never interleave their
     *  read-merge-write cycles and lose each other's sections. */
    private synchronized void writeMerged(File out, List<String> section)
            throws IOException {
        List<String> existing = out.exists()
            ? Files.readAllLines(out.toPath(), StandardCharsets.UTF_8)
            : new java.util.ArrayList<String>();
        List<String> merged = RunReport.mergeDaily(
            existing, RunReport.sectionKey(runDate, runStamp), section);
        StringBuilder sb = new StringBuilder();
        for (String l : merged) sb.append(l).append(System.lineSeparator());
        Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeReport(VerificationResult r) {
        File out = reportFile();
        List<String> section = new java.util.ArrayList<>();
        section.add(RunReport.sectionHead(runDate, runStamp, runLot, runMode));
        section.addAll(RunReport.lines(
            RunReport.toCsv(model.reportEntries(), r).trim()));
        section.add(RunReport.summary(model.getRowCount(), r));
        section.add(RunReport.verifyLine(newStamp(), lastFile, lastAttempts, lastFresh));
        section.add("Aggiornato;" + today() + " " + newStamp());
        try {
            writeMerged(out, section);
            lblHint.setText((reportWritten ? "Report aggiornato: " : "Report salvato: ")
                            + out.getName());
            lblHint.setToolTipText(out.getAbsolutePath());
            reportWritten = true;
        } catch (IOException ex) {
            lblHint.setText("Report NON salvato: " + ex.getMessage());
            lblHint.setToolTipText(out.getAbsolutePath());
        }
    }

    // ── the automatic journal ──────────────────────────────────────────────
    // Every send appends itself: at the end of the day the file holds every
    // code with its time even if nobody ever pressed a button. Coalesced
    // through a short timer (a scan burst is many sends in a blink), built on
    // the EDT, written off it; the verification later replaces the section.

    private final javax.swing.Timer journalTimer =
        new javax.swing.Timer(400, e -> journal());
    { journalTimer.setRepeats(false); }

    private void touchJournal() { journalTimer.restart(); }

    /** A run boundary is crossed: if a journal write is still pending, fire
     *  it NOW under the OLD identity — otherwise the last send of a run that
     *  is reset within the coalescing window would never reach the file. */
    private void flushJournal() {
        if (journalTimer.isRunning()) {
            journalTimer.stop();
            journal();
        }
    }

    private void journal() {
        List<RunReport.Entry> entries = model.reportEntries();
        if (entries.isEmpty()) return;
        final File out = reportFile();
        final List<String> section = new java.util.ArrayList<>();
        section.add(RunReport.sectionHead(runDate, runStamp, runLot, runMode));
        section.addAll(RunReport.lines(RunReport.liveCsv(entries).trim()));
        section.add("Aggiornato;" + today() + " " + newStamp());
        reportWritten = true;   // the section exists: later hints say "aggiornato"
        new Thread(() -> {
            try {
                writeMerged(out, section);
            } catch (IOException ex) {
                System.err.println("[journal] " + ex.getMessage());
            }
        }, "report-journal").start();
    }

    // ── crash recovery ─────────────────────────────────────────────────────

    /** The app died mid-run: offer to check what it had already written. */
    public void offerPendingVerification(int count, Runnable verify, Runnable dismiss) {
        setBanner(AppTheme.PEACH, AppTheme.ON_ACCENT, "RUN NON VERIFICATO");
        lblFooter.setText("L'ultimo run (" + count + " etichette) non è stato verificato.");
        lblHint.setText("Controllo adesso contro il CSV del sito?");
        btnRetry.setVisible(false);
        btnCancel.setVisible(false);
        btnReport.setVisible(false);
        btnPending.setVisible(true);
        btnPendingNo.setVisible(true);
        for (java.awt.event.ActionListener a : btnPending.getActionListeners()) {
            btnPending.removeActionListener(a);
        }
        for (java.awt.event.ActionListener a : btnPendingNo.getActionListeners()) {
            btnPendingNo.removeActionListener(a);
        }
        btnPending.addActionListener(e -> { hidePending(); verify.run(); });
        btnPendingNo.addActionListener(e -> { hidePending(); dismiss.run(); idle(); });
    }

    private void hidePending() {
        btnPending.setVisible(false);
        btnPendingNo.setVisible(false);
    }

    private void scrollToLast() {
        int last = model.getRowCount() - 1;
        if (last >= 0) table.scrollRectToVisible(table.getCellRect(last, 0, true));
    }

    // ── renderer: spine + tint tell the outcome at a glance ────────────────
    private static final class RowRenderer extends DefaultTableCellRenderer {
        private final RunTableModel model;
        RowRenderer(RunTableModel model) { this.model = model; }

        @Override public Component getTableCellRendererComponent(JTable t, Object v,
                boolean sel, boolean foc, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
            int state = model.stateAt(t.convertRowIndexToModel(row));
            boolean ok = state == RunTableModel.OK;
            boolean pending = state == RunTableModel.PENDING;

            if (!sel) {
                c.setBackground(pending ? AppTheme.BASE
                              : ok      ? AppTheme.OK_TINT
                              :           AppTheme.KO_TINT);
            }
            c.setForeground(AppTheme.TEXT);
            c.setFont(col == 1 || col == 2 ? AppTheme.F_MONO : AppTheme.F_UI);

            if (c instanceof JComponent) {
                JComponent jc = (JComponent) c;
                if (col == 0) {
                    Color spine = pending ? AppTheme.SURFACE2
                                : ok      ? AppTheme.GREEN
                                :           AppTheme.RED;
                    jc.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, SPINE, 0, 0, spine),
                        BorderFactory.createEmptyBorder(0, 6, 0, 6)));
                } else {
                    jc.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                }
            }
            if (col == 3) {
                c.setFont(AppTheme.F_BOLD);
                c.setForeground(pending ? AppTheme.OVERLAY
                              : ok      ? AppTheme.GREEN
                              :           AppTheme.RED);
            }
            return c;
        }
    }
}
