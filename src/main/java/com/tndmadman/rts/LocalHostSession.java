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
        GalaxyRuntimeOptions.configure(hostConfig);
        World serverWorld = new World(hostConfig.playerName, hostConfig.disabledNpcFactionIds, hostConfig.systemId, false);
        DevTimerSettings.configure(serverWorld, hostConfig.disableProductionTimers);
        PlayerRegistry.activate(serverWorld);
        PeerNetwork serverNetwork = PeerNetwork.start(hostConfig, serverWorld);
        Config clientConfig = Config.localHostClient(hostConfig);
        SessionTokenStore.rememberAuthDigestForProcess(clientConfig,
                PasswordAuth.verifier(clientConfig.playerName, "local-host"));
        GalaxyRuntimeOptions.configure(clientConfig);
        World clientWorld = new World(clientConfig.playerName, clientConfig.disabledNpcFactionIds, clientConfig.systemId, false);
        DevTimerSettings.configure(clientWorld, clientConfig.disableProductionTimers);
        PlayerRegistry.activate(clientWorld);
        PeerNetwork clientNetwork = PeerNetwork.start(clientConfig, clientWorld);
        LocalHostSession session = new LocalHostSession(serverWorld, serverNetwork, clientWorld, clientNetwork);
        session.timer.start();
        return session;
    }

    PeerNetwork devAuthorityNetwork() { return serverNetwork; }

    void stop() {
        timer.stop();
        PlayerRegistry.activate(clientWorld);
        clientNetwork.shutdown();
        PlayerRegistry.activate(serverWorld);
        serverNetwork.shutdown();
    }

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNanos) / 1_000_000_000.0);
        lastNanos = now;
        PlayerRegistry.activate(serverWorld);
        serverNetwork.updateServerWorlds(dt);
        serverNetwork.tick();
        PlayerRegistry.activate(clientWorld);
        clientNetwork.tick();
    }
}
