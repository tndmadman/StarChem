from pathlib import Path
import re

ROOT = Path('.')


def path(name: str) -> Path:
    return ROOT / name


def replace_once(name: str, old: str, new: str) -> None:
    p = path(name)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{name}: expected one match, found {count}: {old[:90]!r}')
    p.write_text(text.replace(old, new, 1))


def replace_all(name: str, old: str, new: str, minimum: int = 1) -> None:
    p = path(name)
    text = p.read_text()
    count = text.count(old)
    if count < minimum:
        raise SystemExit(f'{name}: expected at least {minimum} matches, found {count}: {old!r}')
    p.write_text(text.replace(old, new))


def regex_once(name: str, pattern: str, replacement: str, flags: int = re.S) -> None:
    p = path(name)
    text = p.read_text()
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f'{name}: regex expected one match, found {count}: {pattern[:100]!r}')
    p.write_text(updated)


# Ship module registry lifecycle and target resolution.
replace_once(
    'src/main/java/com/tndmadman/rts/ShipModuleRules.java',
    '    private ShipModuleRules() { }\n\n    static void registerLoadout',
    '    private ShipModuleRules() { }\n\n    static void clearLoadouts() { LOADOUT_MODULES.clear(); }\n\n    static void registerLoadout')
replace_once(
    'src/main/java/com/tndmadman/rts/ShipModuleRules.java',
    '        return world.units.get(targetKey.startsWith("U:") ? targetKey.substring(2) : targetKey);',
    '        return CombatTarget.unit(world, targetKey);')

# Static authored loadouts can define utility modules and clear the runtime module index with rules reloads.
replace_once(
    'src/main/java/com/tndmadman/rts/WeaponRules.java',
    '        DEFAULT_BY_HULL.clear();\n    }',
    '        DEFAULT_BY_HULL.clear();\n        ShipModuleRules.clearLoadouts();\n    }')
regex_once(
    'src/main/java/com/tndmadman/rts/WeaponRules.java',
    r'    private static void parseLoadouts\(Map<String,Object> doc\) \{.*?\n    \}\n\n    private static void register\(ShipLoadoutDefinition definition\)',
    '''    private static void parseLoadouts(Map<String,Object> doc) {
        Map<String,Object> source = object(doc.getOrDefault("shipLoadouts", doc));
        for (Map.Entry<String,Object> entry : source.entrySet()) {
            String id = entry.getKey();
            Object raw = entry.getValue();
            if (raw instanceof List<?>) {
                register(new ShipLoadoutDefinition(id, title(id), id, stringList(raw), Set.of(),
                        List.of(), List.of(), 12, true));
                ShipModuleRules.registerLoadout(id, List.of());
                continue;
            }
            Map<String,Object> row = object(raw);
            if (row.isEmpty()) continue;
            String hullId = string(row, "hullId", id);
            register(new ShipLoadoutDefinition(
                    id,
                    string(row, "displayName", title(id)),
                    hullId,
                    stringList(row.get("weapons")),
                    new LinkedHashSet<>(stringList(row.get("requiresResearch"))),
                    costs(row.get("buildCost")),
                    costs(row.get("refitCost")),
                    number(row, "refitTimeSeconds", 12),
                    bool(row, "default", id.equals(hullId))));
            ShipModuleRules.registerLoadout(id, stringList(row.get("modules")));
        }
    }

    private static void register(ShipLoadoutDefinition definition)''')
replace_once(
    'src/main/java/com/tndmadman/rts/WeaponRules.java',
    '                SHIP_LOADOUTS.put(hullId, empty);\n                BY_HULL.put(hullId, new ArrayList<>(List.of(empty)));',
    '                SHIP_LOADOUTS.put(hullId, empty);\n                ShipModuleRules.registerLoadout(hullId, List.of());\n                BY_HULL.put(hullId, new ArrayList<>(List.of(empty)));')
replace_once(
    'src/main/java/com/tndmadman/rts/WeaponRules.java',
    '        register(new ShipLoadoutDefinition(hullId, title(hullId), hullId, weapons, Set.of(),\n                List.of(), List.of(), 12, true));',
    '        register(new ShipLoadoutDefinition(hullId, title(hullId), hullId, weapons, Set.of(),\n                List.of(), List.of(), 12, true));\n        ShipModuleRules.registerLoadout(hullId, List.of());')

