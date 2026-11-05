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
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;

/** Shows an image and lets the user drag a rectangle over the part to keep. */
final class SelectPanel extends JPanel implements Scrollable {
    private BufferedImage img;
    private double zoom = 0; // 0 = fit
    private Rectangle sel;   // image pixels
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
        sel = new Rectangle(x1, y1, x2 - x1, y2 - y1);
        repaint();
        firePropertyChange("selection", null, sel);
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
        d.setSize(Math.min(1300, (int) (scr.width * 0.9)), Math.min(850, (int) (scr.height * 0.9)));
        d.setLocationRelativeTo(owner);
        d.setVisible(true); // modal: returns when closed
        return result[0];
    }
}
