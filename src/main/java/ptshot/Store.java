package ptshot;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Serialises captures (title, scale, blocks, annotations, images) to/from a JSON string. */
final class Store {
    private Store() {}

    static final class Restored {
        String title;
        double scale;
        int maxWidth = -1;
        Border frame;   // border around the whole capture (null = none)
        final List<Block> blocks = new ArrayList<>();
    }

    // ------------------------------------------------------------------ save

    /** Cheap fingerprint of a capture's editable state (images by identity), used by undo/redo to skip no-op changes. */
    static String signature(double scale, int maxWidth, List<Block> blocks) {
        return build("", scale, maxWidth, null, blocks, 0, null);
    }

    static String signature(double scale, int maxWidth, Border frame, List<Block> blocks) {
        return build("", scale, maxWidth, frame, blocks, 0, null);
    }

    /** Where image pixels go when they are kept next to the JSON instead of inside it (capture archives). */
    interface Images {
        /** Stores the image and returns the reference written into the JSON. */
        String put(BufferedImage img) throws IOException;

        /** Returns the image for a reference read from the JSON (null if unknown). Must validate / limit what it decodes. */
        BufferedImage get(String ref) throws IOException;
    }

    /** Legacy format: images inline as base64 (still read for old project data and used by the self-tests). */
    static String serialize(String title, double scale, int maxWidth, List<Block> blocks) {
        return build(title, scale, maxWidth, null, blocks, 1, null);
    }

    /** Current format: images are handed to {@code io} and only referenced from the JSON. */
    static String serializeRef(String title, double scale, int maxWidth, List<Block> blocks, Images io) {
        return build(title, scale, maxWidth, null, blocks, 2, io);
    }

    static String serializeRef(String title, double scale, int maxWidth, Border frame, List<Block> blocks, Images io) {
        return build(title, scale, maxWidth, frame, blocks, 2, io);
    }

