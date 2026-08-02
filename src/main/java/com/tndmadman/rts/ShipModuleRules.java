package com.tndmadman.rts;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Configured non-weapon ship fittings and their authoritative simulation behavior. */
final class ShipModuleRules {
    static final Map<String, ShipModuleDefinition> MODULES = new LinkedHashMap<>();
    private static final Map<String, List<String>> LOADOUT_MODULES = new LinkedHashMap<>();

    static {
        if (!loadExternal()) loadDefaults();
    }

    private ShipModuleRules() { }

    static void registerLoadout(String loadoutId, List<String> moduleIds) {
        if (loadoutId == null || loadoutId.isBlank()) return;
        List<String> clean = normalized(moduleIds);
        for (String moduleId : clean) {
            if (!MODULES.containsKey(moduleId)) {
                throw new RuleConfigurationException("Unknown ship module ID " + moduleId + " for loadout " + loadoutId);
            }
        }
        List<String> previous = LOADOUT_MODULES.putIfAbsent(loadoutId, clean);
        if (previous != null && !previous.equals(clean)) {
            throw new IllegalArgumentException("Loadout " + loadoutId + " conflicts with a different module layout.");
        }
    }

    static List<String> moduleIds(ShipLoadoutDefinition loadout) {
        return loadout == null ? List.of() : LOADOUT_MODULES.getOrDefault(loadout.id(), List.of());
    }

    static List<String> moduleIds(Unit unit) {
        if (unit == null) return List.of();
        return moduleIds(WeaponRules.resolveForHull(unit.shipTypeId, unit.loadoutId));
    }

    static List<ShipModuleDefinition> modules(ShipLoadoutDefinition loadout) {
        return modules(moduleIds(loadout));
    }

    static List<ShipModuleDefinition> modules(List<String> ids) {
        List<ShipModuleDefinition> out = new ArrayList<>();
        for (String id : normalized(ids)) {
            ShipModuleDefinition module = MODULES.get(id);
            if (module != null) out.add(module);
        }
        return List.copyOf(out);
    }

