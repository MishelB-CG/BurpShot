package ptshot;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.utilities.CompressionType;
import java.awt.Component;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

public class PtScreenshotExtension implements BurpExtension {
    private static final int MAX_CHARS = 200_000;

    private MontoyaApi api;
    private MainPanel panel;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("PT Screenshot Builder");

        Settings st = new Settings();
        try {
            Font ef = api.userInterface().currentEditorFont();
            if (ef != null) {
                st.bodyFont = new FontSpec(ef.getFamily(), ef.getSize(), false, false, java.awt.Color.BLACK);
            }
        } catch (Throwable ignored) {
            // keep defaults
        }
        st.load(k -> api.persistence().preferences().getString(k));

        SwingUtilities.invokeLater(() -> {
            panel = new MainPanel(st, s -> {
                for (Map.Entry<String, String> e : s.toMap().entrySet()) {
                    api.persistence().preferences().setString("ptshot." + e.getKey(), e.getValue());
                }
            }, c -> api.userInterface().applyThemeToComponent(c), new MainPanel.CaptureStore() {
                private static final String COUNT = "ptshot.captures.count";

                public List<String> load() {
                    List<String> l = new ArrayList<>();
                    burp.api.montoya.persistence.PersistedObject d = api.persistence().extensionData();
                    Integer n = d.getInteger(COUNT);
                    for (int i = 0; n != null && i < n; i++) {
                        String s = d.getString("ptshot.capture." + i);
                        if (s != null) l.add(s);
                    }
                    return l;
                }

                public void save(List<String> items) {
                    burp.api.montoya.persistence.PersistedObject d = api.persistence().extensionData();
                    Integer old = d.getInteger(COUNT);
                    for (int i = 0; i < items.size(); i++) d.setString("ptshot.capture." + i, items.get(i));
                    for (int i = items.size(); old != null && i < old; i++) d.deleteString("ptshot.capture." + i);
                    d.setInteger(COUNT, items.size());
                }
            });
            api.userInterface().applyThemeToComponent(panel);
            api.userInterface().registerSuiteTab("PT Screenshot", panel);
        });

        api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                List<HttpRequestResponse> items = new ArrayList<>();
                event.messageEditorRequestResponse().ifPresent(m -> items.add(m.requestResponse()));
                if (items.isEmpty()) items.addAll(event.selectedRequestResponses());
                if (items.isEmpty()) return new ArrayList<>();
                JMenuItem mi = new JMenuItem("Send to PT Screenshot Builder");
                mi.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                    for (HttpRequestResponse rr : items) send(rr);
                }));
                List<Component> out = new ArrayList<>();
                out.add(mi);
                return out;
            }
        });

        api.extension().registerUnloadingHandler(() -> {
            if (panel != null) {
                panel.saveNow();
                panel.disposeWindows();
            }
        });
        api.logging().logToOutput("PT Screenshot Builder loaded. Right-click a request -> Extensions -> PT Screenshot Builder.");
    }

    private void send(HttpRequestResponse rr) {
        try {
            HttpRequest req = rr.request();
            String request = clean(new String(req.toByteArray().getBytes(), StandardCharsets.UTF_8));
            String response = null;
            if (rr.hasResponse()) response = renderResponse(rr.response());
            if (panel != null) panel.addCapture(req.method() + " " + req.path(), request, response);
        } catch (Throwable t) {
            api.logging().logToError("PT Screenshot Builder: " + t);
        }
    }

    /** Response head as-is, body decompressed when Content-Encoding says so. */
    private String renderResponse(HttpResponse resp) {
        byte[] all = resp.toByteArray().getBytes();
        int off = Math.min(Math.max(resp.bodyOffset(), 0), all.length);
        String head = new String(all, 0, off, StandardCharsets.UTF_8);
        byte[] body = java.util.Arrays.copyOfRange(all, off, all.length);
        try {
            String enc = resp.headerValue("Content-Encoding");
            if (enc != null && body.length > 0) {
                CompressionType ct = null;
                String e = enc.trim().toLowerCase();
                if (e.contains("gzip")) ct = CompressionType.GZIP;
                else if (e.contains("deflate")) ct = CompressionType.DEFLATE;
                else if (e.contains("br")) ct = CompressionType.BROTLI;
                if (ct != null) {
                    ByteArray dec = api.utilities().compressionUtils().decompress(ByteArray.byteArray(body), ct);
                    body = dec.getBytes();
                }
            }
        } catch (Throwable ignored) {
            // leave body as is
        }
        return clean(head + new String(body, StandardCharsets.UTF_8));
    }

    /** Replaces control characters / binary junk so the text stays drawable, and caps the size. */
    private static String clean(String s) {
        if (s.length() > MAX_CHARS) s = s.substring(0, MAX_CHARS);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < 0x20 && c != '\n' && c != '\r' && c != '\t') || c == 0x7f || c == 0xFFFD) sb.append('.');
            else sb.append(c);
        }
        return sb.toString();
    }
}
