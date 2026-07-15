package app.ui;

import app.config.SettingsManager;
import app.core.WindowGuard;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * The cockpit: controls on the left, results on the right — one window, nothing
 * to chase. The results half opens and closes at will (the window shrinks back
 * to the control column when closed), and the choice is remembered.
 *
 * Modes are a machine's selector, not filing-cabinet tabs: REGISTRA (a
 * intervallo / a scansione — two ways to do the same job) and STAMPA.
 */
public class MainWindow extends JFrame implements RunContext {

    private static final int CONTROL_W = 300;
    private static final int RESULTS_W = 400;
    private static final int HUD_W     = 460;

    /** Measured by pack(): a hard-coded height clipped the cards on Windows. */
    private final int cockpitH;

    private static final String VIEW_COCKPIT = "cockpit";
    private static final String VIEW_HUD     = "hud";

    private static final String CARD_RANGE = "range";
    private static final String CARD_SCAN  = "scan";
    private static final String CARD_PRINT = "print";

    private final SettingsManager cfg = SettingsManager.getInstance();

    private final ResultsPanel results;
    private final RangeModePanel rangeMode;
    private final ScanModePanel  scanMode;
    private final PrintModePanel printMode;

    private final JPanel cards = new JPanel(new CardLayout());
    private final JPanel views = new JPanel(new CardLayout());
    private final Segmented subSelector;
    private HudPanel hud;
    private JButton btnResults, btnHud;
    private SettingsWindow settingsWindow = null;
    private boolean resultsOpen;
    private boolean hudMode;
    private boolean autoHud = false;          // this run collapsed us, not the operator
    private Runnable stopAction = null;
    private Rectangle cockpitBounds = null;   // where the cockpit was before the HUD

