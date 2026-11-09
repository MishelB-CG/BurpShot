package ptshot;

import java.util.*;

public class MigrateSelfTest {
    static final class LegacyBackend implements CaptureBackend, LegacySource {
        final MemoryBackend m = new MemoryBackend();
        List<String> legacy = new ArrayList<>();
        boolean cleared;

        public String describe() { return m.describe(); }
        public List<String> index() { return m.index(); }
        public void writeIndex(List<String> ids) { m.writeIndex(ids); }
        public byte[] read(String id) { return m.read(id); }
        public void write(String id, byte[] d) { m.write(id, d); }
        public void delete(String id) { m.delete(id); }

        public List<String> legacyJson() {
            return new ArrayList<>(legacy);
        }

        public void clearLegacy() {
            cleared = true;
            legacy.clear();
        }
    }

    static final class Env implements Persist.Env {
        final Map<String, String> prefs = new HashMap<>();
        final LegacyBackend be;

        Env(LegacyBackend b) {
            be = b;
        }

        public String projectName() {
            return "p";
        }

        public CaptureBackend projectBackend() {
            return be;
        }

        public String pref(String k) {
            return prefs.get(k);
        }

        public void setPref(String k, String v) {
            prefs.put(k, v);
        }
    }

    static int fails = 0;

    static void check(String n, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + n);
        if (!ok) fails++;
    }

    static List<String> wait(CoreSelfTest.Loader l, int want) throws Exception {
        for (int i = 0; i < 100 && l.loaded.size() < want; i++) Thread.sleep(50);
        CoreSelfTest.flushEdt();
        return null;
    }

    public static void main(String[] a) throws Exception {
        Settings st = new Settings();
        // two good legacy captures (with images inline, like the old format) and one corrupt one
        LegacyBackend be = new LegacyBackend();
        be.legacy.add(Store.serialize("old one", 2, 900, CoreSelfTest.sample(st)));
        be.legacy.add("{\"title\": \"broken\", \"blocks\": [{\"kind\": \"NOPE\"}]}");
        be.legacy.add(Store.serialize("old two", 1.5, 800, CoreSelfTest.sample(st)));
        Persist p = new Persist(new Env(be));
        CoreSelfTest.Loader ld = new CoreSelfTest.Loader();
        p.start(ld);
        ld.await();
        wait(ld, 2);
        check("migration: the two valid legacy captures are loaded", ld.loaded.size() == 2);
        check("migration: titles kept", ld.loaded.get(0).title.equals("old one") && ld.loaded.get(1).title.equals("old two"));
        check("migration: images survived (inline -> archive)", ld.loaded.get(0).blocks.get(3).image != null);
        check("migration: with one unreadable entry the legacy data is KEPT (nothing is thrown away)", !be.cleared && be.legacy.size() == 3);
        check("migration: new-format data exists next to it", be.index().size() == 2);

        // all-good case: legacy keys are removed afterwards, and a restart loads from the new format
        LegacyBackend be2 = new LegacyBackend();
        be2.legacy.add(Store.serialize("only", 2, 900, CoreSelfTest.sample(st)));
        Persist p2 = new Persist(new Env(be2));
        CoreSelfTest.Loader ld2 = new CoreSelfTest.Loader();
        p2.start(ld2);
        ld2.await();
        wait(ld2, 1);
        check("migration (all valid): loaded", ld2.loaded.size() == 1);
        check("migration (all valid): legacy keys removed afterwards", be2.cleared);
        Persist p3 = new Persist(new Env(be2));
        CoreSelfTest.Loader ld3 = new CoreSelfTest.Loader();
        p3.start(ld3);
        ld3.await();
        wait(ld3, 1);
        check("migration: second start reads the new format, no duplicates", ld3.loaded.size() == 1 && be2.index().size() == 1);
        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
