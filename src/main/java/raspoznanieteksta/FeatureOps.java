package raspoznanieteksta;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Нормализация вектора признаков 20x20: сглаживание 3x3, дескью (выравнивание
 * наклона) и центрирование по центру масс. Применяется ОДИНАКОВО к образцам базы
 * и к распознаваемым рисункам, поэтому старая база остаётся совместимой: она
 * приводится к нормализованному виду при загрузке.
 */
public final class FeatureOps {

    private FeatureOps() {}

    public static double[] smoothAndCenter(double[] f) {
        return centerByMass(smooth3x3(f));
    }

    /** Выравнивание наклона + центрирование (для уже сглаженных векторов v2). */
    public static double[] deskewAndCenter(double[] f) {
        return centerByMass(deskew(f));
    }

    /**
     * Дескью (экспериментально, в конвейер НЕ включён — на шрифтовой базе
     * снижает точность): оценивает наклон по моментам и «выпрямляет» символ.
     */
    public static double[] deskew(double[] f) {
        int n = ImageProcessor.FEATURE_SIZE;
        double cx = 0, cy = 0, sum = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double v = f[y * n + x];
                cx += v * x;
                cy += v * y;
                sum += v;
            }
        }
        if (sum < 1e-9) return f;
        cx /= sum;
        cy /= sum;

        double mu11 = 0, mu02 = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double v = f[y * n + x];
                mu11 += v * (x - cx) * (y - cy);
                mu02 += v * (y - cy) * (y - cy);
            }
        }
        if (mu02 < 1e-9) return f;

        double skew = Math.max(-0.6, Math.min(0.6, mu11 / mu02));
        if (Math.abs(skew) < 0.05) return f; // почти вертикальный — не трогаем

        // x' = x - skew*(y - cy): через сдвиг строк с субпиксельной интерполяцией
        BufferedImage img = toImage(f);
        BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.shear(-skew, 0);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return fromImage(out);
    }

    private static BufferedImage toImage(double[] f) {
        int n = ImageProcessor.FEATURE_SIZE;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < f.length; i++) {
            int a = (int) Math.round(Math.max(0, Math.min(1, f[i])) * 255);
            img.setRGB(i % n, i / n, a << 24);
        }
        return img;
    }

    private static double[] fromImage(BufferedImage img) {
        int n = ImageProcessor.FEATURE_SIZE;
        double[] res = new double[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                res[y * n + x] = ((img.getRGB(x, y) >>> 24) & 0xff) / 255.0;
            }
        }
        return res;
    }

    /** Сглаживание ядром 3x3 (среднее по окрестности) — снижает шум пикселей. */
    public static double[] smooth3x3(double[] f) {
        int n = ImageProcessor.FEATURE_SIZE;
        double[] b = new double[f.length];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double s = 0;
                int cnt = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < n && ny >= 0 && ny < n) {
                            s += f[ny * n + nx];
                            cnt++;
                        }
                    }
                }
                b[y * n + x] = s / cnt;
            }
        }
        return b;
    }

    /** Сдвигает изображение так, чтобы центр масс оказался в центре картинки. */
    public static double[] centerByMass(double[] f) {
        int n = ImageProcessor.FEATURE_SIZE;
        double sx = 0, sy = 0, sum = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double v = f[y * n + x];
                sx += v * x;
                sy += v * y;
                sum += v;
            }
        }
        if (sum < 1e-9) return f;

        int dx = (int) Math.max(-4, Math.min(4, Math.round(n / 2.0 - sx / sum)));
        int dy = (int) Math.max(-4, Math.min(4, Math.round(n / 2.0 - sy / sum)));
        return shift(f, dx, dy);
    }

    /** Целочисленный сдвиг картинки 20x20 в пространстве признаков. */
    public static double[] shift(double[] f, int dx, int dy) {
        int n = ImageProcessor.FEATURE_SIZE;
        double[] r = new double[f.length];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int sx = x - dx, sy = y - dy;
                r[y * n + x] = (sx >= 0 && sx < n && sy >= 0 && sy < n) ? f[sy * n + sx] : 0;
            }
        }
        return r;
    }
}
