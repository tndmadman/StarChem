package com.tndmadman.rts;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Trusted local-console administration for retained identity lifecycle state. */
final class ServerIdentityAdministration {
    private static final long MAX_AGE_SECONDS = 10L * 365 * 24 * 60 * 60;

    private ServerIdentityAdministration() { }

    static List<String> execute(HeadlessGameServer host, List<String> args) {
        if (host == null || host.network == null || host.network.serverIdentityStore() == null) {
            return List.of("Identity lifecycle administration is unavailable.");
        }
        ServerIdentityStore store = host.network.serverIdentityStore();
        if (store.restricted()) return List.of("Identity lifecycle state requires recovery: " + store.restrictedReason());
        List<String> safeArgs = args == null ? List.of() : args;
        if (safeArgs.isEmpty()) return list(host, "all", 0);
        String action = safeArgs.get(0).toLowerCase(Locale.ROOT);
        return switch (action) {
            case "list" -> safeArgs.size() <= 2
                    ? list(host, safeArgs.size() == 2 ? safeArgs.get(1).toLowerCase(Locale.ROOT) : "all", 0)
                    : usage();
            case "dormant" -> dormant(host, safeArgs);
            case "archive" -> archive(host, safeArgs);
            case "restore" -> restore(host, safeArgs);
            case "delete" -> delete(host, safeArgs);
            default -> usage();
        };
    }

