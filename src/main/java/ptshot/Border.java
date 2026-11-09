package ptshot;

import java.awt.Color;

/** Border styling for a layer or for the whole capture: colour, width, corner curvature and a drop shadow. Values are in 1x pixels. */
final class Border {
    static final int MAX_WIDTH = 40, MAX_RADIUS = 120, MAX_SHADOW = 100;

    Color color = Color.BLACK;
    int width = 2;
    int radius;
    int shadow; // 0..100 (percent): strength and size of the drop shadow

    Border copy() {
        Border b = new Border();
        b.color = color;
        b.width = width;
        b.radius = radius;
        b.shadow = shadow;
        return b;
    }

    /** True if drawing this would change nothing. */
    boolean isNone() {
        return width <= 0 && shadow <= 0;
    }

    void clamp() {
        if (color == null) color = Color.BLACK;
        width = Math.max(0, Math.min(MAX_WIDTH, width));
        radius = Math.max(0, Math.min(MAX_RADIUS, radius));
        shadow = Math.max(0, Math.min(MAX_SHADOW, shadow));
    }

    /** Shadow blur radius and offset in device pixels. */
    int blurPx(double S) {
        return (int) Math.round(12 * S * shadow / 100.0);
    }

    int offsetPx(double S) {
        return (int) Math.round(6 * S * shadow / 100.0);
    }

    /** Free space needed around the box so the stroke and shadow are not cut off. */
    int reachPx(double S) {
        return (shadow > 0 ? blurPx(S) + offsetPx(S) : 0) + (int) Math.ceil(width * S);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Border)) return false;
        Border b = (Border) o;
        return b.width == width && b.radius == radius && b.shadow == shadow && b.color.equals(color);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(color, width, radius, shadow);
    }

    static boolean same(Border a, Border b) {
        return a == null ? b == null : a.equals(b);
    }

    // ------------------------------------------------------------------ drawing

    /** Soft drop shadow of a rounded rectangle (drawn before the content, so the content covers its middle). */
    static void drawShadow(java.awt.Graphics2D g, Border b, java.awt.Rectangle r, double S) {
        if (b == null || b.shadow <= 0) return;
        int blur = Math.max(1, b.blurPx(S)), off = b.offsetPx(S);
        int arc = (int) Math.round(b.radius * S * 2);
        java.awt.Composite old = g.getComposite();
        float each = 0.55f * Math.min(1f, 0.35f + b.shadow / 100f) / blur * 2.2f;
        g.setColor(Color.BLACK);
        for (int k = blur; k >= 0; k--) {
            g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, Math.min(1f, each)));
            g.fillRoundRect(r.x + off - k, r.y + off - k, r.width + 2 * k, r.height + 2 * k, arc + 2 * k, arc + 2 * k);
        }
        g.setComposite(old);
    }

    /** The stroke, drawn just outside {@code r} (so it never covers the content). */
    static void drawStroke(java.awt.Graphics2D g, Border b, java.awt.Rectangle r, double S) {
        if (b == null || b.width <= 0) return;
        float w = (float) (b.width * S);
        int arc = (int) Math.round(b.radius * S * 2);
        g.setColor(b.color);
        g.setStroke(new java.awt.BasicStroke(w, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER));
        double h = w / 2.0;
        if (arc > 0) g.draw(new java.awt.geom.RoundRectangle2D.Double(r.x - h, r.y - h, r.width + w, r.height + w, arc + w, arc + w));
        else g.draw(new java.awt.geom.Rectangle2D.Double(r.x - h, r.y - h, r.width + w, r.height + w));
    }

    /** The shape content is clipped to when the corners are curved, or null for square corners. */
    static java.awt.Shape clipShape(Border b, java.awt.Rectangle r, double S) {
        if (b == null || b.radius <= 0) return null;
        double arc = b.radius * S * 2;
        return new java.awt.geom.RoundRectangle2D.Double(r.x, r.y, r.width, r.height, arc, arc);
    }
}