# Unit propulsion state and afterburner agility model.
replace_once(
    'src/main/java/com/tndmadman/rts/Unit.java',
    '    double weaponCooldown, weaponFlashTimer, wormholeCooldown;\n',
    '    double weaponCooldown, weaponFlashTimer, wormholeCooldown;\n    double microJumpCooldown, microJumpFlashTimer;\n')
replace_once(
    'src/main/java/com/tndmadman/rts/Unit.java',
    '    boolean selected, unloadingThisFrame, miningAnchorSet;\n',
    '    boolean selected, unloadingThisFrame, miningAnchorSet, afterburnerActive;\n')
replace_once(
    'src/main/java/com/tndmadman/rts/Unit.java',
    '        if (!ProductionSystem.refitLocked(world, key())) return false;',
    '        if (!ProductionSystem.refitReserved(world, key())) return false;')
replace_once(
    'src/main/java/com/tndmadman/rts/Unit.java',
    '''        if (dist > 2) {
            heading = Math.atan2(dy, dx);
            double step = Math.min(dist, type().speed * dt);
            x += dx / dist * step;
            y += dy / dist * step;
        }
        x = GameplayCommandNumbers.repairedCoordinate(x, targetX, width);''',
    '''        if (dist > 2) {
            double desiredHeading = Math.atan2(dy, dx);
            if (afterburnerActive) {
                double maxTurn = 1.35 * ShipModuleRules.agilityMultiplier(this) * dt;
                heading += Calc.clamp(angleDelta(heading, desiredHeading), -maxTurn, maxTurn);
            } else {
                heading = desiredHeading;
            }
            double step = Math.min(dist, type().speed * ShipModuleRules.speedMultiplier(this) * dt);
            x += Math.cos(heading) * step;
            y += Math.sin(heading) * step;
        }
        x = GameplayCommandNumbers.repairedCoordinate(x, targetX, width);''')
replace_once(
    'src/main/java/com/tndmadman/rts/Unit.java',
    '    }\n}\n',
    '''    }

    private static double angleDelta(double from, double to) {
        double delta = to - from;
        while (delta > Math.PI) delta -= Math.PI * 2;
        while (delta < -Math.PI) delta += Math.PI * 2;
        return delta;
    }
}
''')

# Client movement predicts module activation as part of the same fitting behavior.
replace_once(
    'src/main/java/com/tndmadman/rts/ClientPrediction.java',
    '            if (PlayerRegistry.isLocal(unit.playerId)) predictTarget(world, unit, dt);\n            unit.updatePosition',
    '            if (PlayerRegistry.isLocal(unit.playerId)) predictTarget(world, unit, dt);\n            ShipModuleRules.update(world, unit, dt);\n            unit.updatePosition')

# Server movement: recalled ships travel under AI control; service-locked ships remain docked.
regex_once(
    'src/main/java/com/tndmadman/rts/World.java',
    r'    private void updateUnit\(Unit unit, double dt\) \{.*?\n    \}\n    private void sendFullHarvestCargoToUnload',
    '''    private void updateUnit(Unit unit, double dt) {
        unit.unloadingThisFrame = false;
        unit.wormholeCooldown = Math.max(0, unit.wormholeCooldown - dt);
        if (ProductionSystem.refitLocked(this, unit.key())) {
            unit.task = UnitTask.IDLE;
            unit.attackTarget = "";
            unit.automationResourceId = -1;
            unit.clearOrder();
            unit.targetX = unit.x;
            unit.targetY = unit.y;
            unit.afterburnerActive = false;
            return;
        }
        if (ProductionSystem.refitReserved(this, unit.key())) {
            ShipModuleRules.update(this, unit, dt);
            unit.updatePosition(dt * SystemModifierRules.movementSpeed(this), width, height);
            return;
        }
        boolean recoveryOwned = NpcRecoverySystem.ownsUnit(this, unit)
                || NpcRepairEvacuationSystem.ownsUnit(this, unit);
        if (!recoveryOwned) {
            sendFullHarvestCargoToUnload(unit);
            autoUnload(unit, dt);
            haulerSystem.update(this, unit, dt);
            workSystem.update(this, unit, dt);
        }
        UnitOrderSystem.update(this, unit, dt);
        if (!recoveryOwned && unit.task == UnitTask.RETURN_TO_STATION) updateReturn(unit);
        if (!recoveryOwned && unit.task == UnitTask.IDLE && unit.orderType == UnitOrderType.NONE) idleNearBase(unit, dt);
        if (unit.task == UnitTask.MOVE && Calc.distance(unit.x, unit.y, unit.targetX, unit.targetY) < 5) unit.task = UnitTask.IDLE;
        ShipModuleRules.update(this, unit, dt);
        unit.updatePosition(dt * SystemModifierRules.movementSpeed(this), width, height);
    }
    private void sendFullHarvestCargoToUnload''')
