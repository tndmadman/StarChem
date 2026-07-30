package com.tndmadman.rts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

final class RecentServerStore {
    private static final int MAX_ENTRIES = 20;
    private static final String OVERRIDE = "starchem.recentServers";

    private RecentServerStore() { }

    static synchronized List<RecentServer> load() {
        Path path = path();
        if (!Files.isRegularFile(path)) return List.of();
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException ex) {
            return List.of();
        }
        int count = parseInt(properties.getProperty("count"), 0);
        List<RecentServer> result = new ArrayList<>();
        for (int i = 0; i < Math.min(count, MAX_ENTRIES); i++) {
            String prefix = "server." + i + ".";
            String host = clean(properties.getProperty(prefix + "host"), 255);
            int port = parseInt(properties.getProperty(prefix + "port"), -1);
            String name = clean(properties.getProperty(prefix + "name"), 80);
            String version = clean(properties.getProperty(prefix + "version"), 40);
            long joinedAt = parseLong(properties.getProperty(prefix + "joinedAt"), 0L);
            if (!host.isBlank() && port >= 1 && port <= 65535) {
                result.add(new RecentServer(host, port, name, version, joinedAt));
            }
        }
        result.sort(Comparator.comparingLong(RecentServer::joinedAtMillis).reversed());
        return List.copyOf(result);
    }

    static synchronized void record(String host, int port, String name, String version) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) return;
        List<RecentServer> entries = new ArrayList<>(load());
        String normalizedHost = clean(host, 255);
        entries.removeIf(entry -> entry.host().equalsIgnoreCase(normalizedHost) && entry.port() == port);
        entries.add(0, new RecentServer(normalizedHost, port, clean(name, 80), clean(version, 40),
                System.currentTimeMillis()));
        if (entries.size() > MAX_ENTRIES) entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
        persist(entries);
    }

    private static void persist(List<RecentServer> entries) {
        Path target = path();
        Path parent = target.getParent();
        if (parent == null) return;
        try {
            Files.createDirectories(parent);
            Properties properties = new Properties();
            properties.setProperty("count", Integer.toString(entries.size()));
            for (int i = 0; i < entries.size(); i++) {
                RecentServer entry = entries.get(i);
                String prefix = "server." + i + ".";
                properties.setProperty(prefix + "host", entry.host());
                properties.setProperty(prefix + "port", Integer.toString(entry.port()));
                properties.setProperty(prefix + "name", entry.name());
                properties.setProperty(prefix + "version", entry.version());
                properties.setProperty(prefix + "joinedAt", Long.toString(entry.joinedAtMillis()));
            }
            Path temporary = Files.createTempFile(parent, "recent-servers-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "StarChem recent multiplayer servers");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) { }
    }

    private static Path path() {
        String override = System.getProperty(OVERRIDE, "").trim();
        return (override.isBlank()
                ? Path.of(System.getProperty("user.home", "."), ".starchem", "recent-servers.properties")
                : Path.of(override)).toAbsolutePath().normalize();
    }

    private static String clean(String value, int max) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (RuntimeException ex) { return fallback; }
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (RuntimeException ex) { return fallback; }
    }

    record RecentServer(String host, int port, String name, String version, long joinedAtMillis) {
        String endpoint() { return host + ":" + port; }
        String displayLabel() {
            String label = name == null || name.isBlank() ? endpoint() : name + " — " + endpoint();
            return version == null || version.isBlank() ? label : label + " — " + version;
        }
    }
}
