package app.ui;

import app.config.SettingsManager;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

// a closed app (by mistake, or a crash) reopened on an empty table and a count
// back at zero, while the day's report held everything. the day now reopens as
// it was left: the day view, the count of every label once, and the holes of
// the numbering in red where the operator looks.
//
//   xvfb-run java -cp out app.ui.DayRestoreTest
public final class DayRestoreTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        File home = Files.createTempDirectory("afs-day").toFile();
        System.setProperty("user.home", home.getPath());
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.REPORT_DIR, home.getPath());
        cfg.set(SettingsManager.UI_RESULTS_DAY, false);
        cfg.save();
        String today = java.time.LocalDate.now().toString();
        String head = "Etichetta;Lotto;OraInvio;Esito;Registrazioni;Dettaglio";
        String report = "RUN;" + today + ";08:00:00;lotto=L1;modalita=intervallo\n" + head + "\n"
            + "T_001;L1;08:00:05;INVIATA;0;\n"
            + "T_002;L1;08:00:07;INVIATA;0;\n"
            + "\n"
            + "RUN;" + today + ";08:10:00;lotto=L1;modalita=intervallo\n" + head + "\n"
            + "T_001;L1;08:10:05;OK;1;\n"
            + "T_002;L1;08:10:07;OK;1;\n"
            + "T_003;L1;08:10:09;OK;1;\n"
            + "T_007;L1;08:10:20;OK;1;\n"
            + "TUTTO OK;4/4\n"
            + "\n"
            + "RUN;" + today + ";09:00:00;lotto=-;modalita=cancella\n" + head + "\n"
            + "T_005;;09:00:02;CANCELLATA;0;\n";
        Files.write(new File(home, "AutoFillSuite_report_" + today + ".csv").toPath(),
                    report.getBytes(StandardCharsets.UTF_8));

        final ResultsPanel[] rp = new ResultsPanel[1];
        SwingUtilities.invokeAndWait(() -> rp[0] = new ResultsPanel(() -> { }));
        final String[] day = { "" };
        final int[] rows = { 0 };
        final String[] alert = { null };
        SwingUtilities.invokeAndWait(() -> {
            day[0] = rp[0].dayText();
            rp[0].tableModel().setDayView(true);
            rows[0] = rp[0].tableModel().getRowCount();
            alert[0] = rp[0].gapAlertText();
        });
        check("the day line counts every label once: 4, not 6", day[0].contains("OGGI: 4 registrate"));
        check("the day line counts the deletion", day[0].contains("1 cancellata"));
        check("the day view lists the reopened day: 5 labels", rows[0] == 5);
        check("the two holes are in red", alert[0] != null && alert[0].startsWith("2 BUCHI")
              && alert[0].contains("2 mancanti") && day[0].contains("2 BUCHI"));

        // a deleted label fills its place: 005 is not a hole, 004 and 006 are
        final List<String> holes = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (app.core.GapFinder.Gap g : rp[0].gaps()) holes.add(g.fromCode);
        });
        check("a deleted label is not a hole", holes.size() == 2
              && holes.contains("T_004") && holes.contains("T_006"));

        final List<String> sent = new ArrayList<>();
        final List<String> asked = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            rp[0].setGapDeleter(sent::addAll);
            rp[0].gapConfirmer = m -> { asked.add(m); return true; };
            rp[0].deleteGap(0);
        });
        check("the hole's button asks first and says it deletes from the portal",
              asked.size() == 1 && asked.get(0).contains("CANCELLATE dal portale"));
        check("the hole's labels go to the Cancella tab", sent.size() == 1 && sent.get(0).equals("T_004"));

        SwingUtilities.invokeAndWait(() -> {
            rp[0].beginSession();
            rp[0].addQueuedPair("T_004", "L1");
        });
        Thread.sleep(500);
        final String[] alert2 = { null };
        SwingUtilities.invokeAndWait(() -> alert2[0] = rp[0].gapAlertText());
        check("a label waiting in the queue already fills its hole",
              alert2[0] != null && alert2[0].startsWith("1 BUCO"));

        File gaps = new File(home, "AutoFillSuite_buchi_" + today + ".csv");
        long until = System.currentTimeMillis() + 3000;
        while (!gaps.exists() && System.currentTimeMillis() < until) Thread.sleep(50);
        Thread.sleep(200);
        String csv = gaps.exists() ? new String(Files.readAllBytes(gaps.toPath()), StandardCharsets.UTF_8) : "";
        check("the day's holes are on disk next to the report", csv.contains("T_006;T_006;1;MANCANTI"));

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
