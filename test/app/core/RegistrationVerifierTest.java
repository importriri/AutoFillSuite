package app.core;

import java.util.Arrays;
import java.util.List;

// self-contained test runner for RegistrationVerifier — no junit, no deps, so it
// compiles and runs with the same plain jdk the project already needs.
//
//   javac -d build src/app/core/*.java test/app/core/RegistrationVerifierTest.java
//   java  -cp build app.core.RegistrationVerifierTest
//
// each case builds an in-memory export (the fixture), runs the REAL verifier and
// checks the diff. a run always knows its own labels, so "correct" means the
// verifier agrees with what the app tried to write — the whole point of this feature.
public final class RegistrationVerifierTest {

    private static final String LOT = "LOT-2026-07";
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        cleanRun_allPresent_isGreen();
        header_onFirstLine_isDropped();
        noHeader_firstLineIsData();
        missingLabel_isCritical();
        emptyBatch_isPrintedNotRegistered();
        wrongLot_isFlagged();
        fullHistoryExport_ignoresForeignLabels();
        spacesAroundSeparator_areTrimmed();
        duplicateLabel_warnsButStaysGreen();
        rightAndWrongLot_isFlaggedAsDuplicate();
        printThenRegister_appendedRow_isGreen();
        redoRun_leftoverEmptyRows_areHarmless();
        wrongThenCorrected_isGreenWithDuplicateWarning();
        headerAfterBlankLines_isStillDropped();
        freeTextLot_matchesStrict();
        realShapeLabels_lineUp();
        pairs_eachLabelItsOwnLot_isGreen();
        pairs_wrongLotOnOneLabel_isFlagged();
        bomOnFirstLine_isHandled();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // three labels: LBL001..LBL003, all with the run's batch -> clean, 3 matched
    private static void cleanRun_allPresent_isGreen() {
        VerificationResult r = run(lines(
            "LBL001|" + LOT,
            "LBL002|" + LOT,
            "LBL003|" + LOT));
        check("clean run is green", r.isClean());
        check("clean run matched all three", r.getMatched() == 3);
        check("clean run has no problems", r.totalProblems() == 0);
        check("clean run counts one registration per label",
              r.getRegistrationCounts().get("LBL002") == 1);
    }

    // "Etichetta|Batch" on line 1 must be recognised as a header and skipped
    private static void header_onFirstLine_isDropped() {
        VerificationResult r = run(lines(
            "Etichetta|Batch",
            "LBL001|" + LOT,
            "LBL002|" + LOT,
            "LBL003|" + LOT));
        check("header row is dropped, run stays green", r.isClean());
        check("header row is not counted as a match", r.getMatched() == 3);
    }

    // no header: line 1 is real data and must not be swallowed by the header guard
    private static void noHeader_firstLineIsData() {
        VerificationResult r = run(lines(
            "LBL001|" + LOT,
            "LBL002|" + LOT,
            "LBL003|" + LOT));
        check("first data line survives when there is no header", r.getMatched() == 3);
    }

    // LBL002 never appears -> missing, and missing keeps the light red
    private static void missingLabel_isCritical() {
        VerificationResult r = run(lines(
            "LBL001|" + LOT,
            "LBL003|" + LOT));
        check("a missing label is caught", r.getMissing().contains("LBL002"));
        check("a missing label blocks green", !r.isClean());
    }

    // label printed but batch cell empty -> the print/register gap he described
    private static void emptyBatch_isPrintedNotRegistered() {
        VerificationResult r = run(lines(
            "LBL001|" + LOT,
            "LBL002|",
            "LBL003|" + LOT));
        check("empty batch is flagged as not-registered", r.getNotRegistered().contains("LBL002"));
        check("empty batch blocks green", !r.isClean());
    }

    // right label, wrong batch -> off-by-one / stale field
    private static void wrongLot_isFlagged() {
        VerificationResult r = run(lines(
            "LBL001|" + LOT,
            "LBL002|LOT-2026-06",
            "LBL003|" + LOT));
        check("wrong batch is flagged", r.getWrongLot().size() == 1);
        check("wrong batch entry names the label", r.getWrongLot().get(0).startsWith("LBL002"));
        check("wrong batch blocks green", !r.isClean());
    }

