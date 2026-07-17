from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one match, found {count}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


CLIENT = "src/main/java/com/tndmadman/rts/PeerClientSide.java"
REMOTE = "src/main/java/com/tndmadman/rts/TcpRemoteSystemVisibilityValidator.java"
RECONNECT = "src/main/java/com/tndmadman/rts/TcpReconnectIntegrationValidator.java"

replace_once(
    CLIENT,
    '''    void readSnapshot(String message) {
        try {
            Snapshot snapshot = SnapshotReader.read(message);
            ResourceNetDebug.clientReceive("REGULAR", snapshot, lastSnapshotSequence, viewSnapshotMode);
            if (holdingDifferentView(snapshot)) return;
            if (stale(snapshot, "REGULAR")) return;
            if (viewSnapshotMode) WorldNetAccess.applyView(world, snapshot);
            else WorldNetAccess.apply(world, snapshot);
            acceptSnapshot(snapshot);
        } catch (SnapshotDecodeException ex) {
            rejectSnapshot(ex);
        }
    }
''',
    '''    void readSnapshot(String message) {
        try {
            Snapshot snapshot = SnapshotReader.read(message);
            ResourceNetDebug.clientReceive("REGULAR", snapshot, lastSnapshotSequence, viewSnapshotMode);
            if (holdingDifferentView(snapshot)) return;
            if (stale(snapshot, "REGULAR")) return;
            boolean applyAsView = shouldApplyAsView(snapshot);
            if (applyAsView) WorldNetAccess.applyView(world, snapshot);
            else WorldNetAccess.apply(world, snapshot);
            acceptSnapshot(snapshot);
            updateViewModeFromRegularSnapshot(snapshot);
        } catch (SnapshotDecodeException ex) {
            rejectSnapshot(ex);
        }
    }
''',
)

replace_once(
    CLIENT,
    '''            if (SyncFrame.isResourceCorrection(message)) {
                ResourceNetDebug.clientReceive("FULL_CORRECTION", snapshot, lastSnapshotSequence, viewSnapshotMode);
                if (holdingDifferentView(snapshot)) return;
                if (stale(snapshot, "FULL_CORRECTION")) return;
                WorldNetAccess.applyResourceCorrection(world, snapshot, viewSnapshotMode);
                acceptSnapshot(snapshot);
                return;
            }
''',
    '''            if (SyncFrame.isResourceCorrection(message)) {
                ResourceNetDebug.clientReceive("FULL_CORRECTION", snapshot, lastSnapshotSequence, viewSnapshotMode);
                if (holdingDifferentView(snapshot)) return;
                if (stale(snapshot, "FULL_CORRECTION")) return;
                boolean applyAsView = shouldApplyAsView(snapshot);
                WorldNetAccess.applyResourceCorrection(world, snapshot, applyAsView);
                acceptSnapshot(snapshot);
                updateViewModeFromRegularSnapshot(snapshot);
                return;
            }
''',
)

replace_once(
    CLIENT,
    '''            WorldNetAccess.applyFullView(world, snapshot);
            acceptSnapshot(snapshot);
            if (requestedView && snapshot.systemId() != null && !snapshot.systemId().isBlank()) {
                viewedSystemId = snapshot.systemId();
                viewRequestPending = false;
                viewSnapshotMode = !WorldNetAccess.hasPlayerAssets(snapshot, localPlayerId);
                viewRequestFallbackSystemId = "";
                viewRequestFallbackMode = false;
            }
            if (!requestedView && WorldNetAccess.hasPlayerAssets(snapshot, localPlayerId)) {
                viewedSystemId = world.activeSystemId();
                viewSnapshotMode = false;
            }
            completeInitialSync();
''',
    '''            WorldNetAccess.applyFullView(world, snapshot);
            acceptSnapshot(snapshot);
            acceptAuthoritativeView(snapshot);
            if (requestedView) {
                viewRequestPending = false;
                viewRequestFallbackSystemId = "";
                viewRequestFallbackMode = false;
            }
            completeInitialSync();
''',
)

