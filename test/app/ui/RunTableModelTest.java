package app.ui;

import app.core.VerificationResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// run table model — the states the operator reads at a glance. pure model,
// no screen: the one rule worth locking in is that a verification only paints
// the rows it actually covered. a dual-scan pair still sitting in the queue
// when a mid-session verify runs was coming out GREEN — never sent, marked OK.
//
//   java -cp build app.ui.RunTableModelTest
public final class RunTableModelTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        coveredRows_getTheirVerdict();
        uncoveredRow_staysPending_neverFakeGreen();
        multipleRegistrations_readOkTimesN();
        lotFix_editsTheRow_andTheReportSeesIt();
        dayView_archivesRunsAndTranslatesIndexes();
        dayView_reportNeverReadsHistory();
        dayView_rollsOverAtMidnight();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static VerificationResult result(List<String> missing, List<String> notReg,
                                             Map<String, String> wrong,
                                             Map<String, Integer> counts, int matched) {
        return new VerificationResult(missing, notReg, wrong,
                                      Arrays.<String>asList(), counts, matched);
    }

    private static void coveredRows_getTheirVerdict() {
        RunTableModel m = new RunTableModel();
        m.beginRun("L1");
        m.addRow("LBL001");
        m.addRow("LBL002");
        m.addRow("LBL003");

        Map<String, String> wrong = new LinkedHashMap<>();
        wrong.put("LBL003", "ALTRO");
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("LBL001", 1);
        m.applyResult(result(Arrays.asList("LBL002"), Arrays.<String>asList(),
                             wrong, counts, 1));

        check("a matched row reads OK",        "OK".equals(m.getValueAt(0, 3)));
        check("a matched row is green",        m.stateAt(0) == RunTableModel.OK);
        check("a missing row reads MANCANTE",  "MANCANTE".equals(m.getValueAt(1, 3)));
        check("a wrong-lot row names the lot",
              String.valueOf(m.getValueAt(2, 3)).startsWith("LOTTO ERRATO"));
    }

    // the bug this test pins down: a code the checker never saw (still queued)
    // fell through the old else-branch and came out "OK"
    private static void uncoveredRow_staysPending_neverFakeGreen() {
        RunTableModel m = new RunTableModel();
        m.beginRun("");
        int sent   = m.addRow("QR-SENT", "LOT-A");
        int queued = m.addRow("QR-QUEUED", "LOT-B");
        m.updateOutcome(sent,   "INVIATA");
        m.updateOutcome(queued, "IN CODA");
        m.clearOutcome();

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("QR-SENT", 1);
        m.applyResult(result(Arrays.<String>asList(), Arrays.<String>asList(),
                             new LinkedHashMap<String, String>(), counts, 1));

        check("the sent pair is verified OK", m.stateAt(sent) == RunTableModel.OK);
        check("the queued pair stays pending", m.stateAt(queued) == RunTableModel.PENDING);
        check("the queued pair is never painted OK",
              !"OK".equals(m.getValueAt(queued, 3)));
    }

    private static void multipleRegistrations_readOkTimesN() {
        RunTableModel m = new RunTableModel();
        m.beginRun("L1");
        m.addRow("LBL001");
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("LBL001", 3);
        m.applyResult(result(Arrays.<String>asList(), Arrays.<String>asList(),
                             new LinkedHashMap<String, String>(), counts, 1));
        check("a double registration reads OK xN",
              String.valueOf(m.getValueAt(0, 3)).endsWith("3"));
    }

    private static void lotFix_editsTheRow_andTheReportSeesIt() {
        RunTableModel m = new RunTableModel();
        m.beginRun("");
        int row = m.addRow("LBL003", "LOTTO-SBAGLIATO");
        m.markSentNow(row);
        m.setLot(row, "LOTTO-GIUSTO");
        check("the row shows the corrected lot", "LOTTO-GIUSTO".equals(m.lotAt(row)));
        app.core.RunReport.Entry e = m.reportEntries().get(row);
        check("the report entry carries the corrected lot",
              "LOTTO-GIUSTO".equals(e.lot));
        check("the send time is stamped", e.sentAt != null && e.sentAt.length() == 8);
        int unsent = m.addRow("QR-X", "L");
        check("an unsent row has no time", m.reportEntries().get(unsent).sentAt == null);
    }

    // the toggle: last run vs everything registered today. history is shown,
    // numbered continuously, and never editable — mutators stay in RUN space
    private static void dayView_archivesRunsAndTranslatesIndexes() {
        RunTableModel m = new RunTableModel();
        m.beginRun("L1");
        m.addRow("A1");
        m.addRow("A2");
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("A1", 1); counts.put("A2", 1);
        m.applyResult(result(Arrays.<String>asList(), Arrays.<String>asList(),
                             new LinkedHashMap<String, String>(), counts, 2));

        m.beginRun("L2");                       // run 1 joins the day archive
        int b1 = m.addRow("B1", "L2");

        check("run view shows only the current run", m.getRowCount() == 1);
        m.setDayView(true);
        check("day view shows the whole day", m.getRowCount() == 3);
        check("numbering runs through the day", m.getValueAt(2, 0).equals(3));
        check("archived verdicts are readable at visual indexes",
              "OK".equals(m.getValueAt(0, 3)) && m.stateAt(0) == RunTableModel.OK);
        check("the archive keeps its own lot", "L1".equals(m.getValueAt(1, 2)));

        m.updateOutcome(b1, "INVIATA");         // run-space mutator, archive above
        check("a run-space update lands on the current run, not on history",
              "INVIATA".equals(m.getValueAt(2, 3)) && "OK".equals(m.getValueAt(0, 3)));

        // an outcome arriving WHILE in day view must not recolor the archive
        Map<String, Integer> live = new LinkedHashMap<>();
        live.put("B1", 1);
        m.applyResult(result(Arrays.<String>asList(), Arrays.<String>asList(),
                             new LinkedHashMap<String, String>(), live, 1));
        check("applyResult in day view colors only the current run",
              "OK".equals(m.getValueAt(2, 3)));
        check("the archive keeps its verdicts through a live outcome",
              "OK".equals(m.getValueAt(0, 3)) && m.stateAt(1) == RunTableModel.OK);

        m.setDayView(false);
        check("back to run view, same single row", m.getRowCount() == 1);

        // review stress: manual verify + clearOutcome + recolor, all while the
        // archive is visible above — the current run must never touch history
        m.setDayView(true);
        m.ensureRows(Arrays.asList("B1", "B2", "B3"), "L2");
        check("manual verify grows the current run under the archive",
              m.getRowCount() == 2 + 3 && "B3".equals(m.getValueAt(4, 1)));
        m.clearOutcome();
        check("clearOutcome resets the current run but spares the archive",
              m.stateAt(4) == RunTableModel.PENDING && m.stateAt(0) == RunTableModel.OK);
        Map<String, Integer> c2 = new LinkedHashMap<>();
        c2.put("B1", 1); c2.put("B2", 1); c2.put("B3", 1);
        m.applyResult(result(Arrays.<String>asList(), Arrays.<String>asList(),
                             new LinkedHashMap<String, String>(), c2, 3));
        check("a re-verify colors the current run, archive untouched",
              m.stateAt(4) == RunTableModel.OK && "OK".equals(m.getValueAt(0, 3)));
    }

    private static void dayView_reportNeverReadsHistory() {
        RunTableModel m = new RunTableModel();
        m.beginRun("L1");
        m.addRow("A1");
        m.beginRun("L2");
        m.addRow("B1");
        m.setDayView(true);
        check("the report files the CURRENT run only, never the archive",
              m.reportEntries().size() == 1
              && "B1".equals(m.reportEntries().get(0).code));
    }

    private static void dayView_rollsOverAtMidnight() {
        RunTableModel m = new RunTableModel();
        m.beginRun("L1");
        m.addRow("A1");
        m.dayStamp = "2000-01-01";              // pretend yesterday
        m.beginRun("L2");                       // archive path hits the rollover
        m.setDayView(true);
        check("yesterday's rows do not survive the rollover",
              m.getRowCount() == 0 && m.dayArchivedCount() == 0);
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
