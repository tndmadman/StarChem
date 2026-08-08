from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one match, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


# Make the issue-specific deterministic validator part of the normal full check.
replace_once(
    "build.gradle",
    "    dependsOn tasks.named('validateDistributedRefitQueues')\n    dependsOn tasks.named('validateIssue289Completion')\n",
    "    dependsOn tasks.named('validateDistributedRefitQueues')\n    dependsOn tasks.named('validateIssue291CommandQueues')\n    dependsOn tasks.named('validateIssue289Completion')\n",
)

# Fill the deterministic acceptance gaps called out by issue #291.
replace_once(
    "src/main/java/com/tndmadman/rts/Issue291CommandQueueValidator.java",
    "        validateHarvestAdvancement();\n        validateWormholeContinuation();\n        validateGalaxySaveRestore();\n        validateWireRoundTripAndOwnerIsolation();\n",
    "        validateHarvestAdvancement();\n        validateAttackMoveChaining();\n        validateDestroyedTargetAdvancement();\n        validateRemovedWormholeHaltsChain();\n        validateReplacementDuringExecution();\n        validateStopAndHoldSemantics();\n        validateMalformedAndUnauthorizedMutations();\n        validateWormholeContinuation();\n        validateGalaxySaveRestore();\n        validateWireRoundTripAndOwnerIsolation();\n",
)

