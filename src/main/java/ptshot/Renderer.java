package ptshot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the "Request -- separator -- Response" screenshot itself (no OCR / no Burp UI scraping), so the output is
 * a clean, crisp image at any scale.
 */
final class Renderer {
    private Renderer() {}

    private static final int HARD_LINE_CAP = 600;

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static final class VLine {
        final String text;
        final int s, e; // source range (-1 for synthetic lines)

        VLine(String text, int s, int e) {
            this.text = text;
            this.s = s;
            this.e = e;
        }
    }

    private static final class Laid {
        Block b;
        FontRenderContext frc;
        Font lf, bf;
        String label;
        Color lcol, base;
        Color[] colors;
        final List<VLine> lines = new ArrayList<>();
        int labelW, labelH, labelAsc, labelGap, textW, textH, bpad, boxW, boxH, w, h, x, y, lineH, asc;
        BufferedImage img;
        int imgW, imgH;

        double sw(Font f, String s) {
            return s.isEmpty() ? 0 : f.getStringBounds(s, frc).getWidth();
        }

        int off(int free, int align) {
            return align == 1 ? free / 2 : align == 2 ? free : 0;
        }

        int labelX() {
            return x + off(w - labelW, b.align);
        }

        int bodyX() {
            return x + (b.kind == Block.Kind.IMAGE ? off(w - boxW, b.align) : 0);
        }

        int bodyY() {
            return y + (label != null ? labelH + labelGap : 0);
        }
    }

    private static final class Rule {
        final int x, y, w, h;
        final Color c;

