package com.tndmadman.rts;

import java.net.InetSocketAddress;

final class Config {
    final String playerName;
    final boolean showLobby;
    final boolean hostMode;
    final int port;
    final InetSocketAddress serverAddress;

    private Config(String playerName, boolean showLobby, boolean hostMode, int port, InetSocketAddress serverAddress) {
        this.playerName = playerName;
        this.showLobby = showLobby;
        this.hostMode = hostMode;
        this.port = port;
        this.serverAddress = serverAddress;
    }

    static Config parse(String[] args) {
        if (args.length == 0) return lobby();
        String name = defaultName();
        boolean host = false;
        int port = 0;
        InetSocketAddress server = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--name", "--id" -> { if (i + 1 < args.length) name = clean(args[++i]); }
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
        if (host) return host(name, port == 0 ? 50000 : port);
        if (server != null) return join(name, server.getHostString(), server.getPort());
        return solo(name);
    }

    static Config lobby() { return new Config(defaultName(), true, false, 0, null); }
    static Config solo(String name) { return new Config(clean(name), false, false, 0, null); }
    static Config host(String name, int port) { return new Config(clean(name), false, true, port, null); }
    static Config join(String name, String host, int port) { return new Config(clean(name), false, false, 0, new InetSocketAddress(host, port)); }

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