replace_once(
    'src/main/java/com/tndmadman/rts/World.java',
    '        StationFuelRules.consume(this, dt);\n        logisticsSystem.update(this, dt);',
    '        StationFuelRules.consume(this, dt);\n        ProductionSystem.update(this, dt);\n        logisticsSystem.update(this, dt);')
replace_once(
    'src/main/java/com/tndmadman/rts/World.java',
    '        weaponSystem.draw(g2, this); for (ExplosionEffect explosion : explosions)',
    '        weaponSystem.draw(g2, this); ShipModuleRules.draw(g2, this); for (ExplosionEffect explosion : explosions)')
replace_once(
    'src/main/java/com/tndmadman/rts/World.java',
    '            if (unit.wormholeCooldown > 0 || ProductionSystem.refitLocked(this, unit.key())) continue;',
    '            if (unit.wormholeCooldown > 0 || ProductionSystem.refitReserved(this, unit.key())\n                    || ShipModuleRules.tackled(this, unit)) continue;')
replace_once(
    'src/main/java/com/tndmadman/rts/World.java',
    'boolean playerShipTouchingWormhole(String playerId) { if (playerId == null || playerId.isBlank()) return false; for (Unit unit : units.values()) if (playerId.equals(unit.playerId) && unit.wormholeCooldown <= 0 && !ProductionSystem.refitLocked(this, unit.key()) && wormholeAt(unit.x, unit.y) != null) return true; return false; }',
    'boolean playerShipTouchingWormhole(String playerId) { if (playerId == null || playerId.isBlank()) return false; for (Unit unit : units.values()) if (playerId.equals(unit.playerId) && unit.wormholeCooldown <= 0 && !ProductionSystem.refitReserved(this, unit.key()) && !ShipModuleRules.tackled(this, unit) && wormholeAt(unit.x, unit.y) != null) return true; return false; }')
replace_all(
    'src/main/java/com/tndmadman/rts/World.java',
    'ProductionSystem.refitLocked(this, unit.key())',
    'ProductionSystem.refitReserved(this, unit.key())',
    minimum=3)
# Restore the service-lock check in updateUnit after the broader command-helper replacement.
replace_once(
    'src/main/java/com/tndmadman/rts/World.java',
    '        if (ProductionSystem.refitReserved(this, unit.key())) {\n            unit.task = UnitTask.IDLE;',
    '        if (ProductionSystem.refitLocked(this, unit.key())) {\n            unit.task = UnitTask.IDLE;')

# Authoritative command handlers reject player orders for recalled ships.
for file_name in [
    'src/main/java/com/tndmadman/rts/AUnitMove.java',
    'src/main/java/com/tndmadman/rts/AUnitAttack.java',
    'src/main/java/com/tndmadman/rts/AUnitWork.java',
    'src/main/java/com/tndmadman/rts/UnitOrders.java',
]:
    replace_all(file_name, 'ProductionSystem.refitLocked', 'ProductionSystem.refitReserved')
replace_once(
    'src/main/java/com/tndmadman/rts/AUnitMove.java',
    'if (unit == null || !playerId.equals(unit.playerId) || unit.wormholeCooldown > 0 || gate == null',
    'if (unit == null || !playerId.equals(unit.playerId) || unit.wormholeCooldown > 0\n                        || ProductionSystem.refitReserved(world, unit.key()) || ShipModuleRules.tackled(world, unit) || gate == null')

# Tackle keeps targets from wormhole transfer.
replace_once(
    'src/main/java/com/tndmadman/rts/GalaxyCoordinator.java',
    '            if (unit.wormholeCooldown > 0 || ProductionSystem.refitLocked(world, unit.key())) continue;',
    '            if (unit.wormholeCooldown > 0 || ProductionSystem.refitReserved(world, unit.key())\n                    || ShipModuleRules.tackled(world, unit)) continue;')

