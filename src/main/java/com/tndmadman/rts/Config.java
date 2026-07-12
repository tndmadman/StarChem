package com.tndmadman.rts;

import java.net.InetSocketAddress;
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
    final boolean devMode;
    final String devToken;
    final boolean disableProductionTimers;
    final int port;
    final InetSocketAddress serverAddress;
    final Set<String> disabledNpcFactionIds;
    final String systemId;
    final int galaxyCopies;

    private Config(String playerName, boolean showLobby, boolean hostMode, boolean dedicatedServer, boolean devMode,
                   String devToken, boolean disableProductionTimers, int port, InetSocketAddress serverAddress,
                   Set<String> disabledNpcFactionIds, String systemId, int galaxyCopies) {
        this.playerName = playerName;
        this.showLobby = showLobby;
        this.hostMode = hostMode;
        this.dedicatedServer = dedicatedServer;
        this.devMode = devMode;
        this.devToken = DevAccessPolicy.normalizeToken(devToken);
        this.disableProductionTimers = devMode && disableProductionTimers;
        this.port = port;
        this.serverAddress = serverAddress;
        this.disabledNpcFactionIds = disabledNpcFactionIds == null ? Set.of() : Set.copyOf(disabledNpcFactionIds);
        this.systemId = cleanSystem(systemId);
        this.galaxyCopies = clampGalaxyCopies(galaxyCopies);
    }

    static Config parse(String[] args) {
        if (args.length == 0) return lobby();
        String name = defaultName();
        String system = StarSystems.DEFAULT_SYSTEM_ID;
        boolean host = false;
        boolean dedicated = false;
        boolean dev = false;
        String devToken = "";
        boolean disableProductionTimers = false;
        int port = 0;
        int galaxyCopies = 1;
        InetSocketAddress server = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--name", "--id" -> { if (i + 1 < args.length) name = clean(args[++i]); }
                case "--system" -> { if (i + 1 < args.length) system = cleanSystem(args[++i]); }
                case "--galaxy-copies" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--galaxy-copies requires 1 or 2.");
                    galaxyCopies = parseGalaxyCopies(args[++i]);
                }
                case "--dev" -> { dev = true; disableProductionTimers = true; }
                case "--dev-token" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--dev-token requires a token value.");
                    devToken = DevAccessPolicy.requireToken(args[++i]);
                }
                case "--disable-timers" -> disableProductionTimers = true;
                case "--enable-timers" -> disableProductionTimers = false;
                case "--server" -> { dedicated = true; host = true; if (i + 1 < args.length && !args[i + 1].startsWith("--")) port = parsePort(args[++i]); }
                case "--host" -> { if (i + 1 < args.length) { host = true; port = parsePort(args[++i]); } }
                case "--join" -> {
                    if (i + 2 >= args.length || args[i + 1].startsWith("--") || args[i + 2].startsWith("--")) {
                        throw new IllegalArgumentException("--join requires a server address and port.");
                    }
                    String hostName = parseHost(args[++i]);
                    int remotePort = parsePort(args[++i]);
                    server = new InetSocketAddress(hostName, remotePort);
                }
                case "--solo" -> { }
                default -> { }
            }
        }
        if (dedicated) return dedicatedServer(name, port == 0 ? 50000 : port, dev, disableProductionTimers, Set.of(), system, devToken, galaxyCopies);
        if (host) return host(name, port == 0 ? 50000 : port, dev, disableProductionTimers, Set.of(), system, devToken, galaxyCopies);
        if (server != null) return join(name, server.getHostString(), server.getPort(), dev, disableProductionTimers, Set.of(), system, devToken, galaxyCopies);
        return solo(name, dev, disableProductionTimers, Set.of(), system, devToken, galaxyCopies);
    }

    static Config lobby() { return new Config(defaultName(), true, false, false, false, "", false, 0, null, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, 1); }
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
    static Config solo(String name, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken, int galaxyCopies) { return new Config(clean(name), false, false, false, dev, devToken, disableProductionTimers, 0, null, disabledNpcFactionIds, systemId, galaxyCopies); }
    static Config host(String name, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken, int galaxyCopies) { return new Config(clean(name), false, true, false, dev, devToken, disableProductionTimers, port, null, disabledNpcFactionIds, systemId, galaxyCopies); }
    static Config dedicatedServer(String name, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken, int galaxyCopies) { return new Config(clean(name), false, true, true, dev, devToken, disableProductionTimers, port, null, disabledNpcFactionIds, systemId, galaxyCopies); }
    static Config join(String name, String host, int port, boolean dev, boolean disableProductionTimers, Set<String> disabledNpcFactionIds, String systemId, String devToken, int galaxyCopies) { return new Config(clean(name), false, false, false, dev, devToken, disableProductionTimers, 0, new InetSocketAddress(parseHost(host), port), disabledNpcFactionIds, systemId, galaxyCopies); }

    NetworkRole role() {
        if (hostMode) return NetworkRole.SERVER;
        if (clientMode()) return NetworkRole.CLIENT;
        return NetworkRole.SOLO;
    }

    boolean clientMode() { return serverAddress != null; }
    boolean dedicatedServerMode() { return dedicatedServer; }
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

    private static int clampGalaxyCopies(int value) {
        if (value < 1 || value > 2) throw new IllegalArgumentException("Galaxy copies must be 1 or 2.");
        return value;
    }

    private static String defaultName() { return clean(System.getProperty("user.name", "Player")); }
    static String clean(String name) {
        if (name == null || name.isBlank()) return "Player";
        String cleaned = name.replace('|',' ').replace(';',' ').replace(',',' ').replaceAll("\\s+", " ").trim();
        return cleaned.length() > 18 ? cleaned.substring(0, 18).trim() : cleaned;
    }

    static String cleanSystem(String systemId) {
        if (systemId == null || systemId.isBlank()) return StarSystems.DEFAULT_SYSTEM_ID;
        return systemId.replace('|','_').replace(';','_').replace(',','_').trim();
    }
}
