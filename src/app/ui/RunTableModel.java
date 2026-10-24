package app.ui;

import app.core.DayLedger;
import app.core.VerificationResult;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One row per label of the run: # | Etichetta | Lotto | Esito.
 * Rows appear the moment they are scanned or written; applyResult colors them.
 */
public class RunTableModel extends AbstractTableModel {

    public static final int PENDING = 0, OK = 1, MISSING = 2, NOT_REG = 3, WRONG = 4;
    /** The robot stopped before this row reached the site: never sent, not lost. */
    public static final int FAILED = 5;
    /** Deleted from the portal by the Cancella tab: history, never counted as registered. */
    public static final int DELETED = 6;

    private static final String[] COLS = { "#", "Etichetta", "Lotto", "Esito" };

    private static final class Row {
        String code;
        String lot;
        String sentAt;                 // null = never sent
        String outcome = "—";
        String tip;                    // the whole value when the cell shows a cut
        int state = PENDING;
        boolean deleted;               // a sent row of the Cancella tab
        int when = -1;                 // seconds of the day, for rows read from the report
        Row(String code, String lot) { this.code = code; this.lot = lot; }
        boolean sent() { return sentAt != null; }
        int when() { return when >= 0 ? when : DayLedger.seconds(sentAt); }
    }

    private final List<Row> rows = new ArrayList<>();
    // every run of the day, archived when the next one begins. READ-ONLY:
    // history can be looked at, never edited — and the report never reads it
    private final List<Row> dayRows = new ArrayList<>();
    // the day's report as read at the start: what earlier app starts did today.
    // Read once; what this app does afterwards is in dayRows and rows
    private final List<Row> history = new ArrayList<>();
    // what the day view shows above the current run: ONE row per label, the
    // last thing that happened to it (a range started twice is not twice the
    // labels), and never a label the current run holds
    private List<Row> dayArchive = new ArrayList<>();
    private final Set<String> archiveCodes = new HashSet<>();
    private boolean dayView = false;
    private boolean deleteRun = false;   // the current run is a Cancella session
    String dayStamp = today();          // package-visible for the rollover test
    private String currentLot = "";

    private static String today() {
        return java.time.LocalDate.now().toString();
    }

    /** The current run joins the day archive; past midnight the day resets. */
    private void archiveCurrent() {
        if (!dayStamp.equals(today())) {
            // rolled past midnight: yesterday's rows — archive AND current —
            // belong to yesterday. Start today empty.
            dayRows.clear();
            history.clear();
            dayStamp = today();
            rebuildArchive();
            return;
        }
        dayRows.addAll(rows);
    }

    /** Toggle between the last run and everything registered today. */
    public void setDayView(boolean on) {
        if (!dayStamp.equals(today())) { dayRows.clear(); history.clear(); dayStamp = today(); }
        dayView = on;
        rebuildArchive();
        fireTableDataChanged();
    }

    /**
     * What earlier starts of the app did today, from the day's report: a
     * closed app (or a crash) reopens on the same day it left. Read-only.
     */
    public void setHistory(List<DayLedger.Event> events) {
        history.clear();
        for (DayLedger.Event e : events) {
            Row r = new Row(e.code, e.lot);
            r.sentAt = e.time;
            r.when = e.when;
            r.deleted = e.deleted;
            r.outcome = e.verdict.isEmpty() ? "INVIATA" : e.verdict;
            r.state = stateOf(e);
            history.add(r);
        }
        rebuildArchive();
        fireTableDataChanged();
    }

    private static int stateOf(DayLedger.Event e) {
        if (e.deleted) return DELETED;
        String v = e.verdict;
        if (v.startsWith("OK")) return OK;
        if (v.startsWith("MANCANTE")) return MISSING;
        if (v.startsWith("NON REGISTRATA")) return NOT_REG;
        if (v.startsWith("LOTTO ERRATO")) return WRONG;
        return PENDING;
    }