acceptance_methods = r'''
    private static void validateAttackMoveChaining() {
        World world = world("Queue attack move");
        Unit unit = soloUnit(world);
        double attackX = clampX(world, unit.x + 150);
        double attackY = clampY(world, unit.y + 70);
        double finalX = clampX(world, attackX + 120);
        double finalY = clampY(world, attackY - 95);

        QueuedUnitCommand attackMove = QueuedUnitCommand.tactical(world.activeSystemId(),
                UnitOrderType.ATTACK_MOVE, unit.x, unit.y, attackX, attackY,
                UnitOrderSystem.defaultRadius(UnitOrderType.ATTACK_MOVE), "");
        require(apply(world, unit, UnitQueueOperation.REPLACE, attackMove) == UnitQueueApplyResult.APPLIED,
                "attack-move should become the active queue step");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), finalX, finalY)) == UnitQueueApplyResult.APPLIED,
                "move should append after attack-move");

        runUntil(world, () -> UnitCommandQueueSystem.commands(world, unit.key()).isEmpty(), 1400,
                "attack-move chain did not complete");
        require(Calc.distance(unit.x, unit.y, finalX, finalY) < 16,
                "move after attack-move did not execute");
    }

    private static void validateDestroyedTargetAdvancement() {
        World world = world("Queue destroyed target");
        Unit attacker = armedSoloUnit(world);
        Unit target = new Unit(Config.CORSAIRS_ID, 9901, "destroyer",
                clampX(world, attacker.x + 70), clampY(world, attacker.y + 15));
        target.loadoutId = "destroyer";
        world.units.put(target.key(), target);
        world.saveActiveSystem();

        double finalX = clampX(world, attacker.x + 180);
        double finalY = clampY(world, attacker.y - 90);
        String targetKey = CombatTarget.unit(target);
        require(apply(world, attacker, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.attack(world.activeSystemId(), targetKey)) == UnitQueueApplyResult.APPLIED,
                "attack target should become the active queue step");
        require(apply(world, attacker, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), finalX, finalY)) == UnitQueueApplyResult.APPLIED,
                "move should append after attack");

        target.hp = 0;
        world.update(0.05);
        List<QueuedUnitCommand> remaining = UnitCommandQueueSystem.commands(world, attacker.key());
        require(!remaining.isEmpty() && remaining.get(0).kind() == QueuedCommandKind.MOVE,
                "destroyed attack target did not advance to the next queued step");
        runUntil(world, () -> UnitCommandQueueSystem.commands(world, attacker.key()).isEmpty(), 1000,
                "post-destruction queued move did not complete");
        require(Calc.distance(attacker.x, attacker.y, finalX, finalY) < 16,
                "destroyed target progression did not reach the next waypoint");
    }

    private static void validateRemovedWormholeHaltsChain() {
        World world = world("Queue removed wormhole");
        Unit unit = soloUnit(world);
        require(!world.wormholes.isEmpty(), "removed-wormhole fixture requires a gate");
        WormholeGate gate = world.wormholes.get(0);
        String source = world.activeSystemId();
        String destination = gate.toSystemId;
        String key = unit.key();
        double stagingX = clampX(world, unit.x + 110);
        double stagingY = clampY(world, unit.y + 65);

        String previous = world.activeSystemId();
        world.activateSystem(destination);
        double destinationX = clampX(world, gate.exitX + 120);
        double destinationY = clampY(world, gate.exitY + 70);
        world.activateSystem(previous);

        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(source, stagingX, stagingY)) == UnitQueueApplyResult.APPLIED,
                "staging move should become active before removed-gate test");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.wormhole(source, gate.id, destination)) == UnitQueueApplyResult.APPLIED,
                "wormhole should append before it is removed");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(destination, destinationX, destinationY)) == UnitQueueApplyResult.APPLIED,
                "destination move should append behind the wormhole");

        world.wormholes.removeIf(candidate -> gate.id.equals(candidate.id));
        world.saveActiveSystem();
        runUntil(world, () -> UnitCommandQueueSystem.commands(world, key).isEmpty(), 1200,
                "removed wormhole did not halt the queued cross-system chain");
        require(source.equals(world.ownerUnitLocations(unit.playerId).get(key)),
                "removed wormhole unexpectedly transferred the unit");
        require(unit.task == UnitTask.IDLE && unit.orderType == UnitOrderType.NONE,
                "removed wormhole left stale runtime intent after halting the chain");
    }

    private static void validateReplacementDuringExecution() {
        World world = world("Queue mid-execution replacement");
        Unit unit = soloUnit(world);
        double startX = unit.x;
        double startY = unit.y;
        double firstX = unit.x < world.width * 0.5 ? world.width - 140 : 140;
        double firstY = clampY(world, unit.y + 160);
        double staleTailX = unit.x < world.width * 0.5 ? 140 : world.width - 140;
        double staleTailY = clampY(world, unit.y - 180);

        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(world.activeSystemId(), firstX, firstY)) == UnitQueueApplyResult.APPLIED,
                "long-running move should apply");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), staleTailX, staleTailY)) == UnitQueueApplyResult.APPLIED,
                "old tail should append before replacement");

        for (int i = 0; i < 300 && Calc.distance(startX, startY, unit.x, unit.y) < 24; i++) world.update(0.05);
        require(Calc.distance(startX, startY, unit.x, unit.y) >= 24,
                "replacement fixture did not begin executing its first move");
        require(UnitCommandQueueSystem.commands(world, unit.key()).size() == 2,
                "old chain advanced before mid-execution replacement was issued");

        double replacementX = clampX(world, unit.x + (unit.x < world.width * 0.5 ? 150 : -150));
        double replacementY = clampY(world, unit.y + 120);
        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(world.activeSystemId(), replacementX, replacementY)) == UnitQueueApplyResult.APPLIED,
                "mid-execution replacement should apply");
        List<QueuedUnitCommand> replacement = UnitCommandQueueSystem.commands(world, unit.key());
        require(replacement.size() == 1 && Math.abs(replacement.get(0).x1() - replacementX) < 0.001
                        && Math.abs(replacement.get(0).y1() - replacementY) < 0.001,
                "mid-execution replacement retained stale queued work");

        runUntil(world, () -> UnitCommandQueueSystem.commands(world, unit.key()).isEmpty(), 1000,
                "replacement issued during movement did not complete");
        require(Calc.distance(unit.x, unit.y, replacementX, replacementY) < 16,
                "unit did not finish the replacement issued during execution");
        require(Calc.distance(unit.x, unit.y, staleTailX, staleTailY) > 40,
                "stale tail resumed after mid-execution replacement");
    }

    private static void validateStopAndHoldSemantics() {
        World world = world("Queue stop and hold");
        Unit unit = soloUnit(world);
        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(world.activeSystemId(), clampX(world, unit.x + 150), unit.y))
                == UnitQueueApplyResult.APPLIED, "stop fixture first move should apply");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), clampX(world, unit.x + 260), clampY(world, unit.y + 90)))
                == UnitQueueApplyResult.APPLIED, "stop fixture tail should append");

        UnitQueueMutation clear = new UnitQueueMutation(unit.playerId, unit.unitId, UnitQueueOperation.CLEAR,
                UnitCommandQueueSystem.revision(world, unit.key()), null);
        require(UnitCommandQueueSystem.applyGlobal(world, clear) == UnitQueueApplyResult.APPLIED,
                "stop/clear mutation should apply");
        require(UnitCommandQueueSystem.commands(world, unit.key()).isEmpty()
                        && unit.task == UnitTask.IDLE && unit.orderType == UnitOrderType.NONE,
                "stop/clear did not remove active and queued work");

        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(world.activeSystemId(), clampX(world, unit.x + 130), unit.y))
                == UnitQueueApplyResult.APPLIED, "hold fixture move should apply");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), clampX(world, unit.x + 230), clampY(world, unit.y - 90)))
                == UnitQueueApplyResult.APPLIED, "hold fixture tail should append");
        QueuedUnitCommand hold = QueuedUnitCommand.tactical(world.activeSystemId(), UnitOrderType.HOLD,
                0, 0, 0, 0, UnitOrderSystem.defaultRadius(UnitOrderType.HOLD), "");
        require(apply(world, unit, UnitQueueOperation.REPLACE, hold) == UnitQueueApplyResult.APPLIED,
                "hold should replace the existing queue");
        List<QueuedUnitCommand> held = UnitCommandQueueSystem.commands(world, unit.key());
        require(held.size() == 1 && held.get(0).tacticalType() == UnitOrderType.HOLD,
                "hold did not clear the old queued tail");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), clampX(world, unit.x + 80), unit.y))
                == UnitQueueApplyResult.REJECTED,
                "terminal hold incorrectly allowed a later queued step");
    }

    private static void validateMalformedAndUnauthorizedMutations() {
        World world = world("Queue mutation security");
        Unit unit = soloUnit(world);
        String systemId = world.activeSystemId();
        UnitQueueMutation wrongOwner = new UnitQueueMutation("OTHER", unit.unitId, UnitQueueOperation.REPLACE, 0,
                QueuedUnitCommand.move(systemId, unit.x, unit.y));
        require(UnitCommandQueueSystem.applyGlobal(world, wrongOwner) == UnitQueueApplyResult.REJECTED,
                "queue mutation accepted an unauthorized player/unit pair");
        UnitQueueMutation missingUnit = new UnitQueueMutation(unit.playerId, Integer.MAX_VALUE, UnitQueueOperation.REPLACE, 0,
                QueuedUnitCommand.move(systemId, unit.x, unit.y));
        require(UnitCommandQueueSystem.applyGlobal(world, missingUnit) == UnitQueueApplyResult.REJECTED,
                "queue mutation accepted an unauthorized unit ID");
        UnitQueueMutation malformedCoordinate = new UnitQueueMutation(unit.playerId, unit.unitId, UnitQueueOperation.REPLACE,
                UnitCommandQueueSystem.revision(world, unit.key()), QueuedUnitCommand.move(systemId, Double.NaN, unit.y));
        require(UnitCommandQueueSystem.applyGlobal(world, malformedCoordinate) == UnitQueueApplyResult.REJECTED,
                "queue mutation accepted a malformed coordinate");

        boolean oversizedRejected = false;
        try {
            UnitQueueWire.mutationPacket(new UnitQueueMutation(unit.playerId, unit.unitId, UnitQueueOperation.REPLACE,
                    UnitCommandQueueSystem.revision(world, unit.key()),
                    QueuedUnitCommand.attack(systemId, "X".repeat(5000))));
        } catch (SnapshotDecodeException expected) {
            oversizedRejected = true;
        }
        require(oversizedRejected, "oversized queue command packet was not rejected");
    }

'''
replace_once(
    "src/main/java/com/tndmadman/rts/Issue291CommandQueueValidator.java",
    "    private static void validateGalaxySaveRestore() {\n",
    acceptance_methods + "    private static void validateGalaxySaveRestore() {\n",
)

