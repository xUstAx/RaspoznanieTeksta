package raspoznanieteksta;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Function;

/**
 * Двумерный разбор формулы «как в Word»:
 * - дробная черта (широкая плоская полоса) + содержимое над и под ней → «(числ)/(знам)»;
 * - маленький бокс над базой → степень «base^(exp)»;
 * - маленький бокс под базой → индекс «base_(sub)»;
 * - остальное — линейная последовательность символов.
 *
 * Результат — линейная строка, которую MathMlBuilder превращает в MathML
 * для редактора уравнений Word.
 */
public final class FormulaLayout {

    private FormulaLayout() {}

    /** Ответ движков на один фрагмент. */
    public static final class FragmentResult {
        public final String label;
        public final String nn;
        public final String knn;

        public FragmentResult(String label, String nn, String knn) {
            this.label = label;
            this.nn = nn;
            this.knn = knn;
        }
    }

    public static final class ParseResult {
        public final String linear;   // линейная строка со скобками-структурой
        public final String nnLine;   // ответы нейросети в порядке чтения
        public final String knnLine;  // ответы kNN в порядке чтения
        public final boolean structured; // была ли 2D-структура (дроби/степени/индексы)

        ParseResult(String linear, String nnLine, String knnLine, boolean structured) {
            this.linear = linear;
            this.nnLine = nnLine;
            this.knnLine = knnLine;
            this.structured = structured;
        }
    }

    /** Порог «дробной черты»: отношение ширины к высоте. */
    private static final double BAR_ASPECT = 2.2;
    /** Порог «маленького» бокса (степень/индекс) относительно базы. */
    private static final double SMALL_RATIO = 0.7;

    public static ParseResult parse(BufferedImage canvas,
                                    Function<BufferedImage, FragmentResult> rec) {
        List<ImageProcessor.Box> boxes = ImageProcessor.segmentComponents(canvas);
        boxes.sort(Comparator.comparingInt(b -> b.x1));
        Map<ImageProcessor.Box, FragmentResult> cache = new HashMap<>();
        Region r = parseRegion(boxes, cache, rec);
        return new ParseResult(r.linear, r.nn, r.knn, r.structured);
    }

    // ============ разбор области ============

    private static final class Region {
        String linear = "";
        String nn = "";
        String knn = "";
        boolean structured = false;

        void append(String lin, String nnS, String knnS) {
            linear += lin;
            nn += nnS;
            knn += knnS;
        }
    }

    private static Region empty() {
        return new Region();
    }

    /** Элемент области: распознанный символ или готовая 2D-конструкция. */
    private static final class Item {
        final int x;
        final String linear;
        final String nn;
        final String knn;
        final ImageProcessor.Box box; // null для готовых конструкций
        final boolean structured;

        Item(int x, String linear, String nn, String knn,
             ImageProcessor.Box box, boolean structured) {
            this.x = x;
            this.linear = linear;
            this.nn = nn;
            this.knn = knn;
            this.box = box;
            this.structured = structured;
        }
    }

