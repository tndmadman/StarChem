package com.tndmadman.rts;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class NpcSystem {
    private final Map<String, NpcState> states = new LinkedHashMap<>();
    private final Set<String> disabledFactionIds;

    NpcSystem() { this(Set.of()); }

    NpcSystem(Set<String> disabledFactionIds) {
        this.disabledFactionIds = disabledFactionIds == null ? Set.of() : Set.copyOf(disabledFactionIds);
    }

    void update(World world, double dt) {
        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.enabled() || disabledFactionIds.contains(faction.id())) continue;
            PlayerRegistry.register(faction.id(), faction.name(), faction.rgb(), false);

            NpcState state = states.computeIfAbsent(faction.id(), ignored -> new NpcState(faction.firstSpawnSeconds()));
            if (!hasAssets(world, faction)) {
                if (!spawnRequirementsMet(world, faction)) {
                    state.spawnTimer = faction.firstSpawnSeconds();
                    continue;
                }
                state.spawnTimer -= dt;
                if (state.spawnTimer <= 0) {
                    spawnFaction(world, faction);
                    state.spawnTimer = faction.respawnSeconds();
                    state.orderTimer = 0;
                    state.buildTimer = faction.buildSeconds();
                    state.raidCooldownTimer = 0;
                }
                continue;
            }

            state.buildTimer -= dt;
            state.orderTimer -= dt;
            state.raidCooldownTimer = Math.max(0, state.raidCooldownTimer - dt);
            if (state.orderTimer <= 0) {
                orderFaction(world, faction, state);
                state.orderTimer = faction.orderSeconds();
            }
        }
    }

    private boolean hasAssets(World world, NpcFaction faction) {
        for (Unit unit : world.units.values()) if (unit.playerId.equals(faction.id()) && unit.hp > 0) return true;
        for (Base base : world.bases.values()) if (base.playerId.equals(faction.id()) && base.hp > 0) return true;
        return false;
    }

    private boolean spawnRequirementsMet(World world, NpcFaction faction) {
        if (!faction.requirePlayerCombatShips()) return true;
        int combatShips = 0;
        for (Unit unit : world.units.values()) {
            if (unit.hp <= 0 || NpcRules.isNpcFaction(unit.playerId)) continue;
            if (WeaponRules.armed(unit.type())) combatShips++;
        }
        return combatShips >= Math.max(1, faction.minPlayerCombatShips());
    }

    private void spawnFaction(World world, NpcFaction faction) {
        SpawnPoint point = spawnPoint(world, faction);
        String baseId = faction.id() + ":B" + nextBaseNumber(world, faction);
        world.bases.put(baseId, new Base(baseId, faction.id(), validBaseType(faction.baseType()), point.x, point.y));

        spawnUnits(world, faction, point, faction.startingUnits());
        world.status = faction.spawnMessage();
        orderFaction(world, faction, new NpcState(0));
    }

    private String validBaseType(String baseType) {
        if (Rules.BASES.containsKey(baseType)) return baseType;
        return Rules.DEFAULT_BASE;
    }

    private void spawnUnits(World world, NpcFaction faction, SpawnPoint point, List<String> requestedTypes) {
        List<String> units = validUnitList(faction, requestedTypes);
        if (units.isEmpty()) units = fallbackUnits(faction);
        int nextUnit = nextUnitNumber(world, faction);
        for (int i = 0; i < units.size(); i++) {
            double angle = i * Math.PI * 2.0 / Math.max(1, units.size());
            double range = faction.unitSpacing() + i * 34;
            Unit unit = new Unit(faction.id(), nextUnit++, units.get(i),
                    Calc.clamp(point.x + Math.cos(angle) * range, 0, world.width),
                    Calc.clamp(point.y + Math.sin(angle) * range, 0, world.height));
            world.units.put(unit.key(), unit);
        }
    }

    private List<String> validUnitList(NpcFaction faction, List<String> requestedTypes) {
        List<String> out = new ArrayList<>();
        for (String shipTypeId : requestedTypes) {
            if (!Rules.SHIPS.containsKey(shipTypeId)) continue;
            ShipType ship = Rules.ship(shipTypeId);
            if (faction.behavior() == NpcBehavior.RAIDER && !WeaponRules.armed(ship)) continue;
            if ((faction.behavior() == NpcBehavior.MINER || faction.behavior() == NpcBehavior.FACTION) && ship.harvestKinds.isEmpty() && !WeaponRules.armed(ship)) continue;
            out.add(shipTypeId);
        }
        return out;
    }

    private List<String> fallbackUnits(NpcFaction faction) {
        if (faction.behavior() == NpcBehavior.MINER || faction.behavior() == NpcBehavior.FACTION) {
            String worker = firstWorkerType(faction);
            return worker.isBlank() ? List.of() : List.of(worker);
        }
        String armed = firstArmedShip();
        return armed.isBlank() ? List.of() : List.of(armed);
    }

    private String firstArmedShip() {
        for (ShipType ship : Rules.SHIPS.values()) if (WeaponRules.armed(ship)) return ship.id;
        return "";
    }

    private String firstWorkerType(NpcFaction faction) {
        for (String id : faction.workerUnitTypes()) {
            if (!Rules.SHIPS.containsKey(id)) continue;
            ShipType ship = Rules.ship(id);
            if (!ship.harvestKinds.isEmpty()) return id;
        }
        for (ShipType ship : Rules.SHIPS.values()) {
            if (!ship.harvestKinds.isEmpty()) return ship.id;
        }
        return "";
    }

    private SpawnPoint spawnPoint(World world, NpcFaction faction) {
        Rectangle2D local = world.localBounds();
        double cx = world.width / 2.0;
        double cy = world.height / 2.0;
        double lx = local == null ? cx : local.getCenterX();
        double ly = local == null ? cy : local.getCenterY();
        double angle = Math.atan2(ly - cy, lx - cx) + Math.PI;
        if (Double.isNaN(angle)) angle = Math.PI * 0.25;
        double distance = Math.max(300.0, faction.spawnDistance());
        double pad = Math.max(0.0, faction.spawnPadding());
        double x = Calc.clamp(lx + Math.cos(angle) * distance, pad, world.width - pad);
        double y = Calc.clamp(ly + Math.sin(angle) * distance, pad, world.height - pad);
        return new SpawnPoint(x, y);
    }

    private void orderFaction(World world, NpcFaction faction, NpcState state) {
        switch (faction.behavior()) {
            case MINER -> orderMiners(world, faction);
            case FACTION -> orderFullFaction(world, faction, state);
            case RAIDER -> orderRaiders(world, faction);
        }
    }

    private void orderRaiders(World world, NpcFaction faction) {
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(faction.id()) || unit.hp <= 0) continue;
            String target = nearestEnemyTarget(world, faction, unit);
            if (!target.isBlank()) unit.attack(target);
        }
    }

    private void orderMiners(World world, NpcFaction faction) {
        if (faction.replaceWorkers()) maintainWorkers(world, faction);
        Set<String> workerTypes = faction.workerTypeSet();
        for (Unit unit : new ArrayList<>(world.units.values())) {
            if (!unit.playerId.equals(faction.id()) || unit.hp <= 0) continue;
            if (!unit.type().harvestKinds.isEmpty() && (workerTypes.isEmpty() || workerTypes.contains(unit.shipTypeId))) {
                assignMiningTarget(world, faction, unit);
                continue;
            }
            if (WeaponRules.armed(unit.type())) {
                String target = nearestEnemyTarget(world, faction, unit);
                if (!target.isBlank()) unit.attack(target);
            }
        }
    }

    private void orderFullFaction(World world, NpcFaction faction, NpcState state) {
        Base base = firstBase(world, faction);
        if (faction.replaceWorkers()) maintainWorkers(world, faction);
        orderFactionWorkers(world, faction);

        if (state.buildTimer <= 0) {
            if (buildFleetShip(world, faction)) state.buildTimer = faction.buildSeconds();
            else state.buildTimer = Math.max(2.0, faction.buildSeconds() * 0.5);
        }

        List<Unit> combat = readyCombatUnits(world, faction, base);
        String defenseTarget = nearestThreatToBase(world, faction);
        if (!defenseTarget.isBlank()) {
            for (Unit unit : combat) unit.attack(defenseTarget);
            return;
        }

        int raidSize = Math.max(1, faction.raidFleetSize());
        if (combat.size() >= raidSize) {
            if (state.raidCooldownTimer <= 0) {
                issueRaid(world, faction, combat);
                state.raidCooldownTimer = Math.max(0, faction.raidCooldownSeconds());
            } else if (base != null) {
                guardIdleCombat(world, combat, base);
            }
            return;
        }

        int harassSize = Math.max(1, faction.harassFleetSize());
        if (faction.harassWorkers() && combat.size() >= harassSize && state.raidCooldownTimer <= 0) {
            String target = nearestWorkerTarget(world, faction, base);
            if (!target.isBlank()) {
                for (int i = 0; i < Math.min(harassSize, combat.size()); i++) combat.get(i).attack(target);
                state.raidCooldownTimer = Math.max(4.0, faction.raidCooldownSeconds() * 0.35);
                return;
            }
        }

        if (base != null) guardIdleCombat(world, combat, base);
    }

    private void issueRaid(World world, NpcFaction faction, List<Unit> combat) {
        for (Unit unit : combat) {
            String target = priorityEnemyTarget(world, faction, unit);
            if (!target.isBlank()) unit.attack(target);
        }
    }

    private void orderFactionWorkers(World world, NpcFaction faction) {
        Set<String> workerTypes = faction.workerTypeSet();
        for (Unit unit : new ArrayList<>(world.units.values())) {
            if (!unit.playerId.equals(faction.id()) || unit.hp <= 0) continue;
            if (unit.type().harvestKinds.isEmpty()) continue;
            if (!workerTypes.isEmpty() && !workerTypes.contains(unit.shipTypeId)) continue;
            assignMiningTarget(world, faction, unit);
        }
    }

    private boolean buildFleetShip(World world, NpcFaction faction) {
        if (faction.targetFleetSize() <= 0 || combatUnits(world, faction).size() >= faction.targetFleetSize()) return false;
        Base base = firstBase(world, faction);
        if (base == null) return false;
        String shipTypeId = affordableFleetShip(base, faction);
        if (shipTypeId.isBlank()) return false;
        ShipType ship = Rules.ship(shipTypeId);
        HangarStore.spend(base.inventory, ship.buildCost);
        int n = nextUnitNumber(world, faction);
        double a = n * 1.35;
        Unit unit = new Unit(faction.id(), n, shipTypeId,
                Calc.clamp(base.x + Math.cos(a) * (base.type().buildRadius + 40), 0, world.width),
                Calc.clamp(base.y + Math.sin(a) * (base.type().buildRadius + 40), 0, world.height));
        world.units.put(unit.key(), unit);
        return true;
    }

    private String affordableFleetShip(Base base, NpcFaction faction) {
        String fallback = "";
        for (String shipTypeId : faction.fleetUnitTypes()) {
            if (!Rules.SHIPS.containsKey(shipTypeId)) continue;
            if (!base.type().buildableShips.contains(shipTypeId)) continue;
            ShipType ship = Rules.ship(shipTypeId);
            if (!WeaponRules.armed(ship)) continue;
            fallback = fallback.isBlank() ? shipTypeId : fallback;
            if (HangarStore.canAfford(base.inventory, ship.buildCost)) return shipTypeId;
        }
        return fallback.isBlank() || !HangarStore.canAfford(base.inventory, Rules.ship(fallback).buildCost) ? "" : fallback;
    }

    private void maintainWorkers(World world, NpcFaction faction) {
        if (faction.maxWorkers() <= 0) return;
        Base base = firstBase(world, faction);
        if (base == null) return;
        int workers = 0;
        Set<String> workerTypes = faction.workerTypeSet();
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(faction.id()) || unit.hp <= 0 || unit.type().harvestKinds.isEmpty()) continue;
            if (workerTypes.isEmpty() || workerTypes.contains(unit.shipTypeId)) workers++;
        }
        String workerType = firstWorkerType(faction);
        if (workerType.isBlank()) return;
        while (workers < faction.maxWorkers()) {
            int n = nextUnitNumber(world, faction);
            double a = n * 1.65;
            Unit worker = new Unit(faction.id(), n, workerType,
                    Calc.clamp(base.x + Math.cos(a) * (base.type().buildRadius + 55), 0, world.width),
                    Calc.clamp(base.y + Math.sin(a) * (base.type().buildRadius + 55), 0, world.height));
            world.units.put(worker.key(), worker);
            workers++;
        }
    }

    private List<Unit> combatUnits(World world, NpcFaction faction) {
        List<Unit> out = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(faction.id()) || unit.hp <= 0) continue;
            if (WeaponRules.armed(unit.type())) out.add(unit);
        }
        return out;
    }

    private List<Unit> readyCombatUnits(World world, NpcFaction faction, Base base) {
        List<Unit> out = new ArrayList<>();
        for (Unit unit : combatUnits(world, faction)) {
            if (shouldRetreat(unit, faction)) {
                if (base != null) guardBase(world, unit, base);
                continue;
            }
            out.add(unit);
        }
        return out;
    }

    private boolean shouldRetreat(Unit unit, NpcFaction faction) {
        return faction.retreatHpPercent() > 0 && unit.hp / Math.max(1.0, unit.type().maxHp) <= faction.retreatHpPercent();
    }

    private String nearestThreatToBase(World world, NpcFaction faction) {
        Base base = firstBase(world, faction);
        if (base == null) return "";
        String best = "";
        double bestDist = Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (!canTarget(faction, unit.playerId) || unit.hp <= 0) continue;
            double d = Calc.distance(base.x, base.y, unit.x, unit.y);
            if (d <= faction.defendRange() && d < bestDist) {
                best = CombatTarget.unit(unit);
                bestDist = d;
            }
        }
        return best;
    }

    private void guardIdleCombat(World world, List<Unit> combat, Base base) {
        for (Unit unit : combat) guardBase(world, unit, base);
    }

    private void guardBase(World world, Unit unit, Base base) {
        if (unit.task == UnitTask.ATTACK && CombatTarget.alive(world, unit.attackTarget)) return;
        double a = unit.unitId * 2.2;
        unit.moveTo(
                Calc.clamp(base.x + Math.cos(a) * Math.max(180, base.type().unloadRange + 160), 0, world.width),
                Calc.clamp(base.y + Math.sin(a) * Math.max(180, base.type().unloadRange + 160), 0, world.height));
    }

    private Base firstBase(World world, NpcFaction faction) {
        for (Base base : world.bases.values()) if (base.playerId.equals(faction.id()) && base.hp > 0) return base;
        return null;
    }

    private void assignMiningTarget(World world, NpcFaction faction, Unit unit) {
        if (unit.task == UnitTask.RETURN_TO_STATION) return;
        ResourceNode active = world.findResource(unit.automationResourceId);
        if (unit.task == UnitTask.AUTO_HARVEST && active != null && active.active && usableResource(faction, unit, active)) return;
        ResourceNode target = nearestResource(world, faction, unit);
        if (target == null) return;
        unit.setMiningAnchor(target.x, target.y);
        unit.startAutoHarvest(target.id);
    }

    private ResourceNode nearestResource(World world, NpcFaction faction, Unit unit) {
        ResourceNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (ResourceNode node : world.resources) {
            if (!usableResource(faction, unit, node)) continue;
            double d = Calc.distance(unit.x, unit.y, node.x, node.y);
            if (d < bestDist) {
                best = node;
                bestDist = d;
            }
        }
        return best;
    }

    private boolean usableResource(NpcFaction faction, Unit unit, ResourceNode node) {
        return node != null
                && node.active
                && node.amount > 0.05
                && unit.type().harvestKinds.contains(node.kind)
                && faction.allowsKind(node.kind)
                && faction.allowsMaterial(node.material);
    }

    private String priorityEnemyTarget(World world, NpcFaction faction, Unit unit) {
        if (faction.preferWorkerTargets()) {
            String worker = nearestWorkerTarget(world, faction, unit.x, unit.y);
            if (!worker.isBlank()) return worker;
        }
        String armed = nearestArmedUnitTarget(world, faction, unit.x, unit.y);
        if (!armed.isBlank()) return armed;
        return nearestEnemyTarget(world, faction, unit);
    }

    private String nearestWorkerTarget(World world, NpcFaction faction, Base base) {
        if (base == null) return "";
        return nearestWorkerTarget(world, faction, base.x, base.y);
    }

    private String nearestWorkerTarget(World world, NpcFaction faction, double x, double y) {
        if (!faction.attackUnits()) return "";
        String best = "";
        double bestDist = Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (!canTarget(faction, unit.playerId) || unit.hp <= 0 || unit.type().harvestKinds.isEmpty()) continue;
            double d = Calc.distance(x, y, unit.x, unit.y);
            if (d < bestDist) {
                best = CombatTarget.unit(unit);
                bestDist = d;
            }
        }
        return best;
    }

    private String nearestArmedUnitTarget(World world, NpcFaction faction, double x, double y) {
        if (!faction.attackUnits()) return "";
        String best = "";
        double bestDist = Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (!canTarget(faction, unit.playerId) || unit.hp <= 0 || !WeaponRules.armed(unit.type())) continue;
            double d = Calc.distance(x, y, unit.x, unit.y);
            if (d < bestDist) {
                best = CombatTarget.unit(unit);
                bestDist = d;
            }
        }
        return best;
    }

    private String nearestEnemyTarget(World world, NpcFaction faction, Unit unit) {
        String best = "";
        double bestDist = Double.MAX_VALUE;

        if (faction.attackUnits()) {
            for (Unit target : world.units.values()) {
                if (!canTarget(faction, target.playerId) || target.hp <= 0) continue;
                double d = Calc.distance(unit.x, unit.y, target.x, target.y);
                if (d < bestDist) {
                    best = CombatTarget.unit(target);
                    bestDist = d;
                }
            }
        }

        if (faction.attackBases()) {
            for (Base base : world.bases.values()) {
                if (!canTarget(faction, base.playerId) || base.hp <= 0) continue;
                double d = Calc.distance(unit.x, unit.y, base.x, base.y);
                if (d < bestDist) {
                    best = CombatTarget.base(base);
                    bestDist = d;
                }
            }
        }

        return best;
    }

    private boolean canTarget(NpcFaction faction, String targetPlayerId) {
        if (targetPlayerId == null || targetPlayerId.equals(faction.id())) return false;
        return faction.attackNpcFactions() || !NpcRules.isNpcFaction(targetPlayerId);
    }

    private int nextUnitNumber(World world, NpcFaction faction) {
        int max = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(faction.id())) max = Math.max(max, unit.unitId);
        return max + 1;
    }

    private int nextBaseNumber(World world, NpcFaction faction) {
        int max = 0;
        String prefix = faction.id() + ":B";
        for (String id : world.bases.keySet()) {
            if (!id.startsWith(prefix)) continue;
            try { max = Math.max(max, Integer.parseInt(id.substring(prefix.length()))); }
            catch (NumberFormatException ignored) { }
        }
        return max + 1;
    }

    private static final class NpcState {
        double spawnTimer;
        double orderTimer;
        double buildTimer;
        double raidCooldownTimer;
        NpcState(double spawnTimer) { this.spawnTimer = spawnTimer; }
    }

    private record SpawnPoint(double x, double y) { }
}
