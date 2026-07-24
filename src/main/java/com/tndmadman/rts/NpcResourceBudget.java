package com.tndmadman.rts;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Builds and reuses galaxy-wide material reservation plans for organized NPC
 * factions. Materials stay in their normal base inventories; the plan only
 * limits which priority is allowed to consume them.
 */
final class NpcResourceBudget {
    private static final double EPSILON = 0.001;
    private static final double EXPANSION_FRACTION = 0.20;
    private static final double EXPANSION_CAP_PER_MATERIAL = 250.0;
    private static final Map<World, Map<String, CachedPlan>> PLAN_CACHE = new WeakHashMap<>();
    private static final Map<World, Map<String, Long>> PLAN_SCAN_COUNTS = new WeakHashMap<>();

    private NpcResourceBudget() { }

    static NpcBudgetPlan plan(World world, NpcFaction faction) {
        NpcStrategicState strategy = faction == null
                ? NpcStrategicState.DEFEATED
                : NpcStrategicDirector.state(world, faction);
        return plan(world, faction, strategy);
    }

    static synchronized NpcBudgetPlan plan(World world, NpcFaction faction, NpcStrategicState strategy) {
        NpcStrategicState normalizedStrategy = strategy == null
                ? NpcStrategicState.DEFEATED : strategy;
        if (world == null || faction == null || faction.behavior() != NpcBehavior.FACTION) {
            return NpcBudgetPlan.empty(normalizedStrategy);
        }

        String systemId = world.activeSystemId();
        long seed = world.systemSeed();
        long timeBits = Double.doubleToLongBits(world.systemTime());
        long fingerprint = localFingerprint(world, faction);
        CachedPlan cached = cachedPlan(world, faction);
        if (cached != null && cached.matches(seed, systemId, timeBits, fingerprint, normalizedStrategy)) {
            return cached.plan;
        }

        NpcBudgetPlan built = buildPlan(world, faction, normalizedStrategy);
        cachePlan(world, faction, new CachedPlan(seed, systemId, timeBits,
                localFingerprint(world, faction), normalizedStrategy, built));
        PLAN_SCAN_COUNTS.computeIfAbsent(world, ignored -> new LinkedHashMap<>())
                .merge(faction.id(), 1L, Long::sum);
        return built;
    }

    private static NpcBudgetPlan buildPlan(World world, NpcFaction faction,
                                           NpcStrategicState strategy) {
        Scan scan = inspect(world, faction);
        EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> direct = emptyBuckets();

        if (scan.hasAssets()) {
            reserveEmergencyFuel(direct.get(NpcBudgetCategory.EMERGENCY_FUEL), faction);
            reserveWorkerRecovery(direct.get(NpcBudgetCategory.WORKER_RECOVERY), world, faction, scan);

            if (strategy.buildsStations() && scan.stations < faction.maxStations()) {
                NpcBudgetCategory category = strategy == NpcStrategicState.EXPAND
                        ? NpcBudgetCategory.EXPANSION
                        : NpcBudgetCategory.STATION_RECOVERY;
                reserveStationRecovery(direct.get(category), world, faction, scan);
            }

            if (strategy == NpcStrategicState.RESEARCH) {
                reserveResearch(direct.get(NpcBudgetCategory.RESEARCH), world, faction, scan);
            }

            if (strategy.prioritizesFleet()
                    && scan.combat < Math.max(1, faction.targetFleetSize())) {
                reserveFleet(direct.get(NpcBudgetCategory.FLEET), world, faction, scan);
            }

            if (strategy == NpcStrategicState.EXPAND) {
                reserveExpansionSupplies(direct.get(NpcBudgetCategory.EXPANSION), scan.homeMaterials);
            }
        }

        EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> desired = emptyBuckets();
        EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> funded = emptyBuckets();
        EnumMap<Material, Double> remaining = copyMaterials(scan.materials);
        for (NpcBudgetCategory category : NpcBudgetCategory.values()) {
            EnumMap<Material, Double> categoryDesired = expandRequirements(
                    world, faction, scan, direct.get(category), remaining);
            desired.put(category, categoryDesired);
            EnumMap<Material, Double> categoryFunded = funded.get(category);
            for (Material material : Material.values()) {
                double need = categoryDesired.getOrDefault(material, 0.0);
                if (need <= EPSILON) continue;
                double amount = Math.min(need, remaining.getOrDefault(material, 0.0));
                if (amount > EPSILON) categoryFunded.put(material, amount);
                subtract(remaining, material, amount);
            }
        }

        return new NpcBudgetPlan(strategy, scan.materials, desired, funded);
    }

