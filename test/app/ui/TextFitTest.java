package app.ui;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

// text fitting — born on the shop floor: the status bar centered its string,
// so "Da 01_2612900022 -> 01_2612900031" overflowed BOTH sides and the last
// digits — the only ones that say where the range ENDS — were unreadable.
// two rules live here: an overflowing text keeps its TAIL, and the preview
// collapses the shared prefix so it fits by construction.
//
//   java -cp build app.ui.TextFitTest
public final class TextFitTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        fitTail_keepsTheTail();
        rangePreview_collapsesTheSharedPrefix();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static FontMetrics metrics() {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setFont(AppTheme.F_MONO);
        FontMetrics fm = g.getFontMetrics();
        g.dispose();
        return fm;
    }

    private static void fitTail_keepsTheTail() {
        FontMetrics fm = metrics();
        String s = "Da 01_2612900022 -> 01_2612900031";

        check("a string that fits passes through untouched",
              AppTheme.fitTail(s, fm, fm.stringWidth(s) + 5).equals(s));

        String cut = AppTheme.fitTail(s, fm, fm.stringWidth(s) / 2);
        check("an overflowing string keeps its TAIL", cut.endsWith("0031"));
        check("the cut is announced at the head", cut.startsWith("..."));
        check("the fitted string respects the budget",
              fm.stringWidth(cut) <= fm.stringWidth(s) / 2);
        check("even a tiny budget still shows the last character",
              AppTheme.fitTail(s, fm, 8).endsWith("1"));
    }

    private static void rangePreview_collapsesTheSharedPrefix() {
        // the real shape from the floor: 13-char serials, shared head
        String p = RangeModePanel.rangePreview("01_2612900022", "01_2612900031");
        check("the first code stays whole", p.contains("01_2612900022"));
        check("the second collapses to its tail", p.endsWith("...0031"));
        check("a single-label range names it once",
              RangeModePanel.rangePreview("X1", "X1").equals("Da X1"));
        check("codes with nothing in common stay full",
              RangeModePanel.rangePreview("AAA1", "BBB2").equals("Da AAA1 -> BBB2"));
        check("the tail keeps at least the last 4 characters",
              RangeModePanel.rangePreview("SN000001", "SN000002").endsWith("...0002"));
    }

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }
}
