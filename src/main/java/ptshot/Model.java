package ptshot;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** An annotation (box / arrow / pixelate / blur) anchored to a piece of text inside a block. */
final class Annotation {
    enum Type { BOX, ARROW, PIXELATE, BLUR }

    enum Edge { BOTTOM, TOP, LEFT, RIGHT, CENTER }

    Type type;
    String anchor;          // the annotated text; located again on every render, so it survives edits elsewhere
    int occurrence;         // which occurrence of the anchor text (0 based)
    Color color = new Color(200, 0, 0);
    double width = 2;       // box/arrow-line stroke width, or arrow stem width for "block" arrows
    int pad = 3;            // extra space around the anchored text
    boolean fullWidth;      // stretch to the full width of the block's text
    boolean blockArrow = true;
    double angle = 90;      // degrees, 0 = right, 90 = down
    int length = 90;
    int headSize = 16;
    Edge edge = Edge.BOTTOM;
    int dx, dy;
    int strength = 8;       // pixelate cell size / blur radius

    static Annotation create(Type t, String anchor, int occurrence) {
        Annotation a = new Annotation();
        a.type = t;
        a.anchor = anchor;
        a.occurrence = occurrence;
        if (t == Type.ARROW) a.width = 10;
        if (t == Type.PIXELATE) a.pad = 1;
        if (t == Type.BLUR) a.pad = 1;
        return a;
    }

    @Override
    public String toString() {
        String s = anchor.replace("\n", "\u21b5");
        if (s.length() > 40) s = s.substring(0, 37) + "...";
        return type + "  \"" + s + "\"";
    }
}

final class Block {
    enum Kind { REQUEST, RESPONSE, CUSTOM, IMAGE }

    Kind kind;
    // label ("Request", "Response", ...)
    boolean showLabel = true;
    String label = "";
    FontSpec labelFont;   // null = global default
    FontSpec bodyFont;    // null = global default
    Color background;     // optional fill behind the body
    boolean highlight = true;
    int align;            // 0 left, 1 centre, 2 right
    // content
    String original = "";
    String text = "";
    BufferedImage image;
    int imageMaxWidth = 500;
    // filters (REQUEST / RESPONSE only)
    boolean incFirst = true, incHeaders = true, incBody = true, prettyJson;
    int maxBodyChars;
    final Set<Integer> hiddenHeaders = new HashSet<>();
    final Set<Integer> hiddenParams = new HashSet<>();
    // fitting
    int wrapCols, maxLines;
    boolean wrap = true;
    // layout
    boolean beside, separatorAfter;
    Color sepColor;
    int sepWidth;         // 0 = global
    final List<Annotation> annotations = new ArrayList<>();

    static Block http(Kind k, String raw, Settings st) {
        Block b = new Block();
        b.kind = k;
        b.label = k == Kind.REQUEST ? "Request" : "Response";
        b.original = raw;
        b.wrapCols = st.wrapCols;
        b.maxLines = st.maxLines;
        b.wrap = st.wrap;
        b.regenerate();
        return b;
    }

    static Block custom(String label, Settings st) {
        Block b = new Block();
        b.kind = Kind.CUSTOM;
        b.label = label;
        b.highlight = false;
        b.wrapCols = st.wrapCols;
        b.maxLines = 0;
        b.wrap = st.wrap;
        return b;
    }

    static Block image(BufferedImage img) {
        Block b = new Block();
        b.kind = Kind.IMAGE;
        b.image = img;
        b.showLabel = false;
        b.align = 1;
        b.label = "Image";
        return b;
    }

    boolean isHttp() {
        return kind == Kind.REQUEST || kind == Kind.RESPONSE;
    }

