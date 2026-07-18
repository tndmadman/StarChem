package com.tndmadman.rts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Validates bounded console input and authoritative-thread command dispatch. */
public final class ServerConsoleValidator {
    private ServerConsoleValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem server console validation passed.");
    }

    static void validate() throws Exception {
        validateBoundedQueue();
        validateReaderEof();
        validateDurationParsing();
        validateAdminPolicyPersistence();
        validateModerationPersistence();
        validatePersistentAdministrationTelemetry();
        validateIpAndDeviceIdentity();
        validateDeviceHandshake();
        validateDispatch();
        ServerDevCommandValidator.validate();
    }

    private static void validateBoundedQueue() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        ServerConsole console = ServerConsole.detached(2, stream(errors));
        require(console.submit("status"), "first command was rejected");
        require(console.submit("players"), "second command was rejected");
        require(!console.submit("save"), "full command queue accepted another command");
        require(console.pendingCount() == 2, "bounded command queue size changed unexpectedly");
        require("status".equals(console.poll()), "commands did not preserve FIFO order");
        require("players".equals(console.poll()), "commands did not preserve FIFO order");
        require(!console.submit("x".repeat(ServerConsole.MAX_LINE_LENGTH + 1)), "oversized command was accepted");
        console.close();
        require(!console.submit("status"), "closed console accepted a command");
        String errorText = errors.toString(StandardCharsets.UTF_8);
        require(errorText.contains("queue is full"), "queue overflow was not reported");
        require(errorText.contains("exceeded"), "oversized input was not reported");
    }

    private static void validateReaderEof() throws Exception {
        byte[] input = "status\nplayers\n".getBytes(StandardCharsets.UTF_8);
        ServerConsole console = ServerConsole.start(new ByteArrayInputStream(input), stream(new ByteArrayOutputStream()));
        await(() -> console.pendingCount() == 2 && !console.acceptingInput(), 1_000,
                "console reader did not preserve queued commands after EOF");
        require("status".equals(console.poll()), "reader changed the first command");
        require("players".equals(console.poll()), "reader changed the second command");
        console.close();
    }

    private static void validateDurationParsing() {
        require(ServerCommandDispatcher.parseDurationSeconds("15") == 15, "plain seconds were not parsed");
        require(ServerCommandDispatcher.parseDurationSeconds("2m") == 120, "minutes were not parsed");
        require(ServerCommandDispatcher.parseDurationSeconds("1h") == 3600, "hours were not parsed");
        require(ServerCommandDispatcher.parseDurationSeconds("1d") == 86_400, "days were not parsed");
        boolean rejected = false;
        try { ServerCommandDispatcher.parseDurationSeconds("0"); }
        catch (IllegalArgumentException ex) { rejected = true; }
        require(rejected, "zero duration was accepted");
    }

    private static void validateAdminPolicyPersistence() throws Exception {
        Path dir = Files.createTempDirectory("starchem-admin-validator-");
        try {
            ServerAdminStore store = new ServerAdminStore(dir, "validation");
            ServerAccessPolicy expected = new ServerAccessPolicy(true, "Testing", 12, "Welcome pilot");
            store.save(expected);
            ServerAccessPolicy restored = store.load();
            require(expected.equals(restored), "server administration policy did not round-trip");
            require(Files.isRegularFile(store.path()), "server administration file was not created");
        } finally {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }


    private static void validateModerationPersistence() throws Exception {
        Path dir = Files.createTempDirectory("starchem-moderation-validator-");
        try {
            long now = System.currentTimeMillis();
            ServerModerationStore store = new ServerModerationStore(dir, "validation");
            ServerModerationState expected = ServerModerationState.open()
                    .addWhitelist("P1", "Alpha")
                    .withWhitelistEnabled(true)
                    .add(new ModerationEntry("entry-one", ModerationKind.PLAYER_BAN, "P2", "Beta",
                            "P2", now, now + 60_000, "Testing"))
                    .add(new ModerationEntry("entry-two", ModerationKind.IP_BAN, "", "",
                            "192.0.2.0/24", now, 0, "CIDR test"));
            store.save(expected);
            ServerModerationState restored = store.load();
            require(restored.whitelistEnabled(), "moderation whitelist state did not round-trip");
            require(restored.whitelisted("P1", "Alpha"), "moderation whitelist identity did not round-trip");
            require(restored.active(null, now).size() == 2, "moderation entries did not round-trip");
            require(Files.isRegularFile(store.path()), "moderation file was not created");
        } finally {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static void validatePersistentAdministrationTelemetry() throws Exception {
        Path dir = Files.createTempDirectory("starchem-admin-telemetry-validator-");
        try {
            ServerEventJournal journal = new ServerEventJournal(dir, "validation");
            journal.add("ADMIN", "dev-mode", "enabled");
            ServerEventJournal restored = new ServerEventJournal(dir, "validation");
            require(restored.lines(10, "ADMIN", "dev-mode").get(0).contains("enabled"),
                    "persistent server activity journal did not reload");

            ServerPlayerObservationStore observations = new ServerPlayerObservationStore(dir, "validation");
            observations.record("P9", "Observer", java.net.InetAddress.getByName("192.0.2.44"),
                    "device-observation-validator-1234");
            ServerPlayerObservationStore restoredObservations = new ServerPlayerObservationStore(dir, "validation");
            ServerPlayerObservationStore.PlayerObservation observation = restoredObservations.find("P9");
            require(observation != null && observation.ips().contains("192.0.2.44")
                            && observation.devices().contains("device-observation-validator-1234"),
                    "player moderation observations did not round-trip");
        } finally {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static void validateIpAndDeviceIdentity() throws Exception {
        require("192.0.2.0/24".equals(IpBanMatcher.normalize("192.0.2.0/24")), "IPv4 CIDR was not normalized");
        require(IpBanMatcher.matches("192.0.2.0/24", java.net.InetAddress.getByName("192.0.2.55")),
                "IPv4 CIDR did not match an address in range");
        require(!IpBanMatcher.matches("192.0.2.0/24", java.net.InetAddress.getByName("198.51.100.1")),
                "IPv4 CIDR matched an address outside the range");
        require(IpBanMatcher.matches("2001:db8::/32", java.net.InetAddress.getByName("2001:db8::1234")),
                "IPv6 CIDR did not match an address in range");
        String device = "device-validator-0123456789";
        require(ServerDeviceIdentity.valid(device), "valid client device ID was rejected");
        require(ServerDeviceIdentity.equal(device, device.toUpperCase(java.util.Locale.ROOT)),
                "device IDs were not compared consistently");
        long now = System.currentTimeMillis();
        ServerModerationState state = ServerModerationState.open()
                .add(new ModerationEntry("player", ModerationKind.PLAYER_BAN, "P7", "Pilot", "P7", now, 0, ""))
                .add(new ModerationEntry("ip", ModerationKind.IP_BAN, "", "", "192.0.2.0/24", now, 0, ""))
                .add(new ModerationEntry("device", ModerationKind.DEVICE_BAN, "", "", device, now, 0, ""));
        require(state.blocked("P7", "Other", java.net.InetAddress.getByName("198.51.100.1"), "other-device-123456", now).kind() == ModerationKind.PLAYER_BAN,
                "player ban did not block a matching player ID");
        require(state.blocked("P8", "Other", java.net.InetAddress.getByName("192.0.2.10"), "other-device-123456", now).kind() == ModerationKind.IP_BAN,
                "IP ban did not block a matching address");
        require(state.blocked("P8", "Other", java.net.InetAddress.getByName("198.51.100.1"), device, now).kind() == ModerationKind.DEVICE_BAN,
                "device ban did not block a matching device ID");
    }

    private static void validateDeviceHandshake() throws Exception {
        Path store = Files.createTempFile("starchem-device-validator-", ".properties");
        Files.deleteIfExists(store);
        String previous = System.getProperty("starchem.sessionStore");
        try {
            System.setProperty("starchem.sessionStore", store.toString());
            String first = ClientDeviceIdentityStore.deviceId();
            String second = ClientDeviceIdentityStore.deviceId();
            require(ServerDeviceIdentity.valid(first) && first.equals(second), "client device ID was not stable");
            String versioned = MultiplayerCompatibility.versionClientHandshake("JOIN|Alpha|NODEV|");
            require(versioned.contains("|DEVICE|" + first + "|PROTO|"), "device ID was not included in the versioned handshake");
            MultiplayerCompatibility.WireResult inspected = MultiplayerCompatibility.inspectClientHandshake(versioned);
            require(inspected.action() == MultiplayerCompatibility.WireAction.ACCEPT, "device handshake was not accepted");
            require("JOIN|Alpha|NODEV|".equals(inspected.message()),
                    "server compatibility normalization did not restore the original JOIN payload");
        } finally {
            if (previous == null) System.clearProperty("starchem.sessionStore");
            else System.setProperty("starchem.sessionStore", previous);
            Files.deleteIfExists(store);
        }
    }

    private static void validateDispatch() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        FakeTarget target = new FakeTarget();
        ServerCommandDispatcher dispatcher = new ServerCommandDispatcher(target, stream(output), stream(errors));
        ServerConsole queue = ServerConsole.detached(4, stream(errors));

        require(queue.submit("status"), "status command was not queued");
        require(target.statusCalls == 0, "queued command ran outside authoritative dispatch");
        dispatcher.execute(queue.poll());
        require(target.statusCalls == 1, "status command was not dispatched");

        dispatcher.execute("players");
        dispatcher.execute("leaderboard top 5");
        dispatcher.execute("player P1 assets");
        dispatcher.execute("sessions connected");
        dispatcher.execute("uptime");
        dispatcher.execute("perf network");
        dispatcher.execute("systems controlled");
        dispatcher.execute("system SYS-1");
        dispatcher.execute("connection P1");
        dispatcher.execute("resync P1");
        dispatcher.execute("server-info compatibility");
        dispatcher.execute("save-info");
        dispatcher.execute("autosave set 5m");
        dispatcher.execute("backups verify current");
        dispatcher.execute("maintenance on Testing");
        dispatcher.execute("slots set 12");
        dispatcher.execute("motd set Welcome pilot");
        dispatcher.execute("whitelist status");
        dispatcher.execute("kick P1 5m Testing");
        dispatcher.execute("kicks");
        dispatcher.execute("unkick P1");
        dispatcher.execute("ban player P1 1h Testing");
        dispatcher.execute("bans all");
        dispatcher.execute("unban P1");
        dispatcher.execute("pause status");
        dispatcher.execute("prune-systems preview");
        dispatcher.execute("health");
        dispatcher.execute("activity last 10");
        dispatcher.execute("factions");
        dispatcher.execute("faction CORSAIR");
        dispatcher.execute("production summary");
        dispatcher.execute("assets player P1");
        dispatcher.execute("asset P1:1");
        dispatcher.execute("save");
        dispatcher.execute("say Maintenance soon");
        dispatcher.execute("shutdown 2m Maintenance");
        dispatcher.execute("shutdown status");
        dispatcher.execute("shutdown cancel");
        dispatcher.execute("disconnect P1 Testing");
        dispatcher.execute("dev ai pause on");
        dispatcher.execute("help \"status\"");
        dispatcher.execute("unknown");
        dispatcher.execute("help \"unterminated");
        dispatcher.execute("stop");

        require(target.playerCalls == 1, "players command was not dispatched");
        require(target.leaderboardCalls == 1 && target.leaderboardLimit == 5, "leaderboard command was not dispatched");
        require(target.playerDetailCalls == 1, "player detail command was not dispatched");
        require(target.sessionsCalls == 1, "sessions command was not dispatched");
        require(target.uptimeCalls == 1, "uptime command was not dispatched");
        require(target.performanceCalls == 1, "perf command was not dispatched");
        require(target.systemsCalls == 1 && target.systemCalls == 1, "system commands were not dispatched");
        require(target.connectionCalls == 1, "connection command was not dispatched");
        require(target.resyncCalls == 1, "resync command was not dispatched");
        require(target.serverInfoCalls == 1, "server-info command was not dispatched");
        require(target.saveInfoCalls == 1, "save-info command was not dispatched");
        require(target.autosaveCalls == 1 && target.backupCalls == 1, "save administration commands were not dispatched");
        require(target.maintenanceCalls == 1 && target.slotsCalls == 1 && target.motdCalls == 1,
                "access-policy commands were not dispatched");
        require(target.saveCalls == 1, "save command was not dispatched");
        require(target.announceCalls == 1, "say command was not dispatched");
        require(target.scheduleCalls == 1 && target.scheduledSeconds == 120, "scheduled shutdown was not dispatched");
        require(target.shutdownStatusCalls == 1 && target.cancelCalls == 1, "shutdown management was not dispatched");
        require(target.disconnectCalls == 1, "disconnect command was not dispatched");
        require(target.developerCalls == 1, "developer command was not dispatched");
        require(target.stopCalls == 1 && !target.running, "stop command did not stop the target");
        require(ServerCommandDispatcher.tokenize("help 'status'").equals(List.of("help", "status")),
                "quoted command tokenization failed");
        String outputText = output.toString(StandardCharsets.UTF_8);
        String errorText = errors.toString(StandardCharsets.UTF_8);
        require(outputText.contains("HOST Test"), "status output was not printed");
        require(outputText.contains("P1 | Alpha | connected"), "player output was not printed");
        require(outputText.contains("status -"), "command-specific help was not printed");
        require(errorText.contains("Unknown console command"), "unknown command was not rejected");
        require(errorText.contains("unterminated quote"), "unterminated quote was not rejected");
        queue.close();
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private static void await(BooleanSupplier condition, long timeoutMs, String message) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        require(condition.getAsBoolean(), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class FakeTarget implements ServerCommandDispatcher.Target {
        int statusCalls;
        int playerCalls;
        int leaderboardCalls;
        int leaderboardLimit;
        int playerDetailCalls;
        int sessionsCalls;
        int uptimeCalls;
        int performanceCalls;
        int systemsCalls;
        int systemCalls;
        int connectionCalls;
        int resyncCalls;
        int serverInfoCalls;
        int saveInfoCalls;
        int autosaveCalls;
        int backupCalls;
        int maintenanceCalls;
        int slotsCalls;
        int motdCalls;
        int saveCalls;
        int announceCalls;
        int scheduleCalls;
        int cancelCalls;
        int shutdownStatusCalls;
        int disconnectCalls;
        int developerCalls;
        int stopCalls;
        long scheduledSeconds;
        boolean running = true;

        @Override public String status() { statusCalls++; return "HOST Test"; }
        @Override public List<String> players() { playerCalls++; return List.of("P1 | Alpha | connected", "P2 | Beta | retained"); }
        @Override public List<String> leaderboard(int limit) { leaderboardCalls++; leaderboardLimit = limit; return List.of("1. Alpha"); }
        @Override public List<String> player(String selector, String section) { playerDetailCalls++; return List.of(selector + " " + section); }
        @Override public List<String> sessions(String filter) { sessionsCalls++; return List.of(filter); }
        @Override public List<String> uptime() { uptimeCalls++; return List.of("Uptime: 1m"); }
        @Override public List<String> performance(String scope) { performanceCalls++; return List.of("Network: " + scope); }
        @Override public List<String> systems(String filter, String value) { systemsCalls++; return List.of("SYS-1"); }
        @Override public List<String> system(String selector) { systemCalls++; return List.of(selector); }
        @Override public List<String> connection(String selector) { connectionCalls++; return List.of(selector + " connected"); }
        @Override public List<String> resync(String selector) { resyncCalls++; return List.of("resync " + selector); }
        @Override public List<String> serverInfo(String scope) { serverInfoCalls++; return List.of(scope); }
        @Override public List<String> saveInfo() { saveInfoCalls++; return List.of("Save: test"); }
        @Override public List<String> autosave(List<String> args) { autosaveCalls++; return List.of("autosave"); }
        @Override public List<String> backups(List<String> args) { backupCalls++; return List.of("backups"); }
        @Override public List<String> maintenance(List<String> args) { maintenanceCalls++; return List.of("maintenance"); }
        @Override public List<String> slots(List<String> args) { slotsCalls++; return List.of("slots"); }
        @Override public List<String> motd(List<String> args) { motdCalls++; return List.of("motd"); }
        @Override public String announce(String message) { announceCalls++; return "sent " + message; }
        @Override public String scheduleShutdown(long delaySeconds, String reason) { scheduleCalls++; scheduledSeconds = delaySeconds; return "scheduled"; }
        @Override public String cancelShutdown() { cancelCalls++; return "cancelled"; }
        @Override public String shutdownStatus() { shutdownStatusCalls++; return "scheduled"; }
        @Override public String disconnect(String selector, String reason) { disconnectCalls++; return "disconnected"; }
        @Override public List<String> developer(List<String> args) { developerCalls++; return List.of("developer"); }
        @Override public boolean save() { saveCalls++; return true; }
        @Override public void stop() { stopCalls++; running = false; }
        @Override public boolean running() { return running; }
    }
}
