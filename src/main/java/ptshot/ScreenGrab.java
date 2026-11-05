package ptshot;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.Timer;

/** Delayed full-screen grab (all monitors, HiDPI aware) - lets the user switch to Burp's Render tab first. */
final class ScreenGrab {
    private ScreenGrab() {}

    static void grab(int seconds, BiConsumer<BufferedImage, Double> done, Consumer<String> error) {
        JWindow w = new JWindow();
        JLabel l = new JLabel();
        l.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        l.setOpaque(true);
        l.setBackground(new Color(30, 30, 30));
        l.setForeground(Color.WHITE);
        w.getContentPane().add(l);
        w.setAlwaysOnTop(true);
        final int[] left = {Math.max(1, seconds)};
        l.setText("Capturing screen in " + left[0] + " s - switch to the view you want");
        w.pack();
        Dimension s = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        w.setLocation(s.width - w.getWidth() - 20, 20);
        w.setVisible(true);
        Timer t = new Timer(1000, null);
        t.addActionListener(e -> {
            left[0]--;
            if (left[0] > 0) {
                l.setText("Capturing screen in " + left[0] + " s - switch to the view you want");
                return;
            }
            t.stop();
            w.dispose(); // make sure the countdown isn't in the picture
            Timer shot = new Timer(350, x -> {
                try {
                    Rectangle all = new Rectangle();
                    for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
                        all = all.union(d.getDefaultConfiguration().getBounds());
                    Robot r = new Robot();
                    MultiResolutionImage mr = r.createMultiResolutionScreenCapture(all);
                    List<Image> vs = mr.getResolutionVariants();
                    Image best = vs.get(vs.size() - 1);
                    int bw = best.getWidth(null), bh = best.getHeight(null);
                    BufferedImage out = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = out.createGraphics();
                    g.drawImage(best, 0, 0, null);
                    g.dispose();
                    done.accept(out, bw / (double) all.width);
                } catch (Throwable ex) {
                    error.accept("Screen capture failed: " + ex + " (on macOS allow screen recording for Burp in System Settings)");
                }
            });
            shot.setRepeats(false);
            shot.start();
        });
        t.start();
    }
}
