package com.tndmadman.rts;

import java.util.LinkedHashSet;
import java.util.Set;

/** Validates server-approved arbitrary galaxy views and cross-system entity visibility. */
public final class TcpRemoteSystemVisibilityValidator {
    private static final int CORSAIR_TEST_UNIT_ID = 90_001;

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
            TcpIntegrationHarness.require(!source.equals(target), "remote-view target matched the viewer's current system");
            TcpIntegrationHarness.require(!directlyConnected(harness.serverWorld, source, target),
                    "visibility regression requires a non-adjacent target system");

            Set<String> expectedUnits = unitKeys(harness.serverWorld, target);
            Set<String> expectedBases = baseKeys(harness.serverWorld, target);
            TcpIntegrationHarness.require(expectedUnits.stream().anyMatch(key -> key.startsWith(ownerId + ":")),
                    "target system did not contain the remote player's ship");
            TcpIntegrationHarness.require(expectedBases.stream().anyMatch(key -> key.startsWith(ownerId + ":")),
                    "target system did not contain the remote player's station");

            viewer.network().viewSystem(viewerId, target);
            TcpIntegrationHarness.require(source.equals(viewer.world().activeSystemId()),
                    "client switched systems optimistically before server approval");
            harness.await(() -> !viewer.network().clientViewSwitchPending()
                            && target.equals(viewer.network().clientViewedSystemId())
                            && target.equals(viewer.world().activeSystemId()),
                    12_000, "client did not settle on the server-approved arbitrary system view");

            TcpIntegrationHarness.require(expectedUnits.equals(new LinkedHashSet<>(viewer.world().units.keySet())),
                    "remote system ship set did not match the authoritative server state");
            TcpIntegrationHarness.require(expectedBases.equals(new LinkedHashSet<>(viewer.world().bases.keySet())),
                    "remote system station set did not match the authoritative server state");

            validateCorsairViewRemainsLive(harness, viewer, viewerId, ownerId);

            viewer.network().viewSystem(viewerId, "missing_system_for_validation");
            harness.await(() -> !viewer.network().clientViewSwitchPending(), 5_000,
                    "invalid system view request was not rejected");
            TcpIntegrationHarness.require(StarSystems.CORSAIR_SYSTEM_ID.equals(viewer.world().activeSystemId()),
                    "rejected view request changed the active client system");
            System.out.println("StarChem remote-system visibility validation passed.");
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

    private static void validateCorsairViewRemainsLive(TcpIntegrationHarness harness,
                                                        TcpIntegrationHarness.TestClient viewer,
                                                        String viewerId,
                                                        String ownerId) throws Exception {
        String unitKey = seedCorsairUnit(harness.serverWorld, ownerId);
        viewer.network().viewSystem(viewerId, StarSystems.CORSAIR_SYSTEM_ID);
        harness.await(() -> !viewer.network().clientViewSwitchPending()
                        && StarSystems.CORSAIR_SYSTEM_ID.equals(viewer.network().clientViewedSystemId())
                        && StarSystems.CORSAIR_SYSTEM_ID.equals(viewer.world().activeSystemId())
                        && viewer.world().units.containsKey(unitKey),
                12_000, "client did not settle on the server-approved Corsair Den view");

        double targetX = 240.0;
        double targetY = 260.0;
        long sequenceBeforeMove = viewer.network().clientSnapshotSequence();
        setUnitPosition(harness.serverWorld, StarSystems.CORSAIR_SYSTEM_ID, unitKey, targetX, targetY);

        harness.await(() -> {
            Unit replicated = viewer.world().units.get(unitKey);
            return viewer.network().clientSnapshotSequence() > sequenceBeforeMove
                    && StarSystems.CORSAIR_SYSTEM_ID.equals(viewer.world().activeSystemId())
                    && replicated != null
                    && TcpIntegrationHarness.distance(replicated.x, replicated.y, targetX, targetY) <= 8.0;
        }, 8_000, "Corsair Den stopped accepting live authoritative snapshots after the view switch");
    }

    private static String seedCorsairUnit(World world, String ownerId) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
            TcpIntegrationHarness.require(StarSystems.CORSAIR_SYSTEM_ID.equals(world.activeSystemId()),
                    "Corsair Den system is unavailable on the server");
            String key = Unit.key(ownerId, CORSAIR_TEST_UNIT_ID);
            Unit unit = new Unit(ownerId, CORSAIR_TEST_UNIT_ID, Rules.STARTING_SHIP,
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

    private static Set<String> unitKeys(World world, String systemId) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            return new LinkedHashSet<>(world.units.keySet());
        } finally {
            world.activateSystem(old);
        }
    }

    private static Set<String> baseKeys(World world, String systemId) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            return new LinkedHashSet<>(world.bases.keySet());
        } finally {
            world.activateSystem(old);
        }
    }
}
