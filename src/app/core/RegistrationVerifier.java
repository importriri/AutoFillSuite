package app.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// compares what a run tried to register against the CSV the site exports.
// the export is one column, "<label>|<lot>" per row, "SerialNumber|ProductionLot" header on line 1.
// no files, no UI, no clicking in here — hand it the lines and the run, get the diff.
// this is the part that has to be right, so it is the part that gets tested.
public final class RegistrationVerifier {

    public static final String DEFAULT_SEPARATOR = "|";
    private static final char BOM = '\uFEFF';   // windows exports like to prepend this

    private final String separator;

    public RegistrationVerifier() {
        this(DEFAULT_SEPARATOR);
    }

    public RegistrationVerifier(String separator) {
        this.separator = separator;
    }

    // ── expected side ────────────────────────────────────────────────────────
    // the labels a run generates: prefix + zero-padded sequence, same shape the
    // scan field produces. the lot is the same for the whole run, so it stays out.
    public static List<String> expectedCodes(String prefix, long start, int count) {
        List<String> codes = new ArrayList<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            codes.add(prefix + String.format("%03d", start + i));
        }
        return codes;
    }

    // ── verify ───────────────────────────────────────────────────────────────
    // classic run: one lot for every label. builds the pairs and delegates,
    // so both entry points share the exact same diff logic (and tests).
    public VerificationResult verify(List<String> exportLines,
                                     List<String> expectedCodes,
                                     String expectedLot) {
        String want = (expectedLot == null) ? "" : expectedLot;
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String code : expectedCodes) pairs.put(code, want);
        return verify(exportLines, pairs);
    }

    // dual scan: every label carries its OWN lot (the second QR of the pair)
    public VerificationResult verify(List<String> exportLines,
                                     Map<String, String> expectedPairs) {
        Map<String, List<String>> exported = parse(exportLines);

        List<String> missing       = new ArrayList<>();
        List<String> notRegistered = new ArrayList<>();
        Map<String, String> wrongLots = new LinkedHashMap<>();
        List<String> duplicates    = new ArrayList<>();
        Map<String, Integer> registrations = new LinkedHashMap<>();
        int matched = 0;

        for (Map.Entry<String, String> pair : expectedPairs.entrySet()) {
            String code = pair.getKey();
            String want = (pair.getValue() == null) ? "" : pair.getValue().trim();
            List<String> lots = exported.get(code);

            // never made it into the export at all — the run skipped it somehow
            if (lots == null || lots.isEmpty()) {
                missing.add(code);
                continue;
            }

            // the export appends, never updates: a print lands as "<label>|" and a
            // late registration adds a NEW row with the lot — the old empty row stays
            // behind (seen in a real export: same label, empty at row 8199, registered
            // at row 15728). so the question is never "what does the first row say",
            // it is "did the expected lot ever land on this label".
            int hits = 0;
            List<String> foreign = new ArrayList<>();
            for (String lot : lots) {
                if (lot.isEmpty()) continue;       // print event, not a registration
                if (lot.equals(want)) hits++;
                else foreign.add(lot);
            }

            if (hits > 0) {
                matched++;
                registrations.put(code, hits);
                if (hits + foreign.size() > 1) duplicates.add(code);  // registered more than once
            } else if (!foreign.isEmpty()) {
                wrongLots.put(code, String.join(", ", foreign));
            } else {
                notRegistered.add(code);             // printed, but never registered
            }
        }
        return new VerificationResult(missing, notRegistered, wrongLots, duplicates,
                                      registrations, matched);
    }

    // ── parsing ──────────────────────────────────────────────────────────────
    // raw export -> label mapped to every lot seen for it (a list, so a label
    // that shows up twice is not silently collapsed). blank lines and a leading
    // header row are dropped. we only look up our own labels, so an export that
    // carries the whole history is fine — the caller's expected set does the filter.
    private Map<String, List<String>> parse(List<String> lines) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        boolean atFirst = true;

        for (String raw : lines) {
            if (raw == null) continue;
            String line = stripBom(raw).trim();
            if (line.isEmpty()) continue;

            String[] cell = splitOnce(line);
            String code  = cell[0].trim();
            String lot = cell[1].trim();

            // a header sits on the first non-empty line and does not look like a
            // label. no header? then that same line is data and passes straight through.
            if (atFirst) {
                atFirst = false;
                if (!looksLikeLabel(code)) continue;
            }
            if (code.isEmpty()) continue;

            out.computeIfAbsent(code, k -> new ArrayList<>()).add(lot);
        }
        return out;
    }

    // split on the first separator only: a label never contains it, a lot might
    private String[] splitOnce(String line) {
        int i = line.indexOf(separator);
        if (i < 0) return new String[]{ line, "" };
        return new String[]{ line.substring(0, i), line.substring(i + separator.length()) };
    }

    // a label ends in the 3-digit sequence — the exact rule the scan field enforces
    private static boolean looksLikeLabel(String code) {
        if (code.length() < 3) return false;
        for (int i = code.length() - 3; i < code.length(); i++) {
            if (!Character.isDigit(code.charAt(i))) return false;
        }
        return true;
    }

    private static String stripBom(String s) {
        return (!s.isEmpty() && s.charAt(0) == BOM) ? s.substring(1) : s;
    }
}