# Weapon AI respects recalled ships and closes to tackle range when equipped.
replace_all(
    'src/main/java/com/tndmadman/rts/WeaponSystem.java',
    'ProductionSystem.refitLocked',
    'ProductionSystem.refitReserved')
replace_once(
    'src/main/java/com/tndmadman/rts/WeaponSystem.java',
    '''        double approachRange = UnitOrderSystem.mayChase(unit) ? range * 0.92 : range;
        if (dist > approachRange) {''',
    '''        double fittedRange = ShipModuleRules.preferredApproachRange(unit, range);
        double approachRange = UnitOrderSystem.mayChase(unit) ? fittedRange * 0.92 : fittedRange;
        if (dist > approachRange) {''')
replace_once(
    'src/main/java/com/tndmadman/rts/WeaponSystem.java',
    '            world.moveTowardOrbit(unit, tx, ty, range * 0.82);',
    '            world.moveTowardOrbit(unit, tx, ty, fittedRange * 0.82);')

# Automatic shipyard recall and a separate travel reservation vs. service lock.
regex_once(
    'src/main/java/com/tndmadman/rts/ProductionSystem.java',
    r'    static boolean enqueueRefit\(World world, Base base, Unit unit, ShipLoadoutDefinition loadout, boolean free\) \{.*?\n    \}\n\n    static boolean enqueuePackage',
    '''    static boolean enqueueRefit(World world, Base base, Unit unit, ShipLoadoutDefinition loadout, boolean free) {
        if (world == null || base == null || unit == null || loadout == null) return false;
        if (base.hp <= 0 || !base.type().canRefitShips || !unit.playerId.equals(base.playerId)) {
            world.status = "An owned refit-capable shipyard is required.";
            return false;
        }
        if (unit.hp <= 0 || !unit.shipTypeId.equals(loadout.hullId())) {
            world.status = "That loadout is not valid for the selected ship.";
            return false;
        }
        if (loadout.id().equals(unit.loadoutId)) {
            world.status = unit.type().name + " already uses " + loadout.displayName() + ".";
            return false;
        }
        if (!free && !WeaponRules.unlocked(world, base.playerId, loadout)) {
            world.status = loadout.displayName() + " requires research: "
                    + WeaponRules.missingResearchLabel(world, base.playerId, loadout) + ".";
            return false;
        }
        if (refitReserved(world, unit.key())) {
            world.status = "That ship is already reserved for refitting.";
            return false;
        }
        List<Cost> cost = WeaponRules.refitCost(loadout);
        if (!free && !HangarStore.canAfford(base.inventory, cost)) {
            world.status = "Need " + Rules.formatCost(cost) + " in " + base.type().name + " hangar.";
            return false;
        }
        if (!free) HangarStore.spend(base.inventory, cost);
        ProductionJob job = newJob(base, ProductionJobKind.REFIT, unit.shipTypeId,
                loadout.refitTimeSeconds(), !free, "");
        job.loadoutId = loadout.id();
        job.subjectUnitKey = unit.key();
        job.blockedReason = "recalling ship to refit";
        base.productionQueue.add(job);
        recall(base, unit, job);
        world.status = "Recalling " + unit.type().name + " to " + base.type().name
                + " for refit: " + loadout.displayName() + ".";
        AlertCenter.push(world, world.status);
        processBase(world, base, 0);
        return true;
    }

    static boolean enqueuePackage''')
replace_once(
    'src/main/java/com/tndmadman/rts/ProductionSystem.java',
    '''        for (Base base : new ArrayList<>(world.bases.values())) {
            cleanupInvalidRefits(world, base);
            processBase(world, base, dt);
        }''',
    '''        for (Base base : new ArrayList<>(world.bases.values())) {
            cleanupInvalidRefits(world, base);
            recallQueuedRefits(world, base);
            processBase(world, base, dt);
        }''')
