package com.tndmadman.rts;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Dedicated player-facing fitting window for authored ship loadout presets. */
final class ShipFittingWindow {
    private static final Color BACKGROUND = new Color(5, 12, 20);
    private static final Color PANEL = new Color(10, 24, 36);
    private static final Color BORDER = new Color(78, 173, 226);
    private static final Color TEXT = Color.WHITE;
    private static final Color MUTED = new Color(177, 207, 224);
    private static final Color GOOD = new Color(125, 226, 166);
    private static final Color WARNING = new Color(255, 198, 104);
    private static final Color BAD = new Color(255, 126, 126);

    private JDialog dialog;

    void showForUnit(Component parent, World world, PeerNetwork network, Unit unit) {
        if (parent == null || world == null || unit == null) return;
        close();
        Window owner = SwingUtilities.getWindowAncestor(parent);
        dialog = new JDialog(owner, "Ship Fitting", Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(content(world, network, unit));
        dialog.getRootPane().registerKeyboardAction(
                event -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setMinimumSize(new Dimension(720, 560));
        dialog.setSize(820, 680);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    void close() {
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
    }

    boolean visible() { return dialog != null && dialog.isVisible(); }

    private JComponent content(World world, PeerNetwork network, Unit unit) {
        JPanel root = panel(new BorderLayout(0, 10), BACKGROUND);
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        root.add(header(world, network, unit), BorderLayout.NORTH);

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(BACKGROUND);
        List<ShipLoadoutDefinition> variants = new ArrayList<>(WeaponRules.loadoutsForHull(unit.shipTypeId));
        variants.sort(Comparator.comparing((ShipLoadoutDefinition fit) -> !fit.defaultForHull())
                .thenComparing(ShipLoadoutDefinition::displayName));
        for (ShipLoadoutDefinition loadout : variants) {
            list.add(loadoutCard(world, network, unit, loadout));
            list.add(Box.createVerticalStrut(8));
        }
        if (variants.isEmpty()) list.add(statusPanel("No authored fitting presets exist for this hull.", BAD));

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(42, 86, 116)));
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(26);
        root.add(scroll, BorderLayout.CENTER);
        root.add(stationStatus(world, unit), BorderLayout.SOUTH);
        return root;
    }

    private JComponent header(World world, PeerNetwork network, Unit unit) {
        JPanel header = panel(new BorderLayout(12, 8), PANEL);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), new EmptyBorder(10, 12, 10, 12)));
        ShipLoadoutDefinition current = WeaponRules.resolveForHull(unit.shipTypeId, unit.loadoutId);

        JPanel text = panel(new GridLayout(0, 1, 0, 3), PANEL);
        text.add(label(unit.type().name + " #" + unit.unitId + "  |  FITTING", 18, Font.BOLD, TEXT));
        text.add(label("Installed preset: " + (current == null ? unit.loadoutId : current.displayName()),
                12, Font.BOLD, GOOD));
        text.add(label(current == null ? "Weapons unavailable" : weaponSummary(current), 11, Font.PLAIN, MUTED));
        header.add(text, BorderLayout.CENTER);

        ActiveRefit active = activeRefit(world, unit);
        JPanel state = panel(new GridLayout(0, 1, 0, 4), PANEL);
        state.add(label("State: " + (active != null ? "REFITTING" : readyState(unit) ? "READY" : "NOT READY"),
                11, Font.BOLD, active != null ? WARNING : readyState(unit) ? GOOD : WARNING));
        if (active != null) {
            ShipLoadoutDefinition target = WeaponRules.findLoadout(active.job.loadoutId);
            state.add(label("Queued: " + (target == null ? active.job.loadoutId : target.displayName()),
                    11, Font.BOLD, WARNING));
            JButton cancel = button("CANCEL REFIT");
            cancel.addActionListener(event -> {
                send(world, network, active.base.playerId, "CANCEL", active.base.id, active.job.id, "");
                close();
            });
            state.add(cancel);
        }
        header.add(state, BorderLayout.EAST);
        return header;
    }

    private JComponent loadoutCard(World world, PeerNetwork network, Unit unit,
                                   ShipLoadoutDefinition loadout) {
        FittingOption option = evaluate(world, unit, loadout);
        JPanel card = panel(new BorderLayout(12, 8), PANEL);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 185));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(option.current ? GOOD : BORDER, option.current ? 2 : 1),
                new EmptyBorder(10, 12, 10, 12)));

        String badge = option.current ? "  [INSTALLED]" : loadout.defaultForHull() ? "  [DEFAULT PRESET]" : "";
        JPanel details = panel(new GridLayout(0, 1, 0, 4), PANEL);
        details.add(label(loadout.displayName() + badge, 15, Font.BOLD, option.current ? GOOD : TEXT));
        details.add(label("Weapons: " + weaponSummary(loadout), 11, Font.PLAIN, MUTED));
        details.add(label("Range: " + whole(WeaponRules.maxRange(loadout))
                + "  |  Refit: " + whole(loadout.refitTimeSeconds()) + "s", 11, Font.PLAIN, MUTED));
        List<Cost> cost = WeaponRules.refitCost(loadout);
        details.add(label("Cost: " + (cost.isEmpty() ? "None" : Rules.formatCost(cost)), 11, Font.PLAIN, MUTED));
        details.add(label(option.reason, 11, Font.BOLD,
                option.ready || option.current ? GOOD : BAD));
        card.add(details, BorderLayout.CENTER);

        JButton action = button(option.current ? "INSTALLED" : option.ready ? "QUEUE REFIT" : "UNAVAILABLE");
        action.setEnabled(option.ready);
        action.setPreferredSize(new Dimension(145, 42));
        action.addActionListener(event -> {
            send(world, network, unit.playerId, "REFIT", option.base.id, unit.key(), loadout.id());
            close();
        });
        card.add(action, BorderLayout.EAST);
        return card;
    }

    private JComponent stationStatus(World world, Unit unit) {
        Base inRange = refitBaseInRange(world, unit);
        if (inRange != null) {
            return statusPanel("Connected to " + inRange.type().name + " " + inRange.id
                    + "  |  distance " + whole(Calc.distance(inRange.x, inRange.y, unit.x, unit.y))
                    + " / " + whole(inRange.type().refitRange) + "  |  Esc closes fitting.", GOOD);
        }
        Base nearest = nearestRefitBase(world, unit);
        if (nearest == null) return statusPanel("No owned refit-capable shipyard exists in this system.", BAD);
        return statusPanel("Move within " + whole(nearest.type().refitRange) + " of "
                + nearest.type().name + " " + nearest.id + ". Current distance: "
                + whole(Calc.distance(nearest.x, nearest.y, unit.x, unit.y)) + ".", WARNING);
    }

    private void send(World world, PeerNetwork network, String playerId,
                      String action, String baseId, String value, String extra) {
        if (network == null) ProductionCommands.apply(world, playerId, action, baseId, value, extra);
        else network.production(playerId, action, baseId, value, extra);
    }

    static FittingOption evaluate(World world, Unit unit, ShipLoadoutDefinition loadout) {
        if (world == null || unit == null || loadout == null || !unit.shipTypeId.equals(loadout.hullId())) {
            return new FittingOption(null, false, false, "Preset does not match this hull.");
        }
        Base base = refitBaseInRange(world, unit);
        if (loadout.id().equals(unit.loadoutId)) {
            return new FittingOption(base, true, false, "Currently installed.");
        }
        ActiveRefit active = activeRefit(world, unit);
        if (active != null) return new FittingOption(active.base, false, false, "A refit is already queued.");
        if (base == null) return new FittingOption(nearestRefitBase(world, unit), false, false,
                nearestRefitBase(world, unit) == null ? "Requires an owned shipyard."
                        : "Move ship into shipyard refit range.");
        boolean free = world.devFreeBuildFor(unit.playerId);
        if (!free && !WeaponRules.unlocked(world, unit.playerId, loadout)) {
            return new FittingOption(base, false, false, "Research required: "
                    + WeaponRules.missingResearchLabel(world, unit.playerId, loadout) + ".");
        }
        if (!readyState(unit)) return new FittingOption(base, false, false,
                "Ship must be idle, stationary, and out of combat.");
        if (!free && !HangarStore.canAfford(base.inventory, WeaponRules.refitCost(loadout))) {
            return new FittingOption(base, false, false, "Shipyard lacks required materials.");
        }
        return new FittingOption(base, false, true, "Ready to refit at " + base.type().name + ".");
    }

    static Base nearestRefitBase(World world, Unit unit) {
        if (world == null || unit == null) return null;
        Base best = null;
        double distance = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (base.hp <= 0 || !unit.playerId.equals(base.playerId) || !base.type().canRefitShips) continue;
            double candidate = Calc.distance(base.x, base.y, unit.x, unit.y);
            if (candidate < distance) { best = base; distance = candidate; }
        }
        return best;
    }

    static Base refitBaseInRange(World world, Unit unit) {
        if (world == null || unit == null) return null;
        Base best = null;
        double distance = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (!base.canRefit(unit)) continue;
            double candidate = Calc.distance(base.x, base.y, unit.x, unit.y);
            if (candidate < distance) { best = base; distance = candidate; }
        }
        return best;
    }

    static ActiveRefit activeRefit(World world, Unit unit) {
        if (world == null || unit == null) return null;
        for (Base base : world.bases.values()) for (ProductionJob job : base.productionQueue) {
            if (job.kind == ProductionJobKind.REFIT && unit.key().equals(job.subjectUnitKey)) {
                return new ActiveRefit(base, job);
            }
        }
        return null;
    }

    static boolean readyState(Unit unit) {
        return unit != null && unit.task == UnitTask.IDLE && unit.attackTarget.isBlank()
                && Calc.distance(unit.x, unit.y, unit.targetX, unit.targetY) <= 2
                && unit.weaponFlashTimer <= 0 && unit.weaponCooldown <= 0 && unit.shieldDelayTimer <= 0;
    }

    private static String weaponSummary(ShipLoadoutDefinition loadout) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, WeaponType> weapons = new LinkedHashMap<>();
        for (WeaponType weapon : WeaponRules.loadout(loadout)) {
            counts.merge(weapon.id, 1, Integer::sum);
            weapons.put(weapon.id, weapon);
        }
        if (counts.isEmpty()) return "Unarmed";
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            WeaponType weapon = weapons.get(entry.getKey());
            labels.add((entry.getValue() > 1 ? entry.getValue() + "× " : "") + weapon.name
                    + " [" + whole(weapon.range) + " range, " + whole(weapon.damage) + " dmg]");
        }
        return String.join("  •  ", labels);
    }

    private static JPanel statusPanel(String text, Color color) {
        JPanel panel = panel(new BorderLayout(), PANEL);
        panel.setBorder(new EmptyBorder(7, 9, 7, 9));
        panel.add(label(text, 11, Font.BOLD, color));
        return panel;
    }

    private static JPanel panel(LayoutManager layout, Color color) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(color);
        return panel;
    }

    private static JLabel label(String text, int size, int style, Color color) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(style, (float) size));
        return label;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setMargin(new Insets(7, 12, 7, 12));
        return button;
    }

    private static String whole(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < 0.001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    record FittingOption(Base base, boolean current, boolean ready, String reason) { }
    record ActiveRefit(Base base, ProductionJob job) { }
}