    /**
     * One row per label above the current run. Arrival order (the report of
     * earlier starts first, then this app's runs); a label seen again moves
     * to its latest row. A row that reached the portal beats one that did
     * not, and between two that did, the later one wins.
     */
    private void rebuildArchive() {
        java.util.LinkedHashMap<String, Row> last = new java.util.LinkedHashMap<>();
        List<Row> all = new ArrayList<>(history);
        all.addAll(dayRows);
        for (Row r : all) {
            Row had = last.get(r.code);
            if (had != null) {
                if (had.sent() && !r.sent()) continue;
                if (had.sent() && r.sent() && r.when() < had.when()) continue;
                last.remove(r.code);
            }
            last.put(r.code, r);
        }
        Set<String> current = new HashSet<>();
        for (Row r : rows) current.add(r.code);
        dayArchive = new ArrayList<>();
        archiveCodes.clear();
        for (Row r : last.values()) {
            if (current.contains(r.code)) continue;
            dayArchive.add(r);
            archiveCodes.add(r.code);
        }
    }

    /** The archive changed under the current run: in day view the whole table moves. */
    private void archiveMayHaveChanged() {
        if (!dayView) return;
        rebuildArchive();
        fireTableDataChanged();
    }

    public boolean isDayView()      { return dayView; }
    /** How many archived rows sit above the current run in day view. */
    public int dayOffset()          { return dayView ? dayArchive.size() : 0; }
    public int dayArchivedCount()   { return dayArchive.size(); }

    /** Resolve a VISUAL index (what the table shows) to its row. */
    private Row at(int visual) {
        int off = dayOffset();
        return visual < off ? dayArchive.get(visual) : rows.get(visual - off);
    }

    public void beginRun(String lot) {
        archiveCurrent();
        currentLot = (lot == null) ? "" : lot;
        rows.clear();
        deleteRun = false;
        rebuildArchive();
        fireTableDataChanged();
    }

    /** A Cancella session: its sent rows are deletions, in the table and in the report. */
    public void beginDeleteRun() {
        beginRun("");
        deleteRun = true;
    }

    public boolean isDeleteRun() { return deleteRun; }

    /** A row with the run's lot; returns its RUN index (never the visual one). */
    public int addRow(String code) {
        return addRow(code, currentLot);
    }

    /** Dual scan: every row carries its own lot (the second QR of the pair). */
    public int addRow(String code, String lot) {
        rows.add(new Row(code, (lot == null) ? "" : lot));
        int idx = rows.size() - 1;
        // the label was in the archive: it moves down into the current run
        if (dayView && archiveCodes.contains(code)) { archiveMayHaveChanged(); return idx; }
        // mutations live in RUN space, but the table listens in VISUAL space:
        // in day view the archive sits above, so events fire at the offset
        int v = dayOffset() + idx;
        fireTableRowsInserted(v, v);
        return idx;
    }

