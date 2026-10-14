package app.ui;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;

// the HUD sat at the bottom of the screen, in the middle, whatever was there.
// the range and print modes check the window only at the start, against the
// whole cockpit: a field or a print button in that strip of the portal was
// then clicked on the HUD, maybe on its STOP.
//
//   java -cp out app.ui.HudPlacementTest
public final class HudPlacementTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        Rectangle screen = new Rectangle(0, 0, 1920, 1040);
        Point free = MainWindow.hudLocation(screen, 470, 96, Collections.<Point>emptyList());
        check("with nothing to click there, the HUD sits at the bottom in the middle",
              free.equals(new Point(725, 1040 - 96 - 12)));

        Point button = new Point(960, 990);   // a portal button in that strip
        Point moved = MainWindow.hudLocation(screen, 470, 96, Arrays.asList(button));
        Rectangle hud = new Rectangle(moved.x, moved.y, 470, 96);
        check("a target under the bottom middle moves the HUD off it",
              app.core.WindowGuard.isClear(hud, Arrays.asList(button)));
        check("...and keeps it on the screen", screen.contains(hud));

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
