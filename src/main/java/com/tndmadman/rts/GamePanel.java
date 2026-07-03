package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;

final class GamePanel extends JPanel implements KeyListener, MouseListener {
    private final World world;
    private final GameFrame owner;
    private final PeerNetwork network;
    private final Timer timer;
    private final BuildMenu buildMenu = new BuildMenu();
    private long lastNanos = System.nanoTime();
    private double zoom = 0.9;

    GamePanel(World world, GameFrame owner) { this(world, owner, null); }

    GamePanel(World world, GameFrame owner, PeerNetwork network) {
        this.world = world;
        this.owner = owner;
        this.network = network;
        setFocusable(true);
        setBackground(new Color(8, 12, 18));
        addKeyListener(this);
        addMouseListener(this);
        timer = new Timer(16, e -> tick());
    }

    void start() { requestFocusInWindow(); timer.start(); }
    void stop() { timer.stop(); }

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNanos) / 1_000_000_000.0);
        lastNanos = now;
        if (network == null || network.statusLine().startsWith("HOST")) world.update(dt);
        repaint();
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        AffineTransform old = g2.getTransform();
        g2.scale(zoom, zoom);
        world.draw(g2);
        g2.setTransform(old);
        drawHud(g2);
        buildMenu.draw(g2);
        g2.dispose();
    }

    private void drawHud(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,175));
        g2.fillRoundRect(12, 12, 980, 92, 14, 14);
        g2.setColor(Color.WHITE);
        g2.drawString("StarChem | " + world.localPlayerName + " | Selected: " + world.selectedCount(), 28, 36);
        g2.setColor(new Color(210,230,245));
        g2.drawString(world.status, 28, 58);
        g2.drawString(network == null ? "Solo" : network.statusLine(), 28, 80);
    }

    private Point2D screenToWorld(Point p) { return new Point2D.Double(p.x / zoom, p.y / zoom); }

    @Override public void mousePressed(MouseEvent e) {
        requestFocusInWindow();
        if (buildMenu.click(e.getX(), e.getY())) return;
        Point2D p = screenToWorld(e.getPoint());
        if (SwingUtilities.isLeftMouseButton(e)) clickLeft(e, p);
        else if (SwingUtilities.isRightMouseButton(e)) clickRight(p);
    }

    private void clickLeft(MouseEvent e, Point2D p) {
        Base base = world.baseAt(p.getX(), p.getY());
        Unit unit = world.unitAt(p.getX(), p.getY());
        if (base != null) buildMenu.showForBase(world, network, base, e.getX(), e.getY());
        else if (unit != null && !unit.basePackageType.isBlank()) buildMenu.showForUnit(world, network, unit, e.getX(), e.getY());
        else world.selectAt(p.getX(), p.getY());
    }

    private void clickRight(Point2D p) {
        ResourceNode node = world.resourceAt(p.getX(), p.getY());
        if (node != null) {
            world.autoHarvestSelected(node);
            if (network != null) for (Unit u : world.selectedUnits()) if (u.automationResourceId == node.id) network.work(new HarvestCommand(u.playerId, u.unitId, node.id));
        } else {
            world.moveSelected(p.getX(), p.getY());
            if (network != null) for (Unit u : world.selectedUnits()) network.move(new MoveCommand(u.playerId, u.unitId, u.targetX, u.targetY));
        }
    }

    @Override public void keyPressed(KeyEvent e) { if (e.getKeyCode() == KeyEvent.VK_ESCAPE) owner.showLobby("Returned to lobby."); }
    @Override public void keyTyped(KeyEvent e) { }
    @Override public void keyReleased(KeyEvent e) { }
    @Override public void mouseClicked(MouseEvent e) { }
    @Override public void mouseReleased(MouseEvent e) { }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { }
}
