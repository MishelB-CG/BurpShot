package ptshot;

import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;

/**
 * "Pick from Burp": lets the user point at any element of Burp's own UI (a table, a button, a text field...).
 * Works by laying a transparent glass pane over every open Burp window; the element under the cursor is outlined,
 * the mouse wheel moves the selection to the parent / back, a click picks it and Esc cancels.
 */
final class UiPicker {
    private UiPicker() {}

    /** What was found behind a picked component. */
    static final class Picked {
        Component comp;
        List<List<String>> table;   // non-null when the component is (or sits in) a table
        String text;                // non-null for labels, buttons and text components
        int fontSize = 12;
    }

    private static final class Glass extends JComponent {
        final Window win;
        final JRootPane root;
        final java.awt.Container oldGlass;
        final boolean oldVisible;
        Component hover;
        Point mouse;

        Glass(Window win, JRootPane root) {
            this.win = win;
            this.root = root;
            this.oldGlass = (java.awt.Container) root.getGlassPane();
            this.oldVisible = oldGlass.isVisible();
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            String hint = "Click = pick     Ctrl+click = use Burp (switch tabs, open menus)     Wheel = parent / child     Esc = cancel";
            g.setFont(getFont().deriveFont(Font.BOLD, 13f));
            int hw = g.getFontMetrics().stringWidth(hint) + 24, hh = g.getFontMetrics().getHeight() + 10;
            int hx = Math.max(4, (getWidth() - hw) / 2);
            g.setColor(new Color(20, 20, 20, 225));
            g.fillRoundRect(hx, 8, hw, hh, 12, 12);
            g.setColor(Color.WHITE);
            g.drawString(hint, hx + 12, 8 + hh - 8);
            if (hover == null || !hover.isShowing()) {
                g.dispose();
                return;
            }
            Point p = SwingUtilities.convertPoint(hover, 0, 0, this);
            Rectangle r = new Rectangle(p.x, p.y, hover.getWidth(), hover.getHeight());
            g.setColor(new Color(0, 120, 255, 50));
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(new Color(0, 120, 255));
            g.setStroke(new BasicStroke(2f));
            g.drawRect(r.x, r.y, r.width, r.height);
            String label = hover.getClass().getSimpleName() + "  " + hover.getWidth() + " x " + hover.getHeight();
            g.setFont(getFont().deriveFont(Font.BOLD, 12f));
            int tw = g.getFontMetrics().stringWidth(label) + 10, th = g.getFontMetrics().getHeight() + 4;
            int ly = r.y - th >= 0 ? r.y - th : r.y + r.height;
            g.setColor(new Color(0, 90, 200));
            g.fillRect(r.x, ly, tw, th);
            g.setColor(Color.WHITE);
            g.drawString(label, r.x + 5, ly + th - 6);
            g.dispose();
        }
    }

