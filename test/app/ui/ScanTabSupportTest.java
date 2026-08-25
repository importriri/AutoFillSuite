package app.ui;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Regression for scanners configured with TAB as the suffix on QR2. */
public final class ScanTabSupportTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(ScanTabSupportTest::run);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void run() {
        ResultsPanel results = new ResultsPanel(() -> {});
        ScanModePanel panel = new ScanModePanel(results, new NoopRunContext());
        List<JTextField> fields = new ArrayList<>();
        collectTextFields(panel, fields);

        check("scan panel still has exactly QR1 and QR2 text fields", fields.size() == 2);
        if (fields.size() != 2) return;

        JTextField qr1 = fields.get(0);
        JTextField qr2 = fields.get(1);
        AtomicInteger actions = new AtomicInteger();
        qr2.addActionListener(e -> actions.incrementAndGet());

        KeyEvent tab = key(qr2, 0, KeyEvent.VK_TAB, '\t');
        check("plain TAB on QR2 is handled", ScanTabSupport.handle(tab, qr2));
        check("handled QR2 TAB is consumed", tab.isConsumed());
        check("QR2 TAB fires the same ActionEvent path as ENTER", actions.get() == 1);

        KeyEvent qr1Tab = key(qr1, 0, KeyEvent.VK_TAB, '\t');
        check("TAB on QR1 keeps normal focus traversal",
              !ScanTabSupport.handle(qr1Tab, qr1));
        check("QR1 TAB does not submit QR2", actions.get() == 1);

        KeyEvent shiftTab = key(qr2, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_TAB, '\t');
        check("Shift+TAB on QR2 keeps backward focus traversal",
              !ScanTabSupport.handle(shiftTab, qr2));
        check("Shift+TAB does not submit QR2", actions.get() == 1);

        KeyEvent enter = key(qr2, 0, KeyEvent.VK_ENTER, '\n');
        check("ENTER remains owned by the field's existing listener",
              !ScanTabSupport.handle(enter, qr2));
        check("shim does not duplicate ENTER", actions.get() == 1);

        JTextField outsider = new JTextField();
        KeyEvent outsideTab = key(outsider, 0, KeyEvent.VK_TAB, '\t');
        check("TAB outside scan mode is untouched",
              !ScanTabSupport.handle(outsideTab, outsider));
    }

    private static KeyEvent key(Component source, int modifiers, int code, char ch) {
        return new KeyEvent(source, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                            modifiers, code, ch);
    }

    private static void collectTextFields(Container root, List<JTextField> fields) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextField) fields.add((JTextField) child);
            if (child instanceof Container) collectTextFields((Container) child, fields);
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    private static final class NoopRunContext implements RunContext {
        @Override public String blockingCollision(Map<String, Point> targets) { return null; }
        @Override public void jobStarted(Runnable stopAction) {}
        @Override public void jobProgress(Color tone, String state, String number,
                                          String caption, int percent, boolean stoppable) {}
        @Override public void jobFinished() {}
        @Override public void focusHome(javax.swing.JComponent target) {}
    }
}