    public MainWindow() {
        super("AutoFill Suite");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setAlwaysOnTop(true);

        results   = new ResultsPanel(() -> setResultsOpen(false));
        rangeMode = new RangeModePanel(results, this);
        scanMode  = new ScanModePanel(results, this);
        printMode = new PrintModePanel(this);

        cards.setBackground(AppTheme.BASE);
        cards.add(rangeMode, CARD_RANGE);
        cards.add(scanMode,  CARD_SCAN);
        cards.add(printMode, CARD_PRINT);

        // sub-selector: two ways of doing the same job
        subSelector = new Segmented(new String[] { "Intervallo", "Scansione" },
                                    false, 0, idx -> showCard(idx == 0 ? CARD_RANGE : CARD_SCAN));

        // main selector: the machine's mode switch
        Segmented selector = new Segmented(new String[] { "REGISTRA", "STAMPA" }, true, 0, idx -> {
            boolean register = idx == 0;
            subSelector.setVisible(register);
            showCard(register
                ? (subSelector.getSelected() == 0 ? CARD_RANGE : CARD_SCAN)
                : CARD_PRINT);
        });

        // one strip across the whole width: both columns then start at the same
        // height, so the controls and the results read as two parallel halves
        JPanel strip = new JPanel(new GridLayout(1, 2, 8, 0));
        strip.setBackground(AppTheme.BASE);
        strip.setBorder(BorderFactory.createEmptyBorder(4, 9, 5, 9));
        strip.add(selector);
        strip.add(subSelector);

        JPanel control = new JPanel(new BorderLayout()) {
            // width pinned, height honest — pack() must see the tallest card
            @Override public Dimension getPreferredSize() {
                return new Dimension(CONTROL_W, super.getPreferredSize().height);
            }
        };
        control.setBackground(AppTheme.BASE);
        control.add(cards, BorderLayout.CENTER);

        JPanel columns = new JPanel(new BorderLayout());
        columns.setBackground(AppTheme.BASE);
        columns.add(control, BorderLayout.WEST);
        columns.add(results, BorderLayout.CENTER);

        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(AppTheme.BASE);
        head.add(buildHeader(), BorderLayout.NORTH);
        head.add(strip, BorderLayout.SOUTH);

        JPanel cockpit = new JPanel(new BorderLayout());
        cockpit.setBackground(AppTheme.BASE);
        cockpit.add(head, BorderLayout.NORTH);
        cockpit.add(columns, BorderLayout.CENTER);

        hud = new HudPanel(this::stopCurrentJob, () -> setHudMode(false));

        views.setBackground(AppTheme.BASE);
        views.add(cockpit, VIEW_COCKPIT);
        views.add(hud, VIEW_HUD);
        setContentPane(views);

        resultsOpen = cfg.getBool(SettingsManager.UI_RESULTS_OPEN, true);
        results.setVisible(resultsOpen);
        refreshResultsButton();

        // measured, not guessed: real fonts, real decorations
        pack();
        cockpitH = getHeight();
        setSize(resultsOpen ? CONTROL_W + RESULTS_W : CONTROL_W, cockpitH);
        // before a saved-HUD start, or the HUD minimum gets stomped
        setMinimumSize(new Dimension(CONTROL_W, cockpitH));
        restorePosition();

        if (cfg.getBool(SettingsManager.UI_HUD, false)) setHudMode(true);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                cfg.save();   // settings are write-through
                dispose();
                System.exit(0);
            }
        });

        setVisible(true);   // without this the app builds a window nobody ever sees

        // a run the app never got to verify is a run nobody checked: offer it now
        SwingUtilities.invokeLater(rangeMode::restorePendingRun);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(AppTheme.MANTLE);
        // no app name here: the window's own title bar already says it, and the
        // second copy cost a whole row on a panel that has to stay short
        header.setBorder(BorderFactory.createEmptyBorder(3, 11, 3, 6));

        JLabel name = new JLabel("AutoFillSuite");
        name.setFont(AppTheme.F_MONO.deriveFont(10.5f));
        name.setForeground(AppTheme.OVERLAY);

        btnResults = AppTheme.secondary("Risultati",
            Icons.chevron(AppTheme.ICON, AppTheme.SUBTEXT, false));
        btnResults.setToolTipText("Apri o chiudi il pannello risultati");
        btnResults.addActionListener(e -> setResultsOpen(!resultsOpen));

        JButton gear = AppTheme.iconButton(
            Icons.gear(AppTheme.ICON + 2, AppTheme.SUBTEXT), "Impostazioni");
        gear.setToolTipText("Impostazioni");
        gear.addActionListener(e -> {
            if (settingsWindow == null) settingsWindow = new SettingsWindow(this);
            settingsWindow.setVisible(true);
            settingsWindow.toFront();
        });

        btnHud = AppTheme.secondary("HUD", Icons.chevron(AppTheme.ICON, AppTheme.SUBTEXT, true));
        btnHud.setToolTipText("Riduci alla barra bassa");
        btnHud.addActionListener(e -> setHudMode(true));

        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        east.setOpaque(false);
        east.add(btnHud);
        east.add(btnResults);
        east.add(gear);

        header.add(name, BorderLayout.WEST);
        header.add(east, BorderLayout.EAST);
        return header;
    }

    private void showCard(String card) {
        ((CardLayout) cards.getLayout()).show(cards, card);
    }

    /** Open or close the results half; the window follows. */
    private void setResultsOpen(boolean open) {
        resultsOpen = open;
        results.setVisible(open);
        refreshResultsButton();
        setSize(open ? CONTROL_W + RESULTS_W : CONTROL_W, getHeight());
        revalidate();
        repaint();
        cfg.set(SettingsManager.UI_RESULTS_OPEN, open);
        cfg.save();
    }

    private void refreshResultsButton() {
        btnResults.setIcon(Icons.chevron(AppTheme.ICON, AppTheme.SUBTEXT, resultsOpen));
        btnResults.setToolTipText(resultsOpen ? "Chiudi i risultati" : "Apri i risultati");
    }

    // ── HUD ────────────────────────────────────────────────────────────────

    /** Collapse to the bar (or back). Remembers where the cockpit was. */
    private void setHudMode(boolean on) {
        if (on == hudMode) return;
        if (on) {
            cockpitBounds = getBounds();
            ((CardLayout) views.getLayout()).show(views, VIEW_HUD);
            hudMode = true;
            // measured: a fixed outer 108 starved the content under the windows title bar
            Insets in = getInsets();
            int hudH = hud.getPreferredSize().height + in.top + in.bottom;
            // the cockpit's minimum would keep the bar from ever shrinking
            setMinimumSize(new Dimension(HUD_W, hudH));
            // sit at the bottom of the screen, out of the form's way
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                                                  .getMaximumWindowBounds();
            setSize(HUD_W, hudH);
            setLocation(screen.x + (screen.width - HUD_W) / 2,
                        screen.y + screen.height - hudH - 12);
        } else {
            ((CardLayout) views.getLayout()).show(views, VIEW_COCKPIT);
            hudMode = false;
            setMinimumSize(new Dimension(CONTROL_W, cockpitH));
            if (cockpitBounds != null) setBounds(cockpitBounds);
            else setSize(resultsOpen ? CONTROL_W + RESULTS_W : CONTROL_W, cockpitH);
        }
        if (!autoHud) {   // only the operator's own choice is worth remembering
            cfg.set(SettingsManager.UI_HUD, on);
            cfg.save();
        }
        revalidate();
        repaint();
    }

    public boolean isHudMode() {
        return hudMode;
    }

    private void stopCurrentJob() {
        Runnable s = stopAction;
        if (s != null) s.run();
    }

    // ── RunContext ─────────────────────────────────────────────────────────

    @Override
    public String blockingCollision(Map<String, Point> targets) {
        if (!cfg.getBool(SettingsManager.GUARD_ENABLED, true)) return null;

        List<String> hits = WindowGuard.collisions(getBounds(), targets);
        if (settingsWindow != null && settingsWindow.isShowing()) {
            hits.addAll(WindowGuard.collisions(settingsWindow.getBounds(), targets));
        }
        if (hits.isEmpty()) return null;

        // the robot would click the app instead of the site — move, or refuse
        if (cfg.getBool(SettingsManager.GUARD_AUTOMOVE, true)) {
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                                                  .getMaximumWindowBounds();
            Point safe = WindowGuard.safeLocation(getBounds(), targets.values(), screen);
            if (safe != null) {
                setLocation(safe);
                if (settingsWindow != null && settingsWindow.isShowing()) {
                    settingsWindow.setVisible(false);
                }
                return null;
            }
        }
        return "La finestra copre: " + String.join(", ", hits)
             + ". Spostala, o attiva lo spostamento automatico in Impostazioni.";
    }

    @Override
    public void jobStarted(Runnable stop) {
        stopAction = stop;
        if (!hudMode && cfg.getBool(SettingsManager.UI_HUD_AUTO, true)) {
            autoHud = true;      // we collapsed for the robot, not because he asked
            setHudMode(true);
        }
    }

    @Override
    public void jobProgress(Color tone, String state, String number, String caption,
                            int percent, boolean stoppable) {
        hud.state(tone, state, number, caption, percent, stoppable);
        hud.tone(tone == AppTheme.RED ? AppTheme.RED
               : tone == AppTheme.GREEN ? AppTheme.GREEN : AppTheme.TEXT);
    }

    @Override
    public void jobFinished() {
        stopAction = null;
        if (autoHud) {           // give back exactly the view he had
            autoHud = false;
            setHudMode(false);
        } else {
            hud.idle();
        }
    }

    @Override public void focusHome(javax.swing.JComponent target) {
        // one EDT turn later: the cockpit may just have been restored
        SwingUtilities.invokeLater(() -> {
            toFront();
            requestFocus();
            if (target == null || !target.isShowing()) return;
            Point p = target.getLocationOnScreen();
            int x = p.x + Math.min(15, target.getWidth() / 2);
            int y = p.y + target.getHeight() / 2;
            target.requestFocusInWindow();
            // real OS click off the EDT: toFront alone loses to the foreground lock
            new Thread(() -> {
                try {
                    app.core.RobotEngine.getInstance().click(x, y);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }, "focus-home").start();
        });
    }

    private void restorePosition() {
        int x = cfg.getInt(SettingsManager.UI_WIN_X, Integer.MIN_VALUE);
        int y = cfg.getInt(SettingsManager.UI_WIN_Y, Integer.MIN_VALUE);
        // every screen, not just the primary: a position remembered on a
        // second monitor is legitimate; one remembered on an unplugged
        // monitor fails the reachability check and gets re-placed instead
        java.util.List<Rectangle> screens = new java.util.ArrayList<>();
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment()
                                                   .getScreenDevices()) {
            screens.add(d.getDefaultConfiguration().getBounds());
        }
        if (x != Integer.MIN_VALUE
            && WindowGuard.reachableOnAny(
                   new Rectangle(x, y, getWidth(), getHeight()), screens)) {
            setLocation(x, y);
        } else {
            setLocationByPlatform(true);
        }
        final Timer saver = new Timer(400, e -> {
            cfg.set(SettingsManager.UI_WIN_X, getX());
            cfg.set(SettingsManager.UI_WIN_Y, getY());
            cfg.save();
        });
        saver.setRepeats(false);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent e) { saver.restart(); }
        });
    }
}
