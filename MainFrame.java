package raspoznanieteksta;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Map;

public class MainFrame extends JFrame {

    private final DrawPanel drawPanel = new DrawPanel();
    private final RecognitionEngine engine = new RecognitionEngine();
    private final JLabel statusLabel = new JLabel("Образцов в базе: 0");
    private final File dataFile = new File("data/samples.dat");

    public MainFrame() {
        super("RaspoznanieTeksta — Распознавание рукописных символов");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        add(drawPanel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout());
        JButton trainBtn = new JButton("Обучить");
        JButton recognizeBtn = new JButton("Распознать");
        JButton clearBtn = new JButton("Очистить");
        JButton saveBtn = new JButton("Сохранить базу");
        JButton loadBtn = new JButton("Загрузить базу");

        buttons.add(trainBtn);
        buttons.add(recognizeBtn);
        buttons.add(clearBtn);
        buttons.add(saveBtn);
        buttons.add(loadBtn);
        add(buttons, BorderLayout.NORTH);

        add(statusLabel, BorderLayout.SOUTH);

        trainBtn.addActionListener(e -> onTrain());
        recognizeBtn.addActionListener(e -> onRecognize());
        clearBtn.addActionListener(e -> drawPanel.clear());
        saveBtn.addActionListener(e -> onSave());
        loadBtn.addActionListener(e -> onLoad());

        if (dataFile.exists()) onLoad();

        setSize(600, 520);
        setLocationRelativeTo(null);
    }

    private void onTrain() {
        BufferedImage img = drawPanel.getImage();
        if (img == null) { showInfo("Сначала нарисуйте символ."); return; }

        double[] features = ImageProcessor.extractFeatures(img);
        if (features == null) { showInfo("Холст пуст — нарисуйте символ."); return; }

        String label = JOptionPane.showInputDialog(this,
                "Введите метку (буква или цифра):",
                "Обучение", JOptionPane.QUESTION_MESSAGE);
        if (label == null || label.isBlank()) return;

        engine.addSample(label.trim().toUpperCase(), features);
        updateStatus();
        drawPanel.clear();
        showInfo("Образец «" + label.trim().toUpperCase() + "» добавлен.");
    }

    private void onRecognize() {
        BufferedImage img = drawPanel.getImage();
        if (img == null) { showInfo("Сначала нарисуйте символ."); return; }

        double[] features = ImageProcessor.extractFeatures(img);
        if (features == null) { showInfo("Холст пуст — нарисуйте символ."); return; }
        if (engine.size() == 0) { showInfo("База пуста. Сначала обучите программу."); return; }

        List<Map.Entry<String, Double>> top = engine.recognize(features, 3);
        StringBuilder sb = new StringBuilder("<html><b>Топ гипотез:</b><br>");
        for (Map.Entry<String, Double> e : top) {
            sb.append(String.format("• %s  (расстояние %.2f)<br>", e.getKey(), e.getValue()));
        }
        sb.append("</html>");
        JOptionPane.showMessageDialog(this, sb.toString(),
                "Результат распознавания", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onSave() {
        try {
            File parent = dataFile.getParentFile();
            if (parent != null) parent.mkdirs();
            engine.save(dataFile);
            showInfo("База сохранена: " + dataFile.getAbsolutePath());
        } catch (Exception ex) {
            showError("Ошибка сохранения: " + ex.getMessage());
        }
    }

    private void onLoad() {
        if (!dataFile.exists()) { showInfo("Файл базы не найден."); return; }
        try {
            engine.load(dataFile);
            updateStatus();
            showInfo("Загружено образцов: " + engine.size());
        } catch (Exception ex) {
            showError("Ошибка загрузки: " + ex.getMessage());
        }
    }

    private void updateStatus() {
        statusLabel.setText("Образцов в базе: " + engine.size());
    }

    private void showInfo(String msg) { JOptionPane.showMessageDialog(this, msg); }
    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }
}