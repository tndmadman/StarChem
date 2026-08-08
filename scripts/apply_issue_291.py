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


def replace_all(path, old, new, expected):
    text = read(path)
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{path}: expected {expected} matches, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new))


def replace_between(path, start, end, replacement):
    text = read(path)
    i = text.find(start)
    if i < 0:
        raise RuntimeError(f"{path}: start marker not found: {start}")
    j = text.find(end, i + len(start))
    if j < 0:
        raise RuntimeError(f"{path}: end marker not found: {end}")
    write(path, text[:i] + replacement + text[j:])


# Unit order execution and selected-unit route rendering.
replace_once(
    "src/main/java/com/tndmadman/rts/UnitOrders.java",
    "    static void update(World world, Unit unit, double dt) {\n        if (world == null || unit == null || unit.orderType == UnitOrderType.NONE) return;\n",
    "    static void update(World world, Unit unit, double dt) {\n        if (world == null || unit == null) return;\n        UnitCommandQueueSystem.update(world, unit, dt);\n        if (unit.orderType == UnitOrderType.NONE) return;\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/UnitOrders.java",
    "    static void draw(Graphics2D g2, World world, Unit unit) {\n        if (g2 == null || world == null || unit == null || !unit.selected || !PlayerRegistry.isLocal(unit.playerId) || unit.orderType == UnitOrderType.NONE) return;\n        Color color = PlayerRegistry.color(unit.playerId);\n",
    "    static void draw(Graphics2D g2, World world, Unit unit) {\n        if (g2 == null || world == null || unit == null || !unit.selected || !PlayerRegistry.isLocal(unit.playerId)) return;\n        UnitCommandQueueRenderer.draw(g2, world, unit);\n        if (UnitCommandQueueSystem.hasPlayerIntent(world, unit) || unit.orderType == UnitOrderType.NONE) return;\n        Color color = PlayerRegistry.color(unit.playerId);\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/UnitOrders.java",
    "        unit.setOrder(safe);\n        UnitOrderSystem.update(world, unit, 0);\n",
    "        UnitCommandQueueSystem.legacyReplace(world, unit);\n        unit.setOrder(safe);\n        UnitOrderSystem.update(world, unit, 0);\n",
)

# Legacy movement packets remain supported but invalidate a queued player chain.
replace_once(
    "src/main/java/com/tndmadman/rts/AUnitMove.java",
    "        u.issueMove(c.x(), c.y());\n        return true;\n",
    "        UnitCommandQueueSystem.legacyReplace(world, u);\n        u.issueMove(c.x(), c.y());\n        return true;\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/AUnitMove.java",
    "            case \"ORDER\" -> applyOrder(s, p, connectionId, id);\n",
    "            case \"ORDER\" -> applyOrder(s, p, connectionId, id);\n            case \"QUEUE\" -> applyQueue(s, p, connectionId, id);\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/AUnitMove.java",
    "    private static void reject(PeerServerSide s) {\n",
    "    private static void applyQueue(PeerServerSide s, String[] p, ConnectionId connectionId, String playerId) {\n        s.touch(connectionId);\n        if (!s.owns(connectionId, playerId)) return;\n        try {\n            UnitQueueMutation mutation = UnitQueueWire.parseMutation(p, playerId);\n            UnitQueueApplyResult result = UnitCommandQueueSystem.applyGlobal(s.world, mutation);\n            if (result == UnitQueueApplyResult.REJECTED) {\n                reject(s);\n                return;\n            }\n            if (result == UnitQueueApplyResult.STALE) {\n                UnitCommandQueueSystem.forceDirty(s.world, mutation.unitKey());\n            }\n            s.broadcastNow();\n        } catch (RuntimeException ignored) {\n            reject(s);\n        }\n    }\n\n    private static void reject(PeerServerSide s) {\n",
)

