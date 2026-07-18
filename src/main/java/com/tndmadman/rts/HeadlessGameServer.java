package com.tndmadman.rts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

final class HeadlessGameServer {
    private static final int MAX_CONSOLE_COMMANDS_PER_TICK = 32;

    final World world;
    final PeerNetwork network;
    private final Config config;
    private final ServerSaveStore saves;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private long nextAutosaveNanos;
    private ServerConsole console;
    private ServerCommandDispatcher consoleCommands;

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

    void attachConsole(ServerConsole console) {
        if (console == null) return;
        if (stopped.get()) {
            console.close();
            return;
        }
        ServerConsole previous = this.console;
        if (previous != null) previous.close();
        this.console = console;
        this.consoleCommands = new ServerCommandDispatcher(new ServerCommandDispatcher.Target() {
            @Override public String status() { return consoleStatusLine(); }
            @Override public List<String> players() { return playerStatusLines(); }
            @Override public boolean save() { return saveNow("manual-console"); }
            @Override public void stop() { HeadlessGameServer.this.stop(); }
            @Override public boolean running() { return HeadlessGameServer.this.running(); }
        }, System.out, System.err);
    }

    void tick(double dt) {
        drainConsoleCommands();
        if (stopped.get()) return;
        network.updateServerWorlds(dt);
        network.tick();
        autosaveIfDue();
    }

    boolean running() {
        return !stopped.get();
    }

    String statusLine() {
        return stopped.get() ? "SERVER STOPPED" : network.statusLine();
    }

    void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        ServerConsole activeConsole = console;
        console = null;
        consoleCommands = null;
        if (activeConsole != null) activeConsole.close();
        saveNow("shutdown");
        network.shutdown();
        System.out.println("Dedicated server stopped.");
    }

    private void drainConsoleCommands() {
        ServerConsole activeConsole = console;
        ServerCommandDispatcher dispatcher = consoleCommands;
        if (activeConsole == null || dispatcher == null) return;
        for (int i = 0; i < MAX_CONSOLE_COMMANDS_PER_TICK; i++) {
            String command = activeConsole.poll();
            if (command == null) return;
            dispatcher.execute(command);
            if (stopped.get()) return;
        }
    }

    private String consoleStatusLine() {
        String autosave = config.autosaveSeconds <= 0
                ? "autosave disabled"
                : "autosave every " + config.autosaveSeconds + "s";
        return statusLine() + " | save " + config.saveName + " | " + autosave;
    }

    private List<String> playerStatusLines() {
        List<PersistentPlayerSession> sessions = new ArrayList<>(network.persistentPlayerSessions());
        sessions.sort(Comparator.comparing(PersistentPlayerSession::playerId));
        List<String> lines = new ArrayList<>();
        for (PersistentPlayerSession session : sessions) {
            String state = network.serverSessionConnected(session.playerId()) ? "connected" : "retained";
            lines.add(session.playerId() + " | " + session.name() + " | " + state);
        }
        return List.copyOf(lines);
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

    private boolean saveNow(String reason) {
        try {
            saves.save(world, config, reason, network.persistentPlayerSessions());
            if (!"autosave".equals(reason)) System.out.println("Server save completed (" + reason + ").");
            return true;
        } catch (IOException ex) {
            System.err.println("Server save failed (" + reason + "): " + ex.getMessage());
            return false;
        }
    }
}
