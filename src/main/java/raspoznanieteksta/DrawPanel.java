package raspoznanieteksta;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Холст на модели ШТРИХОВ (как в Ink to Math у Word): каждый штрих — объект
 * со списком точек. Ластик удаляет ВЕСЬ штрих, которого коснулся, выделение
 * с Delete — целые штрихи внутри рамки. Стертое исчезает полностью —
 * «призраков» в растре не остаётся (раньше ластик рисовал белым непрозрачным,
 * а распознавание читает альфа-канал: стертые линии продолжали считаться чернилами).
 */
public class DrawPanel extends JPanel {

    public enum Tool { PEN, ERASER, SELECT }

    /** Один штрих: полилиния + толщина. */
    static final class InkStroke {
        final List<Point> points = new ArrayList<>();
        final float width;

        InkStroke(float width) {
            this.width = width;
        }

        void add(Point p) {
            points.add(p);
        }

        boolean isEmpty() {
            return points.isEmpty();
        }

        /** Пересекает ли штрих отрезок (маршрут ластика) с запасом radius. */
        boolean hitsSegment(double x1, double y1, double x2, double y2, double radius) {
            double r = radius + width / 2.0;
            double r2 = r * r;
            for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                // расстояние от точки до отрезка маршрута ластика
                double dx = x2 - x1, dy = y2 - y1;
                double len2 = dx * dx + dy * dy;
                double t = len2 == 0 ? 0
                        : ((p.x - x1) * dx + (p.y - y1) * dy) / len2;
                t = Math.max(0, Math.min(1, t));
                double cx = x1 + t * dx, cy = y1 + t * dy;
                double ddx = p.x - cx, ddy = p.y - cy;
                if (ddx * ddx + ddy * ddy <= r2) return true;
            }
            return false;
        }

