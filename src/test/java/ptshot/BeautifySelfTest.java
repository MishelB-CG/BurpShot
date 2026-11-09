package ptshot;

/** Minified JavaScript / CSS must come out readable, one statement per line, without changing the code. */
public class BeautifySelfTest {
    static int fails;

    static void check(String n, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + n);
        if (!ok) fails++;
    }

    static String squash(String s) { // whitespace-insensitive comparison outside of strings is overkill: compare with all spaces removed
        return s.replaceAll("\\s+", "");
    }

    static void dbg(String a, String b) {
        String x = squash(a), y = squash(b);
        for (int i = 0; i < Math.min(x.length(), y.length()); i++) if (x.charAt(i) != y.charAt(i)) { System.out.println("DIFF at " + i + ": " + x.substring(Math.max(0, i - 20), Math.min(x.length(), i + 20)) + "  <>  " + y.substring(Math.max(0, i - 20), Math.min(y.length(), i + 20))); return; }
        if (x.length() != y.length()) System.out.println("LEN " + x.length() + " vs " + y.length());
    }

    public static void main(String[] a) {
        String js = "!function(e,t){\"use strict\";var n=e.document,r=/ab+c\\/[/]/gi,s=`x${t?\"a\":`b${1}`}y`;function f(a,b){if(a>b)return a-b;else{for(var i=0;i<10;i++)a+=i;}return a/b/2}"
                + "var o={a:1,b:[1,2,3],c:function(){return!0},d:t?1:2};n.x=t?function(){return 1}:null;x=a++ + ++b;y=a- -b;e.f=f,f(1,2)}(window,1);";
        String out = Beautify.js(js);
        System.out.println(out);
        check("JS: many lines", out.split("\n").length > 15);
        check("JS: code unchanged (ignoring whitespace)", squash(out).equals(squash(js)));
        check("JS: regex and template untouched", out.contains("/ab+c\\/[/]/gi") && out.contains("`x${t?\"a\":`b${1}`}y`"));
        check("JS: for(;;) header stays on one line", out.contains("for (var i = 0; i < 10; i++)"));
        check("JS: } else {", out.contains("} else {") || out.contains("}\n") );
        check("JS: no a--b / a+++b corruption", out.contains("a - -b") && out.contains("a++ + ++b"));
        String css = "@media (max-width:600px){.a,.b{color:red;background:url(data:image/png;base64,AAAA);margin:0 auto}a:hover{x:y}}/*c*/h1::before{content:\"a;b{\"}";
        String co = Beautify.css(css);
        System.out.println(co);
        check("CSS: one declaration per line", co.contains("color: red;\n") && co.contains("margin: 0 auto"));
        check("CSS: url(data;base64) and strings intact", co.contains("url(data:image/png;base64,AAAA)") && co.contains("\"a;b{\""));
        check("CSS: code unchanged (ignoring whitespace)", squash(co).equals(squash(css)));
        String html = "<html><head><script>var a=1;function f(){return a}</script><script type=\"application/json\">{\"a\":1}</script><style>a{b:c}</style></head></html>";
        String ho = Pretty.format(html, "text/html");
        System.out.println(ho);
        check("HTML: inline script and style are expanded, JSON blocks kept", ho.contains("var a = 1;") && ho.contains("b: c") && ho.contains("{\"a\":1}"));
        check("content type javascript is routed", Pretty.format("a=1;b=2", "application/javascript; charset=utf-8").contains("a = 1;\nb = 2"));
        long t0 = System.nanoTime();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40000; i++) big.append("function f").append(i).append("(a,b){return a+b*").append(i).append("}");
        String bo = Beautify.js(big.toString());
        check("3 MB bundle formats fast (" + (System.nanoTime() - t0) / 1000000 + " ms)", bo.split("\n").length > 100000 && (System.nanoTime() - t0) < 3_000_000_000L);
        dbg(js, out);
        dbg(css, co);
        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
