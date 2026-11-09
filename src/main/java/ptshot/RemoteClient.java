package ptshot;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ptshot.RemoteHttp.Resp;
import ptshot.RemoteUtil.Cfg;

/**
 * Entry points for "Share -> Save to remote". Contains the HTTP based backends (generic REST/WebDAV, S3, GCS,
 * Dropbox, Drive, OneDrive, Azure); FTP, SFTP and folders live in RemoteFtp, RemoteSftp and RemoteFs.
 *
 * Security rules applied everywhere: no redirects, TLS verification on unless explicitly disabled, no
 * secrets in messages (see RemoteUtil.Cfg.redact), all names/paths sanitised and percent-encoded.
 */
final class RemoteClient {
    private RemoteClient() {}

    interface Ui {
        void progress(String message);
        /** Previously trusted SHA-256 fingerprint ("SHA256:base64...") for "host:port", or null. */
        String trustedHostKey(String hostPort);
        boolean confirmHostKey(String hostPort, String keyType, String fingerprint, boolean changed);
        void rememberHostKey(String hostPort, String fingerprint);
    }

    static final class Result {
        final String location;
        final String detail;
        Result(String location, String detail) { this.location = location; this.detail = detail; }
        @Override public String toString() { return location + " (" + detail + ")"; }
    }

    static final class RemoteException extends Exception {
        private static final long serialVersionUID = 1L;
        RemoteException(String message, Throwable cause) { super(message, cause); }
    }

    static final String FILE_MARK = "[[BurpShot: the file contents are inserted here]]";

    static final class RequestPreview {
        final String text; final boolean editable; final String note;
        RequestPreview(String text, boolean editable, String note) { this.text = text; this.editable = editable; this.note = note; }
    }

    static boolean hasRequestView(String typeId) {
        if (typeId == null) return false;
        switch (typeId) {
            case "http": case "s3": case "gcs": case "dropbox": case "gdrive": case "onedrive": case "azure": return true;
            default: return false;
        }
    }

    /** Text of the HTTP request that upload() would send (file bytes replaced by FILE_MARK, secrets as {{secret:KEY}}). */
    static RequestPreview previewRequest(String typeId, Map<String, String> cfg, String remoteName, long dataLength,
                                         String contentType) throws RemoteException {
        RemoteSpec.Type t = RemoteSpec.type(typeId);
        if (t == null) throw new RemoteException("Unknown destination type.", null);
        if (!hasRequestView(typeId)) throw new RemoteException("This destination does not use an HTTP request.", null);
        Cfg c = new Cfg(t, cfg);
        String name = RemoteUtil.sanitizeName(remoteName);
        String ct = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType.trim();
        RemoteUtil.noCtl("Content type", ct);
        try {
            if (typeId.equals("http")) {
                String text = RemoteReq.render(RemoteReq.build(c, name, ct), dataLength);
                return new RequestPreview(text, true, "Host and Content-Length are shown for information only: they are ignored when sending "
                        + "and recomputed. {{secret:...}} placeholders are replaced by the saved values when the request is sent.");
            }
            String text = RemoteReq.dryPreview(c, cfg, name, dataLength, ct);
            return new RequestPreview(text, false, "This request is signed or provider specific and cannot be edited here; change the "
                    + "destination fields instead. Signatures, tokens and SAS values are masked.");
        } catch (RemoteException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RemoteException("Unexpected internal error (" + e.getClass().getSimpleName() + ").", null);
        }
    }

    /** Sends an (edited) request text produced by previewRequest. Only for the generic "http" type. */
    static Result sendRequestText(String typeId, Map<String, String> cfg, String requestText, byte[] data, Ui ui) throws RemoteException {
        RemoteSpec.Type t = RemoteSpec.type(typeId);
        if (t == null || !"http".equals(typeId)) throw new RemoteException("Only the HTTP destination supports editing the request.", null);
        if (data == null) throw new RemoteException("Nothing to upload.", null);
        if (data.length > MAX_UPLOAD) throw new RemoteException("The file is " + (data.length / (1024 * 1024))
                + " MB; the maximum upload size is " + (MAX_UPLOAD / (1024 * 1024)) + " MB.", null);
        Cfg c = new Cfg(t, cfg);
        return guard(c, () -> RemoteReq.execute(c, RemoteReq.parse(requestText), data, ui));
    }

