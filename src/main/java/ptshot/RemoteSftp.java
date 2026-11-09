package ptshot;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import ptshot.RemoteClient.RemoteException;
import ptshot.RemoteClient.Result;
import ptshot.RemoteUtil.Cfg;

/**
 * SFTP / SCP through JSch (com.github.mwiede:jsch).
 *
 * Host key verification is mandatory and happens inside the key exchange, i.e. BEFORE any credential is
 * sent: {@link TrustRepo} asks {@link RemoteClient.Ui} and JSch is configured with StrictHostKeyChecking=yes,
 * so anything the repository does not return OK for aborts the connection. Weak algorithms (SHA-1 ssh-rsa,
 * CBC, 3DES, arcfour, hmac-sha1, DH group1/14-sha1) are not offered; very old servers that only support those
 * will fail with a "no matching algorithm" error.
 */
final class RemoteSftp {
    private RemoteSftp() {}

    private static final int CONNECT_MS = 15_000, READ_MS = 120_000;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._:\\-]{1,253}");

    private static final String HOST_KEYS = "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256";
    private static final String KEX = "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,"
            + "ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,diffie-hellman-group14-sha256";
    private static final String CIPHERS = "aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr";
    private static final String MACS = "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512";

    // ---- host key repository ----------------------------------------------------------------------------------

    /** SHA-256 fingerprint in OpenSSH notation: "SHA256:" + unpadded Base64. */
    static String fingerprint(byte[] keyBlob) {
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(RemoteUtil.sha256(keyBlob));
    }

    /** Key type name is the first length-prefixed string of the wire-format key blob. */
    static String keyType(byte[] blob) {
        try {
            int n = ((blob[0] & 0xff) << 24) | ((blob[1] & 0xff) << 16) | ((blob[2] & 0xff) << 8) | (blob[3] & 0xff);
            if (n > 0 && n < 64 && blob.length >= 4 + n) return RemoteUtil.stripCtl(new String(blob, 4, n, StandardCharsets.US_ASCII));
        } catch (RuntimeException ignored) { }
        return "unknown";
    }

    /** HostKeyRepository with StrictHostKeyChecking=ask semantics backed by the UI callbacks. */
    static final class TrustRepo implements HostKeyRepository {
        private final String hostPort;
        private final RemoteClient.Ui ui;
        volatile String failure;

        TrustRepo(String hostPort, RemoteClient.Ui ui) { this.hostPort = hostPort; this.ui = ui; }

        @Override public int check(String ignoredHost, byte[] key) {
            String fp = fingerprint(key);
            if (ui == null) {
                failure = "The SSH host key of " + hostPort + " (" + fp + ") is not trusted yet and no confirmation is possible here.";
                return NOT_INCLUDED;
            }
            try {
                String trusted = ui.trustedHostKey(hostPort);
                if (trusted != null && trusted.equals(fp)) return OK;
                boolean changed = trusted != null;
                if (ui.confirmHostKey(hostPort, keyType(key), fp, changed)) {
                    ui.rememberHostKey(hostPort, fp);
                    return OK;
                }
                failure = changed
                        ? "The SSH host key of " + hostPort + " has CHANGED and was not accepted. Connection aborted; no credentials were sent."
                        : "The SSH host key of " + hostPort + " was not accepted. Connection aborted; no credentials were sent.";
                return changed ? CHANGED : NOT_INCLUDED;
            } catch (RuntimeException e) {
                failure = "The SSH host key could not be verified (" + e.getClass().getSimpleName() + "). Connection aborted.";
                return NOT_INCLUDED;
            }
        }
        @Override public void add(HostKey hostkey, UserInfo ui) { }
        @Override public void remove(String host, String type) { }
        @Override public void remove(String host, String type, byte[] key) { }
        @Override public String getKnownHostsRepositoryID() { return "BurpShot"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    // ---- connection ------------------------------------------------------------------------------------------

    private static final class Conn implements AutoCloseable {
        Session session; String hostPort; String host; int port; List<String> dirSegs; boolean absDir; Cfg c;
        @Override public void close() { if (session != null) session.disconnect(); }
    }

    private static Conn connect(Cfg c, RemoteClient.Ui ui) throws RemoteException {
        Conn k = new Conn();
        k.c = c;
        k.host = c.req("host");
        if (!HOST.matcher(k.host).matches()) throw new RemoteException("The host name contains invalid characters.", null);
        try {
            String ps = c.trim("port");
            k.port = ps.isEmpty() ? 22 : Integer.parseInt(ps);
        } catch (NumberFormatException e) {
            throw new RemoteException("The port is not a number.", null);
        }
        if (k.port < 1 || k.port > 65535) throw new RemoteException("The port must be between 1 and 65535.", null);
        String user = c.req("user");
        RemoteUtil.noCtl("User name", user);
        String dir = c.get("dir").trim();
        RemoteUtil.noCtl("Directory", dir);
        k.absDir = dir.startsWith("/") || dir.startsWith("\\");
        k.dirSegs = RemoteUtil.sanitizeSegments(dir);
        k.hostPort = k.host + ":" + k.port;

        boolean keyAuth = "private key".equals(c.trim("auth"));
        byte[] pw = null, key = null, pass = null;
        TrustRepo repo = new TrustRepo(k.hostPort, ui);
        try {
            JSch jsch = new JSch();
            jsch.setHostKeyRepository(repo);
            if (keyAuth) {
                key = privateKey(c);
                String pp = c.get("passphrase");
                pass = pp.isEmpty() ? null : pp.getBytes(StandardCharsets.UTF_8);
                try {
                    jsch.addIdentity("burpshot", key, null, pass);   // in memory; nothing written to disk
                } catch (JSchException e) {
                    throw new RemoteException("The private key could not be read (wrong passphrase, or unsupported key format).", null);
                }
            } else {
                pw = c.get("password").getBytes(StandardCharsets.UTF_8);
            }
            Session s = jsch.getSession(user, k.host, k.port);
            k.session = s;
            if (pw != null) s.setPassword(pw);
            s.setConfig("StrictHostKeyChecking", "yes");   // everything not OK from TrustRepo aborts
            s.setConfig("server_host_key", HOST_KEYS);
            s.setConfig("PubkeyAcceptedAlgorithms", HOST_KEYS);
            s.setConfig("kex", KEX);
            s.setConfig("cipher.s2c", CIPHERS);
            s.setConfig("cipher.c2s", CIPHERS);
            s.setConfig("mac.s2c", MACS);
            s.setConfig("mac.c2s", MACS);
            s.setConfig("PreferredAuthentications", keyAuth ? "publickey" : "password,keyboard-interactive");
            s.setTimeout(READ_MS);
            RemoteClient.prog(ui, "Connecting to " + k.hostPort + " ...");
            s.connect(CONNECT_MS);
            return k;
        } catch (JSchException e) {
            k.close();
            throw mapSsh(c, repo, e);
        } catch (RemoteException e) {
            k.close();
            throw e;
        } finally {
            for (byte[] b : new byte[][]{pw, key, pass}) if (b != null) Arrays.fill(b, (byte) 0);
        }
    }

    private static byte[] privateKey(Cfg c) throws RemoteException {
        String text = c.get("keyText");
        if (!text.isBlank()) {
            String t = text.trim().replace("\r\n", "\n") + "\n";
            return t.getBytes(StandardCharsets.UTF_8);
        }
        String f = c.trim("keyFile");
        if (f.isEmpty()) throw new RemoteException("Provide the private key text or a key file.", null);
        try {
            java.nio.file.Path p = Paths.get(f);
            if (Files.size(p) > 64 * 1024) throw new RemoteException("The key file is unexpectedly large.", null);
            return Files.readAllBytes(p);
        } catch (IOException | RuntimeException e) {
            if (e instanceof RemoteException) throw (RemoteException) e;
            throw new RemoteException("The private key file could not be read.", null);
        }
    }

    private static RemoteException mapSsh(Cfg c, TrustRepo repo, JSchException e) {
        if (repo.failure != null) return new RemoteException(repo.failure, null);
        String m = e.getMessage() == null ? "" : e.getMessage();
        String low = m.toLowerCase();
        if (low.contains("auth fail") || low.contains("auth cancel")) {
            return new RemoteException("SSH authentication failed (wrong user name, password or key, or the method is not allowed).", null);
        }
        if (low.contains("algorithm negotiation fail") || low.contains("no matching")) {
            return new RemoteException("SSH negotiation failed: the server offers no algorithm BurpShot accepts "
                    + "(weak SHA-1 RSA, CBC and 3DES algorithms are disabled).", null);
        }
        return new RemoteException("SSH error: " + RemoteUtil.stripCtl(c.redact(m)), null);
    }

    // ---- SFTP ---------------------------------------------------------------------------------------------------

    private static void cdCreate(ChannelSftp ch, Conn k, boolean create) throws SftpException, RemoteException {
        if (k.absDir) ch.cd("/");
        for (String seg : k.dirSegs) {
            try {
                ch.cd(seg);
            } catch (SftpException e) {
                if (!create) throw e;
                ch.mkdir(seg);
                ch.cd(seg);
            }
        }
    }

    private static String location(Conn k, String pwd, String name) {
        List<String> segs = new ArrayList<>(RemoteUtil.sanitizeSegments(pwd));
        if (!name.isEmpty()) segs.add(name);
        return "sftp://" + k.host + (k.port == 22 ? "" : ":" + k.port) + "/" + RemoteUtil.encodePath(segs);
    }

    private static RemoteException sftpFail(Cfg c, SftpException e) {
        String m = c.redact(e.getMessage() == null ? "" : e.getMessage());
        return new RemoteException("SFTP error: " + RemoteUtil.stripCtl(m), null);
    }

    static Result upload(Cfg c, String name, byte[] data, RemoteClient.Ui ui) throws RemoteException {
        try (Conn k = connect(c, ui)) {
            if ("scp".equals(c.trim("protocol"))) return scpUpload(c, k, name, data, ui);
            ChannelSftp ch = null;
            try {
                ch = (ChannelSftp) k.session.openChannel("sftp");
                ch.connect(CONNECT_MS);
                cdCreate(ch, k, true);
                RemoteClient.prog(ui, "Uploading " + name + " ...");
                try (InputStream in = new ByteArrayInputStream(data)) {
                    ch.put(in, name, ChannelSftp.OVERWRITE);
                }
                return new Result(location(k, ch.pwd(), name), "SFTP, " + data.length + " bytes");
            } catch (SftpException e) {
                throw sftpFail(c, e);
            } catch (JSchException | IOException e) {
                throw new RemoteException("SFTP error: " + RemoteUtil.stripCtl(c.redact(String.valueOf(e.getMessage()))), null);
            } finally {
                if (ch != null) ch.disconnect();
            }
        }
    }

    static Result test(Cfg c, RemoteClient.Ui ui) throws RemoteException {
        try (Conn k = connect(c, ui)) {
            if ("scp".equals(c.trim("protocol"))) {
                return new Result(location(k, String.join("/", k.dirSegs), ""),
                        "SSH login OK. SCP cannot be probed without leaving a file, so the transfer itself was not tested.");
            }
            ChannelSftp ch = null;
            try {
                ch = (ChannelSftp) k.session.openChannel("sftp");
                ch.connect(CONNECT_MS);
                try {
                    cdCreate(ch, k, false);
                } catch (SftpException e) {
                    return new Result(location(k, String.join("/", k.dirSegs), ""),
                            "Login OK; the remote directory does not exist yet and will be created on upload.");
                }
                String probe = RemoteClient.probeName();
                try (InputStream in = new ByteArrayInputStream(RemoteClient.PROBE)) {
                    ch.put(in, probe, ChannelSftp.OVERWRITE);
                }
                String del;
                try {
                    ch.rm(probe);
                    del = "probe deleted";
                } catch (SftpException e) {
                    del = "probe could NOT be deleted, remove " + probe + " manually";
                }
                return new Result(location(k, ch.pwd(), ""), "Login OK, upload OK; " + del);
            } catch (SftpException e) {
                throw sftpFail(c, e);
            } catch (JSchException | IOException e) {
                throw new RemoteException("SFTP error: " + RemoteUtil.stripCtl(c.redact(String.valueOf(e.getMessage()))), null);
            } finally {
                if (ch != null) ch.disconnect();
            }
        }
    }

    // ---- SCP (source side of the scp protocol, "scp -t <dir>" on the server) ----------------------------------

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void scpAck(InputStream in, Cfg c, ByteArrayOutputStream err) throws IOException, RemoteException {
        int b = in.read();
        if (b == 0) return;
        if (b == -1) {
            String e = err.size() > 0 ? ": " + RemoteUtil.stripCtl(err.toString(StandardCharsets.UTF_8)).trim() : "";
            throw new RemoteException("SCP failed, the server closed the channel" + c.redact(e.length() > 200 ? e.substring(0, 200) : e), null);
        }
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1 && ch != '\n' && sb.length() < 200) sb.append((char) ch);
        throw new RemoteException("SCP error: " + RemoteUtil.stripCtl(c.redact(sb.toString())), null);
    }

    private static Result scpUpload(Cfg c, Conn k, String name, byte[] data, RemoteClient.Ui ui) throws RemoteException {
        String dir = (k.absDir ? "/" : "") + String.join("/", k.dirSegs);
        if (dir.isEmpty()) dir = ".";
        if (dir.startsWith("-")) dir = "./" + dir;
        ChannelExec ch = null;
        try {
            ch = (ChannelExec) k.session.openChannel("exec");
            ch.setCommand("scp -t " + shellQuote(dir));
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            ch.setErrStream(err);
            OutputStream out = ch.getOutputStream();
            InputStream in = ch.getInputStream();
            ch.connect(CONNECT_MS);
            scpAck(in, c, err);
            RemoteClient.prog(ui, "Uploading " + name + " ...");
            out.write(("C0644 " + data.length + " " + name + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            scpAck(in, c, err);
            out.write(data);
            out.write(0);
            out.flush();
            scpAck(in, c, err);
            return new Result(location(k, dir, name), "SCP, " + data.length + " bytes");
        } catch (JSchException | IOException e) {
            throw new RemoteException("SCP error: " + RemoteUtil.stripCtl(c.redact(String.valueOf(e.getMessage()))), null);
        } finally {
            if (ch != null) ch.disconnect();
        }
    }
}
