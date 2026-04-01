package app.ui;

import app.core.*;
import app.config.*;

import javax.swing.*;
import java.awt.*;

/**
 * Tab 2 — Stampa Auto-Click.
 * Pattern: MVC.
 */
public class TabStampa extends JPanel {

    private Point coordStampa = null;
    private AutomationTask taskCorrente = null;
    private final SettingsManager cfg = SettingsManager.getInstance();

    private JSpinner spCount, spPausa, spWait, spMemoWait;
    private JLabel lblCoords, lblStato;
    private JButton btnMemo, btnAvvia, btnStop;
    private JProgressBar progressBar;

    public TabStampa() {
        setLayout(new GridBagLayout());
        setBackground(AppTheme.C_BG);
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        costruisciUI();
    }

    private void costruisciUI() {
        GridBagConstraints g = AppTheme.gbc();
        g.insets = new Insets(3, 4, 3, 4);
        int y = 0;

        // ── Parametri ─────────────────────────────────────────────────────────
        g.gridx = 0; g.gridy = y; g.weightx = 0; add(lbl("N° click:"), g);
        spCount = AppTheme.spinnerInt(cfg.getInt(SettingsManager.PRINT_COUNT, 1), 1, 9999);
        g.gridx = 1; g.weightx = 1.0; add(spCount, g);

        y++; g.gridx = 0; g.gridy = y; g.weightx = 0; add(lbl("Pausa tra click (s):"), g);
        spPausa = AppTheme.spinnerInt(cfg.getInt(SettingsManager.PRINT_PAUSE, 2), 1, 60);
        g.gridx = 1; add(spPausa, g);

        y++; g.gridx = 0; g.gridy = y; g.weightx = 0; add(lbl("Attesa avvio (s):"), g);
        spWait = AppTheme.spinnerInt(cfg.getInt(SettingsManager.PRINT_WAIT, 3), 1, 10);
        g.gridx = 1; add(spWait, g);

        // ── Sezione Coordinata ────────────────────────────────────────────────
        y++; g.gridx = 0; g.gridy = y; g.gridwidth = 2;
        add(AppTheme.separatore("📍  Coordinata tasto Stampa"), g);

        y++; g.gridy = y;
        JPanel pCoord = new JPanel(new BorderLayout(6, 0));
        pCoord.setBackground(AppTheme.C_BG);
        JPanel pLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        pLeft.setBackground(AppTheme.C_BG);
        btnMemo    = AppTheme.bottone("📍 Memo", AppTheme.C_MEMO);
        spMemoWait = AppTheme.spinnerInt(cfg.getInt(SettingsManager.PRINT_MEMO_WAIT, 4), 1, 10);
        spMemoWait.setPreferredSize(new Dimension(50, 22));
        pLeft.add(btnMemo); pLeft.add(spMemoWait); pLeft.add(AppTheme.labelHint("sec"));
        lblCoords = new JLabel("Non impostate", SwingConstants.RIGHT);
        lblCoords.setFont(AppTheme.F_MONO);
        lblCoords.setForeground(AppTheme.C_ERROR);
        pCoord.add(pLeft, BorderLayout.WEST);
        pCoord.add(lblCoords, BorderLayout.CENTER);
        add(pCoord, g);

        // Ripristina coordinate
        int cx = cfg.getInt(SettingsManager.PRINT_COORD_X, -1);
        int cy = cfg.getInt(SettingsManager.PRINT_COORD_Y, -1);
        if (cx >= 0 && cy >= 0) {
            coordStampa = new Point(cx, cy);
            lblCoords.setText("X:" + cx + " Y:" + cy);
            lblCoords.setForeground(AppTheme.C_SUCCESS);
        }

        // ── Spacer ────────────────────────────────────────────────────────────
        y++; g.gridy = y; g.weighty = 1.0;
        add(Box.createVerticalGlue(), g);
        g.weighty = 0;

        // ── Pulsanti ─────────────────────────────────────────────────────────
        y++; g.gridy = y;
        JPanel pBtn = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        pBtn.setBackground(AppTheme.C_BG);
        btnAvvia = AppTheme.bottone("▶  AVVIA", AppTheme.C_AVVIA);
        btnStop  = AppTheme.bottone("⏹  STOP",  AppTheme.C_STOP);
        btnStop.setEnabled(false);
        pBtn.add(btnAvvia); pBtn.add(btnStop);
        add(pBtn, g);

        // ── Progress bar ──────────────────────────────────────────────────────
        y++; g.gridy = y;
        progressBar = AppTheme.progressBar();
        progressBar.setString("Configura e avvia.");
        add(progressBar, g);

        // ── Listeners ────────────────────────────────────────────────────────
        btnMemo.addActionListener(e ->
            CoordMemorizer.avvia((int) spMemoWait.getValue(), btnMemo, lblCoords, null, p -> {
                coordStampa = p;
                cfg.set(SettingsManager.PRINT_COORD_X, p.x);
                cfg.set(SettingsManager.PRINT_COORD_Y, p.y);
                progressBar.setString("✅ Coordinate acquisite. Pronto.");
            })
        );
        btnAvvia.addActionListener(e -> avvia());
        btnStop.addActionListener(e -> {
            if (taskCorrente != null) taskCorrente.stop();
            progressBar.setString("⛔ Interrotto.");
        });
    }

