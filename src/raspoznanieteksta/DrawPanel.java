package raspoznanieteksta;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

public class DrawPanel extends JPanel {

    public enum Tool { PEN, ERASER, SELECT }

    private BufferedImage canvas;
    private int lastX, lastY;
    private boolean drawing = false;

    private Tool currentTool = Tool.PEN;

    // Выделение
    private int selStartX = -1, selStartY = -1;
    private int selEndX = -1, selEndY = -1;
    private boolean selecting = false;

    public DrawPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(400, 400));

        MouseAdapter handler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                ensureCanvas();
                if (currentTool == Tool.SELECT) {
                    selecting = true;
                    selStartX = e.getX();
                    selStartY = e.getY();
                    selEndX = e.getX();
                    selEndY = e.getY();
                    repaint();
                } else {
                    drawing = true;
                    lastX = e.getX();
                    lastY = e.getY();
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

                Graphics2D g = canvas.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                if (currentTool == Tool.ERASER) {
                    g.setColor(Color.WHITE);
                    g.setStroke(new BasicStroke(20f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                } else {
                    g.setColor(Color.BLACK);
                    g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                }
                g.drawLine(lastX, lastY, e.getX(), e.getY());
                g.dispose();

                lastX = e.getX();
                lastY = e.getY();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                drawing = false;
                if (currentTool == Tool.SELECT && selecting) {
                    selecting = false;
                    // нормализуем координаты
                    int x1 = Math.min(selStartX, selEndX);
                    int y1 = Math.min(selStartY, selEndY);
                    int x2 = Math.max(selStartX, selEndX);
                    int y2 = Math.max(selStartY, selEndY);
                    selStartX = x1; selStartY = y1;
                    selEndX = x2;   selEndY = y2;
                    repaint();
                }
            }
        };

        addMouseListener(handler);
        addMouseMotionListener(handler);
    }

    public void setTool(Tool tool) {
        this.currentTool = tool;
        if (tool != Tool.SELECT) {
            clearSelection();
        }
        setCursor(Cursor.getPredefinedCursor(
                tool == Tool.SELECT ? Cursor.CROSSHAIR_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private void ensureCanvas() {
        if (canvas == null || canvas.getWidth() != getWidth() || canvas.getHeight() != getHeight()) {
            BufferedImage old = canvas;
            canvas = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
            if (old != null) {
                Graphics2D g = canvas.createGraphics();
                g.drawImage(old, 0, 0, null);
                g.dispose();
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (canvas != null) {
            g.drawImage(canvas, 0, 0, null);
        } else {
            g.setColor(Color.GRAY);
            g.drawString("Нарисуйте символ мышкой...", 20, 25);
        }

        // Рамка выделения — поверх рисунка
        if (selStartX >= 0 && selEndX > selStartX && selEndY > selStartY) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(new Color(0, 120, 215));
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{5f, 5f}, 0f));
            g2.drawRect(selStartX, selStartY,
                    selEndX - selStartX, selEndY - selStartY);
        }
    }

    public BufferedImage getImage() {
        if (canvas == null) return null;
        BufferedImage copy = new BufferedImage(canvas.getWidth(), canvas.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(canvas, 0, 0, null);
        g.dispose();
        return copy;
    }

    /** Удаляет область внутри выделения (заливает белым). */
    public void deleteSelection() {
        if (canvas == null) return;
        if (selStartX < 0 || selEndX <= selStartX || selEndY <= selStartY) return;

        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(selStartX, selStartY,
                selEndX - selStartX, selEndY - selStartY);
        g.dispose();

        clearSelection();
        repaint();
    }

    public void clearSelection() {
        selStartX = selStartY = selEndX = selEndY = -1;
        selecting = false;
        repaint();
    }

    public void clear() {
        canvas = null;
        clearSelection();
        repaint();
    }
}