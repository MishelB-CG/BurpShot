package ptshot;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.persistence.PersistedObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Where capture archives are kept. Values are opaque byte arrays (an archive, or its encrypted form). Called from one storage thread. */
interface CaptureBackend {
    /** Human-readable place, for status messages. */
    String describe();

    /** Ordered ids of the saved captures. */
    List<String> index() throws IOException;

    void writeIndex(List<String> ids) throws IOException;

    /** null if the capture does not exist. */
    byte[] read(String id) throws IOException;

    void write(String id, byte[] data) throws IOException;

    void delete(String id) throws IOException;

    /** Capture ids are random UUIDs; anything else is refused so an id can never act as a path or a key prefix. */
    Pattern ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    static List<String> parseIds(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String s : text.split("[,\\r\\n]+")) {
            s = s.trim();
            if (validId(s) && !out.contains(s)) out.add(s);
            if (out.size() >= 5000) break;
        }
        return out;
    }
}

/** A backend that may still hold captures in the pre-archive format (JSON strings with inline images). */
interface LegacySource {
    List<String> legacyJson();

    void clearLegacy();
}

/** Kept in memory only: lives as long as this extension instance (the "this session" mode). */
final class MemoryBackend implements CaptureBackend {
    private final Map<String, byte[]> data = new LinkedHashMap<>();
    private List<String> order = new ArrayList<>();

    public synchronized String describe() {
        return "this session only (memory)";
    }

    public synchronized List<String> index() {
        return new ArrayList<>(order);
    }

    public synchronized void writeIndex(List<String> ids) {
        order = new ArrayList<>(ids);
    }

    public synchronized byte[] read(String id) {
        return data.get(id);
    }

    public synchronized void write(String id, byte[] d) {
        data.put(id, d);
    }

    public synchronized void delete(String id) {
        data.remove(id);
    }
}

/**
 * The Burp project file, through the extension-data API (project scoped: Burp saves it with the project and a temporary project drops it).
 * Captures are stored as binary values (no base64 inflation), one key per capture so that saving one tab does not rewrite the others.
 */
final class ProjectBackend implements CaptureBackend, LegacySource {
    private static final String INDEX = "ptshot2.index", CAP = "ptshot2.cap.";
    private static final String LEGACY_COUNT = "ptshot.captures.count", LEGACY = "ptshot.capture.";
    private final PersistedObject d;

    ProjectBackend(PersistedObject d) {
        this.d = d;
    }

    public String describe() {
        return "the Burp project file";
    }

    public List<String> index() {
        return CaptureBackend.parseIds(d.getString(INDEX));
    }

    public void writeIndex(List<String> ids) {
        d.setString(INDEX, String.join(",", ids));
    }

    public byte[] read(String id) {
        if (!CaptureBackend.validId(id)) return null;
        ByteArray b = d.getByteArray(CAP + id);
        return b == null ? null : b.getBytes();
    }

    public void write(String id, byte[] data) throws IOException {
        if (!CaptureBackend.validId(id)) throw new IOException("bad capture id");
        d.setByteArray(CAP + id, ByteArray.byteArray(data));
    }

    public void delete(String id) {
        if (CaptureBackend.validId(id)) d.deleteByteArray(CAP + id);
    }

    /** Captures saved by BurpShot versions before the archive format: JSON strings with inline images. */
    public List<String> legacyJson() {
        List<String> l = new ArrayList<>();
        Integer n = d.getInteger(LEGACY_COUNT);
        for (int i = 0; n != null && i < n && i < 1000; i++) {
            String s = d.getString(LEGACY + i);
            if (s != null) l.add(s);
        }
        return l;
    }

    public void clearLegacy() {
        Integer n = d.getInteger(LEGACY_COUNT);
        for (int i = 0; n != null && i < n && i < 1000; i++) d.deleteString(LEGACY + i);
        d.deleteInteger(LEGACY_COUNT);
    }
}

/** A folder: {@code <chosen folder>/<project name>/<id>.burpshot} plus {@code index.txt}; also holds the default Save-PNG folder. */
final class DirectoryBackend implements CaptureBackend {
    private final Path dir;

    /** @throws IOException if the folder cannot be used (not creatable, escapes the chosen folder, is a symbolic link) */
    DirectoryBackend(Path base, String projectName) throws IOException {
        Path b = base.toAbsolutePath().normalize();
        Files.createDirectories(b);
        Path p = b.resolve(safeName(projectName)).normalize();
        if (!p.startsWith(b) || p.equals(b)) throw new IOException("Invalid project folder name.");
        if (Files.isSymbolicLink(p)) throw new IOException("The project folder is a symbolic link; refusing to use it.");
        Files.createDirectories(p);
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX file system (Windows): the user's folder permissions apply
        }
        this.dir = p;
    }

    /** A project name made safe to use as a single folder name (no separators, no reserved Windows names, bounded length). */
    static String safeName(String name) {
        String s = name == null ? "" : name.replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("_{2,}", "_").trim();
        s = s.replaceAll("^[. ]+|[. ]+$", "");
        if (s.length() > 80) s = s.substring(0, 80);
        if (s.isEmpty()) s = "project";
        if (s.matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?")) s = "_" + s;
        return s;
    }

    Path folder() {
        return dir;
    }

    /** Default folder for the Save PNG button. */
    Path screenshotFolder() throws IOException {
        Path p = dir.resolve("screenshots");
        Files.createDirectories(p);
        return p;
    }

    public String describe() {
        return dir.toString();
    }

    private Path file(String id) throws IOException {
        if (!CaptureBackend.validId(id)) throw new IOException("bad capture id");
        return dir.resolve(id + ".burpshot");
    }

    public List<String> index() throws IOException {
        Path p = dir.resolve("index.txt");
        if (!Files.isRegularFile(p) || Files.size(p) > (1 << 20)) return new ArrayList<>();
        return CaptureBackend.parseIds(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    public void writeIndex(List<String> ids) throws IOException {
        atomic(dir.resolve("index.txt"), String.join("\n", ids).getBytes(StandardCharsets.UTF_8));
    }

    public byte[] read(String id) throws IOException {
        Path p = file(id);
        if (!Files.isRegularFile(p) || Files.isSymbolicLink(p)) return null;
        if (Files.size(p) > (700L << 20)) throw new IOException("capture file is implausibly large");
        return Files.readAllBytes(p);
    }

    public void write(String id, byte[] data) throws IOException {
        atomic(file(id), data);
    }

    public void delete(String id) throws IOException {
        Files.deleteIfExists(file(id));
    }

    private void atomic(Path target, byte[] data) throws IOException {
        Path tmp = dir.resolve("." + target.getFileName().toString().toLowerCase(Locale.ROOT) + ".tmp");
        Files.write(tmp, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
