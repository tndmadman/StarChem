package com.tndmadman.rts;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Player-facing command surfaces for the persistent-fleet and branching-research systems.
 *
 * This class deliberately reuses the authoritative FleetManager/FleetAuthorityService and
 * ProductionCommands/PeerNetwork paths rather than creating parallel mutable UI state.
 */
final class StrategicCommandCenter {
    private StrategicCommandCenter() { }

    static void showFleetCommand(World world, PeerNetwork network) {
        if (world == null) return;
        Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        new FleetDialog(owner, world, network).setVisible(true);
    }

    static void showResearchTree(World world, PeerNetwork network) {
        if (world == null) return;
        Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        new ResearchDialog(owner, world, network).setVisible(true);
    }

    static List<FleetUiRow> fleetRowsForTest(World world, String ownerId) {
        List<FleetUiRow> rows = new ArrayList<>();
        for (FleetView fleet : FleetManager.viewsForOwner(world, ownerId)) rows.add(FleetUiRow.from(fleet));
        return List.copyOf(rows);
    }

    static List<ResearchUiNode> researchNodesForTest(World world, String ownerId) {
        List<ResearchUiNode> rows = new ArrayList<>();
        for (ResearchTopic topic : orderedTopics()) rows.add(ResearchUiNode.from(world, ownerId, topic));
        return List.copyOf(rows);
    }

    private static String playerId(PeerNetwork network) {
        if (network != null) {
            String id = network.localPlayerId();
            if (id != null && !id.isBlank()) return id;
        }
        return PlayerRegistry.localId();
    }

    private static boolean authoritative(PeerNetwork network) {
        return network == null || !network.clientMode();
    }

    private static String primarySystem(FleetView fleet) {
        return fleet.shipsBySystem().entrySet().stream()
                .sorted(Comparator.<Map.Entry<String,Integer>>comparingInt(entry -> -entry.getValue())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).findFirst().orElse("");
    }

    record FleetUiRow(long fleetId, long revision, String name, String system, int ships,
                      int systems, String formation, String stance, String priority,
                      String order, String orderState, String homeBase, String rallySystem,
                      Set<String> members) {
        static FleetUiRow from(FleetView fleet) {
            FleetStrategicOrder order = fleet.order();
            String orderName = order.type() == FleetOrderType.NONE ? "IDLE" : order.type().name();
            return new FleetUiRow(fleet.fleetId(), fleet.revision(), fleet.name(), primarySystem(fleet),
                    fleet.livingShips(), fleet.shipsBySystem().size(), fleet.formation().name(),
                    fleet.combatStance().name(), fleet.targetPriority().name(), orderName,
                    order.state().name(), fleet.homeBaseId(), fleet.rallySystemId(), fleet.memberKeys());
        }

        @Override public String toString() {
            String location = system.isBlank() ? "unknown system" : system;
            if (systems > 1) location += " +" + (systems - 1);
            return name + "  •  " + ships + " ships  •  " + location + "  •  " + order;
        }
    }

    record ResearchUiNode(String id, String name, String branch, int depth, String status,
                          List<String> prerequisites, String doctrineGroup, String excludedBy,
                          String unlocks, String description) {
        static ResearchUiNode from(World world, String ownerId, ResearchTopic topic) {
            String blocked = ResearchPolicy.blockedResearchReason(world, ownerId, topic);
            String status;
            if (world != null && world.hasResearch(ownerId, topic.id)) status = "COMPLETED";
            else if (world != null && ProductionSystem.researchQueued(world, ownerId, topic.id)) status = "QUEUED";
            else status = blocked.isBlank() ? "AVAILABLE" : "BLOCKED";
            return new ResearchUiNode(topic.id, topic.name,
                    topic.branch.isBlank() ? "General" : topic.branch,
                    topicDepth(topic, new LinkedHashSet<>()), status, topic.requires,
                    topic.doctrineGroup, ResearchPolicy.doctrineExclusion(world, ownerId, topic),
                    topic.unlockLabel(), topic.description);
        }

        @Override public String toString() {
            String prefix = switch (status) {
                case "COMPLETED" -> "✓ ";
                case "QUEUED" -> "▶ ";
                case "AVAILABLE" -> "◇ ";
                default -> "× ";
            };
            return prefix + name;
        }
    }

