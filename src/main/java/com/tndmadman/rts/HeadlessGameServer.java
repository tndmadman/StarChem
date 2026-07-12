package com.tndmadman.rts;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class HeadlessGameServer {
    final World world;
    final PeerNetwork network;
    private final AtomicBoolean stopped = new AtomicBoolean();

    private HeadlessGameServer(World world, PeerNetwork network) {
        this.world = world;
        this.network = network;
    }

    static HeadlessGameServer start(Config config) throws IOException {
        if (config == null || !config.dedicatedServerMode()) {
            throw new IllegalArgumentException("HeadlessGameServer requires dedicated server configuration.");
        }
        GalaxyRuntimeOptions.configure(config);
        World world = new World(config.playerName, config.disabledNpcFactionIds, config.systemId, false);
        DevTimerSettings.configure(world, config.disableProductionTimers);
        PeerNetwork network = PeerNetwork.start(config, world);
        if (network == null) throw new IOException("Dedicated server network did not start.");
        return new HeadlessGameServer(world, network);
    }

    void tick(double dt) {
        if (stopped.get()) return;
        network.updateServerWorlds(dt);
        network.tick();
    }

    String statusLine() {
        return stopped.get() ? "SERVER STOPPED" : network.statusLine();
    }

    void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        network.shutdown();
        System.out.println("Dedicated server stopped.");
    }
}
