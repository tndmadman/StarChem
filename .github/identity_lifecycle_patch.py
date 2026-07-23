from pathlib import Path

def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label} in {path}, found {count}")
    file.write_text(text.replace(old, new, 1), encoding='utf-8')

replace_once(
    'src/main/java/com/tndmadman/rts/CompanionStateFiles.java',
    'return "{\\"version\\":1,\\"maintenance\\":false,\\"maintenanceReason\\":\\"\\",\\"maxSlots\\":0,\\"motd\\":\\"\\"}";',
    'return "{\\"version\\":1,\\"maintenance\\":false,\\"maintenanceReason\\":\\"\\",\\"maxSlots\\":128,\\"motd\\":\\"\\"}";',
    'fresh-server retained identity limit',
)

replace_once(
    'src/main/java/com/tndmadman/rts/PeerServerSide.java',
    '''    int peerCount() { return peers.size(); }\n    boolean sessionConnected(String playerId) {\n''',
    '''    int peerCount() { return peers.size(); }\n    void reserveNextPlayer(int candidate) { nextPlayer = Math.max(nextPlayer, Math.max(1, candidate)); }\n    boolean sessionConnected(String playerId) {\n''',
    'next player high-water reservation',
)

replace_once(
    'src/main/java/com/tndmadman/rts/PeerNetworkFacade.java',
    '''    private final ServerPlayerObservationStore observationStore;\n    private final ServerEventJournal journal;\n''',
    '''    private final ServerPlayerObservationStore observationStore;\n    private final ServerIdentityStore identityStore;\n    private final ServerEventJournal journal;\n''',
    'identity store field',
)

replace_once(
    'src/main/java/com/tndmadman/rts/PeerNetworkFacade.java',
    '''        this.observationStore = new ServerPlayerObservationStore(config == null ? null : config.saveDir,\n                config == null ? "server" : config.saveName);\n        this.journal = new ServerEventJournal(config == null ? null : config.saveDir,\n                config == null ? "server" : config.saveName);\n        this.moderation = server == null ? ServerModerationState.open() : moderationStore.load();\n        this.runtimeDevEnabled = config != null && config.devMode;\n''',
    '''        this.observationStore = new ServerPlayerObservationStore(config == null ? null : config.saveDir,\n                config == null ? "server" : config.saveName);\n        this.identityStore = server == null ? null : new ServerIdentityStore(config == null ? null : config.saveDir,\n                config == null ? "server" : config.saveName);\n        this.journal = new ServerEventJournal(config == null ? null : config.saveDir,\n                config == null ? "server" : config.saveName);\n        this.moderation = server == null ? ServerModerationState.open() : moderationStore.load();\n        if (server != null && identityStore != null) {\n            ServerIdentityStore.MutationResult synchronizedState = identityStore.synchronize(server.persistentSessions());\n            if (!synchronizedState.success()) {\n                System.err.println("Could not synchronize retained identity lifecycle state: " + synchronizedState.message());\n            }\n            server.reserveNextPlayer(identityStore.nextPlayerNumber());\n        }\n        this.runtimeDevEnabled = config != null && config.devMode;\n''',
    'identity store initialization',
)

replace_once(
    'src/main/java/com/tndmadman/rts/PeerNetworkFacade.java',
    '''    ServerEventJournal serverJournal() { return journal; }\n    ServerModerationState serverModeration() { return moderation == null ? ServerModerationState.open() : moderation; }\n''',
    '''    ServerEventJournal serverJournal() { return journal; }\n    ServerIdentityStore serverIdentityStore() { return identityStore; }\n    ServerModerationState serverModeration() { return moderation == null ? ServerModerationState.open() : moderation; }\n''',
    'identity store accessor',
)