replace_once(
    'src/main/java/com/tndmadman/rts/ProductionSystem.java',
    '    private static void processBase(World world, Base base, double dt) {',
    '''    private static void recallQueuedRefits(World world, Base base) {
        if (base == null || !base.type().canRefitShips) return;
        for (ProductionJob job : base.productionQueue) {
            if (job.kind != ProductionJobKind.REFIT) continue;
            Unit unit = world.units.get(job.subjectUnitKey);
            if (unit == null || unit.hp <= 0 || base.canRefit(unit)) continue;
            recall(base, unit, job);
        }
    }

    private static void recall(Base base, Unit unit, ProductionJob job) {
        double angle = (unit.unitId * 1.61803398875) % (Math.PI * 2);
        double radius = Math.max(24, base.type().refitRange * 0.55);
        double dockX = base.x + Math.cos(angle) * radius;
        double dockY = base.y + Math.sin(angle) * radius;
        unit.attackTarget = "";
        unit.automationResourceId = -1;
        unit.clearOrder();
        unit.moveTo(dockX, dockY);
        unit.weaponFlashTimer = 0;
        job.blockedReason = "recalling ship to refit";
    }

    private static void processBase(World world, Base base, double dt) {''')
regex_once(
    'src/main/java/com/tndmadman/rts/ProductionSystem.java',
    r'    private static boolean prepareRefit\(World world, Base base, ProductionJob job\) \{.*?\n    \}\n\n    private static boolean completeRefit',
    '''    private static boolean prepareRefit(World world, Base base, ProductionJob job) {
        Unit unit = world.units.get(job.subjectUnitKey);
        if (unit == null || unit.hp <= 0) {
            if (job.resourcesReserved) refund(base, costFor(job));
            job.resourcesReserved = false;
            job.blockedReason = "refit target destroyed";
            world.status = "Refit cancelled because the target ship was destroyed; reserved resources were refunded.";
            return false;
        }
        ShipLoadoutDefinition loadout = WeaponRules.findLoadout(job.loadoutId);
        if (loadout == null || !unit.shipTypeId.equals(loadout.hullId())) {
            job.blockedReason = "invalid refit target";
            return false;
        }
        if (!base.canRefit(unit)) {
            recall(base, unit, job);
            return false;
        }
        unit.attackTarget = "";
        unit.automationResourceId = -1;
        unit.clearOrder();
        unit.task = UnitTask.IDLE;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        unit.afterburnerActive = false;
        job.blockedReason = "";
        return true;
    }

    private static boolean completeRefit''')
replace_once(
    'src/main/java/com/tndmadman/rts/ProductionSystem.java',
    '''        unit.weaponFlashTimer = 0;
        unit.weaponCooldown = WeaponRules.maxCooldown(unit);''',
    '''        unit.weaponFlashTimer = 0;
        unit.weaponCooldown = WeaponRules.maxCooldown(unit);
        unit.microJumpCooldown = 0;
        unit.microJumpFlashTimer = 0;
        unit.afterburnerActive = false;''')
regex_once(
    'src/main/java/com/tndmadman/rts/ProductionSystem.java',
    r'    static boolean refitLocked\(World world, String unitKey\) \{.*?\n    \}\n\n    private static boolean failUnknown',
    '''    static boolean refitReserved(World world, String unitKey) {
        if (world == null || unitKey == null || unitKey.isBlank()) return false;
        for (Base base : world.bases.values()) for (ProductionJob job : base.productionQueue) {
            if (job.kind == ProductionJobKind.REFIT && unitKey.equals(job.subjectUnitKey)) return true;
        }
        return false;
    }

    static boolean refitLocked(World world, String unitKey) {
        if (world == null || unitKey == null || unitKey.isBlank()) return false;
        Unit unit = world.units.get(unitKey);
        if (unit == null) return false;
        for (Base base : world.bases.values()) for (ProductionJob job : base.productionQueue) {
            if (job.kind == ProductionJobKind.REFIT && unitKey.equals(job.subjectUnitKey)
                    && base.canRefit(unit)) return true;
        }
        return false;
    }

    private static boolean failUnknown''')
replace_once(
    'src/main/java/com/tndmadman/rts/ProductionSystem.java',
    '''            boolean refunded = job.resourcesReserved;
            if (refunded) refund(base, costFor(job));
            world.status = "Cancelled " + displayName(job)''',
    '''            boolean refunded = job.resourcesReserved;
            if (refunded) refund(base, costFor(job));
            if (job.kind == ProductionJobKind.REFIT) {
                Unit unit = world.units.get(job.subjectUnitKey);
                if (unit != null) {
                    unit.task = UnitTask.IDLE;
                    unit.targetX = unit.x;
                    unit.targetY = unit.y;
                    unit.afterburnerActive = false;
                }
            }
            world.status = "Cancelled " + displayName(job)''')

