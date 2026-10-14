package app.core;

// Adapted from ScanLedger test/app/core/CrashTest.java — keep behaviour in sync.

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

// a second JVM queues pairs in a loop and announces every id it has seen
// forced to disk; this JVM kills it with destroyForcibly() — the Task
// Manager's "End task" — and reopens the queue. every announced pair must be
// there. 10 rounds on the same journal, so each round also reopens a killed one.
//
//   java -cp out app.core.CrashTest
public final class CrashTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--child".equals(args[0])) {
            child(new File(args[1]));
            return;
        }
        long base = args.length > 0 ? Long.parseLong(args[0]) : System.nanoTime();
        File dir = Files.createTempDirectory("afs-crash").toFile();
        int rounds = 10, good = 0;
        for (int i = 0; i < rounds; i++) {
            long seed = base + i;
            String why = round(dir, seed);
            if (why == null) good++;
            else System.out.println("  FAIL round " + (i + 1) + " seed " + seed + ": " + why);
        }
        check("every pair announced as queued survives " + rounds + " forced kills", good == rounds);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static String round(File dir, long seed) throws Exception {
        int delay = 200 + new Random(seed).nextInt(1101);
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                                       "app.core.CrashTest", "--child", dir.getPath())
            .redirectErrorStream(true).start();
        AtomicLong announced = new AtomicLong(0);
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("ok ")) announced.accumulateAndGet(Long.parseLong(line.substring(3).trim()), Math::max);
                }
            } catch (IOException ignored) {
                // the pipe dies with the process
            }
        });
        reader.start();
        Thread.sleep(delay);
        p.destroyForcibly();
        p.waitFor();
        reader.join(3000);
        ScanQueueStore s = ScanQueueStore.open(dir, Clock.systemDefaultZone());
        long last = 0;
        for (ScanQueueStore.Item it : s.pending()) last = Math.max(last, it.id);
        s.close();
        if (last < announced.get()) return "announced " + announced.get() + ", on disk " + last;
        System.out.println("  ..   round seed " + seed + ": killed at " + delay + " ms, "
                           + announced.get() + " announced, " + last + " on disk");
        return null;
    }

    private static void child(File dir) throws IOException {
        ScanQueueStore s = ScanQueueStore.open(dir, Clock.systemDefaultZone());
        for (int i = 0; ; i++) {
            ScanQueueStore.Item it = s.enqueue("QR-" + i, "LOT-" + i, "blocco");
            System.out.println("ok " + it.id);
            System.out.flush();
        }
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