    // export carries older runs too -> foreign labels are ignored, ours still line up
    private static void fullHistoryExport_ignoresForeignLabels() {
        VerificationResult r = run(lines(
            "ZZZ998|OLD-LOT",
            "LBL001|" + LOT,
            "ZZZ999|OLD-LOT",
            "LBL002|" + LOT,
            "LBL003|" + LOT));
        check("foreign labels do not break a clean run", r.isClean());
        check("only expected labels are matched", r.getMatched() == 3);
    }

    // "LBL001 | LOT " with padding around the pipe must trim to a clean match
    private static void spacesAroundSeparator_areTrimmed() {
        VerificationResult r = run(lines(
            "LBL001 | " + LOT + " ",
            " LBL002 |" + LOT,
            "LBL003| " + LOT));
        check("spaces around the separator are trimmed", r.isClean());
        check("padded rows still match all three", r.getMatched() == 3);
    }

    // the case the docs once called "unflagged": a label carrying BOTH the
    // right lot and a foreign one. it is NOT silent — the right lot means the
    // label is matched, and the extra foreign row makes it a duplicate: the
    // list the operator scans for exactly these "give it a second look" cases.
    private static void rightAndWrongLot_isFlaggedAsDuplicate() {
        VerificationResult r = run(lines(
            "LBL001|" + LOT,
            "LBL002|" + LOT,           // right lot landed
            "LBL002|WRONG-9999",       // and a foreign one too
            "LBL003|" + LOT));
        check("a label with the right lot still counts as matched",
              r.getRegistrationCounts().containsKey("LBL002"));
        check("the extra foreign lot flags it as a duplicate — never silent",
              r.getDuplicates().contains("LBL002"));
        check("it is not misreported as a wrong-lot row",
              !r.getWrongLots().containsKey("LBL002"));
    }

    // same label twice with the right batch -> warning, but not a failure
    private static void duplicateLabel_warnsButStaysGreen() {
        VerificationResult r = run(lines(
            "LBL001|" + LOT,
            "LBL002|" + LOT,
            "LBL002|" + LOT,
            "LBL003|" + LOT));
        check("a duplicate label is reported", r.getDuplicates().contains("LBL002"));
        check("a duplicate alone does not block green", r.isClean());
        check("the double registration is counted",
              r.getRegistrationCounts().get("LBL002") == 2);
    }

    // dual scan: every label carries its OWN lot (the second QR of the pair)
    private static void pairs_eachLabelItsOwnLot_isGreen() {
        RegistrationVerifier v = new RegistrationVerifier();
        java.util.Map<String, String> pairs = new java.util.LinkedHashMap<>();
        pairs.put("LBL001", "LOT-A");
        pairs.put("LBL002", "LOT-B");
        VerificationResult r = v.verify(lines(
            "SerialNumber|ProductionLot",
            "LBL001|LOT-A",
            "LBL002|LOT-B"), pairs);
        check("pairs with per-label lots come out green", r.isClean());
        check("pairs match one by one", r.getMatched() == 2);
    }

    private static void pairs_wrongLotOnOneLabel_isFlagged() {
        RegistrationVerifier v = new RegistrationVerifier();
        java.util.Map<String, String> pairs = new java.util.LinkedHashMap<>();
        pairs.put("LBL001", "LOT-A");
        pairs.put("LBL002", "LOT-B");
        VerificationResult r = v.verify(lines(
            "SerialNumber|ProductionLot",
            "LBL001|LOT-A",
            "LBL002|LOT-A"), pairs);
        check("a pair registered with the other label's lot is flagged",
              !r.isClean() && r.getWrongLot().size() == 1
              && r.getWrongLot().get(0).startsWith("LBL002"));
    }

    // BOM glued to the first cell must not turn LBL001 into a stranger
    private static void bomOnFirstLine_isHandled() {
        VerificationResult r = run(lines(
            "\uFEFFLBL001|" + LOT,
            "LBL002|" + LOT,
            "LBL003|" + LOT));
        check("a leading BOM does not corrupt the first label", r.isClean());
    }

