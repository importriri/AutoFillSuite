package app.core;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Captures a screen coordinate after a countdown: the operator has a few
 * seconds to place the mouse on the target, then the position is read.
 * The status label is optional — callers with only a coord label get the
 * countdown there instead. Colors are the caller's business.
 */
public final class CoordMemorizer {

    private CoordMemorizer() {}

    public static void capture(int seconds, JButton triggerBtn, JLabel coordLabel,
                               JLabel statusLabel, Consumer<Point> onDone) {
        triggerBtn.setEnabled(false);
        new Thread(() -> {
            try {
                for (int i = seconds; i > 0; i--) {
                    final int s = i;
                    SwingUtilities.invokeLater(() -> {
                        JLabel target = (statusLabel != null) ? statusLabel : coordLabel;
                        target.setText("Posiziona il mouse... " + s + "s");
                    });
                    Thread.sleep(1000);
                }
                final Point p = MouseInfo.getPointerInfo().getLocation();
                SwingUtilities.invokeLater(() -> {
                    coordLabel.setText("X:" + p.x + "  Y:" + p.y);
                    if (statusLabel != null) statusLabel.setText("Coordinate salvate.");
                    onDone.accept(p);
                    triggerBtn.setEnabled(true);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                SwingUtilities.invokeLater(() -> triggerBtn.setEnabled(true));
            }
        }, "coord-capture").start();
    }
}
