package com.tndmadman.rts;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Set;

final class Config {
    static final String RAIDERS_ID = "NPC_RAIDERS";
    static final String FREE_MINERS_ID = "NPC_MINERS";
    static final String CORSAIRS_ID = "NPC_CORSAIRS";
    private static final String DEFAULT_HOST = "127.0.0.1";

    final String playerName;
    final boolean showLobby;
    final boolean hostMode;
    final boolean dedicatedServer;
    final boolean localHostClient;
    final boolean devMode;
    final String devToken;
    final boolean disableProductionTimers;
    final int port;
    final InetSocketAddress serverAddress;
    final Set<String> disabledNpcFactionIds;
    final String systemId;
    final int galaxyCopies;
    final Path saveDir;
    final String saveName;
    final int autosaveSeconds;
    final int backupCount;
    final boolean newWorld;

    private Config(String playerName, boolean showLobby, boolean hostMode, boolean dedicatedServer, boolean localHostClient, boolean devMode,
                   String devToken, boolean disableProductionTimers, int port, InetSocketAddress serverAddress,
                   Set<String> disabledNpcFactionIds, String systemId, int galaxyCopies, Path saveDir,
                   String saveName, int autosaveSeconds, int backupCount, boolean newWorld) {
        this.playerName = playerName;
        this.showLobby = showLobby;
        this.hostMode = hostMode;
        this.dedicatedServer = dedicatedServer;
        this.localHostClient = localHostClient;
        this.devMode = devMode;
        this.devToken = DevAccessPolicy.normalizeToken(devToken);
        this.disableProductionTimers = devMode && disableProductionTimers;
        this.port = port;
        this.serverAddress = serverAddress;
        this.disabledNpcFactionIds = disabledNpcFactionIds == null ? Set.of() : Set.copyOf(disabledNpcFactionIds);
        this.systemId = cleanSystem(systemId);
        this.galaxyCopies = clampGalaxyCopies(galaxyCopies);
        this.saveDir = saveDir == null ? Path.of("saves") : saveDir;
        this.saveName = cleanSaveName(saveName);
        this.autosaveSeconds = Math.max(0, autosaveSeconds);
        this.backupCount = Math.max(1, Math.min(24, backupCount));
        this.newWorld = newWorld;
    }

