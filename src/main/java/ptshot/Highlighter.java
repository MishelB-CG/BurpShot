package ptshot;

import java.awt.Color;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Approximates Burp's message-editor syntax colouring (per source character). */
final class Highlighter {
    private Highlighter() {}

    private static final Pattern FIRST = Pattern.compile("^(HTTP/\\S+ .*|[A-Za-z]+ \\S+ HTTP/\\S+.*)$");
    private static final Pattern HNAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    static Color[] colorize(String t, boolean request, Color base, Color[] syn) {
        int n = t.length();
        Color[] c = new Color[n];
        Arrays.fill(c, base);
        int pos = 0, lineNo = 0, bodyStart = -1;
        while (pos <= n) {
            int e = t.indexOf('\n', pos);
            if (e < 0) e = n;
            String line = t.substring(pos, e);
            if (lineNo == 0 && FIRST.matcher(line).matches()) {
                if (request) {
                    int a = line.indexOf(' '), z = line.lastIndexOf(' ');
                    if (a >= 0 && z > a) {
                        int q = line.indexOf('?', a);
                        if (q >= 0 && q < z) form(t, pos + q + 1, pos + z, c, syn);
                    }
                }
            } else if (line.isEmpty()) {
                bodyStart = e + 1;
                break;
            } else {
                int col = line.indexOf(':');
                if (col > 0 && HNAME.matcher(line.substring(0, col)).matches()) {
                    paint(c, pos, pos + col, syn[Settings.K_HDR]);
                    String hn = line.substring(0, col);
                    if (hn.equalsIgnoreCase("Cookie") || hn.equalsIgnoreCase("Set-Cookie")) cookies(t, pos + col + 1, e, c, syn);
                } else {
                    bodyStart = pos;
                    break;
                }
            }
            lineNo++;
            if (e >= n) break;
            pos = e + 1;
        }
        if (bodyStart >= 0 && bodyStart < n) body(t, bodyStart, c, syn);
        return c;
    }

    private static void paint(Color[] c, int s, int e, Color col) {
        for (int i = Math.max(0, s); i < Math.min(c.length, e); i++) c[i] = col;
    }

    private static void body(String t, int s, Color[] c, Color[] syn) {
        int i = s;
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++;
        if (i >= t.length()) return;
        char f = t.charAt(i);
        if (f == '-' && t.startsWith("--", i) && multipart(t, i, c, syn)) return;
        if (f == '{' || f == '[') json(t, s, c, syn);
        else if (f == '<') markup(t, s, c, syn);
        else {
            int nl = t.indexOf('\n', i);
            String first = t.substring(i, nl < 0 ? t.length() : nl);
            if (first.matches("^[^\\s=&]+=.*")) form(t, s, t.length(), c, syn);
        }
    }

    /**
     * multipart/form-data as Burp shows it: boundary lines plain, part header names in the header colour, the field / file name inside
     * Content-Disposition in the parameter-name colour, and the part's content in the parameter-value colour.
     * @return false (nothing painted) if this does not look like a multipart body
     */
    private static boolean multipart(String t, int start, Color[] c, Color[] syn) {
        int n = t.length();
        int e0 = t.indexOf('\n', start);
        String boundary = t.substring(start, e0 < 0 ? n : e0).trim();
        if (boundary.length() < 3 || t.indexOf("Content-Disposition", start) < 0) return false;
        int pos = start;
        boolean inHeaders = false, inBody = false;
        while (pos < n) {
            int e = t.indexOf('\n', pos);
            if (e < 0) e = n;
            String line = t.substring(pos, e);
            String trimmed = line.trim();
            if (trimmed.startsWith(boundary)) { // "--boundary" or "--boundary--"
                inHeaders = !trimmed.equals(boundary + "--");
                inBody = false;
            } else if (inHeaders) {
                if (trimmed.isEmpty()) {
                    inHeaders = false;
                    inBody = true;
                } else {
                    int col = line.indexOf(':');
                    if (col > 0 && HNAME.matcher(line.substring(0, col)).matches()) {
                        paint(c, pos, pos + col, syn[Settings.K_HDR]);
                        if (line.regionMatches(true, 0, "Content-Disposition", 0, 19)) {
                            for (String key : new String[]{"name=\"", "filename=\""}) {
                                int k = line.indexOf(key, col);
                                if (key.equals("name=\"") && k > 0 && line.charAt(k - 1) != ' ' && line.charAt(k - 1) != ';') k = -1;
                                if (k < 0) continue;
                                int v = k + key.length(), q = line.indexOf('"', v);
                                if (q > v) paint(c, pos + v, pos + q, syn[Settings.K_PNAME]);
                            }
                        }
                    }
                }
            } else if (inBody && !trimmed.isEmpty()) {
                paint(c, pos, e, syn[Settings.K_PVAL]);
            }
            if (e >= n) break;
            pos = e + 1;
        }
        return true;
    }