    /** Replaces secret values (length >= 6) by {{secret:KEY}} tokens. */
    static String maskSecrets(String text, Map<String, String> cfg) { return RemoteReq.mask(text, cfg); }

    /** Inverse of maskSecrets (raw insertion; for display). */
    static String revealSecrets(String text, Map<String, String> cfg) { return RemoteReq.reveal(text, cfg); }

    static Result dispatchUploadForPreview(Cfg c, String name, byte[] fake, String ct) throws RemoteException {
        return dispatchUpload(c, name, fake, ct, null);
    }

    static final long MAX_UPLOAD = 256L * 1024 * 1024;
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern BUCKET = Pattern.compile("[A-Za-z0-9._-]{1,255}");
    private static final Pattern REGION = Pattern.compile("[A-Za-z0-9-]{1,40}");

    // =============================================================================================
    // public API
    // =============================================================================================

    static Result upload(String typeId, Map<String, String> cfg, String remoteName, byte[] data,
                         String contentType, Ui ui) throws RemoteException {
        RemoteSpec.Type t = RemoteSpec.type(typeId);
        if (t == null) throw new RemoteException("Unknown destination type.", null);
        if (data == null) throw new RemoteException("Nothing to upload.", null);
        if (data.length > MAX_UPLOAD) {
            throw new RemoteException("The file is " + (data.length / (1024 * 1024)) + " MB; the maximum upload size is "
                    + (MAX_UPLOAD / (1024 * 1024)) + " MB.", null);
        }
        Cfg c = new Cfg(t, cfg);
        String name = RemoteUtil.sanitizeName(remoteName);
        String ct = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType.trim();
        RemoteUtil.noCtl("Content type", ct);
        return guard(c, () -> dispatchUpload(c, name, data, ct, ui));
    }

    static Result test(String typeId, Map<String, String> cfg, Ui ui) throws RemoteException {
        RemoteSpec.Type t = RemoteSpec.type(typeId);
        if (t == null) throw new RemoteException("Unknown destination type.", null);
        Cfg c = new Cfg(t, cfg);
        return guard(c, () -> dispatchTest(c, ui));
    }

    private interface Job { Result run() throws RemoteException; }

    /** Converts unexpected runtime failures into safe messages (exception text could contain secrets). */
    private static Result guard(Cfg c, Job j) throws RemoteException {
        try {
            return j.run();
        } catch (RemoteException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RemoteException("Unexpected internal error (" + e.getClass().getSimpleName() + ").", null);
        }
    }

    private static Result dispatchUpload(Cfg c, String name, byte[] data, String ct, Ui ui) throws RemoteException {
        switch (c.type.id) {
            case "http": return httpPut(c, name, data, ct, ui);
            case "s3": return s3Upload(c, name, data, ct, ui);
            case "gcs": return gcsUpload(c, name, data, ct, ui);
            case "dropbox": return dropboxUpload(c, name, data, ui);
            case "gdrive": return driveUpload(c, name, data, ct, ui);
            case "onedrive": return onedriveUpload(c, name, data, ui);
            case "azure": return azureUpload(c, name, data, ct, ui);
            case "ftp": return RemoteFtp.upload(c, name, data, ui);
            case "sftp": return RemoteSftp.upload(c, name, data, ui);
            case "share":
            case "local": return RemoteFs.upload(c, name, data, ui);
            default: throw new RemoteException("Unknown destination type.", null);
        }
    }

    private static Result dispatchTest(Cfg c, Ui ui) throws RemoteException {
        switch (c.type.id) {
            case "http": return httpTest(c, ui);
            case "s3": return s3Test(c, ui);
            case "gcs": return gcsTest(c, ui);
            case "dropbox": return dropboxTest(c, ui);
            case "gdrive": return driveTest(c, ui);
            case "onedrive": return onedriveTest(c, ui);
            case "azure": return azureTest(c, ui);
            case "ftp": return RemoteFtp.test(c, ui);
            case "sftp": return RemoteSftp.test(c, ui);
            case "share":
            case "local": return RemoteFs.test(c, ui);
            default: throw new RemoteException("Unknown destination type.", null);
        }
    }

    static void prog(Ui ui, String msg) {
        if (ui != null) ui.progress(msg);
    }

    static String probeName() { return "burpshot-probe-" + RemoteUtil.randomHex(4) + ".txt"; }
    static final byte[] PROBE = "BurpShot connectivity test - safe to delete\n".getBytes(StandardCharsets.UTF_8);

