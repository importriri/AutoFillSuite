package app.ui;

import app.core.*;
import app.config.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Tab 3 — Dual Scan Helper (Doppia).
 *
 * FLUSSO:
 *  1. Scanner spara QR1 → TAB automatico → focus su QR2
 *  2. Scanner spara QR2 → robot parte automaticamente
 *  3. Robot: doubleClick casella1 browser → QR1 → TAB → QR2 → TAB → ENTER (SAVE)
 *  4. Verifica SAVE: click casella1, CTRL+A+C, legge clipboard
 *     → vuota = ✅ salvato, piena = ⚠️ SAVE fallito
 *  5. Torna focus su QR1 Java → pronto per i prossimi due QR
 *
 * Pattern: MVC.
 */
public class TabDualScan extends JPanel {

    private Point coordCasella1 = null;
    private volatile boolean isRunning = false;
    private int contatore = 0;

    private final SettingsManager cfg   = SettingsManager.getInstance();
    private final RobotEngine     robot = RobotEngine.getInstance();

    // UI
    private JTextField tfQr1, tfQr2;
    private JLabel lblCoords, lblStato, lblContatore;
    private JButton btnMemo, btnResetCampi;
    private JSpinner spMemoWait, spFocus, spKey, spEnter;

    public TabDualScan() {
        setLayout(new GridBagLayout());
        setBackground(AppTheme.C_BG);
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        costruisciUI();
    }

