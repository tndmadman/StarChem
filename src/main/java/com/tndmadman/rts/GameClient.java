package com.tndmadman.rts;

final class GameClient {
    private static final double WORMHOLE_TOUCH_REQUEST_SECONDS = 0.4;
    private static final double LOCAL_WORMHOLE_FEEDBACK_COOLDOWN = 0.75;
    private final World world;
    private final PeerNetwork network;
    private double wormholeTouchRequestCooldown;

    private GameClient(World world, PeerNetwork network) {
        this.world = world;
        this.network = network;
    }

    static GameClient forNetwork(World world, PeerNetwork network) {
        if (world == null || network == null) return null;
        return network.statusLine().startsWith("CLIENT") ? new GameClient(world, network) : null;
    }

    void tick(double dt) {
        MultiplayerCommsOverlay.ensureInstalled(world);
        EmpireOverviewOverlay.ensureInstalled(world, network);
        SystemAudio.listenTo(world);
        ClientEnvironmentSync.advance(world, dt);
        ClientPrediction.update(world, dt);
        wormholeTouchRequestCooldown = Math.max(0, wormholeTouchRequestCooldown - dt);
        String playerId = network.localPlayerId();
        WormholeTouchRequest request = WormholeTouchRequest.detect(world, playerId);
        if (wormholeTouchRequestCooldown <= 0 && request != null && request.valid()) {
            announceLocalWormholeTransit(request);
            network.wormholeTouch(request);
            wormholeTouchRequestCooldown = WORMHOLE_TOUCH_REQUEST_SECONDS;
        }
    }

    private void announceLocalWormholeTransit(WormholeTouchRequest request) {
        WormholeGate gate = WormholeTouchRequest.gateById(world, request.gateId());
        if (gate != null) {
            world.status = "Wormhole transit: entering " + StarSystems.get(gate.toSystemId).name() + ".";
        }
        Unit unit = world.units.get(Unit.key(request.playerId(), request.unitId()));
        if (unit != null) unit.wormholeCooldown = Math.max(unit.wormholeCooldown, LOCAL_WORMHOLE_FEEDBACK_COOLDOWN);
    }

    String statusLine() {
        return network.statusLine();
    }
}
