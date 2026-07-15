package app.core;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Runs the whole post-run check off the EDT: click export, wait for the file,
 * hand the lines to the injected checker, retry when something is off.
 * A retry re-clicks the export: a slow server that has not flushed the last
 * rows yet must never produce a false red.
 *
 * The diff itself is injected as a Function, so the same task serves both the
 * classic run (one lot for all labels) and the dual-scan session (one lot per
 * label) — and the tests can plug in whatever they need.
 *
 * Two modes:
 *  - automatic (fallbackToExisting = false): full timeout, retries re-export
 *  - manual    (fallbackToExisting = true):  short wait, then falls back to the
 *    newest export already in the folder, flagged as not fresh
 *
 * cancel() interrupts the worker between steps — the operator is never stuck.
 */
public final class VerificationTask implements Runnable {

    /** Outcome callbacks, all delivered on the EDT like the rest of the app. */
    public interface Listener {
        void onStatus(String message);
        void onOutcome(VerificationResult result, Path exportFile,
                       int attempts, boolean freshExport);
        void onFailure(String reason);
        void onCancelled();
    }

    private final Runnable exportClick;
    private final DownloadWatcher watcher;
    private final Function<List<String>, VerificationResult> checker;
    private final int extraRetries;      // re-export attempts after the first
    private final int retryDelayMs;
    private final boolean fallbackToExisting;
    private final Listener listener;

    private volatile boolean cancelled = false;
    private volatile Thread worker = null;

    public VerificationTask(Runnable exportClick, DownloadWatcher watcher,
                            Function<List<String>, VerificationResult> checker,
                            int extraRetries, int retryDelayMs,
                            boolean fallbackToExisting, Listener listener) {
        this.exportClick        = exportClick;
        this.watcher            = watcher;
        this.checker            = checker;
        this.extraRetries       = extraRetries;
        this.retryDelayMs       = retryDelayMs;
        this.fallbackToExisting = fallbackToExisting;
        this.listener           = listener;
    }

    /** Interrupts the running verification; the listener gets onCancelled(). */
    public void cancel() {
        cancelled = true;
        Thread w = worker;
        if (w != null) w.interrupt();
    }

    @Override
    public void run() {
        worker = Thread.currentThread();
        try {
            int attempt = 0;
            while (true) {
                attempt++;
                status("Esporto il CSV (tentativo " + attempt + ")...");
                long t0 = System.currentTimeMillis();
                exportClick.run();

                status("Attendo il file...");
                Path file = watcher.awaitNewExport(t0);
                boolean fresh = (file != null);

                if (file == null && fallbackToExisting) {
                    status("Nessun file nuovo: controllo l'ultimo export...");
                    file = watcher.newestExisting();
                }
                if (file == null) {
                    fail(fallbackToExisting
                        ? "Nessun export trovato nella cartella download."
                        : "Export non arrivato entro il timeout. Coordinata Export giusta? "
                        + "Sito lento? Alza il timeout nelle Impostazioni.");
                    return;
                }

                status("Verifico " + file.getFileName() + "...");
                List<String> lines = DownloadWatcher.readLines(file);
                VerificationResult result = checker.apply(lines);

                // done when clean, or when the retry budget is spent
                if (result.isClean() || attempt > extraRetries) {
                    final VerificationResult r = result;
                    final Path f = file;
                    final int used = attempt;
                    final boolean fr = fresh;
                    SwingUtilities.invokeLater(() -> listener.onOutcome(r, f, used, fr));
                    return;
                }
                status(result.totalProblems() + " problemi, riprovo tra "
                     + (retryDelayMs / 1000) + "s...");
                Thread.sleep(retryDelayMs);
            }
        } catch (IOException e) {
            fail("Verifica fallita: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (cancelled) {
                SwingUtilities.invokeLater(listener::onCancelled);
            } else {
                fail("Verifica interrotta.");
            }
        }
    }

    private void status(String msg) {
        SwingUtilities.invokeLater(() -> listener.onStatus(msg));
    }

    private void fail(String reason) {
        SwingUtilities.invokeLater(() -> listener.onFailure(reason));
    }
}
