package ptshot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;

final class ColorButton extends JButton {
    private Color color;

    ColorButton(Color init, Runnable onChange) {
        this.color = init;
        setFocusable(false);
        setMargin(new Insets(2, 6, 2, 6));
        setIcon(new Icon() {
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(color);
                g.fillRect(x, y, getIconWidth(), getIconHeight());
                g.setColor(Color.GRAY);
                g.drawRect(x, y, getIconWidth() - 1, getIconHeight() - 1);
            }

            public int getIconWidth() {
                return 26;
            }

            public int getIconHeight() {
                return 12;
            }
        });
        addActionListener(e -> {
            Color n = JColorChooser.showDialog(this, "Choose colour", color);
            if (n != null) {
                color = n;
                repaint();
                onChange.run();
            }
        });
    }

    Color get() {
        return color;
    }

    void set(Color c) {
        color = c;
        repaint();
    }
}

/** Checkbox + colour; value is null when unchecked. */
final class OptColor extends JPanel {
    private final JCheckBox on;
    private final ColorButton btn;

    OptColor(String text, Color initial, Runnable onChange) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        on = new JCheckBox(text);
        btn = new ColorButton(initial, onChange);
        btn.setEnabled(false);
        on.addActionListener(e -> {
            btn.setEnabled(on.isSelected());
            onChange.run();
        });
        add(on);
        add(btn);
    }

    Color get() {
        return on.isSelected() ? btn.get() : null;
    }

    void set(Color c) {
        on.setSelected(c != null);
        btn.setEnabled(c != null);
        if (c != null) btn.set(c);
    }
}

final class FontEditor extends JPanel {
    private final boolean allowOverride;
    private final JCheckBox override = new JCheckBox("Custom font for this block (otherwise global default)");
    private final JComboBox<String> fam = new JComboBox<>(FontSpec.allFamilies());
    private final JSpinner size = new JSpinner(new SpinnerNumberModel(14, 4, 120, 1));
    private final JToggleButton bold = new JToggleButton("B");
    private final JToggleButton italic = new JToggleButton("I");
    private final ColorButton color;
    private final Runnable onChange;
    private boolean loading;

    FontEditor(boolean allowOverride, Runnable onChange) {
        this.allowOverride = allowOverride;
        this.onChange = onChange;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        color = new ColorButton(Color.BLACK, this::fire);
        fam.setEditable(true);
        fam.setPreferredSize(new Dimension(190, 24));
        bold.setMargin(new Insets(1, 7, 1, 7));
        italic.setMargin(new Insets(1, 7, 1, 7));
        JPanel r0 = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 1));
        r0.add(override);
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        r1.add(fam);
        r1.add(new JLabel("size"));
        r1.add(size);
        r1.add(bold);
        r1.add(italic);
        r1.add(new JLabel("colour"));
        r1.add(color);
        if (allowOverride) add(r0);
        add(r1);
        override.addActionListener(e -> fire());
        fam.addActionListener(e -> fire());
        size.addChangeListener(e -> fire());
        bold.addActionListener(e -> fire());
        italic.addActionListener(e -> fire());
    }

    private void fire() {
        if (loading) return;
        updateEnabled();
        onChange.run();
    }

    private void updateEnabled() {
        boolean en = !allowOverride || override.isSelected();
        fam.setEnabled(en);
        size.setEnabled(en);
        bold.setEnabled(en);
        italic.setEnabled(en);
        color.setEnabled(en);
    }

    void load(FontSpec overrideSpec, FontSpec fallback) {
        loading = true;
        FontSpec s = overrideSpec != null ? overrideSpec : fallback;
        override.setSelected(overrideSpec != null);
        fam.setSelectedItem(s.family);
        size.setValue(s.size);
        bold.setSelected(s.bold);
        italic.setSelected(s.italic);
        color.set(s.color);
        updateEnabled();
        loading = false;
    }

    /** null when "override" is off. */
    FontSpec value() {
        if (allowOverride && !override.isSelected()) return null;
        Object f = fam.getSelectedItem();
        return new FontSpec(f == null ? "Monospaced" : f.toString(), (Integer) size.getValue(), bold.isSelected(),
                italic.isSelected(), color.get());
    }
}

