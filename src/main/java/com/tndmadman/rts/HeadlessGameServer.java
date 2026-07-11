package com.tndmadman.rts;

import java.io.IOException;

final class HeadlessGameServer {
    final World world;
    final PeerNetwork network;

    private HeadlessGameServer(World world, PeerNetwork network) {
        this.world = world;
        this.network = network;
    }

    static HeadlessGameServer start(Config config) throws IOException {
        World world = new World(config.playerName, config.disabledNpcFactionIds, config.systemId, false);
        DevTimerSettings.configure(world, config.disableProductionTimers);
        PeerNetwork network = PeerNetwork.start(config, world);
        return new HeadlessGameServer(world, network);
    }

    void tick(double dt) {
        network.updateServerWorlds(dt);
        network.tick();
    }

    void stop() {
        network.shutdown();
    }
}
