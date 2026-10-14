package app.core;

// Adapted from ScanLedger src/app/core/Event.java — keep behaviour in sync.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One line of the scan-queue journal: something that happened to a queued
 * pair, already on disk. Immutable. The type words are data (Italian, like the
 * verdicts in the report), the fields plain strings in a fixed order per type.
 */
public final class QueueEvent {

    public enum Type {
        /** id, etichetta, lotto, modalita (continuo / blocco) — a pair entered the queue */
        ACCODA,
        /** id, etichetta, lotto — edited while still queued */
        MODIFICA_CODA,
        /** id — removed by the operator before sending */
        TOGLI_CODA,
        /** id — written right before ENTER: from here on the pair MAY be on the portal */
        IN_INVIO,
        /** id — written right after ENTER: the pair is on the portal */
        INVIATA,
        /** no fields: the queue starts from nothing */
        SESSIONE_NUOVA;

        static Type parse(String s) {
            for (Type t : values()) if (t.name().equals(s)) return t;
            return null;
        }
    }

    public final long seq;
    /** ISO local date-time, millisecond precision. */
    public final String ts;
    public final Type type;
    private final List<String> fields;

    public QueueEvent(long seq, String ts, Type type, List<String> fields) {
        this.seq = seq;
        this.ts = ts;
        this.type = type;
        this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
    }

    public List<String> fields() {
        return fields;
    }

    public String field(int i) {
        return i < fields.size() ? fields.get(i) : "";
    }

    public int fieldCount() {
        return fields.size();
    }

    /** The timestamp cut to the second: what the CSV calls "ora". */
    public String tsSeconds() {
        return ts.length() >= 19 ? ts.substring(0, 19) : ts;
    }

    @Override public String toString() {
        return seq + " " + ts + " " + type + " " + fields;
    }
}
