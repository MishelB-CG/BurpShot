package ptshot;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Save PNG: plain or encrypted, depending on the encryption setting and the user's choice. */
public class SavePngSelfTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) fails++;
    }

    static boolean throwsAny(CoreSelfTest.Callable c) {
        return CoreSelfTest.throwsAny(c);
    }

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("burpshot-savepng");
        CoreSelfTest.FakeEnv env = new CoreSelfTest.FakeEnv("p");
        final BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 80; y++) for (int x = 0; x < 120; x++) img.setRGB(x, y, (x * 2 << 16) | (y * 3 << 8) | 0x40);

        // ---- encryption off
        Persist p = new Persist(env);
        check("state: encryption off -> 0", Persist.screenshotEncryptionState() == 0);
        File plain = Persist.writeScreenshot(new File(dir.toFile(), "shot"), img, false);
        check("off: plain PNG written, .png added to a bare name", plain.getName().equals("shot.png") && Png.looksLikePng(Files.readAllBytes(plain.toPath())));
        check("off: pixels intact", Png.decode(Files.readAllBytes(plain.toPath())).getRGB(10, 10) == img.getRGB(10, 10));
        check("off: asking for encryption is refused and writes nothing", throwsAny(() -> Persist.writeScreenshot(new File(dir.toFile(), "nope.png"), img, true)) && !new File(dir.toFile(), "nope.png.bsv").exists());

        // ---- encryption on and unlocked
        CoreSelfTest.Done d = new CoreSelfTest.Done();
        p.setPassword("a strong passphrase".toCharArray(), new java.util.ArrayList<>(), d);
        check("password set", d.await() == null);
        check("state: on and unlocked -> 2", Persist.screenshotEncryptionState() == 2);
        File enc = Persist.writeScreenshot(new File(dir.toFile(), "evidence.png"), img, true);
        byte[] raw = Files.readAllBytes(enc.toPath());
        check("encrypted: file is named evidence.png.bsv", enc.getName().equals("evidence.png.bsv"));
        check("encrypted: file is a BSV1 container, not a PNG", Vault.isEncrypted(raw) && !Png.looksLikePng(raw));
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        check("encrypted: no PNG chunk names visible in the file", !text.contains("IHDR") && !text.contains("IDAT") && !text.contains("PNG"));
        byte[] back = Persist.decryptWith("a strong passphrase".toCharArray(), raw);
        check("encrypted: decrypts with the password to the identical image", Png.looksLikePng(back) && Png.decode(back).getRGB(10, 10) == img.getRGB(10, 10) && Png.decode(back).getRGB(119, 79) == img.getRGB(119, 79));
        check("encrypted: wrong password refused", throwsAny(() -> Persist.decryptWith("not the password".toCharArray(), raw)));
        File enc2 = Persist.writeScreenshot(new File(dir.toFile(), "x.png.bsv"), img, true);
        check("encrypted: a chosen name that already ends in .png.bsv is not doubled", enc2.getName().equals("x.png.bsv"));
        File enc3 = Persist.writeScreenshot(new File(dir.toFile(), "Y.PNG"), img, true);
        check("encrypted: upper-case .PNG is normalised", enc3.getName().equals("Y.png.bsv"));
        File opt = Persist.writeScreenshot(new File(dir.toFile(), "report.png"), img, false);
        check("encryption on, user unticks the box: a plain PNG for the report is still possible", Png.looksLikePng(Files.readAllBytes(opt.toPath())));

        // ---- encryption on, but locked (a new session before the password is entered)
        Persist locked = new Persist(env);
        check("state: on but locked -> 1", Persist.screenshotEncryptionState() == 1);
        check("locked: encrypted save is refused, nothing written", throwsAny(() -> Persist.writeScreenshot(new File(dir.toFile(), "locked.png"), img, true)) && !new File(dir.toFile(), "locked.png.bsv").exists());
        check("no half-written or temporary files left in the folder", noTemp(dir.toFile()));
        p.shutdown();
        locked.shutdown();
        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static boolean noTemp(File d) {
        String[] names = d.list();
        if (names == null) return true;
        for (String n : names) if (n.endsWith(".tmp") || n.startsWith(".")) return false;
        return true;
    }
}
