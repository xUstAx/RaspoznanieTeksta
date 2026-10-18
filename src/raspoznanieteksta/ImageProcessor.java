package raspoznanieteksta;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Разбивает изображение на отдельные символы по вертикальным "пустым" столбцам.
     *
     * @param src       изображение холста
     * @param gapPixels сколько пустых столбцов подряд считать границей между символами
     * @return список вырезанных символов слева направо
     */
    public static List<BufferedImage> segmentSymbols(BufferedImage src, int gapPixels) {
        int w = src.getWidth();
        int h = src.getHeight();

        int[] columnFill = new int[w];
        for (int x = 0; x < w; x++) {
            int count = 0;
            for (int y = 0; y < h; y++) {
                int alpha = (src.getRGB(x, y) >>> 24) & 0xff;
                if (alpha > 10) count++;
            }
            columnFill[x] = count;
        }

        List<int[]> ranges = new ArrayList<>();
        int start = -1;
        int emptyRun = 0;

        for (int x = 0; x < w; x++) {
            boolean isEmpty = columnFill[x] == 0;

            if (!isEmpty) {
                if (start == -1) start = x;
                emptyRun = 0;
            } else {
                if (start != -1) {
                    emptyRun++;
                    if (emptyRun >= gapPixels) {
                        ranges.add(new int[]{start, x - emptyRun});
                        start = -1;
                        emptyRun = 0;
                    }
                }
            }
        }
        if (start != -1) {
            int end = w - 1;
            while (end > start && columnFill[end] == 0) end--;
            ranges.add(new int[]{start, end});
        }

        List<BufferedImage> result = new ArrayList<>();
        for (int[] r : ranges) {
            int x1 = r[0], x2 = r[1];
            int width = x2 - x1 + 1;
            if (width < 2) continue;

            BufferedImage frag = src.getSubimage(x1, 0, width, h);
            BufferedImage cropped = cropToContent(frag);
            if (cropped != null) {
                result.add(cropped);
            }
        }
        return result;
    }
}