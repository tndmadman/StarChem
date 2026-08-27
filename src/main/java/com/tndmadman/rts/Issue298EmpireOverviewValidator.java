package com.tndmadman.rts;

import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Issue298EmpireOverviewValidator {
    private Issue298EmpireOverviewValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem issue 298 strategic empire overview validation passed.");
    }

    static void validateOrThrow() {
        GalaxyRuntimeOptions.configureCopies(1);
        World world = new World("Empire Overview Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("SOLO", "Empire Overview Validator", 0x50BEFF);
        try {
            validateOwnerFiltering(world);
            validateCrossSystemProjection(world);
            validateProductionResearchAndRemoval(world);
            validateCacheAndWire(world);
            validatePresentationHook(world);
        } finally {
            EmpireOverviewOverlay.clear(world);
            StrategicSummaryService.clear(world);
            OwnerFleetLocationRegistry.clear(world);
            PlayerRegistry.activate(null);
            GalaxyRuntimeOptions.configureCopies(1);
        }
    }

    private static void validateOwnerFiltering(World world) {
        Unit enemy = new Unit("ENEMY", 9001, Rules.STARTING_SHIP, 150, 150);
        Base enemyBase = new Base("ENEMY:B9001", "ENEMY", Rules.DEFAULT_BASE, 220, 220);
        world.units.put(enemy.key(), enemy);
        world.bases.put(enemyBase.id, enemyBase);
        StrategicSummarySnapshot snapshot = StrategicSummaryService.captureFresh(world, "SOLO");
        require(!snapshot.fleets().isEmpty(), "strategic overview omitted the owner's starting fleet");
        require(!snapshot.stations().isEmpty(), "strategic overview omitted the owner's starting station");
        require(snapshot.fleets().stream().allMatch(row -> row.unitKey().startsWith("SOLO:")),
                "strategic overview leaked a foreign fleet asset");
        require(snapshot.stations().stream().allMatch(row -> row.baseId().startsWith("SOLO:")),
                "strategic overview leaked a foreign station asset");
        require(snapshot.alerts().stream().noneMatch(row -> row.assetKey().startsWith("ENEMY:")),
                "strategic overview leaked a foreign alert");
        world.units.remove(enemy.key());
        world.bases.remove(enemyBase.id);
    }

    private static void validateCrossSystemProjection(World world) {
        String source = world.activeSystemId();
        String target = "";
        for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
            if (system != null && !system.id().equals(source)) { target = system.id(); break; }
        }
        require(!target.isBlank(), "validator could not find a second galaxy system");
        world.movePlayerAssetsToSystem("SOLO", target);
        StrategicSummarySnapshot moved = StrategicSummaryService.captureFresh(world, "SOLO");
        final String expectedTarget = target;
        require(moved.fleets().stream().allMatch(row -> expectedTarget.equals(row.systemId())),
                "fleet transfer was not reflected in strategic overview");
        require(moved.stations().stream().allMatch(row -> expectedTarget.equals(row.systemId())),
                "station transfer was not reflected in strategic overview");
        require(moved.systems().stream().anyMatch(row -> expectedTarget.equals(row.systemId())),
                "destination system disappeared from strategic overview after transfer");
        require(world.viewGalaxySystem(target), "validator could not view transferred asset system");
    }

    private static void validateProductionResearchAndRemoval(World world) {
        Base base = world.bases.values().stream().filter(candidate -> "SOLO".equals(candidate.playerId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("missing owned station after transfer"));
        ProductionJob job = new ProductionJob("P298", ProductionJobKind.SHIP, Rules.STARTING_SHIP,
                30, 20, true, "");
        job.blockedReason = ProductionSystem.WAITING_FOR_RESOURCES;
        base.productionQueue.add(job);
        ResearchTopic completed = ResearchRules.all().isEmpty() ? null : ResearchRules.all().get(0);
        if (completed != null) world.completeResearch("SOLO", completed.id);

        Unit unit = world.units.values().stream().filter(candidate -> "SOLO".equals(candidate.playerId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("missing owned ship after transfer"));
        unit.hp = Math.max(1, unit.type().maxHp * 0.40);
        StrategicSummarySnapshot changed = StrategicSummaryService.captureFresh(world, "SOLO");
        require(changed.production().stream().anyMatch(row -> "P298".equals(row.jobId())
                        && row.queuePosition() == 1 && !row.blockedReason().isBlank()),
                "production queue state did not appear in strategic overview");
        require(changed.alerts().stream().anyMatch(row -> "PRODUCTION".equals(row.category())),
                "blocked production did not generate a strategic alert");
        require(changed.alerts().stream().anyMatch(row -> unit.key().equals(row.assetKey()) && "DAMAGE".equals(row.category())),
                "critical ship damage did not generate a strategic alert");
        if (completed != null) require(changed.research().stream().anyMatch(row -> completed.id.equals(row.topicId())
                        && "COMPLETE".equals(row.status())),
                "completed research did not appear in strategic overview");
        String strategicBaseId = base.id.startsWith(base.playerId + ":") ? base.id : base.playerId + ":" + base.id;
        require(changed.stations().stream().anyMatch(row -> strategicBaseId.equals(row.baseId()) && row.queueSize() == 1),
                "station queue count was not refreshed in strategic overview");

        unit.hp = 0;
        StrategicSummarySnapshot destroyed = StrategicSummaryService.captureFresh(world, "SOLO");
        require(destroyed.fleets().stream().noneMatch(row -> unit.key().equals(row.unitKey())),
                "destroyed fleet asset remained in strategic overview");
    }

    private static void validateCacheAndWire(World world) {
        StrategicSummaryService.clear(world);
        int before = StrategicSummaryService.scanCountForTest(world);
        StrategicSummarySnapshot first = StrategicSummaryService.capture(world, "SOLO");
        int afterFirst = StrategicSummaryService.scanCountForTest(world);
        StrategicSummarySnapshot second = StrategicSummaryService.capture(world, "SOLO");
        int afterSecond = StrategicSummaryService.scanCountForTest(world);
        require(afterFirst == before + 1, "strategic summary did not perform its initial aggregate scan");
        require(afterSecond == afterFirst && first.equals(second),
                "unchanged strategic summaries were rescanned inside the throttle window");

        PlayerRegistry.activate(world);
        String packet = GalaxyMapWire.encode(1, world.authoritativeGalaxyMapSnapshot(), "SOLO",
                OwnerFleetLocations.capture(world, "SOLO"));
        require(packet.contains("|E,"), "owner galaxy packet omitted the strategic summary");
        require(packet.getBytes(StandardCharsets.UTF_8).length < TcpFrameCodec.MAX_FRAME_BYTES,
                "strategic galaxy packet exceeded the TCP frame limit");
        GalaxyMapWire.Decoded decoded = GalaxyMapWire.decode(packet);
        require(decoded.strategicSummary() != null && "SOLO".equals(decoded.strategicSummary().ownerId()),
                "strategic summary did not survive galaxy wire round trip");
        StrategicSummaryRegistry.State registry = StrategicSummaryRegistry.state(world);
        require(registry.initialized() && "SOLO".equals(registry.ownerId()),
                "decoded strategic summary was not installed for the local owner");

        StrategicSummarySnapshot foreignOwner = StrategicSummarySnapshot.empty("ENEMY");
        String forgedOwner = packet.substring(0, packet.indexOf("|E,")) + "|E," + StrategicSummaryWire.encodeToken(foreignOwner);
        expectRejected(() -> GalaxyMapWire.decode(forgedOwner),
                "galaxy wire accepted a strategic summary for another owner");

        StrategicSummarySnapshot foreignAsset = new StrategicSummarySnapshot("SOLO", List.of(),
                List.of(new StrategicFleetRow("ENEMY:7", world.activeSystemId(), Rules.STARTING_SHIP, "Foreign", "IDLE", 1, 1, 0, 0)),
                List.of(), List.of(), List.of(), List.of(), false);
        String foreignToken = StrategicSummaryWire.encodeToken(foreignAsset);
        expectRejected(() -> StrategicSummaryWire.decodeToken(foreignToken),
                "strategic wire accepted a foreign fleet row inside an owner summary");
    }

    private static void validatePresentationHook(World world) {
        require(EmpireOverviewOverlay.HOTKEY == KeyEvent.VK_F7, "strategic overview hotkey is not F7");
        EmpireOverviewOverlay.ensureInstalled(world, null);
        require(!EmpireOverviewOverlay.visibleForTest(world),
                "strategic overview unexpectedly opened during headless installation");
        GameCamera camera = new GameCamera();
        camera.update(world, 1280, 900, 0.016);
        require(GameCamera.forWorld(world) == camera,
                "strategic navigation cannot resolve the active game camera");
    }

    private static void expectRejected(Runnable action, String message) {
        try { action.run(); }
        catch (SnapshotDecodeException expected) { return; }
        throw new IllegalStateException(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
