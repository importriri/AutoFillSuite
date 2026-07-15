package app.config;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Singleton that loads and saves app settings to a .properties file.
 * Settings are auto-saved on app close.
 */
public class SettingsManager {

    private static SettingsManager instance;
    private final Properties props = new Properties();
    private final File file;

    private SettingsManager() {
        file = new File(System.getProperty("user.home"), ".autofill_suite.properties");
        load();
    }

    public static SettingsManager getInstance() {
        if (instance == null) instance = new SettingsManager();
        return instance;
    }

    public String get(String key, String def) {
        return props.getProperty(key, def);
    }

    public int getInt(String key, int def) {
        try { return Integer.parseInt(props.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public double getDouble(String key, double def) {
        try { return Double.parseDouble(props.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(props.getProperty(key, String.valueOf(def)));
    }

    public void set(String key, Object value) {
        props.setProperty(key, String.valueOf(value));
    }

    public void save() {
        // atomic: write a sibling temp file, then rename over the target. A
        // crash mid-write can no longer leave a half-written settings file —
        // the old file survives intact until the rename lands whole.
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                props.store(fos, "AutoFill Suite - settings");
            }
            try {
                Files.move(tmp.toPath(), file.toPath(),
                           StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                // some filesystems cannot do atomic renames: plain replace
                Files.move(tmp.toPath(), file.toPath(),
                           StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[SettingsManager] Cannot save: " + e.getMessage());
            if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit();
        }
    }

    private void load() {
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            } catch (IOException e) {
                System.err.println("[SettingsManager] Cannot load: " + e.getMessage());
            }
        }
    }

    // Tab 1 — Batch Registration
    public static final String REG_COUNT        = "reg.count";
    public static final String REG_WAIT         = "reg.wait";
    public static final String REG_FIELD_DELAY  = "reg.fieldDelay";
    public static final String REG_POST_ENTER   = "reg.postEnter";
    public static final String REG_FIXED_DELAY  = "reg.fixedDelay";
    public static final String REG_MEMO_WAIT    = "reg.memoWait";
    public static final String REG_COORD_X      = "reg.coordX";
    public static final String REG_COORD_Y      = "reg.coordY";

    // Tab 1 — Post-run verification
    public static final String REG_EXPORT_COORD_X   = "reg.exportCoordX";
    public static final String REG_EXPORT_COORD_Y   = "reg.exportCoordY";
    public static final String REG_VERIFY_AUTO      = "reg.verifyAuto";
    public static final String SCAN_VERIFY_AUTO     = "scan.verifyAuto";
    public static final String REPORT_DIR       = "report.dir";
    public static final String REG_DOWNLOAD_DIR     = "reg.downloadDir";
    public static final String REG_EXPORT_PREFIX    = "reg.exportPrefix";
    public static final String REG_EXPORT_TIMEOUT_S = "reg.exportTimeoutS";
    public static final String REG_EXPORT_POLL_MS   = "reg.exportPollMs";
    public static final String REG_EXPORT_STABLE_MS = "reg.exportStableMs";
    public static final String REG_VERIFY_RETRIES   = "reg.verifyRetries";
    public static final String REG_VERIFY_RETRY_S   = "reg.verifyRetryS";

    // Appearance
    public static final String UI_FLAVOR = "ui.flavor";   // mocha | latte
    public static final String UI_RESULTS_OPEN = "ui.resultsOpen";
    public static final String UI_RESULTS_DAY  = "ui.resultsDay";
    public static final String UI_WIN_X        = "ui.winX";
    public static final String UI_WIN_Y        = "ui.winY";
    public static final String UI_HUD          = "ui.hud";        // start collapsed to the bar
    public static final String UI_HUD_AUTO     = "ui.hudAuto";    // collapse while the robot works

    // Collision guard: the app is always-on-top, so it can cover its own targets
    public static final String GUARD_ENABLED   = "guard.enabled";
    public static final String GUARD_AUTOMOVE  = "guard.autoMove";

    // Crash recovery: what the last run was writing when the app went away
    public static final String RUN_PENDING     = "run.pending";
    public static final String RUN_PREFIX      = "run.prefix";
    public static final String RUN_SEQ0        = "run.seq0";
    public static final String RUN_COUNT       = "run.count";
    public static final String RUN_LOT         = "run.lot";

    // Tab 2 — Auto Print
    public static final String PRINT_COUNT      = "print.count";
    public static final String PRINT_PAUSE      = "print.pause";
    public static final String PRINT_WAIT       = "print.wait";
    public static final String PRINT_MEMO_WAIT  = "print.memoWait";
    public static final String PRINT_COORD_X    = "print.coordX";
    public static final String PRINT_COORD_Y    = "print.coordY";

    // Tab 3 — Dual QR Scan
    public static final String SCAN_MEMO_WAIT   = "scan.memoWait";
    public static final String SCAN_FOCUS       = "scan.focus";
    public static final String SCAN_KEY         = "scan.key";
    public static final String SCAN_VERIFY_EVERY = "scan.verifyEvery";
    public static final String SCAN_BATCH        = "scan.batchMode";
    public static final String SCAN_ENTER       = "scan.enter";
    public static final String SCAN_COORD_X     = "scan.coordX";
    public static final String SCAN_COORD_Y     = "scan.coordY";
}
