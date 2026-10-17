package raspoznanieteksta;

import java.io.*;
import java.util.*;

public class RecognitionEngine {

    private final List<Sample> samples = new ArrayList<>();

    public void addSample(String label, double[] features) {
        samples.add(new Sample(label, features));
    }

    public int size() { return samples.size(); }

    public List<Map.Entry<String, Double>> recognize(double[] features, int topN) {
        Map<String, Double> bestByLabel = new HashMap<>();

        for (Sample s : samples) {
            double dist = euclideanDistance(features, s.getFeatures());
            bestByLabel.merge(s.getLabel(), dist, Math::min);
        }

        List<Map.Entry<String, Double>> list = new ArrayList<>(bestByLabel.entrySet());
        list.sort(Map.Entry.comparingByValue());
        return list.size() > topN ? list.subList(0, topN) : list;
    }

    private double euclideanDistance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    public void save(File file) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            oos.writeObject(new ArrayList<>(samples));
        }
    }

    @SuppressWarnings("unchecked")
    public void load(File file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            List<Sample> loaded = (List<Sample>) ois.readObject();
            samples.clear();
            samples.addAll(loaded);
        }
    }
}