package app.ui;

import app.config.SettingsManager;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

// the duplicate guard of the scan looked at the SESSION only. with "the last
// registration wins", registering again a label already done this morning
// with another lot is a silent correction on the portal. before sending, the
// day's archive and the day's report are asked: another lot needs a
// confirmation that says so; the same lot is a re-send, only a warning.
//
//   xvfb-run java -cp out app.ui.RegisteredTodayTest
public final class RegisteredTodayTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        File home = Files.createTempDirectory("afs-today").toFile();
        System.setProperty("user.home", home.getPath());
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.SCAN_BATCH, true);   // collect: no robot in this test
        cfg.save();
        // this morning's report: LBL001 sent with LOT-A, LBL009 only queued
        String report = "RUN;" + java.time.LocalDate.now() + ";08:00:00;lotto=-;modalita=scansione\n"
            + "Etichetta;Lotto;OraInvio;Esito;Registrazioni;Dettaglio\n"
            + "LBL001;LOT-A;08:00:05;OK;1;\n"
            + "LBL009;LOT-Z;-;IN CODA;0;\n";
        Files.write(new File(home, "AutoFillSuite_report_" + java.time.LocalDate.now() + ".csv").toPath(),
                    report.getBytes(StandardCharsets.UTF_8));

        final List<String> asked = new ArrayList<>();
        final boolean[] answer = { false };
        final ScanModePanel[] sp = new ScanModePanel[1];
        final ResultsPanel[] rp = new ResultsPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            rp[0] = new ResultsPanel(() -> { });
            sp[0] = new ScanModePanel(rp[0], new RangeModeTest.NoopRunContext());
            sp[0].confirmer = m -> { asked.add(m); return answer[0]; };
        });

        scan(sp[0], "LBL001", "LOT-B");
        check("another lot on a label sent this morning asks first", asked.size() == 1);
        check("the question says when, which lot, and that it is a correction",
              asked.get(0).contains("08:00") && asked.get(0).contains("LOT-A")
              && asked.get(0).contains("correzione"));
        check("refused: nothing is queued", rp[0].tableModel().getRowCount() == 0);

        answer[0] = true;
        scan(sp[0], "LBL001", "LOT-B");
        check("confirmed: the correction is queued", rp[0].tableModel().getRowCount() == 1);

        asked.clear();
        scan(sp[0], "LBL002", "LOT-C");
        check("a label not seen today asks nothing", asked.isEmpty() && rp[0].tableModel().getRowCount() == 2);

        SwingUtilities.invokeAndWait(() -> rp[0].beginRun(1, "X"));
        scan2(sp[0], rp[0]);
        check("the same lot again is a re-send: no question, a warning, queued",
              asked.isEmpty() && sp[0].bannerText().contains("reinvio"));
        check("a row only queued in the report is not 'registered today'",
              rp[0].registeredToday().get("LBL009") == null);

        // the report used to be read again at every scan, on the interface
        // thread. now it is read once: two sessions of the same day, the
        // second one sees what the first sent, and this morning's report is
        // known without going back to the disk (the file is gone meanwhile)
        File reportFile = new File(home, "AutoFillSuite_report_" + java.time.LocalDate.now() + ".csv");
        SwingUtilities.invokeAndWait(() -> {
            rp[0].beginRun(1, "LOT-R");
            rp[0].addLiveRow("LBL010", 1);
            rp[0].resetIdle();
        });
        Thread.sleep(500);   // the report of the first session is written by a worker
        Files.deleteIfExists(reportFile.toPath());
        SwingUtilities.invokeAndWait(() -> rp[0].beginRun(1, "LOT-S"));
        java.util.Map<String, String[]> seen = rp[0].registeredToday();
        check("a second session sees what the first one sent", seen.get("LBL010") != null);
        check("this morning's report is known without reading it again",
              seen.get("LBL001") != null && "LOT-A".equals(seen.get("LBL001")[0]));

        // the day changes: the new day's report is read, yesterday's is not carried over
        java.time.LocalDate tomorrow = java.time.LocalDate.now().plusDays(1);
        Files.write(new File(home, "AutoFillSuite_report_" + tomorrow + ".csv").toPath(),
            ("RUN;" + tomorrow + ";08:00:00;lotto=-;modalita=scansione\n"
             + "Etichetta;Lotto;OraInvio;Esito;Registrazioni;Dettaglio\n"
             + "LBL050;LOT-T;08:00:05;OK;1;\n").getBytes(StandardCharsets.UTF_8));
        ResultsPanel.clock = java.time.Clock.offset(java.time.Clock.systemDefaultZone(),
                                                    java.time.Duration.ofDays(1));
        seen = rp[0].registeredToday();
        check("a new day reads the new day's report",
              seen.get("LBL050") != null && seen.get("LBL001") == null);
        ResultsPanel.clock = java.time.Clock.systemDefaultZone();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    static void scan(ScanModePanel p, String q1, String q2) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<JTextField> f = RangeModeTest.fields(p);
            f.get(0).setText(q1);
            f.get(1).setText(q2);
            f.get(1).postActionEvent();
        });
        Thread.sleep(150);
    }

    /** A new session where LBL003 was registered by hand earlier, then the same lot again. */
    static void scan2(ScanModePanel p, ResultsPanel rp) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            rp.addLiveRow("LBL003", 1);
            rp.tableModel().setLot(rp.tableModel().getRowCount() - 1, "LOT-S");
        });
        scan(p, "LBL003", "LOT-S");
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
