package ptshot;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** The terminal layer's editor shows only the text; edits keep the colour codes underneath. */
public class TermEditSelfTest {
    static int fails;

    static void check(String n, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + n);
        if (!ok) fails++;
    }

    static final String ESC = "\u001b";

    static Object get(String n, Object o) throws Exception {
        Field f = CapturePanel.class.getDeclaredField(n);
        f.setAccessible(true);
        return f.get(o);
    }

    public static void main(String[] a) throws Exception {
        String raw = ESC + "[01;32muser@host" + ESC + "[00m:" + ESC + "[01;34m~" + ESC + "[00m$ id\r\nuid=1000";
        check("clean: no escape codes, no carriage returns", Term.clean(raw).equals("user@host:~$ id\nuid=1000"));
        check("clean: window titles and other escapes go too", Term.clean("a" + ESC + "]0;title\u0007b" + ESC + "[2Jc").equals("abc"));
        check("clean of text without codes is unchanged", Term.clean("plain\ttext\nline2").equals("plain\ttext\nline2"));

        // typing: the new text takes the colour of the character before it
        String typed = Term.insertClean(raw, "user@hos".length(), "X");
        String exp = ESC + "[01;32muser@hosXt" + ESC + "[00m:" + ESC + "[01;34m~" + ESC + "[00m$ id\r\nuid=1000";
        check("insert inside coloured text keeps the codes in place", typed.equals(exp));
        check("the editor view after the insert is the old text plus X", Term.clean(typed).equals("user@hosXt:~$ id\nuid=1000"));
        // deleting visible characters keeps the colour codes that were between them
        String del = Term.removeClean(raw, 0, "user@host:".length());
        check("deleting text keeps the later colour codes", del.contains(ESC + "[01;34m~") && Term.clean(del).equals("~$ id\nuid=1000"));
        check("removing a range leaves the editor view consistent", Term.clean(Term.removeClean(raw, 5, 3)).equals(removeRange(Term.clean(raw), 5, 3)));
        check("insert at the very start / end", Term.clean(Term.insertClean(raw, 0, ">>")).startsWith(">>user") && Term.clean(Term.insertClean(raw, Term.clean(raw).length(), "!")).endsWith("1000!"));
        check("a pasted escape sequence becomes a real colour code", Term.insertClean("ab", 1, "␛[31mX").contains(ESC + "[31m"));
        // the parsed colours still come out right after edits
        Term.Parsed p0 = Term.parse(raw, Term.preset("Windows cmd (clink)").dark.pal, Color.WHITE, Color.BLACK);
        Term.Parsed p1 = Term.parse(typed, Term.preset("Windows cmd (clink)").dark.pal, Color.WHITE, Color.BLACK);
        check("the inserted character is drawn in the colour of its neighbour", p1.text.startsWith("user@hosXt") && p1.fg[8].equals(p1.fg[7]) && !p1.fg[8].equals(Color.WHITE) && p0.fg[0].equals(p1.fg[0]));

        // the real editor
        final CapturePanel[] cp = new CapturePanel[1];
        Settings st = new Settings();
        List<Block> l = new ArrayList<>();
        Block b = Block.terminal("Kali", st);
        l.add(b);
        SwingUtilities.invokeAndWait(() -> {
            try {
                cp[0] = new CapturePanel(st, l, 2, 900, () -> { }, () -> { }, () -> { });
                ((JList<?>) get("list", cp[0])).setSelectedIndex(0);
                JTextArea ta = (JTextArea) get("ta", cp[0]);
                check("editor: no escape symbol is shown", ta.getText().indexOf('␛') < 0 && ta.getText().indexOf('\u001b') < 0 && ta.getText().contains("user@host"));
                String before = b.raw;
                ta.insert("Z", ta.getText().indexOf("$ id") + 4);
                check("editor: typing changes the raw text underneath and keeps the codes", b.raw.contains("Z") && b.raw.contains(ESC + "[01;32m") && countEsc(b.raw) == countEsc(before));
                ta.replaceRange("", 0, 4);
                check("editor: deleting keeps the other codes", countEsc(b.raw) == countEsc(before) && b.text.startsWith("@host"));
                javax.swing.JCheckBox codes = ((TerminalBar) get("termBar", cp[0])).showCodes;
                codes.doClick();
                check("editor: 'Show colour codes' shows the raw codes", ta.getText().indexOf('␛') >= 0);
                codes.doClick();
                check("editor: switching back hides them again", ta.getText().indexOf('␛') < 0);
            } catch (Exception e) {
                e.printStackTrace();
                fails++;
            }
        });
        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static int countEsc(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\u001b') n++;
        return n;
    }

    static String removeRange(String s, int at, int len) {
        return s.substring(0, at) + s.substring(at + len);
    }
}
