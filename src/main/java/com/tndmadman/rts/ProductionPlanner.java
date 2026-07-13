package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ProductionPlanner {
    private static final double EPSILON = 0.05;
    private static final double UPDATE_INTERVAL = 0.35;
    private static final int MAX_PLANS_PER_WORLD = 128;
    private static final Map<World, PlannerState> STATES = new IdentityHashMap<>();

    private ProductionPlanner() { }

    static boolean queueShip(World world, Base target, ShipType ship) {
        if (ship == null) return false;
        return queue(world, target, ProductionJobKind.SHIP, ship.id, ship.name, ship.buildCost);
    }

    static boolean queuePackage(World world, Base target, BaseType station) {
        if (station == null) return false;
        return queue(world, target, ProductionJobKind.STATION_PACKAGE, station.id,
                station.name + " package", station.buildCost);
    }

    static boolean queueCraftable(World world, Base target, CraftableItem item) {
        if (item == null) return false;
        return queue(world, target, ProductionJobKind.CRAFTABLE, item.id, item.name, item.requiredResources);
    }

    static boolean queueResearch(World world, Base target, ResearchTopic topic) {
        if (topic == null) return false;
        return queue(world, target, ProductionJobKind.RESEARCH, topic.id,
                topic.name + " research", topic.requiredResources);
    }

    static synchronized void update(World world, double dt) {
        if (world == null || dt < 0) return;
        PlannerState state = STATES.get(world);
        if (state == null || state.plans.isEmpty()) return;
        state.timer += Math.min(1.0, dt);
        if (state.timer < UPDATE_INTERVAL) return;
        state.timer = 0;

        String activeSystem = world.activeSystemId();
        Iterator<ProductionPlan> iterator = state.plans.iterator();
        while (iterator.hasNext()) {
            ProductionPlan plan = iterator.next();
            if (!plan.systemId.equals(activeSystem)) continue;
            Base target = world.bases.get(plan.root.targetBaseId);
            if (target == null || target.hp <= 0 || !target.playerId.equals(plan.playerId)) {
                GameNoticeCenter.publish(world, plan.playerId, NoticeCategory.WARNING,
                        "Auto-production plan cancelled because its destination station no longer exists: "
                                + plan.root.displayName + ".", true);
                iterator.remove();
                continue;
            }

            if (networkCanCover(world, plan.playerId, plan.root.cost) && enqueueRoot(world, target, plan.root)) {
                world.status = "Auto-production prerequisites ready. Queued " + plan.root.displayName + ".";
                GameNoticeCenter.publish(world, plan.playerId, NoticeCategory.PRODUCTION,
                        "All prerequisites are available. " + plan.root.displayName + " has been queued.", true);
                iterator.remove();
                continue;
            }

            boolean queuedSomething = false;
            Set<Material> visiting = new HashSet<>();
            for (Cost need : plan.root.cost) {
                EnsureResult result = ensureMaterial(world, plan, need.material(), need.amount(), visiting);
                if (result == EnsureResult.QUEUED) {
                    queuedSomething = true;
                    break;
                }
            }
            reportState(world, plan, queuedSomething);
        }
        if (state.plans.isEmpty()) STATES.remove(world);
    }

    static synchronized int planCount(World world) {
        PlannerState state = STATES.get(world);
        return state == null ? 0 : state.plans.size();
    }

    private static boolean queue(World world, Base target, ProductionJobKind kind, String itemId,
                                 String displayName, List<Cost> cost) {
        if (world == null || target == null || kind == null || itemId == null || itemId.isBlank()
                || cost == null || cost.isEmpty()) return false;
        PlannerState state;
        synchronized (ProductionPlanner.class) {
            state = STATES.computeIfAbsent(world, ignored -> new PlannerState());
            if (state.plans.size() >= MAX_PLANS_PER_WORLD) {
                world.status = "Too many active auto-production plans.";
                return false;
            }
            String id = "AP" + state.nextPlanId++;
            PlannedAction root = new PlannedAction(kind, itemId, displayName, target.id, List.copyOf(cost));
            ProductionPlan plan = new ProductionPlan(id, world.activeSystemId(), target.playerId, root);
            state.plans.add(plan);
            world.status = "Created auto-production plan for " + displayName + ".";
            GameNoticeCenter.publish(world, target.playerId, NoticeCategory.PRODUCTION,
                    "Auto-production plan created for " + displayName + ".", false);
            reportState(world, plan, false);
        }
        return true;
    }

    private static EnsureResult ensureMaterial(World world, ProductionPlan plan, Material material,
                                               double requiredAmount, Set<Material> visiting) {
        double current = networkAmount(world, plan.playerId, material);
        if (current + EPSILON >= requiredAmount) return EnsureResult.READY;
        double future = futureOutput(world, plan.playerId, material);
        if (current + future + EPSILON >= requiredAmount) return EnsureResult.WAITING;
        if (!visiting.add(material)) return EnsureResult.BLOCKED;
        try {
            RecipeChoice choice = chooseRecipe(world, plan, material);
            if (choice == null) return EnsureResult.BLOCKED;
            CraftableItem recipe = choice.item;

            for (Cost input : recipe.requiredResources) {
                EnsureResult inputResult = ensureMaterial(world, plan, input.material(), input.amount(), visiting);
                if (inputResult == EnsureResult.QUEUED) return EnsureResult.QUEUED;
                if (inputResult != EnsureResult.READY) return inputResult;
            }

            if (!networkCanCover(world, plan.playerId, recipe.requiredResources)) return EnsureResult.WAITING;
            if (!queueCraftable(world, choice.station, recipe)) return EnsureResult.WAITING;
            GameNoticeCenter.publish(world, plan.playerId, NoticeCategory.PRODUCTION,
                    "Auto-queued prerequisite: " + recipe.name + " at " + choice.station.type().name + ".", false);
            return EnsureResult.QUEUED;
        } finally {
            visiting.remove(material);
        }
    }

    private static boolean queueCraftable(World world, Base station, CraftableItem item) {
        if (HangarStore.canAfford(station.inventory, item.requiredResources)) {
            return ProductionSystem.enqueueCraftable(world, station, item, false);
        }
        return world.logisticsSystem.queueCraftable(world, station, item);
    }

    private static boolean enqueueRoot(World world, Base target, PlannedAction root) {
        return switch (root.kind) {
            case SHIP -> {
                ShipType ship = Rules.findShip(root.itemId);
                if (ship == null) yield false;
                yield HangarStore.canAfford(target.inventory, ship.buildCost)
                        ? ProductionSystem.enqueueShip(world, target, ship, false)
                        : world.logisticsSystem.queueBuildShip(world, target, ship);
            }
            case STATION_PACKAGE -> {
                BaseType station = Rules.findBase(root.itemId);
                if (station == null) yield false;
                yield HangarStore.canAfford(target.inventory, station.buildCost)
                        ? ProductionSystem.enqueuePackage(world, target, station, false)
                        : world.logisticsSystem.queueBasePackage(world, target, station);
            }
            case CRAFTABLE -> {
                CraftableItem item = CraftingRules.item(root.itemId);
                if (item == null) yield false;
                yield HangarStore.canAfford(target.inventory, item.requiredResources)
                        ? ProductionSystem.enqueueCraftable(world, target, item, false)
                        : world.logisticsSystem.queueCraftable(world, target, item);
            }
            case RESEARCH -> {
                ResearchTopic topic = ResearchRules.topic(root.itemId);
                if (topic == null) yield false;
                yield HangarStore.canAfford(target.inventory, topic.requiredResources)
                        ? ProductionSystem.enqueueResearch(world, target, topic, false)
                        : world.logisticsSystem.queueResearch(world, target, topic);
            }
        };
    }

    private static RecipeChoice chooseRecipe(World world, ProductionPlan plan, Material material) {
        List<CraftableItem> recipes = CraftingRules.recipesForOutput(material);
        if (recipes.isEmpty()) return null;
        Base destination = world.bases.get(plan.root.targetBaseId);
        for (CraftableItem item : recipes) {
            if (!item.unlockedFor(world, plan.playerId)) continue;
            Base station = selectStation(world, plan.playerId, item, destination);
            if (station != null) return new RecipeChoice(item, station);
        }
        return null;
    }

    private static Base selectStation(World world, String playerId, CraftableItem item, Base destination) {
        List<Base> candidates = new ArrayList<>();
        for (Base base : world.bases.values()) {
            if (!playerId.equals(base.playerId) || base.hp <= 0 || !item.canCraftAt(base.typeId)) continue;
            candidates.add(base);
        }
        candidates.sort(Comparator
                .comparing((Base base) -> !StationFuelRules.isOperational(base))
                .thenComparingInt(base -> base.productionQueue.size())
                .thenComparingDouble(base -> destination == null ? 0 : Calc.distance(base.x, base.y, destination.x, destination.y))
                .thenComparing(base -> base.id));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean networkCanCover(World world, String playerId, List<Cost> cost) {
        for (Cost need : cost) {
            if (networkAmount(world, playerId, need.material()) + EPSILON < need.amount()) return false;
        }
        return true;
    }

    private static double networkAmount(World world, String playerId, Material material) {
        double total = 0;
        for (Base base : world.bases.values()) {
            if (!playerId.equals(base.playerId) || base.hp <= 0) continue;
            total += base.inventory.getOrDefault(material, 0.0);
        }
        return total;
    }

    private static double futureOutput(World world, String playerId, Material material) {
        double total = 0;
        for (Base base : world.bases.values()) {
            if (!playerId.equals(base.playerId) || base.hp <= 0) continue;
            for (ProductionJob job : base.productionQueue) {
                if (job.kind != ProductionJobKind.CRAFTABLE) continue;
                CraftableItem item = CraftingRules.item(job.itemId);
                if (item != null && item.outputMaterial == material) total += item.outputAmount;
            }
        }
        return total;
    }

    private static void reportState(World world, ProductionPlan plan, boolean queuedSomething) {
        Analysis analysis = analyze(world, plan);
        String summary = analysis.summary();
        if (!summary.equals(plan.lastSummary)) {
            plan.lastSummary = summary;
            if (!analysis.shortages.isEmpty()) {
                String message = "You are missing " + formatShortages(analysis.shortages) + ".";
                world.status = message;
                GameNoticeCenter.publish(world, plan.playerId, NoticeCategory.SHORTAGE, message, true);
            } else if (!analysis.blocker.isBlank()) {
                world.status = analysis.blocker;
                GameNoticeCenter.publish(world, plan.playerId, NoticeCategory.WARNING, analysis.blocker, true);
            }
        } else if (queuedSomething) {
            world.status = "Auto-production is preparing " + plan.root.displayName + ".";
        }
    }

    private static Analysis analyze(World world, ProductionPlan plan) {
        EnumMap<Material, Double> ledger = new EnumMap<>(Material.class);
        for (Material material : Material.values()) {
            double amount = networkAmount(world, plan.playerId, material) + futureOutput(world, plan.playerId, material);
            if (amount > EPSILON) ledger.put(material, amount);
        }
        Analysis analysis = new Analysis();
        for (Cost need : plan.root.cost) {
            resolveRequirement(world, plan, need.material(), need.amount(), ledger, analysis, new HashSet<>());
        }
        return analysis;
    }

    private static void resolveRequirement(World world, ProductionPlan plan, Material material, double amount,
                                           EnumMap<Material, Double> ledger, Analysis analysis,
                                           Set<Material> visiting) {
        if (amount <= EPSILON) return;
        double available = ledger.getOrDefault(material, 0.0);
        double used = Math.min(available, amount);
        amount -= used;
        if (available - used <= EPSILON) ledger.remove(material);
        else ledger.put(material, available - used);
        if (amount <= EPSILON) return;

        if (!visiting.add(material)) {
            analysis.blocker = "Auto-production found a crafting dependency cycle involving " + material.label + ".";
            return;
        }
        try {
            List<CraftableItem> recipes = CraftingRules.recipesForOutput(material);
            if (material.raw || recipes.isEmpty()) {
                analysis.shortages.merge(material, amount, Double::sum);
                return;
            }
            RecipeChoice choice = chooseRecipe(world, plan, material);
            if (choice == null) {
                analysis.blocker = recipeBlocker(world, plan, material, recipes);
                return;
            }
            CraftableItem item = choice.item;
            int batches = Math.max(1, (int)Math.ceil(amount / Math.max(EPSILON, item.outputAmount)));
            double surplus = batches * item.outputAmount - amount;
            if (surplus > EPSILON) ledger.merge(material, surplus, Double::sum);
            for (Cost input : item.requiredResources) {
                resolveRequirement(world, plan, input.material(), input.amount() * batches,
                        ledger, analysis, visiting);
            }
        } finally {
            visiting.remove(material);
        }
    }

    private static String recipeBlocker(World world, ProductionPlan plan, Material material,
                                        List<CraftableItem> recipes) {
        for (CraftableItem item : recipes) {
            if (!item.unlockedFor(world, plan.playerId)) {
                return "Auto-production is blocked: " + item.name + " requires research: "
                        + item.missingResearchLabel(world, plan.playerId) + ".";
            }
        }
        return "Auto-production is blocked: no owned station in this system can manufacture "
                + material.label + ".";
    }

    private static String formatShortages(EnumMap<Material, Double> shortages) {
        List<Map.Entry<Material, Double>> entries = new ArrayList<>(shortages.entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().label));
        List<String> labels = new ArrayList<>();
        int shown = Math.min(4, entries.size());
        for (int i = 0; i < shown; i++) {
            Map.Entry<Material, Double> entry = entries.get(i);
            labels.add(formatAmount(entry.getValue()) + " " + entry.getKey().label);
        }
        if (entries.size() > shown) labels.add((entries.size() - shown) + " other materials");
        if (labels.size() == 1) return labels.get(0);
        return String.join(", ", labels.subList(0, labels.size() - 1)) + " and " + labels.get(labels.size() - 1);
    }

    private static String formatAmount(double amount) {
        double rounded = Math.rint(amount * 10.0) / 10.0;
        if (Math.abs(rounded - Math.rint(rounded)) < 0.001) return Long.toString(Math.round(rounded));
        return String.format(Locale.ROOT, "%.1f", rounded);
    }

    private enum EnsureResult { READY, WAITING, QUEUED, BLOCKED }

    private record RecipeChoice(CraftableItem item, Base station) { }

    private record PlannedAction(ProductionJobKind kind, String itemId, String displayName,
                                 String targetBaseId, List<Cost> cost) { }

    private static final class ProductionPlan {
        final String id;
        final String systemId;
        final String playerId;
        final PlannedAction root;
        String lastSummary = "";

        ProductionPlan(String id, String systemId, String playerId, PlannedAction root) {
            this.id = id;
            this.systemId = systemId == null ? "" : systemId;
            this.playerId = playerId;
            this.root = root;
        }
    }

    private static final class PlannerState {
        long nextPlanId = 1;
        double timer;
        final List<ProductionPlan> plans = new ArrayList<>();
    }

    private static final class Analysis {
        final EnumMap<Material, Double> shortages = new EnumMap<>(Material.class);
        String blocker = "";

        String summary() {
            StringBuilder out = new StringBuilder(blocker);
            for (Map.Entry<Material, Double> entry : shortages.entrySet()) {
                out.append('|').append(entry.getKey().name()).append('=').append(formatAmount(entry.getValue()));
            }
            return out.toString();
        }
    }
}
