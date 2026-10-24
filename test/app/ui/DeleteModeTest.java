package app.ui;

import app.config.SettingsManager;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// the Cancella tab with fake hands and a fake portal: a spoiled label scanned,
// listed, deleted after the countdown, checked on the export, gone from the
// day's count. the portal deletes on the DELETE click with no question, so the
// mouse is checked before it and the click is the point of no return.
//
//   xvfb-run java -cp out app.ui.DeleteModeTest
public final class DeleteModeTest {

    private static int passed = 0;
    private static int failed = 0;
    private static File home;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        home = Files.createTempDirectory("afs-delete").toFile();
        System.setProperty("user.home", home.getPath());
        File downloads = new File(home, "Downloads");
        downloads.mkdirs();
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.REPORT_DIR, home.getPath());
        cfg.set(SettingsManager.REG_DOWNLOAD_DIR, downloads.getPath());
        cfg.set(SettingsManager.REG_EXPORT_TIMEOUT_S, 5);
        cfg.set(SettingsManager.REG_EXPORT_POLL_MS, 50);
        cfg.set(SettingsManager.REG_EXPORT_STABLE_MS, 100);
        cfg.set(SettingsManager.REG_VERIFY_RETRIES, 0);
        cfg.set(SettingsManager.DEL_COORD_X, 300);
        cfg.set(SettingsManager.DEL_COORD_Y, 200);
        cfg.set(SettingsManager.DEL_BUTTON_X, 300);
        cfg.set(SettingsManager.DEL_BUTTON_Y, 260);
        cfg.set(SettingsManager.DEL_WAIT, 2);
        cfg.save();

        listThenDelete_countsDown_deletes_checks_andLeavesTheDay();
        registeredToday_asksBeforeListing();
        aLot_isRefused();
        mouseBeforeDelete_deletesNothing();
        theList_survivesAClosedApp();
        stillOnThePortal_isRed();
        immediate_deletesAtEnter();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void listThenDelete_countsDown_deletes_checks_andLeavesTheDay() throws Exception {
        Portal portal = new Portal("T_001", "T_002", "T_003");
        Fixture f = fixture(portal);
        type(f.p, "T_002");
        type(f.p, "T_003");
        check("ENTER puts the label in the list and clears the field",
              listed(f.p).equals(java.util.Arrays.asList("T_002", "T_003")) && fieldText(f.p).isEmpty());
        check("nothing happens to the portal before the lever", portal.codes.size() == 3);
        lever(f.p);
        waitFor(() -> !busy(f.p), 10000);
        check("the countdown came first: two seconds", f.hands.sleeps1000() == 2);
        check("the countdown was on the HUD", f.bar.toString().contains("PARTO TRA"));
        check("both labels are deleted from the portal", portal.codes.size() == 1 && portal.codes.contains("T_001"));
        check("the sequence is click field, paste, click DELETE",
              f.hands.steps.toString().startsWith("[DBL 300,200, PASTE T_002, CLICK 300,260"));
        check("the list is empty again", listed(f.p).isEmpty());
        String[] day = { "" };
        SwingUtilities.invokeAndWait(() -> { f.rp.refreshDay(); day[0] = f.rp.dayText(); });
        check("the day counts them as deleted", day[0].contains("2 cancellate"));
        check("the check on the export confirms it", banner(f.p).startsWith("CANCELLATE E CONTROLLATE"));
        String report = readReport();
        check("the report files them as deleted and checked",
              report.contains("modalita=cancella") && report.contains("T_002;;") && report.contains("CANCELLATA OK"));
    }

    private static void registeredToday_asksBeforeListing() throws Exception {
        Portal portal = new Portal("R_001");
        Fixture f = fixture(portal);
        SwingUtilities.invokeAndWait(() -> {
            f.rp.beginRun(1, "L1");
            f.rp.addLiveRow("R_001", 1);
        });
        final List<String> asked = new ArrayList<>();
        f.p.confirmer = m -> { asked.add(m); return false; };
        type(f.p, "R_001");
        check("a label registered today asks first", asked.size() == 1
              && asked.get(0).contains("registrata oggi") && asked.get(0).contains("L1"));
        check("No keeps it off the list", listed(f.p).isEmpty());
    }

    private static void aLot_isRefused() throws Exception {
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.SCAN_QR1_PATTERN, "T_[0-9]+");
        cfg.set(SettingsManager.SCAN_QR2_PATTERN, "[0-9]{18}");
        cfg.save();
        Fixture f = fixture(new Portal());
        type(f.p, "900612202601310007");
        check("a lot scanned into the field is refused and said so",
              listed(f.p).isEmpty() && banner(f.p).contains("LOTTO"));
        type(f.p, "T_9");
        check("a code without three final digits is refused", listed(f.p).isEmpty());
        cfg.set(SettingsManager.SCAN_QR1_PATTERN, "");
        cfg.set(SettingsManager.SCAN_QR2_PATTERN, "");
        cfg.save();
    }

    private static void mouseBeforeDelete_deletesNothing() throws Exception {
        Portal portal = new Portal("M_001", "M_002");
        Fixture f = fixture(portal);
        f.hands.grabAfterPaste = true;
        type(f.p, "M_001");
        type(f.p, "M_002");
        lever(f.p);
        waitFor(() -> !busy(f.p), 10000);
        check("the mouse taken before DELETE: nothing deleted", portal.codes.size() == 2);
        check("DELETE was never clicked", !f.hands.steps.toString().contains("CLICK 300,260"));
        check("both labels stay in the list", listed(f.p).size() == 2);
        check("the banner says why", banner(f.p).contains("mouse mosso prima di DELETE"));
    }

    private static void theList_survivesAClosedApp() throws Exception {
        Files.deleteIfExists(app.config.AppFiles.deleteList().toPath());
        Fixture f = fixture(new Portal());
        type(f.p, "K_101");
        type(f.p, "K_102");
        Fixture again = fixture(new Portal());
        check("a closed app finds its list again", listed(again.p).equals(java.util.Arrays.asList("K_101", "K_102")));
        SwingUtilities.invokeAndWait(() -> {
            again.p.confirmer = m -> true;
        });
        clickButton(again.p, "Svuota");
        check("Svuota empties it, after a question", listed(again.p).isEmpty());
        Fixture third = fixture(new Portal());
        check("and the next start finds it empty", listed(third.p).isEmpty());
    }

    private static void stillOnThePortal_isRed() throws Exception {
        Portal portal = new Portal("S_001");
        portal.ignoreDeletes = true;                 // the portal did not delete it
        Fixture f = fixture(portal);
        type(f.p, "S_001");
        lever(f.p);
        waitFor(() -> !busy(f.p), 10000);
        check("a label still in the export is red", banner(f.p).contains("ANCORA SUL PORTALE"));
        final boolean[] counted = { true };
        SwingUtilities.invokeAndWait(() -> {
            app.core.DayLedger.Event e = f.rp.tableModel().day().get("S_001");
            counted[0] = e != null && e.deleted;
        });
        check("and does not count as deleted", !counted[0]);
    }

    private static void immediate_deletesAtEnter() throws Exception {
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.DEL_IMMEDIATE, true);
        cfg.set(SettingsManager.DEL_WAIT, 0);
        cfg.save();
        Portal portal = new Portal("I_001");
        Fixture f = fixture(portal);
        type(f.p, "I_001");
        waitFor(() -> !busy(f.p) && portal.codes.isEmpty(), 10000);
        check("immediate: ENTER deletes at once", portal.codes.isEmpty());
        cfg.set(SettingsManager.DEL_IMMEDIATE, false);
        cfg.set(SettingsManager.DEL_WAIT, 2);
        cfg.save();
    }

    // ── fakes ────────────────────────────────────────────────────────────

    /** The portal: a set of labels; DELETE removes the label in the field. */
    static final class Portal {
        final Set<String> codes = Collections.synchronizedSet(new LinkedHashSet<String>());
        volatile String field = "";
        volatile boolean ignoreDeletes = false;
        Portal(String... c) { codes.addAll(java.util.Arrays.asList(c)); }
        String csv() {
            StringBuilder sb = new StringBuilder("DemoSerial|DemoBatch\n");
            synchronized (codes) { for (String c : codes) sb.append(c).append("|LOT\n"); }
            return sb.toString();
        }
    }

    static final class Hands extends ScanSessionTest.QuietHands {
        final List<String> steps = Collections.synchronizedList(new ArrayList<String>());
        final Portal portal;
        volatile boolean grabAfterPaste = false;
        private volatile boolean pasted = false;
        private int ones = 0;
        Hands(Portal p) { portal = p; }
        @Override public void doubleClick(int x, int y) { steps.add("DBL " + x + "," + y); pasted = false; }
        @Override public void pasteText(String t) { steps.add("PASTE " + t); portal.field = t; pasted = true; }
        @Override public void click(int x, int y) {
            steps.add("CLICK " + x + "," + y);
            if (!portal.ignoreDeletes) portal.codes.remove(portal.field);
            portal.field = "";
        }
        @Override public void sleep(int ms) { if (ms == 1000) ones++; }
        @Override public boolean isMouseMoved(Point target) { return grabAfterPaste && pasted; }
        int sleeps1000() { return ones; }
    }

    static final class Fixture {
        DeleteModePanel p;
        ResultsPanel rp;
        Hands hands;
        final StringBuilder bar = new StringBuilder();
    }

    private static Fixture fixture(final Portal portal) throws Exception {
        final Fixture f = new Fixture();
        f.hands = new Hands(portal);
        final File downloads = new File(home, "Downloads");
        SwingUtilities.invokeAndWait(() -> {
            f.rp = new ResultsPanel(() -> { });
            f.p = new DeleteModePanel(f.rp, new RangeModeTest.NoopRunContext() {
                @Override public void jobProgress(Color tone, String state, String number,
                                                  String caption, int percent, boolean stoppable) {
                    synchronized (f.bar) { f.bar.append(state).append('|'); }
                }
            });
            f.p.hands = f.hands;
            f.p.confirmer = m -> true;
            f.p.exportClick = () -> {
                try {
                    Thread.sleep(30);
                    File out = new File(downloads, "demo-export (" + System.nanoTime() + ").csv");
                    Files.write(out.toPath(), portal.csv().getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
        });
        return f;
    }

    private static void type(DeleteModePanel p, String code) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            p.field().setText(code);
            p.field().postActionEvent();
        });
        Thread.sleep(50);
    }

    private static void lever(DeleteModePanel p) throws Exception {
        clickButton(p, "CANCELLA DAL PORTALE");
    }

    private static void clickButton(DeleteModePanel p, String prefix) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (JButton b : ResultsOwnershipTest.buttons(p)) {
                if (b.getText() != null && b.getText().startsWith(prefix)) { b.doClick(); return; }
            }
        });
        Thread.sleep(50);
    }

    private static List<String> listed(DeleteModePanel p) throws Exception {
        final List<String> out = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> out.addAll(p.listed()));
        return out;
    }

    private static String fieldText(DeleteModePanel p) throws Exception {
        final String[] t = { "" };
        SwingUtilities.invokeAndWait(() -> t[0] = p.field().getText());
        return t[0];
    }

    private static boolean busy(DeleteModePanel p) throws Exception {
        final boolean[] b = { true };
        SwingUtilities.invokeAndWait(() -> b[0] = p.isBusy());
        return b[0];
    }

    private static String banner(DeleteModePanel p) throws Exception {
        final String[] t = { "" };
        SwingUtilities.invokeAndWait(() -> t[0] = p.bannerText());
        return t[0];
    }

    private static String readReport() throws Exception {
        Thread.sleep(800);   // the journal coalesces its writes
        File rep = app.config.AppFiles.dailyReport(java.time.LocalDate.now().toString());
        return rep.exists() ? new String(Files.readAllBytes(rep.toPath()), StandardCharsets.UTF_8) : "";
    }

    interface Cond { boolean ok() throws Exception; }

    private static void waitFor(Cond c, long ms) throws Exception {
        Thread.sleep(100);
        long until = System.currentTimeMillis() + ms;
        while (!c.ok() && System.currentTimeMillis() < until) Thread.sleep(50);
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
