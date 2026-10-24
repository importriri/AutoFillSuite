package app.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Holes in the day's numbering: labels of one type are printed in sequence,
 * so with 510 and 520 registered and nothing in between, 511-519 are pieces
 * nobody registered (or labels spoiled and never deleted from the portal).
 * Each label type is its own sequence: the type is everything before the
 * final run of digits, the number is that run.
 *
 * A jump bigger than {@code maxGap} is not a hole but another stack of labels
 * printed later in the day: it is reported as a jump, never as missing work.
 * Pure: codes in, holes out.
 */
public final class GapFinder {

    /** One run of missing numbers of one label type. */
    public static final class Gap {
        public final String prefix;
        public final long from, to;          // the missing numbers, both included
        public final String fromCode, toCode;
        public final boolean jump;           // too wide to be a hole

        Gap(String prefix, int width, long from, long to, boolean jump) {
            this.prefix = prefix;
            this.from = from;
            this.to = to;
            this.fromCode = code(prefix, width, from);
            this.toCode = code(prefix, width, to);
            this.jump = jump;
        }

        public long count() { return to - from + 1; }

        /** Every missing label of the hole (empty for a jump: nobody lists 300 codes). */
        public List<String> codes() {
            List<String> out = new ArrayList<>();
            if (jump) return out;
            int width = fromCode.length() - prefix.length();
            for (long n = from; n <= to; n++) out.add(code(prefix, width, n));
            return out;
        }
    }

    private GapFinder() {}

    /** Default widest hole: wider is another stack of labels, not missing work. */
    public static final int DEFAULT_MAX_GAP = 50;

    /**
     * @param present every label the day already accounts for: registered,
     *                deleted from the portal, or waiting in a queue
     * @param maxGap  the widest run reported as missing
     */
    public static List<Gap> find(Collection<String> present, int maxGap) {
        // type -> numbers, keyed by prefix AND width: 007 and 7 are not neighbours
        Map<String, TreeSet<Long>> byType = new LinkedHashMap<>();
        Map<String, String> prefixOf = new LinkedHashMap<>();
        Map<String, Integer> widthOf = new LinkedHashMap<>();
        for (String raw : present) {
            if (raw == null) continue;
            String c = raw.trim();
            int i = c.length();
            while (i > 0 && Character.isDigit(c.charAt(i - 1))) i--;
            int width = c.length() - i;
            if (width == 0 || width > 18) continue;      // no number, or past a long
            String prefix = c.substring(0, i);
            String key = prefix + "\u0000" + width;
            byType.computeIfAbsent(key, k -> new TreeSet<>()).add(Long.parseLong(c.substring(i)));
            prefixOf.put(key, prefix);
            widthOf.put(key, width);
        }
        List<Gap> out = new ArrayList<>();
        for (Map.Entry<String, TreeSet<Long>> e : byType.entrySet()) {
            Long prev = null;
            for (Long n : e.getValue()) {
                if (prev != null && n - prev > 1) {
                    long missing = n - prev - 1;
                    out.add(new Gap(prefixOf.get(e.getKey()), widthOf.get(e.getKey()),
                                    prev + 1, n - 1, missing > Math.max(1, maxGap)));
                }
                prev = n;
            }
        }
        return out;
    }

    /** How many labels the real holes miss (jumps excluded). */
    public static long missing(List<Gap> gaps) {
        long n = 0;
        for (Gap g : gaps) if (!g.jump) n += g.count();
        return n;
    }

    /** How many real holes (jumps excluded). */
    public static int holes(List<Gap> gaps) {
        int n = 0;
        for (Gap g : gaps) if (!g.jump) n++;
        return n;
    }

    /** The day's holes as CSV, for the quality office and for whoever reopens the day. */
    public static String toCsv(List<Gap> gaps) {
        StringBuilder sb = new StringBuilder("Tipo;Da;A;Quante;Stato\n");
        for (Gap g : gaps) {
            sb.append(RunReport.esc(g.prefix)).append(';')
              .append(g.fromCode).append(';')
              .append(g.toCode).append(';')
              .append(g.count()).append(';')
              .append(g.jump ? "SALTO (non controllato)" : "MANCANTI").append('\n');
        }
        return sb.toString();
    }

    private static String code(String prefix, int width, long n) {
        String digits = Long.toString(n);
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = digits.length(); i < width; i++) sb.append('0');
        return sb.append(digits).toString();
    }
}