replace_once(
    'src/main/java/com/tndmadman/rts/PeerNetworkFacade.java',
    '''    boolean disconnectServerPlayer(String playerId) {\n        if (server == null || playerId == null || playerId.isBlank()) return false;\n        ConnectionId connectionId = server.connectionIdForPlayer(playerId);\n        if (!connectionId.valid()) return false;\n        motdDelivered.remove(connectionId);\n        admissionRecorded.remove(connectionId);\n        journal.add("DISCONNECT", playerId, "temporary operator disconnect");\n        server.removePeer(connectionId);\n        return true;\n    }\n\n    int resyncServerPlayer(String playerId) {\n''',
    '''    boolean disconnectServerPlayer(String playerId) {\n        if (server == null || playerId == null || playerId.isBlank()) return false;\n        ConnectionId connectionId = server.connectionIdForPlayer(playerId);\n        if (!connectionId.valid()) return false;\n        motdDelivered.remove(connectionId);\n        admissionRecorded.remove(connectionId);\n        journal.add("DISCONNECT", playerId, "temporary operator disconnect");\n        server.removePeer(connectionId);\n        return true;\n    }\n\n    PeerServerAdminBridge.DeleteResult deleteRetainedIdentity(String playerId) {\n        if (server == null || playerId == null || playerId.isBlank()) {\n            return new PeerServerAdminBridge.DeleteResult(false, Set.of(), "Player identity is required.");\n        }\n        PeerServerAdminBridge.DeleteResult result = PeerServerAdminBridge.delete(server, playerId);\n        if (!result.success()) return result;\n        retainedModerationPlayers.remove(playerId);\n        runtimeDevAccess.remove(playerId);\n        runtimeFreeBuild.remove(playerId);\n        deviceByPlayer.remove(playerId);\n        return result;\n    }\n\n    int resyncServerPlayer(String playerId) {\n''',
    'retained identity deletion facade',
)

replace_once(
    'src/main/java/com/tndmadman/rts/PeerNetworkFacade.java',
    '''                    if (server != null) server.connectionClosed(packet);\n                    if (!playerId.isBlank()) journal.add("LEAVE", playerId, "connection closed");\n''',
    '''                    if (server != null) server.connectionClosed(packet);\n                    if (!playerId.isBlank()) {\n                        PersistentPlayerSession session = sessionById(playerId);\n                        if (identityStore != null) identityStore.recordSeen(playerId, session == null ? playerId : session.name());\n                        journal.add("LEAVE", playerId, "connection closed");\n                    }\n''',
    'disconnect lifecycle timestamp',
)

replace_once(
    'src/main/java/com/tndmadman/rts/PeerNetworkFacade.java',
    '''        String playerId = existing == null ? requestedPlayerId : existing.playerId();\n        String playerName = existing == null ? "" : existing.name();\n        ModerationEntry blocked = serverModeration().blocked(playerId, playerName, packet.address(), deviceId, now);\n''',
    '''        String playerId = existing == null ? requestedPlayerId : existing.playerId();\n        String playerName = existing == null ? "" : existing.name();\n        String lifecycleReason = identityStore == null ? "" : identityStore.denialReason(playerId);\n        if (!lifecycleReason.isBlank()) {\n            rejectIdentity(false, connectionId, lifecycleReason);\n            journal.add("ADMISSION_DENIED", playerId.isBlank() ? requestedPlayerId : playerId, "identity-lifecycle");\n            return true;\n        }\n        ModerationEntry blocked = serverModeration().blocked(playerId, playerName, packet.address(), deviceId, now);\n''',
    'resume lifecycle admission',
)

replace_once(
    'src/main/java/com/tndmadman/rts/PeerNetworkFacade.java',
    '''    private String joinAdmissionDenial(ConnectionId connectionId, String playerId, String playerName,\n                                        InetAddress address, boolean newIdentity, long now) {\n        String deviceId = deviceByConnection.getOrDefault(connectionId, "");\n        ModerationEntry blocked = serverModeration().blocked(playerId, playerName, address, deviceId, now);\n''',
    '''    private String joinAdmissionDenial(ConnectionId connectionId, String playerId, String playerName,\n                                        InetAddress address, boolean newIdentity, long now) {\n        String lifecycleReason = identityStore == null ? "" : identityStore.denialReason(playerId);\n        if (!lifecycleReason.isBlank()) {\n            journal.add("ADMISSION_DENIED", playerId.isBlank() ? playerName : playerId, "identity-lifecycle");\n            return lifecycleReason;\n        }\n        String deviceId = deviceByConnection.getOrDefault(connectionId, "");\n        ModerationEntry blocked = serverModeration().blocked(playerId, playerName, address, deviceId, now);\n''',
    'authenticated lifecycle admission',
)

