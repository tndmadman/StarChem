package com.tndmadman.rts;

import javax.swing.Timer;
import java.io.IOException;
import java.util.List;

final class LocalHostSession {
    private static final String HOST_PLAYER_ID = "P1";
    private static final int HOST_PLAYER_COLOR = 0x50BEFF;

    final World clientWorld;
    final PeerNetwork clientNetwork;
    private final PeerNetwork serverNetwork;
    private final World serverWorld;
    private final Config clientConfig;
    private final Timer timer;
    private long lastNanos = System.nanoTime();

    private LocalHostSession(World serverWorld, PeerNetwork serverNetwork, World clientWorld,
                             PeerNetwork clientNetwork, Config clientConfig) {
        this.serverWorld = serverWorld;
        this.serverNetwork = serverNetwork;
        this.clientWorld = clientWorld;
        this.clientNetwork = clientNetwork;
        this.clientConfig = clientConfig;
        this.timer = new Timer(16, e -> tick());
    }

    static LocalHostSession start(Config hostConfig) throws IOException {
        String processVerifier = PasswordAuth.newProcessVerifier(hostConfig.playerName);
        PersistentPlayerSession reservedHost = processHostSession(hostConfig.playerName, processVerifier);
        PeerNetwork serverNetwork = null;
        PeerNetwork clientNetwork = null;
        Config clientConfig = null;
        try {
            GalaxyRuntimeOptions.configure(hostConfig);
            World serverWorld = new World(hostConfig.playerName, hostConfig.disabledNpcFactionIds, hostConfig.systemId, false);
            DevTimerSettings.configure(serverWorld, hostConfig.disableProductionTimers);
            PlayerRegistry.activate(serverWorld);
            prepareHostWorld(serverWorld);
            serverNetwork = PeerNetwork.start(hostConfig, serverWorld, List.of(reservedHost));
            PlayerRegistry.activate(serverWorld);
            if (!serverWorld.hasLiveAssets(HOST_PLAYER_ID)) WorldNetAccess.addPeerGroup(serverWorld, HOST_PLAYER_ID);

            clientConfig = Config.localHostClient(hostConfig);
            SessionTokenStore.rememberAuthDigestForProcess(clientConfig, processVerifier);
            GalaxyRuntimeOptions.configure(clientConfig);
            World clientWorld = new World(clientConfig.playerName, clientConfig.disabledNpcFactionIds, clientConfig.systemId, false);
            DevTimerSettings.configure(clientWorld, clientConfig.disableProductionTimers);
            PlayerRegistry.activate(clientWorld);
            clientNetwork = PeerNetwork.start(clientConfig, clientWorld);
            LocalHostSession session = new LocalHostSession(serverWorld, serverNetwork, clientWorld, clientNetwork, clientConfig);
            session.timer.start();
            return session;
        } catch (IOException | RuntimeException ex) {
            if (clientNetwork != null) clientNetwork.shutdown();
            if (serverNetwork != null) serverNetwork.shutdown();
            if (clientConfig != null) SessionTokenStore.clear(clientConfig);
            throw ex;
        }
    }

    static void prepareHostWorld(World world) {
        if (world == null) throw new IllegalArgumentException("Graphical host world is required.");
        world.ensurePlayerHome(HOST_PLAYER_ID, true);
    }

    static PersistentPlayerSession processHostSession(String playerName, String verifier) {
        if (!PasswordAuth.validVerifier(verifier)) {
            throw new IllegalArgumentException("Graphical host verifier is invalid.");
        }
        byte[] salt = PasswordAuth.newSalt();
        byte[] passwordDigest = PasswordAuth.serverDigest(PasswordAuth.decodeVerifier(verifier), salt);
        byte[] placeholderTokenDigest = PasswordAuth.tokenDigest(PasswordAuth.newNonce());
        return new PersistentPlayerSession(HOST_PLAYER_ID, Config.clean(playerName), HOST_PLAYER_COLOR,
                salt, passwordDigest, placeholderTokenDigest, new byte[0], 0);
    }

    PeerNetwork devAuthorityNetwork() { return serverNetwork; }

    void stop() {
        timer.stop();
        try {
            PlayerRegistry.activate(clientWorld);
            clientNetwork.shutdown();
        } finally {
            try {
                PlayerRegistry.activate(serverWorld);
                serverNetwork.shutdown();
            } finally {
                AudioEventCenter.discard(clientWorld);
                AudioEventCenter.discard(serverWorld);
                SessionTokenStore.clear(clientConfig);
            }
        }
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