# Class-wide refitting recalls every available same-hull ship in the current system.
regex_once(
    'src/main/java/com/tndmadman/rts/FitCommand.java',
    r'    private static Result refitClass\(World world, String actorId, Map<String,Object> payload\) \{.*?\n    \}\n\n    private static Result build',
    '''    private static Result refitClass(World world, String actorId, Map<String,Object> payload) {
        Base base = ownedBase(world, actorId, ServerSaveStore.string(payload, "baseId", ""));
        ShipLoadoutDefinition loadout = register(world, payload);
        List<Unit> eligible = new ArrayList<>();
        int already = 0, reserved = 0;
        for (Unit unit : world.units.values()) {
            if (!actorId.equals(unit.playerId) || unit.hp <= 0 || !loadout.hullId().equals(unit.shipTypeId)) continue;
            if (loadout.id().equals(unit.loadoutId)) { already++; continue; }
            if (ProductionSystem.refitReserved(world, unit.key())) { reserved++; continue; }
            eligible.add(unit);
        }
        if (eligible.isEmpty()) return Result.fail("No available " + Rules.ship(loadout.hullId()).name
                + " ships can be recalled. Already fitted: " + already + "; already reserved: " + reserved + ".");
        boolean free = world.devFreeBuildFor(actorId);
        if (!free && !WeaponRules.unlocked(world, actorId, loadout)) {
            return Result.fail(loadout.displayName() + " requires research: "
                    + WeaponRules.missingResearchLabel(world, actorId, loadout) + ".");
        }
        if (!free) {
            List<Cost> total = multiply(WeaponRules.refitCost(loadout), eligible.size());
            if (!HangarStore.canAfford(base.inventory, total)) {
                return Result.fail("Need " + Rules.formatCost(total) + " for " + eligible.size() + " class refits.");
            }
        }
        int queued = 0;
        for (Unit unit : eligible) if (ProductionSystem.enqueueRefit(world, base, unit, loadout, free)) queued++;
        if (queued != eligible.size()) return Result.fail("Only " + queued + " of " + eligible.size() + " refits could be queued.");
        world.status = "Recalling " + queued + " " + Rules.ship(loadout.hullId()).name + " ships to "
                + base.type().name + " for class refit: " + loadout.displayName()
                + ". Already fitted: " + already + "; already reserved: " + reserved + ".";
        AlertCenter.push(world, world.status);
        return Result.ok(world.status, true, true);
    }

    private static Result build''')

# Rules fingerprint includes the new module config.
replace_once(
    'config/starchem.json',
    '"rulesVersion": 20',
    '"rulesVersion": 21')
replace_once(
    'config/starchem.json',
    '"weapons": "config/weapons.json",',
    '"weapons": "config/weapons.json",\n    "modules": "config/modules.json",')

# Codex exposes utility modules beside weapons.
replace_once(
    'src/main/java/com/tndmadman/rts/CodexCatalog.java',
    '                String detail = loadout.displayName() + (loadout.defaultForHull() ? " [default]" : "")\n                        + ": " + (weapons.isEmpty() ? "unarmed" : String.join(", ", weapons));',
    '                String detail = loadout.displayName() + (loadout.defaultForHull() ? " [default]" : "")\n                        + ": " + (weapons.isEmpty() ? "unarmed" : String.join(", ", weapons))\n                        + " | utility " + ShipModuleRules.summary(ShipModuleRules.moduleIds(loadout));')

# Validator coverage: fit serialization, automatic module behavior, and remote recall.
replace_once(
    'src/main/java/com/tndmadman/rts/ShipLoadoutValidator.java',
    '        validateDefinitions();\n        validateConstructionAndCombatResolution();',
    '        validateDefinitions();\n        validateUtilityModules();\n        validateConstructionAndCombatResolution();')
