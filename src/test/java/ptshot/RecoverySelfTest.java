package ptshot;

import java.util.*;

/** Locked captures can never trap the user: mixed/foreign captures are judged one by one, and "Forgot the password" always gets out. */
public class RecoverySelfTest {
    static int fails;

    static void check(String n, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + n);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        Vault.defaultIterations = Vault.ITER_MIN;
        Settings st = new Settings();
        CoreSelfTest.FakeEnv env = new CoreSelfTest.FakeEnv("P");
        Persist p = new Persist(env);
        CoreSelfTest.Done d = new CoreSelfTest.Done();
        p.setPassword("first password".toCharArray(), new ArrayList<>(), d);
        d.await();
        String ida = "aaaaaaaa-0000-0000-0000-000000000001", idb = "bbbbbbbb-0000-0000-0000-000000000002", idc = "cccccccc-0000-0000-0000-000000000003";
        env.proj.write(ida, p.encryptBytes(CaptureArchive.pack(ida, "enc", 2, 900, CoreSelfTest.sample(st), null)));
        env.proj.write(idb, CaptureArchive.pack(idb, "plain", 2, 900, CoreSelfTest.sample(st), null));
        Persist other = new Persist(new CoreSelfTest.FakeEnv("Other"));
        CoreSelfTest.Done d0 = new CoreSelfTest.Done();
        other.setPassword("another password".toCharArray(), new ArrayList<>(), d0);
        d0.await();
        env.proj.write(idc, other.encryptBytes(CaptureArchive.pack(idc, "foreign", 2, 900, CoreSelfTest.sample(st), null)));
        env.proj.writeIndex(Arrays.asList(idb, idc, ida)); // the plain one comes first
        p.shutdown();

        Persist q = new Persist(env);
        Fuzz.Sink sink = new Fuzz.Sink();
        q.start(sink);
        sink.await();
        check("restart: encrypted ones are locked", q.lockedCount() == 2 && q.encrypt() && !q.unlocked());
        CoreSelfTest.Done w = new CoreSelfTest.Done();
        q.unlock("nobody knows".toCharArray(), w);
        check("wrong password is refused", w.await() != null && q.lockedCount() == 2);
        CoreSelfTest.Done u = new CoreSelfTest.Done();
        q.unlock("first password".toCharArray(), u);
        String err = u.await();
        Thread.sleep(100);
        CoreSelfTest.flushEdt();
        check("right password opens what it can and reports the rest", err != null && err.contains("1 saved capture") && sink.loaded.size() >= 1 && q.lockedCount() == 1 && q.unlocked());
        CoreSelfTest.Done f = new CoreSelfTest.Done();
        q.discardLocked(f);
        check("forgot the password: no error", f.await() == null);
        check("... nothing locked, encryption off, foreign capture gone", q.lockedCount() == 0 && !q.encrypt() && !q.unlocked() && env.proj.read(idc) == null && !env.proj.index().contains(idc));
        CoreSelfTest.Done s = new CoreSelfTest.Done();
        q.setPassword("brand new password".toCharArray(), new ArrayList<>(), s);
        check("a new password can be set afterwards", s.await() == null && q.encrypt() && q.unlocked());
        q.shutdown();
        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
