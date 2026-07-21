package com.tndmadman.rts;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Persistent moderation policy shared by console commands and network admission. */
final class ServerModeration {
    static final long PERMANENT = 0L;
    static final int MAX_REASON = 512;
    private static final int ENTRY_ID_BYTES = 16;
    private static final SecureRandom ENTRY_ID_RANDOM = new SecureRandom();

    private ServerModeration() { }

    static String clean(String value) {
        if (value == null) return "";
        String clean = value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= MAX_REASON ? clean : clean.substring(0, MAX_REASON);
    }

    static String normalizeName(String value) {
        return Config.clean(value).toLowerCase(Locale.ROOT);
    }

    static String normalizePlayerId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static String newEntryId() {
        byte[] random = new byte[ENTRY_ID_BYTES];
        ENTRY_ID_RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    static long parseModerationExpiry(String value, long now) {
        if (value == null || value.isBlank() || "permanent".equalsIgnoreCase(value) || "perm".equalsIgnoreCase(value)) {
            return PERMANENT;
        }
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
                default -> throw new IllegalArgumentException("duration must use s, m, h, d, w, or permanent");
            };
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        long amount;
        try { amount = Long.parseLong(normalized); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("duration is not numeric"); }
        if (amount < 1 || amount > (10L * 365 * 86_400) / multiplier) {
            throw new IllegalArgumentException("duration must be between 1 second and 10 years");
        }
        long seconds = Math.multiplyExact(amount, multiplier);
        return Math.addExact(now, Math.multiplyExact(seconds, 1_000L));
    }

    static String duration(long expiresAt, long now) {
        if (expiresAt <= 0) return "permanent";
        long seconds = Math.max(0, (expiresAt - now + 999) / 1_000);
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long remainder = seconds % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + remainder + "s";
        return remainder + "s";
    }
}

enum ModerationKind {
    KICK,
    PLAYER_BAN,
    IP_BAN,
    DEVICE_BAN
}

record ModerationEntry(String id, ModerationKind kind, String playerId, String playerName, String target,
                       long createdAt, long expiresAt, String reason) {
    ModerationEntry {
        id = id == null || id.isBlank() ? ServerModeration.newEntryId() : id.trim();
        kind = kind == null ? ModerationKind.PLAYER_BAN : kind;
        playerId = playerId == null ? "" : playerId.trim();
        playerName = Config.clean(playerName);
        target = target == null ? "" : target.trim();
        createdAt = Math.max(0, createdAt);
        expiresAt = Math.max(0, expiresAt);
        reason = ServerModeration.clean(reason);
    }

    ModerationEntry withId(String replacementId) {
        return new ModerationEntry(replacementId, kind, playerId, playerName, target, createdAt, expiresAt, reason);
    }

    boolean active(long now) { return expiresAt == ServerModeration.PERMANENT || expiresAt > now; }

    String label(long now) {
        String subject = switch (kind) {
            case KICK, PLAYER_BAN -> !playerId.isBlank() ? playerId + (playerName.isBlank() ? "" : "/" + playerName) : target;
            case IP_BAN -> target;
            case DEVICE_BAN -> ServerDeviceIdentity.mask(target);
        };
        return id + " | " + kind.name().toLowerCase(Locale.ROOT) + " | " + subject + " | "
                + ServerModeration.duration(expiresAt, now) + (reason.isBlank() ? "" : " | " + reason);
    }
}

record ModerationRemoval(ServerModerationState state, int removedCount, boolean exactId) {
    ModerationRemoval {
        state = state == null ? ServerModerationState.open() : state;
        removedCount = Math.max(0, removedCount);
    }
}

record ServerModerationState(boolean whitelistEnabled, Set<String> whitelist, List<ModerationEntry> entries) {
    private static final Set<ModerationKind> BAN_KINDS = Set.of(
            ModerationKind.PLAYER_BAN, ModerationKind.IP_BAN, ModerationKind.DEVICE_BAN);

    ServerModerationState {
        whitelist = whitelist == null ? Set.of() : Set.copyOf(whitelist);
        entries = uniqueEntries(entries);
    }

    static ServerModerationState open() { return new ServerModerationState(false, Set.of(), List.of()); }

    ServerModerationState withWhitelistEnabled(boolean enabled) {
        return new ServerModerationState(enabled, whitelist, entries);
    }

    ServerModerationState addWhitelist(String playerId, String name) {
        LinkedHashSet<String> next = new LinkedHashSet<>(whitelist);
        String id = ServerModeration.normalizePlayerId(playerId);
        String normalizedName = ServerModeration.normalizeName(name);
        if (!id.isBlank()) next.add("p:" + id);
        if (!normalizedName.isBlank()) next.add("n:" + normalizedName);
        return new ServerModerationState(whitelistEnabled, next, entries);
    }

    ServerModerationState removeWhitelist(String selector) {
        String normalized = selector == null ? "" : selector.trim().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> next = new LinkedHashSet<>(whitelist);
        next.remove(normalized.startsWith("p:") || normalized.startsWith("n:") ? normalized : "p:" + normalized);
        next.remove(normalized.startsWith("p:") || normalized.startsWith("n:") ? normalized : "n:" + ServerModeration.normalizeName(selector));
        return new ServerModerationState(whitelistEnabled, next, entries);
    }