    static Config parse(String[] suppliedArgs) {
        String[] args = suppliedArgs == null ? new String[0] : suppliedArgs;
        if (args.length == 0) return lobby();
        String name = defaultName();
        String system = StarSystems.DEFAULT_SYSTEM_ID;
        boolean host = false;
        boolean dedicated = false;
        boolean dev = false;
        String devToken = "";
        Path devTokenFile = null;
        boolean inlineDevToken = false;
        boolean disableProductionTimers = false;
        int port = 0;
        int galaxyCopies = 1;
        Path saveDir = Path.of("saves");
        String saveName = "server";
        int autosaveSeconds = 60;
        int backupCount = 5;
        boolean newWorld = false;
        InetSocketAddress server = null;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            switch (option) {
                case "--name", "--id" -> name = clean(requiredValue(args, ++i, option));
                case "--system" -> system = cleanSystem(requiredValue(args, ++i, option));
                case "--galaxy-copies" -> galaxyCopies = parseGalaxyCopies(requiredValue(args, ++i, option));
                case "--save-dir" -> saveDir = Path.of(requiredValue(args, ++i, option));
                case "--save-name" -> saveName = requiredValue(args, ++i, option);
                case "--autosave-seconds" -> autosaveSeconds = parseNonNegativeInt(requiredValue(args, ++i, option), "Autosave seconds");
                case "--backup-count" -> backupCount = parsePositiveInt(requiredValue(args, ++i, option), "Backup count");
                case "--new-world" -> newWorld = true;
                case "--dev" -> { dev = true; disableProductionTimers = true; }
                case "--dev-token" -> {
                    if (inlineDevToken || devTokenFile != null) throw conflictingDevTokenSources();
                    devToken = DevAccessPolicy.requireToken(requiredValue(args, ++i, option));
                    inlineDevToken = true;
                    System.err.println("WARNING: --dev-token exposes a reusable secret in process arguments; use --dev-token-file instead.");
                }
                case "--dev-token-file" -> {
                    if (inlineDevToken || devTokenFile != null) throw conflictingDevTokenSources();
                    devTokenFile = Path.of(requiredValue(args, ++i, option));
                }
                case "--disable-timers" -> disableProductionTimers = true;
                case "--enable-timers" -> disableProductionTimers = false;
                case "--server" -> {
                    dedicated = true;
                    host = true;
                    if (hasOptionalValue(args, i + 1)) port = parsePort(args[++i]);
                }
                case "--host" -> {
                    host = true;
                    port = parsePort(requiredValue(args, ++i, option));
                }
                case "--join" -> {
                    String hostName = parseHost(requiredValue(args, ++i, option));
                    int remotePort = parsePort(requiredValue(args, ++i, option));
                    server = new InetSocketAddress(hostName, remotePort);
                }
                case "--solo" -> { }
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            }
        }
        if (devTokenFile != null) devToken = DevTokenSource.load(devTokenFile);
        if (dedicated) return dedicatedServer(name, port == 0 ? 50000 : port, dev, disableProductionTimers, Set.of(), system, devToken, galaxyCopies, saveDir, saveName, autosaveSeconds, backupCount, newWorld);
        if (host) return host(name, port == 0 ? 50000 : port, dev, disableProductionTimers, Set.of(), system, devToken, galaxyCopies);
        if (server != null) return join(name, server.getHostString(), server.getPort(), dev, disableProductionTimers, Set.of(), system, devToken, galaxyCopies);
        return solo(name, dev, disableProductionTimers, Set.of(), system, devToken, galaxyCopies);
    }

    private static IllegalArgumentException conflictingDevTokenSources() {
        return new IllegalArgumentException("Specify exactly one developer token source: --dev-token-file or legacy --dev-token.");
    }

    private static String requiredValue(String[] args, int index, String option) {
        if (index >= args.length || args[index] == null || args[index].isBlank() || args[index].startsWith("--")) {
            throw new IllegalArgumentException(option + " requires a value.");
        }
        return args[index];
    }

    private static boolean hasOptionalValue(String[] args, int index) {
        return index < args.length && args[index] != null && !args[index].isBlank() && !args[index].startsWith("--");
    }

    static Config lobby() { return new Config(defaultName(), true, false, false, false, false, "", false, 0, null, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, 1, Path.of("saves"), "server", 60, 5, false); }
    static Config solo(String name) { return solo(name, false); }
    static Config host(String name, int port) { return host(name, port, false); }
    static Config join(String name, String host, int port) { return join(name, host, port, false); }
    static Config solo(String name, boolean dev) { return solo(name, dev, Set.of()); }
    static Config host(String name, int port, boolean dev) { return host(name, port, dev, Set.of()); }
    static Config join(String name, String host, int port, boolean dev) { return join(name, host, port, dev, Set.of()); }
    static Config solo(String name, boolean dev, Set<String> disabledNpcFactionIds) { return solo(name, dev, disabledNpcFactionIds, StarSystems.DEFAULT_SYSTEM_ID); }
    static Config host(String name, int port, boolean dev, Set<String> disabledNpcFactionIds) { return host(name, port, dev, disabledNpcFactionIds, StarSystems.DEFAULT_SYSTEM_ID); }
    static Config join(String name, String host, int port, boolean dev, Set<String> disabledNpcFactionIds) { return join(name, host, port, dev, disabledNpcFactionIds, StarSystems.DEFAULT_SYSTEM_ID); }
    static Config solo(String name, boolean dev, Set<String> disabledNpcFactionIds, String systemId) { return solo(name, dev, dev, disabledNpcFactionIds, systemId); }
    static Config host(String name, int port, boolean dev, Set<String> disabledNpcFactionIds, String systemId) { return host(name, port, dev, dev, disabledNpcFactionIds, systemId); }
    static Config dedicatedServer(String name, int port, boolean dev, Set<String> disabledNpcFactionIds, String systemId) { return dedicatedServer(name, port, dev, dev, disabledNpcFactionIds, systemId); }
    static Config join(String name, String host, int port, boolean dev, Set<String> disabledNpcFactionIds, String systemId) { return join(name, host, port, dev, dev, disabledNpcFactionIds, systemId); }
    static Config solo(String name, boolean dev, Set<String> disabledNpcFactionIds, String systemId, int galaxyCopies) { return solo(name, dev, dev, disabledNpcFactionIds, systemId, "", galaxyCopies); }
    static Config host(String name, int port, boolean dev, Set<String> disabledNpcFactionIds, String systemId, int galaxyCopies) { return host(name, port, dev, dev, disabledNpcFactionIds, systemId, "", galaxyCopies); }
    static Config dedicatedServer(String name, int port, boolean dev, Set<String> disabledNpcFactionIds, String systemId, int galaxyCopies) { return dedicatedServer(name, port, dev, dev, disabledNpcFactionIds, systemId, "", galaxyCopies); }
    static Config join(String name, String host, int port, boolean dev, Set<String> disabledNpcFactionIds, String systemId, int galaxyCopies) { return join(name, host, port, dev, dev, disabledNpcFactionIds, systemId, "", galaxyCopies); }
    static Config solo(String name, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId) { return solo(name, dev, disableProductionTimers, disabledNpcFactionIds, systemId, ""); }
    static Config host(String name, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId) { return host(name, port, dev, disableProductionTimers, disabledNpcFactionIds, systemId, ""); }
    static Config dedicatedServer(String name, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId) { return dedicatedServer(name, port, dev, disableProductionTimers, disabledNpcFactionIds, systemId, ""); }
    static Config join(String name, String host, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId) { return join(name, host, port, dev, disableProductionTimers, disabledNpcFactionIds, systemId, ""); }
    static Config solo(String name, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken) { return solo(name, dev, disableProductionTimers, disabledNpcFactionIds, systemId, devToken, 1); }
    static Config host(String name, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken) { return host(name, port, dev, disableProductionTimers, disabledNpcFactionIds, systemId, devToken, 1); }
    static Config dedicatedServer(String name, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken) { return dedicatedServer(name, port, dev, disableProductionTimers, disabledNpcFactionIds, systemId, devToken, 1); }
    static Config join(String name, String host, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken) { return join(name, host, port, dev, disableProductionTimers, disabledNpcFactionIds, systemId, devToken, 1); }
    static Config solo(String name, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken, int galaxyCopies) { return new Config(clean(name), false, false, false, false, dev, devToken, disableProductionTimers, 0, null, disabledNpcFactionIds, systemId, galaxyCopies, Path.of("saves"), "server", 60, 5, false); }
    static Config host(String name, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken, int galaxyCopies) { return new Config(clean(name), false, true, false, false, dev, devToken, disableProductionTimers, port, null, disabledNpcFactionIds, systemId, galaxyCopies, Path.of("saves"), "server", 60, 5, false); }
    static Config dedicatedServer(String name, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken, int galaxyCopies) { return dedicatedServer(name, port, dev, disableProductionTimers, disabledNpcFactionIds, systemId, devToken, galaxyCopies, Path.of("saves"), "server", 60, 5, false); }
    static Config dedicatedServer(String name, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken, int galaxyCopies, Path saveDir, String saveName, int autosaveSeconds, int backupCount, boolean newWorld) { return new Config(clean(name), false, true, true, false, dev, devToken, disableProductionTimers, port, null, disabledNpcFactionIds, systemId, galaxyCopies, saveDir, saveName, autosaveSeconds, backupCount, newWorld); }
    static Config join(String name, String host, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken, int galaxyCopies) { return new Config(clean(name), false, false, false, false, dev, devToken, disableProductionTimers, 0, new InetSocketAddress(parseHost(host), port), disabledNpcFactionIds, systemId, galaxyCopies, Path.of("saves"), "server", 60, 5, false); }
    static Config localHostClient(Config hostConfig) {
        if (hostConfig == null || !hostConfig.hostMode || hostConfig.dedicatedServer) {
            throw new IllegalArgumentException("Local host client requires graphical host configuration.");
        }
        return new Config(hostConfig.playerName, false, false, false, true, hostConfig.devMode,
                hostConfig.devToken, hostConfig.disableProductionTimers, 0,
                new InetSocketAddress(DEFAULT_HOST, hostConfig.port), hostConfig.disabledNpcFactionIds,
                hostConfig.systemId, hostConfig.galaxyCopies, Path.of("saves"), "server", 60, 5, false);
    }

    NetworkRole role() {
        if (hostMode) return NetworkRole.SERVER;
        if (clientMode()) return NetworkRole.CLIENT;
        return NetworkRole.SOLO;
    }

    boolean clientMode() { return serverAddress != null; }
    boolean dedicatedServerMode() { return dedicatedServer; }
    boolean localHostClientMode() { return localHostClient; }
    String modeLabel() { return dedicatedServer ? "Server" : switch (role()) { case SERVER -> "Host"; case CLIENT -> "Client"; case SOLO -> "Solo"; }; }

    static String parseHost(String value) {
        if (value == null || value.isBlank()) return DEFAULT_HOST;
        String host = value.trim();
        boolean startsBracket = host.startsWith("[");
        boolean endsBracket = host.endsWith("]");
        if (startsBracket || endsBracket) {
            if (!startsBracket || !endsBracket || host.length() <= 2) throw new IllegalArgumentException("Server address has invalid IPv6 brackets.");
            host = host.substring(1, host.length() - 1);
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == ':' || c == '%';
            if (!allowed) throw new IllegalArgumentException("Server address contains unsupported characters.");
        }
        return host;
    }

    static int parsePort(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1 || parsed > 65535) throw new IllegalArgumentException("Port must be 1-65535.");
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Port must be a number.");
        }
    }

    static int parseGalaxyCopies(String value) {
        try { return clampGalaxyCopies(Integer.parseInt(value.trim())); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Galaxy copies must be 1 or 2."); }
    }

    private static int parseNonNegativeInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) throw new IllegalArgumentException(label + " must be zero or greater.");
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static int parsePositiveInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) throw new IllegalArgumentException(label + " must be at least one.");
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static int clampGalaxyCopies(int value) {
        if (value < 1 || value > 2) throw new IllegalArgumentException("Galaxy copies must be 1 or 2.");
        return value;
    }

    private static String defaultName() { return clean(System.getProperty("user.name", "Player")); }
    static String clean(String name) { return TextSafety.playerName(name); }

    static String cleanSystem(String systemId) {
        if (systemId == null || systemId.isBlank()) return StarSystems.DEFAULT_SYSTEM_ID;
        return systemId.replace('|','_').replace(';','_').replace(',','_').trim();
    }

    static String cleanSaveName(String value) {
        if (value == null || value.isBlank()) return "server";
        String clean = value.replaceAll("[^A-Za-z0-9_.-]", "_").trim();
        return clean.isBlank() ? "server" : clean;
    }
}
