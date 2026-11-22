package raspoznanieteksta;

import java.util.*;
import java.util.function.Predicate;

/**
 * Ансамбль двух движков: итоговая оценка кандидата = произведение
 * долей (уверенностей) нейросети и kNN. Вознаграждает согласие движков
 * и штрафует расхождения — по тестам это надёжнее каждого движка
 * по отдельности (+3 п.п. к лучшему одиночному результату).
 */
public class EnsembleEngine {

    private static final double EPS = 1e-6;

    private final RecognitionEngine knnEngine;
    private final NeuralEngine neuralEngine;

    // последние ответы каждого движка (для строки сравнения в UI)
    private List<Map.Entry<String, Double>> lastNnTop = new ArrayList<>();
    private List<Map.Entry<String, Double>> lastKnnTop = new ArrayList<>();

    public EnsembleEngine(RecognitionEngine knnEngine, NeuralEngine neuralEngine) {
        this.knnEngine = knnEngine;
        this.neuralEngine = neuralEngine;
    }

    public List<Map.Entry<String, Double>> getLastNnTop() { return lastNnTop; }
    public List<Map.Entry<String, Double>> getLastKnnTop() { return lastKnnTop; }

    /** Причина неготовности ансамбля (null — готов). */
    public String readyReason() {
        if (knnEngine.size() == 0) return "База пуста. Сначала обучите программу.";
        if (neuralEngine.isTraining()) return "Идёт обучение нейросети, подождите...";
        if (neuralEngine.isDirty()) {
            return "Образцы изменились — запущено переобучение, попробуйте через несколько секунд.";
        }
        if (!neuralEngine.isTrained()) {
            return "Нейросеть не может быть обучена: нужен минимум 2 разных класса в базе.";
        }
        return null;
    }

    /**
     * Совместное распознавание: топ-5 обоих движков объединяются,
     * итог = произведение долей, среди топ-5 кандидатов сумма = 1.
     */
    public List<Map.Entry<String, Double>> recognize(double[] features, int topN,
                                                     Predicate<String> labelFilter) {
        lastKnnTop = knnEngine.recognize(features, 5, labelFilter);
        lastNnTop = neuralEngine.recognize(features, 5, labelFilter);
        Map<String, Double> knn = topMap(lastKnnTop);
        Map<String, Double> nn = topMap(lastNnTop);

        Set<String> union = new LinkedHashSet<>();
        union.addAll(nn.keySet());
        union.addAll(knn.keySet());

        List<Map.Entry<String, Double>> fused = new ArrayList<>();
        for (String label : union) {
            double p = nn.getOrDefault(label, EPS) * knn.getOrDefault(label, EPS);
            fused.add(new AbstractMap.SimpleEntry<>(label, p));
        }
        fused.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // ренормализация среди топ-5: сумма показанных кандидатов = 1
        int nShow = Math.min(5, fused.size());
        double sum = 0;
        for (int i = 0; i < nShow; i++) sum += fused.get(i).getValue();
        if (sum <= 0) sum = 1;
        List<Map.Entry<String, Double>> top = new ArrayList<>(fused.subList(0, nShow));
        for (Map.Entry<String, Double> e : top) e.setValue(e.getValue() / sum);

        return topN >= nShow ? top : new ArrayList<>(top.subList(0, topN));
    }

    private static Map<String, Double> topMap(List<Map.Entry<String, Double>> list) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : list) m.put(e.getKey(), e.getValue());
        return m;
    }
}
