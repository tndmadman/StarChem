package com.tndmadman.rts;

final class GameClient {
    private final World world;
    private final PeerNetwork network;

    private GameClient(World world, PeerNetwork network) {
        this.world = world;
        this.network = network;
    }

    static GameClient forNetwork(World world, PeerNetwork network) {
        if (world == null || network == null) return null;
        return network.statusLine().startsWith("CLIENT") ? new GameClient(world, network) : null;
    }

    void tick(double dt) {
        world.updateEnvironment(dt);
        ClientPrediction.update(world, dt);
        if (world.transferTouchingShips()) network.wormholeTouch(PlayerRegistry.localId());
    }

    String statusLine() {
        return network.statusLine();
    }
}
