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
import java.util.function.Predicate;

public class MainFrame extends JFrame {

    private enum Mode { DIGITS, LETTERS, BOTH }
    private enum Lang { RU, EN }   // язык распознаваемых букв

    private Mode currentMode = Mode.BOTH;
    private Lang currentLang = Lang.RU;

    private final DrawPanel drawPanel = new DrawPanel();
    private final RecognitionEngine engine = new RecognitionEngine();
    private final JLabel statusLabel = new JLabel();
    private final JTextField resultField = new JTextField();
    private final File dataFile = new File("data/samples.dat");

    private JToggleButton penBtn, eraserBtn, selectBtn;
    private JButton clearAllBtn, deleteSelBtn;

    private JRadioButton modeDigitsBtn, modeLettersBtn, modeBothBtn;
    private JToggleButton langBtn;

    private JLabel headerLabel;
    private JButton trainBtn, recognizeBtn, recognizeLineBtn, clearCanvasBtn, saveBtn, loadBtn;

    private boolean dbSaved = true;

    public MainFrame() {
        super("RaspoznanieTeksta — Распознавание рукописных символов");

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!dbSaved) {
                    int choice = JOptionPane.showConfirmDialog(
                            MainFrame.this,
                            "Закрыть программу?\nЕсть НЕсохранённые изменения базы образцов!",
                            "Подтверждение выхода",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    if (choice != JOptionPane.YES_OPTION) return;
                }
                dispose();
                System.exit(0);
            }
        });

        setLayout(new BorderLayout());
        add(createToolBar(), BorderLayout.NORTH);
        add(createLeftPanel(), BorderLayout.WEST);
        add(drawPanel, BorderLayout.CENTER);
        add(createBottomPanel(), BorderLayout.SOUTH);

        if (dataFile.exists()) onLoad();

        setResizable(true);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setSize(1024, 720);
        setLocationRelativeTo(null);
    }

    // ============ Статус ============
    private void updateStatus() {
        String modeRu = switch (currentMode) {
            case DIGITS -> "Цифры";
            case LETTERS -> "Буквы";
            case BOTH -> "Цифры+Буквы";
        };
        String langRu = (currentLang == Lang.RU) ? "RU (кириллица)" : "EN (латиница)";
        String savedRu = dbSaved ? "сохранена" : "есть изменения";

        statusLabel.setText("Язык: " + langRu
                + " | Режим: " + modeRu
                + " | Образцов в базе: " + engine.size()
                + " | База: " + savedRu);
    }

    // ============ Тулбар ============
    private JPanel createToolBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        bar.setBackground(new Color(245, 245, 245));

        // --- Переключатель языка РАСПОЗНАВАНИЯ (RU/EN) ---
        langBtn = new JToggleButton("RU");
        langBtn.setToolTipText("Язык распознаваемых букв: RU — кириллица, EN — латиница");
        langBtn.addActionListener(e -> {
            currentLang = (currentLang == Lang.RU) ? Lang.EN : Lang.RU;
            langBtn.setText(currentLang == Lang.RU ? "RU" : "EN");
            updateStatus();
        });
        bar.add(langBtn);

        JSeparator sepLang = new JSeparator(SwingConstants.VERTICAL);
        sepLang.setPreferredSize(new Dimension(1, 24));
        bar.add(sepLang);

        penBtn    = new JToggleButton("✏ Карандаш");
        eraserBtn = new JToggleButton("🧽 Ластик");
        selectBtn = new JToggleButton("▭ Выделить");

        ButtonGroup tools = new ButtonGroup();
        tools.add(penBtn);
        tools.add(eraserBtn);
        tools.add(selectBtn);
        penBtn.setSelected(true);

        penBtn.addActionListener(e -> drawPanel.setTool(DrawPanel.Tool.PEN));
        eraserBtn.addActionListener(e -> drawPanel.setTool(DrawPanel.Tool.ERASER));
        selectBtn.addActionListener(e -> drawPanel.setTool(DrawPanel.Tool.SELECT));

        clearAllBtn = new JButton("🗑 Очистить всё");
        clearAllBtn.addActionListener(e -> drawPanel.clear());

        deleteSelBtn = new JButton("✂ Удалить выделенное");
        deleteSelBtn.setToolTipText("Удалить выделенную область (также работает клавиша Delete)");
        deleteSelBtn.addActionListener(e -> drawPanel.deleteSelection());

        JSeparator sep1 = new JSeparator(SwingConstants.VERTICAL);
        sep1.setPreferredSize(new Dimension(1, 24));

        bar.add(penBtn);
        bar.add(eraserBtn);
        bar.add(selectBtn);
        bar.add(sep1);
        bar.add(clearAllBtn);
        bar.add(deleteSelBtn);

        return bar;
    }

    // ============ Левая панель ============
    private JPanel createLeftPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.setPreferredSize(new Dimension(190, 0));
        panel.setBackground(new Color(250, 250, 250));

        JLabel modeTitle = new JLabel("Режим распознавания:");
        modeTitle.setFont(modeTitle.getFont().deriveFont(Font.BOLD, 12f));
        modeTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(modeTitle);
        panel.add(Box.createVerticalStrut(4));

        modeDigitsBtn = new JRadioButton("Цифры");
        modeLettersBtn = new JRadioButton("Буквы");
        modeBothBtn = new JRadioButton("Цифры+Буквы");
        modeBothBtn.setSelected(true);
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(modeDigitsBtn);
        modeGroup.add(modeLettersBtn);
        modeGroup.add(modeBothBtn);

        modeDigitsBtn.addActionListener(e -> setMode(Mode.DIGITS));
        modeLettersBtn.addActionListener(e -> setMode(Mode.LETTERS));
        modeBothBtn.addActionListener(e -> setMode(Mode.BOTH));

        modeDigitsBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        modeLettersBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        modeBothBtn.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(modeDigitsBtn);
        panel.add(modeLettersBtn);
        panel.add(modeBothBtn);

        panel.add(Box.createVerticalStrut(16));
        panel.add(makeSeparator());
        panel.add(Box.createVerticalStrut(16));

        headerLabel = new JLabel("Возможности");
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 13f));
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(headerLabel);
        panel.add(Box.createVerticalStrut(8));

        trainBtn = makeLeftButton("Обучить", e -> onTrain());
        recognizeBtn = makeLeftButton("Распознать", e -> onRecognize());
        recognizeLineBtn = makeLeftButton("Распознать строку", e -> onRecognizeLine());
        clearCanvasBtn = makeLeftButton("Очистить холст", e -> drawPanel.clear());

        panel.add(trainBtn);
        panel.add(Box.createVerticalStrut(4));
        panel.add(recognizeBtn);
        panel.add(Box.createVerticalStrut(4));
        panel.add(recognizeLineBtn);
        panel.add(Box.createVerticalStrut(4));
        panel.add(clearCanvasBtn);

        panel.add(Box.createVerticalStrut(16));
        panel.add(makeSeparator());
        panel.add(Box.createVerticalStrut(16));

        saveBtn = makeLeftButton("Сохранить базу", e -> onSave());
        loadBtn = makeLeftButton("Загрузить базу", e -> onLoad());
        panel.add(saveBtn);
        panel.add(Box.createVerticalStrut(4));
        panel.add(loadBtn);

        panel.add(Box.createVerticalGlue());

        updateStatus();
        return panel;
    }

    private void setMode(Mode mode) {
        currentMode = mode;
        updateStatus();
    }

    // ============ Фильтр и проверка метки с учётом режима и языка ============
    private Predicate<String> modeFilter() {
        return label -> {
            switch (currentMode) {
                case DIGITS:  return label.matches("[0-9]");
                case LETTERS: return label.matches(currentLang == Lang.RU ? "[А-ЯЁ]" : "[A-Z]");
                case BOTH:    return label.matches(currentLang == Lang.RU ? "[0-9А-ЯЁ]" : "[0-9A-Z]");
                default: return true;
            }
        };
    }

    private String validateLabelForMode(String label) {
        String L = label.trim().toUpperCase();
        if (L.isEmpty()) return "Пустая метка.";

        String allowed;
        if (currentMode == Mode.DIGITS) {
            allowed = "[0-9]";
        } else if (currentMode == Mode.LETTERS) {
            allowed = (currentLang == Lang.RU) ? "[А-ЯЁ]" : "[A-Z]";
        } else {
            allowed = (currentLang == Lang.RU) ? "[0-9А-ЯЁ]" : "[0-9A-Z]";
        }

        if (!L.matches(allowed)) {
            String modeName = switch (currentMode) {
                case DIGITS -> "«Цифры»";
                case LETTERS -> "«Буквы»";
                case BOTH -> "«Цифры+Буквы»";
            };
            String langName = (currentLang == Lang.RU) ? "кириллица" : "латиница";
            return "Метка «" + L + "» не подходит.\nРежим: " + modeName
                    + ", язык: " + langName
                    + "\nДопустимые символы: " + allowed.replaceAll("[\\[\\]]", "")
                    + "\n(введите один символ — заглавную букву или цифру)";
        }
        return null;
    }

    // ============ Нижняя панель ============
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));

        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.add(statusLabel, BorderLayout.WEST);

        JPanel resultRow = new JPanel(new BorderLayout(8, 0));
        JLabel resultLabel = new JLabel("Результат:");
        resultField.setEditable(false);
        resultField.setFont(resultField.getFont().deriveFont(Font.BOLD, 16f));
        resultField.setToolTipText("Здесь появится распознанный текст. Можно скопировать.");

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
        showInfo("Скопировано: «" + text + "»");
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

    // ============ Действия ============
    private void onTrain() {
        BufferedImage img = drawPanel.getImage();
        if (img == null) { showInfo("Сначала нарисуйте символ."); return; }

        double[] features = ImageProcessor.extractFeatures(img);
        if (features == null) { showInfo("Холст пуст."); return; }

        String label = JOptionPane.showInputDialog(this,
                "Введите метку (буква или цифра):",
                "Обучение", JOptionPane.QUESTION_MESSAGE);
        if (label == null) return;

        String err = validateLabelForMode(label);
        if (err != null) { showError(err); return; }

        String L = label.trim().toUpperCase();
        engine.addSample(L, features);
        dbSaved = false;
        updateStatus();
        drawPanel.clear();
        showInfo("Образец «" + L + "» добавлен.");
    }

    private void onRecognize() {
        BufferedImage img = drawPanel.getImage();
        if (img == null) { showInfo("Сначала нарисуйте символ."); return; }

        double[] features = ImageProcessor.extractFeatures(img);
        if (features == null) { showInfo("Холст пуст."); return; }
        if (engine.size() == 0) { showInfo("База пуста."); return; }

        List<Map.Entry<String, Double>> top = engine.recognize(features, 3, modeFilter());
        if (top.isEmpty()) {
            showInfo("В базе нет образцов для текущего режима/языка.");
            return;
        }

        StringBuilder sb = new StringBuilder("<html><b>Топ гипотез:</b><br>");
        for (Map.Entry<String, Double> e : top) {
            sb.append(String.format("• %s  (%.2f)<br>", e.getKey(), e.getValue()));
        }
        sb.append("</html>");

        setResultText(top.get(0).getKey());
        JOptionPane.showMessageDialog(this, sb.toString(),
                "Результат распознавания", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onRecognizeLine() {
        BufferedImage img = drawPanel.getImage();
        if (img == null) { showInfo("Сначала нарисуйте строку."); return; }
        if (engine.size() == 0) { showInfo("База пуста."); return; }

        List<BufferedImage> symbols = ImageProcessor.segmentSymbols(img, 6);
        if (symbols.isEmpty()) { showInfo("Символы не найдены."); return; }

        StringBuilder result = new StringBuilder();
        StringBuilder details = new StringBuilder("<html><b>Разбито на фрагм.: "
                + symbols.size() + "</b><br>");

        for (int i = 0; i < symbols.size(); i++) {
            double[] features = ImageProcessor.extractFeatures(symbols.get(i));
            if (features == null) { result.append("?"); continue; }

            List<Map.Entry<String, Double>> top = engine.recognize(features, 1, modeFilter());
            if (top.isEmpty()) { result.append("?"); continue; }

            String label = top.get(0).getKey();
            double dist = top.get(0).getValue();
            result.append(label);
            details.append(String.format("• #%d: <b>%s</b> (%.2f)<br>", i + 1, label, dist));
        }
        details.append("</html>");

        setResultText(result.toString());
        JOptionPane.showMessageDialog(this,
                "<html><b>Распознанная строка:</b><br><font size='5'>"
                        + result + "</font><br><br>" + details,
                "Результат", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onSave() {
        try {
            File parent = dataFile.getParentFile();
            if (parent != null) parent.mkdirs();
            engine.save(dataFile);
            dbSaved = true;
            updateStatus();
            showInfo("База сохранена: " + dataFile.getAbsolutePath());
        } catch (Exception ex) {
            showError("Ошибка сохранения: " + ex.getMessage());
        }
    }

    private void onLoad() {
        if (!dataFile.exists()) { showInfo("Файл базы не найден."); return; }
        try {
            engine.load(dataFile);
            dbSaved = true;
            updateStatus();
            showInfo("Загружено образцов: " + engine.size());
        } catch (Exception ex) {
            showError("Ошибка загрузки: " + ex.getMessage());
        }
    }

    private void showInfo(String msg) { JOptionPane.showMessageDialog(this, msg); }
    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }
}