    /** mode 0 = fingerprint only (undo), 1 = images inline (legacy), 2 = images by reference. */
    private static String build(String title, double scale, int maxWidth, Border frame, List<Block> blocks, int mode, Images io) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("scale", scale);
        m.put("maxWidth", maxWidth);
        if (frame != null && !frame.isNone()) m.put("frame", borderMap(frame));
        List<Object> bl = new ArrayList<>();
        for (Block b : blocks) bl.add(block(b, mode, io));
        m.put("blocks", bl);
        return Mini.write(m);
    }

    private static Map<String, Object> borderMap(Border b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("color", b.color.getRGB() & 0xffffff);
        m.put("width", b.width);
        m.put("radius", b.radius);
        m.put("shadow", b.shadow);
        return m;
    }

    /** Reads a border; out-of-range numbers are clamped, anything that is not a map means "no border". */
    @SuppressWarnings("unchecked")
    private static Border borderOf(Object o) {
        if (!(o instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) o;
        Border b = new Border();
        Color c = col(m, "color");
        if (c != null) b.color = c;
        b.width = (int) fin(num(m, "width", 2), 0, Border.MAX_WIDTH, 2);
        b.radius = (int) fin(num(m, "radius", 0), 0, Border.MAX_RADIUS, 0);
        b.shadow = (int) fin(num(m, "shadow", 0), 0, Border.MAX_SHADOW, 0);
        return b.isNone() ? null : b;
    }

    private static Object rgb(Color c) {
        return c == null ? null : (Object) (c.getRGB() & 0xffffff);
    }

    private static Map<String, Object> block(Block b, int mode, Images io) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", b.kind.name());
        m.put("showLabel", b.showLabel);
        m.put("label", b.label);
        m.put("labelFont", b.labelFont == null ? null : b.labelFont.ser());
        m.put("bodyFont", b.bodyFont == null ? null : b.bodyFont.ser());
        m.put("background", rgb(b.background));
        m.put("highlight", b.highlight);
        m.put("align", b.align);
        m.put("original", b.original);
        m.put("text", b.text);
        m.put("imageMaxWidth", b.imageMaxWidth);
        m.put("offX", b.offX);
        m.put("offY", b.offY);
        m.put("boxW", b.boxW);
        m.put("imageW", b.imageW);
        m.put("overlay", b.overlay);
        m.put("raw", b.raw);
        m.put("preset", b.preset);
        m.put("chrome", b.chrome);
        m.put("title", b.title);
        m.put("theme", b.theme);
        m.put("cmdStyle", b.cmdStyle);
        m.put("indentColors", b.indentColors);
        if (b.accents != null) {
            List<Object> al = new ArrayList<>();
            for (Color ac : b.accents) al.add(ac.getRGB() & 0xffffff);
            m.put("accents", al);
        }
        if (b.palette != null) {
            List<Object> pl = new ArrayList<>();
            for (Color pc : b.palette) pl.add(pc.getRGB() & 0xffffff);
            m.put("palette", pl);
        }
        List<Object> rl = new ArrayList<>();
        for (List<String> r : b.rows) rl.add(new ArrayList<Object>(r));
        m.put("rows", rl);
        if (b.image != null && mode == 0) {
            m.put("imageId", System.identityHashCode(b.image) + ":" + b.image.getWidth() + "x" + b.image.getHeight());
        } else if (b.image != null && mode == 1) {
            try {
                m.put("image", Base64.getEncoder().encodeToString(Png.encode(b.image)));
            } catch (Exception ignored) {
            }
        } else if (b.image != null && mode == 2) {
            try {
                m.put("imageRef", io.put(b.image));
            } catch (IOException e) {
                throw new IllegalStateException("could not store an image", e);
            }
        }
        m.put("incFirst", b.incFirst);
        m.put("incHeaders", b.incHeaders);
        m.put("incBody", b.incBody);
        m.put("prettyJson", b.pretty);
        m.put("maxBodyChars", b.maxBodyChars);
        m.put("hiddenHeaders", new ArrayList<Object>(b.hiddenHeaders));
        m.put("hiddenParams", new ArrayList<Object>(b.hiddenParams));
        m.put("wrapCols", b.wrapCols);
        m.put("maxLines", b.maxLines);
        m.put("wrap", b.wrap);
        m.put("beside", b.beside);
        m.put("separatorAfter", b.separatorAfter);
        m.put("sepColor", rgb(b.sepColor));
        m.put("sepWidth", b.sepWidth);
        if (b.border != null && !b.border.isNone()) m.put("border", borderMap(b.border));
        List<Object> an = new ArrayList<>();
        for (Annotation a : b.annotations) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("type", a.type.name());
            x.put("anchor", a.anchor);
            x.put("occurrence", a.occurrence);
            x.put("color", rgb(a.color));
            x.put("width", a.width);
            x.put("pad", a.pad);
            x.put("fullWidth", a.fullWidth);
            x.put("blockArrow", a.blockArrow);
            x.put("angle", a.angle);
            x.put("length", a.length);
            x.put("headSize", a.headSize);
            x.put("edge", a.edge.name());
            x.put("dx", a.dx);
            x.put("dy", a.dy);
            x.put("offL", a.offL);
            x.put("offT", a.offT);
            x.put("offR", a.offR);
            x.put("offB", a.offB);
            x.put("strength", a.strength);
            x.put("free", a.free);
            x.put("fx", a.fx);
            x.put("fy", a.fy);
            x.put("fw", a.fw);
            x.put("fh", a.fh);
            an.add(x);
        }
        m.put("annotations", an);
        return m;
    }

    // ------------------------------------------------------------------ load

    static Restored deserialize(String json) {
        return deserialize(json, null);
    }

    static final int MAX_BLOCKS = 500, MAX_ANNOTATIONS = 2000, MAX_ROWS = 5000, MAX_COLS = 200, MAX_TEXT = 8_000_000;

    /**
     * Reads a capture. The data is untrusted: it is parsed by a depth- and size-limited JSON parser (no Java deserialization, no
     * reflection, nothing is executed or fetched), counts and lengths are capped, enum / number / colour values are validated and
     * clamped, and images must be small, valid PNGs. Anything out of range makes the whole capture fail with an
     * IllegalArgumentException instead of being half-loaded.
     */
    @SuppressWarnings("unchecked")
    static Restored deserialize(String json, Images io) {
        Object root = Mini.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("not a capture");
        Map<String, Object> m = (Map<String, Object>) root;
        Restored r = new Restored();
        r.title = cut(str(m, "title", "capture"), 200);
        r.scale = fin(num(m, "scale", 2.0), 0.5, 8, 2.0);
        r.maxWidth = (int) fin(num(m, "maxWidth", -1), -1, 20000, -1);
        r.frame = borderOf(m.get("frame"));
        Object bo = m.get("blocks");
        List<Object> bl = bo instanceof List ? (List<Object>) bo : new ArrayList<>();
        if (bl.size() > MAX_BLOCKS) throw new IllegalArgumentException("too many blocks");
        for (Object o : bl) {
            if (!(o instanceof Map)) throw new IllegalArgumentException("bad block");
            r.blocks.add(block((Map<String, Object>) o, io));
        }
        return r;
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Finite number within [lo, hi], else the default (NaN / Infinity from hostile data become the default). */
    private static double fin(double v, double lo, double hi, double d) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return d;
        return Math.max(lo, Math.min(hi, v));
    }

    private static void sanitize(Block b) {
        b.label = cut(b.label, 500);
        b.title = cut(b.title == null ? "" : b.title, 300);
        if (b.original.length() > MAX_TEXT || b.text.length() > MAX_TEXT || b.raw.length() > MAX_TEXT) throw new IllegalArgumentException("text too large");
        b.align = (int) fin(b.align, 0, 2, 0);
        b.imageMaxWidth = (int) fin(b.imageMaxWidth, 40, 20000, 500);
        b.offX = fin(b.offX, -100000, 100000, 0);
        b.offY = fin(b.offY, -100000, 100000, 0);
        b.boxW = fin(b.boxW, 0, 20000, 0);
        b.imageW = fin(b.imageW, 0, 20000, 0);
        b.wrapCols = (int) fin(b.wrapCols, 0, 2000, 100);
        b.maxLines = (int) fin(b.maxLines, 0, 5000, 30);
        b.maxBodyChars = (int) fin(b.maxBodyChars, 0, 50_000_000, 0);
        b.sepWidth = (int) fin(b.sepWidth, 0, 100, 0);
        if (!"DARK".equals(b.theme) && !"LIGHT".equals(b.theme)) b.theme = "DARK";
        boolean okChrome = false;
        for (String c : Term.CHROMES) okChrome |= c.equals(b.chrome);
        if (!okChrome) b.chrome = "NONE";
        b.preset = cut(b.preset, 64);
        for (Annotation a : b.annotations) {
            a.anchor = cut(a.anchor == null ? "" : a.anchor, 2000);
            a.occurrence = (int) fin(a.occurrence, 0, 100000, 0);
            a.width = fin(a.width, 0.5, 100, 2);
            a.pad = (int) fin(a.pad, -50, 200, 3);
            a.angle = fin(a.angle, -720, 720, 90);
            a.length = (int) fin(a.length, 1, 5000, 90);
            a.headSize = (int) fin(a.headSize, 1, 500, 16);
            a.dx = (int) fin(a.dx, -5000, 5000, 0);
            a.dy = (int) fin(a.dy, -5000, 5000, 0);
            a.offL = fin(a.offL, -20000, 20000, 0);
            a.offT = fin(a.offT, -20000, 20000, 0);
            a.offR = fin(a.offR, -20000, 20000, 0);
            a.offB = fin(a.offB, -20000, 20000, 0);
            a.strength = (int) fin(a.strength, 1, 200, 8);
            a.fx = fin(a.fx, -100000, 100000, 0);
            a.fy = fin(a.fy, -100000, 100000, 0);
            a.fw = fin(a.fw, 0, 100000, 0);
            a.fh = fin(a.fh, 0, 100000, 0);
        }
    }

    private static String str(Map<String, Object> m, String k, String d) {
        Object o = m.get(k);
        return o instanceof String ? (String) o : d;
    }

    private static double num(Map<String, Object> m, String k, double d) {
        Object o = m.get(k);
        return o instanceof Number ? ((Number) o).doubleValue() : d;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean d) {
        Object o = m.get(k);
        return o instanceof Boolean ? (Boolean) o : d;
    }

    private static Color col(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o instanceof Number ? new Color(((Number) o).intValue()) : null;
    }

    @SuppressWarnings("unchecked")
    private static Block block(Map<String, Object> m, Images io) {
        Block b = new Block();
        b.kind = Block.Kind.valueOf(str(m, "kind", "CUSTOM"));
        b.showLabel = bool(m, "showLabel", true);
        b.label = str(m, "label", "");
        String lf = str(m, "labelFont", null), bf = str(m, "bodyFont", null);
        b.labelFont = lf == null ? null : FontSpec.parse(lf, null);
        b.bodyFont = bf == null ? null : FontSpec.parse(bf, null);
        b.background = col(m, "background");
        b.highlight = bool(m, "highlight", true);
        b.align = (int) num(m, "align", 0);
        b.original = str(m, "original", "");
        b.text = str(m, "text", "");
        b.imageMaxWidth = (int) num(m, "imageMaxWidth", 500);
        b.offX = num(m, "offX", 0);
        b.offY = num(m, "offY", 0);
        b.boxW = num(m, "boxW", 0);
        b.imageW = num(m, "imageW", 0);
        b.overlay = bool(m, "overlay", false);
        b.raw = str(m, "raw", "");
        b.preset = str(m, "preset", "Windows cmd");
        b.chrome = str(m, "chrome", "NONE");
        b.title = str(m, "title", "");
        b.theme = str(m, "theme", "DARK");
        b.cmdStyle = bool(m, "cmdStyle", true);
        b.indentColors = bool(m, "indentColors", true);
        Object ao = m.get("accents");
        if (ao instanceof List && ((List<Object>) ao).size() == 4) {
            b.accents = new Color[4];
            for (int i = 0; i < 4; i++) b.accents[i] = new Color(((Number) ((List<Object>) ao).get(i)).intValue());
        }
        Object po = m.get("palette");
        if (po instanceof List && ((List<Object>) po).size() == 16) {
            b.palette = new Color[16];
            for (int i = 0; i < 16; i++) b.palette[i] = new Color(((Number) ((List<Object>) po).get(i)).intValue());
        }
        List<Object> rowList = (List<Object>) m.getOrDefault("rows", new ArrayList<>());
        if (rowList.size() > MAX_ROWS) throw new IllegalArgumentException("too many table rows");
        for (Object ro : rowList) {
            List<Object> cells = (List<Object>) ro;
            if (cells.size() > MAX_COLS) throw new IllegalArgumentException("too many table columns");
            List<String> r = new ArrayList<>();
            for (Object co : cells) r.add(cut(String.valueOf(co), 5000));
            b.rows.add(r);
        }
        String img = str(m, "image", null), ref = str(m, "imageRef", null);
        if (img != null && img.length() <= Png.MAX_BYTES * 4L / 3 + 16) {
            try {
                b.image = Png.decode(Base64.getDecoder().decode(img));
            } catch (Exception ignored) {
                // an unreadable image is dropped; the rest of the capture still loads
            }
        } else if (ref != null && io != null) {
            try {
                b.image = io.get(ref);
            } catch (Exception ignored) {
            }
        }
        b.incFirst = bool(m, "incFirst", true);
        b.incHeaders = bool(m, "incHeaders", true);
        b.incBody = bool(m, "incBody", true);
        b.pretty = bool(m, "prettyJson", false);
        b.maxBodyChars = (int) num(m, "maxBodyChars", 0);
        for (Object o : capped((List<Object>) m.getOrDefault("hiddenHeaders", new ArrayList<>()))) b.hiddenHeaders.add(((Number) o).intValue());
        for (Object o : capped((List<Object>) m.getOrDefault("hiddenParams", new ArrayList<>()))) b.hiddenParams.add(((Number) o).intValue());
        b.wrapCols = (int) num(m, "wrapCols", 100);
        b.maxLines = (int) num(m, "maxLines", 30);
        b.wrap = bool(m, "wrap", true);
        b.beside = bool(m, "beside", false);
        b.separatorAfter = bool(m, "separatorAfter", false);
        b.sepColor = col(m, "sepColor");
        b.sepWidth = (int) num(m, "sepWidth", 0);
        b.border = borderOf(m.get("border"));
        for (Object o : annotationList(m)) {
            Map<String, Object> x = (Map<String, Object>) o;
            Annotation a = Annotation.create(Annotation.Type.valueOf(str(x, "type", "BOX")), str(x, "anchor", ""), (int) num(x, "occurrence", 0));
            Color c = col(x, "color");
            if (c != null) a.color = c;
            a.width = num(x, "width", a.width);
            a.pad = (int) num(x, "pad", a.pad);
            a.fullWidth = bool(x, "fullWidth", false);
            a.blockArrow = bool(x, "blockArrow", true);
            a.angle = num(x, "angle", 90);
            a.length = (int) num(x, "length", 90);
            a.headSize = (int) num(x, "headSize", 16);
            a.edge = Annotation.Edge.valueOf(str(x, "edge", "BOTTOM"));
            a.dx = (int) num(x, "dx", 0);
            a.dy = (int) num(x, "dy", 0);
            a.offL = num(x, "offL", 0);
            a.offT = num(x, "offT", 0);
            a.offR = num(x, "offR", 0);
            a.offB = num(x, "offB", 0);
            a.strength = (int) num(x, "strength", 8);
            a.free = bool(x, "free", false);
            a.fx = num(x, "fx", 0);
            a.fy = num(x, "fy", 0);
            a.fw = num(x, "fw", 0);
            a.fh = num(x, "fh", 0);
            b.annotations.add(a);
        }
        sanitize(b);
        return b;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> annotationList(Map<String, Object> m) {
        List<Object> l = (List<Object>) m.getOrDefault("annotations", new ArrayList<>());
        if (l.size() > MAX_ANNOTATIONS) throw new IllegalArgumentException("too many annotations");
        for (Object o : l) if (!(o instanceof Map)) throw new IllegalArgumentException("bad annotation");
        return l;
    }

    private static List<Object> capped(List<Object> l) {
        if (l.size() > 20000) throw new IllegalArgumentException("list too long");
        return l;
    }
}

