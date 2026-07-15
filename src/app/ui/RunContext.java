package app.ui;

import java.awt.Color;
import java.awt.Point;
import java.util.Map;

/**
 * What a mode panel needs from the window it lives in. Keeps the panels free of
 * any knowledge about HUDs, screens or window bounds — they just say what they
 * are doing, and ask permission before letting the robot loose.
 */
public interface RunContext {

    /**
     * Refuse to start when the window covers a coordinate the robot must click:
     * it would click the APP instead of the site, write nothing, and say nothing.
     * Moves the window out of the way when allowed to.
     *
     * @return null when clear to go, otherwise the message to show the operator
     */
    String blockingCollision(Map<String, Point> targets);

    /** A job started: the HUD may take over, and STOP has to reach the task. */
    void jobStarted(Runnable stopAction);

    /** Live reading for the HUD. percent < 0 leaves the bar untouched. */
    void jobProgress(Color tone, String state, String number, String caption,
                     int percent, boolean stoppable);

    /** The job is over: the window goes back the way the operator left it. */
    void jobFinished();

    /** Bring the app in front and put the caret in the field for the next scan. */
    void focusHome(javax.swing.JComponent target);
}
