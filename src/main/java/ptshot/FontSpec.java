package ptshot;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Font family / size / style / colour. Sizes are in "1x pixels"; the renderer multiplies by the output scale. */
final class FontSpec {
    String family;
    int size;
    boolean bold, italic;
    Color color;

    FontSpec(String family, int size, boolean bold, boolean italic, Color color) {
        this.family = family;
        this.size = size;
        this.bold = bold;
        this.italic = italic;
        this.color = color;
    }

    FontSpec copy() {
        return new FontSpec(family, size, bold, italic, color);
    }

    private static Map<String, String> installed;

    private static synchronized String resolveFamily(String wanted) {
        if (installed == null) {
            installed = new HashMap<>();
            for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
                installed.put(f.toLowerCase(Locale.ROOT), f);
            }
            for (String f : Arrays.asList("Dialog", "DialogInput", "Monospaced", "SansSerif", "Serif")) {
                installed.put(f.toLowerCase(Locale.ROOT), f);
            }
        }
        String hit = installed.get(wanted.trim().toLowerCase(Locale.ROOT));
        if (hit != null) return hit;
        // Carlito is the metric-compatible Calibri replacement on Linux
        if (wanted.equalsIgnoreCase("Calibri") && installed.containsKey("carlito")) return installed.get("carlito");
        return "SansSerif";
    }

    static String[] allFamilies() {
        String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        String[] out = new String[names.length + 4];
        out[0] = "Monospaced";
        out[1] = "SansSerif";
        out[2] = "Serif";
        out[3] = "Dialog";
        System.arraycopy(names, 0, out, 4, names.length);
        return out;
    }

    Font toFont(double scale) {
        int style = (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
        return new Font(resolveFamily(family), style, 12).deriveFont((float) (size * scale));
    }

    String ser() {
        return family.replace("|", " ") + "|" + size + "|" + bold + "|" + italic + "|" + Integer.toHexString(color.getRGB() & 0xffffff);
    }

    static FontSpec parse(String s, FontSpec fallback) {
        try {
            String[] p = s.split("\\|");
            return new FontSpec(p[0], Integer.parseInt(p[1]), Boolean.parseBoolean(p[2]), Boolean.parseBoolean(p[3]),
                    new Color(Integer.parseInt(p[4], 16)));
        } catch (Exception e) {
            return fallback;
        }
    }
}
