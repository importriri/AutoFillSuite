package app.core;

import app.config.SettingsManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

// Toolkit.beep() is one faint system sound: on the shop floor nobody hears it
// and it cannot tell "done" from "stop". the tones of Registro QR instead:
// one short high tone = ok, two low ones = error, generated in memory; no
// sound card means silence, never an exception. the beep stays only as the
// fallback inside Sounds.
//
//   java -cp out app.core.SoundsTest
public final class SoundsTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("afs-sound").toString());

        byte[] ok = Sounds.tone(Sounds.OK, 80);
        byte[] err = Sounds.tone(Sounds.ERROR, 80);
        check("ok is one short tone (90 ms)", ok.length == 2 * (22050 * 90 / 1000));
        check("error is longer: two tones and a gap", err.length > ok.length * 3);
        int mid = err.length / 2 & ~1;
        check("the two error tones are separated by silence", err[mid] == 0 && err[mid + 1] == 0);
        check("ok is higher than error (more zero crossings per ms)",
              crossings(ok) * 1.0 / ok.length > crossings(err) * 1.0 / err.length * 2);
        byte[] mute = Sounds.tone(Sounds.OK, 0);
        boolean silent = true;
        for (byte b : mute) silent &= b == 0;
        check("volume 0 is silence", silent);

        SettingsManager.getInstance().set(SettingsManager.UI_SOUND_ON, false);
        boolean threw = false;
        try { Sounds.ok(); Sounds.error(); } catch (Throwable t) { threw = true; }
        check("sounds switched off never throw", !threw);
        SettingsManager.getInstance().set(SettingsManager.UI_SOUND_ON, true);
        try { Sounds.ok(); Sounds.error(); Thread.sleep(600); } catch (Throwable t) { threw = true; }
        check("no sound card here: silence, never an exception", !threw);

        check("no bare Toolkit beep is left outside Sounds", bareBeeps() == 0);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static int crossings(byte[] pcm) {
        int n = 0;
        short prev = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            if ((s >= 0) != (prev >= 0)) n++;
            prev = s;
        }
        return n;
    }

    static int bareBeeps() throws Exception {
        Path src = Paths.get("src");
        if (!Files.isDirectory(src)) return 0;
        int n = 0;
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (f.getFileName().toString().equals("Sounds.java")) continue;
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (String l : lines) {
                    if (l.contains("beep()") && !l.trim().startsWith("//") && !l.trim().startsWith("*")) {
                        System.out.println("       beep in " + f.getFileName() + ": " + l.trim());
                        n++;
                    }
                }
            }
        }
        return n;
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }

}