    static boolean canAfford(World world, NpcFaction faction, NpcBudgetCategory category, List<Cost> cost) {
        return canAfford(world, faction, category, cost, plan(world, faction));
    }

    static boolean canAfford(World world, NpcFaction faction, NpcBudgetCategory category,
                             List<Cost> cost, NpcBudgetPlan plan) {
        if (world == null || faction == null || cost == null || cost.isEmpty()) return true;
        if (category == null) category = NpcBudgetCategory.GENERAL;
        if (faction.behavior() != NpcBehavior.FACTION) return localCanAfford(world, faction.id(), cost);
        if (plan == null) plan = plan(world, faction);

        EnumMap<Material, Double> local = localMaterials(world, faction.id());
        for (Cost entry : cost) {
            if (entry == null || entry.amount() <= EPSILON) continue;
            Material material = entry.material();
            if (local.getOrDefault(material, 0.0) + EPSILON < entry.amount()) return false;
            double globallySpendable = plan.total(material) - plan.protectedBefore(category, material);
            if (globallySpendable + EPSILON < entry.amount()) return false;
        }
        return true;
    }

    static synchronized boolean spend(World world, NpcFaction faction,
                                      NpcBudgetCategory category, List<Cost> cost) {
        if (world == null || faction == null || cost == null || cost.isEmpty()) return true;
        NpcBudgetCategory normalizedCategory = category == null
                ? NpcBudgetCategory.GENERAL : category;
        NpcBudgetPlan current = plan(world, faction);
        if (!canAfford(world, faction, normalizedCategory, cost, current)) return false;
        for (Cost entry : cost) {
            if (entry == null || entry.amount() <= EPSILON) continue;
            spendLocalMaterial(world, faction.id(), entry.material(), entry.amount());
        }
        if (faction.behavior() == NpcBehavior.FACTION) {
            NpcBudgetPlan adjusted = current.afterSpend(normalizedCategory, cost);
            cachePlan(world, faction, new CachedPlan(
                    world.systemSeed(), world.activeSystemId(),
                    Double.doubleToLongBits(world.systemTime()),
                    localFingerprint(world, faction), adjusted.strategy(), adjusted));
        }
        return true;
    }

    static boolean canLaunchExpansion(World world, NpcFaction faction) {
        return canLaunchExpansion(world, faction, plan(world, faction));
    }

    static boolean canLaunchExpansion(World world, NpcFaction faction, NpcBudgetPlan plan) {
        if (world == null || faction == null || plan == null
                || plan.strategy() != NpcStrategicState.EXPAND) return false;
        if (!plan.higherPrioritiesFunded(NpcBudgetCategory.EXPANSION)
                || !plan.fullyFunded(NpcBudgetCategory.EXPANSION)
                || plan.reservedTotal(NpcBudgetCategory.EXPANSION) <= EPSILON) return false;

        Base source = expansionSupplyBase(world, faction);
        if (source == null) return false;
        EnumMap<Material, Double> local = localMaterials(world, faction.id());
        double transferred = 0.0;
        for (Material material : Material.values()) {
            if (!material.raw && material != Material.FUEL) continue;
            double held = source.inventory.getOrDefault(material, 0.0);
            double take = Math.min(EXPANSION_CAP_PER_MATERIAL, held * EXPANSION_FRACTION);
            if (take <= EPSILON) continue;
            transferred += take;
            double leftInSourceSystem = local.getOrDefault(material, 0.0) - take;
            if (leftInSourceSystem + EPSILON < plan.protectedBefore(NpcBudgetCategory.EXPANSION, material)) {
                return false;
            }
        }
        return transferred > 1.0;
    }

    static synchronized void invalidate(World world, NpcFaction faction) {
        if (world == null) return;
        if (faction == null) {
            PLAN_CACHE.remove(world);
            return;
        }
        Map<String, CachedPlan> byFaction = PLAN_CACHE.get(world);
        if (byFaction == null) return;
        byFaction.remove(faction.id());
        if (byFaction.isEmpty()) PLAN_CACHE.remove(world);
    }

