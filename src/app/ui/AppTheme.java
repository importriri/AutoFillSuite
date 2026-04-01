package app.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Centralized UI factory and theme constants.
 */
public class AppTheme {

    public static final int WIN_WIDTH  = 340;
    public static final int WIN_HEIGHT = 430;

    public static final Color C_ACCENT  = new Color(57, 170, 64);
    public static final Color C_AVVIA   = new Color(43, 138, 49);
    public static final Color C_STOP    = new Color(198, 40,  40);
    public static final Color C_RESET   = new Color(2,  119, 189);
    public static final Color C_MEMO    = new Color(84, 110, 122);
    public static final Color C_BG      = new Color(247, 247, 247);
    public static final Color C_SEP     = new Color(210, 210, 210);
    public static final Color C_HINT    = new Color(140, 140, 140);
    public static final Color C_SUCCESS = new Color(27,  94,  32);
    public static final Color C_ERROR   = new Color(183, 28,  28);
    public static final Color C_WHITE   = Color.WHITE;
    public static final Color C_FIELD   = new Color(252, 252, 252);

    public static final Font F_LABEL  = new Font("SansSerif", Font.PLAIN,  11);
    public static final Font F_BOLD   = new Font("SansSerif", Font.BOLD,   11);
    public static final Font F_STATUS = new Font("SansSerif", Font.ITALIC, 11);
    public static final Font F_MONO   = new Font("Consolas",  Font.BOLD,   11);
    public static final Font F_HINT   = new Font("SansSerif", Font.PLAIN,  10);
    public static final Font F_QR     = new Font("Consolas",  Font.BOLD,   13);
    public static final Font F_TITLE  = new Font("SansSerif", Font.BOLD,   12);

    public static final int PAD = 8;
    public static final int GAP = 3;

    public static JButton bottone(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(C_WHITE);
        b.setFont(F_BOLD);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setOpaque(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setMargin(new Insets(4, 12, 4, 12));
        return b;
    }

    public static JSpinner spinnerInt(int val, int min, int max) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(val, min, max, 1));
        sp.setFont(F_LABEL);
        ((JSpinner.DefaultEditor) sp.getEditor()).getTextField().setFont(F_LABEL);
        return sp;
    }

    public static JSpinner spinnerDouble(double val, double min, double max, double step) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(val, min, max, step));
        sp.setFont(F_LABEL);
        JSpinner.NumberEditor ed = new JSpinner.NumberEditor(sp, "0.0");
        ed.getTextField().setFont(F_LABEL);
        sp.setEditor(ed);
        return sp;
    }

    public static JTextField textField() {
        JTextField tf = new JTextField();
        tf.setFont(F_LABEL);
        tf.setBackground(C_FIELD);
        return tf;
    }

    public static JTextField textFieldQr() {
        JTextField tf = new JTextField();
        tf.setFont(F_QR);
        tf.setBackground(C_FIELD);
        return tf;
    }

    public static JLabel labelHint(String text) {
        JLabel l = new JLabel(text);
        l.setFont(F_HINT);
        l.setForeground(C_HINT);
        return l;
    }

    public static JLabel labelStato(String text) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setFont(F_STATUS);
        l.setForeground(C_HINT);
        return l;
    }

    public static JProgressBar progressBar() {
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setString("—");
        bar.setFont(F_HINT);
        bar.setForeground(C_ACCENT);
        return bar;
    }

    public static GridBagConstraints gbc() {
        GridBagConstraints g = new GridBagConstraints();
        g.fill    = GridBagConstraints.HORIZONTAL;
        g.insets  = new Insets(GAP, GAP, GAP, GAP);
        g.weightx = 1.0;
        return g;
    }

    public static JPanel separatore(String title) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setBackground(C_BG);
        p.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
        JLabel l = new JLabel(title);
        l.setFont(F_HINT);
        l.setForeground(new Color(90, 90, 90));
        JSeparator sep = new JSeparator();
        sep.setForeground(C_SEP);
        p.add(l, BorderLayout.WEST);
        p.add(sep, BorderLayout.CENTER);
        return p;
    }

    private AppTheme() {}
}
