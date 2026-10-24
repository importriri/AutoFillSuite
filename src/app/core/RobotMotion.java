package app.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Whether the robot is in the middle of a burst right now: between the first
 * click on the form and the last pause after ENTER (or DELETE). The window may
 * step out of the robot's way only outside a burst: a burst reads where the
 * app is before it starts, and clicks back there at the end.
 */
public final class RobotMotion {

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private RobotMotion() {}

    static void begin() { IN_FLIGHT.incrementAndGet(); }

    static void end() { IN_FLIGHT.decrementAndGet(); }

    /** A burst is running at this moment. */
    public static boolean midStep() {
        return IN_FLIGHT.get() > 0;
    }
}
