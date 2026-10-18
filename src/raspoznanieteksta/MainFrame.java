package raspoznanieteksta;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Map;

public class MainFrame extends JFrame {

    private final DrawPanel drawPanel = new DrawPanel();
    private final RecognitionEngine engine = new RecognitionEngine();
    private final JLabel statusLabel = new JLabel("Образцов в базе: 0");
    private final JTextField resultField = new JTextField();
    private final File dataFile = new File("data/samples.dat");

    // Кнопки инструментов — как в Paint
    private JToggleButton penBtn, eraserBtn, selectBtn;

    public MainFrame() {
        super("RaspoznanieTeksta — Распознавание рукописных символов");

        // --- Поведение при закрытии ---
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int choice = JOptionPane.showConfirmDialog(
                        MainFrame.this,
                        "Закрыть программу?\nНе забудьте сохранить базу образцов!",
                        "Подтверждение выхода",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice == JOptionPane.YES_OPTION) {
                    dispose();
                    System.exit(0);
                }
            }
        });

        setLayout(new BorderLayout());

        // === СВЕРХУ: тулбар инструментов (Paint-style) ===
        add(createToolBar(), BorderLayout.NORTH);

        // === СЛЕВА: вертикальная панель возможностей ===
        add(createLeftPanel(), BorderLayout.WEST);

        // === ЦЕНТР: холст ===
        add(drawPanel, BorderLayout.CENTER);

        // === СНИЗУ: статус + строка результата ===
        add(createBottomPanel(), BorderLayout.SOUTH);

        if (dataFile.exists()) onLoad();

        setResizable(true);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setSize(1024, 720);
        setLocationRelativeTo(null);
    }

    // ---------- Верхний тулбар инструментов ----------
    private JPanel createToolBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        bar.setBackground(new Color(245, 245, 245));

        penBtn    = new JToggleButton("✏ Карандаш");
        eraserBtn = new JToggleButton("🧽 Ластик");
        selectBtn = new JToggleButton("▭ Выделить");

        ButtonGroup group = new ButtonGroup();
        group.add(penBtn);
        group.add(eraserBtn);
        group.add(selectBtn);
        penBtn.setSelected(true);

        penBtn.addActionListener(e -> drawPanel.setTool(DrawPanel.Tool.PEN));
        eraserBtn.addActionListener(e -> drawPanel.setTool(DrawPanel.Tool.ERASER));
        selectBtn.addActionListener(e -> drawPanel.setTool(DrawPanel.Tool.SELECT));

        JButton clearAllBtn = new JButton("🗑 Очистить всё");
        clearAllBtn.addActionListener(e -> drawPanel.clear());

        JButton deleteSelBtn = new JButton("✂ Удалить выделенное");
        deleteSelBtn.addActionListener(e -> drawPanel.deleteSelection());

        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 24));

        bar.add(penBtn);
        bar.add(eraserBtn);
        bar.add(selectBtn);
        bar.add(sep);
        bar.add(clearAllBtn);
        bar.add(deleteSelBtn);

        return bar;
    }

    // ---------- Левая вертикальная панель возможностей ----------
    private JPanel createLeftPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.setPreferredSize(new Dimension(170, 0));
        panel.setBackground(new Color(250, 250, 250));

        JLabel header = new JLabel("Возможности");
        header.setFont(header.getFont().deriveFont(Font.BOLD, 13f));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(header);
        panel.add(Box.createVerticalStrut(8));

        panel.add(makeLeftButton("Обучить",           e -> onTrain()));
        panel.add(Box.createVerticalStrut(4));
        panel.add(makeLeftButton("Распознать",        e -> onRecognize()));
        panel.add(Box.createVerticalStrut(4));
        panel.add(makeLeftButton("Распознать строку", e -> onRecognizeLine()));
        panel.add(Box.createVerticalStrut(4));
        panel.add(makeLeftButton("Очистить холст",    e -> drawPanel.clear()));

        panel.add(Box.createVerticalStrut(16));
        panel.add(makeSeparator());
        panel.add(Box.createVerticalStrut(16));

        panel.add(makeLeftButton("Сохранить базу", e -> onSave()));
        panel.add(Box.createVerticalStrut(4));
        panel.add(makeLeftButton("Загрузить базу", e -> onLoad()));

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JButton makeLeftButton(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.addActionListener(l);
        return b;
    }

    private JSeparator makeSeparator() {
        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        return sep;
    }

    // ---------- Нижняя панель: статус + строка результата ----------
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));

        // Строка статуса сверху
        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.add(statusLabel, BorderLayout.WEST);

        // Строка результата снизу
        JPanel resultRow = new JPanel(new BorderLayout(8, 0));
        JLabel resultLabel = new JLabel("Результат:");
        resultField.setEditable(false);
        resultField.setFont(resultField.getFont().deriveFont(Font.BOLD, 16f));
        resultField.setToolTipText("Здесь появится распознанный текст. Можно выделить и скопировать.");

        JButton copyBtn = new JButton("Копировать");
        copyBtn.addActionListener(e -> copyResultToClipboard());

        resultRow.add(resultLabel, BorderLayout.WEST);
        resultRow.add(resultField, BorderLayout.CENTER);
        resultRow.add(copyBtn, BorderLayout.EAST);

        panel.add(statusRow, BorderLayout.NORTH);
        panel.add(resultRow, BorderLayout.SOUTH);
        return panel;
    }

    private void setResultText(String text) {
        resultField.setText(text);
        // ставим курсор в начало, чтобы видеть начало строки при длинном тексте
        resultField.setCaretPosition(0);
    }

    private void copyResultToClipboard() {
        String text = resultField.getText();
        if (text == null || text.isEmpty()) {
            showInfo("Сначала распознайте что-нибудь.");
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        showInfo("Скопировано в буфер обмена: «" + text + "»");
    }

    // ---------- Действия ----------
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

        String best = top.get(0).getKey();
        setResultText(best);

        JOptionPane.showMessageDialog(this, sb.toString(),
                "Результат распознавания", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onRecognizeLine() {
        BufferedImage img = drawPanel.getImage();
        if (img == null) { showInfo("Сначала нарисуйте строку."); return; }
        if (engine.size() == 0) { showInfo("База пуста. Сначала обучите программу."); return; }

        List<BufferedImage> symbols = ImageProcessor.segmentSymbols(img, 6);
        if (symbols.isEmpty()) { showInfo("Не найдено ни одного символа."); return; }

        StringBuilder result = new StringBuilder();
        StringBuilder details = new StringBuilder("<html><b>Разбито на " + symbols.size()
                + " фрагм.:</b><br>");

        for (int i = 0; i < symbols.size(); i++) {
            double[] features = ImageProcessor.extractFeatures(symbols.get(i));
            if (features == null) { result.append("?"); continue; }

            List<Map.Entry<String, Double>> top = engine.recognize(features, 1);
            String label = top.get(0).getKey();
            double dist = top.get(0).getValue();

            result.append(label);
            details.append(String.format("• #%d: <b>%s</b> (расстояние %.2f)<br>",
                    i + 1, label, dist));
        }
        details.append("</html>");

        setResultText(result.toString());

        JOptionPane.showMessageDialog(this,
                "<html><b>Распознанная строка:</b><br><font size='5'>"
                        + result + "</font><br><br>" + details.toString(),
                "Результат", JOptionPane.INFORMATION_MESSAGE);
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