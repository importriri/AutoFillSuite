package app.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

// the register of imported files: what was completed, what was interrupted and
// where, and the ORDER of a station's exports — an old file registered after a
// correction puts the wrong lot back, in silence, because the last one wins.
// the writer and the parser are tested together: they drifted apart once in
// VerificationLog.
//
//   java -cp out app.core.ImportLedgerTest
public final class ImportLedgerTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        roundTrip_writerAndParserAgree();
        interrupted_knowsWhatWentOut();
        cutLine_isIgnored_notGuessed();
        order_previousFirst_neverAnOlderOneAfter();
        archive_keepsItsOwnCopy();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static ImportLedger fresh() throws Exception {
        File dir = Files.createTempDirectory("afs-import").toFile();
        return ImportLedger.in(dir);
    }

    private static void roundTrip_writerAndParserAgree() throws Exception {
        ImportLedger l = fresh();
        String free = "CA HVCO 50 T 048 3-2; M-CA HVCO 50 T 048 3-2  Rev.04; Serial No.: 000066076 ; 2023/12/11;A";
        l.start("aaa111", "registro_L2_2026-10-13_01.csv", "L2", "2026-10-13", 1, 5, false);
        l.sent("aaa111", 1, "209531.001_01-01_2612000231", "900612202601310007");
        l.sent("aaa111", 6, "209531.001_01-01_2612000236", free);
        l.sent("aaa111", 7, "X\tY", "a\\b\nc");
        l.blockVerified("aaa111", 3, true);
        l.completed("aaa111", true);
        List<ImportLedger.Entry> e = l.read();
        check("every event written is read back", e.size() == 6);
        check("the start carries station, day, export, rows",
              "L2".equals(e.get(0).get("postazione")) && "1".equals(e.get(0).get("export"))
              && "5".equals(e.get(0).get("righe")));
        check("a free-text lot survives the register", free.equals(e.get(2).get("lotto")));
        check("TAB, LF and backslash survive their escaping",
              "X\tY".equals(e.get(3).get("etichetta")) && "a\\b\nc".equals(e.get(3).get("lotto")));
        ImportLedger.FileState s = l.state("aaa111");
        check("the file reads as completed, green", s.completed && "verde".equals(s.outcome)
              && !s.interrupted());
        check("an unknown fingerprint was never started", !l.state("zzz").started);
    }

    private static void interrupted_knowsWhatWentOut() throws Exception {
        ImportLedger l = fresh();
        l.start("bbb", "f.csv", "L2", "2026-10-13", 1, 4, false);
        l.sent("bbb", 1, "A001", "L");
        l.sent("bbb", 2, "A002", "L");
        l.interrupted("bbb", "mouse mosso");
        ImportLedger.FileState s = l.state("bbb");
        check("a stopped file is interrupted", s.interrupted());
        check("the register knows which rows went out", s.sent.equals(new java.util.LinkedHashSet<>(Arrays.asList(1, 2))));
        l.start("bbb", "f.csv", "L2", "2026-10-13", 1, 4, false);   // resumed
        l.sent("bbb", 3, "A003", "L");
        l.sent("bbb", 4, "A004", "L");
        l.completed("bbb", true);
        check("after the resume it is completed", l.state("bbb").completed);
        check("the resume kept the first start time", !l.state("bbb").startedAt.isEmpty());
    }

    private static void cutLine_isIgnored_notGuessed() throws Exception {
        ImportLedger l = fresh();
        l.start("ccc", "f.csv", "L2", "2026-10-13", 1, 2, false);
        l.sent("ccc", 1, "A001", "L");
        l.sent("ccc", 2, "A002", "L");
        byte[] all = Files.readAllBytes(l.file());
        Files.write(l.file(), Arrays.copyOf(all, all.length - 9));   // power cut mid-line
        ImportLedger.FileState s = l.state("ccc");
        check("a line cut in half is ignored", s.sent.size() == 1 && s.sent.contains(1));
        check("the lines before it all read", s.started);
        check("garbage lines are skipped",
              ImportLedger.parse("not a line\n2026-10-13 10:00:00\tINVIATA\tn=3\n").isEmpty());
        String text = new String(Files.readAllBytes(l.file()), StandardCharsets.UTF_8);
        check("the register stays a readable text file", text.startsWith("20") && text.contains("\tINIZIO\t"));
    }

    private static void order_previousFirst_neverAnOlderOneAfter() throws Exception {
        ImportLedger l = fresh();
        check("export 1 may go first", l.orderProblem("L2", "2026-10-13", 1) == null);
        check("export 2 without 1 is refused", l.orderProblem("L2", "2026-10-13", 2) != null
              && l.orderProblem("L2", "2026-10-13", 2).contains("manca l'export 01"));
        l.start("e1", "01.csv", "L2", "2026-10-13", 1, 5, false);
        l.sent("e1", 1, "A", "L");
        check("export 2 while 1 is only started is still refused",
              l.orderProblem("L2", "2026-10-13", 2) != null);
        l.completed("e1", true);
        check("export 2 after 1 is completed may go", l.orderProblem("L2", "2026-10-13", 2) == null);
        l.start("e2", "02.csv", "L2", "2026-10-13", 2, 3, false);
        l.sent("e2", 2, "A", "L2");
        l.completed("e2", true);
        String back = l.orderProblem("L2", "2026-10-13", 1);
        check("export 1 again after 2 is refused: it would put the old lots back",
              back != null && back.contains("rimetterebbe"));
        check("another station's day is independent", l.orderProblem("L3", "2026-10-13", 1) == null);
        check("another day is independent", l.orderProblem("L2", "2026-10-14", 1) == null);
        check("a re-export numbered after the others is accepted",
              l.orderProblem("L2", "2026-10-13", 3) == null);
        check("completed exports are listed in order",
              l.completedExports("L2", "2026-10-13").equals(Arrays.asList(1, 2)));
    }

    private static void archive_keepsItsOwnCopy() throws Exception {
        File report = Files.createTempDirectory("afs-report").toFile();
        File stick = Files.createTempDirectory("afs-stick").toFile();
        File csv = new File(stick, "registro_L2_2026-10-13_01.csv");
        Files.copy(new File("test/fixtures/contratto-v1-export1.csv").toPath(), csv.toPath());
        String sha = "d570b815243882baae5e2a86912bb220f2440fb74c91e472f001b696f8a788db";
        File kept = ImportLedger.archive(report, csv, sha);
        check("the import keeps a copy named with the fingerprint",
              kept.getName().equals("registro_L2_2026-10-13_01_d570b815.csv"));
        check("the copy is identical",
              Arrays.equals(Files.readAllBytes(csv.toPath()), Files.readAllBytes(kept.toPath())));
        check("the stick can go: the copy stays", csv.delete() && kept.exists());
        Files.copy(kept.toPath(), csv.toPath());   // the same file imported again
        check("archiving the same file again does not duplicate",
              ImportLedger.archive(report, csv, sha).equals(kept)
              && new File(report, "importazioni").list().length == 1);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