replace_once(
    'src/main/java/com/tndmadman/rts/PeerNetworkFacade.java',
    '''        String device = deviceByConnection.getOrDefault(connectionId, deviceByPlayer.getOrDefault(playerId, ""));\n        observationStore.record(playerId, session == null ? playerId : session.name(), serverPlayerAddress(playerId), device);\n''',
    '''        String device = deviceByConnection.getOrDefault(connectionId, deviceByPlayer.getOrDefault(playerId, ""));\n        String playerName = session == null ? playerId : session.name();\n        observationStore.record(playerId, playerName, serverPlayerAddress(playerId), device);\n        if (identityStore != null) {\n            ServerIdentityStore.MutationResult lifecycle = identityStore.recordSeen(playerId, playerName);\n            if (!lifecycle.success()) journal.add("IDENTITY_STATE_ERROR", playerId, lifecycle.message());\n            server.reserveNextPlayer(identityStore.nextPlayerNumber());\n        }\n''',
    'successful admission lifecycle timestamp',
)

replace_once(
    'src/main/java/com/tndmadman/rts/PeerServerAdminBridge.java',
    '''    static void sendDeleted(PeerServerSide server, Set<String> systems) {\n        if (server == null || systems == null || systems.isEmpty()) return;\n        try { SEND_DELETED.invoke(server, systems); }\n        catch (ReflectiveOperationException ex) { System.err.println("Could not send deleted-system notice: " + ex.getMessage()); }\n    }\n\n    private static Object session(PeerServerSide server, String playerId) {\n''',
    '''    static void sendDeleted(PeerServerSide server, Set<String> systems) {\n        if (server == null || systems == null || systems.isEmpty()) return;\n        try { SEND_DELETED.invoke(server, systems); }\n        catch (ReflectiveOperationException ex) { System.err.println("Could not send deleted-system notice: " + ex.getMessage()); }\n    }\n\n    static DeleteResult delete(PeerServerSide server, String playerId) {\n        if (server == null || playerId == null || playerId.isBlank()) {\n            return new DeleteResult(false, Set.of(), "Player identity is required.");\n        }\n        try {\n            Object session = session(server, playerId);\n            if (session == null) return new DeleteResult(false, Set.of(), "Unknown player identity: " + playerId);\n            if (getBoolean(session, "connected")) {\n                return new DeleteResult(false, Set.of(), playerId + " is connected; disconnect it before deletion.");\n            }\n            Object sessionsValue = SESSIONS.get(server);\n            if (!(sessionsValue instanceof Map<?,?> rawSessions)) {\n                return new DeleteResult(false, Set.of(), "Server session state is unavailable.");\n            }\n            @SuppressWarnings("unchecked") Map<String,Object> sessions = (Map<String,Object>)rawSessions;\n            sessions.remove(playerId);\n            Object requestsValue = DEV_REQUESTS.get(server);\n            if (requestsValue instanceof Set<?> rawRequests) {\n                @SuppressWarnings("unchecked") Set<String> requests = (Set<String>)rawRequests;\n                requests.remove(playerId);\n            }\n            PlayerRegistry.activate(server.world);\n            PlayerRegistry.remove(playerId);\n            server.world.setDevFreeBuild(playerId, false);\n            server.views.remove(playerId);\n            Set<String> deletedSystems = server.world.removePlayerAndPruneEmptySystems(playerId);\n            server.views.removeSystems(deletedSystems);\n            server.broadcastNow();\n            return new DeleteResult(true, Set.copyOf(deletedSystems), "Deleted retained identity " + playerId + ".");\n        } catch (ReflectiveOperationException | RuntimeException ex) {\n            return new DeleteResult(false, Set.of(), "Could not delete retained identity: " + ex.getMessage());\n        }\n    }\n\n    record DeleteResult(boolean success, Set<String> deletedSystems, String message) {\n        DeleteResult {\n            deletedSystems = deletedSystems == null ? Set.of() : Set.copyOf(deletedSystems);\n            message = message == null ? "" : message;\n        }\n    }\n\n    private static Object session(PeerServerSide server, String playerId) {\n''',
    'retained identity bridge deletion',
)

