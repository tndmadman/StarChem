package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Player-facing live diplomacy controls opened from the in-game menu. */
final class DiplomacyDialog {
    private static final Field FRAME_NETWORK = field(GameFrame.class, "network");

    private DiplomacyDialog() { }

    static void open(Component parent) {
        World world = PlayerRegistry.activeWorld();
        String localId = PlayerRegistry.localId();
        if (world == null || localId == null || localId.isBlank()) {
            JOptionPane.showMessageDialog(parent, "No active player session is available.",
                    "Diplomacy", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        List<PlayerChoice> players = availablePlayers(localId);
        if (players.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "No other human players are currently known to this client.",
                    "Diplomacy", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Window owner = SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "Diplomacy", Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setMinimumSize(new Dimension(560, 360));

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        root.setBackground(new Color(10, 18, 30));

        JLabel title = new JLabel("LIVE DIPLOMACY");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setForeground(new Color(225, 244, 255));
        root.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        JLabel explanation = new JLabel("<html>Alliance offers require the other player to accept. "
                + "Neutral and hostile declarations take effect immediately for both players.</html>");
        explanation.setForeground(new Color(185, 211, 232));
        explanation.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(explanation);
        center.add(Box.createVerticalStrut(16));

        JPanel selector = new JPanel(new BorderLayout(10, 0));
        selector.setOpaque(false);
        selector.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel playerLabel = new JLabel("Player");
        playerLabel.setForeground(Color.WHITE);
        JComboBox<PlayerChoice> playerBox = new JComboBox<>(players.toArray(new PlayerChoice[0]));
        selector.add(playerLabel, BorderLayout.WEST);
        selector.add(playerBox, BorderLayout.CENTER);
        center.add(selector);
        center.add(Box.createVerticalStrut(14));

        JLabel relationship = new JLabel();
        relationship.setForeground(new Color(230, 238, 245));
        relationship.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(relationship);
        center.add(Box.createVerticalStrut(8));

        JLabel offer = new JLabel();
        offer.setForeground(new Color(255, 214, 120));
        offer.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(offer);
        center.add(Box.createVerticalGlue());
        root.add(center, BorderLayout.CENTER);

        JButton ally = new JButton("Offer Alliance");
        JButton neutral = new JButton("Set Neutral");
        JButton hostile = new JButton("Declare Hostile");
        JButton close = new JButton("Close");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(ally);
        actions.add(neutral);
        actions.add(hostile);
        actions.add(close);
        root.add(actions, BorderLayout.SOUTH);

        PeerNetwork network = network(owner);
        Runnable refresh = () -> refresh(world, localId, selected(playerBox),
                relationship, offer, ally, neutral, hostile);
        playerBox.addActionListener(event -> refresh.run());
        ally.addActionListener(event -> send(network, world, localId, selected(playerBox),
                DiplomacySystem.LiveAction.ALLY, refresh));
        neutral.addActionListener(event -> send(network, world, localId, selected(playerBox),
                DiplomacySystem.LiveAction.NEUTRAL, refresh));
        hostile.addActionListener(event -> send(network, world, localId, selected(playerBox),
                DiplomacySystem.LiveAction.HOSTILE, refresh));
        close.addActionListener(event -> dialog.dispose());

        Timer timer = new Timer(500, event -> refresh.run());
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) { timer.start(); }
            @Override public void windowClosed(WindowEvent event) { timer.stop(); }
        });
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        refresh.run();
        dialog.setVisible(true);
    }

    private static void refresh(World world, String localId, PlayerChoice target,
                                JLabel relationshipLabel, JLabel offerLabel,
                                JButton ally, JButton neutral, JButton hostile) {
        if (target == null) return;
        boolean allowed = DiplomacySystem.liveNegotiationAllowed(world);
        DiplomacySystem.Relationship current = DiplomacySystem.relationship(world, localId, target.id());
        boolean incoming = DiplomacySystem.hasAllianceOffer(world, target.id(), localId);
        boolean outgoing = DiplomacySystem.hasAllianceOffer(world, localId, target.id());

        relationshipLabel.setText("Current relationship: " + relationshipName(current));
        if (!allowed) {
            offerLabel.setText("Relationships are controlled by this match's team settings.");
        } else if (incoming) {
            offerLabel.setText(target.name() + " has offered you an alliance.");
        } else if (outgoing) {
            offerLabel.setText("Your alliance offer is waiting for " + target.name() + ".");
        } else {
            offerLabel.setText(" ");
        }

        ally.setText(incoming ? "Accept Alliance"
                : outgoing ? "Alliance Offered"
                : current == DiplomacySystem.Relationship.ALLIED ? "Already Allied"
                : "Offer Alliance");
        ally.setEnabled(allowed && current != DiplomacySystem.Relationship.ALLIED && !outgoing);
        neutral.setEnabled(allowed && current != DiplomacySystem.Relationship.NEUTRAL);
        hostile.setEnabled(allowed && current != DiplomacySystem.Relationship.HOSTILE);
    }

    private static void send(PeerNetwork network, World world, String localId, PlayerChoice target,
                             DiplomacySystem.LiveAction action, Runnable refresh) {
        if (target == null || localId.equals(target.id())) return;
        DiplomacyNetworkBridge.send(network, world, target.id(), action);
        if (refresh != null) refresh.run();
    }

    private static List<PlayerChoice> availablePlayers(String localId) {
        List<PlayerChoice> out = new ArrayList<>();
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            if (player == null || player.id() == null || player.id().isBlank()
                    || localId.equals(player.id()) || NpcRules.isNpcFaction(player.id())
                    || "WAIT".equals(player.id()) || "SENSOR_CONTACT".equals(player.id())) continue;
            out.add(new PlayerChoice(player.id(), PlayerRegistry.baseName(player.id())));
        }
        out.sort(Comparator.comparing(PlayerChoice::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    private static PlayerChoice selected(JComboBox<PlayerChoice> box) {
        Object value = box == null ? null : box.getSelectedItem();
        return value instanceof PlayerChoice choice ? choice : null;
    }

    private static PeerNetwork network(Window owner) {
        if (!(owner instanceof GameFrame frame)) return null;
        try {
            Object value = FRAME_NETWORK.get(frame);
            return value instanceof PeerNetwork network ? network : null;
        } catch (IllegalAccessException ex) {
            return null;
        }
    }

    private static String relationshipName(DiplomacySystem.Relationship relationship) {
        if (relationship == null) return "Neutral";
        return switch (relationship) {
            case ALLIED -> "Allied";
            case NEUTRAL -> "Neutral";
            case HOSTILE -> "Hostile";
        };
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private record PlayerChoice(String id, String name) {
        @Override public String toString() { return name; }
    }
}