    static synchronized long scanCountForTesting(World world, NpcFaction faction) {
        if (world == null || faction == null) return 0;
        return PLAN_SCAN_COUNTS.getOrDefault(world, Map.of()).getOrDefault(faction.id(), 0L);
    }

    private static CachedPlan cachedPlan(World world, NpcFaction faction) {
        Map<String, CachedPlan> byFaction = PLAN_CACHE.get(world);
        return byFaction == null ? null : byFaction.get(faction.id());
    }

    private static void cachePlan(World world, NpcFaction faction, CachedPlan cached) {
        PLAN_CACHE.computeIfAbsent(world, ignored -> new LinkedHashMap<>())
                .put(faction.id(), cached);
    }

    private static long localFingerprint(World world, NpcFaction faction) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, faction.id().hashCode());
        Set<String> research = world.completedResearch.getOrDefault(faction.id(), Set.of());
        for (String topic : research) hash = mix(hash, topic.hashCode());
        for (Base base : world.bases.values()) {
            if (!faction.id().equals(base.playerId)) continue;
            hash = mix(hash, base.id.hashCode());
            hash = mix(hash, base.typeId.hashCode());
            hash = mix(hash, Double.doubleToLongBits(base.hp));
            for (Material material : Material.values()) {
                hash = mix(hash, Double.doubleToLongBits(
                        base.inventory.getOrDefault(material, 0.0)));
            }
            hash = mix(hash, base.productionQueue.size());
            for (ProductionJob job : base.productionQueue) {
                hash = mix(hash, job.id.hashCode());
                hash = mix(hash, job.kind.ordinal());
                hash = mix(hash, job.itemId.hashCode());
                hash = mix(hash, Double.doubleToLongBits(job.remaining));
                hash = mix(hash, job.resourcesReserved ? 1 : 0);
            }
        }
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId)) continue;
            hash = mix(hash, unit.key().hashCode());
            hash = mix(hash, unit.shipTypeId.hashCode());
            hash = mix(hash, Double.doubleToLongBits(unit.hp));
            hash = mix(hash, unit.basePackageType.hashCode());
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static void reserveEmergencyFuel(EnumMap<Material, Double> bucket, NpcFaction faction) {
        if (faction.fuelReserve() <= EPSILON) return;
        double reserve = Math.max(10.0, faction.fuelReserve() * 0.20);
        add(bucket, Material.FUEL, reserve);
    }

    private static void reserveWorkerRecovery(EnumMap<Material, Double> bucket, World world,
                                              NpcFaction faction, Scan scan) {
        if (faction.maxWorkers() <= 0 || scan.workers >= faction.maxWorkers()) return;
        String worker = firstBuildableShip(world, faction, scan, faction.workerUnitTypes(), false, true);
        if (worker.isBlank()) return;
        addCost(bucket, Rules.ship(worker).buildCost);
    }

    private static void reserveStationRecovery(EnumMap<Material, Double> bucket, World world,
                                               NpcFaction faction, Scan scan) {
        if (scan.buildablePackages.isEmpty()) return;
        String packageType = "";
        for (String candidate : faction.stationPackageTypes()) {
            if (!Rules.BASES.containsKey(candidate) || !scan.buildablePackages.contains(candidate)) continue;
            if (!scan.baseTypes.contains(candidate)) { packageType = candidate; break; }
        }
        if (packageType.isBlank()) {
            for (String candidate : faction.stationPackageTypes()) {
                if (Rules.BASES.containsKey(candidate) && scan.buildablePackages.contains(candidate)) {
                    packageType = candidate;
                    break;
                }
            }
        }
        if (packageType.isBlank()) return;
        if (!scan.emptyBuilder && scan.buildableShips.contains("station_builder")
                && Rules.SHIPS.containsKey("station_builder")
                && ResearchRules.shipUnlocked(world, faction.id(), "station_builder")) {
            addCost(bucket, Rules.ship("station_builder").buildCost);
        }
        addCost(bucket, Rules.base(packageType).buildCost);
    }

    private static void reserveResearch(EnumMap<Material, Double> bucket, World world,
                                        NpcFaction faction, Scan scan) {
        for (String topicId : faction.researchTopicIds()) {
            ResearchTopic topic = ResearchRules.topic(topicId);
            if (topic == null || world.hasResearch(faction.id(), topic.id)
                    || scan.activeResearchTopics.contains(topic.id)
                    || !scan.researchCapableTopics.contains(topic.id)
                    || !ResearchRules.missingPrerequisite(world, faction.id(), topic).isBlank()) continue;
            addCost(bucket, topic.requiredResources);
            return;
        }
    }

    private static void reserveFleet(EnumMap<Material, Double> bucket, World world,
                                     NpcFaction faction, Scan scan) {
        String ship = firstBuildableShip(world, faction, scan, faction.fleetUnitTypes(), true, false);
        if (ship.isBlank()) return;
        addCost(bucket, Rules.ship(ship).buildCost);
    }

    private static void reserveExpansionSupplies(EnumMap<Material, Double> bucket,
                                                 EnumMap<Material, Double> homeMaterials) {
        for (Material material : Material.values()) {
            if (!material.raw && material != Material.FUEL) continue;
            double held = homeMaterials.getOrDefault(material, 0.0);
            add(bucket, material, Math.min(EXPANSION_CAP_PER_MATERIAL, held * EXPANSION_FRACTION));
        }
    }

    private static String firstBuildableShip(World world, NpcFaction faction, Scan scan,
                                             List<String> candidates, boolean requireArmed,
                                             boolean requireWorker) {
        for (String shipTypeId : candidates) {
            if (!Rules.SHIPS.containsKey(shipTypeId) || !scan.buildableShips.contains(shipTypeId)) continue;
            ShipType ship = Rules.ship(shipTypeId);
            if (requireArmed && !WeaponRules.armed(ship)) continue;
            if (requireWorker && ship.harvestKinds.isEmpty()) continue;
            if (!ResearchRules.shipUnlocked(world, faction.id(), shipTypeId)) continue;
            return shipTypeId;
        }
        return "";
    }

    private static void addCost(EnumMap<Material, Double> bucket, List<Cost> cost) {
        if (cost == null) return;
        for (Cost entry : cost) {
            if (entry != null) add(bucket, entry.material(), entry.amount());
        }
    }

    private static void add(EnumMap<Material, Double> bucket, Material material, double amount) {
        if (bucket == null || material == null || !Double.isFinite(amount) || amount <= EPSILON) return;
        bucket.merge(material, amount, Double::sum);
    }

    private static void subtract(EnumMap<Material, Double> bucket, Material material, double amount) {
        if (bucket == null || material == null || amount <= EPSILON) return;
        double left = bucket.getOrDefault(material, 0.0) - amount;
        if (left <= EPSILON) bucket.remove(material); else bucket.put(material, left);
    }

    private static EnumMap<Material, Double> expandRequirements(World world, NpcFaction faction,
                                                                 Scan scan,
                                                                 EnumMap<Material, Double> direct,
                                                                 EnumMap<Material, Double> totals) {
        EnumMap<Material, Double> desired = copyMaterials(direct);
        EnumMap<Material, Integer> expandedBatches = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Double> entry : copyMaterials(direct).entrySet()) {
            expandMissing(world, faction, scan, desired, totals, expandedBatches,
                    entry.getKey(), entry.getValue(), new HashSet<>());
        }
        return desired;
    }

    private static void expandMissing(World world, NpcFaction faction, Scan scan,
                                      EnumMap<Material, Double> desired,
                                      EnumMap<Material, Double> totals,
                                      EnumMap<Material, Integer> expandedBatches,
                                      Material material, double totalDemand,
                                      Set<Material> visiting) {
        double missing = Math.max(0.0, totalDemand - totals.getOrDefault(material, 0.0));
        if (missing <= EPSILON || !visiting.add(material)) return;
        try {
            CraftableItem recipe = preferredRecipe(world, faction, scan, material);
            if (recipe == null || recipe.outputAmount <= EPSILON) return;
            int requiredBatches = (int)Math.ceil(missing / recipe.outputAmount);
            int previousBatches = expandedBatches.getOrDefault(material, 0);
            int additionalBatches = requiredBatches - previousBatches;
            if (additionalBatches <= 0) return;
            expandedBatches.put(material, requiredBatches);

            for (Cost input : recipe.requiredResources) {
                double amount = input.amount() * additionalBatches;
                add(desired, input.material(), amount);
                expandMissing(world, faction, scan, desired, totals, expandedBatches,
                        input.material(), desired.getOrDefault(input.material(), 0.0), visiting);
            }
        } finally {
            visiting.remove(material);
        }
    }

    private static CraftableItem preferredRecipe(World world, NpcFaction faction,
                                                  Scan scan, Material output) {
        for (CraftableItem item : CraftingRules.recipesForOutput(output)) {
            if (!faction.craftableItemIds().contains(item.id)) continue;
            if (!scan.craftableItemIds.contains(item.id)) continue;
            if (!item.unlockedFor(world, faction.id())) continue;
            return item;
        }
        return null;
    }

    private static Scan inspect(World world, NpcFaction faction) {
        Scan scan = new Scan();
        String previousSystemId = world.activeSystemId();
        String previousStatus = world.status;
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        try {
            if (map == null || map.systems() == null || map.systems().isEmpty()) {
                scanCurrentSystem(world, faction, scan,
                        NpcFactionRuntime.homeSystemIdFor(faction).equals(world.activeSystemId()));
            } else {
                for (GalaxyMapSystem system : map.systems()) {
                    if (system == null || system.id() == null || system.id().isBlank()) continue;
                    world.activateSystem(system.id());
                    scanCurrentSystem(world, faction, scan,
                            NpcFactionRuntime.homeSystemIdFor(faction).equals(system.id()));
                }
            }
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) world.activateSystem(previousSystemId);
            world.status = previousStatus;
        }
        return scan;
    }

    private static void scanCurrentSystem(World world, NpcFaction faction, Scan scan, boolean homeSystem) {
        Set<String> workerTypes = faction.workerTypeSet();
        for (Base base : world.bases.values()) {
            if (!faction.id().equals(base.playerId) || base.hp <= 0) continue;
            scan.stations++;
            scan.baseTypes.add(base.typeId);
            scan.buildableShips.addAll(base.type().buildableShips);
            scan.buildablePackages.addAll(base.type().basePackages);
            addInventory(scan.materials, base.inventory);
            if (homeSystem) addInventory(scan.homeMaterials, base.inventory);
            boolean operational = StationFuelRules.isOperational(base);
            for (String topicId : faction.researchTopicIds()) {
                ResearchTopic topic = ResearchRules.topic(topicId);
                if (topic != null && topic.canResearchAt(base.typeId) && operational) {
                    scan.researchCapableTopics.add(topic.id);
                }
            }
            if (operational) {
                for (CraftableItem item : CraftingRules.all()) {
                    if (faction.craftableItemIds().contains(item.id) && item.canCraftAt(base.typeId)) {
                        scan.craftableItemIds.add(item.id);
                    }
                }
            }
        }

        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0) continue;
            ShipType type = unit.type();
            if (!type.harvestKinds.isEmpty()
                    && (workerTypes.isEmpty() || workerTypes.contains(unit.shipTypeId))) scan.workers++;
            if (WeaponRules.armed(type)) scan.combat++;
            if (type.baseBuilder && unit.basePackageType.isBlank()) scan.emptyBuilder = true;
        }

        for (String topicId : faction.researchTopicIds()) {
            if (ResearchSystem.active(world, faction.id(), topicId)) scan.activeResearchTopics.add(topicId);
        }
    }

    private static void addInventory(EnumMap<Material, Double> target,
                                     EnumMap<Material, Double> inventory) {
        for (Map.Entry<Material, Double> entry : inventory.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > EPSILON) {
                target.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
    }

    private static boolean localCanAfford(World world, String factionId, List<Cost> cost) {
        EnumMap<Material, Double> local = localMaterials(world, factionId);
        for (Cost entry : cost) {
            if (entry != null && local.getOrDefault(entry.material(), 0.0) + EPSILON < entry.amount()) return false;
        }
        return true;
    }

    private static EnumMap<Material, Double> localMaterials(World world, String factionId) {
        EnumMap<Material, Double> total = new EnumMap<>(Material.class);
        for (Base base : world.bases.values()) {
            if (!factionId.equals(base.playerId) || base.hp <= 0) continue;
            addInventory(total, base.inventory);
        }
        return total;
    }

    private static void spendLocalMaterial(World world, String factionId, Material material, double amount) {
        double remaining = amount;
        for (Base base : world.bases.values()) {
            if (!factionId.equals(base.playerId) || base.hp <= 0 || remaining <= EPSILON) continue;
            double held = base.inventory.getOrDefault(material, 0.0);
            if (held <= EPSILON) continue;
            double take = Math.min(held, remaining);
            double left = held - take;
            if (left <= 0.05) base.inventory.remove(material); else base.inventory.put(material, left);
            remaining -= take;
        }
    }

    static Base expansionSupplyBase(World world, NpcFaction faction) {
        if (world == null || faction == null) return null;
        Base best = null;
        double bestTotal = -1.0;
        for (Base base : world.bases.values()) {
            if (!faction.id().equals(base.playerId) || base.hp <= 0) continue;
            double total = 0.0;
            for (Material material : Material.values()) {
                if (material.raw || material == Material.FUEL) {
                    total += base.inventory.getOrDefault(material, 0.0);
                }
            }
            if (total > bestTotal + EPSILON
                    || (Math.abs(total - bestTotal) <= EPSILON
                    && (best == null || base.id.compareTo(best.id) < 0))) {
                best = base;
                bestTotal = total;
            }
        }
        return best;
    }

    private static EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> emptyBuckets() {
        EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> out = new EnumMap<>(NpcBudgetCategory.class);
        for (NpcBudgetCategory category : NpcBudgetCategory.values()) {
            out.put(category, new EnumMap<>(Material.class));
        }
        return out;
    }

    private static EnumMap<Material, Double> copyMaterials(Map<Material, Double> source) {
        EnumMap<Material, Double> out = new EnumMap<>(Material.class);
        if (source != null) out.putAll(source);
        return out;
    }

    private static final class CachedPlan {
        final long seed;
        final String systemId;
        final long timeBits;
        final long fingerprint;
        final NpcStrategicState strategy;
        final NpcBudgetPlan plan;

        CachedPlan(long seed, String systemId, long timeBits, long fingerprint,
                   NpcStrategicState strategy, NpcBudgetPlan plan) {
            this.seed = seed;
            this.systemId = systemId == null ? "" : systemId;
            this.timeBits = timeBits;
            this.fingerprint = fingerprint;
            this.strategy = strategy;
            this.plan = plan;
        }

        boolean matches(long expectedSeed, String expectedSystemId, long expectedTimeBits,
                        long expectedFingerprint, NpcStrategicState expectedStrategy) {
            return seed == expectedSeed
                    && systemId.equals(expectedSystemId == null ? "" : expectedSystemId)
                    && timeBits == expectedTimeBits
                    && fingerprint == expectedFingerprint
                    && strategy == expectedStrategy;
        }
    }

    private static final class Scan {
        final EnumMap<Material, Double> materials = new EnumMap<>(Material.class);
        final EnumMap<Material, Double> homeMaterials = new EnumMap<>(Material.class);
        final Set<String> baseTypes = new LinkedHashSet<>();
        final Set<String> buildableShips = new LinkedHashSet<>();
        final Set<String> buildablePackages = new LinkedHashSet<>();
        final Set<String> activeResearchTopics = new LinkedHashSet<>();
        final Set<String> researchCapableTopics = new LinkedHashSet<>();
        final Set<String> craftableItemIds = new LinkedHashSet<>();
        int stations;
        int workers;
        int combat;
        boolean emptyBuilder;

        boolean hasAssets() { return stations > 0 || workers > 0 || combat > 0 || emptyBuilder; }
    }
}

