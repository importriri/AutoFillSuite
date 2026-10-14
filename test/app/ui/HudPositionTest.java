package app.ui;

import app.config.SettingsManager;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;

// the window position is saved every time the window moves. in HUD mode
// the window IS the HUD, at the bottom of the screen: closed from there,
// the next start opened the whole cockpit at the bottom edge, mostly off
// screen (the visibility check only looks at its top strip).
//
//   xvfb-run java -cp out app.ui.HudPositionTest
public final class HudPositionTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        File home = Files.createTempDirectory("afs-hudpos").toFile();
        System.setProperty("user.home", home.getPath());
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.UI_WIN_X, 100);
        cfg.set(SettingsManager.UI_WIN_Y, 80);
        cfg.save();
        MainWindow.exit = () -> { };

        final MainWindow[] w = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> w[0] = new MainWindow());
        Thread.sleep(800);
        final Point[] cockpit = new Point[1];
        SwingUtilities.invokeAndWait(() -> cockpit[0] = w[0].getLocation());

        java.lang.reflect.Method hud = MainWindow.class.getDeclaredMethod("setHudMode", boolean.class);
        hud.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try { hud.invoke(w[0], true); } catch (Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(1200);   // the position saver waits 400 ms after a move
        final Point[] inHud = new Point[1];
        SwingUtilities.invokeAndWait(() -> inHud[0] = w[0].getLocation());
        int x = cfg.getInt(SettingsManager.UI_WIN_X, -1), y = cfg.getInt(SettingsManager.UI_WIN_Y, -1);
        check("the HUD moved the window", !inHud[0].equals(cockpit[0]));
        check("the saved position is still the cockpit's, not the HUD's",
              x == cockpit[0].x && y == cockpit[0].y);

        SwingUtilities.invokeAndWait(() -> w[0].dispose());
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