        /** Есть ли точка штриха внутри прямоугольника выделения. */
        boolean insideRect(Rectangle sel) {
            for (Point p : points) {
                if (sel.contains(p)) return true;
            }
            return false;
        }
    }

    /** Радиус ластика (зона касания). */
    private static final int ERASER_RADIUS = 10;
    private static final float PEN_WIDTH = 6f;

    private final List<InkStroke> strokes = new ArrayList<>();
    private InkStroke current; // штрих, который рисуют прямо сейчас

    private Tool currentTool = Tool.PEN;

    // Выделение
    private int selStartX = -1, selStartY = -1;
    private int selEndX = -1, selEndY = -1;
    private boolean selecting = false;

    /** Последняя позиция ластика (для маршрута между событиями). */
    private int lastEraseX = -1, lastEraseY = -1;

    /** Вызывается после завершения штриха (для динамического распознавания). */
    private Runnable strokeListener;

    public void setStrokeListener(Runnable r) {
        this.strokeListener = r;
    }

    /** Идёт ли рисование прямо сейчас. */
    public boolean isDrawing() {
        return drawing;
    }

    private boolean drawing = false;

    public DrawPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(400, 400));
        setFocusable(true);

        MouseAdapter handler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (currentTool == Tool.SELECT) {
                    selecting = true;
                    selStartX = e.getX();
                    selStartY = e.getY();
                    selEndX = e.getX();
                    selEndY = e.getY();
                    repaint();
                } else if (currentTool == Tool.PEN) {
                    drawing = true;
                    current = new InkStroke(PEN_WIDTH);
                    current.add(new Point(e.getX(), e.getY()));
                    repaint();
                } else { // ERASER
                    drawing = true;
                    eraseAt(e.getX(), e.getY(), e.getX(), e.getY());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (currentTool == Tool.SELECT) {
                    if (selecting) {
                        selEndX = e.getX();
                        selEndY = e.getY();
                        repaint();
                    }
                    return;
                }
                if (!drawing) return;
                if (currentTool == Tool.PEN) {
                    if (current != null) {
                        current.add(new Point(e.getX(), e.getY()));
                        repaint();
                    }
                } else { // ERASER
                    eraseAt(lastEraseX, lastEraseY, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                boolean wasStroke = false;
                if (currentTool == Tool.SELECT && selecting) {
                    selecting = false;
                    int x1 = Math.min(selStartX, selEndX);
                    int y1 = Math.min(selStartY, selEndY);
                    int x2 = Math.max(selStartX, selEndX);
                    int y2 = Math.max(selStartY, selEndY);
                    selStartX = x1; selStartY = y1;
                    selEndX = x2;   selEndY = y2;
                    repaint();
                } else if (currentTool == Tool.PEN) {
                    if (current != null && !current.isEmpty()) {
                        strokes.add(current);
                        wasStroke = true;
                    }
                    current = null;
                } else { // ERASER
                    wasStroke = true; // содержимое изменилось
                }
                drawing = false;
                if (strokeListener != null && wasStroke) strokeListener.run();
            }
        };

        addMouseListener(handler);
        addMouseMotionListener(handler);

        // --- Горячая клавиша Delete / Backspace для удаления выделения ---
        InputMap im = getInputMap(WHEN_FOCUSED);
        ActionMap am = getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteSelection");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "deleteSelection");

        am.put("deleteSelection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteSelection();
            }
        });
    }

    public void setTool(Tool tool) {
        this.currentTool = tool;
        if (tool != Tool.SELECT) {
            clearSelection();
        }
        setCursor(Cursor.getPredefinedCursor(
                tool == Tool.SELECT ? Cursor.CROSSHAIR_CURSOR : Cursor.DEFAULT_CURSOR));
        requestFocusInWindow();
    }

    /** Ластик: удаляет целиком все штрихи, которых коснулся маршрут. */
    private void eraseAt(int x1, int y1, int x2, int y2) {
        if (x1 < 0) { x1 = x2; y1 = y2; }
        boolean removed = false;
        Iterator<InkStroke> it = strokes.iterator();
        while (it.hasNext()) {
            if (it.next().hitsSegment(x1, y1, x2, y2, ERASER_RADIUS)) {
                it.remove();
                removed = true;
            }
        }
        lastEraseX = x2;
        lastEraseY = y2;
        if (removed) repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g); // белый фон
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(Color.BLACK);
        for (InkStroke s : strokes) {
            drawStroke(g2, s);
        }
        if (current != null) {
            drawStroke(g2, current);
        }

        if (selStartX >= 0 && selEndX > selStartX && selEndY > selStartY) {
            g2.setColor(new Color(0, 120, 215));
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{5f, 5f}, 0f));
            g2.drawRect(selStartX, selStartY,
                    selEndX - selStartX, selEndY - selStartY);
        }
    }

    private void drawStroke(Graphics2D g2, InkStroke s) {
        g2.setStroke(new BasicStroke(s.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        List<Point> pts = s.points;
        if (pts.size() == 1) {
            Point p = pts.get(0);
            // одиночная точка — рисуем кружок (кап слева не виден на пустом Graphics)
            g2.fillOval(p.x - (int) s.width / 2, p.y - (int) s.width / 2,
                    (int) s.width, (int) s.width);
            return;
        }
        for (int i = 1; i < pts.size(); i++) {
            Point a = pts.get(i - 1), b = pts.get(i);
            g2.draw(new Line2D.Double(a.x, a.y, b.x, b.y));
        }
    }

    /** Рендер всех штрихов в прозрачный растр (для распознавания/фото). */
    public BufferedImage getImage() {
        if (strokes.isEmpty()) return null;
        int w = Math.max(getWidth(), 1), h = Math.max(getHeight(), 1);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        for (InkStroke s : strokes) {
            drawStroke(g, s);
        }
        g.dispose();
        return img;
    }

    public boolean hasSelection() {
        return selStartX >= 0 && selEndX > selStartX && selEndY > selStartY;
    }

    /** Удаляет ЦЕЛИКОМ все штрихи, попавшие в выделение. */
    public void deleteSelection() {
        if (!hasSelection()) return;
        Rectangle sel = new Rectangle(selStartX, selStartY,
                selEndX - selStartX, selEndY - selStartY);
        strokes.removeIf(s -> s.insideRect(sel));
        clearSelection();
        repaint();
    }

    public void clearSelection() {
        selStartX = selStartY = selEndX = selEndY = -1;
        selecting = false;
        repaint();
    }

    public void clear() {
        strokes.clear();
        current = null;
        clearSelection();
        repaint();
    }
}
