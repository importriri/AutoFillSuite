package app.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The scan queue, on disk. In "A blocco" the operator may scan dozens of pairs
 * before releasing them: the queue used to live in RAM only, and a closed app
 * or a power cut lost them with nobody knowing which they were.
 *
 * Write-ahead, like Registro QR's day journal: every change is forced to
 * {@code AutoFillSuite_coda.giornale} BEFORE the queue changes. At start the
 * pairs queued and not sent come back in their order; a pair whose ENTER may
 * have gone (IN_INVIO without INVIATA: the PC went off in between) comes back
 * as "to verify", never silently re-queued.
 */
public final class ScanQueueStore {

    public static final String FILE_NAME = app.config.AppFiles.QUEUE_JOURNAL;

    public enum State { QUEUED, REMOVED, SENDING, SENT }

    /** One pair as the journal knows it. */
    public static final class Item {
        public final long id;
        public String q1;
        public String q2;
        public final String mode;
        public State state = State.QUEUED;

        Item(long id, String q1, String q2, String mode) {
            this.id = id;
            this.q1 = q1;
            this.q2 = q2;
            this.mode = mode;
        }
    }

    private final File file;
    private final Clock clock;
    private QueueJournal journal;
    private QueueJournal.Replay replay;
    private final Map<Long, Item> items = new LinkedHashMap<>();
    private long nextId = 1;
    private boolean salvaged;

    private ScanQueueStore(File file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** Opens (and repairs a torn last line of) the queue journal in the report folder. */
    public static ScanQueueStore open(File reportDir, Clock clock) throws IOException {
        ScanQueueStore s = new ScanQueueStore(new File(reportDir, FILE_NAME), clock);
        s.load();
        return s;
    }

    private void load() throws IOException {
        items.clear();
        nextId = 1;
        replay = QueueJournal.recover(file, clock);
        if (replay.corrupted) {
            // never append after a broken line: the damaged file is kept (and
            // its .corrotto- copy), a fresh journal starts beside it. What the
            // good lines before the damage say is carried over: those pairs
            // were queued, and must be offered back like any other
            QueueJournal.Replay damaged = replay;
            rotate("danneggiato");
            replay = QueueJournal.recover(file, clock);
            journal = QueueJournal.openForAppend(file, replay, clock);
            for (QueueEvent e : damaged.events) apply(e);
            for (Item it : new java.util.ArrayList<>(items.values())) {
                if (it.state != State.QUEUED && it.state != State.SENDING) {
                    items.remove(it.id);
                    continue;
                }
                journal.append(QueueEvent.Type.ACCODA,
                    QueueJournal.fields(String.valueOf(it.id), it.q1, it.q2, it.mode));
                if (it.state == State.SENDING) {
                    journal.append(QueueEvent.Type.IN_INVIO, QueueJournal.fields(String.valueOf(it.id)));
                }
            }
            salvaged = true;
            return;
        }
        for (QueueEvent e : replay.events) apply(e);
        journal = QueueJournal.openForAppend(file, replay, clock);
    }

    /** The journal was damaged at this start; its intact pairs were carried over. */
    public boolean salvaged() {
        return salvaged;
    }

    public File file() {
        return file;
    }

    public QueueJournal.Replay replay() {
        return replay;
    }

    // ── reading ──────────────────────────────────────────────────────────

    /** Pairs queued and not sent, in queue order. */
    public List<Item> pending() {
        List<Item> out = new ArrayList<>();
        for (Item i : items.values()) if (i.state == State.QUEUED) out.add(i);
        return Collections.unmodifiableList(out);
    }

    /** Pairs whose ENTER may have gone: verify before anything else. */
    public List<Item> unsure() {
        List<Item> out = new ArrayList<>();
        for (Item i : items.values()) if (i.state == State.SENDING) out.add(i);
        return Collections.unmodifiableList(out);
    }

    public Item item(long id) {
        return items.get(id);
    }

    // ── writing: disk first, queue second ────────────────────────────────

    public Item enqueue(String q1, String q2, String mode) throws IOException {
        long id = nextId;
        write(QueueEvent.Type.ACCODA, String.valueOf(id), q1, q2, mode);
        return items.get(id);
    }

    public void edit(long id, String q1, String q2) throws IOException {
        write(QueueEvent.Type.MODIFICA_CODA, String.valueOf(id), q1, q2);
    }

    public void remove(long id) throws IOException {
        write(QueueEvent.Type.TOGLI_CODA, String.valueOf(id));
    }

    /** Right before ENTER. */
    public void sending(long id) throws IOException {
        write(QueueEvent.Type.IN_INVIO, String.valueOf(id));
    }

    /** Right after ENTER. */
    public void sent(long id) throws IOException {
        write(QueueEvent.Type.INVIATA, String.valueOf(id));
    }

    /**
     * A new session: SESSIONE_NUOVA closes the old journal, which is renamed
     * with the date and kept — never deleted — and the queue restarts empty.
     */
    public void newSession() throws IOException {
        write(QueueEvent.Type.SESSIONE_NUOVA);
        journal.close();
        rotate("sessione");
        load();
    }

    private void rotate(String why) throws IOException {
        if (journal != null) journal.close();
        if (!file.exists()) return;
        String stamp = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File old = new File(file.getParentFile(), "AutoFillSuite_coda_" + stamp + "_" + why + ".giornale");
        int k = 2;
        while (old.exists()) {
            old = new File(file.getParentFile(), "AutoFillSuite_coda_" + stamp + "_" + why + "_" + (k++) + ".giornale");
        }
        Files.move(file.toPath(), old.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    public void close() {
        if (journal != null) journal.close();
    }

    private void write(QueueEvent.Type type, String... fields) throws IOException {
        QueueEvent e = journal.append(type, QueueJournal.fields(fields));
        apply(e);
    }

    private void apply(QueueEvent e) {
        try {
            switch (e.type) {
                case ACCODA: {
                    long id = Long.parseLong(e.field(0));
                    items.put(id, new Item(id, e.field(1), e.field(2), e.field(3)));
                    nextId = Math.max(nextId, id + 1);
                    break;
                }
                case MODIFICA_CODA: {
                    Item i = items.get(Long.parseLong(e.field(0)));
                    if (i != null && i.state == State.QUEUED) { i.q1 = e.field(1); i.q2 = e.field(2); }
                    break;
                }
                case TOGLI_CODA: {
                    Item i = items.get(Long.parseLong(e.field(0)));
                    if (i != null && i.state == State.QUEUED) i.state = State.REMOVED;
                    break;
                }
                case IN_INVIO: {
                    Item i = items.get(Long.parseLong(e.field(0)));
                    if (i != null) i.state = State.SENDING;
                    break;
                }
                case INVIATA: {
                    Item i = items.get(Long.parseLong(e.field(0)));
                    if (i != null) i.state = State.SENT;
                    break;
                }
                case SESSIONE_NUOVA:
                    items.clear();
                    break;
                default:
                    break;
            }
        } catch (RuntimeException ignored) {
            // a malformed event changes nothing
        }
    }
}
