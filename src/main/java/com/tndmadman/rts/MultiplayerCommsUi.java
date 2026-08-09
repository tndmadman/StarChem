package com.tndmadman.rts;

import javax.swing.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyEventDispatcher;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Swing overlay for bounded chat history and short-lived server-approved tactical pings. */
final class MultiplayerCommsUi extends JPanel {
    private static final Map<World, MultiplayerCommsUi> INSTANCES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int CHAT_MARGIN = 16;
    private static final int CHAT_WIDTH = 610;
    private static final int CHAT_OPEN_HEIGHT = 278;
    private static final int CHAT_CLOSED_HEIGHT = 110;
    private static final int TAB_HEIGHT = 27;
    private static final int INPUT_HEIGHT = 30;
    private static final int LINE_HEIGHT = 17;

    private final World world;
    private final JTextField input = new JTextField();
    private final Timer repaintTimer;
    private final KeyEventDispatcher keyDispatcher = this::dispatchKey;
    private final GalaxyMapOverlay galaxyHelper = new GalaxyMapOverlay();
    private final MinimapHud minimapHelper = new MinimapHud();

    private GameFrame frame;
    private GamePanel gamePanel;
    private JLayeredPane root;
    private MultiplayerComms.ChatChannel channel = MultiplayerComms.ChatChannel.GLOBAL;
    private String directTarget = "";
    private Point lastMouse = new Point();
    private boolean chatOpen;
    private boolean galaxyMapOpen;
    private boolean dispatcherInstalled;
    private int scrollOffset;