enum NpcBudgetCategory {
    EMERGENCY_FUEL,
    WORKER_RECOVERY,
    STATION_RECOVERY,
    RESEARCH,
    FLEET,
    EXPANSION,
    GENERAL
}

final class NpcBudgetPlan {
    private static final double EPSILON = 0.001;

    private final NpcStrategicState strategy;
    private final EnumMap<Material, Double> totals;
    private final EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> desired;
    private final EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> funded;

    NpcBudgetPlan(NpcStrategicState strategy,
                  EnumMap<Material, Double> totals,
                  EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> desired,
                  EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> funded) {
        this.strategy = strategy == null ? NpcStrategicState.DEFEATED : strategy;
        this.totals = copy(totals);
        this.desired = copyBuckets(desired);
        this.funded = copyBuckets(funded);
    }

    static NpcBudgetPlan empty(NpcStrategicState strategy) {
        EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> buckets = new EnumMap<>(NpcBudgetCategory.class);
        for (NpcBudgetCategory category : NpcBudgetCategory.values()) {
            buckets.put(category, new EnumMap<>(Material.class));
        }
        return new NpcBudgetPlan(strategy, new EnumMap<>(Material.class), buckets, buckets);
    }

    NpcStrategicState strategy() { return strategy; }
    double total(Material material) { return totals.getOrDefault(material, 0.0); }
    double desired(NpcBudgetCategory category, Material material) {
        return desired.get(category).getOrDefault(material, 0.0);
    }
    double reserved(NpcBudgetCategory category, Material material) {
        return funded.get(category).getOrDefault(material, 0.0);
    }

