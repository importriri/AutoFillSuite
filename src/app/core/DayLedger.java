package app.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the day looks like, one entry per label: the LAST thing that happened
 * to it. The portal keeps the last registration of a label, and a label
 * deleted from the portal is gone, so the day is counted the same way: a
 * range stopped halfway and started again sends some labels twice, and they
 * used to be counted twice ("47 registrate" with 40 pieces on the bench).
 *
 * Pure: report lines or events in, the day out. The daily report is the
 * source of truth, so a closed app (or a crash) reopens on the same day.
 */
public final class DayLedger {

    /** Report verdicts written by the Cancella tab. */
    public static final String DELETED          = "CANCELLATA";
    public static final String DELETED_VERIFIED = "CANCELLATA OK";
    public static final String STILL_THERE      = "ANCORA PRESENTE";
    /** The report section mode of the Cancella tab. */
    public static final String MODE_DELETE      = "cancella";

    /** One thing that happened to a label at a time of the day. */
    public static final class Event {
        public final String code, lot, time, verdict, mode;
        /** Seconds since the day's midnight; a run past midnight keeps counting. */
        public final int when;
        public final boolean deleted;

        public Event(String code, String lot, String time, String verdict, String mode,
                     int when, boolean deleted) {
            this.code = code;
            this.lot = lot == null ? "" : lot;
            this.time = time;
            this.verdict = verdict == null ? "" : verdict;
            this.mode = mode == null ? "-" : mode;
            this.when = when;
            this.deleted = deleted;
        }
    }

    private DayLedger() {}

    /**
     * Every sent row of the day's report as an event, in file order. Rows
     * never sent (IN CODA, NON INVIATA, no time) did not happen to the portal
     * and are left out; a deletion the check found still on the portal
     * (ANCORA PRESENTE) did not happen either.
     */
    public static List<Event> fromReport(List<String> lines) {
        List<Event> out = new ArrayList<>();
        String mode = "-";
        int sectionStart = 0;
        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            if (line.startsWith("RUN;")) {
                List<String> h = RunReport.splitCsv(line);
                sectionStart = h.size() > 2 ? seconds(h.get(2)) : 0;
                if (sectionStart < 0) sectionStart = 0;
                mode = "-";
                for (String f : h) if (f.startsWith("modalita=")) mode = f.substring("modalita=".length());
                continue;
            }
            if (line.startsWith("Etichetta;") || line.startsWith("TUTTO OK;")
                || line.startsWith("PROBLEMI;") || line.startsWith("Verifica;")
                || line.startsWith("Aggiornato;") || line.startsWith("CANCELLAZIONI;")) continue;
            List<String> f = RunReport.splitCsv(line);
            if (f.size() < 4) continue;
            String code = f.get(0).trim(), sentAt = f.get(2).trim(), esito = f.get(3).trim();
            if (code.isEmpty() || "-".equals(sentAt) || sentAt.isEmpty()) continue;
            if ("IN CODA".equals(esito) || "NON INVIATA".equals(esito) || STILL_THERE.equals(esito)) continue;
            int s = seconds(sentAt);
            if (s < 0) continue;
            // a run started at 23:58 that sent at 00:03 is still the same day's file
            if (s < sectionStart) s += 24 * 3600;
            boolean deleted = MODE_DELETE.equals(mode)
                && (DELETED.equals(esito) || DELETED_VERIFIED.equals(esito));
            if (MODE_DELETE.equals(mode) && !deleted) continue;
            out.add(new Event(code, f.get(1), sentAt, esito, mode, s, deleted));
        }
        return out;
    }

    /**
     * Labels put in the Cancella list today and never deleted: what a closed
     * app (or a crash) left in that list. Without a time, a waiting row is
     * never an event; this is the only place it is read.
     */
    public static List<String> pendingDeletes(List<String> lines) {
        java.util.LinkedHashSet<String> waiting = new java.util.LinkedHashSet<>();
        String mode = "-";
        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            if (line.startsWith("RUN;")) {
                mode = "-";
                for (String f : RunReport.splitCsv(line)) {
                    if (f.startsWith("modalita=")) mode = f.substring("modalita=".length());
                }
                continue;
            }
            if (!MODE_DELETE.equals(mode) || line.startsWith("Etichetta;")) continue;
            List<String> f = RunReport.splitCsv(line);
            if (f.size() < 4) continue;
            if ("IN CODA".equals(f.get(3).trim()) && !f.get(0).trim().isEmpty()) waiting.add(f.get(0).trim());
        }
        Map<String, Event> day = resolve(fromReport(lines));
        List<String> out = new ArrayList<>();
        for (String c : waiting) {
            Event e = day.get(c);
            if (e == null || !e.deleted) out.add(c);
        }
        return out;
    }

    /**
     * The last event of every label, ordered by when that last event
     * happened. Ties keep their order of arrival, so a later line of the same
     * second wins.
     */
    public static LinkedHashMap<String, Event> resolve(Collection<Event> events) {
        List<Event> sorted = new ArrayList<>(events);
        // stable: equal times keep the order they came in
        sorted.sort(Comparator.comparingInt(e -> e.when));
        LinkedHashMap<String, Event> out = new LinkedHashMap<>();
        for (Event e : sorted) {
            out.remove(e.code);      // re-inserted at the end: the order is the last event's
            out.put(e.code, e);
        }
        return out;
    }

    /** Labels whose last event is a registration: label -> {lot, time}. */
    public static Map<String, String[]> registered(Map<String, Event> day) {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (Event e : day.values()) {
            if (!e.deleted) out.put(e.code, new String[] { e.lot, e.time });
        }
        return out;
    }

    /** How many labels the day ends with: {registered, deleted}. */
    public static int[] counts(Map<String, Event> day) {
        int reg = 0, del = 0;
        for (Event e : day.values()) {
            if (e.deleted) del++; else reg++;
        }
        return new int[] { reg, del };
    }

    /** HH:mm:ss (or HH:mm) as seconds since midnight, -1 when it is not a time. */
    public static int seconds(String t) {
        if (t == null) return -1;
        String[] p = t.trim().split(":");
        if (p.length < 2 || p.length > 3) return -1;
        try {
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            int s = p.length == 3 ? Integer.parseInt(p[2]) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59 || s < 0 || s > 59) return -1;
            return h * 3600 + m * 60 + s;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
