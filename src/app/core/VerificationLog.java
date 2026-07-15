package app.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * One stamped block per verification, appended to a plain txt file.
 * Best effort: a logging hiccup must never break a verification.
 */
public final class VerificationLog {

    private final Path file;

    public VerificationLog(Path file) {
        this.file = file;
    }

    /** Returns true when the write went through — the caller may show a hint otherwise. */
    public boolean append(String block) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String entry = stamp + "  " + block.trim() + System.lineSeparator();
        try {
            Files.write(file, entry.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            System.err.println("[VerificationLog] cannot write: " + e.getMessage());
            return false;
        }
    }

    public Path getFile() {
        return file;
    }

    // ── shared entry format: Register and Dual Scan write the same shape ─────

    public static String formatEntry(VerificationResult r, String fileName,
                                     int attempts, int total, String context,
                                     boolean fresh) {
        StringBuilder sb = new StringBuilder();
        if (r.isClean()) {
            sb.append("OK ").append(r.getMatched()).append('/').append(total);
        } else {
            sb.append("PROBLEMI ").append(r.totalProblems()).append('/').append(total)
              .append(" · mancanti=").append(r.getMissing().size())
              .append(" nonReg=").append(r.getNotRegistered().size())
              .append(" lottoErr=").append(r.getWrongLot().size())
              .append(" doppie=").append(r.getDuplicates().size());
        }
        sb.append(" · ").append(context)
          .append(" · file=").append(fileName);
        if (!fresh) sb.append(" (non nuovo)");
        sb.append(" · tentativi=").append(attempts);
        if (r.totalRegistrations() > r.getMatched()) {
            sb.append(" · registrazioni=").append(r.totalRegistrations());
        }
        if (!r.isClean()) {
            appendList(sb, "mancanti",        r.getMissing());
            appendList(sb, "non registrate",  r.getNotRegistered());
            appendList(sb, "lotto sbagliato", r.getWrongLot());
            appendList(sb, "doppie",          r.getDuplicates());
        }
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, String title, List<String> items) {
        for (String s : items) {
            sb.append("\n  ").append(title).append(": ").append(s);
        }
    }
}
