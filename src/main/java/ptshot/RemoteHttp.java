package ptshot;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import ptshot.RemoteClient.RemoteException;
import ptshot.RemoteUtil.Cfg;

/** HTTP transport shared by the REST-like backends: no redirects, strict timeouts, safe errors. */
final class RemoteHttp {
    private RemoteHttp() {}

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    static final class Resp {
        final int status;
        final String body;      // at most the first 4 KiB, decoded as UTF-8
        final String location;  // Location header or null
        Resp(int status, String body, String location) { this.status = status; this.body = body; this.location = location; }
        boolean ok() { return status >= 200 && status < 300; }
    }

    /**
     * Trust-all context. Because the trust manager is an X509ExtendedTrustManager, the JSSE endpoint
     * identification (host name check) is delegated to it too, so this disables BOTH certificate and host
     * name verification for the one SSLContext only (no global system properties involved).
     */
    static SSLContext trustAllContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new TrustAll()}, new SecureRandom());
            return ctx;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("TLS unavailable");
        }
    }

    private static final class TrustAll extends X509ExtendedTrustManager {
        public void checkClientTrusted(X509Certificate[] c, String a) { }
        public void checkServerTrusted(X509Certificate[] c, String a) { }
        public void checkClientTrusted(X509Certificate[] c, String a, java.net.Socket s) { }
        public void checkServerTrusted(X509Certificate[] c, String a, java.net.Socket s) { }
        public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) { }
        public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) { }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    static HttpClient client(boolean insecure) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)   // credentials must never follow a redirect
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1);
        if (insecure) b.sslContext(trustAllContext());        // otherwise: JVM default trust + host name check
        return b.build();
    }

    /** Parses and validates an http(s) URL; rejects other schemes and embedded credentials. */
    static URI parseHttpUri(String raw, String what) throws RemoteException {
        URI u;
        try {
            u = new URI(raw.trim());
        } catch (java.net.URISyntaxException e) {
            throw new RemoteException(what + " is not a valid URL.", null);
        }
        String s = u.getScheme();
        if (s == null || !(s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https"))) {
            throw new RemoteException(what + " must start with http:// or https://.", null);
        }
        if (u.getHost() == null || u.getHost().isEmpty()) throw new RemoteException(what + " has no host name.", null);
        if (u.getRawUserInfo() != null) {
            throw new RemoteException(what + " must not contain a user name or password; use the authentication settings.", null);
        }
        return u;
    }

    static boolean isPlain(URI u) { return "http".equalsIgnoreCase(u.getScheme()); }

    static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
    }

    /** Adds a header after validating name and value (no CR/LF/NUL injection). */
    static void header(HttpRequest.Builder b, String name, String value) throws RemoteException {
        if (name == null || !TOKEN.matcher(name).matches()) throw new RemoteException("Invalid header name.", null);
        RemoteUtil.noCtl("Header '" + name + "'", value);
        try {
            b.header(name, value);
        } catch (IllegalArgumentException e) {
            throw new RemoteException("Header '" + name + "' is not allowed or invalid.", null);
        }
    }

    /** Dry-run capture used to render previews of signed/provider requests without sending them. */
    static final class Dry { HttpRequest req; byte[] body; }
    static final class DryRun extends RuntimeException {
        private static final long serialVersionUID = 1L;
        DryRun() { super(null, null, false, false); }
    }

    /** Body publisher that also records the bytes when a dry run is active. */
    static HttpRequest.BodyPublisher body(Cfg c, byte[] b) {
        if (c.dry != null) c.dry.body = b;
        return HttpRequest.BodyPublishers.ofByteArray(b);
    }

    static Resp send(Cfg c, HttpRequest req) throws RemoteException {
        if (c.dry != null) { c.dry.req = req; throw new DryRun(); }
        HttpClient cl = client(c.bool("insecureTls"));
        try {
            HttpResponse<InputStream> r = cl.send(req, HttpResponse.BodyHandlers.ofInputStream());
            byte[] buf;
            try (InputStream in = r.body()) {
                buf = in.readNBytes(4096);
            }
            String loc = r.headers().firstValue("location").orElse(null);
            return new Resp(r.statusCode(), new String(buf, StandardCharsets.UTF_8), loc);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteException("Upload was interrupted.", null);
        } catch (IOException e) {
            String m = c.redact(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            String hint = e instanceof SSLException
                    ? " (the server certificate was not accepted; the 'Skip TLS certificate verification' option is for lab systems only)"
                    : "";
            throw new RemoteException("Network error: " + RemoteUtil.stripCtl(m) + hint, null);
        } catch (IllegalArgumentException | SecurityException e) {
            throw new RemoteException("The request could not be built (" + e.getClass().getSimpleName() + ").", null);
        }
    }

    /** Short, safe one-line summary of a response body. */
    static String summary(Cfg c, Resp r) {
        String s = r.body == null ? "" : r.body;
        s = RemoteUtil.stripCtl(s).replaceAll("\\s+", " ").trim();
        if (s.length() > 200) s = s.substring(0, 200);
        return c.redact(s);
    }

    /** Throws unless the response is 2xx. 3xx names only the redirect host. */
    static void expectOk(Cfg c, Resp r) throws RemoteException {
        if (r.ok()) return;
        if (r.status >= 300 && r.status < 400) {
            String host = r.location == null ? "an unknown host" : RemoteUtil.hostOf(r.location);
            throw new RemoteException("The server answered HTTP " + r.status + " and redirected to " + host
                    + ". Redirects are never followed so credentials cannot leak; use the final URL instead.", null);
        }
        String sum = summary(c, r);
        throw new RemoteException("The server answered HTTP " + r.status + (sum.isEmpty() ? "" : ": " + sum), null);
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
