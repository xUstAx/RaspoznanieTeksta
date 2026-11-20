package raspoznanieteksta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Преобразует распознанную строку формулы в MathML — редактор уравнений
 * Word (Вставка → Уравнение → Ctrl+V) превращает его в нативную формулу.
 *
 * Поддерживает: ^ (степень), / (дробь), скобки (в т.ч. вложенные),
 * цифры и десятичные запятые, латинские буквы, функции
 * sin/cos/tg/ctg/log/ln/lim/sqrt, знаки + - * / = . , < >.
 */
public final class MathMlBuilder {

    private static final Set<String> FUNCTIONS =
            Set.of("sin", "cos", "tg", "ctg", "log", "ln", "lim", "sqrt", "max", "min");

    private MathMlBuilder() {}

    public static String toMathMl(String s) {
        List<String> tokens = parseTokens(s, 0, s.length());
        tokens = foldPrefix(tokens, "√", "<msqrt>", "</msqrt>");
        tokens = foldBinary(tokens, "^", "<msup>", "</msup>");
        tokens = foldBinary(tokens, "_", "<msub>", "</msub>");
        tokens = foldBinary(tokens, "/", "<mfrac>", "</mfrac>");
        StringBuilder sb = new StringBuilder("<math xmlns=\"http://www.w3.org/1998/Math/MathML\">");
        for (String t : tokens) sb.append(t);
        return sb.append("</math>").toString();
    }

    /** Разбирает участок строки в список MathML-токенов. */
    private static List<String> parseTokens(String s, int from, int to) {
        List<String> out = new ArrayList<>();
        int i = from;
        while (i < to) {
            char c = s.charAt(i);

            if (c == '(') {
                int depth = 1, j = i + 1;
                while (j < to && depth > 0) {
                    char d = s.charAt(j);
                    if (d == '(') depth++;
                    else if (d == ')') depth--;
                    j++;
                }
                String inner = toMathMlInner(s, i + 1, depth == 0 ? j - 1 : to);
                out.add("<mrow>" + inner + "</mrow>");
                i = j;
            } else if (c == ')') {
                i++; // лишняя закрывающая — пропускаем
            } else if (Character.isLetter(c)) {
                // читаем буквенный ряд: sin → функция, остальное — по буквам
                int j = i;
                while (j < to && Character.isLetter(s.charAt(j))) j++;
                String run = s.substring(i, j);
                if (FUNCTIONS.contains(run)) {
                    out.add("<mi>" + run + "</mi>");
                } else {
                    for (int k = i; k < j; k++) {
                        out.add("<mi>" + escape(s.charAt(k)) + "</mi>");
                    }
                }
                i = j;
            } else if (Character.isDigit(c)) {
                // число с десятичной запятой/точкой
                int j = i;
                while (j < to && Character.isDigit(s.charAt(j))) j++;
                if (j < to - 1 && (s.charAt(j) == ',' || s.charAt(j) == '.')
                        && Character.isDigit(s.charAt(j + 1))) {
                    j++;
                    while (j < to && Character.isDigit(s.charAt(j))) j++;
                }
                out.add("<mn>" + escape(s.substring(i, j)) + "</mn>");
                i = j;
            } else {
                out.add(operatorToken(c));
                i++;
            }
        }
        return out;
    }

    private static List<String> parseTokensFull(String s, int from, int to) {
        List<String> tokens = parseTokens(s, from, to);
        tokens = foldBinary(tokens, "^", "<msup>", "</msup>");
        tokens = foldBinary(tokens, "/", "<mfrac>", "</mfrac>");
        return tokens;
    }

    private static String toMathMlInner(String s, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (String t : parseTokensFull(s, from, to)) sb.append(t);
        return sb.toString();
    }

    /** Склеивает prev marker next в одну конструкцию (степень/дробь). */
    private static List<String> foldBinary(List<String> tokens, String marker,
                                           String openTag, String closeTag) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).equals(marker) && !out.isEmpty() && i + 1 < tokens.size()) {
                // для «^» базой считается весь предшествующий ряд букв
                // (sin^2 → (sin)², а не n²); останавливаемся на не-буквенном токене
                String base;
                if (marker.equals("^")) {
                    int end = out.size(), start = end;
                    while (start - 1 >= 0 && isLetterToken(out.get(start - 1))) start--;
                    if (end - start >= 2) {
                        StringBuilder run = new StringBuilder("<mrow>");
                        for (int k = start; k < end; k++) run.append(out.get(k));
                        run.append("</mrow>");
                        base = run.toString();
                        out.subList(start, end).clear();
                    } else {
                        base = out.remove(out.size() - 1);
                    }
                } else {
                    base = out.remove(out.size() - 1);
                }
                String next = tokens.get(++i);
                out.add(openTag + base + next + closeTag);
            } else {
                out.add(tokens.get(i));
            }
        }
        return out;
    }

    /** Префиксная конструкция (√): маркер + следующий атом → <msqrt>. */
    private static List<String> foldPrefix(List<String> tokens, String marker,
                                           String openTag, String closeTag) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).equals(marker)) {
                if (i + 1 < tokens.size()) {
                    out.add(openTag + tokens.get(++i) + closeTag);
                } else {
                    out.add("<mo>√</mo>");
                }
            } else {
                out.add(tokens.get(i));
            }
        }
        return out;
    }

    /** <mi>x</mi> или <mi>sin</mi> — одиночная буква или имя функции. */
    private static boolean isLetterToken(String t) {
        return t.startsWith("<mi>") && t.endsWith("</mi>")
                && t.indexOf("<mi>", 4) == -1;
    }

    private static String operatorToken(char c) {
        return switch (c) {
            case '*' -> "<mo>&#8901;</mo>";   // ⋅
            case '<' -> "<mo>&lt;</mo>";
            case '>' -> "<mo>&gt;</mo>";
            case '^', '_', '/', '√' -> String.valueOf(c); // маркеры для fold
            default -> "<mo>" + escape(c) + "</mo>";
        };
    }

    private static String escape(char c) {
        if (c == '<') return "&lt;";
        if (c == '>') return "&gt;";
        return String.valueOf(c);
    }

    private static String escape(String s) {
        return s.replace("<", "&lt;").replace(">", "&gt;");
    }
}
