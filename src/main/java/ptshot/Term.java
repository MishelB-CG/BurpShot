package ptshot;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Terminal look: colour schemes (dark / light), window chrome, command / indentation styling and an ANSI (SGR) parser. */
final class Term {
    private Term() {}

    static final String[] CHROMES = {"NONE", "WINDOWS", "MAC", "LINUX"};
    static final String[] CHROME_NAMES = {"None", "Windows", "macOS", "Linux"};
    static final String[] COLOR_NAMES = {"Black", "Red", "Green", "Yellow", "Blue", "Magenta", "Cyan", "White",
            "Bright black", "Bright red", "Bright green", "Bright yellow", "Bright blue", "Bright magenta", "Bright cyan", "Bright white"};
    static final String[] ACCENT_NAMES = {"Command", "Indent level 1", "Indent level 2", "Indent level 3"};

    /** How to recognise a prompt, so the command after it can be told apart from the output. */
    enum Prompt { CMD, POWERSHELL, POSIX, NONE }

    /** One colour theme (dark or light) of a scheme. */
    static final class Look {
        final Color bg, fg, cmd;
        final boolean cmdBold;
        final Color[] pal;
        final Color[] indent; // colours of indentation levels 1..3

        Look(Color bg, Color fg, Color cmd, boolean cmdBold, Color[] pal, Color[] indent) {
            this.bg = bg;
            this.fg = fg;
            this.cmd = cmd;
            this.cmdBold = cmdBold;
            this.pal = pal;
            this.indent = indent;
        }
    }

    static final class Preset {
        final String name, chrome, title, sample, defaultTheme;
        final Prompt prompt;
        final Look dark, light;

        Preset(String name, String chrome, String title, String sample, Prompt prompt, String defaultTheme, Look dark, Look light) {
            this.name = name;
            this.chrome = chrome;
            this.title = title;
            this.sample = sample;
            this.prompt = prompt;
            this.defaultTheme = defaultTheme;
            this.dark = dark;
            this.light = light;
        }
    }

    private static Color c(int r, int g, int b) {
        return new Color(r, g, b);
    }

    private static Color[] pal(int... v) {
        Color[] o = new Color[16];
        for (int i = 0; i < 16; i++) o[i] = c(v[i * 3], v[i * 3 + 1], v[i * 3 + 2]);
        return o;
    }

    private static final Color[] CAMPBELL = pal(12, 12, 12, 197, 15, 31, 19, 161, 14, 193, 156, 0, 0, 55, 218, 136, 23, 152, 58, 150, 221, 204, 204, 204,
            118, 118, 118, 231, 72, 86, 22, 198, 12, 249, 241, 165, 59, 120, 255, 180, 0, 158, 97, 214, 214, 242, 242, 242);
    private static final Color[] TANGO = pal(46, 52, 54, 204, 0, 0, 78, 154, 6, 196, 160, 0, 52, 101, 164, 117, 80, 123, 6, 152, 154, 211, 215, 207,
            85, 87, 83, 239, 41, 41, 138, 226, 52, 252, 233, 79, 114, 159, 207, 173, 127, 168, 52, 226, 226, 238, 238, 236);
    private static final Color[] MAC = pal(0, 0, 0, 194, 54, 33, 37, 188, 36, 173, 173, 39, 73, 46, 225, 211, 56, 211, 51, 187, 200, 203, 204, 205,
            129, 131, 131, 252, 57, 31, 49, 231, 34, 234, 236, 35, 88, 51, 255, 249, 53, 248, 20, 240, 240, 233, 235, 235);
    private static final Color[] SOLAR = pal(7, 54, 66, 220, 50, 47, 133, 153, 0, 181, 137, 0, 38, 139, 210, 211, 54, 130, 42, 161, 152, 238, 232, 213,
            0, 43, 54, 203, 75, 22, 88, 110, 117, 101, 123, 131, 131, 148, 150, 108, 113, 196, 147, 161, 161, 253, 246, 227);
    /** Readable on a light background (VS Code light terminal palette). */
    private static final Color[] LIGHT = pal(0, 0, 0, 205, 49, 49, 0, 134, 0, 148, 152, 0, 4, 81, 165, 188, 5, 188, 5, 152, 188, 85, 85, 85,
            102, 102, 102, 205, 49, 49, 20, 160, 20, 160, 140, 0, 4, 81, 165, 188, 5, 188, 5, 152, 188, 60, 60, 60);

