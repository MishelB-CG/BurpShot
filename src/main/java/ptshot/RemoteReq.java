package ptshot;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ptshot.RemoteClient.RemoteException;
import ptshot.RemoteClient.Result;
import ptshot.RemoteHttp.Resp;
import ptshot.RemoteUtil.Cfg;

/**
 * The editable HTTP request model used by the generic "http" destination. One code path serves upload(),
 * previewRequest() (render to text) and sendRequestText() (parse text back), so what the user sees is what is sent.
 * Secrets travel as {{secret:KEY}} tokens until the moment of sending.
 */
final class RemoteReq {
    private RemoteReq() {}

    static final String APPEND = "append to URL path", ONLY = "only where {name} is", NEVER = "never in URL";
    static final String PARAMS_QUERY = "query string", PARAMS_FORM = "multipart form fields";
    static final int MAX_URL = 8192, MAX_PARAMS = 30, MAX_HEADERS_TEXT = 60, MAX_HEADER_BYTES = 32 * 1024;
    static final Pattern TOKEN = Pattern.compile("\\{\\{secret:([A-Za-z0-9_]+)\\}\\}");
    private static final Pattern REQ_LINE = Pattern.compile("^(PUT|POST|PATCH) (\\S+) HTTP/(?:1\\.0|1\\.1)$");
    private static final Set<String> MANAGED = Set.of("host", "content-length", "connection", "transfer-encoding",
            "expect", "upgrade", "keep-alive", "te", "trailer");

    /** A request whose secrets are still tokens and whose body is text-before + file + text-after. */
    static final class Draft {
        String method, url;
        final List<String[]> headers = new ArrayList<>();
        String before = "", after = "";
        boolean hasFile = true, noBody = false;
        boolean multipart;
        List<String> ignored = new ArrayList<>();
        boolean parsed;

        Draft withoutBody(String m) {
            Draft d = new Draft();
            d.method = m; d.url = url; d.noBody = true; d.hasFile = false;
            for (String[] h : headers) if (!h[0].equalsIgnoreCase("Content-Type")) d.headers.add(h);
            return d;
        }
    }

    // ---- building from configuration ------------------------------------------------------------------------------

    static String baseUrl(Cfg c, String name) throws RemoteException {
        String raw = c.req("url");
        RemoteUtil.noCtl("URL", raw);
        int h = raw.indexOf('#');
        if (h >= 0) raw = raw.substring(0, h);
        String mode = c.trim("nameMode");
        if (mode.isEmpty()) mode = APPEND;
        if (mode.equals(NEVER)) return raw;
        String enc = RemoteUtil.encodeSegment(name);
        if (raw.contains("{name}")) return raw.replace("{name}", enc);
        if (mode.equals(ONLY)) return raw;
        int q = raw.indexOf('?');
        String path = q >= 0 ? raw.substring(0, q) : raw;
        String query = q >= 0 ? raw.substring(q) : "";
        return (path.endsWith("/") ? path : path + "/") + enc + query;
    }

    /** name=value lines; {name} is replaced in the values. */
    static List<String[]> parseParams(Cfg c, String name) throws RemoteException {
        List<String[]> out = new ArrayList<>();
        String p = c.get("params");
        if (p.isBlank()) return out;
        for (String line : p.split("\\r?\\n", -1)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int e = line.indexOf('=');
            if (e <= 0) throw new RemoteException("Each extra parameter must look like name=value.", null);
            String n = line.substring(0, e), v = line.substring(e + 1).replace("{name}", name);
            RemoteUtil.noCtl("A parameter name", n);
            RemoteUtil.noCtl("A parameter value", v);
            if (out.size() >= MAX_PARAMS) throw new RemoteException("At most " + MAX_PARAMS + " extra parameters are allowed.", null);
            out.add(new String[]{n, v});
        }
        return out;
    }

    private static String addQuery(String url, String pairs) {
        if (pairs.isEmpty()) return url;
        if (!url.contains("?")) return url + "?" + pairs;
        return url + (url.endsWith("?") || url.endsWith("&") ? "" : "&") + pairs;
    }

