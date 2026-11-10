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
    double offL, offT, offR, offB; // manual edge adjustments (1x px) made by dragging in the preview
    int strength = 8;       // pixelate cell size / blur radius
    boolean free;           // not attached to text: placed on the canvas (1x px) and moved by hand
    double fx, fy, fw, fh;  // canvas rectangle of a free annotation

    /** An element dropped onto the canvas (not attached to any text). Arrows start at the given point. */
    static Annotation createFree(Type t, double cx, double cy) {
        Annotation a = create(t, "", 0);
        a.free = true;
        double w = t == Type.BOX ? 200 : t == Type.ARROW ? 1 : 150, h = t == Type.BOX ? 64 : t == Type.ARROW ? 1 : 40;
        a.fx = Math.max(0, cx - w / 2);
        a.fy = Math.max(0, cy - h / 2);
        a.fw = w;
        a.fh = h;
        if (t == Type.ARROW) a.edge = Edge.CENTER;
        return a;
    }

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
        if (free) return type + "  (free on canvas)";
        String s = anchor.startsWith("\u0001b:") ? "table cells " + anchor.substring(3).replace(':', ',') : anchor.replace("\n", "\u21b5");
        if (s.length() > 40) s = s.substring(0, 37) + "...";
        return type + "  \"" + s + "\"";
    }
}

final class Block {
    enum Kind { REQUEST, RESPONSE, CUSTOM, IMAGE, TABLE, TERMINAL }

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
    // manual layer placement (1x px), set by dragging / resizing in the preview; 0 = automatic
    double offX, offY;    // moved away from where the flow layout put it
    double boxW;          // text blocks: fixed box width (wrap width)
    double imageW;        // image blocks: fixed display width (may enlarge)
    // TERMINAL blocks: raw = pasted output including ANSI colour codes, text = the visible text derived from it
    String raw = "";
    String preset = "Windows cmd";
    String chrome = "NONE";
    String title = "";
    Color[] palette;      // null = the scheme's 16 colours
    String theme = "DARK";   // DARK / LIGHT variant of the scheme
    boolean cmdStyle = true;     // draw the command after a prompt apart from its output
    boolean indentColors = true; // colour output lines by indentation level
    Color[] accents;      // {command, indent 1, indent 2, indent 3}; null = the scheme's
    boolean overlay;      // freehand drawing layer: sits at the canvas origin, takes no space in the flow
    List<List<String>> rows = new ArrayList<>(); // TABLE blocks: row 0 is the header
    // filters (REQUEST / RESPONSE only)
    boolean incFirst = true, incHeaders = true, incBody = true, pretty;
    int maxBodyChars;
    // window over a long body ("scroller"): viewOn -1 = automatic (only very long bodies), 0 = show everything, 1 = always windowed
    int viewOn = -1, viewUnit, viewSize = 10000, viewOffset;   // unit 0 = characters, 1 = lines; offset = characters from the start of the body
    private int winStart, winLen;      // the slice of the body the editor currently shows (body-relative)
    private boolean winValid;
    String genText;                    // what BurpShot generated for the current filters: the base of the merge when a filter changes
    static final int AUTO_WINDOW_AT = 200_000, SHOW_ALL_MAX = 5_000_000, MAX_WINDOW_CHARS = 200_000, MAX_WINDOW_LINES = 5_000, BAR_AT = 20_000;
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
    Border border;        // null = no border around this layer

