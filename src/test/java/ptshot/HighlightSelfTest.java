package ptshot;

import java.awt.Color;

/** Cookie / Set-Cookie headers are coloured like parameters: name = parameter-name colour, value = parameter-value colour. */
public class HighlightSelfTest {
    static int fails;

    static void check(String n, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + n);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        Settings st = new Settings();
        Color[] syn = st.syn;
        Color base = Color.BLACK;
        String req = "GET / HTTP/1.1\nHost: a\nCookie: sid=abc123; theme=dark; flag\n\n";
        Color[] c = Highlighter.colorize(req, true, base, syn);
        int at = req.indexOf("sid=");
        check("header name uses the header colour", c[req.indexOf("Cookie")].equals(syn[Settings.K_HDR]));
        check("cookie name uses the parameter-name colour", c[at].equals(syn[Settings.K_PNAME]) && c[at + 2].equals(syn[Settings.K_PNAME]));
        check("'=' belongs to the name, value uses the parameter-value colour", c[at + 3].equals(syn[Settings.K_PNAME]) && c[at + 4].equals(syn[Settings.K_PVAL]) && c[at + 8].equals(syn[Settings.K_PVAL]));
        int t2 = req.indexOf("theme");
        check("second cookie: name and value", c[t2].equals(syn[Settings.K_PNAME]) && c[req.indexOf("dark")].equals(syn[Settings.K_PVAL]));
        check("separator '; ' keeps the base colour", c[req.indexOf("; theme")].equals(base));
        check("a flag without '=' is coloured as a name", c[req.indexOf("flag")].equals(syn[Settings.K_PNAME]));
        check("name and value colours differ", !syn[Settings.K_PNAME].equals(syn[Settings.K_PVAL]));
        String resp = "HTTP/1.1 200 OK\nSet-Cookie: id=xyz; Path=/; HttpOnly\nContent-Type: text/html\n\n";
        Color[] r = Highlighter.colorize(resp, false, base, syn);
        check("Set-Cookie value and attribute", r[resp.indexOf("xyz")].equals(syn[Settings.K_PVAL]) && r[resp.indexOf("Path")].equals(syn[Settings.K_PNAME]) && r[resp.indexOf("HttpOnly")].equals(syn[Settings.K_PNAME]));
        check("other headers are unchanged", r[resp.indexOf("text/html")].equals(base) && r[resp.indexOf("Content-Type")].equals(syn[Settings.K_HDR]));
        String mp = "POST /x HTTP/1.1\nContent-Type: multipart/form-data; boundary=----B\n\n------B\nContent-Disposition: form-data; name=\"AuthFlow\"\n\nUSER_PASSWORD_AUTH\n------B\nContent-Disposition: form-data; name=\"f\"; filename=\"a.png\"\nContent-Type: image/png\n\nXYZ\n------B--\n";
        Color[] m = Highlighter.colorize(mp, true, base, syn);
        int af = mp.indexOf("AuthFlow");
        check("multipart: boundary lines are plain", m[mp.indexOf("------B")].equals(base) && m[mp.lastIndexOf("------B--")].equals(base));
        check("multipart: part header name uses the header colour", m[mp.indexOf("Content-Disposition")].equals(syn[Settings.K_HDR]));
        check("multipart: field name uses the parameter-name colour, quotes stay plain", m[af].equals(syn[Settings.K_PNAME]) && m[af - 1].equals(base) && m[af + 8].equals(base));
        check("multipart: part content uses the parameter-value colour", m[mp.indexOf("USER_PASSWORD_AUTH")].equals(syn[Settings.K_PVAL]) && m[mp.indexOf("XYZ")].equals(syn[Settings.K_PVAL]));
        check("multipart: file name is coloured as a name", m[mp.indexOf("a.png")].equals(syn[Settings.K_PNAME]));
        check("multipart: 'form-data;' stays plain", m[mp.indexOf("form-data")].equals(base));
        check("multipart: Content-Type inside a part is a header, its value plain", m[mp.indexOf("Content-Type: image")].equals(syn[Settings.K_HDR]) && m[mp.indexOf("image/png")].equals(base));
        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
