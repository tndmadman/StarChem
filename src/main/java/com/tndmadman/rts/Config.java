package com.tndmadman.rts;

import java.net.InetSocketAddress;
import java.util.Set;

final class Config {
    static final String RAIDERS_ID = "NPC_RAIDERS";
    static final String FREE_MINERS_ID = "NPC_MINERS";
    static final String CORSAIRS_ID = "NPC_CORSAIRS";

    final String playerName;
    final boolean showLobby;
    final boolean hostMode;
    final boolean devMode;
    final int port;
    final InetSocketAddress serverAddress;
    final Set<String> disabledNpcFactionIds;

    private Config(String playerName, boolean showLobby, boolean hostMode, boolean devMode, int port, InetSocketAddress serverAddress, Set<String> disabledNpcFactionIds) {
        this.playerName = playerName;
        this.showLobby = showLobby;
        this.hostMode = hostMode;
        this.devMode = devMode;
        this.port = port;
        this.serverAddress = serverAddress;
        this.disabledNpcFactionIds = disabledNpcFactionIds == null ? Set.of() : Set.copyOf(disabledNpcFactionIds);
    }

    static Config parse(String[] args) {
        if (args.length == 0) return lobby();
        String name = defaultName();
        boolean host = false;
        boolean dev = false;
        int port = 0;
        InetSocketAddress server = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--name", "--id" -> { if (i + 1 < args.length) name = clean(args[++i]); }
                case "--dev" -> dev = true;
                case "--host" -> { if (i + 1 < args.length) { host = true; port = parsePort(args[++i]); } }
                case "--join" -> {
                    if (i + 2 < args.length) {
                        String hostName = args[++i];
                        int remotePort = parsePort(args[++i]);
                        server = new InetSocketAddress(hostName, remotePort);
                    }
                }
                case "--solo" -> { }
                default -> { }
            }
        }
        if (host) return host(name, port == 0 ? 50000 : port, dev);
        if (server != null) return join(name, server.getHostString(), server.getPort(), dev);
        return solo(name, dev);
    }

    static Config lobby() { return new Config(defaultName(), true, false, false, 0, null, Set.of()); }
    static Config solo(String name) { return solo(name, false); }
    static Config host(String name, int port) { return host(name, port, false); }
    static Config join(String name, String host, int port) { return join(name, host, port, false); }
    static Config solo(String name, boolean dev) { return solo(name, dev, Set.of()); }
    static Config host(String name, int port, boolean dev) { return host(name, port, dev, Set.of()); }
    static Config join(String name, String host, int port, boolean dev) { return join(name, host, port, dev, Set.of()); }
    static Config solo(String name, boolean dev, Set<String> disabledNpcFactionIds) { return new Config(clean(name), false, false, dev, 0, null, disabledNpcFactionIds); }
    static Config host(String name, int port, boolean dev, Set<String> disabledNpcFactionIds) { return new Config(clean(name), false, true, dev, port, null, disabledNpcFactionIds); }
    static Config join(String name, String host, int port, boolean dev, Set<String> disabledNpcFactionIds) { return new Config(clean(name), false, false, dev, 0, new InetSocketAddress(host, port), disabledNpcFactionIds); }

    boolean clientMode() { return serverAddress != null; }
    String modeLabel() { return hostMode ? "Host" : clientMode() ? "Client" : "Solo"; }

    static int parsePort(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1 || parsed > 65535) throw new IllegalArgumentException("Port must be 1-65535.");
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Port must be a number.");
        }
    }

    private static String defaultName() { return clean(System.getProperty("user.name", "Player")); }
    static String clean(String name) {
        if (name == null || name.isBlank()) return "Player";
        String cleaned = name.replace('|',' ').replace(';',' ').replace(',',' ').replaceAll("\\s+", " ").trim();
        return cleaned.length() > 18 ? cleaned.substring(0, 18).trim() : cleaned;
    }
}
