package app.config;

import java.io.*;
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

    public void set(String key, Object value) {
        props.setProperty(key, String.valueOf(value));
    }

    public void save() {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, "AutoFill Suite - settings");
        } catch (IOException e) {
            System.err.println("[SettingsManager] Cannot save: " + e.getMessage());
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
    public static final String REG_DELAY_TYPE   = "reg.delayType";
    public static final String REG_FIXED_DELAY  = "reg.fixedDelay";
    public static final String REG_MIN_DELAY    = "reg.minDelay";
    public static final String REG_MAX_DELAY    = "reg.maxDelay";
    public static final String REG_MEMO_WAIT    = "reg.memoWait";
    public static final String REG_COORD_X      = "reg.coordX";
    public static final String REG_COORD_Y      = "reg.coordY";

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
    public static final String SCAN_ENTER       = "scan.enter";
    public static final String SCAN_COORD_X     = "scan.coordX";
    public static final String SCAN_COORD_Y     = "scan.coordY";
}
