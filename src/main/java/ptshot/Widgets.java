package ptshot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
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
    private BufferedImage img;
    private double zoom = 0; // 0 = fit width (never upscales)

    PreviewPanel() {
        setBackground(new Color(214, 214, 214));
    }

    void setImage(BufferedImage i) {
        img = i;
        revalidate();
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
        g.dispose();
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
