package app.ui;

import app.config.SettingsManager;
import app.core.WindowGuard;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

// the app is always on top: over a memorized coordinate, the robot clicks the
// APP instead of the portal. the window used to be checked only when a run
// started. now it is watched while the app is open: red and a button while the
// robot is still (the window is under the operator's hand), out of the way by
// itself while the robot works, and never in the middle of a burst.
//
//   xvfb-run java -cp out app.ui.WindowWatchTest
public final class WindowWatchTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        rule();
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
        } else {
            window();
        }
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void rule() {
        java.util.List<String> hit = Arrays.asList("Casella 1");
        java.util.List<String> none = Collections.emptyList();
        check("nothing covered: clear", WindowGuard.watch(true, true, true, false, none) == WindowGuard.Watch.CLEAR);
        check("guard off: clear", WindowGuard.watch(false, true, true, false, hit) == WindowGuard.Watch.CLEAR);
        check("robot still: warn, never move under the operator's hand",
              WindowGuard.watch(true, true, false, false, hit) == WindowGuard.Watch.WARN);
        check("robot working: move", WindowGuard.watch(true, true, true, false, hit) == WindowGuard.Watch.MOVE);
        check("mid-burst: wait for the pause", WindowGuard.watch(true, true, true, true, hit) == WindowGuard.Watch.WAIT);
        check("automatic move off: warn only", WindowGuard.watch(true, false, true, false, hit) == WindowGuard.Watch.WARN);
    }

    private static void window() throws Exception {
        java.io.File home = Files.createTempDirectory("afs-watch").toFile();
        System.setProperty("user.home", home.getPath());
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.REPORT_DIR, home.getPath());
        cfg.set(SettingsManager.UI_WIN_X, 40);
        cfg.set(SettingsManager.UI_WIN_Y, 40);
        cfg.set(SettingsManager.UI_HUD_AUTO, false);
        cfg.set(SettingsManager.REG_COORD_X, 120);     // under the window
        cfg.set(SettingsManager.REG_COORD_Y, 120);
        cfg.save();
        MainWindow.exit = () -> { };
        final MainWindow[] w = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> w[0] = new MainWindow());
        Thread.sleep(400);
        SwingUtilities.invokeAndWait(() -> {
            w[0].setLocation(40, 40);
            w[0].watchWindow();
        });
        final String[] warn = { null };
        final Point[] at = { null };
        SwingUtilities.invokeAndWait(() -> { warn[0] = w[0].guardWarning(); at[0] = w[0].getLocation(); });
        check("robot still: the header names what is covered", warn[0] != null && warn[0].contains("Casella 1"));
        check("robot still: the window stays where the operator put it", at[0].x == 40 && at[0].y == 40);

        cfg.set(SettingsManager.GUARD_AUTOMOVE, false);
        cfg.save();
        SwingUtilities.invokeAndWait(() -> { w[0].jobStarted(() -> { }); w[0].watchWindow(); });
        SwingUtilities.invokeAndWait(() -> { warn[0] = w[0].guardWarning(); at[0] = w[0].getLocation(); });
        check("automatic move off: it only warns, even while the robot works",
              warn[0] != null && at[0].x == 40 && at[0].y == 40);

        cfg.set(SettingsManager.GUARD_AUTOMOVE, true);
        cfg.save();
        SwingUtilities.invokeAndWait(() -> w[0].watchWindow());
        Thread.sleep(300);
        final Rectangle[] b = { null };
        SwingUtilities.invokeAndWait(() -> { warn[0] = w[0].guardWarning(); b[0] = w[0].getBounds(); });
        check("robot working: the window moved out of the way by itself", !b[0].contains(120, 120));
        check("and the warning is gone", warn[0] == null);
        SwingUtilities.invokeAndWait(() -> { w[0].jobFinished(); w[0].dispose(); });
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
