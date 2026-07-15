package app.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Two numbers side by side — the scan mode's instrument: how many went in, how
 * many are still waiting. One lonely number told the operator nothing.
 */
public class StatPair extends JPanel {

    private final JLabel left  = new JLabel("0", SwingConstants.CENTER);
    private final JLabel right = new JLabel("0", SwingConstants.CENTER);

    public StatPair(String leftCaption, String rightCaption) {
        setLayout(new GridLayout(1, 2, 8, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        add(cell(left, leftCaption));
        add(cell(right, rightCaption));
    }

    private JPanel cell(JLabel value, String caption) {
        value.setFont(AppTheme.F_MONO_BIG.deriveFont(17f));
        value.setForeground(AppTheme.TEXT);

        JLabel cap = new JLabel(caption.toUpperCase(), SwingConstants.CENTER);
        cap.setFont(AppTheme.F_SMALL.deriveFont(9.5f));
        cap.setForeground(AppTheme.OVERLAY);

        JPanel p = new JPanel(new BorderLayout(0, 1));
        p.setOpaque(false);
        p.add(value, BorderLayout.CENTER);
        p.add(cap, BorderLayout.SOUTH);
        return p;
    }

    public void set(int a, int b) {
        left.setText(String.valueOf(a));
        right.setText(String.valueOf(b));
        // a non-empty queue is worth noticing, not alarming
        right.setForeground(b > 0 ? AppTheme.PEACH : AppTheme.OVERLAY);
    }

    public void setTone(Color c) {
        left.setForeground(c);
    }
}
