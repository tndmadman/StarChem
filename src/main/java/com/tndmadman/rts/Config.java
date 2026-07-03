package com.tndmadman.rts;

final class Config {
    final String playerName;
    final boolean showLobby;

    private Config(String playerName, boolean showLobby) {
        this.playerName = playerName;
        this.showLobby = showLobby;
    }

    static Config parse(String[] args) {
        if (args.length == 0) return lobby();
        String name = defaultName();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--name", "--id" -> { if (i + 1 < args.length) name = clean(args[++i]); }
                default -> { }
            }
        }
        return solo(name);
    }

    static Config lobby() { return new Config(defaultName(), true); }
    static Config solo(String name) { return new Config(clean(name), false); }
    String modeLabel() { return "Solo"; }

    private static String defaultName() { return clean(System.getProperty("user.name", "Player")); }
    static String clean(String name) {
        if (name == null || name.isBlank()) return "Player";
        String cleaned = name.replace('|',' ').replace(';',' ').replace(',',' ').replaceAll("\\s+", " ").trim();
        return cleaned.length() > 18 ? cleaned.substring(0, 18).trim() : cleaned;
    }
}
