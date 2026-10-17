package raspoznanieteksta;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public class ImageProcessor {

    public static final int FEATURE_SIZE = 20;

    public static BufferedImage cropToContent(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int alpha = (rgb >>> 24) & 0xff;
                if (alpha > 10) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < 0) return null;

        int pad = 4;
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(w - 1, maxX + pad);
        maxY = Math.min(h - 1, maxY + pad);

        return src.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    public static BufferedImage resizeTo(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        return out;
    }

    public static double[] extractFeatures(BufferedImage src) {
        BufferedImage cropped = cropToContent(src);
        if (cropped == null) return null;

        BufferedImage normalized = resizeTo(cropped, FEATURE_SIZE);
        double[] features = new double[FEATURE_SIZE * FEATURE_SIZE];

        for (int y = 0; y < FEATURE_SIZE; y++) {
            for (int x = 0; x < FEATURE_SIZE; x++) {
                int rgb = normalized.getRGB(x, y);
                int alpha = (rgb >>> 24) & 0xff;
                features[y * FEATURE_SIZE + x] = alpha / 255.0;
            }
        }
        return features;
    }
}