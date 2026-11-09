package ptshot;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class CoreSelfTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) fails++;
    }

    interface Callable {
        void run() throws Throwable;
    }

    static boolean throwsAny(Callable c) {
        try {
            c.run();
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    static List<Block> sample(Settings st) {
        List<Block> l = new ArrayList<>();
        Block rq = Block.http(Block.Kind.REQUEST, "POST /api HTTP/1.1\r\nHost: SECRET-HOST.example\r\n\r\n{\"token\":\"TOPSECRET-VALUE\"}", st);
        rq.annotations.add(Annotation.create(Annotation.Type.BOX, "TOPSECRET-VALUE", 0));
        l.add(rq);
        l.add(Block.table("t", Templates.all().get("Intruder - attack results"), st));
        l.add(Block.terminal("Ubuntu", st));
        BufferedImage im = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 200; y++) for (int x = 0; x < 300; x++) im.setRGB(x, y, (x * 7 + y * 3) & 0xffffff);
        l.add(Block.image(im));
        BufferedImage dr = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        dr.setRGB(10, 10, 0xffff0000);
        l.add(Block.drawing(dr, 200));
        return l;
    }

    public static void main(String[] a) throws Exception {
        Settings st = new Settings();
        // ---------------- Vault
        final Vault v = new Vault();
        v.setNew("correct horse battery".toCharArray());
        final byte[] plain = "hello burpshot".getBytes(StandardCharsets.UTF_8);
        final byte[] enc = v.encrypt(plain);
        check("vault: encrypted data is recognised", Vault.isEncrypted(enc));
        check("vault: round trip", Arrays.equals(v.decrypt(enc), plain));
        check("vault: two encryptions of the same data differ (random IV)", !Arrays.equals(enc, v.encrypt(plain)));
        final byte[] t1 = enc.clone();
        t1[t1.length - 1] ^= 1;
        check("vault: modified ciphertext is rejected", throwsAny(() -> v.decrypt(t1)));
        final byte[] t2 = enc.clone();
        t2[20] ^= 1; // salt byte (the header is authenticated)
        check("vault: modified header (salt) is rejected", throwsAny(() -> v.decrypt(t2)));
        final byte[] t3 = enc.clone();
        t3[6] = 0x7f;
        t3[7] = (byte) 0xff;
        t3[8] = (byte) 0xff;
        t3[9] = (byte) 0xff; // ~2 billion iterations
        long t0 = System.nanoTime();
        boolean rejected = throwsAny(() -> v.decrypt(t3));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("vault: absurd iteration count rejected instantly (" + ms + " ms)", rejected && ms < 500);
        final byte[] t4 = enc.clone();
        t4[6] = 0;
        t4[7] = 0;
        t4[8] = 0;
        t4[9] = 1; // 1 iteration
        check("vault: tiny iteration count rejected", throwsAny(() -> v.decrypt(t4)));
        check("vault: truncated file rejected", throwsAny(() -> v.decrypt(Arrays.copyOf(enc, 30))));
        final Vault w = new Vault();
        boolean wrongPw = false;
        try {
            w.unlock("not the password".toCharArray(), enc);
        } catch (Vault.BadPasswordException e) {
            wrongPw = true;
        }
        check("vault: wrong password detected", wrongPw && !w.isUnlocked());
        w.unlock("correct horse battery".toCharArray(), enc);
        check("vault: right password unlocks a second vault instance", w.isUnlocked() && Arrays.equals(w.decrypt(enc), plain));
        w.lock();
        check("vault: lock() wipes the key", !w.isUnlocked() && throwsAny(() -> w.decrypt(enc)));

        // ---------------- Archive round trip
        List<Block> blocks = sample(st);
        byte[] zip = CaptureArchive.pack("11111111-2222-3333-4444-555555555555", "My capture", 2, 900, blocks, null);
        Store.Restored r = CaptureArchive.unpack(zip);
        check("archive: title/scale/width", r.title.equals("My capture") && r.scale == 2 && r.maxWidth == 900);
        check("archive: block count and kinds", r.blocks.size() == 5 && r.blocks.get(1).kind == Block.Kind.TABLE && r.blocks.get(2).kind == Block.Kind.TERMINAL);
        check("archive: annotation survives", r.blocks.get(0).annotations.size() == 1 && r.blocks.get(0).annotations.get(0).anchor.equals("TOPSECRET-VALUE"));
        check("archive: table rows survive", r.blocks.get(1).rows.size() == 5);
        check("archive: image pixels survive", r.blocks.get(3).image != null && r.blocks.get(3).image.getRGB(5, 5) == blocks.get(3).image.getRGB(5, 5));
        check("archive: drawing overlay + alpha survive", r.blocks.get(4).overlay && (r.blocks.get(4).image.getRGB(10, 10) >>> 24) == 255);
        byte[] zip2 = CaptureArchive.pack("11111111-2222-3333-4444-555555555555", "My capture", 2, 900, blocks, null);
        check("archive: packing again is byte-identical (cached images, fixed times)", Arrays.equals(zip, zip2));
        Store.Restored lg = Store.deserialize(Store.serialize("legacy", 2, 900, blocks));
        check("legacy inline-image format still loads", lg.blocks.size() == 5 && lg.blocks.get(3).image != null);

        // ---------------- hostile archives
        check("archive: path traversal entry rejected", throwsAny(() -> CaptureArchive.unpack(zipWith("../../evil.txt", new byte[10]))));
        check("archive: unexpected entry name rejected", throwsAny(() -> CaptureArchive.unpack(zipWith("payload.exe", new byte[10]))));
        check("archive: absolute path entry rejected", throwsAny(() -> CaptureArchive.unpack(zipWith("/etc/passwd", new byte[10]))));
        check("archive: garbage bytes rejected", throwsAny(() -> CaptureArchive.unpack("not a zip at all, really not".getBytes())));
        long tb = System.nanoTime();
        boolean bombRejected = throwsAny(() -> CaptureArchive.unpack(bomb(700L << 20)));
        check("archive: 700 MB zip bomb rejected without exhausting memory (" + (System.nanoTime() - tb) / 1_000_000 + " ms)", bombRejected);
        final byte[] hugePng = bigHeaderPng();
        check("png: declared 60000x60000 image refused before decoding", throwsAny(() -> Png.decode(hugePng)));
        check("png: non-PNG bytes refused", throwsAny(() -> Png.decode("GIF89a....".getBytes())));

        // ---------------- JSON parser / loader limits
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100000; i++) deep.append('[');
        boolean noOverflow;
        try {
            Mini.parse(deep.toString());
            noOverflow = false;
        } catch (IllegalArgumentException e) {
            noOverflow = true;
        } catch (Throwable t) {
            noOverflow = false;
        }
        check("json: 100000-deep nesting rejected cleanly (no StackOverflowError)", noOverflow);
        check("json: trailing garbage rejected", throwsAny(() -> Mini.parse("{\"a\":1} xyz")));
        check("json: unterminated string rejected", throwsAny(() -> Mini.parse("{\"a\":\"abc")));
        check("json: bad unicode escape rejected", throwsAny(() -> Mini.parse("\"\\u12\"")));
        check("loader: unknown block kind rejected", throwsAny(() -> Store.deserialize("{\"blocks\":[{\"kind\":\"EVIL\"}]}")));
        Store.Restored nan = Store.deserialize("{\"scale\":1e999,\"maxWidth\":99999999,\"blocks\":[{\"kind\":\"CUSTOM\",\"offX\":1e999,\"imageW\":-5,\"wrapCols\":123456789}]}");
        check("loader: infinite / out-of-range numbers are clamped to safe values", nan.scale == 2.0 && nan.maxWidth == 20000 && nan.blocks.get(0).offX == 0 && nan.blocks.get(0).imageW == 0 && nan.blocks.get(0).wrapCols == 2000);
        final StringBuilder many = new StringBuilder("{\"blocks\":[");
        for (int i = 0; i < 600; i++) many.append(i == 0 ? "" : ",").append("{\"kind\":\"CUSTOM\"}");
        many.append("]}");
        check("loader: 600 blocks rejected (limit 500)", throwsAny(() -> Store.deserialize(many.toString())));
        check("loader: a JSON array instead of an object rejected", throwsAny(() -> Store.deserialize("[1,2,3]")));

        // ---------------- Persist on a real folder
        Path tmp = Files.createTempDirectory("burpshot-test");
        FakeEnv env = new FakeEnv("Client A / Q4 \"pentest\"");
        env.prefs.put("ptshot.store.mode", "DIRECTORY");
        env.prefs.put("ptshot.store.dir", tmp.toString());
        Persist p = new Persist(env);
        check("persist: folder mode active", p.mode() == Persist.Mode.DIRECTORY);
        Persist.Snap s1 = new Persist.Snap("aaaaaaaa-0000-0000-0000-000000000001", "one", 2, 900, blocks);
        long tsnap = System.nanoTime();
        for (int i = 0; i < 6; i++) new Persist.Snap("x" + i + "xxxxxxx", "t", 2, 900, blocks);
        System.out.println("INFO  6 snapshots (what the UI thread now does per save): " + (System.nanoTime() - tsnap) / 1_000_000 + " ms");
        p.save(s1);
        p.writeIndex(Arrays.asList(s1.id));
        p.flush(10000);
        Path proj = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(tmp)) {
            for (Path x : ds) if (Files.isDirectory(x)) proj = x;
        }
        check("persist: project folder created with a sanitised name: " + (proj == null ? "?" : proj.getFileName()), proj != null && proj.getFileName().toString().matches("[A-Za-z0-9._ -]+"));
        byte[] onDisk = Files.readAllBytes(proj.resolve(s1.id + ".burpshot"));
        check("persist: unencrypted file is a ZIP", onDisk[0] == 'P' && onDisk[1] == 'K');

        Loader ld = new Loader();
        new Persist(env).start(ld);
        ld.await();
        check("persist: reopened (new session) loads the saved capture", ld.loaded.size() == 1 && ld.loaded.get(0).blocks.size() == 5);

        // encryption
        List<Persist.Snap> all = Arrays.asList(s1);
        Done d = new Done();
        p.setPassword("a strong passphrase".toCharArray(), all, d);
        check("persist: enabling encryption re-writes captures", d.await() == null && p.encrypt());
        byte[] enc2 = Files.readAllBytes(proj.resolve(s1.id + ".burpshot"));
        String asText = new String(enc2, StandardCharsets.ISO_8859_1);
        check("persist: file on disk is now BSV1 ciphertext", Vault.isEncrypted(enc2));
        check("persist: no secret strings or PNG data visible in the encrypted file", !asText.contains("TOPSECRET-VALUE") && !asText.contains("SECRET-HOST") && !asText.contains("capture.json") && !asText.contains("IHDR") && !asText.contains("0089PNG"));
        Loader ld2 = new Loader();
        Persist p2 = new Persist(env);
        p2.start(ld2);
        ld2.await();
        check("persist: new session sees the capture as LOCKED, nothing loaded", ld2.loaded.isEmpty() && p2.lockedCount() == 1);
        Done bad = new Done();
        p2.unlock("wrong password!".toCharArray(), bad);
        check("persist: wrong password refused", bad.await() != null && ld2.loaded.isEmpty());
        Done good = new Done();
        p2.unlock("a strong passphrase".toCharArray(), good);
        check("persist: right password unlocks and loads", good.await() == null);
        Thread.sleep(300);
        flushEdt();
        check("persist: unlocked capture is loaded with its content", ld2.loaded.size() == 1 && ld2.loaded.get(0).blocks.get(0).text.contains("TOPSECRET-VALUE"));
        p2.save(new Persist.Snap(s1.id, "edited", 2, 900, blocks));
        p2.flush(10000);
        check("persist: saves after unlock stay encrypted", Vault.isEncrypted(Files.readAllBytes(proj.resolve(s1.id + ".burpshot"))));
        Persist p3 = new Persist(env);
        boolean accepted = p3.save(new Persist.Snap("bbbbbbbb-0000-0000-0000-000000000002", "x", 2, 900, blocks));
        p3.flush(5000);
        check("persist: while locked, a save is refused (never falls back to plaintext)", !accepted && !Files.exists(proj.resolve("bbbbbbbb-0000-0000-0000-000000000002.burpshot")));
        Done rel = new Done();
        p2.relocate(Persist.Mode.SESSION, "", Arrays.asList(new Persist.Snap(s1.id, "edited", 2, 900, blocks)), rel);
        check("persist: relocate to this-session-only works", rel.await() == null && p2.mode() == Persist.Mode.SESSION);
        byte[] expEnc = p2.exportArchive(new Persist.Snap(s1.id, "x", 2, 900, blocks), Png.encode(blocks.get(3).image), true);
        check("export: encrypted archive is ciphertext", Vault.isEncrypted(expEnc));
        byte[] dec = Persist.decryptWith("a strong passphrase".toCharArray(), expEnc);
        check("export: decrypts with only the password (no session state) and opens", CaptureArchive.unpack(dec).blocks.size() == 5);
        check("export: rendered output.png travels with the archive", Png.looksLikePng(CaptureArchive.outputPng(dec)));
        check("export: wrong password refused", throwsAny(() -> Persist.decryptWith("nope nope nope".toCharArray(), expEnc)));
        byte[] pngEnc = p2.encryptBytes(Png.encode(blocks.get(3).image));
        check("export: encrypted PNG round trip", Png.looksLikePng(Persist.decryptWith("a strong passphrase".toCharArray(), pngEnc)));
        byte[] plainZip = p2.exportArchive(new Persist.Snap(s1.id, "x", 2, 900, blocks), null, false);
        check("export: unencrypted archive is a plain zip", plainZip[0] == 'P' && !Vault.isEncrypted(plainZip));
        check("safeName: traversal neutralised", !DirectoryBackend.safeName("../../etc/passwd").contains("/") && !DirectoryBackend.safeName("..\\..\\x").contains("\\"));
        check("safeName: reserved Windows name prefixed", DirectoryBackend.safeName("CON").startsWith("_"));
        check("safeName: empty -> project", DirectoryBackend.safeName("").equals("project"));
        check("validFolder: relative / traversal / NUL refused", !Persist.validFolder("relative/dir") && !Persist.validFolder("/a/../b") && !Persist.validFolder("/a\0b"));
        check("ids: path-like capture ids refused", !CaptureBackend.validId("../x") && !CaptureBackend.validId("a/b") && CaptureBackend.validId("11111111-2222-3333-4444-555555555555"));
        p.shutdown();
        p2.shutdown();
        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void flushEdt() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
    }

    static byte[] zipWith(String name, byte[] data) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry(name));
            z.write(data);
            z.closeEntry();
        }
        return o.toByteArray();
    }

    static byte[] bomb(long size) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry("capture.json"));
            byte[] chunk = new byte[1 << 20];
            Arrays.fill(chunk, (byte) ' ');
            for (long w = 0; w < size; w += chunk.length) z.write(chunk);
            z.closeEntry();
        }
        return o.toByteArray();
    }

    static byte[] bigHeaderPng() throws IOException {
        BufferedImage bi = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi, "png", o);
        byte[] b = o.toByteArray();
        int w = 60000; // IHDR starts at offset 8: length(4) 'IHDR'(4) width(4) height(4) ...
        for (int i = 0; i < 4; i++) {
            b[16 + i] = (byte) (w >> (24 - 8 * i));
            b[20 + i] = (byte) (w >> (24 - 8 * i));
        }
        CRC32 crc = new CRC32();
        crc.update(b, 12, 17);
        long c = crc.getValue();
        for (int i = 0; i < 4; i++) b[29 + i] = (byte) (c >> (24 - 8 * i));
        return b;
    }

    static final class FakeEnv implements Persist.Env {
        final Map<String, String> prefs = new HashMap<>();
        final String name;
        final MemoryBackend proj = new MemoryBackend();

        FakeEnv(String n) {
            name = n;
        }

        public String projectName() {
            return name;
        }

        public CaptureBackend projectBackend() {
            return proj;
        }

        public String pref(String k) {
            return prefs.get(k);
        }

        public void setPref(String k, String v) {
            prefs.put(k, v);
        }
    }

    static final class Loader implements Persist.Sink {
        final List<Store.Restored> loaded = Collections.synchronizedList(new ArrayList<>());
        volatile boolean done;

        public void loaded(String id, Store.Restored c) {
            loaded.add(c);
        }

        public void state(int n, String m) {
            done = true;
        }

        public void error(String m) {
            System.out.println("      (sink error: " + m + ")");
            done = true;
        }

        void await() throws Exception {
            for (int i = 0; i < 100 && !done; i++) Thread.sleep(50);
            flushEdt();
        }
    }

    static final class Done implements Persist.Done {
        volatile boolean fin;
        volatile String err;

        public void done(String e) {
            err = e;
            fin = true;
        }

        String await() throws Exception {
            for (int i = 0; i < 400 && !fin; i++) Thread.sleep(50);
            flushEdt();
            return err;
        }
    }
}
