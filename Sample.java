package raspoznanieteksta;

import java.io.Serializable;

public class Sample implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String label;
    private final double[] features;

    public Sample(String label, double[] features) {
        this.label = label;
        this.features = features;
    }

    public String getLabel() { return label; }
    public double[] getFeatures() { return features; }
}