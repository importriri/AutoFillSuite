package app.ui;

import app.config.SettingsManager;
import app.core.CoordMemorizer;
import app.core.VerificationHistory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.FocusEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ⚙ — modeless settings window, opened by the gear button in the header.
 * One tab per area, each holding its own coordinates AND timings, at its
 * natural size. Every change is written to disk the moment it happens, so the
 * operational tabs simply read SettingsManager when they start.
 *
 * Layout of a tab: a "POSIZIONI" section with the coordinates, then a
 * "TEMPI" section with the knobs, each row labelled and hinted — an
 * industrial control panel, not a wall of spinners.
 */
public class SettingsWindow extends JDialog {

    private final SettingsManager cfg = SettingsManager.getInstance();
    private JSpinner spMemoWait;

    public SettingsWindow(Window owner) {
        super(owner, "Impostazioni", ModalityType.MODELESS);
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(AppTheme.BASE);
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        root.add(buildHeader(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        AppTheme.flatTabs(tabs);
        tabs.addTab("A intervallo", page(buildRegister()));
        tabs.addTab("Verifica",      page(buildVerify()));
        tabs.addTab("Stampa",        page(buildPrint()));
        tabs.addTab("A scansione",   page(buildDualScan()));
        tabs.addTab("Finestra",      page(buildWindow()));
        tabs.addTab("Storico",       page(buildHistory()));
        // width pinned, height MEASURED: a JTabbedPane already asks for its
        // tallest tab, so pack() sizes the dialog right on any platform's font
        // metrics — a hard-coded height is how the main window got clipped
        tabs.setPreferredSize(new Dimension(560, tabs.getPreferredSize().height));
        root.add(tabs, BorderLayout.CENTER);

        JButton close = AppTheme.ghost("Chiudi");
        close.addActionListener(e -> setVisible(false));
        JLabel credit = AppTheme.hint("github.com/importriri");
        credit.setToolTipText("Apri il repository");
        credit.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        credit.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(new java.net.URI("https://github.com/importriri"));
                } catch (Exception ignored) { }
            }
        });
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(credit, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(AppTheme.hint("Le modifiche si salvano subito."));
        right.add(Box.createHorizontalStrut(10));
        right.add(close);
        south.add(right, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);

        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
    }

    /** Shared row: memo countdown + flavor switch. */
    private JPanel buildHeader() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        p.setOpaque(false);

        spMemoWait = memoWaitSpinner();
        p.add(AppTheme.label("Attesa memo (s)"));
        p.add(spMemoWait);
        p.add(AppTheme.hint("countdown prima di catturare il mouse"));
        p.add(Box.createHorizontalStrut(14));

        p.add(AppTheme.label("Tema"));
        JComboBox<String> flavor = AppTheme.combo(new String[] { "Mocha (scuro)", "Latte (chiaro)" });
        flavor.setSelectedIndex(AppTheme.LATTE ? 1 : 0);
        flavor.addActionListener(e -> {
            String chosen = flavor.getSelectedIndex() == 1 ? "latte" : "mocha";
            if (chosen.equals(cfg.get(SettingsManager.UI_FLAVOR, "mocha"))) return;
            save(SettingsManager.UI_FLAVOR, chosen);
            JOptionPane.showMessageDialog(this,
                "Il tema si applica al prossimo avvio dell'app.",
                "Tema", JOptionPane.INFORMATION_MESSAGE);
        });
        p.add(flavor);
        return p;
    }

    // ── tab: registrazione ────────────────────────────────────────────────

    private JPanel buildRegister() {
        Page p = new Page();
        p.section("Posizioni");
        p.coordRow("Casella 1 (serial)", SettingsManager.REG_COORD_X, SettingsManager.REG_COORD_Y,
                   "il primo campo del form sul sito");
        p.section("Tempi");
        p.spinRow("Attesa avvio", intSp(SettingsManager.REG_WAIT, 3, 1, 10), "s",
                  "tempo per passare al browser dopo AVVIA");
        p.spinRow("Pausa tra i tasti", dblSp(SettingsManager.REG_FIELD_DELAY, 0.3, 0.1, 5.0, 0.1), "s",
                  "quanto attendere tra un TAB e l'altro");
        p.spinRow("Attesa dopo SAVE", intSp(SettingsManager.REG_POST_ENTER, 500, 50, 5000), "ms",
                  "Alzalo se il sito è lento a salvare");
        p.spinRow("Ritmo tra i cicli", dblSp(SettingsManager.REG_FIXED_DELAY, 2.0, 0.5, 60.0, 0.5), "s",
                  "pausa tra un'etichetta e la successiva");
        p.section("Verifica");
        JCheckBox autoR = check("Verifica automatica a fine run",
            cfg.getBool(SettingsManager.REG_VERIFY_AUTO, true));
        autoR.addActionListener(e -> save(SettingsManager.REG_VERIFY_AUTO, autoR.isSelected()));
        p.row(autoR, "a run finito l'app scarica ed esegue il confronto da sola");
        return p.panel;
    }

    // ── tab: verifica ─────────────────────────────────────────────────────

    private JPanel buildVerify() {
        Page p = new Page();
        p.section("Posizioni");
        p.coordRow("Export CSV", SettingsManager.REG_EXPORT_COORD_X,
                   SettingsManager.REG_EXPORT_COORD_Y,
                   "il bottone che scarica il CSV (vale anche per Dual Scan)");

        p.section("Cartelle e file");
        JTextField dir = AppTheme.field();
        dir.setText(cfg.get(SettingsManager.REG_DOWNLOAD_DIR, defaultDownloads()));
        saveOnBlur(dir, SettingsManager.REG_DOWNLOAD_DIR);
        p.fieldRow("Cartella download", dir, "dove Chrome salva i file demo-export (N).csv");

        JTextField prefix = AppTheme.field();
        prefix.setText(cfg.get(SettingsManager.REG_EXPORT_PREFIX, "demo-export"));
        saveOnBlur(prefix, SettingsManager.REG_EXPORT_PREFIX);
        p.fieldRow("Prefisso export", prefix,
                   "inizio del nome dei file scaricati dal portale (vuoto = tutti)");

        JTextField repDir = AppTheme.field();
        repDir.setText(cfg.get(SettingsManager.REPORT_DIR, System.getProperty("user.home")));
        saveOnBlur(repDir, SettingsManager.REPORT_DIR);
        p.fieldRow("Cartella report", repDir, "dove finisce il CSV del run (SALVA REPORT)");

        p.section("Attese");
        p.spinRow("Timeout export", intSp(SettingsManager.REG_EXPORT_TIMEOUT_S, 30, 5, 300), "s",
                  "quanto aspettare il file prima di arrendersi");
        p.spinRow("Frequenza controllo", intSp(SettingsManager.REG_EXPORT_POLL_MS, 500, 100, 5000), "ms",
                  "ogni quanto guardare nella cartella");
        p.spinRow("Attesa file completo", intSp(SettingsManager.REG_EXPORT_STABLE_MS, 800, 100, 5000), "ms",
                  "il download è finito se la dimensione non cambia");
        p.spinRow("Ritentativi", intSp(SettingsManager.REG_VERIFY_RETRIES, 2, 0, 10), "",
                  "riesporta se mancano righe: assorbe i ritardi del server");
        p.spinRow("Attesa tra ritentativi", intSp(SettingsManager.REG_VERIFY_RETRY_S, 2, 1, 60), "s", "");
        p.hint("Log delle verifiche: " + new File(System.getProperty("user.home"),
               "AutoFillSuite_verifiche.txt").getAbsolutePath());
        return p.panel;
    }

    // ── tab: stampa ───────────────────────────────────────────────────────

    private JPanel buildPrint() {
        Page p = new Page();
        p.section("Posizioni");
        p.coordRow("Tasto Stampa", SettingsManager.PRINT_COORD_X, SettingsManager.PRINT_COORD_Y,
                   "il bottone di stampa sul sito");
        p.section("Tempi");
        p.spinRow("Attesa avvio", intSp(SettingsManager.PRINT_WAIT, 3, 1, 10), "s",
                  "tempo per passare al browser dopo AVVIA");
        p.spinRow("Pausa tra i click", intSp(SettingsManager.PRINT_PAUSE, 2, 1, 60), "s",
                  "quanto attendere tra una stampa e l'altra");
        return p.panel;
    }

    // ── tab: dual scan ────────────────────────────────────────────────────

    private JPanel buildDualScan() {
        Page p = new Page();
        p.section("Posizioni");
        p.coordRow("Casella 1 (serial)", SettingsManager.SCAN_COORD_X, SettingsManager.SCAN_COORD_Y,
                   "il primo campo del form sul sito");
        p.section("Tempi del burst");
        p.spinRow("Attesa dopo il click", intSp(SettingsManager.SCAN_FOCUS, 300, 50, 5000), "ms",
                  "quanto il sito ci mette a dare il focus al campo");
        p.spinRow("Pausa tra i tasti", intSp(SettingsManager.SCAN_KEY, 70, 10, 2000), "ms", "");
        p.spinRow("Attesa dopo ENTER", intSp(SettingsManager.SCAN_ENTER, 150, 50, 5000), "ms",
                  "il controllo vero lo fa il CSV: qui bastano pochi ms");
        p.section("Verifica");
        JCheckBox autoS = check("Verifica automatica della sessione",
            cfg.getBool(SettingsManager.SCAN_VERIFY_AUTO, true));
        autoS.addActionListener(e -> save(SettingsManager.SCAN_VERIFY_AUTO, autoS.isSelected()));
        p.row(autoS, "spenta: si verifica solo col tasto Verifica");
        p.spinRow("Verifica ogni", intSp(SettingsManager.SCAN_VERIFY_EVERY, 10, 1, 200), "pezzi",
                  "controllo automatico della sessione contro il CSV");
        p.hint("La coordinata Export CSV si imposta nel tab Verifica.");
        return p.panel;
    }

    // ── tab: finestra (integrazione col sito) ─────────────────────────────

    private JPanel buildWindow() {
        Page p = new Page();
        p.section("Guardia anti-collisione");
        JCheckBox guard = check("Blocca l'avvio se la finestra copre una coordinata",
            cfg.getBool(SettingsManager.GUARD_ENABLED, true));
        guard.addActionListener(e -> save(SettingsManager.GUARD_ENABLED, guard.isSelected()));
        p.row(guard, "l'app sta sempre in primo piano: se copre la casella, il robot "
                   + "clicca sull'app invece che sul sito e il run va a vuoto");

        JCheckBox move = check("Sposta la finestra da sola invece di bloccare",
            cfg.getBool(SettingsManager.GUARD_AUTOMOVE, true));
        move.addActionListener(e -> save(SettingsManager.GUARD_AUTOMOVE, move.isSelected()));
        p.row(move, "la porta in un angolo libero e parte lo stesso");

        p.section("Barra bassa (HUD)");
        JCheckBox hudAuto = check("Riduci alla barra mentre il robot lavora",
            cfg.getBool(SettingsManager.UI_HUD_AUTO, true));
        hudAuto.addActionListener(e -> save(SettingsManager.UI_HUD_AUTO, hudAuto.isSelected()));
        p.row(hudAuto, "durante il run servono lo stato e il contatore, non i campi: "
                     + "l'app si fa piccola e torna com'era a fine verifica");
        p.hint("La barra si apre e si chiude anche a mano, col tasto HUD nell'intestazione.");
        return p.panel;
    }

    // ── tab: storico ──────────────────────────────────────────────────────

    private JPanel buildHistory() {
        Page p = new Page();
        p.section("Verifiche eseguite");

        File logFile = new File(System.getProperty("user.home"), "AutoFillSuite_verifiche.txt");
        List<String> lines = new ArrayList<>();
        try {
            if (logFile.isFile()) {
                lines = Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // an unreadable log is not a reason to break the settings window
        }
        VerificationHistory.Stats st =
            VerificationHistory.stats(VerificationHistory.parse(lines));

        if (st.runs == 0) {
            p.hint("Nessuna verifica registrata finora.");
            p.hint("Log: " + logFile.getAbsolutePath());
            return p.panel;
        }

        p.stat("Run verificati", String.valueOf(st.runs));
        p.stat("Run puliti", st.cleanRuns + "  (" + st.cleanPercent() + "%)");
        p.stat("Run con problemi", String.valueOf(st.problemRuns));
        p.stat("Etichette controllate", String.valueOf(st.totalLabels));
        p.stat("Problemi totali", String.valueOf(st.totalProblems));

        p.section("Problemi per giorno");
        int shown = 0;
        List<String> days = new ArrayList<>(st.problemsPerDay.keySet());
        for (int i = days.size() - 1; i >= 0 && shown < 7; i--, shown++) {
            String d = days.get(i);
            Integer n = st.problemsPerDay.get(d);
            p.stat(d, n == 0 ? "nessun problema" : n + " problemi");
        }
        p.hint("Log: " + logFile.getAbsolutePath());
        return p.panel;
    }

    // ── write-through: every change hits the disk at once ─────────────────

    private void save(String key, Object value) {
        cfg.set(key, value);
        cfg.save();
    }

    private JSpinner intSp(String key, int def, int min, int max) {
        JSpinner sp = AppTheme.spinnerInt(cfg.getInt(key, def), min, max);
        sp.addChangeListener(e -> save(key, sp.getValue()));
        return sp;
    }

    private JSpinner dblSp(String key, double def, double min, double max, double step) {
        JSpinner sp = AppTheme.spinnerDouble(cfg.getDouble(key, def), min, max, step);
        sp.addChangeListener(e -> save(key, sp.getValue()));
        return sp;
    }

    // one spinner drives every 📍 countdown (three legacy keys)
    private JSpinner memoWaitSpinner() {
        JSpinner sp = AppTheme.spinnerInt(cfg.getInt(SettingsManager.REG_MEMO_WAIT, 4), 1, 10);
        sp.addChangeListener(e -> {
            cfg.set(SettingsManager.REG_MEMO_WAIT,   sp.getValue());
            cfg.set(SettingsManager.PRINT_MEMO_WAIT, sp.getValue());
            cfg.set(SettingsManager.SCAN_MEMO_WAIT,  sp.getValue());
            cfg.save();
        });
        return sp;
    }

    private void saveOnBlur(JTextField tf, String key) {
        tf.addActionListener(e -> save(key, tf.getText().trim()));
        tf.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { save(key, tf.getText().trim()); }
        });
    }

    private JCheckBox check(String text, boolean sel) {
        JCheckBox c = new JCheckBox(text, sel);
        c.setFont(AppTheme.F_BOLD);
        c.setForeground(AppTheme.TEXT);
        c.setOpaque(false);
        c.setFocusable(false);
        return c;
    }

    // ── page: label | control | unit, with a hint line under each row ─────

    private final class Page {
        final JPanel panel = new JPanel(new GridBagLayout());
        private final GridBagConstraints g = new GridBagConstraints();
        private int y = 0;

        Page() {
            panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            g.fill = GridBagConstraints.HORIZONTAL;
            g.insets = new Insets(2, 0, 2, 6);
        }

        void section(String title) {
            g.gridx = 0; g.gridy = y++; g.gridwidth = 3; g.weightx = 1;
            g.insets = new Insets(10, 0, 4, 0);
            panel.add(AppTheme.section(title), g);
            g.insets = new Insets(2, 0, 2, 6);
        }

        void spinRow(String label, JSpinner sp, String unit, String hint) {
            g.gridx = 0; g.gridy = y; g.gridwidth = 1; g.weightx = 0;
            JLabel l = AppTheme.label(label);
            l.setPreferredSize(new Dimension(150, 22));
            panel.add(l, g);
            g.gridx = 1; g.weightx = 0;
            panel.add(sp, g);
            g.gridx = 2; g.weightx = 1;
            panel.add(AppTheme.hint(unit.isEmpty() ? "" : unit), g);
            y++;
            if (!hint.isEmpty()) hintUnder(hint);
        }

        void fieldRow(String label, JTextField tf, String hint) {
            g.gridx = 0; g.gridy = y; g.gridwidth = 1; g.weightx = 0;
            JLabel l = AppTheme.label(label);
            l.setPreferredSize(new Dimension(150, 22));
            panel.add(l, g);
            g.gridx = 1; g.gridwidth = 2; g.weightx = 1;
            panel.add(tf, g);
            y++;
            if (!hint.isEmpty()) hintUnder(hint);
        }

        void row(Component c, String hint) {
            g.gridx = 0; g.gridy = y++; g.gridwidth = 3; g.weightx = 1;
            panel.add(c, g);
            if (!hint.isEmpty()) hintUnder(hint);
        }

        void coordRow(String label, String keyX, String keyY, String hint) {
            JPanel p = new JPanel(new BorderLayout(8, 0));
            p.setOpaque(false);

            JButton memo = AppTheme.button("Memo", AppTheme.PEACH);
            JLabel name = AppTheme.label(label);
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            left.setOpaque(false);
            left.add(memo);
            left.add(name);

            JLabel coords = new JLabel("Non impostate", SwingConstants.RIGHT);
            coords.setFont(AppTheme.F_MONO);
            coords.setForeground(AppTheme.RED);
            int cx = cfg.getInt(keyX, -1);
            int cy = cfg.getInt(keyY, -1);
            if (cx >= 0 && cy >= 0) {
                coords.setText("X:" + cx + "  Y:" + cy);
                coords.setForeground(AppTheme.GREEN);
            }

            memo.addActionListener(e ->
                CoordMemorizer.capture((int) spMemoWait.getValue(), memo, coords, null, pt -> {
                    cfg.set(keyX, pt.x);
                    cfg.set(keyY, pt.y);
                    cfg.save();
                    coords.setForeground(AppTheme.GREEN);
                })
            );

            p.add(left, BorderLayout.WEST);
            p.add(coords, BorderLayout.CENTER);
            row(p, hint);
        }

        void stat(String label, String value) {
            g.gridx = 0; g.gridy = y; g.gridwidth = 1; g.weightx = 0;
            JLabel l = AppTheme.label(label);
            l.setPreferredSize(new Dimension(170, 20));
            panel.add(l, g);
            g.gridx = 1; g.gridwidth = 2; g.weightx = 1;
            JLabel v = new JLabel(value);
            v.setFont(AppTheme.F_MONO);
            v.setForeground(AppTheme.TEXT);
            panel.add(v, g);
            y++;
        }

        void hint(String text) {
            g.gridx = 0; g.gridy = y++; g.gridwidth = 3; g.weightx = 1;
            g.insets = new Insets(10, 0, 2, 0);
            panel.add(AppTheme.hint(text), g);
            g.insets = new Insets(2, 0, 2, 6);
        }

        private void hintUnder(String text) {
            g.gridx = 0; g.gridy = y++; g.gridwidth = 3; g.weightx = 1;
            g.insets = new Insets(0, 2, 6, 0);
            panel.add(AppTheme.hint(text), g);
            g.insets = new Insets(2, 0, 2, 6);
        }
    }

    /** Keeps a page top-aligned and scrollable if the window shrinks. */
    private JScrollPane page(JPanel content) {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(AppTheme.SURFACE0);
        wrap.add(content, BorderLayout.NORTH);
        JScrollPane sc = new JScrollPane(wrap);
        sc.setBorder(BorderFactory.createEmptyBorder());
        sc.getViewport().setBackground(AppTheme.SURFACE0);
        AppTheme.styleScroll(sc);
        return sc;
    }

    private static String defaultDownloads() {
        return new File(System.getProperty("user.home"), "Downloads").getAbsolutePath();
    }
}
