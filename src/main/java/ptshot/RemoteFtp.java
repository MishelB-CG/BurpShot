package ptshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import ptshot.RemoteClient.RemoteException;
import ptshot.RemoteClient.Result;
import ptshot.RemoteUtil.Cfg;

/**
 * Minimal FTP / FTPS client (RFC 959, 2428, 4217) written directly on sockets.
 *
 * Security notes:
 *  - Every command is checked for CR/LF/NUL, so user supplied values cannot inject extra commands.
 *  - The address advertised in PASV/EPSV replies is ignored; the data connection always goes to the host of
 *    the control connection (prevents FTP bounce / server-directed connections to third parties).
 *  - FTPS uses the JVM trust store with HTTPS-style host name verification (unless "insecureTls" is set).
 *
 * FTPS limits (best effort): the data channel is wrapped with the control connection's host and (via PortFacade) port as the
 * TLS peer identity so the JDK's client session cache offers the control session for resumption (many servers,
 * e.g. vsftpd with require_ssl_reuse=YES, demand that). The JDK offers no API to force resumption, so servers
 * that insist on it and reject the JDK's resumption may still refuse the data connection; in that case the
 * server's error is reported. No active mode, no REST/resume, no MLSD/LIST parsing, no proxy support.
 */
final class RemoteFtp {
    private RemoteFtp() {}