    static long parseAgeMillis(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("age is required");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        char suffix = normalized.charAt(normalized.length() - 1);
        if (Character.isLetter(suffix)) {
            multiplier = switch (suffix) {
                case 's' -> 1;
                case 'm' -> 60;
                case 'h' -> 3_600;
                case 'd' -> 86_400;
                case 'w' -> 604_800;
                default -> throw new IllegalArgumentException("age must use s, m, h, d, or w");
            };
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        long amount;
        try { amount = Long.parseLong(normalized); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("age is not numeric"); }
        if (amount < 1 || amount > MAX_AGE_SECONDS / multiplier) {
            throw new IllegalArgumentException("age must be between 1 second and 10 years");
        }
        return Math.multiplyExact(Math.multiplyExact(amount, multiplier), 1_000L);
    }

    private static List<String> list(HeadlessGameServer host, String filter, long dormantMillis) {
        if (!List.of("all", "active", "archived").contains(filter)) return usage();
        long now = System.currentTimeMillis();
        ArrayList<ServerIdentityStore.IdentityRecord> records = new ArrayList<>(host.network.serverIdentityStore().snapshot());
        records.sort(Comparator.comparingLong(ServerIdentityStore.IdentityRecord::lastSeenAt).reversed()
                .thenComparing(ServerIdentityStore.IdentityRecord::playerId));
        ArrayList<String> lines = new ArrayList<>();
        for (ServerIdentityStore.IdentityRecord record : records) {
            boolean connected = host.network.serverSessionConnected(record.playerId());
            if ("active".equals(filter) && record.archived()) continue;
            if ("archived".equals(filter) && !record.archived()) continue;
            if (dormantMillis > 0 && (record.archived() || connected || now - record.lastSeenAt() < dormantMillis)) continue;
            String state = record.archived() ? "archived" : connected ? "connected" : "retained";
            lines.add(record.playerId() + " | " + record.playerName() + " | " + state
                    + " | created " + Instant.ofEpochMilli(record.createdAt())
                    + " | last seen " + Instant.ofEpochMilli(record.lastSeenAt())
                    + " | age " + age(now, record.lastSeenAt()));
        }
        if (lines.isEmpty()) return List.of(dormantMillis > 0 ? "No dormant identities matched." : "No retained identities matched.");
        return List.copyOf(lines);
    }

    private static List<String> dormant(HeadlessGameServer host, List<String> args) {
        if (args.size() != 2) return List.of("Usage: identity dormant <age>");
        try { return list(host, "active", parseAgeMillis(args.get(1))); }
        catch (IllegalArgumentException ex) { return List.of(ex.getMessage(), "Usage: identity dormant <age>"); }
    }

    private static List<String> archive(HeadlessGameServer host, List<String> args) {
        if (args.size() != 3 || !"confirm".equalsIgnoreCase(args.get(2))) {
            return List.of("Archiving blocks future authentication while preserving world state. Use: identity archive <player> confirm");
        }
        ServerIdentityStore.IdentityRecord record = host.network.serverIdentityStore().find(args.get(1));
        if (record == null) return List.of("Unknown retained identity: " + args.get(1));
        ServerIdentityStore.MutationResult result = host.network.serverIdentityStore().archive(record.playerId());
        if (!result.success()) return List.of(result.message());
        if (host.network.serverSessionConnected(record.playerId())) {
            host.network.sendServerNotice(record.playerId(), "Your retained identity was archived by the server operator.");
            host.network.disconnectServerPlayer(record.playerId());
        }
        host.network.serverJournal().add("IDENTITY_ARCHIVE", record.playerId(), "retained state preserved");
        return List.of(result.message(), "Name and world state remain reserved until restore or permanent deletion.");
    }

    private static List<String> restore(HeadlessGameServer host, List<String> args) {
        if (args.size() != 2) return List.of("Usage: identity restore <player>");
        ServerIdentityStore.MutationResult result = host.network.serverIdentityStore().restore(args.get(1));
        if (result.success()) host.network.serverJournal().add("IDENTITY_RESTORE", args.get(1), "authentication restored");
        return List.of(result.message());
    }

    private static List<String> delete(HeadlessGameServer host, List<String> args) {
        if (args.size() != 3 || !"confirm".equalsIgnoreCase(args.get(2))) {
            return List.of("Permanent deletion removes the identity and all owned world state. Use: identity delete <player> confirm");
        }
        ServerIdentityStore.IdentityRecord record = host.network.serverIdentityStore().find(args.get(1));
        if (record == null) return List.of("Unknown retained identity: " + args.get(1));
        if (host.network.serverSessionConnected(record.playerId())) {
            return List.of(record.playerId() + " is connected; disconnect it before permanent deletion.");
        }
        Config config = host.network.serverConfig();
        ServerBackupAdmin backupAdmin = new ServerBackupAdmin(config.saveDir, config.saveName, config.backupCount);
        if (!host.saveForAdmin("identity-delete-source")) return List.of("Could not save the pre-deletion server state; no identity data was changed.");
        ServerBackupAdmin.BackupCreation backup = backupAdmin.createVerified("identity-" + record.playerId() + "-delete");
        if (!backup.success() || backup.path() == null) return List.of(backup.message(), "No identity data was changed.");

        PeerServerAdminBridge.DeleteResult deleted = host.network.deleteRetainedIdentity(record.playerId());
        if (!deleted.success()) return List.of(deleted.message(), "Verified recovery backup: " + backup.path().getFileName());
        if (!host.saveForAdmin("identity-delete-" + record.playerId())) {
            return recover(host, backupAdmin, backup.path(), "post-deletion save failed");
        }
        ServerBackupAdmin.Verification verified = backupAdmin.verifyCurrent();
        if (!verified.valid()) return recover(host, backupAdmin, backup.path(), "post-deletion save verification failed: " + verified.detail());

        ArrayList<String> warnings = new ArrayList<>();
        ServerIdentityStore.MutationResult metadata = host.network.serverIdentityStore().delete(record.playerId());
        if (!metadata.success()) warnings.add(metadata.message());
        ServerPlayerObservationStore.MutationResult observations = host.network.deletePlayerObservations(record.playerId());
        if (!observations.success()) warnings.add(observations.message());
        String moderationFailure = removeIdentityModeration(host.network, record);
        if (moderationFailure != null) warnings.add(moderationFailure);

        host.network.notifyDeletedSystems(deleted.deletedSystems());
        host.network.resyncAllServerPlayers();
        host.network.serverJournal().add("IDENTITY_DELETE", record.playerId(), "backup " + backup.path().getFileName());
        ArrayList<String> lines = new ArrayList<>();
        lines.add("Permanently deleted " + record.playerName() + " (" + record.playerId() + ").");
        lines.add("Verified recovery backup: " + backup.path().getFileName());
        lines.add("Removed owned assets, research, home state, and control; deleted name is now reusable.");
        lines.addAll(warnings);
        return List.copyOf(lines);
    }

    private static String removeIdentityModeration(PeerNetwork network, ServerIdentityStore.IdentityRecord record) {
        ServerModerationState current = network.serverModeration();
        LinkedHashSet<String> whitelist = new LinkedHashSet<>(current.whitelist());
        whitelist.remove("p:" + ServerModeration.normalizePlayerId(record.playerId()));
        ArrayList<ModerationEntry> entries = new ArrayList<>();
        for (ModerationEntry entry : current.entries()) {
            boolean identityScoped = entry.kind() == ModerationKind.KICK || entry.kind() == ModerationKind.PLAYER_BAN;
            boolean matches = entry.playerId().equalsIgnoreCase(record.playerId())
                    || entry.playerName().equalsIgnoreCase(record.playerName())
                    || entry.target().equalsIgnoreCase(record.playerId())
                    || entry.target().equalsIgnoreCase(record.playerName());
            if (!(identityScoped && matches)) entries.add(entry);
        }
        ServerModerationState updated = new ServerModerationState(current.whitelistEnabled(), whitelist, entries);
        if (updated.equals(current)) return null;
        return network.saveServerModeration(updated);
    }

    private static List<String> recover(HeadlessGameServer host, ServerBackupAdmin backupAdmin, Path backup, String detail) {
        String restore = backupAdmin.restoreCurrent(backup);
        host.enterRecoveryRequired("Identity deletion recovery required: " + detail + "; " + restore);
        return List.of("Identity deletion did not complete safely: " + detail + '.',
                "Recovery: " + restore,
                "Restart the server before continuing; runtime saves are blocked to protect the restored archive.");
    }

    private static String age(long now, long then) {
        long seconds = Math.max(0, (now - then) / 1_000L);
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private static List<String> usage() {
        return List.of("Usage: identity <list [active|archived]|dormant <age>|archive <player> confirm|restore <player>|delete <player> confirm>");
    }
}
