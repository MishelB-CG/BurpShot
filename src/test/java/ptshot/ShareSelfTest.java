package ptshot;

import java.util.List;

/** Share's start-up inputs (saved profiles, trusted SSH host keys) are untrusted: they must be validated and never carry secrets. */
public class ShareSelfTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        CoreSelfTest.FakeEnv env = new CoreSelfTest.FakeEnv("p");
        new Persist(env); // registers itself as Persist.current, which Share reads its preferences through

        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 100; i++) longName.append('x');
        env.prefs.put("ptshot.share.profiles", "["
                + "{\"name\":\"good\",\"type\":\"http\",\"cfg\":{\"url\":\"https://a.example/up\",\"auth\":\"bearer\",\"token\":\"SECRET-TOKEN\",\"unknownKey\":\"zzz\"}},"
                + "{\"name\":\"bad type\",\"type\":\"nope\",\"cfg\":{}},"
                + "{\"name\":\"" + longName + "\",\"type\":\"http\",\"cfg\":{}},"
                + "{\"name\":\"   \",\"type\":\"http\",\"cfg\":{}},"
                + "{\"name\":\"no cfg\",\"type\":\"http\"},"
                + "\"just a string\", 42, null, [1,2],"
                + "{\"name\":\"sftp\",\"type\":\"sftp\",\"cfg\":{\"host\":\"h\",\"password\":\"PW\",\"passphrase\":\"PP\",\"keyText\":\"KEY\",\"dir\":\"/up\"}}"
                + "]");
        List<ShareDialog.Profile> ps = ShareDialog.loadProfiles();
        check("profiles: only the valid entries are kept (good + sftp)", ps.size() == 2 && ps.get(0).name.equals("good") && ps.get(1).name.equals("sftp"));
        check("profiles: a secret that was written into the preferences is NOT loaded (token)", !ps.get(0).cfg.containsKey("token"));
        check("profiles: unknown keys are dropped", !ps.get(0).cfg.containsKey("unknownKey"));
        check("profiles: non-secret fields are kept", "https://a.example/up".equals(ps.get(0).cfg.get("url")) && "bearer".equals(ps.get(0).cfg.get("auth")));
        check("profiles: sftp password / passphrase / key text are not loaded", !ps.get(1).cfg.containsKey("password") && !ps.get(1).cfg.containsKey("passphrase") && !ps.get(1).cfg.containsKey("keyText") && "/up".equals(ps.get(1).cfg.get("dir")));

        env.prefs.put("ptshot.share.profiles", "{ this is not json");
        check("profiles: garbage preferences give an empty list, no exception", ShareDialog.loadProfiles().isEmpty());
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100000; i++) deep.append('[');
        env.prefs.put("ptshot.share.profiles", deep.toString());
        check("profiles: 100000-deep nesting in the preferences is survived", ShareDialog.loadProfiles().isEmpty());
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 80; i++) many.append(i == 0 ? "" : ",").append("{\"name\":\"p").append(i).append("\",\"type\":\"local\",\"cfg\":{\"path\":\"/x\"}}");
        many.append("]");
        env.prefs.put("ptshot.share.profiles", many.toString());
        check("profiles: at most 50 are loaded", ShareDialog.loadProfiles().size() == 50);

        env.prefs.put("ptshot.share.hostkeys", "{\"h:22\":\"SHA256:abc\",\"evil:22\":\"not a fingerprint\",\"n:22\":5}");
        check("host keys: only well-formed SHA256 fingerprints are trusted", ShareDialog.hostKeys().size() == 1 && "SHA256:abc".equals(ShareDialog.hostKeys().get("h:22")));
        env.prefs.put("ptshot.share.hostkeys", "[[[[");
        check("host keys: garbage gives none", ShareDialog.hostKeys().isEmpty());

        check("remoteName: traversal and odd characters neutralised", ShareDialog.remoteName("../../etc/passwd", false, false).equals("_.._etc_passwd.png") || !ShareDialog.remoteName("../../etc/passwd", false, false).contains("/"));
        check("remoteName: extensions", ShareDialog.remoteName("shot", false, false).equals("shot.png") && ShareDialog.remoteName("shot", true, false).equals("shot.burpshot")
                && ShareDialog.remoteName("shot", false, true).equals("shot.png.bsv") && ShareDialog.remoteName("shot.png", false, false).equals("shot.png"));
        check("remoteName: empty falls back, long names are cut", ShareDialog.remoteName("", false, false).equals("burpshot.png") && ShareDialog.remoteName(longName + "yyyy", false, false).length() <= 110);

        check("hostOf: reads the host of a request line", "api.example.com".equals(ShareDialog.hostOf("PUT https://API.example.com:8443/a/b?x={{secret:keyValue}} HTTP/1.1\nHost: x")));
        check("hostOf: unparsable gives empty", ShareDialog.hostOf("garbage").isEmpty() && ShareDialog.hostOf(null).isEmpty());

        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
