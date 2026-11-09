package ptshot;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import ptshot.RemoteClient.RemoteException;
import ptshot.RemoteClient.Result;
import ptshot.RemoteUtil.Cfg;

/**
 * "share" (UNC / mounted SMB, NFS) and "local" destinations, implemented with java.nio.file only.
 * Authentication for network shares is the operating system's business (mapped drive, mount, Windows session).
 */
final class RemoteFs {
    private RemoteFs() {}

    static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    /** Validates and resolves the configured folder. Package-private with explicit OS flag for testing. */
    static Path resolveDir(String raw, boolean windows) throws RemoteException {
        if (raw == null || raw.isBlank()) throw new RemoteException("Missing required setting: Folder path", null);
        String s = raw.trim();
        if (s.indexOf('\0') >= 0) throw new RemoteException("The folder path contains invalid characters.", null);
        if (!windows && (s.startsWith("\\\\") || s.matches("^[A-Za-z]:[\\\\/].*"))) {
            throw new RemoteException("Windows-style paths (\\\\server\\share, Z:\\) cannot be used on this operating system. "
                    + "Mount the share first (SMB/NFS authentication is done by the OS) and enter the mounted path, e.g. /mnt/share.", null);
        }
        Path p;
        try {
            p = Paths.get(s);
        } catch (InvalidPathException e) {
            throw new RemoteException("The folder path is not valid on this operating system.", null);
        }
        if (!p.isAbsolute()) {
            throw new RemoteException("Enter an absolute folder path (relative paths depend on Burp's working directory).", null);
        }
        return p.normalize();
    }

    private static Path dir(Cfg c) throws RemoteException {
        return resolveDir(c.get("path"), isWindows());
    }

    static Result upload(Cfg c, String name, byte[] data, RemoteClient.Ui ui) throws RemoteException {
        Path dir = dir(c);
        RemoteClient.prog(ui, "Writing to " + dir + " ...");
        Path target = write(dir, name, data);
        return new Result(target.toString(), data.length + " bytes written");
    }

    static Path write(Path dir, String name, byte[] data) throws RemoteException {
        String safe = RemoteUtil.sanitizeName(name);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(safe).normalize();
            if (!dir.equals(target.getParent())) {   // defence in depth; sanitizeName already removes separators
                throw new RemoteException("The file name was rejected.", null);
            }
            Path tmp = dir.resolve(".burpshot-" + RemoteUtil.randomHex(6) + ".tmp");
            try {
                Files.write(tmp, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
            return target;
        } catch (IOException | SecurityException e) {
            throw new RemoteException("Could not write to the folder: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " (" + RemoteUtil.stripCtl(e.getMessage()) + ")"), null);
        }
    }

    static Result test(Cfg c, RemoteClient.Ui ui) throws RemoteException {
        Path dir = dir(c);
        if (!Files.isDirectory(dir)) {
            Path parent = dir.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                throw new RemoteException("The folder does not exist and neither does its parent; check the path/mount.", null);
            }
            return new Result(dir.toString(), "The folder does not exist yet; it will be created on upload.");
        }
        String probe = RemoteClient.probeName();
        Path t = write(dir, probe, RemoteClient.PROBE);
        try {
            Files.deleteIfExists(t);
        } catch (IOException e) {
            throw new RemoteException("The folder is writable but the probe file could not be removed: " + probe, null);
        }
        return new Result(dir.toString(), "Folder is writable (probe written and removed).");
    }
}
