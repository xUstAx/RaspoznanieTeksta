package raspoznanieteksta;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

public class DrawPanel extends JPanel {

    private BufferedImage canvas;
    private int lastX, lastY;
    private boolean drawing = false;

    public DrawPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(400, 400));

        MouseAdapter handler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                ensureCanvas();
                drawing = true;
                lastX = e.getX();
                lastY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!drawing) return;
                Graphics2D g = canvas.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(lastX, lastY, e.getX(), e.getY());
                g.dispose();

                lastX = e.getX();
                lastY = e.getY();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                drawing = false;
            }
        };

        addMouseListener(handler);
        addMouseMotionListener(handler);
    }

    private void ensureCanvas() {
        if (canvas == null || canvas.getWidth() != getWidth() || canvas.getHeight() != getHeight()) {
            canvas = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
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

    public void clear() {
        canvas = null;
        repaint();
    }
}