final class Form extends JPanel {
    private int row;

    Form() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    }

    Form row(String label, Component c) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(2, 2, 2, 8);
        add(new JLabel(label), g);
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        add(c, g);
        row++;
        return this;
    }

    Form span(Component c) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.gridwidth = 2;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        g.insets = new Insets(2, 2, 2, 2);
        add(c, g);
        row++;
        return this;
    }

    /** Wrap in a panel that keeps the form at the top. */
    JPanel top() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(this, BorderLayout.NORTH);
        return p;
    }
}

final class Checklist extends JPanel {
    private final JPanel box = new JPanel();

    Checklist(String title) {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder(title));
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        JScrollPane sp = new JScrollPane(box);
        sp.setPreferredSize(new Dimension(120, 96));
        add(sp);
    }

    void set(List<String> texts, int firstId, Set<Integer> hidden, Runnable onChange) {
        box.removeAll();
        for (int i = 0; i < texts.size(); i++) {
            final int id = firstId + i;
            String t = texts.get(i);
            if (t.length() > 60) t = t.substring(0, 57) + "...";
            JCheckBox cb = new JCheckBox(t.isEmpty() ? "(empty)" : t, !hidden.contains(id));
            cb.addActionListener(e -> {
                if (cb.isSelected()) hidden.remove(id);
                else hidden.add(id);
                onChange.run();
            });
            box.add(cb);
        }
        box.revalidate();
        box.repaint();
    }
}

final class PreviewPanel extends JPanel implements Scrollable {
    /** Callbacks for live editing of annotations directly in the preview. */
    interface Editor {
        void picked(Annotation a);   // user clicked an annotation (null = clicked empty space)

        void begin();                // a drag starts (history boundary)

        void edited();               // annotation geometry changed -> re-render now

        void end();                  // drag finished (history boundary)

        void delete(Annotation a);
    }

    private static final class Hit {
        Renderer.Geo g;
        int part; // 0..7 resize handles (NW,N,NE,E,SE,S,SW,W), 8 move rect, 10 arrow tail, 11 arrow head, 12 arrow body
    }

    private static final class Drag {
        int part;
        Point press;
        Annotation an;
        double oL, oT, oR, oB;
        int oDx, oDy;
        Rectangle rect;
        double[] arrow;
    }

    private BufferedImage img;
    private double zoom = 0; // 0 = fit width (never upscales)
    private Renderer.Result res;
    private Editor editor;
    private Annotation selected, hover;
    private Drag drag;

