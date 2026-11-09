package ptshot;

import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/** Edits one {@link Border}: on/off, colour, width, curvature and shadow. Used for a layer and for the whole capture. */
final class BorderEditor extends JPanel {
    private final JCheckBox on = new JCheckBox("Draw a border");
    private final ColorButton color;
    private final JSpinner width = spin(2, Border.MAX_WIDTH), radius = spin(0, Border.MAX_RADIUS), shadow = spin(0, Border.MAX_SHADOW);
    private final JSlider widthS = slider(Border.MAX_WIDTH), radiusS = slider(Border.MAX_RADIUS), shadowS = slider(Border.MAX_SHADOW);
    private final Runnable onChange;
    private boolean loading;

    BorderEditor(Runnable onChange) {
        super(new GridBagLayout());
        this.onChange = onChange;
        color = new ColorButton(Color.BLACK, this::changed);
        on.addActionListener(e -> {
            if (on.isSelected() && width.getValue().equals(0) && ((Number) shadow.getValue()).intValue() == 0) width.setValue(2);
            enableAll();
            changed();
        });
        link(width, widthS);
        link(radius, radiusS);
        link(shadow, shadowS);
        int y = 0;
        add(on, 0, y++, 3);
        addRow("Border color", color, null, y++);
        addRow("Border width (px)", width, widthS, y++);
        addRow("Border curvature (px)", radius, radiusS, y++);
        addRow("Shadow (%)", shadow, shadowS, y++);
        javax.swing.JButton preset = new javax.swing.JButton("Drop shadow preset");
        preset.setToolTipText("A soft shadow with a thin black border");
        preset.addActionListener(e -> {
            loading = true;
            on.setSelected(true);
            width.setValue(1);
            shadow.setValue(45);
            loading = false;
            enableAll();
            changed();
        });
        add(preset, 0, y, 3);
        enableAll();
    }

    private static JSpinner spin(int v, int max) {
        return new JSpinner(new SpinnerNumberModel(v, 0, max, 1));
    }

    private static JSlider slider(int max) {
        JSlider s = new JSlider(0, max, 0);
        s.setFocusable(false);
        return s;
    }

    private void link(JSpinner sp, JSlider sl) {
        sp.addChangeListener(e -> {
            sl.setValue(((Number) sp.getValue()).intValue());
            changed();
        });
        sl.addChangeListener(e -> {
            if (((Number) sp.getValue()).intValue() != sl.getValue()) sp.setValue(sl.getValue());
        });
    }

    private void add(java.awt.Component c, int x, int y, int span) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = x;
        g.gridy = y;
        g.gridwidth = span;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(3, 4, 3, 4);
        super.add(c, g);
    }

    private void addRow(String label, java.awt.Component field, java.awt.Component extra, int y) {
        add(new JLabel(label), 0, y, 1);
        add(field, 1, y, extra == null ? 2 : 1);
        if (extra != null) add(extra, 2, y, 1);
    }

    private void enableAll() {
        boolean e = on.isSelected();
        color.setEnabled(e);
        for (java.awt.Component c : new java.awt.Component[]{width, radius, shadow, widthS, radiusS, shadowS}) c.setEnabled(e);
    }

    private void changed() {
        if (!loading) onChange.run();
    }

    /** Shows {@code b}; null means no border. Does not call the change listener. */
    void load(Border b) {
        loading = true;
        on.setSelected(b != null && !b.isNone());
        Border v = b == null ? new Border() : b;
        color.set(v.color);
        width.setValue(v.width);
        radius.setValue(v.radius);
        shadow.setValue(v.shadow);
        enableAll();
        loading = false;
    }

    /** The border as edited, or null if switched off (or invisible). */
    Border value() {
        if (!on.isSelected()) return null;
        Border b = new Border();
        b.color = color.get();
        b.width = ((Number) width.getValue()).intValue();
        b.radius = ((Number) radius.getValue()).intValue();
        b.shadow = ((Number) shadow.getValue()).intValue();
        b.clamp();
        return b.isNone() ? null : b;
    }

    /** A modal dialog for the whole-capture border. Changes are applied live through {@code live}; Cancel restores {@code current}. */
    static void edit(java.awt.Component parent, String title, Border current, java.util.function.Consumer<Border> live) {
        showDialog(parent, title, current == null ? null : current.copy(), live);
    }

    private static void showDialog(java.awt.Component parent, String title, Border original, java.util.function.Consumer<Border> live) {
        java.awt.Window w = Ui.window(parent);
        javax.swing.JDialog d = new javax.swing.JDialog(w, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        final BorderEditor[] ed = new BorderEditor[1];
        ed[0] = new BorderEditor(() -> live.accept(ed[0].value()));
        ed[0].load(original);
        boolean[] ok = {false};
        javax.swing.JButton okB = new javax.swing.JButton("OK"), cancel = new javax.swing.JButton("Cancel");
        okB.addActionListener(e -> {
            ok[0] = true;
            d.dispose();
        });
        cancel.addActionListener(e -> d.dispose());
        JPanel south = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 6));
        south.add(okB);
        south.add(cancel);
        d.getContentPane().add(ed[0], java.awt.BorderLayout.CENTER);
        d.getContentPane().add(south, java.awt.BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(okB);
        d.pack();
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
        if (!ok[0]) live.accept(original == null ? null : original.copy()); // cancelled: put the old look back
    }
}
