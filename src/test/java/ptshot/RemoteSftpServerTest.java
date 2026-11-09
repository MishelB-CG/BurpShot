package ptshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.scp.server.ScpCommandFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import ptshot.RemoteClient.RemoteException;
import ptshot.RemoteClient.Result;

/**
 * SFTP/SCP test against a real Apache MINA SSHD server started in-process.
 * NOT part of the library classpath: needs sshd-core, sshd-common, sshd-sftp, sshd-scp (2.12.x) and slf4j-api
 * on the classpath in addition to libs/jsch-*.jar (download from Maven Central into a scratch folder):
 *   javac -encoding UTF-8 --release 17 -cp "libs/jsch-2.28.8.jar;SSHD/*" -d OUT src/main/java/ptshot/Remote*.java \
 *         src/test/java/ptshot/RemoteSelfTest.java src/test/java/ptshot/RemoteSftpServerTest.java
 *   java -cp "OUT;libs/jsch-2.28.8.jar;SSHD/*" ptshot.RemoteSftpServerTest
 */
public final class RemoteSftpServerTest {
    static final List<String> order = new CopyOnWriteArrayList<>();
    static final AtomicInteger pwAttempts = new AtomicInteger(), keyAttempts = new AtomicInteger();

    static final class OrderedUi extends RemoteSelfTest.TestUi {
        @Override public boolean confirmHostKey(String hp, String t, String fp, boolean changed) {
            order.add("confirm");
            return super.confirmHostKey(hp, t, fp, changed);
        }
    }

