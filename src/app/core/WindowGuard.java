package app.core;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The app is always-on-top. If its window sits over a memorized coordinate, the
 * robot clicks the APP instead of the site: the run starts, writes nothing, and
 * nobody notices until the verification reports "50 missing".
 *
 * The app knows its own bounds and its own targets, so it can see this coming
 * before it starts. Pure geometry — no Swing, no screen, fully testable.
 */
public final class WindowGuard {

    /** Keep the window this far from a target: a click has a few px of slop. */
    private static final int MARGIN = 8;

    private WindowGuard() {}

    /** Names of the targets the window would swallow. Empty means safe to run. */
    public static List<String> collisions(Rectangle window, Map<String, Point> targets) {
        List<String> hits = new ArrayList<>();
        if (window == null || targets == null) return hits;
        Rectangle padded = pad(window);
        for (Map.Entry<String, Point> t : targets.entrySet()) {
            Point p = t.getValue();
            if (p == null || p.x < 0 || p.y < 0) continue;   // not memorized yet
            if (padded.contains(p)) hits.add(t.getKey());
        }
        return hits;
    }

    /**
     * A place to put the window where it covers none of the targets and still
     * fits on screen. Candidates are walked corner-first, so the window lands
     * somewhere an operator would have put it anyway. null when nothing fits —
     * the caller must then refuse to run rather than guess.
     */
    public static Point safeLocation(Rectangle window, Collection<Point> targets, Rectangle screen) {
        if (window == null || screen == null) return null;
        int w = window.width, h = window.height;
        int left = screen.x + MARGIN;
        int top = screen.y + MARGIN;
        int right = screen.x + screen.width - w - MARGIN;
        int bottom = screen.y + screen.height - h - MARGIN;
        if (right < left || bottom < top) return null;   // window bigger than screen

        int midX = screen.x + (screen.width - w) / 2;
        int midY = screen.y + (screen.height - h) / 2;

        Point[] candidates = {
            new Point(right, bottom),   // bottom-right first: out of the form's way
            new Point(left, bottom),
            new Point(right, top),
            new Point(left, top),
            new Point(midX, bottom),
            new Point(midX, top),
            new Point(right, midY),
            new Point(left, midY),
        };

        for (Point c : candidates) {
            Rectangle at = new Rectangle(c.x, c.y, w, h);
            if (isClear(at, targets)) return c;
        }
        return null;
    }

    /** True when the window at these bounds covers none of the targets. */
    public static boolean isClear(Rectangle window, Collection<Point> targets) {
        if (targets == null) return true;
        Rectangle padded = pad(window);
        for (Point p : targets) {
            if (p == null || p.x < 0 || p.y < 0) continue;
            if (padded.contains(p)) return false;
        }
        return true;
    }

    private static Rectangle pad(Rectangle r) {
        return new Rectangle(r.x - MARGIN, r.y - MARGIN,
                             r.width + 2 * MARGIN, r.height + 2 * MARGIN);
    }

    /**
     * True if enough of the window sits on ANY screen for the operator to
     * grab it: a title-bar-sized sliver of the TOP edge (at least 100x20 px)
     * must be visible somewhere. A window remembered on an unplugged monitor
     * fails this and gets re-placed instead of opening off-screen.
     */
    public static boolean reachableOnAny(Rectangle win, List<Rectangle> screens) {
        Rectangle grab = new Rectangle(win.x, win.y, win.width, 30);
        for (Rectangle s : screens) {
            Rectangle i = grab.intersection(s);
            if (i.width >= 100 && i.height >= 20) return true;
        }
        return false;
    }
}
