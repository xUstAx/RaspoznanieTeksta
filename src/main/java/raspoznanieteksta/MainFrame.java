package raspoznanieteksta;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Интерфейс: всё управление — на панели инструментов слева, холст по центру,
 * результат внизу. Распознавание — ансамбль «нейросеть × kNN»: кандидаты
 * обоих движков объединяются произведением долей, внизу видно, что ответил
 * каждый движок. Формулы копируются в Word как MathML (редактор уравнений
 * превращает его в нативную формулу) и как фото.
 */
public class MainFrame extends JFrame {

    private enum Mode { DIGITS, LETTERS, BOTH, FORMULAS }
    private enum Lang { RU, EN }
    private enum CaseMode { UPPER, TITLE, LOWER }

    private Mode currentMode = Mode.BOTH;
    private Lang currentLang = Lang.RU;
    private CaseMode currentCase = CaseMode.UPPER;

    private final DrawPanel drawPanel = new DrawPanel();
    private final RecognitionEngine knnEngine = new RecognitionEngine();
    private final NeuralEngine neuralEngine = new NeuralEngine(knnEngine);
    /** Ансамбль: объединяет ответы обоих движков (произведение долей). */
    private final EnsembleEngine ensemble = new EnsembleEngine(knnEngine, neuralEngine);
    private final JLabel statusLabel = new JLabel();
    /** Синтетическая база (только чтение, создаётся SampleGenerator'ом). */
    private final File baseFile = new File("data/samples.dat");
    /** «Мой» файл: сюда записываются образцы, добавленные кнопкой «Обучить». */
    private final File userFile = new File("data/samples_user.dat");

    private final JTextField resultField = new JTextField();
    private final JLabel verdictLabel = new JLabel(" ");
    private String lastRaw = "";
    private String lastNnRaw = "";
    private String lastKnnRaw = "";
    private double lastNnConf = 0.0;
    private double lastKnnConf = 0.0;

    private JToggleButton penBtn, eraserBtn, selectBtn;
    private JToggleButton langBtn;
    private JRadioButton modeDigitsBtn, modeLettersBtn, modeBothBtn, modeFormulasBtn;
    private JCheckBox dynamicBtn;
    private JComboBox<String> caseCombo;

    /** Таймер динамического распознавания: срабатывает через паузу после штриха. */
    private final Timer liveTimer = new Timer(700, e -> {
        if (!drawPanel.isDrawing() && dynamicBtn.isSelected()) {
            recognizeLineSilent();
        }
    });

    private boolean dbSaved = true;

