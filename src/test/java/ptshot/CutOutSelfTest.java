package ptshot;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/** Checks the "cut out a band and join" operation of the crop dialog pixel by pixel. */
public class CutOutSelfTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) fails++;
    }

    /** Every pixel encodes its own (x, y), so any misplaced pixel is detected. */
    static BufferedImage grid(int w, int h) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) im.setRGB(x, y, 0xff000000 | (x << 8) | y);
        return im;
    }

    static boolean px(BufferedImage im, int x, int y, int sx, int sy) {
        return im.getRGB(x, y) == (0xff000000 | (sx << 8) | sy);
    }

    public static void main(String[] a) {
        BufferedImage src = grid(100, 60);

        // horizontal band in the middle: rows 20..29 (10 rows) removed, the full width
        ImagePicker.Cut h = ImagePicker.cutOutBand(src, new Rectangle(0, 20, 100, 10), true);
        check("horizontal: height shrinks by the band, width unchanged", h.img.getHeight() == 50 && h.img.getWidth() == 100);
        check("horizontal: rows above the band are untouched", px(h.img, 0, 0, 0, 0) && px(h.img, 99, 19, 99, 19));
        check("horizontal: the first row after the band joins directly under row 19", px(h.img, 5, 20, 5, 30) && px(h.img, 99, 49, 99, 59));
        check("horizontal: no pixel from inside the band survives", !anyRow(h.img, 20, 29));
        check("horizontal: position of the layer is unchanged (band not at the start)", h.dx == 0 && h.dy == 0);

        // band at the very top: the rest keeps its place on screen
        ImagePicker.Cut top = ImagePicker.cutOutBand(src, new Rectangle(0, 0, 100, 15), true);
        check("top band: first remaining row is old row 15", top.img.getHeight() == 45 && px(top.img, 3, 0, 3, 15));
        check("top band: origin moved down by the removed rows so content stays in place", top.dy == 15 && top.dx == 0);

        // band at the bottom
        ImagePicker.Cut bot = ImagePicker.cutOutBand(src, new Rectangle(0, 50, 100, 10), true);
        check("bottom band: image ends at old row 49", bot.img.getHeight() == 50 && px(bot.img, 7, 49, 7, 49) && bot.dy == 0);

        // vertical band in the middle: columns 40..59 (20 columns) removed
        ImagePicker.Cut v = ImagePicker.cutOutBand(src, new Rectangle(40, 0, 20, 60), false);
        check("vertical: width shrinks by the band, height unchanged", v.img.getWidth() == 80 && v.img.getHeight() == 60);
        check("vertical: columns left of the band untouched", px(v.img, 0, 0, 0, 0) && px(v.img, 39, 59, 39, 59));
        check("vertical: first column after the band joins directly right of column 39", px(v.img, 40, 10, 60, 10) && px(v.img, 79, 59, 99, 59));
        check("vertical: no pixel from inside the band survives", !anyCol(v.img, 40, 59));
        check("vertical: position unchanged", v.dx == 0 && v.dy == 0);

        ImagePicker.Cut left = ImagePicker.cutOutBand(src, new Rectangle(0, 0, 25, 60), false);
        check("left band: remaining starts at old column 25, origin shifted", left.img.getWidth() == 75 && px(left.img, 0, 4, 25, 4) && left.dx == 25);

        // two cuts in a row (what the dialog allows): rows, then columns
        ImagePicker.Cut c1 = ImagePicker.cutOutBand(src, new Rectangle(0, 10, 100, 10), true);
        ImagePicker.Cut c2 = ImagePicker.cutOutBand(c1.img, new Rectangle(30, 0, 10, 50), false);
        check("two cuts: result size and a pixel from each side of both seams", c2.img.getWidth() == 90 && c2.img.getHeight() == 50
                && px(c2.img, 29, 9, 29, 9) && px(c2.img, 30, 9, 40, 9) && px(c2.img, 29, 10, 29, 20) && px(c2.img, 30, 10, 40, 20));

        // transparency is kept (the dialog works in ARGB)
        BufferedImage t = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        t.setRGB(2, 8, 0x80ff0000);
        ImagePicker.Cut tc = ImagePicker.cutOutBand(t, new Rectangle(0, 4, 10, 2), true);
        check("alpha channel preserved through the join", tc.img.getRGB(2, 6) == 0x80ff0000 && (tc.img.getRGB(0, 0) >>> 24) == 0);

        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** True if any pixel of the result originally came from source row y0..y1 (the pixel colour encodes its source position). */
    static boolean anyRow(BufferedImage im, int y0, int y1) {
        for (int y = 0; y < im.getHeight(); y++) for (int x = 0; x < im.getWidth(); x++) {
            int sy = im.getRGB(x, y) & 0xff;
            if (sy >= y0 && sy <= y1) return true;
        }
        return false;
    }

    /** True if any pixel of the result originally came from source column x0..x1. */
    static boolean anyCol(BufferedImage im, int x0, int x1) {
        for (int y = 0; y < im.getHeight(); y++) for (int x = 0; x < im.getWidth(); x++) {
            int sx = (im.getRGB(x, y) >> 8) & 0xff;
            if (sx >= x0 && sx <= x1) return true;
        }
        return false;
    }
}
