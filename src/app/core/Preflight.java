package app.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Before registering a file: what does the portal hold NOW for each label?
 * Pure — the rows of the file and {@link RegistrationVerifier#lastLots} of a
 * fresh export in, one verdict per row out. No second parser of the export.
 *
 * <ul>
 * <li>the portal's last lot equals the file's lot: {@code GIA_PRESENTE}, skip it;</li>
 * <li>NUOVA and the label absent, or CORREZIONE and the portal holding exactly
 *     {@code lotto_prima}: {@code DA_INVIARE};</li>
 * <li>anything else: {@code CONFLITTO} — someone registered it another way.
 *     The operator decides, never the program.</li>
 * </ul>
 */
public final class Preflight {

    public enum Verdict { GIA_PRESENTE, DA_INVIARE, CONFLITTO }

    /** One row's verdict, with what the portal holds ("" when nothing). */
    public static final class Item {
        public final CsvImport.CsvRow row;
        public final Verdict verdict;
        public final String found;

        Item(CsvImport.CsvRow row, Verdict verdict, String found) {
            this.row = row;
            this.verdict = verdict;
            this.found = found == null ? "" : found;
        }
    }

    private Preflight() {}

    public static List<Item> check(List<CsvImport.CsvRow> rows, Map<String, String> lastLots) {
        List<Item> out = new ArrayList<>();
        for (CsvImport.CsvRow r : rows) {
            String last = lastLots.get(r.label);
            Verdict v;
            if (last != null && last.equals(r.lot)) {
                v = Verdict.GIA_PRESENTE;
            } else if (!r.isCorrection() && last == null) {
                v = Verdict.DA_INVIARE;
            } else if (r.isCorrection() && last != null && last.equals(r.lotBefore)) {
                v = Verdict.DA_INVIARE;
            } else {
                v = Verdict.CONFLITTO;
            }
            out.add(new Item(r, v, last));
        }
        return Collections.unmodifiableList(out);
    }

    public static int count(List<Item> items, Verdict v) {
        int n = 0;
        for (Item i : items) if (i.verdict == v) n++;
        return n;
    }
}
