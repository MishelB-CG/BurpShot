package ptshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Burp-style "Pretty" view of a message body: JSON, XML and HTML are re-indented, anything else is left alone. */
final class Pretty {
    private Pretty() {}

    private static final Set<String> VOID = new HashSet<>(Arrays.asList(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"));

    static String format(String body, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        String t = body.trim();
        try {
            if (ct.contains("javascript") || ct.contains("ecmascript") || ct.contains("x-js") || ct.contains("jscript")) return Beautify.js(body);
            if (ct.contains("text/css")) return Beautify.css(body);
            boolean json = ct.contains("json") || (!ct.contains("xml") && !ct.contains("html") && (t.startsWith("{") || t.startsWith("[")));
            if (json && (t.startsWith("{") || t.startsWith("["))) return Json.pretty(t);
            boolean html = ct.contains("html") || (ct.isEmpty() && t.regionMatches(true, 0, "<!doctype html", 0, 14)) || (ct.isEmpty() && t.regionMatches(true, 0, "<html", 0, 5));
            if ((ct.contains("xml") || ct.contains("html") || ct.isEmpty() || html) && t.startsWith("<")) return markup(t, html);
        } catch (RuntimeException ignored) {
            // fall through to the untouched body
        }
        return body;
    }

    /** Back to a compact form (used when switching Pretty -> Raw on text that was edited by hand). */
    static String compact(String body, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        String t = body.trim();
        try {
            if (t.startsWith("{") || t.startsWith("[")) {
                if (ct.contains("json") || (!ct.contains("xml") && !ct.contains("html"))) return Json.compact(t);
            }
            if (t.startsWith("<")) return t.replaceAll(">[ \\t]*\\n\\s*", ">").replaceAll("\\n\\s*<", "<");
        } catch (RuntimeException ignored) {
            // fall through to the untouched body
        }
        return body;
    }

    private static final int OPEN = 0, CLOSE = 1, SELF = 2, TEXT = 3, OTHER = 4, RAW = 5;

    private static final class Tok {
        final int type;
        final String s;
        final String name;

        Tok(int type, String s, String name) {
            this.type = type;
            this.s = s;
            this.name = name;
        }
    }

    static String markup(String s, boolean html) {
        List<Tok> toks = new ArrayList<>();
        int n = s.length(), i = 0;
        while (i < n) {
            if (s.charAt(i) != '<') {
                int j = s.indexOf('<', i);
                if (j < 0) j = n;
                String tx = s.substring(i, j).trim();
                if (!tx.isEmpty()) toks.add(new Tok(TEXT, tx, null));
                i = j;
                continue;
            }
            int end;
            if (s.startsWith("<!--", i)) {
                end = s.indexOf("-->", i);
                end = end < 0 ? n : end + 3;
                toks.add(new Tok(OTHER, s.substring(i, end), null));
            } else if (s.startsWith("<![CDATA[", i)) {
                end = s.indexOf("]]>", i);
                end = end < 0 ? n : end + 3;
                toks.add(new Tok(OTHER, s.substring(i, end), null));
            } else {
                end = tagEnd(s, i);
                String tag = s.substring(i, end);
                if (tag.startsWith("<?") || tag.startsWith("<!")) {
                    toks.add(new Tok(OTHER, tag, null));
                } else if (tag.startsWith("</")) {
                    toks.add(new Tok(CLOSE, tag, name(tag, 2)));
                } else {
                    String nm = name(tag, 1);
                    boolean self = tag.endsWith("/>") || (html && VOID.contains(nm.toLowerCase()));
                    toks.add(new Tok(self ? SELF : OPEN, tag, nm));
                    if (!self && html && (nm.equalsIgnoreCase("script") || nm.equalsIgnoreCase("style"))) {
                        int close = indexOfIgnoreCase(s, "</" + nm, end);
                        if (close > end) {
                            String raw = s.substring(end, close).trim();
                            if (nm.equalsIgnoreCase("style")) raw = Beautify.css(raw);
                            else if (jsType(tag)) raw = Beautify.js(raw);
                            if (!raw.isEmpty()) toks.add(new Tok(RAW, raw, null));
                            end = close;
                        }
                    }
                }
            }
            i = end;
        }

        StringBuilder o = new StringBuilder();
        int ind = 0;
        for (int k = 0; k < toks.size(); k++) {
            Tok t = toks.get(k);
            switch (t.type) {
                case OPEN: {
                    line(o, ind, t.s);
                    // <a>text</a> stays on one line
                    if (k + 2 < toks.size() && toks.get(k + 1).type == TEXT && !toks.get(k + 1).s.contains("\n")
                            && toks.get(k + 2).type == CLOSE && t.name.equalsIgnoreCase(toks.get(k + 2).name)) {
                        o.append(toks.get(k + 1).s).append(toks.get(k + 2).s);
                        k += 2;
                    } else if (k + 1 < toks.size() && toks.get(k + 1).type == CLOSE && t.name.equalsIgnoreCase(toks.get(k + 1).name)) {
                        o.append(toks.get(k + 1).s);
                        k += 1;
                    } else {
                        ind++;
                    }
                    break;
                }
                case CLOSE:
                    ind = Math.max(0, ind - 1);
                    line(o, ind, t.s);
                    break;
                case TEXT:
                    for (String ln : t.s.split("\n")) {
                        String x = ln.trim();
                        if (!x.isEmpty()) line(o, ind, x);
                    }
                    break;
                case RAW: // formatted script / style: keep its own indentation, shifted to the current level
                    for (String ln : t.s.split("\n")) if (!ln.trim().isEmpty()) line(o, ind, ln.replaceAll("\\s+$", ""));
                    break;
                default:
                    line(o, ind, t.s);
            }
        }
        return o.toString();
    }

    /** A <script> whose type is JavaScript (no type, text/javascript, module...) - not JSON, templates or other data blocks. */
    private static boolean jsType(String tag) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\btype\\s*=\\s*[\"']?([^\"'\\s>]*)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(tag);
        if (!m.find()) return true;
        String t = m.group(1).toLowerCase();
        return t.isEmpty() || t.equals("module") || t.contains("javascript") || t.contains("ecmascript");
    }

    private static void line(StringBuilder o, int ind, String s) {
        if (o.length() > 0) o.append('\n');
        for (int i = 0; i < ind; i++) o.append("  ");
        o.append(s);
    }

    /** Index just past the '>' that closes the tag starting at {@code from}, skipping quoted attribute values. */
    private static int tagEnd(String s, int from) {
        char q = 0;
        for (int i = from + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) {
                if (c == q) q = 0;
            } else if (c == '"' || c == '\'') {
                q = c;
            } else if (c == '>') {
                return i + 1;
            }
        }
        return s.length();
    }

    private static String name(String tag, int from) {
        int i = from;
        while (i < tag.length() && !Character.isWhitespace(tag.charAt(i)) && tag.charAt(i) != '>' && tag.charAt(i) != '/') i++;
        return tag.substring(from, i);
    }

    private static int indexOfIgnoreCase(String s, String what, int from) {
        for (int i = from; i + what.length() <= s.length(); i++) if (s.regionMatches(true, i, what, 0, what.length())) return i;
        return -1;
    }
}
