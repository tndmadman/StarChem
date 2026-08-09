package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
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
        return queueShip(world, target, ship, ship == null ? null : WeaponRules.defaultLoadout(ship.id));
    }

    static boolean queueShip(World world, Base target, ShipType ship, ShipLoadoutDefinition loadout) {
        if (ship == null || loadout == null) return false;
        return queue(world, target, ProductionJobKind.SHIP, ship.id,
                ship.name + " - " + loadout.displayName(), WeaponRules.buildCost(ship, loadout),
                ship.buildTimeSeconds, loadout.id());
    }

    static boolean queuePackage(World world, Base target, BaseType station) {
        if (station == null) return false;
        return queue(world, target, ProductionJobKind.STATION_PACKAGE, station.id,
                station.name + " package", station.buildCost, station.buildTimeSeconds, "");
    }

    static boolean queueCraftable(World world, Base target, CraftableItem item) {
        if (item == null) return false;
        return queue(world, target, ProductionJobKind.CRAFTABLE, item.id, item.name,
                item.requiredResources, item.timeSeconds, "");
    }

    static boolean queueResearch(World world, Base target, ResearchTopic topic) {
        if (topic == null) return false;
        return queue(world, target, ProductionJobKind.RESEARCH, topic.id,
                topic.name + " research", topic.requiredResources, topic.timeSeconds, "");
    }

    static synchronized void update(World world, double dt) {
        if (world == null || dt < 0) return;
        PlannerState state = STATES.get(world);
        if (state == null || state.plans.isEmpty()) return;
        state.timer += Math.min(1.0, dt);
        if (state.timer < UPDATE_INTERVAL) return;
        state.timer = 0;

        String activeSystem = world.activeSystemId();
        Map<String, PlanningLedger> ledgers = new HashMap<>();
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

            ProductionJob rootJob = ProductionSystem.findJob(target, plan.root.productionJobId);
            if (rootJob == null) {
                iterator.remove();
                continue;
            }
            if (!ProductionSystem.waitingForResources(rootJob)) {
                iterator.remove();
                continue;
            }

            PlanningLedger ledger = ledgers.computeIfAbsent(plan.playerId,
                    playerId -> PlanningLedger.capture(world, playerId));
            if (networkCanCover(world, plan.playerId, plan.root.cost)
                    && activateRoot(world, target, plan.root, rootJob)) {
                ledger.claimAll(plan.root.cost);
                world.status = "Auto-production prerequisites ready. Queued " + plan.root.displayName + ".";
                GameNoticeCenter.publish(world, plan.playerId, NoticeCategory.PRODUCTION,
                        "All prerequisites are available. " + plan.root.displayName + " has been queued.", true);
                iterator.remove();
                continue;
            }

            Analysis analysis = analyze(world, plan, ledger.copyAmounts());
            List<Cost> deficits = ledger.claimAll(plan.root.cost);
            boolean queuedSomething = false;
            Set<Material> visiting = new HashSet<>();
            for (Cost deficit : deficits) {
                EnsureResult result = ensureMissingMaterial(world, plan, deficit.material(), deficit.amount(),
                        visiting, ledger);
                if (result == EnsureResult.QUEUED) {
                    queuedSomething = true;
                    break;
                }
            }
            reportState(world, plan, queuedSomething, analysis);
        }
        if (state.plans.isEmpty()) STATES.remove(world);
    }

    static synchronized int planCount(World world) {
        PlannerState state = STATES.get(world);
        return state == null ? 0 : state.plans.size();
    }

    static synchronized Map<String,Object> capture(World world) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("productionPolicies", ProductionPolicySystem.capture(world));
        if (world != null) out.put("logisticsSystem", world.logisticsSystem.capture());
        PlannerState state = STATES.get(world);
        if (state == null) return out;
        out.put("nextPlanId", state.nextPlanId);
        out.put("timer", state.timer);
        List<Object> plans = new ArrayList<>();
        for (ProductionPlan plan : state.plans) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", plan.id);
            row.put("systemId", plan.systemId);
            row.put("playerId", plan.playerId);
            row.put("lastSummary", plan.lastSummary);
            row.put("root", captureAction(plan.root));
            plans.add(row);
        }
        out.put("plans", plans);
        return out;
    }

    static synchronized void restore(World world, Object saved) {
        if (world == null) return;
        Map<String,Object> data = ServerSaveStore.object(saved);
        PlannerState state = new PlannerState();
        state.nextPlanId = Math.max(1, ServerSaveStore.longValue(data, "nextPlanId", 1));
        state.timer = Math.max(0, ServerSaveStore.doubleValue(data, "timer", 0));
        for (Object item : ServerSaveStore.list(data.get("plans"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            PlannedAction root = restoreAction(row.get("root"));
            if (root == null) continue;
            ProductionPlan plan = new ProductionPlan(
                    ServerSaveStore.string(row, "id", ""),
                    ServerSaveStore.string(row, "systemId", ""),
                    ServerSaveStore.string(row, "playerId", ""),
                    root);
            plan.lastSummary = ServerSaveStore.string(row, "lastSummary", "");
            state.plans.add(plan);
        }
        if (state.plans.isEmpty()) STATES.remove(world);
        else STATES.put(world, state);
        world.logisticsSystem.restore(world, data.get("logisticsSystem"));
        ProductionPolicySystem.restore(world, data.get("productionPolicies"));
    }

    private static boolean queue(World world, Base target, ProductionJobKind kind, String itemId,
                                 String displayName, List<Cost> cost, double duration, String loadoutId) {
        if (world == null || target == null || kind == null || itemId == null || itemId.isBlank()
                || cost == null || cost.isEmpty()) return false;
        synchronized (ProductionPlanner.class) {
            PlannerState state = STATES.computeIfAbsent(world, ignored -> new PlannerState());
            if (state.plans.size() >= MAX_PLANS_PER_WORLD) {
                world.status = "Too many active auto-production plans.";
                return false;
            }

            ProductionJob rootJob = ProductionSystem.enqueueWaiting(world, target, kind, itemId,
                    displayName, duration, loadoutId, "");
            if (rootJob == null) return false;

            String id = "AP" + state.nextPlanId++;
            PlannedAction root = new PlannedAction(kind, itemId, loadoutId, displayName, target.id,
                    rootJob.id, List.copyOf(cost));
            ProductionPlan plan = new ProductionPlan(id, world.activeSystemId(), target.playerId, root);
            state.plans.add(plan);
            world.status = "Created auto-production plan for " + displayName + ".";
            GameNoticeCenter.publish(world, target.playerId, NoticeCategory.PRODUCTION,
                    "Auto-production plan created for " + displayName + ".", false);
            reportState(world, plan, false,
                    analyze(world, plan, PlanningLedger.capture(world, target.playerId).copyAmounts()));
        }
        return true;
    }

    private static boolean activateRoot(World world, Base target, PlannedAction root, ProductionJob rootJob) {
        if (HangarStore.canAfford(target.inventory, root.cost)) {
            return ProductionSystem.fundWaitingJob(world, target, rootJob.id);
        }

        int originalIndex = target.productionQueue.indexOf(rootJob);
        if (originalIndex < 0) return false;
        target.productionQueue.remove(originalIndex);

        boolean queued = switch (root.kind) {
            case SHIP -> {
                ShipType ship = Rules.findShip(root.itemId);
                ShipLoadoutDefinition loadout = WeaponRules.resolveForHull(root.itemId, root.loadoutId);
                yield ship != null && loadout != null && world.logisticsSystem.queueBuildShip(world, target, ship, loadout);
            }
            case STATION_PACKAGE -> {
                BaseType station = Rules.findBase(root.itemId);
                yield station != null && world.logisticsSystem.queueBasePackage(world, target, station);
            }
            case CRAFTABLE -> {
                CraftableItem item = CraftingRules.item(root.itemId);
                yield item != null && world.logisticsSystem.queueCraftable(world, target, item);
            }
            case RESEARCH -> {
                ResearchTopic topic = ResearchRules.topic(root.itemId);
                yield topic != null && world.logisticsSystem.queueResearch(world, target, topic);
            }
            case REFIT -> false;
        };

        if (!queued) {
            target.productionQueue.add(Math.min(originalIndex, target.productionQueue.size()), rootJob);
            return false;
        }

        ProductionJob replacement = target.productionQueue.remove(target.productionQueue.size() - 1);
        target.productionQueue.add(Math.min(originalIndex, target.productionQueue.size()), replacement);
        ProductionPolicySystem.transferJob(world, target, rootJob.id, replacement.id);
        return true;
    }

    private static EnsureResult ensureMissingMaterial(World world, ProductionPlan plan, Material material,
                                                       double missingAmount, Set<Material> visiting,
                                                       PlanningLedger ledger) {
        if (missingAmount <= EPSILON) return EnsureResult.READY;
        if (!visiting.add(material)) return EnsureResult.BLOCKED;
        try {
            RecipeChoice choice = chooseRecipe(world, plan, material, missingAmount,
                    ledger.copyAmounts(), visiting);
            if (choice == null) return EnsureResult.BLOCKED;
            CraftableItem recipe = choice.item;

            for (Cost input : recipe.requiredResources) {
                double missingInput = ledger.claim(input.material(), input.amount());
                if (missingInput <= EPSILON) continue;
                EnsureResult inputResult = ensureMissingMaterial(world, plan, input.material(), missingInput,
                        visiting, ledger);
                if (inputResult != EnsureResult.READY) return inputResult;
            }

            if (!networkCanCover(world, plan.playerId, recipe.requiredResources)) return EnsureResult.WAITING;
            if (!enqueueCraftableAt(world, choice.station, recipe)) return EnsureResult.WAITING;
            ledger.add(recipe.outputMaterial, recipe.outputAmount);
            ledger.claim(recipe.outputMaterial, Math.min(missingAmount, recipe.outputAmount));
            GameNoticeCenter.publish(world, plan.playerId, NoticeCategory.PRODUCTION,
                    "Auto-queued prerequisite: " + recipe.name + " at " + choice.station.type().name + ".", false);
            return EnsureResult.QUEUED;
        } finally {
            visiting.remove(material);
        }
    }

    private static boolean enqueueCraftableAt(World world, Base station, CraftableItem item) {
        if (HangarStore.canAfford(station.inventory, item.requiredResources)) {
            return ProductionSystem.enqueueCraftable(world, station, item, false);
        }
        return world.logisticsSystem.queueCraftable(world, station, item);
    }

    private static RecipeChoice chooseRecipe(World world, ProductionPlan plan, Material material,
                                             double amount, EnumMap<Material, Double> ledger,
                                             Set<Material> visiting) {
        List<CraftableItem> recipes = CraftingRules.recipesForOutput(material);
        if (recipes.isEmpty()) return null;
        Base destination = world.bases.get(plan.root.targetBaseId);
        RecipeEvaluation best = null;
        for (int order = 0; order < recipes.size(); order++) {
            CraftableItem item = recipes.get(order);
            if (!item.unlockedFor(world, plan.playerId)) continue;
            Base station = selectStation(world, plan.playerId, item, destination);
            if (station == null) continue;

            int batches = Math.max(1, (int)Math.ceil(amount / Math.max(EPSILON, item.outputAmount)));
            List<Cost> batchCost = multiplyCosts(item.requiredResources, batches);
            EnumMap<Material, Double> candidateLedger = new EnumMap<>(ledger);
            Analysis candidateAnalysis = new Analysis();
            Set<Material> candidateVisiting = new HashSet<>(visiting);
            for (Cost input : batchCost) {
                resolveRequirement(world, plan, input.material(), input.amount(),
                        candidateLedger, candidateAnalysis, candidateVisiting);
            }
            boolean unblocked = candidateAnalysis.blocker.isBlank();
            boolean fullyAchievable = unblocked && candidateAnalysis.shortages.isEmpty();
            RecipeEvaluation candidate = new RecipeEvaluation(
                    new RecipeChoice(item, station),
                    fullyAchievable,
                    ledgerCanCover(ledger, item.requiredResources),
                    unblocked,
                    candidateAnalysis.shortages.size(),
                    shortageAmount(candidateAnalysis),
                    item.timeSeconds * batches,
                    order);
            if (best == null || candidate.compareTo(best) < 0) best = candidate;
        }
        return best == null ? null : best.choice;
    }

    private static List<Cost> multiplyCosts(List<Cost> costs, int batches) {
        if (batches <= 1) return costs;
        List<Cost> multiplied = new ArrayList<>(costs.size());
        for (Cost cost : costs) {
            multiplied.add(new Cost(cost.material(), cost.amount() * batches));
        }
        return List.copyOf(multiplied);
    }

    private static boolean ledgerCanCover(EnumMap<Material, Double> ledger, List<Cost> cost) {
        EnumMap<Material, Double> remaining = new EnumMap<>(ledger);
        for (Cost need : cost) {
            double available = remaining.getOrDefault(need.material(), 0.0);
            if (available + EPSILON < need.amount()) return false;
            double next = available - need.amount();
            if (next <= EPSILON) remaining.remove(need.material());
            else remaining.put(need.material(), next);
        }
        return true;
    }

    private static double shortageAmount(Analysis analysis) {
        double total = 0;
        for (double amount : analysis.shortages.values()) total += amount;
        return total;
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

    private static void reportState(World world, ProductionPlan plan, boolean queuedSomething, Analysis analysis) {
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

    private static Analysis analyze(World world, ProductionPlan plan, EnumMap<Material, Double> ledger) {
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
            RecipeChoice choice = chooseRecipe(world, plan, material, amount, ledger, visiting);
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

    private record RecipeEvaluation(RecipeChoice choice, boolean fullyAchievable,
                                    boolean immediatelyFundable, boolean unblocked, int shortageKinds,
                                    double shortageAmount, double productionSeconds, int order)
            implements Comparable<RecipeEvaluation> {
        @Override
        public int compareTo(RecipeEvaluation other) {
            int result = Boolean.compare(other.fullyAchievable, fullyAchievable);
            if (result != 0) return result;
            result = Boolean.compare(other.immediatelyFundable, immediatelyFundable);
            if (result != 0) return result;
            result = Boolean.compare(other.unblocked, unblocked);
            if (result != 0) return result;
            result = Integer.compare(shortageKinds, other.shortageKinds);
            if (result != 0) return result;
            result = Double.compare(shortageAmount, other.shortageAmount);
            if (result != 0) return result;
            result = Double.compare(productionSeconds, other.productionSeconds);
            if (result != 0) return result;
            result = Integer.compare(order, other.order);
            if (result != 0) return result;
            result = choice.item.id.compareTo(other.choice.item.id);
            if (result != 0) return result;
            return choice.station.id.compareTo(other.choice.station.id);
        }
    }

    private record PlannedAction(ProductionJobKind kind, String itemId, String loadoutId, String displayName,
                                 String targetBaseId, String productionJobId, List<Cost> cost) { }

    private static Map<String,Object> captureAction(PlannedAction action) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (action == null) return out;
        out.put("kind", action.kind().name());
        out.put("itemId", action.itemId());
        out.put("loadoutId", action.loadoutId());
        out.put("displayName", action.displayName());
        out.put("targetBaseId", action.targetBaseId());
        out.put("productionJobId", action.productionJobId());
        List<Object> cost = new ArrayList<>();
        for (Cost item : action.cost()) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("material", item.material().name());
            row.put("amount", item.amount());
            cost.add(row);
        }
        out.put("cost", cost);
        return out;
    }

    private static PlannedAction restoreAction(Object saved) {
        Map<String,Object> data = ServerSaveStore.object(saved);
        ProductionJobKind kind = ServerSaveStore.enumValue(ProductionJobKind.class, data.get("kind"), null);
        String itemId = ServerSaveStore.string(data, "itemId", "");
        String targetBaseId = ServerSaveStore.string(data, "targetBaseId", "");
        String productionJobId = ServerSaveStore.string(data, "productionJobId", "");
        if (kind == null || itemId.isBlank() || targetBaseId.isBlank() || productionJobId.isBlank()) return null;
        List<Cost> cost = new ArrayList<>();
        for (Object item : ServerSaveStore.list(data.get("cost"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            Material material = ServerSaveStore.enumValue(Material.class, row.get("material"), null);
            double amount = ServerSaveStore.doubleValue(row, "amount", 0);
            if (material != null && amount > EPSILON) cost.add(new Cost(material, amount));
        }
        String loadoutId = ServerSaveStore.string(data, "loadoutId",
                kind == ProductionJobKind.SHIP ? WeaponRules.defaultLoadoutId(itemId) : "");
        return new PlannedAction(kind, itemId, loadoutId,
                ServerSaveStore.string(data, "displayName", itemId),
                targetBaseId, productionJobId, List.copyOf(cost));
    }

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

    private static final class PlanningLedger {
        final EnumMap<Material, Double> amounts = new EnumMap<>(Material.class);

        static PlanningLedger capture(World world, String playerId) {
            PlanningLedger ledger = new PlanningLedger();
            for (Material material : Material.values()) {
                double amount = networkAmount(world, playerId, material) + futureOutput(world, playerId, material);
                if (amount > EPSILON) ledger.amounts.put(material, amount);
            }
            return ledger;
        }

        double claim(Material material, double amount) {
            if (material == null || amount <= EPSILON) return 0;
            double available = amounts.getOrDefault(material, 0.0);
            double used = Math.min(available, amount);
            double remaining = available - used;
            if (remaining <= EPSILON) amounts.remove(material);
            else amounts.put(material, remaining);
            return Math.max(0, amount - used);
        }

        List<Cost> claimAll(List<Cost> cost) {
            List<Cost> deficits = new ArrayList<>();
            for (Cost need : cost) {
                double missing = claim(need.material(), need.amount());
                if (missing > EPSILON) deficits.add(new Cost(need.material(), missing));
            }
            return deficits;
        }

        void add(Material material, double amount) {
            if (material == null || amount <= EPSILON) return;
            amounts.merge(material, amount, Double::sum);
        }

        EnumMap<Material, Double> copyAmounts() {
            return new EnumMap<>(amounts);
        }
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
