package app.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/**
 * Icons drawn with Graphics2D, never typed as characters.
 *
 * WHY: an explicit Font ("Segoe UI") kills Java's glyph fallback, so ▶ 🔎 ⚙ ◂
 * come out as empty boxes on Windows — the app looks broken. Vector icons cannot
 * miss a glyph, scale cleanly and take the color we hand them.
 */
public final class Icons {

    private Icons() {}

    private abstract static class Base implements Icon {
        final int size;
        final Color color;
        Base(int size, Color color) { this.size = size; this.color = color; }
        @Override public int getIconWidth()  { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public final void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            g2.setColor(color);
            draw(g2);
            g2.dispose();
        }
        abstract void draw(Graphics2D g);
    }

    /** Play triangle — the lever's mark. */
    public static Icon play(int size, Color c) {
        return new Base(size, c) {
            @Override void draw(Graphics2D g) {
                Path2D p = new Path2D.Float();
                float pad = size * 0.18f;
                p.moveTo(pad, pad);
                p.lineTo(size - pad, size / 2f);
                p.lineTo(pad, size - pad);
                p.closePath();
                g.fill(p);
            }
        };
    }

    /** Stop square. */
    public static Icon stop(int size, Color c) {
        return new Base(size, c) {
            @Override void draw(Graphics2D g) {
                int pad = Math.round(size * 0.22f);
                g.fillRect(pad, pad, size - 2 * pad, size - 2 * pad);
            }
        };
    }

    /** Pause bars. */
    public static Icon pause(int size, Color c) {
        return new Base(size, c) {
            @Override void draw(Graphics2D g) {
                int pad = Math.round(size * 0.22f);
                int w = Math.round(size * 0.19f);
                g.fillRect(pad, pad, w, size - 2 * pad);
                g.fillRect(size - pad - w, pad, w, size - 2 * pad);
            }
        };
    }

    /** Magnifier — verification. */
    public static Icon search(int size, Color c) {
        return new Base(size, c) {
            @Override void draw(Graphics2D g) {
                float d = size * 0.58f;
                g.setStroke(new BasicStroke(Math.max(1.4f, size * 0.11f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Ellipse2D.Float(size * 0.10f, size * 0.10f, d, d));
                g.drawLine(Math.round(size * 0.63f), Math.round(size * 0.63f),
                           Math.round(size * 0.88f), Math.round(size * 0.88f));
            }
        };
    }

    /** Gear. Hub subtracted via Area: AlphaComposite.Clear paints BLACK on opaque buffers. */
    public static Icon gear(int size, Color c) {
        return new Base(size, c) {
            @Override void draw(Graphics2D g) {
                float cx = size / 2f, cy = size / 2f;
                float rOut = size * 0.46f, rIn = size * 0.31f;
                Area gear = new Area(new Ellipse2D.Float(cx - rIn, cy - rIn, rIn * 2, rIn * 2));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    Path2D t = new Path2D.Float();
                    float w = size * 0.09f;
                    t.moveTo(cx + (float) (Math.cos(a) * rIn - Math.sin(a) * w),
                             cy + (float) (Math.sin(a) * rIn + Math.cos(a) * w));
                    t.lineTo(cx + (float) (Math.cos(a) * rOut - Math.sin(a) * w * 0.6),
                             cy + (float) (Math.sin(a) * rOut + Math.cos(a) * w * 0.6));
                    t.lineTo(cx + (float) (Math.cos(a) * rOut + Math.sin(a) * w * 0.6),
                             cy + (float) (Math.sin(a) * rOut - Math.cos(a) * w * 0.6));
                    t.lineTo(cx + (float) (Math.cos(a) * rIn + Math.sin(a) * w),
                             cy + (float) (Math.sin(a) * rIn - Math.cos(a) * w));
                    t.closePath();
                    gear.add(new Area(t));
                }
                float h = size * 0.13f;
                gear.subtract(new Area(new Ellipse2D.Float(cx - h, cy - h, h * 2, h * 2)));
                g.fill(gear);
            }
        };
    }

    /** Chevron: points left (close) or right (open). */
    public static Icon chevron(int size, Color c, boolean left) {
        return new Base(size, c) {
            @Override void draw(Graphics2D g) {
                g.setStroke(new BasicStroke(Math.max(1.5f, size * 0.14f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int x1 = Math.round(size * (left ? 0.64f : 0.36f));
                int x2 = Math.round(size * (left ? 0.36f : 0.64f));
                g.drawLine(x1, Math.round(size * 0.22f), x2, size / 2);
                g.drawLine(x2, size / 2, x1, Math.round(size * 0.78f));
            }
        };
    }

    /** Circular arrow — retry. */
    public static Icon retry(int size, Color c) {
        return new Base(size, c) {
            @Override void draw(Graphics2D g) {
                g.setStroke(new BasicStroke(Math.max(1.4f, size * 0.12f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int pad = Math.round(size * 0.16f);
                g.drawArc(pad, pad, size - 2 * pad, size - 2 * pad, 40, 280);
                Path2D head = new Path2D.Float();
                float hx = size * 0.78f, hy = size * 0.26f, s = size * 0.16f;
                head.moveTo(hx, hy - s);
                head.lineTo(hx + s * 0.7f, hy + s * 0.5f);
                head.lineTo(hx - s * 0.7f, hy + s * 0.4f);
                head.closePath();
                g.fill(head);
            }
        };
    }

    /** Check mark. */
    public static Icon check(int size, Color c) {
        return new Base(size, c) {
            @Override void draw(Graphics2D g) {
                g.setStroke(new BasicStroke(Math.max(1.6f, size * 0.15f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(Math.round(size * 0.2f), Math.round(size * 0.52f),
                           Math.round(size * 0.42f), Math.round(size * 0.74f));
                g.drawLine(Math.round(size * 0.42f), Math.round(size * 0.74f),
                           Math.round(size * 0.8f), Math.round(size * 0.26f));
            }
        };
    }

    /** Reset / clear. */
    public static Icon reset(int size, Color c) {
        return retry(size, c);
    }

    /** X — clear the fields. */
    public static Icon cross(int size, Color c) {
        return new Base(size, c) {
            @Override void draw(Graphics2D g) {
                g.setStroke(new BasicStroke(Math.max(1.5f, size * 0.14f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int a = Math.round(size * 0.26f), b = Math.round(size * 0.74f);
                g.drawLine(a, a, b, b);
                g.drawLine(b, a, a, b);
            }
        };
    }
}
