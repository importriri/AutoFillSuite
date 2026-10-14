package app.ui;

import app.config.SettingsManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

// "già registrata oggi" opened a Sì/No window with Sì as the default button.
// the scanner ends every code with ENTER: the next scan, fired while the
// window was open, confirmed a correction nobody had read. now ENTER, TAB and
// SPACE all land on No, and Sì takes the mouse. real keystrokes through the X
// server, with the Windows rule "the default button follows the focus" on.
//
//   xvfb-run java -cp out app.ui.ScanConfirmKeysTest
public final class ScanConfirmKeysTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        UIManager.put("Button.defaultButtonFollowsFocus", Boolean.TRUE);
        File home = Files.createTempDirectory("afs-confirm").toFile();
        System.setProperty("user.home", home.getPath());
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.SCAN_BATCH, true);   // collect: no robot burst in this test
        cfg.save();
        String report = "RUN;" + java.time.LocalDate.now() + ";08:00:00;lotto=-;modalita=scansione\n"
            + "Etichetta;Lotto;OraInvio;Esito;Registrazioni;Dettaglio\n"
            + "LBL001;LOT-A;08:00:05;OK;1;\n";
        Files.write(new File(home, "AutoFillSuite_report_" + java.time.LocalDate.now() + ".csv").toPath(),
                    report.getBytes(StandardCharsets.UTF_8));

        final JFrame frame = new JFrame("scan");
        final ScanModePanel[] sp = new ScanModePanel[1];
        final ResultsPanel[] rp = new ResultsPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            rp[0] = new ResultsPanel(() -> { });
            sp[0] = new ScanModePanel(rp[0], new RangeModeTest.NoopRunContext());
            frame.add(sp[0]);
            frame.setSize(900, 700);
            frame.setVisible(true);
        });
        Robot robot = new Robot();
        robot.setAutoDelay(15);

        // the next scan: a code and ENTER
        JDialog d = openQuestion(sp[0], robot);
        check("another lot on a label sent today opens the question", d != null);
        type(robot, KeyEvent.VK_4, KeyEvent.VK_2, KeyEvent.VK_ENTER);
        check("ENTER of the next scan closes the window", waitClosed(d));
        check("ENTER of the next scan does NOT queue the correction", rows(rp[0]) == 0);

        // a scanner set to end with TAB: TAB, then the next code's ENTER
        d = openQuestion(sp[0], robot);
        type(robot, KeyEvent.VK_4, KeyEvent.VK_TAB, KeyEvent.VK_4, KeyEvent.VK_ENTER);
        check("TAB then ENTER does not queue it either", waitClosed(d) && rows(rp[0]) == 0);

        d = openQuestion(sp[0], robot);
        type(robot, KeyEvent.VK_SPACE);
        check("SPACE answers No", waitClosed(d) && rows(rp[0]) == 0);

        d = openQuestion(sp[0], robot);
        type(robot, KeyEvent.VK_ESCAPE);
        check("ESC answers No", waitClosed(d) && rows(rp[0]) == 0);

        // Sì still works, with the mouse
        d = openQuestion(sp[0], robot);
        JButton yes = d == null ? null
            : SafeConfirm.button((JOptionPane) find(d.getContentPane(), JOptionPane.class), SafeConfirm.YES);
        check("Sì cannot take the keyboard focus", yes != null && !yes.isFocusable());
        if (yes != null) {
            Point p = yes.getLocationOnScreen();
            robot.mouseMove(p.x + yes.getWidth() / 2, p.y + yes.getHeight() / 2);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_MASK);
        }
        check("a click on Sì queues the correction", waitClosed(d) && rows(rp[0]) == 1);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    /** Scans LBL001 with another lot; returns the question window once it is up. */
    static JDialog openQuestion(ScanModePanel p, Robot robot) throws Exception {
        SwingUtilities.invokeLater(() -> {
            java.util.List<JTextField> f = RangeModeTest.fields(p);
            f.get(0).setText("LBL001");
            f.get(1).setText("LOT-B");
            f.get(1).postActionEvent();
        });
        for (int i = 0; i < 100; i++) {
            Thread.sleep(50);
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog && w.isShowing()) {
                    Thread.sleep(300);   // let it take the focus
                    Point o = w.getLocationOnScreen();
                    robot.mouseMove(o.x + 10, o.y + 10);   // no window manager: keys follow the pointer
                    return (JDialog) w;
                }
            }
        }
        return null;
    }

    static void type(Robot robot, int... keys) {
        for (int k : keys) {
            robot.keyPress(k);
            robot.keyRelease(k);
        }
    }

    static boolean waitClosed(JDialog d) throws Exception {
        if (d == null) return false;
        for (int i = 0; i < 60; i++) {
            if (!d.isShowing()) {
                Thread.sleep(150);   // the scan finishes on the interface thread
                return true;
            }
            Thread.sleep(50);
        }
        // never leave a modal window behind: the next case would hang on it
        SwingUtilities.invokeAndWait(d::dispose);
        return false;
    }

    static int rows(ResultsPanel rp) throws Exception {
        final int[] n = new int[1];
        SwingUtilities.invokeAndWait(() -> n[0] = rp.tableModel().getRowCount());
        return n[0];
    }

    static Component find(Container c, Class<?> type) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k)) return k;
            if (k instanceof Container) {
                Component f = find((Container) k, type);
                if (f != null) return f;
            }
        }
        return null;
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