    private void avvia() {
        if (coordStampa == null) {
            JOptionPane.showMessageDialog(this, "Memorizza prima le coordinate del tasto Stampa!"); return;
        }
        salvaImpostazioni();

        final int   totale  = (int) spCount.getValue();
        final int   pausaMs = (int) spPausa.getValue() * 1000;
        final int   attesa  = (int) spWait.getValue();
        final Point coord   = new Point(coordStampa);

        progressBar.setValue(0); progressBar.setString("0 / " + totale);
        btnAvvia.setEnabled(false); btnStop.setEnabled(true);

        taskCorrente = new AutomationTask() {
            @Override protected int getTotale()         { return totale; }
            @Override protected int getAttesaIniziale() { return attesa; }
            @Override protected void mostraCountdown(int sec) {
                SwingUtilities.invokeLater(() -> progressBar.setString("⏳ Avvio tra " + sec + "s..."));
            }

            @Override
            protected boolean eseguiCiclo(int i) throws Exception {
                int corr = i + 1;
                if (i > 0 && isMouseMoved(coord)) {
                    attivaFailSafe("Mouse mosso al click " + corr); return false;
                }
                robot.click(coord.x, coord.y);
                SwingUtilities.invokeLater(() ->
                    progressBar.setString("🖨️  Click " + corr + " / " + totale)
                );
                if (i < totale - 1 && running) robot.sleep(pausaMs);
                return true;
            }

            @Override
            protected void aggiornaProgresso(int corrente, int tot) {
                SwingUtilities.invokeLater(() ->
                    progressBar.setValue((int)((double) corrente / tot * 100))
                );
            }

            @Override protected void onCompletato() {
                progressBar.setValue(100);
                progressBar.setString("✅ Completato! " + totale + " click");
            }
            @Override protected void onFinally() {
                btnAvvia.setEnabled(true); btnStop.setEnabled(false);
            }
        };
        new Thread(taskCorrente).start();
    }

    public void salvaImpostazioni() {
        cfg.set(SettingsManager.PRINT_COUNT,     spCount.getValue());
        cfg.set(SettingsManager.PRINT_PAUSE,     spPausa.getValue());
        cfg.set(SettingsManager.PRINT_WAIT,      spWait.getValue());
        cfg.set(SettingsManager.PRINT_MEMO_WAIT, spMemoWait.getValue());
    }

    private JLabel lbl(String t) { JLabel l = new JLabel(t); l.setFont(AppTheme.F_BOLD); return l; }
}