    static Block http(Kind k, String raw, Settings st) {
        Block b = new Block();
        b.kind = k;
        b.label = k == Kind.REQUEST ? "Request" : "Response";
        b.original = raw;
        b.pretty = true; // Burp's message editor defaults to the Pretty view
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

    private static String monoFamily() {
        java.util.Set<String> have = new java.util.HashSet<>(java.util.Arrays.asList(FontSpec.allFamilies()));
        for (String f : new String[]{"Cascadia Mono", "Consolas", "Menlo", "DejaVu Sans Mono"}) if (have.contains(f)) return f;
        return "Monospaced";
    }

    /** A terminal window look-alike: pasted output (ANSI colours honoured) in a chosen colour scheme. */
    static Block terminal(String presetName, Settings st) {
        Term.Preset p = Term.preset(presetName);
        Term.Look lk = "LIGHT".equals(p.defaultTheme) ? p.light : p.dark;
        Block b = new Block();
        b.kind = Kind.TERMINAL;
        b.theme = p.defaultTheme;
        b.label = "Terminal";
        b.showLabel = false;
        b.highlight = false;
        b.preset = p.name;
        b.chrome = p.chrome;
        b.title = p.title;
        b.background = lk.bg;
        b.raw = p.sample;
        b.text = Term.strip(p.sample);
        b.bodyFont = new FontSpec(monoFamily(), Math.max(12, st.bodyFont.size), false, false, lk.fg);
        b.wrapCols = st.wrapCols;
        b.maxLines = 0;
        b.wrap = st.wrap;
        return b;
    }

    static Block table(String label, List<List<String>> rows, Settings st) {
        Block b = new Block();
        b.kind = Kind.TABLE;
        b.label = label;
        b.showLabel = false;
        b.highlight = false;
        b.wrapCols = st.wrapCols;
        b.wrap = st.wrap;
        b.bodyFont = new FontSpec("Segoe UI", Math.max(10, st.bodyFont.size), false, false, new Color(20, 20, 20));
        for (List<String> r : rows) b.rows.add(new ArrayList<>(r));
        return b;
    }

    /** The freehand drawing layer: a transparent image that sits on top at the canvas origin. */
    static Block drawing(BufferedImage img, double width1x) {
        Block b = image(img);
        b.overlay = true;
        b.label = "Drawing";
        b.imageW = width1x;
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

    /** Table anchors: "\u0001b:r0:c0:r1:c1" is a block of cells; any other text matches the first cell containing it. */
    static String cellAnchor(int r0, int c0, int r1, int c1) {
        return "\u0001b:" + Math.min(r0, r1) + ":" + Math.min(c0, c1) + ":" + Math.max(r0, r1) + ":" + Math.max(c0, c1);
    }

    /** Is the annotation's anchor still resolvable in this block? */
    boolean hasAnchor(Annotation a) {
        if (a.free) return true;
        if (kind != Kind.TABLE) return text.contains(a.anchor);
        if (a.anchor.startsWith("\u0001b:")) {
            try {
                String[] p = a.anchor.substring(3).split(":");
                return Integer.parseInt(p[0]) < rows.size() && Integer.parseInt(p[1]) >= 0;
            } catch (RuntimeException e) {
                return false;
            }
        }
        for (List<String> r : rows) for (String c : r) if (c.contains(a.anchor)) return true;
        return false;
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
        b.offX = offX; b.offY = offY; b.boxW = boxW; b.imageW = imageW; b.overlay = overlay;
        b.raw = raw; b.preset = preset; b.chrome = chrome; b.title = title; b.palette = palette == null ? null : palette.clone();
        b.theme = theme; b.cmdStyle = cmdStyle; b.indentColors = indentColors; b.accents = accents == null ? null : accents.clone();
        for (List<String> r : rows) b.rows.add(new ArrayList<>(r));
        b.incFirst = incFirst; b.incHeaders = incHeaders; b.incBody = incBody; b.pretty = pretty;
        b.maxBodyChars = maxBodyChars; b.hiddenHeaders.addAll(hiddenHeaders); b.hiddenParams.addAll(hiddenParams);
        b.viewOn = viewOn; b.viewUnit = viewUnit; b.viewSize = viewSize; b.viewOffset = viewOffset; b.genText = genText;
        b.wrapCols = wrapCols; b.maxLines = maxLines; b.wrap = wrap;
        b.beside = beside; b.separatorAfter = separatorAfter; b.sepColor = sepColor; b.sepWidth = sepWidth;
        b.border = border == null ? null : border.copy();
        for (Annotation a : annotations) {
            Annotation c = Annotation.create(a.type, a.anchor, a.occurrence);
            c.color = a.color; c.width = a.width; c.pad = a.pad; c.fullWidth = a.fullWidth; c.blockArrow = a.blockArrow;
            c.angle = a.angle; c.length = a.length; c.headSize = a.headSize; c.edge = a.edge; c.dx = a.dx; c.dy = a.dy;
            c.offL = a.offL; c.offT = a.offT; c.offR = a.offR; c.offB = a.offB;
            c.strength = a.strength;
            c.free = a.free; c.fx = a.fx; c.fy = a.fy; c.fw = a.fw; c.fh = a.fh;
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

    private static final java.util.regex.Pattern HEADISH = java.util.regex.Pattern.compile("^(HTTP/\\d|[A-Z]+ \\S+ HTTP/|[A-Za-z0-9!#$%&'*+.^_`|~-]+:)");

    /**
     * Splits (possibly hand-edited) message text into {head, body}. The head ends at the first blank line; text without a blank line
     * is the head if it starts like one (request / status line or a header), otherwise it is all body.
     */
    static String[] splitBody(String text) {
        int i = text.indexOf("\n\n");
        if (i >= 0) return new String[]{text.substring(0, i), text.substring(i + 2)};
        if (HEADISH.matcher(text).find()) return new String[]{text, ""};
        return new String[]{"", text};
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

    /** Value of the first header with this (case-insensitive) name, or "". */
    String headerValue(String name) {
        List<String> hl = headLines();
        for (int i = 1; i < hl.size(); i++) {
            String h = hl.get(i);
            int c = h.indexOf(':');
            if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase(name)) return h.substring(c + 1).trim();
        }
        return "";
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

    /** Rebuilds the text from the captured message with the current filters (discards manual edits). */
    void regenerate() {
        if (!isHttp()) return;
        text = generate();
        genText = text;
        winValid = false;
    }

    /** The text the current filters produce from the captured message (does not touch {@link #text}). */
    String generate() {
        if (!isHttp()) return text;
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
            } else if (pretty) {
                body = Pretty.format(body, headerValue("content-type"));
            }
            if (maxBodyChars > 0 && body.length() > maxBodyChars) body = body.substring(0, maxBodyChars) + "\n...";
            if (sb.length() > 0) sb.append('\n');
            sb.append(body);
        }
        String out = sb.toString();
        while (out.endsWith("\n")) out = out.substring(0, out.length() - 1);
        return out;
    }

    // ---- window over a long body ----

    private String splitFor;
    private int splitHead, splitBodyAt;

    /** Locates head / body in {@link #text} (cached for the current text). */
    private void split() {
        if (splitFor == text) return;
        int i = text.indexOf("\n\n");
        if (i >= 0) {
            splitHead = i;
            splitBodyAt = i + 2;
        } else if (HEADISH.matcher(text).find()) {
            splitHead = splitBodyAt = text.length();
        } else {
            splitHead = splitBodyAt = 0;
        }
        splitFor = text;
    }

    int bodyLength() {
        split();
        return text.length() - splitBodyAt;
    }

    /** True if only a slice of the body is shown (a long body, or the user asked for a window). */
    boolean windowed() {
        if (!isHttp() || viewOn == 0) return false;
        int n = bodyLength();
        return n > 0 && (viewOn == 1 || n > AUTO_WINDOW_AT);
    }

    void invalidateWindow() {
        winValid = false;
    }

    /** Replaces the whole text (filters, Reset text, Pretty / Raw); the window is recomputed from its offset. */
    void setFullText(String t) {
        text = t;
        winValid = false;
        int n = bodyLength();
        if (viewOffset > n) viewOffset = Math.max(0, n - 1);
    }

    private void ensureWindow() {
        if (winValid) return;
        split();
        int n = text.length() - splitBodyAt, bs = splitBodyAt;
        int off = Math.max(0, Math.min(viewOffset, n));
        int s, e;
        if (viewUnit == 0) {
            s = off;
            e = Math.min(n, s + Math.max(10, Math.min(viewSize, MAX_WINDOW_CHARS)));
        } else {
            int ls = off == 0 ? -1 : text.lastIndexOf('\n', bs + off - 1);
            s = ls < bs ? 0 : ls + 1 - bs;
            int lines = Math.max(1, Math.min(viewSize, MAX_WINDOW_LINES)), p = bs + s, cap = Math.min(text.length(), p + MAX_WINDOW_CHARS);
            for (int c = 0; c < lines && p < cap; c++) {
                int nl = text.indexOf('\n', p);
                if (nl < 0 || nl + 1 > cap) {
                    p = cap;
                    break;
                }
                p = nl + 1;
            }
            e = p - bs;
        }
        winStart = s;
        winLen = e - s;
        winValid = true;
    }

    /** The whole message body (not just the window). */
    String bodyString() {
        split();
        return text.substring(splitBodyAt);
    }

    /** Where an editor position (in {@link #editorText}) lies in the whole body. */
    int bodyOffsetOfEditorPos(int pos) {
        if (!windowed()) {
            split();
            return Math.max(0, pos - splitBodyAt);
        }
        ensureWindow();
        int prefix = splitHead > 0 ? splitHead + 2 : 0;
        return winStart + Math.max(0, Math.min(pos - prefix, winLen));
    }

    /** Where a body offset is in the editor text (only meaningful if it is inside the current window). */
    int editorPosOfBodyOffset(int off) {
        if (!windowed()) {
            split();
            return splitBodyAt + off;
        }
        ensureWindow();
        return (splitHead > 0 ? splitHead + 2 : 0) + (off - winStart);
    }

    /** Body-relative offset where line number {@code line} (1-based) starts, or -1 if the body has fewer lines. */
    int lineStartOffset(int line) {
        split();
        int p = splitBodyAt;
        for (int i = 1; i < line; i++) {
            int nl = text.indexOf('\n', p);
            if (nl < 0) return -1;
            p = nl + 1;
        }
        return p - splitBodyAt;
    }

    /** The offset {@code lines} lines after (or before, if negative) the line that contains {@code from}. */
    int moveByLines(int from, int lines) {
        split();
        int bs = splitBodyAt, n = text.length() - bs;
        int p = bs + Math.max(0, Math.min(from, n));
        if (lines > 0) {
            for (int i = 0; i < lines; i++) {
                int nl = text.indexOf('\n', p);
                if (nl < 0) break;
                p = nl + 1;
            }
        } else {
            p = Math.max(bs, text.lastIndexOf('\n', p - 1) + 1);
            for (int i = 0; i < -lines && p > bs; i++) p = Math.max(bs, text.lastIndexOf('\n', p - 2) + 1);
        }
        return p - bs;
    }

    /** Start and end (body-relative) of the slice that is shown. */
    int[] windowRange() {
        ensureWindow();
        return new int[]{winStart, winStart + winLen};
    }

    /** What the editor shows: the head plus the current slice of the body (no markers, so editing maps back exactly). */
    String editorText() {
        if (!windowed()) return text;
        ensureWindow();
        String slice = text.substring(splitBodyAt + winStart, splitBodyAt + winStart + winLen);
        return splitHead > 0 ? text.substring(0, splitHead) + "\n\n" + slice : slice;
    }

    /** What is rendered into the screenshot: the head plus the slice of the body that is shown (nothing is added to the image). */
    String shown() {
        if (!windowed()) return text;
        ensureWindow();
        String slice = text.substring(splitBodyAt + winStart, splitBodyAt + winStart + winLen);
        if (slice.endsWith("\n")) slice = slice.substring(0, slice.length() - 1);
        return splitHead > 0 ? text.substring(0, splitHead) + "\n\n" + slice : slice;
    }

    /** Writes the (possibly edited) editor text back into the full text; an edit inside the window changes only that slice. */
    void applyEditorText(String v) {
        if (!windowed()) {
            text = v;
            winValid = false;
            return;
        }
        ensureWindow();
        boolean hasHead = splitHead > 0;
        String head = "", slice = v;
        if (hasHead) {
            int i = v.indexOf("\n\n");
            head = i < 0 ? v : v.substring(0, i);
            slice = i < 0 ? "" : v.substring(i + 2);
        }
        String body = text.substring(splitBodyAt);
        String nb = body.substring(0, winStart) + slice + body.substring(Math.min(body.length(), winStart + winLen));
        text = hasHead ? head + "\n\n" + nb : nb;
        winLen = slice.length();
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

    /** Removes the whitespace outside strings (the opposite of {@link #pretty}); other text is kept as typed. */
    static String compact(String s) {
        StringBuilder o = new StringBuilder();
        boolean inStr = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                o.append(c);
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
                o.append(c);
            } else if (!Character.isWhitespace(c)) {
                o.append(c);
            } else if (o.length() > 0 && Character.isLetterOrDigit(o.charAt(o.length() - 1))
                    && i + 1 < s.length() && Character.isLetterOrDigit(s.charAt(i + 1))) {
                o.append(' '); // keep words apart, e.g. a placeholder next to a literal
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
    double labelRatio = 1.4; // label size = body size x ratio (0 = use the fixed size of the label font)
    int maxWidth = 900;   // max screenshot width in 1x pixels (0 = unlimited)
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
        m.put("maxWidth", "" + maxWidth);
        m.put("labelRatio", "" + labelRatio);
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
        maxWidth = num(g.apply("maxWidth"), maxWidth);
        try {
            if (g.apply("labelRatio") != null) labelRatio = Double.parseDouble(g.apply("labelRatio"));
        } catch (Exception ignored) {
        }
        if (g.apply("wrap") != null) wrap = Boolean.parseBoolean(g.apply("wrap"));
        try {
            if (g.apply("scale") != null) scale = Double.parseDouble(g.apply("scale"));
        } catch (Exception ignored) {
        }
        for (int i = 0; i < syn.length; i++) syn[i] = col(g.apply("syn" + i), syn[i]);
    }
}
