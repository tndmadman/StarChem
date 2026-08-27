package com.tndmadman.rts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Server-authoritative, read-only observer permissions and client presentation state. */
final class ObserverSessions {
    static final int DEFAULT_LIMIT = 8;
    static final long DEFAULT_INVITE_MS = 60 * 60_000L;
    private static final int MAX_OBSERVERS = 512;
    private static final Map<World, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<World, ClientState> CLIENT = Collections.synchronizedMap(new WeakHashMap<>());

    private ObserverSessions() { }

    enum VisibilityMode {
        PUBLIC,
        PLAYER_FOLLOW,
        FULL;

        static VisibilityMode parse(String value) {
            if (value == null) return PUBLIC;
            String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if ("FOLLOW".equals(normalized) || "PLAYER".equals(normalized)) normalized = "PLAYER_FOLLOW";
            try { return valueOf(normalized); }
            catch (IllegalArgumentException ex) { return PUBLIC; }
        }

        String label() {
            return switch (this) {
                case PUBLIC -> "PUBLIC";
                case PLAYER_FOLLOW -> "PLAYER FOLLOW";
                case FULL -> "FULL";
            };
        }
    }

    record ClientRequest(boolean requested) {
        static final ClientRequest PLAYER = new ClientRequest(false);
        static final ClientRequest OBSERVER = new ClientRequest(true);
    }

    record Grant(String playerId, String name, VisibilityMode mode, String followPlayerId) {
        Grant {
            playerId = cleanId(playerId);
            name = Config.clean(name);
            mode = mode == null ? VisibilityMode.PUBLIC : mode;
            followPlayerId = cleanId(followPlayerId);
        }
    }

    record Invitation(String name, VisibilityMode mode, String followPlayerId, long expiresAt) {
        Invitation {
            name = Config.clean(name);
            mode = mode == null ? VisibilityMode.PUBLIC : mode;
            followPlayerId = cleanId(followPlayerId);
            expiresAt = Math.max(0, expiresAt);
        }
        boolean expired(long now) { return expiresAt > 0 && now >= expiresAt; }
    }

    static void configure(Config config, World world) {
        if (world == null) return;
        State state = new State(config, world);
        state.load();
        STATES.put(world, state);
    }

    static ClientRequest clientRequest(Config config) {
        return ObserverClientIntent.get(config);
    }

    static boolean clientObserver(World world) {
        ClientState state = CLIENT.get(world);
        return state != null && state.observer;
    }

    static VisibilityMode clientMode(World world) {
        ClientState state = CLIENT.get(world);
        return state == null ? VisibilityMode.PUBLIC : state.mode;
    }

    static String clientFollow(World world) {
        ClientState state = CLIENT.get(world);
        return state == null ? "" : state.followPlayerId;
    }

    static String clientWatermark(World world) {
        if (!clientObserver(world)) return "";
        VisibilityMode mode = clientMode(world);
        String follow = clientFollow(world);
        return "OBSERVER · " + mode.label() + (mode == VisibilityMode.PLAYER_FOLLOW && !follow.isBlank()
                ? " · " + follow : "");
    }

    static boolean applyClientState(World world, String message) {
        if (world == null || message == null || !message.startsWith("OBSERVER_STATE|")) return false;
        String[] parts = message.split("\\|", -1);
        boolean enabled = parts.length > 1 && flag(parts[1]);
        if (!enabled) {
            CLIENT.remove(world);
            return true;
        }
        VisibilityMode mode = VisibilityMode.parse(marker(parts, "MODE"));
        String follow = cleanId(marker(parts, "FOLLOW"));
        CLIENT.put(world, new ClientState(true, mode, follow));
        String local = PlayerRegistry.localId();
        stripIdentityFromActiveSystem(world, local);
        world.completedResearch.remove(local);
        world.setDevFreeBuild(local, false);
        world.status = "Observer session active: " + mode.label()
                + (mode == VisibilityMode.PLAYER_FOLLOW && !follow.isBlank() ? " (following " + follow + ")" : "") + ".";
        return true;
    }

    static void clearClient(World world) {
        if (world != null) CLIENT.remove(world);
    }

