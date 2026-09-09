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
 * This class deliberately returns descriptors instead of executing commands. The
 * caller may surface suggested recovery actions, but every mutation still has to
 * travel through the existing authoritative production/research/logistics UI.
 * Expensive cross-system inspection is performed only on demand and is bounded.
 */
final class ProductionCausalAnalyzer {
    private static final double EPSILON = 0.05;
    private static final int MAX_DEPENDENCY_DEPTH = 16;

    enum CauseType {
        MISSING_INPUTS,
        NO_LOCAL_SOURCE,
        IN_TRANSIT,
        NO_ROUTE,
        INACCESSIBLE_TOPOLOGY,
        NO_ELIGIBLE_TRANSPORT,
        NO_COURIER,
        BLOCKED_BY_POLICY,
        STATION_UNAVAILABLE,
        QUEUE_WAIT,
        NEEDS_INTERMEDIATE,
        RESEARCH_LOCKED,
        RECIPE_CYCLE,
        DEPTH_LIMIT,
        UNAUTHORIZED,
        STALE_STATE,
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

    private record SourceFacts(double sameSystemAvailable,
                               double routedRemoteAvailable,
                               List<RemoteSource> reachableUnroutedSources,
                               List<RemoteSource> inaccessibleRemoteSources,
                               boolean routeNeedsTransport) { }

    private record RemoteSource(String systemId, String stationId, double available, int hops) { }

    private record TransportFacts(int eligibleCount, double freeCapacity) { }

    private ProductionCausalAnalyzer() { }

    /** Trusted/server-side analysis. Player-facing callers should use analyzeForPlayer. */
    static Analysis analyze(World world, Base target, ProductionJob job) {
        return analyzeInternal(world, target, job, null);
    }

    /**
     * Owner-authorized player-facing analysis. Stale or foreign objects are redacted
     * before any inventory, policy, or logistics inspection occurs.
     */
    static Analysis analyzeForPlayer(World world, Base target, ProductionJob job, String viewerId) {
        String viewer = clean(viewerId);
        if (target != null && (viewer.isBlank() || !viewer.equals(clean(target.playerId)))) {
            return redacted(job, target, CauseType.UNAUTHORIZED,
                    "Production diagnostics are only available to the owning player.");
        }
        if (world != null && target != null) {
            Base live = world.bases.get(target.id);
            ProductionJob liveJob = live == null || job == null ? null : ProductionSystem.findJob(live, job.id);
            if (live != target || liveJob != job) {
                return redacted(job, target, CauseType.STALE_STATE,
                        "Production state changed before diagnostics were opened; refresh the queue and try again.");
            }
        }
        return analyzeInternal(world, target, job, null);
    }

    /** Package-private synthetic recipe seam used only by the issue regression validator. */
    static Cause analyzeMaterialForTest(World world, Base target, Material material, double needed,
                                        Map<Material, List<CraftableItem>> recipes) {
        double have = target == null || material == null ? 0
                : Math.max(0, target.inventory.getOrDefault(material, 0.0));
        double deficit = Math.max(0, needed - have);
        return analyzeMissingMaterial(world, target, material, needed, have, deficit,
                new LinkedHashSet<>(), 0, recipes == null ? Map.of() : recipes);
    }

    static int dependencyDepthLimitForTest() { return MAX_DEPENDENCY_DEPTH; }