replace_once(
    'src/main/java/com/tndmadman/rts/ShipLoadoutValidator.java',
    '    private static void validateConstructionAndCombatResolution() {',
    '''    private static void validateUtilityModules() {
        ShipFitSpec spec = new ShipFitSpec("destroyer", List.of("light_railgun"),
                List.of("afterburner", "micro_jump_drive"));
        require(ShipFitSpec.from(spec.toMap()).equals(spec), "utility modules did not survive fit serialization");
        ShipLoadoutDefinition fit = PlayerFitRules.register("Mobility Test", spec);
        require(ShipModuleRules.moduleIds(fit).equals(spec.moduleIds()),
                "runtime fit lost its utility module layout");

        World world = new World("Module Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        Unit mover = new Unit("MODULE", 1, "destroyer", 100, 100);
        mover.loadoutId = fit.id();
        mover.moveTo(2000, 100);
        world.units.put(mover.key(), mover);
        ShipModuleRules.update(world, mover, 0.1);
        require(mover.afterburnerActive, "afterburner did not activate for a distant objective");
        require(ShipModuleRules.speedMultiplier(mover) > 1.0,
                "afterburner did not increase fitted ship speed");
        require(ShipModuleRules.agilityMultiplier(mover) < 0.5,
                "afterburner did not impose a severe agility penalty");
        double beforeJump = mover.x;
        ShipModuleRules.update(world, mover, 0.1);
        require(mover.x > beforeJump + 400, "micro jump drive did not jump toward a distant objective");

        ShipFitSpec tackleSpec = new ShipFitSpec("destroyer", List.of("light_railgun"), List.of("warp_scrambler"));
        ShipLoadoutDefinition tackleFit = PlayerFitRules.register("Tackle Test", tackleSpec);
        Unit tackler = new Unit("TACKLER", 2, "destroyer", mover.x - 100, mover.y);
        tackler.loadoutId = tackleFit.id();
        tackler.attackTarget = CombatTarget.unit(mover);
        world.units.put(tackler.key(), tackler);
        require(ShipModuleRules.tackled(world, mover), "scrambler did not tackle its targeted enemy");
        mover.afterburnerActive = true;
        mover.microJumpCooldown = 0;
        double tackledX = mover.x;
        ShipModuleRules.update(world, mover, 0.1);
        require(!mover.afterburnerActive && close(mover.x, tackledX),
                "tackle did not suppress afterburner and micro jump activation");
    }

    private static void validateConstructionAndCombatResolution() {''')
replace_once(
    'src/main/java/com/tndmadman/rts/ShipLoadoutValidator.java',
    '''        ship.task = UnitTask.ATTACK;
        require(!ProductionSystem.enqueueRefit(fixture.world, fixture.yard, ship, rail, false),
                "combat-active ship refit was accepted");
        ship.task = UnitTask.IDLE;
        ship.x = fixture.yard.x + fixture.yard.type().refitRange + 5;
        ship.targetX = ship.x;
        require(!ProductionSystem.enqueueRefit(fixture.world, fixture.yard, ship, rail, false),
                "remote refit request was accepted");''',
    '''        ship.task = UnitTask.ATTACK;
        ship.attackTarget = "B:enemy";
        ship.x = fixture.yard.x + fixture.yard.type().refitRange + 500;
        ship.y = fixture.yard.y;
        ship.targetX = ship.x;
        ship.targetY = ship.y;
        double remoteDistance = Calc.distance(ship.x, ship.y, fixture.yard.x, fixture.yard.y);
        require(ProductionSystem.enqueueRefit(fixture.world, fixture.yard, ship, rail, false),
                "remote refit request was rejected instead of recalling the ship");
        require(ProductionSystem.refitReserved(fixture.world, ship.key())
                        && !ProductionSystem.refitLocked(fixture.world, ship.key()),
                "remote refit did not reserve a traveling ship separately from the service lock");
        require(Calc.distance(ship.targetX, ship.targetY, fixture.yard.x, fixture.yard.y) < remoteDistance,
                "remote refit did not issue a shipyard recall destination");''')

# Ensure no remaining player-command guards use the service-only lock.
for java in Path('src/main/java/com/tndmadman/rts').glob('*.java'):
    if java.name in {'ProductionSystem.java', 'World.java'}:
        continue
    text = java.read_text()
    if 'ProductionSystem.refitLocked' in text:
        print(f'NOTICE: service lock remains in {java}')

print('Expanded ship fitting patch applied.')
