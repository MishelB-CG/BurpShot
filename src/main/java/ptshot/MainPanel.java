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
import javax.swing.SwingUtilities;

/** The suite tab: a welcome page plus one tab per capture. */
final class MainPanel extends JPanel {
    private final Settings st;
    private final Consumer<Settings> persist;
    private final Consumer<Component> themer;
    private final JTabbedPane tabs = new JTabbedPane();
    private int counter;
    private boolean settingsOpen;

    MainPanel(Settings st, Consumer<Settings> persist, Consumer<Component> themer) {
        super(new BorderLayout());
        this.st = st;
        this.persist = persist;
        this.themer = themer;
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
    }

    void addCapture(String title, String request, String response) {
        List<Block> blocks = new ArrayList<>();
        Block rq = Block.http(Block.Kind.REQUEST, request, st);
        blocks.add(rq);
        if (response != null && !response.isEmpty()) {
            rq.separatorAfter = true;
            blocks.add(Block.http(Block.Kind.RESPONSE, response, st));
        }
        final CapturePanel[] holder = new CapturePanel[1];
        holder[0] = new CapturePanel(st, blocks, () -> {
            holder[0].disposeWindows();
            tabs.remove(holder[0]);
        }, this::openSettings);
        themer.accept(holder[0]);
        String t = "#" + (++counter) + " " + (title.length() > 28 ? title.substring(0, 28) + "..." : title);
        tabs.addTab(t, holder[0]);
        tabs.setSelectedComponent(holder[0]);
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
