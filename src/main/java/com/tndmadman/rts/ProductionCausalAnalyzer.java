package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only causal analysis for blocked production jobs.
 *
 * This class deliberately returns descriptors instead of executing commands.  The
 * caller may surface the suggested recovery actions, but every mutation still has
 * to travel through the existing authoritative production/research/logistics UI.
 * Expensive cross-system inspection is therefore performed only when a caller asks
 * for an analysis; it is not part of the simulation tick or rendering path.
 */
final class ProductionCausalAnalyzer {
    private static final double EPSILON = 0.05;
    private static final int MAX_DEPENDENCY_DEPTH = 16;

    enum CauseType {
        MISSING_INPUTS,
        NO_LOCAL_SOURCE,
        IN_TRANSIT,
        NO_ROUTE,
        NO_COURIER,
        BLOCKED_BY_POLICY,
        STATION_UNAVAILABLE,
        NEEDS_INTERMEDIATE,
        RESEARCH_LOCKED,
        RECIPE_CYCLE,
        BLOCKED_OTHER
    }

    enum ActionType {
        WAIT_FOR_TRANSIT,
        MOVE_STOCK,
        CREATE_ROUTE,
        ENQUEUE_INTERMEDIATE,
        OPEN_RESEARCH,
        CHOOSE_STATION,
        REVIEW_POLICY,
        REVIEW_JOB
    }

    record RecoveryAction(ActionType type, String label, Map<String, String> parameters) {
        RecoveryAction {
            if (type == null) type = ActionType.REVIEW_JOB;
            label = clean(label);
            parameters = immutableFacts(parameters);
        }
    }

