package app.core;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

// finds the export chrome just downloaded, without ever trusting the filename.
// the site always serves "demo-export.csv" and chrome dedupes to "demo-export (1).csv", "demo-export (2).csv"
// and so on, so names carry no meaning: what identifies OUR file is that it landed
// after t0 (a timestamp taken right before the export click) and that it stopped
// growing. everything between that click and the lines handed to the verifier lives
// here — waiting for the file, and reading it with a charset that cannot blow up.
// no Robot, no UI: a temp folder is enough to test all of it.
public final class DownloadWatcher {

    // in-progress downloads: chrome writes "<name>.crdownload" and renames when done;
    // .part is firefox, .tmp shows up behind some proxies. never pick these up.
    private static final String[] PARTIAL_SUFFIXES = { ".crdownload", ".part", ".tmp" };

    /**
     * Told the base name of an export that arrived under another name than the
     * configured prefix, so the next search finds it straight away. The app
     * saves it as Impostazioni > Verifica > Prefisso export.
     */
    public static volatile Consumer<String> prefixLearned;

    private final Path downloadDir;
    private final String prefix;   // lowercase match; "" accepts anything, a prefix keeps strangers out
    private final int timeoutMs;   // total budget before giving up — the site-speed knob
    private final int pollMs;      // how often the folder is rescanned
    private final int stableMs;    // gap between the two size reads

    public DownloadWatcher(Path downloadDir, String prefix, int timeoutMs, int pollMs, int stableMs) {
        this.downloadDir = downloadDir;
        this.prefix    = (prefix == null) ? "" : prefix.trim().toLowerCase();
        this.timeoutMs = timeoutMs;
        this.pollMs    = pollMs;
        this.stableMs  = stableMs;
    }

    // capture t0 BEFORE clicking export, then call this. returns the finished file,
    // or null when the budget runs out — the caller owns the "export never arrived"
    // message. throws only when the folder itself is wrong: that is a config problem,
    // not a slow site, and it must not look like a timeout.
    public Path awaitNewExport(long sinceMillis) throws IOException, InterruptedException {
        if (!Files.isDirectory(downloadDir)) {
            throw new IOException("download folder not found: " + downloadDir);
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Path candidate = newestCandidate(sinceMillis);
            if (candidate != null && isStable(candidate)) {
                return candidate;
            }
            Thread.sleep(pollMs);
        }
        return null;
    }

    // newest completed export already in the folder, regardless of age — the
    // manual "verify now" falls back to this when no fresh download shows up,
    // so the operator can re-check a run against the last file at any time.
    public Path newestExisting() throws IOException, InterruptedException {
        if (!Files.isDirectory(downloadDir)) {
            throw new IOException("download folder not found: " + downloadDir);
        }
        Path candidate = newestCandidate(Long.MIN_VALUE);
        return (candidate != null && isStable(candidate)) ? candidate : null;
    }

    // newest regular file matching the prefix, born after sinceMillis, not partial.
    // newest wins, so a second export click simply supersedes the first.
    //
    // when nothing carries the prefix, the newest .csv shaped like an export
    // ("<label>|<lot>" lines) is taken instead, and its name learned: a new PC
    // starts with the default prefix, the portal names its file otherwise, and
    // the verification used to wait for a name that never came, export after
    // export, then give up.
    private Path newestCandidate(long sinceMillis) throws IOException {
        Path named = newestCandidate(sinceMillis, true);
        if (named != null || prefix.isEmpty()) return named;
        Path shaped = newestCandidate(sinceMillis, false);
        if (shaped != null) {
            Consumer<String> learn = prefixLearned;
            if (learn != null) learn.accept(baseName(shaped.getFileName().toString()));
        }
        return shaped;
    }

    private Path newestCandidate(long sinceMillis, boolean byName) throws IOException {
        Path best = null;
        long bestTime = sinceMillis;
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(downloadDir)) {
            for (Path p : dir) {
                String name = p.getFileName().toString().toLowerCase();
                if (isPartial(name)) continue;
                if (byName && !name.startsWith(prefix)) continue;
                if (!byName && (!name.endsWith(".csv") || name.startsWith("autofillsuite_"))) continue;
                try {
                    if (!Files.isRegularFile(p)) continue;
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    if (mtime > bestTime && (byName || looksLikeExport(p))) {
                        bestTime = mtime;
                        best = p;
                    }
                } catch (IOException gone) {
                    // renamed or deleted mid-scan (chrome does exactly this on
                    // completion): skip it, the next poll sees the final name
                }
            }
        }
        return best;
    }

    // after a timeout: the newest file that DID land after the click, whatever
    // its name and shape, so the message can name it instead of "nothing came"
    public String strangerSince(long sinceMillis) {
        String best = null;
        long bestTime = sinceMillis;
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(downloadDir)) {
            for (Path p : dir) {
                try {
                    if (!Files.isRegularFile(p)) continue;
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    if (mtime > bestTime) {
                        bestTime = mtime;
                        best = p.getFileName().toString();
                    }
                } catch (IOException gone) {
                    // renamed mid-scan
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return best;
    }

    // the export's shape: its first line is "<something>|<something>", one pipe
    static boolean looksLikeExport(Path file) {
        byte[] head = new byte[512];
        int n;
        try (java.io.InputStream in = Files.newInputStream(file)) {
            n = in.read(head);
        } catch (IOException gone) {
            return false;
        }
        if (n <= 0) return false;
        String text = new String(head, 0, n, StandardCharsets.ISO_8859_1);
        if (text.startsWith("\u00ef\u00bb\u00bf")) text = text.substring(3);   // utf-8 BOM
        int eol = text.indexOf('\n');
        String first = (eol < 0 ? text : text.substring(0, eol)).trim();
        int pipe = first.indexOf('|');
        return pipe > 0 && first.indexOf('|', pipe + 1) < 0;
    }

    // "Export (12).csv" -> "Export": the name the portal gives, before Chrome numbers it
    static String baseName(String fileName) {
        String b = fileName;
        int dot = b.lastIndexOf('.');
        if (dot > 0) b = b.substring(0, dot);
        return b.replaceAll("\\s*\\(\\d+\\)$", "").trim();
    }

    private static boolean isPartial(String lowerName) {
        for (String suffix : PARTIAL_SUFFIXES) {
            if (lowerName.endsWith(suffix)) return true;
        }
        return false;
    }

    // same non-zero size across two reads stableMs apart = the download is done.
    // covers servers that stream straight into the final name, not just chrome's
    // write-then-rename.
    private boolean isStable(Path file) throws InterruptedException {
        try {
            long first = Files.size(file);
            if (first == 0) return false;
            Thread.sleep(stableMs);
            return Files.size(file) == first;
        } catch (IOException gone) {
            return false;   // vanished between the two reads: not our finished file
        }
    }

    // ── reading ──────────────────────────────────────────────────────────────
    // utf-8 first; some windows tools export ANSI instead and utf-8 decoding then
    // explodes on the first accented byte. latin-1 maps every byte to a char, so
    // the retry cannot fail and the ascii labels survive either route intact.
    public static List<String> readLines(Path file) throws IOException {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException notUtf8) {
            return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        }
    }
}
