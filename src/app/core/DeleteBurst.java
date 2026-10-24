package app.core;

import java.awt.Point;

/**
 * One label deleted from the portal: double click on the serial field of the
 * Delete form, paste the label, press ENTER (the portal deletes and empties
 * the field) — or, where a portal wants it, click DELETE instead of ENTER. The
 * portal deletes at once, no question asked, and the label disappears from
 * its CSV export.
 *
 * The mouse is checked between every step, like {@link PairBurst}: ENTER (or
 * the DELETE click) is the point of no return. Before it nothing is deleted;
 * after it the label IS gone, and the robot stops anyway if the operator took
 * the mouse.
 */
public final class DeleteBurst {

    public enum Outcome {
        /** deleted, the pointer still where the robot left it */
        DELETED,
        /** the operator took the mouse before DELETE: nothing deleted */
        NOT_DELETED,
        /** deleted, then the mouse moved (or a step failed) after DELETE: counts, robot stops */
        DELETED_THEN_GRABBED,
        /** the robot itself failed before DELETE: nothing deleted */
        FAILED
    }

    /** The pauses, in ms. */
    public static final class Timing {
        public final int focus, key, after;

        public Timing(int focus, int key, int after) {
            this.focus = focus;
            this.key = key;
            this.after = after;
        }
    }

    private DeleteBurst() {}

    /**
     * @param field   the serial field of the Delete form
     * @param button  the DELETE button, or null: ENTER in the field deletes
     * @param before  run right before ENTER (or the click); if it throws, nothing is deleted
     */
    public static Outcome run(PairBurst.Hands h, Point field, Point button, String label,
                              Timing t, boolean guarded, PairBurst.BeforeEnter before)
            throws InterruptedException {
        boolean clicked = false;
        RobotMotion.begin();
        try {
            h.doubleClick(field.x, field.y);
            h.sleep(t.focus);
            if (guarded && h.isMouseMoved(field)) return Outcome.NOT_DELETED;

            h.pasteText(label);
            h.sleep(t.key);
            if (guarded && h.isMouseMoved(field)) return Outcome.NOT_DELETED;

            if (before != null) before.sending();

            // point of no return: the portal deletes on this key (or click)
            Point rest = field;
            if (button == null) {
                h.pressEnter();
            } else {
                h.click(button.x, button.y);
                rest = button;
            }
            clicked = true;
            h.sleep(t.after);

            return guarded && h.isMouseMoved(rest) ? Outcome.DELETED_THEN_GRABBED : Outcome.DELETED;
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Exception ex) {
            return clicked ? Outcome.DELETED_THEN_GRABBED : Outcome.FAILED;
        } finally {
            RobotMotion.end();
        }
    }
}
