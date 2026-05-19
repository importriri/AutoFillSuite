package app.core;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;

/**
 * Singleton wrapper around java.awt.Robot.
 * Handles mouse fail-safe, clipboard paste, clicks and field reading.
 */
public class RobotEngine {

    private static RobotEngine instance;
    private Robot robot;

    private RobotEngine() {
        try {
            robot = new Robot();
            robot.setAutoDelay(15);
        } catch (AWTException e) {
            System.err.println("[RobotEngine] Init failed: " + e.getMessage());
        }
    }

    public static RobotEngine getInstance() {
        if (instance == null) instance = new RobotEngine();
        return instance;
    }

    public boolean isAvailable() { return robot != null; }

    // 5px tolerance for hardware micro-lag. Call BEFORE mouseMove.
    public boolean isMouseMoved(Point target) {
        Point now = MouseInfo.getPointerInfo().getLocation();
        return (Math.abs(now.x - target.x) > 5 || Math.abs(now.y - target.y) > 5);
    }

    public void click(int x, int y) throws InterruptedException {
        robot.mouseMove(x, y);
        Thread.sleep(30);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(50);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    public void doubleClick(int x, int y) throws InterruptedException {
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
        robot.keyPress(KeyEvent.VK_TAB);
        robot.keyRelease(KeyEvent.VK_TAB);
        Thread.sleep(60);
    }

    public void pressEnter() throws InterruptedException {
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
        Thread.sleep(30);
    }

    public void sleep(int ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    // Paste text using clipboard + CTRL+V (avoids accidental DOM selection).
    public void pasteText(String text) throws InterruptedException {
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