    private static String detail(Resp r, int bytes) { return "HTTP " + r.status + ", " + bytes + " bytes"; }

    private static void warnPlain(Ui ui, URI u) {
        if (RemoteHttp.isPlain(u)) prog(ui, "WARNING: http:// is unencrypted; data and credentials travel in clear text.");
    }

    // =============================================================================================
    // 1. generic HTTP(S) / WebDAV
    // =============================================================================================

    private static Result httpPut(Cfg c, String name, byte[] data, String ct, Ui ui) throws RemoteException {
        return RemoteReq.execute(c, RemoteReq.build(c, name, ct), data, ui);
    }

    private static Result httpTest(Cfg c, Ui ui) throws RemoteException {
        String probe = probeName();
        RemoteReq.Draft d = RemoteReq.build(c, probe, "text/plain");
        boolean nameInUrl = !d.url.equals(RemoteReq.build(c, probe + "x", "text/plain").url);
        if (!"PUT".equals(d.method) || !nameInUrl) {
            // A POST endpoint (or a URL that does not contain the name) cannot be probed without creating or
            // overwriting something, so only check reachability + authentication with HEAD.
            try {
                Result hr = RemoteReq.execute(c, d.withoutBody("HEAD"), null, ui);
                return new Result(hr.location, "Reachable (HEAD succeeded); upload itself was not tested because it would create or overwrite data.");
            } catch (RemoteException e) {
                String m = e.getMessage();
                // 404/405 and similar are fine for HEAD on an upload endpoint; auth failures and server errors are not.
                if (m != null && m.startsWith("The server answered HTTP 4") && !m.contains("HTTP 401") && !m.contains("HTTP 403")) {
                    return new Result(RemoteUtil.safeUrl(d.url), "Reachable (" + m.substring(m.indexOf("HTTP"), m.indexOf("HTTP") + 8).trim()
                            + " for HEAD); upload itself was not tested because it would create or overwrite data.");
                }
                throw e;
            }
        }
        Result put = RemoteReq.execute(c, d, PROBE, ui);
        String del;
        try {
            RemoteReq.execute(c, d.withoutBody("DELETE"), null, ui);
            del = "probe deleted";
        } catch (RemoteException e) {
            del = "probe could NOT be deleted (" + e.getMessage() + "), remove " + probe + " manually";
        }
        return new Result(put.location, put.detail + "; " + del);
    }

    // =============================================================================================
    // 2. S3 and S3-compatible (also used for GCS HMAC mode)
    // =============================================================================================

    /** Resolved addressing for one S3-style endpoint. */
    private static final class S3 {
        String scheme, authority, pathPrefix = "", region;
        String accessKey, secretKey, sessionToken;
        boolean signed;
        List<String> keyPrefix = new ArrayList<>();
        String bucket;
    }

    private static String authority(URI u) {
        int def = "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
        return u.getHost() + (u.getPort() != -1 && u.getPort() != def ? ":" + u.getPort() : "");
    }

    private static S3 s3Conf(Cfg c) throws RemoteException {
        S3 s = new S3();
        s.signed = "access key".equals(c.trim("auth"));
        s.region = c.trim("region").isEmpty() ? "us-east-1" : c.trim("region");
        if (!REGION.matcher(s.region).matches()) throw new RemoteException("The region contains invalid characters.", null);
        s.bucket = c.req("bucket");
        if (!BUCKET.matcher(s.bucket).matches()) throw new RemoteException("The bucket name contains invalid characters.", null);
        String ep = c.trim("endpoint");
        String scheme = "https";
        if (ep.regionMatches(true, 0, "http://", 0, 7)) { scheme = "http"; ep = ep.substring(7); }
        else if (ep.regionMatches(true, 0, "https://", 0, 8)) ep = ep.substring(8);
        while (ep.endsWith("/")) ep = ep.substring(0, ep.length() - 1);
        if (ep.isEmpty() || (ep.equals("s3.amazonaws.com") && !s.region.equals("us-east-1"))) {
            ep = "s3." + s.region + ".amazonaws.com";
        }
        URI u = RemoteHttp.parseHttpUri(scheme + "://" + ep, "The endpoint");
        if (u.getRawPath() != null && !u.getRawPath().isEmpty()) throw new RemoteException("The endpoint must be a host name (no path).", null);
        s.scheme = scheme;
        String host = u.getHost().toLowerCase();
        boolean aws = host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn");
        boolean virtual = aws && !c.bool("pathStyle") && !s.bucket.contains(".");
        URI hostUri = RemoteHttp.parseHttpUri(scheme + "://" + (virtual ? s.bucket + "." : "") + ep, "The endpoint");
        s.authority = authority(hostUri);
        s.pathPrefix = virtual ? "" : "/" + s.bucket;
        s.keyPrefix = RemoteUtil.sanitizeSegments(c.get("prefix"));
        if (s.signed) {
            s.accessKey = c.req("accessKey");
            s.secretKey = c.req("secretKey");
            s.sessionToken = c.trim("sessionToken");
            RemoteUtil.noCtl("Access key", s.accessKey);
            RemoteUtil.noCtl("Session token", s.sessionToken);
        }
        return s;
    }

