package app.core;

import java.awt.Point;

/**
 * One label + lot pair typed into the portal form: the sequence every mode
 * uses, extracted so it is the same sequence everywhere and can be tested
 * without a screen.
 *
 * double click on the form field, paste the label, TAB, paste the lot, TAB,
 * ENTER, then a click back on the app. The mouse is checked between EVERY step
 * (when the fail-safe is on). ENTER is the point of no return: before it the
 * pair is NOT registered and goes back to the queue; after it the pair IS
 * registered — it counts, and the robot stops anyway if the mouse moved.
 */
public final class PairBurst {

    /** What happened to the pair. */
    public enum Outcome {
        /** registered; the return click was made */
        SENT,
        /** the operator took the mouse back before ENTER: nothing registered */
        NOT_SENT,
        /** registered, and the mouse moved after ENTER: counts, robot stops */
        SENT_THEN_GRABBED,
        /** the robot itself failed (no display, no clipboard...): treat as not sent */
        FAILED
    }

    /**
     * The hands the burst needs. {@link RobotEngine} is the real one; tests
     * pass a fake that records the steps and moves the "mouse" when told to.
     */
    public interface Hands {
        void doubleClick(int x, int y) throws InterruptedException;
        void click(int x, int y) throws InterruptedException;
        void pasteText(String text) throws InterruptedException;
        void pressTab() throws InterruptedException;
        void pressEnter() throws InterruptedException;
        void sleep(int ms) throws InterruptedException;
        boolean isMouseMoved(Point target);
    }

    /** The pauses of a burst, in ms. */
    public static final class Timing {
        public final int focus, key, enter;

        public Timing(int focus, int key, int enter) {
            this.focus = focus;
            this.key = key;
            this.enter = enter;
        }
    }

    /** Called right after ENTER, before anything else: the place to record "sent". */
    public interface AfterEnter {
        void sent();
    }

    private PairBurst() {}

    /**
     * @param target   the form field the robot double-clicks (and leaves the pointer on)
     * @param returnTo where to click afterwards to give the focus back to the app; may be null
     * @param guarded  the mouse fail-safe
     * @param after    run right after ENTER (may be null)
     */
    public static Outcome run(Hands h, Point target, Point returnTo, String label, String lot,
                              Timing t, boolean guarded, AfterEnter after)
            throws InterruptedException {
        try {
            h.doubleClick(target.x, target.y);
            h.sleep(t.focus);
            if (guarded && h.isMouseMoved(target)) return Outcome.NOT_SENT;

            h.pasteText(label); h.sleep(t.key);
            if (guarded && h.isMouseMoved(target)) return Outcome.NOT_SENT;

            h.pressTab();       h.sleep(t.key);
            if (guarded && h.isMouseMoved(target)) return Outcome.NOT_SENT;

            h.pasteText(lot);   h.sleep(t.key);
            if (guarded && h.isMouseMoved(target)) return Outcome.NOT_SENT;

            h.pressTab();       h.sleep(t.key);
            if (guarded && h.isMouseMoved(target)) return Outcome.NOT_SENT;

            // point of no return: after ENTER the pair IS registered, so it can
            // never go back in the queue — a re-send would double-register it
            h.pressEnter();
            if (after != null) after.sent();
            h.sleep(t.enter);

            boolean grabbed = guarded && h.isMouseMoved(target);
            if (!grabbed && returnTo != null) h.click(returnTo.x, returnTo.y);
            return grabbed ? Outcome.SENT_THEN_GRABBED : Outcome.SENT;
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Exception ex) {
            return Outcome.FAILED;
        }
    }
}
