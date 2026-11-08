package ptshot;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
        final List<Block> blocks = new ArrayList<>();
    }

    // ------------------------------------------------------------------ save

    /** Cheap fingerprint of a capture's editable state (images by identity), used by undo/redo to skip no-op changes. */
    static String signature(double scale, int maxWidth, List<Block> blocks) {
        return build("", scale, maxWidth, blocks, false);
    }

    static String serialize(String title, double scale, int maxWidth, List<Block> blocks) {
        return build(title, scale, maxWidth, blocks, true);
    }

    private static String build(String title, double scale, int maxWidth, List<Block> blocks, boolean images) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("scale", scale);
        m.put("maxWidth", maxWidth);
        List<Object> bl = new ArrayList<>();
        for (Block b : blocks) bl.add(block(b, images));
        m.put("blocks", bl);
        return Mini.write(m);
    }

    private static Object rgb(Color c) {
        return c == null ? null : (Object) (c.getRGB() & 0xffffff);
    }

    private static Map<String, Object> block(Block b, boolean images) {
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
        if (b.image != null && !images) {
            m.put("imageId", System.identityHashCode(b.image) + ":" + b.image.getWidth() + "x" + b.image.getHeight());
        } else if (b.image != null) {
            try {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                ImageIO.write(b.image, "png", o);
                m.put("image", Base64.getEncoder().encodeToString(o.toByteArray()));
            } catch (Exception ignored) {
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
            an.add(x);
        }
        m.put("annotations", an);
        return m;
    }

    // ------------------------------------------------------------------ load

    @SuppressWarnings("unchecked")
    static Restored deserialize(String json) {
        Map<String, Object> m = (Map<String, Object>) Mini.parse(json);
        Restored r = new Restored();
        r.title = str(m, "title", "capture");
        r.scale = num(m, "scale", 2.0);
        r.maxWidth = (int) num(m, "maxWidth", -1);
        for (Object o : (List<Object>) m.getOrDefault("blocks", new ArrayList<>())) r.blocks.add(block((Map<String, Object>) o));
        return r;
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
    private static Block block(Map<String, Object> m) {
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
        String img = str(m, "image", null);
        if (img != null) {
            try {
                b.image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(img)));
            } catch (Exception ignored) {
            }
        }
        b.incFirst = bool(m, "incFirst", true);
        b.incHeaders = bool(m, "incHeaders", true);
        b.incBody = bool(m, "incBody", true);
        b.pretty = bool(m, "prettyJson", false);
        b.maxBodyChars = (int) num(m, "maxBodyChars", 0);
        for (Object o : (List<Object>) m.getOrDefault("hiddenHeaders", new ArrayList<>())) b.hiddenHeaders.add(((Number) o).intValue());
        for (Object o : (List<Object>) m.getOrDefault("hiddenParams", new ArrayList<>())) b.hiddenParams.add(((Number) o).intValue());
        b.wrapCols = (int) num(m, "wrapCols", 100);
        b.maxLines = (int) num(m, "maxLines", 30);
        b.wrap = bool(m, "wrap", true);
        b.beside = bool(m, "beside", false);
        b.separatorAfter = bool(m, "separatorAfter", false);
        b.sepColor = col(m, "sepColor");
        b.sepWidth = (int) num(m, "sepWidth", 0);
        for (Object o : (List<Object>) m.getOrDefault("annotations", new ArrayList<>())) {
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
            b.annotations.add(a);
        }
        return b;
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

    static Object parse(String s) {
        int[] p = {0};
        Object o = val(s, p);
        return o;
    }

    private static void ws(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
    }

    private static Object val(String s, int[] p) {
        ws(s, p);
        char c = s.charAt(p[0]);
        if (c == '{') {
            p[0]++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws(s, p);
            if (s.charAt(p[0]) == '}') {
                p[0]++;
                return m;
            }
            while (true) {
                ws(s, p);
                String k = (String) val(s, p);
                ws(s, p);
                p[0]++; // ':'
                m.put(k, val(s, p));
                ws(s, p);
                if (s.charAt(p[0]++) == '}') return m;
            }
        }
        if (c == '[') {
            p[0]++;
            List<Object> l = new ArrayList<>();
            ws(s, p);
            if (s.charAt(p[0]) == ']') {
                p[0]++;
                return l;
            }
            while (true) {
                l.add(val(s, p));
                ws(s, p);
                if (s.charAt(p[0]++) == ']') return l;
            }
        }
        if (c == '"') {
            p[0]++;
            StringBuilder sb = new StringBuilder();
            while (s.charAt(p[0]) != '"') {
                char ch = s.charAt(p[0]++);
                if (ch == '\\') {
                    char e = s.charAt(p[0]++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16)); p[0] += 4; break;
                        default: sb.append(e);
                    }
                } else sb.append(ch);
            }
            p[0]++;
            return sb.toString();
        }
        if (s.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p[0])) { p[0] += 4; return null; }
        int st = p[0];
        while (p[0] < s.length() && "+-.eE0123456789".indexOf(s.charAt(p[0])) >= 0) p[0]++;
        return Double.parseDouble(s.substring(st, p[0]));
    }
}
