package app.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The run as CSV for the quality office. Pure text in/out: fully testable. */
public final class RunReport {

    public static final String HEADER =
        "Etichetta;Lotto;OraInvio;Esito;Registrazioni;Dettaglio";

    /** One table row as the report sees it. */
    public static final class Entry {
        public final String code, lot, sentAt;   // sentAt null = never sent
        public Entry(String code, String lot, String sentAt) {
            this.code = code; this.lot = lot; this.sentAt = sentAt;
        }
    }

    private RunReport() {}

    /**
     * The run as a LIVE journal, before any verification exists: every send
     * lands in the daily file the moment it happens, so the end-of-day report
     * is complete even if nobody ever presses a button. Same columns as
     * {@link #toCsv}; the verdict is the row's own state — INVIATA with its
     * send time, IN CODA without — and the verification later replaces the
     * whole section with real verdicts.
     */
    public static String liveCsv(List<Entry> entries) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Entry e : entries) {
            boolean sent = e.sentAt != null;
            sb.append(esc(e.code)).append(';')
              .append(esc(e.lot)).append(';')
              .append(sent ? e.sentAt : "-").append(';')
              .append(sent ? "INVIATA" : "IN CODA").append(';')
              .append(0).append(';')
              .append("").append('\n');
        }
        return sb.toString();
    }

    public static String toCsv(List<Entry> entries, VerificationResult r) {
        Map<String, Integer> counts = r.getRegistrationCounts();
        List<String> missing = r.getMissing();
        List<String> notReg  = r.getNotRegistered();
        Map<String, String> wrong = r.getWrongLots();

        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Entry e : entries) {
            String esito, dettaglio = "";
            Integer n = counts.get(e.code);
            if (missing.contains(e.code)) {
                esito = "MANCANTE"; dettaglio = "non presente nel CSV";
            } else if (notReg.contains(e.code)) {
                esito = "NON REGISTRATA"; dettaglio = "solo stampa, mai registrata";
            } else if (wrong.containsKey(e.code)) {
                esito = "LOTTO ERRATO"; dettaglio = "trovato: " + wrong.get(e.code);
            } else if (n != null) {
                esito = "OK";
                if (n > 1) dettaglio = "registrata " + n + " volte";
            } else {
                // the verification never covered this row
                esito = e.sentAt == null ? "NON INVIATA" : "NON VERIFICATA";
                if (e.sentAt == null) dettaglio = "mai inviata al portale";
            }
            sb.append(esc(e.code)).append(';')
              .append(esc(e.lot)).append(';')
              .append(e.sentAt == null ? "-" : e.sentAt).append(';')
              .append(esc(esito)).append(';')
              .append(n == null ? 0 : n).append(';')
              .append(esc(dettaglio)).append('\n');
        }
        return sb.toString();
    }

    public static String summary(int total, VerificationResult r) {
        return r.isClean()
            ? "TUTTO OK;" + r.getMatched() + "/" + total
            : "PROBLEMI;" + r.totalProblems() + "/" + total;
    }

    public static String verifyLine(String time, String file, int attempts, boolean fresh) {
        return "Verifica;" + time + ";file=" + esc(file)
             + ";tentativi=" + attempts + ";export=" + (fresh ? "fresco" : "ripescato");
    }

    public static List<String> lines(String csv) {
        List<String> out = new ArrayList<>();
        for (String l : csv.split("\n")) out.add(l);
        return out;
    }

    // ── daily file: one CSV per day, one section per run ────────────────────

    public static String sectionHead(String date, String time, String lot, String mode) {
        return "RUN;" + date + ";" + time + ";lotto=" + esc(lot) + ";modalita=" + mode;
    }

    /** Identity of a section inside the day file. */
    public static String sectionKey(String date, String time) {
        return "RUN;" + date + ";" + time + ";";
    }

    /** Replace the section matching {@code key}, or append. */
    public static List<String> mergeDaily(List<String> existing, String key,
                                          List<String> section) {
        List<String> out = new ArrayList<>();
        boolean replaced = false;
        int i = 0;
        while (i < existing.size()) {
            String line = existing.get(i);
            if (line.startsWith(key)) {
                int j = i + 1;
                while (j < existing.size() && !existing.get(j).startsWith("RUN;")) j++;
                while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
                    out.remove(out.size() - 1);
                }
                if (!out.isEmpty()) out.add("");
                out.addAll(section);
                replaced = true;
                if (j < existing.size()) out.add("");
                i = j;
            } else {
                out.add(line);
                i++;
            }
        }
        if (!replaced) {
            while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
                out.remove(out.size() - 1);
            }
            if (!out.isEmpty()) out.add("");
            out.addAll(section);
        }
        return out;
    }

    // free-text lots can carry ; " or newlines: quote and double the quotes
    static String esc(String s) {
        if (s == null) return "";
        boolean needs = s.indexOf(';') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0;
        return needs ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
