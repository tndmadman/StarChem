package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * StarChem: tiny Java 2D top-down RTS starter with direct UDP P2P multiplayer.
 *
 * Controls:
 *   WASD / Arrow keys: pan camera
 *   Mouse wheel: zoom
 *   Left click: select one unit
 *   Left drag: box select
 *   Right click: move selected units
 */
public final class RtsGame {
    public static void main(String[] args) {
        Config config = Config.parse(args);

        SwingUtilities.invokeLater(() -> {
            World world = new World(config.localPlayerId, config.hostMode);
            PeerNetwork network = null;

            try {
                network = PeerNetwork.start(config, world);
            } catch (IOException e) {
                System.err.println("Network disabled: " + e.getMessage());
            }

            JFrame frame = new JFrame("StarChem RTS - " + config.localPlayerId);
            GamePanel panel = new GamePanel(world, network);
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setContentPane(panel);
            frame.setSize(1280, 800);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            panel.start();
        });
    }

    static final class Config {
        final String localPlayerId;
        final boolean hostMode;
        final int localPort;
        final InetSocketAddress peerAddress;

        private Config(String localPlayerId, boolean hostMode, int localPort, InetSocketAddress peerAddress) {
            this.localPlayerId = localPlayerId;
            this.hostMode = hostMode;
            this.localPort = localPort;
            this.peerAddress = peerAddress;
        }

        static Config parse(String[] args) {
            String id = "P" + Integer.toHexString(new SecureRandom().nextInt(0xFFFF)).toUpperCase(Locale.ROOT);
            boolean host = false;
            int localPort = 0;
            InetSocketAddress peer = null;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--id" -> id = require(args, ++i, "--id needs a value");
                    case "--host" -> {
                        host = true;
                        localPort = Integer.parseInt(require(args, ++i, "--host needs a port"));
                    }
                    case "--join" -> {
                        host = false;
                        String hostName = require(args, ++i, "--join needs host ip");
                        int peerPort = Integer.parseInt(require(args, ++i, "--join needs peer port"));
                        peer = new InetSocketAddress(hostName, peerPort);
                        localPort = peerPort + 1 + new SecureRandom().nextInt(1000);
                    }
                    case "--local-port" -> localPort = Integer.parseInt(require(args, ++i, "--local-port needs a port"));
                    default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
                }
            }

