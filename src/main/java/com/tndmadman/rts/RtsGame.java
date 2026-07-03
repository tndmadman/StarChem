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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * StarChem: Java 2D top-down RTS prototype.
 *
 * Networking is host-authoritative:
 * - Host owns the player list and creates/removes each player's unit group.
 * - Host assigns player IDs, unique display names, and unique colors.
 * - Clients send join/heartbeat/move/leave packets.
 * - Important UDP packets can be wrapped as reliable messages with ACK/retry.
 * - Host sends fast snapshots plus periodic reliable full snapshots to reduce desync.
 */
public final class RtsGame {
    public static void main(String[] args) {
        Config config = Config.parse(args);
        SwingUtilities.invokeLater(() -> {
            if (config.showLobby) {
                new LobbyFrame().setVisible(true);
            } else {
                startGame(config);
            }
        });
    }

    private static void startGame(Config config) {
        World world = new World();
        PeerNetwork network = null;

        try {
            network = PeerNetwork.start(config, world);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null,
                    "Could not start network: " + e.getMessage(),
                    "StarChem Network Error",
                    JOptionPane.ERROR_MESSAGE);
            if (!config.soloMode) {
                return;
            }
        }

        if (network == null) {
            world.startSolo(config.localPlayerName);
        }

        PeerNetwork finalNetwork = network;
        JFrame frame = new JFrame("StarChem RTS - " + config.localPlayerName);
        GamePanel panel = new GamePanel(world, finalNetwork);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (finalNetwork != null) {
                    finalNetwork.shutdown();
                }
            }
        });
        frame.setContentPane(panel);
        frame.setSize(1280, 800);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        panel.start();
    }

    static final class LobbyFrame extends JFrame {
        private final JTextField nameField = new JTextField(defaultName(), 18);
        private final JTextField hostPortField = new JTextField("50000", 8);
        private final JTextField joinHostField = new JTextField("127.0.0.1", 14);
        private final JTextField joinPortField = new JTextField("50000", 8);
        private final JLabel statusLabel = new JLabel("Choose Solo, Host, or Join.");

        LobbyFrame() {
            super("StarChem Lobby");
            setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            setSize(460, 330);
            setLocationRelativeTo(null);
            setResizable(false);

            JPanel root = new JPanel(new BorderLayout(12, 12));
            root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
            root.setBackground(new Color(12, 18, 28));

            JLabel title = new JLabel("StarChem Lobby");
            title.setForeground(Color.WHITE);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
            root.add(title, BorderLayout.NORTH);

            JPanel form = new JPanel(new GridBagLayout());
            form.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(6, 6, 6, 6);
            c.fill = GridBagConstraints.HORIZONTAL;

            addRow(form, c, 0, "Player name", nameField);
            addRow(form, c, 1, "Host port", hostPortField);
            addRow(form, c, 2, "Join IP", joinHostField);
            addRow(form, c, 3, "Join port", joinPortField);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));
            buttons.setOpaque(false);
            JButton solo = new JButton("Solo");
            JButton host = new JButton("Host Game");
            JButton join = new JButton("Join Game");
            buttons.add(solo);
            buttons.add(host);
            buttons.add(join);

            c.gridx = 0;
            c.gridy = 4;
            c.gridwidth = 2;
            form.add(buttons, c);

            statusLabel.setForeground(new Color(190, 210, 230));
            c.gridy = 5;
            form.add(statusLabel, c);

            root.add(form, BorderLayout.CENTER);
            setContentPane(root);

            solo.addActionListener(e -> launchSolo());
            host.addActionListener(e -> launchHost());
            join.addActionListener(e -> launchJoin());
        }

        private void addRow(JPanel form, GridBagConstraints c, int row, String label, JComponent field) {
            JLabel jLabel = new JLabel(label);
            jLabel.setForeground(Color.WHITE);
            c.gridwidth = 1;
            c.weightx = 0;
            c.gridx = 0;
            c.gridy = row;
            form.add(jLabel, c);
            c.weightx = 1;
            c.gridx = 1;
            form.add(field, c);
        }

        private void launchSolo() {
            dispose();
            startGame(Config.solo(nameField.getText()));
        }

        private void launchHost() {
            try {
                int port = Integer.parseInt(hostPortField.getText().trim());
                dispose();
                startGame(Config.host(nameField.getText(), port));
            } catch (NumberFormatException ex) {
                statusLabel.setText("Host port must be a number.");
            }
        }

        private void launchJoin() {
            try {
                int port = Integer.parseInt(joinPortField.getText().trim());
                String host = joinHostField.getText().trim();
                if (host.isBlank()) {
                    statusLabel.setText("Join IP cannot be blank.");
                    return;
                }
                dispose();
                startGame(Config.join(nameField.getText(), host, port));
            } catch (NumberFormatException ex) {
                statusLabel.setText("Join port must be a number.");
            }
        }
    }

    static final class Config {
        final String localPlayerName;
        final boolean hostMode;
        final boolean soloMode;
        final boolean showLobby;
        final int localPort;
        final InetSocketAddress serverAddress;

        private Config(String localPlayerName, boolean hostMode, boolean soloMode, boolean showLobby, int localPort, InetSocketAddress serverAddress) {
            this.localPlayerName = localPlayerName;
            this.hostMode = hostMode;
            this.soloMode = soloMode;
            this.showLobby = showLobby;
            this.localPort = localPort;
            this.serverAddress = serverAddress;
        }

        static Config parse(String[] args) {
            if (args.length == 0) {
                return new Config(defaultName(), false, false, true, 0, null);
            }

            String name = defaultName();
            boolean host = false;
            boolean solo = false;
            int localPort = 0;
            InetSocketAddress server = null;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--name", "--id" -> name = require(args, ++i, args[i - 1] + " needs a value");
                    case "--solo" -> solo = true;
                    case "--host" -> {
                        host = true;
                        localPort = Integer.parseInt(require(args, ++i, "--host needs a port"));
                    }
                    case "--join" -> {
                        host = false;
                        String hostName = require(args, ++i, "--join needs host ip");
                        int peerPort = Integer.parseInt(require(args, ++i, "--join needs peer port"));
                        server = new InetSocketAddress(hostName, peerPort);
                    }
                    case "--local-port" -> localPort = Integer.parseInt(require(args, ++i, "--local-port needs a port"));
                    default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
                }
            }

            if (!host && server == null && !solo) {
                return new Config(cleanName(name), false, false, true, 0, null);
            }
            return new Config(cleanName(name), host, solo, false, localPort, server);
        }

        static Config solo(String name) {
            return new Config(cleanName(name), false, true, false, 0, null);
        }

        static Config host(String name, int port) {
            return new Config(cleanName(name), true, false, false, port, null);
        }

        static Config join(String name, String host, int port) {
            return new Config(cleanName(name), false, false, false, 0, new InetSocketAddress(host, port));
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
            List<PlayerInfo> players = world.playersSnapshot();
            int height = 92 + players.size() * 18;
            g2.setColor(new Color(0, 0, 0, 175));
            g2.fillRoundRect(12, 12, 720, height, 14, 14);
            g2.setColor(Color.WHITE);
            g2.drawString("StarChem | Local: " + world.localPlayerLabel() + " | Selected: " + world.selectedCount(), 28, 36);
            g2.drawString("WASD pan | Wheel zoom | Left select/drag | Right move", 28, 58);
            g2.drawString(network == null ? "Network: solo/offline" : network.statusLine(), 28, 80);

            int y = 104;
            for (PlayerInfo player : players) {
                g2.setColor(new Color(player.rgb));
                g2.fillRect(28, y - 11, 12, 12);
                g2.setColor(Color.WHITE);
                String suffix = player.local ? "  (you)" : "";
                g2.drawString(player.name + " - " + world.unitCountFor(player.id) + " ships" + suffix, 48, y);
                y += 18;
            }
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
        final List<ResourceNode> resources = new ArrayList<>();
        private final Map<String, PlayerInfo> players = new LinkedHashMap<>();
        private final Map<String, Unit> units = new LinkedHashMap<>();
        private String localPlayerId = "";
        private String localPlayerName = "Waiting";

        World() {
            resources.add(new ResourceNode(620, 370));
            resources.add(new ResourceNode(1080, 720));
            resources.add(new ResourceNode(1640, 1030));
        }

        synchronized void startSolo(String requestedName) {
            addPlayerWithGroup("SOLO", uniqueName(requestedName), PALETTE[0], true);
        }

        synchronized void addPlayerWithGroup(String playerId, String name, int rgb, boolean local) {
            int index = players.size();
            PlayerInfo info = new PlayerInfo(playerId, name, rgb, local);
            players.put(playerId, info);
            if (local) {
                localPlayerId = playerId;
                localPlayerName = name;
            }
            spawnGroup(playerId, index);
        }

        synchronized void addOrUpdatePlayer(String playerId, String name, int rgb, boolean local) {
            players.put(playerId, new PlayerInfo(playerId, name, rgb, local));
            if (local) {
                localPlayerId = playerId;
                localPlayerName = name;
            }
        }

        synchronized void removePlayer(String playerId) {
            players.remove(playerId);
            units.values().removeIf(u -> u.playerId.equals(playerId));
            if (playerId.equals(localPlayerId)) {
                localPlayerId = "";
                localPlayerName = "Disconnected";
            }
        }

        private void spawnGroup(String playerId, int spawnIndex) {
            PlayerInfo player = players.get(playerId);
            if (player == null) {
                return;
            }
            Point2D start = spawnPoint(spawnIndex);
            for (int i = 0; i < 6; i++) {
                int unitId = i + 1;
                double x = start.getX() + (i % 3) * 46;
                double y = start.getY() + (i / 3) * 46;
                Unit unit = new Unit(playerId, unitId, x, y, player.rgb);
                units.put(unit.key(), unit);
            }
        }

        synchronized String uniqueName(String requested) {
            String base = cleanName(requested);
            if (base.isBlank()) {
                base = "Player";
            }
            String candidate = base;
            int suffix = 2;
            Set<String> names = new HashSet<>();
            for (PlayerInfo p : players.values()) {
                names.add(p.name.toLowerCase(Locale.ROOT));
            }
            while (names.contains(candidate.toLowerCase(Locale.ROOT))) {
                candidate = base + " " + suffix++;
            }
            return candidate;
        }

        synchronized void update(double dt) {
            for (Unit u : units.values()) {
                u.update(dt, width, height);
            }
        }

        synchronized void draw(Graphics2D g2) {
            for (Unit u : units.values()) {
                PlayerInfo player = players.get(u.playerId);
                String name = player == null ? u.playerId : player.name;
                u.draw(g2, name);
            }
        }

        synchronized int selectedCount() {
            int count = 0;
            for (Unit u : units.values()) {
                if (u.selected) count++;
            }
            return count;
        }

        synchronized String localPlayerLabel() {
            return localPlayerName;
        }

        synchronized List<PlayerInfo> playersSnapshot() {
            return new ArrayList<>(players.values());
        }

        synchronized int unitCountFor(String playerId) {
            int count = 0;
            for (Unit u : units.values()) {
                if (u.playerId.equals(playerId)) {
                    count++;
                }
            }
            return count;
        }

        synchronized void selectSingle(double x, double y) {
            Unit best = null;
            double bestDist = Double.MAX_VALUE;

            for (Unit u : units.values()) {
                if (!u.playerId.equals(localPlayerId)) continue;
                double d = distance(x, y, u.x, u.y);
                if (d < 28 && d < bestDist) {
                    bestDist = d;
                    best = u;
                }
            }

            for (Unit u : units.values()) {
                u.selected = false;
            }
            if (best != null) {
                best.selected = true;
            }
        }

        synchronized void selectBox(Rectangle2D box) {
            for (Unit u : units.values()) {
                u.selected = u.playerId.equals(localPlayerId) && box.contains(u.x, u.y);
            }
        }

        synchronized List<MoveCommand> issueMoveSelected(double x, double y) {
            List<Unit> selected = units.values().stream()
                    .filter(u -> u.playerId.equals(localPlayerId) && u.selected)
                    .toList();
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

        synchronized void applyAuthorizedMove(MoveCommand command) {
            Unit unit = units.get(Unit.key(command.playerId, command.unitId));
            if (unit != null) {
                unit.moveTo(command.x, command.y);
            }
        }

        synchronized Snapshot createSnapshot(long sequence) {
            List<PlayerInfo> playerCopies = new ArrayList<>(players.values());
            List<UnitState> unitCopies = new ArrayList<>();
            for (Unit u : units.values()) {
                unitCopies.add(new UnitState(u.playerId, u.unitId, u.x, u.y, u.targetX, u.targetY));
            }
            return new Snapshot(sequence, playerCopies, unitCopies);
        }

        synchronized void applySnapshot(Snapshot snapshot, String knownLocalPlayerId) {
            Set<String> playerIds = new LinkedHashSet<>();
            for (PlayerInfo p : snapshot.players) {
                boolean local = p.id.equals(knownLocalPlayerId);
                addOrUpdatePlayer(p.id, p.name, p.rgb, local);
                playerIds.add(p.id);
            }
            players.keySet().removeIf(id -> !playerIds.contains(id));

            Set<String> unitKeys = new LinkedHashSet<>();
            for (UnitState state : snapshot.units) {
                unitKeys.add(Unit.key(state.playerId, state.unitId));
                PlayerInfo player = players.get(state.playerId);
                int rgb = player == null ? Color.GRAY.getRGB() : player.rgb;
                Unit unit = units.get(Unit.key(state.playerId, state.unitId));
                if (unit == null) {
                    unit = new Unit(state.playerId, state.unitId, state.x, state.y, rgb);
                    units.put(unit.key(), unit);
                }
                unit.rgb = rgb;
                unit.applySnapshot(state);
            }
            units.keySet().removeIf(key -> !unitKeys.contains(key));
        }
    }

    static final class Unit {
        final String playerId;
        final int unitId;
        double x;
        double y;
        double targetX;
        double targetY;
        int rgb;
        boolean selected;
        double hp = 100;

        Unit(String playerId, int unitId, double x, double y, int rgb) {
            this.playerId = playerId;
            this.unitId = unitId;
            this.x = x;
            this.y = y;
            this.targetX = x;
            this.targetY = y;
            this.rgb = rgb;
        }

        static String key(String playerId, int unitId) {
            return playerId + ":" + unitId;
        }

        String key() {
            return key(playerId, unitId);
        }

        void moveTo(double x, double y) {
            this.targetX = x;
            this.targetY = y;
        }

        void applySnapshot(UnitState state) {
            double error = distance(x, y, state.x, state.y);
            if (error > 20) {
                x = state.x;
                y = state.y;
            } else {
                x = x * 0.65 + state.x * 0.35;
                y = y * 0.65 + state.y * 0.35;
            }
            targetX = state.targetX;
            targetY = state.targetY;
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

        void draw(Graphics2D g2, String playerName) {
            int r = 16;
            Polygon ship = new Polygon();
            ship.addPoint((int) x, (int) y - r);
            ship.addPoint((int) x + r, (int) y + r);
            ship.addPoint((int) x, (int) y + 8);
            ship.addPoint((int) x - r, (int) y + r);

            g2.setColor(new Color(0, 0, 0, 130));
            g2.fillOval((int) x - r - 3, (int) y - r + 8, r * 2 + 6, r * 2);
            g2.setColor(new Color(rgb));
            g2.fillPolygon(ship);
            g2.setColor(Color.BLACK);
            g2.drawPolygon(ship);

            g2.setColor(new Color(20, 20, 20));
            g2.fillRect((int) x - 18, (int) y - 30, 36, 5);
            g2.setColor(new Color(80, 230, 90));
            g2.fillRect((int) x - 18, (int) y - 30, (int) (36 * hp / 100.0), 5);

            g2.setColor(Color.WHITE);
            g2.drawString(playerName, (int) x - 22, (int) y - 38);

            if (selected) {
                g2.setColor(new Color(255, 245, 120));
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval((int) x - 24, (int) y - 24, 48, 48);
            }
        }
    }

    static final class PeerNetwork {
        private static final long HEARTBEAT_MS = 1_000;
        private static final long FAST_SNAPSHOT_MS = 250;
        private static final long RELIABLE_FULL_SNAPSHOT_MS = 2_000;
        private static final long CLIENT_TIMEOUT_MS = 10_000;
        private static final long RELIABLE_RESEND_MS = 450;
        private static final int MAX_RELIABLE_ATTEMPTS = 40;

        private final Config config;
        private final World world;
        private final DatagramSocket socket;
        private final ConcurrentLinkedQueue<InboundPacket> inbox = new ConcurrentLinkedQueue<>();
        private final Map<String, ServerPeer> serverPeers = new LinkedHashMap<>();
        private final Map<String, PendingReliable> pendingReliable = new LinkedHashMap<>();
        private final Set<String> deliveredReliable = new LinkedHashSet<>();
        private final String reliablePrefix = Integer.toHexString(new SecureRandom().nextInt()).replace("-", "N");
        private volatile boolean running = true;
        private volatile boolean joined = false;
        private String localPlayerId = "";
        private long nextPlayerNumber = 1;
        private long nextReliableId = 1;
        private long lastJoinReliableAt = 0;
        private long lastPing = 0;
        private long lastFastSnapshot = 0;
        private long lastReliableFullSnapshot = 0;
        private long sequence = 1;
        private long lastPacketAt = 0;

        private PeerNetwork(Config config, World world, DatagramSocket socket) {
            this.config = config;
            this.world = world;
            this.socket = socket;
        }

        static PeerNetwork start(Config config, World world) throws IOException {
            if (!config.hostMode && config.serverAddress == null) {
                return null;
            }

            DatagramSocket socket = config.localPort == 0 ? new DatagramSocket() : new DatagramSocket(config.localPort);
            socket.setSoTimeout(300);
            PeerNetwork network = new PeerNetwork(config, world, socket);

            if (config.hostMode) {
                network.localPlayerId = "HOST";
                world.addPlayerWithGroup("HOST", world.uniqueName(config.localPlayerName), PALETTE[0], true);
                network.joined = true;
                System.out.println("Hosting StarChem on UDP " + socket.getLocalPort());
            } else {
                System.out.println("Joining StarChem server " + config.serverAddress + " from UDP " + socket.getLocalPort());
            }

            Thread thread = new Thread(network::listenLoop, "starchem-udp-listener");
            thread.setDaemon(true);
            thread.start();
            return network;
        }

        String statusLine() {
            int pending = pendingReliable.size();
            if (config.hostMode) {
                return "HOST UDP " + socket.getLocalPort() + " | players: " + world.playersSnapshot().size() + " | clients: " + serverPeers.size() + " | reliable pending: " + pending;
            }
            String age = lastPacketAt == 0 ? "no server snapshot yet" : (System.currentTimeMillis() - lastPacketAt) + "ms since server";
            String id = joined ? localPlayerId : "joining...";
            return "CLIENT UDP " + socket.getLocalPort() + " -> " + config.serverAddress + " | " + id + " | " + age + " | reliable pending: " + pending;
        }

        void drainMessages() {
            long now = System.currentTimeMillis();
            InboundPacket packet;
            while ((packet = inbox.poll()) != null) {
                handleInbound(packet);
            }

            resendPendingReliable(now);

            if (config.hostMode) {
                checkClientTimeouts(now);
                if (now - lastFastSnapshot >= FAST_SNAPSHOT_MS) {
                    broadcastSnapshot(false);
                    lastFastSnapshot = now;
                }
                if (now - lastReliableFullSnapshot >= RELIABLE_FULL_SNAPSHOT_MS) {
                    broadcastSnapshot(true);
                    lastReliableFullSnapshot = now;
                }
            } else {
                if (!joined && now - lastJoinReliableAt >= HEARTBEAT_MS) {
                    if (!hasPendingPayloadPrefix("JOIN|")) {
                        sendReliableToServer("JOIN|" + config.localPlayerName);
                    }
                    lastJoinReliableAt = now;
                } else if (joined && now - lastPing >= HEARTBEAT_MS) {
                    sendToServer("PING|" + localPlayerId);
                    lastPing = now;
                }
            }
        }

        void sendMove(MoveCommand command) {
            if (command.playerId == null || command.playerId.isBlank()) {
                return;
            }
            String msg = "MOVE|" + command.playerId + "|" + command.unitId + "|" + round(command.x) + "|" + round(command.y);
            if (config.hostMode) {
                world.applyAuthorizedMove(command);
                broadcastSnapshot(false);
            } else {
                sendToServer(msg);
            }
        }

        void shutdown() {
            running = false;
            if (!config.hostMode && joined) {
                String leave = "LEAVE|" + localPlayerId;
                for (int i = 0; i < 4; i++) {
                    sendReliableToServer(leave);
                    resendPendingReliable(0);
                    sleepQuietly(50);
                }
            } else if (config.hostMode) {
                broadcastReliable("SERVER_CLOSING");
            }
            socket.close();
        }

        private void handleInbound(InboundPacket packet) {
            lastPacketAt = System.currentTimeMillis();
            String message = packet.message;
            if (message.startsWith("ACK|")) {
                pendingReliable.remove(message.substring(4));
                return;
            }
            if (message.startsWith("REL|")) {
                String[] parts = message.split("\\|", 3);
                if (parts.length < 3) {
                    return;
                }
                String id = parts[1];
                send("ACK|" + id, packet.address, packet.port);
                String deliveryKey = endpointKey(packet.address, packet.port) + "|" + id;
                if (deliveredReliable.contains(deliveryKey)) {
                    return;
                }
                rememberDelivered(deliveryKey);
                handlePayload(parts[2], packet);
                return;
            }
            handlePayload(message, packet);
        }

        private void handlePayload(String payload, InboundPacket packet) {
            if (config.hostMode) {
                handleHostPacket(payload, packet);
            } else {
                handleClientPacket(payload);
            }
        }

        private void handleHostPacket(String message, InboundPacket packet) {
            String[] parts = message.split("\\|");
            if (parts.length == 0) {
                return;
            }

            String endpoint = endpointKey(packet.address, packet.port);
            switch (parts[0]) {
                case "JOIN" -> {
                    ServerPeer existing = serverPeers.get(endpoint);
                    if (existing != null) {
                        existing.lastSeen = System.currentTimeMillis();
                        sendWelcome(existing, packet.address, packet.port);
                        sendSnapshot(packet.address, packet.port, true);
                        return;
                    }

                    String requestedName = parts.length >= 2 ? parts[1] : "Player";
                    String name = world.uniqueName(requestedName);
                    String playerId = "P" + nextPlayerNumber++;
                    int color = colorFor(world.playersSnapshot().size());
                    world.addPlayerWithGroup(playerId, name, color, false);

                    ServerPeer peer = new ServerPeer(playerId, packet.address, packet.port, System.currentTimeMillis());
                    serverPeers.put(endpoint, peer);

                    sendWelcome(peer, packet.address, packet.port);
                    broadcastSnapshot(true);
                    System.out.println(name + " joined as " + playerId + " from " + endpoint);
                }
                case "PING" -> {
                    ServerPeer peer = serverPeers.get(endpoint);
                    if (peer != null) {
                        peer.lastSeen = System.currentTimeMillis();
                    }
                }
                case "MOVE" -> {
                    if (parts.length < 5) {
                        return;
                    }
                    ServerPeer peer = serverPeers.get(endpoint);
                    if (peer == null || !peer.playerId.equals(parts[1])) {
                        return;
                    }
                    peer.lastSeen = System.currentTimeMillis();
                    MoveCommand command = new MoveCommand(parts[1], Integer.parseInt(parts[2]), Double.parseDouble(parts[3]), Double.parseDouble(parts[4]));
                    world.applyAuthorizedMove(command);
                    broadcastSnapshot(false);
                }
                case "LEAVE" -> removePeer(endpoint, true);
                default -> System.err.println("unknown host packet: " + message);
            }
        }

        private void handleClientPacket(String message) {
            String[] parts = message.split("\\|");
            if (parts.length == 0) {
                return;
            }
            switch (parts[0]) {
                case "WELCOME" -> {
                    if (parts.length >= 4) {
                        localPlayerId = parts[1];
                        String name = parts[2];
                        int rgb = Integer.parseInt(parts[3]);
                        world.addOrUpdatePlayer(localPlayerId, name, rgb, true);
                        joined = true;
                        dropPendingPayloadPrefix("JOIN|");
                    }
                }
                case "SNAPSHOT" -> {
                    Snapshot snapshot = decodeSnapshot(message);
                    world.applySnapshot(snapshot, localPlayerId);
                }
                case "REMOVE" -> {
                    if (parts.length >= 2) {
                        world.removePlayer(parts[1]);
                    }
                }
                case "SERVER_CLOSING" -> System.out.println("Server closed.");
                default -> System.err.println("unknown client packet: " + message);
            }
        }

        private void checkClientTimeouts(long now) {
            List<String> deadEndpoints = new ArrayList<>();
            for (Map.Entry<String, ServerPeer> entry : serverPeers.entrySet()) {
                if (now - entry.getValue().lastSeen > CLIENT_TIMEOUT_MS) {
                    deadEndpoints.add(entry.getKey());
                }
            }
            for (String endpoint : deadEndpoints) {
                removePeer(endpoint, true);
            }
        }

        private void removePeer(String endpoint, boolean announce) {
            ServerPeer peer = serverPeers.remove(endpoint);
            if (peer == null) {
                return;
            }
            world.removePlayer(peer.playerId);
            if (announce) {
                broadcastReliable("REMOVE|" + peer.playerId);
                broadcastSnapshot(true);
            }
            System.out.println(peer.playerId + " left.");
        }

        private void sendWelcome(ServerPeer peer, InetAddress address, int port) {
            PlayerInfo info = world.playersSnapshot().stream()
                    .filter(p -> p.id.equals(peer.playerId))
                    .findFirst()
                    .orElse(new PlayerInfo(peer.playerId, peer.playerId, Color.WHITE.getRGB(), false));
            sendReliable("WELCOME|" + info.id + "|" + info.name + "|" + info.rgb, address, port);
        }

        private void broadcastSnapshot(boolean reliable) {
            String encoded = encodeSnapshot(world.createSnapshot(sequence++));
            if (reliable) {
                broadcastReliable(encoded);
            } else {
                broadcast(encoded);
            }
        }

        private void sendSnapshot(InetAddress address, int port, boolean reliable) {
            String encoded = encodeSnapshot(world.createSnapshot(sequence++));
            if (reliable) {
                sendReliable(encoded, address, port);
            } else {
                send(encoded, address, port);
            }
        }

        private void broadcast(String message) {
            for (ServerPeer peer : serverPeers.values()) {
                send(message, peer.address, peer.port);
            }
        }

        private void broadcastReliable(String payload) {
            for (ServerPeer peer : serverPeers.values()) {
                sendReliable(payload, peer.address, peer.port);
            }
        }

        private void sendToServer(String message) {
            if (config.serverAddress == null) {
                return;
            }
            send(message, config.serverAddress.getAddress(), config.serverAddress.getPort());
        }

        private void sendReliableToServer(String payload) {
            if (config.serverAddress == null) {
                return;
            }
            sendReliable(payload, config.serverAddress.getAddress(), config.serverAddress.getPort());
        }

        private void sendReliable(String payload, InetAddress address, int port) {
            String id = reliablePrefix + "-" + nextReliableId++;
            PendingReliable pending = new PendingReliable(id, payload, address, port, 0, 0);
            pendingReliable.put(id, pending);
            sendReliableNow(pending);
        }

        private void sendReliableNow(PendingReliable pending) {
            send("REL|" + pending.id + "|" + pending.payload, pending.address, pending.port);
            pending.lastSent = System.currentTimeMillis();
            pending.attempts++;
        }

        private void resendPendingReliable(long now) {
            long current = now == 0 ? System.currentTimeMillis() : now;
            List<String> dead = new ArrayList<>();
            for (PendingReliable pending : pendingReliable.values()) {
                if (pending.attempts >= MAX_RELIABLE_ATTEMPTS) {
                    dead.add(pending.id);
                    continue;
                }
                if (current - pending.lastSent >= RELIABLE_RESEND_MS) {
                    sendReliableNow(pending);
                }
            }
            for (String id : dead) {
                pendingReliable.remove(id);
            }
        }

        private boolean hasPendingPayloadPrefix(String prefix) {
            for (PendingReliable pending : pendingReliable.values()) {
                if (pending.payload.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        private void dropPendingPayloadPrefix(String prefix) {
            pendingReliable.entrySet().removeIf(e -> e.getValue().payload.startsWith(prefix));
        }

        private void rememberDelivered(String deliveryKey) {
            deliveredReliable.add(deliveryKey);
            while (deliveredReliable.size() > 512) {
                String first = deliveredReliable.iterator().next();
                deliveredReliable.remove(first);
            }
        }

        private void send(String message, InetAddress address, int port) {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, port);
            try {
                socket.send(packet);
            } catch (IOException e) {
                if (running) {
                    System.err.println("send failed: " + e.getMessage());
                }
            }
        }

        private void listenLoop() {
            byte[] buffer = new byte[16_384];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    inbox.add(new InboundPacket(message, packet.getAddress(), packet.getPort()));
                } catch (SocketTimeoutException ignored) {
                    // keep daemon alive
                } catch (SocketException e) {
                    if (running) {
                        System.err.println("socket failed: " + e.getMessage());
                    }
                } catch (IOException e) {
                    if (running) {
                        System.err.println("listen failed: " + e.getMessage());
                    }
                }
            }
        }
    }

    record ResourceNode(double x, double y) { }
    record PlayerInfo(String id, String name, int rgb, boolean local) { }
    record UnitState(String playerId, int unitId, double x, double y, double targetX, double targetY) { }
    record MoveCommand(String playerId, int unitId, double x, double y) { }
    record Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units) { }
    record InboundPacket(String message, InetAddress address, int port) { }

    static final class ServerPeer {
        final String playerId;
        final InetAddress address;
        final int port;
        long lastSeen;

        ServerPeer(String playerId, InetAddress address, int port, long lastSeen) {
            this.playerId = playerId;
            this.address = address;
            this.port = port;
            this.lastSeen = lastSeen;
        }
    }

    static final class PendingReliable {
        final String id;
        final String payload;
        final InetAddress address;
        final int port;
        long lastSent;
        int attempts;

        PendingReliable(String id, String payload, InetAddress address, int port, long lastSent, int attempts) {
            this.id = id;
            this.payload = payload;
            this.address = address;
            this.port = port;
            this.lastSent = lastSent;
            this.attempts = attempts;
        }
    }

    private static final int[] PALETTE = {
            0x50BEFF, 0xFF5F55, 0x7DFF7A, 0xFFE066,
            0xC77DFF, 0xFF9F1C, 0x4DFFD2, 0xFF70A6,
            0xB8F35A, 0xA0C4FF, 0xFFD6A5, 0xCAFFBF
    };

    static int colorFor(int index) {
        if (index < PALETTE.length) {
            return PALETTE[index];
        }
        float hue = (index * 0.61803398875f) % 1.0f;
        return Color.HSBtoRGB(hue, 0.75f, 1.0f) & 0xFFFFFF;
    }

    static Point2D spawnPoint(int index) {
        double[][] points = {
                {220, 260}, {1840, 1020}, {1840, 260}, {220, 1020},
                {1080, 240}, {1080, 1080}, {520, 700}, {1640, 700}
        };
        double[] point = points[index % points.length];
        int ring = index / points.length;
        return new Point2D.Double(point[0] + ring * 34, point[1] + ring * 34);
    }

    static String encodeSnapshot(Snapshot snapshot) {
        StringBuilder players = new StringBuilder();
        for (PlayerInfo p : snapshot.players) {
            if (!players.isEmpty()) players.append(';');
            players.append(p.id).append(',').append(p.name).append(',').append(p.rgb);
        }

        StringBuilder units = new StringBuilder();
        for (UnitState u : snapshot.units) {
            if (!units.isEmpty()) units.append(';');
            units.append(u.playerId).append(',')
                    .append(u.unitId).append(',')
                    .append(round(u.x)).append(',')
                    .append(round(u.y)).append(',')
                    .append(round(u.targetX)).append(',')
                    .append(round(u.targetY));
        }

        return "SNAPSHOT|" + snapshot.sequence + "|" + players + "|" + units;
    }

    static Snapshot decodeSnapshot(String message) {
        String[] parts = message.split("\\|", -1);
        long sequence = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
        List<PlayerInfo> players = new ArrayList<>();
        List<UnitState> units = new ArrayList<>();

        if (parts.length > 2 && !parts[2].isBlank()) {
            String[] playerRows = parts[2].split(";");
            for (String row : playerRows) {
                String[] cols = row.split(",", -1);
                if (cols.length >= 3) {
                    players.add(new PlayerInfo(cols[0], cols[1], Integer.parseInt(cols[2]), false));
                }
            }
        }

        if (parts.length > 3 && !parts[3].isBlank()) {
            String[] unitRows = parts[3].split(";");
            for (String row : unitRows) {
                String[] cols = row.split(",", -1);
                if (cols.length >= 6) {
                    units.add(new UnitState(
                            cols[0],
                            Integer.parseInt(cols[1]),
                            Double.parseDouble(cols[2]),
                            Double.parseDouble(cols[3]),
                            Double.parseDouble(cols[4]),
                            Double.parseDouble(cols[5])
                    ));
                }
            }
        }

        return new Snapshot(sequence, players, units);
    }

    static String defaultName() {
        return cleanName(System.getProperty("user.name", "Player"));
    }

    static String cleanName(String name) {
        if (name == null) {
            return "Player";
        }
        String cleaned = name.replace('|', ' ')
                .replace(';', ' ')
                .replace(',', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()) {
            return "Player";
        }
        return cleaned.length() > 18 ? cleaned.substring(0, 18).trim() : cleaned;
    }

    static String endpointKey(InetAddress address, int port) {
        return address.getHostAddress() + ":" + port;
    }

    static String round(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static double distance(double ax, double ay, double bx, double by) {
        return Math.hypot(ax - bx, ay - by);
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