    private static List<ResearchTopic> orderedTopics() {
        List<ResearchTopic> topics = new ArrayList<>(ResearchRules.all());
        topics.sort(Comparator.comparing((ResearchTopic topic) -> topic.branch.isBlank() ? "General" : topic.branch,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(topic -> topicDepth(topic, new LinkedHashSet<>()))
                .thenComparing(topic -> topic.name, String.CASE_INSENSITIVE_ORDER));
        return topics;
    }

    private static int topicDepth(ResearchTopic topic, Set<String> visiting) {
        if (topic == null || topic.requires.isEmpty() || !visiting.add(topic.id)) return 0;
        int depth = 0;
        for (String required : topic.requires) depth = Math.max(depth, 1 + topicDepth(ResearchRules.topic(required), visiting));
        visiting.remove(topic.id);
        return depth;
    }

    private static final class FleetDialog extends JDialog {
        private final World world;
        private final PeerNetwork network;
        private final String ownerId;
        private final boolean authoritative;
        private final DefaultListModel<FleetUiRow> model = new DefaultListModel<>();
        private final JList<FleetUiRow> fleets = new JList<>(model);
        private final JTextArea detail = new JTextArea();
        private final JLabel state = new JLabel();
        private final List<JButton> mutationButtons = new ArrayList<>();
        private Timer refreshTimer;

        FleetDialog(Window owner, World world, PeerNetwork network) {
            super(owner, "Persistent Fleet Command", Dialog.ModalityType.MODELESS);
            this.world = world;
            this.network = network;
            this.ownerId = playerId(network);
            this.authoritative = authoritative(network);
            build();
            refresh();
        }

        private void build() {
            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            setMinimumSize(new Dimension(980, 600));
            setSize(1120, 690);
            setLocationRelativeTo(getOwner());

            JPanel root = new JPanel(new BorderLayout(8, 8));
            root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            JPanel header = new JPanel(new BorderLayout());
            JLabel title = new JLabel("PERSISTENT FLEET COMMAND");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
            header.add(title, BorderLayout.WEST);
            header.add(state, BorderLayout.EAST);
            root.add(header, BorderLayout.NORTH);

            fleets.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            fleets.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) showDetail(); });
            detail.setEditable(false);
            detail.setLineWrap(true);
            detail.setWrapStyleWord(true);
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(fleets), new JScrollPane(detail));
            split.setResizeWeight(0.42);
            root.add(split, BorderLayout.CENTER);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            addMutation(actions, "Create", this::create);
            addMutation(actions, "Rename", this::rename);
            addMutation(actions, "Attach", this::attach);
            addMutation(actions, "Disband", this::disband);
            addMutation(actions, "Formation", this::formation);
            addMutation(actions, "Combat Policy", this::combatPolicy);
            addMutation(actions, "Move", this::move);
            addMutation(actions, "Rally", this::rally);
            addMutation(actions, "Merge", this::merge);
            addMutation(actions, "Split", this::split);
            addMutation(actions, "Detach", this::detach);
            JButton refresh = new JButton("Refresh");
            refresh.addActionListener(event -> { if (!authoritative && network != null && network.clientReady()) request("SYNC"); else refresh(); });
            actions.add(refresh);
            JButton close = new JButton("Close");
            close.addActionListener(event -> dispose());
            actions.add(close);
            root.add(actions, BorderLayout.SOUTH);

            for (JButton button : mutationButtons) button.setEnabled(canMutate());
            if (!authoritative) for (JButton button : mutationButtons)
                button.setToolTipText("Fleet changes are sent to the authenticated host and applied only after server ownership/revision checks.");

            setContentPane(root);
            refreshTimer = new Timer(750, event -> refreshPreservingSelection());
            refreshTimer.setCoalesce(true);
            refreshTimer.start();
        }

        private void addMutation(JPanel actions, String label, Runnable action) {
            JButton button = new JButton(label);
            button.addActionListener(event -> action.run());
            mutationButtons.add(button);
            actions.add(button);
        }

        @Override public void dispose() {
            if (refreshTimer != null) refreshTimer.stop();
            super.dispose();
        }

        private void refreshPreservingSelection() {
            FleetUiRow selected = fleets.getSelectedValue();
            long id = selected == null ? 0 : selected.fleetId();
            refresh();
            if (id > 0) for (int i = 0; i < model.size(); i++) if (model.get(i).fleetId() == id) {
                fleets.setSelectedIndex(i);
                break;
            }
        }

        private void refresh() {
            model.clear();
            for (FleetUiRow row : fleetRowsForTest(world, ownerId)) model.addElement(row);
            if (!model.isEmpty() && fleets.getSelectedIndex() < 0) fleets.setSelectedIndex(0);
            for (JButton button : mutationButtons) button.setEnabled(canMutate());
            state.setText(model.size() + " persistent fleet" + (model.size() == 1 ? "" : "s")
                    + (authoritative ? " • authoritative" : canMutate() ? " • server-authoritative client" : " • reconnecting/read-only"));
            showDetail();
        }

        private FleetUiRow selected() { return fleets.getSelectedValue(); }

        private void showDetail() {
            FleetUiRow row = selected();
            if (row == null) {
                detail.setText("No persistent fleets exist for this player. Use Create to form one from owned, unassigned ships.");
                return;
            }
            StringBuilder text = new StringBuilder();
            text.append(row.name()).append("  (#").append(row.fleetId()).append(")\n\n")
                    .append("Ships: ").append(row.ships()).append(" across ").append(row.systems()).append(" system(s)\n")
                    .append("Primary system: ").append(row.system().isBlank() ? "Unknown" : row.system()).append('\n')
                    .append("Formation: ").append(row.formation()).append('\n')
                    .append("Combat stance: ").append(row.stance()).append('\n')
                    .append("Target priority: ").append(row.priority()).append('\n')
                    .append("Strategic order: ").append(row.order()).append(" / ").append(row.orderState()).append('\n')
                    .append("Home base: ").append(row.homeBase().isBlank() ? "Not set" : row.homeBase()).append('\n')
                    .append("Rally system: ").append(row.rallySystem().isBlank() ? "Not set" : row.rallySystem()).append("\n\n")
                    .append("Members:\n");
            for (String member : row.members()) text.append("  • ").append(member).append('\n');
            detail.setText(text.toString());
            detail.setCaretPosition(0);
        }

        private boolean canMutate() {
            return authoritative || network != null && network.clientReady() && !network.clientObserver();
        }

        private void create() {
            LinkedHashSet<String> available = new LinkedHashSet<>();
            for (String key : FleetWire.ownerLocations(world, ownerId).keySet())
                if (FleetManager.fleetIdForUnit(world, key) == 0) available.add(key);
            if (available.isEmpty()) { world.status = "No unassigned owned ships are available."; return; }
            String name = JOptionPane.showInputDialog(this, "Fleet name", "New Fleet");
            if (name == null) return;
            String members = JOptionPane.showInputDialog(this,
                    "Member keys (comma-separated)\nAvailable: " + String.join(", ", available), String.join(", ", available));
            if (members == null) return;
            request("CREATE", name, String.join(",", parseMembers(members)));
        }

        private void attach() {
            FleetUiRow row = selected(); if (row == null) return;
            LinkedHashSet<String> available = new LinkedHashSet<>();
            for (String key : FleetWire.ownerLocations(world, ownerId).keySet())
                if (FleetManager.fleetIdForUnit(world, key) == 0) available.add(key);
            if (available.isEmpty()) { world.status = "No unassigned owned ships are available."; return; }
            String members = JOptionPane.showInputDialog(this,
                    "Member keys to attach (comma-separated)\nAvailable: " + String.join(", ", available), "");
            if (members == null) return;
            request("ADD", row.fleetId(), row.revision(), String.join(",", parseMembers(members)));
        }

        private void disband() {
            FleetUiRow row = selected(); if (row == null) return;
            if (JOptionPane.showConfirmDialog(this, "Disband " + row.name() + "? Ships remain intact.",
                    "Disband Fleet", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            request("DISBAND", row.fleetId(), row.revision());
        }

        private void rename() {
            FleetUiRow row = selected(); if (row == null) return;
            String name = JOptionPane.showInputDialog(this, "Fleet name", row.name());
            if (name == null) return;
            request("RENAME", row.fleetId(), row.revision(), name);
        }

        private void formation() {
            FleetUiRow row = selected(); if (row == null) return;
            FleetFormation selected = (FleetFormation)JOptionPane.showInputDialog(this, "Default formation", "Fleet Formation",
                    JOptionPane.PLAIN_MESSAGE, null, FleetFormation.values(), FleetFormation.valueOf(row.formation()));
            if (selected == null) return;
            request("FORMATION", row.fleetId(), row.revision(), selected.name());
        }

        private void combatPolicy() {
            FleetUiRow row = selected(); if (row == null) return;
            JComboBox<CombatStance> stance = new JComboBox<>(CombatStance.values());
            stance.setSelectedItem(CombatStance.valueOf(row.stance()));
            JComboBox<TargetPriorityPolicy> priority = new JComboBox<>(TargetPriorityPolicy.values());
            priority.setSelectedItem(TargetPriorityPolicy.valueOf(row.priority()));
            JPanel panel = new JPanel(new BorderLayout(6, 6));
            panel.add(new JLabel("Combat stance"), BorderLayout.NORTH);
            panel.add(stance, BorderLayout.CENTER);
            JPanel lower = new JPanel(new BorderLayout(6, 6));
            lower.add(new JLabel("Target priority"), BorderLayout.NORTH);
            lower.add(priority, BorderLayout.CENTER);
            panel.add(lower, BorderLayout.SOUTH);
            if (JOptionPane.showConfirmDialog(this, panel, "Combat Policy", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            request("POLICY", row.fleetId(), row.revision(),
                    ((CombatStance)stance.getSelectedItem()).name(), ((TargetPriorityPolicy)priority.getSelectedItem()).name());
        }

        private void move() {
            FleetUiRow row = selected(); if (row == null) return;
            GalaxyMapSystem destination = chooseSystem("Move Fleet", row.system());
            if (destination == null) return;
            request("MOVE", row.fleetId(), row.revision(), destination.id());
        }

        private void rally() {
            FleetUiRow row = selected(); if (row == null) return;
            GalaxyMapSystem destination = chooseSystem("Rally Fleet", row.rallySystem());
            if (destination == null) return;
            request("RALLY", row.fleetId(), row.revision(), destination.id(), world.width * 0.5, world.height * 0.5);
        }

        private GalaxyMapSystem chooseSystem(String title, String current) {
            List<GalaxyMapSystem> systems = world.galaxyMapSnapshot().systems();
            if (systems == null || systems.isEmpty()) return null;
            GalaxyMapSystem initial = systems.get(0);
            for (GalaxyMapSystem system : systems) if (system.id().equals(current)) { initial = system; break; }
            return (GalaxyMapSystem)JOptionPane.showInputDialog(this, "Destination system", title,
                    JOptionPane.PLAIN_MESSAGE, null, systems.toArray(), initial);
        }

        private void merge() {
            FleetUiRow source = selected(); if (source == null) return;
            List<FleetUiRow> options = new ArrayList<>();
            for (int i = 0; i < model.size(); i++) if (model.get(i).fleetId() != source.fleetId()) options.add(model.get(i));
            if (options.isEmpty()) return;
            FleetUiRow target = (FleetUiRow)JOptionPane.showInputDialog(this, "Merge selected fleet into", "Merge Fleets",
                    JOptionPane.PLAIN_MESSAGE, null, options.toArray(), options.get(0));
            if (target == null) return;
            request("MERGE", target.fleetId(), source.fleetId(), target.revision(), source.revision());
        }

        private void split() {
            FleetUiRow source = selected(); if (source == null || source.members().size() < 2) return;
            String members = JOptionPane.showInputDialog(this,
                    "Member keys to move to the new fleet (comma-separated)\nAvailable: " + String.join(", ", source.members()), "");
            if (members == null) return;
            String name = JOptionPane.showInputDialog(this, "New fleet name", source.name() + " Detachment");
            if (name == null) return;
            request("SPLIT", source.fleetId(), source.revision(), name, String.join(",", parseMembers(members)));
        }

        private void detach() {
            FleetUiRow source = selected(); if (source == null) return;
            String members = JOptionPane.showInputDialog(this,
                    "Member keys to detach (comma-separated)\nAvailable: " + String.join(", ", source.members()), "");
            if (members == null) return;
            request("DETACH", source.fleetId(), source.revision(), String.join(",", parseMembers(members)));
        }

        private void request(String action, Object... values) {
            if (!canMutate()) { world.status = "Fleet command unavailable while reconnecting or in observer mode."; refresh(); return; }
            String packet = FleetWire.command(ownerId, action, values);
            if (network != null) network.fleet(packet);
            else FleetWire.applyLocal(world, ownerId, packet);
            refresh();
        }

        private Set<String> parseMembers(String text) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            if (text == null) return out;
            for (String value : text.split(",")) if (!value.trim().isBlank()) out.add(value.trim());
            return out;
        }

        private void commandResult(FleetCommandResult result) {
            world.status = result != null && result.applied() ? result.message() : result == null ? "Fleet command failed." : result.message();
            refresh();
        }

        private void apply(FleetMutationResult result, String action) {
            world.status = result == FleetMutationResult.APPLIED ? "Fleet " + action + " applied." : "Fleet " + action + " rejected: " + result;
            refresh();
        }
    }

    private static final class ResearchDialog extends JDialog {
        private final World world;
        private final PeerNetwork network;
        private final String ownerId;
        private final JTree tree = new JTree();
        private final JTextArea detail = new JTextArea();
        private final JLabel state = new JLabel();
        private final JButton queue = new JButton("Queue Research");
        private Timer refreshTimer;

        ResearchDialog(Window owner, World world, PeerNetwork network) {
            super(owner, "Branching Research Tree", Dialog.ModalityType.MODELESS);
            this.world = world;
            this.network = network;
            this.ownerId = playerId(network);
            build();
            refresh();
        }

        private void build() {
            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            setMinimumSize(new Dimension(980, 620));
            setSize(1120, 720);
            setLocationRelativeTo(getOwner());
            JPanel root = new JPanel(new BorderLayout(8, 8));
            root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            JPanel header = new JPanel(new BorderLayout());
            JLabel title = new JLabel("BRANCHING RESEARCH / STRATEGIC DOCTRINES");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
            header.add(title, BorderLayout.WEST);
            header.add(state, BorderLayout.EAST);
            root.add(header, BorderLayout.NORTH);

            tree.setRootVisible(true);
            tree.setShowsRootHandles(true);
            tree.addTreeSelectionListener(event -> showDetail());
            detail.setEditable(false);
            detail.setLineWrap(true);
            detail.setWrapStyleWord(true);
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tree), new JScrollPane(detail));
            split.setResizeWeight(0.45);
            root.add(split, BorderLayout.CENTER);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            queue.addActionListener(event -> queueSelected());
            actions.add(queue);
            JButton refresh = new JButton("Refresh");
            refresh.addActionListener(event -> refresh());
            actions.add(refresh);
            JButton close = new JButton("Close");
            close.addActionListener(event -> dispose());
            actions.add(close);
            root.add(actions, BorderLayout.SOUTH);
            setContentPane(root);
            refreshTimer = new Timer(750, event -> refreshPreservingSelection());
            refreshTimer.setCoalesce(true);
            refreshTimer.start();
        }

        @Override public void dispose() {
            if (refreshTimer != null) refreshTimer.stop();
            super.dispose();
        }

        private void refreshPreservingSelection() {
            ResearchUiNode selected = selected();
            String id = selected == null ? "" : selected.id();
            refresh();
            if (!id.isBlank()) selectById(id);
        }

        private void refresh() {
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("Research Doctrines");
            LinkedHashMap<String, DefaultMutableTreeNode> branches = new LinkedHashMap<>();
            List<ResearchUiNode> nodes = researchNodesForTest(world, ownerId);
            for (ResearchUiNode node : nodes) {
                DefaultMutableTreeNode branch = branches.computeIfAbsent(node.branch(), key -> {
                    DefaultMutableTreeNode created = new DefaultMutableTreeNode(key);
                    root.add(created);
                    return created;
                });
                branch.add(new DefaultMutableTreeNode(node));
            }
            tree.setModel(new DefaultTreeModel(root));
            for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
            long completed = nodes.stream().filter(node -> "COMPLETED".equals(node.status())).count();
            long queued = nodes.stream().filter(node -> "QUEUED".equals(node.status())).count();
            state.setText(completed + "/" + nodes.size() + " completed • " + queued + " queued");
            if (tree.getSelectionPath() == null && tree.getRowCount() > 1) tree.setSelectionRow(1);
            showDetail();
        }

        private ResearchUiNode selected() {
            TreePath path = tree.getSelectionPath();
            if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)
                    || !(node.getUserObject() instanceof ResearchUiNode research)) return null;
            return research;
        }

        private void showDetail() {
            ResearchUiNode node = selected();
            if (node == null) {
                detail.setText("Select a technology node to inspect its dependency chain, doctrine constraints, unlocks and queue state.");
                queue.setEnabled(false);
                return;
            }
            String prerequisite = node.prerequisites().isEmpty() ? "None" : labels(node.prerequisites());
            String doctrine = node.doctrineGroup().isBlank() ? "None" : node.doctrineGroup();
            String excluded = node.excludedBy().isBlank() ? "None" : node.excludedBy();
            detail.setText(node.name() + "\n\nBranch: " + node.branch() + "\nTier/depth: " + node.depth()
                    + "\nStatus: " + node.status() + "\nPrerequisites: " + prerequisite
                    + "\nDoctrine group: " + doctrine + "\nExcluded by: " + excluded + "\n\n"
                    + node.description() + "\n\n" + node.unlocks());
            detail.setCaretPosition(0);
            queue.setEnabled("AVAILABLE".equals(node.status()) && compatibleResearchBase(node.id()) != null);
            queue.setToolTipText(queue.isEnabled() ? "Queue through the same host-authoritative production path used by Manufacturing Command."
                    : "Research is completed, queued, blocked, or no compatible owned research station is active.");
        }

        private String labels(List<String> ids) {
            List<String> labels = new ArrayList<>();
            for (String id : ids) {
                ResearchTopic topic = ResearchRules.topic(id);
                labels.add(topic == null ? id : topic.name);
            }
            return String.join(" → ", labels);
        }

        private Base compatibleResearchBase(String topicId) {
            ResearchTopic topic = ResearchRules.topic(topicId);
            if (topic == null) return null;
            List<Base> candidates = new ArrayList<>();
            for (Base base : world.bases.values()) {
                if (base.hp > 0 && ownerId.equals(base.playerId) && topic.canResearchAt(base.typeId)) candidates.add(base);
            }
            candidates.sort(Comparator.comparingInt((Base base) -> base.productionQueue.size()).thenComparing(base -> base.id));
            for (Base base : candidates) if (ResearchPolicy.blockedResearchReason(world, base, topic).isBlank()) return base;
            return null;
        }

        private void queueSelected() {
            ResearchUiNode node = selected();
            if (node == null) return;
            Base base = compatibleResearchBase(node.id());
            if (base == null) {
                world.status = "No compatible owned research station can queue " + node.name() + ".";
                refresh();
                return;
            }
            boolean accepted;
            if (network == null) accepted = ProductionCommands.apply(world, base.playerId, "ENQUEUE", base.id,
                    ProductionJobKind.RESEARCH.name(), node.id());
            else {
                network.production(base.playerId, "ENQUEUE", base.id, ProductionJobKind.RESEARCH.name(), node.id());
                accepted = true;
            }
            world.status = accepted ? "Research queued: " + node.name() + "." : "Research request rejected: " + node.name() + ".";
            refresh();
            selectById(node.id());
        }

        private void selectById(String id) {
            Object modelRoot = tree.getModel().getRoot();
            if (!(modelRoot instanceof DefaultMutableTreeNode root)) return;
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode branch = (DefaultMutableTreeNode)root.getChildAt(i);
                for (int j = 0; j < branch.getChildCount(); j++) {
                    DefaultMutableTreeNode child = (DefaultMutableTreeNode)branch.getChildAt(j);
                    if (child.getUserObject() instanceof ResearchUiNode node && node.id().equals(id)) {
                        TreePath path = new TreePath(child.getPath());
                        tree.setSelectionPath(path);
                        tree.scrollPathToVisible(path);
                        return;
                    }
                }
            }
        }
    }
}
