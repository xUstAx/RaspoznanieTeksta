package raspoznanieteksta;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Генератор синтетической базы образцов: глифы цифр и букв (RU/EN) рисуются
 * разными шрифтами (включая рукописные Ink Free, Segoe Script, Comic Sans MS)
 * со случайным поворотом, наклоном, жирностью и микросмещениями, после чего
 * прогоняются через тот же ImageProcessor.extractFeatures, что и рисунки
 * пользователя. Пересоздаёт data/samples.dat (старый файл НЕ трогает —
 * позаботьтесь о резервной копии заранее).
 *
 * Запуск: mvn compile exec:java -Dexec.mainClass=raspoznanieteksta.SampleGenerator
 */
public class SampleGenerator {

    public static void main(String[] args) throws Exception {
        String classes = "0123456789"
                + "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
                + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "abcdefghijklmnopqrstuvwxyz"
                + "+-*/=().^,<>"
                + "√π|≤≥≠";

        // рукописные шрифты идут первыми — их варианты чаще
        String[] wanted = {"Ink Free", "Segoe Script", "Segoe Print", "Comic Sans MS",
                "Arial", "Times New Roman", "Calibri", "Verdana", "Georgia", "Courier New"};
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        List<String> fonts = new ArrayList<>();
        for (String f : wanted) {
            if (available.contains(f)) fonts.add(f);
        }
        if (fonts.isEmpty()) fonts.add("Arial");
        System.out.println("Шрифты: " + fonts);

        RecognitionEngine engine = new RecognitionEngine();
        Random rnd = new Random(42);
        // 24 варианта на класс — чем больше, тем лучше обобщение
        int variants = 24;
        int count = 0;

        for (int ci = 0; ci < classes.length(); ci++) {
            String ch = String.valueOf(classes.charAt(ci));

            for (int v = 0; v < variants; v++) {
                String family = fonts.get(v % fonts.size());
                int style = switch ((v / 2) % 3) {
                    case 0 -> Font.PLAIN;
                    case 1 -> Font.BOLD;
                    default -> Font.ITALIC;
                };
                int size = 90 + rnd.nextInt(40);
                Font font = new Font(family, style, size);
                // спец-символы есть не во всех рукописных шрифтах — подмена
                if (!font.canDisplay(ch.charAt(0))) {
                    font = new Font("Arial", style, size);
                }

                BufferedImage img = new BufferedImage(260, 260, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Color.BLACK);
                g.setFont(font);
                FontMetrics fm = g.getFontMetrics();

                int cx = 130, cy = 130;
                int gx = cx - fm.stringWidth(ch) / 2;
                int gy = cy + (fm.getAscent() - fm.getDescent()) / 2;

                // случайный поворот: знаки ±6° (рисуются более вертикально,
                // иначе их кластеры размазываются и сливаются с буквами),
                // буквы ±12°; наклон ±0.14
                AffineTransform old = g.getTransform();
                boolean isSign = "+-*/=().^,<>√π|≤≥≠".contains(ch);
                int rot = isSign ? rnd.nextInt(13) - 6 : rnd.nextInt(25) - 12;
                g.rotate(Math.toRadians(rot), cx, cy);
                g.shear((rnd.nextDouble() - 0.5) * 0.28, 0);

                if (ch.equals("=") || ch.equals("+")) {
                    // многоштриховые символы рисуем как РЕАЛЬНЫЕ два штриха
                    // со случайным зазором/толщиной — как рисует человек
                    drawStrokeSymbol(g, "-", ch.equals("=") ? null : "|",
                            cx, cy, size, rnd);
                } else if (ch.equals("√")) {
                    // радикал: галочка + верхняя черта справа (как настоящий √) —
                    // без черты он неотличим от буквы v
                    drawRadical(g, font, ch, cx, cy, size, rnd);
                } else {
                    // вариативная «жирность» штриха: 1-3 прохода с микросмещениями
                    int passes = 1 + rnd.nextInt(3);
                    for (int p = 0; p < passes; p++) {
                        g.drawString(ch, gx + rnd.nextInt(3) - 1, gy + rnd.nextInt(3) - 1);
                    }
                }
                g.setTransform(old);
                g.dispose();

                double[] features = ImageProcessor.extractFeatures(img);
                if (features == null) continue;
                engine.addSample(ch, features);
                count++;
            }
        }

        File out = new File("data/samples.dat");
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        engine.save(out);

        System.out.println("Сгенерировано образцов: " + count + " (" + classes.length() + " классов)");
        System.out.println("Файл: " + out.getAbsolutePath());
    }

    /**
     * Радикал: галочка «√» + верхняя черта, продлевающая её вправо —
     * так корень отличим от буквы v.
     */
    private static void drawRadical(Graphics2D g, Font font, String ch,
                                    int cx, int cy, int size, Random rnd) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        FontMetrics fm = g.getFontMetrics(font);
        int gw = fm.stringWidth(ch);
        int gy = cy + (fm.getAscent() - fm.getDescent()) / 2;
        int gx = cx - gw / 2;
        g.setFont(font);
        g.drawString(ch, gx, gy);
        // черта от вершины длинного штриха вправо — длиной до половины-всего
        // размера символа (корень может покрывать дробь целиком)
        float stroke = 4f + rnd.nextInt(4);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int barY = gy - (int) (0.62 * size);
        int barX1 = gx + gw / 2;
        int barX2 = gx + gw / 2 + (int) (size * (0.5 + rnd.nextDouble() * 0.5));
        g.drawLine(barX1, barY, barX2, barY);
    }

    /**
     * Рисует «=» или «+» двумя отдельными штрихами со случайным зазором
     * и толщиной — имитация рисования человеком (отрыв пера).
     */
    private static void drawStrokeSymbol(Graphics2D g, String horizontal, String vertical,
                                         int cx, int cy, int size, Random rnd) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        // толщина штриха 4..9
        float stroke = 4f + rnd.nextInt(6);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int half = size / 3; // половина длины штриха

        int x1 = cx - half, x2 = cx + half;

        if (vertical == null) {
            // «=»: две полосы со случайным зазором (30..70% высоты символа)
            int gap = (int) (size * (0.3 + rnd.nextDouble() * 0.4));
            g.drawLine(x1, cy - gap / 2, x2, cy - gap / 2);
            g.drawLine(x1, cy + gap / 2, x2, cy + gap / 2);
        } else {
            // «+»: горизонтальный штрих + вертикальный, случайно смещённый
            g.drawLine(x1, cy, x2, cy);
            int vHalf = size / 3;
            g.drawLine(cx + rnd.nextInt(7) - 3, cy - vHalf,
                    cx + rnd.nextInt(7) - 3, cy + vHalf);
        }
    }
}