replace_once(
    "src/main/java/com/tndmadman/rts/Issue291CommandQueueValidator.java",
    "    private static Unit soloUnit(World world) {\n        for (Unit unit : world.units.values()) if (\"SOLO\".equals(unit.playerId)) return unit;\n        throw new IllegalStateException(\"Solo unit missing.\");\n    }\n",
    "    private static Unit soloUnit(World world) {\n        for (Unit unit : world.units.values()) if (\"SOLO\".equals(unit.playerId)) return unit;\n        throw new IllegalStateException(\"Solo unit missing.\");\n    }\n\n    private static Unit armedSoloUnit(World world) {\n        Unit unit = new Unit(\"SOLO\", 9001, \"destroyer\", world.width * 0.45, world.height * 0.48);\n        unit.loadoutId = \"destroyer_rail_escort\";\n        world.units.put(unit.key(), unit);\n        world.saveActiveSystem();\n        return unit;\n    }\n",
)

# Exercise real TCP queue mutation, owner state replication, ordered authoritative execution, and client convergence.
replace_once(
    "src/main/java/com/tndmadman/rts/TcpMultiplayerValidator.java",
    "            require(serverUnit != null && clientUnit != null, \"joined player unit was missing\");\n\n            double targetX = serverUnit.x + 30;\n",
    "            require(serverUnit != null && clientUnit != null, \"joined player unit was missing\");\n\n            validateQueuedRoute(server, client, serverWorld, clientWorld, playerId, serverUnit.unitId);\n            serverUnit = unit(serverWorld, playerId, serverUnit.unitId);\n            clientUnit = unit(clientWorld, playerId, serverUnit.unitId);\n            require(serverUnit != null && clientUnit != null, \"queued route validation lost the joined player unit\");\n\n            double targetX = serverUnit.x + 30;\n",
)