    private static Region parseRegion(List<ImageProcessor.Box> boxes,
                                      Map<ImageProcessor.Box, FragmentResult> cache,
                                      Function<BufferedImage, FragmentResult> rec) {
        Region out = new Region();
        if (boxes.isEmpty()) return out;

        // --- 1) дробные черты и радикалы: готовые конструкции с x-позицией ---
        List<ImageProcessor.Box> bars = new ArrayList<>();
        for (ImageProcessor.Box b : boxes) {
            if (!b.used && b.aspect() >= BAR_ASPECT) bars.add(b);
        }
        bars.sort(Comparator.comparingInt(b -> b.x1));

        List<Item> items = new ArrayList<>();
        for (ImageProcessor.Box bar : bars) {
            if (bar.used) continue;
            List<ImageProcessor.Box> num = new ArrayList<>();
            List<ImageProcessor.Box> den = new ArrayList<>();
            // в клетки попадают и вложенные дробные черты — рекурсивный разбор
            // обработает их как дроби; исключена только сама черта
            for (ImageProcessor.Box b : boxes) {
                if (b == bar || b.used) continue;
                int ov = bar.overlapX(b);
                if (ov < 0.3 * b.width()) continue;
                if (b.y2 <= bar.y1 + 0.2 * bar.height()) num.add(b);
                else if (b.y1 >= bar.y2 - 0.2 * bar.height()) den.add(b);
            }

            if (!num.isEmpty() && !den.isEmpty()) {
                // дробь
                Region n = parseRegion(num, cache, rec);
                Region d = parseRegion(den, cache, rec);
                bar.used = true;
                for (ImageProcessor.Box b : num) b.used = true;
                for (ImageProcessor.Box b : den) b.used = true;
                items.add(new Item(bar.x1, "(" + n.linear + ")/(" + d.linear + ")",
                        n.nn + d.nn, n.knn + d.knn, null, true));
            } else if (num.isEmpty() && !den.isEmpty()) {
                // только содержимое снизу — возможно, это верхняя черта радикала:
                // слева вплотную должна быть высокая «галочка» корня (метка не
                // важна — √ и v по форме совпадают, решает структура)
                ImageProcessor.Box rootBox = nearestLeft(
                        b -> b.height() >= 2.0 * bar.height(), bar, boxes);
                if (rootBox != null) {
                    Region d = parseRegion(den, cache, rec);
                    bar.used = true;
                    rootBox.used = true;
                    for (ImageProcessor.Box b : den) b.used = true;
                    items.add(new Item(rootBox.x1, "√(" + d.linear + ")",
                            d.nn, d.knn, null, true));
                }
                // иначе это просто минус — останется линейным символом
            }
        }

        // --- 2) оставшиеся боксы — линейные символы ---
        for (ImageProcessor.Box b : boxes) {
            if (!b.used) items.add(new Item(b.x1, null, null, null, b, false));
        }
        items.sort(Comparator.comparingInt(it -> it.x));

        // --- 3) степени/индексы: цепочки мелких боксов справа от базы ---
        boolean[] emitted = new boolean[items.size()];
        int i = 0;
        while (i < items.size()) {
            if (emitted[i]) { i++; continue; }
            Item it = items.get(i);
            if (it.box == null) { // готовая конструкция (дробь/радикал)
                out.append(it.linear, it.nn, it.knn);
                out.structured |= it.structured;
                emitted[i] = true;
                i++;
                continue;
            }

            // цепочка мелких боксов справа от базы
            List<ImageProcessor.Box> sup = new ArrayList<>();
            List<ImageProcessor.Box> sub = new ArrayList<>();
            List<Integer> attachedIdx = new ArrayList<>();
            int j = i + 1;
            while (j < items.size()) {
                Item cand = items.get(j);
                if (emitted[j] || cand.box == null) { j++; continue; }
                ImageProcessor.Box cb = cand.box;
                if (cb.height() > SMALL_RATIO * it.box.height()) break;
                if (cb.x1 < it.box.x1 - 0.1 * it.box.width()) break;
                if (cb.x1 - it.box.x2 > 1.2 * it.box.width()) break;
                if (cb.y2 <= it.box.y1 + 0.55 * it.box.height()) {
                    sup.add(cb);
                    attachedIdx.add(j);
                } else if (cb.y1 >= it.box.y2 - 0.55 * it.box.height()) {
                    sub.add(cb);
                    attachedIdx.add(j);
                } else {
                    break;
                }
                j++;
            }

            Region baseR = recognizeBox(it.box, cache, rec);
            out.append(baseR.linear, baseR.nn, baseR.knn);
            emitted[i] = true;

            // радикал «√»: подкоренное выражение — СЛЕДУЮЩИЙ элемент
            // (дробь, скобочная группа или символ)
            if (baseR.linear.equals("√") && i + 1 < items.size() && !emitted[i + 1]) {
                Item rad = items.get(i + 1);
                emitted[i + 1] = true;
                out.structured = true;
                out.append("√(" + rad.linear + ")", rad.nn, rad.knn);
                i += 2;
                continue;
            }

            if (!sup.isEmpty()) {
                Region e = parseRegion(sup, cache, rec);
                // степень/индекс — буквы или цифры; одиночный знак (точка,
                // запятая, оператор) это самостоятельный символ, не показатель
                if (isNotExponent(e.linear)) {
                    out.append(e.linear, e.nn, e.knn);
                } else {
                    out.structured = true;
                    out.append("^(" + e.linear + ")", e.nn, e.knn);
                }
            }
            if (!sub.isEmpty()) {
                Region s = parseRegion(sub, cache, rec);
                if (isNotExponent(s.linear)) {
                    out.append(s.linear, s.nn, s.knn);
                } else {
                    out.structured = true;
                    out.append("_(" + s.linear + ")", s.nn, s.knn);
                }
            }
            for (int idx : attachedIdx) emitted[idx] = true;
            i++;
        }

        return out;
    }

    /** Степень/индекс должен быть буквой или цифрой (возможно, в скобках). */
    private static boolean isNotExponent(String linear) {
        if (linear.isEmpty()) return true;
        String inner = linear;
        if (inner.startsWith("(") && inner.endsWith(")") && inner.length() >= 2) {
            inner = inner.substring(1, inner.length() - 1);
        }
        if (inner.length() == 1) return !Character.isLetterOrDigit(inner.charAt(0));
        // "x+1", "-1", "2,5" — содержательные выражения, оставляем степенью
        return inner.matches(".*[0-9a-z].*") ? false : true;
    }

    /** Ближайший бокс слева от черты, удовлетворяющий условию. */
    private static ImageProcessor.Box nearestLeft(
            java.util.function.Predicate<ImageProcessor.Box> test,
            ImageProcessor.Box bar, List<ImageProcessor.Box> boxes) {
        ImageProcessor.Box best = null;
        for (ImageProcessor.Box b : boxes) {
            if (b == bar || b.used || b.aspect() >= BAR_ASPECT) continue;
            // бокс начинается у уровня черты (вершина галочки ≈ черта):
            // допуск — половина высоты самого бокса
            if (b.y1 > bar.y2) continue;
            if (b.y1 < bar.y1 - 0.5 * b.height()) continue;
            int gap = bar.x1 - b.x2;
            if (gap < 0 || gap > Math.max(15, 3.0 * Math.max(1, bar.height()))) continue;
            if (best == null || b.x2 > best.x2) best = b;
        }
        return best != null && test.test(best) ? best : null;
    }

    private static Region recognizeBox(ImageProcessor.Box box,
                                       Map<ImageProcessor.Box, FragmentResult> cache,
                                       Function<BufferedImage, FragmentResult> rec) {
        FragmentResult fr = cache.computeIfAbsent(box, b -> rec.apply(b.image));
        Region r = new Region();
        r.append(fr.label, fr.nn, fr.knn);
        return r;
    }
}
