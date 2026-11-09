package ptshot;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** Random sequences of user actions against Persist; after every step the stored data must still be unlockable with the current password. */
public class Fuzz {
    static final class Sink implements Persist.Sink {
        final List<Persist.Snap> loaded = Collections.synchronizedList(new ArrayList<>());
        volatile boolean done;

        public void loaded(String id, Store.Restored r) {
            loaded.add(new Persist.Snap(id, r.title, r.scale, r.maxWidth, r.blocks));
        }

        public void state(int locked, String m) {
            done = true;
        }

        public void error(String m) {
            errors.add(m);
            done = true;
        }

        final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        void await() throws Exception {
            for (int i = 0; i < 400 && !done; i++) Thread.sleep(25);
            CoreSelfTest.flushEdt();
        }
    }

    static String run(long seed, boolean folderMode, StringBuilder log) throws Exception {
        Random r = new Random(seed);
        Path tmp = Files.createTempDirectory("fuzz");
        CoreSelfTest.FakeEnv env = new CoreSelfTest.FakeEnv("Fuzz Project");
        if (folderMode) {
            env.prefs.put("ptshot.store.mode", "DIRECTORY");
            env.prefs.put("ptshot.store.dir", tmp.toString());
        }
        Settings st = new Settings();
        Persist p = new Persist(env);
        Sink sink = new Sink();
        p.start(sink);
        sink.await();
        List<Persist.Snap> open = new ArrayList<>();
        String pw = null;
        int n = 0;
        for (int step = 0; step < 14; step++) {
            int op = r.nextInt(9);
            String what = null;
            CoreSelfTest.Done d = new CoreSelfTest.Done();
            switch (op) {
                case 0: // enable / change password
                    if (p.lockedCount() > 0 || (p.encrypt() && !p.unlocked())) break;
                    String np = "password-" + r.nextInt(1000000);
                    what = (p.encrypt() ? "change" : "enable") + " password " + np;
                    p.setPassword(np.toCharArray(), new ArrayList<>(open), d);
                    String e0 = d.await();
                    if (e0 != null) return log + what + " FAILED: " + e0;
                    pw = np;
                    break;
                case 1: // disable
                    if (!p.encrypt() || !p.unlocked()) break;
                    what = "disable encryption";
                    p.disableEncryption(new ArrayList<>(open), d);
                    String e1 = d.await();
                    if (e1 != null) return log + what + " FAILED: " + e1;
                    break;
                case 2: // open an imported capture (fresh id) and save it
                case 3:
                    if (p.cannotSave()) break;
                    Store.Restored x = new Store.Restored();
                    x.title = "cap" + (n++);
                    x.scale = 2;
                    x.maxWidth = 900;
                    x.blocks.addAll(CoreSelfTest.sample(st));
                    Persist.Snap sn = new Persist.Snap(UUID.randomUUID().toString(), x.title, 2, 900, x.blocks);
                    open.add(sn);
                    what = "open capture file";
                    p.save(sn);
                    p.writeIndex(ids(open));
                    break;
                case 4: // close a tab
                    if (open.isEmpty()) break;
                    Persist.Snap c = open.remove(r.nextInt(open.size()));
                    what = "close tab";
                    p.delete(c.id);
                    p.writeIndex(ids(open));
                    break;
                case 5: // lock now
                    if (!p.encrypt() || !p.unlocked()) break;
                    what = "lock now";
                    for (Persist.Snap s2 : open) p.save(s2); // saveNow()
                    p.lock(d);
                    d.await();
                    open.clear();
                    break;
                case 6: // unlock with the correct password
                    if (p.lockedCount() == 0) break;
                    what = "unlock with the CORRECT password";
                    sink.loaded.clear();
                    p.unlock(pw.toCharArray(), d);
                    String e6 = d.await();
                    Thread.sleep(50);
                    CoreSelfTest.flushEdt();
                    if (e6 != null) return log + what + " -> FAILED: " + e6;
                    open = new ArrayList<>(sink.loaded);
                    break;
                case 7: // restart Burp: a new Persist on the same stored data
                    p.flush(10000);
                    p.shutdown();
                    p = new Persist(env);
                    sink = new Sink();
                    p.start(sink);
                    sink.await();
                    Thread.sleep(50);
                    CoreSelfTest.flushEdt();
                    open = new ArrayList<>(sink.loaded);
                    what = "restart Burp (" + open.size() + " loaded, " + p.lockedCount() + " locked)";
                    break;
                case 8: // move to another location
                    if (!folderMode || p.cannotSave()) break;
                    break;
                default:
            }
            if (what != null) log.append(what).append("; ");
            p.flush(10000);
            // INVARIANT: with the correct password every stored capture opens; nothing is silently unreadable
            if (pw != null && p.encrypt()) {
                CaptureBackend be = folderMode ? new DirectoryBackend(tmp, "Fuzz Project") : env.proj;
                for (String id : be.index()) {
                    byte[] raw = be.read(id);
                    if (raw == null) return log + "[invariant] index lists " + id + " but the data is missing";
                    if (!Vault.isEncrypted(raw)) return log + "[invariant] encryption is ON but capture " + id + " is stored UNENCRYPTED";
                    try {
                        Persist.decryptWith(pw.toCharArray(), raw);
                    } catch (Exception ex) {
                        return log + "[invariant] capture " + id + " cannot be decrypted with the current password (" + ex.getMessage() + ")";
                    }
                }
            }
        }
        p.shutdown();
        return null;
    }

    static List<String> ids(List<Persist.Snap> l) {
        List<String> o = new ArrayList<>();
        for (Persist.Snap s : l) o.add(s.id);
        return o;
    }

    public static void main(String[] a) throws Exception {
        Vault.defaultIterations = Vault.ITER_MIN;
        int runs = Integer.parseInt(a[0]);
        int bad = 0;
        for (int i = 0; i < runs; i++) {
            for (boolean folder : new boolean[]{false, true}) {
                StringBuilder log = new StringBuilder();
                String res = run(1000 + i, folder, log);
                if (res != null) {
                    bad++;
                    System.out.println("FAIL seed=" + (1000 + i) + " folder=" + folder + "\n   " + res);
                    if (bad >= 5) System.exit(1);
                }
            }
        }
        System.out.println(bad == 0 ? "no failing sequence in " + runs * 2 + " runs" : bad + " failing sequences");
        System.exit(bad == 0 ? 0 : 1);
    }
}
