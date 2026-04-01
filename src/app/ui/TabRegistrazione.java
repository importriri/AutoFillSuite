package app.ui;

import app.core.*;
import app.config.*;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.*;

/**
 * Tab 1 — Registrazione Singola.
 * Layout compatto: tutto visibile senza scroll.
 * Pattern: MVC.
 */
public class TabRegistrazione extends JPanel {

    private Point coordCasella1 = null;
    private AutomationTask taskCorrente = null;
    private final SettingsManager cfg = SettingsManager.getInstance();

    // Campi logica interna (non visibili)
    private final JTextField tfPrefix   = new JTextField();
    private final JTextField tfStartSeq = new JTextField();

    // Campi visibili
    private JTextField tfScanInput, tfBatch;
    private JSpinner spCount, spWait, spFieldDelay, spPostEnter;
    private JSpinner spFixedDelay, spMinDelay, spMaxDelay, spMemoWait;
    private JComboBox<String> cmbDelayType;
    private JPanel pnlDelayVals;

    // Feedback
    private JLabel lblPreview, lblCoords;
    private JButton btnAvvia, btnStop, btnReset, btnMemo;
    private JProgressBar progressBar;

    public TabRegistrazione() {
        setLayout(new GridBagLayout());
        setBackground(AppTheme.C_BG);
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        costruisciUI();
    }

