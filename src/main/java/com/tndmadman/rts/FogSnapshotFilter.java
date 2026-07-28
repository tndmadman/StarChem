package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;

final class FogSnapshotFilter {
    private FogSnapshotFilter() { }

    static Snapshot forPlayer(World world, String playerId, Snapshot source) {
        if (world == null || source == null || playerId == null || playerId.isBlank() || "WAIT".equals(playerId)) return source;

        List<UnitState> units = new ArrayList<>();
        for (UnitState state : source.units()) {
            Unit unit = world.units.get(Unit.key(state.playerId(), state.unitId()));
            if (!VisibilityRules.unitVisible(world, playerId, unit)) continue;
            units.add(sanitizeUnit(world, playerId, state));
        }

        List<ResourceState> resources = new ArrayList<>();
        for (ResourceState state : source.resources()) {
            if (VisibilityRules.pointVisible(world, playerId, state.x(), state.y())) resources.add(state);
        }

        List<BaseState> bases = new ArrayList<>();
        for (BaseState state : source.bases()) {
            Base base = world.bases.get(state.id());
            if (!VisibilityRules.baseVisible(world, playerId, base)) continue;
            if (playerId.equals(state.playerId())) bases.add(state);
            else bases.add(new BaseState(state.id(), state.playerId(), state.typeId(), state.x(), state.y(),
                    state.hp(), state.shield(), "", ""));
        }

        List<ShotState> shots = new ArrayList<>();
        for (ShotState state : source.shots()) {
            if (playerId.equals(state.ownerId())
                    || VisibilityRules.pointVisible(world, playerId, state.x(), state.y())
                    || VisibilityRules.pointVisible(world, playerId, state.lastX(), state.lastY())
                    || VisibilityRules.targetVisible(world, playerId, state.targetKey())) {
                shots.add(state);
            }
        }

        List<ItemState> items = new ArrayList<>();
        for (ItemState state : source.items()) {
            if (VisibilityRules.pointVisible(world, playerId, state.x(), state.y())) items.add(state);
        }

        List<ResearchState> research = new ArrayList<>();
        for (ResearchState state : source.research()) if (playerId.equals(state.playerId())) research.add(state);

        return new Snapshot(source.sequence(), source.players(), List.copyOf(units), List.copyOf(resources),
                List.copyOf(bases), source.stocks(), List.copyOf(shots), List.copyOf(items), source.systemId(),
                source.systemTime(), source.celestialState(), List.copyOf(research), source.objective());
    }

    private static UnitState sanitizeUnit(World world, String playerId, UnitState state) {
        boolean own = playerId.equals(state.playerId());
        String cargo = own ? state.cargo() : "";
        String attackTarget = visibleEntityTarget(world, playerId, state.attackTarget());
        String orderTarget = visibleEntityTarget(world, playerId, state.orderTarget());
        return new UnitState(state.playerId(), state.unitId(), state.shipTypeId(), state.x(), state.y(),
                state.targetX(), state.targetY(), state.heading(), state.task(), state.resourceId(), state.packageType(),
                cargo, state.hp(), state.shield(), attackTarget, state.weaponFlashTimer(), state.orderType(),
                state.orderX1(), state.orderY1(), state.orderX2(), state.orderY2(), state.orderRadius(), orderTarget,
                state.orderPhase());
    }

    private static String visibleEntityTarget(World world, String playerId, String targetKey) {
        if (targetKey == null || targetKey.isBlank()) return "";
        if (!targetKey.startsWith("U:") && !targetKey.startsWith("B:")) return targetKey;
        return VisibilityRules.targetVisible(world, playerId, targetKey) ? targetKey : "";
    }
}
