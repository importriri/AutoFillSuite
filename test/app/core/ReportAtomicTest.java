package app.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

// the day's report is rewritten whole at every send. Files.write truncated
// first and wrote after: an exit or a power cut in between left the report of
// the whole day half empty. AtomicFile writes a forced sibling and renames it:
// whatever moment the crash picks, the file is the old one whole or the new
// one whole.
//
//   java -cp out app.core.ReportAtomicTest
public final class ReportAtomicTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("afs-atomic");
        Path report = dir.resolve("AutoFillSuite_report_2026-10-14.csv");
        byte[] old = section("RUN;2026-10-14;08:00:00;lotto=A", 200);
        byte[] fresh = section("RUN;2026-10-14;09:00:00;lotto=B", 400);
        Files.write(report, old);

        // a crash at every byte of the temp file: the report never changes
        boolean intact = true;
        for (int cut = 0; cut < fresh.length; cut += 97) {
            AtomicFile.crashAfterBytes = cut;
            try {
                AtomicFile.write(report, fresh);
                intact = false;
            } catch (IOException expected) {
                // the program died here
            }
            if (!Arrays.equals(old, Files.readAllBytes(report))) intact = false;
        }
        AtomicFile.crashAfterBytes = -1;
        check("a crash while writing never touches the report", intact);

        // a crash after the temp file is complete, before the rename
        AtomicFile.beforeMove = () -> { throw new IllegalStateException("power cut"); };
        try {
            AtomicFile.write(report, fresh);
        } catch (IllegalStateException expected) {
            // died before the rename
        }
        AtomicFile.beforeMove = null;
        check("a crash before the rename leaves the old report whole",
              Arrays.equals(old, Files.readAllBytes(report)));

        AtomicFile.write(report, fresh);
        check("a write that completes leaves the new report whole",
              Arrays.equals(fresh, Files.readAllBytes(report)));
        check("no temp file is left after a complete write",
              !Files.exists(dir.resolve(report.getFileName() + ".tmp")));

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static byte[] section(String head, int rows) {
        StringBuilder sb = new StringBuilder(head).append('\n');
        for (int i = 0; i < rows; i++) sb.append("LBL").append(i).append(";LOT;08:00:00;INVIATA;0;\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
