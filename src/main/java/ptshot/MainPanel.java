package ptshot;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** The suite tab: a welcome page plus one tab per capture. */
final class MainPanel extends JPanel {
    private final Settings st;
    private final Consumer<Settings> persist;
    private final Consumer<Component> themer;
    private final JTabbedPane tabs = new JTabbedPane();
    private int counter;
    private boolean settingsOpen;

    /** Saving / restoring captures: project file, session, or folder, optionally encrypted. */
    private final Persist store;
    private final javax.swing.Timer saveTimer;
    private final java.util.Set<CapturePanel> dirty = new java.util.LinkedHashSet<>();
    private boolean indexDirty;
    private JPanel lockBar;
    private JLabel lockText;
    private JButton lockBtn;
    private String problem;
    private boolean closing;

    MainPanel(Settings st, Consumer<Settings> persist, Consumer<Component> themer, Persist store) {
        super(new BorderLayout());
        this.st = st;
        this.persist = persist;
        this.themer = themer;
        this.store = store;
        saveTimer = new javax.swing.Timer(1000, e -> saveNow());
        saveTimer.setRepeats(false);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton fresh = new JButton("New empty capture");
        fresh.addActionListener(e -> addCapture("manual", "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html></html>"));
        JButton set = new JButton("Global settings...");
        set.addActionListener(e -> openSettings());
        JButton storage = new JButton("Storage...");
        storage.setToolTipText("Where captures are saved (project file / this session / a folder) and password encryption");
        storage.addActionListener(e -> StorageDialog.open(this, store));
        bar.add(fresh);
        bar.add(set);
        bar.add(storage);
        bar.add(new JLabel("Right-click any request/response in Proxy, Repeater, Logger... \u2192 Extensions \u2192 BurpShot \u2192 Send to BurpShot"));
        lockBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        lockBar.setBackground(new java.awt.Color(255, 244, 200));
        lockText = new JLabel(" ");
        lockText.setForeground(new java.awt.Color(90, 50, 0));
        lockBtn = new JButton("Unlock...");
        lockBtn.addActionListener(e -> StorageDialog.promptPassword(this, store));
        lockBar.add(lockText);
        lockBar.add(lockBtn);
        JButton forgot = new JButton("Forgot the password...");
        forgot.addActionListener(e -> StorageDialog.forgotPassword(this, store, this));
        lockBar.add(forgot);
        lockBar.setVisible(false);
        JPanel north = new JPanel();
        north.setLayout(new javax.swing.BoxLayout(north, javax.swing.BoxLayout.Y_AXIS));
        north.add(bar);
        north.add(lockBar);
        add(north, BorderLayout.NORTH);

        // A JEditorPane (not a JLabel): Burp's theming can switch HTML rendering off on labels, which showed the raw markup.
        javax.swing.JEditorPane help = new javax.swing.JEditorPane("text/html", "<html><body>"
                + "<h2>BurpShot</h2>"
                + "<p>Right-click a request (or several) anywhere in Burp and choose <b>Extensions &rarr; BurpShot &rarr; Send to BurpShot</b>. "
                + "Or select it and press <b>Ctrl+Alt+B</b> (rebindable in Burp's hotkey settings). Each one opens as its own tab here.</p>"
                + "<ol>"
                + "<li><b>Content tab</b> - choose what is shown: <i>Pretty / Raw</i> body (JSON, XML, HTML, JavaScript and CSS re-indented), <i>Filters</i> for first line, headers, form parameters and body length, or edit the text freely. "
                + "<b>Select text and right-click</b> for Box / Arrow / Pixelate / Blur on that text (they stay attached to it) and for edit actions like <i>Keep selection, ... the rest of the line</i>. <b>Ctrl+F</b> searches the editor (all layers: request and response).</li>"
                + "<li><b>Tool palette</b> (left of the preview) - select / move, rectangle selection, pencil, brush, bucket, eraser; free Box / Arrow / Pixelate / Blur elements; new Text, <b>Terminal</b>, <b>Table</b> (Intruder, Proxy history, ...) and Image layers; <b>Pick from Burp</b> (turn any Burp button, text or table into a layer; Ctrl+click switches tabs while picking); <b>Crop</b>.</li>"
                + "<li><b>Preview</b> - click an element or a layer to select it, drag to move, handles to resize, <b>Del</b> deletes it, <b>right-click</b> for a menu (delete, duplicate, order, crop, align, add elements at the click).</li>"
                + "<li><b>Annotations</b> and <b>Style &amp; layout</b> tabs - element colours, arrows, pixelate strength; fonts, labels, wrapping, side-by-side blocks, separators, alignment, borders (colour, width, curvature, shadow) per layer; the <b>Border</b> tool in the palette for a layer or the whole screenshot.</li>"
                + "<li><b>Terminal layer</b> - paste output from a cmd / PowerShell / bash window; ANSI colours are kept, with dark / light themes, window frames, a highlighted command and colours by indentation.</li>"
                + "<li><b>Export</b> - <b>Copy image</b> / <b>Save PNG</b>, or <b>Preview window</b> to capture it with another tool. Undo / redo with Ctrl+Z / Ctrl+Y.</li>"
                + "<li><b>Share...</b> uploads a screenshot or the whole capture to SFTP / FTP / S3 / Google Cloud / Dropbox / Drive / an HTTP API / a network share, optionally encrypted.</li>"
                + "<li><b>Storage...</b> chooses where captures are saved (the Burp project file, this session only, or a folder) and turns on password encryption. Saving runs in the background, so closing Burp is not held up.</li>"
                + "</ol></body></html>");
        help.setEditable(false);
        help.putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        help.setFont(new JLabel().getFont());
        help.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        help.setCaretPosition(0);
        tabs.addTab("Welcome", new JScrollPane(help));
        add(tabs, BorderLayout.CENTER);
        store.start(sink); // reading and decoding happens on the storage thread; captures appear as they are ready
        refreshLockBar();
    }

