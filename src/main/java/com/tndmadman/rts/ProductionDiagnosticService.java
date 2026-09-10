package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Canonical player-facing production diagnostics.
 *
 * ProductionCausalAnalyzer intentionally gathers a broad causal graph, including
 * standing-route facts. Production demand logistics is a different authority:
 * InterSystemProductionLogistics sources owned remote stock over the effective
 * wormhole topology and creates its own courier shuttle. This service normalizes
 * the raw graph to those actual dispatch rules before it reaches the UI.
 */
final class ProductionDiagnosticService {
    private ProductionDiagnosticService() { }

    static ProductionCausalAnalyzer.Analysis analyze(World world, Base target, ProductionJob job) {
        return normalize(world, target, ProductionCausalAnalyzer.analyze(world, target, job));
    }

    static ProductionCausalAnalyzer.Analysis analyzeForPlayer(World world, Base target,
                                                               ProductionJob job, String viewerId) {
        ProductionCausalAnalyzer.Analysis raw = ProductionCausalAnalyzer.analyzeForPlayer(
                world, target, job, viewerId);
        if (contains(raw.causes(), ProductionCausalAnalyzer.CauseType.UNAUTHORIZED)
                || contains(raw.causes(), ProductionCausalAnalyzer.CauseType.STALE_STATE)) {
            return raw;
        }
        return normalize(world, target, raw);
    }

    /** Normalize an already owner-gated analysis, used by the lightweight diagnostics dialog. */
    static ProductionCausalAnalyzer.Analysis normalizeForPlayer(World world,
                                                                 ProductionCausalAnalyzer.Analysis raw) {
        if (raw == null || world == null) return raw;
        if (contains(raw.causes(), ProductionCausalAnalyzer.CauseType.UNAUTHORIZED)
                || contains(raw.causes(), ProductionCausalAnalyzer.CauseType.STALE_STATE)) {
            return raw;
        }
        Base target = world.bases.get(raw.stationId());
        if (target == null || !PlayerRegistry.isLocal(target.playerId)) return raw;
        return normalize(world, target, raw);
    }

    /** Package-private deterministic seam for issue regression fixtures. */
    static ProductionCausalAnalyzer.Analysis normalizeForTest(World world, Base target,
                                                               ProductionCausalAnalyzer.Analysis raw) {
        return normalize(world, target, raw);
    }

    private static ProductionCausalAnalyzer.Analysis normalize(World world, Base target,
                                                                ProductionCausalAnalyzer.Analysis raw) {
        if (raw == null || world == null || target == null) return raw;
        List<ProductionCausalAnalyzer.Cause> causes = normalizeCauses(world, target, raw.causes());
        String summary = causes.isEmpty() ? raw.summary() : causes.get(0).summary();
        return new ProductionCausalAnalyzer.Analysis(raw.jobId(), raw.stationId(), raw.blocked(), summary, causes);
    }

    private static List<ProductionCausalAnalyzer.Cause> normalizeCauses(
            World world, Base target, List<ProductionCausalAnalyzer.Cause> causes) {
        if (causes == null || causes.isEmpty()) return List.of();
        List<ProductionCausalAnalyzer.Cause> out = new ArrayList<>();
        for (ProductionCausalAnalyzer.Cause cause : causes) {
            ProductionCausalAnalyzer.Cause normalized = normalizeCause(world, target, cause);
            if (normalized != null) out.add(normalized);
        }
        return List.copyOf(out);
    }

    private static ProductionCausalAnalyzer.Cause normalizeCause(
            World world, Base target, ProductionCausalAnalyzer.Cause cause) {
        if (cause == null) return null;

        // This raw blocker describes idle transports assigned to standing routes.
        // Production-demand sourcing creates its own shuttle, so it is not a blocker here.
        if (cause.type() == ProductionCausalAnalyzer.CauseType.NO_ELIGIBLE_TRANSPORT) return null;

        if (cause.type() == ProductionCausalAnalyzer.CauseType.NO_COURIER) {
            return new ProductionCausalAnalyzer.Cause(
                    ProductionCausalAnalyzer.CauseType.NO_ELIGIBLE_TRANSPORT,
                    "Production logistics has no eligible courier hull configured for automatic material delivery.",
                    cause.facts(), normalizeCauses(world, target, cause.children()),
                    List.of(new ProductionCausalAnalyzer.RecoveryAction(
                            ProductionCausalAnalyzer.ActionType.REVIEW_JOB,
                            "Restore the production logistics courier configuration before retrying delivery.",
                            cause.facts())));
        }

        if (cause.type() == ProductionCausalAnalyzer.CauseType.NO_ROUTE) {
            return normalizeRemoteRoute(world, target, cause);
        }

        if (cause.type() == ProductionCausalAnalyzer.CauseType.NO_LOCAL_SOURCE
                && cause.facts().containsKey("routedRemoteAvailable")) {
            return new ProductionCausalAnalyzer.Cause(cause.type(),
                    "No same-system source has enough material, but reachable owned remote stock is available to production-demand logistics.",
                    cause.facts(), normalizeCauses(world, target, cause.children()), List.of());
        }

        return new ProductionCausalAnalyzer.Cause(cause.type(), cause.summary(), cause.facts(),
                normalizeCauses(world, target, cause.children()), cause.actions());
    }

