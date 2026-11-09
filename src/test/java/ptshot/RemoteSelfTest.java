package ptshot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import ptshot.RemoteClient.RemoteException;
import ptshot.RemoteClient.Result;

/**
 * Self test for the Remote* library (no JUnit). Run:
 *   javac -encoding UTF-8 --release 17 -cp libs/jsch-2.28.8.jar -d OUT src/main/java/ptshot/Remote*.java src/test/java/ptshot/RemoteSelfTest.java
 *   java -Xmx1g -cp OUT:libs/jsch-2.28.8.jar ptshot.RemoteSelfTest
 * Exit code is non-zero if any check fails. The real-SSH-server test is RemoteSftpServerTest.
 */
public final class RemoteSelfTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }
    static void section(String s) { System.out.println("== " + s); }

    // ------------------------------------------------------------------------------------------------
    // test UI
    // ------------------------------------------------------------------------------------------------
    static class TestUi implements RemoteClient.Ui {
        final List<String> events = new CopyOnWriteArrayList<>();
        final Map<String, String> trusted = new HashMap<>();
        boolean confirm = true;
        boolean lastChanged;
        public void progress(String m) { events.add("progress:" + m); }
        public String trustedHostKey(String hp) { return trusted.get(hp); }
        public boolean confirmHostKey(String hp, String t, String fp, boolean changed) {
            events.add("confirm:" + hp + ":" + t + ":" + fp + ":" + changed);
            lastChanged = changed;
            return confirm;
        }
        public void rememberHostKey(String hp, String fp) { events.add("remember:" + hp); trusted.put(hp, fp); }
    }

    // ------------------------------------------------------------------------------------------------
    // HTTP mock
    // ------------------------------------------------------------------------------------------------
    static final class Req {
        String method, rawPath, rawQuery;
        Map<String, String> h = new HashMap<>();
        byte[] body;
        String hdr(String n) { return h.get(n.toLowerCase()); }
        String bodyStr() { return new String(body, StandardCharsets.UTF_8); }
    }
    static final class Rsp {
        int status = 200; String body = ""; Map<String, String> headers = new HashMap<>();
        Rsp(int s, String b) { status = s; body = b; }
        Rsp header(String k, String v) { headers.put(k, v); return this; }
    }
    static final class Mock {
        final HttpServer server; final int port;
        final List<Req> reqs = new CopyOnWriteArrayList<>();
        volatile Function<Req, Rsp> behaviour = r -> new Rsp(200, "{}");
        Mock(HttpServer s) throws IOException {
            server = s; port = s.getAddress().getPort();
            s.createContext("/", this::handle);
            s.start();
        }
        static Mock http() throws IOException { return new Mock(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)); }
        void handle(HttpExchange ex) throws IOException {
            Req r = new Req();
            r.method = ex.getRequestMethod();
            r.rawPath = ex.getRequestURI().getRawPath();
            r.rawQuery = ex.getRequestURI().getRawQuery();
            ex.getRequestHeaders().forEach((k, v) -> r.h.put(k.toLowerCase(), v.get(0)));
            r.body = ex.getRequestBody().readAllBytes();
            reqs.add(r);
            Rsp rs = behaviour.apply(r);
            byte[] b = rs.body.getBytes(StandardCharsets.UTF_8);
            rs.headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            if (r.method.equals("HEAD")) { ex.sendResponseHeaders(rs.status, -1); }
            else if (b.length == 0) { ex.sendResponseHeaders(rs.status, -1); }
            else { ex.sendResponseHeaders(rs.status, b.length); try (OutputStream o = ex.getResponseBody()) { o.write(b); } }
            ex.close();
        }
        String url() { return "http://127.0.0.1:" + port; }
        Req last() { return reqs.get(reqs.size() - 1); }
        void stop() { server.stop(0); }
    }

    static Map<String, String> cfg(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static RemoteException expectFail(String typeId, Map<String, String> c, byte[] data, RemoteClient.Ui ui) {
        try {
            RemoteClient.upload(typeId, c, "f.png", data, "image/png", ui);
            return null;
        } catch (RemoteException e) { return e; }
    }

    static final byte[] DATA = "PNGDATA\u0000\u0001\u0002binary".getBytes(StandardCharsets.ISO_8859_1);

    // ------------------------------------------------------------------------------------------------
    // main
    // ------------------------------------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("remote-selftest");
        try {
            unitTests();
            sigV4Vectors();
            genericHttp();
            requestTests();
            s3Tests();
            gcsTests();
            dropboxTests();
            driveOneDriveAzure();
            httpsTests(tmp);
            ftpTests(tmp);
            hostKeyRepoTests();
            fsTests(tmp);
            leakTests();
            sizeLimitTests();
        } finally {
            deleteTree(tmp);
        }
        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static void deleteTree(Path p) {
        try (var s = Files.walk(p)) {
            s.sorted(Collections.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------------------------------------
    static void unitTests() {
        section("helpers + spec");
        check("safeUrl strips query/userinfo/fragment",
                RemoteUtil.safeUrl("https://user:pw@h.example:8443/p/a?token=abc&x=1#frag").equals("https://h.example:8443/p/a"));
        check("safeUrl plain", RemoteUtil.safeUrl("https://h/p").equals("https://h/p"));
        check("scrubUrls inside text", RemoteUtil.scrubUrls("fail at https://a:b@h/x?sig=SECRET ok").equals("fail at https://h/x ok"));
        check("sanitizeName traversal", RemoteUtil.sanitizeName("../../etc/passwd").equals("passwd"));
        check("sanitizeName backslash", RemoteUtil.sanitizeName("..\\..\\win\\evil.exe").equals("evil.exe"));
        check("sanitizeName dots", RemoteUtil.sanitizeName("....").equals("__"));
        check("sanitizeName leading dot", RemoteUtil.sanitizeName(".htaccess").equals("htaccess"));
        check("sanitizeName ctl/nul", RemoteUtil.sanitizeName("a\u0000b\r\nc.png").equals("a_b__c.png"));
        check("sanitizeName a..b", RemoteUtil.sanitizeName("a..b.png").equals("a_b.png"));
        check("sanitizeName reserved", RemoteUtil.sanitizeName("CON.txt").equals("_CON.txt"));
        check("sanitizeName length", RemoteUtil.sanitizeName("x".repeat(500) + ".png").length() == 120
                && RemoteUtil.sanitizeName("x".repeat(500) + ".png").endsWith(".png"));
        check("sanitizeName empty", RemoteUtil.sanitizeName(null).equals("upload.bin"));
        check("sanitizeSegments", RemoteUtil.sanitizeSegments("/a/../b/./c d/").equals(List.of("a", "_", "b", "c d")));
        check("encodeSegment", RemoteUtil.encodeSegment("a b/c?d&é~.-_").equals("a%20b%2Fc%3Fd%26%C3%A9~.-_"));
        check("jsonString non-ascii", RemoteUtil.jsonString("é\"\\").equals("\"\\u00e9\\\"\\\\\""));
        Set<String> ids = new HashSet<>();
        boolean specOk = RemoteSpec.types().size() == 11;
        for (RemoteSpec.Type t : RemoteSpec.types()) {
            specOk &= ids.add(t.id) && RemoteSpec.type(t.id) == t;
            Set<String> keys = new HashSet<>();
            for (RemoteSpec.Field f : t.fields) {
                specOk &= keys.add(f.key);
                specOk &= f.kind != RemoteSpec.Kind.PASSWORD || f.secret;
                specOk &= f.kind != RemoteSpec.Kind.CHOICE || f.choices.length > 0;
                specOk &= f.kind != RemoteSpec.Kind.CHOICE || Arrays.asList(f.choices).contains(f.def);
            }
            for (RemoteSpec.Field f : t.fields) {   // showIf must refer to an existing CHOICE and valid values
                if (f.showIf.isEmpty()) continue;
                String[] p = f.showIf.split("=", 2);
                RemoteSpec.Field o = t.field(p[0]);
                specOk &= o != null && o.kind == RemoteSpec.Kind.CHOICE;
                if (o != null) for (String v : p[1].split("\\|")) specOk &= Arrays.asList(o.choices).contains(v);
            }
        }
        check("spec consistent (11 types, unique keys, showIf valid, passwords secret)", specOk);
        check("ftp plaintext flag", RemoteSpec.type("ftp").plaintext && !RemoteSpec.type("sftp").plaintext);
        check("unknown type -> null", RemoteSpec.type("nope") == null);
        check("insecureTls only on http-based types + ftp", RemoteSpec.type("sftp").field("insecureTls") == null
                && RemoteSpec.type("local").field("insecureTls") == null && RemoteSpec.type("s3").field("insecureTls") != null
                && RemoteSpec.type("ftp").field("insecureTls") != null && !RemoteSpec.type("s3").field("insecureTls").def.equals("true"));
        RemoteUtil.Cfg c = new RemoteUtil.Cfg(RemoteSpec.type("http"), cfg("token", "SeCrEt-Token", "auth", "bearer"));
        check("redact secret + url", c.redact("bad SeCrEt-Token at https://u:p@h/x?a=b").equals("bad *** at https://h/x"));
    }

    // ------------------------------------------------------------------------------------------------
    static void sigV4Vectors() {
        section("SigV4 vs AWS published S3 examples");
        String ak = "AKIAIOSFODNN7EXAMPLE", sk = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        String empty = RemoteUtil.hex(RemoteUtil.sha256(new byte[0]));
        // GET Object with Range
        Map<String, String> h = new TreeMap<>();
        h.put("Host", "examplebucket.s3.amazonaws.com"); h.put("Range", "bytes=0-9");
        h.put("x-amz-content-sha256", empty); h.put("x-amz-date", "20130524T000000Z");
        String a = RemoteSigV4.authorization("GET", "/test.txt", "", h, empty, ak, sk, "us-east-1", "s3", "20130524T000000Z");
        check("GET Object signature", a.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"));
        check("GET Object header format", a.equals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + " SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"));
        // PUT Object
        String ph = RemoteUtil.hex(RemoteUtil.sha256("Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8)));
        check("PUT body hash matches AWS doc", ph.equals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072"));
        h = new TreeMap<>();
        h.put("Date", "Fri, 24 May 2013 00:00:00 GMT"); h.put("Host", "examplebucket.s3.amazonaws.com");
        h.put("x-amz-date", "20130524T000000Z"); h.put("x-amz-storage-class", "REDUCED_REDUNDANCY"); h.put("x-amz-content-sha256", ph);
        a = RemoteSigV4.authorization("PUT", "/test%24file.text", "", h, ph, ak, sk, "us-east-1", "s3", "20130524T000000Z");
        check("PUT Object signature", a.endsWith("Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"));
        check("PUT Object signed headers", a.contains("SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class,"));
        // GET Bucket lifecycle
        h = new TreeMap<>();
        h.put("host", "examplebucket.s3.amazonaws.com"); h.put("x-amz-date", "20130524T000000Z"); h.put("x-amz-content-sha256", empty);
        a = RemoteSigV4.authorization("GET", "/", "lifecycle=", h, empty, ak, sk, "us-east-1", "s3", "20130524T000000Z");
        check("GET lifecycle signature", a.endsWith("Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543"));
        // List objects
        a = RemoteSigV4.authorization("GET", "/", "max-keys=2&prefix=J", h, empty, ak, sk, "us-east-1", "s3", "20130524T000000Z");
        check("GET list objects signature", a.endsWith("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"));
    }

    // ------------------------------------------------------------------------------------------------
    static void genericHttp() throws Exception {
        section("generic HTTP");
        Mock m = Mock.http();
        TestUi ui = new TestUi();
        try {
            // raw PUT, no auth, name appended + encoded, traversal neutralised
            Result r = RemoteClient.upload("http", cfg("url", m.url() + "/dav/dir", "insecureTls", "false"),
                    "../../etc/pa ss?wd.png", DATA, "image/png", ui);
            Req q = m.last();
            check("PUT method + encoded sanitised path", q.method.equals("PUT") && q.rawPath.equals("/dav/dir/pa%20ss_wd.png"));
            check("raw body + content-type", Arrays.equals(q.body, DATA) && "image/png".equals(q.hdr("content-type")));
            check("no auth header when none", q.hdr("authorization") == null && q.hdr("cookie") == null);
            check("result location", r.location.equals(m.url() + "/dav/dir/pa%20ss_wd.png") && r.detail.contains("200"));
            check("plaintext warning emitted", ui.events.stream().anyMatch(e -> e.contains("unencrypted")));

            // placeholder + query kept
            RemoteClient.upload("http", cfg("url", m.url() + "/up/{name}?v=1", "method", "POST"), "a b.txt", DATA, null, null);
            q = m.last();
            check("placeholder + POST + default content-type", q.method.equals("POST") && q.rawPath.equals("/up/a%20b.txt")
                    && "v=1".equals(q.rawQuery) && "application/octet-stream".equals(q.hdr("content-type")));

            // basic
            RemoteClient.upload("http", cfg("url", m.url() + "/", "auth", "basic", "user", "al", "password", "p:w d"), "x.png", DATA, "image/png", null);
            check("basic auth header", ("Basic " + Base64.getEncoder().encodeToString("al:p:w d".getBytes())).equals(m.last().hdr("authorization")));
            RemoteClient.upload("http", cfg("url", m.url() + "/", "auth", "bearer", "token", "TTT"), "x.png", DATA, "image/png", null);
            check("bearer header", "Bearer TTT".equals(m.last().hdr("authorization")));
            RemoteClient.upload("http", cfg("url", m.url() + "/", "auth", "apikey-header", "keyName", "X-Api-Key", "keyValue", "KKK"), "x.png", DATA, "image/png", null);
            check("api key header", "KKK".equals(m.last().hdr("x-api-key")));
            RemoteClient.upload("http", cfg("url", m.url() + "/", "auth", "apikey-query", "keyName", "api key", "keyValue", "K&=1"), "x.png", DATA, "image/png", null);
            check("api key query encoded", "api%20key=K%26%3D1".equals(m.last().rawQuery));
            RemoteClient.upload("http", cfg("url", m.url() + "/", "auth", "cookie", "cookie", "sid=1; b=2"), "x.png", DATA, "image/png", null);
            check("cookie header", "sid=1; b=2".equals(m.last().hdr("cookie")));
            RemoteClient.upload("http", cfg("url", m.url() + "/", "headers", "X-One: 1\n\nX-Two:  two words \r\n"), "x.png", DATA, "image/png", null);
            check("extra headers", "1".equals(m.last().hdr("x-one")) && "two words".equals(m.last().hdr("x-two")));

            // multipart
            RemoteClient.upload("http", cfg("url", m.url() + "/form", "method", "POST", "body", "multipart/form-data", "multipartField", "upload"),
                    "sh\"ot.png", DATA, "image/png", null);
            q = m.last();
            String ct = q.hdr("content-type");
            String bs = new String(q.body, StandardCharsets.ISO_8859_1);
            check("multipart content-type boundary", ct.startsWith("multipart/form-data; boundary="));
            String boundary = ct.substring(ct.indexOf("boundary=") + 9);
            check("multipart structure", bs.startsWith("--" + boundary + "\r\nContent-Disposition: form-data; name=\"upload\"; filename=\"sh_ot.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n") && bs.contains(new String(DATA, StandardCharsets.ISO_8859_1))
                    && bs.endsWith("\r\n--" + boundary + "--\r\n"));

            // injection / validation
            check("CRLF in header value rejected", expectFail("http", cfg("url", m.url() + "/", "auth", "bearer", "token", "a\r\nX-Evil: 1"), DATA, null) != null);
            check("CRLF in key name rejected", expectFail("http", cfg("url", m.url() + "/", "auth", "apikey-header", "keyName", "X\r\nEvil", "keyValue", "v"), DATA, null) != null);
            check("bad header line rejected", expectFail("http", cfg("url", m.url() + "/", "headers", "nocolon"), DATA, null) != null);
            RemoteClient.upload("http", cfg("url", m.url() + "/", "headers", "Host: evil.example"), "x.png", DATA, null, null);
            check("restricted header (Host) is ignored, real Host kept", m.last().hdr("host").startsWith("127.0.0.1:"));
            StringBuilder many = new StringBuilder();
            for (int i = 0; i < 21; i++) many.append("X-H").append(i).append(": v\n");
            check("more than 20 headers rejected", expectFail("http", cfg("url", m.url() + "/", "headers", many.toString()), DATA, null) != null);
            for (String bad : new String[]{"file:///etc/passwd", "ftp://h/x", "jar:file:///a!/b", "gopher://h", "//h/x", "/relative", "https://u:p@h/x"}) {
                check("scheme/URL rejected: " + bad, expectFail("http", cfg("url", bad), DATA, null) != null);
            }
            check("missing URL", expectFail("http", cfg(), DATA, null) != null);

            // errors: HTTP status + body summary
            m.behaviour = x -> new Rsp(403, "Denied\u0000\r\n" + "x".repeat(500));
            RemoteException e = expectFail("http", cfg("url", m.url() + "/"), DATA, null);
            check("403 reports status and short body", e != null && e.getMessage().contains("HTTP 403") && e.getMessage().length() < 300
                    && !e.getMessage().contains("\u0000") && !e.getMessage().contains("\n"));

            // redirect not followed
            Mock leak = Mock.http();
            try {
                m.behaviour = x -> new Rsp(302, "").header("Location", leak.url() + "/steal?s=1");
                e = expectFail("http", cfg("url", m.url() + "/", "auth", "bearer", "token", "REDIR-SECRET-TOKEN",
                        "headers", "Cookie: REDIR-COOKIE-SECRET"), DATA, null);
                check("3xx is an error naming only the host", e != null && e.getMessage().contains("HTTP 302") && e.getMessage().contains("127.0.0.1")
                        && !e.getMessage().contains("/steal") && !e.getMessage().contains("?s=1"));
                check("redirect target never contacted (no credential leak)", leak.reqs.isEmpty());
            } finally { leak.stop(); }
            m.behaviour = x -> new Rsp(200, "");

            // test(): PUT probe then DELETE
            m.reqs.clear();
            Result t = RemoteClient.test("http", cfg("url", m.url() + "/dav/"), null);
            check("test() PUT probe + DELETE", m.reqs.size() == 2 && m.reqs.get(0).method.equals("PUT") && m.reqs.get(1).method.equals("DELETE")
                    && m.reqs.get(0).rawPath.equals(m.reqs.get(1).rawPath) && t.detail.contains("deleted"));
            m.reqs.clear();
            RemoteClient.test("http", cfg("url", m.url() + "/form", "method", "POST"), null);
            check("test() with POST only sends HEAD", m.reqs.size() == 1 && m.reqs.get(0).method.equals("HEAD"));
            m.behaviour = x -> new Rsp(401, "no");
            check("test() 401 fails", expectTest("http", cfg("url", m.url() + "/dav/")) != null);
        } finally { m.stop(); }
    }

    // ------------------------------------------------------------------------------------------------
    // round 2: nameMode, params, request view, sendRequestText
    // ------------------------------------------------------------------------------------------------
    static Map<String, String> base(Mock m, String... kv) {
        Map<String, String> c = cfg("url", m.url() + "/api/upload", "nameMode", "never in URL");
        for (int i = 0; i < kv.length; i += 2) c.put(kv[i], kv[i + 1]);
        return c;
    }

    static String normB(String s) { return s.replaceAll("----BurpShot[0-9a-f]+", "BOUNDARY"); }

    static RemoteException sendTextFail(Map<String, String> c, String text) {
        try { RemoteClient.sendRequestText("http", c, text, DATA, null); return null; } catch (RemoteException e) { return e; }
    }

    static void roundTrip(Mock m, String label, Map<String, String> c) throws Exception {
        m.reqs.clear();
        RemoteClient.upload("http", c, "f.png", DATA, "image/png", null);
        Req a = m.last();
        RemoteClient.RequestPreview p = RemoteClient.previewRequest("http", c, "f.png", DATA.length, "image/png");
        RemoteClient.Result r = RemoteClient.sendRequestText("http", c, p.text, DATA, null);
        Req b = m.last();
        Map<String, String> ha = new TreeMap<>(a.h), hb = new TreeMap<>(b.h);
        ha.remove("host"); hb.remove("host");
        ha.replaceAll((k, v) -> normB(v)); hb.replaceAll((k, v) -> normB(v));
        check("round trip " + label + ": method/path/query", a.method.equals(b.method) && a.rawPath.equals(b.rawPath)
                && java.util.Objects.equals(a.rawQuery, b.rawQuery));
        check("round trip " + label + ": headers identical (incl. Content-Length)", ha.equals(hb));
        check("round trip " + label + ": body bytes identical",
                normB(new String(a.body, StandardCharsets.ISO_8859_1)).equals(normB(new String(b.body, StandardCharsets.ISO_8859_1))));
        check("round trip " + label + ": preview Content-Length == received", p.text.contains("\nContent-Length: " + b.hdr("content-length") + "\n"));
        String ct = b.hdr("content-length") == null ? "" : b.hdr("content-type");
        if (ct.startsWith("multipart/")) {
            String bd = ct.substring(ct.indexOf("boundary=") + 9);
            check("round trip " + label + ": sent boundary is the one shown in the preview", p.text.contains(bd)
                    && new String(b.body, StandardCharsets.ISO_8859_1).startsWith("--" + bd + "\r\n"));
        }
    }

    static void requestTests() throws Exception {
        section("round 2: nameMode / params / request view / sendRequestText");
        Mock m = Mock.http();
        try {
            // ---- nameMode
            Map<String, String> c = cfg("url", m.url() + "/api/upload");
            RemoteClient.upload("http", c, "f n.png", DATA, null, null);
            check("nameMode missing (old profile) = append", m.last().rawPath.equals("/api/upload/f%20n.png"));
            c.put("nameMode", "append to URL path");
            RemoteClient.upload("http", c, "f n.png", DATA, null, null);
            check("nameMode append", m.last().rawPath.equals("/api/upload/f%20n.png"));
            c.put("url", m.url() + "/api/{name}/x");
            RemoteClient.upload("http", c, "f n.png", DATA, null, null);
            check("append + {name} substitutes only", m.last().rawPath.equals("/api/f%20n.png/x"));
            c.put("nameMode", "only where {name} is");
            RemoteClient.upload("http", c, "f n.png", DATA, null, null);
            check("only-where + {name} substitutes", m.last().rawPath.equals("/api/f%20n.png/x"));
            c.put("url", m.url() + "/api/upload");
            RemoteClient.upload("http", c, "f n.png", DATA, null, null);
            check("only-where without {name} leaves URL untouched", m.last().rawPath.equals("/api/upload"));
            c.put("nameMode", "never in URL");
            RemoteClient.upload("http", c, "f n.png", DATA, null, null);
            check("never leaves URL untouched", m.last().rawPath.equals("/api/upload"));
            int n0 = m.reqs.size();
            c.put("url", m.url() + "/api/{name}");
            check("never: {name} is not substituted (literal braces make the URL invalid)", expectFail("http", c, DATA, null) != null && m.reqs.size() == n0);

            // ---- params as query string
            c = base(m, "url", m.url() + "/api/upload?x=1", "params", "project=42\n# comment\n\nempty=\nlabel=a=b c&d\nfn={name}\n  \n");
            RemoteClient.upload("http", c, "f n.png", DATA, null, null);
            check("params query: existing query kept, encoded, comments/blank skipped, split on first '=', {name} substituted",
                    "x=1&project=42&empty=&label=a%3Db%20c%26d&fn=f%20n.png".equals(m.last().rawQuery));
            c = base(m, "params", "a=1");
            RemoteClient.upload("http", c, "f.png", DATA, null, null);
            check("params query on URL without query", "a=1".equals(m.last().rawQuery));
            c = base(m, "params", "q=é");
            RemoteClient.upload("http", c, "f.png", DATA, null, null);
            check("params UTF-8 percent-encoded", "q=%C3%A9".equals(m.last().rawQuery));

            // ---- params as multipart form fields
            c = base(m, "body", "multipart/form-data", "paramsIn", "multipart form fields", "params", "project=42\ndesc=hello {name}\nempty=");
            RemoteClient.upload("http", c, "sh.png", DATA, "image/png", null);
            Req q = m.last();
            String ct = q.hdr("content-type"), bd = ct.substring(ct.indexOf("boundary=") + 9), bs = new String(q.body, StandardCharsets.ISO_8859_1);
            check("form fields: no query string", q.rawQuery == null);
            check("form fields before the file part, valid boundary",
                    bs.startsWith("--" + bd + "\r\nContent-Disposition: form-data; name=\"project\"\r\n\r\n42\r\n"
                            + "--" + bd + "\r\nContent-Disposition: form-data; name=\"desc\"\r\n\r\nhello sh.png\r\n"
                            + "--" + bd + "\r\nContent-Disposition: form-data; name=\"empty\"\r\n\r\n\r\n"
                            + "--" + bd + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sh.png\"\r\nContent-Type: image/png\r\n\r\n")
                            && bs.endsWith("\r\n--" + bd + "--\r\n") && bd.matches("----BurpShot[0-9a-f]{24}"));
            RemoteException e = expectFail("http", base(m, "paramsIn", "multipart form fields", "params", "a=1"), DATA, null);
            check("form fields with raw body -> clear error", e != null && e.getMessage().contains("multipart/form-data"));
            check("params limit: 30 ok", expectFail("http", base(m, "params", genParams(30)), DATA, null) == null);
            e = expectFail("http", base(m, "params", genParams(31)), DATA, null);
            check("params limit: 31 rejected", e != null && e.getMessage().contains("30"));
            check("params: CR in value rejected", expectFail("http", base(m, "params", "a=1\rb"), DATA, null) != null);
            check("params: CR in name rejected", expectFail("http", base(m, "params", "a\rb=1"), DATA, null) != null);
            check("params: NUL rejected", expectFail("http", base(m, "params", "a=1\u0000"), DATA, null) != null);
            check("params: empty name rejected", expectFail("http", base(m, "params", "=1"), DATA, null) != null);
            check("params: line without '=' rejected", expectFail("http", base(m, "params", "novalue"), DATA, null) != null);
            RemoteClient.upload("http", base(m, "headers", "X-Fn: file-{name}"), "my f.png", DATA, null, null);
            check("{name} substituted in header values (raw)", "file-my f.png".equals(m.last().hdr("x-fn")));

            // ---- preview: exact format
            c = base(m, "params", "project=42", "auth", "bearer", "token", "TOKENVALUE123", "headers", "X-Custom: value");
            RemoteClient.RequestPreview p = RemoteClient.previewRequest("http", c, "shot.png", 123456, "image/png");
            String host = m.url().substring(7);
            check("preview exact format (raw)", p.editable && p.text.equals("PUT " + m.url() + "/api/upload?project=42 HTTP/1.1\nHost: " + host
                    + "\nContent-Type: image/png\nContent-Length: 123456\nAuthorization: Bearer {{secret:token}}\nX-Custom: value\n\n" + RemoteClient.FILE_MARK));
            check("preview has no secret values", !p.text.contains("TOKENVALUE123") && p.note.contains("Content-Length"));
            String[][] auths = {
                    {"basic", "user", "al", "password", "pw", "Authorization: Basic {{secret:basic}}\n"},
                    {"bearer", "token", "T0KEN1", "", "", "Authorization: Bearer {{secret:token}}\n"},
                    {"apikey-header", "keyName", "X-K", "keyValue", "KV12345", "X-K: {{secret:keyValue}}\n"},
                    {"cookie", "cookie", "s=COOK123", "", "", "Cookie: {{secret:cookie}}\n"}};
            for (String[] a : auths) {
                Map<String, String> ac = base(m, "auth", a[0], a[1], a[2]);
                if (!a[3].isEmpty()) ac.put(a[3], a[4]);
                String t = RemoteClient.previewRequest("http", ac, "f.png", 5, null).text;
                check("preview token for auth " + a[0], t.contains("\n" + a[5]) && !t.contains(a[2].equals("al") ? "YWw6" : a[2] + "x") && !t.contains("COOK123") && !t.contains("KV12345") && !t.contains("T0KEN1"));
            }
            String t = RemoteClient.previewRequest("http", base(m, "auth", "apikey-query", "keyName", "api key", "keyValue", "KV12345", "params", "a=1"), "f.png", 5, null).text;
            check("preview token for apikey-query inside URL", t.startsWith("PUT " + m.url() + "/api/upload?a=1&api%20key={{secret:keyValue}} HTTP/1.1\n"));

            // ---- preview: multipart format + content-length against the server
            c = base(m, "body", "multipart/form-data", "paramsIn", "multipart form fields", "params", "p=1");
            RemoteClient.upload("http", c, "f.png", DATA, "image/png", null);
            p = RemoteClient.previewRequest("http", c, "f.png", DATA.length, "image/png");
            String recvLen = m.last().hdr("content-length");
            check("preview multipart Content-Length matches what the server received", p.text.contains("\nContent-Length: " + recvLen + "\n"));
            check("preview multipart body shows parts and FILE_MARK",
                    p.text.contains("\n\n------BurpShot") && p.text.contains("name=\"p\"\n\n1\n") && p.text.contains("filename=\"f.png\"\nContent-Type: image/png\n\n" + RemoteClient.FILE_MARK + "\n--")
                            && p.text.endsWith("--\n") && !p.text.contains("\r"));
            // ---- preview: other types
            RemoteClient.RequestPreview sp = RemoteClient.previewRequest("s3", cfg("auth", "access key", "endpoint", m.url(), "bucket", "bk", "accessKey", "AKIDEXAMPLE",
                    "secretKey", "SECRETKEY-VALUE-1", "sessionToken", "SESSTOKEN-VALUE-2"), "a b.png", 4242, "image/png");
            check("s3 preview: not editable, masked signature, FILE_MARK, real Content-Length",
                    !sp.editable && sp.text.startsWith("PUT " + m.url() + "/bk/a%20b.png HTTP/1.1\nHost: " + host + "\n") && sp.text.contains("Signature=***")
                            && sp.text.contains("Credential=AKIDEXAMPLE/") && sp.text.contains("Content-Length: 4242\n") && sp.text.endsWith("\n\n" + RemoteClient.FILE_MARK)
                            && !sp.text.contains("SECRETKEY-VALUE-1") && !sp.text.contains("SESSTOKEN-VALUE-2") && sp.text.contains("{{secret:sessionToken}}") && sp.note.contains("cannot be edited"));
            String[][] others = {{"dropbox", "token", "DBXTOKEN-1"}, {"gdrive", "token", "GDTOKEN-1"}, {"onedrive", "token", "ODTOKEN-1"}};
            for (String[] o : others) {
                RemoteClient.RequestPreview op = RemoteClient.previewRequest(o[0], cfg(o[1], o[2], "apiBase", m.url()), "f.png", 100, null);
                check(o[0] + " preview: not editable, token masked", !op.editable && !op.text.contains(o[2]) && op.text.contains("{{secret:token}}"));
            }
            RemoteClient.RequestPreview ap = RemoteClient.previewRequest("azure", cfg("container", m.url() + "/c", "sas", "?sv=2021&sig=AZSIGNATURE%2Fx"), "f.png", 100, null);
            check("azure preview: SAS masked", !ap.editable && !ap.text.contains("AZSIGNATURE") && ap.text.contains("{{secret:sas}}"));
            RemoteClient.RequestPreview gp = RemoteClient.previewRequest("gcs", cfg("auth", "hmac", "bucket", "b", "accessId", "GOOGID", "secretKey", "GCSHMACSECRET", "apiBase", m.url()), "f.png", 100, null);
            check("gcs hmac preview: signature masked", gp.text.contains("Signature=***") && !gp.text.contains("GCSHMACSECRET"));
            for (String id : new String[]{"ftp", "sftp", "share", "local"}) {
                RemoteException pe = null;
                try { RemoteClient.previewRequest(id, cfg(), "f.png", 1, null); } catch (RemoteException x) { pe = x; }
                check("preview throws for " + id, pe != null && pe.getMessage().equals("This destination does not use an HTTP request.") && !RemoteClient.hasRequestView(id));
            }
            check("hasRequestView for http-based types", RemoteClient.hasRequestView("http") && RemoteClient.hasRequestView("s3") && RemoteClient.hasRequestView("azure"));

            // ---- ROUND TRIP
            Map<String, String> authCfg;
            for (boolean mp : new boolean[]{false, true}) {
                for (String[] a : new String[][]{{"none"}, {"basic", "user", "al", "password", "p w"}, {"bearer", "token", "TOK-1234"},
                        {"apikey-header", "keyName", "X-K", "keyValue", "K-1234"}, {"apikey-query", "keyName", "k", "keyValue", "K&1234"},
                        {"cookie", "cookie", "s=C-1234"}}) {
                    authCfg = base(m, "auth", a[0], "params", "p=1\nq={name}", "headers", "X-Custom: v {name}");
                    for (int i = 1; i + 1 < a.length; i += 2) authCfg.put(a[i], a[i + 1]);
                    if (mp) { authCfg.put("body", "multipart/form-data"); authCfg.put("paramsIn", "multipart form fields"); }
                    roundTrip(m, a[0] + (mp ? "/multipart" : "/raw"), authCfg);
                }
            }
            roundTrip(m, "POST+nameMode append", cfg("url", m.url() + "/dir", "method", "POST"));

            // ---- edits arrive as edited
            c = base(m, "auth", "bearer", "token", "TOK-EDIT-1");
            String text = RemoteClient.previewRequest("http", c, "f.png", DATA.length, "image/png").text;
            String edited = text.replace("PUT ", "POST ").replace("/api/upload", "/api/changed?z=9").replace("\nContent-Type", "\nX-Edit: yes\nContent-Type")
                    .replace(RemoteClient.FILE_MARK, "pre-" + RemoteClient.FILE_MARK + "-post");
            RemoteClient.Result er = RemoteClient.sendRequestText("http", c, edited, DATA, null);
            q = m.last();
            check("edited method/path/query/header/body arrive", q.method.equals("POST") && q.rawPath.equals("/api/changed") && "z=9".equals(q.rawQuery)
                    && "yes".equals(q.hdr("x-edit")) && "Bearer TOK-EDIT-1".equals(q.hdr("authorization")) && "image/png".equals(q.hdr("content-type"))
                    && Arrays.equals(q.body, concat("pre-".getBytes(), DATA, "-post".getBytes())));
            check("Result location has no query", er.location.equals(m.url() + "/api/changed"));
            er = RemoteClient.sendRequestText("http", c, text.replace(RemoteClient.FILE_MARK, "just text\nline2"), DATA, null);
            check("deleting FILE_MARK sends no file, says so", er.detail.contains("file not included") && new String(m.last().body).equals("just text\nline2"));
            e = sendTextFail(c, text.replace(RemoteClient.FILE_MARK, RemoteClient.FILE_MARK + RemoteClient.FILE_MARK));
            check("two FILE_MARKs rejected", e != null && e.getMessage().contains("more than once"));
            // multipart CRLF conversion, byte exact
            Map<String, String> mc = base(m, "body", "multipart/form-data", "paramsIn", "multipart form fields", "params", "a=b");
            String mt = RemoteClient.previewRequest("http", mc, "f.png", DATA.length, "image/png").text;
            check("multipart preview text has only LF", !mt.contains("\r"));
            for (String variant : new String[]{mt, mt.replace("\n", "\r\n")}) {
                RemoteClient.sendRequestText("http", mc, variant, DATA, null);
                q = m.last();
                String b = new String(q.body, StandardCharsets.ISO_8859_1);
                String bnd = q.hdr("content-type").substring(q.hdr("content-type").indexOf("boundary=") + 9);
                String expect = "--" + bnd + "\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nb\r\n--" + bnd
                        + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f.png\"\r\nContent-Type: image/png\r\n\r\n"
                        + new String(DATA, StandardCharsets.ISO_8859_1) + "\r\n--" + bnd + "--\r\n";
                check("multipart CRLF conversion byte-exact (" + (variant == mt ? "LF" : "CRLF") + " input)", b.equals(expect));
            }
            RemoteClient.sendRequestText("http", c, text.replace(RemoteClient.FILE_MARK, "a\nb\r\nc"), DATA, null);
            check("non-multipart body text left unchanged (CRLF normalised to LF by the parser)", new String(m.last().body).equals("a\nb\nc"));
            er = RemoteClient.sendRequestText("http", c, text.replace("\nContent-Type", "\nHost: evil.example\nConnection: close\nTE: trailers\nContent-Length: 5\nContent-Type"), DATA, null);
            check("managed headers ignored and reported", m.last().hdr("host").startsWith("127.0.0.1:") && er.detail.contains("ignored")
                    && er.detail.contains("Host") && er.detail.contains("Content-Length") && !"close".equals(m.last().hdr("connection")) && Arrays.equals(m.last().body, DATA));

            // ---- security of sendRequestText
            String ok = "PUT " + m.url() + "/x HTTP/1.1\nContent-Type: a/b\n\n" + RemoteClient.FILE_MARK;
            check("sanity: minimal request works", sendTextFail(c, ok) == null);
            int before = m.reqs.size();
            String[] bad = {
                    ok.replace("a/b", "a\rX-Evil: 1"), ok.replace("a/b", "a\u0000b"), ok.replace("a/b", "a\u0001b"),
                    ok.replace("a/b", "x".repeat(40000)), ok.replace("PUT ", "DELETE "), ok.replace("PUT ", "GET "), ok.replace("PUT ", "put "),
                    ok.replace("PUT " + m.url(), "PUT file:///etc/passwd"), ok.replace("PUT " + m.url(), "PUT ftp://h/x"), ok.replace("PUT " + m.url(), "PUT jar:file:///a!/b"),
                    ok.replace("PUT " + m.url(), "PUT http://user:pw@127.0.0.1:" + m.port), ok.replace("PUT " + m.url(), "PUT /relative"),
                    ok.replace("PUT " + m.url(), "PUT " + m.url() + "/" + "a".repeat(9000)), ok.replace(" HTTP/1.1", ""), ok.replace(" HTTP/1.1", " HTTP/2"),
                    ok.replace("Content-Type: a/b", "Bad Name: v"), ok.replace("Content-Type: a/b", "nocolon"), "", "\n" + ok, ok.replace("Content-Type: a/b", " folded: v"),
                    ok.replace("PUT ", "PUT  "), ok.replace(" HTTP/1.1\n", " HTTP/1.1 \n")};
            int bi = 0, badOk = 0;
            for (String b : bad) { bi++; if (sendTextFail(c, b) != null) badOk++; else System.out.println("    accepted bad request #" + bi); }
            check("all " + bad.length + " malformed/injection/scheme/method variants rejected", badOk == bad.length && m.reqs.size() == before + 0);
            StringBuilder many = new StringBuilder(ok.substring(0, ok.indexOf("\n\n")));
            for (int i = 0; i < 61; i++) many.append("\nX-H").append(i).append(": v");
            check("61 headers rejected", sendTextFail(c, many + "\n\n" + RemoteClient.FILE_MARK) != null);
            check("non-http type rejected by sendRequestText", expectSendType("s3", c, ok));
            // unknown token, no secret in message
            e = sendTextFail(c, ok.replace("a/b", "{{secret:nosuch}}"));
            check("unknown token named, nothing else", e != null && e.getMessage().contains("nosuch") && !e.getMessage().contains("TOK-EDIT-1"));
            e = sendTextFail(c, ok.replace("a/b", "{{secret:url}}"));
            check("non-secret field is not a valid token", e != null && e.getMessage().contains("Unknown placeholder"));
            e = sendTextFail(base(m, "auth", "bearer", "token", "bad\r\nvalue123"), ok.replace("a/b", "{{secret:token}}"));
            check("secret value with CR/LF rejected without echoing it", e != null && !e.getMessage().contains("value123"));
            // token substituted into the URL is percent-encoded
            Map<String, String> tc = base(m, "auth", "bearer", "token", "T/ok en&1");
            RemoteClient.sendRequestText("http", tc, "PUT " + m.url() + "/x?t={{secret:token}} HTTP/1.1\nAuthorization: Bearer {{secret:token}}\n\n" + RemoteClient.FILE_MARK, DATA, null);
            check("token in URL percent-encoded, in header raw", "t=T%2Fok%20en%261".equals(m.last().rawQuery) && "Bearer T/ok en&1".equals(m.last().hdr("authorization")));
            // 3xx not followed
            Mock leak = Mock.http();
            try {
                m.behaviour = x -> new Rsp(307, "").header("Location", leak.url() + "/steal");
                e = sendTextFail(base(m, "auth", "bearer", "token", "REDIR-TOKEN-1"), "PUT " + m.url() + "/x HTTP/1.1\nAuthorization: Bearer {{secret:token}}\n\n" + RemoteClient.FILE_MARK);
                check("sendRequestText: 3xx is an error, target never contacted", e != null && e.getMessage().contains("HTTP 307") && leak.reqs.isEmpty() && !e.getMessage().contains("REDIR-TOKEN-1"));
            } finally { leak.stop(); }
            // failing server echoing everything: no secret in message (incl. values that only exist via token substitution)
            String SEC = "SUBST-SECRET-9876", HDRV = "typed-header-secret-55";
            m.behaviour = x -> new Rsp(500, "echo " + x.rawPath + "?" + x.rawQuery + " " + x.hdr("authorization") + " " + x.hdr("x-typed") + " " + x.hdr("cookie"));
            Map<String, String> lc = base(m, "auth", "bearer", "token", SEC, "cookie", "k=COOKIE-SECRET-777");
            e = sendTextFail(lc, "PUT " + m.url() + "/x?t={{secret:token}}&u=typed-query-secret-4 HTTP/1.1\nAuthorization: Bearer {{secret:token}}\nCookie: {{secret:cookie}}\nX-Typed: " + HDRV + "\n\n" + RemoteClient.FILE_MARK);
            check("server error: status shown, no secrets (substituted, typed header, typed query)", e != null && e.getMessage().contains("HTTP 500")
                    && noSecrets(e.getMessage(), new String[]{SEC, "SUBST-SECRET-9876", "COOKIE-SECRET-777", HDRV, "typed-query-secret-4", "?t=", "&u="}));
            m.behaviour = x -> new Rsp(200, "{}");

            m.reqs.clear();
            RemoteClient.test("http", base(m), null);
            check("test() with 'never in URL' only sends HEAD (never overwrites the fixed endpoint)", m.reqs.size() == 1 && m.reqs.get(0).method.equals("HEAD"));

            // ---- mask / reveal
            Map<String, String> mr = cfg("auth", "basic", "user", "alice", "password", "hunter2hunter2", "token", "TOKEN-ABCDEF", "keyValue", "short", "headers", "X-A: not-masked-header-value");
            String b64 = Base64.getEncoder().encodeToString("alice:hunter2hunter2".getBytes());
            String plain = "Authorization: Basic " + b64 + "\nX: TOKEN-ABCDEF and hunter2hunter2 and short and not-masked-header-value";
            String masked = RemoteClient.maskSecrets(plain, mr);
            check("maskSecrets: basic, token, password masked; short (<6) and headers field not", masked.equals("Authorization: Basic {{secret:basic}}\nX: {{secret:token}} and {{secret:password}} and short and not-masked-header-value"));
            check("revealSecrets inverts maskSecrets", RemoteClient.revealSecrets(masked, mr).equals(plain));
            check("reveal leaves unknown tokens alone", RemoteClient.revealSecrets("{{secret:zzz}}", mr).equals("{{secret:zzz}}"));
        } finally { m.stop(); }
    }

    static boolean expectSendType(String type, Map<String, String> c, String text) {
        try { RemoteClient.sendRequestText(type, c, text, DATA, null); return false; } catch (RemoteException e) { return true; }
    }

    static String genParams(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("p").append(i).append("=v\n");
        return sb.toString();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static RemoteException expectTest(String id, Map<String, String> c) {
        try { RemoteClient.test(id, c, null); return null; } catch (RemoteException e) { return e; }
    }

    // ------------------------------------------------------------------------------------------------
    /** Server-side SigV4 verification (recomputes the signature from what was actually received). */
    static boolean verifySig(Req r, String ak, String sk, String region, String service) {
        String auth = r.hdr("authorization");
        if (auth == null) return false;
        Matcher m = Pattern.compile("AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/([^/]+)/([^/]+)/aws4_request, SignedHeaders=([^,]+), Signature=([0-9a-f]{64})").matcher(auth);
        if (!m.matches() || !m.group(1).equals(ak) || !m.group(3).equals(region) || !m.group(4).equals(service)) return false;
        Map<String, String> hs = new TreeMap<>();
        for (String n : m.group(5).split(";")) hs.put(n, r.hdr(n));
        if (hs.containsValue(null)) return false;
        String payload = RemoteUtil.hex(RemoteUtil.sha256(r.body));
        if (!payload.equals(r.hdr("x-amz-content-sha256"))) return false;
        String expect = RemoteSigV4.authorization(r.method, r.rawPath, r.rawQuery == null ? "" : r.rawQuery, hs, payload, ak, sk, region, service, r.hdr("x-amz-date"));
        return expect.equals(auth);
    }

    static void s3Tests() throws Exception {
        section("S3");
        Mock m = Mock.http();
        String ak = "AKIAIOSFODNN7EXAMPLE", sk = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        try {
            Result r = RemoteClient.upload("s3", cfg("auth", "access key", "endpoint", m.url(), "region", "eu-west-1", "bucket", "my-bucket",
                    "prefix", "shots/2024 x/", "accessKey", ak, "secretKey", sk, "sessionToken", "SESSTOK"), "a b$.png", DATA, "image/png", null);
            Req q = m.last();
            check("S3 PUT path-style key encoded", q.method.equals("PUT") && q.rawPath.equals("/my-bucket/shots/2024%20x/a%20b%24.png"));
            check("S3 signature verified server-side", verifySig(q, ak, sk, "eu-west-1", "s3"));
            check("S3 session token header + signed", "SESSTOK".equals(q.hdr("x-amz-security-token")) && q.hdr("authorization").contains("x-amz-security-token"));
            check("S3 body intact", Arrays.equals(q.body, DATA));
            check("S3 location has no secrets", !r.location.contains("SESSTOK") && !r.location.contains(sk));
            // wrong secret must not verify (sanity of the verifier)
            check("verifier rejects wrong secret", !verifySig(q, ak, sk + "x", "eu-west-1", "s3"));

            m.reqs.clear();
            Result t = RemoteClient.test("s3", cfg("auth", "access key", "endpoint", m.url(), "bucket", "bkt", "accessKey", ak, "secretKey", sk), null);
            check("S3 test: signed PUT + DELETE", m.reqs.size() == 2 && m.reqs.get(0).method.equals("PUT") && m.reqs.get(1).method.equals("DELETE")
                    && verifySig(m.reqs.get(0), ak, sk, "us-east-1", "s3") && verifySig(m.reqs.get(1), ak, sk, "us-east-1", "s3"));

            RemoteClient.upload("s3", cfg("auth", "none", "endpoint", m.url(), "bucket", "pub"), "x.png", DATA, "image/png", null);
            check("S3 unsigned", m.last().hdr("authorization") == null && m.last().rawPath.equals("/pub/x.png"));

            RemoteClient.upload("s3", cfg("auth", "pre-signed URL", "presignedUrl", m.url() + "/b/k.png?X-Amz-Signature=abc&X-Amz-Expires=60"),
                    "ignored.png", DATA, "image/png", null);
            q = m.last();
            check("S3 pre-signed: exact URL, PUT, no Authorization", q.method.equals("PUT") && q.rawPath.equals("/b/k.png")
                    && "X-Amz-Signature=abc&X-Amz-Expires=60".equals(q.rawQuery) && q.hdr("authorization") == null);
            m.behaviour = x -> new Rsp(403, "<Error><Code>SignatureDoesNotMatch</Code></Error>");
            RemoteException e = expectFail("s3", cfg("auth", "pre-signed URL", "presignedUrl", m.url() + "/b/k.png?X-Amz-Signature=abcdef"), DATA, null);
            check("S3 pre-signed error hides query", e != null && !e.getMessage().contains("abcdef") && e.getMessage().contains("403"));
            check("S3 missing keys", expectFail("s3", cfg("auth", "access key", "endpoint", m.url(), "bucket", "b"), DATA, null) != null);
            check("S3 bad bucket", expectFail("s3", cfg("auth", "none", "endpoint", m.url(), "bucket", "a/b"), DATA, null) != null);
            check("S3 endpoint with path rejected", expectFail("s3", cfg("auth", "none", "endpoint", m.url() + "/x", "bucket", "b"), DATA, null) != null);
        } finally { m.stop(); }
    }

    static void gcsTests() throws Exception {
        section("GCS");
        Mock m = Mock.http();
        try {
            RemoteClient.upload("gcs", cfg("auth", "bearer", "bucket", "bk", "prefix", "a/b", "token", "OAUTH", "apiBase", m.url()),
                    "s h.png", DATA, "image/png", null);
            Req q = m.last();
            check("GCS JSON API path/query", q.method.equals("POST") && q.rawPath.equals("/upload/storage/v1/b/bk/o")
                    && q.rawQuery.equals("uploadType=media&name=a%2Fb%2Fs%20h.png"));
            check("GCS bearer + content-type + body", "Bearer OAUTH".equals(q.hdr("authorization")) && "image/png".equals(q.hdr("content-type")) && Arrays.equals(q.body, DATA));
            m.reqs.clear();
            RemoteClient.test("gcs", cfg("auth", "bearer", "bucket", "bk", "token", "OAUTH", "apiBase", m.url()), null);
            check("GCS test uploads then deletes", m.reqs.size() == 2 && m.reqs.get(1).method.equals("DELETE")
                    && m.reqs.get(1).rawPath.startsWith("/storage/v1/b/bk/o/burpshot-probe-"));
            RemoteClient.upload("gcs", cfg("auth", "hmac", "bucket", "bk", "accessId", "GOOG1EXAMPLE", "secretKey", "HMACSECRET1234", "apiBase", m.url()),
                    "x.png", DATA, "image/png", null);
            q = m.last();
            check("GCS HMAC SigV4 (region auto) verified", q.rawPath.equals("/bk/x.png") && verifySig(q, "GOOG1EXAMPLE", "HMACSECRET1234", "auto", "s3"));
            RemoteClient.upload("gcs", cfg("auth", "none", "bucket", "bk", "apiBase", m.url()), "x.png", DATA, "image/png", null);
            check("GCS public", m.last().method.equals("PUT") && m.last().hdr("authorization") == null);
            RemoteClient.upload("gcs", cfg("auth", "signed-url", "signedUrl", m.url() + "/bk/o.png?X-Goog-Signature=zz"), "x.png", DATA, "image/png", null);
            check("GCS signed URL", m.last().method.equals("PUT") && "X-Goog-Signature=zz".equals(m.last().rawQuery));
        } finally { m.stop(); }
    }

    static void dropboxTests() throws Exception {
        section("Dropbox");
        Mock m = Mock.http();
        try {
            m.behaviour = x -> new Rsp(200, "{\"path_display\":\"/BurpShot/sub/\\u00e9.png\"}");
            Result r = RemoteClient.upload("dropbox", cfg("token", "DBXTOK", "folder", "/BurpShot/../sub/", "mode", "overwrite", "apiBase", m.url()),
                    "é\"q.png", DATA, "image/png", null);
            Req q = m.last();
            String arg = q.hdr("dropbox-api-arg");
            check("Dropbox endpoint/auth/content-type", q.method.equals("POST") && q.rawPath.equals("/2/files/upload")
                    && "Bearer DBXTOK".equals(q.hdr("authorization")) && "application/octet-stream".equals(q.hdr("content-type")));
            check("Dropbox-API-Arg JSON (ascii, mode, autorename, mute)", arg.equals("{\"path\":\"/BurpShot/_/sub/\\u00e9_q.png\",\"mode\":\"overwrite\",\"autorename\":true,\"mute\":true}")
                    || arg.equals("{\"path\":\"/BurpShot/sub/\\u00e9_q.png\",\"mode\":\"overwrite\",\"autorename\":true,\"mute\":true}"));
            check("Dropbox body", Arrays.equals(q.body, DATA));
            m.reqs.clear();
            RemoteClient.test("dropbox", cfg("token", "DBXTOK", "apiBase", m.url()), null);
            check("Dropbox test hits get_current_account only", m.reqs.size() == 1 && m.last().rawPath.equals("/2/users/get_current_account"));
            RemoteException e = expectFail("dropbox", cfg("token", "T", "apiBase", m.url()), new byte[150 * 1024 * 1024 + 1], null);
            check("Dropbox > 150 MB rejected clearly", e != null && e.getMessage().contains("150 MB"));
        } finally { m.stop(); }
    }

    static void driveOneDriveAzure() throws Exception {
        section("Drive / OneDrive / Azure");
        Mock m = Mock.http();
        try {
            m.behaviour = x -> new Rsp(200, "{\"id\":\"FILEID_1\"}");
            Result r = RemoteClient.upload("gdrive", cfg("token", "GTOK", "parent", "FOLDER-1_x", "apiBase", m.url()), "n\"a.png", DATA, "image/png", null);
            Req q = m.last();
            String ct = q.hdr("content-type");
            String body = new String(q.body, StandardCharsets.ISO_8859_1);
            check("Drive URL", q.rawPath.equals("/upload/drive/v3/files") && q.rawQuery.startsWith("uploadType=multipart&supportsAllDrives=true"));
            check("Drive multipart/related", ct.startsWith("multipart/related; boundary=") && "Bearer GTOK".equals(q.hdr("authorization"))
                    && body.contains("{\"name\":\"n_a.png\",\"parents\":[\"FOLDER-1_x\"]}") && body.contains("Content-Type: image/png\r\n\r\n")
                    && body.contains(new String(DATA, StandardCharsets.ISO_8859_1)));
            check("Drive location from id", r.location.contains("FILEID_1"));
            check("Drive bad parent rejected", expectFail("gdrive", cfg("token", "T", "parent", "a\"b", "apiBase", m.url()), DATA, null) != null);

            RemoteClient.upload("onedrive", cfg("token", "OTOK", "folder", "Shots/ä b", "apiBase", m.url()), "x y.png", DATA, "image/png", null);
            q = m.last();
            check("OneDrive PUT path", q.method.equals("PUT") && q.rawPath.equals("/v1.0/me/drive/root:/Shots/%C3%A4%20b/x%20y.png:/content")
                    && "Bearer OTOK".equals(q.hdr("authorization")) && Arrays.equals(q.body, DATA));
            RemoteException e = expectFail("onedrive", cfg("token", "T", "apiBase", m.url()), new byte[4 * 1024 * 1024 + 1], null);
            check("OneDrive > 4 MB rejected clearly", e != null && e.getMessage().contains("4 MB"));

            Result r2 = RemoteClient.upload("azure", cfg("container", m.url() + "/cont/", "sas", "?sv=2021&sig=AB%2FCD%3D&sp=cw", "prefix", "p q"), "x.png", DATA, "image/png", null);
            q = m.last();
            check("Azure PUT path+SAS", q.method.equals("PUT") && q.rawPath.equals("/cont/p%20q/x.png") && q.rawQuery.equals("sv=2021&sig=AB%2FCD%3D&sp=cw"));
            check("Azure headers", "BlockBlob".equals(q.hdr("x-ms-blob-type")) && q.hdr("x-ms-version") != null && Arrays.equals(q.body, DATA));
            check("Azure location hides SAS", !r2.location.contains("sig") && !r2.location.contains("?"));
            check("Azure container with query rejected", expectFail("azure", cfg("container", m.url() + "/c?sv=1", "sas", "sig=1"), DATA, null) != null);
            m.reqs.clear();
            RemoteClient.test("azure", cfg("container", m.url() + "/cont", "sas", "sig=1"), null);
            check("Azure test PUT + DELETE", m.reqs.size() == 2 && m.reqs.get(1).method.equals("DELETE"));
            m.reqs.clear();
            RemoteClient.test("gdrive", cfg("token", "GTOK", "apiBase", m.url()), null);
            RemoteClient.test("onedrive", cfg("token", "GTOK", "apiBase", m.url()), null);
            check("Drive/OneDrive test = GET only", m.reqs.size() == 2 && m.reqs.stream().allMatch(x -> x.method.equals("GET")));
        } finally { m.stop(); }
    }

    // ------------------------------------------------------------------------------------------------
    /** Creates a throw-away PKCS12 keystore with keytool (test only; never used by the library). */
    static SSLContext selfSignedServerContext(Path dir) throws Exception {
        Path ks = dir.resolve("self.p12");
        if (!Files.exists(ks)) {
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                    "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", "changeit", "-keypass", "changeit",
                    "-ext", "san=dns:localhost").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() != 0) throw new IOException("keytool failed");
        }
        KeyStore k = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) { k.load(in, "changeit".toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(k, "changeit".toCharArray());
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(kmf.getKeyManagers(), null, null);
        return c;
    }

    static void httpsTests(Path tmp) throws Exception {
        section("HTTPS (self-signed)");
        HttpsServer hs = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        hs.setHttpsConfigurator(new HttpsConfigurator(selfSignedServerContext(tmp)));
        Mock m = new Mock(hs);
        String url = "https://127.0.0.1:" + m.port + "/";
        try {
            RemoteException e = expectFail("http", cfg("url", url, "auth", "bearer", "token", "HTTPS-SECRET-TOKEN"), DATA, null);
            check("untrusted self-signed cert refused by default", e != null && e.getMessage().contains("certificate") && m.reqs.isEmpty()
                    && !e.getMessage().contains("HTTPS-SECRET-TOKEN"));
            Result r = RemoteClient.upload("http", cfg("url", url, "auth", "bearer", "token", "HTTPS-SECRET-TOKEN", "insecureTls", "true"), "x.png", DATA, "image/png", null);
            check("insecureTls=true accepts it (cert + host name, this client only)", m.reqs.size() == 1 && "Bearer HTTPS-SECRET-TOKEN".equals(m.last().hdr("authorization")));
            e = expectFail("http", cfg("url", url), DATA, null);
            check("insecure option is not sticky (next default call fails again)", e != null);
        } finally { m.stop(); }
    }

    // ------------------------------------------------------------------------------------------------
    // FTP mock
    // ------------------------------------------------------------------------------------------------
    static final class FtpServer implements AutoCloseable {
        final ServerSocket ss; final SSLContext tls; final boolean implicit;
        final List<String> commands = new CopyOnWriteArrayList<>();
        final Map<String, byte[]> files = new java.util.concurrent.ConcurrentHashMap<>();
        final Set<String> dirs = Collections.synchronizedSet(new HashSet<>(List.of("/")));
        volatile boolean epsv = true;
        volatile String failPassReply = null;
        volatile Boolean dataSessionReused = null;
        volatile boolean sawTlsControl, sawTlsData;
        final AtomicInteger sessions = new AtomicInteger();

        FtpServer(SSLContext tls, boolean implicit) throws IOException {
            this.tls = tls; this.implicit = implicit;
            ss = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                try { while (true) { Socket s = ss.accept(); new Thread(() -> serve(s)).start(); } } catch (IOException ignored) { }
            });
            t.setDaemon(true); t.start();
        }
        int port() { return ss.getLocalPort(); }

        SSLSocket wrap(Socket s) throws IOException {
            SSLSocket x = (SSLSocket) tls.getSocketFactory().createSocket(s, null, s.getPort(), true);
            x.setUseClientMode(false);
            if (System.getProperty("ftps.tls12") != null) x.setEnabledProtocols(new String[]{"TLSv1.2"});
            x.startHandshake();
            return x;
        }

        static String line(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder(); int c;
            while ((c = in.read()) != -1 && c != '\n') if (c != '\r') sb.append((char) c);
            return c == -1 && sb.length() == 0 ? null : sb.toString();
        }

        void serve(Socket sock0) {
            sessions.incrementAndGet();
            Socket sock = sock0;
            try {
                if (implicit) { sock = wrap(sock); sawTlsControl = true; }
                InputStream in = sock.getInputStream(); final OutputStream[] out = {sock.getOutputStream()};
                java.util.function.Consumer<String> say = s -> { try { out[0].write((s + "\r\n").getBytes(StandardCharsets.UTF_8)); out[0].flush(); } catch (IOException ignored) { } };
                say.accept("220 mock ftp");
                String user = null; boolean ok = false, protP = false; String cwd = "/";
                ServerSocket data = null;
                String l;
                while ((l = line(in)) != null) {
                    String cmd = l.contains(" ") ? l.substring(0, l.indexOf(' ')).toUpperCase() : l.toUpperCase();
                    String arg = l.contains(" ") ? l.substring(l.indexOf(' ') + 1) : "";
                    commands.add(cmd.equals("PASS") ? "PASS ****" : l);
                    switch (cmd) {
                        case "AUTH":
                            if (tls == null) { say.accept("502 no tls"); break; }
                            say.accept("234 go");
                            sock = wrap(sock); sawTlsControl = true;
                            in = sock.getInputStream(); out[0] = sock.getOutputStream();
                            break;
                        case "USER": user = arg; say.accept(user.equals("anonymous") ? "230 anon ok" : "331 need pass"); ok = user.equals("anonymous"); break;
                        case "PASS":
                            if (failPassReply != null) { say.accept(failPassReply.replace("%PW%", arg)); break; }
                            if ("bob".equals(user) && "s3cret pw".equals(arg)) { ok = true; say.accept("230 hi"); } else say.accept("530 Login incorrect for " + arg);
                            break;
                        case "PBSZ": say.accept("200 ok"); break;
                        case "PROT": protP = arg.equals("P"); say.accept("200 ok"); break;
                        case "TYPE": say.accept("200 ok"); break;
                        case "PWD": say.accept("257 \"" + cwd + "\""); break;
                        case "MKD": {
                            String p = abs(cwd, arg); dirs.add(p); say.accept("257 made"); break;
                        }
                        case "CWD": {
                            String p = abs(cwd, arg);
                            if (dirs.contains(p)) { cwd = p; say.accept("250 ok"); } else say.accept("550 no such dir");
                            break;
                        }
                        case "DELE": say.accept(files.remove(abs(cwd, arg)) != null ? "250 deleted" : "550 nope"); break;
                        case "EPSV":
                            if (!epsv) { say.accept("500 unknown"); break; }
                            if (data != null) data.close();
                            data = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                            say.accept("229 Entering Extended Passive Mode (|||" + data.getLocalPort() + "|)");
                            break;
                        case "PASV":
                            if (data != null) data.close();
                            data = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                            say.accept("227 Entering Passive Mode (10,9,8,7," + (data.getLocalPort() >> 8) + "," + (data.getLocalPort() & 255) + ")"); // bogus IP on purpose
                            break;
                        case "STOR": {
                            if (!ok || data == null) { say.accept("503 bad sequence"); break; }
                            Socket d = data.accept();
                            if (protP) {
                                d = wrap(d); sawTlsData = true;
                                if (sock instanceof SSLSocket) {
                                    javax.net.ssl.SSLSession cs = ((SSLSocket) sock).getSession(), ds = ((SSLSocket) d).getSession();
                                    dataSessionReused = cs.getCreationTime() == ds.getCreationTime() || Arrays.equals(cs.getId(), ds.getId());
                                }
                            }
                            say.accept("150 go");
                            byte[] b = d.getInputStream().readAllBytes();
                            d.close();
                            files.put(abs(cwd, arg), b);
                            say.accept("226 done");
                            break;
                        }
                        case "QUIT": say.accept("221 bye"); sock.close(); return;
                        default: say.accept("502 not implemented");
                    }
                }
            } catch (IOException ignored) {
            } finally { try { sock.close(); } catch (IOException ignored) { } }
        }
        static String abs(String cwd, String a) {
            if (a.startsWith("/")) return a;
            return cwd.equals("/") ? "/" + a : cwd + "/" + a;
        }
        @Override public void close() { try { ss.close(); } catch (IOException ignored) { } }
    }

    static void ftpTests(Path tmp) throws Exception {
        section("FTP / FTPS");
        try (FtpServer f = new FtpServer(null, false)) {
            TestUi ui = new TestUi();
            Result r = RemoteClient.upload("ftp", cfg("host", "127.0.0.1", "port", "" + f.port(), "security", "plain FTP", "auth", "anonymous", "dir", "up/a b"),
                    "../x y.png", DATA, "image/png", ui);
            check("FTP anonymous upload (dirs created by MKD/CWD)", Arrays.equals(f.files.get("/up/a b/x y.png"), DATA) && f.dirs.contains("/up") && f.dirs.contains("/up/a b"));
            check("FTP used TYPE I, EPSV, STOR", f.commands.contains("TYPE I") && f.commands.stream().anyMatch(c -> c.startsWith("EPSV")) && f.commands.contains("STOR x y.png"));
            check("FTP data connection ignored bogus PASV IP", true); // see PASV test below (bogus 10.9.8.7 would time out)
            check("FTP plain warning + location", ui.events.stream().anyMatch(e -> e.contains("unencrypted")) && r.location.equals("ftp://127.0.0.1:" + f.port() + "/up/a%20b/x%20y.png"));
            f.epsv = false;
            r = RemoteClient.upload("ftp", cfg("host", "127.0.0.1", "port", "" + f.port(), "security", "plain FTP", "auth", "username+password",
                    "user", "bob", "password", "s3cret pw", "dir", "/abs/dir"), "p.png", DATA, "image/png", null);
            check("FTP PASV fallback + user/pass + absolute dir", Arrays.equals(f.files.get("/abs/dir/p.png"), DATA) && f.commands.contains("PASS ****"));
            RemoteException e = expectFail("ftp", cfg("host", "127.0.0.1", "port", "" + f.port(), "security", "plain FTP", "auth", "username+password", "user", "bob", "password", "wrong"), DATA, null);
            check("FTP login failure -> clear message, no password", e != null && e.getMessage().contains("530") && !e.getMessage().contains("wrong"));
            int before = f.commands.size();
            for (String[] bad : new String[][]{{"dir", "a\r\nDELE x"}, {"user", "bob\r\nDELE y"}, {"password", "pw\nDELE z"}}) {
                Map<String, String> c = cfg("host", "127.0.0.1", "port", "" + f.port(), "security", "plain FTP", "auth", "username+password", "user", "bob", "password", "s3cret pw", "dir", "ok");
                c.put(bad[0], bad[1]);
                e = expectFail("ftp", c, DATA, null);
                check("FTP CRLF injection rejected in " + bad[0], e != null);
            }
            check("FTP no injected command reached the server", f.commands.stream().noneMatch(c -> c.startsWith("DELE")));
            r = RemoteClient.upload("ftp", cfg("host", "127.0.0.1", "port", "" + f.port(), "security", "plain FTP", "auth", "anonymous"), "a\r\nDELE q\r\n.png", DATA, null, null);
            check("FTP CRLF in remote name neutralised", f.commands.stream().noneMatch(c -> c.startsWith("DELE")) && f.files.containsKey("/a__DELE q__.png"));
            f.files.clear();
            Result t = RemoteClient.test("ftp", cfg("host", "127.0.0.1", "port", "" + f.port(), "security", "plain FTP", "auth", "anonymous", "dir", "up"), null);
            check("FTP test: probe stored then DELE'd", f.files.isEmpty() && t.detail.contains("deleted"));
            t = RemoteClient.test("ftp", cfg("host", "127.0.0.1", "port", "" + f.port(), "security", "plain FTP", "auth", "anonymous", "dir", "missing/dir"), null);
            check("FTP test: missing dir not created", !f.dirs.contains("/missing") && t.detail.contains("does not exist"));
            f.failPassReply = "530 bad password %PW%";
            e = expectFail("ftp", cfg("host", "127.0.0.1", "port", "" + f.port(), "security", "plain FTP", "auth", "username+password", "user", "bob", "password", "ECHOED-PASSWORD"), DATA, null);
            check("FTP server echoing the password is redacted", e != null && !e.getMessage().contains("ECHOED-PASSWORD"));
        }
        // Explicit and implicit FTPS against an in-test TLS-capable server
        SSLContext sctx = selfSignedServerContext(tmp);
        try (FtpServer f = new FtpServer(sctx, false)) {
            RemoteException e = expectFail("ftp", cfg("host", "localhost", "port", "" + f.port(), "security", "explicit FTPS (AUTH TLS)", "auth", "anonymous"), DATA, null);
            check("explicit FTPS: untrusted cert refused by default", e != null && !f.sawTlsData && f.files.isEmpty());
            Result r = RemoteClient.upload("ftp", cfg("host", "localhost", "port", "" + f.port(), "security", "explicit FTPS (AUTH TLS)", "auth", "anonymous",
                    "insecureTls", "true", "dir", "t"), "s.png", DATA, null, null);
            check("explicit FTPS upload (AUTH TLS, PBSZ 0, PROT P)", Arrays.equals(f.files.get("/t/s.png"), DATA) && f.commands.contains("AUTH TLS")
                    && f.commands.contains("PROT P") && f.sawTlsControl && f.sawTlsData);
            check("explicit FTPS: data channel resumed the control TLS session (server-observed)", Boolean.TRUE.equals(f.dataSessionReused));
        }
        try (FtpServer f = new FtpServer(sctx, true)) {
            Result r = RemoteClient.upload("ftp", cfg("host", "localhost", "port", "" + f.port(), "security", "implicit FTPS (990)", "auth", "anonymous", "insecureTls", "true"),
                    "i.png", DATA, null, null);
            check("implicit FTPS upload", Arrays.equals(f.files.get("/i.png"), DATA) && f.sawTlsControl && f.sawTlsData && r.location.startsWith("ftps://"));
        }
    }

    // ------------------------------------------------------------------------------------------------
    static void hostKeyRepoTests() {
        section("SFTP host key repository logic (isolated)");
        byte[] blob = new byte[]{0, 0, 0, 11, 's', 's', 'h', '-', 'e', 'd', '2', '5', '5', '1', '9', 1, 2, 3};
        String fp = RemoteSftp.fingerprint(blob);
        check("fingerprint format", fp.startsWith("SHA256:") && !fp.contains("=") && fp.length() == 7 + 43);
        check("key type parsed from blob", RemoteSftp.keyType(blob).equals("ssh-ed25519"));
        TestUi ui = new TestUi();
        RemoteSftp.TrustRepo repo = new RemoteSftp.TrustRepo("h:22", ui);
        ui.confirm = false;
        check("unknown + rejected -> NOT_INCLUDED, asked once, not remembered",
                repo.check("h", blob) == com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED && ui.events.size() == 1 && !ui.lastChanged && repo.failure != null);
        ui.confirm = true; ui.events.clear();
        check("unknown + accepted -> OK and remembered", repo.check("h", blob) == com.jcraft.jsch.HostKeyRepository.OK && ui.events.get(1).equals("remember:h:22"));
        ui.events.clear();
        check("trusted -> OK without asking", repo.check("h", blob) == com.jcraft.jsch.HostKeyRepository.OK && ui.events.isEmpty());
        byte[] other = blob.clone(); other[17] = 9;
        ui.confirm = false;
        check("changed + rejected -> CHANGED with changed=true", repo.check("h", other) == com.jcraft.jsch.HostKeyRepository.CHANGED && ui.lastChanged);
        check("null Ui -> never trusted", new RemoteSftp.TrustRepo("h:22", null).check("h", blob) == com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED);
    }

    // ------------------------------------------------------------------------------------------------
    static void fsTests(Path tmp) throws Exception {
        section("share / local");
        Path d = tmp.resolve("share/sub dir");
        for (String type : new String[]{"local", "share"}) {
            Result r = RemoteClient.upload(type, cfg("path", d.toString()), "../../../evil.png", DATA, null, null);
            Path f = d.resolve("evil.png");
            check(type + ": written inside folder, traversal neutralised", Arrays.equals(Files.readAllBytes(f), DATA) && r.location.equals(f.toString()));
        }
        check("no stray tmp files", Files.list(d).noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        check("nothing escaped to parent", !Files.exists(tmp.resolve("evil.png")) && !Files.exists(tmp.resolve("share/evil.png")));
        RemoteClient.upload("local", cfg("path", d.toString()), "evil.png", "v2".getBytes(), null, null);
        check("overwrite works", new String(Files.readAllBytes(d.resolve("evil.png"))).equals("v2"));
        check("relative path rejected", expectFail("local", cfg("path", "rel/dir"), DATA, null) != null);
        check("empty path rejected", expectFail("share", cfg("path", " "), DATA, null) != null);
        Result t = RemoteClient.test("local", cfg("path", d.toString()), null);
        check("test(): writable, probe removed", t.detail.contains("writable") && Files.list(d).count() == 1);
        t = RemoteClient.test("local", cfg("path", tmp.resolve("newfolder").toString()), null);
        check("test(): missing folder not created", t.detail.contains("does not exist") && !Files.exists(tmp.resolve("newfolder")));
        // UNC handling on a non-Windows OS must produce a clean error, not an exception or a stray local directory
        String unc = "\\\\server\\share\\dir";
        boolean clean = false;
        try { RemoteFs.resolveDir(unc, false); } catch (RemoteException e) { clean = e.getMessage().contains("mount"); }
        check("UNC path on non-Windows -> clean RemoteException", clean);
        clean = false;
        try { RemoteFs.resolveDir("Z:\\x", false); } catch (RemoteException e) { clean = true; }
        check("drive-letter path on non-Windows -> clean RemoteException", clean);
        if (RemoteFs.isWindows()) {
            check("UNC path accepted on Windows (resolves)", RemoteFs.resolveDir(unc, true).isAbsolute());
        }
        check("mounted-style path accepted on non-Windows", resolveOk("/mnt/share/x", false) || RemoteFs.isWindows());
    }

    static boolean resolveOk(String p, boolean win) {
        try { return RemoteFs.resolveDir(p, win) != null; } catch (RemoteException e) { return false; }
    }

    // ------------------------------------------------------------------------------------------------
    static void leakTests() throws Exception {
        section("secret leak: every backend vs failing servers");
        String TOK = "TOK-SECRET-123", PW = "PW-SECRET-456", KEY = "KEY-SECRET-789", COOKIE = "COOKIE-SECRET-000", SAS = "SASSIG%2Fsecret1",
                SK = "SK-SECRET-aaaa", SESS = "SESS-SECRET-bbbb", PRE = "PRESIG-secret-cccc", HDR = "HDR-SECRET-dddd", HMAC = "HMAC-SECRET-eeee";
        String[] secrets = {TOK, PW, KEY, COOKIE, SAS, "sig=" + SAS, SK, SESS, PRE, HDR, HMAC};
        Mock m = Mock.http();
        // The failing server echoes every secret, the request URL and the Authorization header back.
        final String[][] cur = new String[1][];
        m.behaviour = r -> new Rsp(500, "oops " + String.join(" ", cur[0]) + " " + r.rawPath + "?" + r.rawQuery + " " + r.hdr("authorization")
                + " " + r.hdr("cookie") + " " + r.hdr("x-auth"));
        List<Object[]> cases = new ArrayList<>();
        String u = m.url();
        cases.add(new Object[]{"http", cfg("url", u + "/x?k=" + KEY, "auth", "basic", "user", "u", "password", PW)});
        cases.add(new Object[]{"http", cfg("url", u + "/x", "auth", "bearer", "token", TOK)});
        cases.add(new Object[]{"http", cfg("url", u + "/x", "auth", "apikey-query", "keyName", "k", "keyValue", KEY)});
        cases.add(new Object[]{"http", cfg("url", u + "/x", "auth", "apikey-header", "keyName", "X-K", "keyValue", KEY)});
        cases.add(new Object[]{"http", cfg("url", u + "/x", "auth", "cookie", "cookie", "s=" + COOKIE, "headers", "X-Auth: " + HDR)});
        cases.add(new Object[]{"s3", cfg("auth", "access key", "endpoint", u, "bucket", "bk", "accessKey", "AKIDEXAMPLE", "secretKey", SK, "sessionToken", SESS)});
        cases.add(new Object[]{"s3", cfg("auth", "pre-signed URL", "presignedUrl", u + "/b/k?X-Amz-Signature=" + PRE)});
        cases.add(new Object[]{"gcs", cfg("auth", "bearer", "bucket", "bk", "token", TOK, "apiBase", u)});
        cases.add(new Object[]{"gcs", cfg("auth", "hmac", "bucket", "bk", "accessId", "GOOGID", "secretKey", HMAC, "apiBase", u)});
        cases.add(new Object[]{"gcs", cfg("auth", "signed-url", "signedUrl", u + "/b/o?X-Goog-Signature=" + PRE)});
        cases.add(new Object[]{"dropbox", cfg("token", TOK, "apiBase", u)});
        cases.add(new Object[]{"gdrive", cfg("token", TOK, "apiBase", u)});
        cases.add(new Object[]{"onedrive", cfg("token", TOK, "apiBase", u)});
        cases.add(new Object[]{"azure", cfg("container", u + "/c", "sas", "?sv=1&sig=" + SAS)});
        int n = 0;
        for (Object[] cs : cases) {
            @SuppressWarnings("unchecked") Map<String, String> c = (Map<String, String>) cs[1];
            String id = (String) cs[0];
            cur[0] = own(c, secrets);
            String msg = null;
            try { RemoteClient.upload(id, c, "f.png", DATA, "image/png", null); } catch (RemoteException e) { msg = all(e); }
            String tmsg = null;
            try { RemoteClient.test(id, c, null); } catch (RemoteException e) { tmsg = all(e); }
            check("leak: " + id + " upload fails w/o secrets (" + (++n) + ")", msg != null && noSecrets(msg, cur[0]) && msg.contains("500"));
            if (tmsg != null) check("leak: " + id + " test() fails w/o secrets (" + n + ")", noSecrets(tmsg, cur[0]));
        }
        // successful results must not carry secrets either
        m.behaviour = r -> new Rsp(200, "{}");
        for (Object[] cs : cases) {
            @SuppressWarnings("unchecked") Map<String, String> c = (Map<String, String>) cs[1];
            try {
                Result r = RemoteClient.upload((String) cs[0], c, "f.png", DATA, "image/png", null);
                check("leak: " + cs[0] + " Result clean", noSecrets(r.location + " " + r.detail, secrets));
                Result t = RemoteClient.test((String) cs[0], c, null);
                check("leak: " + cs[0] + " test Result clean", noSecrets(t.location + " " + t.detail, secrets));
            } catch (RemoteException e) { check("leak: unexpected failure " + cs[0] + ": " + e.getMessage(), false); }
        }
        m.stop();
        // connection refused: URL carrying secrets must not appear in the message
        int dead; try (ServerSocket s = new ServerSocket(0)) { dead = s.getLocalPort(); }
        String du = "http://u:pw@127.0.0.1:" + dead;
        RemoteException e = expectFail("http", cfg("url", "http://127.0.0.1:" + dead + "/x?token=" + TOK, "auth", "bearer", "token", TOK), DATA, null);
        check("leak: connection refused message clean", e != null && noSecrets(all(e), secrets));
        e = expectFail("ftp", cfg("host", "127.0.0.1", "port", "" + dead, "security", "plain FTP", "auth", "username+password", "user", "u", "password", PW), DATA, null);
        check("leak: FTP connection refused clean", e != null && noSecrets(all(e), secrets));
        e = expectFail("sftp", cfg("host", "127.0.0.1", "port", "" + dead, "user", "u", "auth", "password", "password", PW), DATA, new TestUi());
        check("leak: SFTP connection refused clean", e != null && noSecrets(all(e), secrets));
        // SFTP with no Ui -> fails cleanly (no trust possible) -- tested against a socket that is not SSH
        try (ServerSocket junk = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread th = new Thread(() -> { try (Socket s = junk.accept()) { s.getOutputStream().write("not ssh\r\n".getBytes()); Thread.sleep(300); } catch (Exception ignored) { } });
            th.setDaemon(true); th.start();
            e = expectFail("sftp", cfg("host", "127.0.0.1", "port", "" + junk.getLocalPort(), "user", "u", "auth", "password", "password", PW), DATA, null);
            check("SFTP against a non-SSH server fails cleanly, no secrets", e != null && noSecrets(all(e), secrets));
        }
    }

    /** The subset of known secrets that this particular configuration actually contains. */
    static String[] own(Map<String, String> c, String[] secrets) {
        List<String> l = new ArrayList<>();
        for (String s : secrets) for (String v : c.values()) if (v.contains(s)) { l.add(s); break; }
        return l.toArray(new String[0]);
    }

    static String all(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable x = t; x != null; x = x.getCause()) sb.append(x).append(' ');
        return sb.toString();
    }
    static boolean noSecrets(String s, String[] secrets) {
        for (String x : secrets) if (s.contains(x)) { System.out.println("    LEAKED: " + x + " in: " + s); return false; }
        return true;
    }

    // ------------------------------------------------------------------------------------------------
    static void sizeLimitTests() {
        section("size limit");
        RemoteException e = expectFail("local", cfg("path", System.getProperty("java.io.tmpdir")), new byte[256 * 1024 * 1024 + 1], null);
        check("> 256 MB rejected with clear message", e != null && e.getMessage().contains("256 MB"));
        check("unknown type", expectFail("nope", cfg(), DATA, null) != null);
        check("null data", expectFail("local", cfg(), null, null) != null);
    }
}