    PreviewPanel() {
        setBackground(new Color(214, 214, 214));
        setFocusable(true);
        java.awt.event.MouseAdapter ma = new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (editor == null || res == null) return;
                requestFocusInWindow();
                Hit h = hitTest(e.getPoint());
                if (h == null) {
                    if (selected != null) {
                        selected = null;
                        editor.picked(null);
                    }
                    repaint();
                    return;
                }
                if (h.g.an != selected) {
                    selected = h.g.an;
                    editor.picked(selected);
                }
                Drag d = new Drag();
                d.part = h.part;
                d.press = e.getPoint();
                d.an = h.g.an;
                d.oL = d.an.offL;
                d.oT = d.an.offT;
                d.oR = d.an.offR;
                d.oB = d.an.offB;
                d.oDx = d.an.dx;
                d.oDy = d.an.dy;
                d.rect = h.g.rect == null ? null : new Rectangle(h.g.rect);
                d.arrow = h.g.arrow == null ? null : h.g.arrow.clone();
                drag = d;
                editor.begin();
                repaint();
            }

            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                if (drag == null || res == null) return;
                applyDrag(e);
                editor.edited();
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (drag != null) {
                    drag = null;
                    editor.end();
                    repaint();
                }
            }

            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                if (editor == null || res == null) return;
                Hit h = hitTest(e.getPoint());
                Annotation nh = h == null ? null : h.g.an;
                if (nh != hover) {
                    hover = nh;
                    repaint();
                }
                setCursor(Cursor.getPredefinedCursor(h == null ? Cursor.DEFAULT_CURSOR : cursorFor(h.part)));
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        key("DELETE", () -> deleteSelected());
        key("BACK_SPACE", () -> deleteSelected());
        key("ESCAPE", () -> {
            if (selected != null && editor != null) {
                selected = null;
                editor.picked(null);
                repaint();
            }
        });
        for (int[] k : new int[][]{{java.awt.event.KeyEvent.VK_LEFT, -1, 0}, {java.awt.event.KeyEvent.VK_RIGHT, 1, 0},
                {java.awt.event.KeyEvent.VK_UP, 0, -1}, {java.awt.event.KeyEvent.VK_DOWN, 0, 1}}) {
            getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(k[0], 0), "n" + k[0]);
            getActionMap().put("n" + k[0], act(() -> nudge(k[1], k[2])));
            getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(k[0], java.awt.event.InputEvent.SHIFT_DOWN_MASK), "s" + k[0]);
            getActionMap().put("s" + k[0], act(() -> nudge(k[1] * 10, k[2] * 10)));
        }
    }

    private void key(String name, Runnable r) {
        getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(name), name);
        getActionMap().put(name, act(r));
    }

    private static javax.swing.Action act(Runnable r) {
        return new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        };
    }

    private void deleteSelected() {
        if (selected != null && editor != null) {
            Annotation a = selected;
            selected = null;
            editor.delete(a);
            repaint();
        }
    }

    private void nudge(int dx, int dy) {
        if (selected == null || editor == null) return;
        Annotation a = selected;
        if (a.type == Annotation.Type.ARROW) {
            a.dx += dx;
            a.dy += dy;
        } else {
            a.offL += dx;
            a.offR += dx;
            a.offT += dy;
            a.offB += dy;
        }
        editor.edited();
    }

    void setImage(BufferedImage i) {
        img = i;
        res = null;
        revalidate();
        repaint();
    }

    void setResult(Renderer.Result r) {
        res = r;
        img = r.image;
        revalidate();
        repaint();
    }

    void setEditor(Editor e) {
        editor = e;
    }

    void setSelected(Annotation a) {
        selected = a;
        repaint();
    }

    void setZoom(double z) {
        zoom = z;
        revalidate();
        repaint();
    }

    private double eff() {
        if (img == null) return 1;
        if (zoom > 0) return zoom;
        int w = getParent() != null ? getParent().getWidth() - 12 : img.getWidth();
        return Math.min(1.0, Math.max(50, w) / (double) img.getWidth());
    }

    // ---------------------------------------------------------------- geometry helpers

    private int sc(double dev) {
        return 6 + (int) Math.round(dev * eff());
    }

    private Rectangle scr(Rectangle r) {
        double k = eff();
        return new Rectangle(sc(r.x), sc(r.y), (int) Math.round(r.width * k), (int) Math.round(r.height * k));
    }

    private static Point[] handles(Rectangle s) {
        int x = s.x, y = s.y, w = s.width, h = s.height;
        return new Point[]{new Point(x, y), new Point(x + w / 2, y), new Point(x + w, y), new Point(x + w, y + h / 2),
                new Point(x + w, y + h), new Point(x + w / 2, y + h), new Point(x, y + h), new Point(x, y + h / 2)};
    }

    private Renderer.Geo geoOf(Annotation a) {
        if (res == null || a == null) return null;
        for (Renderer.Geo g : res.geo) if (g.an == a) return g;
        return null;
    }

    private Point[] arrowScreen(Renderer.Geo g) {
        return new Point[]{new Point(sc(g.arrow[0]), sc(g.arrow[1])), new Point(sc(g.arrow[2]), sc(g.arrow[3]))};
    }

    private Hit hitTest(Point p) {
        Renderer.Geo sel = geoOf(selected);
        if (sel != null) {
            Hit h = new Hit();
            h.g = sel;
            if (sel.arrow != null) {
                Point[] a = arrowScreen(sel);
                if (p.distance(a[1]) <= 9) {
                    h.part = 11;
                    return h;
                }
                if (p.distance(a[0]) <= 9) {
                    h.part = 10;
                    return h;
                }
            } else {
                Point[] hp = handles(scr(sel.rect));
                for (int i = 0; i < 8; i++) {
                    if (Math.abs(p.x - hp[i].x) <= 6 && Math.abs(p.y - hp[i].y) <= 6) {
                        h.part = i;
                        return h;
                    }
                }
            }
        }
        Hit best = null;
        double bestArea = Double.MAX_VALUE;
        for (Renderer.Geo g : res.geo) {
            Hit h = new Hit();
            h.g = g;
            double area;
            if (g.arrow != null) {
                Point[] a = arrowScreen(g);
                if (java.awt.geom.Line2D.ptSegDist(a[0].x, a[0].y, a[1].x, a[1].y, p.x, p.y) > 9) continue;
                h.part = 12;
                area = 0; // thin things win over big rectangles
            } else {
                Rectangle s = scr(g.rect);
                Rectangle outer = new Rectangle(s.x - 6, s.y - 6, s.width + 12, s.height + 12);
                Rectangle inner = new Rectangle(s.x + 6, s.y + 6, Math.max(0, s.width - 12), Math.max(0, s.height - 12));
                boolean onBorder = outer.contains(p) && !inner.contains(p);
                boolean ok = g.an.type == Annotation.Type.BOX ? (onBorder || (g.an == selected && s.contains(p))) : outer.contains(p);
                if (!ok) continue;
                h.part = 8;
                area = (double) s.width * s.height;
            }
            if (area < bestArea) {
                bestArea = area;
                best = h;
            }
        }
        return best;
    }

    private static int cursorFor(int part) {
        switch (part) {
            case 0: return Cursor.NW_RESIZE_CURSOR;
            case 1: return Cursor.N_RESIZE_CURSOR;
            case 2: return Cursor.NE_RESIZE_CURSOR;
            case 3: return Cursor.E_RESIZE_CURSOR;
            case 4: return Cursor.SE_RESIZE_CURSOR;
            case 5: return Cursor.S_RESIZE_CURSOR;
            case 6: return Cursor.SW_RESIZE_CURSOR;
            case 7: return Cursor.W_RESIZE_CURSOR;
            case 10: case 11: return Cursor.HAND_CURSOR;
            default: return Cursor.MOVE_CURSOR;
        }
    }

    private void applyDrag(java.awt.event.MouseEvent e) {
        double k = eff(), S = res.scale;
        double dx = (e.getX() - drag.press.x) / k / S, dy = (e.getY() - drag.press.y) / k / S; // 1x pixels
        Annotation a = drag.an;
        if (a.type == Annotation.Type.ARROW) {
            double[] o = drag.arrow;
            double nsx = o[0], nsy = o[1], nex = o[2], ney = o[3];
            if (drag.part == 12) {
                a.dx = drag.oDx + (int) Math.round(dx);
                a.dy = drag.oDy + (int) Math.round(dy);
                return;
            }
            if (drag.part == 10) { // tail moves, head stays
                a.dx = drag.oDx + (int) Math.round(dx);
                a.dy = drag.oDy + (int) Math.round(dy);
                nsx = o[0] + (a.dx - drag.oDx) * S;
                nsy = o[1] + (a.dy - drag.oDy) * S;
            } else { // head moves
                nex = o[2] + dx * S;
                ney = o[3] + dy * S;
            }
            double ang = Math.toDegrees(Math.atan2(ney - nsy, nex - nsx));
            if (e.isShiftDown()) ang = Math.round(ang / 15.0) * 15.0;
            a.angle = Math.round(ang * 10) / 10.0;
            a.length = Math.max(5, (int) Math.round(Math.hypot(nex - nsx, ney - nsy) / S));
            return;
        }
        int part = drag.part;
        boolean L = part == 0 || part == 6 || part == 7, R = part == 2 || part == 3 || part == 4;
        boolean T = part == 0 || part == 1 || part == 2, B = part == 4 || part == 5 || part == 6;
        double dL = 0, dT = 0, dR = 0, dB = 0;
        if (part == 8) {
            dL = dR = dx;
            dT = dB = dy;
        } else {
            if (L) dL = dx;
            if (R) dR = dx;
            if (T) dT = dy;
            if (B) dB = dy;
        }
        double minPx = 6;
        if (drag.rect.width + (dR - dL) * S < minPx) {
            if (L) dL = dR + (drag.rect.width - minPx) / S;
            else if (R) dR = dL - (drag.rect.width - minPx) / S;
        }
        if (drag.rect.height + (dB - dT) * S < minPx) {
            if (T) dT = dB + (drag.rect.height - minPx) / S;
            else if (B) dB = dT - (drag.rect.height - minPx) / S;
        }
        a.offL = drag.oL + dL;
        a.offR = drag.oR + dR;
        a.offT = drag.oT + dT;
        a.offB = drag.oB + dB;
    }

    // ---------------------------------------------------------------- painting

    @Override
    public Dimension getPreferredSize() {
        if (img == null) return new Dimension(200, 100);
        double k = eff();
        return new Dimension((int) (img.getWidth() * k) + 12, (int) (img.getHeight() * k) + 12);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        if (img == null) return;
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double k = eff();
        g.drawImage(img, 6, 6, (int) (img.getWidth() * k), (int) (img.getHeight() * k), null);
        if (editor != null && res != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color blue = new Color(0, 120, 255);
            Renderer.Geo hv = geoOf(hover);
            if (hv != null && hover != selected) outline(g, hv, new Color(0, 120, 255, 140), false, blue);
            Renderer.Geo sg = geoOf(selected);
            if (sg != null) outline(g, sg, blue, true, blue);
        }
        g.dispose();
    }

    private void outline(Graphics2D g, Renderer.Geo geo, Color c, boolean handlesToo, Color hc) {
        g.setColor(c);
        if (geo.arrow != null) {
            Point[] a = arrowScreen(geo);
            g.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10f, new float[]{4f, 3f}, 0f));
            g.drawLine(a[0].x, a[0].y, a[1].x, a[1].y);
            if (handlesToo) {
                for (Point p : a) {
                    g.setColor(Color.WHITE);
                    g.fillOval(p.x - 5, p.y - 5, 10, 10);
                    g.setColor(hc);
                    g.setStroke(new java.awt.BasicStroke(1.5f));
                    g.drawOval(p.x - 5, p.y - 5, 10, 10);
                }
            }
            return;
        }
        Rectangle s = scr(geo.rect);
        g.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10f, new float[]{4f, 3f}, 0f));
        g.drawRect(s.x - 2, s.y - 2, s.width + 4, s.height + 4);
        if (handlesToo) {
            g.setStroke(new java.awt.BasicStroke(1.5f));
            for (Point p : handles(s)) {
                g.setColor(Color.WHITE);
                g.fillRect(p.x - 4, p.y - 4, 8, 8);
                g.setColor(hc);
                g.drawRect(p.x - 4, p.y - 4, 8, 8);
            }
        }
    }

    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(420, 320);
    }

    public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
        return 24;
    }

    public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
        return 160;
    }

    public boolean getScrollableTracksViewportWidth() {
        return zoom <= 0;
    }

    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
