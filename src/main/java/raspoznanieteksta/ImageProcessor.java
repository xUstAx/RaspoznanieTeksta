package raspoznanieteksta;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
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

        // контент-боксы фрагментов: {x1, x2, y1, y2, mergedFlag}
        List<int[]> boxes = new ArrayList<>();
        for (int[] r : ranges) {
            int x1 = r[0], x2 = r[1];
            if (x2 - x1 + 1 < 2) continue;
            int y1 = -1, y2 = -1;
            for (int x = x1; x <= x2; x++) {
                for (int y = 0; y < h; y++) {
                    if (((src.getRGB(x, y) >>> 24) & 0xff) > 10) {
                        if (y1 < 0 || y < y1) y1 = y;
                        if (y2 < 0 || y > y2) y2 = y;
                    }
                }
            }
            if (y1 < 0) continue;
            boxes.add(new int[]{x1, x2, y1, y2, 0});
        }

        // многоштриховые символы рисуются с отрывом пера (=, +) — склеиваем
        mergeMultiStroke(boxes);

        List<BufferedImage> result = new ArrayList<>();
        for (int[] b : boxes) {
            int x1 = b[0], x2 = b[1], y1 = b[2], y2 = b[3];
            int bw = x2 - x1 + 1, bh = y2 - y1 + 1;
            // склеенные боксы не режем; обычные широкие — как раньше
            if (b[4] == 0 && bh > 0 && (double) bw / bh > 1.7 && bw > 10) {
                splitWide(src, columnFill, x1, x2, h, result);
            } else {
                int px = Math.max(0, x1 - 2), py = Math.max(0, y1 - 2);
                int qx = Math.min(w - 1, x2 + 2), qy = Math.min(h - 1, y2 + 2);
                result.add(src.getSubimage(px, py, qx - px + 1, qy - py + 1));
            }
        }
        return result;
    }

    /** Фрагмент строки: границы контента + вырезанная картинка. */
    public static final class Box {
        public final int x1, y1, x2, y2;
        public final BufferedImage image;
        public boolean used;

        Box(int x1, int y1, int x2, int y2, BufferedImage image) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.image = image;
        }

        public int width() { return x2 - x1 + 1; }
        public int height() { return y2 - y1 + 1; }
        public double aspect() { return height() > 0 ? width() / (double) height() : 0; }
        /** Горизонтальное перекрытие двух боксов (в пикселях). */
        public int overlapX(Box o) {
            return Math.min(x2, o.x2) - Math.max(x1, o.x1) + 1;
        }
    }

    /**
     * Сегментация по связным компонентам чернил — основа 2D-разбора формул:
     * символы, стоящие друг над другом (дробь), остаются РАЗДЕЛЬНЫМИ боксами.
     * Многоштриховые знаки (=, +), нарисованные с отрывом пера, склеиваются,
     * точка над «i» приклеивается к основному штриху.
     */
    public static List<Box> segmentComponents(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        byte[] ink = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((src.getRGB(x, y) >>> 24) & 0xff) > 10) ink[y * w + x] = 1;
            }
        }

        int[] label = new int[w * h];
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        List<int[]> bboxes = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (ink[idx] == 0 || label[idx] != 0) continue;
                label[idx] = bboxes.size() + 1;
                stack.clear();
                stack.push(new int[]{x, y});
                int minX = x, maxX = x, minY = y, maxY = y;
                while (!stack.isEmpty()) {
                    int[] p = stack.pop();
                    int px = p[0], py = p[1];
                    if (px < minX) minX = px;
                    if (px > maxX) maxX = px;
                    if (py < minY) minY = py;
                    if (py > maxY) maxY = py;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = px + dx, ny = py + dy;
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                            int nidx = ny * w + nx;
                            if (ink[nidx] == 1 && label[nidx] == 0) {
                                label[nidx] = bboxes.size() + 1;
                                stack.push(new int[]{nx, ny});
                            }
                        }
                    }
                }
                bboxes.add(new int[]{minX, maxX, minY, maxY, 0});
            }
        }

        bboxes.sort(Comparator.comparingInt(b -> b[0]));
        mergeMultiStroke(bboxes);
        mergeDiacritics(bboxes);

        List<Box> result = new ArrayList<>();
        for (int[] b : bboxes) {
            int px = Math.max(0, b[0] - 2), py = Math.max(0, b[2] - 2);
            int qx = Math.min(w - 1, b[1] + 2), qy = Math.min(h - 1, b[3] + 2);
            result.add(new Box(b[0], b[2], b[1], b[3],
                    src.getSubimage(px, py, qx - px + 1, qy - py + 1)));
        }
        return result;
    }

    /**
     * Приклейка диакритики: маленький бокс (точка над «i») прямо над
     * более крупным, по центру — часть того же символа.
     */
    private static void mergeDiacritics(List<int[]> boxes) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < boxes.size(); i++) {
                int[] small = boxes.get(i);
                int ws = small[1] - small[0] + 1, hs = small[3] - small[2] + 1;
                for (int j = 0; j < boxes.size(); j++) {
                    if (i == j) continue;
                    int[] tall = boxes.get(j);
                    int ht = tall[3] - tall[2] + 1;
                    if (ht <= 0) continue;
                    // диакритика — крошечный бокс размером с толщину пера
                    // (точка над «i»): абсолютный + относительный лимит размера
                    if (ws > 16 || hs > 16 || hs > 0.4 * ht) continue;
                    // мелкий целиком НАД крупным, вплотную (зазор до 25% высоты)
                    if (small[3] > tall[2]) continue;
                    int vGap = tall[2] - small[3];
                    if (vGap > 0.35 * ht) continue;
                    // центр мелкого — рядом с крупным (штрих «i» узкий и наклонный)
                    int center = (small[0] + small[1]) / 2;
                    if (center < tall[0] - 0.5 * ht || center > tall[1] + 0.5 * ht) continue;
                    mergeTwo(boxes, i, j);
                    changed = true;
                    break;
                }
                if (changed) break;
            }
        }
    }

    /**
     * Склейка многоштриховых символов, нарисованных с отрывом пера:
     * 1) «=»: две плоские полосы друг над другом;
     * 2) «+»: вертикальный штрих, пронизывающий горизонтальную полосу.
     */
    private static void mergeMultiStroke(List<int[]> boxes) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < boxes.size() - 1; i++) {
                int[] a = boxes.get(i), b = boxes.get(i + 1);
                int wa = a[1] - a[0] + 1, ha = a[3] - a[2] + 1;
                int wb = b[1] - b[0] + 1, hb = b[3] - b[2] + 1;
                int ovX = Math.min(a[1], b[1]) - Math.max(a[0], b[0]) + 1;
                int ovY = Math.min(a[3], b[3]) - Math.max(a[2], b[2]) + 1;
                if (wa < 3 || wb < 3 || ovX <= 0) continue;

                // 1) «=»: обе полосы плоские, одна над другой (нахлёст до 35% допустим)
                if ((double) wa / ha >= 1.3 && (double) wb / hb >= 1.3
                        && ovY <= 0.35 * Math.min(ha, hb)) {
                    int vGap = Math.max(a[2], b[2]) - Math.min(a[3], b[3]);
                    int nw = Math.max(a[1], b[1]) - Math.min(a[0], b[0]) + 1;
                    int nh = Math.max(a[3], b[3]) - Math.min(a[2], b[2]) + 1;
                    if (vGap <= 2.5 * Math.max(ha, hb) && (double) nw / nh <= 3.0) {
                        mergeAt(boxes, i);
                        changed = true;
                        break;
                    }
                }

                // 2) «+»: плоская полоса + узкий вертикальный штрих сквозь неё.
                // Примыкание допускается только вплотную (0.6 толщины полосы),
                // чтобы не склеить числитель дроби с чертой.
                boolean aFlat = (double) wa / ha >= 1.8;
                boolean bFlat = (double) wb / hb >= 1.8;
                boolean aVert = (double) wa / ha <= 0.8;
                boolean bVert = (double) wb / hb <= 0.8;
                if ((aFlat && bVert) || (bFlat && aVert)) {
                    int[] flat = aFlat ? a : b;
                    int[] vert = aFlat ? b : a;
                    int hf = flat[3] - flat[2] + 1;
                    int hv = vert[3] - vert[2] + 1;
                    int wv = vert[1] - vert[0] + 1;
                    boolean aligned = ovX >= 0.6 * Math.min(wa, wb);
                    boolean narrow = wv <= 0.3 * (flat[1] - flat[0] + 1);
                    boolean crossing = ovY > 0 && hv >= 1.2 * hf;
                    boolean abutTop = ovY <= 0 && (flat[2] - vert[3]) <= 0.6 * hf;
                    boolean abutBottom = ovY <= 0 && (vert[2] - flat[3]) <= 0.6 * hf;
                    if (aligned && narrow && (crossing || abutTop || abutBottom)) {
                        mergeAt(boxes, i);
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    /** Объединяет соседние боксы i и i+1, помечая результат как склеенный. */
    private static void mergeAt(List<int[]> boxes, int i) {
        mergeTwo(boxes, i, i + 1);
    }

    /** Объединяет два бокса в один (объединённая рамка, флаг «склеен»). */
    private static void mergeTwo(List<int[]> boxes, int i, int j) {
        int[] a = boxes.get(i), b = boxes.get(j);
        int[] u = new int[]{
                Math.min(a[0], b[0]), Math.max(a[1], b[1]),
                Math.min(a[2], b[2]), Math.max(a[3], b[3]), 1};
        int keep = Math.min(i, j), drop = Math.max(i, j);
        boxes.set(keep, u);
        boxes.remove(drop);
    }

    /**
     * Добавляет фрагмент строки в результат; если фрагмент непропорционально
     * широкий (слипшиеся символы), режет его по самому «тонкому» столбцу
     * в средней части и обрабатывает половины рекурсивно.
     */
    private static void splitWide(BufferedImage src, int[] columnFill,
                                  int x1, int x2, int canvasH, List<BufferedImage> out) {
        BufferedImage frag = src.getSubimage(x1, 0, x2 - x1 + 1, canvasH);
        BufferedImage cropped = cropToContent(frag);
        if (cropped == null) return;

        double aspect = cropped.getWidth() / (double) cropped.getHeight();
        if (aspect <= 1.7 || (x2 - x1) < 10) {
            out.add(cropped);
            return;
        }

        // ищем самый тонкий столбец в средней трети фрагмента
        int lo = x1 + (x2 - x1) / 3;
        int hi = x2 - (x2 - x1) / 3;
        int best = -1, bestFill = Integer.MAX_VALUE;
        for (int x = lo; x <= hi; x++) {
            if (columnFill[x] < bestFill) {
                bestFill = columnFill[x];
                best = x;
            }
        }
        if (best < 0 || best <= x1 || best >= x2) {
            out.add(cropped);
            return;
        }

        splitWide(src, columnFill, x1, best, canvasH, out);
        splitWide(src, columnFill, best + 1, x2, canvasH, out);
    }
}