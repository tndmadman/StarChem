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

        Window owner = SwingUtilities.getWindowAncestor(parent);
        PeerNetwork network = network(owner);
        JDialog dialog = new JDialog(owner, "Diplomacy", Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setMinimumSize(new Dimension(680, 430));

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

        JLabel explanation = new JLabel("<html>The server authenticates every action. Alliance offers require acceptance; "
                + "neutral and hostile declarations take effect immediately for both players.</html>");
        explanation.setForeground(new Color(185, 211, 232));
        explanation.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(explanation);
        center.add(Box.createVerticalStrut(16));

        JPanel selector = new JPanel(new BorderLayout(10, 0));
        selector.setOpaque(false);
        selector.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel playerLabel = new JLabel("Player");
        playerLabel.setForeground(Color.WHITE);
        DefaultComboBoxModel<PlayerChoice> model = new DefaultComboBoxModel<>();
        JComboBox<PlayerChoice> playerBox = new JComboBox<>(model);
        selector.add(playerLabel, BorderLayout.WEST);
        selector.add(playerBox, BorderLayout.CENTER);
        center.add(selector);
        center.add(Box.createVerticalStrut(14));

        JLabel relationship = new JLabel("Requesting authoritative diplomacy roster...");
        relationship.setForeground(new Color(230, 238, 245));
        relationship.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(relationship);
        center.add(Box.createVerticalStrut(8));

        JLabel offer = new JLabel(" ");
        offer.setForeground(new Color(255, 214, 120));
        offer.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(offer);
        center.add(Box.createVerticalGlue());
        root.add(center, BorderLayout.CENTER);

        JButton offerAlliance = new JButton("Offer Alliance");
        JButton acceptAlliance = new JButton("Accept Alliance");
        JButton resolveOffer = new JButton("Decline / Cancel");
        JButton neutral = new JButton("Set Neutral");
        JButton hostile = new JButton("Declare Hostile");
        JButton refreshRoster = new JButton("Refresh");
        JButton close = new JButton("Close");

        JPanel actionGrid = new JPanel(new GridLayout(2, 3, 8, 8));
        actionGrid.setOpaque(false);
        actionGrid.add(offerAlliance);
        actionGrid.add(acceptAlliance);
        actionGrid.add(resolveOffer);
        actionGrid.add(neutral);
        actionGrid.add(hostile);
        actionGrid.add(refreshRoster);
        JPanel south = new JPanel(new BorderLayout(8, 8));
        south.setOpaque(false);
        south.add(actionGrid, BorderLayout.CENTER);
        JPanel closeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        closeRow.setOpaque(false);
        closeRow.add(close);
        south.add(closeRow, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        long[] lastRevision = { Long.MIN_VALUE };
        int[] lastRosterHash = { Integer.MIN_VALUE };
        long[] lastRefreshRequest = { 0 };
        boolean[] rebuilding = { false };
        Runnable rebuild = () -> {
            if (rebuilding[0]) return;
            long revision = DiplomacyClientState.revision(world);
            List<PlayerChoice> choices = choices(world, localId);
            int rosterHash = choices.hashCode();
            if (revision == lastRevision[0] && rosterHash == lastRosterHash[0]
                    && model.getSize() == choices.size()) return;

            rebuilding[0] = true;
            try {
                String selectedId = selected(playerBox) == null ? "" : selected(playerBox).id();
                lastRevision[0] = revision;
                lastRosterHash[0] = rosterHash;
                model.removeAllElements();
                PlayerChoice selected = null;
                for (PlayerChoice choice : choices) {
                    model.addElement(choice);
                    if (choice.id().equals(selectedId)) selected = choice;
                }
                if (selected != null) playerBox.setSelectedItem(selected);
                else if (model.getSize() > 0) playerBox.setSelectedIndex(0);
            } finally {
                rebuilding[0] = false;
            }
        };
        Runnable refresh = () -> {
            rebuild.run();
            refreshLabels(world, selected(playerBox), relationship, offer,
                    offerAlliance, acceptAlliance, resolveOffer, neutral, hostile);
        };
        Runnable requestRefresh = () -> {
            if (network != null && network.clientMode()) {
                DiplomacyNetworkBridge.refresh(network, world);
                lastRefreshRequest[0] = System.currentTimeMillis();
            }
        };

        playerBox.addActionListener(event -> {
            if (!rebuilding[0]) refresh.run();
        });
        offerAlliance.addActionListener(event -> send(network, world, selected(playerBox),
                DiplomacySystem.LiveAction.OFFER_ALLIANCE, requestRefresh));
        acceptAlliance.addActionListener(event -> send(network, world, selected(playerBox),
                DiplomacySystem.LiveAction.ACCEPT_ALLIANCE, requestRefresh));
        resolveOffer.addActionListener(event -> {
            PlayerChoice target = selected(playerBox);
            if (target == null) return;
            DiplomacySystem.LiveAction action = target.incomingOffer()
                    ? DiplomacySystem.LiveAction.DECLINE_ALLIANCE
                    : DiplomacySystem.LiveAction.CANCEL_ALLIANCE;
            send(network, world, target, action, requestRefresh);
        });
        neutral.addActionListener(event -> send(network, world, selected(playerBox),
                DiplomacySystem.LiveAction.NEUTRAL, requestRefresh));
        hostile.addActionListener(event -> send(network, world, selected(playerBox),
                DiplomacySystem.LiveAction.HOSTILE, requestRefresh));
        refreshRoster.addActionListener(event -> requestRefresh.run());
        close.addActionListener(event -> dialog.dispose());

        Timer timer = new Timer(400, event -> {
            if (network != null && network.clientMode()
                    && System.currentTimeMillis() - lastRefreshRequest[0] >= 5_000) requestRefresh.run();
            refresh.run();
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) {
                requestRefresh.run();
                timer.start();
            }
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

    private static void refreshLabels(World world, PlayerChoice target,
                                      JLabel relationshipLabel, JLabel offerLabel,
                                      JButton offerAlliance, JButton acceptAlliance,
                                      JButton resolveOffer, JButton neutral, JButton hostile) {
        boolean allowed = DiplomacyClientState.negotiationAllowed(world);
        if (target == null) {
            relationshipLabel.setText("No other retained human players are available yet.");
            offerLabel.setText("The roster refreshes automatically from the server.");
            setEnabled(false, offerAlliance, acceptAlliance, resolveOffer, neutral, hostile);
            return;
        }

        relationshipLabel.setText("Current relationship: " + relationshipName(target.relationship())
                + " | " + (target.online() ? "Online" : "Offline"));
        if (!allowed) {
            offerLabel.setText("Relationships are controlled by this match's team settings.");
        } else if (target.incomingOffer()) {
            offerLabel.setText(target.name() + " has offered you an alliance.");
        } else if (target.outgoingOffer()) {
            offerLabel.setText("Your alliance offer is waiting for " + target.name() + ".");
        } else {
            offerLabel.setText(" ");
        }

        offerAlliance.setEnabled(allowed
                && target.relationship() != DiplomacySystem.Relationship.ALLIED
                && !target.incomingOffer() && !target.outgoingOffer());
        acceptAlliance.setEnabled(allowed && target.incomingOffer());
        resolveOffer.setText(target.incomingOffer() ? "Decline Alliance"
                : target.outgoingOffer() ? "Cancel Offer" : "Decline / Cancel");
        resolveOffer.setEnabled(allowed && (target.incomingOffer() || target.outgoingOffer()));
        neutral.setEnabled(allowed && target.relationship() != DiplomacySystem.Relationship.NEUTRAL);
        hostile.setEnabled(allowed && target.relationship() != DiplomacySystem.Relationship.HOSTILE);
    }

    private static void send(PeerNetwork network, World world, PlayerChoice target,
                             DiplomacySystem.LiveAction action, Runnable requestRefresh) {
        if (target == null) return;
        if (DiplomacyNetworkBridge.send(network, world, target.id(), action)
                && requestRefresh != null) requestRefresh.run();
    }

    private static List<PlayerChoice> choices(World world, String localId) {
        List<PlayerChoice> out = new ArrayList<>();
        for (DiplomacyClientState.PlayerView player : DiplomacyClientState.players(world)) {
            out.add(new PlayerChoice(player.id(), player.name(), player.online(), player.relationship(),
                    player.incomingOffer(), player.outgoingOffer()));
        }
        if (out.isEmpty()) {
            for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
                if (player == null || player.id() == null || player.id().isBlank()
                        || localId.equals(player.id()) || NpcRules.isNpcFaction(player.id())
                        || "WAIT".equals(player.id()) || "SENSOR_CONTACT".equals(player.id())) continue;
                out.add(new PlayerChoice(player.id(), PlayerRegistry.baseName(player.id()), true,
                        DiplomacySystem.relationship(world, localId, player.id()),
                        DiplomacySystem.hasAllianceOffer(world, player.id(), localId),
                        DiplomacySystem.hasAllianceOffer(world, localId, player.id())));
            }
        }
        out.sort(Comparator.comparing(PlayerChoice::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlayerChoice::id));
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

    private static void setEnabled(boolean enabled, JButton... buttons) {
        for (JButton button : buttons) if (button != null) button.setEnabled(enabled);
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

    private record PlayerChoice(String id, String name, boolean online,
                                DiplomacySystem.Relationship relationship,
                                boolean incomingOffer, boolean outgoingOffer) {
        @Override public String toString() {
            String request = incomingOffer ? " [REQUEST]" : outgoingOffer ? " [PENDING]" : "";
            return name + (online ? "" : " [OFFLINE]") + request;
        }
    }
}