    record Cause(CauseType type, String summary, Map<String, String> facts,
                 List<Cause> children, List<RecoveryAction> actions) {
        Cause {
            if (type == null) type = CauseType.BLOCKED_OTHER;
            summary = clean(summary);
            facts = immutableFacts(facts);
            children = children == null ? List.of() : List.copyOf(children);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    record Analysis(String jobId, String stationId, boolean blocked, String summary, List<Cause> causes) {
        Analysis {
            jobId = clean(jobId);
            stationId = clean(stationId);
            summary = clean(summary);
            causes = causes == null ? List.of() : List.copyOf(causes);
        }

        String renderText() {
            StringBuilder out = new StringBuilder();
            out.append(summary.isBlank() ? (blocked ? "Production is blocked." : "No blocking cause found.") : summary);
            if (!jobId.isBlank()) out.append("\nJob: ").append(jobId);
            if (!stationId.isBlank()) out.append("\nStation: ").append(stationId);
            for (Cause cause : causes) appendCause(out, cause, 0);
            return out.toString();
        }

        private static void appendCause(StringBuilder out, Cause cause, int depth) {
            out.append('\n').append("  ".repeat(Math.max(0, Math.min(depth, MAX_DEPENDENCY_DEPTH))));
            out.append("• ").append(cause.summary());
            for (Cause child : cause.children()) appendCause(out, child, depth + 1);
            for (RecoveryAction action : cause.actions()) {
                out.append('\n').append("  ".repeat(Math.max(0, Math.min(depth + 1, MAX_DEPENDENCY_DEPTH))));
                out.append("→ ").append(action.label());
            }
        }
    }

    private record SourceFacts(double sameSystemAvailable, double routedRemoteAvailable,
                               List<RemoteSource> unroutedRemoteSources) { }

    private record RemoteSource(String systemId, String stationId, double available) { }

    private ProductionCausalAnalyzer() { }

    static Analysis analyze(World world, Base target, ProductionJob job) {
        if (world == null || target == null || job == null) {
            return new Analysis(job == null ? "" : job.id, target == null ? "" : target.id,
                    true, "Production target is unavailable.",
                    List.of(cause(CauseType.STATION_UNAVAILABLE, "Production target is unavailable.", Map.of(),
                            List.of(), action(ActionType.CHOOSE_STATION, "Choose an available owned production station.", Map.of()))));
        }

        List<Cause> causes = new ArrayList<>();
        if (target.hp <= 0 || !target.playerId.equals(ownerOf(target))) {
            causes.add(cause(CauseType.STATION_UNAVAILABLE,
                    "Station " + target.id + " is unavailable.",
                    Map.of("stationId", target.id, "systemId", world.activeSystemId()), List.of(),
                    action(ActionType.CHOOSE_STATION, "Choose another compatible owned station.",
                            Map.of("stationId", target.id))));
        }

        addPolicyCause(world, target, job, causes);

        if (!StationFuelRules.isOperational(target)) {
            causes.add(cause(CauseType.STATION_UNAVAILABLE,
                    target.type().name + " " + target.id + " is offline.",
                    Map.of("stationId", target.id, "stationTypeId", target.typeId,
                            "reason", clean(job.blockedReason)), List.of(),
                    action(ActionType.REVIEW_JOB, "Restore the station's required operating supply, then retry the job.",
                            Map.of("stationId", target.id))));
        }

        List<Cost> cost = ProductionSystem.costFor(world, job);
        EnumMap<Material, Double> required = new EnumMap<>(Material.class);
        for (Cost entry : cost) {
            if (entry == null || entry.material() == null || entry.amount() <= EPSILON) continue;
            required.merge(entry.material(), entry.amount(), Double::sum);
        }

        Set<Material> recursion = new LinkedHashSet<>();
        for (Map.Entry<Material, Double> entry : required.entrySet()) {
            double have = Math.max(0, target.inventory.getOrDefault(entry.getKey(), 0.0));
            double deficit = Math.max(0, entry.getValue() - have);
            if (deficit <= EPSILON) continue;
            causes.add(analyzeMissingMaterial(world, target, entry.getKey(), entry.getValue(), have,
                    deficit, recursion, 0));
        }

        String blockedReason = clean(job.blockedReason);
        boolean explicitlyBlocked = !blockedReason.isBlank();
        if (causes.isEmpty() && explicitlyBlocked) {
            causes.add(cause(CauseType.BLOCKED_OTHER,
                    "Job reports: " + blockedReason,
                    Map.of("jobId", job.id, "reason", blockedReason), List.of(),
                    action(ActionType.REVIEW_JOB, "Review the job and station state before retrying.",
                            Map.of("jobId", job.id, "stationId", target.id))));
        }

        boolean blocked = explicitlyBlocked || !causes.isEmpty();
        String summary = blocked
                ? ProductionSystem.displayName(world, job) + " is blocked by " + causes.size()
                + " causal issue" + (causes.size() == 1 ? "." : "s.")
                : ProductionSystem.displayName(world, job) + " has no current blocking cause.";
        return new Analysis(job.id, target.id, blocked, summary, List.copyOf(causes));
    }

    private static Cause analyzeMissingMaterial(World world, Base target, Material material,
                                                double needed, double have, double deficit,
                                                Set<Material> recursion, int depth) {
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("material", material.name());
        facts.put("needed", number(needed));
        facts.put("have", number(have));
        facts.put("deficit", number(deficit));
        facts.put("stationId", target.id);
        facts.put("systemId", world.activeSystemId());

        List<Cause> children = new ArrayList<>();
        List<RecoveryAction> actions = new ArrayList<>();

        double inTransit = inTransitToActiveStation(world, target, material);
        if (inTransit > EPSILON) {
            children.add(cause(CauseType.IN_TRANSIT,
                    number(inTransit) + " " + material.label + " is already in transit to this station.",
                    Map.of("material", material.name(), "amount", number(inTransit), "eta", "pending",
                            "stationId", target.id), List.of(),
                    action(ActionType.WAIT_FOR_TRANSIT, "Wait for the current logistics delivery to arrive.",
                            Map.of("material", material.name(), "stationId", target.id))));
        }

        SourceFacts sources = sourceFacts(world, target, material);
        if (sources.sameSystemAvailable > EPSILON) {
            actions.add(action(ActionType.MOVE_STOCK,
                    number(sources.sameSystemAvailable) + " " + material.label
                            + " is available at other owned stations in this system; let logistics move it here.",
                    Map.of("material", material.name(), "destSystemId", world.activeSystemId(),
                            "destStationId", target.id)));
        } else if (sources.routedRemoteAvailable > EPSILON) {
            children.add(cause(CauseType.NO_LOCAL_SOURCE,
                    "No same-system source has " + material.label + ", but routed remote stock exists.",
                    Map.of("material", material.name(), "routedRemoteAvailable", number(sources.routedRemoteAvailable),
                            "destSystemId", world.activeSystemId()), List.of(),
                    action(ActionType.MOVE_STOCK, "Allow production logistics to source the routed remote stock.",
                            Map.of("material", material.name(), "destStationId", target.id))));
        } else if (!sources.unroutedRemoteSources.isEmpty()) {
            children.add(cause(CauseType.NO_LOCAL_SOURCE,
                    "No owned same-system station can currently supply " + material.label + ".",
                    Map.of("material", material.name(), "destSystemId", world.activeSystemId()), List.of(), List.of()));
            for (RemoteSource remote : sources.unroutedRemoteSources) {
                children.add(cause(CauseType.NO_ROUTE,
                        number(remote.available) + " " + material.label + " exists in " + remote.systemId
                                + " but has no logistics route to " + world.activeSystemId() + ".",
                        Map.of("material", material.name(), "sourceSystemId", remote.systemId,
                                "sourceStationId", remote.stationId, "destSystemId", world.activeSystemId(),
                                "available", number(remote.available)), List.of(),
                        action(ActionType.CREATE_ROUTE,
                                "Create or repair a logistics route from " + remote.systemId + " to " + world.activeSystemId() + ".",
                                Map.of("sourceSystemId", remote.systemId, "destSystemId", world.activeSystemId()))));
            }
        } else {
            children.add(cause(CauseType.NO_LOCAL_SOURCE,
                    "No known owned station currently has spare " + material.label + ".",
                    Map.of("material", material.name(), "destSystemId", world.activeSystemId()), List.of(), List.of()));
        }

        if (Rules.findShip(LogisticsSystem.SHUTTLE_TYPE) == null) {
            children.add(cause(CauseType.NO_COURIER,
                    "No logistics courier hull is configured for player " + target.playerId + ".",
                    Map.of("playerId", target.playerId, "courierType", LogisticsSystem.SHUTTLE_TYPE), List.of(),
                    action(ActionType.REVIEW_JOB, "Restore the logistics courier configuration before dispatching materials.",
                            Map.of("playerId", target.playerId))));
        }

        if (depth < MAX_DEPENDENCY_DEPTH) {
            addIntermediateCause(world, target, material, deficit, recursion, depth, children, actions);
        }

        return cause(CauseType.MISSING_INPUTS,
                "Missing " + number(deficit) + " " + material.label + " (need " + number(needed)
                        + ", have " + number(have) + ").",
                facts, children, actions);
    }

    private static void addIntermediateCause(World world, Base target, Material material, double deficit,
                                             Set<Material> recursion, int depth,
                                             List<Cause> children, List<RecoveryAction> actions) {
        List<CraftableItem> recipes = new ArrayList<>(CraftingRules.recipesForOutput(material));
        if (recipes.isEmpty()) return;
        if (!recursion.add(material)) {
            children.add(cause(CauseType.RECIPE_CYCLE,
                    "Recipe dependency cycle detected while resolving " + material.label + ".",
                    Map.of("material", material.name()), List.of(), List.of()));
            return;
        }
        try {
            recipes.sort(Comparator
                    .comparing((CraftableItem item) -> !item.unlockedFor(world, target.playerId))
                    .thenComparing(item -> !hasCompatibleOwnedStation(world, target.playerId, item))
                    .thenComparing(item -> item.id));
            CraftableItem recipe = recipes.get(0);
            double batches = Math.max(1, Math.ceil(deficit / Math.max(EPSILON, recipe.outputAmount)));
            List<Cause> dependencies = new ArrayList<>();

            if (!recipe.unlockedFor(world, target.playerId)) {
                String missing = recipe.missingResearchLabel(world, target.playerId);
                dependencies.add(cause(CauseType.RESEARCH_LOCKED,
                        recipe.name + " requires research: " + missing + ".",
                        Map.of("recipeId", recipe.id, "research", missing, "material", material.name()), List.of(),
                        action(ActionType.OPEN_RESEARCH, "Research " + missing + " before producing this intermediate.",
                                Map.of("recipeId", recipe.id, "research", missing))));
            }
            if (!hasCompatibleOwnedStation(world, target.playerId, recipe)) {
                dependencies.add(cause(CauseType.STATION_UNAVAILABLE,
                        "No known owned station can craft " + recipe.name + ".",
                        Map.of("recipeId", recipe.id, "stationTypes", String.join(",", recipe.stationTypes)), List.of(),
                        action(ActionType.CHOOSE_STATION, "Build or use a compatible station for " + recipe.name + ".",
                                Map.of("recipeId", recipe.id))));
            }

            for (Cost input : recipe.requiredResources) {
                if (input == null || input.material() == null || input.amount() <= EPSILON) continue;
                double inputNeeded = input.amount() * batches;
                double networkHave = knownOwnedInventory(world, target.playerId, input.material());
                double inputDeficit = Math.max(0, inputNeeded - networkHave);
                if (inputDeficit <= EPSILON) continue;
                if (recursion.contains(input.material())) {
                    dependencies.add(cause(CauseType.RECIPE_CYCLE,
                            "Recipe dependency cycle detected at " + input.material().label + ".",
                            Map.of("material", input.material().name(), "recipeId", recipe.id), List.of(), List.of()));
                } else {
                    dependencies.add(analyzeMissingMaterial(world, target, input.material(), inputNeeded,
                            Math.min(inputNeeded, networkHave), inputDeficit, recursion, depth + 1));
                }
            }

            children.add(cause(CauseType.NEEDS_INTERMEDIATE,
                    material.label + " can be produced as " + recipe.name + " (about " + number(batches)
                            + " batch" + (Math.abs(batches - 1) < 0.001 ? "" : "es") + ").",
                    Map.of("material", material.name(), "recipeId", recipe.id, "batches", number(batches),
                            "outputPerBatch", number(recipe.outputAmount)), dependencies,
                    action(ActionType.ENQUEUE_INTERMEDIATE, "Queue " + recipe.name + " explicitly when its dependencies are ready.",
                            Map.of("recipeId", recipe.id, "material", material.name()))));
            actions.add(action(ActionType.ENQUEUE_INTERMEDIATE,
                    "Produce the missing intermediate " + recipe.name + ".",
                    Map.of("recipeId", recipe.id, "material", material.name())));
        } finally {
            recursion.remove(material);
        }
    }

    private static void addPolicyCause(World world, Base target, ProductionJob job, List<Cause> causes) {
        for (ProductionPolicySystem.PolicyView policy : ProductionPolicySystem.viewsForBase(world, target)) {
            if (!policy.jobIds().contains(job.id)) continue;
            ProductionPolicySystem.PolicyStatus status = policy.status();
            boolean policyBlocked = status == ProductionPolicySystem.PolicyStatus.WAITING_FOR_RESOURCES
                    || status == ProductionPolicySystem.PolicyStatus.BLOCKED_RESEARCH
                    || status == ProductionPolicySystem.PolicyStatus.NO_COMPATIBLE_STATION
                    || status == ProductionPolicySystem.PolicyStatus.RESERVE_PROTECTED
                    || status == ProductionPolicySystem.PolicyStatus.PAUSED
                    || status == ProductionPolicySystem.PolicyStatus.ORPHANED;
            if (!policyBlocked) return;
            causes.add(cause(CauseType.BLOCKED_BY_POLICY,
                    "Production policy " + policy.id() + " is " + status.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                            + (policy.reason().isBlank() ? "." : ": " + policy.reason()),
                    Map.of("policyId", policy.id(), "status", status.name(), "stationId", target.id,
                            "details", clean(policy.reason())), List.of(),
                    action(ActionType.REVIEW_POLICY, "Review policy " + policy.id() + " before changing or reassigning it.",
                            Map.of("policyId", policy.id(), "stationId", target.id))));
            return;
        }
    }

    private static SourceFacts sourceFacts(World world, Base target, Material material) {
        double sameSystem = 0;
        for (Base source : world.bases.values()) {
            if (source == null || source == target || source.hp <= 0 || !target.playerId.equals(source.playerId)) continue;
            sameSystem += Math.max(0, source.inventory.getOrDefault(material, 0.0));
        }

        double routedRemote = 0;
        List<RemoteSource> unrouted = new ArrayList<>();
        String destination = world.activeSystemId();
        for (WorldSystemState state : world.policySystemStates()) {
            if (state == null || state.id == null || state.id.equals(destination)) continue;
            List<String> path = LogisticsRouteSystem.pathForTest(world, target.playerId, state.id, destination);
            for (Base source : state.bases.values()) {
                if (source == null || source.hp <= 0 || !target.playerId.equals(source.playerId)) continue;
                double available = Math.max(0, source.inventory.getOrDefault(material, 0.0));
                if (available <= EPSILON) continue;
                if (path.size() >= 2) routedRemote += available;
                else unrouted.add(new RemoteSource(state.id, source.id, available));
            }
        }
        unrouted.sort(Comparator.comparing(RemoteSource::systemId).thenComparing(RemoteSource::stationId));
        return new SourceFacts(sameSystem, routedRemote, List.copyOf(unrouted));
    }

    private static double inTransitToActiveStation(World world, Base target, Material material) {
        double total = 0;
        for (Unit unit : world.units.values()) {
            if (unit == null || !LogisticsSystem.SHUTTLE_TYPE.equals(unit.shipTypeId)) continue;
            if (!target.playerId.equals(unit.playerId) || !target.id.equals(unit.logisticsTargetBaseId)) continue;
            total += Math.max(0, unit.inventory.getOrDefault(material, 0.0));
        }
        return total;
    }

    private static boolean hasCompatibleOwnedStation(World world, String playerId, CraftableItem item) {
        if (world == null || item == null) return false;
        for (Base base : world.bases.values()) {
            if (base != null && base.hp > 0 && playerId.equals(base.playerId) && item.canCraftAt(base.typeId)) return true;
        }
        String active = world.activeSystemId();
        for (WorldSystemState state : world.policySystemStates()) {
            if (state == null || state.id == null || state.id.equals(active)) continue;
            for (Base base : state.bases.values()) {
                if (base != null && base.hp > 0 && playerId.equals(base.playerId) && item.canCraftAt(base.typeId)) return true;
            }
        }
        return false;
    }

    private static double knownOwnedInventory(World world, String playerId, Material material) {
        double total = 0;
        for (Base base : world.bases.values()) {
            if (base != null && base.hp > 0 && playerId.equals(base.playerId)) {
                total += Math.max(0, base.inventory.getOrDefault(material, 0.0));
            }
        }
        String active = world.activeSystemId();
        for (WorldSystemState state : world.policySystemStates()) {
            if (state == null || state.id == null || state.id.equals(active)) continue;
            for (Base base : state.bases.values()) {
                if (base != null && base.hp > 0 && playerId.equals(base.playerId)) {
                    total += Math.max(0, base.inventory.getOrDefault(material, 0.0));
                }
            }
        }
        return total;
    }

    private static Cause cause(CauseType type, String summary, Map<String, String> facts,
                               List<Cause> children, RecoveryAction action) {
        return new Cause(type, summary, facts, children, action == null ? List.of() : List.of(action));
    }

    private static Cause cause(CauseType type, String summary, Map<String, String> facts,
                               List<Cause> children, List<RecoveryAction> actions) {
        return new Cause(type, summary, facts, children, actions);
    }

    private static RecoveryAction action(ActionType type, String label, Map<String, String> facts) {
        return new RecoveryAction(type, label, facts);
    }

    private static Map<String, String> immutableFacts(Map<String, String> facts) {
        if (facts == null || facts.isEmpty()) return Map.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : facts.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            out.put(entry.getKey(), clean(entry.getValue()));
        }
        return Map.copyOf(out);
    }

    private static String ownerOf(Base base) {
        return base == null ? "" : clean(base.playerId);
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < 0.000001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