/** Minimal JSON writer / parser (Map, List, String, Number, Boolean, null). */
final class Mini {
    private Mini() {}

    static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        w(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void w(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String) {
            sb.append('"');
            for (char c : ((String) o).toCharArray()) {
                if (c == '"' || c == '\\') sb.append('\\').append(c);
                else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                else sb.append(c);
            }
            sb.append('"');
        } else if (o instanceof Number || o instanceof Boolean) sb.append(o);
        else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                w(sb, e.getKey());
                sb.append(':');
                w(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object x : (List<Object>) o) {
                if (!first) sb.append(',');
                first = false;
                w(sb, x);
            }
            sb.append(']');
        } else w(sb, o.toString());
    }

    static final int MAX_DEPTH = 40;
    static final long MAX_NODES = 3_000_000L;

    /**
     * Strict, limited JSON reader for untrusted data: nesting is capped (no stack overflow), the number of values is capped, every
     * malformed input ends in an IllegalArgumentException (never an Error), and trailing garbage is rejected.
     */
    static Object parse(String s) {
        P p = new P(s);
        Object o = p.val(0);
        p.ws();
        if (p.i != s.length()) throw p.err();
        return o;
    }

    private static final class P {
        final String s;
        int i;
        long nodes;

        P(String s) {
            this.s = s;
        }

        IllegalArgumentException err() {
            return new IllegalArgumentException("malformed capture data");
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        char peek() {
            if (i >= s.length()) throw err();
            return s.charAt(i);
        }

        Object val(int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) throw new IllegalArgumentException("capture data too complex");
            ws();
            char c = peek();
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek() == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    if (peek() != '"') throw err();
                    String k = str();
                    ws();
                    if (peek() != ':') throw err();
                    i++;
                    m.put(k, val(depth + 1));
                    ws();
                    char d = peek();
                    i++;
                    if (d == '}') return m;
                    if (d != ',') throw err();
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek() == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(val(depth + 1));
                    ws();
                    char d = peek();
                    i++;
                    if (d == ']') return l;
                    if (d != ',') throw err();
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            if (i == st) throw err();
            try {
                return Double.parseDouble(s.substring(st, i));
            } catch (NumberFormatException e) {
                throw err();
            }
        }

        String str() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                char ch = peek();
                i++;
                if (ch == '"') return sb.toString();
                if (ch != '\\') {
                    sb.append(ch);
                    continue;
                }
                char e = peek();
                i++;
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err();
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw err();
                        }
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            }
        }
    }
}