    boolean whitelisted(String playerId, String name) {
        if (!whitelistEnabled) return true;
        String id = ServerModeration.normalizePlayerId(playerId);
        String normalizedName = ServerModeration.normalizeName(name);
        return (!id.isBlank() && whitelist.contains("p:" + id))
                || (!normalizedName.isBlank() && whitelist.contains("n:" + normalizedName));
    }

    ServerModerationState add(ModerationEntry entry) {
        ArrayList<ModerationEntry> next = new ArrayList<>(entries);
        next.add(entry);
        return new ServerModerationState(whitelistEnabled, whitelist, next);
    }

    ModerationRemoval removeById(String selector, Set<ModerationKind> allowedKinds) {
        String wanted = selector == null ? "" : selector.trim();
        if (wanted.isBlank()) return new ModerationRemoval(this, 0, true);
        ArrayList<ModerationEntry> next = new ArrayList<>(entries.size());
        boolean removed = false;
        for (ModerationEntry entry : entries) {
            if (!removed && kindAllowed(entry, allowedKinds) && entry.id().equalsIgnoreCase(wanted)) {
                removed = true;
                continue;
            }
            next.add(entry);
        }
        return removed
                ? new ModerationRemoval(new ServerModerationState(whitelistEnabled, whitelist, next), 1, true)
                : new ModerationRemoval(this, 0, true);
    }

    ModerationRemoval removeBySelector(String selector, Set<ModerationKind> allowedKinds) {
        String wanted = selector == null ? "" : selector.trim();
        if (wanted.isBlank()) return new ModerationRemoval(this, 0, false);
        ArrayList<ModerationEntry> next = new ArrayList<>(entries.size());
        int removed = 0;
        for (ModerationEntry entry : entries) {
            boolean targetMatches = entry.kind() == ModerationKind.DEVICE_BAN
                    ? entry.target().equals(wanted)
                    : entry.target().equalsIgnoreCase(wanted);
            boolean matches = entry.playerId().equalsIgnoreCase(wanted)
                    || entry.playerName().equalsIgnoreCase(wanted)
                    || targetMatches
                    || ServerModeration.normalizeName(entry.playerName()).equals(ServerModeration.normalizeName(wanted));
            if (kindAllowed(entry, allowedKinds) && matches) removed++;
            else next.add(entry);
        }
        return removed > 0
                ? new ModerationRemoval(new ServerModerationState(whitelistEnabled, whitelist, next), removed, false)
                : new ModerationRemoval(this, 0, false);
    }

    ServerModerationState removeMatching(String selector, ModerationKind onlyKind) {
        Set<ModerationKind> allowedKinds = onlyKind == null ? BAN_KINDS : Set.of(onlyKind);
        ModerationRemoval exact = removeById(selector, allowedKinds);
        if (exact.removedCount() > 0) return exact.state();
        return removeBySelector(selector, allowedKinds).state();
    }

    ServerModerationState activeOnly(long now) {
        ArrayList<ModerationEntry> next = new ArrayList<>();
        for (ModerationEntry entry : entries) if (entry.active(now)) next.add(entry);
        return next.size() == entries.size() ? this : new ServerModerationState(whitelistEnabled, whitelist, next);
    }

    List<ModerationEntry> active(ModerationKind kind, long now) {
        ArrayList<ModerationEntry> out = new ArrayList<>();
        for (ModerationEntry entry : entries) if (entry.active(now) && (kind == null || entry.kind() == kind)) out.add(entry);
        out.sort(Comparator.comparing(ModerationEntry::createdAt));
        return List.copyOf(out);
    }

    ModerationEntry blocked(String playerId, String name, InetAddress address, String deviceId, long now) {
        String normalizedId = ServerModeration.normalizePlayerId(playerId);
        String normalizedName = ServerModeration.normalizeName(name);
        for (ModerationEntry entry : entries) {
            if (!entry.active(now)) continue;
            boolean match = switch (entry.kind()) {
                case KICK, PLAYER_BAN -> (!normalizedId.isBlank() && normalizedId.equals(ServerModeration.normalizePlayerId(entry.playerId())))
                        || (!normalizedName.isBlank() && normalizedName.equals(ServerModeration.normalizeName(entry.playerName())))
                        || (!normalizedName.isBlank() && normalizedName.equals(ServerModeration.normalizeName(entry.target())));
                case IP_BAN -> IpBanMatcher.matches(entry.target(), address);
                case DEVICE_BAN -> ServerDeviceIdentity.equal(entry.target(), deviceId);
            };
            if (match) return entry;
        }
        return null;
    }

    private static List<ModerationEntry> uniqueEntries(List<ModerationEntry> source) {
        if (source == null || source.isEmpty()) return List.of();
        ArrayList<ModerationEntry> unique = new ArrayList<>(source.size());
        HashSet<String> ids = new HashSet<>();
        for (ModerationEntry entry : source) {
            if (entry == null) continue;
            ModerationEntry candidate = entry;
            while (!ids.add(candidate.id().toLowerCase(Locale.ROOT))) {
                candidate = candidate.withId(ServerModeration.newEntryId());
            }
            unique.add(candidate);
        }
        return List.copyOf(unique);
    }

    private static boolean kindAllowed(ModerationEntry entry, Set<ModerationKind> allowedKinds) {
        return allowedKinds == null || allowedKinds.contains(entry.kind());
    }
}