    private static List<String> keySegments(List<String> prefix, String name) {
        List<String> l = new ArrayList<>(prefix);
        l.add(name);
        return l;
    }

    /** Executes one (optionally SigV4-signed) object request. */
    private static Resp s3Op(Cfg c, S3 s, String method, List<String> key, byte[] body, String ct) throws RemoteException {
        String path = s.pathPrefix + "/" + RemoteUtil.encodePath(key);
        URI uri = URI.create(s.scheme + "://" + s.authority + path);
        HttpRequest.Builder b = RemoteHttp.request(uri);
        byte[] payload = body == null ? new byte[0] : body;
        if (s.signed) {
            String amz = AMZ_DATE.format(Instant.now());
            String hash = RemoteUtil.hex(RemoteUtil.sha256(payload));
            TreeMap<String, String> h = new TreeMap<>();
            h.put("host", s.authority);
            h.put("x-amz-content-sha256", hash);
            h.put("x-amz-date", amz);
            if (!s.sessionToken.isEmpty()) h.put("x-amz-security-token", s.sessionToken);
            String auth = RemoteSigV4.authorization(method, path, "", h, hash, s.accessKey, s.secretKey, s.region, "s3", amz);
            RemoteHttp.header(b, "x-amz-date", amz);
            RemoteHttp.header(b, "x-amz-content-sha256", hash);
            if (!s.sessionToken.isEmpty()) RemoteHttp.header(b, "x-amz-security-token", s.sessionToken);
            RemoteHttp.header(b, "Authorization", auth);
        }
        if (body != null) {
            RemoteHttp.header(b, "Content-Type", ct);
            b.method(method, RemoteHttp.body(c, body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return RemoteHttp.send(c, b.build());
    }

    private static Result presignedPut(Cfg c, String urlKey, byte[] data, String ct, Ui ui) throws RemoteException {
        String raw = c.req(urlKey);
        RemoteUtil.noCtl("URL", raw);
        URI uri = RemoteHttp.parseHttpUri(raw, "The pre-signed URL");
        warnPlain(ui, uri);
        HttpRequest.Builder b = RemoteHttp.request(uri);
        RemoteHttp.header(b, "Content-Type", ct);
        b.PUT(RemoteHttp.body(c, data));
        prog(ui, "Uploading to " + RemoteUtil.safeUrl(raw) + " ...");
        Resp r = RemoteHttp.send(c, b.build());
        RemoteHttp.expectOk(c, r);
        return new Result(RemoteUtil.safeUrl(raw), detail(r, data.length));
    }

    private static Result s3Upload(Cfg c, String name, byte[] data, String ct, Ui ui) throws RemoteException {
        if ("pre-signed URL".equals(c.trim("auth"))) return presignedPut(c, "presignedUrl", data, ct, ui);
        S3 s = s3Conf(c);
        List<String> key = keySegments(s.keyPrefix, name);
        prog(ui, "Uploading to " + s.scheme + "://" + s.authority + s.pathPrefix + "/" + RemoteUtil.encodePath(key) + " ...");
        if ("http".equals(s.scheme)) prog(ui, "WARNING: http:// is unencrypted; data and credentials travel in clear text.");
        Resp r = s3Op(c, s, "PUT", key, data, ct);
        RemoteHttp.expectOk(c, r);
        return new Result(s.scheme + "://" + s.authority + s.pathPrefix + "/" + RemoteUtil.encodePath(key), detail(r, data.length));
    }

    private static Result s3Test(Cfg c, Ui ui) throws RemoteException {
        if ("pre-signed URL".equals(c.trim("auth"))) {
            URI u = RemoteHttp.parseHttpUri(c.req("presignedUrl"), "The pre-signed URL");
            return new Result(RemoteUtil.safeUrl(u.toString()),
                    "URL syntax is valid. A pre-signed URL only works for one object, so no request was sent.");
        }
        S3 s = s3Conf(c);
        String probe = probeName();
        List<String> key = keySegments(s.keyPrefix, probe);
        Resp r = s3Op(c, s, "PUT", key, PROBE, "text/plain");
        RemoteHttp.expectOk(c, r);
        Resp d = s3Op(c, s, "DELETE", key, null, null);
        String del = d.ok() ? "probe deleted" : "probe could NOT be deleted (HTTP " + d.status + "), remove " + probe + " manually";
        return new Result(s.scheme + "://" + s.authority + s.pathPrefix, "PUT OK (HTTP " + r.status + "); " + del);
    }

    // =============================================================================================
    // 3. Google Cloud Storage
    // =============================================================================================

    private static String apiBase(Cfg c) throws RemoteException {
        String ab = c.trim("apiBase");
        if (ab.isEmpty()) ab = c.type.field("apiBase").def;
        URI u = RemoteHttp.parseHttpUri(ab, "The API base URL");
        String s = u.getScheme().toLowerCase() + "://" + authority(u);
        if (u.getRawPath() != null && !u.getRawPath().isEmpty() && !u.getRawPath().equals("/")) {
            throw new RemoteException("The API base URL must not contain a path.", null);
        }
        return s;
    }

    private static S3 gcsInterop(Cfg c, boolean signed) throws RemoteException {
        S3 s = new S3();
        URI u = RemoteHttp.parseHttpUri(apiBase(c), "The API base URL");
        s.scheme = u.getScheme().toLowerCase();
        s.authority = authority(u);
        s.bucket = c.req("bucket");
        if (!BUCKET.matcher(s.bucket).matches()) throw new RemoteException("The bucket name contains invalid characters.", null);
        s.pathPrefix = "/" + s.bucket;     // path style works for every bucket name
        s.region = "auto";
        s.keyPrefix = RemoteUtil.sanitizeSegments(c.get("prefix"));
        s.signed = signed;
        if (signed) {
            s.accessKey = c.req("accessId");
            s.secretKey = c.req("secretKey");
            s.sessionToken = "";
            RemoteUtil.noCtl("Access ID", s.accessKey);
        }
        return s;
    }

    private static Result gcsUpload(Cfg c, String name, byte[] data, String ct, Ui ui) throws RemoteException {
        String auth = c.trim("auth");
        if ("signed-url".equals(auth)) return presignedPut(c, "signedUrl", data, ct, ui);
        if ("bearer".equals(auth)) {
            String bucket = c.req("bucket");
            if (!BUCKET.matcher(bucket).matches()) throw new RemoteException("The bucket name contains invalid characters.", null);
            List<String> key = keySegments(RemoteUtil.sanitizeSegments(c.get("prefix")), name);
            String object = String.join("/", key);
            URI uri = RemoteHttp.parseHttpUri(apiBase(c) + "/upload/storage/v1/b/" + RemoteUtil.encodeSegment(bucket)
                    + "/o?uploadType=media&name=" + RemoteUtil.encodeSegment(object), "The API base URL");
            warnPlain(ui, uri);
            HttpRequest.Builder b = RemoteHttp.request(uri);
            RemoteHttp.header(b, "Authorization", "Bearer " + c.req("token"));
            RemoteHttp.header(b, "Content-Type", ct);
            b.POST(RemoteHttp.body(c, data));
            prog(ui, "Uploading to gs://" + bucket + "/" + object + " ...");
            Resp r = RemoteHttp.send(c, b.build());
            RemoteHttp.expectOk(c, r);
            return new Result("gs://" + bucket + "/" + object, detail(r, data.length));
        }
        S3 s = gcsInterop(c, "hmac".equals(auth));
        List<String> key = keySegments(s.keyPrefix, name);
        prog(ui, "Uploading to gs://" + s.bucket + "/" + String.join("/", key) + " ...");
        Resp r = s3Op(c, s, "PUT", key, data, ct);
        RemoteHttp.expectOk(c, r);
        return new Result("gs://" + s.bucket + "/" + String.join("/", key), detail(r, data.length));
    }

    private static Result gcsTest(Cfg c, Ui ui) throws RemoteException {
        String auth = c.trim("auth");
        if ("signed-url".equals(auth)) {
            URI u = RemoteHttp.parseHttpUri(c.req("signedUrl"), "The signed URL");
            return new Result(RemoteUtil.safeUrl(u.toString()),
                    "URL syntax is valid. A signed URL only works for one object, so no request was sent.");
        }
        String probe = probeName();
        if ("bearer".equals(auth)) {
            String bucket = c.req("bucket");
            if (!BUCKET.matcher(bucket).matches()) throw new RemoteException("The bucket name contains invalid characters.", null);
            Result put = gcsUpload(c, probe, PROBE, "text/plain", ui);
            String object = String.join("/", keySegments(RemoteUtil.sanitizeSegments(c.get("prefix")), probe));
            URI uri = RemoteHttp.parseHttpUri(apiBase(c) + "/storage/v1/b/" + RemoteUtil.encodeSegment(bucket)
                    + "/o/" + RemoteUtil.encodeSegment(object), "The API base URL");
            HttpRequest.Builder b = RemoteHttp.request(uri);
            RemoteHttp.header(b, "Authorization", "Bearer " + c.req("token"));
            b.DELETE();
            Resp d = RemoteHttp.send(c, b.build());
            return new Result("gs://" + bucket, put.detail + "; " + (d.ok() ? "probe deleted"
                    : "probe could NOT be deleted (HTTP " + d.status + "), remove " + probe + " manually"));
        }
        S3 s = gcsInterop(c, "hmac".equals(auth));
        List<String> key = keySegments(s.keyPrefix, probe);
        Resp r = s3Op(c, s, "PUT", key, PROBE, "text/plain");
        RemoteHttp.expectOk(c, r);
        Resp d = s3Op(c, s, "DELETE", key, null, null);
        return new Result("gs://" + s.bucket, "PUT OK (HTTP " + r.status + "); " + (d.ok() ? "probe deleted"
                : "probe could NOT be deleted (HTTP " + d.status + "), remove " + probe + " manually"));
    }

    // =============================================================================================
    // 4. Dropbox
    // =============================================================================================

    private static final long DROPBOX_MAX = 150L * 1024 * 1024;

    private static String dropboxApiHost(Cfg c) throws RemoteException {
        // Content (upload) host -> RPC host: content.dropboxapi.com -> api.dropboxapi.com; mocks keep the same base.
        String base = apiBase(c);
        return base.replace("://content.", "://api.");
    }

    private static Result dropboxUpload(Cfg c, String name, byte[] data, Ui ui) throws RemoteException {
        if (data.length > DROPBOX_MAX) {
            throw new RemoteException("Dropbox simple uploads are limited to 150 MB; this file is "
                    + (data.length / (1024 * 1024)) + " MB.", null);
        }
        List<String> segs = RemoteUtil.sanitizeSegments(c.get("folder"));
        segs.add(name);
        String path = "/" + String.join("/", segs);
        String mode = "overwrite".equals(c.trim("mode")) ? "overwrite" : "add";
        String arg = "{\"path\":" + RemoteUtil.jsonString(path) + ",\"mode\":\"" + mode
                + "\",\"autorename\":true,\"mute\":true}";
        URI uri = RemoteHttp.parseHttpUri(apiBase(c) + "/2/files/upload", "The API base URL");
        warnPlain(ui, uri);
        HttpRequest.Builder b = RemoteHttp.request(uri);
        RemoteHttp.header(b, "Authorization", "Bearer " + c.req("token"));
        RemoteHttp.header(b, "Dropbox-API-Arg", arg);
        RemoteHttp.header(b, "Content-Type", "application/octet-stream");
        b.POST(RemoteHttp.body(c, data));
        prog(ui, "Uploading to Dropbox " + path + " ...");
        Resp r = RemoteHttp.send(c, b.build());
        RemoteHttp.expectOk(c, r);
        Matcher m = Pattern.compile("\"path_display\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(r.body);
        String shown = m.find() ? RemoteUtil.stripCtl(m.group(1)).replace("\\/", "/") : path;
        return new Result("dropbox:" + shown, detail(r, data.length));
    }

    private static Result dropboxTest(Cfg c, Ui ui) throws RemoteException {
        URI uri = RemoteHttp.parseHttpUri(dropboxApiHost(c) + "/2/users/get_current_account", "The API base URL");
        HttpRequest.Builder b = RemoteHttp.request(uri);
        RemoteHttp.header(b, "Authorization", "Bearer " + c.req("token"));
        b.POST(HttpRequest.BodyPublishers.noBody());
        Resp r = RemoteHttp.send(c, b.build());
        RemoteHttp.expectOk(c, r);
        return new Result("dropbox:" + "/" + String.join("/", RemoteUtil.sanitizeSegments(c.get("folder"))),
                "Token accepted (HTTP " + r.status + "). Nothing was uploaded.");
    }

    // =============================================================================================
    // 5. Google Drive
    // =============================================================================================

    private static Result driveUpload(Cfg c, String name, byte[] data, String ct, Ui ui) throws RemoteException {
        String parent = c.trim("parent");
        if (!parent.isEmpty() && !parent.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new RemoteException("The parent folder ID contains invalid characters.", null);
        }
        String meta = "{\"name\":" + RemoteUtil.jsonString(name)
                + (parent.isEmpty() ? "" : ",\"parents\":[" + RemoteUtil.jsonString(parent) + "]") + "}";
        String boundary = "burpshot" + RemoteUtil.randomHex(12);
        byte[] head = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta
                + "\r\n--" + boundary + "\r\nContent-Type: " + ct + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + data.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(data, 0, body, head.length, data.length);
        System.arraycopy(tail, 0, body, head.length + data.length, tail.length);
        URI uri = RemoteHttp.parseHttpUri(apiBase(c)
                + "/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true&fields=id", "The API base URL");
        warnPlain(ui, uri);
        HttpRequest.Builder b = RemoteHttp.request(uri);
        RemoteHttp.header(b, "Authorization", "Bearer " + c.req("token"));
        RemoteHttp.header(b, "Content-Type", "multipart/related; boundary=" + boundary);
        b.POST(RemoteHttp.body(c, body));
        prog(ui, "Uploading to Google Drive ...");
        Resp r = RemoteHttp.send(c, b.build());
        RemoteHttp.expectOk(c, r);
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"").matcher(r.body);
        String loc = m.find() ? "https://drive.google.com/file/d/" + m.group(1) + "/view" : "Google Drive:" + name;
        return new Result(loc, detail(r, data.length));
    }

    private static Result driveTest(Cfg c, Ui ui) throws RemoteException {
        URI uri = RemoteHttp.parseHttpUri(apiBase(c) + "/drive/v3/about?fields=user", "The API base URL");
        HttpRequest.Builder b = RemoteHttp.request(uri);
        RemoteHttp.header(b, "Authorization", "Bearer " + c.req("token"));
        b.GET();
        Resp r = RemoteHttp.send(c, b.build());
        RemoteHttp.expectOk(c, r);
        String parent = c.trim("parent");
        String extra = "";
        if (!parent.isEmpty()) {
            if (!parent.matches("[A-Za-z0-9_-]{1,100}")) throw new RemoteException("The parent folder ID contains invalid characters.", null);
            URI f = RemoteHttp.parseHttpUri(apiBase(c) + "/drive/v3/files/" + parent + "?fields=id&supportsAllDrives=true", "The API base URL");
            HttpRequest.Builder fb = RemoteHttp.request(f);
            RemoteHttp.header(fb, "Authorization", "Bearer " + c.req("token"));
            fb.GET();
            Resp fr = RemoteHttp.send(c, fb.build());
            RemoteHttp.expectOk(c, fr);
            extra = "; parent folder found";
        }
        return new Result("Google Drive", "Token accepted (HTTP " + r.status + ")" + extra + ". Nothing was uploaded.");
    }

    // =============================================================================================
    // 6. OneDrive / Graph
    // =============================================================================================

    private static final int ONEDRIVE_MAX = 4 * 1024 * 1024;

    private static Result onedriveUpload(Cfg c, String name, byte[] data, Ui ui) throws RemoteException {
        if (data.length > ONEDRIVE_MAX) {
            throw new RemoteException("OneDrive simple upload is limited to 4 MB; this file is "
                    + String.format("%.1f", data.length / 1048576.0) + " MB. Use another destination or a smaller file.", null);
        }
        List<String> segs = RemoteUtil.sanitizeSegments(c.get("folder"));
        segs.add(name);
        String path = RemoteUtil.encodePath(segs);
        URI uri = RemoteHttp.parseHttpUri(apiBase(c) + "/v1.0/me/drive/root:/" + path
                + ":/content?@microsoft.graph.conflictBehavior=rename", "The API base URL");
        warnPlain(ui, uri);
        HttpRequest.Builder b = RemoteHttp.request(uri);
        RemoteHttp.header(b, "Authorization", "Bearer " + c.req("token"));
        RemoteHttp.header(b, "Content-Type", "application/octet-stream");
        b.PUT(RemoteHttp.body(c, data));
        prog(ui, "Uploading to OneDrive /" + String.join("/", segs) + " ...");
        Resp r = RemoteHttp.send(c, b.build());
        RemoteHttp.expectOk(c, r);
        return new Result("onedrive:/" + String.join("/", segs), detail(r, data.length));
    }

    private static Result onedriveTest(Cfg c, Ui ui) throws RemoteException {
        URI uri = RemoteHttp.parseHttpUri(apiBase(c) + "/v1.0/me/drive?$select=id", "The API base URL");
        HttpRequest.Builder b = RemoteHttp.request(uri);
        RemoteHttp.header(b, "Authorization", "Bearer " + c.req("token"));
        b.GET();
        Resp r = RemoteHttp.send(c, b.build());
        RemoteHttp.expectOk(c, r);
        return new Result("onedrive:/" + String.join("/", RemoteUtil.sanitizeSegments(c.get("folder"))),
                "Token accepted (HTTP " + r.status + "). Nothing was uploaded.");
    }

    // =============================================================================================
    // 7. Azure Blob (SAS)
    // =============================================================================================

    private static final String AZURE_VERSION = "2021-08-06";

    private static URI azureUri(Cfg c, String name) throws RemoteException {
        String cont = c.req("container");
        RemoteUtil.noCtl("Container URL", cont);
        if (cont.contains("?") || cont.contains("#")) {
            throw new RemoteException("Enter the container URL without a query string; put the SAS token in its own field.", null);
        }
        while (cont.endsWith("/")) cont = cont.substring(0, cont.length() - 1);
        String sas = c.req("sas");
        if (sas.startsWith("?")) sas = sas.substring(1);
        if (sas.isEmpty() || sas.matches(".*[\\s#].*")) throw new RemoteException("The SAS token is not valid.", null);
        List<String> segs = RemoteUtil.sanitizeSegments(c.get("prefix"));
        segs.add(name);
        return RemoteHttp.parseHttpUri(cont + "/" + RemoteUtil.encodePath(segs) + "?" + sas, "The container URL");
    }

    private static Resp azureCall(Cfg c, URI uri, String method, byte[] data, String ct) throws RemoteException {
        HttpRequest.Builder b = RemoteHttp.request(uri);
        RemoteHttp.header(b, "x-ms-version", AZURE_VERSION);
        if (data != null) {
            RemoteHttp.header(b, "x-ms-blob-type", "BlockBlob");
            RemoteHttp.header(b, "x-ms-blob-content-type", ct);
            RemoteHttp.header(b, "Content-Type", ct);
            b.method(method, RemoteHttp.body(c, data));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return RemoteHttp.send(c, b.build());
    }

    private static Result azureUpload(Cfg c, String name, byte[] data, String ct, Ui ui) throws RemoteException {
        URI uri = azureUri(c, name);
        warnPlain(ui, uri);
        prog(ui, "Uploading to " + RemoteUtil.safeUrl(uri.toString()) + " ...");
        Resp r = azureCall(c, uri, "PUT", data, ct);
        RemoteHttp.expectOk(c, r);
        return new Result(RemoteUtil.safeUrl(uri.toString()), detail(r, data.length));
    }

    private static Result azureTest(Cfg c, Ui ui) throws RemoteException {
        String probe = probeName();
        URI uri = azureUri(c, probe);
        Resp r = azureCall(c, uri, "PUT", PROBE, "text/plain");
        RemoteHttp.expectOk(c, r);
        Resp d = azureCall(c, uri, "DELETE", null, null);
        String del = d.ok() ? "probe deleted" : "probe could NOT be deleted (HTTP " + d.status
                + "; the SAS may lack delete permission), remove " + probe + " manually";
        return new Result(RemoteUtil.safeUrl(uri.toString()).replace(probe, ""), "PUT OK (HTTP " + r.status + "); " + del);
    }
}
