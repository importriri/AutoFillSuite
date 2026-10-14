package app.ui;

import app.core.VerificationResult;

import javax.swing.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

// a run started at 23:58 and verified at 00:03: the section carries the date
// of its start, but reportFile() used today's date — the journal landed in
// the 13th's file and the verification in the 14th's, one run in two files.
// the file is chosen by the run's own date now.
//
//   xvfb-run java -cp out app.ui.MidnightReportTest
public final class MidnightReportTest {

    private static int passed = 0;
    private static int failed = 0;

    /** A clock moved by hand. */
    static final class HandClock extends Clock {
        Instant now;
        HandClock(String iso) { set(iso); }
        void set(String iso) { now = LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    public static void main(String[] args) throws Exception {
        File home = Files.createTempDirectory("afs-midnight").toFile();
        System.setProperty("user.home", home.getPath());
        HandClock clock = new HandClock("2026-10-13T23:58:00");
        ResultsPanel.clock = clock;

        final ResultsPanel[] rp = new ResultsPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            rp[0] = new ResultsPanel(() -> { });
            rp[0].beginRun(1, "LOT-NIGHT");
            rp[0].addLiveRow("MID001", 1);
        });
        Thread.sleep(900);   // the coalescing timer and the journal writer

        clock.set("2026-10-14T00:03:00");
        Map<String, Integer> counts = new HashMap<>();
        counts.put("MID001", 1);
        VerificationResult ok = new VerificationResult(Arrays.<String>asList(), Arrays.<String>asList(),
            new HashMap<String, String>(), Arrays.<String>asList(), counts, 1);
        SwingUtilities.invokeAndWait(() -> {
            rp[0].showOutcome(ok, "demo-export.csv", 1, true);
            rp[0].offerReport(ok);
        });
        Thread.sleep(300);

        File day13 = new File(home, "AutoFillSuite_report_2026-10-13.csv");
        File day14 = new File(home, "AutoFillSuite_report_2026-10-14.csv");
        String body = day13.exists() ? new String(Files.readAllBytes(day13.toPath()), StandardCharsets.UTF_8) : "";
        check("the run started on the 13th is filed in the 13th's report", body.contains("MID001"));
        check("its verification lands in the same file, the same section",
              body.contains("Verifica;00:03:00") && body.split("RUN;", -1).length - 1 == 1);
        check("nothing of that run goes to the 14th's file", !day14.exists());

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
