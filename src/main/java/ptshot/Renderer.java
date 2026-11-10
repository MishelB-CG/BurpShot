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
        // terminals
        Term.Parsed term;
        int topExtra;     // window title bar above the text
        // tables
        List<List<String>> rows;
        int[] colW, colX;
        int rowH, padX, padY;
        BufferedImage img;
        int imgW, imgH;
        double S = 1;
        Color canvas = Color.WHITE;

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

    /** Where an annotation ended up in the output (device pixels) - used for live editing in the preview. */
    static final class Geo {
        final Block block;
        final Annotation an;
        final Rectangle rect;   // box / pixelate / blur (after manual adjustment); null for arrows
        final double[] arrow;   // sx, sy, ex, ey for arrows; null otherwise

        Geo(Block block, Annotation an, Rectangle rect, double[] arrow) {
            this.block = block;
            this.an = an;
            this.rect = rect;
            this.arrow = arrow;
        }
    }

    /** Where a whole block (label + body) ended up in the output (device pixels) - used to move / resize layers. */
    static final class Layer {
        final Block block;
        final Rectangle rect;
        final int bodyW;   // width of the text box / image itself (the part manual resizing controls)
        final Rectangle body; // the text box / image itself (without the label)

        Layer(Block block, Rectangle rect, int bodyW, Rectangle body) {
            this.block = block;
            this.rect = rect;
            this.bodyW = bodyW;
            this.body = body;
        }
    }

    static final class Result {
        final BufferedImage image;
        final List<Geo> geo;
        final List<Layer> layers;
        final double scale;

        Result(BufferedImage image, List<Geo> geo, List<Layer> layers, double scale) {
            this.image = image;
            this.geo = geo;
            this.layers = layers;
            this.scale = scale;
        }
    }

    static BufferedImage render(List<Block> blocks, Settings st, double S) {
        return render(blocks, st, S, st.maxWidth);
    }

    /** @param maxWidth maximum image width in 1x pixels (0 = unlimited); text wraps and images shrink to fit. */
    static BufferedImage render(List<Block> blocks, Settings st, double S, int maxWidth) {
        return renderFull(blocks, st, S, maxWidth).image;
    }

    static Result renderFull(List<Block> blocks, Settings st, double S, int maxWidth) {
        return renderFull(blocks, st, S, maxWidth, null);
    }

    /** @param frame border / curvature / shadow around the whole capture (null = none) */
    static Result renderFull(List<Block> blocks, Settings st, double S, int maxWidth, Border frame) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D mg = scratch.createGraphics();
        hints(mg);
        int margin = px(st.margin, S), rowGap = px(st.rowGap, S), colGap = px(st.colGap, S);
        int innerLimit = maxWidth > 0 ? Math.max(px(100, S), px(maxWidth, S) - 2 * margin) : 0;

        // group blocks into rows first, so side-by-side blocks can share the available width
        List<List<Block>> brow = new ArrayList<>();
        for (Block b : blocks) {
            if (b.overlay) continue;
            if (b.beside && !brow.isEmpty()) brow.get(brow.size() - 1).add(b);
            else {
                List<Block> r = new ArrayList<>();
                r.add(b);
                brow.add(r);
            }
        }
        List<Laid> all = new ArrayList<>();
        List<List<Laid>> rows = new ArrayList<>();
        for (List<Block> br : brow) {
            int n = br.size();
            int avail = innerLimit > 0 ? Math.max(px(60, S), innerLimit - colGap * (n - 1)) : 0;
            List<Laid> r = new ArrayList<>();
            for (Block b : br) r.add(measure(b, st, S, mg, avail));
            if (avail > 0 && n > 1) {
                int sum = 0;
                for (Laid a : r) sum += a.w;
                if (sum > avail) {
                    int[] w = new int[n];
                    for (int i = 0; i < n; i++) w[i] = r.get(i).w;
                    int[] allowed = share(w, avail);
                    for (int i = 0; i < n; i++) if (r.get(i).w > allowed[i]) r.set(i, measure(br.get(i), st, S, mg, allowed[i]));
                }
            }
            rows.add(r);
            all.addAll(r);
        }
        List<Laid> ovs = new ArrayList<>();
        for (Block b : blocks) if (b.overlay) ovs.add(measure(b, st, S, mg, 0));
        mg.dispose();
        if (all.isEmpty()) {
            BufferedImage e = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = e.createGraphics();
            g.setColor(st.background);
            g.fillRect(0, 0, 10, 10);
            g.dispose();
            return new Result(e, new ArrayList<Geo>(), new ArrayList<Layer>(), S);
        }

        int inner = 0;
        for (List<Laid> r : rows) {
            int w = 0;
            for (Laid a : r) w += a.w;
            w += colGap * (r.size() - 1);
            inner = Math.max(inner, w);
        }
        int W = inner + 2 * margin;
        final int ruleW = W;
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
                    rules.add(new Rule(0, y, ruleW, sw, sepColor(last, st)));
                    y += sw + rowGap;
                } else {
                    y += rowGap;
                }
            }
        }
        // manual layer moves (clamped to the canvas); the canvas grows to hold layers dragged past the edge
        int right = W, bottom = y + margin;
        for (Laid a : all) {
            a.x = Math.max(0, a.x + px(a.b.offX, S));
            a.y = Math.max(0, a.y + px(a.b.offY, S));
            right = Math.max(right, a.x + a.w + margin);
            bottom = Math.max(bottom, a.y + a.h + margin);
        }
        W = right;
        int H = bottom;
        for (Laid a : ovs) {
            a.x = Math.max(0, px(a.b.offX, S));
            a.y = Math.max(0, px(a.b.offY, S));
        }

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
        for (Laid a : ovs) draw(g, a);
        g.dispose();

        // ---- annotations: pixelate/blur first (they destroy pixels), then boxes/arrows on top ----
        List<Geo> geo = new ArrayList<>();
        for (Laid a : all) {
            for (Annotation an : a.b.annotations) {
                Rectangle r = an.free ? new Rectangle(px(an.fx, S), px(an.fy, S), Math.max(1, px(an.fw, S)), Math.max(1, px(an.fh, S))) : anchorRect(a, an, S);
                if (r == null) continue;
                if (an.type == Annotation.Type.ARROW) geo.add(new Geo(a.b, an, null, arrowPts(an, r, S)));
                else geo.add(new Geo(a.b, an, boxRect(an, r, S), null));
            }
        }
        for (Geo ge : geo) {
            if (ge.an.type == Annotation.Type.PIXELATE) pixelate(out, ge.rect, Math.max(2, (int) Math.round(ge.an.strength * S)));
            if (ge.an.type == Annotation.Type.BLUR) blur(out, ge.rect, Math.max(1, (int) Math.round(ge.an.strength * S / 2.0)));
        }
        Graphics2D g2 = out.createGraphics();
        hints(g2);
        for (Geo ge : geo) {
            Annotation an = ge.an;
            if (an.type == Annotation.Type.BOX) {
                Rectangle b = ge.rect;
                g2.setColor(an.color);
                g2.setStroke(new BasicStroke((float) (an.width * S), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                g2.drawRect(b.x, b.y, b.width, b.height);
            } else if (an.type == Annotation.Type.ARROW) {
                arrow(g2, an, ge.arrow, S);
            }
        }
        g2.dispose();
        List<Layer> layers = new ArrayList<>();
        for (Laid a : all) layers.add(new Layer(a.b, new Rectangle(a.x, a.y, a.w, a.h), a.boxW, new Rectangle(a.bodyX(), a.bodyY(), a.boxW, a.boxH)));
        for (Laid a : ovs) layers.add(new Layer(a.b, new Rectangle(a.x, a.y, a.w, a.h), a.boxW, new Rectangle(a.bodyX(), a.bodyY(), a.boxW, a.boxH)));
        if (frame != null && !frame.isNone()) {
            int pad = frame.shadow > 0 ? frame.blurPx(S) + frame.offsetPx(S) + 1 : 0;
            out = frame(out, frame, S, pad);
            if (pad > 0) { // everything moves with the image; the preview's editing handles use these
                for (Geo ge : geo) {
                    if (ge.rect != null) ge.rect.translate(pad, pad);
                    if (ge.arrow != null) {
                        ge.arrow[0] += pad;
                        ge.arrow[1] += pad;
                        ge.arrow[2] += pad;
                        ge.arrow[3] += pad;
                    }
                }
                for (Layer l : layers) {
                    l.rect.translate(pad, pad);
                    l.body.translate(pad, pad);
                }
            }
        }
        return new Result(out, geo, layers, S);
    }

    /** Draws the capture-wide frame: the image is clipped to rounded corners, gets its drop shadow and a stroke along its edge. */
    private static BufferedImage frame(BufferedImage img, Border f, double S, int pad) {
        boolean alpha = pad > 0 || f.radius > 0;
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage res = new BufferedImage(w + 2 * pad, h + 2 * pad, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = res.createGraphics();
        hints(g);
        Rectangle r = new Rectangle(pad, pad, w, h);
        Border.drawShadow(g, f, r, S);
        java.awt.Shape cs = Border.clipShape(f, r, S);
        if (cs != null) g.clip(cs);
        g.drawImage(img, pad, pad, null);
        g.setClip(null);
        int sw = (int) Math.ceil(f.width * S);
        if (f.width > 0) Border.drawStroke(g, f, new Rectangle(pad + sw, pad + sw, Math.max(1, w - 2 * sw), Math.max(1, h - 2 * sw)), S);
        g.dispose();
        return res;
    }

    /** Anchor rectangle + padding + the user's manual edge adjustments (1x pixels, set by dragging in the preview). */
    private static Rectangle boxRect(Annotation an, Rectangle anchor, double S) {
        Rectangle r = inflate(anchor, an.pad * S);
        int l = (int) Math.round(an.offL * S), t = (int) Math.round(an.offT * S);
        int rr = (int) Math.round(an.offR * S), b = (int) Math.round(an.offB * S);
        return new Rectangle(r.x + l, r.y + t, Math.max(2, r.width - l + rr), Math.max(2, r.height - t + b));
    }

    /** {startX, startY, endX, endY} of an arrow in device pixels. */
    private static double[] arrowPts(Annotation an, Rectangle r, double S) {
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
        double rad = Math.toRadians(an.angle), len = an.length * S;
        return new double[]{sx, sy, sx + len * Math.cos(rad), sy + len * Math.sin(rad)};
    }

    // ------------------------------------------------------------------ measuring

    /** Water-filling: blocks narrower than an equal share keep their width, the rest split what is left. */
    private static int[] share(int[] want, int total) {
        int n = want.length;
        int[] out = new int[n];
        boolean[] fixed = new boolean[n];
        int remaining = total, open = n;
        boolean changed = true;
        while (changed && open > 0) {
            changed = false;
            int eq = remaining / open;
            for (int i = 0; i < n; i++) {
                if (!fixed[i] && want[i] <= eq) {
                    fixed[i] = true;
                    out[i] = want[i];
                    remaining -= want[i];
                    open--;
                    changed = true;
                }
            }
        }
        for (int i = 0; i < n; i++) if (!fixed[i]) out[i] = Math.max(1, remaining / Math.max(1, open));
        return out;
    }

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

    private static Laid measure(Block b, Settings st, double S, Graphics2D mg, int limit) {
        Laid a = new Laid();
        a.b = b;
        a.S = S;
        a.canvas = st.background;
        a.frc = mg.getFontRenderContext();
        FontSpec ls = b.labelFont != null ? b.labelFont : st.labelFont;
        if (b.labelFont == null && st.labelRatio > 0 && b.kind != Block.Kind.IMAGE) {
            // default labels track the body font so "Request"/"Response" keep the same proportion at any Burp font size
            FontSpec bs0 = b.bodyFont != null ? b.bodyFont : st.bodyFont;
            ls = ls.copy();
            ls.size = Math.max(6, (int) Math.round(bs0.size * st.labelRatio));
        }
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
                if (limit > 0 && a.img.getWidth() * k > limit) k = limit / (double) a.img.getWidth();
                if (b.imageW > 0) k = b.imageW * S / a.img.getWidth(); // manual size wins (and may enlarge)
                a.imgW = Math.max(1, (int) Math.round(a.img.getWidth() * k));
                a.imgH = Math.max(1, (int) Math.round(a.img.getHeight() * k));
            }
            a.boxW = a.imgW;
            a.boxH = a.imgH;
        } else if (b.kind == Block.Kind.TABLE) {
            measureTable(a, b, st, S, mg);
        } else {
            FontSpec bs = b.bodyFont != null ? b.bodyFont : st.bodyFont;
            a.bf = bs.toFont(S);
            a.base = bs.color;
            FontMetrics fm = mg.getFontMetrics(a.bf);
            a.lineH = fm.getHeight();
            a.asc = fm.getAscent();
            a.bpad = bpadOf(b, S);
            if (b.kind == Block.Kind.TERMINAL) {
                a.topExtra = Term.chromeH(b) > 0 ? px(Term.chromeH(b), S) : 0;
                a.term = Term.parse(b.raw, Term.palette(b), a.base, Term.bg(b));
                Term.style(a.term, b);
                b.text = a.term.text;
            }
            layoutLines(a, b, b.boxW > 0 ? px(b.boxW, S) : limit);
            double mw = 0;
            for (VLine v : a.lines) mw = Math.max(mw, a.sw(a.bf, v.text));
            a.textW = (int) Math.ceil(mw);
            a.textH = a.lines.size() * a.lineH;
            a.bpad = bpadOf(b, S);
            a.boxW = a.lines.isEmpty() ? 0 : (b.boxW > 0 ? Math.max(px(b.boxW, S), a.textW + 2 * a.bpad) : a.textW + 2 * a.bpad);
            if (b.kind == Block.Kind.TERMINAL && a.topExtra > 0 && !a.lines.isEmpty()) {
                // the title bar needs room for its title plus the window buttons / traffic lights
                double tw = a.sw(a.bf.deriveFont(Font.PLAIN, (float) (a.bf.getSize2D() * 0.85)), b.title == null ? "" : b.title);
                String ch = b.chrome == null ? "" : b.chrome;
                double need = ch.equals("MAC") ? tw + 140 * S : ch.equals("LINUX") ? tw + 88 * S : tw + 170 * S;
                a.boxW = Math.max(a.boxW, (int) Math.ceil(need));
            }
            a.boxH = a.lines.isEmpty() ? 0 : a.textH + 2 * a.bpad + a.topExtra;
            if (b.highlight && b.isHttp()) {
                String shown = b.shown();
                a.colors = Highlighter.colorize(shown, b.kind == Block.Kind.REQUEST, a.base, st.syn, b.windowed() ? b.headerValue("content-type") : null);
            }
        }
        a.w = Math.max(a.labelW, a.boxW);
        a.h = (a.label != null ? a.labelH + (a.boxH > 0 ? a.labelGap : 0) : 0) + a.boxH;
        return a;
    }

    // ------------------------------------------------------------------ terminals

    private static int bpadOf(Block b, double S) {
        if (b.kind == Block.Kind.TERMINAL) return px(10, S);
        return b.background != null ? px(6, S) : 0;
    }

    private static boolean same(Color a, Color b) {
        return a == null ? b == null : a.equals(b);
    }

    private static void drawTerminal(Graphics2D g, Laid a) {
        int bx = a.bodyX(), by = a.bodyY();
        Color bg = Term.bg(a.b);
        g.setColor(bg);
        g.fillRect(bx, by, a.boxW, a.boxH);
        if (a.topExtra > 0) drawChrome(g, a, bx, by, bg);
        g.setFont(a.bf);
        Term.Parsed t = a.term;
        int top = by + a.topExtra + a.bpad;
        for (int i = 0; i < a.lines.size(); i++) {
            VLine v = a.lines.get(i);
            float base = top + i * a.lineH + a.asc;
            double x0 = bx + a.bpad;
            if (v.s < 0) {
                g.setColor(a.base);
                g.drawString(v.text, (float) x0, base);
                continue;
            }
            int n = v.e - v.s, len = v.text.length(), k = 0;
            while (k < len) {
                boolean real = k < n && v.s + k < t.fg.length;
                Color fc = real ? t.fg[v.s + k] : a.base, bc = real ? t.bg[v.s + k] : null;
                boolean u = real && t.ul[v.s + k];
                boolean bd = real && v.s + k < t.bold.length && t.bold[v.s + k];
                int j = k + 1;
                while (j < len) {
                    boolean r2 = j < n && v.s + j < t.fg.length;
                    if (r2 != real) break;
                    if (r2 && !(same(t.fg[v.s + j], fc) && same(t.bg[v.s + j], bc) && t.ul[v.s + j] == u && (v.s + j < t.bold.length && t.bold[v.s + j]) == bd)) break;
                    j++;
                }
                String seg = v.text.substring(k, j);
                double sx = x0 + a.sw(a.bf, v.text.substring(0, k)), sw = a.sw(a.bf, seg);
                if (bc != null) {
                    g.setColor(bc);
                    g.fillRect((int) Math.floor(sx), top + i * a.lineH, (int) Math.ceil(sw), a.lineH);
                }
                g.setColor(fc);
                g.setFont(bd ? a.bf.deriveFont(Font.BOLD) : a.bf);
                g.drawString(seg, (float) sx, base);
                if (u) g.fillRect((int) Math.floor(sx), (int) (base + a.lineH * 0.1), (int) Math.ceil(sw), Math.max(1, a.lineH / 16));
                k = j;
            }
        }
    }

    /** Window title bar: Windows (title left, three buttons right), macOS (traffic lights left), Linux (title centred, close right). */
    private static void drawChrome(Graphics2D g, Laid a, int bx, int by, Color termBg) {
        int w = a.boxW, h = a.topExtra;
        boolean dark = Term.dark(termBg);
        String style = a.b.chrome == null ? "NONE" : a.b.chrome;
        Color bar = style.equals("MAC") ? (dark ? new Color(58, 58, 58) : new Color(232, 232, 232))
                : style.equals("LINUX") ? (dark ? new Color(44, 44, 44) : new Color(225, 225, 225))
                : (dark ? new Color(32, 32, 32) : new Color(243, 243, 243));
        Color txt = Term.dark(bar) ? new Color(235, 235, 235) : new Color(30, 30, 30);
        g.setColor(bar);
        g.fillRect(bx, by, w, h);
        Font tf = a.bf.deriveFont(Font.PLAIN, (float) (a.bf.getSize2D() * 0.85));
        String name = a.b.title == null ? "" : a.b.title;
        FontMetrics fm = g.getFontMetrics(tf);
        int ty = by + (h + fm.getAscent() - fm.getDescent()) / 2;
        double u = h / 34.0;
        g.setFont(tf);
        g.setColor(txt);
        if (style.equals("MAC")) {
            int r = (int) Math.round(6 * u), cy = by + h / 2, cx = bx + (int) Math.round(18 * u);
            Color[] lights = {new Color(255, 95, 86), new Color(255, 189, 46), new Color(39, 201, 63)};
            for (int i = 0; i < 3; i++) {
                g.setColor(lights[i]);
                g.fillOval(cx + i * (int) Math.round(20 * u) - r, cy - r, 2 * r, 2 * r);
            }
            g.setColor(txt);
            g.drawString(name, bx + (w - fm.stringWidth(name)) / 2, ty);
        } else if (style.equals("LINUX")) {
            g.drawString(name, bx + (w - fm.stringWidth(name)) / 2, ty);
            int r = (int) Math.round(8 * u), cx = bx + w - (int) Math.round(22 * u), cy = by + h / 2;
            g.setColor(dark ? new Color(80, 80, 80) : new Color(200, 200, 200));
            g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
            g.setColor(txt);
            int d = (int) Math.round(3.5 * u);
            g.setStroke(new BasicStroke((float) Math.max(1.0, 1.4 * u)));
            g.drawLine(cx - d, cy - d, cx + d, cy + d);
            g.drawLine(cx - d, cy + d, cx + d, cy - d);
        } else {
            g.drawString(name, bx + (int) Math.round(14 * u), ty);
            int bw = (int) Math.round(46 * u), cy = by + h / 2, s = (int) Math.round(5 * u);
            g.setStroke(new BasicStroke((float) Math.max(1.0, 1.2 * u)));
            int x3 = bx + w - bw / 2, x2 = bx + w - bw - bw / 2, x1 = bx + w - 2 * bw - bw / 2;
            g.drawLine(x1 - s, cy, x1 + s, cy);
            g.drawRect(x2 - s, cy - s, 2 * s, 2 * s);
            g.drawLine(x3 - s, cy - s, x3 + s, cy + s);
            g.drawLine(x3 - s, cy + s, x3 + s, cy - s);
        }
        g.setStroke(new BasicStroke(1f));
    }

    // ------------------------------------------------------------------ tables

    private static void measureTable(Laid a, Block b, Settings st, double S, Graphics2D mg) {
        FontSpec bs = b.bodyFont != null ? b.bodyFont : st.bodyFont;
        a.bf = bs.toFont(S);
        a.base = bs.color;
        FontMetrics fm = mg.getFontMetrics(a.bf);
        a.lineH = fm.getHeight();
        a.asc = fm.getAscent();
        a.padX = px(8, S);
        a.padY = px(4, S);
        a.rowH = a.lineH + 2 * a.padY;
        a.rows = b.rows;
        int nc = 0;
        for (List<String> r : b.rows) nc = Math.max(nc, r.size());
        a.colW = new int[nc];
        a.colX = new int[nc];
        for (int c = 0; c < nc; c++) {
            int w = px(30, S);
            for (List<String> r : b.rows) if (c < r.size()) w = Math.max(w, (int) Math.ceil(a.sw(a.bf, r.get(c))) + 2 * a.padX);
            a.colW[c] = w;
        }
        int total = 0;
        for (int w : a.colW) total += w;
        int target = b.boxW > 0 ? px(b.boxW, S) : 0;
        if (target > total && nc > 0) {
            int extra = (target - total) / nc;
            for (int c = 0; c < nc; c++) a.colW[c] += extra;
            total = 0;
            for (int w : a.colW) total += w;
        }
        int x = 0;
        for (int c = 0; c < nc; c++) {
            a.colX[c] = x;
            x += a.colW[c];
        }
        a.textW = total;
        a.boxW = nc == 0 ? 0 : total;
        a.boxH = b.rows.size() * a.rowH;
    }

    private static void drawTable(Graphics2D g, Laid a) {
        int bx = a.bodyX(), by = a.bodyY(), w = a.boxW;
        if (w <= 0) return;
        g.setColor(a.b.background != null ? a.b.background : Color.WHITE);
        g.fillRect(bx, by, w, a.boxH);
        Color grid = new Color(222, 222, 222);
        for (int r = 0; r < a.rows.size(); r++) {
            int y = by + r * a.rowH;
            if (r == 0) {
                g.setColor(new Color(243, 243, 243));
                g.fillRect(bx, y, w, a.rowH);
            } else if (r % 2 == 1 && a.b.background == null) {
                g.setColor(new Color(247, 247, 247));
                g.fillRect(bx, y, w, a.rowH);
            }
        }
        g.setColor(grid);
        for (int c = 1; c < a.colX.length; c++) g.drawLine(bx + a.colX[c], by, bx + a.colX[c], by + a.boxH);
        g.setColor(new Color(200, 200, 200));
        g.drawLine(bx, by + a.rowH, bx + w - 1, by + a.rowH);
        g.setFont(a.bf);
        for (int r = 0; r < a.rows.size(); r++) {
            List<String> row = a.rows.get(r);
            g.setColor(r == 0 ? new Color(60, 60, 60) : a.base);
            for (int c = 0; c < row.size() && c < a.colX.length; c++) {
                g.drawString(row.get(c), (float) (bx + a.colX[c] + a.padX), (float) (by + r * a.rowH + a.padY + a.asc));
            }
        }
    }

    private static Rectangle cellRect(Laid a, int r, int c) {
        return new Rectangle(a.bodyX() + a.colX[c], a.bodyY() + r * a.rowH, a.colW[c], a.rowH);
    }

    private static Rectangle tableAnchor(Laid a, Annotation an) {
        if (a.colX == null || a.colX.length == 0 || a.rows.isEmpty() || an.anchor == null || an.anchor.isEmpty()) return null;
        if (an.anchor.startsWith("\u0001b:")) {
            try {
                String[] p = an.anchor.substring(3).split(":");
                int r0 = Integer.parseInt(p[0]), c0 = Integer.parseInt(p[1]), r1 = Integer.parseInt(p[2]), c1 = Integer.parseInt(p[3]);
                r0 = Math.max(0, Math.min(r0, a.rows.size() - 1));
                r1 = Math.max(0, Math.min(r1, a.rows.size() - 1));
                c0 = Math.max(0, Math.min(c0, a.colX.length - 1));
                c1 = Math.max(0, Math.min(c1, a.colX.length - 1));
                return cellRect(a, r0, c0).union(cellRect(a, r1, c1));
            } catch (RuntimeException e) {
                return null;
            }
        }
        int seen = 0;
        for (int r = 0; r < a.rows.size(); r++) {
            List<String> row = a.rows.get(r);
            for (int c = 0; c < row.size() && c < a.colX.length; c++) {
                if (row.get(c).contains(an.anchor)) {
                    if (seen++ == an.occurrence) return cellRect(a, r, c);
                }
            }
        }
        return null;
    }

    private static void layoutLines(Laid a, Block b, int limit) {
        String text = b.shown();
        if (text == null || text.isEmpty()) return;
        double maxPx = b.wrapCols > 0 ? a.sw(a.bf, "0") * b.wrapCols : 0;
        if (limit > 0) {
            double fit = Math.max(a.sw(a.bf, "0") * 8, limit - 2.0 * a.bpad);
            maxPx = maxPx > 0 ? Math.min(maxPx, fit) : fit;
        }
        if (b.boxW > 0) maxPx = Math.max(a.sw(a.bf, "0") * 4, limit - 2.0 * a.bpad); // manual width wins over wrap columns
        int lineCap = b.maxLines > 0 ? Math.min(b.maxLines, HARD_LINE_CAP) : HARD_LINE_CAP;
        int off = 0;
        boolean truncated = false;
        outer:
        for (String line : text.split("\n", -1)) {
            if (maxPx <= 0 || a.sw(a.bf, line) <= maxPx) {
                a.lines.add(new VLine(line, off, off + line.length()));
            } else if (b.wrap) {
                int st = 0, n = line.length();
                double[] cw = new double[n];
                for (int i = 0; i < n; i++) cw[i] = a.sw(a.bf, line.substring(i, i + 1));
                double w = 0;
                int lastBreak = -1;
                for (int i = 0; i < n; i++) {
                    if (w + cw[i] > maxPx && i > st) {
                        // prefer a natural break (after space & , ; / ? = - ) ] }) if it isn't too early in the line
                        int cut = (lastBreak > st && (lastBreak - st) * 3 >= (i - st)) ? lastBreak : i;
                        a.lines.add(new VLine(line.substring(st, cut), off + st, off + cut));
                        if (a.lines.size() > lineCap) {
                            truncated = true;
                            break outer;
                        }
                        st = cut;
                        w = 0;
                        for (int j = st; j < i; j++) w += cw[j];
                        lastBreak = -1;
                    }
                    w += cw[i];
                    if (" &,;/?=-)]}|".indexOf(line.charAt(i)) >= 0) lastBreak = i + 1;
                }
                a.lines.add(new VLine(line.substring(st), off + st, off + n));
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
            if (a.lines.size() > lineCap) {
                truncated = true;
                break;
            }
        }
        if (truncated || a.lines.size() > lineCap) {
            while (a.lines.size() > lineCap) a.lines.remove(a.lines.size() - 1);
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
        Border bd = a.b.border != null && !a.b.border.isNone() ? a.b.border : null;
        if (bd == null) {
            drawBody(g, a);
            return;
        }
        Rectangle body = new Rectangle(a.bodyX(), a.bodyY(), a.boxW, a.boxH);
        Border.drawShadow(g, bd, body, a.S);
        java.awt.Shape oldClip = g.getClip();
        java.awt.Shape cs = Border.clipShape(bd, body, a.S);
        if (cs != null) g.clip(cs);
        if (bd.shadow > 0) { // an opaque base, so the shadow does not show through a transparent layer
            g.setColor(a.b.background != null ? a.b.background : a.canvas);
            g.fillRect(body.x, body.y, body.width, body.height);
        }
        drawBody(g, a);
        g.setClip(oldClip);
        Border.drawStroke(g, bd, body, a.S);
    }

    private static void drawBody(Graphics2D g, Laid a) {
        if (a.b.kind == Block.Kind.TERMINAL && !a.lines.isEmpty()) {
            drawTerminal(g, a);
            return;
        }
        if (a.rows != null) {
            drawTable(g, a);
            return;
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
        if (a.rows != null) return tableAnchor(a, an);
        if (a.lines.isEmpty() || an.anchor == null || an.anchor.isEmpty()) return null;
        int s = nth(a.b.shown(), an.anchor, an.occurrence);
        if (s < 0) return null;
        int e = s + an.anchor.length();
        int bx = a.bodyX() + a.bpad, by = a.bodyY() + a.bpad + a.topExtra;
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

    private static void arrow(Graphics2D g, Annotation an, double[] pts, double S) {
        double sx = pts[0], sy = pts[1];
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
