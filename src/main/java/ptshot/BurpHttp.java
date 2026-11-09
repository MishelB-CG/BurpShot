package ptshot;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpMode;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Flow;

/**
 * Sends the HTTP(S) requests of "Share" through Burp's own HTTP engine, so Burp's upstream proxy, TLS and connection settings apply
 * (needed on corporate networks). Redirects are never followed. Note that Burp records requests sent by extensions in its Logger, so
 * the request (including any credentials in it) is visible there.
 */
final class BurpHttp implements RemoteHttp.Transport {
    private final MontoyaApi api;

    BurpHttp(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public RemoteHttp.Resp send(java.net.http.HttpRequest req, boolean insecureTls) throws IOException {
        URI u = req.uri();
        boolean secure = "https".equalsIgnoreCase(u.getScheme());
        int port = u.getPort() > 0 ? u.getPort() : secure ? 443 : 80;
        byte[] body = bodyBytes(req);

        StringBuilder head = new StringBuilder();
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
        head.append(req.method()).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(u.getHost()).append(u.getPort() > 0 && u.getPort() != (secure ? 443 : 80) ? ":" + u.getPort() : "").append("\r\n");
        for (Map.Entry<String, List<String>> h : req.headers().map().entrySet()) {
            String n = h.getKey();
            String ln = n.toLowerCase(Locale.ROOT);
            if (ln.equals("host") || ln.equals("content-length") || ln.equals("connection")) continue;
            for (String v : h.getValue()) head.append(n).append(": ").append(v).append("\r\n");
        }
        if (body.length > 0 || req.method().equals("POST") || req.method().equals("PUT")) head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        byte[] h = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] raw = new byte[h.length + body.length];
        System.arraycopy(h, 0, raw, 0, h.length);
        System.arraycopy(body, 0, raw, h.length, body.length);

        HttpRequest hr = HttpRequest.httpRequest(HttpService.httpService(u.getHost(), port, secure), ByteArray.byteArray(raw));
        RequestOptions opt = RequestOptions.requestOptions()
                .withHttpMode(HttpMode.HTTP_1)
                .withRedirectionMode(RedirectionMode.NEVER)
                .withResponseTimeout(RemoteHttp.REQUEST_TIMEOUT.toMillis());
        if (!insecureTls) opt = opt.withUpstreamTLSVerification();
        HttpRequestResponse rr = api.http().sendRequest(hr, opt);
        if (rr == null || !rr.hasResponse()) {
            throw new IOException("No response (the connection failed, the certificate was refused or the request timed out)");
        }
        byte[] rb = rr.response().body().getBytes();
        int n = Math.min(rb.length, 4096);
        String loc = rr.response().headerValue("Location");
        return new RemoteHttp.Resp(rr.response().statusCode(), new String(rb, 0, n, StandardCharsets.UTF_8), loc);
    }

    /** The request body bytes (our publishers are all in-memory, so this completes synchronously). */
    static byte[] bodyBytes(java.net.http.HttpRequest req) throws IOException {
        java.util.Optional<java.net.http.HttpRequest.BodyPublisher> p = req.bodyPublisher();
        if (!p.isPresent()) return new byte[0];
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final Throwable[] err = new Throwable[1];
        p.get().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            public void onNext(ByteBuffer b) {
                byte[] a = new byte[b.remaining()];
                b.get(a);
                out.write(a, 0, a.length);
            }

            public void onError(Throwable t) {
                err[0] = t;
            }

            public void onComplete() {
            }
        });
        if (err[0] != null) throw new IOException("The request body could not be read.");
        return out.toByteArray();
    }
}
