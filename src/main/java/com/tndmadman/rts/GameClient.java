package com.tndmadman.rts;

final class GameClient {
    private static final double WORMHOLE_TOUCH_REQUEST_SECONDS = 0.4;
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
        // Do not advance the world environment on the client render tick.
        // Server snapshots carry authoritative systemTime; WorldNetAccess.apply()
        // advances the client environment by that snapshot delta instead.
        ClientPrediction.update(world, dt);
        wormholeTouchRequestCooldown = Math.max(0, wormholeTouchRequestCooldown - dt);
        String playerId = network.localPlayerId();
        WormholeTouchRequest request = WormholeTouchRequest.detect(world, playerId);
        if (wormholeTouchRequestCooldown <= 0 && request != null && request.valid()) {
            network.wormholeTouch(request);
            wormholeTouchRequestCooldown = WORMHOLE_TOUCH_REQUEST_SECONDS;
        }
    }

    String statusLine() {
        return network.statusLine();
    }
}
