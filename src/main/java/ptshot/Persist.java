package ptshot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/**
 * Saving and restoring captures.
 *
 * <ul>
 *   <li><b>Where</b> ({@link Mode}): the Burp project file (default), memory for this session only, or a folder
 *       ({@code <folder>/<project name>/}).</li>
 *   <li><b>Encryption</b>: optional AES-256-GCM with a password-derived key ({@link Vault}); applies to every mode. When encryption is
 *       on and the vault is locked nothing is ever written in the clear: saves wait until the user unlocks.</li>
 *   <li><b>Never on the UI thread</b>: all reading, encoding and encrypting happens on one background thread; the UI thread only makes
 *       an immutable snapshot of what changed. At unload the pending work is flushed with a hard time limit, so closing Burp can not hang.</li>
 *   <li>Nothing sensitive is part of the settings that are read at start-up (mode, folder, encryption flag): no password, no keys,
 *       no credentials.</li>
 * </ul>
 */
final class Persist {
    enum Mode { PROJECT, SESSION, DIRECTORY }

    /** What the extension has to provide (Burp's project info and preferences); an interface so it can be tested without Burp. */
    interface Env {
        String projectName();

        CaptureBackend projectBackend();

        String pref(String key);

        void setPref(String key, String value);
    }

    /** Callbacks, always delivered on the Swing thread. */
    interface Sink {
        void loaded(String id, Store.Restored capture);

        /** Status after loading / unlocking / switching: how many saved captures are still locked, and an optional message. */
        void state(int locked, String message);

        void error(String message);
    }

    interface Done {
        /** null = success. */
        void done(String error);
    }

    /** An immutable copy of one capture, taken on the Swing thread and written on the storage thread. */
    static final class Snap {
        final String id, title;
        final double scale;
        final int maxWidth;
        final List<Block> blocks;
        final Border frame;   // border around the whole capture (null = none)

        Snap(String id, String title, double scale, int maxWidth, List<Block> blocks) {
            this(id, title, scale, maxWidth, null, blocks);
        }

        Snap(String id, String title, double scale, int maxWidth, Border frame, List<Block> blocks) {
            this.frame = frame == null ? null : frame.copy();
            this.id = id;
            this.title = title;
            this.scale = scale;
            this.maxWidth = maxWidth;
            this.blocks = new ArrayList<>();
            for (Block b : blocks) this.blocks.add(b.duplicate()); // images are shared, never changed in place
        }
    }

    private static final String K_MODE = "ptshot.store.mode", K_DIR = "ptshot.store.dir", K_ENC = "ptshot.store.encrypt";

    private final Env env;
    private final Vault vault = new Vault();
    private final MemoryBackend memory = new MemoryBackend();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BurpShot-storage");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> locked = new LinkedHashSet<>();
    private volatile Mode mode = Mode.PROJECT;
    private volatile String dir = "";
    private volatile boolean encrypt;
    private volatile CaptureBackend backend;
    private volatile Sink sink;
    private volatile String settingsProblem;

    Persist(Env env) {
        this.env = env;
        current = this;
        loadSettings();
        try {
            backend = make(mode, dir);
        } catch (Exception e) {
            settingsProblem = "Could not use the saved folder (" + safe(e) + "); using the Burp project file instead.";
            mode = Mode.PROJECT;
            backend = env.projectBackend();
        }
    }

    // ------------------------------------------------------------------ settings (no secrets here, validated on load)

    private void loadSettings() {
        try {
            String m = env.pref(K_MODE);
            if (m != null) mode = Mode.valueOf(m);
        } catch (Exception ignored) {
            mode = Mode.PROJECT;
        }
        String d = env.pref(K_DIR);
        dir = validFolder(d) ? d : "";
        if (mode == Mode.DIRECTORY && dir.isEmpty()) mode = Mode.PROJECT;
        encrypt = "true".equals(env.pref(K_ENC));
    }