tcp_method = r'''
    private static void validateQueuedRoute(PeerNetwork server, PeerNetwork client, World serverWorld,
                                            World clientWorld, String playerId, int unitId) throws Exception {
        Unit authoritative = unit(serverWorld, playerId, unitId);
        require(authoritative != null, "queued TCP route had no authoritative unit");
        String key = authoritative.key();
        String systemId = serverWorld.ownerUnitLocations(playerId).get(key);
        require(systemId != null && !systemId.isBlank(), "queued TCP route could not locate its authoritative unit system");

        String previous = serverWorld.activeSystemId();
        serverWorld.activateSystem(systemId);
        double x1 = Calc.clamp(authoritative.x + 70, 30, serverWorld.width - 30);
        double y1 = Calc.clamp(authoritative.y + 35, 30, serverWorld.height - 30);
        double x2 = Calc.clamp(authoritative.x + 145, 30, serverWorld.width - 30);
        double y2 = Calc.clamp(authoritative.y - 55, 30, serverWorld.height - 30);
        double x3 = Calc.clamp(authoritative.x + 215, 30, serverWorld.width - 30);
        double y3 = Calc.clamp(authoritative.y + 90, 30, serverWorld.height - 30);
        serverWorld.activateSystem(previous);

        sendQueueAndAwait(server, client, serverWorld, clientWorld, playerId, unitId,
                UnitQueueOperation.REPLACE, QueuedUnitCommand.move(systemId, x1, y1), 1);
        sendQueueAndAwait(server, client, serverWorld, clientWorld, playerId, unitId,
                UnitQueueOperation.APPEND, QueuedUnitCommand.move(systemId, x2, y2), 2);
        sendQueueAndAwait(server, client, serverWorld, clientWorld, playerId, unitId,
                UnitQueueOperation.APPEND, QueuedUnitCommand.move(systemId, x3, y3), 3);

        List<QueuedUnitCommand> serverQueue = UnitCommandQueueSystem.commands(serverWorld, key);
        List<QueuedUnitCommand> clientQueue = UnitCommandQueueSystem.commands(clientWorld, key);
        require(serverQueue.size() == 3 && clientQueue.size() == 3,
                "real TCP queue did not converge to three authoritative steps");
        require(closePoint(serverQueue.get(0), x1, y1) && closePoint(serverQueue.get(1), x2, y2)
                        && closePoint(serverQueue.get(2), x3, y3),
                "authoritative TCP queue changed waypoint order");
        require(closePoint(clientQueue.get(0), x1, y1) && closePoint(clientQueue.get(1), x2, y2)
                        && closePoint(clientQueue.get(2), x3, y3),
                "owner queue replication changed waypoint order");

        long deadline = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < deadline) {
            simulationNetworkTick(server, client, clientWorld);
            Unit current = unit(serverWorld, playerId, unitId);
            if (current != null && UnitCommandQueueSystem.commands(serverWorld, key).isEmpty()
                    && Calc.distance(current.x, current.y, x3, y3) < 16) break;
        }
        authoritative = unit(serverWorld, playerId, unitId);
        require(authoritative != null && UnitCommandQueueSystem.commands(serverWorld, key).isEmpty()
                        && Calc.distance(authoritative.x, authoritative.y, x3, y3) < 16,
                "authoritative server did not execute the TCP queue in order");

        long stateDeadline = System.currentTimeMillis() + 4_000;
        while (System.currentTimeMillis() < stateDeadline
                && !UnitCommandQueueSystem.commands(clientWorld, key).isEmpty()) {
            networkTick(server, client);
        }
        require(UnitCommandQueueSystem.commands(clientWorld, key).isEmpty(),
                "owner client did not receive authoritative queue completion state");
        settleUntilConverged(server, client, serverWorld, clientWorld, playerId, unitId,
                5_000, "owner client did not converge after authoritative queued execution");
    }

    private static void sendQueueAndAwait(PeerNetwork server, PeerNetwork client, World serverWorld,
                                          World clientWorld, String playerId, int unitId,
                                          UnitQueueOperation operation, QueuedUnitCommand command,
                                          int expectedSize) throws Exception {
        String key = Unit.key(playerId, unitId);
        long revision = UnitCommandQueueSystem.revision(clientWorld, key);
        client.queue(new UnitQueueMutation(playerId, unitId, operation, revision, command));
        long deadline = System.currentTimeMillis() + 4_000;
        while (System.currentTimeMillis() < deadline) {
            networkTick(server, client);
            List<QueuedUnitCommand> authoritative = UnitCommandQueueSystem.commands(serverWorld, key);
            List<QueuedUnitCommand> replicated = UnitCommandQueueSystem.commands(clientWorld, key);
            if (authoritative.size() == expectedSize && replicated.size() == expectedSize
                    && UnitCommandQueueSystem.revision(serverWorld, key)
                    == UnitCommandQueueSystem.revision(clientWorld, key)) return;
        }
        throw new IllegalStateException("TCP queue mutation did not converge at size " + expectedSize);
    }

    private static void simulationNetworkTick(PeerNetwork server, PeerNetwork client, World clientWorld)
            throws InterruptedException {
        server.updateServerWorlds(0.016);
        server.tick();
        client.tick();
        ClientEnvironmentSync.advance(clientWorld, 0.016);
        ClientPrediction.update(clientWorld, 0.016);
        Thread.sleep(8);
    }

    private static boolean closePoint(QueuedUnitCommand command, double x, double y) {
        return command != null && command.kind() == QueuedCommandKind.MOVE
                && Math.abs(command.x1() - x) < 0.001 && Math.abs(command.y1() - y) < 0.001;
    }

'''
replace_once(
    "src/main/java/com/tndmadman/rts/TcpMultiplayerValidator.java",
    "    private static void runUntil(PeerNetwork server, PeerNetwork client, World clientWorld, Check condition,\n",
    tcp_method + "    private static void runUntil(PeerNetwork server, PeerNetwork client, World clientWorld, Check condition,\n",
)