    static Draft build(Cfg c, String name, String ct) throws RemoteException {
        Draft d = new Draft();
        d.method = "POST".equalsIgnoreCase(c.trim("method")) ? "POST" : "PUT";
        d.multipart = "multipart/form-data".equals(c.trim("body"));
        String url = baseUrl(c, name);
        List<String[]> params = parseParams(c, name);
        boolean asForm = PARAMS_FORM.equals(c.trim("paramsIn"));
        if (!params.isEmpty() && asForm && !d.multipart) {
            throw new RemoteException("Parameters are set to be sent as multipart form fields, but 'Body' is not multipart/form-data. "
                    + "Change 'Body' to multipart/form-data or send the parameters as a query string.", null);
        }
        if (!params.isEmpty() && !asForm) {
            StringBuilder q = new StringBuilder();
            for (String[] p : params) {
                if (q.length() > 0) q.append('&');
                q.append(RemoteUtil.encodeSegment(p[0])).append('=').append(RemoteUtil.encodeSegment(p[1]));
            }
            url = addQuery(url, q.toString());
        }
        String auth = c.trim("auth");
        if ("apikey-query".equals(auth)) {
            String n = c.req("keyName");
            c.req("keyValue");
            url = addQuery(url, RemoteUtil.encodeSegment(n) + "={{secret:keyValue}}");
        }
        d.url = url;

        if (d.multipart) {
            String field = c.trim("multipartField");
            if (field.isEmpty()) field = "file";
            if (!field.matches("[A-Za-z0-9._\\- ]{1,100}")) throw new RemoteException("The multipart field name contains invalid characters.", null);
            String boundary = "----BurpShot" + RemoteUtil.randomHex(12);
            StringBuilder b = new StringBuilder();
            if (asForm) {
                for (String[] p : params) {
                    b.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"")
                            .append(p[0].replace("\"", "%22")).append("\"\r\n\r\n").append(p[1]).append("\r\n");
                }
            }
            b.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(field)
                    .append("\"; filename=\"").append(name.replace("\"", "_")).append("\"\r\nContent-Type: ").append(ct).append("\r\n\r\n");
            d.before = b.toString();
            d.after = "\r\n--" + boundary + "--\r\n";
            d.headers.add(new String[]{"Content-Type", "multipart/form-data; boundary=" + boundary});
        } else {
            d.headers.add(new String[]{"Content-Type", ct});
        }

