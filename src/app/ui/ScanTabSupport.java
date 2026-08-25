package app.ui;

import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes scanner suffix TAB behave like ENTER on the second QR field.
 *
 * Swing normally consumes TAB as a focus-traversal key before the field can
 * handle it. ScanModePanel already accepts ENTER directly and has a focus-loss
 * fallback for TAB scanners, but that fallback depends on Swing reporting the
 * next focus owner and is not reliable on every desktop/focus transition.
 *
 * This dispatcher is intentionally narrow: only an unmodified TAB pressed in
 * the second of exactly two JTextFields inside ScanModePanel is converted into
 * the field's normal ActionEvent. Everything else keeps Swing's default focus
 * traversal behavior.
 */
public final class ScanTabSupport {
    private static boolean installed;

    private ScanTabSupport() {}

    public static synchronized void install() {
        if (installed) return;
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(ScanTabSupport::dispatch);
        installed = true;
    }

    private static boolean dispatch(KeyEvent event) {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .getFocusOwner();
        return handle(event, focusOwner);
    }

    static boolean handle(KeyEvent event, Component focusOwner) {
        if (event.getID() != KeyEvent.KEY_PRESSED
                || event.getKeyCode() != KeyEvent.VK_TAB
                || event.getModifiersEx() != 0
                || !(focusOwner instanceof JTextField)) {
            return false;
        }

        ScanModePanel scanPanel = enclosingScanPanel(focusOwner);
        if (scanPanel == null || !isSecondQrField(scanPanel, focusOwner)) {
            return false;
        }

        ((JTextField) focusOwner).postActionEvent();
        event.consume();
        return true;
    }

    private static ScanModePanel enclosingScanPanel(Component component) {
        Component current = component;
        while (current != null) {
            if (current instanceof ScanModePanel) return (ScanModePanel) current;
            current = current.getParent();
        }
        return null;
    }

    private static boolean isSecondQrField(ScanModePanel panel, Component focusOwner) {
        List<JTextField> fields = new ArrayList<>();
        collectTextFields(panel, fields);
        return fields.size() == 2 && fields.get(1) == focusOwner;
    }

    private static void collectTextFields(Container root, List<JTextField> fields) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextField) fields.add((JTextField) child);
            if (child instanceof Container) collectTextFields((Container) child, fields);
        }
    }
}
