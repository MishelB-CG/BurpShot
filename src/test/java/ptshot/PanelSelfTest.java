// Run: see README "Self-tests". Needs a display (it builds real Swing panels).
package ptshot;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

public class PanelSelfTest {
    static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    static Store.Restored heavy(Settings st, int n, Random r) {
        Store.Restored x = new Store.Restored();
        x.title = "heavy " + n;
        x.scale = 2;
        x.maxWidth = 900;
        x.blocks.add(Block.http(Block.Kind.REQUEST, "GET / HTTP/1.1\r\nHost: a.com\r\n\r\n", st));
        BufferedImage shot = new BufferedImage(1900, 1000, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 1000; y++) for (int xx = 0; xx < 1900; xx++) shot.setRGB(xx, y, ((xx / 7 + y / 11) % 5 == 0) ? 0x202020 : (0xF0F0F0 - (r.nextInt(6) << 16)));
        x.blocks.add(Block.image(shot));
        BufferedImage draw = new BufferedImage(1900, 1000, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = draw.createGraphics();
        g.setColor(java.awt.Color.RED);
        g.setStroke(new java.awt.BasicStroke(6));
        for (int i = 0; i < 40; i++) g.drawLine(r.nextInt(1900), r.nextInt(1000), r.nextInt(1900), r.nextInt(1000));
        g.dispose();
        x.blocks.add(Block.drawing(draw, 950));
        return x;
    }

    public static void main(String[] a) throws Exception {
        Path tmp = Files.createTempDirectory("burpshot-panel");
        CoreSelfTest.FakeEnv env = new CoreSelfTest.FakeEnv("PanelTest");
        env.prefs.put("ptshot.store.mode", "DIRECTORY");
        env.prefs.put("ptshot.store.dir", tmp.toString());
        final Settings st = new Settings();
        final Persist store = new Persist(env);
        final MainPanel[] mp = new MainPanel[1];
        edt(() -> mp[0] = new MainPanel(st, s -> { }, c -> { }, store));
        Random r = new Random(7);
        long t0 = System.nanoTime();
        for (int i = 0; i < 6; i++) {
            final int n = i;
            edt(() -> mp[0].openImported(heavy(st, n, r)));
        }
        System.out.println("INFO  building 6 heavy tabs on the UI thread: " + (System.nanoTime() - t0) / 1_000_000 + " ms (test setup, not the save)");
        final long[] saveMs = new long[1];
        edt(() -> {
            long s0 = System.nanoTime();
            mp[0].saveNow();
            saveMs[0] = (System.nanoTime() - s0) / 1_000_000;
        });
        System.out.println("RESULT  UI-thread time of saveNow() with 6 heavy tabs: " + saveMs[0] + " ms   (before this change: ~2500 ms, measured)");
        long b0 = System.nanoTime();
        store.flush(60000);
        System.out.println("INFO  background write of all 6 (first time: encodes 12 PNGs): " + (System.nanoTime() - b0) / 1_000_000 + " ms");
        // change something small in one tab: only that tab is written, images come from the cache
        final long[] ms2 = new long[1];
        edt(() -> {
            Component0.touch(mp[0]);
            long s0 = System.nanoTime();
            mp[0].saveNow();
            ms2[0] = (System.nanoTime() - s0) / 1_000_000;
        });
        long b1 = System.nanoTime();
        store.flush(60000);
        System.out.println("RESULT  after a small edit: UI thread " + ms2[0] + " ms, background " + (System.nanoTime() - b1) / 1_000_000 + " ms (one tab, cached images)");
        long files;
        try (java.util.stream.Stream<Path> s = Files.walk(tmp)) {
            files = s.filter(p -> p.toString().endsWith(".burpshot")).count();
        }
        System.out.println("INFO  " + files + " .burpshot files in " + tmp);

        // unload
        long u0 = System.nanoTime();
        mp[0].shutdown();
        System.out.println("RESULT  shutdown() (what Burp waits for at exit): " + (System.nanoTime() - u0) / 1_000_000 + " ms");

        // restart
        final Persist store2 = new Persist(env);
        final MainPanel[] mp2 = new MainPanel[1];
        long l0 = System.nanoTime();
        edt(() -> mp2[0] = new MainPanel(st, s -> { }, c -> { }, store2));
        int tabs = 0;
        for (int i = 0; i < 200; i++) {
            Thread.sleep(100);
            final int[] n = {0};
            edt(() -> n[0] = Component0.captureTabs(mp2[0]));
            tabs = n[0];
            if (tabs == 6) break;
        }
        System.out.println("RESULT  restart restored " + tabs + " of 6 tabs in " + (System.nanoTime() - l0) / 1_000_000 + " ms (loaded in the background, UI stayed responsive)");
        mp2[0].shutdown();
        System.exit(tabs == 6 ? 0 : 1);
    }

    /** Reflection helpers into MainPanel's private tab pane. */
    static final class Component0 {
        static JTabbedPane tabs(MainPanel m) {
            try {
                java.lang.reflect.Field f = MainPanel.class.getDeclaredField("tabs");
                f.setAccessible(true);
                return (JTabbedPane) f.get(m);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        static int captureTabs(MainPanel m) {
            int n = 0;
            for (java.awt.Component c : tabs(m).getComponents()) if (c instanceof CapturePanel) n++;
            return n;
        }

        static void touch(MainPanel m) {
            for (java.awt.Component c : tabs(m).getComponents()) {
                if (c instanceof CapturePanel) {
                    ((CapturePanel) c).blocks().get(0).text = ((CapturePanel) c).blocks().get(0).text + " ";
                    m.markDirty((CapturePanel) c);
                    return;
                }
            }
        }
    }
}
