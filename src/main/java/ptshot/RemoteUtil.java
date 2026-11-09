package ptshot;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sanitising, encoding and redaction helpers shared by all Remote* backends. */
final class RemoteUtil {
    private RemoteUtil() {}

    static final int MAX_NAME = 120;
    private static final Pattern URL_IN_TEXT = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://[^\\s\"'<>]+");
    private static final Pattern USERINFO = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*://)[^/@]*@");

    // ---- configuration view with secret tracking -------------------------------------------------

    /** Wraps the cfg map of one request; knows which values are secret so they can be redacted. */
    static final class Cfg {
        final RemoteSpec.Type type;
        private final Map<String, String> map;
        RemoteHttp.Dry dry;   // non-null only while rendering a preview
        private final Set<String> secrets = new LinkedHashSet<>();

        Cfg(RemoteSpec.Type type, Map<String, String> map) {
            this.type = type;
            this.map = map == null ? new java.util.HashMap<>() : map;
            for (RemoteSpec.Field f : type.fields) {
                String v = this.map.get(f.key);
                if (v == null || v.isEmpty()) continue;
                boolean sec = f.secret;
                // Non-secret URL fields can still carry credentials in a query string / userinfo.
                if (!sec && !v.contains("://")) continue;
                if (sec) addSecret(v);
                if (sec && f.kind == RemoteSpec.Kind.MULTILINE) {
                    for (String line : v.split("\\r?\\n")) {
                        addSecret(line.trim());
                        int c = line.indexOf(':');
                        if (c >= 0) addSecret(line.substring(c + 1).trim());
                        int eq = line.indexOf('=');
                        if (eq >= 0) addSecret(line.substring(eq + 1).trim());
                    }
                }
                // URLs embedding credentials: also treat their query string and userinfo as secret.
                try {
                    URI u = URI.create(v.trim());
                    if (u.getRawQuery() != null) {
                        addSecret(u.getRawQuery());
                        for (String p : u.getRawQuery().split("&")) {
                            int e = p.indexOf('=');
                            if (e >= 0) addSecret(p.substring(e + 1));
                        }
                    }
                    if (u.getRawUserInfo() != null) addSecret(u.getRawUserInfo());
                } catch (RuntimeException ignored) { }
                // key=value&key=value strings (SAS tokens, query strings): each value is secret on its own.
                if (sec && v.indexOf('=') >= 0) {
                    for (String p : (v.startsWith("?") ? v.substring(1) : v).split("&")) {
                        int e = p.indexOf('=');
                        if (e >= 0) addSecret(p.substring(e + 1));
                    }
                }
            }
        }

        private void addSecret(String s) {
            if (s == null || s.length() < 3) return; // too short to redact meaningfully
            secrets.add(s);
            secrets.add(pct(s));
            secrets.add(URLEncoder.encode(s, StandardCharsets.UTF_8));
            try {
                secrets.add(java.net.URLDecoder.decode(s, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) { }
            // Basic-auth style derivatives are handled by callers that build them (see addDerived).
        }

        /** Register a secret derived at runtime (e.g. a Base64 Authorization value). */
        void addDerived(String s) { addSecret(s); }

        String get(String key) {
            String v = map.get(key);
            if (v == null) {
                RemoteSpec.Field f = type.field(key);
                return f == null ? "" : f.def;
            }
            return v;
        }
        String trim(String key) { return get(key).trim(); }
        boolean bool(String key) { return "true".equalsIgnoreCase(get(key).trim()); }

        String req(String key) throws RemoteClient.RemoteException {
            String v = trim(key);
            if (v.isEmpty()) {
                RemoteSpec.Field f = type.field(key);
                throw new RemoteClient.RemoteException("Missing required setting: " + (f == null ? key : f.label), null);
            }
            return v;
        }

        /** Removes every known secret and any URL query/userinfo from free text. */
        String redact(String text) {
            if (text == null) return "";
            String s = text;
            List<String> sorted = new ArrayList<>(secrets);
            sorted.sort((a, b) -> b.length() - a.length()); // longest first
            for (String sec : sorted) s = s.replace(sec, "***");
            return scrubUrls(s);
        }
    }

    // ---- redaction / URL echo --------------------------------------------------------------------

    /** Strips userinfo, query and fragment, and control characters, so a URL is safe to display. */
    static String safeUrl(String url) {
        if (url == null) return "";
        String s = stripCtl(url.trim());
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        Matcher m = USERINFO.matcher(s);
        if (m.find()) s = m.group(1) + s.substring(m.end());
        return s;
    }

    /** Replaces every URL in free text by its safeUrl form. */
    static String scrubUrls(String text) {
        Matcher m = URL_IN_TEXT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(safeUrl(m.group())));
        m.appendTail(sb);
        return sb.toString();
    }

