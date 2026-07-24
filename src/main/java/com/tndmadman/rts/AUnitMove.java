package com.tndmadman.rts;

final class AUnitMove {
    private AUnitMove() { }

    static boolean apply(World world, MoveCommand c) {
        if (world == null || c == null) return false;
        Unit u = world.units.get(Unit.key(c.playerId(), c.unitId()));
        if (u == null) return false;
        if (!GameplayCommandNumbers.worldCoordinate(world, c.x(), c.y())) return false;
        u.issueMove(c.x(), c.y());
        return true;
    }
}

final class SideAOrders {
    private SideAOrders() { }

    static void handle(PeerServerSide s, String[] p, ConnectionId connectionId) {
        String id = p.length > 1 ? s.ownerId(connectionId, p[1]) : "";
        switch (p[0]) {
            case "MOVE" -> applyMove(s, p, connectionId, id);
            case "WORK" -> { s.touch(connectionId); if (s.owns(connectionId, id)) s.change(id, () -> AUnitWork.apply(s.world, new HarvestCommand(id, Integer.parseInt(p[2]), Integer.parseInt(p[3])))); }
            case "ATTACK" -> { s.touch(connectionId); if (s.owns(connectionId, id)) s.change(id, () -> AUnitAttack.apply(s.world, new AttackCommand(id, Integer.parseInt(p[2]), p[3]))); }
            case "ORDER" -> applyOrder(s, p, connectionId, id);
            case "RESPAWN" -> { s.touch(connectionId); if (s.owns(connectionId, id)) { s.change(id, () -> WorldNetAccess.respawnPlayer(s.world, id)); s.broadcastNow(); } }
            case "BUILD" -> { s.touch(connectionId); if (s.owns(connectionId, id)) s.change(id, () -> { if (CommandAuth.base(s.world, id, p[2])) s.world.buildShip(p[2], p[3]); }); }
            case "PACK" -> { s.touch(connectionId); if (s.owns(connectionId, id)) s.change(id, () -> { if (CommandAuth.pack(s.world, id, p[2], p[3])) AUnitPack.apply(s.world, p[2], p[3], p[4]); }); }
            case "PROD" -> { s.touch(connectionId); if (s.owns(connectionId, id) && p.length >= 5) s.change(id, () -> { if (CommandAuth.base(s.world, id, p[3])) ProductionCommands.apply(s.world, id, p[2], p[3], p[4], p.length > 5 ? p[5] : ""); }); }
            case "VIEW_SYSTEM" -> {
                if (p.length >= 4) s.requestView(connectionId, id, p[2], viewRevision(p));
            }
            case "WHTOUCH" -> { s.touch(connectionId); if (s.owns(connectionId, id)) { s.change(id, () -> applyWormholeTouch(s.world, id, p)); s.sendInitialTo(connectionId); } }
        }
    }

    private static void applyMove(PeerServerSide s, String[] p, ConnectionId connectionId, String playerId) {
        s.touch(connectionId);
        if (!s.owns(connectionId, playerId)) return;
        if (p.length < 5) {
            reject(s);
            return;
        }
        try {
            MoveCommand command = new MoveCommand(
                    playerId,
                    Integer.parseInt(p[2]),
                    GameplayCommandNumbers.parseFinite(p[3]),
                    GameplayCommandNumbers.parseFinite(p[4]));
            if (!GameplayCommandNumbers.worldCoordinate(s.world, command.x(), command.y())) {
                reject(s);
                return;
            }
            s.change(playerId, () -> {
                if (!AUnitMove.apply(s.world, command)) reject(s);
            });
        } catch (RuntimeException ignored) {
            reject(s);
        }
    }

    private static void applyOrder(PeerServerSide s, String[] p, ConnectionId connectionId, String playerId) {
        s.touch(connectionId);
        if (!s.owns(connectionId, playerId)) return;
        if (p.length < 11) {
            reject(s);
            return;
        }
        try {
            UnitOrderCommand command = new UnitOrderCommand(
                    playerId,
                    Integer.parseInt(p[2]),
                    UnitOrderType.valueOf(p[3]),
                    GameplayCommandNumbers.parseFinite(p[4]),
                    GameplayCommandNumbers.parseFinite(p[5]),
                    GameplayCommandNumbers.parseFinite(p[6]),
                    GameplayCommandNumbers.parseFinite(p[7]),
                    GameplayCommandNumbers.parseFinite(p[8]),
                    p[9],
                    Integer.parseInt(p[10]));
            if (!GameplayCommandNumbers.worldCoordinate(s.world, command.x1(), command.y1())
                    || !GameplayCommandNumbers.worldCoordinate(s.world, command.x2(), command.y2())
                    || !GameplayCommandNumbers.orderRadius(command.radius())
                    || command.phase() < 0 || command.phase() > 1) {
                reject(s);
                return;
            }
            s.change(playerId, () -> {
                if (!AUnitOrder.apply(s.world, command)) reject(s);
            });
        } catch (RuntimeException ignored) {
            reject(s);
        }
    }

    private static void reject(PeerServerSide s) {
        s.transport.recordMalformedPacket();
    }

    private static long viewRevision(String[] p) {
        if (p.length < 4) return 0;
        try { return Math.max(0, Long.parseLong(p[3])); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static void applyWormholeTouch(World world, String playerId, String[] p) {
        if (p.length < 5) {
            world.transferTouchingShips(playerId);
            return;
        }
        try {
            int unitId = Integer.parseInt(p[2]);
            String fromSystemId = p[3];
            String gateId = p[4];
            if (!WormholeTouchRequest.validPlayerId(playerId) || !WormholeTouchRequest.validSystemId(fromSystemId)) return;
            String old = world.activeSystemId();
            world.activateSystem(fromSystemId);
            try {
                Unit unit = world.units.get(Unit.key(playerId, unitId));
                WormholeGate gate = WormholeTouchRequest.gateById(world, gateId);
                if (unit == null || !playerId.equals(unit.playerId) || unit.wormholeCooldown > 0 || gate == null || !gate.contains(unit.x, unit.y)) return;
                if (!fromSystemId.equals(gate.fromSystemId) || !WormholeTouchRequest.validSystemId(gate.toSystemId)) return;
                world.transferTouchingShips(playerId);
            } finally {
                world.activateSystem(old);
            }
        } catch (RuntimeException ignored) {
            // The transport records malformed command rates without echoing attacker-controlled packet data.
        }
    }
}
