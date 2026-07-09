package com.tndmadman.rts;

final class GameServer {
    private final World world;
    private final PeerNetwork network;

    private GameServer(World world, PeerNetwork network) {
        this.world = world;
        this.network = network;
    }

    static GameServer forNetwork(World world, PeerNetwork network) {
        if (world == null || network == null) return null;
        return network.statusLine().startsWith("HOST") ? new GameServer(world, network) : null;
    }

    void tick(double dt) {
        network.updateServerWorlds(dt);
    }

    String statusLine() {
        return network.statusLine();
    }
}
