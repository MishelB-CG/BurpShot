package ptshot;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.hotkey.HotKey;
import burp.api.montoya.ui.hotkey.HotKeyContext;
import burp.api.montoya.ui.hotkey.HotKeyHandler;
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
    /** Default shortcut for "Send to BurpShot". Ctrl+B is Burp's own Base64-encode, so this one is used; it can be changed in Burp's hotkey settings. */
    static final String HOTKEY = "Ctrl+Alt+B";
    private static final String HOTKEY_NAME = "BurpShot: Send to BurpShot";

    private MontoyaApi api;
    private MainPanel panel;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("BurpShot");

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

        // where captures are kept: Burp project file (default) / this session / a folder, optionally encrypted (see Persist)
        final Persist.Env env = new Persist.Env() {
            public String projectName() {
                try {
                    String n = api.project().name();
                    return n == null ? "" : n;
                } catch (Throwable t) {
                    return "";
                }
            }

            public CaptureBackend projectBackend() {
                return new ProjectBackend(api.persistence().extensionData());
            }

            public String pref(String key) {
                try {
                    return api.persistence().preferences().getString(key);
                } catch (Throwable t) {
                    return null;
                }
            }

            public void setPref(String key, String value) {
                api.persistence().preferences().setString(key, value);
            }
        };
        final Persist store = new Persist(env);

        SwingUtilities.invokeLater(() -> {
            panel = new MainPanel(st, s -> {
                for (Map.Entry<String, String> e : s.toMap().entrySet()) {
                    api.persistence().preferences().setString("ptshot." + e.getKey(), e.getValue());
                }
            }, c -> api.userInterface().applyThemeToComponent(c), store);
            api.userInterface().applyThemeToComponent(panel);
            api.userInterface().registerSuiteTab("BurpShot", panel);
        });

        api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                List<HttpRequestResponse> items = new ArrayList<>();
                event.messageEditorRequestResponse().ifPresent(m -> items.add(m.requestResponse()));
                if (items.isEmpty()) items.addAll(event.selectedRequestResponses());
                if (items.isEmpty()) return new ArrayList<>();
                JMenuItem mi = new JMenuItem("Send to BurpShot");
                mi.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                    for (HttpRequestResponse rr : items) send(rr);
                }));
                List<Component> out = new ArrayList<>();
                out.add(mi);
                return out;
            }
        });

        // keyboard shortcut: works wherever Burp offers hotkeys (message editors, Proxy history, Intruder results, Organizer, site map)
        HotKeyHandler hotKeyHandler = event -> {
            List<HttpRequestResponse> items = new ArrayList<>();
            event.messageEditorRequestResponse().ifPresent(m -> items.add(m.requestResponse()));
            if (items.isEmpty()) items.addAll(event.selectedRequestResponses());
            if (items.isEmpty()) return;
            SwingUtilities.invokeLater(() -> {
                for (HttpRequestResponse rr : items) send(rr);
            });
        };
        int registered = 0;
        for (HotKeyContext ctx : HotKeyContext.values()) {
            try {
                api.userInterface().registerHotKeyHandler(ctx, HotKey.hotKey(HOTKEY_NAME, HOTKEY), hotKeyHandler);
                registered++;
            } catch (Throwable t) {
                api.logging().logToError("BurpShot: could not register the " + HOTKEY + " shortcut in " + ctx + ": " + t);
            }
        }

        try {
            Ui.suite = api.userInterface().swingUtils().suiteFrame();
        } catch (Throwable t) {
            Ui.suite = null; // dialogs then fall back to the window that holds the BurpShot tab
        }
        RemoteHttp.burp = new BurpHttp(api); // Share's HTTP(S) uploads honour Burp's proxy / TLS settings
        api.extension().registerUnloadingHandler(() -> {
            RemoteHttp.burp = null;
            ShareDialog.cancelAll();
            if (panel != null) panel.shutdown(); // bounded: never holds Burp up when it closes
            else store.shutdown();
        });
        api.logging().logToOutput("BurpShot loaded. Right-click a request -> Extensions -> BurpShot, or press " + HOTKEY
                + " (" + registered + " hotkey contexts registered; change it in Settings -> User interface -> Hotkeys).");
    }

    private void send(HttpRequestResponse rr) {
        try {
            HttpRequest req = rr.request();
            String request = clean(new String(req.toByteArray().getBytes(), StandardCharsets.UTF_8));
            String response = null;
            if (rr.hasResponse()) response = renderResponse(rr.response());
            if (panel != null) panel.addCapture(req.method() + " " + req.path(), request, response);
        } catch (Throwable t) {
            api.logging().logToError("BurpShot: " + t);
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
