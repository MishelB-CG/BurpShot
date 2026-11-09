package ptshot;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Borders: stored with the capture, drawn around layers and around the whole capture, and hostile values are clamped. */
public class BorderSelfTest {
    static int fails;

    static void check(String n, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + n);
        if (!ok) fails++;
    }

    static Border border(int w, int r, int sh, Color c) {
        Border b = new Border();
        b.width = w;
        b.radius = r;
        b.shadow = sh;
        b.color = c;
        return b;
    }

    public static void main(String[] a) throws Exception {
        Settings st = new Settings();
        List<Block> blocks = new ArrayList<>(CoreSelfTest.sample(st));
        Block first = blocks.get(0);

        // ---- persistence
        first.border = border(3, 12, 40, new Color(200, 30, 30));
        Border frame = border(2, 20, 60, Color.BLUE);
        byte[] zip = CaptureArchive.pack("11111111-2222-3333-4444-555555555555", "t", 2, 900, frame, blocks, null);
        Store.Restored r = CaptureArchive.unpack(zip);
        check("layer border survives save / load", first.border.equals(r.blocks.get(0).border));
        check("capture frame survives save / load", frame.equals(r.frame));
        check("layers without a border stay without one", blocks.size() < 2 || r.blocks.get(1).border == null);
        check("duplicate() copies the border", first.border.equals(first.duplicate().border) && first.duplicate().border != first.border);
        check("old captures (no border keys) load fine", CaptureArchive.unpack(CaptureArchive.pack("11111111-2222-3333-4444-555555555556", "t", 2, 900, new ArrayList<>(CoreSelfTest.sample(st)), null)).frame == null);
        check("signature changes when the frame changes", !Store.signature(2, 900, null, blocks).equals(Store.signature(2, 900, frame, blocks)));
        Object hostile = Mini.parse("{\"color\":16777215,\"width\":999999,\"radius\":-5,\"shadow\":1e9}");
        String json = Store.serialize("x", 2, 900, new ArrayList<>(blocks));
        String bad = json.replaceFirst("\"blocks\"", "\"frame\":{\"color\":\"red\",\"width\":999999,\"radius\":-5,\"shadow\":1e9},\"blocks\"");
        Border hb = Store.deserialize(bad).frame;
        check("hostile border values are clamped (" + (hb == null ? "null" : hb.width + "/" + hb.radius + "/" + hb.shadow) + ")",
                hb != null && hb.width == Border.MAX_WIDTH && hb.radius == 0 && hb.shadow == Border.MAX_SHADOW && hb.color != null);

        // ---- rendering: whole capture
        List<Block> plain = new ArrayList<>();
        for (Block b : blocks) plain.add(b.duplicate());
        for (Block b : plain) b.border = null;
        Renderer.Result base = Renderer.renderFull(plain, st, 2, 900, null);
        Renderer.Result sq = Renderer.renderFull(plain, st, 2, 900, border(3, 0, 0, Color.BLACK));
        check("plain frame keeps the size", sq.image.getWidth() == base.image.getWidth() && sq.image.getHeight() == base.image.getHeight());
        check("plain frame paints the colour along the edge", (sq.image.getRGB(0, 5) & 0xffffff) == 0 && (sq.image.getRGB(2, 5) & 0xffffff) == 0 && (sq.image.getRGB(sq.image.getWidth() / 2, 0) & 0xffffff) == 0);
        Renderer.Result rd = Renderer.renderFull(plain, st, 2, 900, border(2, 20, 0, Color.BLACK));
        check("curved corners are transparent", (rd.image.getRGB(0, 0) >>> 24) == 0 && (rd.image.getRGB(rd.image.getWidth() / 2, rd.image.getHeight() / 2) >>> 24) == 255);
        Renderer.Result sh = Renderer.renderFull(plain, st, 2, 900, border(2, 0, 80, Color.BLACK));
        int pad = (sh.image.getWidth() - base.image.getWidth()) / 2;
        check("a shadow adds room around the image (" + pad + " px)", pad > 0 && sh.image.getHeight() == base.image.getHeight() + 2 * pad);
        boolean shifted = true;
        for (int i = 0; i < base.layers.size(); i++) shifted &= sh.layers.get(i).rect.x == base.layers.get(i).rect.x + pad && sh.layers.get(i).body.y == base.layers.get(i).body.y + pad;
        check("layer positions move with the shadow padding (editing handles stay correct)", shifted);
        int shadowPx = sh.image.getRGB(sh.image.getWidth() - pad / 2 - 1, sh.image.getHeight() - pad / 2 - 1);
        check("the shadow is a translucent dark area outside the capture", (shadowPx >>> 24) > 0 && (shadowPx >>> 24) < 255);

        // ---- rendering: one layer
        List<Block> one = new ArrayList<>();
        for (Block b : plain) one.add(b.duplicate());
        Renderer.Result l0 = Renderer.renderFull(one, st, 2, 900, null);
        Block target = one.get(0);
        target.border = border(4, 0, 0, new Color(255, 0, 0));
        Renderer.Result l1 = Renderer.renderFull(one, st, 2, 900, null);
        java.awt.Rectangle body = l1.layers.get(0).body;
        int outside = l1.image.getRGB(body.x - 2, body.y + body.height / 2) & 0xffffff;
        check("layer border is drawn just outside the layer body", outside == 0xff0000 && (l0.image.getRGB(body.x - 2, body.y + body.height / 2) & 0xffffff) != 0xff0000);
        check("layer border does not change the layout", l1.image.getWidth() == l0.image.getWidth() && l1.image.getHeight() == l0.image.getHeight());
        target.border = border(2, 24, 50, Color.BLACK);
        Renderer.Result l2 = Renderer.renderFull(one, st, 2, 900, null);
        check("layer with curvature + shadow renders without error", l2.image.getWidth() == l0.image.getWidth());

        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
