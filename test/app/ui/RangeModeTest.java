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
        SwingUtilities.invokeAndWait(() -> p.fixOne(0, "LBL001", "LOT-B"));
        waitIdle(p);
        check("a lot correction stops before ENTER when the mouse moves", h.enters.isEmpty());
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
            if (c instanceof JButton && text.equals(((JButton) c).getText())) return (JButton) c;
            if (c instanceof Container) {
                JButton b = button((Container) c, text);
                if (b != null) return b;
            }
        }
        return null;
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }

    static final class NoopRunContext implements RunContext {
        @Override public String blockingCollision(Map<String, Point> targets) { return null; }
        @Override public void jobStarted(Runnable stopAction) { }
        @Override public void jobProgress(Color tone, String state, String number,
                                          String caption, int percent, boolean stoppable) { }
        @Override public void jobFinished() { }
        @Override public void focusHome(JComponent target) { }
    }
}
