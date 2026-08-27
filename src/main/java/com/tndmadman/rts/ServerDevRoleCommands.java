package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Clear role-oriented developer and observer access controls for the trusted local server console. */
final class ServerDevRoleCommands {
    private ServerDevRoleCommands() { }

    static List<String> help(List<String> supplied) {
        List<String> args = supplied == null ? List.of() : supplied;
        if (args.size() > 1) return List.of("Usage: help dev [access|role|freebuild|mode|observer]");
        String topic = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
        return switch (topic) {
            case "" -> List.of(
                    "Developer access quick start:",
                    "  dev mode on",
                    "  dev role list",
                    "  dev role set <player> developer",
                    "  dev role set <player> developer-freebuild",
                    "  dev role set <player> none",
                    "Observer administration: dev role observer status",
                    "Use 'help dev role', 'help dev access', 'help dev freebuild', 'help dev mode', or 'help dev observer' for details.");
            case "role" -> List.of(
                    "dev role list [all|connected|granted] - Show retained players and their effective developer role.",
                    "dev role show <player> - Show one retained player's connection, request, access, and free-build state.",
                    "dev role set <player> none - Revoke developer access and free-build, including while offline.",
                    "dev role set <player> developer - Grant developer access without free-build.",
                    "dev role set <player> developer-freebuild - Grant developer access and free-build together.",
                    "dev role observer ... - Administer the separate read-only observer role.");
            case "observer" -> List.of(
                    "dev role observer status - Show observer enablement, separate slot use, invitations, and retained grants.",
                    "dev role observer on|off - Enable observers or disable them and disconnect connected observers.",
                    "dev role observer limit <count> - Set the independent observer slot limit.",
                    "dev role observer invite <name> public [duration] - Invite a public-view observer.",
                    "dev role observer invite <name> full [duration] - Invite a trusted full-view observer.",
                    "dev role observer invite <name> follow:<player> [duration] - Lock an observer to a player's visibility.",
                    "dev role observer revoke <name-or-id> - Revoke an invitation/grant and disconnect the observer.",
                    "dev role observer list - List invitations and retained observer grants.");
            case "access" -> List.of(
                    "dev access list - Show connected developer candidates.",
                    "dev access requests - Show pending client requests.",
                    "dev access grant <player> - Grant developer access to a retained identity; free-build is unchanged.",
                    "dev access revoke <player> - Revoke access and free-build from a retained identity, even while offline.",
                    "dev access revoke-all - Revoke every runtime developer grant.",
                    "Prefer 'dev role set' when assigning a complete, explicit role.");
            case "freebuild" -> List.of(
                    "dev freebuild status <player> - Show free-build state.",
                    "dev freebuild <player> on|off - Change free-build independently.",
                    "Free-build does not replace developer access; use 'dev role set <player> developer-freebuild' for both.");
            case "mode" -> List.of(
                    "dev mode status - Show runtime developer mode.",
                    "dev mode on - Enable runtime developer controls for this server process.",
                    "dev mode off [confirm] - Disable developer controls, revoke grants, and reset developer simulation state.");
            default -> List.of("Unknown developer help topic: " + args.get(0)
                    + ". Use 'help dev [access|role|freebuild|mode|observer]'.");
        };
    }

    static List<String> execute(ServerCommandDispatcher.Target target, List<String> supplied) {
        HeadlessGameServer host = host(target);
        if (host == null || host.network == null) return List.of("Developer role context is unavailable.");
        List<String> args = supplied == null ? List.of() : supplied;
        if (!args.isEmpty() && "observer".equalsIgnoreCase(args.get(0))) {
            return ObserverSessions.console(host, args.subList(1, args.size()));
        }
        if (args.isEmpty() || "list".equalsIgnoreCase(args.get(0))) return list(host.network, args);
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if ("show".equals(action) && args.size() == 2) {
            PersistentPlayerSession session = resolve(host.network, args.get(1));
            return session == null ? List.of("Unknown retained player identity: " + args.get(1))
                    : List.of(describe(host, session, peerMap(host.network).get(session.playerId())));
        }
        if ("set".equals(action) && args.size() == 3) {
            PersistentPlayerSession session = resolve(host.network, args.get(1));
            if (session == null) return List.of("Unknown retained player identity: " + args.get(1));
            String role = normalizeRole(args.get(2));
            if (role.isBlank()) return List.of("Usage: dev role set <player> <none|developer|developer-freebuild>");
            if (ObserverSessions.isObserver(host.world, session.playerId()) && !"none".equals(role)) {
                return List.of("Observer identities are read-only and cannot receive developer roles.");
            }
            if (!"none".equals(role) && !host.network.runtimeDevEnabled()) {
                return List.of("Runtime developer mode is disabled. Use 'dev mode on' first.");
            }
            apply(host.network, session.playerId(), role);
            String connection = host.network.serverSessionConnected(session.playerId()) ? "connected" : "retained/offline";
            return List.of("Developer role for " + session.playerId() + " set to " + role + " (" + connection + ").");
        }
        return List.of("Usage: dev role <list [all|connected|granted]|show <player>|set <player> <none|developer|developer-freebuild>|observer ...>");
    }

