package app.ui;

import app.config.SettingsManager;
import app.core.PairBurst;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// the range mode with fake hands, under Xvfb. the robot types into a browser
// nobody is watching, so the operator's hand on the mouse must stop it AT ANY
// STEP — the first cycle included, the lot correction included — with ENTER as
// the point of no return, exactly like the scan mode.
//
//   xvfb-run java -cp out app.ui.RangeModeTest
public final class RangeModeTest {

    private static int passed = 0;
    private static int failed = 0;

    /** Fake hands: counts ENTERs; the mouse "moves" at a chosen check. */
    static final class Hands implements PairBurst.Hands {
        final List<String> enters = new ArrayList<>();
        String label;
        int pastes, checks;
        int grabAtCheck = -1;      // global count of mouse checks
        @Override public void doubleClick(int x, int y) { pastes = 0; }
        @Override public void click(int x, int y) { }
        @Override public void pasteText(String t) { if (pastes++ == 0) label = t; }
        @Override public void pressTab() { }
        @Override public void pressEnter() { enters.add(label); }
        @Override public void sleep(int ms) { }
        @Override public boolean isMouseMoved(Point target) {
            checks++;
            return grabAtCheck > 0 && checks >= grabAtCheck;
        }
    }

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        File home = Files.createTempDirectory("afs-range").toFile();
        System.setProperty("user.home", home.getPath());
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.REG_COORD_X, 100);
        cfg.set(SettingsManager.REG_COORD_Y, 100);
        cfg.set(SettingsManager.REG_WAIT, 0);
        cfg.set(SettingsManager.REG_FIELD_DELAY, 0);
        cfg.set(SettingsManager.REG_POST_ENTER, 0);
        cfg.set(SettingsManager.REG_FIXED_DELAY, 0);
        cfg.set(SettingsManager.REG_VERIFY_AUTO, false);
        cfg.save();

        grabInTheFirstCycle_stopsBeforeEnter();
        grabAfterEnter_countsTheLabel_andStops();
        grabDuringALotFix_stopsBeforeEnter();
        lotInTheLabelField_isAnInversion_swapFixesIt();
        codeOfNoKnownShape_isRefused();
        rangePast999_neverStarts();
        labelsRegisteredToday_needAConfirmation();
        robotBusyElsewhere_neverStarts();
        lotFix_isWhatTheNextCheckExpects_orIsUndone();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    static RangeModePanel panel(Hands h) throws Exception {
        final RangeModePanel[] p = new RangeModePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            p[0] = new RangeModePanel(new ResultsPanel(() -> { }), new NoopRunContext());
            p[0].hands = h;
        });
        return p[0];
    }

    /** Scan the first label, type the lot and the quantity, press AVVIA, wait. */
    static void run(RangeModePanel p, String first, String lot, int count) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<JTextField> f = fields(p);
            f.get(0).setText(first);
            f.get(0).postActionEvent();
            f.get(1).setText(lot);
            spinner(p).setValue(count);
            button(p, "AVVIA").doClick();
        });
        waitIdle(p);
    }

    static void waitIdle(RangeModePanel p) throws Exception {
        long until = System.currentTimeMillis() + 10000;
        Thread.sleep(150);
        while (p.isBusy() && System.currentTimeMillis() < until) Thread.sleep(50);
        Thread.sleep(150);
        SwingUtilities.invokeAndWait(() -> { });
    }

    static int rows(RangeModePanel p) throws Exception {
        java.lang.reflect.Field f = RangeModePanel.class.getDeclaredField("results");
        f.setAccessible(true);
        return ((ResultsPanel) f.get(p)).tableModel().getRowCount();
    }

    // ── cases ────────────────────────────────────────────────────────────

    private static void grabInTheFirstCycle_stopsBeforeEnter() throws Exception {
        Hands h = new Hands();
        h.grabAtCheck = 2;   // after the label was pasted, before the lot
        RangeModePanel p = panel(h);
        run(p, "LBL001", "LOT-A", 3);
        check("a mouse taken back in the FIRST cycle stops the robot before ENTER", h.enters.isEmpty());
        check("nothing is registered and nothing shows as sent", rows(p) == 0);
    }

    private static void grabAfterEnter_countsTheLabel_andStops() throws Exception {
        Hands h = new Hands();
        h.grabAtCheck = 6;   // right after the first ENTER
        RangeModePanel p = panel(h);
        run(p, "LBL001", "LOT-A", 3);
        check("a mouse taken back after ENTER: that label is registered, the run stops",
              h.enters.size() == 1 && rows(p) == 1);
    }

    private static void grabDuringALotFix_stopsBeforeEnter() throws Exception {
        Hands h = new Hands();
        RangeModePanel p = panel(h);
        run(p, "LBL001", "LOT-A", 1);
        h.enters.clear();
        h.checks = 0;
        h.grabAtCheck = 3;
        SwingUtilities.invokeAndWait(() -> p.fixOne(0, "LBL001", "LOT-A", "LOT-B"));
        waitIdle(p);
        check("a lot correction stops before ENTER when the mouse moves", h.enters.isEmpty());
    }

    // the lot scanned into Etichetta: confirmScan only checked the last three
    // digits, so an 18-digit lot passed and the robot registered a range of
    // LOTS as labels. the QR 1 / QR 2 patterns now guard the range as well
    private static void lotInTheLabelField_isAnInversion_swapFixesIt() throws Exception {
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.SCAN_QR1_PATTERN, "[0-9]{6}\\.[0-9]{3}_.*");
        cfg.set(SettingsManager.SCAN_QR2_PATTERN, "[0-9]{18}");
        Hands h = new Hands();
        RangeModePanel p = panel(h);
        run(p, "900612202601310007", "209531.001_01-01_2612000231", 3);
        check("a lot scanned into Etichetta never starts the robot", h.enters.isEmpty());
        check("the banner names the inversion", p.bannerText().contains("invertit"));
        SwingUtilities.invokeAndWait(() -> button(p, "Scambia").doClick());
        check("Scambia trades Etichetta and Lotto",
              "209531.001_01-01_2612000231".equals(fields(p).get(0).getText())
              && "900612202601310007".equals(fields(p).get(1).getText()));
        SwingUtilities.invokeAndWait(() -> button(p, "AVVIA").doClick());
        waitIdle(p);
        check("after the swap the range runs", h.enters.size() == 3
              && "209531.001_01-01_2612000233".equals(h.enters.get(2)));
        cfg.set(SettingsManager.SCAN_QR1_PATTERN, "");
        cfg.set(SettingsManager.SCAN_QR2_PATTERN, "");
    }

    private static void codeOfNoKnownShape_isRefused() throws Exception {
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.SCAN_QR1_PATTERN, "[0-9]{6}\\.[0-9]{3}_.*");
        cfg.set(SettingsManager.SCAN_QR2_PATTERN, "[0-9]{18}");
        Hands h = new Hands();
        RangeModePanel p = panel(h);
        run(p, "BOH-001", "900612202601310007", 2);
        check("a label of no known shape is refused", h.enters.isEmpty()
              && p.bannerText().contains("Formato"));
        cfg.set(SettingsManager.SCAN_QR1_PATTERN, "");
        cfg.set(SettingsManager.SCAN_QR2_PATTERN, "");
    }

    private static void rangePast999_neverStarts() throws Exception {
        Hands h = new Hands();
        RangeModePanel p = panel(h);
        run(p, "LBL998", "LOT-A", 5);
        check("a range past 999 never starts the robot", h.enters.isEmpty());
        check("and says why", p.statusText().contains("supera 999"));
    }

    private static void labelsRegisteredToday_needAConfirmation() throws Exception {
        java.nio.file.Files.write(new File(System.getProperty("user.home"),
            "AutoFillSuite_report_" + java.time.LocalDate.now() + ".csv").toPath(),
            ("RUN;" + java.time.LocalDate.now() + ";07:00:00;lotto=LOT-A;modalita=intervallo\n"
             + "Etichetta;Lotto;OraInvio;Esito;Registrazioni;Dettaglio\n"
             + "LBL502;LOT-A;07:00:09;OK;1;\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Hands h = new Hands();
        RangeModePanel p = panel(h);
        final List<String> asked = new ArrayList<>();
        p.confirmer = m -> { asked.add(m); return false; };
        run(p, "LBL501", "LOT-B", 3);
        check("a range over a label registered today with another lot asks first, with the list",
              asked.size() == 1 && asked.get(0).contains("LBL502") && asked.get(0).contains("LOT-A"));
        check("refused: the robot never starts", h.enters.isEmpty());
        p.confirmer = m -> true;
        SwingUtilities.invokeAndWait(() -> button(p, "AVVIA").doClick());
        waitIdle(p);
        check("confirmed: the range runs", h.enters.size() == 3);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    static List<JTextField> fields(Container root) {
        List<JTextField> out = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (c instanceof JTextField && !(c instanceof JFormattedTextField)) out.add((JTextField) c);
            else if (c instanceof Container) out.addAll(fields((Container) c));
        }
        return out;
    }

    static JSpinner spinner(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JSpinner) return (JSpinner) c;
            if (c instanceof Container) {
                JSpinner s = spinner((Container) c);
                if (s != null) return s;
            }
        }
        return null;
    }

    static JButton button(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton && (text.equals(((JButton) c).getText())
                || (((JButton) c).getToolTipText() != null && ((JButton) c).getToolTipText().startsWith(text)))) {
                return (JButton) c;
            }
            if (c instanceof Container) {
                JButton b = button((Container) c, text);
                if (b != null) return b;
            }
        }
        return null;
    }

    // each mode checked its own flags only: a range could start while a scan
    // verification waited for its export, or between two pairs of a scan block
    private static void robotBusyElsewhere_neverStarts() throws Exception {
        Hands h = new Hands();
        RangeModePanel p = panel(h);
        app.core.RobotGate.Lease other = app.core.RobotGate.tryAcquire("Da file");
        try {
            run(p, "LBL301", "LOT-G", 3);
        } finally {
            other.release();
        }
        check("a range never starts while another mode holds the robot", h.enters.isEmpty() && rows(p) == 0);
        run(p, "LBL301", "LOT-G", 3);
        check("...and starts once the robot is free", h.enters.size() == 3);
        check("the robot is given back at the end of the run", app.core.RobotGate.owner() == null);

        Hands h2 = new Hands();
        final RangeModePanel[] q = new RangeModePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            q[0] = new RangeModePanel(new ResultsPanel(() -> { }), new NoopRunContext() {
                @Override public String busyElsewhere(Object self) { return "Scansione — 4 coppie in coda"; }
            });
            q[0].hands = h2;
        });
        run(q[0], "LBL311", "LOT-G", 3);
        check("a range never starts between the pairs of another mode's job", h2.enters.isEmpty());
    }

    // a correction sent changed the row's lot, but the next range check still
    // looked for the run's lot: LOTTO ERRATO forever. a correction that could
    // not start (robot busy) or stopped before ENTER left the new lot on a
    // row that still showed green, and the report wrote it as sent.
    private static void lotFix_isWhatTheNextCheckExpects_orIsUndone() throws Exception {
        Hands h = new Hands();
        RangeModePanel p = panel(h);
        run(p, "LBL401", "LOT-R", 3);
        final ResultsPanel r = results(p);
        SwingUtilities.invokeAndWait(() -> r.setLotFixer(p::fixOne));
        java.util.List<String> codes = java.util.Arrays.asList("LBL401", "LBL402", "LBL403");

        app.core.RobotGate.Lease other = app.core.RobotGate.tryAcquire("Da file");
        SwingUtilities.invokeAndWait(() -> r.applyFix(1, "LOT-NEW"));
        other.release();
        check("a correction that cannot start leaves the row on the old lot",
              "LOT-R".equals(r.tableModel().lotAt(1)));

        h.checks = 0;
        h.grabAtCheck = 2;   // the operator takes the mouse before ENTER
        SwingUtilities.invokeAndWait(() -> r.applyFix(1, "LOT-NEW"));
        waitIdle(p);
        check("a correction stopped before ENTER goes back to the old lot",
              "LOT-R".equals(r.tableModel().lotAt(1))
              && "CORREZIONE NON INVIATA".equals(r.tableModel().getValueAt(1, 3)));

        h.grabAtCheck = -1;
        h.enters.clear();
        SwingUtilities.invokeAndWait(() -> r.applyFix(1, "LOT-NEW"));
        waitIdle(p);
        check("a correction sent shows the new lot", h.enters.size() == 1
              && "LOT-NEW".equals(r.tableModel().lotAt(1)));
        final java.util.List<java.util.Map<String, String>> want = new java.util.ArrayList<>();
        SwingUtilities.invokeAndWait(() -> want.add(r.expectedLots(codes, "LOT-R")));
        check("the next check looks for the new lot on that label only",
              "LOT-NEW".equals(want.get(0).get("LBL402")) && "LOT-R".equals(want.get(0).get("LBL401")));
    }

    static ResultsPanel results(RangeModePanel p) throws Exception {
        java.lang.reflect.Field f = RangeModePanel.class.getDeclaredField("results");
        f.setAccessible(true);
        return (ResultsPanel) f.get(p);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }

    static class NoopRunContext implements RunContext {
        @Override public String blockingCollision(Map<String, Point> targets) { return null; }
        @Override public void jobStarted(Runnable stopAction) { }
        @Override public void jobProgress(Color tone, String state, String number,
                                          String caption, int percent, boolean stoppable) { }
        @Override public void jobFinished() { }
        @Override public void focusHome(JComponent target) { }
    }
}