    private static final Color[] IND_DARK = {c(97, 214, 214), c(152, 195, 121), c(229, 192, 123)};
    private static final Color[] IND_LIGHT = {c(0, 125, 155), c(40, 130, 50), c(170, 105, 0)};

    private static final String WIN_SAMPLE = "C:\\Users\\user>whoami\ndesktop-pc\\user\n\nC:\\Users\\user>ipconfig\n\nWindows IP Configuration\n\n\n"
            + "Ethernet adapter Ethernet:\n\n   Media State . . . . . . . . . . . : Media disconnected\n\nC:\\Users\\user>";
    private static final String PS_SAMPLE = "PS C:\\Users\\user> whoami\ndesktop-pc\\user\nPS C:\\Users\\user> ipconfig\n\nWindows IP Configuration\n\n\n"
            + "Ethernet adapter Ethernet:\n\n   Media State . . . . . . . . . . . : Media disconnected\n\nPS C:\\Users\\user> ";
    private static final String LINUX_SAMPLE = "\u001b[01;32muser@host\u001b[00m:\u001b[01;34m~\u001b[00m$ id\nuid=1000(user) gid=1000(user) groups=1000(user)\n"
            + "\u001b[01;32muser@host\u001b[00m:\u001b[01;34m~\u001b[00m$ ip addr\n1: lo: <LOOPBACK,UP>\n    inet 127.0.0.1/8 scope host lo\n        valid_lft forever\n"
            + "\u001b[01;32muser@host\u001b[00m:\u001b[01;34m~\u001b[00m$ ";
    private static final String MAC_SAMPLE = "user@mac ~ % id\nuid=501(user) gid=20(staff) groups=20(staff)\nuser@mac ~ % ";
    private static final String PLAIN_SAMPLE = "$ id\nuid=1000(user) gid=1000(user)\n$ ";

    private static Map<String, Preset> presets;

    /** Colour schemes in menu order. */
    static synchronized Map<String, Preset> presets() {
        if (presets == null) {
            Map<String, Preset> m = new LinkedHashMap<>();
            // Windows Terminal look; CMD with clink (command in bold bright blue), PowerShell with its default (command in yellow)
            Look wtDark = new Look(c(20, 20, 20), c(186, 183, 182), c(16, 141, 255), true, CAMPBELL, IND_DARK);
            Look wtLight = new Look(c(250, 250, 250), c(56, 58, 66), c(0, 90, 200), true, LIGHT, IND_LIGHT);
            m.put("Windows cmd (clink)", new Preset("Windows cmd (clink)", "WINDOWS", "C:\\Windows\\system32\\cmd.exe", WIN_SAMPLE, Prompt.CMD, "DARK", wtDark, wtLight));
            m.put("PowerShell", new Preset("PowerShell", "WINDOWS", "Windows PowerShell", PS_SAMPLE, Prompt.POWERSHELL, "DARK",
                    new Look(c(20, 20, 20), c(186, 183, 182), c(252, 205, 26), false, CAMPBELL, IND_DARK),
                    new Look(c(250, 250, 250), c(56, 58, 66), c(150, 110, 0), false, LIGHT, IND_LIGHT)));
            m.put("PowerShell (classic blue)", new Preset("PowerShell (classic blue)", "WINDOWS", "Windows PowerShell", PS_SAMPLE, Prompt.POWERSHELL, "DARK",
                    new Look(c(1, 36, 86), c(238, 237, 240), c(255, 235, 59), false, CAMPBELL, IND_DARK),
                    new Look(c(255, 255, 255), c(1, 36, 86), c(150, 110, 0), false, LIGHT, IND_LIGHT)));
            m.put("Windows cmd (classic)", new Preset("Windows cmd (classic)", "WINDOWS", "C:\\Windows\\system32\\cmd.exe", WIN_SAMPLE, Prompt.NONE, "DARK",
                    new Look(c(12, 12, 12), c(204, 204, 204), c(204, 204, 204), false, CAMPBELL, IND_DARK),
                    new Look(c(255, 255, 255), c(12, 12, 12), c(12, 12, 12), false, LIGHT, IND_LIGHT)));
            m.put("Ubuntu", new Preset("Ubuntu", "LINUX", "user@host: ~", LINUX_SAMPLE, Prompt.POSIX, "DARK",
                    new Look(c(48, 10, 36), c(255, 255, 255), c(255, 255, 255), true, TANGO, IND_DARK),
                    new Look(c(255, 255, 255), c(46, 52, 54), c(46, 52, 54), true, LIGHT, IND_LIGHT)));
            m.put("Kali", new Preset("Kali", "LINUX", "kali@kali: ~", LINUX_SAMPLE, Prompt.POSIX, "DARK",
                    new Look(c(28, 28, 36), c(230, 230, 230), c(255, 255, 255), true, TANGO, IND_DARK),
                    new Look(c(245, 245, 245), c(40, 40, 40), c(20, 20, 20), true, LIGHT, IND_LIGHT)));
            m.put("macOS Terminal", new Preset("macOS Terminal", "MAC", "user \u2014 -zsh \u2014 80\u00d724", MAC_SAMPLE, Prompt.POSIX, "LIGHT",
                    new Look(c(30, 30, 30), c(230, 230, 230), c(255, 255, 255), true, MAC, IND_DARK),
                    new Look(c(255, 255, 255), c(0, 0, 0), c(0, 0, 0), true, MAC, IND_LIGHT)));
            m.put("Solarized", new Preset("Solarized", "NONE", "", LINUX_SAMPLE, Prompt.POSIX, "DARK",
                    new Look(c(0, 43, 54), c(131, 148, 150), c(238, 232, 213), true, SOLAR, IND_DARK),
                    new Look(c(253, 246, 227), c(101, 123, 131), c(7, 54, 66), true, SOLAR, IND_LIGHT)));
            m.put("Green on black", new Preset("Green on black", "NONE", "", PLAIN_SAMPLE, Prompt.POSIX, "DARK",
                    new Look(c(0, 0, 0), c(0, 255, 70), c(180, 255, 200), true, CAMPBELL, IND_DARK),
                    new Look(c(255, 255, 255), c(0, 120, 40), c(0, 80, 25), true, LIGHT, IND_LIGHT)));
            presets = m;
        }
        return presets;
    }