    static boolean prepareJoin(PeerServerSide server, ConnectionId connectionId, String name, String[] parts) {
        if (server == null || connectionId == null || !connectionId.valid()) return true;
        State state = state(server.world);
        boolean requested = flag(marker(parts, "OBSERVER"));
        if (!requested) {
            state.pending.remove(connectionId);
            return true;
        }
        long now = System.currentTimeMillis();
        state.pruneExpired(now);
        Grant retained = state.grantByName(name);
        Invitation invite = state.invitation(name);
        if (retained == null && (invite == null || invite.expired(now))) {
            server.transport.sendOrdered("JOIN_DENIED|Observer invitation is missing or expired.", connectionId);
            return false;
        }
        if (!state.enabled) {
            server.transport.sendOrdered("JOIN_DENIED|Observer sessions are disabled on this server.", connectionId);
            return false;
        }
        int connected = connectedCount(server, state, "");
        if (connected >= state.maxObservers && (retained == null || !server.sessionConnected(retained.playerId()))) {
            server.transport.sendOrdered("JOIN_DENIED|Observer slots are full (" + state.maxObservers + ").", connectionId);
            return false;
        }
        VisibilityMode mode = retained == null ? invite.mode() : retained.mode();
        String follow = retained == null ? invite.followPlayerId() : retained.followPlayerId();
        if (mode == VisibilityMode.PLAYER_FOLLOW && !validFollowTarget(server.world, follow)) {
            server.transport.sendOrdered("JOIN_DENIED|Observer follow target is unavailable.", connectionId);
            return false;
        }
        state.pending.put(connectionId, new Pending(Config.clean(name), mode, follow));
        return true;
    }

    static boolean prepareResume(PeerServerSide server, ConnectionId connectionId, String playerId) {
        if (server == null || connectionId == null || !connectionId.valid()) return true;
        State state = state(server.world);
        Grant grant = state.grants.get(cleanId(playerId));
        if (grant == null) return true;
        if (!state.enabled) {
            server.transport.sendOrdered("SESSION_DENIED|Observer sessions are disabled on this server.", connectionId);
            return false;
        }
        if (connectedCount(server, state, grant.playerId()) >= state.maxObservers) {
            server.transport.sendOrdered("SESSION_DENIED|Observer slots are full (" + state.maxObservers + ").", connectionId);
            return false;
        }
        if (grant.mode() == VisibilityMode.PLAYER_FOLLOW && !validFollowTarget(server.world, grant.followPlayerId())) {
            server.transport.sendOrdered("SESSION_DENIED|Observer follow target is unavailable.", connectionId);
            return false;
        }
        return true;
    }

    /** Called by ClientViewCache before it would create a gameplay home. */
    static boolean promoteAtHome(World world, String playerId) {
        State state = STATES.get(world);
        if (state == null) return false;
        String id = cleanId(playerId);
        Grant existing = state.grants.get(id);
        if (existing != null) {
            stripGameplayIdentity(world, id);
            return true;
        }
        String name = PlayerRegistry.baseName(id);
        Pending pending = state.pendingByName(name);
        if (pending == null) return false;
        Invitation invite = state.invitation(name);
        long now = System.currentTimeMillis();
        if (!state.enabled || invite == null || invite.expired(now)) return false;
        Grant grant = new Grant(id, name, pending.mode, pending.followPlayerId);
        state.grants.put(id, grant);
        state.invitations.remove(normalized(name));
        state.removePendingName(name);
        state.save();
        stripGameplayIdentity(world, id);
        return true;
    }

    static void finishAuthentication(PeerServerSide server, ConnectionId connectionId) {
        if (server == null || connectionId == null || !connectionId.valid()) return;
        String playerId = server.ownerId(connectionId, "");
        if (playerId.isBlank()) return;
        State state = state(server.world);
        Grant grant = state.grants.get(playerId);
        if (grant == null) {
            state.pending.remove(connectionId);
            return;
        }
        state.pending.remove(connectionId);
        stripGameplayIdentity(server.world, playerId);
        PlayerRegistry.remove(playerId);
        server.world.setDevFreeBuild(playerId, false);
        server.transport.sendOrdered(packet(grant), connectionId);
        server.sendInitialTo(connectionId);
    }

