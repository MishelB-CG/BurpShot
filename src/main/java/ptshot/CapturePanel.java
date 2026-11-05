package ptshot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.DefaultCaret;

/** One screenshot being built: block list + per-block editor on the left, live preview on the right. */
final class CapturePanel extends JPanel {
    private static final Pattern HDR_PREFIX = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+:\\s*");

    private final Settings st;
    private final Runnable onClose, openSettings;
    private final DefaultListModel<Block> model = new DefaultListModel<>();
    private final JList<Block> list = new JList<>(model);
    private final PreviewPanel preview = new PreviewPanel();
    private PreviewPanel winPanel;
    private JFrame win;
    private Block cur;
    private boolean loading;
    private double scale;
    private BufferedImage last;
    private final javax.swing.Timer timer;
    private final JLabel status = new JLabel(" ");
    private JTabbedPane tabs;

    // content tab
    private JCheckBox incFirst, incHeaders, incBody, pretty;
    private Checklist hdrList, parList;
    private JSpinner maxBody;
    private JTextArea ta;
    private JPanel filterPanel;

    // annotation tab
    private final DefaultListModel<Annotation> annModel = new DefaultListModel<>();
    private final JList<Annotation> annList = new JList<>(annModel);
    private ColorButton aColor;
    private JSpinner aWidth, aPad, aAngle, aLen, aHead, aDx, aDy, aStrength;
    private JCheckBox aFull, aBlock;
    private JComboBox<Annotation.Edge> aEdge;

    // style tab
    private JCheckBox showLabel, highlight, beside, sepAfter, wrap;
    private JTextField labelField;
    private JComboBox<String> align;
    private FontEditor labelFont, bodyFont;
    private OptColor bgOpt, sepOpt;
    private JSpinner sepWidth, wrapCols, maxLines, imgMax;

