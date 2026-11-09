package ptshot;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * PNG encoding / decoding that never touches the disk and refuses hostile input.
 * <ul>
 *   <li>{@code ImageIO.write(img, "png", OutputStream)} may spool the image into a temp file (ImageIO's disk cache), which would leave
 *       screenshots of an engagement in the temp directory; here everything stays in memory.</li>
 *   <li>Decoding checks the PNG signature and the declared size before decoding, so a tiny "decompression bomb" file cannot exhaust memory.</li>
 * </ul>
 */
final class Png {
    private Png() {}

    static final int MAX_BYTES = 64 << 20;          // one encoded image
    static final int MAX_DIM = 16384;               // width or height
    static final long MAX_PIXELS = 36_000_000L;     // ~144 MB as ARGB

    private static final byte[] SIG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    static boolean looksLikePng(byte[] d) {
        if (d == null || d.length < SIG.length) return false;
        for (int i = 0; i < SIG.length; i++) if (d[i] != SIG[i]) return false;
        return true;
    }

    static byte[] encode(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, img.getWidth() * img.getHeight() / 4));
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("png");
        if (!it.hasNext()) throw new IOException("no PNG encoder available");
        ImageWriter w = it.next();
        try (ImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            w.setOutput(ios);
            w.write(img);
        } finally {
            w.dispose();
        }
        return out.toByteArray();
    }

    /** Decodes a PNG; throws IOException for anything that is not a PNG or is implausibly large. */
    static BufferedImage decode(byte[] data) throws IOException {
        if (data == null || data.length > MAX_BYTES) throw new IOException("image too large");
        if (!looksLikePng(data)) throw new IOException("not a PNG image");
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("png");
        if (!it.hasNext()) throw new IOException("no PNG decoder available");
        ImageReader r = it.next();
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            r.setInput(in, true, true);
            int w = r.getWidth(0), h = r.getHeight(0);
            if (w <= 0 || h <= 0 || w > MAX_DIM || h > MAX_DIM || (long) w * h > MAX_PIXELS) throw new IOException("image dimensions out of range");
            BufferedImage img = r.read(0);
            if (img == null) throw new IOException("unreadable image");
            return img;
        } finally {
            r.dispose();
        }
    }
}
