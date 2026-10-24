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
    // three digits move: a range that would need a fourth (998 + 5 = 1000,
    // 1001) builds labels that do not exist, so it is refused here — the one
    // place both the robot and the verification take their codes from
    public static List<String> expectedCodes(String prefix, long start, int count) {
        if (count > 0 && start + count - 1 > 999) {
            throw new IllegalArgumentException("l'intervallo supera 999: " + start + " + " + count
                + " darebbe etichette con una cifra in più");
        }
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
            // at row 15728). and the portal keeps the LAST registration: registering
            // a label again with another lot is a correction. so the question is
            // "what is the last non-empty lot of this label" — not "did the expected
            // lot ever land", which painted right-then-wrong green while the portal
            // held the wrong one.
            int hits = 0;
            int registered = 0;
            String effective = null;
            for (String lot : lots) {
                if (lot.isEmpty()) continue;       // print event, not a registration
                registered++;
                if (lot.equals(want)) hits++;
                effective = lot;                   // export order is chronological
            }

            if (registered > 1) duplicates.add(code);   // registered more than once: look
            if (effective == null) {
                notRegistered.add(code);             // printed, but never registered
            } else if (effective.equals(want)) {
                matched++;
                registrations.put(code, hits);
            } else {
                wrongLots.put(code, effective);      // what the portal holds NOW
            }
        }
        return new VerificationResult(missing, notRegistered, wrongLots, duplicates,
                                      registrations, matched, expectedPairs);
    }

    // ── effective view ───────────────────────────────────────────────────────
    // label -> the lot the portal holds now: the last non-empty row, in export
    // order. labels that were only printed have no lot and are left out. this is
    // what a pre-flight compares a file against before registering anything.
    public Map<String, String> lastLots(List<String> exportLines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : parse(exportLines).entrySet()) {
            String last = null;
            for (String lot : e.getValue()) {
                if (!lot.isEmpty()) last = lot;
            }
            if (last != null) out.put(e.getKey(), last);
        }
        return out;
    }

    // ── presence ─────────────────────────────────────────────────────────────
    // every label the export holds at all, printed or registered. a label
    // deleted from the portal disappears from the export: this is what the
    // check of the Cancella tab asks.
    public java.util.Set<String> presentCodes(List<String> exportLines) {
        return new java.util.LinkedHashSet<>(parse(exportLines).keySet());
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
