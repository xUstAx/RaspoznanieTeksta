package raspoznanieteksta;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class MainFrame extends JFrame {

    private enum Mode { DIGITS, LETTERS, BOTH }
    private enum Lang { RU, EN }
    private enum CaseMode { UPPER, TITLE, LOWER }

    private Mode currentMode = Mode.BOTH;
    private Lang currentLang = Lang.RU;
    private CaseMode currentCase = CaseMode.UPPER;

    private final DrawPanel drawPanel = new DrawPanel();
    private final RecognitionEngine engine = new RecognitionEngine();
    private final JLabel statusLabel = new JLabel();
    private final JTextField resultField = new JTextField();
    private final File dataFile = new File("data/samples.dat");

    private String lastRawResult = "";

    private JToggleButton penBtn, eraserBtn, selectBtn;
    private JButton clearAllBtn, deleteSelBtn;

    private JRadioButton modeDigitsBtn, modeLettersBtn, modeBothBtn;
    private JToggleButton langBtn;

    private JLabel headerLabel;
    private JButton trainBtn, recognizeBtn, recognizeLineBtn, clearCanvasBtn, saveBtn, loadBtn;

    private JComboBox<String> caseCombo;

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

    // ============ Фильтр и проверка метки ============
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
                    + "\nДопустимые символы: " + allowed.replaceAll("[\\[\\]]", "");
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

        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        rightBox.add(copyBtn);

        JLabel caseLabel = new JLabel("Регистр:");
        caseCombo = new JComboBox<>(new String[]{
                "ВСЕ ЗАГЛАВНЫЕ",
                "Первая заглавная",
                "все маленькие"
        });
        caseCombo.setSelectedIndex(0);
        caseCombo.addActionListener(e -> onCaseChanged());

        rightBox.add(caseLabel);
        rightBox.add(caseCombo);

        resultRow.add(resultLabel, BorderLayout.WEST);
        resultRow.add(resultField, BorderLayout.CENTER);
        resultRow.add(rightBox, BorderLayout.EAST);

        panel.add(statusRow, BorderLayout.NORTH);
        panel.add(resultRow, BorderLayout.SOUTH);
        return panel;
    }

    private void onCaseChanged() {
        int idx = caseCombo.getSelectedIndex();
        currentCase = switch (idx) {
            case 0 -> CaseMode.UPPER;
            case 1 -> CaseMode.TITLE;
            default -> CaseMode.LOWER;
        };
        resultField.setText(applyCase(lastRawResult));
        resultField.setCaretPosition(0);
    }

    private String applyCase(String text) {
        if (text == null || text.isEmpty()) return "";
        return switch (currentCase) {
            case UPPER -> text.toUpperCase();
            case LOWER -> text.toLowerCase();
            case TITLE -> {
                String lower = text.toLowerCase();
                if (lower.isEmpty()) yield lower;
                yield Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
            }
        };
    }

    private void setResultText(String rawText) {
        lastRawResult = rawText == null ? "" : rawText;
        resultField.setText(applyCase(lastRawResult));
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

    // ============ Окно выбора: список всех кандидатов на каждый фрагмент ============
    /**
     * Показывает окно со списком всех кандидатов для каждого фрагмента.
     * Слева — список фрагментов, справа — таблица кандидатов выбранного фрагмента.
     * Клик по строке таблицы СРАЗУ применяет этого кандидата к выбранному фрагменту.
     * "Готово" подтверждает итог, "Отмена" закрывает без изменений.
     */
    private String[] showChoiceDialog(List<List<Map.Entry<String, Double>>> perFragment,
                                      String title) {
        if (perFragment == null || perFragment.isEmpty()) return null;

        final int n = perFragment.size();
        String[] chosen = new String[n];
        for (int i = 0; i < n; i++) {
            chosen[i] = perFragment.get(i).get(0).getKey();
        }

        JDialog dialog = new JDialog(this, title, true);
        dialog.setLayout(new BorderLayout(8, 8));

        // ===== Слева: список фрагментов =====
        DefaultListModel<String> fragModel = new DefaultListModel<>();
        for (int i = 0; i < n; i++) {
            fragModel.addElement("Фрагмент " + (i + 1) + " → " + chosen[i]);
        }
        JList<String> fragList = new JList<>(fragModel);
        fragList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fragList.setSelectedIndex(0);
        JScrollPane fragScroll = new JScrollPane(fragList);
        fragScroll.setPreferredSize(new Dimension(180, 260));
        fragScroll.setBorder(BorderFactory.createTitledBorder(
                (n == 1) ? "Фрагмент" : "Фрагменты строки"));

        // ===== Справа: таблица кандидатов выбранного фрагмента =====
        DefaultTableModel candModel = new DefaultTableModel(
                new Object[]{"Метка", "Расстояние"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable candTable = new JTable(candModel);
        candTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        candTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        candTable.getColumnModel().getColumn(1).setPreferredWidth(90);

        JScrollPane candScroll = new JScrollPane(candTable);
        candScroll.setPreferredSize(new Dimension(240, 260));
        candScroll.setBorder(BorderFactory.createTitledBorder("Кандидаты (все варианты)"));

        // Флаг, чтобы не реагировать на программное выделение строки
        final boolean[] suppressSelectionEvent = {false};

        // ===== Заполнение таблицы для выбранного фрагмента =====
        Runnable fillCandidates = () -> {
            int fi = fragList.getSelectedIndex();
            if (fi < 0) return;
            List<Map.Entry<String, Double>> cands = perFragment.get(fi);

            suppressSelectionEvent[0] = true;
            candModel.setRowCount(0);
            for (Map.Entry<String, Double> e : cands) {
                candModel.addRow(new Object[]{
                        e.getKey(),
                        String.format("%.2f", e.getValue())
                });
            }
            // выделяем строку, соответствующую текущему выбранному варианту
            for (int r = 0; r < candModel.getRowCount(); r++) {
                if (candModel.getValueAt(r, 0).equals(chosen[fi])) {
                    candTable.setRowSelectionInterval(r, r);
                    break;
                }
            }
            suppressSelectionEvent[0] = false;
        };
        fillCandidates.run();

        fragList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) fillCandidates.run();
        });

        // ===== Клик по кандидату сразу применяет его к текущему фрагменту =====
        candTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            if (suppressSelectionEvent[0]) return;

            int fi = fragList.getSelectedIndex();
            int ci = candTable.getSelectedRow();
            if (fi < 0 || ci < 0) return;

            String label = (String) candModel.getValueAt(ci, 0);
            chosen[fi] = label;
            fragModel.set(fi, "Фрагмент " + (fi + 1) + " → " + label);
        });

        JPanel center = new JPanel(new BorderLayout(8, 0));
        center.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        center.add(fragScroll, BorderLayout.WEST);
        center.add(candScroll, BorderLayout.CENTER);

        // ===== Снизу: Готово / Отмена =====
        JButton okBtn = new JButton("Готово");
        JButton cancelBtn = new JButton("Отмена");
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        bottom.add(okBtn);
        bottom.add(cancelBtn);

        dialog.add(center, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);

        final String[] result = {null};
        okBtn.addActionListener(e -> {
            result[0] = String.join("", chosen);
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        dialog.getRootPane().setDefaultButton(okBtn);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);

        return result[0] == null ? null : chosen;
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

        List<Map.Entry<String, Double>> top = engine.recognize(features, 5, modeFilter());
        if (top.isEmpty()) {
            showInfo("В базе нет образцов для текущего режима/языка.");
            return;
        }

        List<List<Map.Entry<String, Double>>> perFragment = new ArrayList<>();
        perFragment.add(top);

        String[] chosen = showChoiceDialog(perFragment, "Выбор символа");
        if (chosen != null && chosen.length > 0) {
            setResultText(chosen[0]);
        }
    }

    private void onRecognizeLine() {
        BufferedImage img = drawPanel.getImage();
        if (img == null) { showInfo("Сначала нарисуйте строку."); return; }
        if (engine.size() == 0) { showInfo("База пуста."); return; }

        List<BufferedImage> symbols = ImageProcessor.segmentSymbols(img, 6);
        if (symbols.isEmpty()) { showInfo("Символы не найдены."); return; }

        List<List<Map.Entry<String, Double>>> perFragment = new ArrayList<>();
        for (BufferedImage sym : symbols) {
            double[] features = ImageProcessor.extractFeatures(sym);
            if (features == null) {
                List<Map.Entry<String, Double>> empty = new ArrayList<>();
                empty.add(new java.util.AbstractMap.SimpleEntry<>("?", 0.0));
                perFragment.add(empty);
                continue;
            }
            List<Map.Entry<String, Double>> top = engine.recognize(features, 5, modeFilter());
            if (top.isEmpty()) {
                List<Map.Entry<String, Double>> empty = new ArrayList<>();
                empty.add(new java.util.AbstractMap.SimpleEntry<>("?", 0.0));
                perFragment.add(empty);
            } else {
                perFragment.add(top);
            }
        }

        String[] chosen = showChoiceDialog(perFragment, "Выбор символов строки");
        if (chosen == null) {
            showInfo("Распознавание отменено.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (String c : chosen) sb.append(c);
        setResultText(sb.toString());
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