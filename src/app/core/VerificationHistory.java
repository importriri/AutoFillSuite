package app.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads back what VerificationLog wrote and turns it into numbers a supervisor
 * can use: how many runs, how many went clean, where the problems pile up.
 * Parsing only — hand it the lines, get the stats. No files in here.
 */
public final class VerificationHistory {

    /** One parsed run. */
    public static final class Entry {
        public final String day;      // yyyy-MM-dd
        public final boolean clean;
        public final int problems;
        public final int total;

        Entry(String day, boolean clean, int problems, int total) {
            this.day = day;
            this.clean = clean;
            this.problems = problems;
            this.total = total;
        }
    }

    public static final class Stats {
        public final int runs;
        public final int cleanRuns;
        public final int problemRuns;
        public final int totalProblems;
        public final int totalLabels;
        public final Map<String, Integer> problemsPerDay;   // insertion-ordered

        Stats(int runs, int cleanRuns, int problemRuns, int totalProblems,
              int totalLabels, Map<String, Integer> perDay) {
            this.runs = runs;
            this.cleanRuns = cleanRuns;
            this.problemRuns = problemRuns;
            this.totalProblems = totalProblems;
            this.totalLabels = totalLabels;
            this.problemsPerDay = perDay;
        }

        /** Share of runs that came out clean, 0..100. */
        public int cleanPercent() {
            return runs == 0 ? 0 : (int) Math.round(cleanRuns * 100.0 / runs);
        }
    }

    private VerificationHistory() {}

    public static List<Entry> parse(List<String> lines) {
        List<Entry> out = new ArrayList<>();
        if (lines == null) return out;

        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            // a run line starts with the stamp and carries a verdict; the indented
            // detail lines under it are not runs and must not be counted twice
            if (line.length() < 20 || raw.startsWith("    ")) continue;

            String day = line.substring(0, 10);
            if (!looksLikeDay(day)) continue;

            String body = line.substring(19).trim();
            boolean clean = body.startsWith("OK ");
            boolean problem = body.startsWith("PROBLEMI ");
            if (!clean && !problem) continue;   // ERRORE / ANNULLATA are not runs

            int[] ratio = ratio(body);
            if (ratio == null) continue;
            out.add(new Entry(day, clean, clean ? 0 : ratio[0], ratio[1]));
        }
        return out;
    }

    public static Stats stats(List<Entry> entries) {
        int clean = 0, problems = 0, labels = 0, problemRuns = 0;
        Map<String, Integer> perDay = new LinkedHashMap<>();

        for (Entry e : entries) {
            if (e.clean) {
                clean++;
            } else {
                problemRuns++;
                problems += e.problems;
            }
            labels += e.total;
            Integer had = perDay.get(e.day);
            perDay.put(e.day, (had == null ? 0 : had) + e.problems);
        }
        return new Stats(entries.size(), clean, problemRuns, problems, labels, perDay);
    }

    // "OK 10/10 ..." or "PROBLEMI 3/50 ..." -> { 3, 50 }
    private static int[] ratio(String body) {
        int sp = body.indexOf(' ');
        if (sp < 0) return null;
        int end = body.indexOf(' ', sp + 1);
        String frac = (end < 0) ? body.substring(sp + 1) : body.substring(sp + 1, end);
        int slash = frac.indexOf('/');
        if (slash < 0) return null;
        try {
            return new int[] {
                Integer.parseInt(frac.substring(0, slash).trim()),
                Integer.parseInt(frac.substring(slash + 1).trim())
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean looksLikeDay(String s) {
        if (s.length() != 10 || s.charAt(4) != '-' || s.charAt(7) != '-') return false;
        for (int i = 0; i < 10; i++) {
            if (i == 4 || i == 7) continue;
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
