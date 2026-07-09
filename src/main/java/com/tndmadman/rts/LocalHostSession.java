package com.tndmadman.rts;

import javax.swing.Timer;
import java.io.IOException;

final class LocalHostSession {
    final World clientWorld;
    final PeerNetwork clientNetwork;
    private final PeerNetwork serverNetwork;
    private final World serverWorld;
    private final Timer timer;
    private long lastNanos = System.nanoTime();

    private LocalHostSession(World serverWorld, PeerNetwork serverNetwork, World clientWorld, PeerNetwork clientNetwork) {
        this.serverWorld = serverWorld;
        this.serverNetwork = serverNetwork;
        this.clientWorld = clientWorld;
        this.clientNetwork = clientNetwork;
        this.timer = new Timer(16, e -> tick());
    }

    static LocalHostSession start(Config hostConfig) throws IOException {
        World serverWorld = new World(hostConfig.playerName, hostConfig.disabledNpcFactionIds, hostConfig.systemId, false);
        PeerNetwork serverNetwork = PeerNetwork.start(hostConfig, serverWorld);
        Config clientConfig = Config.join(hostConfig.playerName, "127.0.0.1", hostConfig.port, hostConfig.devMode, hostConfig.disabledNpcFactionIds, hostConfig.systemId);
        World clientWorld = new World(clientConfig.playerName, clientConfig.disabledNpcFactionIds, clientConfig.systemId, false);
        PeerNetwork clientNetwork = PeerNetwork.start(clientConfig, clientWorld);
        LocalHostSession session = new LocalHostSession(serverWorld, serverNetwork, clientWorld, clientNetwork);
        session.timer.start();
        return session;
    }

    PeerNetwork devAuthorityNetwork() { return serverNetwork; }

    void stop() {
        timer.stop();
        clientNetwork.shutdown();
        serverNetwork.shutdown();
    }

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNanos) / 1_000_000_000.0);
        lastNanos = now;
        serverNetwork.updateServerWorlds(dt);
        serverNetwork.tick();
        clientNetwork.tick();
    }
}
