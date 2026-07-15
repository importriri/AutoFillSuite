package app.ui;

import javax.swing.*;
import java.awt.*;

/**
 * HUD — the app reduced to a strip at the bottom of the screen. While the robot
 * works nobody needs the fields; they need the state. Band, counter, one lever.
 * The cockpit is one click away, and the app can drop into this by itself when
 * a run starts.
 */
public class HudPanel extends JPanel {

    private final JLabel band = AppTheme.banner();
    private final JLabel value = new JLabel("—", SwingConstants.CENTER);
    private final JLabel caption = new JLabel(" ", SwingConstants.CENTER);
    private final JProgressBar line = AppTheme.thinLine(6);
    private final JButton btnStop;
    private final JButton btnExpand;

    public HudPanel(Runnable onStop, Runnable onExpand) {
        setLayout(new BorderLayout());
        setBackground(AppTheme.BASE);

        band.setFont(AppTheme.F_BOLD);
        band.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        // SUBTEXT: ON_ACCENT on plain BASE is invisible in both flavors
        btnExpand = AppTheme.iconButton(
            Icons.chevron(AppTheme.ICON, AppTheme.SUBTEXT, false), "Apri il pannello completo");
        btnExpand.addActionListener(e -> onExpand.run());

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBackground(AppTheme.BASE);
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
        top.add(band, BorderLayout.CENTER);
        top.add(btnExpand, BorderLayout.EAST);

        value.setFont(AppTheme.F_MONO_BIG.deriveFont(19f));
        value.setForeground(AppTheme.TEXT);
        caption.setFont(AppTheme.F_SMALL);
        caption.setForeground(AppTheme.OVERLAY);
        caption.setText("PRONTO");

        JPanel num = new JPanel(new BorderLayout());
        num.setOpaque(false);
        num.add(value, BorderLayout.CENTER);
        num.add(caption, BorderLayout.SOUTH);
        // width pinned, height measured: a forced 34 clipped the digits on windows
        num.setPreferredSize(new Dimension(110, num.getPreferredSize().height));



        btnStop = AppTheme.secondary("STOP", Icons.stop(AppTheme.ICON, AppTheme.SUBTEXT));
        btnStop.setEnabled(false);
        btnStop.addActionListener(e -> onStop.run());

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setBackground(AppTheme.BASE);
        row.setBorder(BorderFactory.createEmptyBorder(2, 10, 8, 10));
        row.add(num, BorderLayout.WEST);
        row.add(line, BorderLayout.CENTER);
        row.add(btnStop, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(row, BorderLayout.CENTER);
        idle();
    }

    public void idle() {
        setBand(AppTheme.SURFACE2, AppTheme.TEXT, "PRONTO");
        value.setText("—");
        value.setForeground(AppTheme.TEXT);
        caption.setText("IN ATTESA");
        line.setValue(0);
        btnStop.setEnabled(false);
    }

    /** Live state while a job runs; percent < 0 leaves the bar alone. */
    public void state(Color bg, String text, String number, String cap, int percent,
                      boolean stoppable) {
        setBand(bg, AppTheme.ON_ACCENT, text);
        value.setText(number);
        caption.setText(cap.toUpperCase());
        if (percent >= 0) line.setValue(percent);
        btnStop.setEnabled(stoppable);
    }

    public void tone(Color c) {
        value.setForeground(c);
        line.setForeground(c);
    }

    private void setBand(Color bg, Color fg, String text) {
        band.setBackground(bg);
        band.setForeground(fg);
        band.setText(text);
    }
}