    Block duplicate() {
        Block b = new Block();
        b.kind = kind; b.showLabel = showLabel; b.label = label;
        b.labelFont = labelFont == null ? null : labelFont.copy();
        b.bodyFont = bodyFont == null ? null : bodyFont.copy();
        b.background = background; b.highlight = highlight; b.align = align;
        b.original = original; b.text = text; b.image = image; b.imageMaxWidth = imageMaxWidth;
        b.incFirst = incFirst; b.incHeaders = incHeaders; b.incBody = incBody; b.prettyJson = prettyJson;
        b.maxBodyChars = maxBodyChars; b.hiddenHeaders.addAll(hiddenHeaders); b.hiddenParams.addAll(hiddenParams);
        b.wrapCols = wrapCols; b.maxLines = maxLines; b.wrap = wrap;
        b.beside = beside; b.separatorAfter = separatorAfter; b.sepColor = sepColor; b.sepWidth = sepWidth;
        for (Annotation a : annotations) {
            Annotation c = Annotation.create(a.type, a.anchor, a.occurrence);
            c.color = a.color; c.width = a.width; c.pad = a.pad; c.fullWidth = a.fullWidth; c.blockArrow = a.blockArrow;
            c.angle = a.angle; c.length = a.length; c.headSize = a.headSize; c.edge = a.edge; c.dx = a.dx; c.dy = a.dy;
            c.strength = a.strength;
            b.annotations.add(c);
        }
        return b;
    }

    // ---- HTTP message parsing / filtering ----

    private String norm() {
        return original.replace("\r\n", "\n");
    }

    private String head() {
        String n = norm();
        int sp = n.indexOf("\n\n");
        return sp < 0 ? n : n.substring(0, sp);
    }

    private String body() {
        String n = norm();
        int sp = n.indexOf("\n\n");
        return sp < 0 ? "" : n.substring(sp + 2);
    }

    /** The message body (as captured, before filters / edits). */
    String bodyText() {
        return body();
    }

    /** Lines of the head; index 0 is the request/status line. */
    List<String> headLines() {
        List<String> l = new ArrayList<>();
        for (String s : head().split("\n", -1)) l.add(s);
        return l;
    }

    static boolean looksForm(String body) {
        return !body.isEmpty() && body.indexOf('\n') < 0 && body.matches("^[^\\s=&{}<>\\[\\]\"]+=.*");
    }

    static boolean looksJson(String body) {
        String t = body.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    /** Form parameters of the body, or null if the body is not url-encoded form data. */
    List<String> formParams() {
        String b = body();
        if (!looksForm(b)) return null;
        List<String> l = new ArrayList<>();
        for (String s : b.split("&", -1)) l.add(s);
        return l;
    }

    void regenerate() {
        if (!isHttp()) return;
        List<String> hl = headLines();
        StringBuilder sb = new StringBuilder();
        if (incFirst && !hl.isEmpty()) sb.append(hl.get(0)).append('\n');
        if (incHeaders) {
            for (int i = 1; i < hl.size(); i++) if (!hiddenHeaders.contains(i)) sb.append(hl.get(i)).append('\n');
        }
        String body = body();
        if (incBody && !body.isEmpty()) {
            List<String> params = formParams();
            if (params != null && !hiddenParams.isEmpty()) {
                List<String> keep = new ArrayList<>();
                for (int i = 0; i < params.size(); i++) if (!hiddenParams.contains(i)) keep.add(params.get(i));
                body = String.join("&", keep);
            } else if (prettyJson && looksJson(body)) {
                body = Json.pretty(body);
            }
            if (maxBodyChars > 0 && body.length() > maxBodyChars) body = body.substring(0, maxBodyChars) + "\n...";
            if (sb.length() > 0) sb.append('\n');
            sb.append(body);
        }
        String out = sb.toString();
        while (out.endsWith("\n")) out = out.substring(0, out.length() - 1);
        text = out;
    }

    @Override
    public String toString() {
        String s = kind + (label.isEmpty() ? "" : " \u2013 " + label);
        if (kind == Kind.CUSTOM && !text.isEmpty()) {
            String t = text.replace("\n", " ");
            s += "  [" + (t.length() > 24 ? t.substring(0, 24) + "..." : t) + "]";
        }
        return (beside ? "   \u21aa " : "") + s;
    }
}

final class Json {
    private Json() {}

    /** Tiny string-aware JSON pretty printer (2 space indent, no space after ':' - like Burp's Pretty view). */
    static String pretty(String s) {
        StringBuilder o = new StringBuilder();
        int ind = 0, n = s.length();
        boolean inStr = false, esc = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (inStr) {
                o.append(c);
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            switch (c) {
                case '"':
                    inStr = true;
                    o.append(c);
                    break;
                case '{':
                case '[': {
                    o.append(c);
                    int j = i + 1;
                    while (j < n && Character.isWhitespace(s.charAt(j))) j++;
                    if (j < n && (s.charAt(j) == '}' || s.charAt(j) == ']')) {
                        o.append(s.charAt(j));
                        i = j;
                    } else {
                        ind++;
                        nl(o, ind);
                    }
                    break;
                }
                case '}':
                case ']':
                    ind = Math.max(0, ind - 1);
                    nl(o, ind);
                    o.append(c);
                    break;
                case ',':
                    o.append(c);
                    nl(o, ind);
                    break;
                case ' ':
                case '\t':
                case '\n':
                case '\r':
                    break;
                default:
                    o.append(c);
            }
        }
        return o.toString();
    }

