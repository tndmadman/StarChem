package com.tndmadman.rts;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

final class HeadlessGameServer {
    final World world;
    final PeerNetwork network;
    private final Config config;
    private final ServerSaveStore saves;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private long nextAutosaveNanos;

    private HeadlessGameServer(World world, PeerNetwork network, Config config, ServerSaveStore saves) {
        this.world = world;
        this.network = network;
        this.config = config;
        this.saves = saves;
        scheduleNextAutosave();
    }

    static HeadlessGameServer start(Config config) throws IOException {
        if (config == null || !config.dedicatedServerMode()) {
            throw new IllegalArgumentException("HeadlessGameServer requires dedicated server configuration.");
        }
        GalaxyRuntimeOptions.configure(config);
        ServerSaveStore saves = new ServerSaveStore(config.saveDir, config.saveName, config.backupCount);
        Optional<World> loaded = config.newWorld ? Optional.empty() : saves.load(config);
        World world = loaded.orElseGet(() -> new World(config.playerName, config.disabledNpcFactionIds, config.systemId, false));
        if (loaded.isPresent()) System.out.println("Loaded server save '" + config.saveName + "'.");
        else System.out.println(config.newWorld ? "Starting a new server world by request." : "No server save found; starting a new world.");
        DevTimerSettings.configure(world, config.disableProductionTimers);
        PeerNetwork network = PeerNetwork.start(config, world, saves.loadedPlayerSessions());
        if (network == null) throw new IOException("Dedicated server network did not start.");
        return new HeadlessGameServer(world, network, config, saves);
    }

    void tick(double dt) {
        if (stopped.get()) return;
        network.updateServerWorlds(dt);
        network.tick();
        autosaveIfDue();
    }

    String statusLine() {
        return stopped.get() ? "SERVER STOPPED" : network.statusLine();
    }

    void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        saveNow("shutdown");
        network.shutdown();
        System.out.println("Dedicated server stopped.");
    }

    private void autosaveIfDue() {
        if (config.autosaveSeconds <= 0 || System.nanoTime() < nextAutosaveNanos) return;
        saveNow("autosave");
        scheduleNextAutosave();
    }

    private void scheduleNextAutosave() {
        nextAutosaveNanos = config.autosaveSeconds <= 0
                ? Long.MAX_VALUE
                : System.nanoTime() + config.autosaveSeconds * 1_000_000_000L;
    }

    private void saveNow(String reason) {
        try {
            saves.save(world, config, reason, network.persistentPlayerSessions());
            if (!"autosave".equals(reason)) System.out.println("Server save completed (" + reason + ").");
        } catch (IOException ex) {
            System.err.println("Server save failed (" + reason + "): " + ex.getMessage());
        }
    }
}
