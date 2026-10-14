package app.ui;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

// the results panel is shared by every mode, and its retry, cancel and lot
// correction were set by whichever mode verified LAST. a range "Verifica"
// rebuilt the table in place: the rows of a scan or file session vanished
// from the table and the day archive, and their report section was written
// over with the range codes. a range stopped at 5 of 10 and verified by hand
// lost the send times of the five rows it did send.
//
//   xvfb-run java -cp out app.ui.ResultsOwnershipTest
public final class ResultsOwnershipTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        File home = Files.createTempDirectory("afs-owner").toFile();
        System.setProperty("user.home", home.getPath());

        final ResultsPanel[] rp = new ResultsPanel[1];
        SwingUtilities.invokeAndWait(() -> rp[0] = new ResultsPanel(() -> { }));
        final ResultsPanel r = rp[0];
        final List<String> range = Arrays.asList("LBL001", "LBL002", "LBL003");

        // a scan session with two pairs sent, then a range check by hand
        SwingUtilities.invokeAndWait(() -> {
            r.beginSession();
            r.markSent(r.addQueuedPair("QR-A", "LOT-A"));
            r.markSent(r.addQueuedPair("QR-B", "LOT-B"));
            r.ensureRows(range, "LOT-R");
        });
        check("a range check never rebuilds a scan session's rows: they go to the day archive",
              r.tableModel().dayArchivedCount() == 2 && r.tableModel().getRowCount() == 3);
        check("the scan pairs are still known as sent today",
              r.registeredToday().containsKey("QR-A") && r.registeredToday().containsKey("QR-B"));

        // a range stopped after 2 of 3, verified by hand: the two keep their send time
        SwingUtilities.invokeAndWait(() -> {
            r.beginRun(3, "LOT-S");
            r.addLiveRow("LBL101", 1);
            r.addLiveRow("LBL102", 2);
            r.ensureRows(Arrays.asList("LBL101", "LBL102", "LBL103"), "LOT-S");
        });
        check("a range verified by hand keeps the send times of the rows it sent",
              r.registeredToday().containsKey("LBL101") && r.registeredToday().containsKey("LBL102")
              && !r.registeredToday().containsKey("LBL103"));
        check("...and lists the label it never reached", r.tableModel().getRowCount() == 3);

        // a correction set by the last verifier never acts on another mode's rows
        final int[] calls = { 0 };
        SwingUtilities.invokeAndWait(() -> {
            r.setLotFixer((row, code, oldLot, newLot) -> { calls[0]++; return true; });
            r.setActions(() -> calls[0]++, () -> calls[0]++);
            r.beginSession();
            r.markSent(r.addQueuedPair("QR-C", "LOT-C"));
            r.applyFix(0, "LOT-X");
        });
        check("a new session drops the previous mode's correction", calls[0] == 0
              && "LOT-C".equals(r.tableModel().lotAt(0)));
        SwingUtilities.invokeAndWait(() -> {
            for (JButton b : buttons(r)) if (b.getText().contains("RIPROVA") || b.getText().contains("ANNULLA")) b.doClick();
        });
        check("...and its retry and cancel", calls[0] == 0);

        // at start: a range never verified, and a file left half way. the file
        // was loaded after the range offer and hid its VERIFICA ORA / Ignora
        SwingUtilities.invokeAndWait(() -> {
            r.offerPendingVerification(10, () -> { }, () -> { });
            r.beginFileSession("registro_L2_2026-10-13_01.csv");
        });
        boolean offered = false;
        for (JButton b : buttons(r)) if ("VERIFICA ORA".equals(b.getText()) && b.isVisible()) offered = true;
        check("a file reloaded at start keeps the offer to verify the last range", offered);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    static List<JButton> buttons(Container root) {
        List<JButton> out = new java.util.ArrayList<>();
        for (Component c : root.getComponents()) {
            if (c instanceof JButton) out.add((JButton) c);
            if (c instanceof Container) out.addAll(buttons((Container) c));
        }
        return out;
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
