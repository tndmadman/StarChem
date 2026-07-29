package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class FogSnapshotFilter {
    private static final String UNKNOWN_OWNER = "SENSOR_CONTACT";

    private FogSnapshotFilter() { }

    static Snapshot forPlayer(World world, String playerId, Snapshot source) {
        if (world == null || source == null || playerId == null || playerId.isBlank() || "WAIT".equals(playerId)) return source;
        VisibilityRules.Frame visibility = VisibilityRules.frame(world, playerId);
        Set<String> revealedPlayers = new LinkedHashSet<>();
        revealedPlayers.add(playerId);

        List<UnitState> units = new ArrayList<>();
        for (UnitState state : source.units()) {
            Unit unit = world.units.get(Unit.key(state.playerId(), state.unitId()));
            IntelWarfareSystem.DetectionStage stage = visibility.unitStage(unit);
            if (stage == IntelWarfareSystem.DetectionStage.NONE) continue;
            if (stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)) revealedPlayers.add(state.playerId());
            units.add(sanitizeUnit(world, playerId, visibility, unit, state, stage));
        }

        List<ResourceState> resources = new ArrayList<>();
        for (ResourceState state : source.resources()) {
            ResourceNode resource = world.findResource(state.id());
            IntelWarfareSystem.DetectionStage stage = visibility.resourceStage(resource);
            if (stage == IntelWarfareSystem.DetectionStage.NONE) continue;
            resources.add(sanitizeResource(world, state, stage));
        }

        List<BaseState> bases = new ArrayList<>();
        for (BaseState state : source.bases()) {
            Base base = world.bases.get(state.id());
            IntelWarfareSystem.DetectionStage stage = visibility.baseStage(base);
            if (stage == IntelWarfareSystem.DetectionStage.NONE) continue;
            if (stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)) revealedPlayers.add(state.playerId());
            bases.add(sanitizeBase(world, playerId, base, state, stage));
        }

        List<ShotState> shots = new ArrayList<>();
        for (ShotState state : source.shots()) {
            if (visibility.pointVisible(state.x(), state.y())
                    || visibility.pointVisible(state.lastX(), state.lastY())
                    || visibility.targetVisible(world, state.targetKey())) {
                shots.add(new ShotState(state.id(), state.ownerId(), state.weaponId(),
                        visibleEntityTarget(world, visibility, state.targetKey()),
                        state.x(), state.y(), state.lastX(), state.lastY()));
            }
        }

        List<ItemState> items = new ArrayList<>();
        for (ItemState state : source.items()) {
            if (visibility.pointVisible(state.x(), state.y())) items.add(state);
        }

        List<PlayerInfo> players = new ArrayList<>();
        for (PlayerInfo player : source.players()) {
            if (playerId.equals(player.id()) || IntelWarfareSystem.allied(world, playerId, player.id())
                    || revealedPlayers.contains(player.id())) players.add(player);
        }

        List<ResearchState> research = new ArrayList<>();
        for (ResearchState state : source.research()) if (playerId.equals(state.playerId())) research.add(state);

        return new Snapshot(source.sequence(), List.copyOf(players), List.copyOf(units), List.copyOf(resources),
                List.copyOf(bases), source.stocks(), List.copyOf(shots), List.copyOf(items), source.systemId(),
                source.systemTime(), source.celestialState(), List.copyOf(research), source.objective());
    }

    private static UnitState sanitizeUnit(World world, String playerId, VisibilityRules.Frame visibility,
                                          Unit unit, UnitState state,
                                          IntelWarfareSystem.DetectionStage stage) {
        boolean ownOrAllied = IntelWarfareSystem.allied(world, playerId, state.playerId());
        if (ownOrAllied || stage == IntelWarfareSystem.DetectionStage.DETAILED) {
            return ownOrAllied ? state : sanitizeDetailedEnemy(world, visibility, state);
        }

        String key = "U:" + state.playerId() + ':' + state.unitId();
        if (!stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)) {
            String contactType = IntelWarfareSystem.contactShipType(unit, stage);
            double x = IntelWarfareSystem.approximateX(world, key, stage, state.x());
            double y = IntelWarfareSystem.approximateY(world, key, stage, state.y());
            return new UnitState(UNKNOWN_OWNER, syntheticUnitId(key), contactType, x, y, x, y,
                    stage == IntelWarfareSystem.DetectionStage.CLASSIFIED ? state.heading() : 0,
                    UnitTask.IDLE.name(), -1, "", "", 1, 0, "", 0,
                    UnitOrderType.NONE.name(), 0, 0, 0, 0, 0, "", 0);
        }

        boolean targetPointVisible = visibility.pointVisible(state.targetX(), state.targetY());
        double targetX = targetPointVisible ? state.targetX() : state.x();
        double targetY = targetPointVisible ? state.targetY() : state.y();
        double hp = approximateCondition(state.hp(), unit == null ? state.hp() : unit.type().maxHp);
        double shield = approximateCondition(state.shield(), unit == null ? state.shield() : unit.type().maxShield);
        return new UnitState(state.playerId(), state.unitId(), state.shipTypeId(), state.x(), state.y(),
                targetX, targetY, state.heading(), state.task(), -1, "", "", hp, shield, "",
                state.weaponFlashTimer(), UnitOrderType.NONE.name(), 0, 0, 0, 0, 0, "", 0);
    }

    private static UnitState sanitizeDetailedEnemy(World world, VisibilityRules.Frame visibility, UnitState state) {
        boolean targetPointVisible = visibility.pointVisible(state.targetX(), state.targetY());
        double targetX = targetPointVisible ? state.targetX() : state.x();
        double targetY = targetPointVisible ? state.targetY() : state.y();
        int resourceId = -1;
        ResourceNode resource = world.findResource(state.resourceId());
        if (resource != null && visibility.resourceStage(resource).atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)) {
            resourceId = state.resourceId();
        }
        return new UnitState(state.playerId(), state.unitId(), state.shipTypeId(), state.x(), state.y(),
                targetX, targetY, state.heading(), state.task(), resourceId, "", "", state.hp(), state.shield(),
                visibleEntityTarget(world, visibility, state.attackTarget()), state.weaponFlashTimer(),
                UnitOrderType.NONE.name(), 0, 0, 0, 0, 0,
                visibleEntityTarget(world, visibility, state.orderTarget()), 0);
    }

    private static BaseState sanitizeBase(World world, String playerId, Base base, BaseState state,
                                          IntelWarfareSystem.DetectionStage stage) {
        boolean ownOrAllied = IntelWarfareSystem.allied(world, playerId, state.playerId());
        if (ownOrAllied) return state;
        String key = "B:" + state.id();
        boolean spoofing = base != null && IntelWarfareSystem.isDecoy(base.typeId)
                && stage.atLeast(IntelWarfareSystem.DetectionStage.CLASSIFIED)
                && stage != IntelWarfareSystem.DetectionStage.DETAILED;
        String visibleType = spoofing ? StationControls.decoySpoofType(world, base) : state.typeId();
        if (!stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)) {
            double x = IntelWarfareSystem.approximateX(world, key, stage, state.x());
            double y = IntelWarfareSystem.approximateY(world, key, stage, state.y());
            return new BaseState("CONTACT-" + Integer.toUnsignedString(key.hashCode()), UNKNOWN_OWNER,
                    spoofing ? visibleType : IntelWarfareSystem.CONTACT_STATION, x, y, 1, 0, "", "");
        }
        if (stage == IntelWarfareSystem.DetectionStage.IDENTIFIED) {
            double hp = approximateCondition(state.hp(), base == null ? state.hp() : base.type().maxHp);
            double shield = approximateCondition(state.shield(), base == null ? state.shield() : base.type().maxShield);
            return new BaseState(state.id(), state.playerId(), visibleType, state.x(), state.y(),
                    hp, shield, "", "");
        }
        return new BaseState(state.id(), state.playerId(), state.typeId(), state.x(), state.y(),
                state.hp(), state.shield(), "", "");
    }

    private static ResourceState sanitizeResource(World world, ResourceState state,
                                                  IntelWarfareSystem.DetectionStage stage) {
        if (stage == IntelWarfareSystem.DetectionStage.DETAILED) return state;
        String key = "R:" + state.id();
        double x = stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED) ? state.x()
                : IntelWarfareSystem.approximateX(world, key, stage, state.x());
        double y = stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED) ? state.y()
                : IntelWarfareSystem.approximateY(world, key, stage, state.y());
        String name;
        String kind;
        String material;
        double maximum;
        double amount;
        double rate;
        if (stage == IntelWarfareSystem.DetectionStage.CONTACT) {
            name = "Unsurveyed Contact";
            kind = NodeKind.SILICATE_ROCK.name();
            material = Material.IRON.name();
            maximum = amount = 1;
            rate = 0;
        } else if (stage == IntelWarfareSystem.DetectionStage.CLASSIFIED) {
            boolean gas = NodeKind.GAS_CLOUD.name().equals(state.kind());
            name = gas ? "Gas Contact" : "Mineral Contact";
            kind = state.kind();
            material = gas ? Material.HYDROGEN.name() : Material.IRON.name();
            maximum = amount = 1;
            rate = 0;
        } else {
            name = state.name();
            kind = state.kind();
            material = state.material();
            maximum = Math.max(1, state.maxAmount());
            amount = approximateCondition(state.amount(), maximum);
            rate = 0;
        }
        return new ResourceState(state.id(), name, kind, material, x, y, maximum, rate,
                state.radius(), amount, state.active(), state.respawnTimer(),
                state.orbitCenterX(), state.orbitCenterY(), state.orbitRadius(), state.orbitAngle(),
                state.orbitSpeed(), state.orbiting());
    }

    private static double approximateCondition(double current, double maximum) {
        if (maximum <= 0) return Math.max(0, current);
        double fraction = Math.max(0, Math.min(1, current / maximum));
        double band = Math.max(0.25, Math.ceil(fraction * 4.0) / 4.0);
        return Math.min(maximum, maximum * band);
    }

    private static int syntheticUnitId(String key) {
        return 1_000_000_000 + Math.floorMod(key == null ? 0 : key.hashCode(), 900_000_000);
    }

    private static String visibleEntityTarget(World world, VisibilityRules.Frame visibility, String targetKey) {
        if (targetKey == null || targetKey.isBlank()) return "";
        if (!targetKey.startsWith("U:") && !targetKey.startsWith("B:")) return targetKey;
        return visibility.targetVisible(world, targetKey) ? targetKey : "";
    }
}
