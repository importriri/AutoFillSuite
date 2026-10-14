package app.ui;

import app.config.SettingsManager;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

// the scan session's edges: what a click on "Nuova sessione" throws away.
//
//   xvfb-run java -cp out app.ui.ScanSessionTest
public final class ScanSessionTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        File home = Files.createTempDirectory("afs-session").toFile();
        System.setProperty("user.home", home.getPath());
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.SCAN_BATCH, true);   // A blocco: pairs wait for REGISTRA TUTTO
        cfg.save();

        newSession_withQueuedPairs_asksFirst();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    // one click on "Nuova sessione", next to Verifica, emptied a queue of 40
    // pairs collected in A blocco: gone from the table, the queue and the
    // recovery at the next start (the journal was renamed and never offered)
    private static void newSession_withQueuedPairs_asksFirst() throws Exception {
        final ResultsPanel[] rp = new ResultsPanel[1];
        final ScanModePanel[] sp = new ScanModePanel[1];
        final List<String> asked = new ArrayList<>();
        final boolean[] answer = { false };
        SwingUtilities.invokeAndWait(() -> {
            rp[0] = new ResultsPanel(() -> { });
            sp[0] = new ScanModePanel(rp[0], new RangeModeTest.NoopRunContext());
            sp[0].sessionConfirmer = m -> { asked.add(m); return answer[0]; };
        });
        scan(sp[0], "LBL001", "LOT-A");
        scan(sp[0], "LBL002", "LOT-B");
        check("two pairs wait in the queue", rows(rp[0]) == 2);

        clickNewSession(sp[0]);
        check("Nuova sessione with pairs in the queue asks first", asked.size() == 1
              && asked.get(0).contains("2"));
        check("refused: the queue and its rows stay", rows(rp[0]) == 2 && queued(sp[0]) == 2);

        answer[0] = true;
        clickNewSession(sp[0]);
        check("confirmed: a new, empty session", rows(rp[0]) == 0 && queued(sp[0]) == 0);

        asked.clear();
        clickNewSession(sp[0]);
        check("an empty session starts again without a question", asked.isEmpty());
    }

    static void scan(ScanModePanel p, String q1, String q2) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<JTextField> f = RangeModeTest.fields(p);
            f.get(0).setText(q1);
            f.get(1).setText(q2);
            f.get(1).postActionEvent();
        });
        Thread.sleep(100);
    }

    static void clickNewSession(ScanModePanel p) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (JButton b : ResultsOwnershipTest.buttons(p)) {
                if ("Nuova sessione".equals(b.getText())) b.doClick();
            }
        });
    }

    static int rows(ResultsPanel r) throws Exception {
        final int[] n = new int[1];
        SwingUtilities.invokeAndWait(() -> n[0] = r.tableModel().getRowCount());
        return n[0];
    }

    static int queued(ScanModePanel p) throws Exception {
        java.lang.reflect.Field f = ScanModePanel.class.getDeclaredField("queue");
        f.setAccessible(true);
        return ((java.util.Collection<?>) f.get(p)).size();
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