    static Preset preset(String name) {
        Preset p = presets().get(name);
        if (p == null && name != null) { // names of older versions
            if (name.equals("Windows cmd") || name.equals("Windows Terminal")) p = presets().get("Windows cmd (clink)");
            else if (name.equals("Solarized Dark")) p = presets().get("Solarized");
        }
        return p != null ? p : presets().values().iterator().next();
    }

    static Look look(Block b) {
        Preset p = preset(b.preset);
        return "LIGHT".equals(b.theme) ? p.light : p.dark;
    }

    static Color[] palette(Block b) {
        return b.palette != null && b.palette.length == 16 ? b.palette : look(b).pal;
    }

    static Color bg(Block b) {
        return b.background != null ? b.background : look(b).bg;
    }

    static Color fg(Block b) {
        return b.bodyFont != null ? b.bodyFont.color : look(b).fg;
    }

    /** {command colour, indent 1, indent 2, indent 3}; the scheme's unless the user changed them. */
    static Color[] accents(Block b) {
        if (b.accents != null && b.accents.length == 4) return b.accents;
        Look l = look(b);
        return new Color[]{l.cmd, l.indent[0], l.indent[1], l.indent[2]};
    }

    /** Height of the window title bar in 1x pixels (0 = none). */
    static int chromeH(Block b) {
        return "NONE".equals(b.chrome) || b.chrome == null ? 0 : 34;
    }

