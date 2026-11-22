package raspoznanieteksta;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Контейнер базы образцов с версией формата.
 * v2 = признаки нормализованы (FeatureOps.smoothAndCenter применён один раз).
 * Старые файлы (просто List&lt;Sample&gt; с сырыми признаками) нормализуются при загрузке.
 */
public class SampleBase implements Serializable {
    // НЕ менять: изменение ломает чтение старых файлов; версия формата — поле version
    private static final long serialVersionUID = 2L;

    private final int version = 2;
    private final List<Sample> samples;

    public SampleBase(List<Sample> samples) {
        this.samples = new ArrayList<>(samples);
    }

    public int getVersion() { return version; }
    public List<Sample> getSamples() { return samples; }
}