replace_once(
    CLIENT,
    '''    private boolean holdingDifferentView(Snapshot snapshot) {
''',
    '''    private boolean shouldApplyAsView(Snapshot snapshot) {
        return viewSnapshotMode || currentViewSnapshot(snapshot)
                && !WorldNetAccess.hasPlayerAssets(snapshot, localPlayerId);
    }

    private void updateViewModeFromRegularSnapshot(Snapshot snapshot) {
        if (!currentViewSnapshot(snapshot)) return;
        viewedSystemId = snapshot.systemId();
        viewSnapshotMode = !WorldNetAccess.hasPlayerAssets(snapshot, localPlayerId);
    }

    private void acceptAuthoritativeView(Snapshot snapshot) {
        if (snapshot == null || snapshot.systemId() == null || snapshot.systemId().isBlank()) return;
        viewedSystemId = snapshot.systemId();
        viewSnapshotMode = !WorldNetAccess.hasPlayerAssets(snapshot, localPlayerId);
    }

    private boolean currentViewSnapshot(Snapshot snapshot) {
        if (snapshot == null || snapshot.systemId() == null || snapshot.systemId().isBlank()) return false;
        return snapshot.systemId().equals(viewedSystemId)
                || snapshot.systemId().equals(world.activeSystemId());
    }

    private boolean holdingDifferentView(Snapshot snapshot) {
''',
)

replace_once(
    REMOTE,
    '''            String source = viewer.world().activeSystemId();
            String target = harness.serverWorld.playerHomeSystemId(ownerId);
''',
    '''            String source = viewer.world().activeSystemId();
            String target = harness.serverWorld.playerHomeSystemId(ownerId);
            validateViewSurvivesLastLocalAssetRemoval(harness, viewer, viewerId, source);
''',
)

replace_once(
    REMOTE,
    '''    private static void validateCorsairViewRemainsLive(TcpIntegrationHarness harness,
''',
    '''    private static void validateViewSurvivesLastLocalAssetRemoval(TcpIntegrationHarness harness,
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

    private static void validateCorsairViewRemainsLive(TcpIntegrationHarness harness,
''',
)

replace_once(
    RECONNECT,
    '''    public static void main(String[] args) throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host();
''',
    '''    public static void main(String[] args) throws Exception {
        validateNormalReconnect();
        validateRemoteViewReconnect();
        System.out.println("StarChem TCP automatic reconnect validation passed.");
    }

    private static void validateNormalReconnect() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host();
''',
)

replace_once(
    RECONNECT,
    '''            System.out.println("StarChem TCP automatic reconnect validation passed.");
        }
    }
}
''',
    '''        }
    }

    private static void validateRemoteViewReconnect() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host();
             TcpFaultProxy proxy = new TcpFaultProxy(InetAddress.getLoopbackAddress(), harness.serverConfig.port)) {
            TcpIntegrationHarness.TestClient reconnecting = harness.addProxiedClient("TCP Remote View Reconnect", proxy);
            TcpIntegrationHarness.TestClient observer = harness.addClient("TCP Remote View Observer");
            harness.awaitJoined(reconnecting);
            harness.awaitJoined(observer);

            String playerId = reconnecting.playerId();
            String remoteSystem = StarSystems.CORSAIR_SYSTEM_ID;
            reconnecting.network().viewSystem(playerId, remoteSystem);
            harness.await(() -> !reconnecting.network().clientViewSwitchPending()
                            && remoteSystem.equals(reconnecting.network().clientViewedSystemId())
                            && remoteSystem.equals(reconnecting.world().activeSystemId())
                            && !currentSystemHasPlayerAssets(reconnecting.world(), playerId),
                    12_000, "client did not establish a remote view before reconnect validation");

            proxy.dropActiveConnection();
            harness.await(() -> reconnecting.network().clientReconnecting(), 5_000,
                    "remote-view client did not begin reconnecting after socket loss");
            harness.await(() -> !harness.serverNetwork.serverSessionConnected(playerId), 5_000,
                    "server did not detach the remote-view client connection");
            harness.await(() -> reconnecting.network().clientConnected()
                            && harness.serverNetwork.serverSessionConnected(playerId)
                            && remoteSystem.equals(reconnecting.network().clientViewedSystemId())
                            && remoteSystem.equals(reconnecting.world().activeSystemId())
                            && !currentSystemHasPlayerAssets(reconnecting.world(), playerId),
                    15_000, "resumed session did not restore its authoritative remote view");

            long resumedSequence = reconnecting.network().clientSnapshotSequence();
            harness.await(() -> reconnecting.network().clientSnapshotSequence() > resumedSequence
                            && remoteSystem.equals(reconnecting.network().clientViewedSystemId())
                            && remoteSystem.equals(reconnecting.world().activeSystemId())
                            && !currentSystemHasPlayerAssets(reconnecting.world(), playerId),
                    8_000, "remote view did not remain synchronized after session resume");
            TcpIntegrationHarness.require(observer.network().clientConnected(),
                    "observer was affected by remote-view reconnect");
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
}
''',
)

print("Phase 3 networking repairs applied.")
