package app.ui;

import app.config.SettingsManager;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

// the scan worker took a pair off the queue, then asked the interface thread
// whether the window was in the way, and only then said "busy". in that gap
// the queue looked empty and the robot idle: the verification owed by the
// previous pair started, and its Export click landed in the middle of the
// next pair's burst. here the window check takes 300 ms on purpose, so the
// gap is wide open every time; two robots must never be at work together.
//
//   xvfb-run java -cp out app.ui.ScanRaceTest
public final class ScanRaceTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        Path home = Files.createTempDirectory("afs-race");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve("Downloads"));
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.SCAN_VERIFY_EVERY, 1);
        cfg.set(SettingsManager.SCAN_VERIFY_AUTO, true);
        cfg.set(SettingsManager.SCAN_FAILSAFE, false);
        cfg.set(SettingsManager.SCAN_FOCUS, 60);
        cfg.set(SettingsManager.SCAN_KEY, 40);
        cfg.set(SettingsManager.SCAN_ENTER, 60);
        cfg.set(SettingsManager.REG_EXPORT_TIMEOUT_S, 2);
        cfg.set(SettingsManager.REG_VERIFY_RETRIES, 0);
        cfg.save();

        final ScanModePanel[] sp = new ScanModePanel[1];
        RunContext slowWindowCheck = new RangeModeTest.NoopRunContext() {
            @Override public String blockingCollision(Map<String, Point> targets) {
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return null;
            }
        };
        final Point[] at = new Point[2];
        SwingUtilities.invokeAndWait(() -> {
            sp[0] = new ScanModePanel(new ResultsPanel(() -> { }), slowWindowCheck);
            JFrame f = new JFrame("scan");
            f.add(sp[0]);
            f.pack();
            f.setLocation(0, 0);
            f.setVisible(true);
            // a stand-in portal under the robot's coordinates: what it types lands here
            JFrame portal = new JFrame("portal");
            JPanel form = new JPanel(null);
            JTextField label = new JTextField();
            label.setBounds(20, 20, 200, 28);
            JTextField lot = new JTextField();
            lot.setBounds(20, 60, 200, 28);
            JButton export = new JButton("EXPORT");
            export.setBounds(260, 20, 100, 28);
            form.add(label);
            form.add(lot);
            form.add(export);
            portal.setContentPane(form);
            portal.setBounds(700, 500, 420, 160);
            portal.setVisible(true);
        });
        Thread.sleep(500);
        SwingUtilities.invokeAndWait(() -> {
            for (Window w : Window.getWindows()) {
                if (!(w instanceof JFrame) || !"portal".equals(((JFrame) w).getTitle())) continue;
                Container form = ((JFrame) w).getContentPane();
                Component label = form.getComponent(0), export = form.getComponent(2);
                Point a = label.getLocationOnScreen(), b = export.getLocationOnScreen();
                at[0] = new Point(a.x + 30, a.y + 12);
                at[1] = new Point(b.x + 30, b.y + 12);
            }
        });
        // hands that take the time of a real burst and type nothing
        sp[0].hands = new app.core.PairBurst.Hands() {
            @Override public void doubleClick(int x, int y) throws InterruptedException { Thread.sleep(40); }
            @Override public void click(int x, int y) throws InterruptedException { Thread.sleep(20); }
            @Override public void pasteText(String text) throws InterruptedException { Thread.sleep(40); }
            @Override public void pressTab() throws InterruptedException { Thread.sleep(20); }
            @Override public void pressEnter() throws InterruptedException { Thread.sleep(20); }
            @Override public void sleep(int ms) throws InterruptedException { Thread.sleep(ms); }
            @Override public boolean isMouseMoved(Point target) { return false; }
        };
        cfg.set(SettingsManager.SCAN_COORD_X, at[0].x);
        cfg.set(SettingsManager.SCAN_COORD_Y, at[0].y);
        cfg.set(SettingsManager.REG_EXPORT_COORD_X, at[1].x);
        cfg.set(SettingsManager.REG_EXPORT_COORD_Y, at[1].y);
        cfg.save();

        // two pairs, a verification owed after each one
        for (int i = 1; i <= 2; i++) {
            final int n = i;
            SwingUtilities.invokeAndWait(() -> {
                java.util.List<JTextField> f = RangeModeTest.fields(sp[0]);
                f.get(0).setText("LBL00" + n);
                f.get(1).setText("LOT" + n);
                f.get(1).postActionEvent();
            });
        }

        int overlap = 0;
        boolean sawBurst = false, sawVerify = false;
        long end = System.currentTimeMillis() + 7000;
        while (System.currentTimeMillis() < end) {
            boolean burst = false, verify = false;
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                String name = e.getKey().getName();
                for (StackTraceElement el : e.getValue()) {
                    if ("dualscan-worker".equals(name) && el.getClassName().endsWith("PairBurst")) burst = true;
                    if ("dualscan-verify".equals(name) && el.getClassName().endsWith("VerificationTask")) verify = true;
                }
            }
            sawBurst |= burst;
            sawVerify |= verify;
            if (burst && verify) overlap++;
            Thread.sleep(2);
        }
        check("the pairs were typed", sawBurst);
        check("only the two scanned pairs went out", sentCount(sp[0]) == 2);
        check("a verification ran", sawVerify);
        check("never a burst while a verification is at work (samples: " + overlap + ")", overlap == 0);

        // another mode holds the robot: a pair scanned now waits for it
        while (app.core.RobotGate.owner() != null) Thread.sleep(20);
        app.core.RobotGate.Lease other = app.core.RobotGate.tryAcquire("Intervallo");
        SwingUtilities.invokeAndWait(() -> {
            java.util.List<JTextField> f = RangeModeTest.fields(sp[0]);
            f.get(0).setText("LBL003");
            f.get(1).setText("LOT3");
            f.get(1).postActionEvent();
        });
        Thread.sleep(1500);
        check("a scanned pair waits while another mode holds the robot", sentCount(sp[0]) == 2);
        other.release();
        long until = System.currentTimeMillis() + 5000;
        while (sentCount(sp[0]) < 3 && System.currentTimeMillis() < until) Thread.sleep(50);
        check("...and goes out once it is free", sentCount(sp[0]) == 3);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    static int sentCount(ScanModePanel p) throws Exception {
        java.lang.reflect.Field f = ScanModePanel.class.getDeclaredField("sent");
        f.setAccessible(true);
        return f.getInt(p);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
