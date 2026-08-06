package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Configured non-weapon ship fittings and their authoritative simulation behavior. */
final class ShipModuleRules {
    static final Map<String, ShipModuleDefinition> MODULES = new LinkedHashMap<>();
    private static final Map<String, Integer> HULL_SLOTS = new LinkedHashMap<>();
    private static final Map<String, List<String>> LOADOUT_MODULES = new LinkedHashMap<>();
    private static final double MICRO_JUMP_CHARGE_SECONDS = 1.6;
    private static final double MICRO_JUMP_TUNNEL_SECONDS = 1.15;
    private static final Map<Unit, JumpVisual> JUMP_VISUALS =
            Collections.synchronizedMap(new WeakHashMap<>());

    static {
        loadExternal();
    }

    private ShipModuleRules() { }

    static ShipModuleDefinition find(String id) {
        return id == null ? null : MODULES.get(id);
    }

    static Map<String, Integer> configuredHullSlots() {
        return Map.copyOf(HULL_SLOTS);
    }

    static void clearLoadouts() { LOADOUT_MODULES.clear(); }

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
        if (loadout == null) return List.of();
        if (!loadout.moduleIds().isEmpty()) return loadout.moduleIds();
        return LOADOUT_MODULES.getOrDefault(loadout.id(), List.of());
    }

    static List<String> moduleIds(World world, Unit unit) {
        if (unit == null) return List.of();
        return moduleIds(WeaponRules.resolveForHull(world, unit.shipTypeId, unit.loadoutId));
    }

    static List<String> moduleIds(Unit unit) {
        return moduleIds(PlayerRegistry.activeWorld(), unit);
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
        if (Rules.findShip(hullId) == null || moduleSlotCount(hullId) <= 0) return List.of();
        return MODULES.values().stream()
                .filter(module -> module.compatibleHulls().contains(hullId))
                .sorted(java.util.Comparator.comparing(ShipModuleDefinition::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    static int moduleSlotCount(String hullId) {
        if (Rules.findShip(hullId) == null) return 0;
        return HULL_SLOTS.getOrDefault(hullId, 0);
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
            ShipModuleDefinition module = MODULES.get(moduleId);
            if (module == null) return Validation.reject("Unknown ship module: " + moduleId + ".");
            if (!module.compatibleHulls().contains(hullId)) {
                return Validation.reject("Ship module " + moduleId + " is not compatible with this hull.");
            }
            if (!module.allowDuplicates() && !unique.add(moduleId)) {
                return Validation.reject("A ship cannot fit the same utility module twice.");
            }
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

    static boolean has(World world, Unit unit, ShipModuleKind kind) {
        if (unit == null || kind == null) return false;
        for (ShipModuleDefinition module : modules(moduleIds(world, unit))) if (module.kind() == kind) return true;
        return false;
    }

    static boolean has(Unit unit, ShipModuleKind kind) {
        return has(PlayerRegistry.activeWorld(), unit, kind);
    }

    static boolean tackled(World world, Unit target) {
        if (world == null || target == null || target.hp <= 0) return false;
        String targetKey = CombatTarget.unit(target);
        for (Unit tackler : world.units.values()) {
            if (tackler == null || tackler.hp <= 0 || tackler == target
                    || !targetKey.equals(tackler.attackTarget)
                    || !CombatTarget.enemy(world, tackler, targetKey)) continue;
            ShipModuleDefinition module = first(world, tackler, ShipModuleKind.TACKLE);
            if (module != null && Calc.distance(tackler.x, tackler.y, target.x, target.y) <= module.range()) return true;
        }
        return false;
    }

    static double preferredApproachRange(World world, Unit unit, double weaponRange) {
        ShipModuleDefinition tackle = first(world, unit, ShipModuleKind.TACKLE);
        if (tackle == null) return weaponRange;
        return Math.min(weaponRange, Math.max(80, tackle.range() * 0.82));
    }

    static double preferredApproachRange(Unit unit, double weaponRange) {
        return preferredApproachRange(PlayerRegistry.activeWorld(), unit, weaponRange);
    }

    static void update(World world, Unit unit, double dt) {
        if (world == null || unit == null || !Double.isFinite(dt) || dt <= 0) return;
        updateJumpVisual(unit, dt);
        if (unit.microJumpCooldown > 0) unit.microJumpCooldown = Math.max(0, unit.microJumpCooldown - dt);
        unit.microJumpFlashTimer = Math.max(0, unit.microJumpFlashTimer - dt);
        unit.afterburnerActive = false;

        Objective objective = objective(world, unit);
        if (!objective.valid()) {
            cancelCharge(unit);
            return;
        }
        double dx = objective.x() - unit.x;
        double dy = objective.y() - unit.y;
        double distance = Math.hypot(dx, dy);
        if (!Double.isFinite(distance) || distance <= 2) {
            cancelCharge(unit);
            return;
        }

        boolean scrambled = tackled(world, unit);
        ShipModuleDefinition jump = first(world, unit, ShipModuleKind.MICRO_JUMP_DRIVE);
        if (scrambled || jump == null || distance < jump.activationDistance()) {
            cancelCharge(unit);
        } else if (unit.microJumpCooldown <= 0) {
            if (unit.microJumpCooldown == 0) {
                unit.microJumpCooldown = -MICRO_JUMP_CHARGE_SECONDS;
                playAuthoritativeCue(world, unit, SoundCue.MICRO_JUMP_CHARGE);
            } else {
                unit.microJumpCooldown = Math.min(0, unit.microJumpCooldown + dt);
                if (unit.microJumpCooldown >= -0.0001) {
                    double amount = jumpAmount(jump, distance);
                    if (amount > 1) {
                        double startX = unit.x;
                        double startY = unit.y;
                        unit.heading = Math.atan2(dy, dx);
                        unit.x = Calc.clamp(unit.x + dx / distance * amount, 0, world.width);
                        unit.y = Calc.clamp(unit.y + dy / distance * amount, 0, world.height);
                        unit.microJumpCooldown = jump.cooldownSeconds();
                        unit.microJumpFlashTimer = 0.72;
                        showJumpTrail(unit, startX, startY, unit.x, unit.y);
                        playAuthoritativeCue(world, unit, SoundCue.MICRO_JUMP);
                        dx = objective.x() - unit.x;
                        dy = objective.y() - unit.y;
                        distance = Math.hypot(dx, dy);
                    } else {
                        unit.microJumpCooldown = 0;
                    }
                }
            }
        }

        ShipModuleDefinition afterburner = first(world, unit, ShipModuleKind.AFTERBURNER);
        unit.afterburnerActive = !scrambled && afterburner != null
                && unit.microJumpCooldown >= 0
                && distance >= afterburner.activationDistance();
    }

    private static void cancelCharge(Unit unit) {
        if (unit != null && unit.microJumpCooldown < 0) unit.microJumpCooldown = 0;
    }

    private static double jumpAmount(ShipModuleDefinition jump, double distance) {
        if (jump == null || !Double.isFinite(distance) || distance <= 0) return 0;
        double configured = jump.jumpDistance();
        if (configured > 0 && configured <= 1) {
            return distance * Calc.clamp(configured, 0.05, 0.99);
        }
        return Math.min(configured, Math.max(0, distance - 180));
    }

    static double microJumpChargeProgress(Unit unit) {
        if (unit == null || unit.microJumpCooldown >= 0) return 0;
        return Calc.clamp(1.0 + unit.microJumpCooldown / MICRO_JUMP_CHARGE_SECONDS, 0, 1);
    }

    static void showJumpTrail(Unit unit, double startX, double startY, double endX, double endY) {
        if (unit == null || !GameplayCommandNumbers.finite(startX, startY, endX, endY)) return;
        if (Calc.distance(startX, startY, endX, endY) <= 1) return;
        JUMP_VISUALS.put(unit, new JumpVisual(startX, startY, endX, endY, MICRO_JUMP_TUNNEL_SECONDS));
    }

    static boolean jumpVisualActiveForTest(Unit unit) {
        JumpVisual visual = unit == null ? null : JUMP_VISUALS.get(unit);
        return visual != null && visual.remaining > 0;
    }

    private static void updateJumpVisual(Unit unit, double dt) {
        if (unit == null) return;
        synchronized (JUMP_VISUALS) {
            JumpVisual visual = JUMP_VISUALS.get(unit);
            if (visual == null) return;
            visual.remaining = Math.max(0, visual.remaining - dt);
            if (visual.remaining <= 0) JUMP_VISUALS.remove(unit);
        }
    }

    private static void playAuthoritativeCue(World world, Unit unit, SoundCue cue) {
        if (world == null || unit == null || cue == null) return;
        if (SystemAudio.nonRendered(world) || "SOLO".equals(unit.playerId)) {
            SystemAudio.play(world, cue);
        }
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
            drawJumpTunnel(g2, unit);
            if (unit.afterburnerActive) {
                double length = 34 * unit.type().size.scale;
                double bx = unit.x - Math.cos(unit.heading) * length;
                double by = unit.y - Math.sin(unit.heading) * length;
                g2.setStroke(new BasicStroke((float)Math.max(2, 3 * unit.type().size.scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(new Color(80, 205, 255, 190));
                g2.drawLine((int)Math.round(unit.x), (int)Math.round(unit.y), (int)Math.round(bx), (int)Math.round(by));
            }
            drawJumpCharge(g2, world, unit);
            if (unit.microJumpFlashTimer > 0) {
                int alpha = (int)Calc.clamp(70 + unit.microJumpFlashTimer * 260, 0, 235);
                double radius = (1.0 - Math.min(1.0, unit.microJumpFlashTimer / 0.72)) * 118 + 28;
                g2.setStroke(new BasicStroke(3f));
                g2.setColor(new Color(115, 225, 255, alpha));
                g2.drawOval((int)Math.round(unit.x - radius), (int)Math.round(unit.y - radius),
                        (int)Math.round(radius * 2), (int)Math.round(radius * 2));
            }
            ShipModuleDefinition tackle = first(world, unit, ShipModuleKind.TACKLE);
            Unit target = targetUnit(world, unit.attackTarget);
            if (tackle != null && target != null
                    && Calc.distance(unit.x, unit.y, target.x, target.y) <= tackle.range()) {
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(moduleColor(tackle, 175));
                g2.drawLine((int)Math.round(unit.x), (int)Math.round(unit.y),
                        (int)Math.round(target.x), (int)Math.round(target.y));
            }
        }
        g2.setStroke(oldStroke);
    }

    private static void drawJumpCharge(Graphics2D g2, World world, Unit unit) {
        double progress = microJumpChargeProgress(unit);
        if (progress <= 0) return;
        ShipModuleDefinition jump = first(world, unit, ShipModuleKind.MICRO_JUMP_DRIVE);
        Color color = jump == null ? new Color(0x9BEAFF) : jump.color();
        double pulse = 0.5 + 0.5 * Math.sin((world.systemTime() + progress * 2.5) * Math.PI * 5.0);
        double radius = 28 + progress * 58;
        int alpha = (int)Calc.clamp(80 + progress * 150, 0, 235);
        for (int i = 0; i < 3; i++) {
            double ring = radius - i * 12 * (0.4 + progress);
            if (ring <= 4) continue;
            g2.setStroke(new BasicStroke((float)(1.5 + progress * 2.2 - i * 0.25)));
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(20, alpha - i * 48)));
            g2.drawOval((int)Math.round(unit.x - ring), (int)Math.round(unit.y - ring),
                    (int)Math.round(ring * 2), (int)Math.round(ring * 2));
        }
        double aimX = unit.targetX;
        double aimY = unit.targetY;
        double distance = Calc.distance(unit.x, unit.y, aimX, aimY);
        if (distance > 1) {
            double nx = (aimX - unit.x) / distance;
            double ny = (aimY - unit.y) / distance;
            double beam = Math.min(distance, 180 + progress * 260);
            g2.setStroke(new BasicStroke((float)(2 + progress * 3), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int)(55 + pulse * 90)));
            g2.drawLine((int)Math.round(unit.x), (int)Math.round(unit.y),
                    (int)Math.round(unit.x + nx * beam), (int)Math.round(unit.y + ny * beam));
            for (int i = 1; i <= 5; i++) {
                double t = (i / 6.0 + progress * 0.8) % 1.0;
                double px = unit.x + nx * beam * t;
                double py = unit.y + ny * beam * t;
                double r = 2 + progress * 4;
                g2.fillOval((int)Math.round(px - r), (int)Math.round(py - r),
                        (int)Math.round(r * 2), (int)Math.round(r * 2));
            }
        }
    }

    private static void drawJumpTunnel(Graphics2D g2, Unit unit) {
        JumpVisual visual = JUMP_VISUALS.get(unit);
        if (visual == null || visual.remaining <= 0) return;
        double fade = Calc.clamp(visual.remaining / MICRO_JUMP_TUNNEL_SECONDS, 0, 1);
        double dx = visual.endX - visual.startX;
        double dy = visual.endY - visual.startY;
        double distance = Math.hypot(dx, dy);
        if (distance <= 1) return;
        double nx = dx / distance;
        double ny = dy / distance;
        double px = -ny;
        double py = nx;
        Color color = new Color(155, 234, 255);

        g2.setStroke(new BasicStroke((float)(22 * fade + 3), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int)(22 + fade * 35)));
        g2.drawLine((int)Math.round(visual.startX), (int)Math.round(visual.startY),
                (int)Math.round(visual.endX), (int)Math.round(visual.endY));
        g2.setStroke(new BasicStroke((float)(7 * fade + 2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int)(75 + fade * 95)));
        g2.drawLine((int)Math.round(visual.startX), (int)Math.round(visual.startY),
                (int)Math.round(visual.endX), (int)Math.round(visual.endY));
        g2.setStroke(new BasicStroke(2f));
        for (int i = 0; i <= 12; i++) {
            double t = i / 12.0;
            double centerX = visual.startX + dx * t;
            double centerY = visual.startY + dy * t;
            double half = (8 + Math.sin(Math.PI * t) * 34) * fade;
            int alpha = (int)(35 + fade * 120 * (0.45 + 0.55 * Math.sin(Math.PI * t)));
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(230, alpha))));
            g2.drawLine((int)Math.round(centerX - px * half), (int)Math.round(centerY - py * half),
                    (int)Math.round(centerX + px * half), (int)Math.round(centerY + py * half));
        }
        double endRadius = 30 + fade * 72;
        g2.setStroke(new BasicStroke((float)(2 + fade * 3)));
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int)(70 + fade * 150)));
        g2.drawOval((int)Math.round(visual.startX - endRadius), (int)Math.round(visual.startY - endRadius),
                (int)Math.round(endRadius * 2), (int)Math.round(endRadius * 2));
        g2.drawOval((int)Math.round(visual.endX - endRadius), (int)Math.round(visual.endY - endRadius),
                (int)Math.round(endRadius * 2), (int)Math.round(endRadius * 2));
    }

    static String summary(List<String> moduleIds) {
        List<String> names = new ArrayList<>();
        for (ShipModuleDefinition module : modules(moduleIds)) names.add(module.displayName());
        return names.isEmpty() ? "None" : String.join(", ", names);
    }

    static String effectSummary(ShipModuleDefinition module) {
        if (module == null) return "Empty utility socket";
        return switch (module.kind()) {
            case AFTERBURNER -> "Speed ×" + decimal(module.speedMultiplier())
                    + " / agility ×" + decimal(module.agilityMultiplier())
                    + " / auto at " + whole(module.activationDistance()) + "u";
            case MICRO_JUMP_DRIVE -> (module.jumpDistance() <= 1
                    ? "Jump " + whole(module.jumpDistance() * 100) + "% of route"
                    : "Jump " + whole(module.jumpDistance()) + "u")
                    + " / charge " + decimal(MICRO_JUMP_CHARGE_SECONDS) + "s"
                    + " / auto at " + whole(module.activationDistance()) + "u"
                    + " / cooldown " + decimal(module.cooldownSeconds()) + "s";
            case TACKLE -> "Suppress propulsion and jumps inside " + whole(module.range()) + "u";
        };
    }

    private static Color moduleColor(ShipModuleDefinition module, int alpha) {
        Color color = module == null ? new Color(0x72D8FF) : module.color();
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static ShipModuleDefinition first(World world, Unit unit, ShipModuleKind kind) {
        if (unit == null) return null;
        for (ShipModuleDefinition module : modules(moduleIds(world, unit))) if (module.kind() == kind) return module;
        return null;
    }

    private static ShipModuleDefinition first(Unit unit, ShipModuleKind kind) {
        return first(PlayerRegistry.activeWorld(), unit, kind);
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
        return CombatTarget.unit(world, targetKey);
    }

    private static void loadExternal() {
        Path path = configuredModulePath();
        if (!Files.isRegularFile(path)) {
            throw new RuleConfigurationException("Missing configured ship module file: " + path);
        }
        try {
            Object parsed = MiniJson.parse(Files.readString(path));
            Map<String,Object> root = ServerSaveStore.object(parsed);
            Map<String,Object> source = ServerSaveStore.object(root.get("shipModules"));
            if (source.isEmpty()) throw new RuleConfigurationException("No shipModules configured in " + path);

            int defaultSlots = strictInteger(root.get("defaultUtilitySlots"), "defaultUtilitySlots");
            if (defaultSlots < 0 || defaultSlots > 8) {
                throw new RuleConfigurationException("defaultUtilitySlots must be between 0 and 8.");
            }
            Map<String,Object> configuredSlots = ServerSaveStore.object(root.get("hullUtilitySlots"));
            Map<String,Integer> slots = new LinkedHashMap<>();
            for (String hullId : Rules.SHIPS.keySet()) slots.put(hullId, defaultSlots);
            for (Map.Entry<String,Object> entry : configuredSlots.entrySet()) {
                if (Rules.findShip(entry.getKey()) == null) {
                    throw new RuleConfigurationException("Unknown hull in hullUtilitySlots: " + entry.getKey());
                }
                int count = strictInteger(entry.getValue(), "utility slots for " + entry.getKey());
                if (count < 0 || count > 8) {
                    throw new RuleConfigurationException("Utility slot count for " + entry.getKey() + " must be between 0 and 8.");
                }
                slots.put(entry.getKey(), count);
            }

            Map<String,ShipModuleDefinition> modules = new LinkedHashMap<>();
            for (Map.Entry<String,Object> entry : source.entrySet()) {
                String id = entry.getKey();
                Map<String,Object> row = ServerSaveStore.object(entry.getValue());
                String displayName = requiredString(row, "displayName", id);
                String description = requiredString(row, "description", id);
                ShipModuleKind kind;
                ShipModuleVisualStyle visualStyle;
                try { kind = ShipModuleKind.valueOf(requiredString(row, "kind", id).toUpperCase(Locale.ROOT)); }
                catch (RuntimeException ex) { throw new RuleConfigurationException("Unknown ship module kind for " + id); }
                try { visualStyle = ShipModuleVisualStyle.valueOf(requiredString(row, "visualStyle", id).toUpperCase(Locale.ROOT)); }
                catch (RuntimeException ex) { throw new RuleConfigurationException("Unknown module visualStyle for " + id); }
                int seed = strictInteger(row.get("seed"), "seed for " + id);
                if (seed == 0) throw new RuleConfigurationException("Module seed must be non-zero for " + id);
                Color color = color(requiredString(row, "color", id));

                ShipModuleDefinition definition = new ShipModuleDefinition(
                        id,
                        displayName,
                        description,
                        kind,
                        ServerSaveStore.doubleValue(row, "activationDistance", 0),
                        ServerSaveStore.doubleValue(row, "range", 0),
                        ServerSaveStore.doubleValue(row, "speedMultiplier", 1),
                        ServerSaveStore.doubleValue(row, "agilityMultiplier", 1),
                        ServerSaveStore.doubleValue(row, "jumpDistance", 0),
                        ServerSaveStore.doubleValue(row, "cooldownSeconds", 0),
                        declaredSet(row, "compatibleHulls", id, false),
                        declaredSet(row, "requiresResearch", id, true),
                        costsRequired(row, "installationCost", id),
                        requiredBoolean(row, "allowDuplicates", id),
                        seed,
                        visualStyle,
                        color);
                validateDefinition(definition);
                if (modules.putIfAbsent(definition.id(), definition) != null) {
                    throw new RuleConfigurationException("Duplicate ship module ID: " + definition.id());
                }
            }

            MODULES.clear();
            MODULES.putAll(modules);
            HULL_SLOTS.clear();
            HULL_SLOTS.putAll(slots);
        } catch (RuleConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuleConfigurationException("Could not load ship module config " + path + ": " + ex.getMessage());
        }
    }

    private static void validateDefinition(ShipModuleDefinition module) {
        if (module.compatibleHulls().isEmpty()) {
            throw new RuleConfigurationException("Ship module " + module.id() + " has no compatible hulls.");
        }
        for (String hullId : module.compatibleHulls()) if (Rules.findShip(hullId) == null) {
            throw new RuleConfigurationException("Unknown compatible hull " + hullId + " for module " + module.id());
        }
        for (String topicId : module.requiredResearch()) if (ResearchRules.topic(topicId) == null) {
            throw new RuleConfigurationException("Unknown research ID " + topicId + " for module " + module.id());
        }
        if (module.installationCost().isEmpty()) {
            throw new RuleConfigurationException("Ship module " + module.id() + " must declare a positive installation cost.");
        }
        if (module.kind() == ShipModuleKind.AFTERBURNER) {
            if (module.activationDistance() <= 0 || module.speedMultiplier() <= 1 || module.agilityMultiplier() >= 1) {
                throw new RuleConfigurationException("Afterburner module " + module.id() + " has invalid propulsion values.");
            }
        } else if (module.kind() == ShipModuleKind.MICRO_JUMP_DRIVE) {
            if (module.activationDistance() <= 0 || module.jumpDistance() <= 0 || module.cooldownSeconds() <= 0) {
                throw new RuleConfigurationException("Micro jump module " + module.id() + " has invalid jump values.");
            }
        } else if (module.kind() == ShipModuleKind.TACKLE && module.range() <= 0) {
            throw new RuleConfigurationException("Tackle module " + module.id() + " must configure a positive range.");
        }
    }

    private static Path configuredModulePath() {
        Path manifest = Path.of("config/starchem.json");
        if (!Files.isRegularFile(manifest)) return Path.of("config/modules.json");
        try {
            Map<String,Object> root = ServerSaveStore.object(MiniJson.parse(Files.readString(manifest)));
            Map<String,Object> files = ServerSaveStore.object(root.get("files"));
            return Path.of(ServerSaveStore.string(files, "modules", "config/modules.json"));
        } catch (Exception ex) {
            throw new RuleConfigurationException("Could not resolve modules file from " + manifest + ": " + ex.getMessage());
        }
    }

    private static String requiredString(Map<String,Object> row, String key, String moduleId) {
        String value = ServerSaveStore.string(row, key, "").trim();
        if (value.isBlank()) throw new RuleConfigurationException("Missing " + key + " for ship module " + moduleId);
        return value;
    }

    private static Set<String> declaredSet(Map<String,Object> row, String key, String moduleId,
                                           boolean allowEmpty) {
        if (!row.containsKey(key)) {
            throw new RuleConfigurationException("Missing " + key + " for ship module " + moduleId + ".");
        }
        Set<String> result = new LinkedHashSet<>(strings(row.get(key)));
        if (!allowEmpty && result.isEmpty()) {
            throw new RuleConfigurationException("Ship module " + moduleId + " must declare " + key + ".");
        }
        return Set.copyOf(result);
    }

    private static List<Cost> costsRequired(Map<String,Object> row, String key, String moduleId) {
        if (!row.containsKey(key)) {
            throw new RuleConfigurationException("Missing " + key + " for ship module " + moduleId + ".");
        }
        List<Cost> result = costs(row.get(key));
        if (result.isEmpty()) {
            throw new RuleConfigurationException("Ship module " + moduleId + " must declare a positive " + key + ".");
        }
        return result;
    }

    private static boolean requiredBoolean(Map<String,Object> row, String key, String moduleId) {
        Object value = row.get(key);
        if (!(value instanceof Boolean flag)) {
            throw new RuleConfigurationException("Missing or invalid " + key + " for ship module " + moduleId + ".");
        }
        return flag;
    }

    private static int strictInteger(Object value, String label) {
        if (!(value instanceof Number number)) throw new RuleConfigurationException("Missing or invalid " + label + ".");
        double raw = number.doubleValue();
        int result = number.intValue();
        if (!Double.isFinite(raw) || Math.abs(raw - result) > 0.000001) {
            throw new RuleConfigurationException("Expected an integer for " + label + ".");
        }
        return result;
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

    private static Color color(String value) {
        try { return Color.decode(value); }
        catch (RuntimeException ex) { throw new RuleConfigurationException("Invalid ship module color: " + value); }
    }

    private static String whole(double value) {
        if (!Double.isFinite(value)) return "0";
        return Long.toString(Math.round(value));
    }

    private static String decimal(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < 0.001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    record Validation(boolean valid, String reason) {
        static Validation accept() { return new Validation(true, ""); }
        static Validation reject(String reason) { return new Validation(false, reason == null ? "Invalid module fit." : reason); }
    }

    private record Objective(double x, double y, boolean valid) { }

    private static final class JumpVisual {
        final double startX;
        final double startY;
        final double endX;
        final double endY;
        double remaining;

        JumpVisual(double startX, double startY, double endX, double endY, double remaining) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.remaining = remaining;
        }
    }
}

enum ShipModuleKind { AFTERBURNER, MICRO_JUMP_DRIVE, TACKLE }

enum ShipModuleVisualStyle { THRUSTER, JUMP_CORE, DISRUPTOR }

record ShipModuleDefinition(String id, String displayName, String description, ShipModuleKind kind,
                            double activationDistance, double range, double speedMultiplier,
                            double agilityMultiplier, double jumpDistance, double cooldownSeconds,
                            Set<String> compatibleHulls, Set<String> requiredResearch,
                            List<Cost> installationCost, boolean allowDuplicates,
                            int seed, ShipModuleVisualStyle visualStyle, Color color) {
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
        compatibleHulls = compatibleHulls == null ? Set.of() : Set.copyOf(compatibleHulls);
        requiredResearch = requiredResearch == null ? Set.of() : Set.copyOf(requiredResearch);
        installationCost = installationCost == null ? List.of() : List.copyOf(installationCost);
        if (seed == 0) throw new IllegalArgumentException("Module seed must be non-zero.");
        visualStyle = visualStyle == null ? ShipModuleVisualStyle.JUMP_CORE : visualStyle;
        color = color == null ? new Color(0x72D8FF) : color;
    }
}