    /** Starts picking; exactly one of the callbacks runs (on the Swing thread). */
    static void pick(Consumer<Component> picked, Runnable cancelled) {
        final List<Glass> glasses = new ArrayList<>();
        final AWTEventListener[] keys = new AWTEventListener[1];
        final boolean[] done = {false};
        final Runnable[] restore = new Runnable[1];
        restore[0] = () -> {
            if (keys[0] != null) Toolkit.getDefaultToolkit().removeAWTEventListener(keys[0]);
            for (Glass gl : glasses) {
                gl.root.setGlassPane(gl.oldGlass);
                gl.oldGlass.setVisible(gl.oldVisible);
            }
        };
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || !(w instanceof RootPaneContainer)) continue;
            JRootPane root = ((RootPaneContainer) w).getRootPane();
            Glass gl = new Glass(w, root);
            final Glass fg = gl;
            MouseAdapter ma = new MouseAdapter() {
                private Component deepest(MouseEvent e) {
                    Point p = SwingUtilities.convertPoint(fg, e.getPoint(), fg.root.getLayeredPane());
                    Component c = SwingUtilities.getDeepestComponentAt(fg.root.getLayeredPane(), p.x, p.y);
                    return c == fg ? null : c;
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    for (Glass o : glasses) if (o != fg && o.hover != null) {
                        o.hover = null;
                        o.repaint();
                    }
                    Component c = deepest(e);
                    if (c != fg.hover) {
                        fg.hover = c;
                        fg.repaint();
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    if (fg.hover == null) return;
                    if (e.getWheelRotation() < 0) {
                        Component par = fg.hover.getParent();
                        if (par != null && !(par instanceof JRootPane) && par != fg.root.getLayeredPane()) fg.hover = par;
                    } else {
                        Component c = deepest(e);
                        // walk back down towards the cursor
                        Component cur = fg.hover;
                        while (c != null && c != cur && c.getParent() != cur) c = c.getParent();
                        if (c != null && c != cur) fg.hover = c;
                        else if (c == null) fg.hover = deepest(e);
                    }
                    fg.repaint();
                }

                private Component fwd;

                /** Ctrl+click behaves like a normal click on the Burp UI underneath (tabs, menus...). */
                private void forward(MouseEvent e, Component c, int id) {
                    Point p = SwingUtilities.convertPoint(fg, e.getPoint(), c);
                    int mods = e.getModifiersEx() & ~(java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.META_DOWN_MASK);
                    c.dispatchEvent(new MouseEvent(c, id, e.getWhen(), mods, p.x, p.y, e.getClickCount(), e.isPopupTrigger(), e.getButton()));
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (fwd == null) return;
                    Component c = fwd;
                    fwd = null;
                    forward(e, c, MouseEvent.MOUSE_RELEASED);
                    forward(e, c, MouseEvent.MOUSE_CLICKED);
                    for (Glass o : glasses) {
                        o.hover = null;
                        o.repaint();
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (done[0]) return;
                    if (e.isControlDown() || e.isMetaDown()) {
                        Component c = deepest(e);
                        if (c != null) {
                            fwd = c;
                            forward(e, c, MouseEvent.MOUSE_PRESSED);
                        }
                        return;
                    }
                    Component c = fg.hover != null ? fg.hover : deepest(e);
                    done[0] = true;
                    restore[0].run();
                    if (c != null && SwingUtilities.isLeftMouseButton(e)) picked.accept(c);
                    else cancelled.run();
                }
            };
            gl.addMouseListener(ma);
            gl.addMouseMotionListener(ma);
            gl.addMouseWheelListener(ma);
            root.setGlassPane(gl);
            gl.setVisible(true);
            glasses.add(gl);
        }
        if (glasses.isEmpty()) {
            cancelled.run();
            return;
        }
        keys[0] = ev -> {
            if (ev instanceof KeyEvent && ev.getID() == KeyEvent.KEY_PRESSED && ((KeyEvent) ev).getKeyCode() == KeyEvent.VK_ESCAPE && !done[0]) {
                done[0] = true;
                restore[0].run();
                cancelled.run();
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(keys[0], AWTEvent.KEY_EVENT_MASK);
        for (Glass gl : glasses) gl.repaint();
    }

    // ------------------------------------------------------------------ inspecting a picked component

    static Picked inspect(Component c) {
        Picked p = new Picked();
        p.comp = c;
        JTable t = tableOf(c);
        if (t != null) {
            p.table = readTable(t);
            p.fontSize = t.getFont().getSize();
        } else if (c instanceof JList) {
            JList<?> l = (JList<?>) c;
            List<List<String>> rows = new ArrayList<>();
            int n = Math.min(l.getModel().getSize(), 200);
            for (int i = 0; i < n; i++) {
                List<String> r = new ArrayList<>();
                r.add(plain(String.valueOf(l.getModel().getElementAt(i))));
                rows.add(r);
            }
            if (!rows.isEmpty()) {
                List<String> hdr = new ArrayList<>();
                hdr.add("Item");
                rows.add(0, hdr);
                p.table = rows;
            }
        } else if (c instanceof JTextComponent) {
            JTextComponent tc = (JTextComponent) c;
            String sel = tc.getSelectedText();
            p.text = sel != null && !sel.isEmpty() ? sel : tc.getText();
            p.fontSize = tc.getFont().getSize();
        } else if (c instanceof JLabel) {
            p.text = plain(((JLabel) c).getText());
            p.fontSize = c.getFont().getSize();
        } else if (c instanceof AbstractButton) {
            p.text = plain(((AbstractButton) c).getText());
            p.fontSize = c.getFont().getSize();
        }
        if (p.text != null && p.text.trim().isEmpty()) p.text = null;
        return p;
    }

    private static JTable tableOf(Component c) {
        if (c instanceof JScrollPane && ((JScrollPane) c).getViewport().getView() instanceof JTable) return (JTable) ((JScrollPane) c).getViewport().getView();
        for (Component x = c; x != null && !(x instanceof JRootPane); x = x.getParent()) {
            if (x instanceof JTable) return (JTable) x;
            if (x instanceof JTableHeader && ((JTableHeader) x).getTable() != null) return ((JTableHeader) x).getTable();
        }
        return null;
    }

    private static String plain(String s) {
        if (s == null) return "";
        if (s.regionMatches(true, 0, "<html>", 0, 6)) s = s.replaceAll("(?is)<br\\s*/?>", " ").replaceAll("<[^>]*>", "");
        return s.trim();
    }

    private static List<List<String>> readTable(JTable t) {
        List<List<String>> rows = new ArrayList<>();
        int nc = t.getColumnCount();
        List<String> hdr = new ArrayList<>();
        for (int c = 0; c < nc; c++) {
            Object hv = t.getColumnModel().getColumn(c).getHeaderValue();
            hdr.add(hv == null ? t.getColumnName(c) : plain(String.valueOf(hv)));
        }
        rows.add(hdr);
        int nr = Math.min(t.getRowCount(), 200);
        for (int r = 0; r < nr; r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < nc; c++) {
                String v;
                try {
                    Component rc = t.prepareRenderer(t.getCellRenderer(r, c), r, c);
                    if (rc instanceof JLabel) v = plain(((JLabel) rc).getText());
                    else {
                        Object o = t.getValueAt(r, c);
                        v = o == null ? "" : plain(String.valueOf(o));
                    }
                } catch (RuntimeException ex) {
                    Object o = t.getValueAt(r, c);
                    v = o == null ? "" : plain(String.valueOf(o));
                }
                row.add(v);
            }
            rows.add(row);
        }
        return rows;
    }

    /** A pixel copy of how the component looks right now (at the screen's pixel density). */
    static BufferedImage snapshot(Component c) {
        GraphicsConfiguration gc = c.getGraphicsConfiguration();
        double k = gc == null ? 1.0 : gc.getDefaultTransform().getScaleX();
        int w = Math.max(1, c.getWidth()), h = Math.max(1, c.getHeight());
        BufferedImage im = new BufferedImage((int) Math.ceil(w * k), (int) Math.ceil(h * k), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(k, k);
        Color bg = null;
        for (Component x = c; x != null && bg == null; x = x.getParent()) if (x.isOpaque()) bg = x.getBackground();
        if (bg != null) {
            g.setColor(bg);
            g.fillRect(0, 0, w, h);
        }
        c.paint(g);
        g.dispose();
        return im;
    }
}
