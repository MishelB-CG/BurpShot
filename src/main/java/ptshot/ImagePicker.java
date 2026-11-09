package ptshot;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.IntFunction;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JRadioButton;
import javax.swing.ButtonGroup;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;

/** Shows an image and lets the user drag a rectangle over the part to keep. */
final class SelectPanel extends JPanel implements Scrollable {
    private BufferedImage img;
    private double zoom = 0; // 0 = fit
    private Rectangle sel;   // image pixels
    private int mode;        // 0 = rectangle, 1 = horizontal band (full width), 2 = vertical band (full height)
    private Point anchor;

    SelectPanel() {
        setBackground(new Color(90, 90, 90));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                anchor = toImg(e);
                sel = null;
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (anchor == null) return;
                select(anchor, toImg(e));
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    void setImage(BufferedImage i) {
        img = i;
        sel = null;
        revalidate();
        repaint();
    }

    /** 0 = free rectangle, 1 = horizontal band across the whole width, 2 = vertical band down the whole height. Clears the selection. */
    void setMode(int m) {
        mode = m;
        sel = null;
        repaint();
        firePropertyChange("selection", null, null);
    }

    void setZoom(double z) {
        zoom = z;
        revalidate();
        repaint();
    }

    /** Programmatic selection in image pixels (also used by the mouse handler). */
    void select(Point a, Point b) {
        if (img == null) return;
        int x1 = Math.max(0, Math.min(img.getWidth(), Math.min(a.x, b.x)));
        int y1 = Math.max(0, Math.min(img.getHeight(), Math.min(a.y, b.y)));
        int x2 = Math.max(0, Math.min(img.getWidth(), Math.max(a.x, b.x)));
        int y2 = Math.max(0, Math.min(img.getHeight(), Math.max(a.y, b.y)));
        if (mode == 1) { // "ray" selection: the whole width, only the dragged height
            x1 = 0;
            x2 = img.getWidth();
        } else if (mode == 2) { // the whole height, only the dragged width
            y1 = 0;
            y2 = img.getHeight();
        }
        sel = new Rectangle(x1, y1, x2 - x1, y2 - y1);
        repaint();
        firePropertyChange("selection", null, sel);
    }

    Rectangle selRect() {
        return hasSelection() ? new Rectangle(sel) : null;
    }

    boolean hasSelection() {
        return sel != null && sel.width >= 3 && sel.height >= 3;
    }

    BufferedImage selection() {
        if (!hasSelection()) return null;
        BufferedImage out = new BufferedImage(sel.width, sel.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img.getSubimage(sel.x, sel.y, sel.width, sel.height), 0, 0, null);
        g.dispose();
        return out;
    }

    BufferedImage whole() {
        return img;
    }

    private double k() {
        if (img == null) return 1;
        if (zoom > 0) return zoom;
        int vw = getParent() != null ? getParent().getWidth() : img.getWidth();
        int vh = getParent() != null ? getParent().getHeight() : img.getHeight();
        return Math.min(1.0, Math.min((vw - 12) / (double) img.getWidth(), (vh - 12) / (double) img.getHeight()));
    }

    private Point toImg(MouseEvent e) {
        double k = k();
        return new Point((int) Math.round((e.getX() - 6) / k), (int) Math.round((e.getY() - 6) / k));
    }

    @Override
    public Dimension getPreferredSize() {
        if (img == null) return new Dimension(200, 100);
        double k = k();
        return new Dimension((int) (img.getWidth() * k) + 12, (int) (img.getHeight() * k) + 12);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        if (img == null) return;
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double k = k();
        int w = (int) Math.round(img.getWidth() * k), h = (int) Math.round(img.getHeight() * k);
        g.drawImage(img, 6, 6, w, h, null);
        if (sel != null && sel.width > 0 && sel.height > 0) {
            int sx = 6 + (int) Math.round(sel.x * k), sy = 6 + (int) Math.round(sel.y * k);
            int sw = (int) Math.round(sel.width * k), sh = (int) Math.round(sel.height * k);
            g.setColor(new Color(0, 0, 0, 120));
            g.fillRect(6, 6, w, sy - 6);
            g.fillRect(6, sy + sh, w, 6 + h - (sy + sh));
            g.fillRect(6, sy, sx - 6, sh);
            g.fillRect(sx + sw, sy, 6 + w - (sx + sw), sh);
            g.setColor(new Color(255, 40, 40));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f));
            g.drawRect(sx, sy, sw, sh);
        }
        g.dispose();
    }

    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(800, 520);
    }

    public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
        return 24;
    }

    public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
        return 200;
    }

    public boolean getScrollableTracksViewportWidth() {
        return zoom <= 0;
    }

    public boolean getScrollableTracksViewportHeight() {
        return zoom <= 0;
    }
}

