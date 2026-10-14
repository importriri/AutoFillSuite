package app.ui;

import javax.swing.*;
import java.awt.*;

// texts cut on screen at every scale. the HUD counter had a fixed 110 px:
// "1000 / 1000" for a file of a thousand rows did not fit. the mode banners
// in the 300 px column cut their end, and the end is where the label and
// the instruction were ("Coppia NON inviata: <label> — controlla e rispara").
//
//   xvfb-run java -cp out app.ui.BannerFitTest
public final class BannerFitTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("  skip  no display: run under xvfb-run");
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            HudPanel hud = new HudPanel(() -> { }, () -> { });
            hud.state(AppTheme.PEACH, "ROBOT AL LAVORO", "1000 / 1000", "registrate", 100, true);
            JLabel value = null;
            for (Component c : all(hud)) {
                if (c instanceof JLabel && "1000 / 1000".equals(((JLabel) c).getText())) value = (JLabel) c;
            }
            int need = value == null ? Integer.MAX_VALUE
                : value.getFontMetrics(value.getFont()).stringWidth("1000 / 1000");
            check("the HUD counter has room for 1000 / 1000",
                  value != null && value.getParent().getPreferredSize().width >= need);

            JLabel banner = AppTheme.banner();
            banner.setText("SPARA PURE");
            int oneLine = banner.getPreferredSize().height;
            banner.setSize(300, oneLine);
            String text = "Coppia NON inviata: 209531.001_01-01_2610000232 — controlla e rispara";
            banner.setText(text);
            check("a banner too long for its width wraps instead of cutting",
                  banner.getPreferredSize().height > oneLine
                  && banner.getText().contains("209531.001_01-01_2610000232")
                  && banner.getText().contains("controlla e rispara"));
            check("...and its whole text is in the tooltip", text.equals(banner.getToolTipText()));
            banner.setText("SPARA PURE");
            check("a short text is one plain line again",
                  "SPARA PURE".equals(banner.getText()) && banner.getPreferredSize().height == oneLine);
        });

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    static java.util.List<Component> all(Container root) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        for (Component c : root.getComponents()) {
            out.add(c);
            if (c instanceof Container) out.addAll(all((Container) c));
        }
        return out;
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
