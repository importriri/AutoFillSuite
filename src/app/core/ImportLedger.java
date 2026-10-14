package app.core;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The register of imported files ({@code AutoFillSuite_importazioni.txt} in
 * the report folder). One line per event, appended and forced to disk:
 * <pre>
 * yyyy-MM-dd HH:mm:ss TAB EVENT TAB key=value TAB key=value ...
 * </pre>
 * Events: INIZIO, INVIATA (written right after ENTER), BLOCCO_VERIFICATO,
 * INTERROTTO, COMPLETATO. TAB, LF and backslash inside a value are escaped, so
 * free-text lots survive. Writer and parser live together and are tested on a
 * round trip: the two drifted apart once in VerificationLog.
 *
 * It answers three questions before the robot clicks anything: has this file
 * (by fingerprint) been completed; was it interrupted, and which rows already
 * went out; is the ORDER right — export k of a station's day only after k-1 is
 * completed and before anything later, because an old file registered after a
 * correction puts the wrong lot back, in silence (the last one wins).
 */
public final class ImportLedger {

    public static final String FILE_NAME = app.config.AppFiles.IMPORT_LEDGER;

    public static final String INIZIO = "INIZIO";
    public static final String INVIATA = "INVIATA";
    public static final String BLOCCO_VERIFICATO = "BLOCCO_VERIFICATO";
    public static final String INTERROTTO = "INTERROTTO";
    public static final String COMPLETATO = "COMPLETATO";

    private final Path file;

    public ImportLedger(Path file) {
        this.file = file;
    }

    /** The register in a report folder. */
    public static ImportLedger in(File reportDir) {
        return new ImportLedger(new File(reportDir, FILE_NAME).toPath());
    }

    public Path file() {
        return file;
    }

    // ── writing ──────────────────────────────────────────────────────────

    public void start(String sha, String name, String station, String date, int export,
                      int rows, boolean reexport) throws IOException {
        append(INIZIO, "sha", sha, "nome", name, "postazione", station, "giornata", date,
               "export", String.valueOf(export), "righe", String.valueOf(rows),
               "riesportazione", reexport ? "1" : "0");
    }

    /** Right after ENTER, before anything else: this line is what a resume trusts. */
    public void sent(String sha, int n, String label, String lot) throws IOException {
        append(INVIATA, "sha", sha, "n", String.valueOf(n), "etichetta", label, "lotto", lot);
    }

    public void blockVerified(String sha, int upToSent, boolean clean) throws IOException {
        append(BLOCCO_VERIFICATO, "sha", sha, "inviate", String.valueOf(upToSent),
               "esito", clean ? "verde" : "rosso");
    }

    public void interrupted(String sha, String why) throws IOException {
        append(INTERROTTO, "sha", sha, "motivo", why == null ? "" : why);
    }

    public void completed(String sha, boolean clean) throws IOException {
        append(COMPLETATO, "sha", sha, "esito", clean ? "verde" : "rosso");
    }

