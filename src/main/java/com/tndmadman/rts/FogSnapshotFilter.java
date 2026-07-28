package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;

final class FogSnapshotFilter {
    private FogSnapshotFilter() { }

    static Snapshot forPlayer(World world, String playerId, Snapshot source) {
        if (world == null || source == null || playerId == null || playerId.isBlank() || "WAIT".equals(playerId)) return source;
        VisibilityRules.Frame visibility = VisibilityRules.frame(world, playerId);

        List<UnitState> units = new ArrayList<>();
        for (UnitState state : source.units()) {
            Unit unit = world.units.get(Unit.key(state.playerId(), state.unitId()));
            if (!visibility.unitVisible(unit)) continue;
            units.add(sanitizeUnit(world, playerId, visibility, state));
        }

        List<ResourceState> resources = new ArrayList<>();
        for (ResourceState state : source.resources()) {
            if (visibility.pointVisible(state.x(), state.y())) resources.add(state);
        }

        List<BaseState> bases = new ArrayList<>();
        for (BaseState state : source.bases()) {
            Base base = world.bases.get(state.id());
            if (!visibility.baseVisible(base)) continue;
            if (playerId.equals(state.playerId())) bases.add(state);
            else bases.add(new BaseState(state.id(), state.playerId(), state.typeId(), state.x(), state.y(),
                    state.hp(), state.shield(), "", ""));
        }

        List<ShotState> shots = new ArrayList<>();
        for (ShotState state : source.shots()) {
            if (visibility.pointVisible(state.x(), state.y())
                    || visibility.pointVisible(state.lastX(), state.lastY())
                    || visibility.targetVisible(world, state.targetKey())) {
                shots.add(state);
            }
        }

        List<ItemState> items = new ArrayList<>();
        for (ItemState state : source.items()) {
            if (visibility.pointVisible(state.x(), state.y())) items.add(state);
        }

        List<ResearchState> research = new ArrayList<>();
        for (ResearchState state : source.research()) if (playerId.equals(state.playerId())) research.add(state);

        return new Snapshot(source.sequence(), source.players(), List.copyOf(units), List.copyOf(resources),
                List.copyOf(bases), source.stocks(), List.copyOf(shots), List.copyOf(items), source.systemId(),
                source.systemTime(), source.celestialState(), List.copyOf(research), source.objective());
    }

    private static UnitState sanitizeUnit(World world, String playerId, VisibilityRules.Frame visibility, UnitState state) {
        boolean own = playerId.equals(state.playerId());
        if (own) return state;

        boolean targetPointVisible = visibility.pointVisible(state.targetX(), state.targetY());
        double targetX = targetPointVisible ? state.targetX() : state.x();
        double targetY = targetPointVisible ? state.targetY() : state.y();

        int resourceId = -1;
        ResourceNode resource = world.findResource(state.resourceId());
        if (resource != null && visibility.pointVisible(resource.x, resource.y)) resourceId = state.resourceId();

        String attackTarget = visibleEntityTarget(world, visibility, state.attackTarget());
        String orderTarget = visibleEntityTarget(world, visibility, state.orderTarget());
        return new UnitState(state.playerId(), state.unitId(), state.shipTypeId(), state.x(), state.y(),
                targetX, targetY, state.heading(), state.task(), resourceId, state.packageType(), "",
                state.hp(), state.shield(), attackTarget, state.weaponFlashTimer(), UnitOrderType.NONE.name(),
                0, 0, 0, 0, 0, orderTarget, 0);
    }

    private static String visibleEntityTarget(World world, VisibilityRules.Frame visibility, String targetKey) {
        if (targetKey == null || targetKey.isBlank()) return "";
        if (!targetKey.startsWith("U:") && !targetKey.startsWith("B:")) return targetKey;
        return visibility.targetVisible(world, targetKey) ? targetKey : "";
    }
}
