package app.ui;

import app.Main;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// the close that killed the last row: windowClosing did System.exit(0) at
// once, the coalescing timer (400 ms) never fired, and the report writer was
// killed mid-write. a send followed by an immediate close must leave the row
// in the day's report.
//
//   xvfb-run java -cp out app.ui.ShutdownTest
public final class ShutdownTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        File home = Files.createTempDirectory("afs-close").toFile();
        System.setProperty("user.home", home.getPath());
        CountDownLatch exited = new CountDownLatch(1);
        MainWindow.exit = exited::countDown;   // stay alive to read the file
        Main.main(new String[0]);

        MainWindow w = null;
        long until = System.currentTimeMillis() + 5000;
        while (w == null && System.currentTimeMillis() < until) {
            for (Window x : Window.getWindows()) if (x instanceof MainWindow && x.isShowing()) w = (MainWindow) x;
            Thread.sleep(100);
        }
        check("the app is up", w != null);
        if (w == null) { report(); return; }
        final MainWindow win = w;
        ResultsPanel rp = find(win, ResultsPanel.class);

        SwingUtilities.invokeAndWait(() -> {
            rp.beginRun(1, "CLOSE-LOT");
            rp.addLiveRow("LAST001", 1);   // a send: the timer is now pending
            win.closeApp(false);           // and the operator closes at once
        });
        check("the app went through its exit", exited.await(10, TimeUnit.SECONDS));
        File rep = new File(home, "AutoFillSuite_report_" + java.time.LocalDate.now() + ".csv");
        String body = rep.exists() ? new String(Files.readAllBytes(rep.toPath()), StandardCharsets.UTF_8) : "";
        check("the row sent right before closing is in the report", body.contains("LAST001"));
        check("with its INVIATA state", body.contains("INVIATA"));
        check("busy work is named before closing", win.busyReason() == null);
        report();
    }

    @SuppressWarnings("unchecked")
    static <T> T find(Container root, Class<T> cls) {
        for (Component c : root.getComponents()) {
            if (cls.isInstance(c)) return (T) c;
            if (c instanceof Container) {
                T t = find((Container) c, cls);
                if (t != null) return t;
            }
        }
        return null;
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }

    static void report() {
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }
}