    private MultiplayerCommsUi(World world) {
        this.world = world;
        setOpaque(false);
        setLayout(null);
        setFocusable(false);
        input.setVisible(false);
        input.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        input.setToolTipText("Enter sends | Esc closes | Tab changes channel");
        ((AbstractDocument) input.getDocument()).setDocumentFilter(new MaxDocumentFilter(1_024));
        add(input);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { handleOverlayClick(event); }
        });
        addMouseWheelListener(event -> {
            if (!chatOpen) return;
            scrollOffset = Math.max(0, scrollOffset + event.getWheelRotation() * 2);
            repaint();
        });
        repaintTimer = new Timer(100, event -> repaint());
    }

    static void ensureInstalled(World world) {
        if (world == null || GraphicsEnvironment.isHeadless()) return;
        synchronized (INSTANCES) {
            MultiplayerCommsUi existing = INSTANCES.get(world);
            if (existing != null && (existing.getParent() != null || existing.root != null)) return;
            if (existing == null) {
                existing = new MultiplayerCommsUi(world);
                INSTANCES.put(world, existing);
            }
        }
        MultiplayerCommsUi ui;
        synchronized (INSTANCES) { ui = INSTANCES.get(world); }
        if (ui == null) return;
        SwingUtilities.invokeLater(ui::installIfPossible);
    }

    static void clear(World world) {
        MultiplayerCommsUi ui;
        synchronized (INSTANCES) { ui = INSTANCES.remove(world); }
        if (ui != null) SwingUtilities.invokeLater(ui::uninstall);
        MultiplayerComms.clearClient(world);
    }

    @Override public boolean contains(int x, int y) {
        return chatOpen && chatBounds().contains(x, y);
    }

    @Override public void doLayout() {
        Rectangle bounds = chatBounds();
        int x = bounds.x + 10;
        int y = bounds.y + bounds.height - INPUT_HEIGHT - 8;
        input.setBounds(x, y, Math.max(80, bounds.width - 20), INPUT_HEIGHT);
    }

    @Override protected void paintComponent(Graphics source) {
        super.paintComponent(source);
        if (world == null) return;
        Graphics2D g = (Graphics2D) source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawTacticalPings(g);
        drawChat(g);
        g.dispose();
    }

    private void installIfPossible() {
        if (root != null && getParent() != null) return;
        GameFrame candidate = null;
        GamePanel panel = null;
        for (Frame candidateFrame : Frame.getFrames()) {
            if (!(candidateFrame instanceof GameFrame gameFrame) || !gameFrame.isDisplayable()) continue;
            Container content = gameFrame.getContentPane();
            GamePanel found = findGamePanel(content);
            if (found != null) {
                candidate = gameFrame;
                panel = found;
                break;
            }
        }
        if (candidate == null || panel == null || !(candidate.getContentPane() instanceof JLayeredPane layered)) {
            synchronized (INSTANCES) {
                if (getParent() == null) INSTANCES.remove(world, this);
            }
            return;
        }
        frame = candidate;
        gamePanel = panel;
        root = layered;
        setBounds(0, 0, root.getWidth(), root.getHeight());
        root.add(this, Integer.valueOf(JLayeredPane.PALETTE_LAYER.intValue() + 35));
        root.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                if (root != null) setBounds(0, 0, root.getWidth(), root.getHeight());
            }
        });
        installGameMouseTracking(panel);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher);
        dispatcherInstalled = true;
        repaintTimer.start();
        revalidate();
        repaint();
    }

    private void uninstall() {
        repaintTimer.stop();
        if (dispatcherInstalled) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyDispatcher);
            dispatcherInstalled = false;
        }
        if (getParent() != null) getParent().remove(this);
        root = null;
        gamePanel = null;
        frame = null;
    }

    @Override public void removeNotify() {
        if (dispatcherInstalled) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyDispatcher);
            dispatcherInstalled = false;
        }
        repaintTimer.stop();
        super.removeNotify();
    }

    private void installGameMouseTracking(GamePanel panel) {
        panel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent event) { lastMouse = event.getPoint(); }
            @Override public void mouseDragged(MouseEvent event) { lastMouse = event.getPoint(); }
        });
        panel.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                lastMouse = event.getPoint();
                if (galaxyMapOpen && SwingUtilities.isLeftMouseButton(event)) galaxyMapOpen = false;
                if (!event.isAltDown() || !SwingUtilities.isMiddleMouseButton(event)) return;
                MultiplayerComms.SendResult result = galaxyMapOpen
                        ? pingGalaxyAt(event.getPoint(), MultiplayerComms.TacticalPingType.ATTENTION)
                        : pingWorldAt(event.getPoint(), MultiplayerComms.TacticalPingType.ATTENTION);
                announceResult(result);
            }
        });
    }

    private boolean dispatchKey(KeyEvent event) {
        if (event == null || event.getID() != KeyEvent.KEY_PRESSED || !isShowing()) return false;
        Component source = event.getComponent();
        if (source instanceof JTextComponent && source != input) return false;

        if (input.hasFocus()) {
            if (event.getKeyCode() == KeyEvent.VK_ENTER) {
                submitInput();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                closeChat();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.VK_TAB) {
                cycleChannel(event.isShiftDown() ? -1 : 1);
                return true;
            }
            if (event.getKeyCode() == KeyEvent.VK_PAGE_UP) {
                scrollOffset += 6;
                repaint();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.VK_PAGE_DOWN) {
                scrollOffset = Math.max(0, scrollOffset - 6);
                repaint();
                return true;
            }
            return false;
        }

        if (frame != null && frame.gameSettings().matches("galaxy_map", event)) {
            galaxyMapOpen = !galaxyMapOpen;
            return false;
        }
        if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
            galaxyMapOpen = false;
            return false;
        }
        if (frame != null && frame.gameSettings().matches("chat_open", event)) {
            openChat();
            return true;
        }
        if (event.isAltDown() && !event.isControlDown() && !event.isMetaDown()) {
            MultiplayerComms.TacticalPingType type = pingTypeForKey(event.getKeyCode());
            if (type != null) {
                MultiplayerComms.SendResult result = galaxyMapOpen
                        ? pingGalaxyAt(lastMouse, type) : pingWorldAt(lastMouse, type);
                announceResult(result);
                return true;
            }
        }
        return false;
    }

    private MultiplayerComms.TacticalPingType pingTypeForKey(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_1 -> MultiplayerComms.TacticalPingType.ATTENTION;
            case KeyEvent.VK_2 -> MultiplayerComms.TacticalPingType.THREAT;
            case KeyEvent.VK_3 -> MultiplayerComms.TacticalPingType.DEFEND;
            case KeyEvent.VK_4 -> MultiplayerComms.TacticalPingType.RESOURCE;
            case KeyEvent.VK_5 -> MultiplayerComms.TacticalPingType.MOVE;
            default -> null;
        };
    }

    private MultiplayerComms.SendResult pingWorldAt(Point screenPoint, MultiplayerComms.TacticalPingType type) {
        GameCamera camera = GameCamera.forWorld(world);
        if (camera == null || screenPoint == null) return MultiplayerComms.SendResult.fail("Camera is not ready for tactical pings.");
        Point2D point;
        Rectangle minimap = minimapHelper.bounds(world, getWidth(), getHeight());
        if (minimap.contains(screenPoint)) {
            point = minimapWorldPoint(screenPoint, minimap);
        } else {
            point = camera.screenToWorld(screenPoint);
        }
        return MultiplayerComms.sendWorldPing(world, type, point.getX(), point.getY());
    }

    private MultiplayerComms.SendResult pingGalaxyAt(Point screenPoint, MultiplayerComms.TacticalPingType type) {
        if (screenPoint == null) return MultiplayerComms.SendResult.fail("Move the cursor over a galaxy system first.");
        GalaxyMapSnapshot snapshot = world.galaxyMapSnapshot();
        String systemId = galaxyHelper.systemAt(snapshot, screenPoint.x, screenPoint.y, getWidth(), getHeight());
        if (systemId == null || systemId.isBlank()) return MultiplayerComms.SendResult.fail("Move the cursor over a known galaxy system first.");
        return MultiplayerComms.sendSystemPing(world, type, systemId);
    }

    private Point2D minimapWorldPoint(Point point, Rectangle outer) {
        int title = 24;
        int mapX = outer.x + 8;
        int mapY = outer.y + title;
        int mapW = Math.max(1, outer.width - 16);
        int mapH = Math.max(1, outer.height - title - 12);
        double nx = Calc.clamp((point.x - mapX) / (double) mapW, 0, 1);
        double ny = Calc.clamp((point.y - mapY) / (double) mapH, 0, 1);
        return new Point2D.Double(nx * world.width, ny * world.height);
    }

    private void openChat() {
        chatOpen = true;
        scrollOffset = 0;
        input.setVisible(true);
        doLayout();
        MultiplayerComms.markRead(world, channel);
        SwingUtilities.invokeLater(input::requestFocusInWindow);
        repaint();
    }

    private void closeChat() {
        chatOpen = false;
        input.setVisible(false);
        input.setText("");
        if (gamePanel != null) gamePanel.requestFocusInWindow();
        repaint();
    }

    private void cycleChannel(int direction) {
        MultiplayerComms.ChatChannel[] values = MultiplayerComms.ChatChannel.values();
        int index = channel.ordinal();
        index = Math.floorMod(index + direction, values.length);
        channel = values[index];
        scrollOffset = 0;
        MultiplayerComms.markRead(world, channel);
        repaint();
    }

    private void submitInput() {
        String raw = input.getText();
        input.setText("");
        if (raw == null || raw.isBlank()) return;
        if (raw.startsWith("/")) {
            if (handleLocalCommand(raw)) return;
        }
        MultiplayerComms.SendResult result = MultiplayerComms.sendChat(world, channel, directTarget, raw);
        announceResult(result);
    }

    private boolean handleLocalCommand(String raw) {
        String[] parts = raw.trim().split("\\s+", 3);
        String command = parts[0].toLowerCase(Locale.ROOT);
        switch (command) {
            case "/global" -> { channel = MultiplayerComms.ChatChannel.GLOBAL; announce("Global chat selected."); return true; }
            case "/system" -> { channel = MultiplayerComms.ChatChannel.SYSTEM; announce("System chat selected."); return true; }
            case "/team" -> { channel = MultiplayerComms.ChatChannel.TEAM; announce("Team chat selected."); return true; }
            case "/direct", "/dm", "/w" -> {
                if (parts.length < 2) {
                    announce("Usage: /direct <player-id> [message]");
                    return true;
                }
                directTarget = parts[1].trim();
                channel = MultiplayerComms.ChatChannel.DIRECT;
                if (parts.length == 3 && !parts[2].isBlank()) {
                    announceResult(MultiplayerComms.sendChat(world, channel, directTarget, parts[2]));
                } else announce("Direct chat target: " + directTarget);
                return true;
            }
            case "/block" -> {
                if (parts.length < 2) announce("Usage: /block <player-id>");
                else { MultiplayerComms.block(world, parts[1]); announce("Blocked local chat/pings from " + parts[1] + "."); }
                return true;
            }
            case "/unblock" -> {
                if (parts.length < 2) announce("Usage: /unblock <player-id>");
                else { MultiplayerComms.unblock(world, parts[1]); announce("Unblocked " + parts[1] + "."); }
                return true;
            }
            case "/blocks" -> {
                Set<String> blocked = MultiplayerComms.blocked(world);
                announce(blocked.isEmpty() ? "No locally blocked players." : "Locally blocked: " + String.join(", ", blocked));
                return true;
            }
            case "/help" -> {
                announce("Chat: Enter opens | Tab changes channel | /direct P# | /block P# | Alt+1..5 tactical pings | Alt+middle-click attention ping.");
                return true;
            }
            default -> { return false; }
        }
    }

    private void handleOverlayClick(MouseEvent event) {
        if (!chatOpen || event == null || !SwingUtilities.isLeftMouseButton(event)) return;
        Rectangle bounds = chatBounds();
        int tabY = bounds.y + 7;
        if (event.getY() < tabY || event.getY() > tabY + TAB_HEIGHT) return;
        MultiplayerComms.ChatChannel[] values = MultiplayerComms.ChatChannel.values();
        int tabWidth = Math.max(70, (bounds.width - 20) / values.length);
        int index = (event.getX() - bounds.x - 10) / tabWidth;
        if (index >= 0 && index < values.length) {
            channel = values[index];
            scrollOffset = 0;
            MultiplayerComms.markRead(world, channel);
            input.requestFocusInWindow();
            repaint();
        }
    }

    private void drawChat(Graphics2D g) {
        Rectangle bounds = chatBounds();
        List<MultiplayerComms.ChatMessage> history = MultiplayerComms.messages(world, channel);
        if (!chatOpen && history.isEmpty()) return;

        g.setColor(new Color(3, 8, 14, chatOpen ? 218 : 155));
        g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 13, 13);
        g.setColor(new Color(90, 169, 214, chatOpen ? 200 : 125));
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 13, 13);

        int contentTop = bounds.y + 10;
        if (chatOpen) {
            drawTabs(g, bounds);
            contentTop += TAB_HEIGHT + 7;
        }
        int contentBottom = chatOpen ? bounds.y + bounds.height - INPUT_HEIGHT - 15 : bounds.y + bounds.height - 8;
        int visibleLines = Math.max(1, (contentBottom - contentTop) / LINE_HEIGHT);
        int end = Math.max(0, history.size() - scrollOffset);
        int start = Math.max(0, end - visibleLines);
        List<MultiplayerComms.ChatMessage> visible = history.subList(start, end);

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        int y = contentTop + 14;
        for (MultiplayerComms.ChatMessage message : visible) {
            drawMessage(g, message, bounds.x + 10, y, bounds.width - 20);
            y += LINE_HEIGHT;
        }
        if (!chatOpen) {
            g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
            g.setColor(new Color(170, 211, 235));
            g.drawString("Enter: chat", bounds.x + bounds.width - 75, bounds.y + 15);
        }
    }

    private void drawTabs(Graphics2D g, Rectangle bounds) {
        MultiplayerComms.ChatChannel[] values = MultiplayerComms.ChatChannel.values();
        int tabWidth = Math.max(70, (bounds.width - 20) / values.length);
        int y = bounds.y + 7;
        g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
        for (int i = 0; i < values.length; i++) {
            MultiplayerComms.ChatChannel tab = values[i];
            int x = bounds.x + 10 + i * tabWidth;
            boolean active = tab == channel;
            g.setColor(active ? new Color(26, 91, 130, 235) : new Color(17, 35, 49, 210));
            g.fillRoundRect(x, y, tabWidth - 4, TAB_HEIGHT, 8, 8);
            g.setColor(active ? new Color(126, 218, 255) : new Color(91, 143, 174));
            g.drawRoundRect(x, y, tabWidth - 4, TAB_HEIGHT, 8, 8);
            int unread = MultiplayerComms.unread(world, tab);
            String label = tab.label + (unread > 0 ? " (" + unread + ")" : "");
            if (tab == MultiplayerComms.ChatChannel.DIRECT && !directTarget.isBlank()) label = "Direct:" + directTarget;
            g.setColor(Color.WHITE);
            g.drawString(trimToWidth(g, label, tabWidth - 14), x + 7, y + 18);
        }
    }

    private void drawMessage(Graphics2D g, MultiplayerComms.ChatMessage message, int x, int y, int maxWidth) {
        String prefix = switch (message.channel()) {
            case GLOBAL -> "[G] ";
            case SYSTEM -> "[S] ";
            case TEAM -> "[T] ";
            case DIRECT -> "[DM] ";
        };
        String name = message.senderName().isBlank() ? message.senderId() : message.senderName();
        String line = prefix + name + ": " + message.text();
        Color playerColor = PlayerRegistry.color(message.senderId());
        g.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 245));
        String head = prefix + name + ": ";
        g.drawString(trimToWidth(g, head, maxWidth), x, y);
        int headWidth = Math.min(maxWidth, g.getFontMetrics().stringWidth(head));
        if (headWidth < maxWidth) {
            g.setColor(new Color(228, 239, 247));
            g.drawString(trimToWidth(g, message.text(), maxWidth - headWidth), x + headWidth, y);
        }
    }

    private void drawTacticalPings(Graphics2D g) {
        List<MultiplayerComms.TacticalPing> pings = MultiplayerComms.pings(world);
        if (pings.isEmpty()) return;
        long now = System.currentTimeMillis();
        GameCamera camera = GameCamera.forWorld(world);
        for (MultiplayerComms.TacticalPing ping : pings) {
            double age = Math.max(0, now - ping.receivedAtMs()) / (double) MultiplayerComms.PING_LIFETIME_MS;
            int alpha = (int) Math.round(235 * Math.max(0, 1 - age));
            if (alpha <= 0) continue;
            Color base = new Color(ping.type().rgb);
            Color color = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
            if (ping.targetKind() == MultiplayerComms.PingTargetKind.WORLD
                    && ping.systemId().equals(world.activeSystemId())) {
                if (camera != null) {
                    Point2D screen = camera.worldToScreen(ping.worldX(), ping.worldY());
                    drawPingMarker(g, screen.getX(), screen.getY(), color, ping, 18 + age * 13, true);
                }
                Point mini = minimapHelper.pointForWorld(world, ping.worldX(), ping.worldY(), getWidth(), getHeight());
                drawPingMarker(g, mini.x, mini.y, color, ping, 7 + age * 6, false);
            }
            if (galaxyMapOpen && ping.targetKind() == MultiplayerComms.PingTargetKind.SYSTEM) {
                Point2D node = galaxyHelper.pointForSystem(world.galaxyMapSnapshot(), ping.targetSystemId(), getWidth(), getHeight());
                if (node != null) drawPingMarker(g, node.getX(), node.getY(), color, ping, 24 + age * 16, true);
            }
        }
    }

    private void drawPingMarker(Graphics2D g, double x, double y, Color color,
                                MultiplayerComms.TacticalPing ping, double radius, boolean label) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < -100 || y < -100 || x > getWidth() + 100 || y > getHeight() + 100) return;
        g.setColor(color);
        g.setStroke(new BasicStroke(2f));
        g.draw(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
        g.drawLine((int)Math.round(x - radius - 5), (int)Math.round(y), (int)Math.round(x - radius + 3), (int)Math.round(y));
        g.drawLine((int)Math.round(x + radius - 3), (int)Math.round(y), (int)Math.round(x + radius + 5), (int)Math.round(y));
        if (!label) return;
        String name = ping.senderName().isBlank() ? ping.senderId() : ping.senderName();
        String text = ping.type().label + " — " + name;
        g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
        int width = g.getFontMetrics().stringWidth(text) + 12;
        int bx = (int)Math.round(x - width / 2.0);
        int by = (int)Math.round(y - radius - 25);
        g.setColor(new Color(0, 0, 0, Math.min(190, color.getAlpha())));
        g.fillRoundRect(bx, by, width, 20, 8, 8);
        g.setColor(color);
        g.drawString(text, bx + 6, by + 14);
    }

    private Rectangle chatBounds() {
        int width = Math.min(CHAT_WIDTH, Math.max(320, getWidth() - CHAT_MARGIN * 2));
        int height = chatOpen ? CHAT_OPEN_HEIGHT : CHAT_CLOSED_HEIGHT;
        int y = Math.max(154, getHeight() - height - CHAT_MARGIN);
        return new Rectangle(CHAT_MARGIN, y, width, height);
    }

    private void announceResult(MultiplayerComms.SendResult result) {
        if (result != null && !result.sent() && !result.message().isBlank()) announce(result.message());
    }

    private void announce(String text) {
        if (text != null && !text.isBlank()) AlertCenter.push(world, TextSafety.chatText(text, 240));
    }

    private String trimToWidth(Graphics2D g, String text, int width) {
        String safe = text == null ? "" : text;
        if (g.getFontMetrics().stringWidth(safe) <= width) return safe;
        String suffix = "…";
        int end = safe.length();
        while (end > 0 && g.getFontMetrics().stringWidth(safe.substring(0, end) + suffix) > width) end--;
        return end <= 0 ? "" : safe.substring(0, end) + suffix;
    }

    private GamePanel findGamePanel(Container container) {
        if (container instanceof GamePanel panel) return panel;
        for (Component component : container.getComponents()) {
            if (component instanceof GamePanel panel) return panel;
            if (component instanceof Container child) {
                GamePanel found = findGamePanel(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class MaxDocumentFilter extends DocumentFilter {
        private final int maxChars;

        MaxDocumentFilter(int maxChars) { this.maxChars = Math.max(1, maxChars); }

        @Override public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            replace(fb, offset, 0, string, attr);
        }

        @Override public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            String safe = text == null ? "" : text;
            int available = maxChars - (fb.getDocument().getLength() - length);
            if (available <= 0) return;
            if (safe.length() > available) safe = safe.substring(0, available);
            super.replace(fb, offset, length, safe, attrs);
        }
    }
}