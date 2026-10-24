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
import java.util.Map;

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
    private String runStamp = nextRunStamp();
    private String runDate  = today();
    // the day's report as read at the start (or at the day change): see registeredToday
    private String sentOnDiskDay = today();
    private String runMode  = "-";
    private String runLot   = "-";
    private boolean reportWritten = false;
    private String lastFile = "-";
    private int lastAttempts = 0;
    private boolean lastFresh = true;

    /** Installed by the mode panel: re-registers ONE label with a new lot. */
    /**
     * The mode that owns the rows takes a correction in charge. false = it could
     * not (robot busy, no coordinate, disk): the row goes back to the old lot.
     */
    public interface LotFixer { boolean fix(int row, String code, String oldLot, String newLot); }
    private LotFixer lotFixer;
    public void setLotFixer(LotFixer f) { lotFixer = f; }

    public interface QueueEditor {
        boolean canEdit(int row);
        void edit(int row);
        void delete(int row);
    }
    private QueueEditor queueEditor;
    private boolean queueEditMode = false;

    public void setQueueEditor(QueueEditor editor) {
        queueEditor = editor;
        refreshQueueActions();
    }
    private final JTable table = new JTable(model);
    private final JLabel banner    = AppTheme.banner();
    private final JLabel lblHint   = new JLabel("", SwingConstants.CENTER);
    private final JLabel lblFooter = new JLabel("", SwingConstants.CENTER);
    // the day in one line, always in sight: every label once, as the portal counts
    private final JLabel lblDay = new JLabel(" ", SwingConstants.CENTER);
    // the holes of the day turn the same line red: no extra row, nothing squeezed
    private String gapAlert = null;
    private final JPanel center = new JPanel(new CardLayout());
    private static final String CENTER_TABLE = "table", CENTER_GAPS = "gaps";
    private static final int VIEW_RUN = 0, VIEW_DAY = 1, VIEW_GAPS = 2;
    private Segmented viewSel;
    private final GapTableModel gapModel = new GapTableModel();
    private final JTable gapTable = new JTable(gapModel);
    private final JButton btnGapDelete = AppTheme.secondary("Cancella dal portale",
        Icons.cross(AppTheme.ICON, AppTheme.SUBTEXT));
    private java.util.List<app.core.GapFinder.Gap> gaps = new java.util.ArrayList<>();
    private String gapsCsvWritten = null;
    private String gapsDay = null;
    private java.util.function.Consumer<java.util.List<String>> gapDeleter;
    private final JButton btnRetry  = AppTheme.primary("RIPROVA", AppTheme.BLUE,
        Icons.retry(AppTheme.ICON, AppTheme.ON_ACCENT));
    private final JButton btnCancel = AppTheme.primary("ANNULLA", AppTheme.RED,
        Icons.stop(AppTheme.ICON, AppTheme.ON_ACCENT));
    private final JButton btnReport = AppTheme.secondary("Report CSV", null);
    private final JButton btnPending = AppTheme.primary("VERIFICA ORA", AppTheme.PEACH,
        Icons.search(AppTheme.ICON, AppTheme.ON_ACCENT));
    private final JButton btnPendingNo = AppTheme.secondary("Ignora", null);
    private final JButton btnQueueEdit = AppTheme.secondary("Modifica", null);
    private final JButton btnQueueDelete = AppTheme.secondary("Elimina", null);

    private boolean pendingOffered;   // VERIFICA ORA / Ignora waiting for an answer
    private Runnable onRetry  = () -> {};
    private Runnable onCancel = () -> {};
    private int total = 0;

    public ResultsPanel(Runnable onClose) {
        setLayout(new BorderLayout());
        setBackground(AppTheme.MANTLE);
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, AppTheme.SURFACE1));

        add(buildHead(onClose), BorderLayout.NORTH);
        center.setOpaque(false);
        center.add(buildTable(), CENTER_TABLE);
        center.add(buildGaps(), CENTER_GAPS);
        add(center, BorderLayout.CENTER);
        add(buildFoot(), BorderLayout.SOUTH);

        btnRetry.addActionListener(e -> onRetry.run());
        btnCancel.addActionListener(e -> onCancel.run());
        btnQueueEdit.addActionListener(e -> runQueueAction(true));
        btnQueueDelete.addActionListener(e -> runQueueAction(false));
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshQueueActions();
        });
        btnRetry.setVisible(false);
        btnCancel.setVisible(false);
        btnReport.setVisible(false);
        btnPending.setVisible(false);
        btnPendingNo.setVisible(false);
        btnQueueEdit.setVisible(false);
        btnQueueDelete.setVisible(false);
        // what earlier starts of the app did today: the day reopens as it was left
        model.setHistory(readHistory(sentOnDiskDay));
        model.addTableModelListener(e -> dayChanged());
        idle();
        if (model.dayArchivedCount() > 0) {
            // the app was closed (or fell) with work done today: the day line
            // already counts it, and the day view lists it
            lblHint.setText("Giornata riaperta: in «Oggi» c'è tutto quello che hai fatto prima");
        }
        refreshDay();
    }

    /** The active mode owns these: it knows what a retry means right now. */
    public void setActions(Runnable retry, Runnable cancel) {
        this.onRetry  = retry;
        this.onCancel = cancel;
    }

    /**
     * New rows, new owner: the retry, cancel and correction of the mode that
     * verified the previous rows must never act on these. The mode that
     * verifies them sets its own again.
     */
    private void newOwner() {
        onRetry  = () -> {};
        onCancel = () -> {};
        lotFixer = null;
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
        viewSel = new Segmented(
            new String[] { "Ultimo giro", "Oggi", "Buchi" }, false, day ? VIEW_DAY : VIEW_RUN,
            this::showView);
        JPanel viewRow = new JPanel();
        viewRow.setLayout(new BoxLayout(viewRow, BoxLayout.Y_AXIS));
        viewRow.setOpaque(false);
        viewRow.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        viewSel.setAlignmentX(Component.CENTER_ALIGNMENT);
        viewRow.add(viewSel);
        // one plain line, the same height red or not: a row that appears with
        // the first hole would squeeze the table under a fixed window height
        lblDay.setFont(AppTheme.F_BOLD);
        lblDay.setForeground(AppTheme.SUBTEXT);
        lblDay.setOpaque(true);
        lblDay.setBackground(AppTheme.MANTLE);
        lblDay.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        lblDay.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblDay.setMaximumSize(new Dimension(Integer.MAX_VALUE, lblDay.getPreferredSize().height + 8));
        lblDay.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (gapAlert != null) viewSel.select(VIEW_GAPS);
            }
        });
        JPanel dayBox = new JPanel(new BorderLayout());
        dayBox.setOpaque(false);
        dayBox.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        dayBox.add(lblDay, BorderLayout.CENTER);
        dayBox.setAlignmentX(Component.CENTER_ALIGNMENT);
        viewRow.add(dayBox);
        head.add(viewRow, BorderLayout.SOUTH);
        return head;
    }

    /** The segments: the last run, the whole day, the holes of the day. */
    private void showView(int i) {
        if (i == VIEW_GAPS) {
            ((CardLayout) center.getLayout()).show(center, CENTER_GAPS);
            refreshGapButton();
            return;
        }
        ((CardLayout) center.getLayout()).show(center, CENTER_TABLE);
        model.setDayView(i == VIEW_DAY);
        cfg.set(SettingsManager.UI_RESULTS_DAY, i == VIEW_DAY);
        cfg.save();
        scrollToLast();
    }

    /** The Cancella tab takes these labels in charge (MainWindow wires it). */
    public void setGapDeleter(java.util.function.Consumer<java.util.List<String>> d) {
        gapDeleter = d;
        refreshGapButton();
    }

    private JPanel buildGaps() {
        gapTable.setRowHeight(21);
        gapTable.setShowGrid(false);
        gapTable.setIntercellSpacing(new Dimension(0, 0));
        gapTable.setBackground(AppTheme.BASE);
        gapTable.setForeground(AppTheme.TEXT);
        gapTable.setSelectionBackground(AppTheme.SURFACE1);
        gapTable.setSelectionForeground(AppTheme.TEXT);
        gapTable.setFont(AppTheme.F_UI);
        gapTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JTableHeader h = gapTable.getTableHeader();
        h.setFont(AppTheme.F_SMALL.deriveFont(Font.BOLD));
        h.setBackground(AppTheme.MANTLE);
        h.setForeground(AppTheme.OVERLAY);
        h.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, AppTheme.SURFACE1));
        h.setReorderingAllowed(false);
        gapTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        gapTable.getColumnModel().getColumn(1).setPreferredWidth(70);
        gapTable.getColumnModel().getColumn(2).setPreferredWidth(70);
        gapTable.getColumnModel().getColumn(3).setPreferredWidth(70);
        gapTable.setDefaultRenderer(Object.class, new GapRenderer(gapModel));
        gapTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshGapButton();
        });
        gapTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int r = gapTable.rowAtPoint(e.getPoint());
                if (r < 0) return;
                app.core.GapFinder.Gap g = gapModel.at(gapTable.convertRowIndexToModel(r));
                String text = g.fromCode.equals(g.toCode) ? g.fromCode : g.fromCode + " - " + g.toCode;
                Toolkit.getDefaultToolkit().getSystemClipboard()
                       .setContents(new StringSelection(text), null);
                lblHint.setText("Copiato negli appunti: " + text);
            }
        });
        JScrollPane sc = new JScrollPane(gapTable);
        sc.setPreferredSize(new Dimension(RESULTS_MIN_W, 110));
        sc.setBorder(BorderFactory.createEmptyBorder(0, 9, 0, 9));
        sc.getViewport().setBackground(AppTheme.BASE);
        AppTheme.styleScroll(sc);

        JLabel how = AppTheme.hint("Rosso = numeri saltati tra due etichette registrate. Registrale, "
            + "oppure, se sono rovinate, cancellale dal portale: il buco sparisce.");
        how.setBorder(BorderFactory.createEmptyBorder(4, 10, 2, 10));
        btnGapDelete.addActionListener(e -> deleteSelectedGap());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        btns.setOpaque(false);
        btns.add(btnGapDelete);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(how, BorderLayout.CENTER);
        south.add(btns, BorderLayout.SOUTH);

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(sc, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private void refreshGapButton() {
        int r = gapTable.getSelectedRow();
        app.core.GapFinder.Gap g = r < 0 ? null : gapModel.at(gapTable.convertRowIndexToModel(r));
        btnGapDelete.setEnabled(gapDeleter != null && g != null && !g.jump);
        btnGapDelete.setToolTipText(g == null ? "Seleziona un buco rosso"
            : g.jump ? "Un salto di numerazione non è un buco: non si cancella"
            : "Manda le " + g.count() + " etichette del buco al tab Cancella");
    }

    /** The selected hole goes to the Cancella tab, after a question that says how many. */
    private void deleteSelectedGap() {
        int r = gapTable.getSelectedRow();
        if (r < 0 || gapDeleter == null) return;
        app.core.GapFinder.Gap g = gapModel.at(gapTable.convertRowIndexToModel(r));
        if (g.jump) return;
        String range = g.fromCode.equals(g.toCode) ? g.fromCode : g.fromCode + "\n" + "fino a " + g.toCode;
        if (!gapConfirmer.test("Mandare al tab Cancella " + g.count()
                + (g.count() == 1 ? " etichetta" : " etichette") + "?\n\n" + range
                + "\n\nFallo solo se sono rovinate o buttate: verranno CANCELLATE dal portale"
                + " quando premi CANCELLA nel tab.")) return;
        gapDeleter.accept(g.codes());
    }

    /** Answers the gap question; a test answers it. */
    java.util.function.Predicate<String> gapConfirmer =
        m -> SafeConfirm.ask(this, m, "Cancella il buco");

    /** The holes now (the tests read them). */
    java.util.List<app.core.GapFinder.Gap> gaps() {
        return gaps;
    }

    /** Select a hole by index and press its button (the tests do). */
    void deleteGap(int index) {
        gapTable.getSelectionModel().setSelectionInterval(index, index);
        deleteSelectedGap();
    }

    /** What the red strip says, or null when it is hidden (the tests read it). */
    String gapAlertText() {
        return gapAlert;
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
        btns.add(btnQueueEdit);
        btns.add(btnQueueDelete);
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

    /** The time the report is stamped with; a test moves it across midnight. */
    static java.time.Clock clock = java.time.Clock.systemDefaultZone();

    private static final java.time.format.DateTimeFormatter STAMP =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Display time: whenever it is asked, that is the answer. */
    private static String newStamp() {
        return java.time.LocalTime.now(clock).format(STAMP);
    }

    // The last identity handed out, so the next one is always later. NO explicit
    // initializer: runStamp is built by a field initializer above, and an "= null"
    // here would run after it and forget the very first run's stamp.
    private java.time.LocalTime lastRunTime;

    /**
     * IDENTITY, not display: a section of the daily file is keyed by date and
     * stamp, so two runs started inside the same second would share one key and
     * the second would overwrite the first — a whole run gone from the report
     * of the day. Stamps handed out here are strictly increasing.
     */
    private String nextRunStamp() {
        java.time.LocalTime now = java.time.LocalTime.now(clock).withNano(0);
        if (lastRunTime != null && !now.isAfter(lastRunTime)) {
            now = lastRunTime.plusSeconds(1);
        }
        lastRunTime = now;
        return now.format(STAMP);
    }

    private static String today() {
        return java.time.LocalDate.now(clock).toString();   // yyyy-MM-dd
    }

    public void beginRun(int total, String lot) {
        newOwner();
        queueEditMode = false;
        refreshQueueActions();
        flushJournal();
        runStamp = nextRunStamp();
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
        // the RUN index: in day view the archive sits above, and the visual
        // index (getRowCount() - 1) pointed past the run — the row was never
        // marked sent, and a range in day view looked unsent to everybody
        int row = model.addRow(code);
        model.markSentNow(row);
        setBanner(AppTheme.SURFACE2, AppTheme.TEXT, "RUN IN CORSO — " + done + "/" + total);
        scrollToLast();
    }

    // ── dual scan session ──────────────────────────────────────────────────

    public void beginSession() {
        newOwner();
        queueEditMode = true;
        refreshQueueActions();
        flushJournal();
        runStamp = nextRunStamp();
        runDate  = today();
        runMode  = "scansione";
        runLot   = "-";
        reportWritten = false;
        total = 0;
        model.beginRun("");
        sessionCounters(0, 0);
        lblHint.setText("A blocco: seleziona una riga IN CODA per modificarla o eliminarla");
        lblFooter.setText("");
        buttons(false, false);
    }

    /** The table holds a scan session now (and not a range or a file). */
    public boolean isScanSession() {
        return "scansione".equals(runMode);
    }

    // ── file session ("Da file") ───────────────────────────────────────────

    /**
     * A session of its own for a Registro QR file: the queue editor stays OFF
     * (the rows of a file are corrected in Registro QR, never here), and the
     * report section says modalita=file with the file name as its lot.
     */
    public void beginFileSession(String fileName) {
        newOwner();
        queueEditMode = false;
        refreshQueueActions();
        flushJournal();
        runStamp = nextRunStamp();
        runDate  = today();
        runMode  = "file";
        runLot   = fileName;
        reportWritten = false;
        total = 0;
        model.beginRun("");
        setBanner(AppTheme.SURFACE2, AppTheme.TEXT, "FILE — " + fileName);
        lblHint.setText("Le righe di un file si correggono in Registro QR");
        lblFooter.setText("");
        buttons(false, false);
    }

    // ── delete session ("Cancella") ────────────────────────────────────────

    /**
     * A session of its own for the labels deleted from the portal: its rows
     * go to the day's report as deletions (modalita=cancella), and the day
     * stops counting them as registered.
     */
    public void beginDeleteSession() {
        newOwner();
        queueEditMode = false;
        refreshQueueActions();
        flushJournal();
        runStamp = nextRunStamp();
        runDate  = today();
        runMode  = app.core.DayLedger.MODE_DELETE;
        runLot   = "-";
        reportWritten = false;
        total = 0;
        model.beginDeleteRun();
        setBanner(AppTheme.SURFACE2, AppTheme.TEXT, "CANCELLA");
        lblHint.setText("Le etichette in lista spariscono dal portale quando premi CANCELLA");
        lblFooter.setText("");
        buttons(false, false);
    }

    /** The table holds a Cancella session now. */
    public boolean isDeleteSession() {
        return app.core.DayLedger.MODE_DELETE.equals(runMode);
    }

    /** A label put in the list: on disk at once (IN CODA), so a closed app finds it again. */
    public int addDeleteRow(String code) {
        int row = model.addRow(code, "");
        model.updateOutcome(row, "DA CANCELLARE");
        touchJournal();
        scrollToLast();
        return row;
    }

    /** Off the list before the robot got to it. */
    public boolean removeDeleteRow(int row) {
        boolean removed = model.removeQueuedPair(row);
        if (removed) touchJournal();
        return removed;
    }

    /** The robot clicked DELETE on this label. */
    public void markDeleted(int row) {
        touchJournal();
        model.markDeleted(row);
    }

    public void deleteCounters(int waiting, int deleted) {
        setBanner(AppTheme.SURFACE2, AppTheme.TEXT,
                  "CANCELLA — in lista: " + waiting + " · cancellate: " + deleted);
    }

    public void showDeleteChecking() {
        setBanner(AppTheme.BLUE, AppTheme.ON_ACCENT, "CONTROLLO SUL PORTALE...");
        lblHint.setText("Attendo l'export dal sito...");
        buttons(false, false);
    }

    /**
     * The export after the deletions: every deleted label must be gone from it.
     * @param checked run rows the check covered, with true = gone from the export
     */
    public void showDeleteOutcome(Map<Integer, Boolean> checked, java.util.Set<String> stillThere,
                                  String fileName, int attempts) {
        int gone = 0;
        for (Map.Entry<Integer, Boolean> e : checked.entrySet()) {
            model.markDeleteChecked(e.getKey(), e.getValue());
            if (e.getValue()) gone++;
        }
        int bad = checked.size() - gone;
        lastFile = fileName; lastAttempts = attempts; lastFresh = true;
        if (bad == 0) {
            setBanner(AppTheme.GREEN, AppTheme.ON_ACCENT, "CANCELLATE — " + gone + "/" + checked.size());
            lblHint.setText("Il portale non le ha più: il conteggio di oggi è aggiornato");
        } else {
            setBanner(AppTheme.RED, AppTheme.ON_ACCENT, bad + " ANCORA SUL PORTALE");
            lblHint.setText("Righe rosse: il portale le ha ancora. Controlla e rimettile in lista");
        }
        lblFooter.setText("file: " + fileName + " · tentativi: " + attempts);
        buttons(true, false);
        writeDeleteReport(stillThere);
    }

    public void showDeleteFailure(String reason) {
        setBanner(AppTheme.RED, AppTheme.ON_ACCENT, "CONTROLLO NON RIUSCITO");
        lblFooter.setText(reason);
        lblHint.setText("Le cancellazioni sono fatte: il controllo si può ripetere");
        buttons(true, false);
    }

    private void writeDeleteReport(java.util.Set<String> stillThere) {
        File out = reportFile();
        List<RunReport.Entry> entries = model.reportEntries();
        int gone = 0;
        for (RunReport.Entry e : entries) if (e.sentAt != null && !stillThere.contains(e.code)) gone++;
        List<String> section = new java.util.ArrayList<>();
        section.add(RunReport.sectionHead(runDate, runStamp, runLot, runMode));
        section.addAll(RunReport.lines(RunReport.deleteCsv(entries, stillThere).trim()));
        section.add("CANCELLAZIONI;" + gone + "/" + entries.size());
        section.add(RunReport.verifyLine(newStamp(), lastFile, lastAttempts, lastFresh));
        section.add("Aggiornato;" + today() + " " + newStamp());
        try {
            writeMerged(out, RunReport.sectionKey(runDate, runStamp), section);
            reportWritten = true;
        } catch (IOException ex) {
            lblHint.setText("Report NON salvato: " + ex.getMessage());
        }
    }

    /** One row of the file, with the word that says what will happen to it. */
    public int addFileRow(String code, String lot, String outcome) {
        int row = model.addRow(code, lot);
        model.updateOutcome(row, outcome);
        return row;
    }

    /** A phase word on a row (IN CODA, GIA' PRESENTE...), never a verdict color. */
    public void setRowOutcome(int row, String outcome) {
        model.updateOutcome(row, outcome);
    }

    /**
     * Every label sent today, with its lot and time: the day's report file
     * (earlier app starts) and this app's own table on top.
     *
     * Asked at every scan, on the interface thread: the report is read ONCE,
     * at the start and again only when the day changes. What this app sends
     * afterwards is in the table, which every send updates (earlier runs of the
     * day stay in its day archive), so nothing is lost by not reading again.
     */
    public Map<String, String[]> registeredToday() {
        reloadIfNewDay();
        return model.sentToday();
    }

    /** Past midnight the history of the start is yesterday's: read today's. */
    private void reloadIfNewDay() {
        String day = today();
        if (day.equals(sentOnDiskDay)) return;
        sentOnDiskDay = day;
        model.setHistory(readHistory(day));
    }

    private static List<app.core.DayLedger.Event> readHistory(String day) {
        File rep = app.config.AppFiles.dailyReport(day);
        if (!rep.exists()) return new java.util.ArrayList<>();
        try {
            return app.core.DayLedger.fromReport(Files.readAllLines(rep.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[results] report not readable: " + e.getMessage());
            return new java.util.ArrayList<>();
        }
    }

    // ── the day in one line ────────────────────────────────────────────────

    private final javax.swing.Timer dayTimer = new javax.swing.Timer(250, e -> refreshDay());
    { dayTimer.setRepeats(false); }

    /** Any change in the table: the day line follows, coalesced (a burst is many rows). */
    private void dayChanged() {
        dayTimer.restart();
    }

    /** Registered and deleted labels of the whole day, each once. */
    void refreshDay() {
        reloadIfNewDay();
        int[] c = model.dayCounts();
        afterDayRefresh();
        String text = "OGGI: " + c[0] + (c[0] == 1 ? " registrata" : " registrate");
        if (c[1] > 0) text += " · " + c[1] + (c[1] == 1 ? " cancellata" : " cancellate");
        if (gapAlert != null) text += " · " + gapAlert;
        lblDay.setText(text);
        boolean red = gapAlert != null;
        lblDay.setBackground(red ? AppTheme.RED : AppTheme.MANTLE);
        lblDay.setForeground(red ? AppTheme.ON_ACCENT : AppTheme.SUBTEXT);
        lblDay.setCursor(red ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        lblDay.setToolTipText(red
            ? "Numeri saltati tra etichette registrate dello stesso tipo: clicca per vederli"
            : "Ogni etichetta contata una volta sola: vale l'ultima registrazione, "
              + "come sul portale. Le cancellate non contano.");
    }

    /**
     * The holes of the day from every label the day accounts for: registered,
     * deleted from the portal, or waiting in a queue. Red strip, red segment,
     * and the file of the day's holes, rewritten only when they change.
     */
    private void afterDayRefresh() {
        java.util.LinkedHashMap<String, app.core.DayLedger.Event> day = model.day();
        java.util.Set<String> present = new java.util.LinkedHashSet<>(day.keySet());
        present.addAll(model.queuedCodes());
        int max = cfg.getInt(SettingsManager.GAP_MAX, app.core.GapFinder.DEFAULT_MAX_GAP);
        gaps = app.core.GapFinder.find(present, max);
        int holes = app.core.GapFinder.holes(gaps);
        long missing = app.core.GapFinder.missing(gaps);
        gapModel.set(gaps);
        viewSel.setLabel(VIEW_GAPS, holes == 0 ? "Buchi" : "Buchi (" + holes + ")");
        viewSel.setAlarm(VIEW_GAPS, holes > 0);
        gapAlert = holes == 0 ? null
            : (holes == 1 ? "1 BUCO" : holes + " BUCHI") + " (" + missing
              + (missing == 1 ? " mancante)" : " mancanti)");
        refreshGapButton();
        writeGapsIfChanged();
    }

    /** The day's holes on disk next to the report: only when they change, off the EDT. */
    private void writeGapsIfChanged() {
        final String date = today();
        final String csv = app.core.GapFinder.toCsv(gaps);
        if (csv.equals(gapsCsvWritten) && date.equals(gapsDay)) return;
        boolean nothing = gaps.isEmpty();
        final File out = app.config.AppFiles.dailyGaps(date);
        if (nothing && !out.exists()) { gapsCsvWritten = csv; gapsDay = date; return; }
        gapsCsvWritten = csv;
        gapsDay = date;
        try {
            JOURNAL.execute(() -> {
                try {
                    app.core.AtomicFile.write(out.toPath(), csv.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ex) {
                    System.err.println("[gaps] " + ex.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            // the app is closing: the next start writes them again
        }
    }

    /** What the day line says now (the tests read it). */
    String dayText() {
        return lblDay.getText();
    }

    /** A one-off message for the operator (the workstation at start, say). */
    public void notice(String text) {
        lblHint.setText(text.replace("\n", " · "));
        lblHint.setToolTipText("<html>" + text.replace("&", "&amp;").replace("<", "&lt;")
                               .replace("\n", "<br>") + "</html>");
    }

    /** The table model, for the modes' own checks and the tests. */
    RunTableModel tableModel() {
        return model;
    }

    public void fileBanner(Color bg, String text) {
        setBanner(bg, AppTheme.ON_ACCENT, text);
    }

    /** Fresh session by the operator's hand: table and state back to idle. */
    public void resetIdle() {
        queueEditMode = false;
        refreshQueueActions();
        flushJournal();
        runStamp = nextRunStamp();
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
        refreshQueueActions();
        return row;
    }

    public boolean updateQueuedPair(int row, String code, String lot) {
        boolean changed = model.updateQueuedPair(row, code, lot);
        if (changed) {
            lblHint.setText("Coppia aggiornata prima dell'invio");
            refreshQueueActions();
        }
        return changed;
    }

    public boolean removeQueuedPair(int row) {
        boolean removed = model.removeQueuedPair(row);
        if (removed) {
            table.clearSelection();
            lblHint.setText("Coppia rimossa dalla coda");
            refreshQueueActions();
        }
        return removed;
    }

    public void queueActionsChanged() {
        refreshQueueActions();
    }

    private int selectedRunRow() {
        int view = table.getSelectedRow();
        if (view < 0) return -1;
        int visual = table.convertRowIndexToModel(view);
        return visual - model.dayOffset();
    }

    private void runQueueAction(boolean edit) {
        int row = selectedRunRow();
        if (row < 0 || queueEditor == null || !queueEditor.canEdit(row)) {
            refreshQueueActions();
            return;
        }
        if (edit) queueEditor.edit(row);
        else queueEditor.delete(row);
        refreshQueueActions();
    }

    private void refreshQueueActions() {
        if (btnQueueEdit == null || btnQueueDelete == null) return;
        int row = selectedRunRow();
        boolean enabled = queueEditMode && queueEditor != null
            && row >= 0 && queueEditor.canEdit(row);
        btnQueueEdit.setVisible(queueEditMode);
        btnQueueDelete.setVisible(queueEditMode);
        btnQueueEdit.setEnabled(enabled);
        btnQueueDelete.setEnabled(enabled);
    }

    public void markSent(int row) {
        touchJournal();
        model.markSentNow(row);
        model.updateOutcome(row, "INVIATA");
    }

    /** The robot stopped before this pair reached the site: never journal it. */
    public void markNotSent(int row, String why) {
        model.markFailed(row);
        lblHint.setText(why);
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

    /**
     * A range check by hand. The table may hold a scan or file session, or
     * another range: those are another run and go to the day archive, never
     * rebuilt in place (their report section would be written over). The
     * same range keeps its rows and their send times, and gains the labels it
     * never reached.
     */
    public void ensureRows(List<String> codes, String lot) {
        if (!"intervallo".equals(runMode) || !model.runCodesWithin(codes)) beginRun(codes.size(), lot);
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
        // the offer to verify an unchecked range stays until it is answered:
        // a file reloaded at start used to hide it
        btnPending.setVisible(pendingOffered);
        btnPendingNo.setVisible(pendingOffered);
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
        applyFix(row, input);
    }

    /** The answer of the "Registra di nuovo" box, for a visual row (the tests call it). */
    void applyFix(int row, String input) {
        int runRow = row - model.dayOffset();
        if (runRow < 0 || lotFixer == null) return;
        String code = model.codeAt(row);
        String cur  = model.lotAt(row);
        String before = String.valueOf(model.getValueAt(row, 3));
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
        if (!lotFixer.fix(runRow, code, cur, lot)) {
            // nothing was taken in charge: the row shows what the portal has
            model.setLot(runRow, cur);
            model.updateOutcome(runRow, before);
            lblHint.setText("Correzione non accodata: " + code);
            return;
        }
        touchJournal();
    }

    /** A correction that never reached ENTER: the row goes back to the lot sent before. */
    public void correctionNotSent(int runRow, String oldLot, String why) {
        model.setLot(runRow, oldLot);
        model.updateOutcome(runRow, "CORREZIONE NON INVIATA");
        lblHint.setText(why);
        touchJournal();
    }

    /**
     * What a verification of these labels should look for: the lot each one
     * shows in the current run (a correction sent changed it), the run's lot
     * for a label not in the table.
     */
    public Map<String, String> expectedLots(List<String> codes, String lot) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String c : codes) {
            String shown = model.runLotOf(c);
            out.put(c, shown == null ? lot : shown);
        }
        return out;
    }

    /**
     * The file of the RUN's day, not of today: a run started at 23:58 and
     * verified at 00:03 used to have its journal in one file and its
     * verification in the next day's.
     */
    private File reportFile() {
        return app.config.AppFiles.dailyReport(runDate);
    }

    /**
     * The only way a section reaches the daily file. Synchronization keeps the
     * journal thread and verification from interleaving read-merge-write cycles
     * and losing each other's sections.
     *
     * The key travels with the section: reading runStamp here would read it
     * from the journal thread, and a run boundary crossed in between would
     * file the old run's rows under the new run's identity.
     */
    private synchronized void writeMerged(File out, String key, List<String> section)
            throws IOException {
        List<String> existing = out.exists()
            ? Files.readAllLines(out.toPath(), StandardCharsets.UTF_8)
            : new java.util.ArrayList<String>();
        List<String> merged = RunReport.mergeDaily(existing, key, section);
        StringBuilder sb = new StringBuilder();
        for (String l : merged) sb.append(l).append(System.lineSeparator());
        // whole or not at all: Files.write truncated first, and an exit or a
        // power cut in the middle left the day's report half empty
        app.core.AtomicFile.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
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
            writeMerged(out, RunReport.sectionKey(runDate, runStamp), section);
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

    /**
     * ONE writer, in submission order. Every write is a read-modify-write of the
     * whole daily file, so two of them at once lose a section outright: the
     * coalesced write of a run and the boundary flush of the next one overlap
     * exactly at the moment a session ends, which is the moment the file matters.
     *
     * The thread is not a daemon, but that alone protects nothing: System.exit
     * ends the JVM with non-daemon threads still running, and the coalescing
     * timer was never flushed either — closing the app right after a send lost
     * the last row. The window now calls {@link #shutdown()} before exiting. The
     * idle thread times out, so it never keeps the app from exiting either.
     */
    private static final java.util.concurrent.ThreadPoolExecutor JOURNAL =
        new java.util.concurrent.ThreadPoolExecutor(1, 1, 5,
            java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>(),
            r -> new Thread(r, "report-journal"));
    static { JOURNAL.allowCoreThreadTimeOut(true); }

    /**
     * Before the app exits: the pending coalesced write goes out NOW, then the
     * writer is drained and waited for (5 s at most). true when everything
     * reached the disk in time.
     */
    public boolean shutdown() {
        flushJournal();
        JOURNAL.shutdown();
        try {
            return JOURNAL.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
        final String key = RunReport.sectionKey(runDate, runStamp);
        JOURNAL.execute(() -> {
            try {
                writeMerged(out, key, section);
            } catch (IOException ex) {
                System.err.println("[journal] " + ex.getMessage());
            }
        });
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
        pendingOffered = true;
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
        pendingOffered = false;
        btnPending.setVisible(false);
        btnPendingNo.setVisible(false);
    }

    private void scrollToLast() {
        int last = model.getRowCount() - 1;
        if (last >= 0) table.scrollRectToVisible(table.getCellRect(last, 0, true));
    }

    // ── the holes: one row per run of missing numbers ──────────────────────
    private static final class GapTableModel extends javax.swing.table.AbstractTableModel {
        private static final String[] COLS = { "Tipo", "Da", "A", "Mancano" };
        private java.util.List<app.core.GapFinder.Gap> rows = new java.util.ArrayList<>();

        void set(java.util.List<app.core.GapFinder.Gap> g) {
            rows = new java.util.ArrayList<>(g);
            fireTableDataChanged();
        }

        app.core.GapFinder.Gap at(int r) { return rows.get(r); }

        @Override public int getRowCount()           { return rows.size(); }
        @Override public int getColumnCount()        { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override public Object getValueAt(int r, int c) {
            app.core.GapFinder.Gap g = rows.get(r);
            switch (c) {
                case 0:  return g.prefix;
                case 1:  return tail(g.fromCode, g.prefix);
                case 2:  return tail(g.toCode, g.prefix);
                default: return g.jump ? "salto: " + g.count() : String.valueOf(g.count());
            }
        }

        /** The number without its type: the type already has a column. */
        private static String tail(String code, String prefix) {
            return code.startsWith(prefix) ? code.substring(prefix.length()) : code;
        }
    }

    private static final class GapRenderer extends DefaultTableCellRenderer {
        private final GapTableModel model;
        GapRenderer(GapTableModel model) { this.model = model; }

        @Override public Component getTableCellRendererComponent(JTable t, Object v,
                boolean sel, boolean foc, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
            app.core.GapFinder.Gap g = model.at(t.convertRowIndexToModel(row));
            if (!sel) c.setBackground(g.jump ? AppTheme.BASE : AppTheme.KO_TINT);
            c.setForeground(g.jump ? AppTheme.OVERLAY : AppTheme.TEXT);
            c.setFont(col == 0 ? AppTheme.F_SMALL : col == 3 ? AppTheme.F_BOLD : AppTheme.F_MONO);
            if (col == 3 && !g.jump) c.setForeground(AppTheme.RED);
            if (c instanceof JComponent) {
                JComponent jc = (JComponent) c;
                jc.setToolTipText(g.jump
                    ? "Salto di " + g.count() + " numeri: un'altra pila di etichette, non controllato"
                    : g.fromCode + " - " + g.toCode);
                if (col == 0) {
                    jc.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, SPINE, 0, 0, g.jump ? AppTheme.SURFACE2 : AppTheme.RED),
                        BorderFactory.createEmptyBorder(0, 6, 0, 6)));
                } else {
                    jc.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                }
            }
            return c;
        }
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
            boolean deleted = state == RunTableModel.DELETED;
            // a deleted label is history, not a problem: grey like a waiting row
            boolean pending = state == RunTableModel.PENDING || deleted;

            if (!sel) {
                c.setBackground(pending ? AppTheme.BASE
                              : ok      ? AppTheme.OK_TINT
                              :           AppTheme.KO_TINT);
            }
            c.setForeground(deleted ? AppTheme.OVERLAY : AppTheme.TEXT);
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
            if (c instanceof JComponent) {
                int runRow = t.convertRowIndexToModel(row);
                ((JComponent) c).setToolTipText(col == 3 ? model.tipAt(runRow) : null);
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