    double protectedBefore(NpcBudgetCategory category, Material material) {
        double total = 0.0;
        for (NpcBudgetCategory candidate : NpcBudgetCategory.values()) {
            if (candidate.ordinal() >= category.ordinal()) break;
            total += reserved(candidate, material);
        }
        return total;
    }

    boolean fullyFunded(NpcBudgetCategory category) {
        for (Material material : Material.values()) {
            if (reserved(category, material) + EPSILON < desired(category, material)) return false;
        }
        return true;
    }

    boolean higherPrioritiesFunded(NpcBudgetCategory category) {
        for (NpcBudgetCategory candidate : NpcBudgetCategory.values()) {
            if (candidate.ordinal() >= category.ordinal()) break;
            if (!fullyFunded(candidate)) return false;
        }
        return true;
    }

    double reservedTotal(NpcBudgetCategory category) {
        double total = 0.0;
        for (double amount : funded.get(category).values()) total += amount;
        return total;
    }

    NpcBudgetPlan afterSpend(NpcBudgetCategory category, List<Cost> cost) {
        NpcBudgetCategory normalized = category == null ? NpcBudgetCategory.GENERAL : category;
        EnumMap<Material, Double> nextTotals = copy(totals);
        EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> nextFunded = copyBuckets(funded);
        if (cost != null) {
            for (Cost entry : cost) {
                if (entry == null || entry.amount() <= EPSILON) continue;
                Material material = entry.material();
                double amount = entry.amount();
                subtract(nextTotals, material, amount);
                double remaining = amount;
                for (NpcBudgetCategory candidate : NpcBudgetCategory.values()) {
                    if (candidate.ordinal() < normalized.ordinal() || remaining <= EPSILON) continue;
                    EnumMap<Material, Double> bucket = nextFunded.get(candidate);
                    double reserved = bucket.getOrDefault(material, 0.0);
                    double used = Math.min(reserved, remaining);
                    subtract(bucket, material, used);
                    remaining -= used;
                }
            }
        }
        return new NpcBudgetPlan(strategy, nextTotals, desired, nextFunded);
    }

