package app.core;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    private Path newestCandidate(long sinceMillis) throws IOException {
        Path best = null;
        long bestTime = sinceMillis;
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(downloadDir)) {
            for (Path p : dir) {
                String name = p.getFileName().toString().toLowerCase();
                if (!name.startsWith(prefix) || isPartial(name)) continue;
                try {
                    if (!Files.isRegularFile(p)) continue;
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    if (mtime > bestTime) {
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
