package app.ui;

import app.config.SettingsManager;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The workstation, ready at the first start of the day: the portal open in
 * the browser and the print program (the proxy the portal sends its labels
 * to, on localhost) running. An operator who uses the PC twice a year clicks
 * the app and finds everything where it should be.
 *
 * The print program is started WITHOUT a window, its output into a log file
 * next to the report: a console window can be clicked into "Seleziona" mode,
 * and that freezes the program at its next log line — the portal then reports
 * a printer error with a printer that is fine. It is started only when nothing
 * answers on its port, so a program already open is never doubled.
 */
final class Station {

    /** Opens a web address; a test records it instead. */
    interface Browser { void open(String url) throws Exception; }
    /** Starts a program; a test records it instead. */
    interface Launcher { void start(List<String> command, File dir, File log) throws IOException; }
    /** Whether something answers on a local port; a test answers. */
    interface Probe { boolean alive(int port); }

    static Browser browser = url -> Desktop.getDesktop().browse(new URI(url));
    static Launcher launcher = (command, dir, log) -> {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (dir != null) pb.directory(dir);
        // never a pipe nobody reads: a full pipe blocks the program's next log line
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        pb.start();
    };
    static Probe probe = Station::portAnswers;
    /** Today, as the station sees it; a test moves it. */
    static java.util.function.Supplier<String> today = () -> java.time.LocalDate.now().toString();

    private Station() {}

    /**
     * Prepare the workstation. {@code force} = the button in the settings;
     * otherwise only at the first start of the day, and only when switched on.
     * @return what happened, in the operator's words; null = nothing to do
     */
    static String prepare(boolean force) {
        SettingsManager cfg = SettingsManager.getInstance();
        String day = today.get();
        if (!force) {
            if (!cfg.getBool(SettingsManager.STATION_AUTO, true)) return null;
            if (day.equals(cfg.get(SettingsManager.STATION_LAST_DAY, ""))) return null;
            String url = cfg.get(SettingsManager.STATION_PORTAL_URL, "").trim();
            String exe = cfg.get(SettingsManager.STATION_PROXY_PATH, "").trim();
            if (url.isEmpty() && exe.isEmpty()) return null;   // never configured: stay quiet
        }
        cfg.set(SettingsManager.STATION_LAST_DAY, day);
        cfg.save();
        List<String> said = new ArrayList<>();
        said.add(openPortal(cfg));
        said.add(startPrinter(cfg));
        return String.join("\n", said);
    }

    private static String openPortal(SettingsManager cfg) {
        String url = cfg.get(SettingsManager.STATION_PORTAL_URL, "").trim();
        if (url.isEmpty()) return "Portale: indirizzo non impostato (Impostazioni > Giornata)";
        if (!url.contains("://")) url = "https://" + url;
        try {
            browser.open(url);
            return "Portale aperto nel browser";
        } catch (Exception e) {
            return "Portale NON aperto: " + e.getMessage();
        }
    }

    private static String startPrinter(SettingsManager cfg) {
        int port = cfg.getInt(SettingsManager.STATION_PROXY_PORT, 8889);
        if (probe.alive(port)) return "Programma stampa già acceso";
        String path = cfg.get(SettingsManager.STATION_PROXY_PATH, "").trim();
        if (path.isEmpty()) return "Programma stampa: non impostato (Impostazioni > Giornata)";
        File exe = new File(path);
        if (!exe.isFile()) return "Programma stampa NON trovato: " + path;
        File log = new File(app.config.AppFiles.reportDir(), "AutoFillSuite_stampa_log.txt");
        try {
            launcher.start(command(exe), exe.getAbsoluteFile().getParentFile(), log);
            return path.toLowerCase().endsWith(".lnk")
                ? "Programma stampa aperto dal collegamento (meglio scegliere il file .exe)"
                : "Programma stampa avviato (senza finestra; log: " + log.getName() + ")";
        } catch (IOException e) {
            return "Programma stampa NON avviato: " + e.getMessage();
        }
    }

    /**
     * How the program is started: the .exe itself, from its own folder (its
     * settings live next to it). A desktop shortcut cannot be run directly:
     * Explorer opens it, with its window.
     */
    static List<String> command(File exe) {
        List<String> c = new ArrayList<>();
        if (exe.getName().toLowerCase().endsWith(".lnk")) {
            c.add("explorer.exe");
        }
        c.add(exe.getAbsolutePath());
        return c;
    }

    /** Something answers on localhost:port (the print program is up). */
    static boolean portAnswers(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