    public MainFrame() {
        super("RaspoznanieTeksta — Нейросеть + Машинное обучение (ансамбль)");

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

        if (baseFile.exists() || userFile.exists()) loadAll();

        // Динамическое распознавание: каждый завершённый штрих перезапускает
        // таймер; после паузы 700 мс холст распознаётся автоматически (тихо).
        liveTimer.setRepeats(false);
        drawPanel.setStrokeListener(() -> {
            if (dynamicBtn.isSelected()) liveTimer.restart();
        });

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
            case FORMULAS -> "Формулы";
        };
        String langRu = (currentLang == Lang.RU) ? "RU" : "EN";
        statusLabel.setText("Язык: " + langRu
                + " | Режим: " + modeRu
                + " | Образцов: " + knnEngine.size()
                + " (моих: " + knnEngine.userSize() + ")"
                + " | Нейросеть: " + neuralEngine.statusText());
    }

    // ============ Панель инструментов ============
    private JPanel createToolBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        bar.setBackground(new Color(245, 245, 245));

        // --- Ряд 1: инструменты и распознавание ---
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        row1.setOpaque(false);

        langBtn = new JToggleButton("RU");
        langBtn.setToolTipText("Язык распознаваемых букв: RU — кириллица, EN — латиница");
        langBtn.addActionListener(e -> {
            currentLang = (currentLang == Lang.RU) ? Lang.EN : Lang.RU;
            langBtn.setText(currentLang == Lang.RU ? "RU" : "EN");
            updateStatus();
        });
        row1.add(langBtn);

        penBtn = new JToggleButton("Карандаш");
        penBtn.setToolTipText("Карандаш");
        eraserBtn = new JToggleButton("Ластик");
        eraserBtn.setToolTipText("Ластик");
        selectBtn = new JToggleButton("Выделить");
        selectBtn.setToolTipText("Выделить (Delete — удалить выделенное)");
        ButtonGroup tools = new ButtonGroup();
        tools.add(penBtn);
        tools.add(eraserBtn);
        tools.add(selectBtn);
        penBtn.setSelected(true);
        penBtn.addActionListener(e -> drawPanel.setTool(DrawPanel.Tool.PEN));
        eraserBtn.addActionListener(e -> drawPanel.setTool(DrawPanel.Tool.ERASER));
        selectBtn.addActionListener(e -> drawPanel.setTool(DrawPanel.Tool.SELECT));
        row1.add(penBtn);
        row1.add(eraserBtn);
        row1.add(selectBtn);

        JButton clearAllBtn = new JButton("Очистить");
        clearAllBtn.setToolTipText("Очистить холст");
        clearAllBtn.addActionListener(e -> drawPanel.clear());
        row1.add(clearAllBtn);

        JButton deleteSelBtn = new JButton("Удалить выдел.");
        deleteSelBtn.setToolTipText("Удалить выделенное");
        deleteSelBtn.addActionListener(e -> drawPanel.deleteSelection());
        row1.add(deleteSelBtn);

        // --- Ряд 2: режимы и база ---
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        row2.setOpaque(false);

        row2.add(new JLabel("Режим:"));
        modeDigitsBtn = new JRadioButton("Цифры");
        modeLettersBtn = new JRadioButton("Буквы");
        modeBothBtn = new JRadioButton("Цифры+Буквы");
        modeFormulasBtn = new JRadioButton("Формулы");
        modeFormulasBtn.setToolTipText("Цифры, знаки + - * / = ( ) ^ . , < > и строчные латинские буквы (sin, cos, x, y...)");
        modeBothBtn.setSelected(true);
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(modeDigitsBtn);
        modeGroup.add(modeLettersBtn);
        modeGroup.add(modeBothBtn);
        modeGroup.add(modeFormulasBtn);
        modeDigitsBtn.addActionListener(e -> setMode(Mode.DIGITS));
        modeLettersBtn.addActionListener(e -> setMode(Mode.LETTERS));
        modeBothBtn.addActionListener(e -> setMode(Mode.BOTH));
        modeFormulasBtn.addActionListener(e -> setMode(Mode.FORMULAS));
        row2.add(modeDigitsBtn);
        row2.add(modeLettersBtn);
        row2.add(modeBothBtn);
        row2.add(modeFormulasBtn);

        row2.add(makeSeparator());

        dynamicBtn = new JCheckBox("Динамически", true);
        dynamicBtn.setToolTipText("Распознавать автоматически после паузы в рисовании (700 мс)");
        row2.add(dynamicBtn);

        row2.add(makeSeparator());

        row2.add(new JLabel("Регистр:"));
        caseCombo = new JComboBox<>(new String[]{"ВСЕ ЗАГЛАВНЫЕ", "Первая заглавная", "все маленькие"});
        caseCombo.setSelectedIndex(0);
        caseCombo.addActionListener(e -> onCaseChanged());
        row2.add(caseCombo);

        row2.add(makeSeparator());

        JButton saveBtn = new JButton("Мои образцы");
        saveBtn.setToolTipText("Сохранить мои образцы в data/samples_user.dat");
        saveBtn.addActionListener(e -> onSave());
        row2.add(saveBtn);

        JButton loadBtn = new JButton("Перечитать базы");
        loadBtn.setToolTipText("Перечитать синтетическую базу и мои образцы");
        loadBtn.addActionListener(e -> onLoad());
        row2.add(loadBtn);

        bar.add(row1);
        bar.add(row2);
        return bar;
    }

    // ============ Левая панель: функции распознавания ============
    private JPanel createLeftPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(8, 8, 8, 4),
                BorderFactory.createTitledBorder("Распознавание")));
        panel.setPreferredSize(new Dimension(210, 0));
        panel.setBackground(new Color(240, 246, 255));

        JButton trainBtn = makeSideButton("Обучить",
                "Добавить нарисованный символ в «мои» образцы (сохраняется сразу)",
                e -> onTrain());
        JButton recognizeBtn = makeSideButton("Распознать символ",
                "Распознать символ ансамблем (с окном кандидатов)",
                e -> onRecognize());
        JButton recognizeLineBtn = makeSideButton("Распознать строку",
                "Распознать строку ансамблем (с окном кандидатов)",
                e -> onRecognizeLine());

        dynamicBtn = new JCheckBox("Динамически", true);
        dynamicBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        dynamicBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        dynamicBtn.setToolTipText("Распознавать автоматически после паузы в рисовании (700 мс)");

        panel.add(trainBtn);
        panel.add(Box.createVerticalStrut(6));
        panel.add(recognizeBtn);
        panel.add(Box.createVerticalStrut(6));
        panel.add(recognizeLineBtn);
        panel.add(Box.createVerticalStrut(10));
        panel.add(dynamicBtn);

        panel.add(Box.createVerticalStrut(12));
        panel.add(makeHorizontalSeparator());
        panel.add(Box.createVerticalStrut(12));

        JButton photoBtn = makeSideButton("Фото в буфер (Ctrl+V)",
                "Скопировать формулу с холста картинкой — вставьте Ctrl+V в документ",
                e -> onCopyPhoto());
        JButton wordBtn = makeSideButton("В Word (MathML)",
                "Скопировать распознанную формулу как MathML — Вставка → Уравнение в Word → Ctrl+V",
                e -> onCopyWord());

        panel.add(photoBtn);
        panel.add(Box.createVerticalStrut(6));
        panel.add(wordBtn);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JButton makeSideButton(String text, String tooltip, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setToolTipText(tooltip);
        b.addActionListener(l);
        return b;
    }

    private JSeparator makeHorizontalSeparator() {
        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        return sep;
    }

    private JSeparator makeSeparator() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 22));
        return sep;
    }

    private void setMode(Mode mode) {
        currentMode = mode;
        updateStatus();
    }

    // ============ Нижняя панель ============
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));

        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.add(statusLabel, BorderLayout.WEST);

        JPanel resultRow = new JPanel(new BorderLayout(8, 0));
        resultRow.add(new JLabel("Результат:"), BorderLayout.WEST);
        resultField.setEditable(false);
        resultField.setFont(resultField.getFont().deriveFont(Font.BOLD, 18f));
        resultField.setToolTipText("Распознанный текст (ансамбль). Можно скопировать.");
        resultRow.add(resultField, BorderLayout.CENTER);

        JButton copyBtn = new JButton("Копировать");
        copyBtn.addActionListener(e -> copyResultToClipboard());
        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        rightBox.add(copyBtn);
        resultRow.add(rightBox, BorderLayout.EAST);

        verdictLabel.setFont(verdictLabel.getFont().deriveFont(Font.PLAIN, 12f));
        verdictLabel.setForeground(new Color(60, 60, 60));

        JPanel center = new JPanel(new BorderLayout());
        center.add(resultRow, BorderLayout.CENTER);
        center.add(verdictLabel, BorderLayout.SOUTH);

        panel.add(statusRow, BorderLayout.NORTH);
        panel.add(center, BorderLayout.CENTER);
        return panel;
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

    private void onCaseChanged() {
        int idx = caseCombo.getSelectedIndex();
        currentCase = switch (idx) {
            case 0 -> CaseMode.UPPER;
            case 1 -> CaseMode.TITLE;
            default -> CaseMode.LOWER;
        };
        refreshResults();
    }

    private String applyCase(String text) {
        if (text == null || text.isEmpty()) return "";
        // в режиме формул регистр результата не трогаем: sin(x) не должно
        // превращаться в SIN(X)
        if (currentMode == Mode.FORMULAS) return text;
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

    private void refreshResults() {
        resultField.setText(applyCase(lastRaw));
        verdictLabel.setText(buildDetailText());
    }

    private void setResultText(String raw, String nnText, Double nnConf,
                               String knnText, Double knnConf) {
        lastRaw = raw == null ? "" : raw;
        lastNnRaw = nnText == null ? "" : nnText;
        lastKnnRaw = knnText == null ? "" : knnText;
        lastNnConf = nnConf == null ? 0.0 : nnConf;
        lastKnnConf = knnConf == null ? 0.0 : knnConf;
        refreshResults();
    }

    /** Строка деталей: что ответил каждый движок и как согласованы. */
    private String buildDetailText() {
        if (lastNnRaw.isEmpty() && lastKnnRaw.isEmpty()) return " ";
        StringBuilder sb = new StringBuilder();
        if (!lastNnRaw.isEmpty()) {
            sb.append("Нейросеть: «").append(applyCase(lastNnRaw)).append("»");
            if (lastNnConf > 0) sb.append(" ").append(String.format("%.0f%%", lastNnConf * 100));
        }
        if (!lastKnnRaw.isEmpty()) {
            if (sb.length() > 0) sb.append("  •  ");
            sb.append("kNN: «").append(applyCase(lastKnnRaw)).append("»");
            if (lastKnnConf > 0) sb.append(" ").append(String.format("%.0f%%", lastKnnConf * 100));
        }
        if (!lastNnRaw.isEmpty() && !lastKnnRaw.isEmpty()) {
            sb.append("  •  ");
            if (lastNnRaw.equals(lastKnnRaw)) {
                sb.append("движки согласны");
            } else {
                sb.append("движки разошлись, ансамбль выбрал «").append(applyCase(lastRaw)).append("»");
            }
        }
        return sb.toString();
    }

    // ============ Окно выбора кандидатов ============
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

        DefaultTableModel candModel = new DefaultTableModel(
                new Object[]{"Метка", "Уверенность"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable candTable = new JTable(candModel);
        candTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        candTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        candTable.getColumnModel().getColumn(1).setPreferredWidth(90);

        JScrollPane candScroll = new JScrollPane(candTable);
        candScroll.setPreferredSize(new Dimension(240, 260));
        candScroll.setBorder(BorderFactory.createTitledBorder("Кандидаты (ансамбль)"));

        final boolean[] suppressSelectionEvent = {false};

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

    // ============ Фильтр и проверка метки ============
    private Predicate<String> modeFilter() {
        return label -> {
            switch (currentMode) {
                case DIGITS:  return label.matches("[0-9]");
                case LETTERS: return label.matches(currentLang == Lang.RU ? "[А-ЯЁ]" : "[A-Z]");
                case BOTH:    return label.matches(currentLang == Lang.RU ? "[0-9А-ЯЁ]" : "[0-9A-Z]");
                case FORMULAS: return label.matches("[0-9+\\-*/=().^,<>a-z√π|≤≥≠]");
                default: return true;
            }
        };
    }

    private String validateLabelForMode(String label) {
        // в режиме формул регистр метки важен (x — переменная), остальное — верхний регистр
        String L = (currentMode == Mode.FORMULAS) ? label.trim() : label.trim().toUpperCase();
        if (L.isEmpty()) return "Пустая метка.";

        String allowed;
        if (currentMode == Mode.DIGITS) {
            allowed = "[0-9]";
        } else if (currentMode == Mode.LETTERS) {
            allowed = (currentLang == Lang.RU) ? "[А-ЯЁ]" : "[A-Z]";
        } else if (currentMode == Mode.FORMULAS) {
            // только строчные латинские: заглавные и строчные буквы у шрифтов
            // неразличимы по форме (x/X, s/S), а в формулах стандарт — строчные
            allowed = "[0-9+\\-*/=().^,<>a-z√π|≤≥≠]";
        } else {
            allowed = (currentLang == Lang.RU) ? "[0-9А-ЯЁ]" : "[0-9A-Z]";
        }

        if (!L.matches(allowed)) {
            String modeName = switch (currentMode) {
                case DIGITS -> "«Цифры»";
                case LETTERS -> "«Буквы»";
                case BOTH -> "«Цифры+Буквы»";
                case FORMULAS -> "«Формулы» (цифры, + - * / = ( ) ^ . , < >, строчные латинские)";
            };
            String langName = (currentLang == Lang.RU) ? "кириллица" : "латиница";
            return "Метка «" + L + "» не подходит.\nРежим: " + modeName
                    + (currentMode == Mode.FORMULAS ? "" : ", язык: " + langName)
                    + "\nДопустимые символы: " + allowed.replaceAll("[\\[\\]]", "");
        }
        return null;
    }

    // ============ Действия ============

    /** Обучение: добавить нарисованный символ в общую базу (для обоих движков). */
    private void onTrain() {
        BufferedImage img = drawPanel.getImage();
        if (img == null) { showInfo("Сначала нарисуйте символ."); return; }

        double[] features = ImageProcessor.extractFeatures(img);
        if (features == null) { showInfo("Холст пуст."); return; }

        String label = JOptionPane.showInputDialog(this,
                "Введите метку (символ):",
                "Обучение", JOptionPane.QUESTION_MESSAGE);
        if (label == null) return;

        String err = validateLabelForMode(label);
        if (err != null) { showError(err); return; }

        // в режиме формул регистр важен (x, y), в остальных — верхний регистр
        String L = (currentMode == Mode.FORMULAS) ? label.trim() : label.trim().toUpperCase();
        knnEngine.addUserSample(L, features);

        // образец пользователя сразу записывается в «мой» файл
        try {
            File parent = userFile.getParentFile();
            if (parent != null) parent.mkdirs();
            knnEngine.saveUser(userFile);
            dbSaved = true;
        } catch (Exception ex) {
            dbSaved = false;
            showError("Образец добавлен в память, но не записан в «мой» файл: " + ex.getMessage());
        }

        neuralEngine.markDirty();
        updateStatus();
        drawPanel.clear();

        retrainNeuralAsync();
        showInfo("Образец «" + L + "» добавлен и сохранён в " + userFile.getPath()
                + "\nНейросеть переобучается в фоне.");
    }

    /** Фоновое переобучение нейросети (не блокирует интерфейс). */
    private void retrainNeuralAsync() {
        if (neuralEngine.isTraining()) return;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                neuralEngine.retrain();
                return null;
            }
            @Override protected void done() {
                updateStatus();
            }
        }.execute();
        updateStatus();
    }

    /** Распознавание одного символа ансамблем (с окном кандидатов). */
    private void onRecognize() {
        BufferedImage img = drawPanel.getImage();
        if (img == null) { showInfo("Сначала нарисуйте символ."); return; }

        double[] features = ImageProcessor.extractFeatures(img);
        if (features == null) { showInfo("Холст пуст."); return; }

        String reason = ensemble.readyReason();
        if (reason != null) { showInfo(reason); return; }

        List<Map.Entry<String, Double>> top = ensemble.recognize(features, 5, modeFilter());
        if (top.isEmpty()) {
            showInfo("В базе нет образцов для текущего режима/языка.");
            return;
        }

        List<List<Map.Entry<String, Double>>> perFragment = new ArrayList<>();
        perFragment.add(top);

        String[] chosen = showChoiceDialog(perFragment, "Выбор символа");
        if (chosen != null && chosen.length > 0) {
            String label = chosen[0];
            setResultText(label,
                    shareOf(ensemble.getLastNnTop(), label),
                    shareConf(ensemble.getLastNnTop(), label),
                    shareOf(ensemble.getLastKnnTop(), label),
                    shareConf(ensemble.getLastKnnTop(), label));
        }
    }

    private String shareOf(List<Map.Entry<String, Double>> top, String label) {
        for (Map.Entry<String, Double> e : top) {
            if (e.getKey().equals(label)) return label;
        }
        return top.isEmpty() ? "?" : top.get(0).getKey();
    }

    private double shareConf(List<Map.Entry<String, Double>> top, String label) {
        for (Map.Entry<String, Double> e : top) {
            if (e.getKey().equals(label)) return e.getValue();
        }
        return top.isEmpty() ? 0.0 : top.get(0).getValue();
    }

    /** Распознавание строки/формулы ансамблем (с окном кандидатов для линейных). */
    private void onRecognizeLine() {
        BufferedImage img = drawPanel.getImage();
        if (img == null) { showInfo("Сначала нарисуйте строку."); return; }

        String reason = ensemble.readyReason();
        if (reason != null) { showInfo(reason); return; }

        if (ImageProcessor.cropToContent(img) == null) { showInfo("Холст пуст."); return; }

        // 2D-разбор: дроби, степени, индексы (как в редакторе формул Word)
        FormulaLayout.ParseResult p = FormulaLayout.parse(img, this::recognizeFragment);
        if (p.structured) {
            setResultText(p.linear, p.nnLine, null, p.knnLine, null);
            return;
        }

        // линейная строка — окно кандидатов для исправления
        List<BufferedImage> symbols = ImageProcessor.segmentSymbols(img, 3);
        if (symbols.isEmpty()) { showInfo("Символы не найдены."); return; }

        List<List<Map.Entry<String, Double>>> perFragment = new ArrayList<>();
        StringBuilder nnLine = new StringBuilder(), knnLine = new StringBuilder();
        for (BufferedImage sym : symbols) {
            double[] features = ImageProcessor.extractFeatures(sym);
            List<Map.Entry<String, Double>> top = null;
            if (features != null) {
                top = ensemble.recognize(features, 5, modeFilter());
                nnLine.append(top1(ensemble.getLastNnTop()));
                knnLine.append(top1(ensemble.getLastKnnTop()));
            }
            if (top == null || top.isEmpty()) {
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
        setResultText(sb.toString(), nnLine.toString(), null, knnLine.toString(), null);
    }

    /** Распознавание одного фрагмента холста ансамблем (для 2D-парсера). */
    private FormulaLayout.FragmentResult recognizeFragment(BufferedImage frag) {
        double[] f = ImageProcessor.extractFeatures(frag);
        if (f == null) return new FormulaLayout.FragmentResult("?", "?", "?");
        List<Map.Entry<String, Double>> top = ensemble.recognize(f, 1, modeFilter());
        String label = top.isEmpty() ? "?" : top.get(0).getKey();
        return new FormulaLayout.FragmentResult(label,
                top1(ensemble.getLastNnTop()), top1(ensemble.getLastKnnTop()));
    }

    private String top1(List<Map.Entry<String, Double>> top) {
        return top.isEmpty() ? "?" : top.get(0).getKey();
    }

    /** Тихое динамическое распознавание строки/формулы (без диалогов). */
    private void recognizeLineSilent() {
        BufferedImage img = drawPanel.getImage();
        if (img == null) return;
        if (ensemble.readyReason() != null) return;
        if (ImageProcessor.cropToContent(img) == null) return;

        FormulaLayout.ParseResult p = FormulaLayout.parse(img, this::recognizeFragment);
        setResultText(p.linear, p.nnLine, null, p.knnLine, null);
    }

    /**
     * «Фото»: формула с холста обрезается, масштабируется на белом фоне
     * и кладётся в системный буфер обмена как изображение.
     */
    private void onCopyPhoto() {
        BufferedImage img = drawPanel.getImage();
        if (img == null) { showInfo("Сначала нарисуйте формулу."); return; }

        BufferedImage cropped = ImageProcessor.cropToContent(img);
        if (cropped == null) { showInfo("Холст пуст."); return; }

        double scale = Math.max(1.0, 160.0 / cropped.getHeight());
        int w = (int) Math.ceil(cropped.getWidth() * scale) + 40;
        int h = (int) Math.ceil(cropped.getHeight() * scale) + 40;
        BufferedImage photo = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(cropped, 20, 20, w - 40, h - 40, null);
        g.dispose();

        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
                @Override public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[]{DataFlavor.imageFlavor};
                }
                @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return DataFlavor.imageFlavor.equals(flavor);
                }
                @Override public Object getTransferData(DataFlavor flavor)
                        throws UnsupportedFlavorException, IOException {
                    if (DataFlavor.imageFlavor.equals(flavor)) return photo;
                    throw new UnsupportedFlavorException(flavor);
                }
            }, null);
            showInfo("Фото формулы скопировано в буфер.\nВставьте (Ctrl+V) в Word или другой документ.");
        } catch (Exception ex) {
            showError("Не удалось скопировать фото: " + ex.getMessage());
        }
    }

    /** «В Word»: распознанный текст формулы → MathML в буфер обмена. */
    private void onCopyWord() {
        String formula = lastRaw;
        if (formula == null || formula.isEmpty()) {
            showInfo("Сначала распознайте формулу.");
            return;
        }
        String mathMl = MathMlBuilder.toMathMl(formula);
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(mathMl), null);
        showInfo("MathML скопирован в буфер.\nВ Word: Вставка → Уравнение → вставьте (Ctrl+V)\n— формула превратится в нативное уравнение.");
    }

    private void loadAll() {
        try {
            knnEngine.load(baseFile);
            if (userFile.exists()) knnEngine.loadUser(userFile);
            dbSaved = true;
            neuralEngine.markDirty();
            retrainNeuralAsync();
            updateStatus();
            if (isVisible()) {
                showInfo("Загружено: базовых " + (knnEngine.size() - knnEngine.userSize())
                        + " + моих " + knnEngine.userSize()
                        + "\nНейросеть обучается в фоне...");
            }
        } catch (Exception ex) {
            showError("Ошибка загрузки баз: " + ex.getMessage());
        }
    }

    private void onSave() {
        try {
            File parent = userFile.getParentFile();
            if (parent != null) parent.mkdirs();
            knnEngine.saveUser(userFile);
            dbSaved = true;
            updateStatus();
            showInfo("Мои образцы сохранены: " + userFile.getAbsolutePath()
                    + "\n(всего моих: " + knnEngine.userSize() + ")");
        } catch (Exception ex) {
            dbSaved = false;
            showError("Ошибка сохранения: " + ex.getMessage());
        }
    }

    private void onLoad() {
        if (!baseFile.exists() && !userFile.exists()) {
            showInfo("Файлы баз не найдены.");
            return;
        }
        loadAll();
    }

    private void showInfo(String msg) { JOptionPane.showMessageDialog(this, msg); }
    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }
}
