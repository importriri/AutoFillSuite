package app.core;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Utility that waits N seconds then captures the current mouse position.
 * Used to let the user move the mouse over a target before the coordinates are saved.
 */
public class CoordMemorizer {

    public static void avvia(int seconds, JButton triggerBtn, JLabel coordLabel,
                             JLabel statusLabel, Consumer<Point> onDone) {
        triggerBtn.setEnabled(false);
        new Thread(() -> {
            try {
                for (int i = seconds; i > 0; i--) {
                    final int s = i;
                    SwingUtilities.invokeLater(() -> statusLabel.setText("Move mouse... " + s + "s"));
                    Thread.sleep(1000);
                }
                Point p = MouseInfo.getPointerInfo().getLocation();
                SwingUtilities.invokeLater(() -> {
                    coordLabel.setText("X:" + p.x + " Y:" + p.y);
                    coordLabel.setForeground(new Color(0, 140, 0));
                    statusLabel.setText("Coordinates saved.");
                    onDone.accept(p);
                    triggerBtn.setEnabled(true);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
}
