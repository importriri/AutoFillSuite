package app.core;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;

// ANNULLA interrupts the verification thread. if that happened between the
// press and the release of the Export click, the release never ran and the
// operating system kept the left button held down.
//
//   xvfb-run java -cp out app.core.RobotReleaseTest
public final class RobotReleaseTest {

    private static int passed = 0;
    private static int failed = 0;

    /** Records the buttons, moves nothing. */
    static final class Recording extends Robot {
        int pressed, released;
        Recording() throws java.awt.AWTException { super(); }
        @Override public synchronized void mousePress(int buttons) { pressed++; }
        @Override public synchronized void mouseRelease(int buttons) { released++; }
    }

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        Recording r = new Recording();
        Thread.currentThread().interrupt();   // ANNULLA arrives while the button is down
        boolean interrupted = false;
        try {
            RobotEngine.pressAndRelease(r);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        check("the interruption still stops the click", interrupted);
        check("the button pressed is released anyway", r.pressed == 1 && r.released == 1);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
