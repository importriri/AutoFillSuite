package app.core;

import javax.swing.*;
import java.awt.*;

/**
 * Abstract base for all automation tasks.
 * Template Method pattern: initial delay → loop → completion → cleanup.
 * Subclasses only need to implement the cycle logic.
 */
public abstract class AutomationTask implements Runnable {

    protected volatile boolean running = true;
    protected final RobotEngine robot = RobotEngine.getInstance();

    protected abstract boolean eseguiCiclo(int index) throws Exception;
    protected abstract int getTotale();
    protected abstract int getAttesaIniziale();
    protected abstract void onCompletato();
    protected abstract void onFinally();
    protected abstract void aggiornaProgresso(int current, int total);

    @Override
    public final void run() {
        try {
            int wait = getAttesaIniziale();
            for (int i = wait; i > 0 && running; i--) {
                final int sec = i;
                SwingUtilities.invokeLater(() -> mostraCountdown(sec));
                Thread.sleep(1000);
            }

            int total = getTotale();
            for (int i = 0; i < total && running; i++) {
                if (!eseguiCiclo(i)) break;
                aggiornaProgresso(i + 1, total);
            }

            if (running) {
                SwingUtilities.invokeLater(() -> {
                    onCompletato();
                    Toolkit.getDefaultToolkit().beep();
                });
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            e.printStackTrace();
            SwingUtilities.invokeLater(() -> mostraErrore(e.getMessage()));
        } finally {
            running = false;
            SwingUtilities.invokeLater(this::onFinally);
        }
    }

    public void stop() { running = false; }

    protected void mostraCountdown(int sec) {}

    protected void mostraErrore(String msg) {
        JOptionPane.showMessageDialog(null, "Error: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    protected boolean isMouseMoved(java.awt.Point target) {
        return robot.isMouseMoved(target);
    }

    protected void attivaFailSafe(String message) {
        running = false;
        Toolkit.getDefaultToolkit().beep();
        SwingUtilities.invokeLater(() ->
            JOptionPane.showMessageDialog(null,
                "⚠️ FAIL-SAFE: " + message,
                "Automation stopped",
                JOptionPane.WARNING_MESSAGE)
        );
    }
}