    private synchronized void append(String event, String... kv) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
          .append('\t').append(event);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append('\t').append(kv[i]).append('=').append(escape(kv[i + 1]));
        }
        sb.append('\n');
        File dir = file.toAbsolutePath().getParent().toFile();
        if (!dir.isDirectory()) Files.createDirectories(dir.toPath());
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buf = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        }
    }

    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\t': sb.append("\\t");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { sb.append(c); continue; }
            char n = s.charAt(++i);
            sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
        }
        return sb.toString();
    }

    // ── reading ──────────────────────────────────────────────────────────

    /** One parsed line. */
    public static final class Entry {
        public final String when;
        public final String event;
        public final Map<String, String> values;

        Entry(String when, String event, Map<String, String> values) {
            this.when = when;
            this.event = event;
            this.values = Collections.unmodifiableMap(values);
        }

        public String get(String key) {
            String v = values.get(key);
            return v == null ? "" : v;
        }

        int getInt(String key) {
            try { return Integer.parseInt(get(key).trim()); } catch (NumberFormatException e) { return -1; }
        }
    }

    /**
     * Parses the register. A line cut by a shutdown (no final LF) and any line
     * that does not read are skipped, never guessed.
     */
    public static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<>();
        int start = 0;
        while (true) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) break;   // the last line has no LF: written halfway, ignored
            String line = text.substring(start, nl);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            start = nl + 1;
            String[] parts = line.split("\t", -1);
            if (parts.length < 2 || parts[0].length() != 19) continue;
            Map<String, String> kv = new LinkedHashMap<>();
            boolean ok = true;
            for (int i = 2; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                if (eq <= 0) { ok = false; break; }
                kv.put(parts[i].substring(0, eq), unescape(parts[i].substring(eq + 1)));
            }
            if (!ok || !kv.containsKey("sha")) continue;
            out.add(new Entry(parts[0], parts[1], kv));
        }
        return out;
    }

    public List<Entry> read() {
        try {
            if (!Files.exists(file)) return new ArrayList<>();
            byte[] all = Files.readAllBytes(file);
            return parse(new String(all, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /** What the register knows about one file (by fingerprint). */
    public static final class FileState {
        public boolean started;
        public boolean completed;
        public String completedAt = "";
        public String outcome = "";
        public String startedAt = "";
        public String name = "";
        public String station = "";
        public String date = "";
        public int export;
        public int rows;
        public boolean reexport;
        /** n already sent (INVIATA), in order */
        public final Set<Integer> sent = new LinkedHashSet<>();

        /** Started, never completed: a resume is due. */
        public boolean interrupted() {
            return started && !completed;
        }
    }

    public Map<String, FileState> states() {
        Map<String, FileState> out = new LinkedHashMap<>();
        for (Entry e : read()) {
            String sha = e.get("sha");
            FileState s = out.get(sha);
            if (s == null) {
                s = new FileState();
                out.put(sha, s);
            }
            switch (e.event) {
                case INIZIO:
                    if (!s.started) s.startedAt = e.when;
                    s.started = true;
                    s.name = e.get("nome");
                    s.station = e.get("postazione");
                    s.date = e.get("giornata");
                    s.export = e.getInt("export");
                    s.rows = e.getInt("righe");
                    s.reexport = "1".equals(e.get("riesportazione"));
                    break;
                case INVIATA:
                    s.sent.add(e.getInt("n"));
                    break;
                case COMPLETATO:
                    s.completed = true;
                    s.completedAt = e.when;
                    s.outcome = e.get("esito");
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    public FileState state(String sha) {
        FileState s = states().get(sha);
        return s == null ? new FileState() : s;
    }

    /**
     * The order rule. null when export {@code k} of (station, date) may go;
     * otherwise why not, for the operator.
     */
    public String orderProblem(String station, String date, int k) {
        boolean previousDone = k <= 1;
        int later = 0;
        for (FileState s : states().values()) {
            if (!s.started || !s.station.equals(station) || !s.date.equals(date)) continue;
            if (s.export == k - 1 && s.completed) previousDone = true;
            boolean touched = s.completed || !s.sent.isEmpty();
            if (s.export > k && touched) later = Math.max(later, s.export);
        }
        if (later > 0) {
            return "è già stato registrato l'export " + String.format("%02d", later)
                 + " di " + station + ": questo file è più vecchio e rimetterebbe lotti già corretti";
        }
        if (!previousDone) {
            return "manca l'export " + String.format("%02d", k - 1) + " di " + station
                 + " del " + date + ": va registrato prima";
        }
        return null;
    }

    /** Export numbers completed for (station, date), ascending. */
    public List<Integer> completedExports(String station, String date) {
        List<Integer> out = new ArrayList<>();
        for (FileState s : states().values()) {
            if (s.completed && s.station.equals(station) && s.date.equals(date)) out.add(s.export);
        }
        Collections.sort(out);
        return out;
    }

    // ── archive ──────────────────────────────────────────────────────────

    /**
     * Keeps AutoFillSuite's own copy of an imported file in
     * {@code <report>/importazioni/<name>_<first 8 of sha>.csv}, if not there yet:
     * the stick can be lost after the import, the file is not.
     */
    public static File archive(File reportDir, File csv, String sha) throws IOException {
        File dir = app.config.AppFiles.importsDir(reportDir);
        Files.createDirectories(dir.toPath());
        String base = csv.getName().toLowerCase().endsWith(".csv")
            ? csv.getName().substring(0, csv.getName().length() - 4) : csv.getName();
        File dest = new File(dir, base + "_" + sha.substring(0, Math.min(8, sha.length())) + ".csv");
        if (dest.exists()) return dest;
        File tmp = new File(dir, dest.getName() + ".tmp");
        Files.copy(csv.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }
}
