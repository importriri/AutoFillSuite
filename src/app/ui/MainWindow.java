package app.ui;

import app.core.RobotEngine;
import app.config.SettingsManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Main application window. Compact, always-on-top.
 * Settings are auto-saved on close.
 */
public class MainWindow extends JFrame {

    private final TabRegistrazione tabReg;
    private final TabStampa        tabStampa;
    private final TabDualScan      tabDualScan;

    public MainWindow() {
        super("AutoFill Suite");

        if (!RobotEngine.getInstance().isAvailable()) {
            JOptionPane.showMessageDialog(null,
                "Cannot initialize java.awt.Robot.\nThe app cannot run.",
                "Critical error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setResizable(true);
        setAlwaysOnTop(true);

        tabReg      = new TabRegistrazione();
        tabStampa   = new TabStampa();
        tabDualScan = new TabDualScan();

        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setFont(AppTheme.F_LABEL);
        tabs.setBackground(AppTheme.C_BG);
        tabs.addTab("📋  Register",  tabReg);
        tabs.addTab("🖨  Print",     tabStampa);
        tabs.addTab("📷  Dual Scan", tabDualScan);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(AppTheme.C_ACCENT);
        header.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));

        JLabel title = new JLabel("AutoFill Suite");
        title.setFont(AppTheme.F_TITLE);
        title.setForeground(Color.WHITE);

        JLabel version = new JLabel("v1.0");
        version.setFont(AppTheme.F_HINT);
        version.setForeground(new Color(255, 255, 255, 180));

        header.add(title,   BorderLayout.WEST);
        header.add(version, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(AppTheme.C_BG);
        root.add(header, BorderLayout.NORTH);
        root.add(tabs,   BorderLayout.CENTER);
        add(root);

        setPreferredSize(new Dimension(AppTheme.WIN_WIDTH, AppTheme.WIN_HEIGHT));
        pack();
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                tabReg.salvaImpostazioni();
                tabStampa.salvaImpostazioni();
                tabDualScan.salvaImpostazioni();
                SettingsManager.getInstance().save();
                dispose();
                System.exit(0);
            }
        });

        setVisible(true);
    }
}
