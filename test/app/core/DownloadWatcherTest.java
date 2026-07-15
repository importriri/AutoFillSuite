package app.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

// self-contained runner for DownloadWatcher — no junit, plain jdk, same idea as
// RegistrationVerifierTest. every case gets a throwaway folder and this file plays
// the browser: dropping exports, growing them mid-write, renaming .crdownload on
// completion. names mimic the real ones — the site serves "demo-export.csv" and chrome
// dedupes to "demo-export (N).csv".
//
//   javac -d build src/app/core/*.java test/app/core/*.java
//   java  -cp build app.core.DownloadWatcherTest
public final class DownloadWatcherTest {

    private static int passed = 0;
    private static int failed = 0;
    private static Path root;

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("dw-test");
        try {
            freshExport_isFound();
            staleExports_fromPastRuns_areIgnored();
            crdownload_isIgnoredUntilRenamed();
            growingFile_isPickedOnlyComplete();
            foreignName_isIgnored();
            newerOfTwo_wins();
            missingFolder_isALoudConfigError();
            newestExisting_picksTheLatestCompletedExport();
            newestExisting_emptyFolder_isNull();
            readLines_survivesAnsiAndUtf8();
        } finally {
            deleteTree(root);
        }
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void freshExport_isFound() throws Exception {
        Path dir = caseDir();
        long t0 = System.currentTimeMillis();
        Thread.sleep(30);
        drop(dir, "demo-export.csv", "LBL001|LOT\n");
        Path got = quick(dir, 1500).awaitNewExport(t0);
        check("a fresh export is found", got != null && name(got).equals("demo-export.csv"));
    }

    // the real downloads folder holds dozens of old "demo-export (N).csv" — none of them
    // may ever pass for the export we just asked for.
    private static void staleExports_fromPastRuns_areIgnored() throws Exception {
        Path dir = caseDir();
        drop(dir, "demo-export.csv", "OLD|X\n");
        drop(dir, "demo-export (36).csv", "OLD|X\n");
        drop(dir, "demo-export (37).csv", "OLD|X\n");
        Thread.sleep(40);
        long t0 = System.currentTimeMillis();
        check("a folder full of past exports yields nothing", quick(dir, 300).awaitNewExport(t0) == null);
        Thread.sleep(20);
        drop(dir, "demo-export (38).csv", "LBL001|LOT\n");
        Path got = quick(dir, 1500).awaitNewExport(t0);
        check("the fresh export wins over the stale pile", got != null && name(got).equals("demo-export (38).csv"));
    }

    private static void crdownload_isIgnoredUntilRenamed() throws Exception {
        Path dir = caseDir();
        long t0 = System.currentTimeMillis();
        Thread.sleep(30);
        Path partial = dir.resolve("demo-export (5).csv.crdownload");
        Files.write(partial, "LBL001|LOT\n".getBytes(StandardCharsets.UTF_8));
        Thread browser = new Thread(() -> {
            try {
                Thread.sleep(250);
                Files.move(partial, dir.resolve("demo-export (5).csv"));
            } catch (Exception ignored) { }
        });
        browser.start();
        Path got = new DownloadWatcher(dir, "demo-export", 3000, 25, 60).awaitNewExport(t0);
        browser.join();
        check("crdownload is skipped until chrome renames it", got != null && name(got).equals("demo-export (5).csv"));
    }

    private static void growingFile_isPickedOnlyComplete() throws Exception {
        Path dir = caseDir();
        Path file = dir.resolve("demo-export (9).csv");
        byte[] chunk = "LBL001|LOT\n".getBytes(StandardCharsets.UTF_8);
        long t0 = System.currentTimeMillis();
        Thread.sleep(30);
        Thread server = new Thread(() -> {
            try {
                for (int i = 0; i < 3; i++) {
                    Files.write(file, chunk, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    Thread.sleep(100);
                }
            } catch (Exception ignored) { }
        });
        server.start();
        // stability window (300) wider than the write gap (100): growth cannot hide in it
        Path got = new DownloadWatcher(dir, "demo-export", 4000, 25, 300).awaitNewExport(t0);
        server.join();
        long size = (got == null) ? -1 : Files.size(got);
        check("a still-growing file is returned only once complete", size == chunk.length * 3L);
    }

    private static void foreignName_isIgnored() throws Exception {
        Path dir = caseDir();
        long t0 = System.currentTimeMillis();
        Thread.sleep(30);
        drop(dir, "report.csv", "NOT|OURS\n");
        check("a fresh file without the prefix is ignored", quick(dir, 300).awaitNewExport(t0) == null);
    }

    private static void newerOfTwo_wins() throws Exception {
        Path dir = caseDir();
        long t0 = System.currentTimeMillis();
        Thread.sleep(30);
        drop(dir, "demo-export (1).csv", "FIRST|X\n");
        Thread.sleep(80);
        drop(dir, "demo-export (2).csv", "SECOND|X\n");
        Path got = quick(dir, 1500).awaitNewExport(t0);
        check("with two fresh exports the newer one wins", got != null && name(got).equals("demo-export (2).csv"));
    }

    private static void missingFolder_isALoudConfigError() throws Exception {
        Path dir = caseDir();
        boolean threw = false;
        try {
            quick(dir.resolve("nope"), 200).awaitNewExport(0);
        } catch (IOException expected) {
            threw = true;
        }
        check("a missing download folder throws instead of faking a timeout", threw);
    }

    // the manual-verify fallback: no fresh download, use the last completed export
    private static void newestExisting_picksTheLatestCompletedExport() throws Exception {
        Path dir = caseDir();
        drop(dir, "demo-export.csv", "OLD|X\n");
        Thread.sleep(60);
        drop(dir, "demo-export (37).csv", "NEWER|X\n");
        Files.write(dir.resolve("demo-export (38).csv.crdownload"),
                    "HALF|X\n".getBytes(StandardCharsets.UTF_8));
        Path got = quick(dir, 300).newestExisting();
        check("newestExisting picks the latest completed export, skipping partials",
              got != null && name(got).equals("demo-export (37).csv"));
    }

    private static void newestExisting_emptyFolder_isNull() throws Exception {
        check("newestExisting on an empty folder is null",
              quick(caseDir(), 300).newestExisting() == null);
    }

    private static void readLines_survivesAnsiAndUtf8() throws Exception {
        Path dir = caseDir();
        // 0xE8 is "è" in latin-1 and an invalid lone byte in utf-8
        Path ansi = dir.resolve("ansi.csv");
        Files.write(ansi, new byte[] {
            'L', 'B', 'L', '0', '0', '1', '|', 'L', (byte) 0xE8, '\n', 'X', '|', 'Y', '\n' });
        List<String> lines = DownloadWatcher.readLines(ansi);
        check("an ansi export falls back to latin-1 and survives",
              lines.size() == 2 && lines.get(0).startsWith("LBL001|L"));

        Path utf8 = dir.resolve("utf8.csv");
        Files.write(utf8, "LBL001|L\u00d2T\n".getBytes(StandardCharsets.UTF_8));
        check("a plain utf-8 export reads straight through",
              DownloadWatcher.readLines(utf8).get(0).endsWith("L\u00d2T"));
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private static Path caseDir() throws IOException {
        return Files.createTempDirectory(root, "case");
    }

    private static DownloadWatcher quick(Path dir, int timeoutMs) {
        return new DownloadWatcher(dir, "demo-export", timeoutMs, 25, 60);
    }

    private static void drop(Path dir, String fileName, String content) throws IOException {
        Files.write(dir.resolve(fileName), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String name(Path p) {
        return p.getFileName().toString();
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

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        }
    }
}
