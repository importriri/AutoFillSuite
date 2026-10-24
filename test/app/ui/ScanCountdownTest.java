package app.ui;

import app.config.SettingsManager;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// REGISTRA TUTTO is pressed with the mouse. the old lever released the block
// at once: the first burst moved the pointer while the hand was still on the
// mouse, the hand leaving read as "mouse moved", and the fail-safe stopped the
// robot almost every time. the lever now counts down first, on the banner, on
// the lever itself (which turns into ANNULLA) and on the HUD.
//
//   xvfb-run java -cp out app.ui.ScanCountdownTest
public final class ScanCountdownTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        java.io.File home = Files.createTempDirectory("afs-countdown").toFile();
        System.setProperty("user.home", home.getPath());
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.REPORT_DIR, home.getPath());
        cfg.set(SettingsManager.SCAN_COORD_X, 400);
        cfg.set(SettingsManager.SCAN_COORD_Y, 300);
        cfg.set(SettingsManager.SCAN_VERIFY_AUTO, false);
        cfg.set(SettingsManager.SCAN_START_WAIT, 1);
        cfg.set(SettingsManager.SCAN_BATCH, true);
        cfg.save();

        lever_countsDown_beforeTheFirstBurst();
        lever_pressedAgain_cancels();
        stopFromTheBar_duringTheCountdown_sendsNothing();
        resume_inContinuo_countsDownToo();
        zeroSeconds_startsAtOnce();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
        System.exit(0);
    }

    private static void lever_countsDown_beforeTheFirstBurst() throws Exception {
        final Hands h = new Hands();
        final List<String> bar = Collections.synchronizedList(new ArrayList<String>());
        final ScanModePanel p = panel(h, bar, null);
        ScanSessionTest.scan(p, "LBL101", "LOT-A");
        ScanSessionTest.scan(p, "LBL102", "LOT-A");
        clickLever(p, "REGISTRA TUTTO");
        Thread.sleep(150);
        check("the lever arms a countdown instead of firing", armed(p));
        check("the lever turns into ANNULLA while it counts", leverText(p).startsWith("ANNULLA AVVIO"));
        check("the banner tells the operator to take the hand off the mouse",
              banner(p).contains("togli la mano dal mouse"));
        check("the HUD shows the countdown too", contains(bar, "PARTO TRA"));
        Thread.sleep(400);
        check("no burst during the countdown", h.steps().isEmpty());
        waitFor(() -> queued(p) == 0, 5000);
        check("after the countdown the whole block goes out", queued(p) == 0 && h.enters() == 2);
    }

    private static void lever_pressedAgain_cancels() throws Exception {
        final Hands h = new Hands();
        final ScanModePanel p = panel(h, null, null);
        ScanSessionTest.scan(p, "LBL201", "LOT-B");
        clickLever(p, "REGISTRA TUTTO");
        Thread.sleep(150);
        clickLever(p, "ANNULLA AVVIO");
        Thread.sleep(1500);
        check("ANNULLA stops the countdown", !armed(p));
        check("nothing is sent after ANNULLA", h.steps().isEmpty());
        check("the pair stays in the queue", queued(p) == 1);
        check("the lever is REGISTRA TUTTO again", leverText(p).startsWith("REGISTRA TUTTO"));
    }

    private static void stopFromTheBar_duringTheCountdown_sendsNothing() throws Exception {
        final Hands h = new Hands();
        final Runnable[] stop = { null };
        final ScanModePanel p = panel(h, null, stop);
        ScanSessionTest.scan(p, "LBL301", "LOT-C");
        clickLever(p, "REGISTRA TUTTO");
        Thread.sleep(150);
        check("the block hands a STOP to the bar at once", stop[0] != null);
        SwingUtilities.invokeAndWait(() -> { if (stop[0] != null) stop[0].run(); });
        Thread.sleep(1500);
        check("STOP from the bar cancels the countdown", !armed(p) && h.steps().isEmpty());
        check("the pair is still queued after STOP", queued(p) == 1);
    }

    private static void resume_inContinuo_countsDownToo() throws Exception {
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.SCAN_BATCH, false);
        cfg.save();
        final Hands h = new Hands();
        final ScanModePanel p = panel(h, null, null);
        clickLever(p, "PAUSA");
        ScanSessionTest.scan(p, "LBL401", "LOT-D");
        Thread.sleep(300);
        check("paused: the pair waits", h.steps().isEmpty() && queued(p) == 1);
        clickLever(p, "RIPRENDI");
        Thread.sleep(150);
        check("RIPRENDI with pairs waiting counts down first", armed(p) && h.steps().isEmpty());
        waitFor(() -> queued(p) == 0, 5000);
        check("after the countdown the pair goes out", h.enters() == 1);
        cfg.set(SettingsManager.SCAN_BATCH, true);
        cfg.save();
    }

    private static void zeroSeconds_startsAtOnce() throws Exception {
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.SCAN_START_WAIT, 0);
        cfg.save();
        final Hands h = new Hands();
        final ScanModePanel p = panel(h, null, null);
        ScanSessionTest.scan(p, "LBL501", "LOT-E");
        clickLever(p, "REGISTRA TUTTO");
        Thread.sleep(100);
        check("0 seconds: no countdown", !armed(p));
        waitFor(() -> queued(p) == 0, 3000);
        check("0 seconds: the block goes out", h.enters() == 1);
        cfg.set(SettingsManager.SCAN_START_WAIT, 1);
        cfg.save();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Hands that record what they would do, with the pointer always in place. */
    static final class Hands extends ScanSessionTest.QuietHands {
        private final List<String> steps = Collections.synchronizedList(new ArrayList<String>());
        @Override public void doubleClick(int x, int y) { steps.add("DBL"); }
        @Override public void pressEnter() { steps.add("ENTER"); }
        List<String> steps() { synchronized (steps) { return new ArrayList<>(steps); } }
        int enters() {
            int n = 0;
            for (String s : steps()) if ("ENTER".equals(s)) n++;
            return n;
        }
    }

    private static ScanModePanel panel(final Hands h, final List<String> bar, final Runnable[] stop)
            throws Exception {
        final ScanModePanel[] sp = new ScanModePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            sp[0] = new ScanModePanel(new ResultsPanel(() -> { }), new RangeModeTest.NoopRunContext() {
                @Override public void jobStarted(Runnable s) { if (stop != null) stop[0] = s; }
                @Override public void jobProgress(Color tone, String state, String number,
                                                  String caption, int percent, boolean stoppable) {
                    if (bar != null) bar.add(state);
                }
            });
            sp[0].hands = h;
        });
        return sp[0];
    }

    private static void clickLever(ScanModePanel p, String prefix) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (JButton b : ResultsOwnershipTest.buttons(p)) {
                if (b.getText() != null && b.getText().startsWith(prefix)) { b.doClick(); return; }
            }
        });
    }

    private static String leverText(ScanModePanel p) throws Exception {
        final String[] t = { "" };
        SwingUtilities.invokeAndWait(() -> {
            for (JButton b : ResultsOwnershipTest.buttons(p)) {
                String s = b.getText();
                if (s != null && (s.startsWith("REGISTRA") || s.startsWith("ANNULLA")
                        || s.startsWith("RIPRENDI") || s.startsWith("PAUSA"))) { t[0] = s; return; }
            }
        });
        return t[0];
    }

    private static boolean armed(ScanModePanel p) throws Exception {
        final boolean[] a = { false };
        SwingUtilities.invokeAndWait(() -> a[0] = p.isArmed());
        return a[0];
    }

    private static String banner(ScanModePanel p) throws Exception {
        final String[] t = { "" };
        SwingUtilities.invokeAndWait(() -> t[0] = p.bannerText());
        return t[0];
    }

    private static int queued(ScanModePanel p) throws Exception {
        return ScanSessionTest.queued(p);
    }

    private static boolean contains(List<String> l, String part) {
        synchronized (l) {
            for (String s : l) if (s != null && s.contains(part)) return true;
        }
        return false;
    }

    interface Cond { boolean ok() throws Exception; }

    private static void waitFor(Cond c, long ms) throws Exception {
        long until = System.currentTimeMillis() + ms;
        while (!c.ok() && System.currentTimeMillis() < until) Thread.sleep(50);
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