    public void markSentNow(int row) {
        if (row < 0 || row >= rows.size()) return;
        rows.get(row).sentAt = java.time.LocalTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    /** The Cancella tab deleted this label from the portal: grey, and off the day's count. */
    public void markDeleted(int row) {
        if (row < 0 || row >= rows.size()) return;
        Row r = rows.get(row);
        if (r.sentAt == null) markSentNow(row);
        r.deleted = true;
        r.state = DELETED;
        r.outcome = DayLedger.DELETED;
        int v = dayOffset() + row;
        fireTableRowsUpdated(v, v);
    }

    /** The check after a deletion: gone from the export, or still there. */
    public void markDeleteChecked(int row, boolean gone) {
        if (row < 0 || row >= rows.size()) return;
        Row r = rows.get(row);
        if (!r.sent()) return;
        r.deleted = gone;
        r.state = gone ? DELETED : MISSING;
        r.outcome = gone ? DayLedger.DELETED_VERIFIED : DayLedger.STILL_THERE;
        r.tip = gone ? null : "L'export del portale contiene ancora questa etichetta";
        int v = dayOffset() + row;
        fireTableRowsUpdated(v, v);
    }

    /** The burst died on this pair: the row says so instead of staying "IN CODA". */
    public void markFailed(int row) {
        if (row < 0 || row >= rows.size()) return;
        Row r = rows.get(row);
        r.state = FAILED;
        r.outcome = "NON INVIATA";
        int v = dayOffset() + row;
        fireTableRowsUpdated(v, v);
    }

    public void setLot(int row, String lot) {
        if (row < 0 || row >= rows.size()) return;
        rows.get(row).lot = lot;
        int v = dayOffset() + row;
        fireTableRowsUpdated(v, v);
    }

    public boolean updateQueuedPair(int row, String code, String lot) {
        if (row < 0 || row >= rows.size()) return false;
        Row r = rows.get(row);
        if (r.sentAt != null || r.state != PENDING) return false;
        boolean moved = !r.code.equals(code);
        r.code = code;
        r.lot = lot;
        r.outcome = "IN CODA";
        if (moved && dayView) { archiveMayHaveChanged(); return true; }
        int v = dayOffset() + row;
        fireTableRowsUpdated(v, v);
        return true;
    }

    public boolean removeQueuedPair(int row) {
        if (row < 0 || row >= rows.size()) return false;
        Row r = rows.get(row);
        if (r.sentAt != null || r.state != PENDING) return false;
        int v = dayOffset() + row;
        rows.remove(row);
        // the label may come back up from the archive
        if (dayView) { archiveMayHaveChanged(); return true; }
        fireTableRowsDeleted(v, v);
        return true;
    }

    public String lotAt(int row) { return at(row).lot; }

    /** The lot a label shows in the current run (the last row of it), or null. */
    public String runLotOf(String code) {
        String lot = null;
        for (Row r : rows) if (r.code.equals(code)) lot = r.lot;
        return lot;
    }

    /**
     * The day, one entry per label, the last thing that happened to it: the
     * report of earlier starts, this app's archive and the current run.
     */
    public java.util.LinkedHashMap<String, DayLedger.Event> day() {
        List<DayLedger.Event> events = new ArrayList<>();
        if (!dayStamp.equals(today())) return DayLedger.resolve(events);
        List<Row> all = new ArrayList<>(history);
        all.addAll(dayRows);
        all.addAll(rows);
        for (Row r : all) {
            if (!r.sent()) continue;
            // a deletion the check found still on the portal did not happen
            if (DayLedger.STILL_THERE.equals(r.outcome)) continue;
            int w = r.when();
            if (w < 0) continue;
            events.add(new DayLedger.Event(r.code, r.lot, r.sentAt, r.outcome,
                r.deleted ? DayLedger.MODE_DELETE : "-", w, r.deleted));
        }
        return DayLedger.resolve(events);
    }

    /** Labels registered today and not deleted since: label -> {lot, time}. */
    public Map<String, String[]> sentToday() {
        return DayLedger.registered(day());
    }

    /** {registered, deleted}: every label once, as the portal counts them. */
    public int[] dayCounts() {
        return DayLedger.counts(day());
    }

    /** Labels of the current run still waiting in the queue: they fill a gap already. */
    public Set<String> queuedCodes() {
        Set<String> out = new HashSet<>();
        if (deleteRun) return out;   // waiting to be deleted is not filling anything
        for (Row r : rows) if (!r.sent() && r.state == PENDING) out.add(r.code);
        return out;
    }

    public java.util.List<app.core.RunReport.Entry> reportEntries() {
        java.util.List<app.core.RunReport.Entry> out = new java.util.ArrayList<>();
        for (Row r : rows) {
            out.add(new app.core.RunReport.Entry(r.code, r.lot, r.sentAt, deleteRun,
                                                 deleteRun ? r.outcome : null));
        }
        return out;
    }

    /** Phase update (IN CODA → INVIATA) without touching the outcome color. */
    public void updateOutcome(int row, String outcome) {
        if (row < 0 || row >= rows.size()) return;
        rows.get(row).outcome = outcome;
        int v = dayOffset() + row;
        fireTableRowsUpdated(v, v);
    }

    /**
     * Manual verify with no live run: build the whole expected set at once.
     * When the rows already match the run, keep them as they are.
     */
    public void ensureRows(List<String> codes, String lot) {
        boolean same = rows.size() == codes.size();
        if (same) {
            for (int i = 0; i < codes.size(); i++) {
                if (!rows.get(i).code.equals(codes.get(i))) { same = false; break; }
            }
        }
        if (same) return;
        // the rows already there keep their lot and send time: a range
        // stopped half way and checked by hand still knows what it sent
        Map<String, Row> kept = new java.util.HashMap<>();
        for (Row r : rows) kept.put(r.code, r);
        currentLot = (lot == null) ? "" : lot;
        rows.clear();
        for (String c : codes) {
            Row r = kept.remove(c);
            rows.add(r != null ? r : new Row(c, currentLot));
        }
        fireTableDataChanged();
    }

    /** Every row of the current run is one of these labels. */
    public boolean runCodesWithin(List<String> codes) {
        java.util.Set<String> all = new java.util.HashSet<>(codes);
        for (Row r : rows) if (!all.contains(r.code)) return false;
        return true;
    }

    public void clearOutcome() {
        for (Row r : rows) {
            if (r.state == DELETED) continue;
            // a row that never went out, or waits in the queue, keeps its word:
            // a check cannot judge it, and NON INVIATA must stay red
            if (r.state == FAILED || r.outcome.endsWith("IN CODA")) continue;
            r.outcome = "—"; r.state = PENDING; r.tip = null;
        }
        fireTableDataChanged();
    }

    public void applyResult(VerificationResult res) {
        Set<String> missing = new HashSet<>(res.getMissing());
        Set<String> notReg  = new HashSet<>(res.getNotRegistered());
        // structured, not parsed: the label -> lot map comes straight from the core
        Map<String, String> wrong = res.getWrongLots();
        // the site appends: re-running the same batch by mistake only raises the
        // count, it is not a problem — hence "OK ×N", never a "duplicate" warning
        Map<String, Integer> counts = res.getRegistrationCounts();
        for (Row r : rows) {
            // the check looked for another lot than the one this row shows
            // (a correction still queued): it says nothing about this row
            if (!res.covers(r.code, r.lot))     continue;
            if (missing.contains(r.code))       { r.state = MISSING; r.outcome = "MANCANTE"; }
            else if (notReg.contains(r.code))   { r.state = NOT_REG; r.outcome = "NON REGISTRATA"; }
            else if (wrong.containsKey(r.code)) {
                r.state = WRONG;
                r.outcome = "LOTTO ERRATO: " + shorten(wrong.get(r.code));
                r.tip = "Trovato: " + wrong.get(r.code);
            }
            else if (counts.containsKey(r.code)) {
                int n = counts.get(r.code);
                r.state = OK;
                r.outcome = (n > 1) ? "OK ×" + n : "OK";
            }
            // uncovered rows stay pending — never fake green
        }
        fireTableDataChanged();
    }

    /** The TAIL survives, like fitTail: the end of a lot is what tells lots apart. */
    static String shorten(String s) {
        return s.length() > 18 ? "..." + s.substring(s.length() - 17) : s;
    }

    /** The whole value behind a cut cell, or null. */
    public String tipAt(int row) {
        return at(row).tip;
    }

    public int stateAt(int row)   { return at(row).state; }
    public String codeAt(int row) { return at(row).code; }

    @Override public int getRowCount()           { return dayOffset() + rows.size(); }
    @Override public int getColumnCount()        { return COLS.length; }
    @Override public String getColumnName(int c) { return COLS[c]; }

    @Override public Object getValueAt(int r, int c) {
        Row row = at(r);
        switch (c) {
            case 0:  return r + 1;
            case 1:  return row.code;
            case 2:  return row.lot;
            default: return row.outcome;
        }
    }
}
