package ptshot;

import java.awt.Component;
import java.awt.Frame;
import java.awt.Window;
import javax.swing.SwingUtilities;

/** Dialog parents: always Burp's main window, so pop-ups appear on the right monitor (BApp Store requirement). */
final class Ui {
    private Ui() {}

    /** Burp's main frame (from the Montoya API); null outside Burp (tests). */
    static volatile Frame suite;

    static Window window(Component c) {
        Window w = c == null ? null : SwingUtilities.getWindowAncestor(c);
        return w != null ? w : suite;
    }
}