    static boolean dark(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) < 140;
    }

    // ------------------------------------------------------------------ ANSI parsing

    /** Plain text plus, for every character, its colours. */
    static final class Parsed {
        String text = "";
        Color[] fg = new Color[0];
        Color[] bg = new Color[0];   // null = terminal background
        boolean[] ul = new boolean[0];
        boolean[] bold = new boolean[0]; // set by style(): bold command text
    }

    private static Color xterm(int n, Color[] pal) {
        if (n < 16) return pal[Math.max(0, n)];
        if (n < 232) {
            int[] lv = {0, 95, 135, 175, 215, 255};
            int i = n - 16;
            return c(lv[i / 36], lv[(i / 6) % 6], lv[i % 6]);
        }
        int g = 8 + 10 * (Math.min(255, n) - 232);
        return c(g, g, g);
    }

    static Parsed parse(String raw, Color[] pal, Color defFg, Color defBg) {
        if (raw == null) raw = "";
        StringBuilder sb = new StringBuilder();
        List<Color> fgs = new ArrayList<>(), bgs = new ArrayList<>();
        List<Boolean> uls = new ArrayList<>();
        Integer fgIdx = null, bgIdx = null;      // palette index (so bold can brighten) or null
        Color fgRgb = null, bgRgb = null;        // explicit colour (256 / true colour)
        boolean bold = false, ul = false, rev = false;
        int col = 0, n = raw.length();
        for (int i = 0; i < n; i++) {
            char ch = raw.charAt(i);
            if (ch == '\u001b') {
                if (i + 1 < n && raw.charAt(i + 1) == '[') {
                    int j = i + 2;
                    while (j < n && (raw.charAt(j) < 0x40 || raw.charAt(j) > 0x7e)) j++;
                    if (j < n && raw.charAt(j) == 'm') {
                        String ps = raw.substring(i + 2, j);
                        String[] parts = ps.isEmpty() ? new String[]{"0"} : ps.split("[;:]", -1);
                        for (int k = 0; k < parts.length; k++) {
                            int v;
                            try {
                                v = parts[k].isEmpty() ? 0 : Integer.parseInt(parts[k]);
                            } catch (NumberFormatException e) {
                                continue;
                            }
                            if (v == 0) {
                                fgIdx = bgIdx = null;
                                fgRgb = bgRgb = null;
                                bold = ul = rev = false;
                            } else if (v == 1) bold = true;
                            else if (v == 22) bold = false;
                            else if (v == 4) ul = true;
                            else if (v == 24) ul = false;
                            else if (v == 7) rev = true;
                            else if (v == 27) rev = false;
                            else if (v >= 30 && v <= 37) { fgIdx = v - 30; fgRgb = null; }
                            else if (v >= 90 && v <= 97) { fgIdx = v - 90 + 8; fgRgb = null; }
                            else if (v >= 40 && v <= 47) { bgIdx = v - 40; bgRgb = null; }
                            else if (v >= 100 && v <= 107) { bgIdx = v - 100 + 8; bgRgb = null; }
                            else if (v == 39) { fgIdx = null; fgRgb = null; }
                            else if (v == 49) { bgIdx = null; bgRgb = null; }
                            else if ((v == 38 || v == 48) && k + 1 < parts.length) {
                                try {
                                    int mode = Integer.parseInt(parts[k + 1]);
                                    Color cc = null;
                                    if (mode == 5 && k + 2 < parts.length) {
                                        cc = xterm(Integer.parseInt(parts[k + 2]), pal);
                                        k += 2;
                                    } else if (mode == 2 && k + 4 < parts.length) {
                                        cc = c(Integer.parseInt(parts[k + 2]) & 255, Integer.parseInt(parts[k + 3]) & 255, Integer.parseInt(parts[k + 4]) & 255);
                                        k += 4;
                                    }
                                    if (cc != null) {
                                        if (v == 38) { fgRgb = cc; fgIdx = null; }
                                        else { bgRgb = cc; bgIdx = null; }
                                    }
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }
                    i = Math.min(j, n - 1); // any other control sequence (cursor moves, erase...) is dropped
                } else if (i + 1 < n && raw.charAt(i + 1) == ']') { // OSC, e.g. window title: up to BEL or ESC \
                    int j = i + 2;
                    while (j < n && raw.charAt(j) != '\u0007' && !(raw.charAt(j) == '\u001b' && j + 1 < n && raw.charAt(j + 1) == '\\')) j++;
                    i = raw.charAt(Math.min(j, n - 1)) == '\u001b' ? j + 1 : j;
                } else {
                    i++; // two-character escape
                }
                continue;
            }
            if (ch == '\r') continue;
            if (ch == '\n') {
                sb.append('\n');
                fgs.add(defFg);
                bgs.add(null);
                uls.add(false);
                col = 0;
                continue;
            }
            if (ch < 0x20 && ch != '\t') continue;
            int reps = 1;
            char out = ch;
            if (ch == '\t') {
                reps = 8 - (col % 8);
                out = ' ';
            }
            Color f = fgRgb != null ? fgRgb : fgIdx != null ? pal[Math.min(15, fgIdx + (bold && fgIdx < 8 ? 8 : 0))] : defFg;
            Color b = bgRgb != null ? bgRgb : bgIdx != null ? pal[bgIdx] : null;
            if (rev) {
                Color nf = b != null ? b : defBg, nb = f;
                f = nf;
                b = nb;
            }
            for (int r = 0; r < reps; r++) {
                sb.append(out);
                fgs.add(f);
                bgs.add(b);
                uls.add(ul);
                col++;
            }
        }
        Parsed p = new Parsed();
        p.text = sb.toString();
        p.fg = fgs.toArray(new Color[0]);
        p.bg = bgs.toArray(new Color[0]);
        p.ul = new boolean[uls.size()];
        for (int i = 0; i < p.ul.length; i++) p.ul[i] = uls.get(i);
        p.bold = new boolean[p.text.length()];
        return p;
    }

    // ------------------------------------------------------------------ command / output / indentation styling

    private static final Pattern P_CMD = Pattern.compile("^[A-Za-z]:\\\\[^>\\n]*>");
    private static final Pattern P_PS = Pattern.compile("^PS [^>\\n]*> ?");
    private static final Pattern P_POSIX = Pattern.compile("^(?:\\([^)\\n]*\\)\\s*)?(?:[\\w.\\-]+@[\\w.\\-]+(?:[: ]\\s*[^\\s$#%]*)?\\s*)?[$#%] ");

    private static Pattern promptPattern(Prompt p) {
        switch (p) {
            case CMD: return P_CMD;
            case POWERSHELL: return P_PS;
            case POSIX: return P_POSIX;
            default: return null;
        }
    }

    private static boolean isDefault(Parsed p, int k, Color def) {
        return k < p.fg.length && p.bg[k] == null && p.fg[k].equals(def);
    }

    /**
     * Separates the command from its output: text after a prompt (matching the scheme's shell) is drawn in the command colour
     * (CMD / PowerShell: the program name only; POSIX shells: the whole command in bold), and output lines are coloured by how far
     * they are indented. Characters that already carry an explicit colour from the pasted text are left alone.
     */
    static void style(Parsed p, Block b) {
        Preset pr = preset(b.preset);
        Look lk = look(b);
        Color def = fg(b);
        Color[] ac = accents(b);
        Pattern pat = b.cmdStyle ? promptPattern(pr.prompt) : null;
        String t = p.text;
        int n = t.length(), i = 0;
        while (i <= n) {
            int e = t.indexOf('\n', i);
            if (e < 0) e = n;
            String line = t.substring(i, e);
            boolean cmdLine = false;
            if (pat != null) {
                Matcher m = pat.matcher(line);
                if (m.find()) {
                    cmdLine = true;
                    int from = i + m.end(), to = e;
                    if (pr.prompt == Prompt.CMD || pr.prompt == Prompt.POWERSHELL) {
                        int s = from;
                        while (s < e && t.charAt(s) == ' ') s++;
                        int w = s;
                        while (w < e && t.charAt(w) != ' ') w++; // the program name
                        from = s;
                        to = w;
                    }
                    for (int k = from; k < to; k++) {
                        if (!isDefault(p, k, def)) continue;
                        if (pr.prompt == Prompt.POSIX) {
                            p.bold[k] = true;
                            p.fg[k] = ac[0];
                        } else {
                            p.fg[k] = ac[0];
                            p.bold[k] = lk.cmdBold;
                        }
                    }
                }
            }
            if (!cmdLine && b.indentColors) {
                int ind = 0;
                while (ind < line.length() && line.charAt(ind) == ' ') ind++;
                if (ind > 0 && ind < line.length()) {
                    int lv = Math.min(3, (ind + 3) / 4);
                    for (int k = i + ind; k < e; k++) if (isDefault(p, k, def)) p.fg[k] = ac[lv];
                }
            }
            i = e + 1;
        }
    }

    /** The visible text of raw terminal output (escape sequences removed). */
    static String strip(String raw) {
        return parse(raw, preset("Windows cmd (clink)").dark.pal, Color.BLACK, Color.WHITE).text;
    }

    /** ESC characters are shown as a visible symbol in the editor and converted back when read. */
    static String show(String raw) {
        return raw == null ? "" : raw.replace('\u001b', '\u241b');
    }

    static String unshow(String s) {
        return s.replace('\u241b', '\u001b');
    }
}
