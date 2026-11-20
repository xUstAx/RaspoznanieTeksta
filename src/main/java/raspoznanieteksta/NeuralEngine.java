package raspoznanieteksta;

import org.deeplearning4j.datasets.iterator.utilty.ListDataSetIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Predicate;

/**
 * Движок распознавания на нейронной сети (Deeplearning4j).
 * Многослойный перцептрон: 400 входов (20x20) -> 128 -> 64 -> softmax по классам.
 * Обучается на общей базе образцов (samples), как и классический kNN-движок.
 */
public class NeuralEngine {

    public static final int FEATURE_SIZE = ImageProcessor.FEATURE_SIZE;
    private static final int INPUT = FEATURE_SIZE * FEATURE_SIZE; // 400
    private static final int HIDDEN1 = 128;
    private static final int HIDDEN2 = 64;
    private static final int EPOCHS_MIN = 200;
    private static final int EPOCHS_MAX = 300;
    private static final long SEED = 123;

    /**
     * Варианты аугментации {сдвигX, сдвигY, поворот°, масштаб}.
     * Искусственно расширяют маленькую базу, повышая устойчивость сети.
     */
    private static final double[][] AUGMENTATIONS = {
            {0, 0, 0, 1},      // оригинал
            {1, 0, 0, 1}, {-1, 0, 0, 1}, {0, 1, 0, 1}, {0, -1, 0, 1}, // сдвиги на 1px
            {0, 0, 6, 1}, {0, 0, -6, 1},                              // повороты
            {0, 0, 0, 0.92}, {0, 0, 0, 1.08}                          // масштаб
    };

    private final RecognitionEngine store;

    private MultiLayerNetwork model;
    private List<String> classes = new ArrayList<>(); // индексы выходов сети
    private volatile boolean trained = false;
    private volatile boolean dirty = true;
    private volatile boolean training = false;
    private volatile double lastAccuracy = 0.0;

    public NeuralEngine(RecognitionEngine store) {
        this.store = store;
    }

    // ============ Состояние ============

    public boolean isTrained() { return trained; }
    public boolean isDirty() { return dirty; }
    public boolean isTraining() { return training; }
    public double getLastAccuracy() { return lastAccuracy; }

    /** Вызывается после добавления образца или загрузки базы. */
    public void markDirty() {
        dirty = true;
    }

    public String statusText() {
        if (training) return "обучается...";
        if (!trained) return "не обучена";
        return String.format("обучена (%.0f%%, %d классов)", lastAccuracy * 100, classes.size());
    }

    // ============ Обучение ============

    /**
     * Полное переобучение сети на всех образцах базы. Блокирующий вызов —
     * запускать из фонового потока (SwingWorker), не из EDT.
     */
    public synchronized void retrain() {
        training = true;
        try {
            List<Sample> samples = store.getSamples();

            // Список классов = отсортированные различные метки
            TreeSet<String> labelSet = new TreeSet<>();
            for (Sample s : samples) labelSet.add(s.getLabel());
            classes = new ArrayList<>(labelSet);

            if (classes.size() < 2) {
                // softmax требует минимум 2 класса — сеть не строим
                trained = false;
                dirty = false;
                return;
            }

            long n = samples.size();

            // Матрица оригинальных образцов — для оценки точности после обучения
            INDArray originalFeatures = org.nd4j.linalg.factory.Nd4j.create(n, INPUT);
            INDArray originalLabels = org.nd4j.linalg.factory.Nd4j.create(n, classes.size());
            for (int i = 0; i < n; i++) {
                Sample s = samples.get(i);
                double[] f = s.getFeatures();
                for (int j = 0; j < INPUT; j++) {
                    originalFeatures.putScalar(i, j, f[j]);
                }
                originalLabels.putScalar(i, classes.indexOf(s.getLabel()), 1.0);
            }

            // Сколько вариантов аугментации использовать: чем больше база,
            // тем меньше нужно искусственных копий (иначе обучение долго)
            int variants = (n > 800) ? 2 : (n > 300 ? 5 : AUGMENTATIONS.length);

            List<double[]> trainFeatures = new ArrayList<>();
            List<Integer> trainLabels = new ArrayList<>();
            for (Sample s : samples) {
                int classIdx = classes.indexOf(s.getLabel());
                for (int v = 0; v < variants; v++) {
                    double[] f = (v == 0) ? s.getFeatures()
                            : augment(s.getFeatures(), AUGMENTATIONS[v]);
                    trainFeatures.add(f);
                    trainLabels.add(classIdx);
                }
            }
            int m = trainFeatures.size();

            INDArray features = org.nd4j.linalg.factory.Nd4j.create(m, INPUT);
            INDArray labels = org.nd4j.linalg.factory.Nd4j.create(m, classes.size());
            for (int i = 0; i < m; i++) {
                double[] f = trainFeatures.get(i);
                for (int j = 0; j < INPUT; j++) {
                    features.putScalar(i, j, f[j]);
                }
                labels.putScalar(i, trainLabels.get(i), 1.0);
            }

            int batch = (int) Math.min(m > 1000 ? 64 : 32, m);
            // Чем меньше база, тем больше эпох нужно для сходимости
            int epochs = (int) Math.max(EPOCHS_MIN, Math.min(EPOCHS_MAX, 25000 / m));

            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                    .seed(SEED)
                    .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                    .updater(new Adam(0.01))
                    .weightInit(WeightInit.XAVIER)
                    .list()
                    .layer(new DenseLayer.Builder()
                            .nIn(INPUT).nOut(HIDDEN1)
                            .activation(Activation.RELU)
                            .build())
                    .layer(new DenseLayer.Builder()
                            .nIn(HIDDEN1).nOut(HIDDEN2)
                            .activation(Activation.RELU)
                            .build())
                    .layer(new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                            .nIn(HIDDEN2).nOut(classes.size())
                            .activation(Activation.SOFTMAX)
                            .build())
                    .build();

            MultiLayerNetwork net = new MultiLayerNetwork(conf);
            net.init();

            // ВАЖНО: перемешиваем данные каждую эпоху. Без этого батчи состоят
            // из образцов одного класса (генератор пишет класс-за-классом),
            // и сеть "схлопывается" на несколько классов.
            List<DataSet> data = splitToDataSets(features, labels);
            Random shuffler = new Random(SEED);
            for (int e = 0; e < epochs; e++) {
                Collections.shuffle(data, shuffler);
                net.fit(new ListDataSetIterator<>(data, batch));
            }

            // Точность на настоящих (не аугментированных) образцах — для статуса
            Evaluation eval = net.evaluate(new ListDataSetIterator<>(
                    splitToDataSets(originalFeatures, originalLabels), batch));
            lastAccuracy = eval.accuracy();

            model = net;
            trained = true;
            dirty = false;
        } finally {
            training = false;
        }
    }