        switch (auth) {
            case "basic": {
                String user = c.req("user");
                if (user.indexOf(':') >= 0) throw new RemoteException("The user name must not contain ':' for basic authentication.", null);
                d.headers.add(new String[]{"Authorization", "Basic {{secret:basic}}"});
                break;
            }
            case "bearer":
                c.req("token");
                d.headers.add(new String[]{"Authorization", "Bearer {{secret:token}}"});
                break;
            case "apikey-header":
                d.headers.add(new String[]{c.req("keyName"), "{{secret:keyValue}}"});
                c.req("keyValue");
                break;
            case "cookie":
                c.req("cookie");
                d.headers.add(new String[]{"Cookie", "{{secret:cookie}}"});
                break;
            default:
        }
        String extra = c.get("headers");
        if (!extra.isBlank()) {
            int n = 0;
            for (String line : extra.split("\\r?\\n", -1)) {
                if (line.isBlank()) continue;
                if (++n > 20) throw new RemoteException("At most 20 extra headers are allowed.", null);
                int i = line.indexOf(':');
                if (i <= 0) throw new RemoteException("Extra header line " + n + " is not in 'Name: value' form.", null);
                d.headers.add(new String[]{line.substring(0, i).trim(), line.substring(i + 1).trim().replace("{name}", name)});
            }
        }
        return d;
    }

    // ---- secrets --------------------------------------------------------------------------------------------------

    static String secretValue(Cfg c, String key) throws RemoteException {
        String v;
        if (key.equals("basic")) {
            String user = c.trim("user");
            if (user.isEmpty()) throw new RemoteException("The placeholder {{secret:basic}} needs the user name and password settings.", null);
            if (user.indexOf(':') >= 0) throw new RemoteException("The user name must not contain ':' for basic authentication.", null);
            String raw = user + ":" + c.get("password");
            RemoteUtil.noCtl("Credentials", raw);
            v = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            c.addDerived(v);
            return v;
        }
        RemoteSpec.Field f = c.type.field(key);
        if (f == null || !f.secret) throw new RemoteException("Unknown placeholder {{secret:" + key + "}}.", null);
        v = key.equals("password") ? c.get(key) : c.trim(key);
        if (v.isEmpty()) throw new RemoteException("The setting behind {{secret:" + key + "}} is empty.", null);
        RemoteUtil.noCtl("The value of {{secret:" + key + "}}", v);
        return v;
    }

    static String resolve(Cfg c, String s, boolean urlEncode) throws RemoteException {
        Matcher m = TOKEN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = secretValue(c, m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(urlEncode ? RemoteUtil.encodeSegment(v) : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Map<String, String> secretMap(Map<String, String> cfg) {
        Set<String> keys = new java.util.HashSet<>();
        for (RemoteSpec.Type t : RemoteSpec.types()) for (RemoteSpec.Field f : t.fields) if (f.secret) keys.add(f.key);
        keys.remove("headers");
        keys.remove("params");
        Map<String, String> out = new LinkedHashMap<>();   // value -> key
        if (cfg == null) return out;
        for (String k : keys) {
            String v = cfg.get(k);
            if (v == null) continue;
            String t = k.equals("password") ? v : v.trim();
            if (t.length() >= 6) out.putIfAbsent(t, k);
            if (t.startsWith("?") && t.length() - 1 >= 6) out.putIfAbsent(t.substring(1), k);
        }
        String u = cfg.get("user"), p = cfg.get("password");
        if (u != null && !u.isBlank() && u.indexOf(':') < 0 && p != null) {
            String b = Base64.getEncoder().encodeToString((u.trim() + ":" + p).getBytes(StandardCharsets.UTF_8));
            if (b.length() >= 6) out.putIfAbsent(b, "basic");
        }
        return out;
    }

    static String mask(String text, Map<String, String> cfg) {
        Map<String, String> m = secretMap(cfg);
        List<String> vals = new ArrayList<>(m.keySet());
        vals.sort((a, b) -> b.length() - a.length());
        String s = text;
        for (String v : vals) s = s.replace(v, "{{secret:" + m.get(v) + "}}");
        return s;
    }

    static String reveal(String text, Map<String, String> cfg) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : secretMap(cfg).entrySet()) byKey.put(e.getValue(), e.getKey());
        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = byKey.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- rendering ------------------------------------------------------------------------------------------------

    private static URI parseForHost(String urlWithTokens) throws RemoteException {
        String dummy = TOKEN.matcher(urlWithTokens).replaceAll("X");
        if (dummy.length() > MAX_URL) throw new RemoteException("The URL is too long.", null);
        return RemoteHttp.parseHttpUri(dummy, "The URL");
    }

    static String render(Draft d, long dataLength) throws RemoteException {
        URI u = parseForHost(d.url);
        long len = d.before.getBytes(StandardCharsets.UTF_8).length + d.after.getBytes(StandardCharsets.UTF_8).length + (d.hasFile ? dataLength : 0);
        StringBuilder sb = new StringBuilder();
        sb.append(d.method).append(' ').append(d.url).append(" HTTP/1.1\n");
        sb.append("Host: ").append(u.getRawAuthority()).append('\n');
        boolean lenDone = false;
        for (String[] h : d.headers) {
            sb.append(h[0]).append(": ").append(h[1]).append('\n');
            if (h[0].equalsIgnoreCase("Content-Type") && !lenDone) { sb.append("Content-Length: ").append(len).append('\n'); lenDone = true; }
        }
        if (!lenDone) sb.append("Content-Length: ").append(len).append('\n');
        sb.append('\n');
        sb.append((d.before + (d.hasFile ? RemoteClient.FILE_MARK : "") + d.after).replace("\r\n", "\n"));
        return sb.toString();
    }

    // ---- parsing --------------------------------------------------------------------------------------------------

    static Draft parse(String text) throws RemoteException {
        if (text == null) throw new RemoteException("The request text is empty.", null);
        String t = text.replace("\r\n", "\n");
        String[] lines = t.split("\n", -1);
        if (lines.length == 0 || lines[0].isEmpty()) throw new RemoteException("The request is empty or does not start with a request line.", null);
        Matcher m = REQ_LINE.matcher(lines[0]);
        if (!m.matches()) {
            throw new RemoteException("The first line must be 'METHOD URL HTTP/1.1' with METHOD PUT, POST or PATCH.", null);
        }
        Draft d = new Draft();
        d.parsed = true;
        d.method = m.group(1);
        d.url = m.group(2);
        if (d.url.length() > MAX_URL) throw new RemoteException("The URL is too long.", null);
        int i = 1, total = lines[0].length();
        for (; i < lines.length && !lines[i].isEmpty(); i++) {
            String l = lines[i];
            total += l.length() + 1;
            if (total > MAX_HEADER_BYTES) throw new RemoteException("The request headers are too large (limit 32 KB).", null);
            if (d.headers.size() >= MAX_HEADERS_TEXT) throw new RemoteException("At most " + MAX_HEADERS_TEXT + " headers are allowed.", null);
            int c = l.indexOf(':');
            if (c <= 0) throw new RemoteException("Header line " + i + " is not in 'Name: value' form.", null);
            String name = l.substring(0, c), val = l.substring(c + 1);
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw new RemoteException("Header line " + i + " has an invalid header name.", null);
            for (int k = 0; k < val.length(); k++) {
                char ch = val.charAt(k);
                if ((ch < 0x20 && ch != '\t') || ch == 0x7f) throw new RemoteException("Header '" + name + "' contains a control character.", null);
            }
            d.headers.add(new String[]{name, val.strip().replaceAll("^[ \\t]+|[ \\t]+$", "")});
        }
        String body = "";
        if (i < lines.length) {
            StringBuilder sb = new StringBuilder();
            for (int k = i + 1; k < lines.length; k++) { if (k > i + 1) sb.append('\n'); sb.append(lines[k]); }
            body = sb.toString();
        }
        boolean multipart = false;
        for (String[] h : d.headers) if (h[0].equalsIgnoreCase("Content-Type") && h[1].toLowerCase(Locale.ROOT).startsWith("multipart/")) multipart = true;
        if (multipart) body = body.replace("\n", "\r\n");
        int a = body.indexOf(RemoteClient.FILE_MARK);
        if (a >= 0) {
            if (body.indexOf(RemoteClient.FILE_MARK, a + 1) >= 0) throw new RemoteException("The file marker appears more than once; keep exactly one.", null);
            d.before = body.substring(0, a);
            d.after = body.substring(a + RemoteClient.FILE_MARK.length());
            d.hasFile = true;
        } else {
            d.before = body;
            d.after = "";
            d.hasFile = false;
        }
        return d;
    }

    // ---- sending --------------------------------------------------------------------------------------------------

    static Result execute(Cfg c, Draft d, byte[] data, RemoteClient.Ui ui) throws RemoteException {
        if (data != null && data.length > RemoteClient.MAX_UPLOAD) throw new RemoteException("The file is larger than the maximum upload size.", null);
        String urlTpl = d.url;
        if (urlTpl.length() > MAX_URL) throw new RemoteException("The URL is too long.", null);
        String url = resolve(c, urlTpl, true);
        RemoteUtil.noCtl("URL", url);
        if (d.parsed) {   // typed-in text: whatever it contains in the URL or headers must be redacted from errors
            int q = url.indexOf('?');
            if (q >= 0) c.addDerived(url.substring(q + 1));
        }
        URI uri = RemoteHttp.parseHttpUri(url, "The URL");
        if (RemoteHttp.isPlain(uri)) RemoteClient.prog(ui, "WARNING: http:// is unencrypted; data and credentials travel in clear text.");
        HttpRequest.Builder b = RemoteHttp.request(uri);
        List<String> ignored = new ArrayList<>(d.ignored);
        int bytes = 0;
        for (String[] h : d.headers) {
            String val = resolve(c, h[1], false);
            if (MANAGED.contains(h[0].toLowerCase(Locale.ROOT))) { if (!ignored.contains(h[0])) ignored.add(h[0]); continue; }
            if (d.parsed && !h[0].equalsIgnoreCase("Content-Type") && !h[0].equalsIgnoreCase("Accept")) c.addDerived(val);
            bytes += h[0].length() + val.length();
            if (bytes > MAX_HEADER_BYTES) throw new RemoteException("The request headers are too large (limit 32 KB).", null);
            RemoteHttp.header(b, h[0], val);
        }
        String before = resolve(c, d.before, false), after = resolve(c, d.after, false);
        if (d.noBody) {
            b.method(d.method, HttpRequest.BodyPublishers.noBody());
        } else {
            byte[] bb = before.getBytes(StandardCharsets.UTF_8), ab = after.getBytes(StandardCharsets.UTF_8);
            byte[] file = d.hasFile && data != null ? data : new byte[0];
            byte[] body = new byte[bb.length + file.length + ab.length];
            System.arraycopy(bb, 0, body, 0, bb.length);
            System.arraycopy(file, 0, body, bb.length, file.length);
            System.arraycopy(ab, 0, body, bb.length + file.length, ab.length);
            if (body.length > RemoteClient.MAX_UPLOAD + 1024 * 1024) throw new RemoteException("The request body is too large.", null);
            b.method(d.method, RemoteHttp.body(c, body));
        }
        String shown = RemoteUtil.safeUrl(uri.toString());
        RemoteClient.prog(ui, "Sending " + d.method + " to " + shown + " ...");
        Resp r = RemoteHttp.send(c, b.build());
        RemoteHttp.expectOk(c, r);
        String detail = d.method + " HTTP " + r.status + ", " + (data == null || !d.hasFile ? 0 : data.length) + " bytes";
        if (!d.hasFile && !d.noBody) detail += "; file not included";
        if (!ignored.isEmpty()) detail += "; ignored managed headers: " + String.join(", ", ignored);
        return new Result(shown, detail);
    }

    // ---- non-http previews (dry run) ------------------------------------------------------------------------------

    private static final String FAKE = "\u0001BSFILE-PLACEHOLDER\u0001";

    static String dryPreview(Cfg c, Map<String, String> cfg, String name, long dataLength, String ct) throws RemoteException {
        if (c.type.id.equals("dropbox") && dataLength > 150L * 1024 * 1024) throw new RemoteException("Dropbox simple uploads are limited to 150 MB.", null);
        if (c.type.id.equals("onedrive") && dataLength > 4L * 1024 * 1024) throw new RemoteException("OneDrive simple upload is limited to 4 MB.", null);
        c.dry = new RemoteHttp.Dry();
        byte[] fake = FAKE.getBytes(StandardCharsets.ISO_8859_1);
        try {
            RemoteClient.dispatchUploadForPreview(c, name, fake, ct);
            throw new RemoteException("This destination did not produce an HTTP request.", null);
        } catch (RemoteHttp.DryRun stop) {
            HttpRequest r = c.dry.req;
            byte[] body = c.dry.body;
            URI u = r.uri();
            StringBuilder sb = new StringBuilder();
            sb.append(r.method()).append(' ').append(u.toString()).append(" HTTP/1.1\n");
            sb.append("Host: ").append(u.getRawAuthority()).append('\n');
            Map<String, List<String>> hs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            hs.putAll(r.headers().map());
            long len = 0;
            String bodyText = "";
            if (body != null) {
                String s = new String(body, StandardCharsets.ISO_8859_1);
                len = body.length + (dataLength - fake.length);
                bodyText = s.replace(FAKE, RemoteClient.FILE_MARK);
                bodyText = new String(bodyText.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
            boolean lenDone = false;
            for (Map.Entry<String, List<String>> e : hs.entrySet()) {
                for (String v : e.getValue()) {
                    sb.append(e.getKey()).append(": ").append(v).append('\n');
                }
                if (e.getKey().equalsIgnoreCase("Content-Type") && body != null) { sb.append("Content-Length: ").append(len).append('\n'); lenDone = true; }
            }
            if (!lenDone && body != null) sb.append("Content-Length: ").append(len).append('\n');
            sb.append('\n').append(bodyText);
            String text = sb.toString().replaceAll("Signature=[0-9a-fA-F]{64}", "Signature=***");
            return mask(text, cfg);
        }
    }
}