    static boolean handleObserverControl(PeerServerSide server, String[] parts, ConnectionId connectionId) {
        if (server == null || parts == null || parts.length == 0 || connectionId == null || !connectionId.valid()) return false;
        String type = parts[0];
        if ("OBSERVER_CONVERT".equals(type)) {
            String playerId = parts.length > 1 ? parts[1] : "";
            convertConnectedPlayer(server, connectionId, playerId);
            return true;
        }
        String playerId = server.ownerId(connectionId, "");
        if (!isObserver(server.world, playerId)) return false;
        if ("VIEW_SYSTEM".equals(type)) return false;
        server.transport.sendOrdered("OBSERVER_DENIED|READ_ONLY|" + safe(type), connectionId);
        return true;
    }

    static boolean convertConnectedPlayer(PeerServerSide server, ConnectionId connectionId, String playerId) {
        if (server == null || !server.owns(connectionId, playerId)) return false;
        State state = state(server.world);
        if (!state.enabled) {
            server.transport.sendOrdered("OBSERVER_DENIED|DISABLED|Observer sessions are disabled.", connectionId);
            return false;
        }
        if (connectedCount(server, state, playerId) >= state.maxObservers) {
            server.transport.sendOrdered("OBSERVER_DENIED|FULL|Observer slots are full.", connectionId);
            return false;
        }
        Grant existing = state.grants.get(playerId);
        VisibilityMode mode = existing == null ? VisibilityMode.PUBLIC : existing.mode();
        String follow = existing == null ? "" : existing.followPlayerId();
        Grant grant = new Grant(playerId, PlayerRegistry.baseName(playerId), mode, follow);
        state.grants.put(playerId, grant);
        state.save();
        stripGameplayIdentity(server.world, playerId);
        PlayerRegistry.remove(playerId);
        server.world.setDevFreeBuild(playerId, false);
        server.transport.sendOrdered(packet(grant), connectionId);
        server.sendInitialTo(connectionId);
        server.world.status = grant.name() + " converted to observer mode.";
        return true;
    }

    static boolean isObserver(World world, String playerId) {
        State state = STATES.get(world);
        return state != null && state.grants.containsKey(cleanId(playerId));
    }

    static VisibilityMode mode(World world, String playerId) {
        Grant grant = grant(world, playerId);
        return grant == null ? VisibilityMode.PUBLIC : grant.mode();
    }

    static String followPlayer(World world, String playerId) {
        Grant grant = grant(world, playerId);
        return grant == null ? "" : grant.followPlayerId();
    }

    static String visibilityOwner(World world, String observerId) {
        Grant grant = grant(world, observerId);
        if (grant == null) return "";
        if (grant.mode() == VisibilityMode.PLAYER_FOLLOW) {
            return validFollowTarget(world, grant.followPlayerId()) ? grant.followPlayerId() : "";
        }
        if (grant.mode() == VisibilityMode.PUBLIC) return publicAnchor(world);
        return "";
    }

    static String initialSystem(World world, String observerId) {
        String owner = visibilityOwner(world, observerId);
        if (!owner.isBlank()) {
            String home = world.playerHomeSystemId(owner);
            if (realSystem(home)) return home;
        }
        String active = world.activeSystemId();
        if (realSystem(active)) return active;
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        if (map != null && map.systems() != null) for (GalaxyMapSystem system : map.systems()) {
            if (system != null && realSystem(system.id())) return system.id();
        }
        return StarSystems.DEFAULT_SYSTEM_ID;
    }

    static Snapshot sanitizeSnapshot(World world, String observerId, Snapshot source) {
        if (source == null) return null;
        VisibilityMode mode = mode(world, observerId);
        Snapshot projected;
        if (mode == VisibilityMode.FULL) projected = source;
        else {
            String visibilityOwner = visibilityOwner(world, observerId);
            projected = visibilityOwner.isBlank() ? emptySnapshot(source)
                    : FogSnapshotFilter.forPlayer(world, visibilityOwner, source);
        }
        List<PlayerInfo> players = new ArrayList<>();
        for (PlayerInfo player : projected.players()) if (!isObserver(world, player.id())) players.add(player);
        List<ResearchState> research = mode == VisibilityMode.FULL ? projected.research() : List.of();
        return new Snapshot(projected.sequence(), List.copyOf(players), projected.units(), projected.resources(),
                projected.bases(), List.of(), projected.shots(), projected.items(), projected.systemId(),
                projected.systemTime(), projected.celestialState(), research, projected.objective());
    }

