package app.ui;

import app.config.SettingsManager;
import app.core.CsvImport;
import app.core.ImportLedger;
import app.core.PairBurst;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

// the "Da file" card under Xvfb, with a fake robot and a fake portal: on ENTER
// the robot appends label|lot to the portal; a click on Export writes the
// portal's CSV into the download folder, where the real DownloadWatcher picks
// it up. every check that must happen before the first click, the blocks, the
// verifications between them (never inside), the fail-safe on both sides of
// ENTER, and the resume.
//
//   xvfb-run java -cp out app.ui.FileModePanelTest
public final class FileModePanelTest {

    private static int passed = 0;
    private static int failed = 0;

    static File home, dl;
    static final List<String> portal = new ArrayList<>();
    static final List<Integer> exportsAt = new ArrayList<>();   // portal size at each Export click
    static int exportSeq = 0;

    /** Fake hands: two pastes and an ENTER make a registration on the fake portal. */
    static final class Hands implements PairBurst.Hands {
        String label, lot;
        int pastes;
        int grabPair = -1;     // which pair (1-based) the operator grabs the mouse on
        int grabCheck = -1;    // at which check of that pair (1..6; 6 = after ENTER)
        int pair, checks;

        @Override public void doubleClick(int x, int y) { pair++; checks = 0; pastes = 0; }
        @Override public void click(int x, int y) { }
        @Override public void pasteText(String t) {
            if (pastes++ == 0) label = t; else lot = t;
        }
        @Override public void pressTab() { }
        @Override public void pressEnter() {
            synchronized (portal) { portal.add(label + "|" + lot); }
        }
        @Override public void sleep(int ms) { }
        @Override public boolean isMouseMoved(Point target) {
            checks++;
            return pair == grabPair && checks >= grabCheck;
        }
    }

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        home = Files.createTempDirectory("afs-file").toFile();
        System.setProperty("user.home", home.getPath());
        dl = new File(home, "dl");
        dl.mkdirs();
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.SCAN_COORD_X, 100);
        cfg.set(SettingsManager.SCAN_COORD_Y, 100);
        cfg.set(SettingsManager.SCAN_FOCUS, 0);
        cfg.set(SettingsManager.SCAN_KEY, 0);
        cfg.set(SettingsManager.SCAN_ENTER, 0);
        cfg.set(SettingsManager.REG_DOWNLOAD_DIR, dl.getPath());
        cfg.set(SettingsManager.REG_EXPORT_PREFIX, "demo-export");
        cfg.set(SettingsManager.REG_EXPORT_TIMEOUT_S, 5);
        cfg.set(SettingsManager.REG_EXPORT_POLL_MS, 50);
        cfg.set(SettingsManager.REG_EXPORT_STABLE_MS, 100);
        cfg.set(SettingsManager.REG_VERIFY_RETRIES, 0);
        cfg.set(SettingsManager.FILE_BLOCK_SIZE, 3);
        cfg.save();

        validFile_queuesEveryRow_andRunsInBlocks();
        alreadyDone_andOutOfOrder_needAConfirmation();
        conflict_stopsBeforeAnyClick();
        failSafeBeforeEnter_keepsTheRow_resumeFinishes();
        grabAfterEnter_countsTheRow_andStops();
        robotBusyElsewhere_neverStarts();
        correctionByDoubleClick_keepsTheFileLot();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    // ── harness ──────────────────────────────────────────────────────────

    static FileModePanel panel(Hands h, String reportDir) throws Exception {
        SettingsManager.getInstance().set(SettingsManager.REPORT_DIR, new File(home, reportDir).getPath());
        new File(home, reportDir).mkdirs();
        final FileModePanel[] p = new FileModePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            ResultsPanel results = new ResultsPanel(() -> { });
            p[0] = new FileModePanel(results, new NoopRunContext());
            p[0].hands = h;
            p[0].exportClick = FileModePanelTest::exportClick;
            p[0].confirmer = m -> false;
        });
        return p[0];
    }

    /** The portal's Export CSV: full history, pipe, LF — into the download folder. */
    static void exportClick() {
        try {
            Thread.sleep(20);
            StringBuilder sb = new StringBuilder("SerialNumber|ProductionLot\n");
            synchronized (portal) {
                exportsAt.add(portal.size());
                for (String r : portal) sb.append(r).append('\n');
            }
            File f = new File(dl, "demo-export (" + (++exportSeq) + ").csv");
            Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static File fixture(String name) throws Exception {
        File f = new File(home, name);
        Files.copy(new File("test/fixtures/" + name).toPath(), f.toPath(),
                   java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return f;
    }

    /** A sealed export of n new rows (labels LBL0001...), as Registro QR writes it. */
    static File sevenRows(String name, int n) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("#registro-qr;formato=1;postazione=L9;giornata=2026-10-14;export=1;creato=2026-10-14T10:00:00");
        lines.add(CsvImport.COLUMNS);
        for (int i = 1; i <= n; i++) {
            lines.add(i + ";209531.001_01-01_261" + String.format("%07d", i) + ";9006122026013100"
                      + String.format("%02d", i) + ";2026-10-14T09:00:00;SCAN;NUOVA;0;");
        }
        String sha = CsvImport.fingerprint(lines);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\r\n");
        sb.append("#fine;righe=").append(n).append(";sha256=").append(sha).append("\r\n");
        File f = new File(home, name);
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    static void open(FileModePanel p, File f) throws Exception {
        SwingUtilities.invokeAndWait(() -> p.open(f));
    }

    static void startAndWait(FileModePanel p) throws Exception {
        SwingUtilities.invokeAndWait(p::start);
        long until = System.currentTimeMillis() + 30000;
        Thread.sleep(100);
        while ((p.isRunning()) && System.currentTimeMillis() < until) Thread.sleep(50);
        Thread.sleep(200);
        SwingUtilities.invokeAndWait(() -> { });
    }

    static String word(FileModePanel p, ResultsPanel r, int row) {
        return String.valueOf(r.tableModel().getValueAt(row, 3));
    }

    static ResultsPanel results(FileModePanel p) throws Exception {
        java.lang.reflect.Field f = FileModePanel.class.getDeclaredField("results");
        f.setAccessible(true);
        return (ResultsPanel) f.get(p);
    }

    // ── cases ────────────────────────────────────────────────────────────

    private static void validFile_queuesEveryRow_andRunsInBlocks() throws Exception {
        portal.clear();
        exportsAt.clear();
        Hands h = new Hands();
        FileModePanel p = panel(h, "rep1");
        File f1 = fixture("contratto-v1-export1.csv");
        open(p, f1);
        ResultsPanel r = results(p);
        check("a valid file passes the fingerprint check", "OK".equals(p.checkValue("impronta")));
        check("a new file reads NUOVO in the register", "NUOVO".equals(p.checkValue("registro")));
        check("export 01 is in order", "OK".equals(p.checkValue("ordine")));
        check("every row is queued", r.tableModel().getRowCount() == 5 && "IN CODA".equals(word(p, r, 0)));
        check("a row touched by hand says so in words", "IN CODA (a mano)".equals(word(p, r, 3)));
        check("the banner says what will happen", p.bannerText().startsWith("PRONTO — 5 da inviare"));

        startAndWait(p);
        check("all five rows reach the portal", portal.size() == 5);
        check("pre-flight, then a check after 3 and after 5 — never inside a block",
              exportsAt.equals(Arrays.asList(0, 3, 5)));
        ImportLedger.FileState s = ImportLedger.in(new File(home, "rep1")).state(
            CsvImport.read(Files.readAllBytes(f1.toPath())).computedSha);
        check("the register says completed", s.completed && s.sent.size() == 5);
        check("AutoFillSuite kept its own copy of the file",
              new File(home, "rep1/importazioni").list().length == 1);
        check("the free-text lot reached the portal intact",
              portal.get(4).endsWith("|CA HVCO 50 T 048 3-2; M-CA HVCO 50 T 048 3-2  Rev.04; Serial No.: 000066076 ; 2023/12/11;A"));

        // export 2 next, in order: the correction of row 2 goes out as a re-send
        File f2 = fixture("contratto-v1-export2.csv");
        open(p, f2);
        check("export 02 after 01 is in order", "OK".equals(p.checkValue("ordine")));
        check("the correction is queued as such", "CORREZIONE IN CODA".equals(word(p, r, 0)));
        startAndWait(p);
        check("the correction and the two new rows reach the portal", portal.size() == 8
              && portal.get(5).equals("209531.001_01-01_2612000232|900612202601310009"));

        // seven rows, blocks of three: checks after 3, 6, 7
        portal.clear();
        exportsAt.clear();
        FileModePanel p7 = panel(new Hands(), "rep7");
        open(p7, sevenRows("sette.csv", 7));
        startAndWait(p7);
        check("blocks of 3 over 7 rows are checked after 3, 6 and 7, never in between",
              exportsAt.equals(Arrays.asList(0, 3, 6, 7)));
    }

    private static void alreadyDone_andOutOfOrder_needAConfirmation() throws Exception {
        Hands h = new Hands();
        FileModePanel p = panel(h, "rep1");   // the register of the first case: 01 and 02 done
        open(p, fixture("contratto-v1-export1.csv"));
        check("a file already completed reads GIÀ FATTO", "GIÀ FATTO".equals(p.checkValue("registro")));
        check("an older export after a newer one reads VECCHIO", "VECCHIO".equals(p.checkValue("ordine")));
        check("the lever is locked", !lever(p).isEnabled());
        check("the banner says when it was registered", p.bannerText().startsWith("GIÀ REGISTRATO"));
        final List<String> asked = new ArrayList<>();
        p.confirmer = m -> { asked.add(m); return false; };
        SwingUtilities.invokeAndWait(() -> clickOverride(p));
        check("refusing the confirmation keeps it locked", !lever(p).isEnabled());
        check("the confirmation explains the risk of an old file",
              !asked.isEmpty() && asked.get(0).contains("rimettere sul portale"));
        p.confirmer = m -> true;
        SwingUtilities.invokeAndWait(() -> clickOverride(p));
        check("an explicit confirmation unlocks it", lever(p).isEnabled());

        FileModePanel q = panel(new Hands(), "repOrder");
        open(q, fixture("contratto-v1-export2.csv"));
        check("export 02 without 01 is out of order and locked",
              "FUORI ORDINE".equals(q.checkValue("ordine")) && !lever(q).isEnabled());

        FileModePanel m = panel(new Hands(), "repMod");
        open(m, fixture("contratto-v1-manomesso.csv"));
        check("a hand-edited file is MODIFICATO and locked",
              "MODIFICATO".equals(m.checkValue("impronta")) && !lever(m).isEnabled());
        FileModePanel t = panel(new Hands(), "repTrunc");
        open(t, fixture("contratto-v1-troncato.csv"));
        check("a truncated file is refused outright, no override offered",
              "TRONCATO".equals(t.checkValue("impronta")) && !lever(t).isEnabled() && !override(t).isVisible());
    }

    private static void conflict_stopsBeforeAnyClick() throws Exception {
        portal.clear();
        portal.add("209531.001_01-01_2612000231|ALTRO-LOTTO");
        Hands h = new Hands();
        FileModePanel p = panel(h, "repConflict");
        open(p, fixture("contratto-v1-export1.csv"));
        startAndWait(p);
        check("a label registered another way stops everything", h.pair == 0 && portal.size() == 1);
        check("the conflict is on screen, with what the portal holds",
              p.bannerText().startsWith("CONFLITTO") && word(p, results(p), 0).contains("ALTRO-LOTTO"));
        check("a conflict locks the lever", !lever(p).isEnabled());
    }

    private static void failSafeBeforeEnter_keepsTheRow_resumeFinishes() throws Exception {
        portal.clear();
        Hands h = new Hands();
        h.grabPair = 2;
        h.grabCheck = 3;   // between the label and the lot
        FileModePanel p = panel(h, "repFail");
        File f = sevenRows("cinque.csv", 5);
        open(p, f);
        startAndWait(p);
        check("the mouse taken back before ENTER stops after one pair", portal.size() == 1);
        String sha = CsvImport.read(Files.readAllBytes(f.toPath())).computedSha;
        ImportLedger.FileState s = ImportLedger.in(new File(home, "repFail")).state(sha);
        check("the register has the first pair only, and the file is interrupted",
              s.sent.equals(new java.util.LinkedHashSet<>(Arrays.asList(1))) && s.interrupted());

        // reopen: the card offers to resume from row 2
        h.grabPair = -1;
        FileModePanel again = panel(h, "repFail");
        open(again, f);
        check("reopening offers Riprendi da 2", lever(again).getText().contains("Riprendi da 2")
              || lever(again).getText().contains("PRE-VOLO"));
        check("the stopped row is not counted as sent", again.sentCount() == 1);
        check("the reopened file says the row was not sent", again.bannerText().startsWith("FERMATO — alla riga 2"));
        startAndWait(again);
        check("the resume sends rows 2..5 once each", portal.size() == 5
              && portal.stream().distinct().count() == 5);
        check("after the resume the file is completed", ImportLedger.in(new File(home, "repFail")).state(sha).completed);
    }

    private static void grabAfterEnter_countsTheRow_andStops() throws Exception {
        portal.clear();
        Hands h = new Hands();
        h.grabPair = 1;
        h.grabCheck = 6;   // after ENTER
        FileModePanel p = panel(h, "repGrab");
        File f = sevenRows("tre.csv", 3);
        open(p, f);
        startAndWait(p);
        String sha = CsvImport.read(Files.readAllBytes(f.toPath())).computedSha;
        ImportLedger.FileState s = ImportLedger.in(new File(home, "repGrab")).state(sha);
        check("a mouse grabbed after ENTER: the pair is on the portal", portal.size() == 1);
        check("and in the register as sent, written right after ENTER", s.sent.contains(1));
        check("and the robot stopped there", s.interrupted() && p.sentCount() == 1);
        check("it is never queued again", !lever(p).getText().contains("Riprendi da 1"));
    }

    private static void robotBusyElsewhere_neverStarts() throws Exception {
        portal.clear();
        Hands h = new Hands();
        FileModePanel p = panel(h, "rep9");
        open(p, sevenRows("busy.csv", 4));
        app.core.RobotGate.Lease other = app.core.RobotGate.tryAcquire("Intervallo");
        try {
            startAndWait(p);
        } finally {
            other.release();
        }
        check("a file never starts while another mode holds the robot", portal.isEmpty());
        startAndWait(p);
        check("...and runs once the robot is free", portal.size() == 4);
        check("the robot is given back at the end", app.core.RobotGate.owner() == null);
    }

    // a double-click correction in "Da file" added a NEW job and left the
    // original one pending: AVVIA sent the correction, then the original row
    // with the file's old lot, which won on the portal; and every later check
    // expected the file lot, so a sent row corrected to another lot made each
    // block red. a file's rows are corrected in Registro QR: here a row can
    // only be sent again with its own lot.
    private static void correctionByDoubleClick_keepsTheFileLot() throws Exception {
        portal.clear();
        Hands h = new Hands();
        h.grabPair = 5;
        h.grabCheck = 3;   // rows 1..3 sent and verified, row 4 sent, row 5 stopped before ENTER
        FileModePanel p = panel(h, "repFix");
        File f = sevenRows("correzioni.csv", 5);
        open(p, f);
        startAndWait(p);
        check("the run stopped at row 5 with four rows on the portal", portal.size() == 4);
        final ResultsPanel r = results(p);
        String lot5 = r.tableModel().lotAt(4), lot1 = r.tableModel().lotAt(0);

        SwingUtilities.invokeAndWait(() -> r.applyFix(4, "ALTRO-LOTTO"));
        check("another lot on a file row is refused: the row keeps the file lot",
              lot5.equals(r.tableModel().lotAt(4)));
        SwingUtilities.invokeAndWait(() -> r.applyFix(4, lot5));   // the row not sent yet, same lot
        SwingUtilities.invokeAndWait(() -> r.applyFix(0, lot1));   // a sent row, sent again

        h.grabPair = -1;
        startAndWait(p);
        String label5 = r.tableModel().codeAt(4), label1 = r.tableModel().codeAt(0);
        long fives = portal.stream().filter(e -> e.startsWith(label5 + "|")).count();
        long ones  = portal.stream().filter(e -> e.startsWith(label1 + "|")).count();
        check("the row not sent yet goes out once, never twice", fives == 1);
        check("nothing else reaches the portal with another lot",
              portal.stream().noneMatch(e -> e.endsWith("|ALTRO-LOTTO")));
        check("the sent row is sent again with its own lot", ones == 2);
        check("the last registration of every label is the file's lot",
              portal.stream().filter(e -> e.startsWith(label5 + "|")).allMatch(e -> e.endsWith("|" + lot5)));
    }

    static JButton lever(FileModePanel p) {
        return find(p, b -> b.getText() != null && (b.getText().startsWith("AVVIA")
            || b.getText().startsWith("PRE-VOLO") || b.getText().startsWith("Riprendi")
            || b.getText().startsWith("REGISTRO")));
    }

    static JButton override(FileModePanel p) {
        return find(p, b -> b.getText() != null && b.getText().startsWith("Registra comunque"));
    }

    static void clickOverride(FileModePanel p) {
        JButton b = override(p);
        if (b != null) b.doClick();
    }

    static JButton find(Container root, java.util.function.Predicate<JButton> want) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton && want.test((JButton) c)) return (JButton) c;
            if (c instanceof Container) {
                JButton b = find((Container) c, want);
                if (b != null) return b;
            }
        }
        return null;
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }

    private static final class NoopRunContext implements RunContext {
        @Override public String blockingCollision(Map<String, Point> targets) { return null; }
        @Override public void jobStarted(Runnable stopAction) { }
        @Override public void jobProgress(Color tone, String state, String number,
                                          String caption, int percent, boolean stoppable) { }
        @Override public void jobFinished() { }
        @Override public void focusHome(JComponent target) { }
    }
}