    private void renameTab(Component c) {
        int i = tabs.indexOfComponent(c);
        if (i < 0) return;
        Object n = JOptionPane.showInputDialog(this, "Tab name:", tabs.getTitleAt(i));
        if (n != null && !n.toString().trim().isEmpty()) {
            String t = n.toString().trim();
            tabs.setTitleAt(i, t);
            Component h = tabs.getTabComponentAt(i);
            if (h instanceof JPanel && ((JPanel) h).getComponent(0) instanceof JLabel) ((JLabel) ((JPanel) h).getComponent(0)).setText(t);
            markDirty((CapturePanel) c);
        }
    }

    private void closeTab(CapturePanel cp) {
        cp.disposeWindows();
        cp.stopTimers();
        dirty.remove(cp);
        store.delete(cp.captureId); // closing a tab deletes its saved copy
        tabs.remove(cp);
        indexDirty = true;
        saveSoon();
    }

    /** Tab header: title (double-click to rename, middle-click to close) plus a close button. */
    private JPanel tabHeader(String title, CapturePanel cp) {
        JPanel h = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        h.setOpaque(false);
        JLabel l = new JLabel(title);
        JButton x = new JButton("×");
        x.setToolTipText("Close this capture");
        x.setFocusable(false);
        x.setMargin(new java.awt.Insets(0, 4, 0, 4));
        x.setBorderPainted(false);
        x.setContentAreaFilled(false);
        x.setFont(x.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        x.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                x.setForeground(java.awt.Color.RED);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                x.setForeground(null);
            }
        });
        x.addActionListener(e -> closeTab(cp));
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isMiddleMouseButton(e)) closeTab(cp);
                else tabs.setSelectedComponent(cp);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) renameTab(cp);
            }
        };
        l.addMouseListener(ma);
        h.addMouseListener(ma);
        h.add(l);
        h.add(x);
        return h;
    }

    /** Callbacks from the storage thread (already on the Swing thread). */
    private final Persist.Sink sink = new Persist.Sink() {
        public void loaded(String id, Store.Restored r) {
            if (closing || r.blocks.isEmpty()) return;
            addTab(r.title, r.scale, r.maxWidth < 0 ? st.maxWidth : r.maxWidth, r.blocks, false, id, false).setFrame(r.frame);
        }

        public void state(int locked, String message) {
            if (tabs.getTabCount() > 1 && tabs.getSelectedIndex() == 0) tabs.setSelectedIndex(1);
            problem = message;
            refreshLockBar();
        }

        public void error(String message) {
            problem = message;
            refreshLockBar();
        }
    };

    /** The yellow bar under the buttons: locked captures / missing password / the last storage problem. */
    void refreshLockBar() {
        boolean need = store.cannotSave();
        int locked = store.lockedCount();
        String text = null;
        if (need) {
            text = locked > 0 ? locked + " saved capture" + (locked == 1 ? " is" : "s are") + " encrypted and locked. Unlock to open " + (locked == 1 ? "it" : "them") + " and to keep saving."
                    : "Encryption is on, but the password has not been entered: new captures are not saved yet.";
        } else if (problem != null) {
            text = problem;
        }
        lockBar.setVisible(text != null);
        lockBtn.setVisible(need);
        lockBtn.setText(locked > 0 ? "Unlock..." : "Enter password...");
        if (text != null) lockText.setText(text);
        lockBar.revalidate();
    }

    void markDirty(CapturePanel cp) {
        if (cp != null) dirty.add(cp);
        saveSoon();
    }

    void saveSoon() {
        if (!closing) saveTimer.restart();
    }

    /**
     * Hands what changed to the storage thread. Only the Swing thread work is a cheap copy of the changed tabs; encoding, encrypting
     * and writing happen in the background, and unchanged tabs are not touched at all.
     */
    void saveNow() {
        saveTimer.stop();
        if (store.cannotSave()) { // encryption is on but locked: keep everything marked as changed, never write in the clear
            refreshLockBar();
            return;
        }
        List<String> order = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Component c = tabs.getComponentAt(i);
            if (!(c instanceof CapturePanel)) continue;
            CapturePanel cp = (CapturePanel) c;
            order.add(cp.captureId);
            if (dirty.contains(cp)) store.save(new Persist.Snap(cp.captureId, tabs.getTitleAt(i), cp.scale(), cp.maxWidth(), cp.frame(), cp.blocks()));
        }
        dirty.clear();
        if (indexDirty) {
            store.writeIndex(order);
            indexDirty = false;
        }
    }

    /** Copies of every open capture (Swing thread), for re-writing them in a new place / with a new password. */
    java.util.List<Persist.Snap> snapshots() {
        java.util.List<Persist.Snap> l = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Component c = tabs.getComponentAt(i);
            if (c instanceof CapturePanel) {
                CapturePanel cp = (CapturePanel) c;
                l.add(new Persist.Snap(cp.captureId, tabs.getTitleAt(i), cp.scale(), cp.maxWidth(), cp.frame(), cp.blocks()));
            }
        }
        return l;
    }

    /** Closes every capture tab on screen without touching the saved data (used by "Lock now"). */
    void removeTabsKeepData() {
        saveTimer.stop();
        dirty.clear();
        for (Component c : tabs.getComponents()) {
            if (c instanceof CapturePanel) {
                ((CapturePanel) c).disposeWindows();
                ((CapturePanel) c).stopTimers();
                tabs.remove(c);
            }
        }
        tabs.setSelectedIndex(0);
    }

    /** Opens an imported capture archive as a new tab. */
    void openImported(Store.Restored r) {
        addTab(r.title, r.scale, r.maxWidth < 0 ? st.maxWidth : r.maxWidth, r.blocks, true, null, true).setFrame(r.frame);
    }

    /**
     * Called when Burp unloads the extension. Must never hang Burp: the copy of the changed tabs is taken on the Swing thread with a
     * time limit, the storage thread is flushed with a time limit, timers are stopped and windows are closed without waiting.
     */
    void shutdown() {
        closing = true;
        Runnable snap = () -> {
            try {
                saveNow();
            } catch (Throwable ignored) {
            }
            for (Component c : tabs.getComponents()) if (c instanceof CapturePanel) ((CapturePanel) c).stopTimers();
            disposeWindows();
        };
        if (SwingUtilities.isEventDispatchThread()) snap.run();
        else {
            java.util.concurrent.FutureTask<Void> f = new java.util.concurrent.FutureTask<>(snap, null);
            SwingUtilities.invokeLater(f);
            try {
                f.get(2500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // the Swing thread is busy: what was saved earlier is already stored, do not hold Burp up
            }
        }
        store.shutdown();
    }

    void addCapture(String title, String request, String response) {
        List<Block> blocks = new ArrayList<>();
        Block rq = Block.http(Block.Kind.REQUEST, request, st);
        blocks.add(rq);
        if (response != null && !response.isEmpty()) {
            rq.separatorAfter = true;
            blocks.add(Block.http(Block.Kind.RESPONSE, response, st));
        }
        String t = "#" + (++counter) + " " + (title.length() > 28 ? title.substring(0, 28) + "..." : title);
        addTab(t, st.scale, st.maxWidth, blocks, true, null, true);
    }

    /** @param id the saved id (null = a new capture); @param fresh true for new captures, which still have to be saved */
    private CapturePanel addTab(String title, double scale, int maxWidth, List<Block> blocks, boolean select, String id, boolean fresh) {
        final CapturePanel[] holder = new CapturePanel[1];
        holder[0] = new CapturePanel(st, blocks, scale, maxWidth, () -> closeTab(holder[0]), this::openSettings, () -> markDirty(holder[0]));
        holder[0].captureId = id != null ? id : java.util.UUID.randomUUID().toString();
        holder[0].titleSupplier = () -> {
            int i = tabs.indexOfComponent(holder[0]);
            return i < 0 ? "capture" : tabs.getTitleAt(i);
        };
        themer.accept(holder[0]);
        tabs.addTab(title, holder[0]);
        tabs.setTabComponentAt(tabs.indexOfComponent(holder[0]), tabHeader(title, holder[0]));
        if (select) tabs.setSelectedComponent(holder[0]);
        if (fresh) {
            dirty.add(holder[0]);
            indexDirty = true;
            saveSoon();
        }
        return holder[0];
    }

    void openSettings() {
        if (settingsOpen) return;
        settingsOpen = true;
        SettingsDialog.open(Ui.window(this), st, () -> {
            persist.accept(st);
            for (Component c : tabs.getComponents()) if (c instanceof CapturePanel) ((CapturePanel) c).globalsChanged();
        });
        settingsOpen = false; // dialog is modeless; re-opening simply creates another one, which is harmless
    }

    void disposeWindows() {
        for (Component c : tabs.getComponents()) if (c instanceof CapturePanel) ((CapturePanel) c).disposeWindows();
    }
}