    static List<ShipModuleDefinition> allowedModules(String hullId) {
        if (Rules.findShip(hullId) == null) return List.of();
        return MODULES.values().stream()
                .sorted(java.util.Comparator.comparing(ShipModuleDefinition::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    static int moduleSlotCount(String hullId) {
        if (Rules.findShip(hullId) == null) return 0;
        int weaponSlots = 0;
        for (ShipLoadoutDefinition fit : WeaponRules.loadoutsForHull(hullId)) {
            weaponSlots = Math.max(weaponSlots, fit.weaponIds().size());
        }
        if (weaponSlots <= 1) return 1;
        if (weaponSlots <= 4) return 2;
        return 3;
    }

    static Validation validate(String hullId, List<String> moduleIds) {
        if (Rules.findShip(hullId) == null) return Validation.reject("Unknown ship hull.");
        List<String> clean = normalized(moduleIds);
        int slots = moduleSlotCount(hullId);
        if (clean.size() > slots) {
            return Validation.reject("Fit uses " + clean.size() + " utility modules but the hull has " + slots + " utility slots.");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String moduleId : clean) {
            if (!MODULES.containsKey(moduleId)) return Validation.reject("Unknown ship module: " + moduleId + ".");
            if (!unique.add(moduleId)) return Validation.reject("A ship cannot fit the same utility module twice.");
        }
        return Validation.accept();
    }

    static Set<String> requiredResearch(List<String> moduleIds) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (ShipModuleDefinition module : modules(moduleIds)) out.addAll(module.requiredResearch());
        return Set.copyOf(out);
    }

    static List<Cost> installationCost(List<String> moduleIds) {
        EnumMap<Material, Double> totals = new EnumMap<>(Material.class);
        for (ShipModuleDefinition module : modules(moduleIds)) {
            for (Cost cost : module.installationCost()) totals.merge(cost.material(), cost.amount(), Double::sum);
        }
        List<Cost> out = new ArrayList<>();
        for (Map.Entry<Material, Double> entry : totals.entrySet()) {
            if (entry.getValue() > 0) out.add(new Cost(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(out);
    }

    static boolean has(Unit unit, ShipModuleKind kind) {
        if (unit == null || kind == null) return false;
        for (ShipModuleDefinition module : modules(moduleIds(unit))) if (module.kind() == kind) return true;
        return false;
    }

    static boolean tackled(World world, Unit target) {
        if (world == null || target == null || target.hp <= 0) return false;
        String targetKey = CombatTarget.unit(target);
        for (Unit tackler : world.units.values()) {
            if (tackler == null || tackler.hp <= 0 || tackler == target
                    || !targetKey.equals(tackler.attackTarget)
                    || !CombatTarget.enemy(world, tackler, targetKey)) continue;
            ShipModuleDefinition module = first(tackler, ShipModuleKind.TACKLE);
            if (module != null && Calc.distance(tackler.x, tackler.y, target.x, target.y) <= module.range()) return true;
        }
        return false;
    }

    static double preferredApproachRange(Unit unit, double weaponRange) {
        ShipModuleDefinition tackle = first(unit, ShipModuleKind.TACKLE);
        if (tackle == null) return weaponRange;
        return Math.min(weaponRange, Math.max(80, tackle.range() * 0.82));
    }

    static void update(World world, Unit unit, double dt) {
        if (world == null || unit == null || !Double.isFinite(dt) || dt <= 0) return;
        if (unit.microJumpCooldown > 0) unit.microJumpCooldown = Math.max(0, unit.microJumpCooldown - dt);
        unit.microJumpFlashTimer = Math.max(0, unit.microJumpFlashTimer - dt);
        unit.afterburnerActive = false;

        Objective objective = objective(world, unit);
        if (!objective.valid()) {
            if (unit.microJumpCooldown < 0) unit.microJumpCooldown = 0;
            return;
        }
        double dx = objective.x() - unit.x;
        double dy = objective.y() - unit.y;
        double distance = Math.hypot(dx, dy);
        if (!Double.isFinite(distance) || distance <= 2) {
            if (unit.microJumpCooldown < 0) unit.microJumpCooldown = 0;
            return;
        }

        boolean scrambled = tackled(world, unit);
        ShipModuleDefinition jump = first(unit, ShipModuleKind.MICRO_JUMP_DRIVE);
        if (scrambled || jump == null || distance < jump.activationDistance()) {
            if (unit.microJumpCooldown < 0) unit.microJumpCooldown = 0;
        } else if (unit.microJumpCooldown <= 0) {
            if (unit.microJumpCooldown == 0) {
                // Negative cooldown is an internal one-tick spool marker. Tackle or a lost objective cancels it.
                unit.microJumpCooldown = -1;
            } else {
                double amount = Math.min(jump.jumpDistance(), Math.max(0, distance - 180));
                if (amount > 1) {
                    unit.heading = Math.atan2(dy, dx);
                    unit.x = Calc.clamp(unit.x + dx / distance * amount, 0, world.width);
                    unit.y = Calc.clamp(unit.y + dy / distance * amount, 0, world.height);
                    unit.microJumpCooldown = jump.cooldownSeconds();
                    unit.microJumpFlashTimer = 0.55;
                    dx = objective.x() - unit.x;
                    dy = objective.y() - unit.y;
                    distance = Math.hypot(dx, dy);
                } else {
                    unit.microJumpCooldown = 0;
                }
            }
        }

        ShipModuleDefinition afterburner = first(unit, ShipModuleKind.AFTERBURNER);
        unit.afterburnerActive = !scrambled && afterburner != null
                && distance >= afterburner.activationDistance();
    }

    static double speedMultiplier(Unit unit) {
        if (unit == null || !unit.afterburnerActive) return 1.0;
        ShipModuleDefinition module = first(unit, ShipModuleKind.AFTERBURNER);
        return module == null ? 1.0 : Math.max(1.0, module.speedMultiplier());
    }

    static double agilityMultiplier(Unit unit) {
        if (unit == null || !unit.afterburnerActive) return 1.0;
        ShipModuleDefinition module = first(unit, ShipModuleKind.AFTERBURNER);
        return module == null ? 1.0 : Calc.clamp(module.agilityMultiplier(), 0.05, 1.0);
    }

    static void draw(Graphics2D g2, World world) {
        if (g2 == null || world == null) return;
        Stroke oldStroke = g2.getStroke();
        for (Unit unit : world.units.values()) {
            if (unit.afterburnerActive) {
                double length = 34 * unit.type().size.scale;
                double bx = unit.x - Math.cos(unit.heading) * length;
                double by = unit.y - Math.sin(unit.heading) * length;
                g2.setStroke(new BasicStroke((float)Math.max(2, 3 * unit.type().size.scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(new Color(80, 205, 255, 190));
                g2.drawLine((int)Math.round(unit.x), (int)Math.round(unit.y), (int)Math.round(bx), (int)Math.round(by));
            }
            if (unit.microJumpFlashTimer > 0) {
                int alpha = (int)Calc.clamp(70 + unit.microJumpFlashTimer * 300, 0, 230);
                double radius = (1.0 - Math.min(1.0, unit.microJumpFlashTimer / 0.55)) * 90 + 26;
                g2.setStroke(new BasicStroke(3f));
                g2.setColor(new Color(115, 225, 255, alpha));
                g2.drawOval((int)Math.round(unit.x - radius), (int)Math.round(unit.y - radius),
                        (int)Math.round(radius * 2), (int)Math.round(radius * 2));
            }
            ShipModuleDefinition tackle = first(unit, ShipModuleKind.TACKLE);
            Unit target = targetUnit(world, unit.attackTarget);
            if (tackle != null && target != null
                    && Calc.distance(unit.x, unit.y, target.x, target.y) <= tackle.range()) {
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(new Color(255, 126, 72, 175));
                g2.drawLine((int)Math.round(unit.x), (int)Math.round(unit.y),
                        (int)Math.round(target.x), (int)Math.round(target.y));
            }
        }
        g2.setStroke(oldStroke);
    }

    static String summary(List<String> moduleIds) {
        List<String> names = new ArrayList<>();
        for (ShipModuleDefinition module : modules(moduleIds)) names.add(module.displayName());
        return names.isEmpty() ? "None" : String.join(", ", names);
    }

    private static ShipModuleDefinition first(Unit unit, ShipModuleKind kind) {
        if (unit == null) return null;
        for (ShipModuleDefinition module : modules(moduleIds(unit))) if (module.kind() == kind) return module;
        return null;
    }

    private static Objective objective(World world, Unit unit) {
        if (unit.task == UnitTask.ATTACK && !unit.attackTarget.isBlank()
                && CombatTarget.alive(world, unit.attackTarget)) {
            return new Objective(CombatTarget.x(world, unit.attackTarget), CombatTarget.y(world, unit.attackTarget), true);
        }
        boolean moving = unit.task == UnitTask.MOVE || unit.task == UnitTask.RETURN_TO_STATION
                || unit.task == UnitTask.AUTO_HARVEST || ProductionSystem.refitReserved(world, unit.key());
        return moving ? new Objective(unit.targetX, unit.targetY, true) : new Objective(0, 0, false);
    }

    private static Unit targetUnit(World world, String targetKey) {
        if (world == null || targetKey == null || targetKey.isBlank()) return null;
        return world.units.get(targetKey.startsWith("U:") ? targetKey.substring(2) : targetKey);
    }

    private static boolean loadExternal() {
        try {
            Path path = Path.of("config/modules.json");
            if (!Files.isRegularFile(path)) return false;
            Object parsed = MiniJson.parse(Files.readString(path));
            Map<String,Object> root = ServerSaveStore.object(parsed);
            Map<String,Object> source = ServerSaveStore.object(root.getOrDefault("shipModules", root));
            MODULES.clear();
            for (Map.Entry<String,Object> entry : source.entrySet()) {
                Map<String,Object> row = ServerSaveStore.object(entry.getValue());
                ShipModuleKind kind;
                try { kind = ShipModuleKind.valueOf(ServerSaveStore.string(row, "kind", "").toUpperCase(Locale.ROOT)); }
                catch (RuntimeException ex) { throw new RuleConfigurationException("Unknown ship module kind for " + entry.getKey()); }
                ShipModuleDefinition definition = new ShipModuleDefinition(
                        entry.getKey(),
                        ServerSaveStore.string(row, "displayName", title(entry.getKey())),
                        ServerSaveStore.string(row, "description", ""),
                        kind,
                        ServerSaveStore.doubleValue(row, "activationDistance", 0),
                        ServerSaveStore.doubleValue(row, "range", 0),
                        ServerSaveStore.doubleValue(row, "speedMultiplier", 1),
                        ServerSaveStore.doubleValue(row, "agilityMultiplier", 1),
                        ServerSaveStore.doubleValue(row, "jumpDistance", 0),
                        ServerSaveStore.doubleValue(row, "cooldownSeconds", 0),
                        new LinkedHashSet<>(strings(row.get("requiresResearch"))),
                        costs(row.get("installationCost")),
                        color(ServerSaveStore.string(row, "color", "#72D8FF")));
                if (MODULES.putIfAbsent(definition.id(), definition) != null) {
                    throw new RuleConfigurationException("Duplicate ship module ID: " + definition.id());
                }
            }
            return !MODULES.isEmpty();
        } catch (RuleConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            System.err.println("Could not load ship module config: " + ex.getMessage());
            return false;
        }
    }

    private static void loadDefaults() {
        MODULES.clear();
        MODULES.put("afterburner", new ShipModuleDefinition("afterburner", "Afterburner",
                "Automatically burns on long approaches: much higher speed, sharply reduced turning agility.",
                ShipModuleKind.AFTERBURNER, 420, 0, 1.85, 0.22, 0, 0,
                Set.of("combat_doctrine"), List.of(new Cost(Material.TARGETING_COMPUTER, 1)), new Color(0x62D8FF)));
        MODULES.put("micro_jump_drive", new ShipModuleDefinition("micro_jump_drive", "Micro Jump Drive",
                "Automatically jumps toward distant objectives; disabled by hostile tackle.",
                ShipModuleKind.MICRO_JUMP_DRIVE, 1250, 0, 1, 1, 700, 14,
                Set.of("battlefleet_engineering"), List.of(new Cost(Material.TARGETING_COMPUTER, 2),
                new Cost(Material.LANCE_FOCUSING_ARRAY, 1)), new Color(0x9BEAFF)));
        MODULES.put("warp_scrambler", new ShipModuleDefinition("warp_scrambler", "Jump Scrambler",
                "Tackles the targeted enemy ship in close range, shutting down afterburners, micro jumps, and wormhole escape.",
                ShipModuleKind.TACKLE, 0, 360, 1, 1, 0, 0,
                Set.of("combat_doctrine"), List.of(new Cost(Material.TARGETING_COMPUTER, 1),
                new Cost(Material.POINT_DEFENSE_LASER_ASSEMBLY, 1)), new Color(0xFF7E48)));
    }

    private static List<String> normalized(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) for (String value : values) {
            String clean = value == null ? "" : value.trim();
            if (!clean.isBlank()) out.add(clean);
        }
        return List.copyOf(out);
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        for (Object item : ServerSaveStore.list(value)) {
            String text = String.valueOf(item).trim();
            if (!text.isBlank()) out.add(text);
        }
        return List.copyOf(out);
    }

    private static List<Cost> costs(Object value) {
        List<Cost> out = new ArrayList<>();
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(value).entrySet()) {
            Material material;
            try { material = Material.valueOf(entry.getKey().toUpperCase(Locale.ROOT)); }
            catch (RuntimeException ex) { throw new RuleConfigurationException("Unknown module material: " + entry.getKey()); }
            double amount = entry.getValue() instanceof Number number ? number.doubleValue() : -1;
            if (!Double.isFinite(amount) || amount < 0) throw new RuleConfigurationException("Invalid module cost for " + material.name());
            if (amount > 0) out.add(new Cost(material, amount));
        }
        return List.copyOf(out);
    }

    private static String title(String value) {
        StringBuilder out = new StringBuilder();
        for (String word : value.split("[_-]")) {
            if (word.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? "Module" : out.toString();
    }

    private static Color color(String value) {
        try { return Color.decode(value); }
        catch (RuntimeException ex) { return new Color(0x72D8FF); }
    }

    record Validation(boolean valid, String reason) {
        static Validation accept() { return new Validation(true, ""); }
        static Validation reject(String reason) { return new Validation(false, reason == null ? "Invalid module fit." : reason); }
    }

    private record Objective(double x, double y, boolean valid) { }
}

enum ShipModuleKind { AFTERBURNER, MICRO_JUMP_DRIVE, TACKLE }

record ShipModuleDefinition(String id, String displayName, String description, ShipModuleKind kind,
                            double activationDistance, double range, double speedMultiplier,
                            double agilityMultiplier, double jumpDistance, double cooldownSeconds,
                            Set<String> requiredResearch, List<Cost> installationCost, Color color) {
    ShipModuleDefinition {
        id = id == null ? "" : id.trim();
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        description = description == null ? "" : description.trim();
        activationDistance = Math.max(0, activationDistance);
        range = Math.max(0, range);
        speedMultiplier = Math.max(1, speedMultiplier);
        agilityMultiplier = Calc.clamp(agilityMultiplier, 0.05, 1.0);
        jumpDistance = Math.max(0, jumpDistance);
        cooldownSeconds = Math.max(0, cooldownSeconds);
        requiredResearch = requiredResearch == null ? Set.of() : Set.copyOf(requiredResearch);
        installationCost = installationCost == null ? List.of() : List.copyOf(installationCost);
        color = color == null ? new Color(0x72D8FF) : color;
    }
}
