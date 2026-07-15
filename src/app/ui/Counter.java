package app.ui;

import javax.swing.*;
import java.awt.*;

/**
 * The instrument: a big glanceable number with a caption and a thin progress
 * line. This is what the operator looks at from two meters away — not a form.
 */
public class Counter extends JPanel {

    private final JLabel value = new JLabel("—", SwingConstants.CENTER);
    private final JLabel caption = new JLabel("", SwingConstants.CENTER);
    private final JProgressBar line = AppTheme.thinLine(5);

    public Counter(String captionText) {
        setLayout(new BorderLayout(0, 2));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));

        value.setFont(AppTheme.F_MONO_BIG.deriveFont(20f));
        value.setForeground(AppTheme.TEXT);

        caption.setFont(AppTheme.F_SMALL.deriveFont(9.5f));
        caption.setForeground(AppTheme.OVERLAY);
        caption.setText(captionText.toUpperCase());

        line.setValue(0);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(value, BorderLayout.CENTER);
        top.add(caption, BorderLayout.SOUTH);

        add(top, BorderLayout.CENTER);
        add(line, BorderLayout.SOUTH);
    }

    /** Big number as "current / total" with the line following along. */
    public void set(int current, int total) {
        value.setText(current + " / " + total);
        line.setValue(total > 0 ? (int) ((double) current / total * 100) : 0);
    }

    /** Free-form reading (dual scan: "24 · coda 2"). */
    public void set(String text, int percent) {
        value.setText(text);
        line.setValue(percent);
    }

    public void setCaption(String text) {
        caption.setText(text.toUpperCase());
    }

    public void setTone(Color c) {
        value.setForeground(c);
        line.setForeground(c);
    }
}