    // append model, straight from a real export: the print lands as "<label>|",
    // the registration appends a second row with the lot — the empty row stays.
    private static void printThenRegister_appendedRow_isGreen() {
        VerificationResult r = run(lines(
            "LBL001|",
            "LBL001|" + LOT,
            "LBL002|",
            "LBL002|" + LOT,
            "LBL003|",
            "LBL003|" + LOT));
        check("print row + later registration row is green", r.isClean());
        check("leftover print rows raise no duplicate noise", r.getDuplicates().isEmpty());
        check("append pairs count as one match each", r.getMatched() == 3);
        check("a print row does not inflate the registration count",
              r.getRegistrationCounts().get("LBL001") == 1);
    }

    // a failed run leaves a block of printed-only rows; the redo registers for real
    // much later in the file (real export: empty at row 8199, lot at row 15728).
    private static void redoRun_leftoverEmptyRows_areHarmless() {
        VerificationResult r = run(lines(
            "LBL001|",
            "LBL002|",
            "LBL003|",
            "ZZZ777|OLD-LOT",
            "LBL001|" + LOT,
            "LBL002|" + LOT,
            "LBL003|" + LOT));
        check("a redo after a failed run comes out green", r.isClean());
    }

    // registered once with the wrong lot, then corrected: green, but the double
    // registration deserves a glance — that is what the duplicates list is for.
    private static void wrongThenCorrected_isGreenWithDuplicateWarning() {
        VerificationResult r = run(lines(
            "LBL001|" + LOT,
            "LBL002|WRONG-LOT",
            "LBL002|" + LOT,
            "LBL003|" + LOT));
        check("a corrected wrong lot ends green", r.isClean());
        check("the double registration is flagged", r.getDuplicates().contains("LBL002"));
    }

    private static void headerAfterBlankLines_isStillDropped() {
        VerificationResult r = run(lines(
            "",
            "SerialNumber|ProductionLot",
            "LBL001|" + LOT,
            "LBL002|" + LOT,
            "LBL003|" + LOT));
        check("blank lines before the header do not confuse the guard", r.isClean());
    }

    // lots are sometimes free text pasted by the operator — semicolons, dots,
    // double spaces and all. strict equality must hold on the monsters too.
    private static void freeTextLot_matchesStrict() {
        String lot = "CA HVCO 50 T 048 3-2; M-CA HVCO 50 T 048 3-2  Rev.04; Serial No.: 000066076 ; 2023/12/11;A";
        RegistrationVerifier v = new RegistrationVerifier();
        List<String> expected = RegistrationVerifier.expectedCodes("LBL", 1, 2);
        VerificationResult r = v.verify(lines(
            "SerialNumber|ProductionLot",
            "LBL001|" + lot,
            "LBL002|" + lot), expected, lot);
        check("a free-text lot with double spaces matches strictly", r.isClean());
        check("a free-text lot never collapses into a wrong-lot", r.getWrongLot().isEmpty());
    }

    // real label shapes: long article prefix, the last 3 digits are the sequence
    private static void realShapeLabels_lineUp() {
        RegistrationVerifier v = new RegistrationVerifier();
        List<String> expected = RegistrationVerifier.expectedCodes("A-0001-000539_01-03_2610900", 3, 3);
        VerificationResult r = v.verify(lines(
            "SerialNumber|ProductionLot",
            "A-0001-000539_01-03_2610900003|" + LOT,
            "A-0001-000539_01-03_2610900004|" + LOT,
            "A-0001-000539_01-03_2610900005|" + LOT), expected, LOT);
        check("real-shape labels with long prefixes line up", r.isClean() && r.getMatched() == 3);
    }

    // ── harness ────────────────────────────────────────────────────────────────

    private static VerificationResult run(List<String> export) {
        RegistrationVerifier v = new RegistrationVerifier();
        List<String> expected = RegistrationVerifier.expectedCodes("LBL", 1, 3);
        return v.verify(export, expected, LOT);
    }

    private static List<String> lines(String... rows) {
        return Arrays.asList(rows);
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
