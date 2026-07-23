package com.tndmadman.rts;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class ServerObservationStoreValidator {
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final String DEVICE_A = "11111111-1111-4111-8111-111111111111";
    private static final String DEVICE_B = "22222222-2222-4222-8222-222222222222";

    private ServerObservationStoreValidator() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("starchem-observation-validator-");
        try {
  validateMigration(root.resolve("migration"));
  validateLifecycle(root.resolve("lifecycle"));
  System.out.println("Player observation retention validation passed.");
        } finally {
  try (var paths = Files.walk(root)) {
      paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
          try { Files.deleteIfExists(path); }
          catch (Exception ignored) { }
      });
  }
        }
    }

    private static void validateMigration(Path dir) throws Exception {
        Files.createDirectories(dir);
        long seenAt = 5 * DAY;
        Map<String,Object> legacy = Map.of(
      "version", 1,
      "players", java.util.List.of(Map.of(
              "playerId", "LEGACY",
              "playerName", "Legacy Player",
              "ips", java.util.List.of("198.51.100.4"),
              "devices", java.util.List.of(DEVICE_A),
              "lastSeenAt", seenAt)));
        Files.writeString(dir.resolve("server-observations.json"), MiniJson.stringify(legacy) + "\n",
      StandardCharsets.UTF_8);
        AtomicLong now = new AtomicLong(6 * DAY);
        ServerPlayerObservationStore store = new ServerPlayerObservationStore(dir, "server",
      10 * DAY, 3 * DAY, now::get);
        ServerPlayerObservationStore.PlayerObservation migrated = store.find("LEGACY");
        require(migrated != null, "legacy observation was not loaded");
        require(migrated.ipSignals().get(0).lastSeenAt() == seenAt,
      "legacy IP did not inherit the player timestamp");
        require(migrated.deviceSignals().get(0).lastSeenAt() == seenAt,
      "legacy device did not inherit the player timestamp");
        Object parsed = MiniJson.parse(Files.readString(store.pathForTest(), StandardCharsets.UTF_8));
        require(parsed instanceof Map<?,?> map && Number.class.cast(map.get("version")).intValue() == 2,
      "legacy observation file was not migrated to schema version 2");
    }

    private static void validateLifecycle(Path dir) throws Exception {
        AtomicLong now = new AtomicLong(DAY);
        ServerPlayerObservationStore store = new ServerPlayerObservationStore(dir, "server",
      10 * DAY, 3 * DAY, now::get);
        store.record("P1", "Player One", InetAddress.getByName("203.0.113.10"), DEVICE_A);
        now.set(8 * DAY);
        store.record("P1", "Player One", InetAddress.getByName("203.0.113.11"), DEVICE_B);

        var recent = store.moderationSignals("P1", false);
        require(recent.ips().equals(java.util.List.of("203.0.113.11")),
      "stale IP was included in automatic ban expansion");
        require(recent.devices().equals(java.util.List.of(DEVICE_B)),
      "stale device was included in automatic ban expansion");
        require(recent.staleCount() == 2, "stale moderation signals were not counted");
        var all = store.moderationSignals("P1", true);
        require(all.ips().size() == 2 && all.devices().size() == 2,
      "explicit stale-signal inclusion did not return every retained signal");

        java.util.List<String> lines = store.lines("P1");
        require(lines.stream().anyMatch(line -> line.contains("203.0.113.10") && line.contains("last seen") && line.contains("age")),
      "IP output omitted its individual timestamp or age");
        require(lines.stream().anyMatch(line -> line.contains("device") && line.contains("last seen") && line.contains("age")),
      "device output omitted its individual timestamp or age");

        store.record("P2", "Player Two", InetAddress.getByName("203.0.113.10"), "");
        require(store.moderationSignals("P2", false).ips().contains("203.0.113.10"),
      "recent reuse of an old IP by another player was not retained independently");
        require(!store.moderationSignals("P1", false).ips().contains("203.0.113.10"),
      "old IP reuse made the stale first-player signal recent");

        now.set(12 * DAY);
        ServerPlayerObservationStore.MutationResult pruned = store.pruneExpired();
        require(pruned.success() && pruned.changed(), "expired signals were not pruned");
        require(store.find("P1") != null && store.find("P1").ips().equals(java.util.List.of("203.0.113.11")),
      "pruning removed recent data or retained expired data");

        store.record("P3", "Player Three", InetAddress.getByName("192.0.2.3"), "");
        store.failNextSaveForTest();
        ServerPlayerObservationStore.MutationResult failedDelete = store.delete("P3");
        require(!failedDelete.success() && store.find("P3") != null,
      "failed deletion did not roll back in-memory state");
        ServerPlayerObservationStore reopened = new ServerPlayerObservationStore(dir, "server",
      10 * DAY, 3 * DAY, now::get);
        require(reopened.find("P3") != null, "failed deletion changed persisted state");

        ServerPlayerObservationStore.MutationResult deleted = reopened.delete("P2");
        require(deleted.success() && deleted.changed() && reopened.find("P2") == null,
      "per-player deletion failed");
        require(new ServerPlayerObservationStore(dir, "server", 10 * DAY, 3 * DAY, now::get).find("P2") == null,
      "per-player deletion was not durable");

        ServerPlayerObservationStore.MutationResult cleared = reopened.clearAll();
        require(cleared.success() && cleared.changed(), "clear-all failed");
        require(new ServerPlayerObservationStore(dir, "server", 10 * DAY, 3 * DAY, now::get).lines("")
              .equals(java.util.List.of("No player observations matched.")),
      "clear-all was not durable");

        validatePermissions(reopened.pathForTest());
    }

    private static void validatePermissions(Path file) throws Exception {
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (view == null) return;
        Set<PosixFilePermission> permissions = view.readAttributes().permissions();
        require(permissions.equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
      "observation file permissions are not owner-only: " + permissions);
        Path parent = file.toAbsolutePath().getParent();
        Set<PosixFilePermission> directoryPermissions = Files.getPosixFilePermissions(parent);
        require(directoryPermissions.equals(Set.of(PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)),
      "observation directory permissions are not owner-only: " + directoryPermissions);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