final class ImagePicker {
    private ImagePicker() {}

    /** Result of {@link #edit}: the edited pixels plus where the top-left corner of the result lies in the original. */
    static final class Edited {
        final BufferedImage img;
        final int dx, dy;

        Edited(BufferedImage img, int dx, int dy) {
            this.img = img;
            this.dx = dx;
            this.dy = dy;
        }
    }

    private static BufferedImage argb(BufferedImage src) {
        BufferedImage o = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = o.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return o;
    }

    /** Result of {@link #cutOutBand}: the joined image and how far its top-left corner moved in the original (kept in place on screen). */
    static final class Cut {
        final BufferedImage img;
        final int dx, dy;

        Cut(BufferedImage img, int dx, int dy) {
            this.img = img;
            this.dx = dx;
            this.dy = dy;
        }
    }

    /**
     * Removes a whole band from the image and joins what is left. {@code r} is the band: for a horizontal band only its y / height
     * matter (it spans the full width), for a vertical band only x / width (full height). The caller makes sure something remains.
     */
    static Cut cutOutBand(BufferedImage src, Rectangle r, boolean horizontal) {
        int w0 = src.getWidth(), h0 = src.getHeight();
        int removed = horizontal ? r.height : r.width;
        BufferedImage out = new BufferedImage(horizontal ? w0 : w0 - removed, horizontal ? h0 - removed : h0, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        int dx = 0, dy = 0;
        if (horizontal) {
            if (r.y > 0) g.drawImage(src.getSubimage(0, 0, w0, r.y), 0, 0, null);                       // part above the band
            int below = h0 - (r.y + r.height);
            if (below > 0) g.drawImage(src.getSubimage(0, r.y + r.height, w0, below), 0, r.y, null);   // part below, moved up
            if (r.y == 0) dy = r.height; // the first rows are gone: the rest keeps its place on screen
        } else {
            if (r.x > 0) g.drawImage(src.getSubimage(0, 0, r.x, h0), 0, 0, null);                      // part left of the band
            int right = w0 - (r.x + r.width);
            if (right > 0) g.drawImage(src.getSubimage(r.x + r.width, 0, right, h0), r.x, 0, null);   // part right, moved left
            if (r.x == 0) dx = r.width;
        }
        g.dispose();
        return new Cut(out, dx, dy);
    }

    /** Crop / cut-out dialog: select a region, then keep it (Crop) or make it transparent (Cut out). null = nothing changed. */
    static Edited edit(Window owner, String title, BufferedImage src) {
        final BufferedImage[] cur = {argb(src)};
        final int[] org = {0, 0};
        final boolean[] changed = {false};
        final java.util.ArrayDeque<Object[]> hist = new java.util.ArrayDeque<>();
        final Edited[] result = new Edited[1];
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        SelectPanel sp = new SelectPanel();
        sp.setImage(cur[0]);
        JLabel info = new JLabel(" ");
        JButton crop = new JButton("Crop to selection");
        JButton cut = new JButton("Erase selection");
        JButton join = new JButton("Cut out and join");
        JButton undo = new JButton("Undo");
        JButton done = new JButton("Done");
        JButton cancel = new JButton("Cancel");
        crop.setToolTipText("Keep only the selected region");
        cut.setToolTipText("Erase the selected region but keep the layer's size (the area becomes transparent)");
        join.setToolTipText("Remove the selected band completely and join the two remaining parts (use Horizontal or Vertical band)");
        JRadioButton rbRect = new JRadioButton("Rectangle", true), rbH = new JRadioButton("Horizontal band"), rbV = new JRadioButton("Vertical band");
        rbRect.setToolTipText("Free rectangle");
        rbH.setToolTipText("Selects the whole width: drag up / down to choose which rows");
        rbV.setToolTipText("Selects the whole height: drag left / right to choose which columns");
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(rbRect);
        modeGroup.add(rbH);
        modeGroup.add(rbV);
        final int[] mode = {0};
        crop.setEnabled(false);
        cut.setEnabled(false);
        join.setEnabled(false);
        undo.setEnabled(false);
        sp.addPropertyChangeListener("selection", e -> {
            crop.setEnabled(sp.hasSelection());
            cut.setEnabled(sp.hasSelection());
            join.setEnabled(sp.hasSelection() && mode[0] != 0);
        });
        java.awt.event.ActionListener modeChanged = e -> {
            mode[0] = rbH.isSelected() ? 1 : rbV.isSelected() ? 2 : 0;
            sp.setMode(mode[0]);
        };
        rbRect.addActionListener(modeChanged);
        rbH.addActionListener(modeChanged);
        rbV.addActionListener(modeChanged);
        Runnable refresh = () -> {
            sp.setImage(cur[0]);
            crop.setEnabled(false);
            cut.setEnabled(false);
            join.setEnabled(false);
            undo.setEnabled(!hist.isEmpty());
            info.setText("  " + cur[0].getWidth() + " x " + cur[0].getHeight() + " px");
        };
        crop.addActionListener(e -> {
            Rectangle r = sp.selRect();
            if (r == null) return;
            hist.push(new Object[]{cur[0], org[0], org[1]});
            BufferedImage sub = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = sub.createGraphics();
            g.drawImage(cur[0], -r.x, -r.y, null);
            g.dispose();
            org[0] += r.x;
            org[1] += r.y;
            cur[0] = sub;
            changed[0] = true;
            refresh.run();
        });
        cut.addActionListener(e -> {
            Rectangle r = sp.selRect();
            if (r == null) return;
            hist.push(new Object[]{cur[0], org[0], org[1]});
            BufferedImage n = argb(cur[0]);
            Graphics2D g = n.createGraphics();
            g.setComposite(java.awt.AlphaComposite.Clear);
            g.fillRect(r.x, r.y, r.width, r.height);
            g.dispose();
            cur[0] = n;
            changed[0] = true;
            refresh.run();
        });
        join.addActionListener(e -> {
            Rectangle r = sp.selRect();
            if (r == null || mode[0] == 0) return;
            BufferedImage src0 = cur[0];
            int w0 = src0.getWidth(), h0 = src0.getHeight();
            boolean horizontal = mode[0] == 1;
            int removed = horizontal ? r.height : r.width, total = horizontal ? h0 : w0;
            if (removed >= total) {
                JOptionPane.showMessageDialog(d, "That would remove everything - leave some of the layer.");
                return;
            }
            hist.push(new Object[]{cur[0], org[0], org[1]});
            Cut c = cutOutBand(src0, r, horizontal);
            org[0] += c.dx;
            org[1] += c.dy;
            BufferedImage out = c.img;
            cur[0] = out;
            changed[0] = true;
            refresh.run();
        });
        undo.addActionListener(e -> {
            if (hist.isEmpty()) return;
            Object[] h = hist.pop();
            cur[0] = (BufferedImage) h[0];
            org[0] = (Integer) h[1];
            org[1] = (Integer) h[2];
            changed[0] = !hist.isEmpty();
            refresh.run();
        });
        done.addActionListener(e -> {
            if (changed[0]) result[0] = new Edited(cur[0], org[0], org[1]);
            d.dispose();
        });
        cancel.addActionListener(e -> d.dispose());
        JComboBox<String> zoom = new JComboBox<>(new String[]{"Fit", "50%", "100%", "200%"});
        zoom.addActionListener(e -> {
            String z = (String) zoom.getSelectedItem();
            sp.setZoom(z.equals("Fit") ? 0 : Integer.parseInt(z.replace("%", "")) / 100.0);
        });
        // two rows so that nothing is cut off at large font sizes: what to select, then what to do with it
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        row1.add(new JLabel("Zoom"));
        row1.add(zoom);
        row1.add(new JLabel("   Select:"));
        row1.add(rbRect);
        row1.add(rbH);
        row1.add(rbV);
        row1.add(info);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        row2.add(new JLabel("Then:"));
        row2.add(crop);
        row2.add(cut);
        row2.add(join);
        row2.add(undo);
        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        top.add(row1);
        top.add(row2);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        bottom.add(done);
        bottom.add(cancel);
        d.getContentPane().add(top, BorderLayout.NORTH);
        d.getContentPane().add(new JScrollPane(sp), BorderLayout.CENTER);
        d.getContentPane().add(bottom, BorderLayout.SOUTH);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        refresh.run();
        // wide enough for the toolbar at the current font size (Burp's UI scale can make the buttons much wider), but never off-screen
        int need = Math.max(row1.getPreferredSize().width, row2.getPreferredSize().width) + 40;
        d.setSize(Math.min(scr.width - 20, Math.max(Math.min(1200, (int) (scr.width * 0.85)), need)), Math.min(800, (int) (scr.height * 0.85)));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return result[0];
    }

    /**
     * @param rerender optional: renders the source at a given width (HTML mode); null for a plain image.
     * @return the chosen part of the image, or null if cancelled
     */
    static BufferedImage show(Window owner, String title, BufferedImage initial, IntFunction<BufferedImage> rerender, int width0) {
        final BufferedImage[] result = new BufferedImage[1];
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        SelectPanel sp = new SelectPanel();
        sp.setImage(initial);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JComboBox<String> zoom = new JComboBox<>(new String[]{"Fit", "50%", "100%", "200%"});
        zoom.addActionListener(e -> {
            String s = (String) zoom.getSelectedItem();
            sp.setZoom(s.equals("Fit") ? 0 : Integer.parseInt(s.replace("%", "")) / 100.0);
        });
        top.add(new JLabel("Zoom"));
        top.add(zoom);
        if (rerender != null) {
            JSpinner w = new JSpinner(new SpinnerNumberModel(width0, 200, 3000, 20));
            w.addChangeListener(e -> sp.setImage(rerender.apply(((Number) w.getValue()).intValue())));
            top.add(new JLabel("   Render width (px)"));
            top.add(w);
        }
        top.add(new JLabel("   Drag a rectangle over the part you want."));

        JButton use = new JButton("Use selection");
        use.setEnabled(false);
        sp.addPropertyChangeListener("selection", e -> use.setEnabled(sp.hasSelection()));
        use.addActionListener(e -> {
            result[0] = sp.selection();
            d.dispose();
        });
        JButton whole = new JButton("Use whole image");
        whole.addActionListener(e -> {
            result[0] = sp.whole();
            d.dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> d.dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        bottom.add(use);
        bottom.add(whole);
        bottom.add(cancel);

        d.getContentPane().add(top, BorderLayout.NORTH);
        d.getContentPane().add(new JScrollPane(sp), BorderLayout.CENTER);
        d.getContentPane().add(bottom, BorderLayout.SOUTH);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        int need = Math.max(top.getPreferredSize().width, bottom.getPreferredSize().width) + 40; // toolbar must fit at large font sizes
        d.setSize(Math.min(scr.width - 20, Math.max(Math.min(1300, (int) (scr.width * 0.9)), need)), Math.min(850, (int) (scr.height * 0.9)));
        d.setLocationRelativeTo(owner);
        d.setVisible(true); // modal: returns when closed
        return result[0];
    }
}
