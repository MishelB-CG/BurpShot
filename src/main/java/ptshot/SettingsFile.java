package ptshot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Export / import of the global settings as a small JSON file. Importing never trusts the file: it is read with the limited parser, only
 * known keys with text values are used, and every value is clamped to the range the settings dialog allows.
 */
final class SettingsFile {
    private SettingsFile() {}

    static final String FORMAT = "burpshot-settings";
    static final int VERSION = 1;
    private static final long MAX_BYTES = 256 * 1024;

    static void export(File f, Settings st) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("settings", new LinkedHashMap<String, Object>(st.toMap()));
        byte[] data = Mini.write(root).getBytes(StandardCharsets.UTF_8);
        File tmp = new File(f.getAbsoluteFile().getParentFile(), f.getName() + ".tmp");
        Files.write(tmp.toPath(), data);
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** @return settings read from the file (starting from the defaults, so missing keys keep their default) */
    static Settings read(File f) throws IOException {
        if (f.length() > MAX_BYTES) throw new IOException("This does not look like a BurpShot settings file (too large).");
        Object o;
        try {
            o = Mini.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("This is not a valid BurpShot settings file.");
        }
        if (!(o instanceof Map) || !FORMAT.equals(((Map<?, ?>) o).get("format"))) throw new IOException("This is not a BurpShot settings file.");
        Object v = ((Map<?, ?>) o).get("version");
        if (!(v instanceof Number) || ((Number) v).intValue() > VERSION) throw new IOException("This settings file was made by a newer BurpShot.");
        Object sm = ((Map<?, ?>) o).get("settings");
        if (!(sm instanceof Map)) throw new IOException("The settings file has no settings.");
        final Map<?, ?> m = (Map<?, ?>) sm;
        Settings s = new Settings();
        s.load(k -> {
            Object x = m.get(k.startsWith("ptshot.") ? k.substring(7) : k);
            return x instanceof String && ((String) x).length() < 300 ? (String) x : null;
        });
        return s;
    }
}
