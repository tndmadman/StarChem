package com.tndmadman.rts;

import java.util.Set;

/** Validates server-approved remote views without leaking entities outside friendly sensor coverage. */
public final class TcpRemoteSystemVisibilityValidator {
    private static final int CORSAIR_TEST_UNIT_ID = 90_001;
    private static final int VIEWER_SCOUT_UNIT_ID = 90_002;

    private TcpRemoteSystemVisibilityValidator() { }

    public static void main(String[] args) throws Exception {
        validateRegularViewSnapshotRecovery();
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host()) {
            TcpIntegrationHarness.TestClient viewer = harness.addClient("TCP Remote Viewer");
            TcpIntegrationHarness.TestClient owner = harness.addClient("TCP Remote Owner");
            harness.awaitJoined(viewer);
            harness.awaitJoined(owner);

            String viewerId = viewer.playerId();
            String ownerId = owner.playerId();
            String source = viewer.world().activeSystemId();
            String target = harness.serverWorld.playerHomeSystemId(ownerId);
            validateViewSurvivesLastLocalAssetRemoval(harness, viewer, viewerId, source);
            TcpIntegrationHarness.require(!source.equals(target), "remote-view target matched the viewer's current system");
            TcpIntegrationHarness.require(!directlyConnected(harness.serverWorld, source, target),
                    "visibility regression requires a non-adjacent target system");
            TcpIntegrationHarness.require(systemHasPlayerAssets(harness.serverWorld, target, ownerId),
                    "target system did not contain the remote player's assets");

            viewer.network().viewSystem(viewerId, target);
            TcpIntegrationHarness.require(source.equals(viewer.world().activeSystemId()),
                    "client switched systems optimistically before server approval");
            harness.await(() -> !viewer.network().clientViewSwitchPending(), 5_000,
                    "unknown remote system view request was not resolved");
            TcpIntegrationHarness.require(source.equals(viewer.world().activeSystemId()),
                    "guessed unknown system view was not denied");

            seedViewerScoutNearOwner(harness.serverWorld, target, viewerId, ownerId);
            viewer.network().viewSystem(viewerId, target);
            harness.await(() -> !viewer.network().clientViewSwitchPending()
                            && target.equals(viewer.network().clientViewedSystemId())
                            && target.equals(viewer.world().activeSystemId()),
                    12_000, "owned scout system did not become viewable");
            harness.await(() -> viewer.world().units.values().stream().anyMatch(unit -> ownerId.equals(unit.playerId))
                            && viewer.world().bases.values().stream().anyMatch(base -> ownerId.equals(base.playerId)),
                    12_000, "remote enemy assets did not appear after a friendly scout established sensor coverage");

            validateCorsairViewRemainsLive(harness, viewer, viewerId, ownerId);

            viewer.network().viewSystem(viewerId, "missing_system_for_validation");
            harness.await(() -> !viewer.network().clientViewSwitchPending(), 5_000,
                    "invalid system view request was not rejected");
            TcpIntegrationHarness.require(StarSystems.CORSAIR_SYSTEM_ID.equals(viewer.world().activeSystemId()),
                    "rejected view request changed the active client system");
            System.out.println("StarChem remote-system fog visibility validation passed.");
        }
    }

    private static void validateRegularViewSnapshotRecovery() {
        PlayerRegistry.reset("SOLO", "Remote View Recovery", 0x50BEFF);
        World authoritative = new World("Remote View Authority", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        authoritative.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        TcpIntegrationHarness.require(StarSystems.CORSAIR_SYSTEM_ID.equals(authoritative.activeSystemId()),
                "authoritative Corsair Den system is unavailable");
        Snapshot corsairSnapshot = WorldNetAccess.snapshot(authoritative, 1);

        World client = new World("Remote View Client", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        TcpIntegrationHarness.require(!StarSystems.CORSAIR_SYSTEM_ID.equals(client.activeSystemId()),
                "regular-view recovery test did not start outside Corsair Den");
        WorldNetAccess.applyView(client, corsairSnapshot);
        TcpIntegrationHarness.require(StarSystems.CORSAIR_SYSTEM_ID.equals(client.activeSystemId()),
                "regular view snapshot did not recover the requested static-system view");
    }

    private static void validateViewSurvivesLastLocalAssetRemoval(TcpIntegrationHarness harness,
                                                                    TcpIntegrationHarness.TestClient viewer,
                                                                    String viewerId,
                                                                    String systemId) throws Exception {
        long sequenceBeforeRemoval = viewer.network().clientSnapshotSequence();
        removePlayerAssets(harness.serverWorld, systemId, viewerId);
        harness.await(() -> viewer.network().clientSnapshotSequence() > sequenceBeforeRemoval
                        && systemId.equals(viewer.network().clientViewedSystemId())
                        && systemId.equals(viewer.world().activeSystemId())
                        && !currentSystemHasPlayerAssets(viewer.world(), viewerId),
                8_000, "client abandoned the selected view after its last local asset disappeared");
        harness.runTicks(30);
        TcpIntegrationHarness.require(systemId.equals(viewer.world().activeSystemId())
                        && !currentSystemHasPlayerAssets(viewer.world(), viewerId),
                "client created local fallback assets after authoritative removal");
    }

    private static void removePlayerAssets(World world, String systemId, String playerId) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            world.units.values().removeIf(unit -> playerId.equals(unit.playerId));
            world.bases.values().removeIf(base -> playerId.equals(base.playerId));
            world.shots.removeIf(shot -> playerId.equals(shot.ownerId));
            world.saveActiveSystem();
        } finally {
            world.activateSystem(old);
        }
    }

    private static boolean currentSystemHasPlayerAssets(World world, String playerId) {
        for (Unit unit : world.units.values()) {
            if (playerId.equals(unit.playerId) && unit.hp > 0) return true;
        }
        for (Base base : world.bases.values()) {
            if (playerId.equals(base.playerId) && base.hp > 0) return true;
        }
        return false;
    }

    private static boolean systemHasPlayerAssets(World world, String systemId, String playerId) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            return currentSystemHasPlayerAssets(world, playerId);
        } finally {
            world.activateSystem(old);
        }
    }

    private static void seedViewerScoutNearOwner(World world, String systemId, String viewerId, String ownerId) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            Unit owner = firstUnit(world, ownerId);
            Base ownerBase = firstBase(world, ownerId);
            TcpIntegrationHarness.require(owner != null && ownerBase != null,
                    "remote visibility test requires an owner ship and station");
            owner.x = ownerBase.x + 120;
            owner.y = ownerBase.y;
            owner.targetX = owner.x;
            owner.targetY = owner.y;
            String key = Unit.key(viewerId, VIEWER_SCOUT_UNIT_ID);
            Unit scout = new Unit(viewerId, VIEWER_SCOUT_UNIT_ID, "scout", ownerBase.x - 120, ownerBase.y);
            scout.targetX = scout.x;
            scout.targetY = scout.y;
            world.units.put(key, scout);
            world.saveActiveSystem();
        } finally {
            world.activateSystem(old);
        }
    }

    private static void validateCorsairViewRemainsLive(TcpIntegrationHarness harness,
                                                         TcpIntegrationHarness.TestClient viewer,
                                                         String viewerId,
                                                         String ownerId) throws Exception {
        boolean previousDisableAttacks = harness.serverWorld.aiDevSettings.disableAttacks;
        boolean previousFreezeNpcCombat = harness.serverWorld.aiDevSettings.freezeNpcCombat;
        harness.serverWorld.aiDevSettings.disableAttacks = true;
        harness.serverWorld.aiDevSettings.freezeNpcCombat = true;
        try {
            String unitKey = seedCorsairUnit(harness.serverWorld, ownerId);
            seedCorsairScout(harness.serverWorld, viewerId, unitKey);
            viewer.network().viewSystem(viewerId, StarSystems.CORSAIR_SYSTEM_ID);
            harness.await(() -> !viewer.network().clientViewSwitchPending()
                            && StarSystems.CORSAIR_SYSTEM_ID.equals(viewer.network().clientViewedSystemId())
                            && StarSystems.CORSAIR_SYSTEM_ID.equals(viewer.world().activeSystemId())
                            && viewer.world().units.containsKey(unitKey),
                    12_000, "client did not receive a sensor-visible Corsair Den contact");

            Unit authoritative = unitInSystem(harness.serverWorld, StarSystems.CORSAIR_SYSTEM_ID, unitKey);
            TcpIntegrationHarness.require(authoritative != null, "Corsair validation unit disappeared from the server");
            double targetX = authoritative.x + 100.0;
            double targetY = authoritative.y + 40.0;
            long sequenceBeforeMove = viewer.network().clientSnapshotSequence();
            setUnitPosition(harness.serverWorld, StarSystems.CORSAIR_SYSTEM_ID, unitKey, targetX, targetY);
            awaitCorsairReplication(harness, viewer, unitKey, sequenceBeforeMove, targetX, targetY);
        } finally {
            harness.serverWorld.aiDevSettings.disableAttacks = previousDisableAttacks;
            harness.serverWorld.aiDevSettings.freezeNpcCombat = previousFreezeNpcCombat;
        }
    }

    private static void awaitCorsairReplication(TcpIntegrationHarness harness,
                                                 TcpIntegrationHarness.TestClient viewer,
                                                 String unitKey,
                                                 long sequenceBeforeMove,
                                                 double targetX,
                                                 double targetY) throws Exception {
        long deadline = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < deadline) {
            Unit replicated = viewer.world().units.get(unitKey);
            if (viewer.network().clientSnapshotSequence() > sequenceBeforeMove
                    && StarSystems.CORSAIR_SYSTEM_ID.equals(viewer.world().activeSystemId())
                    && replicated != null
                    && TcpIntegrationHarness.distance(replicated.x, replicated.y, targetX, targetY) <= 8.0) {
                return;
            }
            harness.tick();
        }

        Unit authoritative = unitInSystem(harness.serverWorld, StarSystems.CORSAIR_SYSTEM_ID, unitKey);
        Unit replicated = viewer.world().units.get(unitKey);
        throw new IllegalStateException("Corsair Den stopped accepting live sensor-visible snapshots after the view switch"
                + " | beforeSequence=" + sequenceBeforeMove
                + " | clientSequence=" + viewer.network().clientSnapshotSequence()
                + " | viewed=" + viewer.network().clientViewedSystemId()
                + " | active=" + viewer.world().activeSystemId()
                + " | serverUnit=" + describe(authoritative)
                + " | clientUnit=" + describe(replicated));
    }

    private static String seedCorsairUnit(World world, String ownerId) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
            TcpIntegrationHarness.require(StarSystems.CORSAIR_SYSTEM_ID.equals(world.activeSystemId()),
                    "Corsair Den system is unavailable on the server");
            String key = Unit.key(ownerId, CORSAIR_TEST_UNIT_ID);
            Unit unit = new Unit(ownerId, CORSAIR_TEST_UNIT_ID, "station_builder",
                    world.width * 0.32, world.height * 0.48);
            unit.task = UnitTask.IDLE;
            unit.targetX = unit.x;
            unit.targetY = unit.y;
            world.units.put(key, unit);
            world.saveActiveSystem();
            return key;
        } finally {
            world.activateSystem(old);
        }
    }

    private static void seedCorsairScout(World world, String viewerId, String targetKey) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
            Unit target = world.units.get(targetKey);
            TcpIntegrationHarness.require(target != null, "Corsair validation target is missing");
            Unit scout = new Unit(viewerId, VIEWER_SCOUT_UNIT_ID, "scout", target.x - 140, target.y);
            scout.targetX = scout.x;
            scout.targetY = scout.y;
            world.units.put(scout.key(), scout);
            world.saveActiveSystem();
        } finally {
            world.activateSystem(old);
        }
    }

    private static Unit firstUnit(World world, String playerId) {
        for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId) && unit.hp > 0) return unit;
        return null;
    }

    private static Base firstBase(World world, String playerId) {
        for (Base base : world.bases.values()) if (playerId.equals(base.playerId) && base.hp > 0) return base;
        return null;
    }

    private static void setUnitPosition(World world, String systemId, String unitKey, double x, double y) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            Unit unit = world.units.get(unitKey);
            TcpIntegrationHarness.require(unit != null, "Corsair validation unit disappeared from the server");
            unit.x = Calc.clamp(x, 0, world.width);
            unit.y = Calc.clamp(y, 0, world.height);
            unit.targetX = unit.x;
            unit.targetY = unit.y;
            unit.task = UnitTask.IDLE;
            world.saveActiveSystem();
        } finally {
            world.activateSystem(old);
        }
    }

    private static Unit unitInSystem(World world, String systemId, String unitKey) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            return world.units.get(unitKey);
        } finally {
            world.activateSystem(old);
        }
    }

    private static String describe(Unit unit) {
        if (unit == null) return "missing";
        return unit.key() + "@(" + unit.x + ',' + unit.y + ")"
                + " target=(" + unit.targetX + ',' + unit.targetY + ')'
                + " hp=" + unit.hp + " shield=" + unit.shield
                + " task=" + unit.task;
    }

    private static boolean directlyConnected(World world, String source, String target) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(source);
            for (WormholeGate gate : world.wormholes) if (target.equals(gate.toSystemId)) return true;
            return false;
        } finally {
            world.activateSystem(old);
        }
    }
}
