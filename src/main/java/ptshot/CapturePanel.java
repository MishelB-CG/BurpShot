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
    private final Runnable onClose, openSettings, onChanged;
    private final DefaultListModel<Block> model = new DefaultListModel<>();
    private final JList<Block> list = new JList<>(model);
    private final PreviewPanel preview = new PreviewPanel();
    private PreviewPanel winPanel;
    private JFrame win;
    private Block cur;
    private boolean loading;
    private double scale;
    private int maxWidth;
    private BufferedImage last;
    private final javax.swing.Timer timer;
    private final JLabel status = new JLabel(" ");
    private JTabbedPane tabs;
    private JSpinner scaleSpin, widthSpin;
    private JButton undoBtn, redoBtn;

    // undo / redo
    private static final class Snap {
        final List<Block> blocks = new ArrayList<>();
        double scale;
        int maxWidth, sel;
        String sig;
    }

    private final java.util.ArrayDeque<Snap> undo = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Snap> redo = new java.util.ArrayDeque<>();
    private Snap committed;
    private javax.swing.Timer histTimer;

    // content tab
    private JCheckBox incFirst, incHeaders, incBody;
    private javax.swing.JToggleButton viewPretty, viewRaw, filtersBtn;
    private JLabel filterSummary;
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

    CapturePanel(Settings st, List<Block> init, double scale, int maxWidth, Runnable onClose, Runnable openSettings, Runnable onChanged) {
        super(new BorderLayout());
        this.st = st;
        this.onClose = onClose;
        this.openSettings = openSettings;
        this.scale = scale;
        this.maxWidth = maxWidth;
        this.onChanged = onChanged;
        for (Block b : init) model.addElement(b);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel leftPane = buildLeft();
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, buildRight());
        // wide enough for the widest button row (so no button is clipped), but never more than ~60% of the screen
        int screenW = Toolkit.getDefaultToolkit().getScreenSize().width;
        split.setDividerLocation(Math.max(540, Math.min(leftPane.getPreferredSize().width + 14, (int) (screenW * 0.6))));
        split.setResizeWeight(0.0);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        split.setDividerSize(9);
        // the panels' natural minimum sizes are wide; without this the divider can't be dragged
        split.getLeftComponent().setMinimumSize(new Dimension(150, 100));
        split.getRightComponent().setMinimumSize(new Dimension(150, 100));
        add(split, BorderLayout.CENTER);
        timer = new javax.swing.Timer(120, e -> renderNow());
        timer.setRepeats(false);
        histTimer = new javax.swing.Timer(500, e -> commitHistory());
        histTimer.setRepeats(false);
        preview.setEditor(new PreviewPanel.Editor() {
            public void picked(Annotation a) {
                pickAnnotation(a);
            }

            public void begin() {
                commitHistory(); // everything before the drag becomes its own undo step
            }

            public void edited() {
                renderNow();
                loadAnn();
                onChanged.run();
                histTimer.restart();
            }

            public void end() {
                commitHistory();
                onChanged.run();
            }

            public void delete(Annotation a) {
                deleteAnnotation(a);
            }

            public void pickedBlock(Block b) {
                if (b != cur) list.setSelectedValue(b, true);
            }
        });
        installUndoKeys();
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) select(list.getSelectedValue());
        });
        annList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadAnn();
        });
        if (!model.isEmpty()) list.setSelectedIndex(0);
        renderNow();
        committed = snap();
        updateUndoButtons();
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
        viewPretty = new javax.swing.JToggleButton("Pretty", true);
        viewRaw = new javax.swing.JToggleButton("Raw");
        javax.swing.ButtonGroup vg = new javax.swing.ButtonGroup();
        for (javax.swing.JToggleButton t : new javax.swing.JToggleButton[]{viewPretty, viewRaw}) {
            vg.add(t);
            t.setFocusable(false);
            t.setMargin(new java.awt.Insets(5, 14, 5, 14));
            t.addActionListener(e -> filtersChanged());
        }
        viewPretty.setToolTipText("Re-indent JSON / XML / HTML bodies (like Burp's Pretty view)");
        viewRaw.setToolTipText("Show the body exactly as captured");
        for (JCheckBox c : new JCheckBox[]{incFirst, incHeaders, incBody}) c.addActionListener(e -> filtersChanged());
        hdrList = new Checklist("Headers");
        parList = new Checklist("Form parameters");
        maxBody = ispin(0, 0, 1_000_000, 100);
        maxBody.addChangeListener(e -> filtersChanged());

        // always visible: one slim row  [Pretty|Raw]  [> Filters]  summary
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        JPanel barL = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JPanel seg = new JPanel(new GridLayout(1, 2, 0, 0));
        seg.add(viewPretty);
        seg.add(viewRaw);
        barL.add(seg);
        filtersBtn = new javax.swing.JToggleButton("Filters ▸");
        filtersBtn.setFocusable(false);
        filtersBtn.setMargin(new java.awt.Insets(5, 12, 5, 12));
        filtersBtn.setToolTipText("Choose which parts of the message are shown. Changing a filter or Pretty/Raw rebuilds the text (manual edits are lost).");
        barL.add(filtersBtn);
        filterSummary = new JLabel(" ");
        filterSummary.setFont(filterSummary.getFont().deriveFont(Font.ITALIC));
        filterSummary.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        bar.add(barL, BorderLayout.WEST);
        bar.add(filterSummary, BorderLayout.CENTER);

        // collapsed by default
        JPanel details = new JPanel();
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setVisible(false);
        JPanel inc = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        inc.add(new JLabel("Include:"));
        inc.add(incFirst);
        inc.add(incHeaders);
        inc.add(incBody);
        inc.add(new JLabel("  Body limit"));
        maxBody.setToolTipText("Cut the body after this many characters (0 = all)");
        inc.add(maxBody);
        inc.add(btn("Reset text", "Discard manual edits and rebuild the text from the captured message", e -> filtersChanged()));
        JPanel lists = new JPanel(new GridLayout(1, 2, 4, 0));
        lists.add(hdrList);
        lists.add(parList);
        details.add(inc);
        details.add(lists);
        filtersBtn.addActionListener(e -> {
            details.setVisible(filtersBtn.isSelected());
            filtersBtn.setText(filtersBtn.isSelected() ? "Filters ▾" : "Filters ▸");
            filterPanel.revalidate();
        });
        filterPanel.add(bar);
        filterPanel.add(details);

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

        // one slim toolbar, acting on the text selected in the box above:  [annotate icons] | Edit text v | Add image v
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        south.add(new JLabel("Select text, then:"));
        south.add(iconBtn(Icons.box(), "Box - red rectangle around the selected text", e -> addAnn(Annotation.Type.BOX)));
        south.add(iconBtn(Icons.arrow(), "Arrow - starts at the selected text", e -> addAnn(Annotation.Type.ARROW)));
        south.add(iconBtn(Icons.pixelate(), "Pixelate the selected text", e -> addAnn(Annotation.Type.PIXELATE)));
        south.add(iconBtn(Icons.blur(), "Blur the selected text", e -> addAnn(Annotation.Type.BLUR)));
        south.add(menuBtn("Edit text", Icons.lines(), "Shorten or remove parts of the text",
                item("Replace selection with ...", "Replace the selected text with ...", e -> replaceSel()),
                item("Keep selection, ... the rest of the line", "Keep the header name and the selection, replace the rest of the line with ...", e -> ellipsizeOutside()),
                null,
                item("Delete line(s)", "Delete the selected line(s)", e -> lineOp(0)),
                item("Collapse line(s) to ...", "Replace the selected line(s) with a single ... line", e -> lineOp(1)),
                item("Keep only selected line(s)", "Keep only the selected line(s), collapse the rest into ...", e -> lineOp(2))));
        south.add(menuBtn("Render", Icons.picture(), "Add a rendered HTML page or a screen capture as an image block",
                item("Render HTML (selection / body / pasted)...", "Render HTML code and pick a region to add as an image block", e -> renderHtmlAction()),
                item("Capture screen region...", "Grab the screen after a delay (e.g. Burp's Render tab) and pick a region", e -> screenAction())));

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
        bar.add(btn("Reset position / size", "Undo manual moves and resizes made in the preview (the annotation snaps back to its text)", e -> resetAdjust()));
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
        f.span(Txt.heading("Label", "(the red \"Request\" / \"Response\" header)"));
        f.row("", showLabel);
        f.row("Text", labelField);
        f.row("Alignment (label / block)", align);
        f.span(labelFont);
        f.span(Txt.heading("Body", null));
        f.span(bodyFont);
        f.span(highlight);
        f.span(bgOpt);
        f.span(Txt.heading("Fit", "(also see \"Max width\" in the preview toolbar)"));
        f.row("Wrap at columns (0 = off)", wrapCols);
        f.span(wrap);
        f.row("Max lines (0 = all)", maxLines);
        f.row("Image max width (px)", imgMax);
        f.span(Txt.heading("Layout", null));
        f.span(beside);
        f.span(sepAfter);
        f.span(sepOpt);
        f.row("Custom separator width (0 = global)", sepWidth);
        f.span(btn("Reset layer position and size", "Undo drag-moves and resizes of this layer made in the preview (it returns to its automatic place and size)", e -> resetLayer()));
        return new JPanel(new BorderLayout()) {{
            add(new JScrollPane(f.top()));
        }};
    }

    private JPanel buildRight() {
        JPanel p = new JPanel(new BorderLayout());
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        JComboBox<String> zoom = new JComboBox<>(new String[]{"Fit width", "25%", "50%", "75%", "100%", "150%"});
        zoom.addActionListener(e -> {
            String s = (String) zoom.getSelectedItem();
            preview.setZoom(s.startsWith("Fit") ? 0 : Integer.parseInt(s.replace("%", "")) / 100.0);
        });
        scaleSpin = dspin(scale, 1, 4, 0.5);
        scaleSpin.addChangeListener(e -> {
            scale = ((Number) scaleSpin.getValue()).doubleValue();
            refresh();
        });
        widthSpin = ispin(maxWidth, 0, 5000, 50);
        widthSpin.setToolTipText("Maximum screenshot width in pixels (at 1x). Text wraps and images shrink to fit. 0 = unlimited.");
        widthSpin.addChangeListener(e -> {
            maxWidth = ival(widthSpin);
            refresh();
        });
        undoBtn = btn("\u21b6 Undo", "Undo (Ctrl/Cmd+Z)", e -> undo());
        redoBtn = btn("\u21b7 Redo", "Redo (Ctrl/Cmd+Y or Ctrl/Cmd+Shift+Z)", e -> redo());
        row1.add(undoBtn);
        row1.add(redoBtn);
        row1.add(btn("Preview window", "Open the preview in its own window (screenshot it with an external tool)", e -> openWindow()));
        row1.add(btn("Copy image", "Copy the PNG to the clipboard", e -> copy()));
        row1.add(btn("Save PNG...", "Save as PNG", e -> save()));
        row1.add(btn("Global settings...", "Default fonts, colours, separator, syntax colours...", e -> openSettings.run()));
        row1.add(btn("Close capture", "Close this capture", e -> onClose.run()));
        row2.add(new JLabel("Zoom"));
        row2.add(zoom);
        row2.add(new JLabel("Output scale \u00d7"));
        row2.add(scaleSpin);
        row2.add(new JLabel("Max width (px, 0 = off)"));
        row2.add(widthSpin);
        row2.add(new JLabel("  Click an annotation or a whole block in the preview to move / resize it"));
        bar.add(row1);
        bar.add(row2);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        south.add(status);
        p.add(bar, BorderLayout.NORTH);
        p.add(new JScrollPane(preview), BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    // ================================================================== undo / redo

    private Snap snap() {
        Snap s = new Snap();
        for (Block b : blocks()) s.blocks.add(b.duplicate());
        s.scale = scale;
        s.maxWidth = maxWidth;
        s.sel = list.getSelectedIndex();
        s.sig = Store.signature(scale, maxWidth, blocks());
        return s;
    }

    /** Pushes the state before the latest batch of edits onto the undo stack (no-op if nothing changed). */
    private void commitHistory() {
        histTimer.stop();
        if (committed == null) {
            committed = snap();
            return;
        }
        if (Store.signature(scale, maxWidth, blocks()).equals(committed.sig)) return;
        undo.push(committed);
        while (undo.size() > 100) undo.removeLast();
        redo.clear();
        committed = snap();
        updateUndoButtons();
    }

    private void undo() {
        commitHistory();
        if (undo.isEmpty()) return;
        redo.push(committed);
        committed = undo.pop();
        applySnap(committed);
    }

    private void redo() {
        commitHistory();
        if (redo.isEmpty()) return;
        undo.push(committed);
        committed = redo.pop();
        applySnap(committed);
    }

    private void applySnap(Snap s) {
        histTimer.stop();
        scale = s.scale;
        maxWidth = s.maxWidth;
        scaleSpin.setValue(scale);
        widthSpin.setValue(maxWidth);
        model.clear();
        for (Block b : s.blocks) model.addElement(b.duplicate()); // history entries stay immutable
        int i = Math.min(s.sel, model.size() - 1);
        if (i >= 0) list.setSelectedIndex(i);
        else {
            cur = null;
            refresh();
        }
        renderNow();
        updateUndoButtons();
        onChanged.run();
    }

    private void updateUndoButtons() {
        if (undoBtn != null) undoBtn.setEnabled(!undo.isEmpty());
        if (redoBtn != null) redoBtn.setEnabled(!redo.isEmpty());
    }

    private void installUndoKeys() {
        int mask;
        try {
            mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (java.awt.HeadlessException e) {
            mask = java.awt.event.InputEvent.CTRL_DOWN_MASK;
        }
        javax.swing.InputMap im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap am = getActionMap();
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, mask), "pt-undo");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, mask | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "pt-redo");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y, mask), "pt-redo");
        am.put("pt-undo", new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                undo();
            }
        });
        am.put("pt-redo", new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                redo();
            }
        });
    }

    // ================================================================== editing in the preview

    private Block ownerOf(Annotation a) {
        for (Block b : blocks()) if (b.annotations.contains(a)) return b;
        return null;
    }

    private void pickAnnotation(Annotation a) {
        if (a == null) {
            annList.clearSelection();
            return;
        }
        Block b = ownerOf(a);
        if (b == null) return;
        if (b != cur) list.setSelectedValue(b, true);
        annList.setSelectedValue(a, true);
        tabs.setSelectedIndex(1);
    }

    private void deleteAnnotation(Annotation a) {
        Block b = ownerOf(a);
        if (b == null) return;
        commitHistory();
        b.annotations.remove(a);
        annModel.removeElement(a);
        renderNow();
        commitHistory();
        onChanged.run();
    }

    // ================================================================== helpers

    private static JButton iconBtn(javax.swing.Icon icon, String tip, java.awt.event.ActionListener l) {
        JButton b = new JButton(icon);
        b.setToolTipText(tip);
        b.setMargin(new java.awt.Insets(6, 10, 6, 10));
        b.setFocusable(false);
        b.addActionListener(l);
        return b;
    }

    private static JMenuItem item(String text, String tip, java.awt.event.ActionListener l) {
        JMenuItem m = new JMenuItem(text);
        m.setToolTipText(tip);
        m.addActionListener(l);
        return m;
    }

    /** A button that pops up a menu; a null entry is a separator. */
    private static JButton menuBtn(String text, javax.swing.Icon icon, String tip, JMenuItem... items) {
        JButton b = new JButton(text + " ▾", icon);
        b.setToolTipText(tip);
        b.setMargin(new java.awt.Insets(6, 10, 6, 10));
        b.setFocusable(false);
        b.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            for (JMenuItem i : items) {
                if (i == null) m.addSeparator();
                else m.add(i);
            }
            m.show(b, 0, b.getHeight());
        });
        return b;
    }

    /** One-line description of what the current filters hide. */
    private void updateSummary() {
        if (filterSummary == null || cur == null || !cur.isHttp()) return;
        List<String> s = new ArrayList<>();
        if (!cur.incFirst) s.add("no first line");
        if (!cur.incHeaders) s.add("no headers");
        else if (!cur.hiddenHeaders.isEmpty()) s.add(cur.hiddenHeaders.size() + " header" + (cur.hiddenHeaders.size() == 1 ? "" : "s") + " hidden");
        if (!cur.incBody) s.add("no body");
        else {
            if (!cur.hiddenParams.isEmpty()) s.add(cur.hiddenParams.size() + " param" + (cur.hiddenParams.size() == 1 ? "" : "s") + " hidden");
            if (cur.maxBodyChars > 0) s.add("body cut at " + cur.maxBodyChars);
        }
        filterSummary.setText(s.isEmpty() ? "everything shown" : String.join(", ", s));
    }

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

    double scale() {
        return scale;
    }

    int maxWidth() {
        return maxWidth;
    }

    List<Block> blocks() {
        List<Block> l = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) l.add(model.get(i));
        return l;
    }

    // ================================================================== block management

    private void select(Block b) {
        cur = b;
        preview.followBlock(b);
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
                (b.pretty ? viewPretty : viewRaw).setSelected(true);
                maxBody.setValue(b.maxBodyChars);
                if (http) {
                    List<String> hl = b.headLines();
                    hdrList.set(hl.subList(Math.min(1, hl.size()), hl.size()), 1, b.hiddenHeaders, this::filtersChanged);
                    List<String> ps = b.formParams();
                    parList.set(ps == null ? new ArrayList<>() : ps, 0, b.hiddenParams, this::filtersChanged);
                }
                loadStyle(b);
                updateSummary();
                annModel.clear();
                for (Annotation a : b.annotations) annModel.addElement(a);
                annList.clearSelection();
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
        cur.pretty = viewPretty.isSelected();
        cur.maxBodyChars = ival(maxBody);
        cur.regenerate();
        loading = true;
        ta.setText(cur.text);
        ta.setCaretPosition(0);
        loading = false;
        annList.repaint();
        updateSummary();
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

    // ================================================================== rendered HTML

    private void insertImage(BufferedImage img, double pixelRatio) {
        Block b = Block.image(img);
        b.imageMaxWidth = Math.max(40, Math.min(900, (int) Math.round(img.getWidth() / pixelRatio)));
        insert(b);
        status.setText("Added image block " + img.getWidth() + " x " + img.getHeight() + " px.");
    }

    private void renderHtmlAction() {
        String html = "";
        if (cur != null && cur.kind != Block.Kind.IMAGE) {
            String sel = ta.getSelectedText();
            html = (sel != null && !sel.trim().isEmpty()) ? sel : (cur.isHttp() ? cur.bodyText() : ta.getText());
        }
        if (!html.contains("<")) {
            // nothing usable selected: let the user paste / type any HTML to render
            html = promptHtml(html);
            if (html == null) return;
            if (html.trim().isEmpty()) {
                status.setText("No HTML entered.");
                return;
            }
        }
        final String src = html;
        BufferedImage first = Html.render(src, 700, 2.0);
        BufferedImage out = ImagePicker.show(SwingUtilities.getWindowAncestor(this),
                "Rendered HTML - drag to select the part to include", first, w -> Html.render(src, w, 2.0), 700);
        if (out != null) insertImage(out, 2.0);
    }

    /** Asks for HTML source to render; returns null if cancelled. */
    private String promptHtml(String initial) {
        JTextArea area = new JTextArea(initial, 16, 70);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.add(new JLabel("Paste or type any HTML to render (none was selected / found in the current block):"), BorderLayout.NORTH);
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        int r = JOptionPane.showConfirmDialog(SwingUtilities.getWindowAncestor(this), p, "Render HTML", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return r == JOptionPane.OK_OPTION ? area.getText() : null;
    }

    private void screenAction() {
        java.awt.Window w = SwingUtilities.getWindowAncestor(this);
        JSpinner delay = ispin(5, 1, 60, 1);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.add(new JLabel("Delay (seconds):"));
        row.add(delay);
        int r = JOptionPane.showConfirmDialog(w, new Object[]{
                Txt.lines(false, "After you press OK, switch to the view to capture - for example the response's",
                        "Render tab in Burp (press Render there first). The screen is grabbed when the",
                        "countdown ends and you then select the region to include."), row},
                "Capture screen region", JOptionPane.OK_CANCEL_OPTION);
        if (r != JOptionPane.OK_OPTION) return;
        ScreenGrab.grab(ival(delay), (img, ratio) -> {
            BufferedImage out = ImagePicker.show(SwingUtilities.getWindowAncestor(this), "Select the region to include", img, null, 0);
            if (out != null) insertImage(out, ratio);
        }, msg -> status.setText(msg));
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

    private void resetLayer() {
        if (cur == null) return;
        commitHistory();
        cur.offX = cur.offY = cur.boxW = cur.imageW = 0;
        renderNow();
        commitHistory();
        onChanged.run();
    }

    private void resetAdjust() {
        Annotation a = annList.getSelectedValue();
        if (a == null) return;
        a.offL = a.offT = a.offR = a.offB = 0;
        a.dx = 0;
        a.dy = 0;
        loadAnn();
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
        preview.setSelected(a);
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
        if (histTimer != null) histTimer.restart();
        onChanged.run();
    }

    BufferedImage renderNow() {
        Renderer.Result res = Renderer.renderFull(blocks(), st, scale, maxWidth);
        last = res.image;
        preview.setResult(res);
        if (winPanel != null) winPanel.setImage(last);
        int lw = (int) Math.round(last.getWidth() / scale), lh = (int) Math.round(last.getHeight() / scale);
        String warn = (lh > 1400) ? "   |   Tall screenshot - lower max lines or drop some headers" : (maxWidth == 0 && lw > 1200) ? "   |   Wide screenshot - set a max width to wrap it" : "";
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
