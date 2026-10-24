package app.ui;

import app.config.SettingsManager;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

// the first start of the day opens the portal and the print program, so an
// operator who uses the PC twice a year finds the station ready. once a day,
// never twice the program, and never a pipe that fills up and freezes it.
//
//   java -cp out app.ui.StationTest
public final class StationTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        File home = Files.createTempDirectory("afs-station").toFile();
        System.setProperty("user.home", home.getPath());
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.REPORT_DIR, home.getPath());
        cfg.save();

        final List<String> opened = new ArrayList<>();
        final List<List<String>> started = new ArrayList<>();
        final List<File> dirs = new ArrayList<>();
        final List<File> logs = new ArrayList<>();
        final boolean[] up = { false };
        final String[] day = { "2026-10-24" };
        Station.browser = opened::add;
        Station.launcher = (c, d, l) -> { started.add(c); dirs.add(d); logs.add(l); };
        Station.probe = port -> up[0];
        Station.today = () -> day[0];

        check("never configured: the first start stays quiet", Station.prepare(false) == null
              && opened.isEmpty() && started.isEmpty());

        File proxyDir = new File(home, "PrintProxy8889");
        proxyDir.mkdirs();
        File exe = new File(proxyDir, "Alpitronic.ZebraPrinter.Proxy.App.exe");
        Files.write(exe.toPath(), new byte[] { 1 });
        cfg.set(SettingsManager.STATION_PORTAL_URL, "lifecycle.example.test/supplier/tca");
        cfg.set(SettingsManager.STATION_PROXY_PATH, exe.getPath());
        cfg.save();

        String said = Station.prepare(false);
        check("the first start of the day opens the portal", opened.size() == 1);
        check("an address without a scheme gets https", "https://lifecycle.example.test/supplier/tca".equals(opened.get(0)));
        check("and starts the print program", started.size() == 1
              && started.get(0).get(0).equals(exe.getAbsolutePath()));
        check("from its own folder (its settings live next to it)", proxyDir.getAbsoluteFile().equals(dirs.get(0)));
        check("its output goes to a log file next to the report",
              logs.get(0).getName().equals("AutoFillSuite_stampa_log.txt"));
        check("and says so", said != null && said.contains("Portale aperto") && said.contains("avviato"));

        check("the second start of the same day does nothing", Station.prepare(false) == null && opened.size() == 1);

        day[0] = "2026-10-25";
        up[0] = true;
        said = Station.prepare(false);
        check("the next day opens the portal again", opened.size() == 2);
        check("a print program already answering is never started twice",
              started.size() == 1 && said.contains("già acceso"));

        up[0] = false;
        said = Station.prepare(true);
        check("the button works any time", opened.size() == 3 && started.size() == 2);

        cfg.set(SettingsManager.STATION_PROXY_PATH, new File(proxyDir, "missing.exe").getPath());
        cfg.save();
        said = Station.prepare(true);
        check("a missing program is said plainly", said.contains("NON trovato"));

        cfg.set(SettingsManager.STATION_AUTO, false);
        cfg.save();
        day[0] = "2026-10-26";
        check("switched off: nothing at the start", Station.prepare(false) == null);

        cfg.set(SettingsManager.STATION_AUTO, true);
        cfg.set(SettingsManager.STATION_PROXY_PATH, exe.getPath());
        File chromeDir = new File(home, "Chrome/Application");
        chromeDir.mkdirs();
        File chrome = new File(chromeDir, "chrome.exe");
        Files.write(chrome.toPath(), new byte[] { 1 });
        cfg.set(SettingsManager.STATION_BROWSER, chrome.getPath());
        cfg.save();
        int before = opened.size(), launched = started.size();
        said = Station.prepare(true);
        check("a chosen browser opens the portal, not the default one", opened.size() == before
              && started.size() == launched + 2
              && started.get(launched).get(0).equals(chrome.getAbsolutePath())
              && started.get(launched).get(1).equals("https://lifecycle.example.test/supplier/tca"));
        check("and says which", said.contains("Portale aperto in chrome.exe"));

        cfg.set(SettingsManager.STATION_BROWSER, new File(chromeDir, "gone.exe").getPath());
        cfg.save();
        said = Station.prepare(true);
        check("a browser that is gone falls back to the default one, and says so",
              opened.size() == before + 1 && said.contains("predefinito") && said.contains("non trovato"));

        cfg.set(SettingsManager.STATION_BROWSER, "");
        cfg.save();

        List<String> lnk = Station.command(new File("C:/Desktop/Stampa - collegamento.lnk"));
        check("a shortcut is opened through Explorer", "explorer.exe".equals(lnk.get(0)) && lnk.size() == 2);

        check("nothing answers on a closed port", !Station.portAnswers(1));

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
