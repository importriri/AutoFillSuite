package app.core;

import javax.swing.*;
import java.awt.*;

/**
 * Abstract base for all automation tasks.
 * Template Method pattern: initial delay → loop → completion → cleanup.
 * Subclasses only implement the cycle logic.
 *
 * RULE: never a modal dialog from a task. A modal under an always-on-top
 * window deadlocks the whole app (the dialog blocks input, the window hides
 * it). Fail-safe and errors reach the UI through EDT hooks instead.
 */
public abstract class AutomationTask implements Runnable {

    protected volatile boolean running = true;
    protected final RobotEngine robot = RobotEngine.getInstance();

    protected abstract boolean executeCycle(int index) throws Exception;
    protected abstract int getTotal();
    protected abstract int getStartDelay();
    protected abstract void onCompleted();
    protected abstract void onFinally();
    protected abstract void updateProgress(int current, int total);

    @Override
    public final void run() {
        try {
            int wait = getStartDelay();
            for (int i = wait; i > 0 && running; i--) {
                final int sec = i;
                SwingUtilities.invokeLater(() -> showCountdown(sec));
                Thread.sleep(1000);
            }

            int total = getTotal();
            for (int i = 0; i < total && running; i++) {
                if (!executeCycle(i)) break;
                updateProgress(i + 1, total);
            }

            if (running) {
                SwingUtilities.invokeLater(() -> {
                    onCompleted();
                    Toolkit.getDefaultToolkit().beep();
                });
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            e.printStackTrace();
            Toolkit.getDefaultToolkit().beep();
            SwingUtilities.invokeLater(() -> showError(e.getMessage()));
        } finally {
            running = false;
            SwingUtilities.invokeLater(this::onFinally);
        }
    }

    public void stop() { running = false; }

    protected void showCountdown(int sec) {}

    /** UI hook, called on the EDT — status bar / banner, never a popup. */
    protected void showError(String msg) {
        System.err.println("[AutomationTask] " + msg);
    }

    protected boolean isMouseMoved(Point target) {
        return robot.isMouseMoved(target);
    }

    protected void triggerFailSafe(String message) {
        running = false;
        Toolkit.getDefaultToolkit().beep();
        SwingUtilities.invokeLater(() -> onFailSafe(message));
    }

    /** UI hook, called on the EDT — banner + status, never a popup. */
    protected void onFailSafe(String message) {}
}
