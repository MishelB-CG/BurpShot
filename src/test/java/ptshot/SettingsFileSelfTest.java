package ptshot;

import java.awt.Color;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Export / import of the global settings: round trip, and a hostile or wrong file can neither crash nor corrupt them. */
public class SettingsFileSelfTest {
    static int fails;

    static void check(String n, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + n);
        if (!ok) fails++;
    }

    static String error(File f) {
        try {
            SettingsFile.read(f);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("bsset").toFile();
        File f = new File(dir, "s.json");

        Settings s = new Settings();
        s.bodyFont = new FontSpec("Consolas", 18, true, false, new Color(10, 20, 30));
        s.background = new Color(240, 240, 200);
        s.margin = 33;
        s.maxWidth = 1200;
        s.scale = 3.0;
        s.wrap = false;
        s.labelRatio = 2.0;
        s.syn[Settings.K_PVAL] = new Color(255, 102, 0);
        SettingsFile.export(f, s);
        Settings r = SettingsFile.read(f);
        check("round trip: fonts, colours, spacing, defaults, syntax colours",
                r.bodyFont.family.equals("Consolas") && r.bodyFont.size == 18 && r.bodyFont.bold && r.background.equals(s.background) && r.margin == 33
                        && r.maxWidth == 1200 && r.scale == 3.0 && !r.wrap && r.labelRatio == 2.0 && r.syn[Settings.K_PVAL].equals(new Color(255, 102, 0)));
        Settings target = new Settings();
        target.copyFrom(r);
        check("copyFrom takes over every value", target.toMap().equals(r.toMap()));
        check("the exported file is small readable JSON", f.length() < 4000 && new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains("burpshot-settings"));
        check("no temp file is left behind", dir.list().length == 1);

        File bad = new File(dir, "bad.json");
        Files.write(bad.toPath(), "{\"format\":\"burpshot-settings\",\"version\":1,\"settings\":{\"margin\":\"999999999\",\"maxWidth\":\"-5\",\"scale\":\"1e9\",\"labelRatio\":\"NaN\",\"sepW\":\"0\",\"body\":\"X|99999|true|true|ffffff\",\"bg\":\"zzz\",\"unknown\":\"x\"}}".getBytes());
        Settings h = SettingsFile.read(bad);
        check("out-of-range values are clamped (margin " + h.margin + ", width " + h.maxWidth + ", scale " + h.scale + ")",
                h.margin == 100 && h.maxWidth == 0 && h.scale == 2.0 && h.labelRatio == 1.4 && h.sepWidth == 1 && h.bodyFont.size == 200 && h.background.equals(Color.WHITE));

        File wrong = new File(dir, "w.json");
        Files.write(wrong.toPath(), "{\"hello\":1}".getBytes());
        check("a JSON file that is not a settings file is refused", error(wrong) != null);
        Files.write(wrong.toPath(), "not json at all {{{".getBytes());
        check("garbage is refused", error(wrong) != null);
        Files.write(wrong.toPath(), "{\"format\":\"burpshot-settings\",\"version\":99,\"settings\":{}}".getBytes());
        check("a newer version is refused", error(wrong) != null && error(wrong).contains("newer"));
        Files.write(wrong.toPath(), new byte[300_000]);
        check("an oversized file is refused", error(wrong) != null);
        Files.write(wrong.toPath(), "{\"format\":\"burpshot-settings\",\"version\":1,\"settings\":{\"margin\":5,\"body\":{\"x\":1}}}".getBytes());
        Settings nt = SettingsFile.read(wrong);
        check("non-text values are ignored (defaults kept)", nt.margin == new Settings().margin && nt.bodyFont.family.equals(new Settings().bodyFont.family));

        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
