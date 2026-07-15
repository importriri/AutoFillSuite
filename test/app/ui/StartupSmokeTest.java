package app.ui;

import app.Main;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// startup smoke test — the one the screenshot harness could never catch, because
// the harness called setVisible() itself. this one calls app.Main.main exactly
// like `java -jar` does and then asks the toolkit a blunt question: is there a
// window on screen? a build that compiles and shows nothing is still a broken app.
//
// TWO STARTUPS, because they are different code paths and only the second one is
// what production actually does:
//   (no arg)  first run ever, no settings file
//   --saved   restart with every setting already saved — the path that crashed
//             with an NPE when a restored "A blocco" fired a listener while the
//             panel was still half-built
//
//   xvfb-run java -cp build app.ui.StartupSmokeTest           (first run)
//   xvfb-run java -cp build app.ui.StartupSmokeTest --saved   (restart)
public final class StartupSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run to exercise the ui");
            return;
        }
        // never touch the real settings file: give the app a throwaway home
        Path home = Files.createTempDirectory("afs-home");
        System.setProperty("user.home", home.toString());

        boolean saved = args.length > 0 && "--saved".equals(args[0]);
        if (saved) {
            writeSavedSettings(home);
            System.out.println("  ..   avvio con impostazioni salvate");
        } else {
            System.out.println("  ..   primo avvio, nessun file impostazioni");
        }

        Main.main(new String[0]);

        JFrame main = awaitFrame("AutoFill Suite", 5000);
        check("the app actually puts a window on screen", main != null);
        if (main == null) { report(); return; }

        check("the window has a size a human could use",
              main.getWidth() > 200 && main.getHeight() > 200);

        // the cockpit: the mode selector and both jobs must be reachable
        check("the mode selector is there", findButton(main, "REGISTRA") != null
                                         && findButton(main, "STAMPA") != null);
        JLabel greet = findLabelByPrefix(main, "PRONTO");
        check("the range card greets with its state banner", greet != null);
        // the automatic journal: one sent row, zero buttons -> the daily file
        // exists on disk with the code, its INVIATA state, and one RUN section
        ResultsPanel rp = findPanel(main, ResultsPanel.class);
        check("the results panel is reachable for the journal check", rp != null);
        if (rp != null) {
            SwingUtilities.invokeAndWait(() -> {
                rp.beginRun(1, "SMOKE-LOT");
                rp.addLiveRow("JRN001", 1);
            });
            Thread.sleep(900);   // coalescing timer + journal thread
            File rep = new File(System.getProperty("user.home"),
                "AutoFillSuite_report_"
                + new java.text.SimpleDateFormat("yyyy-MM-dd")
                      .format(new java.util.Date()) + ".csv");
            check("a send journals itself to the daily file", rep.exists());
            String body = rep.exists()
                ? new String(Files.readAllBytes(rep.toPath())) : "";
            check("the journaled row carries code and state",
                  body.contains("JRN001") && body.contains("INVIATA"));
            check("the journal filed exactly one RUN section",
                  body.split("RUN;", -1).length - 1 == 1);
            // the boundary flush: send + INSTANT reset (inside the coalescing
            // window) must still land the row in the file, under the OLD run
            SwingUtilities.invokeAndWait(() -> {
                rp.beginRun(1, "FLUSH-LOT");
                rp.addLiveRow("JRN002", 1);
                rp.resetIdle();               // no sleep: the timer is pending
            });
            Thread.sleep(500);                // journal thread lands
            body = new String(Files.readAllBytes(rep.toPath()));
            check("a send flushed at a run boundary still reaches the file",
                  body.contains("JRN002"));
            check("the day file now holds both runs",
                  body.split("RUN;", -1).length - 1 == 2);
            Thread.sleep(200);
        }

        check("the results panel offers the day-view toggle",
              findButton(main, "Ultimo giro") != null
              && findButton(main, "Oggi") != null);
        // centered like the scan card: the banner spans its card, it does not
        // hug the field column (the old gridx=1 lopsided stack)
        check("the range stack spans the card, not the field column",
              greet != null && greet.getParent() != null
              && greet.getWidth() > greet.getParent().getWidth() * 0.85);
        check("registering has both ways: range and scan",
              findButton(main, "Intervallo") != null
           && findButton(main, "Scansione") != null);

        // nothing may be cut off: a button the operator cannot reach is a bug,
        // and a card taller than its window silently swallows its own footer.
        // EVERY mode gets measured — the clip only shows on the displayed card,
        // and the windows machines proved the initial one is not enough.
        check("no panel is clipped by the window", nothingClipped(main));
        JButton stampa = findButton(main, "STAMPA");
        if (stampa != null) {
            SwingUtilities.invokeAndWait(stampa::doClick);
            Thread.sleep(200);
            check("the print card is not clipped either", nothingClipped(main));
            check("the print card greets with its state banner",
                  findLabelByPrefix(main, "PRONTO") != null);
        }
        JButton registra = findButton(main, "REGISTRA");
        JButton scans = findButton(main, "Scansione");
        if (registra != null && scans != null) {
            SwingUtilities.invokeAndWait(registra::doClick);
            SwingUtilities.invokeAndWait(scans::doClick);
            Thread.sleep(200);
            check("the scan card is not clipped either", nothingClipped(main));

            // the queue counts from the FIRST pair: the old worker took the
            // head pair in hand before waiting for ▶, so one scanned pair read
            // "REGISTRA TUTTO (0)" — disabled, unreleasable, count off by one
            JButton blocco = findButton(main, "A blocco");
            if (blocco != null) {
                SwingUtilities.invokeAndWait(blocco::doClick);
                List<JTextField> qr = showingTextFields(main);
                check("the scan card exposes its two QR fields", qr.size() == 2);
                if (qr.size() == 2) {
                    SwingUtilities.invokeAndWait(() -> {
                        qr.get(0).setText("QR-SMOKE-1");
                        qr.get(1).setText("QR-SMOKE-2");
                        qr.get(1).postActionEvent();   // the scanner's ENTER
                    });
                    Thread.sleep(300);
                    JButton fire = findButtonByPrefix(main, "REGISTRA TUTTO");
                    check("one scanned pair counts as one, from the FIRST",
                          fire != null && fire.getText().contains("(1)"));
                    check("one queued pair is releasable", fire != null && fire.isEnabled());
                    SwingUtilities.invokeAndWait(() ->
                        findButtonByPrefix(main, "Nuova sessione").doClick());
                    Thread.sleep(200);
                    fire = findButtonByPrefix(main, "REGISTRA TUTTO");
                    check("a new session empties the queue for real",
                          fire != null && fire.getText().contains("(0)") && !fire.isEnabled());
                }
            }

            SwingUtilities.invokeAndWait(() -> findButton(main, "Intervallo").doClick());
            Thread.sleep(200);
        }

        // the results half must close and reopen — the whole point of the cockpit.
        // with saved settings it starts CLOSED, so measure, do not assume.
        JButton res = findButton(main, "Risultati");
        check("the results toggle is in the header", res != null);
        if (res != null) {
            int startW = main.getWidth();
            SwingUtilities.invokeAndWait(res::doClick);
            Thread.sleep(300);
            int toggledW = main.getWidth();
            check("toggling the results resizes the window", toggledW != startW);
            SwingUtilities.invokeAndWait(res::doClick);
            Thread.sleep(300);
            check("toggling back restores the width", main.getWidth() == startW);
        }

        // every button must show something: an empty label AND no icon is a
        // button the operator cannot read — exactly the tofu bug, one level up
        check("no button is left blank (no text and no icon)", allButtonsReadable(main));

        // the HUD: the app must be able to shrink to the bar and come back
        JButton hudBtn = findButton(main, "HUD");
        check("the HUD button is in the header", hudBtn != null);
        if (hudBtn != null) {
            int fullW = main.getWidth(), fullH = main.getHeight();
            SwingUtilities.invokeAndWait(hudBtn::doClick);
            Thread.sleep(350);
            check("the HUD shrinks the window", main.getHeight() < fullH);
            // the big number was clipped at the top: 108 was the OUTER height
            // and the title bar starved the row. with honest preferred sizes
            // this walk catches it wherever the platform metrics bite.
            check("the HUD shows everything, number included", nothingClipped(main));
            check("the HUD sits at the bottom of the screen",
                  main.getY() + main.getHeight() > screenBottom() - 60);
            JButton back = findByTooltip(main, "Apri il pannello completo");
            check("the HUD can be expanded back", back != null);
            if (back != null) {
                SwingUtilities.invokeAndWait(back::doClick);
                Thread.sleep(350);
                check("expanding restores the cockpit",
                      main.getWidth() == fullW && main.getHeight() == fullH);
            }
        }

        // crash recovery: a pending run must be offered, not silently dropped
        if (saved) {
            check("a pending run is offered for verification",
                  findButton(main, "VERIFICA ORA") != null);
        }

        // the gear must open the settings window: the only door to every setting
        JButton gear = findByTooltip(main, "Impostazioni");
        check("the gear button is in the header", gear != null);
        if (gear != null) {
            SwingUtilities.invokeAndWait(gear::doClick);
            JDialog settings = awaitDialog("Impostazioni", 3000);
            check("the gear opens the settings window", settings != null);
            if (settings != null) {
                check("the settings hold the window and history tabs",
                      hasTab(settings, "Finestra") && hasTab(settings, "Storico"));

                // same disease as the main window's hard-coded height: a fixed
                // tab-area size clips whichever tab grows a row. measure ALL of them.
                JTabbedPane tabs = findTabs(settings);
                boolean allTabsFit = tabs != null;
                if (tabs != null) {
                    for (int i = 0; i < tabs.getTabCount(); i++) {
                        final int idx = i;
                        SwingUtilities.invokeAndWait(() -> tabs.setSelectedIndex(idx));
                        Thread.sleep(120);
                        if (!nothingClipped(settings)) {
                            System.out.println("       clipped tab: " + tabs.getTitleAt(i));
                            allTabsFit = false;
                        }
                    }
                }
                check("no settings tab is clipped", allTabsFit);
            }
        }

        report();
    }

    // an operator who used the app before restarts with EVERY setting already in
    // place — coordinates, timings, and the modes they picked. this is the normal
    // path, and it exercises every restore branch at once.
    private static void writeSavedSettings(Path home) throws Exception {
        Properties p = new Properties();
        p.setProperty("scan.batchMode", "true");      // the one that blew up
        p.setProperty("ui.resultsOpen", "false");     // results closed at exit
        p.setProperty("reg.coordX", "512");
        p.setProperty("reg.coordY", "384");
        p.setProperty("reg.exportCoordX", "900");
        p.setProperty("reg.exportCoordY", "120");
        p.setProperty("print.coordX", "700");
        p.setProperty("print.coordY", "300");
        p.setProperty("scan.coordX", "512");
        p.setProperty("scan.coordY", "384");
        p.setProperty("reg.count", "50");
        p.setProperty("reg.verifyAuto", "true");
        p.setProperty("scan.verifyEvery", "5");
        p.setProperty("ui.winX", "40");
        p.setProperty("ui.winY", "40");
        // a run the app never verified: the restart must offer to check it
        p.setProperty("run.pending", "true");
        p.setProperty("run.prefix", "LBL");
        p.setProperty("run.seq0", "001");
        p.setProperty("run.count", "10");
        p.setProperty("run.lot", "900612202601310007");
        try (FileOutputStream out = new FileOutputStream(
                new File(home.toFile(), ".autofill_suite.properties"))) {
            p.store(out, "saved by StartupSmokeTest");
        }
    }

    /** A container asking for more height than it got is losing content. */
    private static JButton findButtonByPrefix(Container root, String prefix) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton && c.isShowing() && ((JButton) c).getText() != null
                && ((JButton) c).getText().startsWith(prefix)) return (JButton) c;
            if (c instanceof Container) {
                JButton b = findButtonByPrefix((Container) c, prefix);
                if (b != null) return b;
            }
        }
        return null;
    }

    // only the fields the operator can SEE: CardLayout keeps the hidden cards
    // in the tree, isShowing() filters them out
    private static List<JTextField> showingTextFields(Container root) {
        List<JTextField> out = new ArrayList<>();
        collectShowingTextFields(root, out);
        return out;
    }

    private static void collectShowingTextFields(Container root, List<JTextField> out) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTextField && c.isShowing()) out.add((JTextField) c);
            else if (c instanceof Container) collectShowingTextFields((Container) c, out);
        }
    }

    private static boolean nothingClipped(Container root) {
        for (Component c : root.getComponents()) {
            // a CardLayout host asks for its TALLEST card even while showing a
            // small one — that is the layout's contract, not clipping. The
            // showing child underneath is what gets measured.
            boolean cardHost = c instanceof Container
                            && ((Container) c).getLayout() instanceof CardLayout;
            if (c instanceof JPanel && c.isShowing() && !cardHost) {
                Dimension want = c.getPreferredSize();
                Dimension got = c.getSize();
                if (got.height > 0 && want.height > got.height + 2) {
                    System.out.println("       clipped: " + c.getClass().getSimpleName()
                        + " wants " + want.height + "px, got " + got.height + "px");
                    return false;
                }
            }
            if (c instanceof Container && !nothingClipped((Container) c)) return false;
        }
        return true;
    }

    private static int screenBottom() {
        Rectangle r = GraphicsEnvironment.getLocalGraphicsEnvironment()
                                         .getMaximumWindowBounds();
        return r.y + r.height;
    }

    @SuppressWarnings("unchecked")
    private static <T> T findPanel(Container root, Class<T> cls) {
        for (Component c : root.getComponents()) {
            if (cls.isInstance(c)) return (T) c;
            if (c instanceof Container) {
                T t = findPanel((Container) c, cls);
                if (t != null) return t;
            }
        }
        return null;
    }

    private static JLabel findLabelByPrefix(Container root, String prefix) {
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel && c.isShowing() && ((JLabel) c).getText() != null
                && ((JLabel) c).getText().startsWith(prefix)) return (JLabel) c;
            if (c instanceof Container) {
                JLabel l = findLabelByPrefix((Container) c, prefix);
                if (l != null) return l;
            }
        }
        return null;
    }

    private static JTabbedPane findTabs(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTabbedPane) return (JTabbedPane) c;
            if (c instanceof Container) {
                JTabbedPane t = findTabs((Container) c);
                if (t != null) return t;
            }
        }
        return null;
    }

    private static boolean hasTab(Container root, String title) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTabbedPane) {
                JTabbedPane t = (JTabbedPane) c;
                for (int i = 0; i < t.getTabCount(); i++) {
                    if (title.equals(t.getTitleAt(i))) return true;
                }
            }
            if (c instanceof Container && hasTab((Container) c, title)) return true;
        }
        return false;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static JFrame awaitFrame(String title, int timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Window w : Window.getWindows()) {
                if (w instanceof JFrame && w.isShowing()
                        && title.equals(((JFrame) w).getTitle())) {
                    return (JFrame) w;
                }
            }
            Thread.sleep(100);
        }
        return null;
    }

    private static JDialog awaitDialog(String title, int timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog && w.isShowing()
                        && title.equals(((JDialog) w).getTitle())) {
                    return (JDialog) w;
                }
            }
            Thread.sleep(100);
        }
        return null;
    }

    /** A button with neither a label nor an icon is invisible to the operator. */
    private static boolean allButtonsReadable(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton) {
                JButton b = (JButton) c;
                // a spinner's arrows and a scrollbar's stubs are drawn by their own
                // UI and live INSIDE those widgets: blank by design, not by mistake.
                // the parent is the honest test — a name or a width is not.
                Container parent = b.getParent();
                boolean internal = parent instanceof JScrollBar || parent instanceof JSpinner;
                boolean hasText = b.getText() != null && !b.getText().trim().isEmpty();
                if (!internal && !hasText && b.getIcon() == null) {
                    System.out.println("       blank button inside " + parent.getClass().getSimpleName());
                    return false;
                }
            }
            if (c instanceof Container && !allButtonsReadable((Container) c)) return false;
        }
        return true;
    }

    private static JButton findByTooltip(Container root, String tip) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton && tip.equals(((JButton) c).getToolTipText())) {
                return (JButton) c;
            }
            if (c instanceof Container) {
                JButton b = findByTooltip((Container) c, tip);
                if (b != null) return b;
            }
        }
        return null;
    }

    private static JButton findButton(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton && text.equals(((JButton) c).getText())) {
                return (JButton) c;
            }
            if (c instanceof Container) {
                JButton b = findButton((Container) c, text);
                if (b != null) return b;
            }
        }
        return null;
    }

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    private static void report() {
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }
}
