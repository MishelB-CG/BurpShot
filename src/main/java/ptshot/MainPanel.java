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

    /** Project-scoped storage for captures (backed by Burp's extension data, like Repeater tabs). */
    interface CaptureStore {
        List<String> load();

        void save(List<String> serialized);
    }

    private final CaptureStore store;
    private final javax.swing.Timer saveTimer;
    private boolean restoring;

    MainPanel(Settings st, Consumer<Settings> persist, Consumer<Component> themer, CaptureStore store) {
        super(new BorderLayout());
        this.st = st;
        this.persist = persist;
        this.themer = themer;
        this.store = store;
        saveTimer = new javax.swing.Timer(800, e -> saveNow());
        saveTimer.setRepeats(false);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton fresh = new JButton("New empty capture");
        fresh.addActionListener(e -> addCapture("manual", "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html></html>"));
        JButton set = new JButton("Global settings...");
        set.addActionListener(e -> openSettings());
        bar.add(fresh);
        bar.add(set);
        bar.add(new JLabel("Right-click any request/response in Proxy, Repeater, Logger... \u2192 Extensions \u2192 PT Screenshot Builder \u2192 Send to builder"));
        add(bar, BorderLayout.NORTH);

        JLabel help = new JLabel("<html><body style='width:640px'>"
                + "<h2>PT Screenshot Builder</h2>"
                + "<p>Right-click a request (or several) anywhere in Burp and choose <b>Extensions &rarr; PT Screenshot Builder &rarr; Send to builder</b>. "
                + "Each one opens as its own tab here.</p>"
                + "<ol>"
                + "<li><b>Content</b> - pick the first line, headers, body, individual headers / form parameters, or edit the text freely. "
                + "Select text and use <i>Selection &rarr; ...</i>, <i>Ellipsize outside</i> or the line buttons to keep only part of a header value or JSON element.</li>"
                + "<li><b>Annotate</b> - select text and press Box / Arrow / Pixelate / Blur. They stay attached to that text. Style them in the <i>Annotations</i> tab.</li>"
                + "<li><b>Style &amp; layout</b> - labels (default Calibri, red), fonts, separators, put blocks beside each other, add custom headers/footers and image blocks.</li>"
                + "<li>The right side is a live preview. <b>Copy image</b> / <b>Save PNG</b> export it, or use <b>Preview window</b> and capture it with any external tool.</li>"
                + "</ol></body></html>");
        help.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        help.setVerticalAlignment(JLabel.TOP);
        tabs.addTab("Welcome", new JScrollPane(help));
        add(tabs, BorderLayout.CENTER);
        restore();
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
            saveSoon();
        }
    }

    private void closeTab(CapturePanel cp) {
        cp.disposeWindows();
        tabs.remove(cp);
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

    private void restore() {
        restoring = true;
        try {
            for (String json : store.load()) {
                try {
                    Store.Restored r = Store.deserialize(json);
                    if (!r.blocks.isEmpty()) addTab(r.title, r.scale, r.maxWidth < 0 ? st.maxWidth : r.maxWidth, r.blocks, false);
                } catch (Exception ignored) {
                    // skip unreadable capture
                }
            }
            if (tabs.getTabCount() > 1) tabs.setSelectedIndex(1);
        } finally {
            restoring = false;
        }
    }

    void saveSoon() {
        if (!restoring) saveTimer.restart();
    }

    /** Writes every capture to the project file now. */
    void saveNow() {
        saveTimer.stop();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Component c = tabs.getComponentAt(i);
            if (c instanceof CapturePanel) {
                CapturePanel cp = (CapturePanel) c;
                out.add(Store.serialize(tabs.getTitleAt(i), cp.scale(), cp.maxWidth(), cp.blocks()));
            }
        }
        try {
            store.save(out);
        } catch (Exception ignored) {
        }
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
        addTab(t, st.scale, st.maxWidth, blocks, true);
    }

    private void addTab(String title, double scale, int maxWidth, List<Block> blocks, boolean select) {
        final CapturePanel[] holder = new CapturePanel[1];
        holder[0] = new CapturePanel(st, blocks, scale, maxWidth, () -> closeTab(holder[0]), this::openSettings, this::saveSoon);
        themer.accept(holder[0]);
        tabs.addTab(title, holder[0]);
        tabs.setTabComponentAt(tabs.indexOfComponent(holder[0]), tabHeader(title, holder[0]));
        if (select) tabs.setSelectedComponent(holder[0]);
        saveSoon();
    }

    void openSettings() {
        if (settingsOpen) return;
        settingsOpen = true;
        SettingsDialog.open(SwingUtilities.getWindowAncestor(this), st, () -> {
            persist.accept(st);
            for (Component c : tabs.getComponents()) if (c instanceof CapturePanel) ((CapturePanel) c).globalsChanged();
        });
        settingsOpen = false; // dialog is modeless; re-opening simply creates another one, which is harmless
    }

    void disposeWindows() {
        for (Component c : tabs.getComponents()) if (c instanceof CapturePanel) ((CapturePanel) c).disposeWindows();
    }
}