        Rule(int x, int y, int w, int h, Color c) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.c = c;
        }
    }

    static BufferedImage render(List<Block> blocks, Settings st, double S) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D mg = scratch.createGraphics();
        hints(mg);
        List<Laid> all = new ArrayList<>();
        for (Block b : blocks) all.add(measure(b, st, S, mg));
        mg.dispose();
        if (all.isEmpty()) {
            BufferedImage e = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = e.createGraphics();
            g.setColor(st.background);
            g.fillRect(0, 0, 10, 10);
            g.dispose();
            return e;
        }

        int margin = px(st.margin, S), rowGap = px(st.rowGap, S), colGap = px(st.colGap, S);

        // ---- arrange into rows ----
        List<List<Laid>> rows = new ArrayList<>();
        for (Laid a : all) {
            if (a.b.beside && !rows.isEmpty()) rows.get(rows.size() - 1).add(a);
            else {
                List<Laid> r = new ArrayList<>();
                r.add(a);
                rows.add(r);
            }
        }
        int inner = 0;
        for (List<Laid> r : rows) {
            int w = 0;
            for (Laid a : r) w += a.w;
            w += colGap * (r.size() - 1);
            inner = Math.max(inner, w);
        }
        int W = inner + 2 * margin;
        List<Rule> rules = new ArrayList<>();
        int y = margin;
        for (int ri = 0; ri < rows.size(); ri++) {
            List<Laid> r = rows.get(ri);
            int rowH = 0;
            for (Laid a : r) rowH = Math.max(rowH, a.h);
            int x = margin;
            for (int j = 0; j < r.size(); j++) {
                Laid a = r.get(j);
                a.y = y;
                a.x = x;
                if (r.size() == 1 && a.b.align != 0) a.x = margin + a.off(inner - a.w, a.b.align);
                x += a.w + colGap;
                if (j < r.size() - 1 && a.b.separatorAfter) {
                    int sw = sepPx(a.b, st, S);
                    rules.add(new Rule(a.x + a.w + (colGap - sw) / 2, y, sw, rowH, sepColor(a.b, st)));
                }
            }
            y += rowH;
            if (ri < rows.size() - 1) {
                Block last = r.get(r.size() - 1).b;
                if (last.separatorAfter) {
                    int sw = sepPx(last, st, S);
                    y += rowGap;
                    rules.add(new Rule(0, y, W, sw, sepColor(last, st)));
                    y += sw + rowGap;
                } else {
                    y += rowGap;
                }
            }
        }
        int H = y + margin;

        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        hints(g);
        g.setColor(st.background);
        g.fillRect(0, 0, W, H);
        for (Rule r : rules) {
            g.setColor(r.c);
            g.fillRect(r.x, r.y, r.w, r.h);
        }
        for (Laid a : all) draw(g, a);
        g.dispose();

        // ---- annotations: pixelate/blur first (they destroy pixels), then boxes/arrows on top ----
        List<Object[]> todo = new ArrayList<>(); // {Annotation, Rectangle}
        for (Laid a : all) {
            for (Annotation an : a.b.annotations) {
                Rectangle r = anchorRect(a, an, S);
                if (r != null) todo.add(new Object[]{an, r});
            }
        }
        for (Object[] t : todo) {
            Annotation an = (Annotation) t[0];
            Rectangle r = inflate((Rectangle) t[1], an.pad * S);
            if (an.type == Annotation.Type.PIXELATE) pixelate(out, r, Math.max(2, (int) Math.round(an.strength * S)));
            if (an.type == Annotation.Type.BLUR) blur(out, r, Math.max(1, (int) Math.round(an.strength * S / 2.0)));
        }
        Graphics2D g2 = out.createGraphics();
        hints(g2);
        for (Object[] t : todo) {
            Annotation an = (Annotation) t[0];
            Rectangle r = (Rectangle) t[1];
            if (an.type == Annotation.Type.BOX) {
                Rectangle b = inflate(r, an.pad * S);
                g2.setColor(an.color);
                g2.setStroke(new BasicStroke((float) (an.width * S), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                g2.drawRect(b.x, b.y, b.width, b.height);
            } else if (an.type == Annotation.Type.ARROW) {
                arrow(g2, an, r, S);
            }
        }
        g2.dispose();
        return out;
    }

    // ------------------------------------------------------------------ measuring

    private static int px(double v, double S) {
        return (int) Math.round(v * S);
    }

    private static int sepPx(Block b, Settings st, double S) {
        return Math.max(1, px(b.sepWidth > 0 ? b.sepWidth : st.sepWidth, S));
    }

    private static Color sepColor(Block b, Settings st) {
        return b.sepColor != null ? b.sepColor : st.sepColor;
    }

    private static Rectangle inflate(Rectangle r, double by) {
        int d = (int) Math.round(by);
        return new Rectangle(r.x - d, r.y - d, r.width + 2 * d, r.height + 2 * d);
    }

    private static Laid measure(Block b, Settings st, double S, Graphics2D mg) {
        Laid a = new Laid();
        a.b = b;
        a.frc = mg.getFontRenderContext();
        FontSpec ls = b.labelFont != null ? b.labelFont : st.labelFont;
        if (b.showLabel && b.label != null && !b.label.isEmpty()) {
            a.label = b.label;
            a.lf = ls.toFont(S);
            a.lcol = ls.color;
            FontMetrics fm = mg.getFontMetrics(a.lf);
            a.labelH = fm.getHeight();
            a.labelAsc = fm.getAscent();
            a.labelW = (int) Math.ceil(a.sw(a.lf, a.label));
            a.labelGap = px(st.labelGap, S);
        }
        if (b.kind == Block.Kind.IMAGE) {
            a.img = b.image;
            if (a.img != null) {
                double k = Math.min(1.0, b.imageMaxWidth / (double) a.img.getWidth()) * S;
                a.imgW = Math.max(1, (int) Math.round(a.img.getWidth() * k));
                a.imgH = Math.max(1, (int) Math.round(a.img.getHeight() * k));
            }
            a.boxW = a.imgW;
            a.boxH = a.imgH;
        } else {
            FontSpec bs = b.bodyFont != null ? b.bodyFont : st.bodyFont;
            a.bf = bs.toFont(S);
            a.base = bs.color;
            FontMetrics fm = mg.getFontMetrics(a.bf);
            a.lineH = fm.getHeight();
            a.asc = fm.getAscent();
            layoutLines(a, b);
            double mw = 0;
            for (VLine v : a.lines) mw = Math.max(mw, a.sw(a.bf, v.text));
            a.textW = (int) Math.ceil(mw);
            a.textH = a.lines.size() * a.lineH;
            a.bpad = b.background != null ? px(6, S) : 0;
            a.boxW = a.lines.isEmpty() ? 0 : a.textW + 2 * a.bpad;
            a.boxH = a.lines.isEmpty() ? 0 : a.textH + 2 * a.bpad;
            if (b.highlight && b.isHttp()) a.colors = Highlighter.colorize(b.text, b.kind == Block.Kind.REQUEST, a.base, st.syn);
        }
        a.w = Math.max(a.labelW, a.boxW);
        a.h = (a.label != null ? a.labelH + (a.boxH > 0 ? a.labelGap : 0) : 0) + a.boxH;
        return a;
    }

    private static void layoutLines(Laid a, Block b) {
        String text = b.text;
        if (text == null || text.isEmpty()) return;
        double maxPx = b.wrapCols > 0 ? a.sw(a.bf, "0") * b.wrapCols : 0;
        int limit = b.maxLines > 0 ? Math.min(b.maxLines, HARD_LINE_CAP) : HARD_LINE_CAP;
        int off = 0;
        boolean truncated = false;
        outer:
        for (String line : text.split("\n", -1)) {
            if (maxPx <= 0 || a.sw(a.bf, line) <= maxPx) {
                a.lines.add(new VLine(line, off, off + line.length()));
            } else if (b.wrap) {
                int st = 0;
                double w = 0;
                for (int i = 0; i < line.length(); i++) {
                    double cw = a.sw(a.bf, line.substring(i, i + 1));
                    if (w + cw > maxPx && i > st) {
                        a.lines.add(new VLine(line.substring(st, i), off + st, off + i));
                        if (a.lines.size() > limit) {
                            truncated = true;
                            break outer;
                        }
                        st = i;
                        w = 0;
                    }
                    w += cw;
                }
                a.lines.add(new VLine(line.substring(st), off + st, off + line.length()));
            } else {
                double dots = a.sw(a.bf, "...");
                int i = 0;
                double w = 0;
                while (i < line.length()) {
                    double cw = a.sw(a.bf, line.substring(i, i + 1));
                    if (w + cw > maxPx - dots) break;
                    w += cw;
                    i++;
                }
                a.lines.add(new VLine(line.substring(0, i) + "...", off, off + i));
            }
            off += line.length() + 1;
            if (a.lines.size() > limit) {
                truncated = true;
                break;
            }
        }
        if (truncated || a.lines.size() > limit) {
            while (a.lines.size() > limit) a.lines.remove(a.lines.size() - 1);
            a.lines.add(new VLine("...", -1, -1));
        }
    }

    // ------------------------------------------------------------------ drawing

    private static void draw(Graphics2D g, Laid a) {
        if (a.label != null) {
            g.setFont(a.lf);
            g.setColor(a.lcol);
            g.drawString(a.label, (float) a.labelX(), (float) (a.y + a.labelAsc));
        }
        if (a.img != null) {
            g.drawImage(a.img, a.bodyX(), a.bodyY(), a.imgW, a.imgH, null);
            return;
        }
        if (a.lines.isEmpty()) return;
        int bx = a.bodyX(), by = a.bodyY();
        if (a.b.background != null) {
            g.setColor(a.b.background);
            g.fillRect(bx, by, a.boxW, a.boxH);
        }
        g.setFont(a.bf);
        for (int i = 0; i < a.lines.size(); i++) {
            VLine v = a.lines.get(i);
            float base = by + a.bpad + i * a.lineH + a.asc;
            double x0 = bx + a.bpad;
            if (a.colors == null || v.s < 0) {
                g.setColor(a.base);
                g.drawString(v.text, (float) x0, base);
                continue;
            }
            int n = v.e - v.s, len = v.text.length(), k = 0;
            while (k < len) {
                Color c = k < n ? a.colors[v.s + k] : a.base;
                int j = k + 1;
                while (j < len && (j < n ? a.colors[v.s + j] : a.base).equals(c)) j++;
                g.setColor(c);
                g.drawString(v.text.substring(k, j), (float) (x0 + a.sw(a.bf, v.text.substring(0, k))), base);
                k = j;
            }
        }
    }

    private static int nth(String hay, String needle, int occ) {
        int idx = -1, from = 0;
        for (int i = 0; i <= occ; i++) {
            idx = hay.indexOf(needle, from);
            if (idx < 0) return i == 0 ? -1 : lastIdx(hay, needle);
            from = idx + 1;
        }
        return idx;
    }

    private static int lastIdx(String hay, String needle) {
        return hay.lastIndexOf(needle);
    }

    private static Rectangle anchorRect(Laid a, Annotation an, double S) {
        if (a.lines.isEmpty() || an.anchor == null || an.anchor.isEmpty()) return null;
        int s = nth(a.b.text, an.anchor, an.occurrence);
        if (s < 0) return null;
        int e = s + an.anchor.length();
        int bx = a.bodyX() + a.bpad, by = a.bodyY() + a.bpad;
        Rectangle r = null;
        for (int i = 0; i < a.lines.size(); i++) {
            VLine v = a.lines.get(i);
            if (v.s < 0 || v.e <= v.s) continue;
            int lo = Math.max(s, v.s), hi = Math.min(e, v.e);
            if (hi <= lo) continue;
            double x0 = bx + a.sw(a.bf, v.text.substring(0, lo - v.s));
            double x1 = bx + a.sw(a.bf, v.text.substring(0, hi - v.s));
            Rectangle lr = new Rectangle((int) Math.floor(x0), by + i * a.lineH, (int) Math.ceil(x1 - x0), a.lineH);
            r = r == null ? lr : r.union(lr);
        }
        if (r != null && an.fullWidth) {
            r.x = bx;
            r.width = Math.max(r.width, a.textW);
        }
        return r;
    }

    private static void arrow(Graphics2D g, Annotation an, Rectangle r, double S) {
        double pad = an.pad * S, sx, sy;
        switch (an.edge) {
            case TOP: sx = r.getCenterX(); sy = r.y - pad; break;
            case LEFT: sx = r.x - pad; sy = r.getCenterY(); break;
            case RIGHT: sx = r.getMaxX() + pad; sy = r.getCenterY(); break;
            case CENTER: sx = r.getCenterX(); sy = r.getCenterY(); break;
            default: sx = r.getCenterX(); sy = r.getMaxY() + pad;
        }
        sx += an.dx * S;
        sy += an.dy * S;
        double len = an.length * S, head = Math.min(an.headSize * S, len), hw = an.headSize * S * 1.3;
        double neck = Math.max(0, len - head);
        AffineTransform old = g.getTransform();
        g.translate(sx, sy);
        g.rotate(Math.toRadians(an.angle));
        if (an.blockArrow) {
            double sw = an.width * S, tw = sw * 0.45;
            Path2D p = new Path2D.Double();
            p.moveTo(0, -tw / 2);
            p.lineTo(neck, -sw / 2);
            p.lineTo(neck, -hw / 2);
            p.lineTo(len, 0);
            p.lineTo(neck, hw / 2);
            p.lineTo(neck, sw / 2);
            p.lineTo(0, tw / 2);
            p.closePath();
            Color faint = new Color(an.color.getRed(), an.color.getGreen(), an.color.getBlue(), 80);
            g.setPaint(new GradientPaint(0f, 0f, faint, (float) Math.max(1, len), 0f, an.color));
            g.fill(p);
        } else {
            g.setColor(an.color);
            g.setStroke(new BasicStroke((float) (an.width * S), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.draw(new java.awt.geom.Line2D.Double(0, 0, neck + 1, 0));
            Path2D p = new Path2D.Double();
            p.moveTo(neck, -hw / 2);
            p.lineTo(len, 0);
            p.lineTo(neck, hw / 2);
            p.closePath();
            g.fill(p);
        }
        g.setTransform(old);
    }

    // ------------------------------------------------------------------ pixel effects

    private static Rectangle clip(BufferedImage img, Rectangle r) {
        return r.intersection(new Rectangle(0, 0, img.getWidth(), img.getHeight()));
    }

    static void pixelate(BufferedImage img, Rectangle r0, int cell) {
        Rectangle r = clip(img, r0);
        if (r.isEmpty()) return;
        int[] px = img.getRGB(r.x, r.y, r.width, r.height, null, 0, r.width);
        for (int cy = 0; cy < r.height; cy += cell) {
            for (int cx = 0; cx < r.width; cx += cell) {
                int w = Math.min(cell, r.width - cx), h = Math.min(cell, r.height - cy);
                long R = 0, G = 0, B = 0;
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) {
                        int p = px[(cy + y) * r.width + cx + x];
                        R += (p >> 16) & 255;
                        G += (p >> 8) & 255;
                        B += p & 255;
                    }
                int n = w * h;
                int avg = (int) (R / n) << 16 | (int) (G / n) << 8 | (int) (B / n);
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) px[(cy + y) * r.width + cx + x] = avg;
            }
        }
        img.setRGB(r.x, r.y, r.width, r.height, px, 0, r.width);
    }

    static void blur(BufferedImage img, Rectangle r0, int radius) {
        Rectangle r = clip(img, r0);
        if (r.isEmpty()) return;
        int w = r.width, h = r.height;
        int[] px = img.getRGB(r.x, r.y, w, h, null, 0, w);
        for (int pass = 0; pass < 3; pass++) {
            px = boxPass(px, w, h, radius, true);
            px = boxPass(px, w, h, radius, false);
        }
        img.setRGB(r.x, r.y, w, h, px, 0, w);
    }

    private static int[] boxPass(int[] src, int w, int h, int rad, boolean horiz) {
        int[] dst = new int[src.length];
        int lines = horiz ? h : w, len = horiz ? w : h;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                long R = 0, G = 0, B = 0;
                int cnt = 0;
                for (int k = -rad; k <= rad; k++) {
                    int j = Math.max(0, Math.min(len - 1, i + k));
                    int p = horiz ? src[l * w + j] : src[j * w + l];
                    R += (p >> 16) & 255;
                    G += (p >> 8) & 255;
                    B += p & 255;
                    cnt++;
                }
                int v = (int) (R / cnt) << 16 | (int) (G / cnt) << 8 | (int) (B / cnt);
                if (horiz) dst[l * w + i] = v;
                else dst[i * w + l] = v;
            }
        }
        return dst;
    }
}
