package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.geom.AffineTransform;

final class GamePanel extends JPanel implements KeyListener {
    private final World world;
    private final GameFrame owner;
    private final Timer timer;
    private long lastNanos = System.nanoTime();

    GamePanel(World world, GameFrame owner) {
        this.world = world;
        this.owner = owner;
        setFocusable(true);
        setBackground(new Color(8, 12, 18));
        addKeyListener(this);
        timer = new Timer(16, e -> tick());
    }

    void start() { requestFocusInWindow(); timer.start(); }
    void stop() { timer.stop(); }

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNanos) / 1_000_000_000.0);
        lastNanos = now;
        world.update(dt);
        repaint();
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        AffineTransform old = g2.getTransform();
        g2.scale(0.9, 0.9);
        world.draw(g2);
        g2.setTransform(old);
        drawHud(g2);
        g2.dispose();
    }

    private void drawHud(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,175));
        g2.fillRoundRect(12, 12, 850, 90, 14, 14);
        g2.setColor(Color.WHITE);
        g2.drawString("StarChem modular build | " + world.localPlayerName, 28, 36);
        g2.setColor(new Color(210,230,245));
        g2.drawString(world.status, 28, 58);
        g2.drawString("ESC returns to lobby.", 28, 80);
    }

    @Override public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) owner.showLobby("Returned to lobby.");
    }
    @Override public void keyTyped(KeyEvent e) { }
    @Override public void keyReleased(KeyEvent e) { }
}
