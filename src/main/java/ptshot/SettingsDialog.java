package ptshot;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/** Global defaults. Changes apply live to every capture and are persisted by the extension. */
final class SettingsDialog {
    private SettingsDialog() {}

    private static JSpinner spin(int v, int min, int max) {
        return new JSpinner(new SpinnerNumberModel(v, min, max, 1));
    }

    static void open(Window owner, Settings st, Runnable changed) {
        JDialog d = new JDialog(owner, "PT Screenshot - global settings", Dialog.ModalityType.MODELESS);
        Form f = new Form();

        // the callbacks need the editors themselves, so build via holder arrays
        final FontEditor[] fe = new FontEditor[2];
        fe[0] = new FontEditor(false, () -> {
            st.labelFont = fe[0].value();
            changed.run();
        });
        fe[1] = new FontEditor(false, () -> {
            st.bodyFont = fe[1].value();
            changed.run();
        });
        fe[0].load(null, st.labelFont);
        fe[1].load(null, st.bodyFont);

        f.span(new JLabel("<html><b>Label font</b> (\"Request\" / \"Response\" / custom headers). Default: Calibri, red</html>"));
        f.span(fe[0]);
        JSpinner ratio = new JSpinner(new SpinnerNumberModel(st.labelRatio, 0.0, 4.0, 0.1));
        ratio.setToolTipText("Label size = body font size x this value, so labels keep their proportion when Burp's font is large or small. 0 = use the fixed size above.");
        ratio.addChangeListener(e -> {
            st.labelRatio = ((Number) ratio.getValue()).doubleValue();
            changed.run();
        });
        f.row("Label size = body size \u00d7 (0 = fixed size above)", ratio);
        f.span(new JLabel("<html><b>Body font</b> (default: Burp's message-editor font)</html>"));
        f.span(fe[1]);

        f.span(new JLabel("<html><b>Colours</b></html>"));
        ColorButton[] cb = new ColorButton[2];
        cb[0] = new ColorButton(st.background, () -> {
            st.background = cb[0].get();
            changed.run();
        });
        cb[1] = new ColorButton(st.sepColor, () -> {
            st.sepColor = cb[1].get();
            changed.run();
        });
        f.row("Image background", cb[0]);
        f.row("Separator line colour", cb[1]);

        JSpinner sw = spin(st.sepWidth, 1, 20), mg = spin(st.margin, 0, 100), rg = spin(st.rowGap, 0, 100),
                cg = spin(st.colGap, 0, 200), lg = spin(st.labelGap, 0, 50), wc = spin(st.wrapCols, 0, 500),
                ml = spin(st.maxLines, 0, 600), mwd = spin(st.maxWidth, 0, 5000);
        JSpinner sc = new JSpinner(new SpinnerNumberModel(st.scale, 1.0, 4.0, 0.5));
        JCheckBox wrap = new JCheckBox("Wrap long lines by default (off = cut with ...)", st.wrap);
        sw.addChangeListener(e -> {
            st.sepWidth = (Integer) sw.getValue();
            changed.run();
        });
        mg.addChangeListener(e -> {
            st.margin = (Integer) mg.getValue();
            changed.run();
        });
        rg.addChangeListener(e -> {
            st.rowGap = (Integer) rg.getValue();
            changed.run();
        });
        cg.addChangeListener(e -> {
            st.colGap = (Integer) cg.getValue();
            changed.run();
        });
        lg.addChangeListener(e -> {
            st.labelGap = (Integer) lg.getValue();
            changed.run();
        });
        wc.addChangeListener(e -> {
            st.wrapCols = (Integer) wc.getValue();
            changed.run();
        });
        ml.addChangeListener(e -> {
            st.maxLines = (Integer) ml.getValue();
            changed.run();
        });
        sc.addChangeListener(e -> {
            st.scale = ((Number) sc.getValue()).doubleValue();
            changed.run();
        });
        mwd.addChangeListener(e -> {
            st.maxWidth = (Integer) mwd.getValue();
            changed.run();
        });
        wrap.addActionListener(e -> {
            st.wrap = wrap.isSelected();
            changed.run();
        });
        f.span(new JLabel("<html><b>Spacing</b> (1x pixels)</html>"));
        f.row("Separator thickness", sw);
        f.row("Outer margin", mg);
        f.row("Gap between rows", rg);
        f.row("Gap between side-by-side blocks", cg);
        f.row("Gap between label and body", lg);
        f.span(new JLabel("<html><b>Defaults for NEW captures</b> (existing blocks keep their own values)</html>"));
        f.row("Max screenshot width px (0 = off)", mwd);
        f.row("Wrap at columns (0 = off)", wc);
        f.row("Max lines per block (0 = all)", ml);
        f.span(wrap);
        f.row("Output scale (pixel density)", sc);

        f.span(new JLabel("<html><b>Syntax colours</b> (Burp light theme by default)</html>"));
        ColorButton[] sy = new ColorButton[st.syn.length];
        for (int i = 0; i < sy.length; i++) {
            final int k = i;
            sy[i] = new ColorButton(st.syn[i], () -> {
                st.syn[k] = sy[k].get();
                changed.run();
            });
            f.row(Settings.SYN_NAMES[i], sy[i]);
        }

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("Close");
        close.addActionListener(e -> d.dispose());
        south.add(close);
        d.getContentPane().add(new JScrollPane(f.top()), BorderLayout.CENTER);
        d.getContentPane().add(south, BorderLayout.SOUTH);
        d.setSize(560, 720);
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }
}