    static String pkcs8Pem(KeyPair kp) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("sshd-root");
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        KeyPair hostKey = ec.generateKeyPair();
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        KeyPair clientKey = rsa.generateKeyPair();

        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        sshd.setPasswordAuthenticator((u, p, s) -> {
            order.add("auth-password");
            pwAttempts.incrementAndGet();
            return u.equals("bob") && p.equals("hunter2");
        });
        sshd.setPublickeyAuthenticator((u, k, s) -> {
            order.add("auth-key");
            keyAttempts.incrementAndGet();
            return u.equals("bob") && KeyUtils.compareKeys(k, clientKey.getPublic());
        });
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setCommandFactory(new ScpCommandFactory());
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(root));
        sshd.start();
        String port = "" + sshd.getPort();
        byte[] data = "sftp-payload\u0000\u0001".getBytes(StandardCharsets.ISO_8859_1);
        try {
            Map<String, String> pw = RemoteSelfTest.cfg("host", "127.0.0.1", "port", port, "protocol", "sftp", "user", "bob",
                    "auth", "password", "password", "hunter2", "dir", "a/b c");

            RemoteSelfTest.section("SFTP against a real SSH server (Apache MINA SSHD)");
            OrderedUi ui = new OrderedUi();
            ui.confirm = true;
            Result r = RemoteClient.upload("sftp", pw, "../x y.png", data, null, ui);
            RemoteSelfTest.check("unknown host key -> Ui.confirmHostKey asked (changed=false) and remembered",
                    ui.events.stream().anyMatch(e -> e.startsWith("confirm:127.0.0.1:" + port + ":ecdsa-sha2-nistp256:SHA256:") && e.endsWith(":false"))
                            && ui.events.contains("remember:127.0.0.1:" + port));
            RemoteSelfTest.check("host key confirmed BEFORE any credential reached the server",
                    order.indexOf("confirm") >= 0 && order.indexOf("auth-password") > order.indexOf("confirm"));
            RemoteSelfTest.check("SFTP password upload, dirs created recursively, name sanitised",
                    Arrays.equals(Files.readAllBytes(root.resolve("a/b c/x y.png")), data));
            RemoteSelfTest.check("result location has no secrets", r.location.equals("sftp://127.0.0.1:" + port + "/a/b%20c/x%20y.png"));

            int confirms = (int) ui.events.stream().filter(e -> e.startsWith("confirm")).count();
            RemoteClient.upload("sftp", pw, "second.png", data, null, ui);
            RemoteSelfTest.check("known host key -> no second prompt",
                    ui.events.stream().filter(e -> e.startsWith("confirm")).count() == confirms && Files.exists(root.resolve("a/b c/second.png")));

            // rejected unknown host key
            int before = pwAttempts.get();
            OrderedUi rej = new OrderedUi();
            rej.confirm = false;
            RemoteException e = RemoteSelfTest.expectFail("sftp", pw, data, rej);
            RemoteSelfTest.check("rejected host key -> failure, password never sent",
                    e != null && e.getMessage().contains("not accepted") && pwAttempts.get() == before && !e.getMessage().contains("hunter2"));

            // changed host key
            OrderedUi chg = new OrderedUi();
            chg.trusted.put("127.0.0.1:" + port, "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            chg.confirm = false;
            e = RemoteSelfTest.expectFail("sftp", pw, data, chg);
            RemoteSelfTest.check("changed host key refused (changed=true strong warning), password never sent",
                    e != null && e.getMessage().contains("CHANGED") && chg.lastChanged && pwAttempts.get() == before);
            chg.confirm = true;
            Files.deleteIfExists(root.resolve("a/b c/x y.png"));
            RemoteClient.upload("sftp", pw, "x y.png", data, null, chg);
            RemoteSelfTest.check("changed host key accepted only on explicit confirmation",
                    Files.exists(root.resolve("a/b c/x y.png")) && chg.lastChanged);

            // no Ui -> never trusted automatically
            before = pwAttempts.get();
            e = RemoteSelfTest.expectFail("sftp", pw, data, null);
            RemoteSelfTest.check("Ui == null -> not trusted, clear message, no credentials sent",
                    e != null && e.getMessage().contains("not trusted") && pwAttempts.get() == before);

            // wrong password
            Map<String, String> bad = new java.util.HashMap<>(pw);
            bad.put("password", "WRONG-PW-LEAK-1");
            OrderedUi t2 = new OrderedUi();
            t2.trusted.putAll(ui.trusted);
            e = RemoteSelfTest.expectFail("sftp", bad, data, t2);
            RemoteSelfTest.check("wrong password -> clear auth failure without the password",
                    e != null && e.getMessage().contains("authentication failed") && !e.getMessage().contains("WRONG-PW-LEAK-1"));

            // key authentication (PKCS#8 PEM text, kept in memory only)
            Map<String, String> key = RemoteSelfTest.cfg("host", "127.0.0.1", "port", port, "protocol", "sftp", "user", "bob",
                    "auth", "private key", "keyText", pkcs8Pem(clientKey), "dir", "keydir");
            int kb = keyAttempts.get();
            RemoteClient.upload("sftp", key, "k.png", data, null, t2);
            RemoteSelfTest.check("private key (text) authentication works", keyAttempts.get() > kb && Files.exists(root.resolve("keydir/k.png")));
            Path keyFile = Files.createTempFile("k", ".pem");
            Files.writeString(keyFile, pkcs8Pem(clientKey));
            Map<String, String> kf = new java.util.HashMap<>(key);
            kf.remove("keyText");
            kf.put("keyFile", keyFile.toString());
            RemoteClient.upload("sftp", kf, "kf.png", data, null, t2);
            RemoteSelfTest.check("private key (file) authentication works", Files.exists(root.resolve("keydir/kf.png")));
            Files.delete(keyFile);
            Map<String, String> garbage = new java.util.HashMap<>(key);
            garbage.put("keyText", "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----");
            e = RemoteSelfTest.expectFail("sftp", garbage, data, t2);
            RemoteSelfTest.check("unreadable key -> clean error", e != null && e.getMessage().contains("private key"));

            // SCP
            Files.createDirectories(root.resolve("scpdir"));
            Map<String, String> scp = new java.util.HashMap<>(pw);
            scp.put("protocol", "scp");
            scp.put("dir", "scpdir");
            r = RemoteClient.upload("sftp", scp, "s'cp.png", data, null, t2);
            RemoteSelfTest.check("SCP upload", Arrays.equals(Files.readAllBytes(root.resolve("scpdir/s'cp.png")), data));

            // test()
            Result t = RemoteClient.test("sftp", pw, t2);
            RemoteSelfTest.check("SFTP test(): probe uploaded and deleted", t.detail.contains("deleted")
                    && Files.list(root.resolve("a/b c")).noneMatch(p -> p.getFileName().toString().startsWith("burpshot-probe")));
            Map<String, String> missing = new java.util.HashMap<>(pw);
            missing.put("dir", "does/not/exist");
            t = RemoteClient.test("sftp", missing, t2);
            RemoteSelfTest.check("SFTP test(): missing dir reported, not created", t.detail.contains("does not exist") && !Files.exists(root.resolve("does")));
        } finally {
            sshd.stop(true);
        }
        System.out.println();
        System.out.println("passed=" + RemoteSelfTest.passed + " failed=" + RemoteSelfTest.failed);
        System.exit(RemoteSelfTest.failed == 0 ? 0 : 1);
    }
}