    private void costruisciUI() {
        GridBagConstraints g = AppTheme.gbc();
        g.insets = new Insets(3, 4, 3, 4);
        int y = 0;

        // ── Scan ─────────────────────────────────────────────────────────────
        g.gridx = 0; g.gridy = y; g.gridwidth = 1; g.weightx = 0;
        add(lbl("Etichetta:"), g);
        JPanel pScan = new JPanel(new BorderLayout(3, 0));
        pScan.setBackground(AppTheme.C_BG);
        tfScanInput = AppTheme.textField();
        JButton btnConferma = AppTheme.bottone("✔", AppTheme.C_MEMO);
        btnConferma.setMargin(new Insets(1, 6, 1, 6));
        pScan.add(tfScanInput, BorderLayout.CENTER);
        pScan.add(btnConferma, BorderLayout.EAST);
        g.gridx = 1; g.weightx = 1.0; add(pScan, g);

        // ── Anteprima range ───────────────────────────────────────────────────
        y++; g.gridx = 0; g.gridy = y; g.gridwidth = 2;
        lblPreview = new JLabel("Da: —   A: —", SwingConstants.CENTER);
        lblPreview.setFont(AppTheme.F_MONO);
        lblPreview.setForeground(AppTheme.C_HINT);
        lblPreview.setOpaque(true);
        lblPreview.setBackground(new Color(237, 247, 237));
        lblPreview.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(57, 170, 64, 80)),
            BorderFactory.createEmptyBorder(3, 8, 3, 8)
        ));
        add(lblPreview, g);

        // ── Batch ─────────────────────────────────────────────────────────────
        y++; g.gridx = 0; g.gridy = y; g.gridwidth = 1; g.weightx = 0;
        add(lbl("Batch:"), g);
        tfBatch = AppTheme.textField();
        g.gridx = 1; g.weightx = 1.0; add(tfBatch, g);

        // ── Quantità ──────────────────────────────────────────────────────────
        y++; g.gridx = 0; g.gridy = y; g.weightx = 0;
        add(lbl("Quantità:"), g);
        spCount = AppTheme.spinnerInt(cfg.getInt(SettingsManager.REG_COUNT, 10), 1, 9999);
        g.gridx = 1; g.weightx = 1.0; add(spCount, g);

        // ── Sezione Coordinata ────────────────────────────────────────────────
        y++; g.gridx = 0; g.gridy = y; g.gridwidth = 2;
        add(AppTheme.separatore("📍  Coordinata Casella 1"), g);

        y++; g.gridy = y;
        JPanel pCoord = new JPanel(new BorderLayout(6, 0));
        pCoord.setBackground(AppTheme.C_BG);
        JPanel pMemoLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        pMemoLeft.setBackground(AppTheme.C_BG);
        btnMemo    = AppTheme.bottone("📍 Memo", AppTheme.C_MEMO);
        spMemoWait = AppTheme.spinnerInt(cfg.getInt(SettingsManager.REG_MEMO_WAIT, 4), 1, 10);
        spMemoWait.setPreferredSize(new Dimension(50, 22));
        pMemoLeft.add(btnMemo);
        pMemoLeft.add(spMemoWait);
        pMemoLeft.add(AppTheme.labelHint("sec"));
        lblCoords = new JLabel("Non impostate", SwingConstants.RIGHT);
        lblCoords.setFont(AppTheme.F_MONO);
        lblCoords.setForeground(AppTheme.C_ERROR);
        pCoord.add(pMemoLeft, BorderLayout.WEST);
        pCoord.add(lblCoords, BorderLayout.CENTER);
        add(pCoord, g);

        // Ripristina coordinate salvate
        int cx = cfg.getInt(SettingsManager.REG_COORD_X, -1);
        int cy = cfg.getInt(SettingsManager.REG_COORD_Y, -1);
        if (cx >= 0 && cy >= 0) {
            coordCasella1 = new Point(cx, cy);
            lblCoords.setText("X:" + cx + " Y:" + cy);
            lblCoords.setForeground(AppTheme.C_SUCCESS);
        }

        // ── Sezione Tempi ─────────────────────────────────────────────────────
        y++; g.gridy = y;
        add(AppTheme.separatore("⏱  Tempi"), g);

        // Griglia tempi 2x2
        y++; g.gridy = y;
        JPanel pT1 = tempiPanel();
        spWait       = AppTheme.spinnerInt(cfg.getInt(SettingsManager.REG_WAIT, 3), 1, 10);
        spFieldDelay = AppTheme.spinnerDouble(cfg.getDouble(SettingsManager.REG_FIELD_DELAY, 0.3), 0.1, 3.0, 0.1);
        pT1.add(AppTheme.labelHint("Attesa avvio (s):")); pT1.add(spWait);
        pT1.add(AppTheme.labelHint("Pausa tasti (s):")); pT1.add(spFieldDelay);
        add(pT1, g);

        y++; g.gridy = y;
        JPanel pT2 = tempiPanel();
        spPostEnter = AppTheme.spinnerInt(cfg.getInt(SettingsManager.REG_POST_ENTER, 500), 100, 3000);
        pT2.add(AppTheme.labelHint("Attesa dopo SAVE (ms):")); pT2.add(spPostEnter);
        pT2.add(AppTheme.labelHint("↑ aumenta se sito lento")); pT2.add(new JLabel(""));
        add(pT2, g);

        // Ritmo tra cicli
        y++; g.gridy = y;
        JPanel pRitmo = tempiPanel();
        cmbDelayType = new JComboBox<>(new String[]{"Fisso", "Range casuale"});
        cmbDelayType.setFont(AppTheme.F_LABEL);
        pnlDelayVals = new JPanel(new CardLayout());
        pnlDelayVals.setBackground(AppTheme.C_BG);
        spFixedDelay = AppTheme.spinnerDouble(cfg.getDouble(SettingsManager.REG_FIXED_DELAY, 2.0), 0.1, 30.0, 0.5);
        spMinDelay   = AppTheme.spinnerDouble(cfg.getDouble(SettingsManager.REG_MIN_DELAY, 1.5), 0.1, 30.0, 0.5);
        spMaxDelay   = AppTheme.spinnerDouble(cfg.getDouble(SettingsManager.REG_MAX_DELAY, 3.5), 0.1, 30.0, 0.5);
        JPanel pFixed = new JPanel(new BorderLayout()); pFixed.setBackground(AppTheme.C_BG); pFixed.add(spFixedDelay);
        JPanel pRange = new JPanel(new GridLayout(1, 2, 2, 0)); pRange.setBackground(AppTheme.C_BG);
        pRange.add(spMinDelay); pRange.add(spMaxDelay);
        pnlDelayVals.add(pFixed, "FIXED");
        pnlDelayVals.add(pRange, "RANGE");
        pRitmo.add(AppTheme.labelHint("Ritmo cicli (s):")); pRitmo.add(cmbDelayType);
        pRitmo.add(new JLabel("")); pRitmo.add(pnlDelayVals);
        add(pRitmo, g);

        int delayIdx = cfg.getInt(SettingsManager.REG_DELAY_TYPE, 0);
        cmbDelayType.setSelectedIndex(delayIdx);
        if (delayIdx == 1) ((CardLayout) pnlDelayVals.getLayout()).show(pnlDelayVals, "RANGE");

        // ── Pulsanti ─────────────────────────────────────────────────────────
        y++; g.gridy = y;
        JPanel pBtn = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        pBtn.setBackground(AppTheme.C_BG);
        btnAvvia = AppTheme.bottone("▶  AVVIA", AppTheme.C_AVVIA);
        btnStop  = AppTheme.bottone("⏹  STOP",  AppTheme.C_STOP);
        btnReset = AppTheme.bottone("↺  RESET",  AppTheme.C_RESET);
        btnStop.setEnabled(false);
        pBtn.add(btnAvvia); pBtn.add(btnStop); pBtn.add(btnReset);
        add(pBtn, g);

        // ── Progress bar con stato integrato ─────────────────────────────────
        y++; g.gridy = y;
        progressBar = AppTheme.progressBar();
        progressBar.setString("Spara un codice per iniziare");
        add(progressBar, g);

        // ── Listeners ────────────────────────────────────────────────────────
        tfScanInput.addActionListener(e -> processaScansione());
        btnConferma.addActionListener(e -> processaScansione());
        spCount.addChangeListener(e -> aggiornaPreview());
        cmbDelayType.addActionListener(e -> {
            CardLayout cl = (CardLayout) pnlDelayVals.getLayout();
            cl.show(pnlDelayVals, cmbDelayType.getSelectedIndex() == 0 ? "FIXED" : "RANGE");
        });
        btnMemo.addActionListener(e ->
            CoordMemorizer.avvia((int) spMemoWait.getValue(), btnMemo, lblCoords, null, p -> {
                coordCasella1 = p;
                cfg.set(SettingsManager.REG_COORD_X, p.x);
                cfg.set(SettingsManager.REG_COORD_Y, p.y);
                progressBar.setString("✅ Coordinate acquisite. Pronto.");
            })
        );
        btnAvvia.addActionListener(e -> avvia());
        btnStop.addActionListener(e -> {
            if (taskCorrente != null) taskCorrente.stop();
            progressBar.setString("⛔ Interrotto.");
        });
        btnReset.addActionListener(e -> reset());

        ChangeListener validaRange = e -> {
            double min = (double) spMinDelay.getValue();
            double max = (double) spMaxDelay.getValue();
            if (min >= max) spMaxDelay.setValue(min + 0.5);
        };
        spMinDelay.addChangeListener(validaRange);
        spMaxDelay.addChangeListener(validaRange);
    }

    // ── Logica ────────────────────────────────────────────────────────────────

    private void processaScansione() {
        String raw = tfScanInput.getText().trim();
        if (raw.length() < 4) { progressBar.setString("⚠ Codice troppo corto."); return; }
        String seqStr = raw.substring(raw.length() - 3);
        if (!seqStr.matches("\\d{3}")) {
            progressBar.setString("⚠ Ultime 3 cifre non numeriche.");
            tfScanInput.setText(""); return;
        }
        tfPrefix.setText(raw.substring(0, raw.length() - 3));
        tfStartSeq.setText(seqStr);
        tfScanInput.setText("");
        tfBatch.requestFocusInWindow();
        progressBar.setString("✓ Letto: " + raw);
        aggiornaPreview();
    }

    private void aggiornaPreview() {
        if (tfPrefix.getText().isEmpty()) return;
        try {
            long s = Long.parseLong(tfStartSeq.getText());
            int  c = (int) spCount.getValue();
            lblPreview.setText("Da: " + tfPrefix.getText() + String.format("%03d", s)
                + "   →   " + tfPrefix.getText() + String.format("%03d", s + c - 1));
            lblPreview.setForeground(AppTheme.C_SUCCESS);
        } catch (Exception ignored) {}
    }

    private void avvia() {
        if (coordCasella1 == null) {
            JOptionPane.showMessageDialog(this, "Memorizza prima le coordinate della Casella 1!"); return;
        }
        if (tfBatch.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Inserisci il Cable Batch!"); return;
        }
        salvaImpostazioni();

        final int    totale    = (int) spCount.getValue();
        final int    attesaVia = (int) spWait.getValue();
        final int    fDelay    = (int) ((double) spFieldDelay.getValue() * 1000);
        final int    postEnter = (int) spPostEnter.getValue();
        final String prefix    = tfPrefix.getText();
        final String batch     = tfBatch.getText().trim();
        final long   seq0      = Long.parseLong(tfStartSeq.getText());
        final Point  coord     = new Point(coordCasella1);
        final boolean useRange = cmbDelayType.getSelectedIndex() == 1;
        final int    fixedMs   = (int) ((double) spFixedDelay.getValue() * 1000);
        final int    minMs     = (int) ((double) spMinDelay.getValue() * 1000);
        final int    maxMs     = (int) ((double) spMaxDelay.getValue() * 1000);

        progressBar.setValue(0);
        progressBar.setString("0 / " + totale);
        btnAvvia.setEnabled(false); btnStop.setEnabled(true);
        btnReset.setEnabled(false); tfScanInput.setEnabled(false);

        taskCorrente = new AutomationTask() {
            @Override protected int getTotale()         { return totale; }
            @Override protected int getAttesaIniziale() { return attesaVia; }
            @Override protected void mostraCountdown(int sec) {
                SwingUtilities.invokeLater(() -> progressBar.setString("⏳ Partenza tra " + sec + "s..."));
            }

            @Override
            protected boolean eseguiCiclo(int i) throws Exception {
                String cod  = prefix + String.format("%03d", seq0 + i);
                int    corr = i + 1;

                if (i > 0 && isMouseMoved(coord)) {
                    attivaFailSafe("Mouse mosso al ciclo " + corr);
                    return false;
                }

                SwingUtilities.invokeLater(() -> progressBar.setString("📝 " + corr + "/" + totale + " — " + cod));

                robot.doubleClick(coord.x, coord.y); robot.sleep(300);
                robot.pasteText(cod);                robot.sleep(fDelay);
                robot.pressTab();                    robot.sleep(80);
                robot.pasteText(batch);              robot.sleep(fDelay);
                robot.pressTab();                    robot.sleep(100);
                robot.pressEnter();                  robot.sleep(postEnter);

                if (i < totale - 1 && running) {
                    int delay = useRange ? minMs + (int)(Math.random() * (maxMs - minMs)) : fixedMs;
                    robot.sleep(delay);
                }
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
                progressBar.setString("✅ Completato! " + totale + " etichette");
            }

            @Override protected void onFinally() {
                btnAvvia.setEnabled(true); btnStop.setEnabled(false);
                btnReset.setEnabled(true); tfScanInput.setEnabled(true);
            }
        };
        new Thread(taskCorrente).start();
    }

    private void reset() {
        tfScanInput.setText(""); tfPrefix.setText(""); tfStartSeq.setText(""); tfBatch.setText("");
        lblPreview.setText("Da: —   A: —"); lblPreview.setForeground(AppTheme.C_HINT);
        progressBar.setValue(0); progressBar.setString("Spara un codice per iniziare");
    }

    public void salvaImpostazioni() {
        cfg.set(SettingsManager.REG_COUNT,       spCount.getValue());
        cfg.set(SettingsManager.REG_WAIT,        spWait.getValue());
        cfg.set(SettingsManager.REG_FIELD_DELAY, spFieldDelay.getValue());
        cfg.set(SettingsManager.REG_POST_ENTER,  spPostEnter.getValue());
        cfg.set(SettingsManager.REG_DELAY_TYPE,  cmbDelayType.getSelectedIndex());
        cfg.set(SettingsManager.REG_FIXED_DELAY, spFixedDelay.getValue());
        cfg.set(SettingsManager.REG_MIN_DELAY,   spMinDelay.getValue());
        cfg.set(SettingsManager.REG_MAX_DELAY,   spMaxDelay.getValue());
        cfg.set(SettingsManager.REG_MEMO_WAIT,   spMemoWait.getValue());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private JLabel lbl(String t) { JLabel l = new JLabel(t); l.setFont(AppTheme.F_BOLD); return l; }
    private JPanel tempiPanel() {
        JPanel p = new JPanel(new GridLayout(1, 4, 4, 0));
        p.setBackground(AppTheme.C_BG); return p;
    }
}