    private static ProductionCausalAnalyzer.Cause normalizeRemoteRoute(
            World world, Base target, ProductionCausalAnalyzer.Cause cause) {
        Map<String,String> facts = cause.facts();
        String sourceSystemId = facts.getOrDefault("sourceSystemId", "");
        String destinationSystemId = facts.getOrDefault("destSystemId", world.activeSystemId());
        if (sourceSystemId.isBlank() || destinationSystemId.isBlank()) {
            return new ProductionCausalAnalyzer.Cause(cause.type(), cause.summary(), facts,
                    normalizeCauses(world, target, cause.children()), stripStandingRouteActions(cause.actions()));
        }

        List<String> path = LogisticsRouteSystem.pathForTest(
                world, target.playerId, sourceSystemId, destinationSystemId);
        if (path.size() < 2) {
            return new ProductionCausalAnalyzer.Cause(
                    ProductionCausalAnalyzer.CauseType.INACCESSIBLE_TOPOLOGY,
                    "Owned remote stock exists in " + sourceSystemId
                            + ", but no permitted wormhole path reaches " + destinationSystemId + ".",
                    facts, normalizeCauses(world, target, cause.children()),
                    List.of(new ProductionCausalAnalyzer.RecoveryAction(
                            ProductionCausalAnalyzer.ActionType.REVIEW_JOB,
                            "Restore topology access or choose a reachable source.", facts)));
        }

        String nextHop = path.get(1);
        if (!hasDepartureGate(world, sourceSystemId, nextHop)) {
            return new ProductionCausalAnalyzer.Cause(
                    ProductionCausalAnalyzer.CauseType.NO_ROUTE,
                    "Owned remote stock exists in " + sourceSystemId
                            + ", but its departure wormhole gate toward " + nextHop
                            + " is unavailable, so production logistics cannot launch the delivery.",
                    facts, normalizeCauses(world, target, cause.children()),
                    List.of(new ProductionCausalAnalyzer.RecoveryAction(
                            ProductionCausalAnalyzer.ActionType.REVIEW_JOB,
                            "Restore the source system's departure wormhole gate before retrying delivery.", facts)));
        }

        String material = facts.getOrDefault("material", "material");
        String station = facts.getOrDefault("sourceStationId", "an owned remote station");
        String available = facts.getOrDefault("available", "");
        String prefix = available.isBlank() ? "Owned " : available + " ";
        return new ProductionCausalAnalyzer.Cause(
                ProductionCausalAnalyzer.CauseType.NO_LOCAL_SOURCE,
                prefix + material + " is available at " + station + " in " + sourceSystemId
                        + " and is reachable by production-demand logistics; no standing route or assigned hauler is required.",
                facts, normalizeCauses(world, target, cause.children()), List.of());
    }

    private static boolean hasDepartureGate(World world, String sourceSystemId, String nextHop) {
        if (sourceSystemId.equals(world.activeSystemId())) {
            for (WormholeGate gate : world.wormholes) {
                if (gate != null && nextHop.equals(gate.remoteSystemId)) return true;
            }
            return false;
        }
        for (WorldSystemState state : world.policySystemStates()) {
            if (state == null || !sourceSystemId.equals(state.id)) continue;
            for (WormholeGate gate : state.wormholes) {
                if (gate != null && nextHop.equals(gate.remoteSystemId)) return true;
            }
            return false;
        }
        return false;
    }

    private static List<ProductionCausalAnalyzer.RecoveryAction> stripStandingRouteActions(
            List<ProductionCausalAnalyzer.RecoveryAction> actions) {
        if (actions == null || actions.isEmpty()) return List.of();
        List<ProductionCausalAnalyzer.RecoveryAction> out = new ArrayList<>();
        for (ProductionCausalAnalyzer.RecoveryAction action : actions) {
            if (action != null && action.type() != ProductionCausalAnalyzer.ActionType.CREATE_ROUTE) out.add(action);
        }
        return List.copyOf(out);
    }

    private static boolean contains(List<ProductionCausalAnalyzer.Cause> causes,
                                    ProductionCausalAnalyzer.CauseType type) {
        if (causes == null) return false;
        for (ProductionCausalAnalyzer.Cause cause : causes) {
            if (cause == null) continue;
            if (cause.type() == type || contains(cause.children(), type)) return true;
        }
        return false;
    }
}
