package app.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

// self-contained runner for VerificationTask — no junit, plain jdk, headless.
// the injected "export click" plays the browser by dropping a real file into a
// temp folder; the real DownloadWatcher and RegistrationVerifier do the rest,
// so retry, manual fallback and cancellation are exercised end to end.
//
//   javac -d build src/app/core/*.java test/app/core/*.java
//   java  -cp build app.core.VerificationTaskTest
public final class VerificationTaskTest {

    private static final String LOT = "LOT-1";
    private static int passed = 0;
    private static int failed = 0;
    private static Path root;

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("vt-test");
        try {
            cleanFirstTry();
            serverLag_secondExportGoesClean();
            retriesExhausted_staysRed();
            exportNeverArrives_isAFailure();
            manualFallback_usesTheLastExport();
            manualFallback_emptyFolder_fails();
            cancel_stopsTheWaitCleanly();
        } finally {
            deleteTree(root);
        }
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void cleanFirstTry() throws Exception {
        Path dir = caseDir();
        AtomicInteger clicks = new AtomicInteger();
        Capture cap = run(dir, clicker(dir, clicks, i -> csv(
            "LBL001|" + LOT, "LBL002|" + LOT, "LBL003|" + LOT)), 2, false, 900);
        check("a clean export finishes on the first attempt",
              cap.result != null && cap.result.isClean() && cap.attempts == 1);
        check("a fresh download is flagged as fresh", cap.fresh);
        check("a clean run never re-clicks the export", clicks.get() == 1);
    }

    // the server has not flushed the last row yet: first export misses LBL003,
    // the retry re-clicks and the second export is complete.
    private static void serverLag_secondExportGoesClean() throws Exception {
        Path dir = caseDir();
        AtomicInteger clicks = new AtomicInteger();
        Capture cap = run(dir, clicker(dir, clicks, i -> i == 1
            ? csv("LBL001|" + LOT, "LBL002|" + LOT)
            : csv("LBL001|" + LOT, "LBL002|" + LOT, "LBL003|" + LOT)), 2, false, 900);
        check("server lag is absorbed by the retry", cap.result != null && cap.result.isClean());
        check("the clean outcome lands on attempt two", cap.attempts == 2);
        check("the retry really re-clicked the export", clicks.get() == 2);
    }

    private static void retriesExhausted_staysRed() throws Exception {
        Path dir = caseDir();
        AtomicInteger clicks = new AtomicInteger();
        Capture cap = run(dir, clicker(dir, clicks, i -> csv(
            "LBL001|" + LOT, "LBL002|" + LOT)), 1, false, 900);
        check("a real problem survives the retries", cap.result != null && !cap.result.isClean());
        check("the retry budget is honored (1 extra = 2 attempts)", cap.attempts == 2);
        check("the missing label is named",
              cap.result != null && cap.result.getMissing().contains("LBL003"));
    }

    private static void exportNeverArrives_isAFailure() throws Exception {
        Path dir = caseDir();
        Capture cap = run(dir, () -> { }, 0, false, 400);
        check("a missing export ends in onFailure, not a fake outcome",
              cap.failure != null && cap.result == null);
    }

    // manual mode: the click produces nothing new, but yesterday's export is in
    // the folder — verify against it and say loudly that it is not fresh.
    private static void manualFallback_usesTheLastExport() throws Exception {
        Path dir = caseDir();
        Files.write(dir.resolve("demo-export (37).csv"),
            csv("LBL001|" + LOT, "LBL002|" + LOT, "LBL003|" + LOT)
                .getBytes(StandardCharsets.UTF_8));
        Thread.sleep(40);
        Capture cap = run(dir, () -> { }, 0, true, 300);
        check("fallback verifies against the newest existing export",
              cap.result != null && cap.result.isClean());
        check("a fallback outcome is flagged as NOT fresh", cap.result != null && !cap.fresh);
    }

    private static void manualFallback_emptyFolder_fails() throws Exception {
        Capture cap = run(caseDir(), () -> { }, 0, true, 300);
        check("fallback with an empty folder is a failure, not a fake green",
              cap.failure != null && cap.result == null);
    }

    private static void cancel_stopsTheWaitCleanly() throws Exception {
        Path dir = caseDir();
        Capture cap = new Capture();
        DownloadWatcher watcher = new DownloadWatcher(dir, "demo-export", 8000, 25, 60);
        VerificationTask task = new VerificationTask(() -> { }, watcher,
            lines -> new RegistrationVerifier().verify(
                lines, RegistrationVerifier.expectedCodes("LBL", 1, 3), LOT),
            0, 100, false, cap);
        new Thread(task, "vt-cancel").start();
        Thread.sleep(150);
        task.cancel();
        check("cancel ends in onCancelled within moments",
              cap.done.await(5, TimeUnit.SECONDS) && cap.cancelled
              && cap.result == null && cap.failure == null);
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private static final class Capture implements VerificationTask.Listener {
        final CountDownLatch done = new CountDownLatch(1);
        volatile VerificationResult result;
        volatile Path file;
        volatile int attempts = -1;
        volatile boolean fresh;
        volatile boolean cancelled;
        volatile String failure;

        @Override public void onStatus(String message) { }
        @Override public void onOutcome(VerificationResult r, Path f, int a, boolean fr) {
            result = r; file = f; attempts = a; fresh = fr;
            done.countDown();
        }
        @Override public void onFailure(String reason) {
            failure = reason;
            done.countDown();
        }
        @Override public void onCancelled() {
            cancelled = true;
            done.countDown();
        }
    }

    private static Capture run(Path dir, Runnable click, int extraRetries,
                               boolean fallback, int timeoutMs) throws Exception {
        Capture cap = new Capture();
        DownloadWatcher watcher = new DownloadWatcher(dir, "demo-export", timeoutMs, 25, 60);
        VerificationTask task = new VerificationTask(click, watcher,
            lines -> new RegistrationVerifier().verify(
                lines, RegistrationVerifier.expectedCodes("LBL", 1, 3), LOT),
            extraRetries, 120, fallback, cap);
        new Thread(task, "vt-test").start();
        if (!cap.done.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("task never finished");
        }
        return cap;
    }

    // each click drops "demo-export (N).csv" after a short beat — a download is never instant
    private static Runnable clicker(Path dir, AtomicInteger clicks,
                                    Function<Integer, String> content) {
        return () -> {
            try {
                Thread.sleep(20);
                int i = clicks.incrementAndGet();
                Files.write(dir.resolve("demo-export (" + i + ").csv"),
                            content.apply(i).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static String csv(String... rows) {
        StringBuilder sb = new StringBuilder("SerialNumber|ProductionLot\n");
        for (String r : rows) sb.append(r).append('\n');
        return sb.toString();
    }

    private static Path caseDir() throws Exception {
        return Files.createTempDirectory(root, "case");
    }

    private static void check(String testName, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  ok   " + testName);
        } else {
            failed++;
            System.out.println("  FAIL " + testName);
        }
    }

    private static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        }
    }
}
