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
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * StarChem: Java 2D top-down RTS prototype.
 *
 * This build wires the first real automation/progression pass:
 * - Right-click resource with selected ship starts automated harvesting.
 * - Full miners return to station, unload, then resume if the node still exists.
 * - Idle ships loiter/orbit near station.
 * - Harvesting ships orbit the rock/cloud while mining.
 * - Depleted resource nodes vanish and respawn somewhere else.
 * - Outposts build Prospectors and Deployers.
 * - Outposts fabricate/load a Shipyard package into a Deployer.
 * - Placing a Shipyard consumes the Deployer and unlocks Haulers/Scouts.
 */
public final class RtsGame {
    private static final int STARTING_UNITS = 1;
    private static final double MIN_AUTO_ZOOM = 0.38;
    private static final double MAX_AUTO_ZOOM = 1.12;
    private static final double RESOURCE_RESPAWN_SECONDS = 18.0;
    private static final double DEPLETED_HIDE_SECONDS = 2.0;
    private static final double MIN_RESOURCE_STATION_DISTANCE = 180.0;
    private static final double MIN_RESOURCE_NODE_DISTANCE = 140.0;
    private static final Rules RULES = Rules.createDefault();

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

    enum NodeKind { SILICATE_ROCK, GAS_CLOUD }
    enum UnitTask { IDLE, MOVE, AUTO_HARVEST, RETURN_TO_STATION }

    static final class Rules {
        final Map<String, ShipDef> ships = new LinkedHashMap<>();
        final Map<String, StationDef> stations = new LinkedHashMap<>();
        String startingShipType = "prospector";
        String defaultStationType = "outpost";

        static Rules createDefault() {
            Rules r = new Rules();
            r.ships.put("prospector", new ShipDef(
                    "prospector", "Prospector", "starter miner", 100, 185, 120, 105, 72, 96,
                    cost(80, 40, 0, 0, 0), set(NodeKind.SILICATE_ROCK, NodeKind.GAS_CLOUD), false, 0,
                    List.of(), false
            ));
            r.ships.put("station_builder", new ShipDef(
                    "station_builder", "Deployer", "station placement ship", 240, 115, 0, 0, 90, 120,
                    cost(220, 120, 100, 40, 0), Set.of(), true, 1,
                    List.of("shipyard"), true
            ));
            r.ships.put("hauler", new ShipDef(
                    "hauler", "Hauler", "cargo ship", 140, 140, 320, 80, 84, 120,
                    cost(150, 60, 80, 0, 0), Set.of(), false, 0,
                    List.of(), false
            ));
            r.ships.put("scout", new ShipDef(
                    "scout", "Scout", "fast recon", 70, 270, 45, 60, 70, 115,
                    cost(60, 90, 0, 0, 40), Set.of(), false, 0,
                    List.of(), false
            ));

            r.stations.put("outpost", new StationDef(
                    "outpost", "Outpost", 1200, 118, 95, 72,
                    List.of("prospector", "station_builder"), List.of("shipyard"), new EnumMap<>(Material.class), null, false
            ));
            r.stations.put("shipyard", new StationDef(
                    "shipyard", "Shipyard", 2400, 150, 160, 100,
                    List.of("prospector", "station_builder", "hauler", "scout"), List.of(),
                    cost(500, 250, 350, 160, 0), "station_builder", true
            ));
            return r;
        }

        ShipDef ship(String id) { return ships.getOrDefault(id, ships.get(startingShipType)); }
        StationDef station(String id) { return stations.getOrDefault(id, stations.get(defaultStationType)); }
    }

    static final class ShipDef {
        final String id;
        final String displayName;
        final String role;
        final double maxHp;
        final double speed;
        final double cargoCapacity;
        final double harvestRange;
        final double orbitRadius;
        final double idleStationOrbitRadius;
        final EnumMap<Material, Double> buildCost;
        final Set<NodeKind> canHarvest;
        final boolean stationBuilder;
        final int stationPackageSlots;
        final List<String> canCarryStationPackages;
        final boolean singleUseAfterStationPlacement;

        ShipDef(String id, String displayName, String role, double maxHp, double speed, double cargoCapacity,
                double harvestRange, double orbitRadius, double idleStationOrbitRadius,
                EnumMap<Material, Double> buildCost, Set<NodeKind> canHarvest, boolean stationBuilder,
                int stationPackageSlots, List<String> canCarryStationPackages, boolean singleUseAfterStationPlacement) {
            this.id = id;
            this.displayName = displayName;
            this.role = role;
            this.maxHp = maxHp;
            this.speed = speed;
            this.cargoCapacity = cargoCapacity;
            this.harvestRange = harvestRange;
            this.orbitRadius = orbitRadius;
            this.idleStationOrbitRadius = idleStationOrbitRadius;
            this.buildCost = buildCost;
            this.canHarvest = canHarvest;
            this.stationBuilder = stationBuilder;
            this.stationPackageSlots = stationPackageSlots;
            this.canCarryStationPackages = canCarryStationPackages;
            this.singleUseAfterStationPlacement = singleUseAfterStationPlacement;
        }
    }

    static final class StationDef {
        final String id;
        final String displayName;
        final double maxHp;
        final double unloadRange;
        final double unloadRate;
        final double buildRadius;
        final List<String> canBuildShips;
        final List<String> canBuildStationPackages;
        final EnumMap<Material, Double> buildCost;
        final String mustBeCarriedByShipType;
        final boolean carrierIsRemovedAfterPlacement;

        StationDef(String id, String displayName, double maxHp, double unloadRange, double unloadRate, double buildRadius,
                   List<String> canBuildShips, List<String> canBuildStationPackages, EnumMap<Material, Double> buildCost,
                   String mustBeCarriedByShipType, boolean carrierIsRemovedAfterPlacement) {
            this.id = id;
            this.displayName = displayName;
            this.maxHp = maxHp;
            this.unloadRange = unloadRange;
            this.unloadRate = unloadRate;
            this.buildRadius = buildRadius;
            this.canBuildShips = canBuildShips;
            this.canBuildStationPackages = canBuildStationPackages;
            this.buildCost = buildCost;
            this.mustBeCarriedByShipType = mustBeCarriedByShipType;
            this.carrierIsRemovedAfterPlacement = carrierIsRemovedAfterPlacement;
        }
    }

    static EnumMap<Material, Double> cost(double iron, double copper, double silicates, double ice, double hydrogen) {
        EnumMap<Material, Double> m = new EnumMap<>(Material.class);
        if (iron > 0) m.put(Material.IRON, iron);
        if (copper > 0) m.put(Material.COPPER, copper);
        if (silicates > 0) m.put(Material.SILICATES, silicates);
        if (ice > 0) m.put(Material.ICE, ice);
        if (hydrogen > 0) m.put(Material.HYDROGEN, hydrogen);
        return m;
    }

