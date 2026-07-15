package app.core;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// self-contained runner for the three pure additions: the collision guard, the
// run report and the history reader. no junit, plain jdk, no screen needed —
// they are geometry, text and parsing, and that is exactly why they live in core.
//
//   java -cp build app.core.CoreExtrasTest
public final class CoreExtrasTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // the settings singleton binds to user.home at first touch: pin it to
        // a temp dir before ANY test so the suite never writes the real one
        System.setProperty("user.home",
            java.nio.file.Files.createTempDirectory("afs-extras").toString());

        guard_windowOverTheField_isCaught();
        guard_windowElsewhere_isClear();
        guard_unsetCoordinate_isIgnored();
        guard_marginCountsAsCollision();
        guard_safeLocation_avoidsEveryTarget();
        guard_safeLocation_nullWhenNothingFits();
        guard_reachable_onAnyMonitor_notJustPrimary();
        settings_saveIsAtomic_validFileNoTempLeftover();
        journal_liveRows_carryTheirOwnState();

        report_cleanRun_isAllOk();
        report_problems_areSpelledOut();
        report_uncoveredRows_areHonest();
        report_verifyLine_documentsTheExport();
        report_freeTextLotWithSemicolon_isQuoted();

        history_countsRunsAndProblems();
        history_ignoresDetailLinesAndErrors();
        history_cleanPercent();
        log_roundTrip_writerAndParserAgree();
        daily_firstRunOfTheDay();
        daily_secondRunAppends();
        daily_reverifyRewritesOwnSectionOnly();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ── guard ────────────────────────────────────────────────────────────────

    private static Map<String, Point> targets() {
        Map<String, Point> t = new LinkedHashMap<>();
        t.put("Casella 1", new Point(512, 384));
        t.put("Export CSV", new Point(900, 120));
        return t;
    }

    // the window sits right on top of the field the robot must click
    private static void guard_windowOverTheField_isCaught() {
        Rectangle win = new Rectangle(400, 300, 300, 200);   // contains 512,384
        List<String> hits = WindowGuard.collisions(win, targets());
        check("a window over the field is caught", hits.contains("Casella 1"));
        check("only the covered target is named", hits.size() == 1);
    }

    private static void guard_windowElsewhere_isClear() {
        Rectangle win = new Rectangle(20, 700, 300, 200);
        check("a window out of the way reports nothing",
              WindowGuard.collisions(win, targets()).isEmpty());
    }

    // a coordinate never memorized is -1,-1: it must not be treated as a hit
    private static void guard_unsetCoordinate_isIgnored() {
        Map<String, Point> t = new LinkedHashMap<>();
        t.put("Export CSV", new Point(-1, -1));
        check("an unset coordinate is not a collision",
              WindowGuard.collisions(new Rectangle(0, 0, 500, 500), t).isEmpty());
    }

    // a click has slop: touching the border is close enough to be a problem
    private static void guard_marginCountsAsCollision() {
        Rectangle win = new Rectangle(516, 300, 300, 200);   // starts 4px right of 512
        check("a target just outside the border still collides (margin)",
              !WindowGuard.collisions(win, targets()).isEmpty());
    }

    private static void guard_safeLocation_avoidsEveryTarget() {
        Rectangle screen = new Rectangle(0, 0, 1920, 1080);
        Rectangle win = new Rectangle(400, 300, 300, 200);
        List<Point> pts = new ArrayList<>(targets().values());
        Point p = WindowGuard.safeLocation(win, pts, screen);
        check("a safe spot is found", p != null);
        if (p != null) {
            Rectangle moved = new Rectangle(p.x, p.y, win.width, win.height);
            check("the safe spot covers no target", WindowGuard.isClear(moved, pts));
            check("the safe spot stays on screen", screen.contains(moved));
        }
    }

    private static void guard_safeLocation_nullWhenNothingFits() {
        Rectangle screen = new Rectangle(0, 0, 200, 200);
        Rectangle win = new Rectangle(0, 0, 500, 500);       // bigger than the screen
        check("no safe spot rather than a wrong guess",
              WindowGuard.safeLocation(win, new ArrayList<Point>(), screen) == null);
    }

    // ── report ───────────────────────────────────────────────────────────────

    private static VerificationResult verify(List<String> lines, List<String> codes, String lot) {
        return new RegistrationVerifier().verify(lines, codes, lot);
    }

    private static List<RunReport.Entry> entries(List<String> codes, String lot, String at) {
        List<RunReport.Entry> out = new ArrayList<>();
        for (String c : codes) out.add(new RunReport.Entry(c, lot, at));
        return out;
    }

    private static void report_cleanRun_isAllOk() {
        List<String> codes = RegistrationVerifier.expectedCodes("LBL", 1, 2);
        VerificationResult r = verify(Arrays.asList("LBL001|L1", "LBL002|L1"), codes, "L1");
        String csv = RunReport.toCsv(entries(codes, "L1", "14:32:07"), r);
        check("report starts with the header", csv.startsWith(RunReport.HEADER + "\n"));
        check("a clean row carries lot, send time and verdict",
              csv.contains("LBL001;L1;14:32:07;OK;1;"));
        check("summary states the clean count", RunReport.summary(2, r).startsWith("TUTTO OK;2/2"));
    }

    private static void report_problems_areSpelledOut() {
        List<String> codes = RegistrationVerifier.expectedCodes("LBL", 1, 3);
        VerificationResult r = verify(Arrays.asList("LBL001|L1", "LBL002|", "LBL003|ALTRO"), codes, "L1");
        String csv = RunReport.toCsv(entries(codes, "L1", "14:32:07"), r);
        check("a print-only row says so in Dettaglio",
              csv.contains("LBL002;L1;14:32:07;NON REGISTRATA;0;solo stampa, mai registrata"));
        check("a wrong lot lands in Dettaglio, filterable",
              csv.contains("LBL003;L1;14:32:07;LOTTO ERRATO;0;trovato: ALTRO"));
        check("summary counts the problems", RunReport.summary(3, r).startsWith("PROBLEMI;2/3"));
    }

    private static void report_uncoveredRows_areHonest() {
        List<String> codes = RegistrationVerifier.expectedCodes("LBL", 1, 1);
        VerificationResult r = verify(Arrays.asList("LBL001|L1"), codes, "L1");
        List<RunReport.Entry> e = new ArrayList<>();
        e.add(new RunReport.Entry("LBL001", "L1", "14:32:07"));
        e.add(new RunReport.Entry("QR-QUEUED", "L2", null));       // never sent
        String csv = RunReport.toCsv(e, r);
        check("a never-sent row reads NON INVIATA with a dash time",
              csv.contains("QR-QUEUED;L2;-;NON INVIATA;0;mai inviata al portale"));
    }

    private static void report_verifyLine_documentsTheExport() {
        check("the verify line files what the UI knew",
              RunReport.verifyLine("14:33:40", "demo-export (4).csv", 2, false)
                  .equals("Verifica;14:33:40;file=demo-export (4).csv;tentativi=2;export=ripescato"));
    }

    // real lots are free text and DO contain semicolons: the csv must survive it
    private static void report_freeTextLotWithSemicolon_isQuoted() {
        String lot = "CA HVCO 50 T; Rev.04; Serial No.: 66076";
        List<String> codes = RegistrationVerifier.expectedCodes("LBL", 1, 1);
        VerificationResult r = verify(Arrays.asList("LBL001|" + lot), codes, lot);
        String csv = RunReport.toCsv(entries(codes, lot, "09:00:00"), r);
        check("a lot with semicolons is quoted", csv.contains("\"" + lot + "\""));
        check("the quoted row still has 6 fields",
              csv.split("\n")[1].replace("\"" + lot + "\"", "LOT").split(";", -1).length == 6);
    }

    // ── history ──────────────────────────────────────────────────────────────

    private static List<String> log() {
        return Arrays.asList(
            "2026-07-10 08:15:02  OK 50/50 · lotto=900612 · file=demo-export (1).csv · tentativi=1",
            "2026-07-10 09:02:44  PROBLEMI 3/50 · mancanti=2 nonReg=1 lottoErr=0 doppie=0",
            "    mancanti: LBL047",
            "    mancanti: LBL048",
            "    non registrate: LBL049",
            "2026-07-11 07:41:10  OK 20/20 · lotto=900755 · file=demo-export (2).csv · tentativi=2",
            "2026-07-11 10:20:00  ERRORE · Export non arrivato entro il timeout.",
            "2026-07-11 10:25:00  ANNULLATA",
            "2026-07-11 11:00:00  PROBLEMI 1/10 · mancanti=1 nonReg=0 lottoErr=0 doppie=0");
    }

    private static void history_countsRunsAndProblems() {
        VerificationHistory.Stats s =
            VerificationHistory.stats(VerificationHistory.parse(log()));
        check("only real runs are counted", s.runs == 4);
        check("clean runs are counted", s.cleanRuns == 2);
        check("problem runs are counted", s.problemRuns == 2);
        check("problems are summed", s.totalProblems == 4);
        check("labels are summed", s.totalLabels == 130);
    }

    private static void history_ignoresDetailLinesAndErrors() {
        VerificationHistory.Stats s =
            VerificationHistory.stats(VerificationHistory.parse(log()));
        check("indented detail lines are not runs", s.runs == 4);
        check("problems land on the right day", s.problemsPerDay.get("2026-07-10") == 3);
        check("a day with a clean run is present with zero", s.problemsPerDay.get("2026-07-11") == 1);
    }

    private static void history_cleanPercent() {
        VerificationHistory.Stats s =
            VerificationHistory.stats(VerificationHistory.parse(log()));
        check("clean percentage", s.cleanPercent() == 50);
        check("an empty log does not divide by zero",
              VerificationHistory.stats(VerificationHistory.parse(
                  new ArrayList<String>())).cleanPercent() == 0);
    }

    // ── daily report file: one section per run, merged in place ────────────

    private static final String DAY = "2026-07-13";

    private static List<String> section(String time, String lot, String... rows) {
        List<String> s = new ArrayList<>();
        s.add(RunReport.sectionHead(DAY, time, lot, "intervallo"));
        s.add(RunReport.HEADER);
        for (String r : rows) s.add(r);
        return s;
    }

    private static List<String> merge(List<String> existing, String time, List<String> sec) {
        return RunReport.mergeDaily(existing, RunReport.sectionKey(DAY, time), sec);
    }

    private static void daily_firstRunOfTheDay() {
        List<String> merged = merge(new ArrayList<String>(),
            "08:15:02", section("08:15:02", "L1", "A;L1;OK;1"));
        check("the first run IS the file", merged.size() == 3
              && merged.get(0).equals(RunReport.sectionHead(DAY, "08:15:02", "L1", "intervallo")));
        check("a lot with a semicolon cannot break the head",
              RunReport.sectionHead(DAY, "08:15:02", "L;1", "intervallo").equals("RUN;" + DAY + ";08:15:02;lotto=\"L;1\";modalita=intervallo"));
    }

    private static void daily_secondRunAppends() {
        List<String> day = merge(new ArrayList<String>(),
            "08:15:02", section("08:15:02", "L1", "A;L1;OK;1"));
        List<String> merged = merge(day,
            "09:30:11", section("09:30:11", "L2", "B;L2;OK;1"));
        check("the second run lands after the first",
              headIndex(merged, "08:15:02") < headIndex(merged, "09:30:11"));
        check("a blank line separates the sections",
              merged.get(headIndex(merged, "09:30:11") - 1).isEmpty());
        check("the first run is untouched", merged.contains("A;L1;OK;1"));
    }

    private static void daily_reverifyRewritesOwnSectionOnly() {
        List<String> day = new ArrayList<>();
        day = merge(day, "08:15:02", section("08:15:02", "L1", "A;L1;OK;1"));
        day = merge(day, "09:30:11", section("09:30:11", "L2", "B;L2;MANCANTE;0"));
        day = merge(day, "11:00:00", section("11:00:00", "L3", "C;L3;OK;1"));

        // the operator fixed B on the site and pressed RIPROVA
        List<String> merged = merge(day, "09:30:11",
            section("09:30:11", "L2", "B;L2;OK;1"));

        check("the re-verified section is rewritten", merged.contains("B;L2;OK;1"));
        check("its old verdict is gone", !merged.contains("B;L2;MANCANTE;0"));
        check("the neighbours survive",
              merged.contains("A;L1;OK;1") && merged.contains("C;L3;OK;1"));
        check("the order of the day holds",
              headIndex(merged, "08:15:02") < headIndex(merged, "09:30:11")
           && headIndex(merged, "09:30:11") < headIndex(merged, "11:00:00"));
        check("re-verifying never grows the run count",
              countHeads(merged) == 3);
    }

    private static int headIndex(List<String> lines, String time) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(RunReport.sectionKey(DAY, time))) return i;
        }
        return -1;
    }

    private static int countHeads(List<String> lines) {
        int n = 0;
        for (String l : lines) if (l.startsWith("RUN;")) n++;
        return n;
    }

    // the drift this test forbids: the writer once glued the counters together
    // ("mancanti=2nonReg=1") while the parser tests kept the readable fixture —
    // green CI, garbage file. what append() writes TODAY, parse() must count
    // TODAY, and a human must be able to read it.
    private static void log_roundTrip_writerAndParserAgree() {
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("afs-log", ".txt");
            VerificationLog log = new VerificationLog(tmp);

            Map<String, String> wrong = new LinkedHashMap<>();
            wrong.put("LBL003", "ALTRO");
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("LBL001", 1);
            VerificationResult r = new VerificationResult(
                Arrays.asList("LBL002"), new ArrayList<String>(), wrong,
                new ArrayList<String>(), counts, 1);

            check("the entry is written",
                  log.append(VerificationLog.formatEntry(r, "demo-export (7).csv", 2, 3,
                                                         "lotto=L1", false)));

            List<String> lines = java.nio.file.Files.readAllLines(
                tmp, java.nio.charset.StandardCharsets.UTF_8);
            String head = lines.get(0);

            check("the stamp and the verdict are separated",
                  head.contains("  PROBLEMI "));
            check("the counters read as words, not as a glued blob",
                  head.contains(" mancanti=1 nonReg=0 lottoErr=1 doppie=0"));
            check("a stale file is flagged with a space before the note",
                  head.contains(" (non nuovo)"));

            VerificationHistory.Stats s =
                VerificationHistory.stats(VerificationHistory.parse(lines));
            check("the parser counts the fresh entry as one run", s.runs == 1);
            check("the parser reads the problem count back", s.totalProblems == 2);
            check("the parser reads the label count back", s.totalLabels == 3);

            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception e) {
            check("round trip did not throw (" + e + ")", false);
        }
    }

    // ── harness ──────────────────────────────────────────────────────────────

    // restorePosition asked only the primary screen: a window remembered on a
    // second monitor was thrown back to default. reachability is per-screen.
    private static void guard_reachable_onAnyMonitor_notJustPrimary() {
        List<Rectangle> two = Arrays.asList(
            new Rectangle(0, 0, 1920, 1080),          // primary
            new Rectangle(1920, 0, 1920, 1080));      // second, to the right

        check("a window on the SECOND monitor is reachable",
              WindowGuard.reachableOnAny(new Rectangle(2200, 300, 700, 400), two));
        check("a window on the primary still is",
              WindowGuard.reachableOnAny(new Rectangle(100, 100, 700, 400), two));
        check("a window on an unplugged monitor is not",
              !WindowGuard.reachableOnAny(new Rectangle(5000, 300, 700, 400), two));
        check("a sliver of the title bar is enough to grab",
              WindowGuard.reachableOnAny(new Rectangle(-580, 200, 700, 400), two));
        check("a title bar ABOVE every screen cannot be grabbed",
              !WindowGuard.reachableOnAny(new Rectangle(100, -200, 700, 400), two));
    }

    // a crash mid-save must never leave a half-written settings file
    private static void settings_saveIsAtomic_validFileNoTempLeftover() throws Exception {
        app.config.SettingsManager cfg = app.config.SettingsManager.getInstance();
        cfg.set("probe.key", "probe-value");
        cfg.save();
        java.io.File home = new java.io.File(System.getProperty("user.home"));
        java.io.File f    = new java.io.File(home, ".autofill_suite.properties");
        check("the settings file lands", f.exists());
        check("no temp sibling is left behind",
              !new java.io.File(home, f.getName() + ".tmp").exists());
        java.util.Properties back = new java.util.Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            back.load(in);
        }
        check("the file reads back whole", "probe-value".equals(back.getProperty("probe.key")));
    }

    // the automatic journal: before any verification exists, a row's verdict
    // is its own state — sent rows say INVIATA with their time, queued say IN CODA
    private static void journal_liveRows_carryTheirOwnState() {
        List<RunReport.Entry> entries = Arrays.asList(
            new RunReport.Entry("LBL001", "L1", "08:15:07"),
            new RunReport.Entry("LBL002", "L1", null));
        List<String> lines = RunReport.lines(RunReport.liveCsv(entries).trim());
        check("the live journal opens with the shared header",
              lines.get(0).equals(RunReport.HEADER));
        check("a sent row is INVIATA with its exact time",
              lines.get(1).equals("LBL001;L1;08:15:07;INVIATA;0;"));
        check("a queued row is IN CODA with no time",
              lines.get(2).equals("LBL002;L1;-;IN CODA;0;"));
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
}