    static GalaxyMapSnapshot emptyPublicGalaxy(GalaxyMapSnapshot source, String activeSystemId) {
        if (source == null || source.systems() == null) return new GalaxyMapSnapshot("", List.of(), List.of());
        List<GalaxyMapSystem> systems = new ArrayList<>();
        for (GalaxyMapSystem system : source.systems()) {
            if (system == null) continue;
            systems.add(new GalaxyMapSystem(system.id(), system.name(), system.templateId(), system.lifetime(),
                    0, 0, 0, 0, 0, system.id().equals(activeSystemId), system.home(), system.special(),
                    "", "Unknown", system.home() ? SystemControlStatus.PROTECTED : SystemControlStatus.NEUTRAL,
                    0, 0x8A96A3));
        }
        return new GalaxyMapSnapshot(activeSystemId, List.copyOf(systems),
                source.links() == null ? List.of() : List.copyOf(source.links()));
    }

    static int normalPlayerSessionCount(PeerServerSide server) {
        if (server == null) return 0;
        int count = 0;
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session != null && !isObserver(server.world, session.playerId())) count++;
        }
        return count;
    }

    static boolean pendingObserver(World world, ConnectionId connectionId) {
        State state = STATES.get(world);
        return state != null && state.pending.containsKey(connectionId);
    }

    static String statusSuffix(PeerServerSide server) {
        if (server == null) return "observers 0/0";
        State state = state(server.world);
        return "observers " + connectedCount(server, state, "") + "/" + state.maxObservers
                + (state.enabled ? "" : " disabled");
    }

    static List<String> console(HeadlessGameServer host, List<String> supplied) {
        if (host == null || host.network == null) return List.of("Observer session context is unavailable.");
        State state = state(host.world);
        List<String> args = supplied == null ? List.of() : supplied;
        if (args.isEmpty() || "status".equalsIgnoreCase(args.get(0))) {
            return List.of("Observers: " + (state.enabled ? "enabled" : "disabled")
                            + " | connected " + connectedCountForNetwork(host.network, state)
                            + " | limit " + state.maxObservers,
                    "Invitations: " + state.invitations.size() + " | retained observer grants " + state.grants.size());
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if (("on".equals(action) || "off".equals(action)) && args.size() == 1) {
            state.enabled = "on".equals(action);
            state.save();
            int disconnected = 0;
            if (!state.enabled) {
                for (Grant grant : new ArrayList<>(state.grants.values())) {
                    if (host.network.disconnectServerPlayer(grant.playerId())) disconnected++;
                }
            }
            return List.of("Observer sessions " + (state.enabled ? "enabled." : "disabled; disconnected " + disconnected + " observer(s)."));
        }
        if ("limit".equals(action) && args.size() == 2) {
            int limit;
            try { limit = Integer.parseInt(args.get(1)); }
            catch (NumberFormatException ex) { return List.of("Observer limit must be numeric."); }
            if (limit < 1 || limit > MAX_OBSERVERS) return List.of("Observer limit must be between 1 and " + MAX_OBSERVERS + ".");
            state.maxObservers = limit;
            state.save();
            return List.of("Observer limit set to " + limit + ".");
        }
        if ("invite".equals(action) && args.size() >= 3 && args.size() <= 4) {
            String name = Config.clean(args.get(1));
            if (name.isBlank()) return List.of("Observer name is required.");
            ModeTarget target = parseModeTarget(args.get(2));
            if (!target.valid) return List.of("Mode must be public, full, or follow:<player>.");
            if (target.mode == VisibilityMode.PLAYER_FOLLOW && !validFollowTarget(host.world, target.follow)) {
                return List.of("Unknown follow player: " + target.follow);
            }
            long durationMs = DEFAULT_INVITE_MS;
            if (args.size() == 4) {
                try { durationMs = ServerCommandDispatcher.parseDurationSeconds(args.get(3)) * 1000L; }
                catch (IllegalArgumentException ex) { return List.of(ex.getMessage()); }
            }
            long expiry = System.currentTimeMillis() + durationMs;
            state.invitations.put(normalized(name), new Invitation(name, target.mode, target.follow, expiry));
            state.save();
            return List.of("Observer invitation created for " + name + " | " + target.mode.label()
                    + (target.follow.isBlank() ? "" : " " + target.follow) + ".");
        }
        if ("revoke".equals(action) && args.size() == 2) {
            String selector = args.get(1);
            boolean changed = state.invitations.remove(normalized(selector)) != null;
            Grant grant = state.grantBySelector(selector);
            if (grant != null) {
                state.grants.remove(grant.playerId());
                host.network.disconnectServerPlayer(grant.playerId());
                changed = true;
            }
            state.save();
            return List.of(changed ? "Observer permission revoked for " + selector + "." : "No observer permission matched " + selector + ".");
        }
        if ("list".equals(action) && args.size() == 1) {
            ArrayList<String> lines = new ArrayList<>();
            long now = System.currentTimeMillis();
            state.pruneExpired(now);
            for (Grant grant : state.grants.values()) {
                lines.add(grant.playerId() + " | " + grant.name() + " | " + grant.mode().label()
                        + (grant.followPlayerId().isBlank() ? "" : " " + grant.followPlayerId())
                        + " | " + (host.network.serverSessionConnected(grant.playerId()) ? "connected" : "retained"));
            }
            for (Invitation invite : state.invitations.values()) {
                lines.add("invite | " + invite.name() + " | " + invite.mode().label()
                        + (invite.followPlayerId().isBlank() ? "" : " " + invite.followPlayerId())
                        + " | expires in " + Math.max(0, (invite.expiresAt() - now) / 1000) + "s");
            }
            lines.sort(String::compareToIgnoreCase);
            return lines.isEmpty() ? List.of("No observer grants or invitations.") : List.copyOf(lines);
        }
        return List.of("Usage: dev role observer <status|on|off|limit <count>|invite <name> <public|full|follow:player> [duration]|revoke <name-or-id>|list>");
    }

    private static Snapshot emptySnapshot(Snapshot source) {
        return new Snapshot(source.sequence(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), source.systemId(), source.systemTime(), source.celestialState(), List.of(), source.objective());
    }

    private static Grant grant(World world, String playerId) {
        State state = STATES.get(world);
        return state == null ? null : state.grants.get(cleanId(playerId));
    }

    private static State state(World world) {
        State state = STATES.get(world);
        if (state != null) return state;
        state = new State(null, world);
        STATES.put(world, state);
        return state;
    }

    private static String packet(Grant grant) {
        return "OBSERVER_STATE|1|MODE|" + grant.mode().name() + "|FOLLOW|" + safe(grant.followPlayerId());
    }

    private static int connectedCount(PeerServerSide server, State state, String excludingPlayerId) {
        int count = 0;
        for (Grant grant : state.grants.values()) {
            if (grant.playerId().equals(excludingPlayerId)) continue;
            if (server.sessionConnected(grant.playerId())) count++;
        }
        return count;
    }

    private static int connectedCountForNetwork(PeerNetwork network, State state) {
        int count = 0;
        for (Grant grant : state.grants.values()) if (network.serverSessionConnected(grant.playerId())) count++;
        return count;
    }

    private static String publicAnchor(World world) {
        if (world == null) return "";
        ArrayList<PlayerInfo> players = new ArrayList<>(PlayerRegistry.snapshotPlayers());
        players.sort(Comparator.comparing(PlayerInfo::id));
        for (PlayerInfo player : players) {
            if (!realPlayer(player.id()) || isObserver(world, player.id())) continue;
            if (hasAssetsAnywhere(world, player.id())) return player.id();
        }
        for (PlayerInfo player : players) if (realPlayer(player.id()) && !isObserver(world, player.id())) return player.id();
        return "";
    }

    private static boolean validFollowTarget(World world, String playerId) {
        if (!realPlayer(playerId) || isObserver(world, playerId)) return false;
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) if (playerId.equals(player.id())) return true;
        return hasAssetsAnywhere(world, playerId);
    }

    private static boolean hasAssetsAnywhere(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank()) return false;
        String old = world.activeSystemId();
        try {
            for (String systemId : systemIds(world)) {
                world.activateSystem(systemId);
                if (world.hasLiveAssets(playerId)) return true;
            }
            return false;
        } finally {
            world.activateSystem(old);
        }
    }

    static void stripGameplayIdentity(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank()) return;
        String old = world.activeSystemId();
        try {
            for (String systemId : systemIds(world)) {
                world.activateSystem(systemId);
                stripIdentityFromActiveSystem(world, playerId);
                world.saveActiveSystem();
            }
            world.completedResearch.remove(playerId);
        } finally {
            world.activateSystem(old);
        }
    }

    private static void stripIdentityFromActiveSystem(World world, String playerId) {
        world.units.entrySet().removeIf(entry -> entry.getValue() != null && playerId.equals(entry.getValue().playerId));
        world.bases.entrySet().removeIf(entry -> entry.getValue() != null && playerId.equals(entry.getValue().playerId));
    }

    private static Set<String> systemIds(World world) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        if (map != null && map.systems() != null) for (GalaxyMapSystem system : map.systems()) {
            if (system != null && realSystem(system.id())) ids.add(system.id());
        }
        if (realSystem(world.activeSystemId())) ids.add(world.activeSystemId());
        return ids;
    }

    private static ModeTarget parseModeTarget(String value) {
        if (value == null) return new ModeTarget(false, VisibilityMode.PUBLIC, "");
        String clean = value.trim();
        if (clean.equalsIgnoreCase("public")) return new ModeTarget(true, VisibilityMode.PUBLIC, "");
        if (clean.equalsIgnoreCase("full")) return new ModeTarget(true, VisibilityMode.FULL, "");
        if (clean.toLowerCase(Locale.ROOT).startsWith("follow:")) {
            String follow = cleanId(clean.substring("follow:".length()));
            return new ModeTarget(!follow.isBlank(), VisibilityMode.PLAYER_FOLLOW, follow);
        }
        return new ModeTarget(false, VisibilityMode.PUBLIC, "");
    }

    private static boolean realPlayer(String id) {
        return id != null && !id.isBlank() && !"WAIT".equals(id) && !"SOLO".equals(id) && !NpcRules.isNpcFaction(id);
    }

    private static boolean realSystem(String id) {
        return id != null && !id.isBlank() && !id.contains("WAIT");
    }

    private static String marker(String[] parts, String marker) {
        if (parts == null || marker == null) return "";
        for (int i = 0; i + 1 < parts.length; i++) if (marker.equalsIgnoreCase(parts[i])) return parts[i + 1];
        return "";
    }

    private static boolean flag(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value)
                || "observer".equalsIgnoreCase(value);
    }

    private static String cleanId(String value) {
        return value == null ? "" : value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String normalized(String value) { return Config.clean(value).toLowerCase(Locale.ROOT); }
    private static String safe(String value) { return cleanId(value); }

    private record Pending(String name, VisibilityMode mode, String followPlayerId) { }
    private record ModeTarget(boolean valid, VisibilityMode mode, String follow) { }
    private record ClientState(boolean observer, VisibilityMode mode, String followPlayerId) { }

    private static final class State {
        final World world;
        final Path path;
        final Map<String, Invitation> invitations = new LinkedHashMap<>();
        final Map<String, Grant> grants = new LinkedHashMap<>();
        final Map<ConnectionId, Pending> pending = new LinkedHashMap<>();
        boolean enabled;
        int maxObservers = DEFAULT_LIMIT;

        State(Config config, World world) {
            this.world = world;
            Path dir = config == null || config.saveDir == null ? null : config.saveDir;
            this.path = dir == null ? null : dir.resolve(Config.cleanSaveName(config.saveName) + "-observers.json");
        }

        Invitation invitation(String name) { return invitations.get(normalized(name)); }

        Grant grantByName(String name) {
            String wanted = normalized(name);
            for (Grant grant : grants.values()) if (wanted.equals(normalized(grant.name()))) return grant;
            return null;
        }

        Grant grantBySelector(String selector) {
            String wanted = cleanId(selector);
            Grant direct = grants.get(wanted);
            return direct != null ? direct : grantByName(selector);
        }

        Pending pendingByName(String name) {
            String wanted = normalized(name);
            for (Pending value : pending.values()) if (wanted.equals(normalized(value.name))) return value;
            return null;
        }

        void removePendingName(String name) {
            String wanted = normalized(name);
            pending.entrySet().removeIf(entry -> wanted.equals(normalized(entry.getValue().name)));
        }

        void pruneExpired(long now) {
            if (invitations.entrySet().removeIf(entry -> entry.getValue().expired(now))) save();
        }

        void load() {
            invitations.clear();
            grants.clear();
            if (path == null || !Files.isRegularFile(path)) return;
            try {
                Object parsed = MiniJson.parse(Files.readString(path, StandardCharsets.UTF_8));
                if (!(parsed instanceof Map<?,?> raw)) throw new IOException("observer file root is not an object");
                enabled = bool(raw.get("enabled"), false);
                maxObservers = clampInt(raw.get("maxObservers"), DEFAULT_LIMIT, 1, MAX_OBSERVERS);
                Object inviteValue = raw.get("invitations");
                if (inviteValue instanceof List<?> rows) for (Object rowValue : rows) {
                    if (!(rowValue instanceof Map<?,?> row)) continue;
                    String name = text(row.get("name"));
                    if (name.isBlank()) continue;
                    Invitation invite = new Invitation(name, VisibilityMode.parse(text(row.get("mode"))),
                            text(row.get("followPlayerId")), number(row.get("expiresAt"), 0));
                    if (!invite.expired(System.currentTimeMillis())) invitations.put(normalized(name), invite);
                }
                Object grantValue = raw.get("grants");
                if (grantValue instanceof List<?> rows) for (Object rowValue : rows) {
                    if (!(rowValue instanceof Map<?,?> row)) continue;
                    Grant grant = new Grant(text(row.get("playerId")), text(row.get("name")),
                            VisibilityMode.parse(text(row.get("mode"))), text(row.get("followPlayerId")));
                    if (!grant.playerId().isBlank()) grants.put(grant.playerId(), grant);
                }
            } catch (Exception ex) {
                enabled = false;
                maxObservers = DEFAULT_LIMIT;
                invitations.clear();
                grants.clear();
                System.err.println("Observer permissions failed closed: " + ex.getMessage());
            }
        }

        synchronized void save() {
            if (path == null) return;
            try {
                Files.createDirectories(path.getParent());
                Map<String,Object> root = new LinkedHashMap<>();
                root.put("version", 1);
                root.put("enabled", enabled);
                root.put("maxObservers", maxObservers);
                List<Object> inviteRows = new ArrayList<>();
                for (Invitation invite : invitations.values()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("name", invite.name());
                    row.put("mode", invite.mode().name());
                    row.put("followPlayerId", invite.followPlayerId());
                    row.put("expiresAt", invite.expiresAt());
                    inviteRows.add(row);
                }
                root.put("invitations", inviteRows);
                List<Object> grantRows = new ArrayList<>();
                for (Grant grant : grants.values()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("playerId", grant.playerId());
                    row.put("name", grant.name());
                    row.put("mode", grant.mode().name());
                    row.put("followPlayerId", grant.followPlayerId());
                    grantRows.add(row);
                }
                root.put("grants", grantRows);
                Path temp = path.resolveSibling(path.getFileName() + ".tmp");
                Files.writeString(temp, MiniJson.stringify(root), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (IOException ex) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
            } catch (IOException ex) {
                System.err.println("Could not persist observer permissions: " + ex.getMessage());
            }
        }

        private static boolean bool(Object value, boolean fallback) {
            return value instanceof Boolean flag ? flag : fallback;
        }
        private static int clampInt(Object value, int fallback, int min, int max) {
            if (!(value instanceof Number number)) return fallback;
            return Math.max(min, Math.min(max, number.intValue()));
        }
        private static long number(Object value, long fallback) {
            return value instanceof Number number ? number.longValue() : fallback;
        }
        private static String text(Object value) { return value instanceof String text ? text : ""; }
    }
}

final class ObserverClientIntent {
    private static final Map<Config, ObserverSessions.ClientRequest> REQUESTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ObserverClientIntent() { }

    static void set(Config config, boolean observer) {
        if (config == null) return;
        if (observer) REQUESTS.put(config, ObserverSessions.ClientRequest.OBSERVER);
        else REQUESTS.remove(config);
    }

    static ObserverSessions.ClientRequest get(Config config) {
        return config == null ? ObserverSessions.ClientRequest.PLAYER
                : REQUESTS.getOrDefault(config, ObserverSessions.ClientRequest.PLAYER);
    }
}
