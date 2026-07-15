package app.ui;

import app.config.SettingsManager;
import app.core.AutomationTask;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mode: STAMPA — auto-click printing. How many, then the lever.
 * Pause, start delay and the button coordinate live in ⚙.
 */
public class PrintModePanel extends JPanel {

    private final SettingsManager cfg = SettingsManager.getInstance();
    private final RunContext ctx;
    private AutomationTask currentTask = null;

    private JSpinner spCount;
    private JLabel banner;
    private JButton btnStart, btnStop;
    private JProgressBar status;
    private Counter counter;

    public PrintModePanel(RunContext ctx) {
        this.ctx = ctx;
        setLayout(new BorderLayout());
        setBackground(AppTheme.BASE);
        setBorder(BorderFactory.createEmptyBorder(0, 9, 8, 9));
        add(buildCard(), BorderLayout.CENTER);
    }

    private JPanel buildCard() {
        JPanel card = AppTheme.card();
        card.setLayout(new GridBagLayout());
        card.setBorder(BorderFactory.createEmptyBorder(6, 10, 5, 10));
        GridBagConstraints g = AppTheme.gbc();
        int y = 0;

        g.gridx = 0; g.gridy = y; g.weightx = 0;
        card.add(AppTheme.label("N° stampe"), g);
        spCount = AppTheme.spinnerInt(cfg.getInt(SettingsManager.PRINT_COUNT, 1), 1, 9999);
        g.gridx = 1; g.weightx = 1.0;
        card.add(spCount, g);

        y++; g.gridx = 0; g.gridy = y; g.gridwidth = 2;
        card.add(AppTheme.hint("Pausa, attesa e coordinata del tasto nelle Impostazioni"), g);

        y++; g.gridy = y;
        banner = AppTheme.banner();
        card.add(banner, g);

        y++; g.gridy = y;
        counter = new Counter("stampe eseguite");
        counter.set(0, 0);
        card.add(counter, g);

        y++; g.gridy = y;
        btnStart = AppTheme.primary("STAMPA", AppTheme.GREEN,
            Icons.play(AppTheme.ICON, AppTheme.ON_ACCENT));
        card.add(btnStart, g);

        y++; g.gridy = y;
        btnStop = AppTheme.secondary("Stop", Icons.stop(AppTheme.ICON, AppTheme.SUBTEXT));
        btnStop.setEnabled(false);
        card.add(btnStop, g);

        y++; g.gridy = y;
        status = AppTheme.progressStatus();
        status.setString("Configura e avvia.");
        card.add(status, g);

        btnStart.addActionListener(e -> start());
        btnStop.addActionListener(e -> {
            if (currentTask != null) currentTask.stop();
            status.setString("Interrotto.");
            state(AppTheme.PEACH, AppTheme.ON_ACCENT, "INTERROTTO");
        });
        state(AppTheme.SURFACE2, AppTheme.TEXT, "PRONTO");
        return card;
    }

    private void state(java.awt.Color bg, java.awt.Color fg, String text) {
        banner.setBackground(bg);
        banner.setForeground(fg);
        banner.setText(text);
    }

    private void start() {
        int cx = cfg.getInt(SettingsManager.PRINT_COORD_X, -1);
        int cy = cfg.getInt(SettingsManager.PRINT_COORD_Y, -1);
        if (cx < 0 || cy < 0) {
            status.setString("Memorizza il tasto Stampa nelle Impostazioni.");
            return;
        }
        Map<String, Point> targets = new LinkedHashMap<>();
        targets.put("Tasto Stampa", new Point(cx, cy));
        String blocked = ctx.blockingCollision(targets);
        if (blocked != null) { status.setString(blocked); return; }

        try { spCount.commitEdit(); } catch (java.text.ParseException ignored) { }
        cfg.set(SettingsManager.PRINT_COUNT, spCount.getValue());
        cfg.save();

        final int   total   = (int) spCount.getValue();
        final int   pauseMs  = cfg.getInt(SettingsManager.PRINT_PAUSE, 2) * 1000;
        final int   delay    = cfg.getInt(SettingsManager.PRINT_WAIT, 3);
        final Point coord    = new Point(cx, cy);

        status.setValue(0);
        status.setString("Avvio...");
        counter.set(0, total);
        counter.setTone(AppTheme.TEXT);
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);

        currentTask = new AutomationTask() {
            @Override protected int getTotal()      { return total; }
            @Override protected int getStartDelay() { return delay; }
            @Override protected void showCountdown(int sec) {
                SwingUtilities.invokeLater(() -> {
                    state(AppTheme.PEACH, AppTheme.ON_ACCENT, "AVVIO TRA " + sec + "s");
                    status.setString("Avvio tra " + sec + "s...");
                    ctx.jobProgress(AppTheme.PEACH, "AVVIO TRA " + sec + "s",
                                    "0", "stampe", 0, true);
                });
            }
            @Override protected boolean executeCycle(int i) throws Exception {
                int n = i + 1;
                if (i > 0 && isMouseMoved(coord)) {
                    triggerFailSafe("Mouse mosso al click " + n);
                    return false;
                }
                robot.click(coord.x, coord.y);
                SwingUtilities.invokeLater(() -> {
                    counter.set(n, total);
                    state(AppTheme.GREEN, AppTheme.ON_ACCENT, "STAMPA IN CORSO");
                    status.setString("in corso...");
                    ctx.jobProgress(AppTheme.PEACH, "STAMPA IN CORSO", n + " / " + total,
                                    "stampe", n * 100 / total, true);
                });
                if (i < total - 1 && running) robot.sleep(pauseMs);
                return true;
            }
            @Override protected void updateProgress(int cur, int tot) {
                SwingUtilities.invokeLater(() -> status.setValue((int) ((double) cur / tot * 100)));
            }
            @Override protected void onCompleted() {
                status.setValue(100);
                counter.set(total, total);
                counter.setTone(AppTheme.GREEN);
                status.setString("Completato");
                state(AppTheme.GREEN, AppTheme.ON_ACCENT,
                      "COMPLETATO — " + total + "/" + total);
            }
            @Override protected void onFinally() {
                btnStart.setEnabled(true);
                btnStop.setEnabled(false);
                ctx.jobFinished();
            }
            @Override protected void onFailSafe(String message) {
                status.setString(message);
                state(AppTheme.PEACH, AppTheme.ON_ACCENT, "INTERROTTO — fail-safe");
            }
            @Override protected void showError(String msg) {
                Toolkit.getDefaultToolkit().beep();
                status.setString("Errore: " + msg);
                state(AppTheme.RED, AppTheme.ON_ACCENT, "ERRORE");
            }
        };
        ctx.jobStarted(() -> {
            currentTask.stop();
            status.setString("Interrotto.");
        });
        new Thread(currentTask).start();
    }
}
