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
import java.util.EnumMap;
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
 * The lobby is an in-game screen inside the same main window.
 *
 * Current economy pass:
 * - Finite rocks/clouds with slow respawn.
 * - Selected ship + selected resource + F1 starts harvesting.
 * - Each ship has its own inventory shown when selected.
 *
 * Networking is host-authoritative:
 * - Host owns the player list and creates/removes each player's unit group.
 * - Host assigns player IDs, unique display names, and unique colors.
 * - Clients send join/heartbeat/move/harvest/leave packets.
 * - Important UDP packets can be wrapped as reliable messages with ACK/retry.
 * - Host sends fast snapshots plus periodic reliable full snapshots to reduce desync.
 */
public final class RtsGame {
    private static final int STARTING_UNITS = 1;
    private static final double SHIP_SPEED = 185.0;
    private static final double SHIP_CARGO_CAPACITY = 120.0;
    private static final double HARVEST_RANGE = 105.0;
    private static final double MIN_AUTO_ZOOM = 0.38;
    private static final double MAX_AUTO_ZOOM = 1.12;

    public static void main(String[] args) {
        Config config = Config.parse(args);
        SwingUtilities.invokeLater(() -> new GameFrame(config).setVisible(true));
    }

    enum Material {
        IRON("Iron", new Color(180, 150, 120)),
        COPPER("Copper", new Color(221, 122, 60)),
        SILICATES("Silicates", new Color(165, 170, 155)),
        ICE("Water Ice", new Color(145, 220, 255)),
        HYDROGEN("Hydrogen", new Color(110, 210, 255)),
        HELIUM("Helium", new Color(210, 175, 255)),
        METHANE("Methane", new Color(100, 255, 190)),
        AMMONIA("Ammonia", new Color(235, 245, 150));

        final String label;
        final Color color;

        Material(String label, Color color) {
            this.label = label;
            this.color = color;
        }
    }

    enum NodeKind {
        SILICATE_ROCK,
        GAS_CLOUD
    }

    static final class GameFrame extends JFrame {
        private final CardLayout cards = new CardLayout();
        private final JPanel root = new JPanel(cards);
        private final LobbyPanel lobbyPanel = new LobbyPanel(this);
        private PeerNetwork currentNetwork;
        private GamePanel currentGamePanel;

