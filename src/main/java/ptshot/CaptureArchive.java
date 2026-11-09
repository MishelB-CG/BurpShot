package ptshot;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * One capture as a single file: a ZIP with {@code manifest.json}, {@code capture.json} (everything except pixels) and
 * {@code images/<hash>.png}. The same bytes are what gets stored in the project file / session / folder (optionally encrypted by
 * {@link Vault}) and what "Share" uploads.
 *
 * <p>Reading is defensive: only a fixed set of entry names is accepted (so nothing can ever be written to a path chosen by the archive -
 * entries are only ever read into memory), the number and size of entries is capped while reading (zip bombs), the JSON goes through the
 * limited parser in {@link Store}, and images through {@link Png#decode}.
 */
final class CaptureArchive {
    private CaptureArchive() {}

    static final String FORMAT = "burpshot-capture";
    static final int VERSION = 1;
    private static final Pattern IMAGE = Pattern.compile("images/[0-9a-f]{32}\\.png");
    private static final int MAX_ENTRIES = 1200;
    private static final long MAX_TOTAL = 512L << 20, MAX_JSON = 96L << 20;
    private static final long FIXED_TIME = 315532800000L; // 1980-01-01, so archives do not leak the save time

    /** Encoded PNG per image object: unchanged images are not re-encoded on every save. */
    private static final Map<BufferedImage, Enc> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Enc {
        final byte[] png;
        final String name;

        Enc(byte[] png, String name) {
            this.png = png;
            this.name = name;
        }
    }

    private static Enc encoded(BufferedImage img) throws IOException {
        Enc e = CACHE.get(img);
        if (e == null) {
            byte[] png = Png.encode(img);
            e = new Enc(png, "images/" + sha256hex(png).substring(0, 32) + ".png");
            CACHE.put(img, e);
        }
        return e;
    }

    private static String sha256hex(byte[] d) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(d);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param outputPng optional rendered screenshot to include as {@code output.png} (handy when the archive is shared); may be null */
    static byte[] pack(String id, String title, double scale, int maxWidth, List<Block> blocks, byte[] outputPng) throws IOException {
        final Map<String, byte[]> images = new LinkedHashMap<>();
        Store.Images io = new Store.Images() {
            public String put(BufferedImage img) throws IOException {
                Enc e = encoded(img);
                images.put(e.name, e.png);
                return e.name;
            }

            public BufferedImage get(String ref) {
                return null;
            }
        };
        String capture = Store.serializeRef(title, scale, maxWidth, blocks, io);
        Map<String, Object> mf = new LinkedHashMap<>();
        mf.put("format", FORMAT);
        mf.put("version", VERSION);
        mf.put("id", id);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.setLevel(Deflater.DEFAULT_COMPRESSION);
            add(z, "manifest.json", Mini.write(mf).getBytes(StandardCharsets.UTF_8));
            add(z, "capture.json", capture.getBytes(StandardCharsets.UTF_8));
            z.setLevel(Deflater.BEST_SPEED); // PNG data is already compressed
            for (Map.Entry<String, byte[]> e : images.entrySet()) add(z, e.getKey(), e.getValue());
            if (outputPng != null) add(z, "output.png", outputPng);
        }
        return out.toByteArray();
    }

    private static void add(ZipOutputStream z, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(FIXED_TIME);
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    /** Reads an archive produced by {@link #pack}. Throws IOException / IllegalArgumentException for anything that is not a valid capture. */
    static Store.Restored unpack(byte[] zip) throws IOException {
        final Map<String, byte[]> files = readAll(zip);
        byte[] mf = files.get("manifest.json"), cap = files.get("capture.json");
        if (mf == null || cap == null) throw new IOException("Not a BurpShot capture archive.");
        Object m = Mini.parse(new String(mf, StandardCharsets.UTF_8));
        if (!(m instanceof Map) || !FORMAT.equals(((Map<?, ?>) m).get("format"))) throw new IOException("Not a BurpShot capture archive.");
        Object v = ((Map<?, ?>) m).get("version");
        if (!(v instanceof Number) || ((Number) v).intValue() > VERSION) throw new IOException("This archive was made by a newer BurpShot.");
        Store.Images io = new Store.Images() {
            public String put(BufferedImage img) throws IOException {
                throw new IOException("read-only");
            }

            public BufferedImage get(String ref) throws IOException {
                if (ref == null || !IMAGE.matcher(ref).matches()) return null;
                byte[] png = files.get(ref);
                return png == null ? null : Png.decode(png);
            }
        };
        return Store.deserialize(new String(cap, StandardCharsets.UTF_8), io);
    }

    /** The rendered screenshot stored with the archive, if any. */
    static byte[] outputPng(byte[] zip) throws IOException {
        return readAll(zip).get("output.png");
    }

    private static Map<String, byte[]> readAll(byte[] zip) throws IOException {
        if (zip == null || zip.length < 22) throw new IOException("Not a BurpShot capture archive.");
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName();
                if (++count > MAX_ENTRIES) throw new IOException("Archive has too many entries.");
                boolean json = name.equals("manifest.json") || name.equals("capture.json");
                boolean ok = json || name.equals("output.png") || IMAGE.matcher(name).matches();
                if (e.isDirectory() || !ok) throw new IOException("Archive contains an unexpected entry.");
                if (files.containsKey(name)) throw new IOException("Archive contains a duplicate entry.");
                long cap = json ? MAX_JSON : Png.MAX_BYTES;
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[64 * 1024];
                long size = 0;
                int n;
                while ((n = in.read(chunk)) > 0) { // sizes in the zip headers are not trusted: count what really comes out
                    size += n;
                    total += n;
                    if (size > cap || total > MAX_TOTAL) throw new IOException("Archive is too large when unpacked.");
                    buf.write(chunk, 0, n);
                }
                files.put(name, buf.toByteArray());
            }
        }
        return files;
    }
}