# Legacy attack/work paths only clear queues when the command is actually accepted.
write(
    "src/main/java/com/tndmadman/rts/AUnitAttack.java",
    """package com.tndmadman.rts;\n\nfinal class AUnitAttack {\n    private AUnitAttack() { }\n\n    static boolean apply(World world, AttackCommand c) {\n        if (world == null || c == null) return false;\n        Unit u = world.units.get(Unit.key(c.playerId(), c.unitId()));\n        if (u == null || ProductionSystem.refitReserved(world, u.key())\n                || !VisibilityRules.targetVisible(world, c.playerId(), c.targetKey())\n                || !CombatTarget.enemy(world, u, c.targetKey()) || !WeaponRules.armed(u)) return false;\n        UnitCommandQueueSystem.legacyReplace(world, u);\n        u.issueAttack(c.targetKey());\n        return true;\n    }\n}\n""",
)
write(
    "src/main/java/com/tndmadman/rts/AUnitWork.java",
    """package com.tndmadman.rts;\n\nfinal class AUnitWork {\n    private AUnitWork() { }\n\n    static boolean apply(World world, HarvestCommand command) {\n        if (world == null || command == null) return false;\n        Unit unit = world.units.get(Unit.key(command.playerId(), command.unitId()));\n        ResourceNode node = world.findResource(command.resourceId());\n        ResourceNetDebug.hostWorkOrder(world, command, unit, node);\n        if (!valid(world, unit, node, command)) return false;\n        UnitCommandQueueSystem.legacyReplace(world, unit);\n        unit.setMiningAnchor(node.x, node.y);\n        unit.startAutoHarvest(node.id);\n        return true;\n    }\n\n    private static boolean valid(World world, Unit unit, ResourceNode node, HarvestCommand command) {\n        return unit != null\n                && !ProductionSystem.refitReserved(world, unit.key())\n                && unit.playerId.equals(command.playerId())\n                && node != null\n                && node.active\n                && node.amount > 0.05\n                && unit.type().harvestKinds.contains(node.kind)\n                && VisibilityRules.resourceStage(world, command.playerId(), node)\n                .atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED);\n    }\n}\n""",
)

# A queued explicit harvest target must complete instead of silently auto-retargeting.
replace_all(
    "src/main/java/com/tndmadman/rts/WorkSystem.java",
    "            if (world.scoutRetarget(unit, node)) return;\n",
    "            if (!UnitCommandQueueSystem.ownsHarvest(world, unit) && world.scoutRetarget(unit, node)) return;\n",
    2,
)

# Player queue ownership takes precedence over autonomous hauler routing.
replace_once(
    "src/main/java/com/tndmadman/rts/HaulerSystem.java",
    "        if (!NpcRules.isNpcFaction(hauler.playerId)\n                && hauler.orderType != UnitOrderType.NONE) return;\n",
    "        if (!NpcRules.isNpcFaction(hauler.playerId)\n                && (hauler.orderType != UnitOrderType.NONE\n                || UnitCommandQueueSystem.hasPlayerIntent(world, hauler))) return;\n",
)

# Refit/destruction lifecycle must not leave orphaned queue state.
replace_once(
    "src/main/java/com/tndmadman/rts/World.java",
    "        if (ProductionSystem.refitLocked(this, unit.key())) {\n            unit.task = UnitTask.IDLE;\n",
    "        if (ProductionSystem.refitLocked(this, unit.key())) {\n            UnitCommandQueueSystem.cancelForSystem(this, unit);\n            unit.task = UnitTask.IDLE;\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/World.java",
    "if (unit.hp <= 0) { dropLoot(unit); explodeUnit(unit); unitIt.remove(); }",
    "if (unit.hp <= 0) { UnitCommandQueueSystem.remove(this, unit.key()); dropLoot(unit); explodeUnit(unit); unitIt.remove(); }",
)
replace_once(
    "src/main/java/com/tndmadman/rts/WorldNetAccess.java",
    "    static void respawnPlayer(World world, String playerId) {\n        if (!realPlayerId(playerId) && !\"SOLO\".equals(playerId)) return;\n",
    "    static void respawnPlayer(World world, String playerId) {\n        if (!realPlayerId(playerId) && !\"SOLO\".equals(playerId)) return;\n        UnitCommandQueueSystem.removePlayer(world, playerId);\n",
)