    String summary() {
        StringBuilder out = new StringBuilder();
        for (NpcBudgetCategory category : NpcBudgetCategory.values()) {
            double amount = reservedTotal(category);
            if (amount <= EPSILON) continue;
            if (out.length() > 0) out.append(" | ");
            out.append(category.name()).append(' ').append((int)Math.round(amount));
        }
        return out.length() == 0 ? "unreserved" : out.toString();
    }

    private static void subtract(EnumMap<Material, Double> bucket, Material material, double amount) {
        if (bucket == null || material == null || amount <= EPSILON) return;
        double left = bucket.getOrDefault(material, 0.0) - amount;
        if (left <= EPSILON) bucket.remove(material); else bucket.put(material, left);
    }

    private static EnumMap<Material, Double> copy(Map<Material, Double> source) {
        EnumMap<Material, Double> out = new EnumMap<>(Material.class);
        if (source != null) out.putAll(source);
        return out;
    }

    private static EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> copyBuckets(
            EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> source) {
        EnumMap<NpcBudgetCategory, EnumMap<Material, Double>> out = new EnumMap<>(NpcBudgetCategory.class);
        for (NpcBudgetCategory category : NpcBudgetCategory.values()) {
            out.put(category, copy(source == null ? null : source.get(category)));
        }
        return out;
    }
}
