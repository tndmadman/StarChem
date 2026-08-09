package com.tndmadman.rts;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Swing editor for persistent station production policies and reusable templates. */
final class ProductionPolicyMenu {
    private static final Color BACKGROUND = new Color(10, 20, 30);
    private static final Color PANEL = new Color(18, 37, 53);
    private static final Color TEXT = new Color(230, 242, 250);
    private static final Color MUTED = new Color(155, 180, 196);
    private static final Color ACCENT = new Color(90, 220, 255);

    private ProductionPolicyMenu() { }

    static void show(Component invoker, World world, PeerNetwork network, Base base, int x, int y) {
        if (invoker == null || world == null || base == null || StationControls.nonProduction(base.typeId)) return;
        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createLineBorder(ACCENT));
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel title = label(base.type().name.toUpperCase(Locale.ROOT) + " PRODUCTION POLICIES", Font.BOLD, 15, TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(title);
        content.add(Box.createVerticalStrut(3));
        JLabel note = label("Standing policies create normal visible queue jobs. Manual cancellation pauses the owning policy.",
                Font.PLAIN, 11, MUTED);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(note);
        content.add(Box.createVerticalStrut(9));

        List<ProductionPolicyRecoveryBridge.OrphanView> orphans =
                ProductionPolicyRecoveryBridge.orphanViews(world, base.playerId);
        if (!orphans.isEmpty()) {
            JLabel orphanTitle = label("ORPHANED POLICIES", Font.BOLD, 12, new Color(255, 145, 120));
            orphanTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(orphanTitle);
            content.add(Box.createVerticalStrut(3));
            JLabel orphanNote = label("Their assigned station was lost. Reassign a compatible policy here or delete it.",
                    Font.PLAIN, 11, MUTED);
            orphanNote.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(orphanNote);
            content.add(Box.createVerticalStrut(6));
            for (ProductionPolicyRecoveryBridge.OrphanView orphan : orphans) {
                content.add(orphanRow(popup, invoker, world, network, base, x, y, orphan));
                content.add(Box.createVerticalStrut(5));
            }
            content.add(Box.createVerticalStrut(7));
        }

        List<ProductionPolicySystem.PolicyView> policies = ProductionPolicySystem.viewsForBase(world, base);
        if (policies.isEmpty()) {
            JLabel empty = label("No standing production policies are configured for this station.", Font.PLAIN, 12, MUTED);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(empty);
            content.add(Box.createVerticalStrut(7));
        } else {
            for (ProductionPolicySystem.PolicyView policy : policies) {
                content.add(policyRow(popup, invoker, world, network, base, x, y, policy));
                content.add(Box.createVerticalStrut(5));
            }
        }

        JButton create = actionButton("CREATE POLICY", () -> openEditor(
                popup, invoker, world, network, base, x, y, null));
        create.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(create);
        content.add(Box.createVerticalStrut(12));

        JLabel templateTitle = label("REUSABLE TEMPLATES", Font.BOLD, 12, ACCENT);
        templateTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(templateTitle);
        content.add(Box.createVerticalStrut(4));
        JLabel templateNote = label("Applying a template creates independent copies; later template edits do not live-link stations.",
                Font.PLAIN, 11, MUTED);
        templateNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(templateNote);
        content.add(Box.createVerticalStrut(7));

        List<ProductionPolicySystem.TemplateView> templates = ProductionPolicySystem.templateViews(world, base);
        for (ProductionPolicySystem.TemplateView template : templates) {
            content.add(templateRow(popup, invoker, world, network, base, x, y, template));
            content.add(Box.createVerticalStrut(4));
        }
        JButton saveTemplate = actionButton("SAVE THIS STATION AS TEMPLATE", () -> {
            String name = JOptionPane.showInputDialog(invoker, "Template name:", "Save production template",
                    JOptionPane.PLAIN_MESSAGE);
            if (name == null) return;
            send(popup, invoker, world, network, base, x, y,
                    ProductionPolicySystem.COMMAND_TEMPLATE_SAVE, name);
        });
        saveTemplate.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveTemplate.setEnabled(!policies.isEmpty());
        content.add(saveTemplate);
        content.add(Box.createVerticalStrut(10));

        JButton back = actionButton("BACK TO STATION CONTROLS", () -> {
            popup.setVisible(false);
            StationControlMenu.showIfHandled(invoker, world, network, base, x, y);
        });
        back.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(back);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setPreferredSize(new Dimension(620, Math.min(680, Math.max(280, content.getPreferredSize().height + 18))));
        popup.add(scroll);
        popup.show(invoker, Math.max(4, x), Math.max(4, y));
    }

