package raspoznanieteksta;

import java.io.*;
import java.util.*;
import java.util.function.Predicate;

public class RecognitionEngine {

    /** Все образцы (синтетическая база + мои) — используются kNN и обучением нейросети. */
    private final List<Sample> samples = new ArrayList<>();
    /** Только пользовательские образцы — сохраняются в «мой» файл. */
    private final List<Sample> userSamples = new ArrayList<>();

    /** Добавляет образец, нормализуя его признаки (общий список — генератор/тесты). */
    public void addSample(String label, double[] features) {
        samples.add(new Sample(label, FeatureOps.smoothAndCenter(features)));
    }

    /** Новый образец пользователя: попадает и в общий список, и в «мой» файл. */
    public void addUserSample(String label, double[] features) {
        Sample s = new Sample(label, FeatureOps.smoothAndCenter(features));
        samples.add(s);
        userSamples.add(s);
    }

    public int size() { return samples.size(); }

    /** Число пользовательских образцов («мой» файл). */
    public int userSize() { return userSamples.size(); }

    /** Живой список образцов (уже нормализованных) — используется нейросетью для обучения. */
    public List<Sample> getSamples() { return samples; }

    /** Сохранение «моего» файла (только пользовательские образцы). */
    public void saveUser(File file) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            oos.writeObject(new SampleBase(userSamples));
        }
    }

    /**
     * Загрузка «моего» файла: образцы добавляются к базе.
     * Поддерживает все версии формата.
     */
    public void loadUser(File file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            for (Sample s : readSamples(ois.readObject())) {
                userSamples.add(s);
                samples.add(s);
            }
        }
    }

    public List<Map.Entry<String, Double>> recognize(double[] features, int topN) {
        return recognize(features, topN, label -> true);
    }

    /**
     * Распознавание взвешенным голосованием k ближайших образцов +
     * центроидов классов (усреднённые шаблоны — «якорные» голоса, стабилизируют
     * ответ, когда соседние образцы похожих шрифтов раздувают голосование).
     * Возвращает кандидатов с долей голосов (уверенностью 0..1).
     */
    public List<Map.Entry<String, Double>> recognize(double[] features, int topN,
                                                     Predicate<String> labelFilter) {
        double[] q = FeatureOps.smoothAndCenter(features);

        List<Map.Entry<Sample, Double>> dists = new ArrayList<>();
        for (Sample s : samples) {
            if (!labelFilter.test(s.getLabel())) continue;
            dists.add(new AbstractMap.SimpleEntry<>(s, euclideanDistance(q, s.getFeatures())));
        }
        dists.sort(Map.Entry.comparingByValue());

        final int K = 3;
        int k = Math.min(K, dists.size());
        Map<String, Double> votes = new HashMap<>();
        double total = 0;
        for (int i = 0; i < k; i++) {
            double w = 1.0 / (1.0 + dists.get(i).getValue());
            votes.merge(dists.get(i).getKey().getLabel(), w, Double::sum);
            total += w;
        }

        // голос центроида каждого класса (вес 0.5)
        Map<String, double[]> centroidSum = new HashMap<>();
        Map<String, Integer> centroidCnt = new HashMap<>();
        for (Sample s : samples) {
            if (!labelFilter.test(s.getLabel())) continue;
            double[] acc = centroidSum.computeIfAbsent(s.getLabel(), x -> new double[s.getFeatures().length]);
            double[] f = s.getFeatures();
            for (int j = 0; j < f.length; j++) acc[j] += f[j];
            centroidCnt.merge(s.getLabel(), 1, Integer::sum);
        }
        for (Map.Entry<String, double[]> e : centroidSum.entrySet()) {
            int c = centroidCnt.get(e.getKey());
            double[] mean = e.getValue();
            for (int j = 0; j < mean.length; j++) mean[j] /= c;
            double w = 0.5 / (1.0 + euclideanDistance(q, mean));
            votes.merge(e.getKey(), w, Double::sum);
            total += w;
        }

        List<Map.Entry<String, Double>> list = new ArrayList<>();
        for (Map.Entry<String, Double> e : votes.entrySet()) {
            list.add(new AbstractMap.SimpleEntry<>(e.getKey(), total > 0 ? e.getValue() / total : 0.0));
        }
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // Уверенность = доля голосов кандидата среди ТОП-5 (сумма показанных = 1).
        // Сырые доли среди 3 соседей + 69 центроидов «плоские» (~0.1) и плохо читаются.
        int nShow = Math.min(5, list.size());
        double sumTop = 0;
        for (int i = 0; i < nShow; i++) sumTop += list.get(i).getValue();
        if (sumTop <= 0) sumTop = 1;
        List<Map.Entry<String, Double>> top = new ArrayList<>(list.subList(0, nShow));
        for (Map.Entry<String, Double> e : top) {
            e.setValue(e.getValue() / sumTop);
        }
        return topN >= nShow ? top : new ArrayList<>(top.subList(0, topN));
    }

    private double euclideanDistance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    /** Сохранение: формат v2 (признаки уже нормализованы). */
    public void save(File file) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            oos.writeObject(new SampleBase(samples));
        }
    }

    /**
     * Чтение образцов из сериализованного объекта с учётом версии формата:
     * SampleBase (v2+) — уже нормализованы; сырой List — полная нормализация.
     */
    private static List<Sample> readSamples(Object o) {
        List<Sample> result = new ArrayList<>();
        if (o instanceof SampleBase) {
            result.addAll(((SampleBase) o).getSamples());
        } else {
            @SuppressWarnings("unchecked")
            List<Sample> raw = (List<Sample>) o;
            for (Sample s : raw) {
                result.add(new Sample(s.getLabel(), FeatureOps.smoothAndCenter(s.getFeatures())));
            }
        }
        return result;
    }

    /**
     * Загрузка базы. Формат v3 (SampleBase) — признаки уже нормализованы, применяется как есть.
     * Старый формат (List&lt;Sample&gt; с сырыми признаками) — нормализуется при загрузке.
     */
    public void load(File file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            samples.clear();
            samples.addAll(readSamples(ois.readObject()));
        }
    }
}