    static List<String> accessAlias(ServerCommandDispatcher.Target target, List<String> supplied) {
        HeadlessGameServer host = host(target);
        if (host == null || host.network == null) return List.of("Developer access context is unavailable.");
        List<String> args = supplied == null ? List.of() : supplied;
        if (args.size() != 3 || !"access".equalsIgnoreCase(args.get(0))) {
            return List.of("Usage: dev access <grant|revoke> <player>");
        }
        String action = args.get(1).toLowerCase(Locale.ROOT);
        if (!"grant".equals(action) && !"revoke".equals(action)) {
            return List.of("Usage: dev access <grant|revoke> <player>");
        }
        PersistentPlayerSession session = resolve(host.network, args.get(2));
        if (session == null) return List.of("Unknown retained player identity: " + args.get(2));
        if ("grant".equals(action)) {
            if (ObserverSessions.isObserver(host.world, session.playerId())) {
                return List.of("Observer identities are read-only and cannot receive developer access.");
            }
            if (!host.network.runtimeDevEnabled()) return List.of("Runtime developer mode is disabled. Use 'dev mode on' first.");
            host.network.setRemoteDevAccess(session.playerId(), true);
            return List.of("Developer access granted to " + session.playerId()
                    + ". Free-build is unchanged; use 'dev role set " + session.playerId() + " developer-freebuild' to grant both.");
        }
        host.network.setServerFreeBuild(session.playerId(), false);
        host.network.setRemoteDevAccess(session.playerId(), false);
        return List.of("Developer access and free-build revoked from " + session.playerId() + ".");
    }

    private static List<String> list(PeerNetwork network, List<String> args) {
        if (args.size() > 2) return List.of("Usage: dev role list [all|connected|granted]");
        String filter = args.size() == 2 ? args.get(1).toLowerCase(Locale.ROOT) : "all";
        if (!List.of("all", "connected", "granted").contains(filter)) {
            return List.of("Usage: dev role list [all|connected|granted]");
        }
        Map<String,DevPeerAccess> peers = peerMap(network);
        HeadlessGameServer host = null;
        ArrayList<String> lines = new ArrayList<>();
        for (PersistentPlayerSession session : network.persistentPlayerSessions()) {
            if (session == null) continue;
            boolean connected = network.serverSessionConnected(session.playerId());
            boolean granted = network.runtimeDevAccessGranted(session.playerId());
            if ("connected".equals(filter) && !connected) continue;
            if ("granted".equals(filter) && !granted) continue;
            lines.add(describe(network, session, peers.get(session.playerId())));
        }
        return lines.isEmpty() ? List.of("No matching retained player identities.") : List.copyOf(lines);
    }

    private static String describe(HeadlessGameServer host, PersistentPlayerSession session, DevPeerAccess peer) {
        if (ObserverSessions.isObserver(host.world, session.playerId())) {
            return session.playerId() + " | " + session.name() + " | "
                    + (host.network.serverSessionConnected(session.playerId()) ? "connected" : "retained")
                    + " | role observer-" + ObserverSessions.mode(host.world, session.playerId()).name().toLowerCase(Locale.ROOT);
        }
        return describe(host.network, session, peer);
    }

    private static String describe(PeerNetwork network, PersistentPlayerSession session, DevPeerAccess peer) {
        boolean access = network.runtimeDevAccessGranted(session.playerId());
        boolean freebuild = network.runtimeFreeBuildEnabled(session.playerId());
        String role = freebuild ? "developer-freebuild" : access ? "developer" : "none";
        boolean requested = peer != null && peer.requested();
        boolean local = peer != null && peer.local();
        return session.playerId() + " | " + session.name()
                + " | " + (network.serverSessionConnected(session.playerId()) ? "connected" : "retained")
                + " | role " + role + " | requested " + requested + " | local " + local;
    }

    private static void apply(PeerNetwork network, String playerId, String role) {
        switch (role) {
            case "none" -> {
                network.setServerFreeBuild(playerId, false);
                network.setRemoteDevAccess(playerId, false);
            }
            case "developer" -> {
                network.setRemoteDevAccess(playerId, true);
                network.setServerFreeBuild(playerId, false);
            }
            case "developer-freebuild" -> {
                network.setRemoteDevAccess(playerId, true);
                network.setServerFreeBuild(playerId, true);
            }
            default -> throw new IllegalArgumentException("Unknown developer role: " + role);
        }
    }

    private static String normalizeRole(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("off".equals(normalized) || "revoked".equals(normalized)) return "none";
        if ("dev".equals(normalized)) return "developer";
        if ("freebuild".equals(normalized) || "developer+freebuild".equals(normalized)) return "developer-freebuild";
        return List.of("none", "developer", "developer-freebuild").contains(normalized) ? normalized : "";
    }

    private static PersistentPlayerSession resolve(PeerNetwork network, String selector) {
        if (selector == null) return null;
        for (PersistentPlayerSession session : network.persistentPlayerSessions()) {
            if (session != null && (session.playerId().equalsIgnoreCase(selector)
                    || session.name().equalsIgnoreCase(selector))) return session;
        }
        return null;
    }

    private static Map<String,DevPeerAccess> peerMap(PeerNetwork network) {
        LinkedHashMap<String,DevPeerAccess> peers = new LinkedHashMap<>();
        for (DevPeerAccess peer : network.devAccessPeers()) if (peer != null) peers.put(peer.playerId(), peer);
        return peers;
    }

    private static HeadlessGameServer host(ServerCommandDispatcher.Target target) {
        if (target == null) return null;
        Object context = target.extensionContext();
        return context instanceof HeadlessGameServer host ? host : null;
    }
}