        GameFrame(Config config) {
            super("StarChem");
            setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            setSize(1280, 800);
            setMinimumSize(new Dimension(900, 620));
            setLocationRelativeTo(null);
            setContentPane(root);
            root.add(lobbyPanel, "lobby");

            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    shutdownCurrentNetwork();
                }
            });

            if (config.showLobby) {
                showLobby("Choose Solo, Host, or Join.");
            } else {
                launchGame(config);
            }
        }

        void showLobby(String status) {
            shutdownCurrentNetwork();
            currentGamePanel = null;
            lobbyPanel.setStatus(status);
            setTitle("StarChem - Lobby");
            cards.show(root, "lobby");
            lobbyPanel.requestFocusForName();
        }

        void launchGame(Config config) {
            shutdownCurrentNetwork();
            World world = new World();
            PeerNetwork network = null;

            try {
                network = PeerNetwork.start(config, world);
            } catch (IOException e) {
                if (config.showLobby || config.serverAddress != null || config.hostMode) {
                    showLobby("Network failed: " + e.getMessage());
                    return;
                }
            }

            if (network == null) {
                world.startSolo(config.localPlayerName);
            }

            currentNetwork = network;
            currentGamePanel = new GamePanel(world, currentNetwork, this);
            String cardName = "game-" + System.nanoTime();
            root.add(currentGamePanel, cardName);
            setTitle("StarChem - " + config.modeLabel() + " - " + config.localPlayerName);
            cards.show(root, cardName);
            revalidate();
            repaint();
            SwingUtilities.invokeLater(currentGamePanel::start);
        }

        private void shutdownCurrentNetwork() {
            if (currentNetwork != null) {
                currentNetwork.shutdown();
                currentNetwork = null;
            }
        }
    }

    static final class LobbyPanel extends JPanel {
        private final GameFrame owner;
        private final JTextField nameField = new JTextField(defaultName(), 18);
        private final JTextField hostPortField = new JTextField("50000", 8);
        private final JTextField joinHostField = new JTextField("127.0.0.1", 14);
        private final JTextField joinPortField = new JTextField("50000", 8);
        private final JLabel statusLabel = new JLabel("Choose Solo, Host, or Join.");

        LobbyPanel(GameFrame owner) {
            super(new BorderLayout(16, 16));
            this.owner = owner;
            setBorder(BorderFactory.createEmptyBorder(42, 60, 42, 60));
            setBackground(new Color(4, 8, 15));

            JPanel titlePanel = new JPanel(new GridLayout(0, 1, 0, 7));
            titlePanel.setOpaque(false);
            JLabel title = new JLabel("STAR  CHEM");
            title.setForeground(new Color(224, 245, 255));
            title.setFont(title.getFont().deriveFont(Font.BOLD, 48f));
            JLabel subtitle = new JLabel("Fleet command prototype");
            subtitle.setForeground(new Color(112, 190, 235));
            subtitle.setFont(subtitle.getFont().deriveFont(Font.BOLD, 16f));
            JLabel hint = new JLabel("Mine finite rocks and gas clouds. Cargo is ship-by-ship. F1 starts harvesting.");
            hint.setForeground(new Color(160, 180, 205));
            hint.setFont(hint.getFont().deriveFont(13f));
            titlePanel.add(title);
            titlePanel.add(subtitle);
            titlePanel.add(hint);
            add(titlePanel, BorderLayout.NORTH);

            JPanel centerWrap = new JPanel(new GridBagLayout());
            centerWrap.setOpaque(false);
            JPanel form = new JPanel(new GridBagLayout());
            form.setOpaque(false);
            form.setBorder(BorderFactory.createEmptyBorder(24, 26, 24, 26));

            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(8, 8, 8, 8);
            c.fill = GridBagConstraints.HORIZONTAL;

            JLabel section = new JLabel("SESSION SETUP");
            section.setForeground(new Color(110, 210, 255));
            section.setFont(section.getFont().deriveFont(Font.BOLD, 13f));
            c.gridx = 0;
            c.gridy = 0;
            c.gridwidth = 2;
            form.add(section, c);

            styleField(nameField);
            styleField(hostPortField);
            styleField(joinHostField);
            styleField(joinPortField);
            addRow(form, c, 1, "Commander", nameField);
            addRow(form, c, 2, "Host port", hostPortField);
            addRow(form, c, 3, "Join IP", joinHostField);
            addRow(form, c, 4, "Join port", joinPortField);

            JPanel buttons = new JPanel(new GridLayout(1, 3, 12, 0));
            buttons.setOpaque(false);
            JButton solo = new MenuButton("SOLO");
            JButton host = new MenuButton("HOST");
            JButton join = new MenuButton("JOIN");
            buttons.add(solo);
            buttons.add(host);
            buttons.add(join);

            c.gridx = 0;
            c.gridy = 5;
            c.gridwidth = 2;
            c.insets = new Insets(18, 8, 10, 8);
            form.add(buttons, c);

            statusLabel.setForeground(new Color(210, 228, 245));
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
            c.gridy = 6;
            c.insets = new Insets(8, 8, 8, 8);
            form.add(statusLabel, c);

            JPanel glass = new MenuCardPanel(new BorderLayout());
            glass.add(form, BorderLayout.CENTER);
            centerWrap.add(glass);
            add(centerWrap, BorderLayout.CENTER);

            JTextArea notes = new JTextArea("Select a ship, select a resource node, press F1 to harvest. Resources are finite but slowly respawn. Camera follows your fleet.");
            notes.setEditable(false);
            notes.setLineWrap(true);
            notes.setWrapStyleWord(true);
            notes.setOpaque(false);
            notes.setForeground(new Color(150, 175, 205));
            notes.setFont(notes.getFont().deriveFont(13f));
            add(notes, BorderLayout.SOUTH);

            solo.addActionListener(e -> launchSolo());
            host.addActionListener(e -> launchHost());
            join.addActionListener(e -> launchJoin());
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();

            GradientPaint bg = new GradientPaint(0, 0, new Color(4, 8, 15), w, h, new Color(12, 25, 44));
            g2.setPaint(bg);
            g2.fillRect(0, 0, w, h);

            g2.setColor(new Color(50, 130, 190, 38));
            g2.fillOval(w - 360, -160, 520, 520);
            g2.setColor(new Color(160, 80, 255, 25));
            g2.fillOval(-220, h - 320, 520, 420);

            for (int i = 0; i < 180; i++) {
                int x = Math.floorMod(i * 97 + 37, Math.max(w, 1));
                int y = Math.floorMod(i * 53 + 91, Math.max(h, 1));
                int alpha = 70 + (i % 4) * 35;
                int size = i % 17 == 0 ? 2 : 1;
                g2.setColor(new Color(180, 225, 255, alpha));
                g2.fillOval(x, y, size, size);
            }

            g2.setColor(new Color(80, 170, 255, 35));
            for (int x = -120; x < w + 120; x += 90) {
                g2.drawLine(x, h, x + 260, 0);
            }
            g2.dispose();
        }

        void setStatus(String status) {
            statusLabel.setText(status);
        }

        void requestFocusForName() {
            SwingUtilities.invokeLater(() -> {
                nameField.requestFocusInWindow();
                nameField.selectAll();
            });
        }

        private void addRow(JPanel form, GridBagConstraints c, int row, String label, JComponent field) {
            JLabel jLabel = new JLabel(label);
            jLabel.setForeground(new Color(218, 235, 248));
            jLabel.setFont(jLabel.getFont().deriveFont(Font.BOLD, 13f));
            c.gridwidth = 1;
            c.weightx = 0;
            c.gridx = 0;
            c.gridy = row;
            form.add(jLabel, c);
            c.weightx = 1;
            c.gridx = 1;
            form.add(field, c);
        }

        private void styleField(JTextField field) {
            field.setForeground(Color.WHITE);
            field.setCaretColor(Color.WHITE);
            field.setBackground(new Color(9, 18, 31));
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(70, 115, 150)),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)
            ));
            field.setFont(field.getFont().deriveFont(Font.BOLD, 14f));
        }

        private void launchSolo() {
            owner.launchGame(Config.solo(nameField.getText()));
        }

        private void launchHost() {
            try {
                int port = parsePort(hostPortField.getText().trim());
                owner.launchGame(Config.host(nameField.getText(), port));
            } catch (IllegalArgumentException ex) {
                setStatus(ex.getMessage());
            }
        }

        private void launchJoin() {
            try {
                int port = parsePort(joinPortField.getText().trim());
                String host = joinHostField.getText().trim();
                if (host.isBlank()) {
                    setStatus("Join IP cannot be blank.");
                    return;
                }
                owner.launchGame(Config.join(nameField.getText(), host, port));
            } catch (IllegalArgumentException ex) {
                setStatus(ex.getMessage());
            }
        }
    }

    static final class MenuCardPanel extends JPanel {
        MenuCardPanel(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(6, 12, 22, 218));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 26, 26);
            g2.setColor(new Color(80, 170, 225, 140));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 26, 26);
            g2.setColor(new Color(130, 220, 255, 40));
            g2.drawRoundRect(7, 7, getWidth() - 15, getHeight() - 15, 20, 20);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static final class MenuButton extends JButton {
        MenuButton(String text) {
            super(text);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setForeground(Color.WHITE);
            setFont(getFont().deriveFont(Font.BOLD, 15f));
            setPreferredSize(new Dimension(120, 44));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ButtonModel model = getModel();
            Color top = model.isPressed() ? new Color(25, 90, 130) : model.isRollover() ? new Color(34, 128, 180) : new Color(18, 64, 100);
            Color bottom = model.isPressed() ? new Color(16, 52, 82) : model.isRollover() ? new Color(18, 86, 132) : new Color(9, 34, 62);
            g2.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bottom));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g2.setColor(new Color(126, 220, 255));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.dispose();
            super.paintComponent(g);
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
                        localPort = parsePort(require(args, ++i, "--host needs a port"));
                    }
                    case "--join" -> {
                        host = false;
                        String hostName = require(args, ++i, "--join needs host ip");
                        int peerPort = parsePort(require(args, ++i, "--join needs peer port"));
                        server = new InetSocketAddress(hostName, peerPort);
                    }
                    case "--local-port" -> localPort = parsePort(require(args, ++i, "--local-port needs a port"));
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

        String modeLabel() {
            if (soloMode) return "Solo";
            if (hostMode) return "Host";
            if (serverAddress != null) return "Client";
            return "Lobby";
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
        private final GameFrame owner;
        private final Set<Integer> keys = new HashSet<>();
        private final javax.swing.Timer timer;
        private double cameraX = 0;
        private double cameraY = 0;
        private double zoom = 1.0;
        private Point dragStart;
        private Point dragNow;
        private long lastNanos = System.nanoTime();

        GamePanel(World world, PeerNetwork network, GameFrame owner) {
            this.world = world;
            this.network = network;
            this.owner = owner;
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

            handleInputAndCamera(dt);
            if (network != null) {
                network.drainMessages();
            }
            world.update(dt);
            repaint();
        }

        private void handleInputAndCamera(double dt) {
            if (keys.contains(KeyEvent.VK_ESCAPE)) {
                timer.stop();
                owner.showLobby("Returned to lobby.");
                return;
            }
            updateAutoCamera(dt);
        }

        private void updateAutoCamera(double dt) {
            Rectangle2D bounds = world.localUnitBounds();
            if (bounds == null) {
                return;
            }

            double fleetWidth = Math.max(bounds.getWidth(), 40);
            double fleetHeight = Math.max(bounds.getHeight(), 40);
            double padding = Math.max(320, Math.max(fleetWidth, fleetHeight) * 0.55);
            double availableW = Math.max(500, getWidth() - 120);
            double availableH = Math.max(360, getHeight() - 120);
            double desiredZoom = Math.min(availableW / (fleetWidth + padding), availableH / (fleetHeight + padding));
            double targetZoom = clamp(desiredZoom, MIN_AUTO_ZOOM, MAX_AUTO_ZOOM);

            double visibleW = getWidth() / targetZoom;
            double visibleH = getHeight() / targetZoom;
            double targetCameraX = bounds.getCenterX() - visibleW / 2.0;
            double targetCameraY = bounds.getCenterY() - visibleH / 2.0;

            double zoomBlend = clamp(dt * 3.2, 0, 1);
            double cameraBlend = clamp(dt * 3.8, 0, 1);
            zoom = lerp(zoom, targetZoom, zoomBlend);
            cameraX = lerp(cameraX, targetCameraX, cameraBlend);
            cameraY = lerp(cameraY, targetCameraY, cameraBlend);

            double currentVisibleW = getWidth() / zoom;
            double currentVisibleH = getHeight() / zoom;
            cameraX = clamp(cameraX, -160, Math.max(-160, world.width - currentVisibleW + 160));
            cameraY = clamp(cameraY, -160, Math.max(-160, world.height - currentVisibleH + 160));
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
            int leftHeight = 136 + players.size() * 18;
            g2.setColor(new Color(0, 0, 0, 175));
            g2.fillRoundRect(12, 12, 860, leftHeight, 14, 14);
            g2.setColor(Color.WHITE);
            g2.drawString("StarChem | Local: " + world.localPlayerLabel() + " | Selected: " + world.selectedCount(), 28, 36);
            g2.drawString("Left select ship/resource | Right move | F1 harvest selected target | ESC lobby", 28, 58);
            g2.drawString(network == null ? "Network: solo/offline" : network.statusLine(), 28, 80);
            g2.setColor(new Color(210, 230, 245));
            g2.drawString(world.statusLine(), 28, 102);

            int y = 126;
            for (PlayerInfo player : players) {
                g2.setColor(new Color(player.rgb));
                g2.fillRect(28, y - 11, 12, 12);
                g2.setColor(Color.WHITE);
                String suffix = player.local ? "  (you)" : "";
                g2.drawString(player.name + " - " + world.unitCountFor(player.id) + " ships" + suffix, 48, y);
                y += 18;
            }

            drawInfoPanel(g2);
        }

        private void drawInfoPanel(Graphics2D g2) {
            int panelW = 330;
            int panelH = 250;
            int x = getWidth() - panelW - 18;
            int y = 18;
            g2.setColor(new Color(0, 0, 0, 178));
            g2.fillRoundRect(x, y, panelW, panelH, 16, 16);
            g2.setColor(new Color(80, 170, 225, 180));
            g2.drawRoundRect(x, y, panelW, panelH, 16, 16);

            int lineY = y + 26;
            g2.setColor(Color.WHITE);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f));
            g2.drawString("SELECTION", x + 16, lineY);
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));

            lineY += 24;
            for (String line : world.selectedShipInfoLines()) {
                g2.setColor(new Color(225, 240, 255));
                g2.drawString(line, x + 16, lineY);
                lineY += 17;
            }

            lineY += 8;
            for (String line : world.selectedResourceInfoLines()) {
                g2.setColor(new Color(210, 235, 210));
                g2.drawString(line, x + 16, lineY);
                lineY += 17;
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
                    world.selectAt(p.getX(), p.getY());
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

        private void triggerHarvest() {
            List<HarvestCommand> commands = world.issueHarvestSelected();
            if (network != null) {
                for (HarvestCommand command : commands) {
                    network.sendHarvest(command);
                }
            }
        }

        @Override public void mouseEntered(MouseEvent e) { }
        @Override public void mouseExited(MouseEvent e) { }
        @Override public void mouseDragged(MouseEvent e) { dragNow = e.getPoint(); }
        @Override public void mouseMoved(MouseEvent e) { }
        @Override public void mouseWheelMoved(MouseWheelEvent e) { }
        @Override public void keyTyped(KeyEvent e) { }

        @Override
        public void keyPressed(KeyEvent e) {
            keys.add(e.getKeyCode());
            if (e.getKeyCode() == KeyEvent.VK_F1) {
                triggerHarvest();
            }
        }

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
        private int selectedResourceId = -1;
        private String statusLine = "Select a ship and a resource, then press F1 to harvest.";

        World() {
            seedResources();
        }

        private void seedResources() {
            resources.add(new ResourceNode(1, "Silicate Rock - Iron Vein", NodeKind.SILICATE_ROCK, Material.IRON, 620, 370, 260, 0.18, 7.5, 32));
            resources.add(new ResourceNode(2, "Silicate Rock - Copper Vein", NodeKind.SILICATE_ROCK, Material.COPPER, 1010, 610, 180, 0.14, 6.5, 28));
            resources.add(new ResourceNode(3, "Ice-Rich Silicate Rock", NodeKind.SILICATE_ROCK, Material.ICE, 1380, 330, 220, 0.16, 7.0, 31));
            resources.add(new ResourceNode(4, "Fractured Silicate Cluster", NodeKind.SILICATE_ROCK, Material.SILICATES, 1660, 1000, 320, 0.22, 8.0, 36));
            resources.add(new ResourceNode(5, "Hydrogen Gas Cloud", NodeKind.GAS_CLOUD, Material.HYDROGEN, 890, 980, 360, 0.34, 9.0, 58));
            resources.add(new ResourceNode(6, "Helium Pocket", NodeKind.GAS_CLOUD, Material.HELIUM, 1830, 520, 210, 0.24, 7.5, 52));
            resources.add(new ResourceNode(7, "Methane-Ammonia Cloud", NodeKind.GAS_CLOUD, Material.METHANE, 420, 1060, 240, 0.26, 7.5, 55));
            resources.add(new ResourceNode(8, "Ammonia Trace Cloud", NodeKind.GAS_CLOUD, Material.AMMONIA, 1250, 1130, 180, 0.20, 6.5, 50));
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
            for (int i = 0; i < STARTING_UNITS; i++) {
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
            for (ResourceNode node : resources) {
                node.update(dt);
            }
            for (Unit u : units.values()) {
                u.update(dt, width, height);
                updateHarvest(u, dt);
            }
        }

        private void updateHarvest(Unit unit, double dt) {
            if (unit.harvestNodeId < 0) {
                return;
            }
            ResourceNode node = findResource(unit.harvestNodeId);
            if (node == null) {
                unit.harvestNodeId = -1;
                return;
            }
            double range = distance(unit.x, unit.y, node.x, node.y);
            if (range > HARVEST_RANGE + node.radius) {
                return;
            }
            if (node.amount <= 0.05 || unit.freeCargo() <= 0.05) {
                return;
            }

            double harvested = Math.min(node.harvestRate * dt, node.amount);
            harvested = Math.min(harvested, unit.freeCargo());
            if (harvested <= 0) {
                return;
            }
            node.amount -= harvested;
            unit.addCargo(node.material, harvested);
        }

        synchronized void draw(Graphics2D g2) {
            for (ResourceNode node : resources) {
                node.draw(g2, node.id == selectedResourceId);
            }
            for (Unit u : units.values()) {
                ResourceNode node = findResource(u.harvestNodeId);
                if (node != null) {
                    u.drawHarvestVisual(g2, node, u.playerId.equals(localPlayerId));
                }
                u.drawMoveOrder(g2, u.playerId.equals(localPlayerId));
            }
            for (Unit u : units.values()) {
                PlayerInfo player = players.get(u.playerId);
                String name = player == null ? u.playerId : player.name;
                u.draw(g2, name);
            }
        }

        synchronized String statusLine() {
            return statusLine;
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

        synchronized Rectangle2D localUnitBounds() {
            boolean found = false;
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (Unit u : units.values()) {
                if (!u.playerId.equals(localPlayerId)) {
                    continue;
                }
                found = true;
                minX = Math.min(minX, u.x);
                minY = Math.min(minY, u.y);
                maxX = Math.max(maxX, u.x);
                maxY = Math.max(maxY, u.y);
            }
            if (!found) {
                return null;
            }
            return new Rectangle2D.Double(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
        }

        synchronized void selectAt(double x, double y) {
            ResourceNode node = findResourceAt(x, y);
            if (node != null) {
                selectedResourceId = node.id;
                statusLine = "Targeted " + node.name + ". Select a ship in range and press F1.";
                return;
            }
            selectSingle(x, y);
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
                statusLine = "Selected ship " + best.unitId + ". Target a rock/cloud and press F1.";
            }
        }

        synchronized void selectBox(Rectangle2D box) {
            for (Unit u : units.values()) {
                u.selected = u.playerId.equals(localPlayerId) && box.contains(u.x, u.y);
            }
            statusLine = selectedCount() + " ship(s) selected.";
        }

        synchronized List<MoveCommand> issueMoveSelected(double x, double y) {
            List<Unit> selected = units.values().stream()
                    .filter(u -> u.playerId.equals(localPlayerId) && u.selected)
                    .toList();
            List<MoveCommand> commands = new ArrayList<>();
            int count = selected.size();
            if (count == 0) {
                statusLine = "No ship selected.";
                return commands;
            }

            double spacing = 42;
            int columns = (int) Math.ceil(Math.sqrt(count));
            int rows = (int) Math.ceil(count / (double) columns);
            double centerCol = (columns - 1) / 2.0;
            double centerRow = (rows - 1) / 2.0;
            for (int i = 0; i < count; i++) {
                Unit u = selected.get(i);
                int col = i % columns;
                int row = i / columns;
                double targetX = x + (col - centerCol) * spacing;
                double targetY = y + (row - centerRow) * spacing;
                u.moveTo(targetX, targetY);
                commands.add(new MoveCommand(localPlayerId, u.unitId, targetX, targetY));
            }
            statusLine = "Move order issued.";
            return commands;
        }

        synchronized List<HarvestCommand> issueHarvestSelected() {
            List<HarvestCommand> commands = new ArrayList<>();
            ResourceNode node = findResource(selectedResourceId);
            if (node == null) {
                statusLine = "No asteroid/gas cloud targeted. Left-click one first.";
                return commands;
            }

            List<Unit> selected = units.values().stream()
                    .filter(u -> u.playerId.equals(localPlayerId) && u.selected)
                    .toList();
            if (selected.isEmpty()) {
                statusLine = "No ship selected. Select a ship, target a resource, then press F1.";
                return commands;
            }

            int started = 0;
            for (Unit unit : selected) {
                if (canHarvest(unit, node)) {
                    unit.harvestNodeId = node.id;
                    unit.targetX = unit.x;
                    unit.targetY = unit.y;
                    commands.add(new HarvestCommand(localPlayerId, unit.unitId, node.id));
                    started++;
                }
            }

            if (started == 0) {
                statusLine = "Ship must be in range and have cargo space to harvest " + node.material.label + ".";
            } else {
                statusLine = "Harvesting " + node.material.label + " from " + node.name + ".";
            }
            return commands;
        }

        private boolean canHarvest(Unit unit, ResourceNode node) {
            if (node.amount <= 0.05) {
                return false;
            }
            if (unit.freeCargo() <= 0.05) {
                return false;
            }
            return distance(unit.x, unit.y, node.x, node.y) <= HARVEST_RANGE + node.radius;
        }

        synchronized void applyAuthorizedMove(MoveCommand command) {
            Unit unit = units.get(Unit.key(command.playerId, command.unitId));
            if (unit != null) {
                unit.moveTo(command.x, command.y);
            }
        }

        synchronized void applyAuthorizedHarvest(HarvestCommand command) {
            Unit unit = units.get(Unit.key(command.playerId, command.unitId));
            ResourceNode node = findResource(command.resourceId);
            if (unit != null && node != null && canHarvest(unit, node)) {
                unit.harvestNodeId = node.id;
                unit.targetX = unit.x;
                unit.targetY = unit.y;
            }
        }

        synchronized Snapshot createSnapshot(long sequence) {
            List<PlayerInfo> playerCopies = new ArrayList<>(players.values());
            List<UnitState> unitCopies = new ArrayList<>();
            for (Unit u : units.values()) {
                unitCopies.add(new UnitState(u.playerId, u.unitId, u.x, u.y, u.targetX, u.targetY, u.harvestNodeId, encodeInventory(u.inventory)));
            }
            List<ResourceState> resourceCopies = new ArrayList<>();
            for (ResourceNode node : resources) {
                resourceCopies.add(new ResourceState(node.id, node.amount));
            }
            return new Snapshot(sequence, playerCopies, unitCopies, resourceCopies);
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

            for (ResourceState state : snapshot.resources) {
                ResourceNode node = findResource(state.id);
                if (node != null) {
                    node.amount = clamp(state.amount, 0, node.maxAmount);
                }
            }
        }

        synchronized List<String> selectedShipInfoLines() {
            Unit selected = selectedLocalUnit();
            List<String> lines = new ArrayList<>();
            if (selected == null) {
                lines.add("Ship: none selected");
                lines.add("Click your ship, then target a resource.");
                return lines;
            }
            lines.add("Ship: #" + selected.unitId);
            lines.add(String.format(Locale.ROOT, "Cargo: %.1f / %.0f", selected.cargoUsed(), selected.cargoCapacity));
            if (selected.harvestNodeId >= 0) {
                ResourceNode node = findResource(selected.harvestNodeId);
                lines.add("Action: harvesting " + (node == null ? "unknown" : node.material.label));
            } else {
                lines.add("Action: idle/moving");
            }
            lines.add("Inventory:");
            boolean empty = true;
            for (Material material : Material.values()) {
                double amount = selected.inventory.getOrDefault(material, 0.0);
                if (amount > 0.05) {
                    lines.add("  " + material.label + ": " + String.format(Locale.ROOT, "%.1f", amount));
                    empty = false;
                }
            }
            if (empty) {
                lines.add("  Empty");
            }
            return lines;
        }

        synchronized List<String> selectedResourceInfoLines() {
            ResourceNode node = findResource(selectedResourceId);
            List<String> lines = new ArrayList<>();
            if (node == null) {
                lines.add("Target: none");
                return lines;
            }
            lines.add("Target: " + node.name);
            lines.add("Type: " + (node.kind == NodeKind.GAS_CLOUD ? "Gas cloud" : "Silicate rock"));
            lines.add("Contains: " + node.material.label);
            lines.add(String.format(Locale.ROOT, "Remaining: %.1f / %.0f", node.amount, node.maxAmount));
            lines.add("F1: harvest if selected ship is in range");
            return lines;
        }

        private Unit selectedLocalUnit() {
            for (Unit unit : units.values()) {
                if (unit.playerId.equals(localPlayerId) && unit.selected) {
                    return unit;
                }
            }
            return null;
        }

        private ResourceNode findResourceAt(double x, double y) {
            ResourceNode best = null;
            double bestDistance = Double.MAX_VALUE;
            for (ResourceNode node : resources) {
                double d = distance(x, y, node.x, node.y);
                if (d <= node.radius + 14 && d < bestDistance) {
                    best = node;
                    bestDistance = d;
                }
            }
            return best;
        }

        private ResourceNode findResource(int id) {
            for (ResourceNode node : resources) {
                if (node.id == id) {
                    return node;
                }
            }
            return null;
        }
    }

    static final class ResourceNode {
        final int id;
        final String name;
        final NodeKind kind;
        final Material material;
        final double x;
        final double y;
        final double maxAmount;
        final double respawnRate;
        final double harvestRate;
        final double radius;
        double amount;

        ResourceNode(int id, String name, NodeKind kind, Material material, double x, double y, double maxAmount, double respawnRate, double harvestRate, double radius) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.material = material;
            this.x = x;
            this.y = y;
            this.maxAmount = maxAmount;
            this.respawnRate = respawnRate;
            this.harvestRate = harvestRate;
            this.radius = radius;
            this.amount = maxAmount;
        }

        void update(double dt) {
            if (amount < maxAmount) {
                amount = Math.min(maxAmount, amount + respawnRate * dt);
            }
        }

        void draw(Graphics2D g2, boolean selected) {
            Graphics2D r = (Graphics2D) g2.create();
            r.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (kind == NodeKind.GAS_CLOUD) {
                drawGas(r, selected);
            } else {
                drawRock(r, selected);
            }
            drawAmountBar(r);
            r.dispose();
        }

        private void drawRock(Graphics2D g2, boolean selected) {
            int sides = 9;
            Polygon poly = new Polygon();
            for (int i = 0; i < sides; i++) {
                double a = -Math.PI / 2 + i * Math.PI * 2 / sides;
                double wobble = 0.78 + (Math.floorMod(id * 31 + i * 17, 30) / 100.0);
                poly.addPoint((int) Math.round(x + Math.cos(a) * radius * wobble), (int) Math.round(y + Math.sin(a) * radius * wobble));
            }
            g2.setColor(new Color(70, 68, 63));
            g2.fillPolygon(poly);
            g2.setColor(material.color);
            g2.setStroke(new BasicStroke(2f));
            g2.drawPolygon(poly);
            g2.setColor(new Color(material.color.getRed(), material.color.getGreen(), material.color.getBlue(), 85));
            g2.fillOval((int) (x - radius * 0.45), (int) (y - radius * 0.45), (int) radius, (int) radius);
            if (selected) {
                g2.setColor(new Color(255, 245, 140, 210));
                g2.setStroke(new BasicStroke(2.4f));
                g2.drawOval((int) (x - radius - 10), (int) (y - radius - 10), (int) (radius * 2 + 20), (int) (radius * 2 + 20));
            }
        }

        private void drawGas(Graphics2D g2, boolean selected) {
            for (int i = 0; i < 7; i++) {
                double a = i * Math.PI * 2 / 7.0;
                double ox = Math.cos(a) * radius * 0.25;
                double oy = Math.sin(a) * radius * 0.22;
                int alpha = 42 + i * 8;
                g2.setColor(new Color(material.color.getRed(), material.color.getGreen(), material.color.getBlue(), alpha));
                g2.fillOval((int) (x + ox - radius * 0.55), (int) (y + oy - radius * 0.42), (int) (radius * 1.1), (int) (radius * 0.84));
            }
            g2.setColor(new Color(material.color.getRed(), material.color.getGreen(), material.color.getBlue(), 150));
            g2.setStroke(new BasicStroke(1.8f));
            g2.drawOval((int) (x - radius * 0.8), (int) (y - radius * 0.62), (int) (radius * 1.6), (int) (radius * 1.24));
            if (selected) {
                g2.setColor(new Color(255, 245, 140, 210));
                g2.setStroke(new BasicStroke(2.4f));
                g2.drawOval((int) (x - radius - 12), (int) (y - radius - 12), (int) (radius * 2 + 24), (int) (radius * 2 + 24));
            }
        }

        private void drawAmountBar(Graphics2D g2) {
            int barW = 64;
            int barH = 6;
            int bx = (int) (x - barW / 2.0);
            int by = (int) (y + radius + 12);
            double pct = maxAmount <= 0 ? 0 : amount / maxAmount;
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRoundRect(bx, by, barW, barH, 6, 6);
            g2.setColor(material.color);
            g2.fillRoundRect(bx, by, (int) Math.round(barW * pct), barH, 6, 6);
        }
    }

    static final class Unit {
        final String playerId;
        final int unitId;
        final double cargoCapacity = SHIP_CARGO_CAPACITY;
        final EnumMap<Material, Double> inventory = new EnumMap<>(Material.class);
        double x;
        double y;
        double targetX;
        double targetY;
        int harvestNodeId = -1;
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
            this.harvestNodeId = -1;
        }

        double cargoUsed() {
            double total = 0;
            for (double value : inventory.values()) {
                total += value;
            }
            return total;
        }

        double freeCargo() {
            return Math.max(0, cargoCapacity - cargoUsed());
        }

        void addCargo(Material material, double amount) {
            inventory.put(material, inventory.getOrDefault(material, 0.0) + amount);
        }

        void setCargoFromEncoded(String encoded) {
            inventory.clear();
            if (encoded == null || encoded.isBlank() || encoded.equals("-")) {
                return;
            }
            String[] parts = encoded.split("~");
            for (String part : parts) {
                String[] pair = part.split(":");
                if (pair.length == 2) {
                    try {
                        Material material = Material.valueOf(pair[0]);
                        double amount = Double.parseDouble(pair[1]);
                        if (amount > 0.05) {
                            inventory.put(material, amount);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // ignore stale material keys
                    }
                }
            }
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
            harvestNodeId = state.harvestNodeId;
            setCargoFromEncoded(state.cargo);
        }

        void update(double dt, int mapW, int mapH) {
            double dx = targetX - x;
            double dy = targetY - y;
            double distance = Math.hypot(dx, dy);
            if (distance > 2) {
                double step = Math.min(distance, SHIP_SPEED * dt);
                x += dx / distance * step;
                y += dy / distance * step;
            }
            x = clamp(x, 0, mapW);
            y = clamp(y, 0, mapH);
        }

        void drawMoveOrder(Graphics2D g2, boolean showEta) {
            double distance = distance(x, y, targetX, targetY);
            if (distance <= 4) {
                return;
            }

            Graphics2D route = (Graphics2D) g2.create();
            route.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = new Color(rgb);
            int alpha = showEta ? 180 : 95;
            route.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
            route.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{9f, 8f}, 0));
            route.draw(new Line2D.Double(x, y, targetX, targetY));

            route.setStroke(new BasicStroke(2.0f));
            route.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.min(220, alpha + 35)));
            route.draw(new Ellipse2D.Double(targetX - 11, targetY - 11, 22, 22));
            route.draw(new Line2D.Double(targetX - 16, targetY, targetX + 16, targetY));
            route.draw(new Line2D.Double(targetX, targetY - 16, targetX, targetY + 16));

            if (showEta) {
                double eta = distance / SHIP_SPEED;
                String label = String.format(Locale.ROOT, "%.0f u/s | ETA %.1fs", SHIP_SPEED, eta);
                Font oldFont = route.getFont();
                route.setFont(oldFont.deriveFont(Font.BOLD, 12f));
                FontMetrics fm = route.getFontMetrics();
                double labelX = (x + targetX) / 2.0 + 10;
                double labelY = (y + targetY) / 2.0 - 10;
                int textW = fm.stringWidth(label);
                int textH = fm.getHeight();
                route.setColor(new Color(0, 0, 0, 170));
                route.fillRoundRect((int) labelX - 5, (int) labelY - textH + 3, textW + 10, textH + 5, 8, 8);
                route.setColor(new Color(220, 245, 255));
                route.drawString(label, (int) labelX, (int) labelY);
            }
            route.dispose();
        }

        void drawHarvestVisual(Graphics2D g2, ResourceNode node, boolean local) {
            if (harvestNodeId != node.id || distance(x, y, node.x, node.y) > HARVEST_RANGE + node.radius) {
                return;
            }
            Graphics2D beam = (Graphics2D) g2.create();
            beam.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            long t = System.currentTimeMillis();
            float pulse = (float) (0.55 + 0.45 * Math.sin(t / 110.0 + unitId));
            Color material = node.material.color;
            beam.setStroke(new BasicStroke(local ? 3.0f : 2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            beam.setColor(new Color(material.getRed(), material.getGreen(), material.getBlue(), local ? 150 : 90));
            beam.draw(new Line2D.Double(x, y, node.x, node.y));
            beam.setColor(new Color(255, 255, 255, (int) (70 + 95 * pulse)));
            beam.setStroke(new BasicStroke(1.2f));
            beam.draw(new Line2D.Double(x, y, node.x, node.y));

            for (int i = 0; i < 5; i++) {
                double f = Math.floorMod((int) (t / 70 + i * 23), 100) / 100.0;
                double px = x + (node.x - x) * f;
                double py = y + (node.y - y) * f;
                beam.setColor(new Color(material.getRed(), material.getGreen(), material.getBlue(), 120));
                beam.fillOval((int) px - 3, (int) py - 3, 6, 6);
            }

            String label = "HARVESTING " + node.material.label.toUpperCase(Locale.ROOT);
            beam.setFont(beam.getFont().deriveFont(Font.BOLD, 11f));
            FontMetrics fm = beam.getFontMetrics();
            int textW = fm.stringWidth(label);
            int lx = (int) ((x + node.x) / 2 - textW / 2.0);
            int ly = (int) ((y + node.y) / 2 + 24);
            beam.setColor(new Color(0, 0, 0, 170));
            beam.fillRoundRect(lx - 5, ly - 13, textW + 10, 18, 8, 8);
            beam.setColor(new Color(235, 250, 255));
            beam.drawString(label, lx, ly);
            beam.dispose();
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

            double cargoPct = cargoCapacity <= 0 ? 0 : cargoUsed() / cargoCapacity;
            g2.setColor(new Color(20, 20, 20));
            g2.fillRect((int) x - 18, (int) y + 27, 36, 4);
            g2.setColor(new Color(110, 200, 255));
            g2.fillRect((int) x - 18, (int) y + 27, (int) (36 * cargoPct), 4);

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

        void sendHarvest(HarvestCommand command) {
            if (command.playerId == null || command.playerId.isBlank()) {
                return;
            }
            String msg = "HARVEST|" + command.playerId + "|" + command.unitId + "|" + command.resourceId;
            if (config.hostMode) {
                world.applyAuthorizedHarvest(command);
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
                case "HARVEST" -> {
                    if (parts.length < 4) {
                        return;
                    }
                    ServerPeer peer = serverPeers.get(endpoint);
                    if (peer == null || !peer.playerId.equals(parts[1])) {
                        return;
                    }
                    peer.lastSeen = System.currentTimeMillis();
                    HarvestCommand command = new HarvestCommand(parts[1], Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    world.applyAuthorizedHarvest(command);
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

    record PlayerInfo(String id, String name, int rgb, boolean local) { }
    record UnitState(String playerId, int unitId, double x, double y, double targetX, double targetY, int harvestNodeId, String cargo) { }
    record ResourceState(int id, double amount) { }
    record MoveCommand(String playerId, int unitId, double x, double y) { }
    record HarvestCommand(String playerId, int unitId, int resourceId) { }
    record Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources) { }
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
                    .append(round(u.targetY)).append(',')
                    .append(u.harvestNodeId).append(',')
                    .append(u.cargo == null || u.cargo.isBlank() ? "-" : u.cargo);
        }

        StringBuilder resources = new StringBuilder();
        for (ResourceState r : snapshot.resources) {
            if (!resources.isEmpty()) resources.append(';');
            resources.append(r.id).append(',').append(round(r.amount));
        }

        return "SNAPSHOT|" + snapshot.sequence + "|" + players + "|" + units + "|" + resources;
    }

    static Snapshot decodeSnapshot(String message) {
        String[] parts = message.split("\\|", -1);
        long sequence = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
        List<PlayerInfo> players = new ArrayList<>();
        List<UnitState> units = new ArrayList<>();
        List<ResourceState> resources = new ArrayList<>();

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
                if (cols.length >= 8) {
                    units.add(new UnitState(
                            cols[0],
                            Integer.parseInt(cols[1]),
                            Double.parseDouble(cols[2]),
                            Double.parseDouble(cols[3]),
                            Double.parseDouble(cols[4]),
                            Double.parseDouble(cols[5]),
                            Integer.parseInt(cols[6]),
                            cols[7]
                    ));
                }
            }
        }

        if (parts.length > 4 && !parts[4].isBlank()) {
            String[] resourceRows = parts[4].split(";");
            for (String row : resourceRows) {
                String[] cols = row.split(",", -1);
                if (cols.length >= 2) {
                    resources.add(new ResourceState(Integer.parseInt(cols[0]), Double.parseDouble(cols[1])));
                }
            }
        }

        return new Snapshot(sequence, players, units, resources);
    }

    static String encodeInventory(EnumMap<Material, Double> inventory) {
        if (inventory.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (Material material : Material.values()) {
            double amount = inventory.getOrDefault(material, 0.0);
            if (amount > 0.05) {
                if (!builder.isEmpty()) {
                    builder.append('~');
                }
                builder.append(material.name()).append(':').append(round(amount));
            }
        }
        return builder.isEmpty() ? "-" : builder.toString();
    }

    static String defaultName() {
        return cleanName(System.getProperty("user.name", "Player"));
    }

    static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Port must be 1-65535.");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be a number.");
        }
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

    static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    static double distance(double ax, double ay, double bx, double by) {
        return Math.hypot(ax - bx, ay - by);
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