    private static void json(String t, int s, Color[] c, Color[] syn) {
        int n = t.length(), i = s;
        while (i < n) {
            char ch = t.charAt(i);
            if (ch == '"') {
                int j = i + 1;
                while (j < n && t.charAt(j) != '"') {
                    if (t.charAt(j) == '\\') j++;
                    j++;
                }
                j = Math.min(n, j + 1);
                int k = j;
                while (k < n && (t.charAt(k) == ' ' || t.charAt(k) == '\t')) k++;
                boolean key = k < n && t.charAt(k) == ':';
                paint(c, i, j, key ? syn[Settings.K_KEY] : syn[Settings.K_STR]);
                i = j;
            } else if (ch == '-' || Character.isDigit(ch)) {
                int j = i;
                while (j < n && "-+.eE0123456789".indexOf(t.charAt(j)) >= 0) j++;
                paint(c, i, j, syn[Settings.K_NUM]);
                i = j;
            } else if (Character.isLetter(ch)) {
                int j = i;
                while (j < n && Character.isLetter(t.charAt(j))) j++;
                String w = t.substring(i, j);
                if (w.equals("true") || w.equals("false") || w.equals("null")) paint(c, i, j, syn[Settings.K_KEY]);
                i = j;
            } else {
                i++;
            }
        }
    }

    private static void markup(String t, int s, Color[] c, Color[] syn) {
        int n = t.length(), i = s;
        while (i < n) {
            if (t.charAt(i) == '<') {
                int j = t.indexOf('>', i);
                j = j < 0 ? n : j + 1;
                paint(c, i, j, syn[Settings.K_TAG]);
                i = j;
            } else {
                i++;
            }
        }
    }

    /** Cookie / Set-Cookie values: "name=value; name=value" - names like parameter names, values like parameter values. */
    private static void cookies(String t, int s, int e, Color[] c, Color[] syn) {
        int i = s;
        while (i < e) {
            int end = t.indexOf(';', i);
            if (end < 0 || end > e) end = e;
            int st = i;
            while (st < end && t.charAt(st) == ' ') st++;
            int eq = t.indexOf('=', st);
            if (eq < 0 || eq >= end) {
                paint(c, st, end, syn[Settings.K_PNAME]);
            } else {
                paint(c, st, eq + 1, syn[Settings.K_PNAME]);
                paint(c, eq + 1, end, syn[Settings.K_PVAL]);
            }
            i = end + 1;
        }
    }

    /** name=value&name=value, newlines terminate a pair as well. */
    private static void form(String t, int s, int e, Color[] c, Color[] syn) {
        int i = s;
        while (i < e) {
            int end = i;
            while (end < e && t.charAt(end) != '&' && t.charAt(end) != '\n') end++;
            int eq = t.indexOf('=', i);
            if (eq < 0 || eq >= end) {
                paint(c, i, end, syn[Settings.K_PNAME]);
            } else {
                paint(c, i, eq + 1, syn[Settings.K_PNAME]);
                paint(c, eq + 1, end, syn[Settings.K_PVAL]);
            }
            i = end + 1;
        }
    }
}