# Prove reconnect initial sync restores authoritative queue state rather than relying on stale client cache.
replace_once(
    "src/main/java/com/tndmadman/rts/TcpReconnectIntegrationValidator.java",
    "            Unit unit = harness.firstUnit(harness.serverWorld, playerId);\n            TcpIntegrationHarness.require(unit != null, \"reconnecting player had no authoritative unit\");\n            String tokenBefore = SessionTokenStore.load(reconnecting.config()).token();\n",
    "            Unit unit = harness.firstUnit(harness.serverWorld, playerId);\n            TcpIntegrationHarness.require(unit != null, \"reconnecting player had no authoritative unit\");\n            String unitKey = unit.key();\n            String unitSystem = harness.serverWorld.ownerUnitLocations(playerId).get(unitKey);\n            TcpIntegrationHarness.require(unitSystem != null && !unitSystem.isBlank(),\n                    \"reconnecting queue fixture could not locate its authoritative unit\");\n            QueuedUnitCommand patrol = QueuedUnitCommand.tactical(unitSystem, UnitOrderType.PATROL,\n                    Calc.clamp(unit.x + 45, 20, harness.serverWorld.width - 20),\n                    Calc.clamp(unit.y + 25, 20, harness.serverWorld.height - 20),\n                    Calc.clamp(unit.x + 115, 20, harness.serverWorld.width - 20),\n                    Calc.clamp(unit.y + 95, 20, harness.serverWorld.height - 20),\n                    UnitOrderSystem.defaultRadius(UnitOrderType.PATROL), \"\");\n            reconnecting.network().queue(new UnitQueueMutation(playerId, unit.unitId, UnitQueueOperation.REPLACE,\n                    UnitCommandQueueSystem.revision(reconnecting.world(), unitKey), patrol));\n            harness.await(() -> {\n                java.util.List<QueuedUnitCommand> authoritative = UnitCommandQueueSystem.commands(harness.serverWorld, unitKey);\n                java.util.List<QueuedUnitCommand> replicated = UnitCommandQueueSystem.commands(reconnecting.world(), unitKey);\n                return authoritative.size() == 1 && replicated.size() == 1\n                        && authoritative.get(0).tacticalType() == UnitOrderType.PATROL\n                        && replicated.get(0).tacticalType() == UnitOrderType.PATROL\n                        && UnitCommandQueueSystem.revision(harness.serverWorld, unitKey)\n                        == UnitCommandQueueSystem.revision(reconnecting.world(), unitKey);\n            }, 5_000, \"queued patrol did not synchronize before reconnect validation\");\n            long queueRevisionBefore = UnitCommandQueueSystem.revision(harness.serverWorld, unitKey);\n            String tokenBefore = SessionTokenStore.load(reconnecting.config()).token();\n",
)

