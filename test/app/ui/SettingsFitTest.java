package app.ui;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;

// every settings page fits the window across: a hint too long for one line
// wraps instead of pushing the page wider, so no "Sfoglia..." button ends up
// behind a horizontal scroll bar. and a number reads whole: the print
// program's port 8889 used to show as "8,88".
//
//   java -cp out app.ui.SettingsFitTest
public final class SettingsFitTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("afs-fit").toString());
        SwingUtilities.invokeAndWait(() -> {
            SettingsWindow w = new SettingsWindow(null);
            w.setVisible(true);
            JTabbedPane tp = find(w.getContentPane(), JTabbedPane.class);
            check("the settings have their tabs", tp != null && tp.getTabCount() > 5);
            for (int i = 0; tp != null && i < tp.getTabCount(); i++) {
                tp.setSelectedIndex(i);
                w.validate();
                Component c = tp.getComponentAt(i);
                if (!(c instanceof JScrollPane)) continue;
                JViewport vp = ((JScrollPane) c).getViewport();
                int page = vp.getView().getWidth(), view = vp.getWidth();
                check("\"" + tp.getTitleAt(i) + "\" fits across (" + page + " <= " + view + ")", page <= view);
            }
            JSpinner port = AppTheme.spinnerInt(8889, 1, 65535);
            String shown = ((JSpinner.DefaultEditor) port.getEditor()).getTextField().getText();
            check("a port reads whole, with no separator (" + shown + ")", "8889".equals(shown));
            w.dispose();
        });
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static <T> T find(Container c, Class<T> k) {
        for (Component x : c.getComponents()) {
            if (k.isInstance(x)) return k.cast(x);
            if (x instanceof Container) {
                T r = find((Container) x, k);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
