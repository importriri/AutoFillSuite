package app.core;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;

/**
 * Singleton wrapper around java.awt.Robot.
 * Handles mouse fail-safe, clipboard paste, clicks and field reading.
 */
public class RobotEngine {

    /** A failed creation is retried, not remembered forever. */
    private static final long RETRY_MS = 500;

    private static RobotEngine instance;
    private Robot robot;
    private long lastAttempt = 0;

    private RobotEngine() {
        robot();   // the usual case: it works at once
    }

    public static RobotEngine getInstance() {
        if (instance == null) instance = new RobotEngine();
        return instance;
    }

    /**
     * The app used to build its Robot once, in the constructor, and keep the
     * failure: a display that was not ready yet at startup (a session just
     * logged in, a remote desktop reconnecting) left the app unable to type for
     * the rest of the day, silently. Now the attempt is repeated on demand.
     */
    private synchronized Robot robot() {
        if (robot != null) return robot;
        long now = System.currentTimeMillis();
        if (now - lastAttempt < RETRY_MS) return null;
        lastAttempt = now;
        try {
            Robot fresh = new Robot();
            fresh.setAutoDelay(15);
            robot = fresh;
        } catch (AWTException e) {
            System.err.println("[RobotEngine] Init failed: " + e.getMessage());
        } catch (SecurityException e) {
            System.err.println("[RobotEngine] Init refused: " + e.getMessage());
        }
        return robot;
    }

    public boolean isAvailable() { return robot() != null; }

    /** Every action goes through here: no method may ever NPE on a missing Robot. */
    private Robot required() {
        Robot r = robot();
        if (r == null) throw new IllegalStateException("Robot non disponibile");
        return r;
    }

    /** Tolerance for hardware micro-lag: below this, the pointer did not move. */
    public static final int TOLERANCE = 5;

    /**
     * Pure geometry, so the fail-safe can be tested without a screen.
     * A null on either side is NOT a movement: an unreadable pointer must never
     * stop a run that is going fine.
     */
    public static boolean movedFrom(Point now, Point target, int tolerance) {
        if (now == null || target == null) return false;
        return Math.abs(now.x - target.x) > tolerance
            || Math.abs(now.y - target.y) > tolerance;
    }

    // Call BEFORE mouseMove: it compares against where the robot left the pointer.
    public boolean isMouseMoved(Point target) {
        return isMouseMoved(target, TOLERANCE);
    }

    public boolean isMouseMoved(Point target, int tolerance) {
        // getPointerInfo() returns null on a locked session or a headless X:
        // no reading is not the same as "the operator grabbed the mouse"
        PointerInfo info = MouseInfo.getPointerInfo();
        if (info == null) return false;
        return movedFrom(info.getLocation(), target, tolerance);
    }

    public void click(int x, int y) throws InterruptedException {
        Robot robot = required();
        robot.mouseMove(x, y);
        Thread.sleep(30);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(50);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    public void doubleClick(int x, int y) throws InterruptedException {
        Robot robot = required();
        robot.mouseMove(x, y);
        Thread.sleep(30);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(50);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(60);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(50);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    public void pressTab() throws InterruptedException {
        Robot robot = required();
        robot.keyPress(KeyEvent.VK_TAB);
        robot.keyRelease(KeyEvent.VK_TAB);
        Thread.sleep(60);
    }

    public void pressEnter() throws InterruptedException {
        Robot robot = required();
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
        Thread.sleep(30);
    }

    public void sleep(int ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    // Paste text using clipboard + CTRL+V (avoids accidental DOM selection).
    public void pasteText(String text) throws InterruptedException {
        Robot robot = required();
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(new StringSelection(text), null);
        Thread.sleep(35);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(30);
    }

    // Reads whatever is in the currently focused browser field via CTRL+A+C.
    public String readFocusedFieldContent() throws InterruptedException {
        Robot robot = required();
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(""), null);
        Thread.sleep(30);

        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(40);

        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_C);
        robot.keyRelease(KeyEvent.VK_C);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(60);

        try {
            Transferable t = Toolkit.getDefaultToolkit()
                .getSystemClipboard().getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return (String) t.getTransferData(DataFlavor.stringFlavor);
            }
        } catch (Exception e) {
            System.err.println("[RobotEngine] readFocusedFieldContent error: " + e.getMessage());
        }
        return "";
    }
}