    CapturePanel(Settings st, List<Block> init, Runnable onClose, Runnable openSettings) {
        super(new BorderLayout());
        this.st = st;
        this.onClose = onClose;
        this.openSettings = openSettings;
        this.scale = st.scale;
        for (Block b : init) model.addElement(b);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeft(), buildRight());
        split.setDividerLocation(540);
        split.setResizeWeight(0.0);
        add(split, BorderLayout.CENTER);
        timer = new javax.swing.Timer(120, e -> renderNow());
        timer.setRepeats(false);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) select(list.getSelectedValue());
        });
        annList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadAnn();
        });
        if (!model.isEmpty()) list.setSelectedIndex(0);
        renderNow();
    }

    // ================================================================== layout

    private JPanel buildLeft() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 2));
        JScrollPane ls = new JScrollPane(list);
        ls.setPreferredSize(new Dimension(300, 104));
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createTitledBorder("Blocks (top to bottom)"));
        top.add(ls, BorderLayout.CENTER);
        top.add(blockButtons(), BorderLayout.SOUTH);
        tabs = new JTabbedPane();
        tabs.addTab("Content", contentTab());
        tabs.addTab("Annotations", annTab());
        tabs.addTab("Style & layout", styleTab());
        p.add(top, BorderLayout.NORTH);
        p.add(tabs, BorderLayout.CENTER);
        return p;
    }

    private JPanel blockButtons() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        JButton up = btn("\u25b2", "Move block up", e -> move(-1));
        JButton down = btn("\u25bc", "Move block down", e -> move(1));
        JButton addTxt = btn("+ Text / header", "Add a custom text block (use it for custom headers, footers, notes)", e -> addCustom());
        JButton addImg = btn("+ Image \u25be", "Add an image block (e.g. a rendered page)", null);
        addImg.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            JMenuItem f = new JMenuItem("From file...");
            f.addActionListener(x -> addImageFromFile());
            JMenuItem c = new JMenuItem("From clipboard");
            c.addActionListener(x -> addImageFromClipboard());
            m.add(f);
            m.add(c);
            m.show(addImg, 0, addImg.getHeight());
        });
        JButton dup = btn("Duplicate", "Duplicate selected block", e -> duplicate());
        JButton rem = btn("Remove", "Remove selected block", e -> remove());
        for (JButton b : new JButton[]{up, down, addTxt, addImg, dup, rem}) p.add(b);
        return p;
    }

    private JPanel contentTab() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        filterPanel = new JPanel();
        filterPanel.setLayout(new BoxLayout(filterPanel, BoxLayout.Y_AXIS));
        incFirst = new JCheckBox("First line", true);
        incHeaders = new JCheckBox("Headers", true);
        incBody = new JCheckBox("Body", true);
        pretty = new JCheckBox("Pretty-print JSON", false);
        for (JCheckBox c : new JCheckBox[]{incFirst, incHeaders, incBody, pretty}) c.addActionListener(e -> filtersChanged());
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        r1.add(incFirst);
        r1.add(incHeaders);
        r1.add(incBody);
        r1.add(pretty);
        hdrList = new Checklist("Headers (untick to hide)");
        parList = new Checklist("Form parameters (untick to hide)");
        JPanel r2 = new JPanel(new GridLayout(1, 2, 4, 0));
        r2.add(hdrList);
        r2.add(parList);
        maxBody = ispin(0, 0, 1_000_000, 100);
        maxBody.addChangeListener(e -> filtersChanged());
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        r3.add(new JLabel("Max body chars (0 = all)"));
        r3.add(maxBody);
        r3.add(btn("Rebuild text from original", "Discard manual edits and rebuild from the captured message", e -> filtersChanged()));
        JLabel hint = new JLabel("<html><body style='width:470px'><i>Select text in the box below, then use the buttons. Changing a filter rebuilds the text (manual edits are lost).</i></body></html>");
        filterPanel.add(r1);
        filterPanel.add(r2);
        filterPanel.add(r3);
        filterPanel.add(hint);

        ta = new JTextArea();
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ta.setCaret(new DefaultCaret() {
            @Override
            public void setSelectionVisible(boolean v) {
                super.setSelectionVisible(true); // keep the selection visible while other controls have focus
            }

            @Override
            public void focusLost(FocusEvent e) {
                setVisible(false);
            }
        });
        ta.getDocument().addDocumentListener(new DocumentListener() {
            void ch() {
                if (loading || cur == null || cur.kind == Block.Kind.IMAGE) return;
                cur.text = ta.getText();
                annList.repaint();
                refresh();
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

        JPanel ops = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
        ops.add(new JLabel("Part of a value:"));
        ops.add(btn("Selection \u2192 ...", "Replace the selected text with ...", e -> replaceSel()));
        ops.add(btn("Ellipsize outside", "Keep the header name and the selection, replace the rest of the line with ...", e -> ellipsizeOutside()));
        JPanel ops2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
        ops2.add(new JLabel("Whole lines:"));
        ops2.add(btn("Delete", "Delete the selected line(s)", e -> lineOp(0)));
        ops2.add(btn("Collapse to ...", "Replace the selected line(s) with a single ... line", e -> lineOp(1)));
        ops2.add(btn("Keep only these", "Keep only the selected line(s), collapse the rest into ...", e -> lineOp(2)));
        JPanel adds = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
        adds.add(new JLabel("Annotate selection:"));
        adds.add(btn("Box", "Red rectangle around the selected text", e -> addAnn(Annotation.Type.BOX)));
        adds.add(btn("Arrow", "Arrow starting at the selected text", e -> addAnn(Annotation.Type.ARROW)));
        adds.add(btn("Pixelate", "Pixelate the selected text", e -> addAnn(Annotation.Type.PIXELATE)));
        adds.add(btn("Blur", "Blur the selected text", e -> addAnn(Annotation.Type.BLUR)));
        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.add(ops);
        south.add(ops2);
        south.add(adds);

        p.add(filterPanel, BorderLayout.NORTH);
        p.add(new JScrollPane(ta), BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JPanel annTab() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        annList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foc);
                Annotation a = (Annotation) v;
                if (cur != null && !cur.text.contains(a.anchor)) {
                    c.setText(c.getText() + "   (text not found - hidden)");
                    if (!sel) c.setForeground(Color.GRAY);
                }
                return c;
            }
        });
        JScrollPane sp = new JScrollPane(annList);
        sp.setPreferredSize(new Dimension(300, 90));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
        bar.add(btn("Remove", "Remove the selected annotation", e -> removeAnn()));
        bar.add(btn("Re-anchor to selection", "Attach the selected annotation to the text currently selected in the Content tab", e -> reanchor()));
        JPanel top = new JPanel(new BorderLayout());
        top.add(sp, BorderLayout.CENTER);
        top.add(bar, BorderLayout.SOUTH);

        aColor = new ColorButton(Color.RED, this::annChanged);
        aWidth = dspin(2, 0.5, 60, 0.5);
        aPad = ispin(3, 0, 60, 1);
        aFull = new JCheckBox("Stretch to full text width");
        aBlock = new JCheckBox("Gradient block arrow (off = simple line + head)");
        aAngle = dspin(90, -360, 360, 5);
        aLen = ispin(90, 5, 1500, 5);
        aHead = ispin(16, 4, 120, 1);
        aEdge = new JComboBox<>(Annotation.Edge.values());
        aDx = ispin(0, -1000, 1000, 2);
        aDy = ispin(0, -1000, 1000, 2);
        aStrength = ispin(8, 1, 80, 1);
        for (JSpinner s : new JSpinner[]{aWidth, aPad, aAngle, aLen, aHead, aDx, aDy, aStrength}) s.addChangeListener(e -> annChanged());
        aFull.addActionListener(e -> annChanged());
        aBlock.addActionListener(e -> annChanged());
        aEdge.addActionListener(e -> annChanged());

        Form f = new Form();
        f.row("Colour", wrapLeft(aColor));
        f.row("Line / stem width", aWidth);
        f.row("Padding around text", aPad);
        f.span(aFull);
        f.span(new JLabel("Arrow:"));
        f.span(aBlock);
        f.row("Angle (0 = right, 90 = down)", aAngle);
        f.row("Length", aLen);
        f.row("Head size", aHead);
        f.row("Start at edge of text", aEdge);
        f.row("Offset X / Y", twin(aDx, aDy));
        f.span(new JLabel("Pixelate / blur:"));
        f.row("Strength (cell size / radius)", aStrength);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(f.top()), BorderLayout.CENTER);
        return p;
    }

    private JPanel styleTab() {
        showLabel = new JCheckBox("Show label");
        labelField = new JTextField(14);
        align = new JComboBox<>(new String[]{"Left", "Centre", "Right"});
        labelFont = new FontEditor(true, this::styleChanged);
        bodyFont = new FontEditor(true, this::styleChanged);
        highlight = new JCheckBox("Burp-style syntax colouring (request / response blocks)");
        bgOpt = new OptColor("Body background", new Color(240, 240, 240), this::styleChanged);
        beside = new JCheckBox("Place beside the previous block (same row) instead of below it");
        sepAfter = new JCheckBox("Separator line after this block");
        sepOpt = new OptColor("Custom separator colour", new Color(170, 170, 170), this::styleChanged);
        sepWidth = ispin(0, 0, 20, 1);
        wrapCols = ispin(100, 0, 500, 5);
        wrap = new JCheckBox("Wrap long lines (off = cut with ...)");
        maxLines = ispin(30, 0, 600, 1);
        imgMax = ispin(500, 40, 3000, 20);
        showLabel.addActionListener(e -> styleChanged());
        highlight.addActionListener(e -> styleChanged());
        beside.addActionListener(e -> styleChanged());
        sepAfter.addActionListener(e -> styleChanged());
        wrap.addActionListener(e -> styleChanged());
        align.addActionListener(e -> styleChanged());
        for (JSpinner s : new JSpinner[]{sepWidth, wrapCols, maxLines, imgMax}) s.addChangeListener(e -> styleChanged());
        labelField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                styleChanged();
            }

            public void removeUpdate(DocumentEvent e) {
                styleChanged();
            }

            public void changedUpdate(DocumentEvent e) {
                styleChanged();
            }
        });
        Form f = new Form();
        f.span(new JLabel("<html><b>Label</b> (the red \"Request\" / \"Response\" header)</html>"));
        f.row("", showLabel);
        f.row("Text", labelField);
        f.row("Alignment (label / block)", align);
        f.span(labelFont);
        f.span(new JLabel("<html><b>Body</b></html>"));
        f.span(bodyFont);
        f.span(highlight);
        f.span(bgOpt);
        f.span(new JLabel("<html><b>Fit</b> (keeps screenshots from getting too wide / tall)</html>"));
        f.row("Wrap at columns (0 = off)", wrapCols);
        f.span(wrap);
        f.row("Max lines (0 = all)", maxLines);
        f.row("Image max width (px)", imgMax);
        f.span(new JLabel("<html><b>Layout</b></html>"));
        f.span(beside);
        f.span(sepAfter);
        f.span(sepOpt);
        f.row("Custom separator width (0 = global)", sepWidth);
        return new JPanel(new BorderLayout()) {{
            add(new JScrollPane(f.top()));
        }};
    }

    private JPanel buildRight() {
        JPanel p = new JPanel(new BorderLayout());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        JComboBox<String> zoom = new JComboBox<>(new String[]{"Fit width", "25%", "50%", "75%", "100%", "150%"});
        zoom.addActionListener(e -> {
            String s = (String) zoom.getSelectedItem();
            preview.setZoom(s.startsWith("Fit") ? 0 : Integer.parseInt(s.replace("%", "")) / 100.0);
        });
        JSpinner sc = dspin(scale, 1, 4, 0.5);
        sc.addChangeListener(e -> {
            scale = ((Number) sc.getValue()).doubleValue();
            refresh();
        });
        bar.add(new JLabel("Zoom"));
        bar.add(zoom);
        bar.add(new JLabel("Output scale \u00d7"));
        bar.add(sc);
        bar.add(btn("Preview window", "Open the preview in its own window (screenshot it with an external tool)", e -> openWindow()));
        bar.add(btn("Copy image", "Copy the PNG to the clipboard", e -> copy()));
        bar.add(btn("Save PNG...", "Save as PNG", e -> save()));
        bar.add(btn("Global settings...", "Default fonts, colours, separator, syntax colours...", e -> openSettings.run()));
        bar.add(btn("Close capture", "Close this capture", e -> onClose.run()));
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        south.add(status);
        p.add(bar, BorderLayout.NORTH);
        p.add(new JScrollPane(preview), BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    // ================================================================== helpers

    private static JButton btn(String text, String tip, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setMargin(new java.awt.Insets(2, 6, 2, 6));
        b.setFocusable(false); // keeps the text selection / focus in the editor
        if (l != null) b.addActionListener(l);
        return b;
    }

    private static JSpinner ispin(int v, int min, int max, int step) {
        return new JSpinner(new SpinnerNumberModel(v, min, max, step));
    }

    private static JSpinner dspin(double v, double min, double max, double step) {
        return new JSpinner(new SpinnerNumberModel(v, min, max, step));
    }

    private static JPanel wrapLeft(Component c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.add(c);
        return p;
    }

    private static JPanel twin(Component a, Component b) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.add(a);
        p.add(b);
        return p;
    }

    private static int ival(JSpinner s) {
        return ((Number) s.getValue()).intValue();
    }

    private static double dval(JSpinner s) {
        return ((Number) s.getValue()).doubleValue();
    }

    private List<Block> blocks() {
        List<Block> l = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) l.add(model.get(i));
        return l;
    }

    // ================================================================== block management

    private void select(Block b) {
        cur = b;
        loading = true;
        try {
            if (b != null) {
                boolean http = b.isHttp(), img = b.kind == Block.Kind.IMAGE;
                filterPanel.setVisible(http);
                ta.setEnabled(!img);
                ta.setText(img ? "(image block - no text)" : b.text);
                ta.setCaretPosition(0);
                incFirst.setSelected(b.incFirst);
                incHeaders.setSelected(b.incHeaders);
                incBody.setSelected(b.incBody);
                pretty.setSelected(b.prettyJson);
                maxBody.setValue(b.maxBodyChars);
                if (http) {
                    List<String> hl = b.headLines();
                    hdrList.set(hl.subList(Math.min(1, hl.size()), hl.size()), 1, b.hiddenHeaders, this::filtersChanged);
                    List<String> ps = b.formParams();
                    parList.set(ps == null ? new ArrayList<>() : ps, 0, b.hiddenParams, this::filtersChanged);
                }
                loadStyle(b);
                annModel.clear();
                for (Annotation a : b.annotations) annModel.addElement(a);
                if (!annModel.isEmpty()) annList.setSelectedIndex(0);
            }
        } finally {
            loading = false;
        }
        loadAnn();
        refresh();
    }

    private void loadStyle(Block b) {
        boolean was = loading;
        loading = true;
        showLabel.setSelected(b.showLabel);
        labelField.setText(b.label);
        align.setSelectedIndex(b.align);
        labelFont.load(b.labelFont, st.labelFont);
        bodyFont.load(b.bodyFont, st.bodyFont);
        highlight.setSelected(b.highlight);
        bgOpt.set(b.background);
        beside.setSelected(b.beside);
        beside.setEnabled(model.indexOf(b) > 0);
        sepAfter.setSelected(b.separatorAfter);
        sepOpt.set(b.sepColor);
        sepWidth.setValue(b.sepWidth);
        wrapCols.setValue(b.wrapCols);
        wrap.setSelected(b.wrap);
        maxLines.setValue(b.maxLines);
        imgMax.setValue(b.imageMaxWidth);
        boolean img = b.kind == Block.Kind.IMAGE;
        bodyFont.setEnabled(!img);
        wrapCols.setEnabled(!img);
        maxLines.setEnabled(!img);
        imgMax.setEnabled(img);
        loading = was;
    }

    private void styleChanged() {
        if (loading || cur == null) return;
        cur.showLabel = showLabel.isSelected();
        cur.label = labelField.getText();
        cur.align = align.getSelectedIndex();
        cur.labelFont = labelFont.value();
        cur.bodyFont = bodyFont.value();
        cur.highlight = highlight.isSelected();
        cur.background = bgOpt.get();
        cur.beside = beside.isSelected() && model.indexOf(cur) > 0;
        cur.separatorAfter = sepAfter.isSelected();
        cur.sepColor = sepOpt.get();
        cur.sepWidth = ival(sepWidth);
        cur.wrapCols = ival(wrapCols);
        cur.wrap = wrap.isSelected();
        cur.maxLines = ival(maxLines);
        cur.imageMaxWidth = ival(imgMax);
        list.repaint();
        refresh();
    }

    private void filtersChanged() {
        if (loading || cur == null || !cur.isHttp()) return;
        cur.incFirst = incFirst.isSelected();
        cur.incHeaders = incHeaders.isSelected();
        cur.incBody = incBody.isSelected();
        cur.prettyJson = pretty.isSelected();
        cur.maxBodyChars = ival(maxBody);
        cur.regenerate();
        loading = true;
        ta.setText(cur.text);
        ta.setCaretPosition(0);
        loading = false;
        annList.repaint();
        refresh();
    }

    /** Called when the global settings dialog changed something. */
    void globalsChanged() {
        if (cur != null) loadStyle(cur);
        refresh();
    }

    private void move(int d) {
        int i = list.getSelectedIndex(), j = i + d;
        if (i < 0 || j < 0 || j >= model.size()) return;
        Block b = model.remove(i);
        model.add(j, b);
        list.setSelectedIndex(j);
    }

    private void insert(Block b) {
        int i = list.getSelectedIndex();
        int at = i < 0 ? model.size() : i + 1;
        model.add(at, b);
        list.setSelectedIndex(at);
    }

    private void addCustom() {
        insert(Block.custom("Custom header", st));
    }

    private void duplicate() {
        if (cur != null) insert(cur.duplicate());
    }

    private void remove() {
        int i = list.getSelectedIndex();
        if (i < 0) return;
        model.remove(i);
        if (!model.isEmpty()) list.setSelectedIndex(Math.min(i, model.size() - 1));
        else {
            cur = null;
            refresh();
        }
    }

    private void addImageFromFile() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "bmp"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            BufferedImage img = ImageIO.read(fc.getSelectedFile());
            if (img == null) throw new IllegalStateException("Not an image");
            insert(Block.image(img));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not load image: " + ex.getMessage());
        }
    }

    private void addImageFromClipboard() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t == null || !t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                JOptionPane.showMessageDialog(this, "The clipboard does not contain an image.");
                return;
            }
            java.awt.Image im = (java.awt.Image) t.getTransferData(DataFlavor.imageFlavor);
            BufferedImage bi = new BufferedImage(im.getWidth(null), im.getHeight(null), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = bi.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, bi.getWidth(), bi.getHeight());
            g.drawImage(im, 0, 0, null);
            g.dispose();
            insert(Block.image(bi));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not read clipboard image: " + ex.getMessage());
        }
    }

    // ================================================================== text operations

    private boolean needSelection() {
        if (cur == null || cur.kind == Block.Kind.IMAGE) return true;
        if (ta.getSelectionStart() == ta.getSelectionEnd()) {
            status.setText("Select some text in the text box first.");
            return true;
        }
        return false;
    }

    private void replaceSel() {
        if (needSelection()) return;
        ta.replaceRange("...", ta.getSelectionStart(), ta.getSelectionEnd());
    }

    private void ellipsizeOutside() {
        if (needSelection()) return;
        String t = ta.getText();
        int s = ta.getSelectionStart(), e = ta.getSelectionEnd();
        int ls = t.lastIndexOf('\n', s - 1) + 1;
        int le = t.indexOf('\n', s);
        if (le < 0) le = t.length();
        e = Math.min(e, le);
        String line = t.substring(ls, le);
        int rs = s - ls, re = e - ls;
        Matcher m = HDR_PREFIX.matcher(line);
        int keep = (m.find() && m.end() <= rs) ? m.end() : 0;
        String before = line.substring(keep, rs), after = line.substring(re);
        ta.replaceRange(line.substring(0, keep) + (before.isEmpty() ? "" : "...") + line.substring(rs, re)
                + (after.isEmpty() ? "" : "..."), ls, le);
    }

    /** mode 0 = delete, 1 = collapse into "...", 2 = keep only (collapse everything else). */
    private void lineOp(int mode) {
        if (cur == null || cur.kind == Block.Kind.IMAGE) return;
        String t = ta.getText();
        int s = ta.getSelectionStart(), e = ta.getSelectionEnd();
        int eEff = (e > s && e <= t.length() && t.charAt(e - 1) == '\n') ? e - 1 : e;
        int ls = t.lastIndexOf('\n', s - 1) + 1;
        int le = t.indexOf('\n', eEff);
        if (le < 0) le = t.length();
        String pre = t.substring(0, ls), mid = t.substring(ls, le), post = le < t.length() ? t.substring(le + 1) : "";
        String out;
        if (mode == 0) out = pre + post;
        else if (mode == 1) out = pre + "..." + (post.isEmpty() ? "" : "\n" + post);
        else out = (pre.trim().isEmpty() ? "" : "...\n") + mid + (post.trim().isEmpty() ? "" : "\n...");
        ta.setText(out);
        ta.setCaretPosition(Math.min(ls, out.length()));
    }

    // ================================================================== annotations

    private void addAnn(Annotation.Type type) {
        if (needSelection()) return;
        String t = ta.getText();
        int s = ta.getSelectionStart(), e = ta.getSelectionEnd();
        String anchor = t.substring(s, e);
        int occ = 0, idx = t.indexOf(anchor);
        while (idx >= 0 && idx < s) {
            occ++;
            idx = t.indexOf(anchor, idx + 1);
        }
        Annotation a = Annotation.create(type, anchor, occ);
        cur.annotations.add(a);
        annModel.addElement(a);
        annList.setSelectedIndex(annModel.size() - 1);
        status.setText("Added " + type + " - tweak its style in the Annotations tab.");
        refresh();
    }

    private void removeAnn() {
        Annotation a = annList.getSelectedValue();
        if (a == null || cur == null) return;
        cur.annotations.remove(a);
        annModel.removeElement(a);
        refresh();
    }

    private void reanchor() {
        Annotation a = annList.getSelectedValue();
        if (a == null || needSelection()) return;
        String t = ta.getText();
        int s = ta.getSelectionStart(), e = ta.getSelectionEnd();
        a.anchor = t.substring(s, e);
        int occ = 0, idx = t.indexOf(a.anchor);
        while (idx >= 0 && idx < s) {
            occ++;
            idx = t.indexOf(a.anchor, idx + 1);
        }
        a.occurrence = occ;
        annList.repaint();
        refresh();
    }

    private void loadAnn() {
        Annotation a = annList.getSelectedValue();
        boolean was = loading;
        loading = true;
        boolean has = a != null;
        for (Component c : new Component[]{aColor, aWidth, aPad, aFull, aBlock, aAngle, aLen, aHead, aEdge, aDx, aDy, aStrength})
            c.setEnabled(has);
        if (has) {
            aColor.set(a.color);
            aWidth.setValue(a.width);
            aPad.setValue(a.pad);
            aFull.setSelected(a.fullWidth);
            aBlock.setSelected(a.blockArrow);
            aAngle.setValue(a.angle);
            aLen.setValue(a.length);
            aHead.setValue(a.headSize);
            aEdge.setSelectedItem(a.edge);
            aDx.setValue(a.dx);
            aDy.setValue(a.dy);
            aStrength.setValue(a.strength);
            boolean arrow = a.type == Annotation.Type.ARROW, fx = a.type == Annotation.Type.PIXELATE || a.type == Annotation.Type.BLUR;
            aColor.setEnabled(!fx);
            aWidth.setEnabled(!fx);
            aBlock.setEnabled(arrow);
            aAngle.setEnabled(arrow);
            aLen.setEnabled(arrow);
            aHead.setEnabled(arrow);
            aEdge.setEnabled(arrow);
            aDx.setEnabled(arrow);
            aDy.setEnabled(arrow);
            aStrength.setEnabled(fx);
        }
        loading = was;
    }

    private void annChanged() {
        Annotation a = annList.getSelectedValue();
        if (loading || a == null) return;
        a.color = aColor.get();
        a.width = dval(aWidth);
        a.pad = ival(aPad);
        a.fullWidth = aFull.isSelected();
        a.blockArrow = aBlock.isSelected();
        a.angle = dval(aAngle);
        a.length = ival(aLen);
        a.headSize = ival(aHead);
        a.edge = (Annotation.Edge) aEdge.getSelectedItem();
        a.dx = ival(aDx);
        a.dy = ival(aDy);
        a.strength = ival(aStrength);
        refresh();
    }

    // ================================================================== rendering / export

    void refresh() {
        timer.restart();
    }

    BufferedImage renderNow() {
        last = Renderer.render(blocks(), st, scale);
        preview.setImage(last);
        if (winPanel != null) winPanel.setImage(last);
        int lw = (int) Math.round(last.getWidth() / scale), lh = (int) Math.round(last.getHeight() / scale);
        String warn = (lh > 1400 || lw > 1500) ? "   |   Large screenshot - lower max lines / wrap columns, or drop some headers" : "";
        status.setText("Output: " + last.getWidth() + " x " + last.getHeight() + " px (scale " + scale + "x)" + warn);
        return last;
    }

    private void openWindow() {
        if (win != null) {
            win.toFront();
            return;
        }
        winPanel = new PreviewPanel();
        win = new JFrame("Screenshot preview");
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        JComboBox<String> zoom = new JComboBox<>(new String[]{"Fit width", "25%", "50%", "75%", "100%", "150%"});
        zoom.addActionListener(e -> {
            String s = (String) zoom.getSelectedItem();
            winPanel.setZoom(s.startsWith("Fit") ? 0 : Integer.parseInt(s.replace("%", "")) / 100.0);
        });
        bar.add(new JLabel("Zoom"));
        bar.add(zoom);
        bar.add(btn("Copy image", "Copy to clipboard", e -> copy()));
        bar.add(btn("Save PNG...", "Save as PNG", e -> save()));
        win.getContentPane().add(bar, BorderLayout.NORTH);
        win.getContentPane().add(new JScrollPane(winPanel), BorderLayout.CENTER);
        win.setSize(1000, 750);
        win.setLocationRelativeTo(this);
        win.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                win = null;
                winPanel = null;
            }
        });
        win.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        winPanel.setImage(renderNow());
        win.setVisible(true);
    }

    void disposeWindows() {
        if (win != null) win.dispose();
    }

    private void copy() {
        final BufferedImage img = renderNow();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{DataFlavor.imageFlavor};
            }

            public boolean isDataFlavorSupported(DataFlavor f) {
                return DataFlavor.imageFlavor.equals(f);
            }

            public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
                return img;
            }
        }, null);
        status.setText("Copied " + img.getWidth() + " x " + img.getHeight() + " px image to the clipboard.");
    }

    private void save() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("PNG image", "png"));
        fc.setSelectedFile(new File("screenshot.png"));
        Component parent = SwingUtilities.getWindowAncestor(this);
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".png")) f = new File(f.getPath() + ".png");
        try {
            ImageIO.write(renderNow(), "png", f);
            status.setText("Saved " + f.getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }
}