    private static Analysis analyzeInternal(World world, Base target, ProductionJob job,
                                            Map<Material, List<CraftableItem>> recipeOverrides) {
        if (world == null || target == null || job == null) {
            return new Analysis(job == null ? "" : job.id, target == null ? "" : target.id,
                    true, "Production target is unavailable.",
                    List.of(cause(CauseType.STATION_UNAVAILABLE, "Production target is unavailable.", Map.of(),
                            List.of(), action(ActionType.CHOOSE_STATION,
                                    "Choose an available owned production station.", Map.of()))));
        }

        List<Cause> causes = new ArrayList<>();
        if (target.hp <= 0 || clean(target.playerId).isBlank()) {
            causes.add(cause(CauseType.STATION_UNAVAILABLE,
                    "Station " + target.id + " is unavailable.",
                    Map.of("stationId", target.id, "systemId", world.activeSystemId()), List.of(),
                    action(ActionType.CHOOSE_STATION, "Choose another compatible owned station.",
                            Map.of("stationId", target.id))));
        }

        addQueueCause(target, job, causes);
        addPolicyCause(world, target, job, causes);
        addJobPrerequisiteCauses(world, target, job, causes);

        if (!StationFuelRules.isOperational(target)) {
            causes.add(cause(CauseType.STATION_UNAVAILABLE,
                    target.type().name + " " + target.id + " is offline.",
                    Map.of("stationId", target.id, "stationTypeId", target.typeId,
                            "reason", clean(job.blockedReason)), List.of(),
                    action(ActionType.REVIEW_JOB,
                            "Restore the station's required operating supply, then retry the job.",
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
                    deficit, recursion, 0, recipeOverrides));
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

    private static void addQueueCause(Base target, ProductionJob job, List<Cause> causes) {
        int position = target.productionQueue.indexOf(job);
        if (position <= 0) return;
        causes.add(cause(CauseType.QUEUE_WAIT,
                "Job is waiting at queue position " + (position + 1) + " behind " + position
                        + " earlier job" + (position == 1 ? "." : "s."),
                Map.of("jobId", job.id, "stationId", target.id,
                        "queuePosition", Integer.toString(position + 1)), List.of(),
                action(ActionType.REVIEW_JOB, "Review or reorder the station production queue.",
                        Map.of("jobId", job.id, "stationId", target.id))));
    }

    private static void addJobPrerequisiteCauses(World world, Base target, ProductionJob job,
                                                 List<Cause> causes) {
        if (!jobCompatibleWithStation(target, job)) {
            causes.add(cause(CauseType.STATION_UNAVAILABLE,
                    target.type().name + " " + target.id + " cannot perform this production job.",
                    Map.of("stationId", target.id, "stationTypeId", target.typeId,
                            "jobKind", job.kind.name(), "itemId", job.itemId), List.of(),
                    action(ActionType.CHOOSE_STATION, "Choose a compatible owned station for this job.",
                            Map.of("stationId", target.id, "itemId", job.itemId))));
        }

        String missingResearch = missingResearch(world, target.playerId, job);
        if (!missingResearch.isBlank()) {
            causes.add(cause(CauseType.RESEARCH_LOCKED,
                    ProductionSystem.displayName(world, job) + " requires research: " + missingResearch + ".",
                    Map.of("jobId", job.id, "itemId", job.itemId, "research", missingResearch), List.of(),
                    action(ActionType.OPEN_RESEARCH, "Open research for " + missingResearch + ".",
                            Map.of("research", missingResearch, "itemId", job.itemId))));
        }
    }

    private static boolean jobCompatibleWithStation(Base target, ProductionJob job) {
        if (target == null || job == null) return false;
        return switch (job.kind) {
            case SHIP -> target.type().buildableShips.contains(job.itemId);
            case CRAFTABLE -> {
                CraftableItem item = CraftingRules.item(job.itemId);
                yield item != null && item.canCraftAt(target.typeId);
            }
            case STATION_PACKAGE -> target.type().basePackages.contains(job.itemId);
            case RESEARCH -> {
                ResearchTopic topic = ResearchRules.topic(job.itemId);
                yield topic != null && topic.canResearchAt(target.typeId);
            }
            case REFIT -> target.type().canRefitShips;
        };
    }

    private static String missingResearch(World world, String playerId, ProductionJob job) {
        if (world == null || job == null) return "";
        return switch (job.kind) {
            case SHIP -> {
                if (!ResearchRules.shipUnlocked(world, playerId, job.itemId)) {
                    ResearchTopic topic = ResearchRules.firstTopicUnlockingShip(job.itemId);
                    yield topic == null ? "required ship research" : topic.name;
                }
                ShipLoadoutDefinition loadout = WeaponRules.resolveForHull(world, job.itemId, job.loadoutId);
                yield loadout != null && !WeaponRules.unlocked(world, playerId, loadout)
                        ? WeaponRules.missingResearchLabel(world, playerId, loadout) : "";
            }
            case CRAFTABLE -> {
                CraftableItem item = CraftingRules.item(job.itemId);
                yield item != null && !item.unlockedFor(world, playerId)
                        ? item.missingResearchLabel(world, playerId) : "";
            }
            case STATION_PACKAGE -> StationPackageResearchRules.unlocked(world, playerId, job.itemId)
                    ? "" : StationPackageResearchRules.requiredResearchName(job.itemId);
            case RESEARCH -> {
                ResearchTopic topic = ResearchRules.topic(job.itemId);
                yield topic == null ? "" : ResearchRules.missingPrerequisite(world, playerId, topic);
            }
            case REFIT -> {
                ShipLoadoutDefinition loadout = WeaponRules.resolveForHull(world, job.itemId, job.loadoutId);
                yield loadout != null && !WeaponRules.unlocked(world, playerId, loadout)
                        ? WeaponRules.missingResearchLabel(world, playerId, loadout) : "";
            }
        };
    }

    private static Cause analyzeMissingMaterial(World world, Base target, Material material,
                                                double needed, double have, double deficit,
                                                Set<Material> recursion, int depth,
                                                Map<Material, List<CraftableItem>> recipeOverrides) {
        if (world == null || target == null || material == null) {
            return cause(CauseType.BLOCKED_OTHER, "Material dependency is unavailable.", Map.of(),
                    List.of(), List.of());
        }

        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("material", material.name());
        facts.put("needed", number(needed));
        facts.put("have", number(have));
        facts.put("deficit", number(deficit));
        facts.put("stationId", target.id);
        facts.put("systemId", world.activeSystemId());

        List<Cause> children = new ArrayList<>();
        List<RecoveryAction> actions = new ArrayList<>();

        double inTransit = inTransitToStation(world, target, material);
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
        }

        if (sources.routedRemoteAvailable > EPSILON) {
            children.add(cause(CauseType.NO_LOCAL_SOURCE,
                    "No same-system source has enough " + material.label
                            + ", but an active standing route can reach remote owned stock.",
                    Map.of("material", material.name(),
                            "routedRemoteAvailable", number(sources.routedRemoteAvailable),
                            "destSystemId", world.activeSystemId()), List.of(),
                    action(ActionType.MOVE_STOCK,
                            "Allow production logistics to source the routed remote stock.",
                            Map.of("material", material.name(), "destStationId", target.id))));
        }

        for (RemoteSource remote : sources.reachableUnroutedSources) {
            children.add(cause(CauseType.NO_ROUTE,
                    number(remote.available) + " " + material.label + " exists at " + remote.stationId
                            + " in " + remote.systemId + " (" + remote.hops + " hop"
                            + (remote.hops == 1 ? "" : "s") + "), but no active standing route covers it.",
                    Map.of("material", material.name(), "sourceSystemId", remote.systemId,
                            "sourceStationId", remote.stationId, "destSystemId", world.activeSystemId(),
                            "destStationId", target.id, "available", number(remote.available),
                            "hops", Integer.toString(remote.hops)), List.of(),
                    action(ActionType.CREATE_ROUTE,
                            "Open the source station and create or repair a logistics route to "
                                    + world.activeSystemId() + ".",
                            Map.of("sourceSystemId", remote.systemId,
                                    "sourceStationId", remote.stationId,
                                    "destSystemId", world.activeSystemId(),
                                    "destStationId", target.id,
                                    "material", material.name()))));
        }

        for (RemoteSource remote : sources.inaccessibleRemoteSources) {
            children.add(cause(CauseType.INACCESSIBLE_TOPOLOGY,
                    number(remote.available) + " " + material.label + " exists at " + remote.stationId
                            + " in " + remote.systemId + ", but no permitted wormhole path reaches "
                            + world.activeSystemId() + ".",
                    Map.of("material", material.name(), "sourceSystemId", remote.systemId,
                            "sourceStationId", remote.stationId, "destSystemId", world.activeSystemId(),
                            "available", number(remote.available)), List.of(),
                    action(ActionType.REVIEW_JOB,
                            "Restore topology access or choose a reachable source before retrying logistics.",
                            Map.of("sourceSystemId", remote.systemId,
                                    "destSystemId", world.activeSystemId()))));
        }

        boolean anyKnownSource = sources.sameSystemAvailable > EPSILON
                || sources.routedRemoteAvailable > EPSILON
                || !sources.reachableUnroutedSources.isEmpty()
                || !sources.inaccessibleRemoteSources.isEmpty();
        if (!anyKnownSource) {
            children.add(cause(CauseType.NO_LOCAL_SOURCE,
                    "No known owned station currently has spare " + material.label + ".",
                    Map.of("material", material.name(), "destSystemId", world.activeSystemId()),
                    List.of(), List.of()));
        }

        if (sources.routeNeedsTransport || !sources.reachableUnroutedSources.isEmpty()) {
            TransportFacts transports = eligibleTransports(world, target.playerId);
            if (transports.eligibleCount == 0) {
                children.add(cause(CauseType.NO_ELIGIBLE_TRANSPORT,
                        "No idle owned hauler or freighter is currently eligible for a standing logistics route.",
                        Map.of("playerId", target.playerId, "eligibleTransports", "0",
                                "freeCapacity", "0"), List.of(),
                        action(ActionType.CREATE_ROUTE,
                                "Free, build, or assign a hauler/freighter before configuring a standing route.",
                                Map.of("destSystemId", world.activeSystemId(),
                                        "destStationId", target.id))));
            }
        }

        if (Rules.findShip(LogisticsSystem.SHUTTLE_TYPE) == null) {
            children.add(cause(CauseType.NO_COURIER,
                    "No production-demand logistics courier hull is configured for player " + target.playerId + ".",
                    Map.of("playerId", target.playerId, "courierType", LogisticsSystem.SHUTTLE_TYPE), List.of(),
                    action(ActionType.REVIEW_JOB,
                            "Restore the logistics courier configuration before dispatching materials.",
                            Map.of("playerId", target.playerId))));
        }

        addIntermediateCause(world, target, material, deficit, recursion, depth,
                children, actions, recipeOverrides);

        return cause(CauseType.MISSING_INPUTS,
                "Missing " + number(deficit) + " " + material.label + " (need " + number(needed)
                        + ", have " + number(have) + ").",
                facts, children, actions);
    }

    private static void addIntermediateCause(World world, Base target, Material material, double deficit,
                                             Set<Material> recursion, int depth,
                                             List<Cause> children, List<RecoveryAction> actions,
                                             Map<Material, List<CraftableItem>> recipeOverrides) {
        List<CraftableItem> recipes = recipesFor(material, recipeOverrides);
        if (recipes.isEmpty()) return;
        if (depth >= MAX_DEPENDENCY_DEPTH) {
            children.add(cause(CauseType.DEPTH_LIMIT,
                    "Dependency trace stopped at the depth limit while resolving " + material.label + ".",
                    Map.of("material", material.name(), "depthLimit", Integer.toString(MAX_DEPENDENCY_DEPTH)),
                    List.of(), List.of()));
            return;
        }
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
            boolean unlocked = recipe.unlockedFor(world, target.playerId);
            boolean compatible = hasCompatibleOwnedStation(world, target.playerId, recipe);

            if (!unlocked) {
                String missing = recipe.missingResearchLabel(world, target.playerId);
                dependencies.add(cause(CauseType.RESEARCH_LOCKED,
                        recipe.name + " requires research: " + missing + ".",
                        Map.of("recipeId", recipe.id, "research", missing,
                                "material", material.name()), List.of(),
                        action(ActionType.OPEN_RESEARCH,
                                "Research " + missing + " before producing this intermediate.",
                                Map.of("recipeId", recipe.id, "research", missing))));
            }
            if (!compatible) {
                dependencies.add(cause(CauseType.STATION_UNAVAILABLE,
                        "No known owned station can craft " + recipe.name + ".",
                        Map.of("recipeId", recipe.id,
                                "stationTypes", String.join(",", recipe.stationTypes)), List.of(),
                        action(ActionType.CHOOSE_STATION,
                                "Build or use a compatible station for " + recipe.name + ".",
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
                            Map.of("material", input.material().name(), "recipeId", recipe.id),
                            List.of(), List.of()));
                } else {
                    dependencies.add(analyzeMissingMaterial(world, target, input.material(), inputNeeded,
                            Math.min(inputNeeded, networkHave), inputDeficit, recursion, depth + 1,
                            recipeOverrides));
                }
            }

            RecoveryAction queueAction = unlocked && compatible
                    ? action(ActionType.ENQUEUE_INTERMEDIATE,
                            "Queue " + recipe.name + " through the normal production command path.",
                            Map.of("recipeId", recipe.id, "material", material.name()))
                    : null;
            children.add(cause(CauseType.NEEDS_INTERMEDIATE,
                    material.label + " can be produced as " + recipe.name + " (about " + number(batches)
                            + " batch" + (Math.abs(batches - 1) < 0.001 ? "" : "es") + ").",
                    Map.of("material", material.name(), "recipeId", recipe.id,
                            "batches", number(batches), "outputPerBatch", number(recipe.outputAmount)),
                    dependencies, queueAction));
            if (queueAction != null) actions.add(queueAction);
        } finally {
            recursion.remove(material);
        }
    }

    private static List<CraftableItem> recipesFor(Material material,
                                                   Map<Material, List<CraftableItem>> recipeOverrides) {
        if (recipeOverrides != null && !recipeOverrides.isEmpty()) {
            return new ArrayList<>(recipeOverrides.getOrDefault(material, List.of()));
        }
        return new ArrayList<>(CraftingRules.recipesForOutput(material));
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
                    "Production policy " + policy.id() + " is "
                            + status.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                            + (policy.reason().isBlank() ? "." : ": " + policy.reason()),
                    Map.of("policyId", policy.id(), "status", status.name(),
                            "stationId", target.id, "details", clean(policy.reason())), List.of(),
                    action(ActionType.REVIEW_POLICY,
                            "Review policy " + policy.id() + " before changing or reassigning it.",
                            Map.of("policyId", policy.id(), "stationId", target.id))));
            return;
        }
    }

    private static SourceFacts sourceFacts(World world, Base target, Material material) {
        double sameSystem = 0;
        for (Base source : world.bases.values()) {
            if (source == null || source == target || source.hp <= 0
                    || !target.playerId.equals(source.playerId)) continue;
            sameSystem += Math.max(0, source.inventory.getOrDefault(material, 0.0));
        }

        double routedRemote = 0;
        boolean routeNeedsTransport = false;
        List<RemoteSource> reachableUnrouted = new ArrayList<>();
        List<RemoteSource> inaccessible = new ArrayList<>();
        String destination = world.activeSystemId();
        for (WorldSystemState state : world.policySystemStates()) {
            if (state == null || state.id == null || state.id.equals(destination)) continue;
            List<String> path = LogisticsRouteSystem.pathForTest(
                    world, target.playerId, state.id, destination);
            for (Base source : state.bases.values()) {
                if (source == null || source.hp <= 0 || !target.playerId.equals(source.playerId)) continue;
                double available = Math.max(0, source.inventory.getOrDefault(material, 0.0));
                if (available <= EPSILON) continue;
                if (path.size() < 2) {
                    inaccessible.add(new RemoteSource(state.id, source.id, available, 0));
                    continue;
                }
                LogisticsRouteSystem.RouteView route = matchingRoute(source, destination, material);
                if (route != null && route.phase() != LogisticsRouteSystem.RoutePhase.PAUSED
                        && route.phase() != LogisticsRouteSystem.RoutePhase.BLOCKED) {
                    routedRemote += available;
                } else {
                    if (route != null && (route.condition() == LogisticsRouteSystem.RouteCondition.WAITING_FOR_TRANSPORT
                            || route.condition() == LogisticsRouteSystem.RouteCondition.NO_ASSIGNED_TRANSPORT)) {
                        routeNeedsTransport = true;
                    }
                    reachableUnrouted.add(new RemoteSource(state.id, source.id, available, path.size() - 1));
                }
            }
        }
        reachableUnrouted.sort(Comparator.comparingInt(RemoteSource::hops)
                .thenComparing(RemoteSource::systemId).thenComparing(RemoteSource::stationId));
        inaccessible.sort(Comparator.comparing(RemoteSource::systemId).thenComparing(RemoteSource::stationId));
        return new SourceFacts(sameSystem, routedRemote, List.copyOf(reachableUnrouted),
                List.copyOf(inaccessible), routeNeedsTransport);
    }

    private static LogisticsRouteSystem.RouteView matchingRoute(Base source, String destinationSystemId,
                                                                 Material material) {
        if (source == null || material == null) return null;
        for (LogisticsRouteSystem.RouteView route : LogisticsRouteSystem.viewsFromStatus(source.logisticsStatus)) {
            if (route == null || !destinationSystemId.equals(route.destinationSystemId())) continue;
            if (route.materials().contains(material)) return route;
        }
        return null;
    }

    private static TransportFacts eligibleTransports(World world, String playerId) {
        int count = 0;
        double freeCapacity = 0;
        for (WorldSystemState state : world.policySystemStates()) {
            if (state == null) continue;
            for (Unit unit : state.units.values()) {
                if (unit == null || unit.hp <= 0 || !playerId.equals(unit.playerId)
                        || !LogisticsRouteSystem.isTransport(unit)
                        || LogisticsRouteSystem.ownsTransport(world, unit.key())
                        || unit.cargoUsed() > EPSILON) continue;
                count++;
                freeCapacity += Math.max(0, unit.type().cargoCapacity - unit.cargoUsed());
            }
        }
        return new TransportFacts(count, freeCapacity);
    }

    private static double inTransitToStation(World world, Base target, Material material) {
        double total = 0;
        for (WorldSystemState state : world.policySystemStates()) {
            if (state == null) continue;
            for (Unit unit : state.units.values()) {
                if (unit == null || !LogisticsSystem.SHUTTLE_TYPE.equals(unit.shipTypeId)) continue;
                if (!target.playerId.equals(unit.playerId)
                        || !target.id.equals(unit.logisticsTargetBaseId)) continue;
                total += Math.max(0, unit.inventory.getOrDefault(material, 0.0));
            }
        }
        return total;
    }

    private static boolean hasCompatibleOwnedStation(World world, String playerId, CraftableItem item) {
        if (world == null || item == null) return false;
        String active = world.activeSystemId();
        for (WorldSystemState state : world.policySystemStates()) {
            if (state == null || state.id == null) continue;
            for (Base base : state.bases.values()) {
                if (base != null && base.hp > 0 && playerId.equals(base.playerId)
                        && item.canCraftAt(base.typeId)) return true;
            }
        }
        for (Base base : world.bases.values()) {
            if (base != null && base.hp > 0 && playerId.equals(base.playerId)
                    && item.canCraftAt(base.typeId)) return true;
        }
        return false;
    }

    private static double knownOwnedInventory(World world, String playerId, Material material) {
        double total = 0;
        for (WorldSystemState state : world.policySystemStates()) {
            if (state == null) continue;
            for (Base base : state.bases.values()) {
                if (base != null && base.hp > 0 && playerId.equals(base.playerId)) {
                    total += Math.max(0, base.inventory.getOrDefault(material, 0.0));
                }
            }
        }
        return total;
    }

    private static Analysis redacted(ProductionJob job, Base target, CauseType type, String message) {
        String jobId = job == null ? "" : job.id;
        String stationId = target == null ? "" : target.id;
        return new Analysis(jobId, stationId, true, message,
                List.of(cause(type, message, Map.of(), List.of(), List.of())));
    }

    private static Cause cause(CauseType type, String summary, Map<String, String> facts,
                               List<Cause> children, RecoveryAction action) {
        return new Cause(type, summary, facts, children,
                action == null ? List.of() : List.of(action));
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

    private static String number(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < 0.000001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
