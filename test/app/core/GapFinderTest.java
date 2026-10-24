package app.core;

import java.util.Arrays;
import java.util.List;

// 510 and 520 registered, nothing in between: 511-519 are pieces nobody
// registered, or spoiled labels nobody deleted from the portal. the operator
// must see it while it can still be fixed, not at the end of the month.
//
//   java -cp out app.core.GapFinderTest
public final class GapFinderTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        oneHole_isFound_withItsCodes();
        everyType_isItsOwnSequence();
        tooWide_isAJump_notMissingWork();
        leadingZeros_areKept();
        noNumber_isIgnored();
        noHole_noGap();
        csv_listsHolesAndJumps();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static final String T1 = "209531.001_01-01_";

    private static void oneHole_isFound_withItsCodes() {
        List<GapFinder.Gap> g = GapFinder.find(Arrays.asList(
            T1 + "2614300510", T1 + "2614300508", T1 + "2614300509", T1 + "2614300514"), 50);
        check("one hole", g.size() == 1 && GapFinder.holes(g) == 1);
        check("from the first missing to the last missing",
              (T1 + "2614300511").equals(g.get(0).fromCode) && (T1 + "2614300513").equals(g.get(0).toCode));
        check("three labels missing", g.get(0).count() == 3 && GapFinder.missing(g) == 3);
        check("the hole lists its codes", g.get(0).codes().size() == 3
              && g.get(0).codes().get(1).equals(T1 + "2614300512"));
    }

    private static void everyType_isItsOwnSequence() {
        String t2 = "300000.002_02-01_";
        List<GapFinder.Gap> g = GapFinder.find(Arrays.asList(
            T1 + "2614300001", T1 + "2614300002", t2 + "2614300005", t2 + "2614300007"), 50);
        check("only the type with a hole reports it", g.size() == 1 && t2.equals(g.get(0).prefix));
    }

    private static void tooWide_isAJump_notMissingWork() {
        List<GapFinder.Gap> g = GapFinder.find(Arrays.asList(
            T1 + "2614300399", T1 + "2614300600"), 50);
        check("a jump of 200 is reported", g.size() == 1 && g.get(0).jump);
        check("a jump is never counted as missing", GapFinder.missing(g) == 0 && GapFinder.holes(g) == 0);
        check("a jump lists no codes", g.get(0).codes().isEmpty());
        List<GapFinder.Gap> wide = GapFinder.find(Arrays.asList(
            T1 + "2614300399", T1 + "2614300600"), 500);
        check("a wider limit makes the same run a hole", !wide.get(0).jump && wide.get(0).count() == 200);
    }

    private static void leadingZeros_areKept() {
        List<GapFinder.Gap> g = GapFinder.find(Arrays.asList("LBL007", "LBL010"), 50);
        check("the codes keep their zeros", "LBL008".equals(g.get(0).fromCode) && "LBL009".equals(g.get(0).toCode));
        List<GapFinder.Gap> w = GapFinder.find(Arrays.asList("LBL7", "LBL007"), 50);
        check("7 and 007 are not neighbours", w.isEmpty());
    }

    private static void noNumber_isIgnored() {
        check("a code without a final number has no sequence",
              GapFinder.find(Arrays.asList("ABC", "DEF-", "X1", "X3"), 50).size() == 1);
    }

    private static void noHole_noGap() {
        check("a full sequence has no holes",
              GapFinder.find(Arrays.asList(T1 + "001", T1 + "002", T1 + "003"), 50).isEmpty());
    }

    private static void csv_listsHolesAndJumps() {
        String csv = GapFinder.toCsv(GapFinder.find(Arrays.asList("A001", "A004", "A900"), 50));
        check("the csv has a header and both lines",
              csv.startsWith("Tipo;Da;A;Quante;Stato\n") && csv.contains("A002;A003;2;MANCANTI")
              && csv.contains("SALTO"));
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