    /**
     * Аугментация образца: восстанавливает из вектора признаков картинку 20x20,
     * применяет сдвиг/поворот/масштаб и возвращает новый вектор признаков.
     */
    private static double[] augment(double[] f, double[] p) {
        BufferedImage img = new BufferedImage(FEATURE_SIZE, FEATURE_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < f.length; i++) {
            int a = (int) Math.round(Math.max(0, Math.min(1, f[i])) * 255);
            img.setRGB(i % FEATURE_SIZE, i / FEATURE_SIZE, a << 24);
        }

        BufferedImage out = new BufferedImage(FEATURE_SIZE, FEATURE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(FEATURE_SIZE / 2.0 + p[0], FEATURE_SIZE / 2.0 + p[1]);
        g.rotate(Math.toRadians(p[2]));
        g.scale(p[3], p[3]);
        g.drawImage(img, -FEATURE_SIZE / 2, -FEATURE_SIZE / 2, null);
        g.dispose();

        double[] res = new double[f.length];
        for (int i = 0; i < res.length; i++) {
            res[i] = ((out.getRGB(i % FEATURE_SIZE, i / FEATURE_SIZE) >>> 24) & 0xff) / 255.0;
        }
        return res;
    }

    private List<DataSet> splitToDataSets(INDArray features, INDArray labels) {
        List<DataSet> list = new ArrayList<>();
        for (int i = 0; i < features.rows(); i++) {
            // getRow(i, true) сохраняет ранг 2: [1, N] — как требует DL4J
            list.add(new DataSet(features.getRow(i, true), labels.getRow(i, true)));
        }
        return list;
    }

    // ============ Распознавание ============

    /**
     * Распознавание: прогон через сеть, возврат top-N кандидатов
     * с вероятностями (0..1), отсортированных по убыванию.
     */
    public List<Map.Entry<String, Double>> recognize(double[] features, int topN,
                                                     Predicate<String> labelFilter) {
        List<Map.Entry<String, Double>> result = new ArrayList<>();
        if (!trained || model == null) return result;

        double[] norm = FeatureOps.smoothAndCenter(features);
        INDArray input = org.nd4j.linalg.factory.Nd4j.create(1, INPUT);
        for (int j = 0; j < INPUT; j++) {
            input.putScalar(0, j, norm[j]);
        }

        INDArray out = model.output(input);
        for (int c = 0; c < classes.size(); c++) {
            String label = classes.get(c);
            if (!labelFilter.test(label)) continue;
            result.add(new AbstractMap.SimpleEntry<>(label, out.getDouble(0, c)));
        }

        result.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // Уверенность = доля кандидата среди ТОП-5 (как у kNN) — чтобы
        // сравнение «кто увереннее» шло по одной шкале, а не softmax 0.9+ против доли 0.1.
        int nShow = Math.min(5, result.size());
        double sumTop = 0;
        for (int i = 0; i < nShow; i++) sumTop += result.get(i).getValue();
        if (sumTop <= 0) sumTop = 1;
        List<Map.Entry<String, Double>> top = new ArrayList<>(result.subList(0, nShow));
        for (Map.Entry<String, Double> e : top) {
            e.setValue(e.getValue() / sumTop);
        }
        return topN >= nShow ? top : new ArrayList<>(top.subList(0, topN));
    }
}
