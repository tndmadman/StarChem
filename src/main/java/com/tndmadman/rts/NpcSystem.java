package com.tndmadman.rts;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashSet;
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
                state.activeInitialized = false;
                if (!spawnRequirementsMet(world, faction)) {
                    state.spawnTimer = faction.firstSpawnSeconds();
                    continue;
                }
                state.spawnTimer -= dt;
                if (state.spawnTimer <= 0) {
                    spawnFaction(world, faction);
                    initializeSpawnedState(state, faction);
                }
                continue;
            }

            if (!state.activeInitialized) initializeSpawnedState(state, faction);
            state.buildTimer -= dt;
            state.stationBuildTimer -= dt;
            state.orderTimer -= dt;
            state.raidCooldownTimer = Math.max(0, state.raidCooldownTimer - dt);
            if (state.orderTimer <= 0) {
                orderFaction(world, faction, state);
                state.orderTimer = faction.orderSeconds();
            }
        }
    }

    private void initializeSpawnedState(NpcState state, NpcFaction faction) {
        state.spawnTimer = faction.respawnSeconds();
        state.orderTimer = 0;
        state.buildTimer = faction.buildSeconds();
        state.stationBuildTimer = faction.stationBuildSeconds();
        state.raidCooldownTimer = 0;
        state.activeInitialized = true;
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
            if ((faction.behavior() == NpcBehavior.MINER || faction.behavior() == NpcBehavior.FACTION) && ship.harvestKinds.isEmpty() && !WeaponRules.armed(ship) && !isSupportShip(faction, shipTypeId)) continue;
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
        NpcStrategicState strategy = NpcStrategicDirector.state(world, faction);
        Base base = firstBase(world, faction);

        if (strategy.runsEconomy()) {
            if (faction.replaceWorkers()) maintainWorkers(world, faction);
            orderFactionWorkers(world, faction);
            orderSupportShips(world, faction, base);
            operateStations(world, faction, strategy.allowsResearch());
        }

        if (strategy.buildsStations() && state.stationBuildTimer <= 0) {
            if (buildOrDeployStation(world, faction)) state.stationBuildTimer = faction.stationBuildSeconds();
            else state.stationBuildTimer = Math.max(3.0, faction.stationBuildSeconds() * 0.5);
        }

        if (strategy.buildsShips() && state.buildTimer <= 0) {
            boolean built;
            if (strategy.prioritizesFleet()) {
                built = buildFleetShip(world, faction)
                        || buildSupportShip(world, faction)
                        || buildIndustryShip(world, faction);
            } else {
                built = buildSupportShip(world, faction)
                        || buildIndustryShip(world, faction)
                        || buildFleetShip(world, faction);
            }
            state.buildTimer = built
                    ? faction.buildSeconds()
                    : Math.max(2.0, faction.buildSeconds() * 0.5);
        }

        List<Unit> combat = readyCombatUnits(world, faction, base);
        String defenseTarget = nearestThreatToBase(world, faction);
        if (!defenseTarget.isBlank()) {
            for (Unit unit : combat) unit.attack(defenseTarget);
            return;
        }

        if (!strategy.allowsRaid()) {
            if (base != null) guardIdleCombat(world, combat, base);
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

    private void operateStations(World world, NpcFaction faction, boolean allowResearch) {
        craftConfiguredItems(world, faction);
        supplyFuelToStations(world, faction);
        if (allowResearch) startConfiguredResearch(world, faction);
    }

    private boolean craftConfiguredItems(World world, NpcFaction faction) {
        if (faction.fuelReserve() <= 0 || factionMaterial(world, faction, Material.FUEL) >= faction.fuelReserve()) return false;
        return craftTowardMaterial(world, faction, Material.FUEL, faction.fuelReserve(), new HashSet<>());
    }

    private void supplyFuelToStations(World world, NpcFaction faction) {
        int consumers = fuelConsumerCount(world, faction);
        if (consumers <= 0) return;
        double target = Math.max(8.0, faction.fuelReserve() / Math.max(1, consumers));
        for (Base base : world.bases.values()) {
            if (!base.playerId.equals(faction.id()) || base.hp <= 0) continue;
            StationFuelRequirement req = StationFuelRules.requirement(base.typeId);
            if (req == null) continue;
            double held = base.inventory.getOrDefault(req.material(), 0.0);
            if (held < target) transferMaterialToBase(world, faction, base, req.material(), target - held);
        }
    }

    private int fuelConsumerCount(World world, NpcFaction faction) {
        int count = 0;
        for (Base base : world.bases.values()) {
            if (base.playerId.equals(faction.id()) && base.hp > 0 && StationFuelRules.requirement(base.typeId) != null) count++;
        }
        return count;
    }

    private boolean startConfiguredResearch(World world, NpcFaction faction) {
        for (String topicId : faction.researchTopicIds()) {
            ResearchTopic topic = ResearchRules.topic(topicId);
            if (topic == null || world.hasResearch(faction.id(), topic.id) || ResearchSystem.active(world, faction.id(), topic.id)) continue;
            if (!ResearchRules.missingPrerequisite(world, faction.id(), topic).isBlank()) continue;
            for (Base base : world.bases.values()) {
                if (!base.playerId.equals(faction.id()) || base.hp <= 0) continue;
                if (!topic.canResearchAt(base.typeId) || !StationFuelRules.isOperational(base)) continue;
                if (!factionCanAfford(world, faction, topic.requiredResources)) {
                    if (craftTowardCost(world, faction, topic.requiredResources)) return true;
                    continue;
                }
                spendFaction(world, faction, topic.requiredResources);
                ResearchSystem.start(world, base, topic);
                return true;
            }
        }
        return false;
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
            if (!workerTypes.isEmpty() && !workerTypes.contains(unit.shipTypeId) && !faction.industryUnitTypes().contains(unit.shipTypeId)) continue;
            assignMiningTarget(world, faction, unit);
        }
    }

    private void orderSupportShips(World world, NpcFaction faction, Base base) {
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(faction.id()) || unit.hp <= 0) continue;
            if (MobileDepot.isDepot(unit)) {
                guardBase(world, unit, base == null ? nearestBase(world, faction, unit.x, unit.y) : base);
            } else if ("salvager".equals(unit.shipTypeId)) {
                orderSalvager(world, unit, base);
            }
        }
    }

    private void orderSalvager(World world, Unit unit, Base base) {
        if (unit.freeCargo() <= 0.05) { world.sendToNearestBase(unit); return; }
        WorldItem item = nearestWorldItem(world, unit);
        if (item != null) unit.moveTo(item.x, item.y);
        else if (base != null) guardBase(world, unit, base);
    }

    private WorldItem nearestWorldItem(World world, Unit unit) {
        WorldItem best = null;
        double bestDist = Double.MAX_VALUE;
        for (WorldItem item : world.items) {
            if (item.empty()) continue;
            double d = Calc.distance(unit.x, unit.y, item.x, item.y);
            if (d < bestDist) { best = item; bestDist = d; }
        }
        return best;
    }

    private boolean buildOrDeployStation(World world, NpcFaction faction) {
        if (faction.stationPackageTypes().isEmpty() || faction.maxStations() <= 0) return false;
        if (baseCount(world, faction) >= faction.maxStations()) return false;
        Base source = stationPackageSource(world, faction);
        if (source == null) return false;
        Unit builder = emptyBuilder(world, faction, source);
        if (builder == null) return buildShipFromBase(world, faction, source, "station_builder");
        String packageType = nextStationPackage(world, faction, source);
        if (packageType.isBlank()) return false;
        BaseType packageBase = Rules.base(packageType);
        if (!factionCanAfford(world, faction, packageBase.buildCost)) {
            return craftTowardCost(world, faction, packageBase.buildCost);
        }
        spendFaction(world, faction, packageBase.buildCost);
        builder.basePackageType = packageType;
        placeStationFromBuilder(world, faction, source, builder, packageType);
        return true;
    }

    private Base stationPackageSource(World world, NpcFaction faction) {
        for (Base base : world.bases.values()) {
            if (!base.playerId.equals(faction.id()) || base.hp <= 0) continue;
            for (String packageType : faction.stationPackageTypes()) {
                if (!Rules.BASES.containsKey(packageType)) continue;
                if (base.type().basePackages.contains(packageType)) return base;
            }
        }
        return null;
    }

    private String nextStationPackage(World world, NpcFaction faction, Base source) {
        for (String packageType : faction.stationPackageTypes()) {
            if (!Rules.BASES.containsKey(packageType) || !source.type().basePackages.contains(packageType)) continue;
            if (!hasBaseType(world, faction, packageType)) return packageType;
        }
        for (String packageType : faction.stationPackageTypes()) {
            if (Rules.BASES.containsKey(packageType) && source.type().basePackages.contains(packageType)) return packageType;
        }
        return "";
    }

    private void placeStationFromBuilder(World world, NpcFaction faction, Base source, Unit builder, String packageType) {
        int n = nextBaseNumber(world, faction);
        double angle = n * 2.35;
        double distance = Math.max(source.type().unloadRange + 220, faction.stationSpacing());
        double x = Calc.clamp(source.x + Math.cos(angle) * distance, 0, world.width);
        double y = Calc.clamp(source.y + Math.sin(angle) * distance, 0, world.height);
        String baseId = faction.id() + ":B" + n;
        world.bases.put(baseId, new Base(baseId, faction.id(), packageType, x, y));
        world.units.remove(builder.key());
    }

    private Unit emptyBuilder(World world, NpcFaction faction, Base source) {
        Unit best = null;
        double bestDist = Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(faction.id()) || unit.hp <= 0) continue;
            if (!unit.type().baseBuilder || !unit.basePackageType.isBlank()) continue;
            double d = Calc.distance(unit.x, unit.y, source.x, source.y);
            if (d < bestDist) { best = unit; bestDist = d; }
        }
        return best;
    }

    private boolean buildFleetShip(World world, NpcFaction faction) {
        if (faction.targetFleetSize() <= 0 || combatUnits(world, faction).size() >= faction.targetFleetSize()) return false;
        return buildFirstAffordable(world, faction, faction.fleetUnitTypes(), true);
    }

    private boolean buildSupportShip(World world, NpcFaction faction) {
        if (faction.maxSupportUnits() <= 0 || supportCount(world, faction) >= faction.maxSupportUnits()) return false;
        return buildFirstAffordable(world, faction, nextSupportBuildOrder(world, faction), false);
    }

    private boolean buildIndustryShip(World world, NpcFaction faction) {
        if (faction.maxIndustryUnits() <= 0 || industryCount(world, faction) >= faction.maxIndustryUnits()) return false;
        return buildFirstAffordable(world, faction, faction.industryUnitTypes(), false);
    }

    private List<String> nextSupportBuildOrder(World world, NpcFaction faction) {
        List<String> missing = new ArrayList<>();
        for (String shipTypeId : faction.supportUnitTypes()) if (countUnitsOfType(world, faction, shipTypeId) <= 0) missing.add(shipTypeId);
        if (!missing.isEmpty()) return missing;
        return faction.supportUnitTypes();
    }

    private boolean buildFirstAffordable(World world, NpcFaction faction, List<String> shipTypes, boolean requireArmed) {
        for (Base base : world.bases.values()) {
            if (!base.playerId.equals(faction.id()) || base.hp <= 0) continue;
            for (String shipTypeId : shipTypes) {
                if (!canBuildShip(world, faction, base, shipTypeId, requireArmed)) continue;
                List<Cost> cost = Rules.ship(shipTypeId).buildCost;
                if (factionCanAfford(world, faction, cost)) return buildShipFromBase(world, faction, base, shipTypeId);
                if (craftTowardCost(world, faction, cost)) return true;
            }
        }
        return false;
    }

    private boolean buildShipFromBase(World world, NpcFaction faction, Base base, String shipTypeId) {
        if (!canBuildShip(world, faction, base, shipTypeId, false)) return false;
        ShipType ship = Rules.ship(shipTypeId);
        if (!factionCanAfford(world, faction, ship.buildCost)) return false;
        spendFaction(world, faction, ship.buildCost);
        int n = nextUnitNumber(world, faction);
        double a = n * 1.35;
        Unit unit = new Unit(faction.id(), n, shipTypeId,
                Calc.clamp(base.x + Math.cos(a) * (base.type().buildRadius + 40), 0, world.width),
                Calc.clamp(base.y + Math.sin(a) * (base.type().buildRadius + 40), 0, world.height));
        world.units.put(unit.key(), unit);
        return true;
    }

    private boolean canBuildShip(World world, NpcFaction faction, Base base, String shipTypeId, boolean requireArmed) {
        if (!Rules.SHIPS.containsKey(shipTypeId)) return false;
        if (!base.type().buildableShips.contains(shipTypeId)) return false;
        if (!ResearchRules.shipUnlocked(world, faction.id(), shipTypeId)) return false;
        return !requireArmed || WeaponRules.armed(Rules.ship(shipTypeId));
    }

    private boolean craftTowardCost(World world, NpcFaction faction, List<Cost> cost) {
        for (Cost need : cost) {
            double have = factionMaterial(world, faction, need.material());
            if (have + 0.001 >= need.amount()) continue;
            if (craftTowardMaterial(world, faction, need.material(), need.amount(), new HashSet<>())) return true;
        }
        return false;
    }

    private boolean craftTowardMaterial(World world, NpcFaction faction, Material material,
                                        double targetAmount, Set<Material> visiting) {
        if (factionMaterial(world, faction, material) + 0.001 >= targetAmount) return false;
        if (!visiting.add(material)) return false;
        try {
            for (CraftableItem item : CraftingRules.recipesForOutput(material)) {
                if (!faction.craftableItemIds().contains(item.id)) continue;
                if (!item.unlockedFor(world, faction.id())) continue;
                Base base = npcCraftingBase(world, faction, item);
                if (base == null) continue;

                boolean missingRaw = false;
                for (Cost input : item.requiredResources) {
                    if (factionMaterial(world, faction, input.material()) + 0.001 >= input.amount()) continue;
                    if (CraftingRules.preferredForOutput(input.material()) == null) {
                        missingRaw = true;
                        break;
                    }
                    if (craftTowardMaterial(world, faction, input.material(), input.amount(), visiting)) return true;
                    missingRaw = true;
                    break;
                }
                if (missingRaw || !factionCanAfford(world, faction, item.requiredResources)) continue;

                spendFaction(world, faction, item.requiredResources);
                HangarStore.add(base.inventory, item.outputMaterial, item.outputAmount);
                return true;
            }
            return false;
        } finally {
            visiting.remove(material);
        }
    }

    private Base npcCraftingBase(World world, NpcFaction faction, CraftableItem item) {
        for (Base base : world.bases.values()) {
            if (!base.playerId.equals(faction.id()) || base.hp <= 0) continue;
            if (!item.canCraftAt(base.typeId) || !StationFuelRules.isOperational(base)) continue;
            return base;
        }
        return null;
    }

    private boolean factionCanAfford(World world, NpcFaction faction, List<Cost> cost) {
        for (Cost c : cost) if (factionMaterial(world, faction, c.material()) + 0.001 < c.amount()) return false;
        return true;
    }

    private void spendFaction(World world, NpcFaction faction, List<Cost> cost) {
        for (Cost c : cost) spendFactionMaterial(world, faction, c.material(), c.amount());
    }

    private double factionMaterial(World world, NpcFaction faction, Material material) {
        double total = 0;
        for (Base base : world.bases.values()) if (base.playerId.equals(faction.id()) && base.hp > 0) total += base.inventory.getOrDefault(material, 0.0);
        return total;
    }

    private void spendFactionMaterial(World world, NpcFaction faction, Material material, double amount) {
        double remaining = amount;
        for (Base base : world.bases.values()) {
            if (!base.playerId.equals(faction.id()) || base.hp <= 0 || remaining <= 0.001) continue;
            double held = base.inventory.getOrDefault(material, 0.0);
            if (held <= 0.001) continue;
            double take = Math.min(held, remaining);
            double next = held - take;
            if (next <= 0.05) base.inventory.remove(material);
            else base.inventory.put(material, next);
            remaining -= take;
        }
    }

    private void transferMaterialToBase(World world, NpcFaction faction, Base target, Material material, double amount) {
        double remaining = amount;
        for (Base source : world.bases.values()) {
            if (source == target || !source.playerId.equals(faction.id()) || source.hp <= 0 || remaining <= 0.001) continue;
            double held = source.inventory.getOrDefault(material, 0.0);
            if (held <= 0.001) continue;
            double take = Math.min(held, remaining);
            double next = held - take;
            if (next <= 0.05) source.inventory.remove(material);
            else source.inventory.put(material, next);
            HangarStore.add(target.inventory, material, take);
            remaining -= take;
        }
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

    private boolean isSupportShip(NpcFaction faction, String shipTypeId) { return faction.supportTypeSet().contains(shipTypeId) || "station_builder".equals(shipTypeId); }

    private int supportCount(World world, NpcFaction faction) {
        int count = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(faction.id()) && unit.hp > 0 && isSupportShip(faction, unit.shipTypeId)) count++;
        return count;
    }

    private int industryCount(World world, NpcFaction faction) {
        int count = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(faction.id()) && unit.hp > 0 && faction.industryUnitTypes().contains(unit.shipTypeId)) count++;
        return count;
    }

    private int countUnitsOfType(World world, NpcFaction faction, String shipTypeId) {
        int count = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(faction.id()) && unit.hp > 0 && unit.shipTypeId.equals(shipTypeId)) count++;
        return count;
    }

    private int baseCount(World world, NpcFaction faction) {
        int count = 0;
        for (Base base : world.bases.values()) if (base.playerId.equals(faction.id()) && base.hp > 0) count++;
        return count;
    }

    private boolean hasBaseType(World world, NpcFaction faction, String typeId) {
        for (Base base : world.bases.values()) if (base.playerId.equals(faction.id()) && base.hp > 0 && base.typeId.equals(typeId)) return true;
        return false;
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
        if (unit == null || base == null) return;
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

    private Base nearestBase(World world, NpcFaction faction, double x, double y) {
        Base best = null;
        double bestDist = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (!base.playerId.equals(faction.id()) || base.hp <= 0) continue;
            double d = Calc.distance(x, y, base.x, base.y);
            if (d < bestDist) { best = base; bestDist = d; }
        }
        return best;
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
        double stationBuildTimer;
        double raidCooldownTimer;
        boolean activeInitialized;
        NpcState(double spawnTimer) { this.spawnTimer = spawnTimer; }
    }

    private record SpawnPoint(double x, double y) { }
}
