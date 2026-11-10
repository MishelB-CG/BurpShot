package ptshot;

import java.util.ArrayList;
import java.util.List;

/** Filters merge with manual edits, and long bodies are shown through a scrollable window. */
public class WindowSelfTest {
    static int fails;

    static void check(String n, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + n);
        if (!ok) fails++;
    }

    static Block response(String body) {
        Settings st = new Settings();
        return Block.http(Block.Kind.RESPONSE, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nX-A: 1\r\n\r\n" + body, st);
    }

    public static void main(String[] a) throws Exception {
        // ---------------------------------------------------------------- merge
        String g0 = "H1\nH2\nH3\n\nbody1\nbody2";
        String g1 = "H1\nH3\n\nbody1\nbody2";
        String edited = "H1\nH2\nH3\n\nbody1\nBODY2";
        Merge3.Result r = Merge3.merge(g0, edited, g1);
        check("merge: a filter hides a header, the body edit survives", r != null && r.text.equals("H1\nH3\n\nbody1\nBODY2") && r.conflicts == 0);
        Merge3.Result back = Merge3.merge(g1, r.text, g0);
        check("merge: Select none -> Select all gives back the edited text", back != null && back.text.equals(edited));
        Merge3.Result none = Merge3.merge(g0, g0, g1);
        check("merge: no edits -> exactly the new filtered text", none.text.equals(g1) && none.conflicts == 0);
        Merge3.Result clash = Merge3.merge(g0, "H1\nH2-edited\nH3\n\nbody1\nbody2", g1);
        check("merge: editing a line the filter removes keeps the edit and reports it", clash.text.contains("H2-edited") && clash.conflicts == 1);
        Merge3.Result both = Merge3.merge("a\nb\nc\nd", "a\nB\nc\nd", "a\nb\nc\nD");
        check("merge: edits in different places both apply", both.text.equals("a\nB\nc\nD"));
        Merge3.Result ins = Merge3.merge("a\nb", "a\nX\nb", "a\nY\nb");
        check("merge: two insertions at the same place are both kept", ins.text.contains("X") && ins.text.contains("Y") && ins.conflicts == 0);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 150000; i++) big.append("line ").append(i).append('\n');
        String bigBase = big.toString(), bigEdit = bigBase.replace("line 70000\n", "edited 70000\n"), bigNew = bigBase.substring(bigBase.indexOf("line 3\n"));
        long t0 = System.nanoTime();
        Merge3.Result bm = Merge3.merge(bigBase, bigEdit, bigNew);
        check("merge: 150,000 lines (" + (System.nanoTime() - t0) / 1000000 + " ms)", bm != null && bm.text.contains("edited 70000") && !bm.text.startsWith("line 0") && (System.nanoTime() - t0) < 5_000_000_000L);

        // ---------------------------------------------------------------- window, characters
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100000; i++) sb.append("0123456789"); // 1,000,000 characters on ONE line
        Block b = response(sb.toString());
        check("a 1 MB body is windowed automatically", b.windowed() && b.bodyLength() == 1_000_000);
        String ed = b.editorText();
        check("the editor shows the head plus exactly 10,000 characters", ed.length() == b.text.indexOf("\n\n") + 2 + 10000);
        String shown = b.shown();
        check("the screenshot text is the head plus the slice, with no added marker text", shown.equals(ed) && !shown.contains("not shown"));
        b.viewOffset = 500_000;
        b.invalidateWindow();
        check("scrolled: still only the slice, no markers", b.shown().length() == ed.length() && !b.shown().contains("not shown"));
        // edit inside the window: replace the first 10 window characters
        String e1 = b.editorText();
        int hs = e1.indexOf("\n\n") + 2;
        b.applyEditorText(e1.substring(0, hs) + "EDITED!!!!" + e1.substring(hs + 10));
        String full = b.text.substring(b.text.indexOf("\n\n") + 2);
        check("an edit in the window lands at the right place of the full body", full.length() == 1_000_000 && full.startsWith("0123456789", 499_990) && full.startsWith("EDITED!!!!", 500_000) && full.startsWith("0123456789", 500_010));
        b.applyEditorText(b.editorText() + "XYZ");
        check("growing the window text grows only the window", b.bodyLength() == 1_000_003);
        b.viewOn = 0;
        b.invalidateWindow();
        check("Show all: the editor holds the whole text", b.editorText().equals(b.text) && b.shown().equals(b.text));

        // ---------------------------------------------------------------- window, lines
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= 1000; i++) lines.append("row ").append(i).append(i < 1000 ? "\n" : "");
        Block l = response(lines.toString());
        l.viewOn = 1;
        l.viewUnit = 1;
        l.viewSize = 100;
        l.viewOffset = l.lineStartOffset(450);
        l.invalidateWindow();
        String le = l.editorText();
        String body = le.substring(le.indexOf("\n\n") + 2);
        String[] bl = body.split("\n");
        check("lines mode: exactly 100 lines starting at line 450 (" + bl.length + ")", bl.length >= 100 && bl[0].equals("row 450") && bl[99].equals("row 549"));
        int off = l.moveByLines(l.windowRange()[0], 3);
        check("moving by 3 lines lands on line 453", l.lineStartOffset(453) == off);
        check("moving back by 3 lines returns", l.moveByLines(off, -3) == l.lineStartOffset(450));
        check("lineStartOffset beyond the end is -1", l.lineStartOffset(5000) == -1);

        // ---------------------------------------------------------------- persistence
        Block saved = response(sb.toString());
        saved.viewOn = 1;
        saved.viewUnit = 0;
        saved.viewSize = 2500;
        saved.viewOffset = 12345;
        List<Block> one = new ArrayList<>();
        one.add(saved);
        String json = Store.serialize("t", 2, 900, one);
        check("an unedited long body is not stored twice (" + json.length() / 1000 + " KB for a 1,000 KB body)", json.length() < 1_300_000);
        Store.Restored rr = Store.deserialize(json);
        Block rb = rr.blocks.get(0);
        check("view settings survive save / load", rb.viewOn == 1 && rb.viewSize == 2500 && rb.viewOffset == 12345 && rb.viewUnit == 0);
        check("the text of an unedited long body is rebuilt on load", rb.text.equals(saved.text));
        saved.applyEditorText(saved.editorText().replace("0123", "ABCD"));
        Store.Restored re = Store.deserialize(Store.serialize("t", 2, 900, one));
        check("an edited long body is stored and restored exactly", re.blocks.get(0).text.equals(saved.text));
        Block small = response("small body");
        check("small bodies are not windowed and keep their text", !small.windowed() && small.editorText().equals(small.text));
        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