    static String stripCtl(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return sb.toString();
    }

    /** Host part of a (possibly hostile) URL for messages; "an unknown host" if unparsable. */
    static String hostOf(String url) {
        try {
            String h = URI.create(url.trim()).getHost();
            if (h != null && !h.isEmpty()) return stripCtl(h);
        } catch (RuntimeException ignored) { }
        return "an unknown host";
    }

    // ---- name / path sanitising ------------------------------------------------------------------

    private static final String BAD_CHARS = "<>:\"|?*\\/";

    private static String cleanComponent(String n) {
        StringBuilder sb = new StringBuilder();
        n.codePoints().forEach(cp -> {
            boolean bad = cp < 0x20 || cp == 0x7f || Character.isISOControl(cp)
                    || BAD_CHARS.indexOf(cp) >= 0
                    || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069) // bidi spoofing
                    || cp == 0x2028 || cp == 0x2029 || cp == 0xFEFF || cp == 0xFFFD;
            sb.appendCodePoint(bad ? '_' : cp);
        });
        String s = sb.toString();
        while (s.contains("..")) s = s.replace("..", "_");
        int a = 0;
        while (a < s.length() && (s.charAt(a) == '.' || s.charAt(a) == ' ')) a++;
        int b = s.length();
        while (b > a && (s.charAt(b - 1) == '.' || s.charAt(b - 1) == ' ')) b--;
        s = s.substring(a, b);
        if (s.isEmpty()) return "";
        String base = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        if (base.toUpperCase().matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) s = "_" + s;
        if (s.length() > MAX_NAME) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 20 ? s.substring(dot) : "";
            s = s.substring(0, MAX_NAME - ext.length()) + ext;
            // do not cut a surrogate pair in half
            if (Character.isHighSurrogate(s.charAt(MAX_NAME - ext.length() - 1))) {
                s = s.substring(0, MAX_NAME - ext.length() - 1) + ext;
            }
        }
        return s;
    }

    /** Sanitised plain file name (no directories); never empty. */
    static String sanitizeName(String name) {
        String n = name == null ? "" : name;
        int i = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (i >= 0) n = n.substring(i + 1);
        String s = cleanComponent(n);
        return s.isEmpty() ? "upload.bin" : s;
    }

    /** Splits a user supplied folder path into sanitised segments (empty, '.' and '..' dropped). */
    static List<String> sanitizeSegments(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) return out;
        for (String seg : path.split("[/\\\\]")) {
            String c = cleanComponent(seg);
            if (!c.isEmpty()) out.add(c);
        }
        return out;
    }

    // ---- percent encoding (RFC 3986) --------------------------------------------------------------

    private static String pct(String s) { return encodeSegment(s); }

    /** Encodes everything except unreserved characters ALPHA / DIGIT / - . _ ~ */
    static String encodeSegment(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(b.length + 8);
        for (byte x : b) {
            int c = x & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return sb.toString();
    }

    /** Encodes each segment and joins with '/'. */
    static String encodePath(List<String> segs) {
        StringBuilder sb = new StringBuilder();
        for (String s : segs) {
            if (sb.length() > 0) sb.append('/');
            sb.append(encodeSegment(s));
        }
        return sb.toString();
    }

    // ---- validation ----------------------------------------------------------------------------------

    /** Rejects CR, LF and NUL in anything going into a header or protocol command. */
    static void noCtl(String what, String v) throws RemoteClient.RemoteException {
        if (v == null) return;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                throw new RemoteClient.RemoteException(what + " must not contain line breaks or NUL characters.", null);
            }
        }
    }

    // ---- hashing -------------------------------------------------------------------------------------

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new java.security.SecureRandom().nextBytes(b);
        return hex(b);
    }

    /** JSON string literal; non-ASCII is escaped as \\uXXXX so it is safe inside an HTTP header. */
    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
