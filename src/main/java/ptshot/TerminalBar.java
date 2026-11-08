package ptshot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Controls for a TERMINAL layer: scheme, dark / light theme, window frame, title, colours, command / indentation styling. */
final class TerminalBar extends JPanel {
    private final Runnable onChange;
    private Block blk;
    private boolean loading;
    private final JComboBox<String> preset = new JComboBox<>(Term.presets().keySet().toArray(new String[0]));
    private final JComboBox<String> theme = new JComboBox<>(new String[]{"Dark", "Light"});
    private final JComboBox<String> chrome = new JComboBox<>(Term.CHROME_NAMES);
    private final JTextField title = new JTextField(14);
    private final ColorButton bgBtn, fgBtn;
    private final JCheckBox cmdStyle = new JCheckBox("Highlight the command"), indent = new JCheckBox("Colour by indentation");

    TerminalBar(Runnable onChange) {
        super();
        this.onChange = onChange;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)), r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)),
                r3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bgBtn = new ColorButton(Color.BLACK, this::colorsChanged);
        fgBtn = new ColorButton(Color.WHITE, this::colorsChanged);
        setBorder(BorderFactory.createTitledBorder("Terminal"));
        preset.setToolTipText("Colour scheme (also sets the window frame and colours)");
        theme.setToolTipText("Dark or light variant of the scheme");
        chrome.setToolTipText("Window frame drawn around the text");
        title.setToolTipText("Text shown in the window title bar");
        bgBtn.setToolTipText("Background colour");
        fgBtn.setToolTipText("Default text colour");
        cmdStyle.setToolTipText("Draw the command after a prompt (C:\\>, PS C:\\>, user@host$) apart from its output");
        indent.setToolTipText("Give output lines a different colour for each indentation level");
        r1.add(new JLabel("Scheme"));
        r1.add(preset);
        r1.add(new JLabel("Theme"));
        r1.add(theme);
        r1.add(new JLabel("Window"));
        r1.add(chrome);
        r2.add(new JLabel("Title"));
        r2.add(title);
        r2.add(new JLabel("Background"));
        r2.add(bgBtn);
        r2.add(new JLabel("Text"));
        r2.add(fgBtn);
        r3.add(cmdStyle);
        r3.add(indent);
        JButton pal = new JButton("Colours...");
        pal.setToolTipText("Edit the 16 ANSI colours plus the command and indentation colours");
        pal.setFocusable(false);
        pal.addActionListener(e -> editPalette());
        r3.add(pal);
        add(r1);
        add(r2);
        add(r3);
        preset.addActionListener(e -> {
            if (loading || blk == null) return;
            applyScheme((String) preset.getSelectedItem(), blk.theme);
        });
        theme.addActionListener(e -> {
            if (loading || blk == null) return;
            applyScheme(blk.preset, theme.getSelectedIndex() == 1 ? "LIGHT" : "DARK");
        });
        chrome.addActionListener(e -> {
            if (loading || blk == null) return;
            blk.chrome = Term.CHROMES[chrome.getSelectedIndex()];
            onChange.run();
        });
        cmdStyle.addActionListener(e -> {
            if (loading || blk == null) return;
            blk.cmdStyle = cmdStyle.isSelected();
            onChange.run();
        });
        indent.addActionListener(e -> {
            if (loading || blk == null) return;
            blk.indentColors = indent.isSelected();
            onChange.run();
        });
        title.getDocument().addDocumentListener(new DocumentListener() {
            void ch() {
                if (loading || blk == null) return;
                blk.title = title.getText();
                onChange.run();
            }

            public void insertUpdate(DocumentEvent e) {
                ch();
            }

            public void removeUpdate(DocumentEvent e) {
                ch();
            }

            public void changedUpdate(DocumentEvent e) {
                ch();
            }
        });
    }

    /** Switches scheme and / or theme: takes over the scheme's colours, frame and title and drops colour overrides. */
    private void applyScheme(String presetName, String themeName) {
        Term.Preset p = Term.preset(presetName);
        blk.preset = p.name;
        blk.theme = themeName;
        Term.Look lk = Term.look(blk);
        blk.background = lk.bg;
        if (blk.bodyFont != null) blk.bodyFont.color = lk.fg;
        blk.palette = null;
        blk.accents = null;
        blk.chrome = p.chrome;
        blk.title = p.title;
        load(blk);
        onChange.run();
    }

    void load(Block b) {
        blk = b;
        loading = true;
        try {
            if (b == null) return;
            preset.setSelectedItem(Term.preset(b.preset).name);
            theme.setSelectedIndex("LIGHT".equals(b.theme) ? 1 : 0);
            int ci = 0;
            for (int i = 0; i < Term.CHROMES.length; i++) if (Term.CHROMES[i].equals(b.chrome)) ci = i;
            chrome.setSelectedIndex(ci);
            title.setText(b.title == null ? "" : b.title);
            bgBtn.set(Term.bg(b));
            fgBtn.set(Term.fg(b));
            cmdStyle.setSelected(b.cmdStyle);
            indent.setSelected(b.indentColors);
        } finally {
            loading = false;
        }
    }

    private void colorsChanged() {
        if (loading || blk == null) return;
        blk.background = bgBtn.get();
        if (blk.bodyFont != null) blk.bodyFont.color = fgBtn.get();
        onChange.run();
    }

    private void editPalette() {
        if (blk == null) return;
        Window w = SwingUtilities.getWindowAncestor(this);
        JDialog d = new JDialog(w, "Terminal colours", Dialog.ModalityType.APPLICATION_MODAL);
        Color[] cur = Term.palette(blk).clone();
        Color[] acc = Term.accents(blk).clone();
        ColorButton[] btns = new ColorButton[16], abtns = new ColorButton[4];

        JPanel grid = new JPanel(new GridLayout(4, 4, 8, 6));
        grid.setBorder(BorderFactory.createTitledBorder("ANSI colours"));
        for (int i = 0; i < 16; i++) {
            final int idx = i;
            btns[i] = new ColorButton(cur[i], () -> {
                cur[idx] = btns[idx].get();
                blk.palette = cur.clone();
                onChange.run();
            });
            JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            cell.add(btns[i]);
            cell.add(new JLabel(Term.COLOR_NAMES[i]));
            grid.add(cell);
        }
        JPanel ag = new JPanel(new GridLayout(1, 4, 8, 6));
        ag.setBorder(BorderFactory.createTitledBorder("Command and indentation"));
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            abtns[i] = new ColorButton(acc[i], () -> {
                acc[idx] = abtns[idx].get();
                blk.accents = acc.clone();
                onChange.run();
            });
            JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            cell.add(abtns[i]);
            cell.add(new JLabel(Term.ACCENT_NAMES[i]));
            ag.add(cell);
        }
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        center.add(grid);
        center.add(ag);

        JButton reset = new JButton("Reset to scheme");
        reset.addActionListener(e -> {
            blk.palette = null;
            blk.accents = null;
            Color[] p = Term.palette(blk), a2 = Term.accents(blk);
            for (int i = 0; i < 16; i++) {
                cur[i] = p[i];
                btns[i].set(p[i]);
            }
            for (int i = 0; i < 4; i++) {
                acc[i] = a2[i];
                abtns[i].set(a2[i]);
            }
            onChange.run();
        });
        JButton close = new JButton("Close");
        close.addActionListener(e -> d.dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        bottom.add(reset);
        bottom.add(close);
        d.getContentPane().add(center, BorderLayout.CENTER);
        d.getContentPane().add(bottom, BorderLayout.SOUTH);
        d.pack();
        d.setMinimumSize(new Dimension(640, 0));
        d.setLocationRelativeTo(w);
        d.setVisible(true);
    }
}