    private static JPanel orphanRow(JPopupMenu popup, Component invoker, World world, PeerNetwork network,
                                    Base base, int x, int y, ProductionPolicyRecoveryBridge.OrphanView orphan) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBackground(PANEL);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 145, 120)),
                BorderFactory.createEmptyBorder(6, 7, 6, 7)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
        JLabel title = label(orphan.id() + " | " + readable(orphan.type()) + " | " + orphan.itemId(),
                Font.BOLD, 11, TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(title);
        JLabel detail = label("Lost station " + orphan.stationId() + " | target " + whole(orphan.targetAmount()),
                Font.PLAIN, 10, MUTED);
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(detail);
        row.add(Box.createVerticalStrut(4));
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(smallButton("Reassign here", () -> send(popup, invoker, world, network, base, x, y,
                ProductionPolicyRecoveryBridge.COMMAND_RECOVER_HERE, orphan.id())));
        actions.add(Box.createHorizontalStrut(4));
        actions.add(smallButton("Delete", () -> send(popup, invoker, world, network, base, x, y,
                ProductionPolicyRecoveryBridge.COMMAND_DELETE_ORPHAN, orphan.id())));
        actions.add(Box.createHorizontalGlue());
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(actions);
        return row;
    }

    private static JPanel policyRow(JPopupMenu popup, Component invoker, World world, PeerNetwork network,
                                    Base base, int x, int y, ProductionPolicySystem.PolicyView policy) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBackground(PANEL);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(policy.enabled() ? ACCENT : new Color(85, 95, 105)),
                BorderFactory.createEmptyBorder(6, 7, 6, 7)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 118));

        JLabel title = label(policy.id() + " | " + readable(policy.type()) + " | " + itemLabel(world, policy),
                Font.BOLD, 11, TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(title);
        JLabel status = label(policy.status().name().replace('_', ' ') + (policy.reason().isBlank() ? "" : " — " + policy.reason()),
                Font.PLAIN, 10, statusColor(policy.status()));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(status);
        JLabel detail = label("target " + whole(policy.targetAmount()) + " | batch " + policy.batchSize()
                + " | priority " + policy.priority() + " | max outstanding " + policy.maxOutstandingJobs()
                + (policy.type() == ProductionPolicySystem.PolicyType.REPEAT
                ? " | completed " + policy.completedBatches() + (policy.repeatLimit() > 0 ? "/" + policy.repeatLimit() : "") : "")
                + " | jobs " + policy.jobIds().size(), Font.PLAIN, 10, MUTED);
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(detail);
        row.add(Box.createVerticalStrut(4));

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(smallButton(policy.enabled() ? "Pause" : "Resume", () -> send(
                popup, invoker, world, network, base, x, y, ProductionPolicySystem.COMMAND_TOGGLE,
                policy.id() + "~" + (policy.enabled() ? "0" : "1"))));
        actions.add(Box.createHorizontalStrut(4));
        actions.add(smallButton("Edit", () -> openEditor(popup, invoker, world, network, base, x, y, policy)));
        actions.add(Box.createHorizontalStrut(4));
        actions.add(smallButton("↑", () -> send(popup, invoker, world, network, base, x, y,
                ProductionPolicySystem.COMMAND_MOVE_UP, policy.id())));
        actions.add(Box.createHorizontalStrut(3));
        actions.add(smallButton("↓", () -> send(popup, invoker, world, network, base, x, y,
                ProductionPolicySystem.COMMAND_MOVE_DOWN, policy.id())));
        actions.add(Box.createHorizontalStrut(4));
        actions.add(smallButton("Delete", () -> send(popup, invoker, world, network, base, x, y,
                ProductionPolicySystem.COMMAND_DELETE, policy.id())));
        actions.add(Box.createHorizontalGlue());
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(actions);
        return row;
    }

    private static JPanel templateRow(JPopupMenu popup, Component invoker, World world, PeerNetwork network,
                                      Base base, int x, int y, ProductionPolicySystem.TemplateView template) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBackground(PANEL);
        row.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        JLabel name = label(template.name() + " [" + template.id() + "] — " + template.entryCount() + " polic"
                + (template.entryCount() == 1 ? "y" : "ies"), Font.PLAIN, 11, TEXT);
        row.add(name);
        row.add(Box.createHorizontalGlue());
        row.add(smallButton("Apply", () -> send(popup, invoker, world, network, base, x, y,
                ProductionPolicySystem.COMMAND_TEMPLATE_APPLY, template.id())));
        row.add(Box.createHorizontalStrut(4));
        row.add(smallButton("Delete", () -> send(popup, invoker, world, network, base, x, y,
                ProductionPolicySystem.COMMAND_TEMPLATE_DELETE, template.id())));
        return row;
    }

    private static void openEditor(JPopupMenu popup, Component invoker, World world, PeerNetwork network,
                                   Base base, int x, int y, ProductionPolicySystem.PolicyView existing) {
        List<ItemChoice> choices = itemChoices(world, base);
        if (choices.isEmpty()) {
            world.status = base.type().name + " has no ship or manufacturing outputs available for standing policies.";
            return;
        }
        JComboBox<ItemChoice> item = new JComboBox<>(choices.toArray(ItemChoice[]::new));
        JComboBox<ProductionPolicySystem.PolicyType> type = new JComboBox<>(ProductionPolicySystem.PolicyType.values());
        JTextField loadout = new JTextField(existing == null ? "" : existing.loadoutId());
        JTextField target = new JTextField(existing == null ? "10" : whole(existing.targetAmount()));
        JSpinner batch = new JSpinner(new SpinnerNumberModel(existing == null ? 1 : existing.batchSize(),
                1, ProductionPolicySystem.MAX_BATCH_SIZE, 1));
        JSpinner priority = new JSpinner(new SpinnerNumberModel(existing == null ? 50 : existing.priority(), 0, 100, 1));
        JSpinner maxOutstanding = new JSpinner(new SpinnerNumberModel(existing == null ? 2 : existing.maxOutstandingJobs(),
                1, ProductionPolicySystem.MAX_OUTSTANDING_PER_POLICY, 1));
        JSpinner repeatLimit = new JSpinner(new SpinnerNumberModel(existing == null ? 0 : existing.repeatLimit(), 0, 100_000, 1));
        JTextField stationReserve = new JTextField();
        JTextField networkReserve = new JTextField();
        JCheckBox replaceReserves = new JCheckBox("Replace reserve floors");
        if (existing != null) {
            type.setSelectedItem(existing.type());
            selectExisting(item, existing);
            stationReserve.setEnabled(false);
            networkReserve.setEnabled(false);
            replaceReserves.addActionListener(event -> {
                stationReserve.setEnabled(replaceReserves.isSelected());
                networkReserve.setEnabled(replaceReserves.isSelected());
            });
        }

        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 5));
        fields.add(new JLabel("Policy type")); fields.add(type);
        fields.add(new JLabel("Output")); fields.add(item);
        fields.add(new JLabel("Ship loadout ID (blank = default)")); fields.add(loadout);
        fields.add(new JLabel("Target amount/count (Repeat: 0)")); fields.add(target);
        fields.add(new JLabel("Batch enqueue limit")); fields.add(batch);
        fields.add(new JLabel("Priority (0-100)")); fields.add(priority);
        fields.add(new JLabel("Maximum outstanding jobs")); fields.add(maxOutstanding);
        fields.add(new JLabel("Repeat maximum (0 = unlimited)")); fields.add(repeatLimit);
        fields.add(new JLabel("Station reserves (IRON:100,FUEL:50)")); fields.add(stationReserve);
        fields.add(new JLabel("Network reserves (same format)")); fields.add(networkReserve);
        if (existing != null) {
            fields.add(replaceReserves);
            fields.add(new JLabel("Unchecked keeps current server reserve floors; checked + blank clears them"));
        }

        int answer = JOptionPane.showConfirmDialog(invoker, fields,
                existing == null ? "Create production policy" : "Edit " + existing.id(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        ItemChoice selected = (ItemChoice)item.getSelectedItem();
        ProductionPolicySystem.PolicyType selectedType = (ProductionPolicySystem.PolicyType)type.getSelectedItem();
        if (selected == null || selectedType == null) return;
        double targetValue = number(target.getText());
        Map<Material,Double> stationFloors = reserves(stationReserve.getText());
        Map<Material,Double> networkFloors = reserves(networkReserve.getText());
        if (!Double.isFinite(targetValue) || targetValue < 0 || stationFloors == null || networkFloors == null) {
            world.status = "Invalid production policy target or reserve list.";
            return;
        }
        String loadoutId = selected.kind == ProductionJobKind.SHIP
                ? loadout.getText().trim() : "";
        if (selected.kind == ProductionJobKind.SHIP && loadoutId.isBlank()) {
            loadoutId = WeaponRules.defaultLoadoutId(selected.itemId);
        }
        String encoded = ProductionPolicyWire.encodeSpec(existing == null ? "" : existing.id(),
                selectedType, selected.kind, selected.itemId, loadoutId, targetValue,
                ((Number)batch.getValue()).intValue(), ((Number)priority.getValue()).intValue(),
                ((Number)maxOutstanding.getValue()).intValue(), ((Number)repeatLimit.getValue()).intValue(),
                stationFloors, networkFloors);
        String command = existing == null
                ? ProductionPolicySystem.COMMAND_CREATE
                : replaceReserves.isSelected()
                ? ProductionPolicySystem.COMMAND_UPDATE
                : ProductionPolicyCommandBridge.COMMAND_UPDATE_KEEP_RESERVES;
        send(popup, invoker, world, network, base, x, y, command, encoded);
    }

    private static List<ItemChoice> itemChoices(World world, Base base) {
        List<ItemChoice> out = new ArrayList<>();
        for (String shipId : base.type().buildableShips) {
            ShipType ship = Rules.findShip(shipId);
            if (ship != null) out.add(new ItemChoice(ProductionJobKind.SHIP, ship.id,
                    "Ship | " + ship.name + " [" + ship.id + "]"));
        }
        for (CraftableItem craftable : CraftingRules.forStation(base.typeId)) {
            if (craftable != null) out.add(new ItemChoice(ProductionJobKind.CRAFTABLE, craftable.id,
                    "Manufacture | " + craftable.name + " [" + craftable.id + "]"));
        }
        return out;
    }

    private static void selectExisting(JComboBox<ItemChoice> combo, ProductionPolicySystem.PolicyView existing) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            ItemChoice choice = combo.getItemAt(i);
            if (choice.kind == existing.kind() && choice.itemId.equals(existing.itemId())) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    private static Map<Material,Double> reserves(String text) {
        EnumMap<Material,Double> out = new EnumMap<>(Material.class);
        if (text == null || text.isBlank()) return out;
        for (String token : text.split(",")) {
            String[] pair = token.trim().split(":", 2);
            if (pair.length != 2) return null;
            try {
                Material material = Material.valueOf(pair[0].trim().toUpperCase(Locale.ROOT));
                double amount = Double.parseDouble(pair[1].trim());
                if (!Double.isFinite(amount) || amount < 0 || amount > 1_000_000) return null;
                if (amount > 0.05) out.put(material, amount);
            } catch (RuntimeException ex) {
                return null;
            }
        }
        return out;
    }

    private static double number(String text) {
        try { return Double.parseDouble(text.trim()); }
        catch (RuntimeException ignored) { return Double.NaN; }
    }

    private static void send(JPopupMenu popup, Component invoker, World world, PeerNetwork network,
                             Base base, int x, int y, String command, String payload) {
        boolean remote = network != null && network.clientMode();
        boolean changed;
        if (remote) {
            network.production(base.playerId, "POLICY", base.id, command, payload);
            world.status = "Requested production policy update from server.";
            changed = true;
        } else {
            changed = ProductionCommands.apply(world, base.playerId, "POLICY", base.id, command, payload);
        }
        if (!changed) {
            if (world.status == null || world.status.isBlank()) world.status = "Production policy update was rejected.";
            return;
        }
        popup.setVisible(false);
        if (!remote) show(invoker, world, network, base, x, y);
    }

    private static String itemLabel(World world, ProductionPolicySystem.PolicyView policy) {
        if (policy.kind() == ProductionJobKind.SHIP) {
            ShipType ship = Rules.findShip(policy.itemId());
            return ship == null ? policy.itemId() : ship.name;
        }
        CraftableItem item = CraftingRules.item(policy.itemId());
        return item == null ? policy.itemId() : item.name;
    }

    private static String readable(ProductionPolicySystem.PolicyType type) {
        return switch (type) {
            case MAINTAIN_STOCK -> "Maintain stock";
            case MAINTAIN_FLEET -> "Maintain fleet";
            case REPEAT -> "Repeat";
        };
    }

    private static Color statusColor(ProductionPolicySystem.PolicyStatus status) {
        return switch (status) {
            case SATISFIED -> new Color(130, 235, 155);
            case PRODUCING -> ACCENT;
            case WAITING_FOR_RESOURCES, RESERVE_PROTECTED -> new Color(245, 205, 110);
            case BLOCKED_RESEARCH, NO_COMPATIBLE_STATION, ORPHANED -> new Color(255, 125, 115);
            case PAUSED -> MUTED;
        };
    }

    private static JLabel label(String text, int style, int size, Color color) {
        JLabel label = new JLabel(text == null ? "" : text, SwingConstants.LEFT);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(style, (float)size));
        return label;
    }

    private static JButton actionButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setFocusPainted(false);
        button.setForeground(TEXT);
        button.setBackground(PANEL);
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ACCENT),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        button.addActionListener(event -> action.run());
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return button;
    }

    private static JButton smallButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setForeground(TEXT);
        button.setBackground(new Color(28, 67, 86));
        button.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
        button.addActionListener(event -> action.run());
        return button;
    }

    private static String whole(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private record ItemChoice(ProductionJobKind kind, String itemId, String label) {
        @Override public String toString() { return label; }
    }
}