    private void costruisciUI() {
        GridBagConstraints g = AppTheme.gbc();
        g.insets = new Insets(3, 4, 3, 4);
        int y = 0;

        // ── Sezione Coordinata ────────────────────────────────────────────────
        g.gridx = 0; g.gridy = y; g.gridwidth = 2;
        add(AppTheme.separatore("📍  Coordinata Casella 1 (browser)"), g);

        y++; g.gridy = y;
        JPanel pCoord = new JPanel(new BorderLayout(6, 0));
        pCoord.setBackground(AppTheme.C_BG);
        JPanel pLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        pLeft.setBackground(AppTheme.C_BG);
        btnMemo    = AppTheme.bottone("📍 Memo", AppTheme.C_MEMO);
        spMemoWait = AppTheme.spinnerInt(cfg.getInt(SettingsManager.SCAN_MEMO_WAIT, 4), 1, 10);
        spMemoWait.setPreferredSize(new Dimension(50, 22));
        pLeft.add(btnMemo); pLeft.add(spMemoWait); pLeft.add(AppTheme.labelHint("sec"));
        lblCoords = new JLabel("Non impostate", SwingConstants.RIGHT);
        lblCoords.setFont(AppTheme.F_MONO);
        lblCoords.setForeground(AppTheme.C_ERROR);
        pCoord.add(pLeft, BorderLayout.WEST);
        pCoord.add(lblCoords, BorderLayout.CENTER);
        add(pCoord, g);

        // Ripristina coordinate salvate
        int cx = cfg.getInt(SettingsManager.SCAN_COORD_X, -1);
        int cy = cfg.getInt(SettingsManager.SCAN_COORD_Y, -1);
        if (cx >= 0 && cy >= 0) {
            coordCasella1 = new Point(cx, cy);
            lblCoords.setText("X:" + cx + " Y:" + cy);
            lblCoords.setForeground(AppTheme.C_SUCCESS);
        }

        // ── Sezione Tempi ─────────────────────────────────────────────────────
        y++; g.gridy = y;
        add(AppTheme.separatore("⚙  Tempi Robot"), g);

        // Griglia 2 righe × 3 colonne: label + spinner
        y++; g.gridy = y;
        JPanel pTempi = new JPanel(new GridLayout(2, 6, 4, 2));
        pTempi.setBackground(AppTheme.C_BG);

        // Valori default ottimizzati per sito con server (rete locale ~150ms RTT)
        // Focus: 500ms — tempo per il browser di portare il cursore nel campo
        // Tasti: 120ms — pausa tra un'azione e l'altra
        // Dopo ENTER: 600ms — tempo che il server impiega a processare e resettare i campi
        spFocus = AppTheme.spinnerInt(cfg.getInt(SettingsManager.SCAN_FOCUS, 500), 50, 5000);
        spKey   = AppTheme.spinnerInt(cfg.getInt(SettingsManager.SCAN_KEY,   120), 10, 2000);
        spEnter = AppTheme.spinnerInt(cfg.getInt(SettingsManager.SCAN_ENTER, 600), 50, 5000);

        pTempi.add(labelTempi("Focus click (ms)"));
        pTempi.add(labelTempi("Pausa tasti (ms)"));
        pTempi.add(labelTempi("Dopo SAVE (ms)"));
        pTempi.add(spFocus);
        pTempi.add(spKey);
        pTempi.add(spEnter);
        add(pTempi, g);

        // Hint sotto i tempi
        y++; g.gridy = y;
        add(AppTheme.labelHint("  ↑ Aumenta «Dopo SAVE» se il sito è lento o su VPN"), g);

        // ── Sezione Scansione ─────────────────────────────────────────────────
        y++; g.gridy = y;
        add(AppTheme.separatore("📷  Dual QR Scan"), g);

        // QR 1
        y++; g.gridy = y; g.gridwidth = 1; g.weightx = 0;
        add(lbl("QR 1:"), g);
        tfQr1 = AppTheme.textFieldQr();
        g.gridx = 1; g.weightx = 1.0; add(tfQr1, g);

        // QR 2
        y++; g.gridx = 0; g.gridy = y; g.weightx = 0;
        add(lbl("QR 2:"), g);
        tfQr2 = AppTheme.textFieldQr();
        g.gridx = 1; g.weightx = 1.0; add(tfQr2, g);

        // ── Riga azioni + contatore ───────────────────────────────────────────
        y++; g.gridx = 0; g.gridy = y; g.gridwidth = 2;
        JPanel pAzioni = new JPanel(new BorderLayout(8, 0));
        pAzioni.setBackground(AppTheme.C_BG);

        btnResetCampi = AppTheme.bottone("✖ Reset", AppTheme.C_RESET);
        btnResetCampi.setMargin(new Insets(3, 8, 3, 8));

        lblContatore = new JLabel("✅  0  registrate", SwingConstants.RIGHT);
        lblContatore.setFont(AppTheme.F_BOLD);
        lblContatore.setForeground(AppTheme.C_HINT);

        pAzioni.add(btnResetCampi, BorderLayout.WEST);
        pAzioni.add(lblContatore, BorderLayout.EAST);
        add(pAzioni, g);

        // ── Stato ─────────────────────────────────────────────────────────────
        y++; g.gridy = y;
        lblStato = AppTheme.labelStato("Imposta coordinate, poi spara i QR.");
        add(lblStato, g);

        // ── Listeners ────────────────────────────────────────────────────────
        btnMemo.addActionListener(e ->
            CoordMemorizer.avvia((int) spMemoWait.getValue(), btnMemo, lblCoords, lblStato, p -> {
                coordCasella1 = p;
                cfg.set(SettingsManager.SCAN_COORD_X, p.x);
                cfg.set(SettingsManager.SCAN_COORD_Y, p.y);
            })
        );

        // Scanner → TAB dopo QR1: focus passa a QR2
        tfQr1.addActionListener(e -> {
            if (!tfQr1.getText().trim().isEmpty()) tfQr2.requestFocusInWindow();
        });

        // Scanner → ENTER dopo QR2: parte la sequenza
        tfQr2.addActionListener(e -> esegui());
        tfQr2.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                if (!tfQr2.getText().trim().isEmpty()) esegui();
            }
        });

        btnResetCampi.addActionListener(e -> resetCampi());
    }

    // ── Logica ────────────────────────────────────────────────────────────────

    private void esegui() {
        if (isRunning) return;
        String q1 = tfQr1.getText().trim();
        String q2 = tfQr2.getText().trim();
        if (q1.isEmpty() || q2.isEmpty()) return;

        if (coordCasella1 == null) {
            aggiornaStato("⛔ Memorizza le coordinate prima!", AppTheme.C_ERROR); return;
        }

        isRunning = true;
        tfQr1.setEnabled(false); tfQr2.setEnabled(false);
        aggiornaStato("⚙  Inserimento in corso...", new Color(0, 100, 200));
        salvaImpostazioni();

        final int   tFocus = (int) spFocus.getValue();
        final int   tKey   = (int) spKey.getValue();
        final int   tEnter = (int) spEnter.getValue();
        final Point coord  = new Point(coordCasella1);

        new Thread(() -> {
            try {
                // 1. Doppio click → casella 1 browser
                robot.doubleClick(coord.x, coord.y);
                robot.sleep(tFocus);

                // 2. QR1 → campo 1
                robot.pasteText(q1); robot.sleep(tKey);

                // 3. TAB → campo 2
                robot.pressTab(); robot.sleep(tKey);

                // 4. QR2 → campo 2
                robot.pasteText(q2); robot.sleep(tKey);

                // 5. TAB → pulsante SAVE
                robot.pressTab(); robot.sleep(tKey);

                // 6. ENTER → salva
                robot.pressEnter(); robot.sleep(tEnter);

                // 7. Verifica SAVE: click casella1, leggi contenuto
                //    Il sito resetta serial+batch dopo SAVE → il campo deve essere vuoto
                robot.doubleClick(coord.x, coord.y);
                robot.sleep(150);
                String contenuto = robot.readFocusedFieldContent();

                if (!contenuto.trim().isEmpty()) {
                    // Campo ancora pieno → SAVE non riuscito
                    Toolkit.getDefaultToolkit().beep();
                    SwingUtilities.invokeLater(() ->
                        aggiornaStato("⚠️  SAVE fallito! Controlla il browser.", AppTheme.C_ERROR)
                    );
                    return; // Non incrementa contatore, lascia i QR per ritentare
                }

                // 8. SAVE confermato → torna focus su QR1 Java
                contatore++;
                Point posApp = tfQr1.getLocationOnScreen();
                robot.click(posApp.x + 20, posApp.y + 10);

                SwingUtilities.invokeLater(() -> {
                    tfQr1.setText(""); tfQr2.setText("");
                    tfQr1.requestFocusInWindow();
                    lblContatore.setText("✅  " + contatore + "  registrate");
                    lblContatore.setForeground(AppTheme.C_SUCCESS);
                    aggiornaStato("✅  Salvato! Pronto per i prossimi.", AppTheme.C_SUCCESS);
                });

            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() ->
                    aggiornaStato("Errore: " + ex.getMessage(), AppTheme.C_ERROR)
                );
            } finally {
                isRunning = false;
                SwingUtilities.invokeLater(() -> {
                    tfQr1.setEnabled(true); tfQr2.setEnabled(true);
                });
            }
        }).start();
    }

    private void resetCampi() {
        if (isRunning) return;
        tfQr1.setText(""); tfQr2.setText("");
        tfQr1.requestFocusInWindow();
        aggiornaStato("Campi resettati. Pronto.", AppTheme.C_HINT);
    }

    private void aggiornaStato(String testo, Color colore) {
        lblStato.setText(testo);
        lblStato.setForeground(colore);
    }

    public void salvaImpostazioni() {
        cfg.set(SettingsManager.SCAN_MEMO_WAIT, spMemoWait.getValue());
        cfg.set(SettingsManager.SCAN_FOCUS,     spFocus.getValue());
        cfg.set(SettingsManager.SCAN_KEY,       spKey.getValue());
        cfg.set(SettingsManager.SCAN_ENTER,     spEnter.getValue());
    }

    // ── Helpers UI ────────────────────────────────────────────────────────────
    private JLabel lbl(String t) { JLabel l = new JLabel(t); l.setFont(AppTheme.F_BOLD); return l; }

    private JLabel labelTempi(String t) {
        JLabel l = new JLabel(t, SwingConstants.CENTER);
        l.setFont(AppTheme.F_HINT);
        l.setForeground(new Color(80, 80, 80));
        return l;
    }
}