    private static final int CONNECT_MS = 15_000, READ_MS = 120_000;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._:\\-]{1,253}");
    private static final Pattern EPSV = Pattern.compile("\\(\\|[^|]*\\|[^|]*\\|(\\d{1,5})\\|\\)");
    private static final Pattern PASV = Pattern.compile("(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})");

    private static final String PLAIN = "plain FTP", EXPLICIT = "explicit FTPS (AUTH TLS)", IMPLICIT = "implicit FTPS (990)";

    private static final class Reply {
        final int code; final String text;
        Reply(int code, String text) { this.code = code; this.text = text; }
    }

    private static final class Conn implements AutoCloseable {
        final Cfg c; final String host; final int port; final boolean secure; final SSLSocketFactory sf;
        Socket sock; InputStream in; OutputStream out;

        Conn(Cfg c, String host, int port, boolean secure, SSLSocketFactory sf) {
            this.c = c; this.host = host; this.port = port; this.secure = secure; this.sf = sf;
        }

        void wrapControl() throws IOException {
            SSLSocket s = (SSLSocket) sf.createSocket(sock, host, port, true);
            harden(s);
            s.startHandshake();
            sock = s;
            in = s.getInputStream();
            out = s.getOutputStream();
        }

        String readLine() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream(80);
            int ch;
            while ((ch = in.read()) != -1) {
                if (ch == '\n') break;
                if (b.size() >= 4096) throw new IOException("reply line too long");
                if (ch != '\r') b.write(ch);
            }
            if (ch == -1 && b.size() == 0) throw new IOException("connection closed by server");
            return b.toString(StandardCharsets.UTF_8);
        }

        Reply read() throws IOException {
            String line = readLine();
            if (line.length() < 3 || !Character.isDigit(line.charAt(0)) || !Character.isDigit(line.charAt(1))
                    || !Character.isDigit(line.charAt(2))) throw new IOException("malformed reply");
            int code = Integer.parseInt(line.substring(0, 3));
            StringBuilder text = new StringBuilder(line.length() > 4 ? line.substring(4) : "");
            if (line.length() > 3 && line.charAt(3) == '-') {
                String end = line.substring(0, 3) + " ";
                for (int i = 0; i < 200; i++) {
                    String l = readLine();
                    text.append('\n').append(l.startsWith(end) ? l.substring(4) : l);
                    if (l.startsWith(end)) break;
                    if (i == 199) throw new IOException("reply too long");
                }
            }
            return new Reply(code, text.toString());
        }

        void send(String cmd) throws IOException, RemoteException {
            RemoteUtil.noCtl("FTP value", cmd);   // command injection guard
            out.write((cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        Reply cmd(String cmd) throws IOException, RemoteException {
            send(cmd);
            return read();
        }

        void expect(String cmd, String what, int... okFirstDigit) throws IOException, RemoteException {
            Reply r = cmd(cmd);
            check(r, what, okFirstDigit);
        }

        void check(Reply r, String what, int... okFirstDigit) throws RemoteException {
            for (int d : okFirstDigit) if (r.code / 100 == d) return;
            throw fail(c, what, r);
        }

        @Override public void close() {
            try {
                if (sock != null && !sock.isClosed()) {
                    try { out.write("QUIT\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush(); } catch (IOException ignored) { }
                    sock.close();
                }
            } catch (IOException ignored) { }
        }
    }

    private static RemoteException fail(Cfg c, String what, Reply r) {
        String t = RemoteUtil.stripCtl(r.text).replaceAll("\\s+", " ").trim();
        if (t.length() > 200) t = t.substring(0, 200);
        return new RemoteException("FTP " + what + " failed: " + r.code + " " + c.redact(t), null);
    }

    /**
     * The JDK keys its client TLS session cache by (peer host, Socket.getPort()) and, for a layered socket, takes
     * the port from the underlying socket rather than from the createSocket argument. This thin delegate reports
     * the control connection's port so the data channel offers the control channel's session for resumption.
     * (No reflection; everything else is forwarded to the real data socket.)
     */
    private static final class PortFacade extends Socket {
        private final Socket d; private final int fakePort;
        PortFacade(Socket d, int fakePort) { this.d = d; this.fakePort = fakePort; }
        @Override public int getPort() { return fakePort; }
        @Override public InputStream getInputStream() throws IOException { return d.getInputStream(); }
        @Override public OutputStream getOutputStream() throws IOException { return d.getOutputStream(); }
        @Override public synchronized void close() throws IOException { d.close(); }
        @Override public boolean isClosed() { return d.isClosed(); }
        @Override public boolean isConnected() { return d.isConnected(); }
        @Override public boolean isBound() { return d.isBound(); }
        @Override public java.net.InetAddress getInetAddress() { return d.getInetAddress(); }
        @Override public java.net.InetAddress getLocalAddress() { return d.getLocalAddress(); }
        @Override public int getLocalPort() { return d.getLocalPort(); }
        @Override public java.net.SocketAddress getRemoteSocketAddress() { return d.getRemoteSocketAddress(); }
        @Override public java.net.SocketAddress getLocalSocketAddress() { return d.getLocalSocketAddress(); }
        @Override public synchronized void setSoTimeout(int t) throws java.net.SocketException { d.setSoTimeout(t); }
        @Override public synchronized int getSoTimeout() throws java.net.SocketException { return d.getSoTimeout(); }
        @Override public void shutdownInput() throws IOException { d.shutdownInput(); }
        @Override public void shutdownOutput() throws IOException { d.shutdownOutput(); }
        @Override public boolean isInputShutdown() { return d.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return d.isOutputShutdown(); }
        @Override public void setTcpNoDelay(boolean on) throws java.net.SocketException { d.setTcpNoDelay(on); }
        @Override public boolean getTcpNoDelay() throws java.net.SocketException { return d.getTcpNoDelay(); }
    }

    private static void harden(SSLSocket s) {
        SSLParameters p = s.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");   // host name verification
        s.setSSLParameters(p);
    }

    private static RemoteException ioFail(Cfg c, IOException e) {
        String m = c.redact(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        String hint = e instanceof javax.net.ssl.SSLException
                ? " (TLS failed; the certificate may be untrusted or not match the host name)" : "";
        return new RemoteException("FTP network error: " + RemoteUtil.stripCtl(m) + hint, null);
    }

    // ---- connection + login ----------------------------------------------------------------------------

    private static Conn connect(Cfg c, RemoteClient.Ui ui) throws RemoteException {
        String host = c.req("host");
        if (!HOST.matcher(host).matches()) throw new RemoteException("The host name contains invalid characters.", null);
        String security = c.trim("security");
        if (security.isEmpty()) security = EXPLICIT;
        boolean implicit = IMPLICIT.equals(security), explicit = EXPLICIT.equals(security);
        int port;
        try {
            String ps = c.trim("port");
            port = ps.isEmpty() ? 21 : Integer.parseInt(ps);
            if (implicit && port == 21) port = 990;
        } catch (NumberFormatException e) {
            throw new RemoteException("The port is not a number.", null);
        }
        if (port < 1 || port > 65535) throw new RemoteException("The port must be between 1 and 65535.", null);
        boolean anonymous = "anonymous".equals(c.trim("auth"));
        String user = anonymous ? "anonymous" : c.req("user");
        String pass = anonymous ? "anonymous@" : c.get("password");
        RemoteUtil.noCtl("User name", user);
        RemoteUtil.noCtl("Password", pass);

        SSLSocketFactory sf = null;
        if (implicit || explicit) {
            try {
                SSLContext ctx = c.bool("insecureTls") ? RemoteHttp.trustAllContext() : SSLContext.getDefault();
                sf = ctx.getSocketFactory();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new RemoteException("TLS is not available.", null);
            }
        } else {
            RemoteClient.prog(ui, "WARNING: plain FTP is unencrypted; the password and the data travel in clear text.");
        }
        Conn k = new Conn(c, host, port, implicit || explicit, sf);
        try {
            RemoteClient.prog(ui, "Connecting to " + host + ":" + port + " ...");
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), CONNECT_MS);
            s.setSoTimeout(READ_MS);
            k.sock = s;
            k.in = s.getInputStream();
            k.out = s.getOutputStream();
            if (implicit) k.wrapControl();
            k.check(k.read(), "connection", 2);
            if (explicit) {
                k.expect("AUTH TLS", "TLS negotiation (the server may not support FTPS)", 2);
                k.wrapControl();
            }
            Reply r = k.cmd("USER " + user);
            if (r.code / 100 == 3) r = k.cmd("PASS " + pass);
            k.check(r, "login", 2);
            if (implicit || explicit) {
                k.expect("PBSZ 0", "PBSZ", 2);
                k.expect("PROT P", "PROT P (private data channel)", 2);
            }
            k.expect("TYPE I", "TYPE I", 2);
            return k;
        } catch (IOException e) {
            k.close();
            throw ioFail(c, e);
        } catch (RemoteException e) {
            k.close();
            throw e;
        }
    }

    /** CWD into each segment, creating it (MKD) when missing and allowed. Returns false if missing and !create. */
    private static boolean enterDir(Conn k, String dirRaw, boolean create) throws IOException, RemoteException {
        String d = dirRaw == null ? "" : dirRaw.trim();
        RemoteUtil.noCtl("Directory", d);
        if (d.startsWith("/") || d.startsWith("\\")) k.expect("CWD /", "CWD", 2);
        for (String seg : RemoteUtil.sanitizeSegments(d)) {
            Reply r = k.cmd("CWD " + seg);
            if (r.code / 100 == 2) continue;
            if (!create) return false;
            k.expect("MKD " + seg, "creating directory", 2);
            k.expect("CWD " + seg, "CWD", 2);
        }
        return true;
    }

    // ---- data transfer ---------------------------------------------------------------------------------

    private static int passivePort(Conn k) throws IOException, RemoteException {
        Reply r = k.cmd("EPSV");
        if (r.code == 229) {
            Matcher m = EPSV.matcher(r.text);
            if (m.find()) return validPort(k, Integer.parseInt(m.group(1)));
        }
        r = k.cmd("PASV");
        k.check(r, "passive mode", 2);
        Matcher m = PASV.matcher(r.text);
        if (!m.find()) throw new RemoteException("FTP server sent an unreadable PASV reply.", null);
        return validPort(k, Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6)));
    }

    private static int validPort(Conn k, int p) throws RemoteException {
        if (p < 1 || p > 65535) throw new RemoteException("FTP server sent an invalid data port.", null);
        return p;
    }

    private static void store(Conn k, String name, byte[] data) throws IOException, RemoteException {
        int dport = passivePort(k);
        k.send("STOR " + name);
        Socket d = new Socket();
        try {
            // Always the control connection's address: never the one the server advertises.
            d.connect(new InetSocketAddress(k.sock.getInetAddress(), dport), CONNECT_MS);
            d.setSoTimeout(READ_MS);
            if (k.secure) {
                // Peer identity = control host/port so the JDK offers the control TLS session for reuse (see class comment).
                SSLSocket ds = (SSLSocket) k.sf.createSocket(new PortFacade(d, k.port), k.host, k.port, true);
                harden(ds);
                ds.startHandshake();
                d = ds;
            }
            Reply pre = k.read();
            if (pre.code / 100 != 1) throw fail(k.c, "upload", pre);
            OutputStream o = d.getOutputStream();
            o.write(data);
            o.flush();
        } finally {
            try { d.close(); } catch (IOException ignored) { }
        }
        k.check(k.read(), "upload", 2);
    }

    // ---- API ---------------------------------------------------------------------------------------------

    private static String location(Conn k, String dir, String name) {
        List<String> segs = new ArrayList<>(RemoteUtil.sanitizeSegments(dir));
        segs.add(name);
        boolean implicit = IMPLICIT.equals(k.c.trim("security"));
        int def = implicit ? 990 : 21;
        return (implicit ? "ftps://" : "ftp://") + k.host + (k.port == def ? "" : ":" + k.port) + "/" + RemoteUtil.encodePath(segs);
    }

    static Result upload(Cfg c, String name, byte[] data, RemoteClient.Ui ui) throws RemoteException {
        try (Conn k = connect(c, ui)) {
            enterDir(k, c.get("dir"), true);
            RemoteClient.prog(ui, "Uploading " + name + " ...");
            store(k, name, data);
            return new Result(location(k, c.get("dir"), name), "FTP, " + data.length + " bytes");
        } catch (IOException e) {
            throw ioFail(c, e);
        }
    }

    static Result test(Cfg c, RemoteClient.Ui ui) throws RemoteException {
        try (Conn k = connect(c, ui)) {
            if (!enterDir(k, c.get("dir"), false)) {
                return new Result(location(k, c.get("dir"), "").replaceAll("/$", ""),
                        "Login OK; the remote directory does not exist yet and will be created on upload.");
            }
            String probe = RemoteClient.probeName();
            store(k, probe, RemoteClient.PROBE);
            Reply d = k.cmd("DELE " + probe);
            String del = d.code / 100 == 2 ? "probe deleted" : "probe could NOT be deleted, remove " + probe + " manually";
            return new Result(location(k, c.get("dir"), "").replaceAll("/$", ""), "Login OK, upload OK; " + del);
        } catch (IOException e) {
            throw ioFail(c, e);
        }
    }
}
