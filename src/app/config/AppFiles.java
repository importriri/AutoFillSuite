package app.config;

import java.io.File;

/**
 * Every file and folder the app writes or reads by default, in one place.
 * The same names used to be spelled in a dozen places across six classes: one
 * typo, or one default changed in one place only, and two modes would write to
 * two different files without anybody noticing.
 */
public final class AppFiles {

    public static final String VERIFICATION_LOG = "AutoFillSuite_verifiche.txt";
    public static final String IMPORT_LEDGER    = "AutoFillSuite_importazioni.txt";
    public static final String QUEUE_JOURNAL    = "AutoFillSuite_coda.giornale";
    public static final String IMPORTS_DIR      = "importazioni";

    private AppFiles() {}

    public static File home() {
        return new File(System.getProperty("user.home"));
    }

    /** The verification log: always in the user's home. */
    public static File verificationLog() {
        return new File(home(), VERIFICATION_LOG);
    }

    /** Where the browser saves downloads when nothing else is configured. */
    public static File defaultDownloads() {
        return new File(home(), "Downloads").getAbsoluteFile();
    }

    /** The configured download folder (the portal's export lands here). */
    public static File downloadDir() {
        return new File(SettingsManager.getInstance().get(SettingsManager.REG_DOWNLOAD_DIR,
                                                          defaultDownloads().getPath()));
    }

    /** The report folder: daily reports, the import register, the queue journal. */
    public static File reportDir() {
        return new File(SettingsManager.getInstance().get(SettingsManager.REPORT_DIR, home().getPath()));
    }

    /** The day's report, yyyy-MM-dd. */
    public static File dailyReport(String date) {
        return new File(reportDir(), "AutoFillSuite_report_" + date + ".csv");
    }

    /** The holes in the day's numbering, yyyy-MM-dd: rewritten whole at every change. */
    public static File dailyGaps(String date) {
        return new File(reportDir(), "AutoFillSuite_buchi_" + date + ".csv");
    }

    /** The Cancella tab's list of labels still to delete: a closed app finds it again. */
    public static File deleteList() {
        return new File(reportDir(), "AutoFillSuite_cancella_lista.txt");
    }

    /** AutoFillSuite's own copies of the imported Registro QR files. */
    public static File importsDir(File reportDir) {
        return new File(reportDir, IMPORTS_DIR);
    }
}