    @SafeVarargs
    static <T> Set<T> set(T... values) {
        Set<T> out = new LinkedHashSet<>();
        for (T v : values) out.add(v);
        return out;
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
                @Override public void windowClosing(WindowEvent e) { shutdownCurrentNetwork(); }
            });
            if (config.showLobby) showLobby("Choose Solo, Host, or Join.");
            else launchGame(config);
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
                showLobby("Network failed: " + e.getMessage());
                return;
            }
            if (network == null) world.startSolo(config.localPlayerName);
            currentNetwork = network;
            currentGamePanel = new GamePanel(world, network, this);
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
            JLabel hint = new JLabel("Right-click resources to automate mining. Build Deployers and place Shipyards.");
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
            c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
            form.add(section, c);

            styleField(nameField); styleField(hostPortField); styleField(joinHostField); styleField(joinPortField);
            addRow(form, c, 1, "Commander", nameField);
            addRow(form, c, 2, "Host port", hostPortField);
            addRow(form, c, 3, "Join IP", joinHostField);
            addRow(form, c, 4, "Join port", joinPortField);

            JPanel buttons = new JPanel(new GridLayout(1, 3, 12, 0));
            buttons.setOpaque(false);
            JButton solo = new MenuButton("SOLO");
            JButton host = new MenuButton("HOST");
            JButton join = new MenuButton("JOIN");
            buttons.add(solo); buttons.add(host); buttons.add(join);
            c.gridx = 0; c.gridy = 5; c.gridwidth = 2; c.insets = new Insets(18, 8, 10, 8);
            form.add(buttons, c);

            statusLabel.setForeground(new Color(210, 228, 245));
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
            c.gridy = 6; c.insets = new Insets(8, 8, 8, 8);
            form.add(statusLabel, c);

            JPanel glass = new MenuCardPanel(new BorderLayout());
            glass.add(form, BorderLayout.CENTER);
            centerWrap.add(glass);
            add(centerWrap, BorderLayout.CENTER);

            JTextArea notes = new JTextArea("Controls: Right-click resource = auto-mine. 1 Prospector, 2 Deployer, 3 Hauler, 4 Scout, U load/place Shipyard.");
            notes.setEditable(false);
            notes.setLineWrap(true);
            notes.setWrapStyleWord(true);
            notes.setOpaque(false);
            notes.setForeground(new Color(150, 175, 205));
            notes.setFont(notes.getFont().deriveFont(13f));
            add(notes, BorderLayout.SOUTH);

            solo.addActionListener(e -> owner.launchGame(Config.solo(nameField.getText())));
            host.addActionListener(e -> {
                try { owner.launchGame(Config.host(nameField.getText(), parsePort(hostPortField.getText().trim()))); }
                catch (IllegalArgumentException ex) { setStatus(ex.getMessage()); }
            });
            join.addActionListener(e -> {
                try {
                    String hostName = joinHostField.getText().trim();
                    if (hostName.isBlank()) { setStatus("Join IP cannot be blank."); return; }
                    owner.launchGame(Config.join(nameField.getText(), hostName, parsePort(joinPortField.getText().trim())));
                } catch (IllegalArgumentException ex) { setStatus(ex.getMessage()); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setPaint(new GradientPaint(0, 0, new Color(4, 8, 15), w, h, new Color(12, 25, 44)));
            g2.fillRect(0, 0, w, h);
            g2.setColor(new Color(50, 130, 190, 38)); g2.fillOval(w - 360, -160, 520, 520);
            g2.setColor(new Color(160, 80, 255, 25)); g2.fillOval(-220, h - 320, 520, 420);
            for (int i = 0; i < 180; i++) {
                int x = Math.floorMod(i * 97 + 37, Math.max(w, 1));
                int y = Math.floorMod(i * 53 + 91, Math.max(h, 1));
                g2.setColor(new Color(180, 225, 255, 70 + (i % 4) * 35));
                g2.fillOval(x, y, i % 17 == 0 ? 2 : 1, i % 17 == 0 ? 2 : 1);
            }
            g2.setColor(new Color(80, 170, 255, 35));
            for (int x = -120; x < w + 120; x += 90) g2.drawLine(x, h, x + 260, 0);
            g2.dispose();
        }

        void setStatus(String status) { statusLabel.setText(status); }
        void requestFocusForName() { SwingUtilities.invokeLater(() -> { nameField.requestFocusInWindow(); nameField.selectAll(); }); }

        private void addRow(JPanel form, GridBagConstraints c, int row, String label, JComponent field) {
            JLabel jLabel = new JLabel(label);
            jLabel.setForeground(new Color(218, 235, 248));
            jLabel.setFont(jLabel.getFont().deriveFont(Font.BOLD, 13f));
            c.gridwidth = 1; c.weightx = 0; c.gridx = 0; c.gridy = row; form.add(jLabel, c);
            c.weightx = 1; c.gridx = 1; form.add(field, c);
        }

        private void styleField(JTextField field) {
            field.setForeground(Color.WHITE); field.setCaretColor(Color.WHITE); field.setBackground(new Color(9, 18, 31));
            field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(70, 115, 150)), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            field.setFont(field.getFont().deriveFont(Font.BOLD, 14f));
        }
    }

    static final class MenuCardPanel extends JPanel {
        MenuCardPanel(LayoutManager layout) { super(layout); setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(6, 12, 22, 218)); g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 26, 26);
            g2.setColor(new Color(80, 170, 225, 140)); g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 26, 26);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static final class MenuButton extends JButton {
        MenuButton(String text) {
            super(text); setFocusPainted(false); setBorderPainted(false); setContentAreaFilled(false); setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); setForeground(Color.WHITE);
            setFont(getFont().deriveFont(Font.BOLD, 15f)); setPreferredSize(new Dimension(120, 44));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ButtonModel model = getModel();
            Color top = model.isPressed() ? new Color(25, 90, 130) : model.isRollover() ? new Color(34, 128, 180) : new Color(18, 64, 100);
            Color bottom = model.isPressed() ? new Color(16, 52, 82) : model.isRollover() ? new Color(18, 86, 132) : new Color(9, 34, 62);
            g2.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bottom)); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g2.setColor(new Color(126, 220, 255)); g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static final class Config {
        final String localPlayerName; final boolean hostMode; final boolean soloMode; final boolean showLobby; final int localPort; final InetSocketAddress serverAddress;
        private Config(String name, boolean host, boolean solo, boolean lobby, int port, InetSocketAddress server) {
            localPlayerName = name; hostMode = host; soloMode = solo; showLobby = lobby; localPort = port; serverAddress = server;
        }
        static Config parse(String[] args) {
            if (args.length == 0) return new Config(defaultName(), false, false, true, 0, null);
            String name = defaultName(); boolean host = false, solo = false; int localPort = 0; InetSocketAddress server = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--name", "--id" -> name = require(args, ++i, args[i-1] + " needs a value");
                    case "--solo" -> solo = true;
                    case "--host" -> { host = true; localPort = parsePort(require(args, ++i, "--host needs a port")); }
                    case "--join" -> { String h = require(args, ++i, "--join needs host ip"); int p = parsePort(require(args, ++i, "--join needs peer port")); server = new InetSocketAddress(h, p); }
                    case "--local-port" -> localPort = parsePort(require(args, ++i, "--local-port needs a port"));
                    default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
                }
            }
            if (!host && server == null && !solo) return new Config(cleanName(name), false, false, true, 0, null);
            return new Config(cleanName(name), host, solo, false, localPort, server);
        }
        static Config solo(String name) { return new Config(cleanName(name), false, true, false, 0, null); }
        static Config host(String name, int port) { return new Config(cleanName(name), true, false, false, port, null); }
        static Config join(String name, String host, int port) { return new Config(cleanName(name), false, false, false, 0, new InetSocketAddress(host, port)); }
        String modeLabel() { return soloMode ? "Solo" : hostMode ? "Host" : serverAddress != null ? "Client" : "Lobby"; }
        private static String require(String[] args, int index, String message) { if (index >= args.length) throw new IllegalArgumentException(message); return args[index]; }
    }

    static final class GamePanel extends JPanel implements MouseListener, MouseMotionListener, MouseWheelListener, KeyListener {
        private final World world; private final PeerNetwork network; private final GameFrame owner; private final Set<Integer> keys = new HashSet<>();
        private final javax.swing.Timer timer; private double cameraX, cameraY, zoom = 1.0; private Point dragStart, dragNow; private long lastNanos = System.nanoTime();

        GamePanel(World world, PeerNetwork network, GameFrame owner) {
            this.world = world; this.network = network; this.owner = owner;
            setBackground(new Color(8, 12, 18)); setFocusable(true);
            addMouseListener(this); addMouseMotionListener(this); addMouseWheelListener(this); addKeyListener(this);
            timer = new javax.swing.Timer(16, e -> tick());
        }
        void start() { requestFocusInWindow(); timer.start(); }
        private void tick() {
            long now = System.nanoTime(); double dt = Math.min(0.05, (now - lastNanos) / 1_000_000_000.0); lastNanos = now;
            if (keys.contains(KeyEvent.VK_ESCAPE)) { timer.stop(); owner.showLobby("Returned to lobby."); return; }
            updateAutoCamera(dt);
            if (network != null) network.drainMessages();
            world.update(dt); repaint();
        }
        private void updateAutoCamera(double dt) {
            Rectangle2D bounds = world.localUnitBounds(); if (bounds == null) return;
            double fleetWidth = Math.max(bounds.getWidth(), 40), fleetHeight = Math.max(bounds.getHeight(), 40);
            double padding = Math.max(340, Math.max(fleetWidth, fleetHeight) * 0.55);
            double desiredZoom = Math.min(Math.max(500, getWidth() - 120) / (fleetWidth + padding), Math.max(360, getHeight() - 120) / (fleetHeight + padding));
            double targetZoom = clamp(desiredZoom, MIN_AUTO_ZOOM, MAX_AUTO_ZOOM);
            double visibleW = getWidth() / targetZoom, visibleH = getHeight() / targetZoom;
            double targetCameraX = bounds.getCenterX() - visibleW / 2.0, targetCameraY = bounds.getCenterY() - visibleH / 2.0;
            zoom = lerp(zoom, targetZoom, clamp(dt * 3.2, 0, 1));
            cameraX = lerp(cameraX, targetCameraX, clamp(dt * 3.8, 0, 1));
            cameraY = lerp(cameraY, targetCameraY, clamp(dt * 3.8, 0, 1));
            cameraX = clamp(cameraX, -160, Math.max(-160, world.width - getWidth()/zoom + 160));
            cameraY = clamp(cameraY, -160, Math.max(-160, world.height - getHeight()/zoom + 160));
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g); Graphics2D g2 = (Graphics2D) g.create(); g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            AffineTransform old = g2.getTransform(); g2.scale(zoom, zoom); g2.translate(-cameraX, -cameraY);
            drawMap(g2); world.draw(g2); drawSelectionBox(g2); g2.setTransform(old); drawHud(g2); g2.dispose();
        }
        private void drawMap(Graphics2D g2) {
            g2.setColor(new Color(9, 15, 24)); g2.fillRect(0, 0, world.width, world.height);
            g2.setColor(new Color(22, 33, 48));
            for (int x = 0; x <= world.width; x += 80) g2.drawLine(x, 0, x, world.height);
            for (int y = 0; y <= world.height; y += 80) g2.drawLine(0, y, world.width, y);
        }
        private void drawSelectionBox(Graphics2D g2) {
            if (dragStart == null || dragNow == null) return; Rectangle2D box = screenRectToWorldRect(dragStart, dragNow);
            g2.setColor(new Color(80, 170, 255, 60)); g2.fill(box); g2.setColor(new Color(120, 205, 255)); g2.draw(box);
        }
        private void drawHud(Graphics2D g2) {
            int leftHeight = 172 + world.playersSnapshot().size() * 18;
            g2.setColor(new Color(0, 0, 0, 175)); g2.fillRoundRect(12, 12, 1090, leftHeight, 14, 14);
            g2.setColor(Color.WHITE);
            g2.drawString("StarChem | Local: " + world.localPlayerLabel() + " | Selected: " + world.selectedCount(), 28, 36);
            g2.drawString("Right-click resource = auto-harvest | 1 Prospector | 2 Deployer | 3 Hauler | 4 Scout | U load/place Shipyard | ESC lobby", 28, 58);
            g2.drawString(network == null ? "Network: solo/offline" : network.statusLine(), 28, 80);
            g2.setColor(new Color(210, 230, 245)); g2.drawString(world.statusLine(), 28, 102); g2.drawString(world.buildHintLine(), 28, 124); g2.drawString(world.packageHintLine(), 28, 146);
            int y = 170;
            for (PlayerInfo player : world.playersSnapshot()) {
                g2.setColor(new Color(player.rgb)); g2.fillRect(28, y - 11, 12, 12); g2.setColor(Color.WHITE);
                g2.drawString(player.name + " - " + world.unitCountFor(player.id) + " ships / " + world.stationCountFor(player.id) + " stations" + (player.local ? "  (you)" : ""), 48, y); y += 18;
            }
            drawInfoPanel(g2);
        }
        private void drawInfoPanel(Graphics2D g2) {
            int panelW = 380, panelH = 430, x = getWidth() - panelW - 18, y = 18;
            g2.setColor(new Color(0, 0, 0, 178)); g2.fillRoundRect(x, y, panelW, panelH, 16, 16);
            g2.setColor(new Color(80, 170, 225, 180)); g2.drawRoundRect(x, y, panelW, panelH, 16, 16);
            int lineY = y + 26; g2.setColor(Color.WHITE); g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f)); g2.drawString("SELECTION / ECONOMY", x + 16, lineY); g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
            lineY += 24;
            for (String line : world.selectedShipInfoLines()) { g2.setColor(new Color(225, 240, 255)); g2.drawString(line, x + 16, lineY); lineY += 17; }
            lineY += 8;
            for (String line : world.selectedResourceInfoLines()) { g2.setColor(new Color(210, 235, 210)); g2.drawString(line, x + 16, lineY); lineY += 17; }
            lineY += 8;
            for (String line : world.stockpileInfoLines()) { g2.setColor(new Color(235, 225, 185)); g2.drawString(line, x + 16, lineY); lineY += 17; }
        }
        private Point2D screenToWorld(Point p) { return new Point2D.Double(p.x / zoom + cameraX, p.y / zoom + cameraY); }
        private Rectangle2D screenRectToWorldRect(Point a, Point b) {
            Point2D aw = screenToWorld(a), bw = screenToWorld(b); return new Rectangle2D.Double(Math.min(aw.getX(), bw.getX()), Math.min(aw.getY(), bw.getY()), Math.abs(aw.getX()-bw.getX()), Math.abs(aw.getY()-bw.getY()));
        }
        @Override public void mouseClicked(MouseEvent e) { }
        @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); if (SwingUtilities.isLeftMouseButton(e)) { dragStart = e.getPoint(); dragNow = e.getPoint(); } }
        @Override public void mouseReleased(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e) && dragStart != null) {
                Rectangle2D box = screenRectToWorldRect(dragStart, e.getPoint());
                if (box.getWidth() < 6 && box.getHeight() < 6) { Point2D p = screenToWorld(e.getPoint()); world.selectAt(p.getX(), p.getY()); } else world.selectBox(box);
                dragStart = null; dragNow = null;
            }
            if (SwingUtilities.isRightMouseButton(e)) {
                Point2D p = screenToWorld(e.getPoint());
                ResourceNode clickedResource = world.resourceAt(p.getX(), p.getY());
                if (clickedResource != null) {
                    List<HarvestCommand> commands = world.issueAutoHarvestSelected(clickedResource.id);
                    if (network != null) for (HarvestCommand command : commands) network.sendHarvest(command);
                } else {
                    List<MoveCommand> commands = world.issueMoveSelected(p.getX(), p.getY());
                    if (network != null) for (MoveCommand command : commands) network.sendMove(command);
                }
            }
        }
        private void triggerBuild(String shipType) {
            BuildCommand command = world.issueBuildShipRequest(shipType); if (command == null) return;
            if (network != null) network.sendBuild(command); else world.applyBuildShip(command.playerId, command.shipType);
        }
        private void triggerStationPackage() {
            StationCommand command = world.issueStationPackageCommand(); if (command == null) return;
            if (network != null) network.sendStationCommand(command); else world.applyStationCommand(command);
        }
        @Override public void mouseEntered(MouseEvent e) { } @Override public void mouseExited(MouseEvent e) { } @Override public void mouseDragged(MouseEvent e) { dragNow = e.getPoint(); } @Override public void mouseMoved(MouseEvent e) { } @Override public void mouseWheelMoved(MouseWheelEvent e) { } @Override public void keyTyped(KeyEvent e) { }
        @Override public void keyPressed(KeyEvent e) {
            keys.add(e.getKeyCode());
            if (e.getKeyCode() == KeyEvent.VK_1) triggerBuild("prospector");
            if (e.getKeyCode() == KeyEvent.VK_2) triggerBuild("station_builder");
            if (e.getKeyCode() == KeyEvent.VK_3) triggerBuild("hauler");
            if (e.getKeyCode() == KeyEvent.VK_4) triggerBuild("scout");
            if (e.getKeyCode() == KeyEvent.VK_U) triggerStationPackage();
        }
        @Override public void keyReleased(KeyEvent e) { keys.remove(e.getKeyCode()); }
    }

    static final class World {
        final int width = 2200, height = 1400;
        final List<ResourceNode> resources = new ArrayList<>();
        private final Map<String, PlayerInfo> players = new LinkedHashMap<>();
        private final Map<String, Unit> units = new LinkedHashMap<>();
        private final Map<String, Station> stations = new LinkedHashMap<>();
        private final Map<String, EnumMap<Material, Double>> stockpiles = new LinkedHashMap<>();
        private final Random random = new Random(1977);
        private String localPlayerId = "", localPlayerName = "Waiting"; private int selectedResourceId = -1; private String statusLine = "Right-click a resource with a ship selected to auto-harvest.";
        World() { seedResources(); }
        private void seedResources() {
            resources.add(new ResourceNode(1, "Silicate Rock - Iron Vein", NodeKind.SILICATE_ROCK, Material.IRON, 620, 370, 260, 7.5, 32));
            resources.add(new ResourceNode(2, "Silicate Rock - Copper Vein", NodeKind.SILICATE_ROCK, Material.COPPER, 1010, 610, 180, 6.5, 28));
            resources.add(new ResourceNode(3, "Ice-Rich Silicate Rock", NodeKind.SILICATE_ROCK, Material.ICE, 1380, 330, 220, 7.0, 31));
            resources.add(new ResourceNode(4, "Fractured Silicate Cluster", NodeKind.SILICATE_ROCK, Material.SILICATES, 1660, 1000, 320, 8.0, 36));
            resources.add(new ResourceNode(5, "Hydrogen Gas Cloud", NodeKind.GAS_CLOUD, Material.HYDROGEN, 890, 980, 360, 9.0, 58));
            resources.add(new ResourceNode(6, "Helium Pocket", NodeKind.GAS_CLOUD, Material.HELIUM, 1830, 520, 210, 7.5, 52));
            resources.add(new ResourceNode(7, "Methane-Ammonia Cloud", NodeKind.GAS_CLOUD, Material.METHANE, 420, 1060, 240, 7.5, 55));
            resources.add(new ResourceNode(8, "Ammonia Trace Cloud", NodeKind.GAS_CLOUD, Material.AMMONIA, 1250, 1130, 180, 6.5, 50));
        }
        synchronized void startSolo(String requestedName) { addPlayerWithGroup("SOLO", uniqueName(requestedName), PALETTE[0], true); }
        synchronized void addPlayerWithGroup(String playerId, String name, int rgb, boolean local) {
            players.put(playerId, new PlayerInfo(playerId, name, rgb, local)); ensureEconomy(playerId);
            if (local) { localPlayerId = playerId; localPlayerName = name; }
            spawnStartingShip(playerId);
        }
        synchronized void addOrUpdatePlayer(String playerId, String name, int rgb, boolean local) {
            players.put(playerId, new PlayerInfo(playerId, name, rgb, local)); ensureEconomy(playerId);
            if (local) { localPlayerId = playerId; localPlayerName = name; }
        }
        private void ensureEconomy(String playerId) { stockpileFor(playerId); if (stations.values().stream().noneMatch(s -> s.playerId.equals(playerId))) addStation(playerId, RULES.defaultStationType, stationPoint(playerId)); }
        private Station addStation(String playerId, String type, Point2D p) {
            String id = playerId + ":S" + (stationCountFor(playerId) + 1); Station station = new Station(id, playerId, type, p.getX(), p.getY()); stations.put(id, station); return station;
        }
        private void spawnStartingShip(String playerId) { spawnShip(playerId, RULES.startingShipType, spawnPoint(playerSlot(playerId))); }
        private Unit spawnShip(String playerId, String shipType, Point2D p) {
            PlayerInfo player = players.get(playerId); int rgb = player == null ? Color.WHITE.getRGB() : player.rgb;
            int unitId = nextUnitId(playerId); Unit unit = new Unit(playerId, unitId, shipType, p.getX(), p.getY(), rgb); units.put(unit.key(), unit); return unit;
        }
        synchronized void removePlayer(String playerId) { players.remove(playerId); units.values().removeIf(u -> u.playerId.equals(playerId)); stations.values().removeIf(s -> s.playerId.equals(playerId)); stockpiles.remove(playerId); if (playerId.equals(localPlayerId)) { localPlayerId = ""; localPlayerName = "Disconnected"; } }
        synchronized String uniqueName(String requested) {
            String base = cleanName(requested); if (base.isBlank()) base = "Player"; String candidate = base; int suffix = 2; Set<String> names = new HashSet<>(); for (PlayerInfo p : players.values()) names.add(p.name.toLowerCase(Locale.ROOT)); while (names.contains(candidate.toLowerCase(Locale.ROOT))) candidate = base + " " + suffix++; return candidate;
        }
        synchronized void update(double dt) {
            for (ResourceNode node : resources) node.updateRespawn(dt, this);
            for (Unit unit : new ArrayList<>(units.values())) updateUnit(unit, dt);
        }
        private void updateUnit(Unit u, double dt) {
            u.unloadingThisFrame = false;
            autoUnloadAtStation(u, dt);
            ShipDef def = u.def();
            switch (u.task) {
                case AUTO_HARVEST -> updateAutoHarvest(u, def, dt);
                case RETURN_TO_STATION -> updateReturnToStation(u);
                case IDLE -> updateIdleOrbit(u, def, dt);
                case MOVE -> { if (distance(u.x, u.y, u.targetX, u.targetY) < 5) u.task = UnitTask.IDLE; }
            }
            u.updatePosition(dt, width, height);
        }
        private void updateAutoHarvest(Unit u, ShipDef def, double dt) {
            ResourceNode node = findResource(u.automationResourceId);
            if (node == null || !node.active) { u.task = UnitTask.IDLE; u.automationResourceId = -1; return; }
            if (u.freeCargo() <= 0.05) { sendToNearestStation(u); return; }
            if (!def.canHarvest.contains(node.kind)) { u.task = UnitTask.IDLE; return; }
            double dist = distance(u.x, u.y, node.x, node.y);
            double harvestRange = def.harvestRange + node.radius;
            if (dist <= harvestRange && node.amount > 0.05) {
                double harvested = Math.min(node.harvestRate * dt, node.amount); harvested = Math.min(harvested, u.freeCargo());
                if (harvested > 0) { node.amount -= harvested; u.addCargo(node.material, harvested); }
                if (node.amount <= 0.05) { node.deplete(); u.task = UnitTask.IDLE; u.automationResourceId = -1; statusLine = node.name + " depleted. Deposit will relocate."; return; }
                orbitAround(u, node.x, node.y, node.radius + def.orbitRadius, dt, 0.7);
            } else {
                moveTowardOrbit(u, node.x, node.y, node.radius + def.orbitRadius);
            }
        }
        private void updateReturnToStation(Unit u) {
            Station station = nearestStation(u.playerId, u.x, u.y); if (station == null) { u.task = UnitTask.IDLE; return; }
            if (u.cargoUsed() <= 0.05) {
                ResourceNode resume = findResource(u.automationResourceId);
                if (resume != null && resume.active) u.task = UnitTask.AUTO_HARVEST; else u.task = UnitTask.IDLE;
                return;
            }
            moveTowardOrbit(u, station.x, station.y, station.def().unloadRange * 0.55);
        }
        private void updateIdleOrbit(Unit u, ShipDef def, double dt) {
            Station station = nearestStation(u.playerId, u.x, u.y); if (station == null) return;
            if (distance(u.x, u.y, station.x, station.y) < station.def().unloadRange + 170) orbitAround(u, station.x, station.y, def.idleStationOrbitRadius, dt, 0.35);
        }
        private void orbitAround(Unit u, double cx, double cy, double radius, double dt, double speed) {
            u.orbitAngle += dt * speed * (u.unitId % 2 == 0 ? 1 : -1);
            u.orbitRetarget -= dt;
            if (u.orbitRetarget <= 0 || distance(u.x, u.y, u.targetX, u.targetY) < 12) {
                u.targetX = clamp(cx + Math.cos(u.orbitAngle) * radius, 0, width);
                u.targetY = clamp(cy + Math.sin(u.orbitAngle) * radius, 0, height);
                u.orbitRetarget = 1.1;
            }
        }
        private void moveTowardOrbit(Unit u, double cx, double cy, double radius) {
            double angle = Math.atan2(u.y - cy, u.x - cx); if (Double.isNaN(angle)) angle = u.unitId;
            u.targetX = clamp(cx + Math.cos(angle) * radius, 0, width); u.targetY = clamp(cy + Math.sin(angle) * radius, 0, height);
        }
        private void sendToNearestStation(Unit u) { Station station = nearestStation(u.playerId, u.x, u.y); if (station == null) return; u.task = UnitTask.RETURN_TO_STATION; moveTowardOrbit(u, station.x, station.y, station.def().unloadRange * 0.55); }
        private void autoUnloadAtStation(Unit unit, double dt) {
            if (unit.cargoUsed() <= 0.05) return; Station station = nearestStation(unit.playerId, unit.x, unit.y); if (station == null || distance(unit.x, unit.y, station.x, station.y) > station.def().unloadRange) return;
            double remaining = Math.min(station.def().unloadRate * dt, unit.cargoUsed()); EnumMap<Material, Double> stockpile = stockpileFor(unit.playerId);
            for (Material material : Material.values()) {
                if (remaining <= 0.001) break; double held = unit.inventory.getOrDefault(material, 0.0); if (held <= 0.001) continue;
                double take = Math.min(held, remaining); unit.inventory.put(material, held - take); if (unit.inventory.getOrDefault(material, 0.0) <= 0.05) unit.inventory.remove(material);
                stockpile.put(material, stockpile.getOrDefault(material, 0.0) + take); remaining -= take; unit.unloadingThisFrame = true;
            }
            if (unit.unloadingThisFrame && unit.playerId.equals(localPlayerId)) statusLine = "Auto-unloading cargo at station.";
        }
        synchronized void draw(Graphics2D g2) {
            for (Station station : stations.values()) station.draw(g2, players.get(station.playerId), stockpileFor(station.playerId), station.playerId.equals(localPlayerId));
            for (ResourceNode node : resources) node.draw(g2, node.id == selectedResourceId);
            for (Unit u : units.values()) {
                ResourceNode node = findResource(u.automationResourceId); if (u.task == UnitTask.AUTO_HARVEST && node != null) u.drawHarvestVisual(g2, node, u.playerId.equals(localPlayerId));
                Station station = nearestStation(u.playerId, u.x, u.y); if (u.unloadingThisFrame && station != null) u.drawUnloadVisual(g2, station, u.playerId.equals(localPlayerId));
                u.drawMoveOrder(g2, u.playerId.equals(localPlayerId));
            }
            for (Unit u : units.values()) { PlayerInfo player = players.get(u.playerId); u.draw(g2, player == null ? u.playerId : player.name); }
        }
        synchronized ResourceNode resourceAt(double x, double y) { ResourceNode best = null; double bd = Double.MAX_VALUE; for (ResourceNode n : resources) if (n.active) { double d = distance(x, y, n.x, n.y); if (d <= n.radius + 14 && d < bd) { best = n; bd = d; } } return best; }
        synchronized void selectAt(double x, double y) { ResourceNode node = resourceAt(x, y); if (node != null) { selectedResourceId = node.id; statusLine = "Targeted " + node.name + ". Right-click it with a ship selected to automate mining."; return; } selectSingle(x, y); }
        synchronized void selectSingle(double x, double y) {
            Unit best = null; double bd = Double.MAX_VALUE; for (Unit u : units.values()) if (u.playerId.equals(localPlayerId)) { double d = distance(x, y, u.x, u.y); if (d < 28 && d < bd) { best = u; bd = d; } }
            for (Unit u : units.values()) u.selected = false; if (best != null) { best.selected = true; statusLine = "Selected " + best.def().displayName + " #" + best.unitId + "."; }
        }
        synchronized void selectBox(Rectangle2D box) { for (Unit u : units.values()) u.selected = u.playerId.equals(localPlayerId) && box.contains(u.x, u.y); statusLine = selectedCount() + " ship(s) selected."; }
        synchronized List<MoveCommand> issueMoveSelected(double x, double y) {
            List<Unit> selected = localSelectedUnits(); List<MoveCommand> commands = new ArrayList<>(); if (selected.isEmpty()) { statusLine = "No ship selected."; return commands; }
            int count = selected.size(); int cols = (int)Math.ceil(Math.sqrt(count)); int rows = (int)Math.ceil(count / (double)cols); double spacing = 42, centerCol = (cols - 1) / 2.0, centerRow = (rows - 1) / 2.0;
            for (int i=0;i<count;i++) { Unit u = selected.get(i); int col = i % cols, row = i / cols; double tx = x + (col - centerCol) * spacing, ty = y + (row - centerRow) * spacing; u.moveTo(tx, ty); commands.add(new MoveCommand(localPlayerId, u.unitId, tx, ty)); }
            statusLine = "Move order issued."; return commands;
        }
        synchronized List<HarvestCommand> issueAutoHarvestSelected(int resourceId) {
            ResourceNode node = findResource(resourceId); List<HarvestCommand> commands = new ArrayList<>(); if (node == null || !node.active) { statusLine = "That resource is not available."; return commands; }
            for (Unit u : localSelectedUnits()) {
                if (u.def().canHarvest.contains(node.kind)) { u.startAutoHarvest(resourceId); commands.add(new HarvestCommand(localPlayerId, u.unitId, resourceId)); }
            }
            statusLine = commands.isEmpty() ? "Selected ship cannot harvest that node." : "Auto-harvest order: " + node.name + "."; return commands;
        }
        private List<Unit> localSelectedUnits() { List<Unit> out = new ArrayList<>(); for (Unit u : units.values()) if (u.playerId.equals(localPlayerId) && u.selected) out.add(u); return out; }
        synchronized BuildCommand issueBuildShipRequest(String shipType) {
            ShipDef def = RULES.ship(shipType); if (!hasStationThatCanBuild(localPlayerId, shipType)) { statusLine = "Need a station that can build " + def.displayName + "."; return null; }
            if (!canAfford(localPlayerId, def.buildCost)) { statusLine = "Need resources for " + def.displayName + ": " + formatCost(def.buildCost); return null; }
            statusLine = "Build requested: " + def.displayName + "."; return new BuildCommand(localPlayerId, shipType);
        }
        synchronized boolean applyBuildShip(String playerId, String shipType) {
            ShipDef def = RULES.ship(shipType); Station station = firstStationThatCanBuild(playerId, shipType); if (station == null || !canAfford(playerId, def.buildCost)) return false;
            spend(stockpileFor(playerId), def.buildCost); int unitId = nextUnitId(playerId); double a = unitId * 1.35; Point2D p = new Point2D.Double(clamp(station.x + Math.cos(a) * (station.def().buildRadius + 35), 0, width), clamp(station.y + Math.sin(a) * (station.def().buildRadius + 35), 0, height));
            spawnShip(playerId, shipType, p); if (playerId.equals(localPlayerId)) statusLine = "Built " + def.displayName + "."; return true;
        }
        synchronized StationCommand issueStationPackageCommand() {
            Unit u = selectedLocalUnit(); if (u == null || !u.def().stationBuilder) { statusLine = "Select a Deployer first."; return null; }
            if (u.stationPackageType == null || u.stationPackageType.isBlank()) return new StationCommand(localPlayerId, u.unitId, "LOAD", "shipyard");
            return new StationCommand(localPlayerId, u.unitId, "PLACE", u.stationPackageType);
        }
        synchronized boolean applyStationCommand(StationCommand cmd) {
            Unit u = units.get(Unit.key(cmd.playerId, cmd.unitId)); if (u == null || !u.def().stationBuilder) return false;
            if ("LOAD".equals(cmd.mode)) {
                if (u.stationPackageType != null && !u.stationPackageType.isBlank()) return false; Station near = nearestStation(cmd.playerId, u.x, u.y); if (near == null || distance(u.x, u.y, near.x, near.y) > near.def().unloadRange) { if (cmd.playerId.equals(localPlayerId)) statusLine = "Move Deployer near an Outpost to load Shipyard package."; return false; }
                StationDef nearDef = near.def(); StationDef packageDef = RULES.station(cmd.stationType); if (!nearDef.canBuildStationPackages.contains(cmd.stationType)) { if (cmd.playerId.equals(localPlayerId)) statusLine = nearDef.displayName + " cannot fabricate " + packageDef.displayName + "."; return false; }
                if (!canAfford(cmd.playerId, packageDef.buildCost)) { if (cmd.playerId.equals(localPlayerId)) statusLine = "Need resources for Shipyard package: " + formatCost(packageDef.buildCost); return false; }
                spend(stockpileFor(cmd.playerId), packageDef.buildCost); u.stationPackageType = cmd.stationType; if (cmd.playerId.equals(localPlayerId)) statusLine = "Loaded Shipyard package into Deployer. Move it and press U to place."; return true;
            }
            if ("PLACE".equals(cmd.mode)) {
                if (u.stationPackageType == null || u.stationPackageType.isBlank()) return false; StationDef packageDef = RULES.station(u.stationPackageType); if (packageDef.mustBeCarriedByShipType != null && !packageDef.mustBeCarriedByShipType.equals(u.shipType)) return false;
                addStation(cmd.playerId, u.stationPackageType, new Point2D.Double(u.x, u.y)); if (packageDef.carrierIsRemovedAfterPlacement || u.def().singleUseAfterStationPlacement) units.remove(u.key()); else u.stationPackageType = "";
                if (cmd.playerId.equals(localPlayerId)) statusLine = "Shipyard placed. Deployer consumed. Hauler and Scout unlocked."; return true;
            }
            return false;
        }
        synchronized void applyAuthorizedMove(MoveCommand c) { Unit u = units.get(Unit.key(c.playerId, c.unitId)); if (u != null) u.moveTo(c.x, c.y); }
        synchronized void applyAuthorizedHarvest(HarvestCommand c) { Unit u = units.get(Unit.key(c.playerId, c.unitId)); ResourceNode n = findResource(c.resourceId); if (u != null && n != null && n.active && u.def().canHarvest.contains(n.kind)) u.startAutoHarvest(c.resourceId); }
        synchronized Snapshot createSnapshot(long seq) {
            List<PlayerInfo> ps = new ArrayList<>(players.values()); List<UnitState> us = new ArrayList<>(); for (Unit u: units.values()) us.add(u.state());
            List<ResourceState> rs = new ArrayList<>(); for (ResourceNode r: resources) rs.add(r.state());
            List<StationState> ss = new ArrayList<>(); for (Station s: stations.values()) ss.add(s.state());
            List<StockpileState> st = new ArrayList<>(); for (Map.Entry<String, EnumMap<Material, Double>> e: stockpiles.entrySet()) st.add(new StockpileState(e.getKey(), encodeInventory(e.getValue())));
            return new Snapshot(seq, ps, us, rs, ss, st);
        }
        synchronized void applySnapshot(Snapshot snap, String knownLocalPlayerId) {
            Set<String> playerIds = new LinkedHashSet<>(); for (PlayerInfo p : snap.players) { boolean local = p.id.equals(knownLocalPlayerId); addOrUpdatePlayer(p.id, p.name, p.rgb, local); playerIds.add(p.id); }
            players.keySet().removeIf(id -> !playerIds.contains(id)); stockpiles.keySet().removeIf(id -> !playerIds.contains(id));
            Set<String> unitKeys = new LinkedHashSet<>(); for (UnitState s : snap.units) { unitKeys.add(Unit.key(s.playerId, s.unitId)); Unit u = units.get(Unit.key(s.playerId, s.unitId)); int rgb = players.containsKey(s.playerId) ? players.get(s.playerId).rgb : Color.GRAY.getRGB(); if (u == null) { u = new Unit(s.playerId, s.unitId, s.shipType, s.x, s.y, rgb); units.put(u.key(), u); } u.rgb = rgb; u.applySnapshot(s); }
            units.keySet().removeIf(k -> !unitKeys.contains(k));
            resources.clear(); for (ResourceState r : snap.resources) resources.add(ResourceNode.fromState(r));
            stations.clear(); for (StationState s : snap.stations) stations.put(s.id, Station.fromState(s));
            for (StockpileState s : snap.stockpiles) { EnumMap<Material, Double> stock = stockpileFor(s.playerId); stock.clear(); decodeInventoryInto(s.cargo, stock); }
        }
        synchronized String statusLine() { return statusLine; }
        synchronized String localPlayerLabel() { return localPlayerName; }
        synchronized List<PlayerInfo> playersSnapshot() { return new ArrayList<>(players.values()); }
        synchronized int selectedCount() { int c=0; for(Unit u: units.values()) if(u.selected)c++; return c; }
        synchronized int unitCountFor(String playerId) { int c=0; for(Unit u: units.values()) if(u.playerId.equals(playerId))c++; return c; }
        synchronized int stationCountFor(String playerId) { int c=0; for(Station s: stations.values()) if(s.playerId.equals(playerId))c++; return c; }
        synchronized Rectangle2D localUnitBounds() { boolean found=false; double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE; for(Unit u:units.values()) if(u.playerId.equals(localPlayerId)){found=true; minX=Math.min(minX,u.x);minY=Math.min(minY,u.y);maxX=Math.max(maxX,u.x);maxY=Math.max(maxY,u.y);} for(Station s:stations.values()) if(s.playerId.equals(localPlayerId)){found=true; minX=Math.min(minX,s.x);minY=Math.min(minY,s.y);maxX=Math.max(maxX,s.x);maxY=Math.max(maxY,s.y);} return found?new Rectangle2D.Double(minX,minY,Math.max(1,maxX-minX),Math.max(1,maxY-minY)):null; }
        synchronized List<String> selectedShipInfoLines() {
            Unit u = selectedLocalUnit(); List<String> lines = new ArrayList<>(); if(u==null){lines.add("Ship: none selected"); lines.add("Right-click resource with ship selected."); return lines;} ShipDef d=u.def(); lines.add("Ship: #"+u.unitId+" " + d.displayName); lines.add("Task: "+u.task); lines.add(String.format(Locale.ROOT,"Cargo: %.1f / %.0f",u.cargoUsed(),d.cargoCapacity)); if(u.stationPackageType!=null&&!u.stationPackageType.isBlank()) lines.add("Package: "+RULES.station(u.stationPackageType).displayName); lines.add("Inventory:"); boolean empty=true; for(Material m:Material.values()){double a=u.inventory.getOrDefault(m,0.0); if(a>0.05){lines.add("  "+m.label+": "+round(a)); empty=false;}} if(empty)lines.add("  Empty"); return lines;
        }
        synchronized List<String> selectedResourceInfoLines() { ResourceNode n=findResource(selectedResourceId); List<String> lines=new ArrayList<>(); if(n==null||!n.active){lines.add("Target: none"); return lines;} lines.add("Target: "+n.name); lines.add("Type: "+(n.kind==NodeKind.GAS_CLOUD?"Gas cloud":"Silicate rock")); lines.add("Contains: "+n.material.label); lines.add(String.format(Locale.ROOT,"Remaining: %.1f / %.0f",n.amount,n.maxAmount)); return lines; }
        synchronized List<String> stockpileInfoLines() { List<String> lines=new ArrayList<>(); EnumMap<Material,Double> stock=stockpileFor(localPlayerId); lines.add("Station Stockpile:"); boolean empty=true; for(Material m:Material.values()){double a=stock.getOrDefault(m,0.0); if(a>0.05){lines.add("  "+m.label+": "+round(a)); empty=false;}} if(empty)lines.add("  Empty"); return lines; }
        synchronized String buildHintLine() { return "Build: 1 Prospector " + formatCost(RULES.ship("prospector").buildCost) + " | 2 Deployer " + formatCost(RULES.ship("station_builder").buildCost) + " | 3 Hauler | 4 Scout"; }
        synchronized String packageHintLine() { return "Shipyard package [U]: " + formatCost(RULES.station("shipyard").buildCost) + " | Load into Deployer, press U again to place."; }
        private boolean hasStationThatCanBuild(String playerId,String shipType){return firstStationThatCanBuild(playerId,shipType)!=null;} private Station firstStationThatCanBuild(String playerId,String shipType){for(Station s:stations.values())if(s.playerId.equals(playerId)&&s.def().canBuildShips.contains(shipType))return s;return null;}
        private boolean canAfford(String playerId, EnumMap<Material,Double> cost){EnumMap<Material,Double> stock=stockpileFor(playerId); for(Map.Entry<Material,Double> e:cost.entrySet()) if(stock.getOrDefault(e.getKey(),0.0)+0.0001<e.getValue()) return false; return true;} private void spend(EnumMap<Material,Double> stock, EnumMap<Material,Double> cost){for(Map.Entry<Material,Double> e:cost.entrySet()){double next=stock.getOrDefault(e.getKey(),0.0)-e.getValue(); if(next<=0.05)stock.remove(e.getKey()); else stock.put(e.getKey(),next);}}
        private String formatCost(EnumMap<Material,Double> cost){if(cost.isEmpty())return "free"; StringBuilder b=new StringBuilder(); for(Map.Entry<Material,Double> e:cost.entrySet()){if(b.length()>0)b.append(", "); b.append(round(e.getValue())).append(' ').append(e.getKey().label);} return b.toString();}
        private Unit selectedLocalUnit(){for(Unit u:units.values()) if(u.playerId.equals(localPlayerId)&&u.selected)return u; return null;} private ResourceNode findResource(int id){for(ResourceNode n:resources) if(n.id==id)return n; return null;} private Station nearestStation(String playerId,double x,double y){Station best=null; double bd=Double.MAX_VALUE; for(Station s:stations.values()) if(s.playerId.equals(playerId)){double d=distance(x,y,s.x,s.y); if(d<bd){best=s;bd=d;}} return best;} private EnumMap<Material,Double> stockpileFor(String playerId){return stockpiles.computeIfAbsent(playerId,k->new EnumMap<>(Material.class));}
        private int nextUnitId(String playerId){int max=0; for(Unit u:units.values()) if(u.playerId.equals(playerId)) max=Math.max(max,u.unitId); return max+1;}
        void relocateResource(ResourceNode node){for(int attempt=0;attempt<120;attempt++){double x=140+random.nextDouble()*(width-280); double y=140+random.nextDouble()*(height-280); if(validResourceSpot(node.id,x,y)){node.x=x;node.y=y;node.amount=node.maxAmount;node.active=true;node.respawnTimer=0;return;}} node.x=width/2.0;node.y=height/2.0;node.amount=node.maxAmount;node.active=true;}
        private boolean validResourceSpot(int nodeId,double x,double y){for(Station s:stations.values()) if(distance(x,y,s.x,s.y)<MIN_RESOURCE_STATION_DISTANCE) return false; for(ResourceNode n:resources) if(n.id!=nodeId&&n.active&&distance(x,y,n.x,n.y)<MIN_RESOURCE_NODE_DISTANCE)return false; return true;}
    }

    static final class ResourceNode {
        final int id; final String name; final NodeKind kind; final Material material; final double maxAmount; final double harvestRate; final double radius; double x,y,amount,respawnTimer; boolean active=true;
        ResourceNode(int id,String name,NodeKind kind,Material material,double x,double y,double maxAmount,double harvestRate,double radius){this.id=id;this.name=name;this.kind=kind;this.material=material;this.x=x;this.y=y;this.maxAmount=maxAmount;this.harvestRate=harvestRate;this.radius=radius;this.amount=maxAmount;}
        void deplete(){active=false;amount=0;respawnTimer=RESOURCE_RESPAWN_SECONDS+DEPLETED_HIDE_SECONDS;}
        void updateRespawn(double dt,World world){if(active)return; respawnTimer-=dt; if(respawnTimer<=0) world.relocateResource(this);}
        void draw(Graphics2D g2, boolean selected){if(!active)return; Graphics2D r=(Graphics2D)g2.create(); r.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); if(kind==NodeKind.GAS_CLOUD)drawGas(r,selected); else drawRock(r,selected); drawAmountBar(r); r.dispose();}
        private void drawRock(Graphics2D g2,boolean selected){Polygon poly=new Polygon(); for(int i=0;i<9;i++){double a=-Math.PI/2+i*Math.PI*2/9; double w=0.78+(Math.floorMod(id*31+i*17,30)/100.0); poly.addPoint((int)Math.round(x+Math.cos(a)*radius*w),(int)Math.round(y+Math.sin(a)*radius*w));} g2.setColor(new Color(70,68,63));g2.fillPolygon(poly);g2.setColor(material.color);g2.setStroke(new BasicStroke(2f));g2.drawPolygon(poly);g2.setColor(new Color(material.color.getRed(),material.color.getGreen(),material.color.getBlue(),85));g2.fillOval((int)(x-radius*.45),(int)(y-radius*.45),(int)radius,(int)radius); if(selected){g2.setColor(new Color(255,245,140,210));g2.setStroke(new BasicStroke(2.4f));g2.drawOval((int)(x-radius-10),(int)(y-radius-10),(int)(radius*2+20),(int)(radius*2+20));}}
        private void drawGas(Graphics2D g2,boolean selected){for(int i=0;i<7;i++){double a=i*Math.PI*2/7.0;double ox=Math.cos(a)*radius*.25,oy=Math.sin(a)*radius*.22;g2.setColor(new Color(material.color.getRed(),material.color.getGreen(),material.color.getBlue(),42+i*8));g2.fillOval((int)(x+ox-radius*.55),(int)(y+oy-radius*.42),(int)(radius*1.1),(int)(radius*.84));}g2.setColor(new Color(material.color.getRed(),material.color.getGreen(),material.color.getBlue(),150));g2.setStroke(new BasicStroke(1.8f));g2.drawOval((int)(x-radius*.8),(int)(y-radius*.62),(int)(radius*1.6),(int)(radius*1.24)); if(selected){g2.setColor(new Color(255,245,140,210));g2.setStroke(new BasicStroke(2.4f));g2.drawOval((int)(x-radius-12),(int)(y-radius-12),(int)(radius*2+24),(int)(radius*2+24));}}
        private void drawAmountBar(Graphics2D g2){int w=64,h=6,bx=(int)(x-w/2.0),by=(int)(y+radius+12);double pct=maxAmount<=0?0:amount/maxAmount;g2.setColor(new Color(0,0,0,150));g2.fillRoundRect(bx,by,w,h,6,6);g2.setColor(material.color);g2.fillRoundRect(bx,by,(int)Math.round(w*pct),h,6,6);}
        ResourceState state(){return new ResourceState(id,name,kind.name(),material.name(),x,y,maxAmount,harvestRate,radius,amount,active,respawnTimer);} static ResourceNode fromState(ResourceState s){ResourceNode n=new ResourceNode(s.id,s.name,NodeKind.valueOf(s.kind),Material.valueOf(s.material),s.x,s.y,s.maxAmount,s.harvestRate,s.radius);n.amount=s.amount;n.active=s.active;n.respawnTimer=s.respawnTimer;return n;}
    }

    static final class Station { final String id,playerId,type; double x,y; Station(String id,String playerId,String type,double x,double y){this.id=id;this.playerId=playerId;this.type=type;this.x=x;this.y=y;} StationDef def(){return RULES.station(type);} void draw(Graphics2D g2,PlayerInfo player,EnumMap<Material,Double> stockpile,boolean local){Graphics2D s=(Graphics2D)g2.create();s.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);Color c=new Color(player==null?Color.GRAY.getRGB():player.rgb);StationDef d=def();s.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),local?42:25));s.fillOval((int)(x-d.unloadRange),(int)(y-d.unloadRange),(int)(d.unloadRange*2),(int)(d.unloadRange*2));s.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),local?110:70));s.setStroke(new BasicStroke(1.4f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,0,new float[]{10f,8f},0));s.drawOval((int)(x-d.unloadRange),(int)(y-d.unloadRange),(int)(d.unloadRange*2),(int)(d.unloadRange*2));double radius=type.equals("shipyard")?82:64;Polygon hull=new Polygon();for(int i=0;i<6;i++){double a=Math.PI/6+i*Math.PI*2/6.0;hull.addPoint((int)Math.round(x+Math.cos(a)*radius),(int)Math.round(y+Math.sin(a)*radius));}s.setColor(new Color(20,29,42));s.fillPolygon(hull);s.setColor(c);s.setStroke(new BasicStroke(3f));s.drawPolygon(hull);s.setColor(new Color(125,205,255,90));s.fillOval((int)(x-26),(int)(y-26),52,52);s.setColor(Color.WHITE);String label=(player==null?playerId:player.name)+" "+d.displayName; s.setFont(s.getFont().deriveFont(Font.BOLD,12f)); FontMetrics fm=s.getFontMetrics(); int tw=fm.stringWidth(label);s.setColor(new Color(0,0,0,160));s.fillRoundRect((int)(x-tw/2.0-6),(int)(y-radius-32),tw+12,18,8,8);s.setColor(Color.WHITE);s.drawString(label,(int)(x-tw/2.0),(int)(y-radius-18));s.dispose();} StationState state(){return new StationState(id,playerId,type,x,y);} static Station fromState(StationState s){return new Station(s.id,s.playerId,s.type,s.x,s.y);} }

    static final class Unit { final String playerId; final int unitId; String shipType; double x,y,targetX,targetY,orbitAngle,orbitRetarget; int rgb,automationResourceId=-1; boolean selected,unloadingThisFrame; UnitTask task=UnitTask.IDLE; final EnumMap<Material,Double> inventory=new EnumMap<>(Material.class); String stationPackageType=""; double hp;
        Unit(String playerId,int unitId,String shipType,double x,double y,int rgb){this.playerId=playerId;this.unitId=unitId;this.shipType=shipType;this.x=x;this.y=y;this.targetX=x;this.targetY=y;this.rgb=rgb;this.hp=def().maxHp;this.orbitAngle=unitId;}
        ShipDef def(){return RULES.ship(shipType);} static String key(String playerId,int unitId){return playerId+":"+unitId;} String key(){return key(playerId,unitId);} void moveTo(double x,double y){targetX=x;targetY=y;task=UnitTask.MOVE;automationResourceId=-1;} void startAutoHarvest(int resourceId){automationResourceId=resourceId;task=UnitTask.AUTO_HARVEST;}
        double cargoUsed(){double t=0;for(double v:inventory.values())t+=v;return t;} double freeCargo(){return Math.max(0,def().cargoCapacity-cargoUsed());} void addCargo(Material m,double a){inventory.put(m,inventory.getOrDefault(m,0.0)+a);} void updatePosition(double dt,int mapW,int mapH){double dx=targetX-x,dy=targetY-y,d=Math.hypot(dx,dy);if(d>2){double step=Math.min(d,def().speed*dt);x+=dx/d*step;y+=dy/d*step;}x=clamp(x,0,mapW);y=clamp(y,0,mapH);}
        void applySnapshot(UnitState s){double err=distance(x,y,s.x,s.y);if(err>25){x=s.x;y=s.y;}else{x=x*.65+s.x*.35;y=y*.65+s.y*.35;}shipType=s.shipType;targetX=s.targetX;targetY=s.targetY;task=UnitTask.valueOf(s.task);automationResourceId=s.automationResourceId;stationPackageType=s.stationPackageType;decodeInventoryInto(s.cargo,inventory);}
        UnitState state(){return new UnitState(playerId,unitId,shipType,x,y,targetX,targetY,task.name(),automationResourceId,stationPackageType==null?"":stationPackageType,encodeInventory(inventory));}
        void drawMoveOrder(Graphics2D g2,boolean showEta){double d=distance(x,y,targetX,targetY);if(d<=4)return;Graphics2D r=(Graphics2D)g2.create();Color b=new Color(rgb);int a=showEta?180:95;r.setColor(new Color(b.getRed(),b.getGreen(),b.getBlue(),a));r.setStroke(new BasicStroke(1.6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,0,new float[]{9f,8f},0));r.draw(new Line2D.Double(x,y,targetX,targetY));r.setStroke(new BasicStroke(2f));r.draw(new Ellipse2D.Double(targetX-11,targetY-11,22,22));if(showEta){String label=String.format(Locale.ROOT,"%.0f u/s | ETA %.1fs",def().speed,d/Math.max(1,def().speed));r.setFont(r.getFont().deriveFont(Font.BOLD,12f));FontMetrics fm=r.getFontMetrics();double lx=(x+targetX)/2+10,ly=(y+targetY)/2-10;int tw=fm.stringWidth(label),th=fm.getHeight();r.setColor(new Color(0,0,0,170));r.fillRoundRect((int)lx-5,(int)ly-th+3,tw+10,th+5,8,8);r.setColor(new Color(220,245,255));r.drawString(label,(int)lx,(int)ly);}r.dispose();}
        void drawHarvestVisual(Graphics2D g2,ResourceNode node,boolean local){if(distance(x,y,node.x,node.y)>def().harvestRange+node.radius+20)return;Graphics2D b=(Graphics2D)g2.create();b.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);Color m=node.material.color;b.setStroke(new BasicStroke(local?3f:2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));b.setColor(new Color(m.getRed(),m.getGreen(),m.getBlue(),local?150:90));b.draw(new Line2D.Double(x,y,node.x,node.y));String label="AUTO-MINING "+node.material.label.toUpperCase(Locale.ROOT);b.setFont(b.getFont().deriveFont(Font.BOLD,11f));int tw=b.getFontMetrics().stringWidth(label),lx=(int)((x+node.x)/2-tw/2.0),ly=(int)((y+node.y)/2+24);b.setColor(new Color(0,0,0,170));b.fillRoundRect(lx-5,ly-13,tw+10,18,8,8);b.setColor(new Color(235,250,255));b.drawString(label,lx,ly);b.dispose();}
        void drawUnloadVisual(Graphics2D g2,Station station,boolean local){Graphics2D b=(Graphics2D)g2.create();Color c=new Color(rgb);b.setStroke(new BasicStroke(local?3.2f:2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));b.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),local?150:80));b.draw(new Line2D.Double(x,y,station.x,station.y));b.dispose();}
        void draw(Graphics2D g2,String playerName){int r=def().stationBuilder?19:16;Polygon ship=new Polygon();ship.addPoint((int)x,(int)y-r);ship.addPoint((int)x+r,(int)y+r);ship.addPoint((int)x,(int)y+8);ship.addPoint((int)x-r,(int)y+r);g2.setColor(new Color(0,0,0,130));g2.fillOval((int)x-r-3,(int)y-r+8,r*2+6,r*2);g2.setColor(new Color(rgb));g2.fillPolygon(ship);g2.setColor(Color.BLACK);g2.drawPolygon(ship);g2.setColor(new Color(20,20,20));g2.fillRect((int)x-18,(int)y-30,36,5);g2.setColor(new Color(80,230,90));g2.fillRect((int)x-18,(int)y-30,(int)(36*hp/Math.max(1,def().maxHp)),5);double cp=def().cargoCapacity<=0?0:cargoUsed()/def().cargoCapacity;g2.setColor(new Color(20,20,20));g2.fillRect((int)x-18,(int)y+27,36,4);g2.setColor(new Color(110,200,255));g2.fillRect((int)x-18,(int)y+27,(int)(36*cp),4);g2.setColor(Color.WHITE);g2.drawString(playerName+" "+def().displayName,(int)x-32,(int)y-38);if(stationPackageType!=null&&!stationPackageType.isBlank()){g2.setColor(new Color(255,230,130));g2.drawString("PKG",(int)x-12,(int)y+45);}if(selected){g2.setColor(new Color(255,245,120));g2.setStroke(new BasicStroke(2f));g2.drawOval((int)x-24,(int)y-24,48,48);}}
    }

    static final class PeerNetwork {
        private static final long HEARTBEAT_MS=1000,FAST_SNAPSHOT_MS=250,RELIABLE_FULL_SNAPSHOT_MS=2000,CLIENT_TIMEOUT_MS=10000,RELIABLE_RESEND_MS=450; private static final int MAX_RELIABLE_ATTEMPTS=40;
        private final Config config; private final World world; private final DatagramSocket socket; private final ConcurrentLinkedQueue<InboundPacket> inbox=new ConcurrentLinkedQueue<>(); private final Map<String,ServerPeer> serverPeers=new LinkedHashMap<>(); private final Map<String,PendingReliable> pendingReliable=new LinkedHashMap<>(); private final Set<String> deliveredReliable=new LinkedHashSet<>(); private final String reliablePrefix=Integer.toHexString(new SecureRandom().nextInt()).replace("-","N"); private volatile boolean running=true,joined=false; private String localPlayerId=""; private long nextPlayerNumber=1,nextReliableId=1,lastJoinReliableAt=0,lastPing=0,lastFastSnapshot=0,lastReliableFullSnapshot=0,sequence=1,lastPacketAt=0;
        private PeerNetwork(Config c,World w,DatagramSocket s){config=c;world=w;socket=s;}
        static PeerNetwork start(Config config,World world)throws IOException{if(!config.hostMode&&config.serverAddress==null)return null;DatagramSocket socket=config.localPort==0?new DatagramSocket():new DatagramSocket(config.localPort);socket.setSoTimeout(300);PeerNetwork n=new PeerNetwork(config,world,socket);if(config.hostMode){n.localPlayerId="HOST";world.addPlayerWithGroup("HOST",world.uniqueName(config.localPlayerName),PALETTE[0],true);n.joined=true;System.out.println("Hosting StarChem on UDP "+socket.getLocalPort());}else System.out.println("Joining StarChem server "+config.serverAddress+" from UDP "+socket.getLocalPort());Thread t=new Thread(n::listenLoop,"starchem-udp-listener");t.setDaemon(true);t.start();return n;}
        String statusLine(){int p=pendingReliable.size();if(config.hostMode)return "HOST UDP "+socket.getLocalPort()+" | players: "+world.playersSnapshot().size()+" | clients: "+serverPeers.size()+" | reliable pending: "+p;String age=lastPacketAt==0?"no server snapshot yet":(System.currentTimeMillis()-lastPacketAt)+"ms since server";return "CLIENT UDP "+socket.getLocalPort()+" -> "+config.serverAddress+" | "+(joined?localPlayerId:"joining...")+" | "+age+" | reliable pending: "+p;}
        void drainMessages(){long now=System.currentTimeMillis();InboundPacket packet;while((packet=inbox.poll())!=null)handleInbound(packet);resendPendingReliable(now);if(config.hostMode){checkClientTimeouts(now);if(now-lastFastSnapshot>=FAST_SNAPSHOT_MS){broadcastSnapshot(false);lastFastSnapshot=now;}if(now-lastReliableFullSnapshot>=RELIABLE_FULL_SNAPSHOT_MS){broadcastSnapshot(true);lastReliableFullSnapshot=now;}}else{if(!joined&&now-lastJoinReliableAt>=HEARTBEAT_MS){if(!hasPendingPayloadPrefix("JOIN|"))sendReliableToServer("JOIN|"+config.localPlayerName);lastJoinReliableAt=now;}else if(joined&&now-lastPing>=HEARTBEAT_MS){sendToServer("PING|"+localPlayerId);lastPing=now;}}}
        void sendMove(MoveCommand c){String msg="MOVE|"+c.playerId+"|"+c.unitId+"|"+round(c.x)+"|"+round(c.y);if(config.hostMode){world.applyAuthorizedMove(c);broadcastSnapshot(false);}else sendToServer(msg);} void sendHarvest(HarvestCommand c){String msg="HARVEST|"+c.playerId+"|"+c.unitId+"|"+c.resourceId;if(config.hostMode){world.applyAuthorizedHarvest(c);broadcastSnapshot(false);}else sendToServer(msg);} void sendBuild(BuildCommand c){String msg="BUILD|"+c.playerId+"|"+c.shipType;if(config.hostMode){world.applyBuildShip(c.playerId,c.shipType);broadcastSnapshot(true);}else sendToServer(msg);} void sendStationCommand(StationCommand c){String msg="STATION|"+c.playerId+"|"+c.unitId+"|"+c.mode+"|"+c.stationType;if(config.hostMode){world.applyStationCommand(c);broadcastSnapshot(true);}else sendToServer(msg);}
        void shutdown(){running=false;if(!config.hostMode&&joined){String leave="LEAVE|"+localPlayerId;for(int i=0;i<4;i++){sendReliableToServer(leave);resendPendingReliable(0);sleepQuietly(50);}}else if(config.hostMode)broadcastReliable("SERVER_CLOSING");socket.close();}
        private void handleInbound(InboundPacket p){lastPacketAt=System.currentTimeMillis();String m=p.message;if(m.startsWith("ACK|")){pendingReliable.remove(m.substring(4));return;}if(m.startsWith("REL|")){String[] parts=m.split("\\|",3);if(parts.length<3)return;String id=parts[1];send("ACK|"+id,p.address,p.port);String key=endpointKey(p.address,p.port)+"|"+id;if(deliveredReliable.contains(key))return;rememberDelivered(key);handlePayload(parts[2],p);return;}handlePayload(m,p);} private void handlePayload(String payload,InboundPacket p){if(config.hostMode)handleHostPacket(payload,p);else handleClientPacket(payload);} 
        private void handleHostPacket(String m,InboundPacket p){String[] parts=m.split("\\|");if(parts.length==0)return;String endpoint=endpointKey(p.address,p.port);try{switch(parts[0]){case"JOIN"->{ServerPeer ex=serverPeers.get(endpoint);if(ex!=null){ex.lastSeen=System.currentTimeMillis();sendWelcome(ex,p.address,p.port);sendSnapshot(p.address,p.port,true);return;}String name=world.uniqueName(parts.length>=2?parts[1]:"Player");String id="P"+nextPlayerNumber++;int color=colorFor(world.playersSnapshot().size());world.addPlayerWithGroup(id,name,color,false);ServerPeer peer=new ServerPeer(id,p.address,p.port,System.currentTimeMillis());serverPeers.put(endpoint,peer);sendWelcome(peer,p.address,p.port);broadcastSnapshot(true);}case"PING"->{ServerPeer peer=serverPeers.get(endpoint);if(peer!=null)peer.lastSeen=System.currentTimeMillis();}case"MOVE"->{ServerPeer peer=serverPeers.get(endpoint);if(peer==null||!peer.playerId.equals(parts[1]))return;peer.lastSeen=System.currentTimeMillis();world.applyAuthorizedMove(new MoveCommand(parts[1],Integer.parseInt(parts[2]),Double.parseDouble(parts[3]),Double.parseDouble(parts[4])));broadcastSnapshot(false);}case"HARVEST"->{ServerPeer peer=serverPeers.get(endpoint);if(peer==null||!peer.playerId.equals(parts[1]))return;peer.lastSeen=System.currentTimeMillis();world.applyAuthorizedHarvest(new HarvestCommand(parts[1],Integer.parseInt(parts[2]),Integer.parseInt(parts[3])));broadcastSnapshot(false);}case"BUILD"->{ServerPeer peer=serverPeers.get(endpoint);if(peer==null||!peer.playerId.equals(parts[1]))return;peer.lastSeen=System.currentTimeMillis();world.applyBuildShip(parts[1],parts[2]);broadcastSnapshot(true);}case"STATION"->{ServerPeer peer=serverPeers.get(endpoint);if(peer==null||!peer.playerId.equals(parts[1]))return;peer.lastSeen=System.currentTimeMillis();world.applyStationCommand(new StationCommand(parts[1],Integer.parseInt(parts[2]),parts[3],parts[4]));broadcastSnapshot(true);}case"LEAVE"->removePeer(endpoint,true);default->System.err.println("unknown host packet: "+m);}}catch(Exception e){System.err.println("bad host packet: "+m+" / "+e.getMessage());}}
        private void handleClientPacket(String m){String[] parts=m.split("\\|");if(parts.length==0)return;switch(parts[0]){case"WELCOME"->{if(parts.length>=4){localPlayerId=parts[1];world.addOrUpdatePlayer(localPlayerId,parts[2],Integer.parseInt(parts[3]),true);joined=true;dropPendingPayloadPrefix("JOIN|");}}case"SNAPSHOT"->world.applySnapshot(decodeSnapshot(m),localPlayerId);case"REMOVE"->{if(parts.length>=2)world.removePlayer(parts[1]);}case"SERVER_CLOSING"->System.out.println("Server closed.");default->System.err.println("unknown client packet: "+m);}}
        private void checkClientTimeouts(long now){List<String> dead=new ArrayList<>();for(Map.Entry<String,ServerPeer> e:serverPeers.entrySet())if(now-e.getValue().lastSeen>CLIENT_TIMEOUT_MS)dead.add(e.getKey());for(String ep:dead)removePeer(ep,true);} private void removePeer(String ep,boolean announce){ServerPeer peer=serverPeers.remove(ep);if(peer==null)return;world.removePlayer(peer.playerId);if(announce){broadcastReliable("REMOVE|"+peer.playerId);broadcastSnapshot(true);}}
        private void sendWelcome(ServerPeer peer,InetAddress a,int port){PlayerInfo info=world.playersSnapshot().stream().filter(p->p.id.equals(peer.playerId)).findFirst().orElse(new PlayerInfo(peer.playerId,peer.playerId,Color.WHITE.getRGB(),false));sendReliable("WELCOME|"+info.id+"|"+info.name+"|"+info.rgb,a,port);} private void broadcastSnapshot(boolean reliable){String e=encodeSnapshot(world.createSnapshot(sequence++));if(reliable)broadcastReliable(e);else broadcast(e);} private void sendSnapshot(InetAddress a,int port,boolean reliable){String e=encodeSnapshot(world.createSnapshot(sequence++));if(reliable)sendReliable(e,a,port);else send(e,a,port);} private void broadcast(String msg){for(ServerPeer p:serverPeers.values())send(msg,p.address,p.port);} private void broadcastReliable(String payload){for(ServerPeer p:serverPeers.values())sendReliable(payload,p.address,p.port);} private void sendToServer(String msg){if(config.serverAddress!=null)send(msg,config.serverAddress.getAddress(),config.serverAddress.getPort());} private void sendReliableToServer(String payload){if(config.serverAddress!=null)sendReliable(payload,config.serverAddress.getAddress(),config.serverAddress.getPort());}
        private void sendReliable(String payload,InetAddress a,int port){String id=reliablePrefix+"-"+nextReliableId++;PendingReliable p=new PendingReliable(id,payload,a,port,0,0);pendingReliable.put(id,p);sendReliableNow(p);} private void sendReliableNow(PendingReliable p){send("REL|"+p.id+"|"+p.payload,p.address,p.port);p.lastSent=System.currentTimeMillis();p.attempts++;} private void resendPendingReliable(long now){long cur=now==0?System.currentTimeMillis():now;List<String> dead=new ArrayList<>();for(PendingReliable p:pendingReliable.values()){if(p.attempts>=MAX_RELIABLE_ATTEMPTS){dead.add(p.id);continue;}if(cur-p.lastSent>=RELIABLE_RESEND_MS)sendReliableNow(p);}for(String id:dead)pendingReliable.remove(id);} private boolean hasPendingPayloadPrefix(String prefix){for(PendingReliable p:pendingReliable.values())if(p.payload.startsWith(prefix))return true;return false;} private void dropPendingPayloadPrefix(String prefix){pendingReliable.entrySet().removeIf(e->e.getValue().payload.startsWith(prefix));} private void rememberDelivered(String k){deliveredReliable.add(k);while(deliveredReliable.size()>512){String f=deliveredReliable.iterator().next();deliveredReliable.remove(f);}}
        private void send(String msg,InetAddress a,int port){byte[] bytes=msg.getBytes(StandardCharsets.UTF_8);try{socket.send(new DatagramPacket(bytes,bytes.length,a,port));}catch(IOException e){if(running)System.err.println("send failed: "+e.getMessage());}}
        private void listenLoop(){byte[] buf=new byte[65535];while(running){DatagramPacket p=new DatagramPacket(buf,buf.length);try{socket.receive(p);inbox.add(new InboundPacket(new String(p.getData(),p.getOffset(),p.getLength(),StandardCharsets.UTF_8),p.getAddress(),p.getPort()));}catch(SocketTimeoutException ignored){}catch(SocketException e){if(running)System.err.println("socket failed: "+e.getMessage());}catch(IOException e){if(running)System.err.println("listen failed: "+e.getMessage());}}}
    }

    record PlayerInfo(String id,String name,int rgb,boolean local){} record UnitState(String playerId,int unitId,String shipType,double x,double y,double targetX,double targetY,String task,int automationResourceId,String stationPackageType,String cargo){} record ResourceState(int id,String name,String kind,String material,double x,double y,double maxAmount,double harvestRate,double radius,double amount,boolean active,double respawnTimer){} record StationState(String id,String playerId,String type,double x,double y){} record StockpileState(String playerId,String cargo){} record MoveCommand(String playerId,int unitId,double x,double y){} record HarvestCommand(String playerId,int unitId,int resourceId){} record BuildCommand(String playerId,String shipType){} record StationCommand(String playerId,int unitId,String mode,String stationType){} record Snapshot(long sequence,List<PlayerInfo> players,List<UnitState> units,List<ResourceState> resources,List<StationState> stations,List<StockpileState> stockpiles){} record InboundPacket(String message,InetAddress address,int port){}
    static final class ServerPeer{final String playerId;final InetAddress address;final int port;long lastSeen;ServerPeer(String id,InetAddress a,int p,long seen){playerId=id;address=a;port=p;lastSeen=seen;}} static final class PendingReliable{final String id,payload;final InetAddress address;final int port;long lastSent;int attempts;PendingReliable(String id,String payload,InetAddress a,int p,long sent,int attempts){this.id=id;this.payload=payload;address=a;port=p;lastSent=sent;this.attempts=attempts;}}
    private static final int[] PALETTE={0x50BEFF,0xFF5F55,0x7DFF7A,0xFFE066,0xC77DFF,0xFF9F1C,0x4DFFD2,0xFF70A6,0xB8F35A,0xA0C4FF,0xFFD6A5,0xCAFFBF};
    static int colorFor(int i){if(i<PALETTE.length)return PALETTE[i];float h=(i*.61803398875f)%1.0f;return Color.HSBtoRGB(h,.75f,1.0f)&0xFFFFFF;} static int playerSlot(String id){if(id==null||id.isBlank()||id.equals("HOST")||id.equals("SOLO"))return 0;if(id.startsWith("P"))try{return Math.max(1,Integer.parseInt(id.substring(1)));}catch(NumberFormatException ignored){}return Math.floorMod(id.hashCode(),8);} static Point2D spawnPoint(int index){double[][] p={{220,260},{1840,1020},{1840,260},{220,1020},{1080,240},{1080,1080},{520,700},{1640,700}};double[] point=p[index%p.length];int ring=index/p.length;return new Point2D.Double(point[0]+ring*34,point[1]+ring*34);} static Point2D stationPoint(String playerId){int slot=playerSlot(playerId);Point2D s=spawnPoint(slot);double dx=slot%2==0?-96:96,dy=slot%3==0?92:-92;return new Point2D.Double(clamp(s.getX()+dx,90,2110),clamp(s.getY()+dy,90,1310));}
    static String encodeSnapshot(Snapshot s){StringBuilder players=new StringBuilder();for(PlayerInfo p:s.players){if(players.length()>0)players.append(';');players.append(p.id).append(',').append(p.name).append(',').append(p.rgb);}StringBuilder units=new StringBuilder();for(UnitState u:s.units){if(units.length()>0)units.append(';');units.append(u.playerId).append(',').append(u.unitId).append(',').append(u.shipType).append(',').append(round(u.x)).append(',').append(round(u.y)).append(',').append(round(u.targetX)).append(',').append(round(u.targetY)).append(',').append(u.task).append(',').append(u.automationResourceId).append(',').append(blank(u.stationPackageType)).append(',').append(blank(u.cargo));}StringBuilder resources=new StringBuilder();for(ResourceState r:s.resources){if(resources.length()>0)resources.append(';');resources.append(r.id).append(',').append(r.name).append(',').append(r.kind).append(',').append(r.material).append(',').append(round(r.x)).append(',').append(round(r.y)).append(',').append(round(r.maxAmount)).append(',').append(round(r.harvestRate)).append(',').append(round(r.radius)).append(',').append(round(r.amount)).append(',').append(r.active).append(',').append(round(r.respawnTimer));}StringBuilder stations=new StringBuilder();for(StationState st:s.stations){if(stations.length()>0)stations.append(';');stations.append(st.id).append(',').append(st.playerId).append(',').append(st.type).append(',').append(round(st.x)).append(',').append(round(st.y));}StringBuilder stock=new StringBuilder();for(StockpileState st:s.stockpiles){if(stock.length()>0)stock.append(';');stock.append(st.playerId).append(',').append(blank(st.cargo));}return "SNAPSHOT|"+s.sequence+"|"+players+"|"+units+"|"+resources+"|"+stations+"|"+stock;}
    static Snapshot decodeSnapshot(String m){String[] parts=m.split("\\|",-1);long seq=parts.length>1?Long.parseLong(parts[1]):0;List<PlayerInfo> players=new ArrayList<>();List<UnitState> units=new ArrayList<>();List<ResourceState> resources=new ArrayList<>();List<StationState> stations=new ArrayList<>();List<StockpileState> stock=new ArrayList<>();if(parts.length>2&&!parts[2].isBlank())for(String row:parts[2].split(";")){String[] c=row.split(",",-1);if(c.length>=3)players.add(new PlayerInfo(c[0],c[1],Integer.parseInt(c[2]),false));}if(parts.length>3&&!parts[3].isBlank())for(String row:parts[3].split(";")){String[] c=row.split(",",-1);if(c.length>=11)units.add(new UnitState(c[0],Integer.parseInt(c[1]),c[2],Double.parseDouble(c[3]),Double.parseDouble(c[4]),Double.parseDouble(c[5]),Double.parseDouble(c[6]),c[7],Integer.parseInt(c[8]),unblank(c[9]),unblank(c[10])));}if(parts.length>4&&!parts[4].isBlank())for(String row:parts[4].split(";")){String[] c=row.split(",",-1);if(c.length>=12)resources.add(new ResourceState(Integer.parseInt(c[0]),c[1],c[2],c[3],Double.parseDouble(c[4]),Double.parseDouble(c[5]),Double.parseDouble(c[6]),Double.parseDouble(c[7]),Double.parseDouble(c[8]),Double.parseDouble(c[9]),Boolean.parseBoolean(c[10]),Double.parseDouble(c[11])));}if(parts.length>5&&!parts[5].isBlank())for(String row:parts[5].split(";")){String[] c=row.split(",",-1);if(c.length>=5)stations.add(new StationState(c[0],c[1],c[2],Double.parseDouble(c[3]),Double.parseDouble(c[4])));}if(parts.length>6&&!parts[6].isBlank())for(String row:parts[6].split(";")){String[] c=row.split(",",-1);if(c.length>=2)stock.add(new StockpileState(c[0],unblank(c[1])));}return new Snapshot(seq,players,units,resources,stations,stock);}
    static String encodeInventory(EnumMap<Material,Double> inv){if(inv.isEmpty())return "-";StringBuilder b=new StringBuilder();for(Material m:Material.values()){double a=inv.getOrDefault(m,0.0);if(a>0.05){if(b.length()>0)b.append('~');b.append(m.name()).append(':').append(round(a));}}return b.length()==0?"-":b.toString();} static void decodeInventoryInto(String encoded,EnumMap<Material,Double> inv){inv.clear();if(encoded==null||encoded.isBlank()||encoded.equals("-"))return;for(String part:encoded.split("~")){String[] p=part.split(":");if(p.length==2)try{Material m=Material.valueOf(p[0]);double a=Double.parseDouble(p[1]);if(a>0.05)inv.put(m,a);}catch(IllegalArgumentException ignored){}}}
    static String blank(String v){return v==null||v.isBlank()?"-":v;} static String unblank(String v){return v==null||v.equals("-")?"":v;} static String defaultName(){return cleanName(System.getProperty("user.name","Player"));} static int parsePort(String v){try{int p=Integer.parseInt(v.trim());if(p<1||p>65535)throw new IllegalArgumentException("Port must be 1-65535.");return p;}catch(NumberFormatException e){throw new IllegalArgumentException("Port must be a number.");}} static String cleanName(String name){if(name==null)return"Player";String c=name.replace('|',' ').replace(';',' ').replace(',',' ').replaceAll("\\s+"," ").trim();if(c.isBlank())return"Player";return c.length()>18?c.substring(0,18).trim():c;} static String endpointKey(InetAddress a,int p){return a.getHostAddress()+":"+p;} static String round(double v){return String.format(Locale.ROOT,"%.1f",v);} static void sleepQuietly(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}} static double lerp(double a,double b,double t){return a+(b-a)*t;} static double distance(double ax,double ay,double bx,double by){return Math.hypot(ax-bx,ay-by);} static double clamp(double v,double min,double max){return Math.max(min,Math.min(max,v));}
}
