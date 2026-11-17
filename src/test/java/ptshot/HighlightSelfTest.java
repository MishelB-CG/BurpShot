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
        String html = "HTTP/2 200 OK\nContent-Type: text/html\n\n<!DOCTYPE html>\n<html>\n<head>\n<title>T</title>\n<meta content=\"w=1\" name='v'>\n<script src=x.js></script>\n<!-- c -->\n</head></html>";
        Color[] h = Highlighter.colorize(html, false, base, syn);
        check("html: doctype uses the string colour", h[html.indexOf("<!DOCTYPE")].equals(syn[Settings.K_STR]) && h[html.indexOf("html>\n<html") + 4].equals(syn[Settings.K_STR]));
        check("html: brackets stay plain, the tag name uses the markup colour", h[html.indexOf("<html>")].equals(base) && h[html.indexOf("<html>") + 1].equals(syn[Settings.K_TAG]) && h[html.indexOf("<html>") + 5].equals(base));
        check("html: closing tags: '</' plain, name coloured", h[html.indexOf("</head>")].equals(base) && h[html.indexOf("</head>") + 1].equals(base) && h[html.indexOf("</head>") + 2].equals(syn[Settings.K_TAG]));
        int meta = html.indexOf("<meta");
        check("html: attribute name = parameter-name colour, '=' plain", h[html.indexOf("content=")].equals(syn[Settings.K_PNAME]) && h[html.indexOf("content=") + 7].equals(base));
        check("html: attribute value including its quotes = parameter-value colour", h[html.indexOf("\"w=1\"")].equals(syn[Settings.K_PVAL]) && h[html.indexOf("\"w=1\"") + 4].equals(syn[Settings.K_PVAL]) && h[html.indexOf("'v'")].equals(syn[Settings.K_PVAL]));
        check("html: unquoted attribute value", h[html.indexOf("x.js")].equals(syn[Settings.K_PVAL]) && h[html.indexOf("src=x")].equals(syn[Settings.K_PNAME]));
        check("html: text between tags is plain", h[html.indexOf(">T<") + 1].equals(base));
        check("html: comment uses the string colour", h[html.indexOf("<!-- c")].equals(syn[Settings.K_STR]) && h[html.indexOf("c -->")].equals(syn[Settings.K_STR]));
        check("html: headers above are unaffected", h[html.indexOf("Content-Type")].equals(syn[Settings.K_HDR]));
        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