replace_once(
    "src/main/java/com/tndmadman/rts/TcpReconnectIntegrationValidator.java",
    "            TcpIntegrationHarness.require(harness.serverWorld.hasLiveAssets(playerId),\n                    \"server removed player state instead of retaining the resumable session\");\n\n            harness.await(() -> reconnecting.network().clientConnected()\n                            && playerId.equals(reconnecting.playerId())\n                            && harness.serverNetwork.serverSessionConnected(playerId),\n                    15_000, \"automatic full-path TCP RESUME did not complete\");\n",
    "            TcpIntegrationHarness.require(harness.serverWorld.hasLiveAssets(playerId),\n                    \"server removed player state instead of retaining the resumable session\");\n            TcpIntegrationHarness.require(UnitCommandQueueSystem.commands(harness.serverWorld, unitKey).size() == 1,\n                    \"server lost queued work while the owner was disconnected\");\n            UnitCommandQueueSystem.clearWorld(reconnecting.world());\n            TcpIntegrationHarness.require(UnitCommandQueueSystem.commands(reconnecting.world(), unitKey).isEmpty(),\n                    \"queue reconnect fixture did not clear its client cache\");\n\n            harness.await(() -> reconnecting.network().clientConnected()\n                            && playerId.equals(reconnecting.playerId())\n                            && harness.serverNetwork.serverSessionConnected(playerId)\n                            && UnitCommandQueueSystem.commands(reconnecting.world(), unitKey).size() == 1\n                            && UnitCommandQueueSystem.commands(reconnecting.world(), unitKey).get(0).tacticalType() == UnitOrderType.PATROL\n                            && UnitCommandQueueSystem.revision(reconnecting.world(), unitKey) == queueRevisionBefore,\n                    15_000, \"automatic full-path TCP RESUME did not restore authoritative queue state\");\n",
)

# Keep documentation aligned with the completed acceptance matrix.
replace_once(
    "docs/issue-291-command-queues.md",
    "`validateIssue291CommandQueues` covers at least eight queued movement waypoints, replacement, stale revisions, the 16-step bound, harvest advancement, wormhole continuation, galaxy save/restore, wire round-tripping, owner-only synchronization, and oversized state rejection. The feature is additionally exercised with galaxy connectivity, save-store, network-security, and the repository-wide `check` validation.\n",
    "`validateIssue291CommandQueues` covers at least eight queued movement waypoints, attack-move chaining, destroyed-target advancement, removed-wormhole handling, replacement during active execution, stop/hold semantics, stale revisions, unauthorized and malformed mutations, the 16-step bound, harvest advancement, wormhole continuation, galaxy save/restore, wire round-tripping, owner-only synchronization, and oversized packet/state rejection. Real TCP validation additionally proves ordered authoritative queue execution and owner convergence, while reconnect validation clears the client cache before resume and verifies the authoritative queue is restored by initial resynchronization. The issue-specific validator is a dependency of the repository-wide `check` task.\n",
)

print("Issue 291 acceptance completion patch applied.")