    private void saveSettings() {
        env.setPref(K_MODE, mode.name());
        env.setPref(K_DIR, dir);
        env.setPref(K_ENC, String.valueOf(encrypt));
    }

    /** An absolute, normalised, plausible folder path (the saved value is untrusted input like everything read at start-up). */
    static boolean validFolder(String s) {
        if (s == null || s.isEmpty() || s.length() > 500 || s.indexOf('\0') >= 0) return false;
        try {
            Path p = Paths.get(s);
            return p.isAbsolute() && p.equals(p.normalize());
        } catch (Exception e) {
            return false;
        }
    }

    private CaptureBackend make(Mode m, String folder) throws IOException {
        switch (m) {
            case SESSION: return memory;
            case DIRECTORY:
                if (!validFolder(folder)) throw new IOException("The folder is not a valid absolute path.");
                return new DirectoryBackend(Paths.get(folder), env.projectName());
            default: return env.projectBackend();
        }
    }

    private static String safe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m.replaceAll("[\\r\\n]+", " ");
    }

    Mode mode() {
        return mode;
    }

    String dir() {
        return dir;
    }

    boolean encrypt() {
        return encrypt;
    }

    boolean unlocked() {
        return vault.isUnlocked();
    }

    /** Burp's user-level preferences (profiles of Share destinations, trusted SSH host keys). Never used for secrets. */
    String pref(String key) {
        return env.pref(key);
    }

    void setPref(String key, String value) {
        env.setPref(key, value);
    }

    synchronized int lockedCount() {
        return locked.size();
    }

    synchronized private List<String> lockedIds() {
        return new ArrayList<>(locked);
    }

    String where() {
        return backend.describe();
    }

    String settingsProblem() {
        return settingsProblem;
    }

    /** Encryption is switched on but there is no key yet: nothing can be saved until the user unlocks or sets a password. */
    boolean cannotSave() {
        return encrypt && !vault.isUnlocked();
    }

    /** Default folder for Save PNG / archives (only in folder mode), else null. */
    File screenshotDir() {
        try {
            if (backend instanceof DirectoryBackend) return ((DirectoryBackend) backend).screenshotFolder().toFile();
        } catch (IOException ignored) {
        }
        return null;
    }

    private void ui(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    // ------------------------------------------------------------------ loading

    /** Reads everything that was saved (on the storage thread); captures arrive through {@code sink} as they are decoded. */
    void start(Sink s) {
        this.sink = s;
        if (settingsProblem != null) {
            final String p = settingsProblem;
            ui(() -> s.error(p));
        }
        io.execute(() -> {
            try {
                CaptureBackend b = backend;
                List<String> ids = b.index();
                if (ids.isEmpty() && b instanceof LegacySource) ids = migrateLegacy(b, (LegacySource) b);
                for (String id : ids) loadOne(b, id, s);
                final int n = lockedCount();
                ui(() -> s.state(n, null));
            } catch (Throwable t) {
                final String m = "Could not load the saved captures: " + safe(t);
                ui(() -> s.error(m));
            }
        });
    }

    /** Imports captures saved by older versions (JSON with inline images) into the archive format; the old keys go away only if all worked. */
    private List<String> migrateLegacy(CaptureBackend pb, LegacySource src) throws IOException {
        List<String> legacy = src.legacyJson();
        if (legacy.isEmpty() || cannotSave()) return new ArrayList<>();
        List<String> ids = new ArrayList<>();
        boolean allOk = true;
        for (String json : legacy) {
            try {
                Store.Restored r = Store.deserialize(json);
                if (r.blocks.isEmpty()) continue;
                String id = UUID.randomUUID().toString();
                pb.write(id, seal(CaptureArchive.pack(id, r.title, r.scale, r.maxWidth, r.blocks, null)));
                ids.add(id);
            } catch (Throwable t) {
                allOk = false;
            }
        }
        pb.writeIndex(ids);
        if (allOk) src.clearLegacy();
        return ids;
    }

    private void loadOne(CaptureBackend b, String id, Sink s) {
        try {
            byte[] raw = b.read(id);
            if (raw == null) return;
            if (Vault.isEncrypted(raw)) {
                if (!vault.isUnlocked()) {
                    synchronized (this) {
                        locked.add(id);
                    }
                    return;
                }
                raw = vault.decrypt(raw);
            }
            final Store.Restored r = CaptureArchive.unpack(raw);
            ui(() -> s.loaded(id, r));
        } catch (Throwable t) {
            final String m = "Skipped an unreadable saved capture (" + safe(t) + ").";
            ui(() -> s.error(m));
        }
    }

    // ------------------------------------------------------------------ writing

    private byte[] seal(byte[] archive) throws IOException {
        if (!encrypt) return archive;
        if (!vault.isUnlocked()) throw new IOException("Encryption is on but the password has not been entered; nothing was written.");
        return vault.encrypt(archive);
    }

    /** Queues a write; returns false (and does nothing) while encryption is on but locked. */
    boolean save(Snap s) {
        if (cannotSave()) return false;
        io.execute(() -> {
            try {
                backend.write(s.id, seal(CaptureArchive.pack(s.id, s.title, s.scale, s.maxWidth, s.frame, s.blocks, null)));
            } catch (Throwable t) {
                report("Saving a capture failed: " + safe(t));
            }
        });
        return true;
    }

    void delete(String id) {
        io.execute(() -> {
            try {
                backend.delete(id);
                synchronized (this) {
                    locked.remove(id);
                }
            } catch (Throwable t) {
                report("Deleting a saved capture failed: " + safe(t));
            }
        });
    }

    /** Persists the tab order. Captures that exist but are still locked stay in the index. */
    void writeIndex(List<String> loadedIds) {
        final List<String> ids = new ArrayList<>(loadedIds);
        io.execute(() -> {
            try {
                for (String id : lockedIds()) if (!ids.contains(id)) ids.add(id);
                backend.writeIndex(ids);
            } catch (Throwable t) {
                report("Saving the capture list failed: " + safe(t));
            }
        });
    }

    private void report(String m) {
        Sink s = sink;
        if (s != null) ui(() -> s.error(m));
    }

    /** Waits (at most {@code ms}) for queued work; used when Burp unloads the extension so that closing can never hang on us. */
    boolean flush(long ms) {
        try {
            Future<?> f = io.submit(() -> { });
            f.get(ms, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void shutdown() {
        flush(4000);
        ShareDialog.wipe();
        vault.lock();
        io.shutdownNow();
    }

    // ------------------------------------------------------------------ password / encryption / location changes

    private void finish(Done d, Throwable t) {
        final String m = t == null ? null : safe(t);
        ui(() -> d.done(m));
    }

    private void rewriteAll(List<Snap> all) throws IOException {
        List<String> ids = new ArrayList<>();
        for (Snap s : all) {
            backend.write(s.id, seal(CaptureArchive.pack(s.id, s.title, s.scale, s.maxWidth, s.frame, s.blocks, null)));
            ids.add(s.id);
        }
        for (String id : lockedIds()) if (!ids.contains(id)) ids.add(id);
        backend.writeIndex(ids);
    }

    /** Turns encryption on (or changes the password) and re-writes every open capture encrypted. */
    void setPassword(char[] pw, List<Snap> all, Done d) {
        io.execute(() -> {
            try {
                if (lockedCount() > 0) throw new IOException("Unlock the existing encrypted captures first.");
                vault.setNew(pw);
                encrypt = true;
                rewriteAll(all);
                saveSettings();
                finish(d, null);
            } catch (Throwable t) {
                finish(d, t);
            } finally {
                java.util.Arrays.fill(pw, '\0');
            }
        });
    }

    /** Turns encryption off: captures are re-written in the clear. Needs the vault unlocked. */
    void disableEncryption(List<Snap> all, Done d) {
        io.execute(() -> {
            try {
                if (lockedCount() > 0) throw new IOException("Unlock the encrypted captures first.");
                encrypt = false;
                rewriteAll(all);
                saveSettings();
                finish(d, null);
            } catch (Throwable t) {
                finish(d, t);
            }
        });
    }

    /**
     * Enters the password for captures that are already saved. Every capture is judged on its own: a capture that is not encrypted (or was
     * saved under another password) can no longer make the others unreadable. Whatever cannot be opened stays locked and is reported.
     */
    void unlock(char[] pw, Done d) {
        io.execute(() -> {
            try {
                List<String> ids = lockedIds();
                if (ids.isEmpty()) throw new IOException("There is nothing to unlock.");
                Map<String, byte[]> raws = new LinkedHashMap<>();
                boolean opened = false, sawEncrypted = false;
                for (String id : ids) {
                    byte[] raw = backend.read(id);
                    if (raw == null) continue;
                    raws.put(id, raw);
                    if (!opened && Vault.isEncrypted(raw)) {
                        sawEncrypted = true;
                        try {
                            vault.unlock(pw, raw);
                            opened = true;
                        } catch (IOException | java.security.GeneralSecurityException e) {
                            // maybe another capture was saved under this password: keep looking
                        }
                    }
                }
                if (sawEncrypted && !opened) throw new Vault.BadPasswordException();
                List<String> stay = new ArrayList<>();
                Sink s = sink;
                for (Map.Entry<String, byte[]> en : raws.entrySet()) {
                    try {
                        byte[] raw = en.getValue();
                        if (Vault.isEncrypted(raw)) raw = vault.decrypt(raw);
                        final Store.Restored r = CaptureArchive.unpack(raw);
                        final String id = en.getKey();
                        if (s != null) ui(() -> s.loaded(id, r));
                    } catch (Throwable t) {
                        stay.add(en.getKey());
                    }
                }
                synchronized (this) {
                    locked.clear();
                    locked.addAll(stay);
                }
                if (!stay.isEmpty()) {
                    throw new IOException(stay.size() + " saved capture(s) could not be opened with this password (saved under a different password, or damaged). "
                            + "They stay locked; if you do not have that password use 'Forgot the password' to remove them.");
                }
                finish(d, null);
            } catch (Throwable t) {
                finish(d, t);
            } finally {
                java.util.Arrays.fill(pw, ' ');
            }
        });
    }

    /**
     * Escape hatch for a forgotten password: deletes the captures that are still locked, forgets the key and turns encryption off, so the
     * extension can never be stuck behind a password. Nothing else (open tabs, the settings) is touched.
     */
    void discardLocked(Done d) {
        io.execute(() -> {
            try {
                List<String> gone = lockedIds();
                for (String id : gone) backend.delete(id);
                synchronized (this) {
                    locked.clear();
                }
                vault.lock();
                encrypt = false;
                List<String> keep = new ArrayList<>(backend.index());
                keep.removeAll(gone);
                backend.writeIndex(keep);
                saveSettings();
                finish(d, null);
            } catch (Throwable t) {
                finish(d, t);
            }
        });
    }

    /** Wipes the key from memory. The caller removes the decrypted tabs from the screen. */
    void lock(Done d) {
        io.execute(() -> {
            try {
                vault.lock();
                List<String> ids = backend.index();
                synchronized (this) {
                    locked.clear();
                    locked.addAll(ids); // everything saved is locked again until the password is entered
                }
                finish(d, null);
            } catch (Throwable t) {
                finish(d, t);
            }
        });
    }

    /**
     * Writes a screenshot for Save PNG: a normal PNG, or - when {@code encrypted} - an encrypted container named {@code <name>.png.bsv}
     * (open it again with Storage... > Open a capture file...). Works in memory, so no unencrypted temp file is ever created.
     * @return the file that was written (the extension is normalised: .png or .png.bsv)
     */
    static File writeScreenshot(File chosen, java.awt.image.BufferedImage img, boolean encrypted) throws IOException {
        File parent = chosen.getAbsoluteFile().getParentFile();
        String base = chosen.getName().replaceAll("(?i)(\\.png)?(\\.bsv)?$", "");
        if (base.isEmpty()) base = "screenshot";
        byte[] png = Png.encode(img);
        File out;
        byte[] data;
        if (encrypted) {
            Persist me = current;
            if (me == null || !me.unlocked()) throw new IOException("Encryption needs your BurpShot password: unlock under Storage... first.");
            data = me.encryptBytes(png);
            out = new File(parent, base + ".png.bsv");
        } else {
            data = png;
            out = new File(parent, base + ".png");
        }
        java.nio.file.Files.write(out.toPath(), data);
        return out;
    }

    /** Whether Save PNG should offer encryption: 0 = off (not configured), 1 = on but locked, 2 = on and unlocked. */
    static int screenshotEncryptionState() {
        Persist me = current;
        if (me == null || !me.encrypt()) return 0;
        return me.unlocked() ? 2 : 1;
    }

    /** Decrypts a file with an explicit password, independent of the session vault (e.g. a file from another engagement). */
    static byte[] decryptWith(char[] pw, byte[] data) throws IOException, java.security.GeneralSecurityException {
        Vault v = new Vault();
        try {
            v.unlock(pw, data);
            return v.decrypt(data);
        } finally {
            v.lock();
        }
    }

    /** The one instance of this extension (used for the Save PNG default folder). */
    static volatile Persist current;

    static File defaultShotDir() {
        Persist p = current;
        return p == null ? null : p.screenshotDir();
    }

    /** Moves everything to a different place (project / session / folder). The old copy is left where it was. */
    void relocate(Mode newMode, String newDir, List<Snap> all, Done d) {
        io.execute(() -> {
            try {
                CaptureBackend nb = make(newMode, newDir);
                if (cannotSave()) throw new IOException("Unlock the captures first.");
                CaptureBackend old = backend;
                List<String> ids = new ArrayList<>();
                for (Snap s : all) {
                    nb.write(s.id, seal(CaptureArchive.pack(s.id, s.title, s.scale, s.maxWidth, s.frame, s.blocks, null)));
                    ids.add(s.id);
                }
                for (String id : lockedIds()) { // not decrypted: carried over as they are
                    byte[] raw = old.read(id);
                    if (raw != null) {
                        nb.write(id, raw);
                        ids.add(id);
                    }
                }
                nb.writeIndex(ids);
                backend = nb;
                mode = newMode;
                dir = newMode == Mode.DIRECTORY ? newDir : dir;
                settingsProblem = null;
                saveSettings();
                finish(d, null);
            } catch (Throwable t) {
                finish(d, t);
            }
        });
    }

    // ------------------------------------------------------------------ export (for "Share" / "Save archive")

    /** A capture as one file: the archive, encrypted with the vault password when {@code encryptIt}. Slow: call off the Swing thread. */
    byte[] exportArchive(Snap s, byte[] outputPng, boolean encryptIt) throws IOException {
        byte[] zip = CaptureArchive.pack(s.id, s.title, s.scale, s.maxWidth, s.blocks, outputPng);
        if (!encryptIt) return zip;
        if (!vault.isUnlocked()) throw new IOException("Enter your password first (Storage settings).");
        return vault.encrypt(zip);
    }

    /** Encrypts arbitrary bytes (e.g. a PNG) with the vault password. */
    byte[] encryptBytes(byte[] data) throws IOException {
        if (!vault.isUnlocked()) throw new IOException("Enter your password first (Storage settings).");
        return vault.encrypt(data);
    }

    /** Opens a file that BurpShot exported: decrypts it if needed (vault must be unlocked) and returns the plain bytes. */
    byte[] decryptIfNeeded(byte[] data) throws IOException, java.security.GeneralSecurityException {
        if (!Vault.isEncrypted(data)) return data;
        if (!vault.isUnlocked()) throw new IOException("The file is encrypted: enter your password first (Storage settings).");
        return vault.decrypt(data);
    }
}
