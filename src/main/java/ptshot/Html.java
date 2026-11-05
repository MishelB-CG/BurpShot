package ptshot;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JEditorPane;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

/**
 * Offline HTML renderer based on Swing's HTML engine (HTML 3.2 + a CSS subset - good for login forms, error pages,
 * simple reflected-XSS / injected-link evidence; not a browser). For pixel-exact pages use the screen capture of
 * Burp's own Render tab instead.
 *
 * Nothing is ever fetched: scripts, frames, stylesheets, CSS url()s and images are stripped before rendering,
 * so rendering untrusted response bodies can't make network requests.
 */
final class Html {
    private Html() {}

    private static final Pattern IMG = Pattern.compile("(?is)<img\\b([^>]*)>");
    private static final Pattern ALT = Pattern.compile("(?is)\\balt\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

    static String sanitize(String html) {
        String s = html;
        s = s.replaceAll("(?is)<script\\b.*?</script>", "");
        s = s.replaceAll("(?is)<(iframe|object|embed|applet|frame)\\b.*?(</\\1>|/>|>)", "");
        s = s.replaceAll("(?is)<(link|base|meta)\\b[^>]*>", "");
        s = s.replaceAll("(?is)@import[^;]*;", "");
        s = s.replaceAll("(?is)url\\s*\\([^)]*\\)", "none");
        s = s.replaceAll("(?is)\\sbackground\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        s = formControls(s);
        Matcher m = IMG.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String alt = "";
            Matcher a = ALT.matcher(m.group(1));
            if (a.find()) alt = a.group(2) != null ? a.group(2) : a.group(3);
            String rep = "<span style=\"color:#888888\">[image" + (alt.isEmpty() ? "" : ": " + alt.replace("$", "")) + "]</span>";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?is)\\b" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").matcher(tag);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
    }

    /** Swing can't paint form controls offscreen, so draw them as bracketed text ([ value ], [ ***** ], [ Submit ]). */
    private static String formControls(String s) {
        Matcher m = Pattern.compile("(?is)<input\\b[^>]*>").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tag = m.group();
            String type = attr(tag, "type");
            type = type == null ? "text" : type.toLowerCase();
            String val = attr(tag, "value"), ph = attr(tag, "placeholder");
            String rep;
            switch (type) {
                case "hidden": rep = ""; break;
                case "submit": case "button": case "reset": case "image": rep = "[&nbsp;" + (val == null ? "Submit" : val) + "&nbsp;]"; break;
                case "checkbox": rep = tag.toLowerCase().contains("checked") ? "[x]" : "[&nbsp;&nbsp;]"; break;
                case "radio": rep = tag.toLowerCase().contains("checked") ? "(*)" : "(&nbsp;&nbsp;)"; break;
                case "password": rep = "[&nbsp;" + "\u2022".repeat(val == null ? 8 : Math.max(1, val.length())) + "&nbsp;]"; break;
                default:
                    String shown = val != null && !val.isEmpty() ? val : ph != null ? ph : "";
                    rep = "[&nbsp;" + (shown.isEmpty() ? "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;" : shown) + "&nbsp;]";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        s = sb.toString();
        s = s.replaceAll("(?is)<button\\b[^>]*>(.*?)</button>", "[&nbsp;$1&nbsp;]");
        s = s.replaceAll("(?is)<textarea\\b[^>]*>(.*?)</textarea>", "[&nbsp;$1&nbsp;]");
        s = s.replaceAll("(?is)<select\\b.*?</select>", "[&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&#9662;&nbsp;]");
        return s;
    }

    /** Renders the HTML at a logical width; the bitmap is {@code scale} times larger for crispness. */
    static BufferedImage render(String html, int width, double scale) {
        String src = sanitize(html);
        String low = src.toLowerCase();
        if (!low.contains("<html") && !low.contains("<body")) src = "<html><body>" + src + "</body></html>";

        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet ss = new StyleSheet();
        ss.addStyleSheet(kit.getStyleSheet());
        ss.addRule("body { font-family: SansSerif; font-size: 14px; margin: 8px; color: #000000; }");
        kit.setStyleSheet(ss);
        JEditorPane p = new JEditorPane();
        p.setEditorKit(kit);
        p.setEditable(false);
        p.setBackground(Color.WHITE);
        HTMLDocument doc = (HTMLDocument) kit.createDefaultDocument();
        doc.setAsynchronousLoadPriority(-1); // load synchronously
        p.setDocument(doc);
        p.setText(src);

        p.setSize(new Dimension(width, Short.MAX_VALUE));
        int h = Math.max(24, p.getPreferredSize().height);
        p.setSize(new Dimension(width, h));

        BufferedImage img = new BufferedImage((int) Math.ceil(width * scale), (int) Math.ceil(h * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Renderer.hints(g);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.scale(scale, scale);
        p.paint(g);
        g.dispose();
        return img;
    }
}
