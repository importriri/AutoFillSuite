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
        /** registered, then the mouse moved (or the robot failed) after ENTER: counts, robot stops */
        SENT_THEN_GRABBED,
        /** the robot itself failed BEFORE ENTER (no display, no clipboard...): not sent */
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

    /**
     * Called right before ENTER, after the last mouse check: the place to record
     * "this pair may be on the portal from now on". If it throws, ENTER is NOT
     * pressed and the burst is FAILED: never send what could not be recorded.
     */
    public interface BeforeEnter {
        void sending() throws Exception;
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
        return run(h, target, returnTo, label, lot, t, guarded, null, after);
    }

    /** The same, with a hook right before ENTER as well. */
    public static Outcome run(Hands h, Point target, Point returnTo, String label, String lot,
                              Timing t, boolean guarded, BeforeEnter before, AfterEnter after)
            throws InterruptedException {
        boolean entered = false;
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

            if (before != null) before.sending();

            // point of no return: after ENTER the pair IS registered, so it can
            // never go back in the queue — a re-send would double-register it
            h.pressEnter();
            entered = true;
            if (after != null) after.sent();
            h.sleep(t.enter);

            boolean grabbed = guarded && h.isMouseMoved(target);
            if (!grabbed && returnTo != null) h.click(returnTo.x, returnTo.y);
            return grabbed ? Outcome.SENT_THEN_GRABBED : Outcome.SENT;
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Exception ex) {
            // past ENTER the pair IS on the portal: a failure now (the return
            // click, a sleep) must not read as "not sent" — that would send it
            // twice. It counts, and the robot stops.
            return entered ? Outcome.SENT_THEN_GRABBED : Outcome.FAILED;
        }
    }
}
