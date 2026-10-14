package app.core;

import java.io.File;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;

// the scan queue lived in RAM: in "A blocco" a closed app lost every pair
// scanned and not yet released, and nobody knew which they were. now the
// queue is a journal (the one Registro QR uses for its days): every change on
// disk first, the queue rebuilt by reading it back.
//
//   java -cp out app.core.ScanQueueJournalTest
public final class ScanQueueJournalTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        roundTrip_rebuildsTheSameQueue();
        sendingWithoutSent_comesBackToVerify();
        newSession_keepsTheOldJournal();
        truncation_atEveryByte_isAnExactPrefix();
        brokenLineInTheMiddle_keepsTheIntactPairs();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static File dir() throws Exception {
        return Files.createTempDirectory("afs-queue").toFile();
    }

    private static void roundTrip_rebuildsTheSameQueue() throws Exception {
        File d = dir();
        ScanQueueStore s = ScanQueueStore.open(d, Clock.systemDefaultZone());
        String free = "CA HVCO 50 T 048 3-2; M-CA HVCO 50 T 048 3-2  Rev.04; Serial No.: 000066076 ; 2023/12/11;A";
        long a = s.enqueue("QR-1", "LOT-1", "blocco").id;
        long b = s.enqueue("QR-2", "LOT-2", "blocco").id;
        long c = s.enqueue("QR-3", free, "blocco").id;
        long e = s.enqueue("QR-4", "LOT-4", "blocco").id;
        long f = s.enqueue("QR-5", "LOT-5", "blocco").id;
        s.edit(c, "QR-3", free + " x");
        s.remove(e);
        s.sending(a); s.sent(a);
        s.sending(b); s.sent(b);
        s.close();
        ScanQueueStore back = ScanQueueStore.open(d, Clock.systemDefaultZone());
        List<ScanQueueStore.Item> q = back.pending();
        check("five queued, one removed, two sent: two come back", q.size() == 2);
        check("in their order, with their values (the edit included)",
              q.get(0).id == c && "QR-3".equals(q.get(0).q1) && (free + " x").equals(q.get(0).q2)
              && q.get(1).id == f && "QR-5".equals(q.get(1).q1));
        check("the mode they were scanned in comes back", "blocco".equals(q.get(0).mode));
        check("nothing is in doubt", back.unsure().isEmpty());
        long next = back.enqueue("QR-6", "LOT-6", "continuo").id;
        check("new pairs never reuse an id", next > f);
        back.close();
    }

    private static void sendingWithoutSent_comesBackToVerify() throws Exception {
        File d = dir();
        ScanQueueStore s = ScanQueueStore.open(d, Clock.systemDefaultZone());
        long a = s.enqueue("QR-1", "LOT-1", "continuo").id;
        s.enqueue("QR-2", "LOT-2", "continuo");
        s.sending(a);      // ENTER pressed... and the PC went off
        s.close();
        ScanQueueStore back = ScanQueueStore.open(d, Clock.systemDefaultZone());
        check("a pair cut between ENTER and INVIATA comes back to verify, not queued",
              back.unsure().size() == 1 && back.unsure().get(0).id == a && back.pending().size() == 1);
        back.close();
    }

    private static void newSession_keepsTheOldJournal() throws Exception {
        File d = dir();
        ScanQueueStore s = ScanQueueStore.open(d, Clock.systemDefaultZone());
        s.enqueue("QR-1", "LOT-1", "blocco");
        s.newSession();
        check("a new session empties the queue", s.pending().isEmpty());
        String[] names = d.list();
        check("the old journal is renamed with the date, not deleted",
              names != null && Arrays.stream(names).anyMatch(n -> n.startsWith("AutoFillSuite_coda_")));
        s.enqueue("QR-9", "LOT-9", "blocco");
        s.close();
        check("the new journal holds only the new session",
              ScanQueueStore.open(d, Clock.systemDefaultZone()).pending().size() == 1);
    }

    // a line broken in the middle (a bad sector, a stray write) set the whole
    // journal aside and started an empty one: the pairs of the good lines
    // before it were never offered back, they lived only in the renamed file
    private static void brokenLineInTheMiddle_keepsTheIntactPairs() throws Exception {
        File d = dir();
        ScanQueueStore s = ScanQueueStore.open(d, Clock.systemDefaultZone());
        s.enqueue("QR-1", "LOT-1", "blocco");
        long b = s.enqueue("QR-2", "LOT-2", "blocco").id;
        s.sending(b);
        s.enqueue("QR-3", "LOT-3", "blocco");
        s.enqueue("QR-4", "LOT-4", "blocco");
        s.close();
        File f = new File(d, ScanQueueStore.FILE_NAME);
        List<String> lines = new java.util.ArrayList<>(java.nio.file.Files.readAllLines(f.toPath()));
        // the third pair's line is damaged; one more good line follows it
        lines.set(3, lines.get(3).replace("QR-3", "QR-#"));
        java.nio.file.Files.write(f.toPath(), (String.join("\n", lines) + "\n").getBytes("UTF-8"));
        ScanQueueStore back = ScanQueueStore.open(d, Clock.systemDefaultZone());
        check("the pairs of the good lines before the damage come back",
              back.pending().size() == 1 && "QR-1".equals(back.pending().get(0).q1));
        check("a pair cut at ENTER before the damage comes back to verify",
              back.unsure().size() == 1 && back.unsure().get(0).id == b);
        back.close();
        ScanQueueStore third = ScanQueueStore.open(d, Clock.systemDefaultZone());
        check("they are in the fresh journal: a second start still has them",
              third.pending().size() == 1 && third.unsure().size() == 1);
        check("the damaged journal is kept beside it",
              Arrays.stream(d.list()).anyMatch(n -> n.contains("danneggiato")));
        third.close();
    }

    private static void truncation_atEveryByte_isAnExactPrefix() throws Exception {
        File d = dir();
        ScanQueueStore s = ScanQueueStore.open(d, Clock.systemDefaultZone());
        for (int i = 0; i < 12; i++) {
            long id = s.enqueue("QR-" + i, "LOTTO \"è\" " + i, "blocco").id;
            if (i % 3 == 0) s.edit(id, "QR-" + i, "LOT-" + i);
            if (i % 4 == 1) { s.sending(id); s.sent(id); }
            if (i % 5 == 2) s.remove(id);
        }
        s.close();
        File f = new File(d, ScanQueueStore.FILE_NAME);
        byte[] all = Files.readAllBytes(f.toPath());
        List<QueueEvent> full = QueueJournal.read(f).events;
        int bad = 0;
        File copy = new File(d, "cut.giornale");
        for (int len = all.length; len >= 0; len--) {
            Files.write(copy.toPath(), Arrays.copyOf(all, len));
            QueueJournal.Replay r = QueueJournal.recover(copy, Clock.systemDefaultZone());
            boolean prefix = !r.corrupted && r.events.size() <= full.size();
            for (int i = 0; prefix && i < r.events.size(); i++) {
                prefix = r.events.get(i).seq == full.get(i).seq
                      && r.events.get(i).fields().equals(full.get(i).fields());
            }
            if (!prefix) bad++;
        }
        check("every cut of the queue journal (" + all.length + " offsets) reads an exact prefix", bad == 0);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
