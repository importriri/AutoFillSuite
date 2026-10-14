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
        scanAfterAnotherModesRun_opensItsOwnSection();
        scanWhileAnotherModeWorks_isRefused();
        restore_keepsEveryPairOnDisk();
        sentNeverVerified_isNotForgotten();
        verifyByHand_duringABlock_waitsForItsEnd();

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

    // the scan rows pointed into whatever run the table held: after a range,
    // new scans were appended to the range's rows and its report section
    private static void scanAfterAnotherModesRun_opensItsOwnSection() throws Exception {
        final ResultsPanel[] rp = new ResultsPanel[1];
        final ScanModePanel[] sp = new ScanModePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            rp[0] = new ResultsPanel(() -> { });
            sp[0] = new ScanModePanel(rp[0], new RangeModeTest.NoopRunContext());
        });
        scan(sp[0], "LBL899", "LOT-S");          // the scan session is open
        clearQueue(sp[0]);                       // released, say: the queue is empty again
        SwingUtilities.invokeAndWait(() -> {
            rp[0].beginRun(1, "LOT-R");          // a range ran meanwhile
            rp[0].addLiveRow("LBL900", 1);
        });
        scan(sp[0], "LBL901", "LOT-S");
        check("a scan after a range opens a scan section: the range row goes to the archive",
              rows(rp[0]) == 1 && "LBL901".equals(rp[0].tableModel().codeAt(rp[0].tableModel().dayOffset())));
    }

    private static void scanWhileAnotherModeWorks_isRefused() throws Exception {
        final ResultsPanel[] rp = new ResultsPanel[1];
        final ScanModePanel[] sp = new ScanModePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            rp[0] = new ResultsPanel(() -> { });
            sp[0] = new ScanModePanel(rp[0], new RangeModeTest.NoopRunContext() {
                @Override public String busyElsewhere(Object self) { return "Da file al lavoro"; }
            });
        });
        scan(sp[0], "LBL902", "LOT-S");
        check("a scan while another mode works is refused, never queued", rows(rp[0]) == 0
              && queued(sp[0]) == 0 && sp[0].bannerText().contains("Da file"));
    }

    // Ripristina emptied the journal first, then queued the pairs again one by
    // one: a crash in between left them only in the renamed file, a failed
    // write dropped the rest, and the pairs "to verify" were never written to
    // the new journal, so a second crash before the check lost them for good
    private static void restore_keepsEveryPairOnDisk() throws Exception {
        File dir = Files.createTempDirectory("afs-restore").toFile();
        SettingsManager.getInstance().set(SettingsManager.REPORT_DIR, dir.getPath());
        app.core.ScanQueueStore left = app.core.ScanQueueStore.open(dir, java.time.Clock.systemDefaultZone());
        left.enqueue("QR-1", "LOT-1", "blocco");
        long cut = left.enqueue("QR-2", "LOT-2", "blocco").id;
        left.enqueue("QR-3", "LOT-3", "blocco");
        left.sending(cut);   // the PC went off at ENTER
        left.close();

        final ScanModePanel[] sp = new ScanModePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            sp[0] = new ScanModePanel(new ResultsPanel(() -> { }), new RangeModeTest.NoopRunContext());
            sp[0].restoreQueue();
        });
        check("Ripristina queues the two unsent pairs", queued(sp[0]) == 2);
        // a second crash right now: what does the disk say?
        app.core.ScanQueueStore disk = app.core.ScanQueueStore.open(dir, java.time.Clock.systemDefaultZone());
        check("after Ripristina the disk still has the two queued pairs", disk.pending().size() == 2);
        check("...and the pair to verify, still to verify", disk.unsure().size() == 1
              && disk.unsure().get(0).id == cut);
        disk.close();
    }

    // continuo, a check every 10: 7 pairs sent, then the app closed (no
    // question) or the PC went off. nothing remembered them: never verified,
    // and nothing at the next start said so
    private static void sentNeverVerified_isNotForgotten() throws Exception {
        File dir = Files.createTempDirectory("afs-unverified").toFile();
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.REPORT_DIR, dir.getPath());
        cfg.set(SettingsManager.SCAN_BATCH, false);
        cfg.set(SettingsManager.SCAN_VERIFY_AUTO, false);
        cfg.set(SettingsManager.SCAN_COORD_X, 5);
        cfg.set(SettingsManager.SCAN_COORD_Y, 5);
        cfg.set(SettingsManager.SCAN_FOCUS, 0);
        cfg.set(SettingsManager.SCAN_KEY, 0);
        cfg.set(SettingsManager.SCAN_ENTER, 0);
        cfg.save();
        final ScanModePanel[] sp = new ScanModePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            sp[0] = new ScanModePanel(new ResultsPanel(() -> { }), new RangeModeTest.NoopRunContext());
            sp[0].hands = new QuietHands();
        });
        scan(sp[0], "LBL501", "LOT-U");
        scan(sp[0], "LBL502", "LOT-U");
        long until = System.currentTimeMillis() + 5000;
        while (queued(sp[0]) > 0 && System.currentTimeMillis() < until) Thread.sleep(50);
        Thread.sleep(300);
        check("closing now says two pairs were never verified", sp[0].closeReason() != null
              && sp[0].closeReason().contains("2"));

        // the PC goes off: the next start
        final ScanModePanel[] next = new ScanModePanel[1];
        SwingUtilities.invokeAndWait(() ->
            next[0] = new ScanModePanel(new ResultsPanel(() -> { }), new RangeModeTest.NoopRunContext()));
        check("the next start offers the two sent pairs to verify",
              next[0].recoveryText() != null && next[0].recoveryText().contains("2 da verificare"));
        cfg.set(SettingsManager.SCAN_BATCH, true);
        cfg.save();
    }

    // Verifica pressed while a block was being released: the check's outcome
    // ended the block (HUD back to the cockpit, STOP gone) while the rest of
    // the block was still to be sent
    private static void verifyByHand_duringABlock_waitsForItsEnd() throws Exception {
        File dir = Files.createTempDirectory("afs-blockcheck").toFile();
        SettingsManager cfg = SettingsManager.getInstance();
        cfg.set(SettingsManager.REPORT_DIR, dir.getPath());
        cfg.set(SettingsManager.SCAN_BATCH, true);
        cfg.set(SettingsManager.SCAN_VERIFY_AUTO, false);
        cfg.set(SettingsManager.REG_EXPORT_COORD_X, 5);
        cfg.set(SettingsManager.REG_EXPORT_COORD_Y, 5);
        cfg.set(SettingsManager.REG_DOWNLOAD_DIR, dir.getPath());
        cfg.save();
        final int[] endedEarly = { 0 };
        final ScanModePanel[] sp = new ScanModePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            sp[0] = new ScanModePanel(new ResultsPanel(() -> { }), new RangeModeTest.NoopRunContext() {
                @Override public void jobFinished() {
                    try { if (queued(sp[0]) > 0) endedEarly[0]++; } catch (Exception ignored) { }
                }
            });
            sp[0].hands = new QuietHands() {
                @Override public void pressEnter() { try { Thread.sleep(400); } catch (InterruptedException e) { } }
            };
        });
        for (int i = 1; i <= 6; i++) scan(sp[0], "LBL6" + i, "LOT-B");
        SwingUtilities.invokeAndWait(() -> {
            for (JButton b : ResultsOwnershipTest.buttons(sp[0])) {
                if (b.getText() != null && b.getText().startsWith("REGISTRA TUTTO")) b.doClick();
            }
        });
        Thread.sleep(700);
        SwingUtilities.invokeAndWait(() -> {
            for (JButton b : ResultsOwnershipTest.buttons(sp[0])) {
                if ("Verifica".equals(b.getText())) b.doClick();
            }
        });
        long until = System.currentTimeMillis() + 15000;
        while (queued(sp[0]) > 0 && System.currentTimeMillis() < until) Thread.sleep(100);
        Thread.sleep(500);
        check("a check by hand never ends a block that still has pairs to send", endedEarly[0] == 0);
        check("the block went out whole", queued(sp[0]) == 0);
    }

    /** Hands that type nothing: the pair counts as sent at once. */
    static class QuietHands implements app.core.PairBurst.Hands {
        @Override public void doubleClick(int x, int y) { }
        @Override public void click(int x, int y) { }
        @Override public void pasteText(String text) { }
        @Override public void pressTab() { }
        @Override public void pressEnter() { }
        @Override public void sleep(int ms) { }
        @Override public boolean isMouseMoved(Point target) { return false; }
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

    static void clearQueue(ScanModePanel p) throws Exception {
        java.lang.reflect.Field f = ScanModePanel.class.getDeclaredField("queue");
        f.setAccessible(true);
        ((java.util.Collection<?>) f.get(p)).clear();
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