            return new Config(id, host, localPort, peer);
        }

        private static String require(String[] args, int index, String message) {
            if (index >= args.length) {
                throw new IllegalArgumentException(message);
            }
            return args[index];
        }
    }

    static final class GamePanel extends JPanel implements MouseListener, MouseMotionListener, MouseWheelListener, KeyListener {
        private final World world;
        private final PeerNetwork network;
        private final Set<Integer> keys = new HashSet<>();
        private final javax.swing.Timer timer;
        private double cameraX = 0;
        private double cameraY = 0;
        private double zoom = 1.0;
        private Point dragStart;
        private Point dragNow;
        private long lastNanos = System.nanoTime();

        GamePanel(World world, PeerNetwork network) {
            this.world = world;
            this.network = network;
            setBackground(new Color(8, 12, 18));
            setFocusable(true);
            addMouseListener(this);
            addMouseMotionListener(this);
            addMouseWheelListener(this);
            addKeyListener(this);
            timer = new javax.swing.Timer(16, e -> tick());
        }

        void start() {
            requestFocusInWindow();
            timer.start();
        }

        private void tick() {
            long now = System.nanoTime();
            double dt = Math.min(0.05, (now - lastNanos) / 1_000_000_000.0);
            lastNanos = now;

            handleCamera(dt);
            if (network != null) {
                network.drainMessages();
            }
            world.update(dt);
            repaint();
        }

        private void handleCamera(double dt) {
            double speed = 800 / zoom;
            if (keys.contains(KeyEvent.VK_W) || keys.contains(KeyEvent.VK_UP)) cameraY -= speed * dt;
            if (keys.contains(KeyEvent.VK_S) || keys.contains(KeyEvent.VK_DOWN)) cameraY += speed * dt;
            if (keys.contains(KeyEvent.VK_A) || keys.contains(KeyEvent.VK_LEFT)) cameraX -= speed * dt;
            if (keys.contains(KeyEvent.VK_D) || keys.contains(KeyEvent.VK_RIGHT)) cameraX += speed * dt;
            cameraX = clamp(cameraX, -200, world.width - 200);
            cameraY = clamp(cameraY, -200, world.height - 200);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            AffineTransform old = g2.getTransform();
            g2.scale(zoom, zoom);
            g2.translate(-cameraX, -cameraY);

            drawMap(g2);
            world.draw(g2);
            drawSelectionBox(g2);

            g2.setTransform(old);
            drawHud(g2);
            g2.dispose();
        }

        private void drawMap(Graphics2D g2) {
            g2.setColor(new Color(9, 15, 24));
            g2.fillRect(0, 0, world.width, world.height);

            g2.setColor(new Color(22, 33, 48));
            for (int x = 0; x <= world.width; x += 80) {
                g2.drawLine(x, 0, x, world.height);
            }
            for (int y = 0; y <= world.height; y += 80) {
                g2.drawLine(0, y, world.width, y);
            }

            g2.setColor(new Color(90, 135, 165));
            for (ResourceNode node : world.resources) {
                Polygon crystal = new Polygon();
                crystal.addPoint((int) node.x, (int) node.y - 26);
                crystal.addPoint((int) node.x + 22, (int) node.y);
                crystal.addPoint((int) node.x, (int) node.y + 26);
                crystal.addPoint((int) node.x - 22, (int) node.y);
                g2.fillPolygon(crystal);
                g2.setColor(new Color(150, 220, 255));
                g2.drawPolygon(crystal);
                g2.setColor(new Color(90, 135, 165));
            }
        }

        private void drawSelectionBox(Graphics2D g2) {
            if (dragStart == null || dragNow == null) {
                return;
            }
            Rectangle2D box = screenRectToWorldRect(dragStart, dragNow);
            g2.setColor(new Color(80, 170, 255, 60));
            g2.fill(box);
            g2.setColor(new Color(120, 205, 255));
            g2.draw(box);
        }

        private void drawHud(Graphics2D g2) {
            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRoundRect(12, 12, 570, 82, 14, 14);
            g2.setColor(Color.WHITE);
            g2.drawString("StarChem | Player: " + world.localPlayerId + " | Selected: " + world.selectedCount(), 28, 36);
            g2.drawString("WASD pan | Wheel zoom | Left select/drag | Right move", 28, 58);
            g2.drawString(network == null ? "Network: offline" : network.statusLine(), 28, 80);
        }

        private Point2D screenToWorld(Point p) {
            return new Point2D.Double(p.x / zoom + cameraX, p.y / zoom + cameraY);
        }

        private Rectangle2D screenRectToWorldRect(Point a, Point b) {
            Point2D aw = screenToWorld(a);
            Point2D bw = screenToWorld(b);
            double x = Math.min(aw.getX(), bw.getX());
            double y = Math.min(aw.getY(), bw.getY());
            double w = Math.abs(aw.getX() - bw.getX());
            double h = Math.abs(aw.getY() - bw.getY());
            return new Rectangle2D.Double(x, y, w, h);
        }

        @Override public void mouseClicked(MouseEvent e) { }

        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            if (SwingUtilities.isLeftMouseButton(e)) {
                dragStart = e.getPoint();
                dragNow = e.getPoint();
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e) && dragStart != null) {
                Rectangle2D box = screenRectToWorldRect(dragStart, e.getPoint());
                if (box.getWidth() < 6 && box.getHeight() < 6) {
                    Point2D p = screenToWorld(e.getPoint());
                    world.selectSingle(p.getX(), p.getY());
                } else {
                    world.selectBox(box);
                }
                dragStart = null;
                dragNow = null;
            }

            if (SwingUtilities.isRightMouseButton(e)) {
                Point2D p = screenToWorld(e.getPoint());
                List<MoveCommand> commands = world.issueMoveSelected(p.getX(), p.getY());
                if (network != null) {
                    for (MoveCommand command : commands) {
                        network.sendMove(command);
                    }
                }
            }
        }

        @Override public void mouseEntered(MouseEvent e) { }
        @Override public void mouseExited(MouseEvent e) { }
        @Override public void mouseDragged(MouseEvent e) { dragNow = e.getPoint(); }
        @Override public void mouseMoved(MouseEvent e) { }
        @Override public void mouseWheelMoved(MouseWheelEvent e) { zoom = clamp(zoom - e.getPreciseWheelRotation() * 0.08, 0.45, 2.2); }
        @Override public void keyTyped(KeyEvent e) { }
        @Override public void keyPressed(KeyEvent e) { keys.add(e.getKeyCode()); }
        @Override public void keyReleased(KeyEvent e) { keys.remove(e.getKeyCode()); }
    }

    static final class World {
        final int width = 2200;
        final int height = 1400;
        final String localPlayerId;
        final List<Unit> units = new ArrayList<>();
        final List<ResourceNode> resources = new ArrayList<>();

        World(String localPlayerId, boolean hostMode) {
            this.localPlayerId = localPlayerId;
            resources.add(new ResourceNode(620, 370));
            resources.add(new ResourceNode(1080, 720));
            resources.add(new ResourceNode(1640, 1030));
            spawnPlayer(localPlayerId, hostMode ? 220 : 1840, hostMode ? 260 : 1020, true);
        }

        synchronized void spawnPlayer(String playerId, double startX, double startY, boolean local) {
            if (hasPlayer(playerId)) {
                return;
            }

            Color color = local ? new Color(80, 190, 255) : new Color(255, 95, 85);
            for (int i = 0; i < 6; i++) {
                int unitId = i + 1;
                double x = startX + (i % 3) * 46;
                double y = startY + (i / 3) * 46;
                units.add(new Unit(playerId, unitId, x, y, color, local));
            }
        }

        synchronized boolean hasPlayer(String playerId) {
            return units.stream().anyMatch(u -> u.playerId.equals(playerId));
        }

        synchronized void update(double dt) {
            for (Unit u : units) {
                u.update(dt, width, height);
            }
        }

        synchronized void draw(Graphics2D g2) {
            for (Unit u : units) {
                u.draw(g2);
            }
        }

        synchronized int selectedCount() {
            int count = 0;
            for (Unit u : units) {
                if (u.selected) count++;
            }
            return count;
        }

        synchronized void selectSingle(double x, double y) {
            Unit best = null;
            double bestDist = Double.MAX_VALUE;

            for (Unit u : units) {
                if (!u.local) continue;
                double d = distance(x, y, u.x, u.y);
                if (d < 28 && d < bestDist) {
                    bestDist = d;
                    best = u;
                }
            }

            for (Unit u : units) {
                u.selected = false;
            }
            if (best != null) {
                best.selected = true;
            }
        }

        synchronized void selectBox(Rectangle2D box) {
            for (Unit u : units) {
                u.selected = u.local && box.contains(u.x, u.y);
            }
        }

        synchronized List<MoveCommand> issueMoveSelected(double x, double y) {
            List<Unit> selected = units.stream().filter(u -> u.local && u.selected).toList();
            List<MoveCommand> commands = new ArrayList<>();
            int count = selected.size();
            if (count == 0) {
                return commands;
            }

            double spacing = 42;
            int columns = (int) Math.ceil(Math.sqrt(count));
            for (int i = 0; i < count; i++) {
                Unit u = selected.get(i);
                int col = i % columns;
                int row = i / columns;
                double targetX = x + (col - columns / 2.0) * spacing;
                double targetY = y + row * spacing;
                u.moveTo(targetX, targetY);
                commands.add(new MoveCommand(localPlayerId, u.unitId, targetX, targetY));
            }
            return commands;
        }

        synchronized void applyRemoteMove(MoveCommand command) {
            for (Unit u : units) {
                if (u.playerId.equals(command.playerId) && u.unitId == command.unitId) {
                    u.moveTo(command.x, command.y);
                    return;
                }
            }
        }

        synchronized void ensureRemotePlayer(String playerId) {
            if (!playerId.equals(localPlayerId) && !hasPlayer(playerId)) {
                spawnPlayer(playerId, 1840, 1020, false);
            }
        }
    }

    static final class Unit {
        final String playerId;
        final int unitId;
        double x;
        double y;
        double targetX;
        double targetY;
        final Color color;
        final boolean local;
        boolean selected;
        double hp = 100;

        Unit(String playerId, int unitId, double x, double y, Color color, boolean local) {
            this.playerId = playerId;
            this.unitId = unitId;
            this.x = x;
            this.y = y;
            this.targetX = x;
            this.targetY = y;
            this.color = color;
            this.local = local;
        }

        void moveTo(double x, double y) {
            this.targetX = x;
            this.targetY = y;
        }

        void update(double dt, int mapW, int mapH) {
            double dx = targetX - x;
            double dy = targetY - y;
            double distance = Math.hypot(dx, dy);
            if (distance > 2) {
                double speed = 185;
                double step = Math.min(distance, speed * dt);
                x += dx / distance * step;
                y += dy / distance * step;
            }
            x = clamp(x, 0, mapW);
            y = clamp(y, 0, mapH);
        }

        void draw(Graphics2D g2) {
            int r = 16;
            Polygon ship = new Polygon();
            ship.addPoint((int) x, (int) y - r);
            ship.addPoint((int) x + r, (int) y + r);
            ship.addPoint((int) x, (int) y + 8);
            ship.addPoint((int) x - r, (int) y + r);

            g2.setColor(new Color(0, 0, 0, 130));
            g2.fillOval((int) x - r - 3, (int) y - r + 8, r * 2 + 6, r * 2);
            g2.setColor(color);
            g2.fillPolygon(ship);
            g2.setColor(Color.BLACK);
            g2.drawPolygon(ship);

            g2.setColor(new Color(20, 20, 20));
            g2.fillRect((int) x - 18, (int) y - 30, 36, 5);
            g2.setColor(new Color(80, 230, 90));
            g2.fillRect((int) x - 18, (int) y - 30, (int) (36 * hp / 100.0), 5);

            if (selected) {
                g2.setColor(new Color(255, 245, 120));
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval((int) x - 24, (int) y - 24, 48, 48);
            }
        }
    }

    record ResourceNode(double x, double y) { }
    record MoveCommand(String playerId, int unitId, double x, double y) { }

    static final class PeerNetwork {
        private final Config config;
        private final World world;
        private final DatagramSocket socket;
        private final ConcurrentLinkedQueue<String> inbox = new ConcurrentLinkedQueue<>();
        private volatile InetSocketAddress peerAddress;
        private volatile boolean running = true;
        private long lastHello = 0;
        private long lastPacketAt = 0;

        private PeerNetwork(Config config, World world, DatagramSocket socket) {
            this.config = config;
            this.world = world;
            this.socket = socket;
            this.peerAddress = config.peerAddress;
        }

        static PeerNetwork start(Config config, World world) throws IOException {
            if (config.localPort == 0 && config.peerAddress == null) {
                return null;
            }

            DatagramSocket socket = config.localPort == 0 ? new DatagramSocket() : new DatagramSocket(config.localPort);
            socket.setSoTimeout(300);

            PeerNetwork network = new PeerNetwork(config, world, socket);
            Thread thread = new Thread(network::listenLoop, "starchem-p2p-udp-listener");
            thread.setDaemon(true);
            thread.start();
            network.sendHello();
            return network;
        }

        String statusLine() {
            String peer = peerAddress == null ? "waiting for peer" : peerAddress.toString();
            String age = lastPacketAt == 0 ? "no packets yet" : (System.currentTimeMillis() - lastPacketAt) + "ms since packet";
            return "UDP " + socket.getLocalPort() + " -> " + peer + " | " + age;
        }

        void drainMessages() {
            long now = System.currentTimeMillis();
            if (now - lastHello > 1500) {
                sendHello();
                lastHello = now;
            }

            String msg;
            while ((msg = inbox.poll()) != null) {
                handleMessage(msg);
            }
        }

        void sendMove(MoveCommand command) {
            send("MOVE|" + command.playerId() + "|" + command.unitId() + "|" + command.x() + "|" + command.y());
        }

        private void sendHello() {
            send("HELLO|" + config.localPlayerId);
        }

        private void send(String message) {
            InetSocketAddress target = peerAddress;
            if (target == null) {
                return;
            }

            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, target.getAddress(), target.getPort());
            try {
                socket.send(packet);
            } catch (IOException e) {
                System.err.println("send failed: " + e.getMessage());
            }
        }

        private void listenLoop() {
            byte[] buffer = new byte[2048];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    peerAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
                    lastPacketAt = System.currentTimeMillis();
                    inbox.add(message);
                } catch (SocketTimeoutException ignored) {
                    // keep daemon alive
                } catch (IOException e) {
                    if (running) {
                        System.err.println("listen failed: " + e.getMessage());
                    }
                }
            }
        }

        private void handleMessage(String message) {
            String[] parts = message.split("\\|");
            if (parts.length == 0) {
                return;
            }

            try {
                switch (parts[0]) {
                    case "HELLO" -> {
                        if (parts.length >= 2) {
                            world.ensureRemotePlayer(parts[1]);
                            send("HELLO_ACK|" + config.localPlayerId);
                        }
                    }
                    case "HELLO_ACK" -> {
                        if (parts.length >= 2) {
                            world.ensureRemotePlayer(parts[1]);
                        }
                    }
                    case "MOVE" -> {
                        if (parts.length >= 5) {
                            MoveCommand command = new MoveCommand(
                                    parts[1],
                                    Integer.parseInt(parts[2]),
                                    Double.parseDouble(parts[3]),
                                    Double.parseDouble(parts[4])
                            );
                            if (!command.playerId().equals(config.localPlayerId)) {
                                world.ensureRemotePlayer(command.playerId());
                                world.applyRemoteMove(command);
                            }
                        }
                    }
                    default -> System.err.println("unknown packet: " + message);
                }
            } catch (RuntimeException e) {
                System.err.println("bad packet: " + message + " | " + e.getMessage());
            }
        }
    }

    static double distance(double ax, double ay, double bx, double by) {
        return Math.hypot(ax - bx, ay - by);
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