# Persist queue state beside the unit that owns it, across every galaxy system.
replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    "        clearWorld(world);\n        nextResourceId = 1;\n",
    "        clearWorld(world);\n        nextResourceId = 1;\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    "    private void clearWorld(World world) {\n        world.resources.clear();\n",
    "    private void clearWorld(World world) {\n        UnitCommandQueueSystem.clearWorld(world);\n        world.resources.clear();\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    "        for (WorldSystemState state : systems.values()) savedSystems.add(captureSystem(state));\n",
    "        for (WorldSystemState state : systems.values()) savedSystems.add(captureSystem(world, state));\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    "    private Map<String,Object> captureSystem(WorldSystemState state) {\n",
    "    private Map<String,Object> captureSystem(World world, WorldSystemState state) {\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    "        out.put(\"units\", captureUnits(state.units.values()));\n",
    "        out.put(\"units\", captureUnits(world, state.units.values()));\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    "    private List<Object> captureUnits(Collection<Unit> units) {\n",
    "    private List<Object> captureUnits(World world, Collection<Unit> units) {\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    "            row.put(\"orderRadius\", unit.orderRadius); row.put(\"orderPhase\", unit.orderPhase); row.put(\"miningAnchorSet\", unit.miningAnchorSet);\n            row.put(\"inventory\", ServerSaveStore.materialMap(unit.inventory));\n",
    "            row.put(\"orderRadius\", unit.orderRadius); row.put(\"orderPhase\", unit.orderPhase); row.put(\"miningAnchorSet\", unit.miningAnchorSet);\n            Map<String,Object> commandQueue = UnitCommandQueueSystem.capture(world, unit);\n            if (!commandQueue.isEmpty()) row.put(\"commandQueue\", commandQueue);\n            row.put(\"inventory\", ServerSaveStore.materialMap(unit.inventory));\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    "            unit.inventory.putAll(ServerSaveStore.restoreMaterialMap(row.get(\"inventory\")));\n            out.put(unit.key(), unit);\n",
    "            unit.inventory.putAll(ServerSaveStore.restoreMaterialMap(row.get(\"inventory\")));\n            UnitCommandQueueSystem.restore(world, unit, row.get(\"commandQueue\"));\n            out.put(unit.key(), unit);\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    "    Set<String> removePlayerAndPruneEmptySystems(World world, String playerId) {\n        if (playerId == null || playerId.isBlank() || \"WAIT\".equals(playerId)) return Set.of();\n        saveActive(world);\n",
    "    Set<String> removePlayerAndPruneEmptySystems(World world, String playerId) {\n        if (playerId == null || playerId.isBlank() || \"WAIT\".equals(playerId)) return Set.of();\n        UnitCommandQueueSystem.removePlayer(world, playerId);\n        saveActive(world);\n",
)

# Queue mutations and owner-only queue state synchronization.
replace_once(
    "src/main/java/com/tndmadman/rts/PeerNetworkFacade.java",
    "    @Override public void move(MoveCommand c) { if (server != null) serverCommand(() -> AUnitMove.apply(server.world, c), c.playerId()); else client.move(c); }\n",
    "    UnitQueueApplyResult queue(UnitQueueMutation mutation) {\n        if (mutation == null) return UnitQueueApplyResult.REJECTED;\n        if (server != null) {\n            UnitQueueApplyResult result = UnitCommandQueueSystem.applyGlobal(server.world, mutation);\n            if (result != UnitQueueApplyResult.REJECTED) server.broadcastNow();\n            return result;\n        }\n        client.queue(mutation);\n        return UnitQueueApplyResult.APPLIED;\n    }\n\n    @Override public void move(MoveCommand c) { if (server != null) serverCommand(() -> AUnitMove.apply(server.world, c), c.playerId()); else client.move(c); }\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/PeerClientSide.java",
    "        lastServerPacket = System.currentTimeMillis();\n        if (readWorldInfo(message) || readGalaxy(message) || readLeaderboard(message)\n",
    "        lastServerPacket = System.currentTimeMillis();\n        if (UnitQueueWire.readState(world, message, localPlayerId)) return;\n        if (readWorldInfo(message) || readGalaxy(message) || readLeaderboard(message)\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/PeerClientSide.java",
    "    void move(MoveCommand command) { sendCommandToServer(\"MOVE|\" + command.playerId() + \"|\" + command.unitId() + \"|\" + Calc.round(command.x()) + \"|\" + Calc.round(command.y())); }\n",
    "    void queue(UnitQueueMutation mutation) { sendCommandToServer(UnitQueueWire.mutationPacket(mutation)); }\n    void move(MoveCommand command) { sendCommandToServer(\"MOVE|\" + command.playerId() + \"|\" + command.unitId() + \"|\" + Calc.round(command.x()) + \"|\" + Calc.round(command.y())); }\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/PeerSyncBatch.java",
    "            next = PeerSyncSender.sendOne(world, views, peer, next, SyncKind.REGULAR, fullResources, out);\n            sendNotices(world, peer, out);\n",
    "            next = PeerSyncSender.sendOne(world, views, peer, next, SyncKind.REGULAR, fullResources, out);\n            sendQueueState(world, peer, false, out);\n            sendNotices(world, peer, out);\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/PeerSyncBatch.java",
    "        sendFogState(world, views, peer, out);\n        sendNotices(world, peer, out);\n",
    "        sendFogState(world, views, peer, out);\n        sendQueueState(world, peer, true, out);\n        sendNotices(world, peer, out);\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/PeerSyncBatch.java",
    "    private static void sendFitCatalog(World world, ServerPeer peer, NetOutbound out) {\n",
    "    private static void sendQueueState(World world, ServerPeer peer, boolean initial, NetOutbound out) {\n        if (world == null || peer == null || out == null) return;\n        for (String packet : UnitCommandQueueSystem.statePackets(world, peer.playerId(), initial)) {\n            out.send(packet, peer.connectionId(), DeliveryClass.ORDERED);\n        }\n    }\n\n    private static void sendFitCatalog(World world, ServerPeer peer, NetOutbound out) {\n",
)

# Protocol/save schema revisions.
replace_once(
    "src/main/java/com/tndmadman/rts/MultiplayerCompatibility.java",
    "    static final int PROTOCOL_VERSION = 14;\n",
    "    static final int PROTOCOL_VERSION = 15;\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/ServerSaveStore.java",
    "    static final int SAVE_FORMAT_VERSION = 5;\n",
    "    static final int SAVE_FORMAT_VERSION = 6;\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/ServerSaveMigration.java",
    "        if (version == 4) {\n            version = 5;\n            notes.add(\"v4->v5 persists exact source-to-destination refit reservations\");\n        }\n        if (version != ServerSaveStore.SAVE_FORMAT_VERSION) {\n",
    "        if (version == 4) {\n            version = 5;\n            notes.add(\"v4->v5 persists exact source-to-destination refit reservations\");\n        }\n        if (version == 5) {\n            version = 6;\n            notes.add(\"v5->v6 adds bounded persistent per-unit command queues\");\n        }\n        if (version != ServerSaveStore.SAVE_FORMAT_VERSION) {\n",
)

# Player-facing stop binding and HUD help.
replace_once(
    "src/main/java/com/tndmadman/rts/GameSettings.java",
    "                + bindingText(\"escort\") + \" escort | \"\n                + bindingText(\"hold\") + \" hold\";\n",
    "                + bindingText(\"escort\") + \" escort | \"\n                + bindingText(\"hold\") + \" hold | \"\n                + bindingText(\"stop_orders\") + \" stop\";\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GameSettings.java",
    "        add(\"Orders\", \"Hold Position\", \"hold\", KeyEvent.VK_H, false, true);\n",
    "        add(\"Orders\", \"Hold Position\", \"hold\", KeyEvent.VK_H, false, true);\n        add(\"Orders\", \"Stop / Clear Orders\", \"stop_orders\", KeyEvent.VK_C, false, true);\n",
)

# GamePanel now issues all player commands through the same queue mutation path.
replace_once(
    "src/main/java/com/tndmadman/rts/GamePanel.java",
    "import java.util.LinkedHashMap;\n",
    "import java.util.ArrayList;\nimport java.util.LinkedHashMap;\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GamePanel.java",
    "    private final ControlGroupManager controlGroups = new ControlGroupManager();\n",
    "    private final ControlGroupManager controlGroups = new ControlGroupManager();\n    private final LinkedHashSet<String> queuedPlanningUnits = new LinkedHashSet<>();\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GamePanel.java",
    "        if (SwingUtilities.isRightMouseButton(e)) { clickRight(screenToWorld(e.getPoint())); return; }\n",
    "        if (SwingUtilities.isRightMouseButton(e)) { clickRight(screenToWorld(e.getPoint()), e.isShiftDown()); return; }\n",
)

click_right = r'''    private void clickRight(Point2D p, boolean append) {
        if (commandMode != UnitOrderType.NONE) { handleCommandClick(p, append); return; }
        boolean visiblePoint = FogOfWarView.currentlyVisible(world, p.getX(), p.getY());
        boolean exploredPoint = FogOfWarView.explored(world, p.getX(), p.getY());
        Unit enemyUnit = visiblePoint ? world.unitAt(p.getX(), p.getY()) : null;
        if (enemyUnit != null && !PlayerRegistry.isLocal(enemyUnit.playerId)) {
            ProceduralAudio.play(SoundCue.ATTACK_ORDER);
            orderAttack(CombatTarget.unit(enemyUnit), append);
            return;
        }
        Base enemyBase = visiblePoint ? world.baseAt(p.getX(), p.getY()) : null;
        if (enemyBase != null && !PlayerRegistry.isLocal(enemyBase.playerId)) {
            ProceduralAudio.play(SoundCue.ATTACK_ORDER);
            orderAttack(CombatTarget.base(enemyBase), append);
            return;
        }
        WormholeGate gate = exploredPoint ? wormholeAt(p) : null;
        if (gate != null) {
            int applied = queueWormholeSelected(gate, append);
            world.status = applied > 0
                    ? (append ? "Queued" : "Ordered") + " wormhole transit for " + applied + " ship(s) to "
                    + StarSystems.get(gate.toSystemId).name() + "."
                    : "No ship available for that wormhole order.";
            ProceduralAudio.play(applied > 0 ? SoundCue.MOVE_ORDER : SoundCue.ERROR);
            return;
        }
        ResourceNode node = visiblePoint ? world.resourceAt(p.getX(), p.getY()) : null;
        if (node != null) {
            int applied = queueHarvestSelected(node, append);
            world.status = applied > 0
                    ? (append ? "Queued harvest of " : "Auto-harvesting ") + node.name + "."
                    : "Selected ship cannot harvest this node.";
            ProceduralAudio.play(applied > 0 ? SoundCue.HARVEST_ORDER : SoundCue.ERROR);
            return;
        }
        int applied = queueMoveSelected(p, append);
        world.status = applied > 0
                ? (append ? "Queued waypoint for " : "Moving ") + applied + " ship(s) in " + formation.label + " formation."
                : "No ship selected.";
        ProceduralAudio.play(applied > 0 ? SoundCue.MOVE_ORDER : SoundCue.ERROR);
    }

'''
replace_between(
    "src/main/java/com/tndmadman/rts/GamePanel.java",
    "    private void clickRight(Point2D p) {",
    "    private void handleCommandClick(Point2D p) {",
    click_right,
)

handle_command = r'''    private void handleCommandClick(Point2D p, boolean append) {
        if (commandMode == UnitOrderType.PATROL && patrolStart == null) {
            patrolStart = p;
            world.status = "Patrol start set. Right-click the second patrol point.";
            ProceduralAudio.play(SoundCue.SELECT);
            return;
        }
        if (commandMode == UnitOrderType.PATROL) {
            issueSelectedOrder(UnitOrderType.PATROL, patrolStart.getX(), patrolStart.getY(),
                    p.getX(), p.getY(), "", append);
            clearCommandMode();
            return;
        }
        if (commandMode == UnitOrderType.ATTACK_MOVE) {
            issueSelectedOrder(UnitOrderType.ATTACK_MOVE, p.getX(), p.getY(), p.getX(), p.getY(), "", append);
            clearCommandMode();
            return;
        }
        Unit unit = world.unitAt(p.getX(), p.getY());
        Base base = world.baseAt(p.getX(), p.getY());
        if (commandMode == UnitOrderType.ESCORT) {
            if (unit == null || !DiplomacySystem.allied(world, PlayerRegistry.localId(), unit.playerId)) {
                world.status = "Escort requires a friendly ship target.";
                ProceduralAudio.play(SoundCue.ERROR);
                return;
            }
            issueSelectedOrder(UnitOrderType.ESCORT, unit.x, unit.y, unit.x, unit.y,
                    CombatTarget.unit(unit), append);
            clearCommandMode();
            return;
        }
        if (commandMode == UnitOrderType.GUARD) {
            String target = "";
            if (unit != null && DiplomacySystem.allied(world, PlayerRegistry.localId(), unit.playerId)) target = CombatTarget.unit(unit);
            else if (base != null && DiplomacySystem.allied(world, PlayerRegistry.localId(), base.playerId)) target = CombatTarget.base(base);
            issueSelectedOrder(UnitOrderType.GUARD, p.getX(), p.getY(), p.getX(), p.getY(), target, append);
            clearCommandMode();
        }
    }

'''
replace_between(
    "src/main/java/com/tndmadman/rts/GamePanel.java",
    "    private void handleCommandClick(Point2D p) {",
    "    private void issueSelectedOrder(UnitOrderType type, double x1, double y1,",
    handle_command,
)

issue_order = r'''    private void issueSelectedOrder(UnitOrderType type, double x1, double y1,
                                    double x2, double y2, String targetKey, boolean append) {
        int applied = queueTacticalSelected(type, x1, y1, x2, y2, targetKey, append);
        world.status = applied > 0
                ? (append ? "Queued " : "Assigned ") + orderName(type) + " for " + applied + " ship(s)."
                : "Unable to assign " + orderName(type) + ".";
        ProceduralAudio.play(applied > 0 ? SoundCue.MOVE_ORDER : SoundCue.ERROR);
    }

'''
replace_between(
    "src/main/java/com/tndmadman/rts/GamePanel.java",
    "    private void issueSelectedOrder(UnitOrderType type, double x1, double y1,",
    "    private void setCommandMode(UnitOrderType type) {",
    issue_order,
)
replace_once(
    "src/main/java/com/tndmadman/rts/GamePanel.java",
    "        if (world.selectedCount() <= 0) {\n            world.status = \"Select one or more ships first.\";\n",
    "        if (world.selectedCount() <= 0 && queuedPlanningUnits.isEmpty()) {\n            world.status = \"Select one or more ships first.\";\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GamePanel.java",
    "            issueSelectedOrder(UnitOrderType.HOLD, 0, 0, 0, 0, \"\");\n",
    "            issueSelectedOrder(UnitOrderType.HOLD, 0, 0, 0, 0, \"\", false);\n",
)
replace_once(
    "src/main/java/com/tndmadman/rts/GamePanel.java",
    "        if (settings.matches(\"attack_move\", e)) { setCommandMode(UnitOrderType.ATTACK_MOVE); return; }\n",
    "        if (settings.matches(\"stop_orders\", e)) { clearQueuedOrders(); clearCommandMode(); return; }\n        if (settings.matches(\"attack_move\", e)) { setCommandMode(UnitOrderType.ATTACK_MOVE); return; }\n",
)

queue_helpers = r'''    private void orderAttack(String targetKey, boolean append) {
        int applied = queueAttackSelected(targetKey, append);
        world.status = applied > 0
                ? (append ? "Queued attack for " : "Attacking target with ") + applied + " ship(s)."
                : "No valid attack-capable ship selected.";
        if (applied <= 0) ProceduralAudio.play(SoundCue.ERROR);
    }

    private int queueMoveSelected(Point2D point, boolean append) {
        List<String> keys = commandUnitKeys(append);
        if (keys.isEmpty()) return 0;
        int applied = 0;
        for (int i = 0; i < keys.size(); i++) {
            Point2D target = queuedFormationTarget(point.getX(), point.getY(), i, keys.size());
            QueuedUnitCommand command = QueuedUnitCommand.move(world.activeSystemId(), target.getX(), target.getY());
            if (issueQueueMutation(keys.get(i), command, append ? UnitQueueOperation.APPEND : UnitQueueOperation.REPLACE)) applied++;
        }
        return applied;
    }

    private int queueAttackSelected(String targetKey, boolean append) {
        List<String> keys = commandUnitKeys(append);
        if (keys.isEmpty()) return 0;
        int applied = 0;
        for (String key : keys) {
            Unit present = world.units.get(key);
            if (present != null && !WeaponRules.armed(world, present)) continue;
            if (issueQueueMutation(key, QueuedUnitCommand.attack(world.activeSystemId(), targetKey),
                    append ? UnitQueueOperation.APPEND : UnitQueueOperation.REPLACE)) applied++;
        }
        return applied;
    }

    private int queueHarvestSelected(ResourceNode node, boolean append) {
        if (node == null) return 0;
        List<String> keys = commandUnitKeys(append);
        if (keys.isEmpty()) return 0;
        int applied = 0;
        for (String key : keys) {
            Unit present = world.units.get(key);
            if (present != null && !present.type().harvestKinds.contains(node.kind)) continue;
            if (issueQueueMutation(key, QueuedUnitCommand.harvest(world.activeSystemId(), node.id),
                    append ? UnitQueueOperation.APPEND : UnitQueueOperation.REPLACE)) applied++;
        }
        return applied;
    }

    private int queueWormholeSelected(WormholeGate gate, boolean append) {
        if (gate == null) return 0;
        List<String> keys = commandUnitKeys(append);
        if (keys.isEmpty()) return 0;
        int applied = 0;
        for (String key : keys) {
            if (issueQueueMutation(key, QueuedUnitCommand.wormhole(world.activeSystemId(), gate.id, gate.toSystemId),
                    append ? UnitQueueOperation.APPEND : UnitQueueOperation.REPLACE)) applied++;
        }
        return applied;
    }

    private int queueTacticalSelected(UnitOrderType type, double x1, double y1,
                                      double x2, double y2, String targetKey, boolean append) {
        List<String> keys = commandUnitKeys(append);
        if (keys.isEmpty()) return 0;
        int applied = 0;
        for (int i = 0; i < keys.size(); i++) {
            double ax = x1, ay = y1, bx = x2, by = y2;
            if (type == UnitOrderType.ATTACK_MOVE) {
                Point2D end = queuedFormationTarget(x2, y2, i, keys.size());
                ax = bx = end.getX(); ay = by = end.getY();
            } else if (type == UnitOrderType.PATROL) {
                Point2D start = queuedFormationTarget(x1, y1, i, keys.size());
                Point2D end = queuedFormationTarget(x2, y2, i, keys.size());
                ax = start.getX(); ay = start.getY(); bx = end.getX(); by = end.getY();
            } else if (type == UnitOrderType.GUARD && (targetKey == null || targetKey.isBlank())) {
                Point2D anchor = queuedFormationTarget(x1, y1, i, keys.size());
                ax = bx = anchor.getX(); ay = by = anchor.getY();
            }
            double radius = UnitOrderSystem.defaultRadius(type);
            QueuedUnitCommand command = QueuedUnitCommand.tactical(world.activeSystemId(), type,
                    ax, ay, bx, by, radius, targetKey);
            if (issueQueueMutation(keys.get(i), command,
                    append ? UnitQueueOperation.APPEND : UnitQueueOperation.REPLACE)) applied++;
        }
        return applied;
    }

    private boolean issueQueueMutation(String key, QueuedUnitCommand command, UnitQueueOperation operation) {
        int unitId = unitIdFromKey(key);
        String playerId = PlayerRegistry.localId();
        if (unitId < 0 || playerId == null || playerId.isBlank()) return false;
        UnitQueueMutation mutation = new UnitQueueMutation(playerId, unitId, operation,
                UnitCommandQueueSystem.revision(world, key), command);
        if (network == null) return UnitCommandQueueSystem.applyGlobal(world, mutation) == UnitQueueApplyResult.APPLIED;
        if (network.clientMode()) {
            if (!network.clientReady()) return false;
            if (UnitCommandQueueSystem.predict(world, mutation) != UnitQueueApplyResult.APPLIED) return false;
            network.queue(mutation);
            return true;
        }
        return network.queue(mutation) == UnitQueueApplyResult.APPLIED;
    }

    private List<String> commandUnitKeys(boolean append) {
        List<String> selected = new ArrayList<>();
        for (Unit unit : world.selectedUnits()) {
            if (PlayerRegistry.isLocal(unit.playerId)) selected.add(unit.key());
        }
        if (!selected.isEmpty()) {
            queuedPlanningUnits.clear();
            queuedPlanningUnits.addAll(selected);
            return selected;
        }
        if (append && !queuedPlanningUnits.isEmpty()) return new ArrayList<>(queuedPlanningUnits);
        return List.of();
    }

    private void clearQueuedOrders() {
        List<String> keys = commandUnitKeys(true);
        int cleared = 0;
        for (String key : keys) {
            int unitId = unitIdFromKey(key);
            if (unitId < 0) continue;
            UnitQueueMutation mutation = new UnitQueueMutation(PlayerRegistry.localId(), unitId,
                    UnitQueueOperation.CLEAR, UnitCommandQueueSystem.revision(world, key), null);
            boolean success;
            if (network == null) success = UnitCommandQueueSystem.applyGlobal(world, mutation) == UnitQueueApplyResult.APPLIED;
            else if (network.clientMode()) {
                success = network.clientReady() && UnitCommandQueueSystem.predict(world, mutation) == UnitQueueApplyResult.APPLIED;
                if (success) network.queue(mutation);
            } else success = network.queue(mutation) == UnitQueueApplyResult.APPLIED;
            if (success) cleared++;
        }
        queuedPlanningUnits.clear();
        world.status = cleared > 0 ? "Stopped and cleared orders for " + cleared + " ship(s)." : "No queued ships to stop.";
        ProceduralAudio.play(cleared > 0 ? SoundCue.SELECT : SoundCue.ERROR);
    }

    private Point2D queuedFormationTarget(double x, double y, int index, int count) {
        double spacing = 54, ox = 0, oy = 0;
        switch (formation) {
            case LINE -> ox = (index - (count - 1) / 2.0) * spacing;
            case COLUMN -> oy = (index - (count - 1) / 2.0) * spacing;
            case WEDGE -> {
                if (index > 0) {
                    int rank = (index + 1) / 2;
                    int side = index % 2 == 1 ? -1 : 1;
                    ox = side * rank * spacing;
                    oy = rank * spacing;
                }
            }
            case GRID -> {
                int cols = (int)Math.ceil(Math.sqrt(count));
                double rows = Math.ceil(count / (double)cols);
                int col = index % cols;
                int row = index / cols;
                ox = (col - (cols - 1) / 2.0) * 42;
                oy = (row - (rows - 1) / 2.0) * 42;
            }
        }
        return new Point2D.Double(Calc.clamp(x + ox, 0, world.width), Calc.clamp(y + oy, 0, world.height));
    }

    private int unitIdFromKey(String key) {
        if (key == null) return -1;
        int separator = key.lastIndexOf(':');
        if (separator < 0 || separator + 1 >= key.length()) return -1;
        try { return Integer.parseInt(key.substring(separator + 1)); }
        catch (RuntimeException ignored) { return -1; }
    }

    private String orderName(UnitOrderType type) {
        return switch (type) {
            case PATROL -> "patrol";
            case GUARD -> "guard";
            case ESCORT -> "escort";
            case HOLD -> "hold position";
            case ATTACK_MOVE -> "attack-move";
            case NONE -> "order";
        };
    }

'''
replace_between(
    "src/main/java/com/tndmadman/rts/GamePanel.java",
    "    private void orderAttack(String targetKey) {",
    "    private void clearSelection() {",
    queue_helpers,
)

# Build task for the focused issue validator.
replace_once(
    "build.gradle",
    "tasks.register('validateIssue289Completion', JavaExec) {\n",
    "tasks.register('validateIssue291CommandQueues', JavaExec) {\n    group = 'verification'\n    description = 'Validate bounded authoritative queued waypoints and compound order chains.'\n    dependsOn tasks.named('classes')\n    classpath = sourceSets.main.runtimeClasspath\n    mainClass = 'com.tndmadman.rts.Issue291CommandQueueValidator'\n    jvmArgs '-Djava.awt.headless=true'\n}\n\ntasks.register('validateIssue289Completion', JavaExec) {\n",
)

print("Issue 291 integration patch applied.")