    private static void nl(StringBuilder o, int ind) {
        o.append('\n');
        for (int i = 0; i < ind; i++) o.append("  ");
    }
}

/** Global defaults (persisted by the extension). */
final class Settings {
    static final int K_KEY = 0, K_STR = 1, K_NUM = 2, K_PNAME = 3, K_PVAL = 4, K_TAG = 5, K_HDR = 6;
    static final String[] SYN_NAMES = {"JSON key / literal", "JSON string", "Number", "Parameter name",
            "Parameter value", "Markup tag", "Header name"};

    FontSpec bodyFont = new FontSpec("Monospaced", 14, false, false, Color.BLACK);
    FontSpec labelFont = new FontSpec("Calibri", 20, false, false, new Color(255, 0, 0));
    Color background = Color.WHITE;
    Color sepColor = new Color(170, 170, 170);
    int sepWidth = 1, margin = 14, rowGap = 12, colGap = 24, labelGap = 4;
    int wrapCols = 100, maxLines = 30;
    boolean wrap = true;
    double scale = 2.0;
    Color[] syn = {new Color(0, 0, 128), new Color(0, 128, 0), new Color(0, 0, 255), new Color(0, 0, 205),
            new Color(170, 0, 0), new Color(160, 0, 160), new Color(0, 0, 128)};

    private static String hex(Color c) {
        return Integer.toHexString(c.getRGB() & 0xffffff);
    }

    private static Color col(String s, Color d) {
        try {
            return s == null ? d : new Color(Integer.parseInt(s, 16));
        } catch (Exception e) {
            return d;
        }
    }

    private static int num(String s, int d) {
        try {
            return s == null ? d : Integer.parseInt(s);
        } catch (Exception e) {
            return d;
        }
    }

    Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("body", bodyFont.ser());
        m.put("label", labelFont.ser());
        m.put("bg", hex(background));
        m.put("sep", hex(sepColor));
        m.put("sepW", "" + sepWidth);
        m.put("margin", "" + margin);
        m.put("rowGap", "" + rowGap);
        m.put("colGap", "" + colGap);
        m.put("labelGap", "" + labelGap);
        m.put("wrapCols", "" + wrapCols);
        m.put("maxLines", "" + maxLines);
        m.put("wrap", "" + wrap);
        m.put("scale", "" + scale);
        for (int i = 0; i < syn.length; i++) m.put("syn" + i, hex(syn[i]));
        return m;
    }

    void load(Function<String, String> get) {
        Function<String, String> g = k -> {
            try {
                return get.apply("ptshot." + k);
            } catch (Exception e) {
                return null;
            }
        };
        if (g.apply("body") != null) bodyFont = FontSpec.parse(g.apply("body"), bodyFont);
        if (g.apply("label") != null) labelFont = FontSpec.parse(g.apply("label"), labelFont);
        background = col(g.apply("bg"), background);
        sepColor = col(g.apply("sep"), sepColor);
        sepWidth = num(g.apply("sepW"), sepWidth);
        margin = num(g.apply("margin"), margin);
        rowGap = num(g.apply("rowGap"), rowGap);
        colGap = num(g.apply("colGap"), colGap);
        labelGap = num(g.apply("labelGap"), labelGap);
        wrapCols = num(g.apply("wrapCols"), wrapCols);
        maxLines = num(g.apply("maxLines"), maxLines);
        if (g.apply("wrap") != null) wrap = Boolean.parseBoolean(g.apply("wrap"));
        try {
            if (g.apply("scale") != null) scale = Double.parseDouble(g.apply("scale"));
        } catch (Exception ignored) {
        }
        for (int i = 0; i < syn.length; i++) syn[i] = col(g.apply("syn" + i), syn[i]);
    }
}