replace_once(
    'src/main/java/com/tndmadman/rts/ServerCommandDispatcher.java',
    '''        register("observations", "observations [player]|delete <player>|prune|clear confirm", "Inspect or delete age-limited IP and client-device moderation signals.", args -> extended("observations", args));\n        register("say", "say <message>", "Broadcast a server notice to connected clients.", this::say);\n''',
    '''        register("observations", "observations [player]|delete <player>|prune|clear confirm", "Inspect or delete age-limited IP and client-device moderation signals.", args -> extended("observations", args));\n        register("identity", "identity <list [active|archived]|dormant <age>|archive <player> confirm|restore <player>|delete <player> confirm>", "Inspect, archive, restore, or permanently delete retained player identities.", args -> extended("identity", args));\n        register("say", "say <message>", "Broadcast a server notice to connected clients.", this::say);\n''',
    'identity console command registration',
)

replace_once(
    'src/main/java/com/tndmadman/rts/ServerCommandExtensions.java',
    '''            case "observations" -> observations(host.network, args);\n            case "tell", "notice", "threads", "memory", "gc-status", "dump" ->\n''',
    '''            case "observations" -> observations(host.network, args);\n            case "identity" -> ServerIdentityAdministration.execute(host, args);\n            case "tell", "notice", "threads", "memory", "gc-status", "dump" ->\n''',
    'identity command dispatch',
)

replace_once(
    'src/main/java/com/tndmadman/rts/DedicatedTcpServerValidator.java',
    '''        PreviousTokenProofRecoveryValidator.validate();\n        try (TcpIntegrationHarness harness = TcpIntegrationHarness.dedicated()) {\n''',
    '''        PreviousTokenProofRecoveryValidator.validate();\n        IdentityLifecycleValidator.validate();\n        try (TcpIntegrationHarness harness = TcpIntegrationHarness.dedicated()) {\n''',
    'identity lifecycle regression validation',
)

replace_once(
    'README.md',
    '''observations clear confirm  Delete retained observation data.\nprune-systems preview      Preview abandoned dynamic systems.\n''',
    '''observations clear confirm  Delete retained observation data.\nidentity list [active|archived]\nidentity dormant <age>     Inspect retained identities by lifecycle and inactivity.\nidentity archive <player> confirm\nidentity restore <player>\nidentity delete <player> confirm\n                           Archive or permanently delete retained identities.\nprune-systems preview      Preview abandoned dynamic systems.\n''',
    'identity command documentation',
)

replace_once(
    'README.md',
    '''Maintenance mode and the slot limit apply only to brand-new player identities. Existing connected players remain online, and retained identities may reconnect or reclaim their session. Lowering the slot limit never disconnects an existing session.\n\nThe message of the day, maintenance state, maintenance reason, and slot limit are stored beside the server save in `<save-name>-admin.json`. Whitelist entries, kicks, and bans are stored in `<save-name>-moderation.json`. The bounded operator journal is retained in `<save-name>-activity.log`, and age-limited last-seen moderation signals are retained in the owner-only `<save-name>-observations.json` companion file. Runtime autosave, simulation pause, and runtime developer mode changes last only until the process exits.\n''',
    '''Maintenance mode and the slot limit apply only to brand-new player identities. Existing connected players remain online, and retained identities may reconnect or reclaim their session. Lowering the slot limit never disconnects an existing session. Fresh dedicated servers default to a finite limit of 128 retained identities; operators may change it with `slots set` or explicitly choose `slots unlimited`.\n\nIdentity creation and last-seen timestamps, archive state, and the monotonic player-ID high-water mark are stored in `<save-name>-identities.json`. Archived identities keep their names and world state but cannot authenticate until restored. Permanent deletion requires a disconnected player, an explicit `confirm`, a fresh verified backup, and a verified post-deletion save. Deletion removes the session, ships, bases, research, home state, and system ownership; the deleted name becomes reusable while player IDs are never recycled. Identity-scoped whitelist, kick, and player-ban entries are removed, while IP and device bans remain as independent security records.\n\nThe message of the day, maintenance state, maintenance reason, and slot limit are stored beside the server save in `<save-name>-admin.json`. Whitelist entries, kicks, and bans are stored in `<save-name>-moderation.json`. The bounded operator journal is retained in `<save-name>-activity.log`, and age-limited last-seen moderation signals are retained in the owner-only `<save-name>-observations.json` companion file. Runtime autosave, simulation pause, and runtime developer mode changes last only until the process exits.\n''',
    'identity lifecycle policy documentation',
)
