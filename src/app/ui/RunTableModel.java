package app.ui;

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

    private static final String[] COLS = { "#", "Etichetta", "Lotto", "Esito" };

    private static final class Row {
        final String code;
        String lot;
        String sentAt;                 // null = never sent
        String outcome = "—";
        int state = PENDING;
        Row(String code, String lot) { this.code = code; this.lot = lot; }
    }

    private final List<Row> rows = new ArrayList<>();
    // every run of the day, archived when the next one begins. READ-ONLY:
    // history can be looked at, never edited — and the report never reads it
    private final List<Row> dayRows = new ArrayList<>();
    private boolean dayView = false;
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
            dayStamp = today();
            return;
        }
        dayRows.addAll(rows);
    }

    /** Toggle between the last run and everything registered today. */
    public void setDayView(boolean on) {
        if (!dayStamp.equals(today())) { dayRows.clear(); dayStamp = today(); }
        dayView = on;
        fireTableDataChanged();
    }

    public boolean isDayView()      { return dayView; }
    /** How many archived rows sit above the current run in day view. */
    public int dayOffset()          { return dayView ? dayRows.size() : 0; }
    public int dayArchivedCount()   { return dayRows.size(); }

    /** Resolve a VISUAL index (what the table shows) to its row. */
    private Row at(int visual) {
        int off = dayOffset();
        return visual < off ? dayRows.get(visual) : rows.get(visual - off);
    }

    public void beginRun(String lot) {
        archiveCurrent();
        currentLot = (lot == null) ? "" : lot;
        rows.clear();
        fireTableDataChanged();
    }

    public void addRow(String code) {
        addRow(code, currentLot);
    }

    /** Dual scan: every row carries its own lot (the second QR of the pair). */
    public int addRow(String code, String lot) {
        rows.add(new Row(code, (lot == null) ? "" : lot));
        int idx = rows.size() - 1;
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

    public void setLot(int row, String lot) {
        if (row < 0 || row >= rows.size()) return;
        rows.get(row).lot = lot;
        int v = dayOffset() + row;
        fireTableRowsUpdated(v, v);
    }

    public String lotAt(int row) { return at(row).lot; }

    public java.util.List<app.core.RunReport.Entry> reportEntries() {
        java.util.List<app.core.RunReport.Entry> out = new java.util.ArrayList<>();
        for (Row r : rows) out.add(new app.core.RunReport.Entry(r.code, r.lot, r.sentAt));
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
        currentLot = (lot == null) ? "" : lot;
        rows.clear();
        for (String c : codes) rows.add(new Row(c, currentLot));
        fireTableDataChanged();
    }

    public void clearOutcome() {
        for (Row r : rows) { r.outcome = "—"; r.state = PENDING; }
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
            if (missing.contains(r.code))       { r.state = MISSING; r.outcome = "MANCANTE"; }
            else if (notReg.contains(r.code))   { r.state = NOT_REG; r.outcome = "NON REGISTRATA"; }
            else if (wrong.containsKey(r.code)) { r.state = WRONG;   r.outcome = "LOTTO ERRATO: " + shorten(wrong.get(r.code)); }
            else if (counts.containsKey(r.code)) {
                int n = counts.get(r.code);
                r.state = OK;
                r.outcome = (n > 1) ? "OK ×" + n : "OK";
            }
            // uncovered rows stay pending — never fake green
        }
        fireTableDataChanged();
    }

    private static String shorten(String s) {
        return s.length() > 18 ? s.substring(0, 17) + "..." : s;
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
