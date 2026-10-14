package app.core;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

// the one sequence every mode uses to type a pair into the portal, with a
// fake pair of hands: what the robot does, in which order, and the point of
// no return. a mouse taken back BEFORE enter means nothing was registered (the
// pair goes back to the queue); AFTER enter the pair IS registered — it counts,
// and only then does the robot stop.
//
//   java -cp out app.core.PairBurstTest
public final class PairBurstTest {

    private static int passed = 0;
    private static int failed = 0;

    /** Records every step; the mouse "moves" at step {@code grabAt} (0 = never). */
    static final class FakeHands implements PairBurst.Hands {
        final List<String> steps = new ArrayList<>();
        int grabAt;
        boolean failOnPaste;
        int checks;

        @Override public void doubleClick(int x, int y) { steps.add("dbl " + x + "," + y); }
        @Override public void click(int x, int y)       { steps.add("click " + x + "," + y); }
        @Override public void pasteText(String text) {
            if (failOnPaste) throw new IllegalStateException("clipboard");
            steps.add("paste " + text);
        }
        @Override public void pressTab()   { steps.add("TAB"); }
        @Override public void pressEnter() { steps.add("ENTER"); }
        @Override public void sleep(int ms) { }
        @Override public boolean isMouseMoved(Point target) {
            checks++;
            return grabAt > 0 && checks >= grabAt;
        }
    }

    static final PairBurst.Timing T = new PairBurst.Timing(0, 0, 0);
    static final Point TARGET = new Point(500, 400);
    static final Point HOME = new Point(20, 10);

    public static void main(String[] args) throws Exception {
        clean_runsTheWholeSequence();
        grabBeforeEnter_isNotSent_atEveryStep();
        grabAfterEnter_isSent_butStops();
        robotFailure_isFailed();
        noGuard_neverStops();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void clean_runsTheWholeSequence() throws Exception {
        FakeHands h = new FakeHands();
        boolean[] noted = { false };
        PairBurst.Outcome o = PairBurst.run(h, TARGET, HOME, "LBL001", "LOT A", T, true, () -> {
            noted[0] = true;
            h.steps.add("noted");
        });
        check("a clean burst is SENT", o == PairBurst.Outcome.SENT);
        List<String> want = java.util.Arrays.asList(
            "dbl 500,400", "paste LBL001", "TAB", "paste LOT A", "TAB", "ENTER", "noted", "click 20,10");
        check("the sequence is double click, label, TAB, lot, TAB, ENTER, return click", h.steps.equals(want));
        check("the after-ENTER hook runs right after ENTER, before the return click",
              noted[0] && h.steps.indexOf("noted") == h.steps.indexOf("ENTER") + 1);
        check("the mouse is checked between every step", h.checks == 6);
    }

    private static void grabBeforeEnter_isNotSent_atEveryStep() throws Exception {
        for (int at = 1; at <= 5; at++) {
            FakeHands h = new FakeHands();
            h.grabAt = at;
            boolean[] noted = { false };
            PairBurst.Outcome o = PairBurst.run(h, TARGET, HOME, "LBL001", "LOT", T, true, () -> noted[0] = true);
            check("mouse taken back at check " + at + ": NOT_SENT, no ENTER, nothing recorded",
                  o == PairBurst.Outcome.NOT_SENT && !h.steps.contains("ENTER") && !noted[0]);
        }
    }

    private static void grabAfterEnter_isSent_butStops() throws Exception {
        FakeHands h = new FakeHands();
        h.grabAt = 6;
        boolean[] noted = { false };
        PairBurst.Outcome o = PairBurst.run(h, TARGET, HOME, "LBL001", "LOT", T, true, () -> noted[0] = true);
        check("mouse taken back after ENTER: SENT_THEN_GRABBED", o == PairBurst.Outcome.SENT_THEN_GRABBED);
        check("the pair was recorded as sent", noted[0]);
        check("no return click under the operator's hand", !h.steps.contains("click 20,10"));
    }

    private static void robotFailure_isFailed() throws Exception {
        FakeHands h = new FakeHands();
        h.failOnPaste = true;
        PairBurst.Outcome o = PairBurst.run(h, TARGET, HOME, "LBL001", "LOT", T, true, null);
        check("a robot that cannot paste is FAILED, never SENT", o == PairBurst.Outcome.FAILED);
        check("a failed burst never pressed ENTER", !h.steps.contains("ENTER"));
    }

    private static void noGuard_neverStops() throws Exception {
        FakeHands h = new FakeHands();
        h.grabAt = 1;
        PairBurst.Outcome o = PairBurst.run(h, TARGET, null, "LBL001", "LOT", T, false, null);
        check("with the fail-safe off the burst runs to the end", o == PairBurst.Outcome.SENT && h.checks == 0);
        check("no return point, no return click", h.steps.get(h.steps.size() - 1).equals("ENTER"));
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
