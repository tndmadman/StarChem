package com.tndmadman.rts;

final class AUnitMove {
    private AUnitMove() { }

    static void apply(World world, MoveCommand c) {
        Unit u = world.units.get(Unit.key(c.playerId(), c.unitId()));
        if (u == null) {
            System.out.println("MOVE MISS " + c.playerId() + ":" + c.unitId());
            return;
        }
        u.issueMove(c.x(), c.y());
        System.out.println("MOVE OK " + c.playerId() + ":" + c.unitId());
    }
}

final class SideAOrders {
    private SideAOrders() { }

    static void handle(PeerServerSide s, String[] p, String ep) {
        String id = p.length > 1 ? s.ownerId(ep, p[1]) : "";
        switch (p[0]) {
            case "MOVE" -> { s.touch(ep); if (s.owns(ep, id)) s.change(id, () -> AUnitMove.apply(s.world, new MoveCommand(id, Integer.parseInt(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4])))); }
            case "WORK" -> { s.touch(ep); if (s.owns(ep, id)) s.change(id, () -> AUnitWork.apply(s.world, new HarvestCommand(id, Integer.parseInt(p[2]), Integer.parseInt(p[3])))); }
            case "ATTACK" -> { s.touch(ep); if (s.owns(ep, id)) s.change(id, () -> AUnitAttack.apply(s.world, new AttackCommand(id, Integer.parseInt(p[2]), p[3]))); }
            case "ORDER" -> { s.touch(ep); if (s.owns(ep, id)) s.change(id, () -> applyOrder(s.world, id, p)); }
            case "RESPAWN" -> { s.touch(ep); if (s.owns(ep, id)) { s.change(id, () -> WorldNetAccess.respawnPlayer(s.world, id)); s.broadcastNow(); } }
            case "BUILD" -> { s.touch(ep); if (s.owns(ep, id)) s.change(id, () -> { if (CommandAuth.base(s.world, id, p[2])) s.world.buildShip(p[2], p[3]); }); }
            case "PACK" -> { s.touch(ep); if (s.owns(ep, id)) s.change(id, () -> { if (CommandAuth.pack(s.world, id, p[2], p[3])) AUnitPack.apply(s.world, p[2], p[3], p[4]); }); }
            case "JUMP" -> { s.touch(ep); if (s.owns(ep, id)) { s.change(id, () -> applyJump(s.world, p)); s.sendInitialTo(ep); } }
            case "WHTOUCH" -> { s.touch(ep); if (s.owns(ep, id)) { s.change(id, () -> applyWormholeTouch(s.world, id, p)); s.sendInitialTo(ep); } }
        }
    }

    private static void applyOrder(World world, String playerId, String[] p) {
        if (p.length < 11) return;
        try {
            AUnitOrder.apply(world, new UnitOrderCommand(
                    playerId,
                    Integer.parseInt(p[2]),
                    UnitOrderType.valueOf(p[3]),
                    Double.parseDouble(p[4]),
                    Double.parseDouble(p[5]),
                    Double.parseDouble(p[6]),
                    Double.parseDouble(p[7]),
                    Double.parseDouble(p[8]),
                    p[9],
                    Integer.parseInt(p[10])));
        } catch (RuntimeException ignored) {
            System.out.println("ORDER BAD PACKET");
        }
    }

    private static void applyJump(World world, String[] p) {
        if (p.length >= 5) {
            String targetSystemId = p[2];
            double x = Double.parseDouble(p[3]);
            double y = Double.parseDouble(p[4]);
            if (!world.viewSystemThroughWormhole(targetSystemId)) world.jumpThroughWormholeAt(x, y);
            return;
        }
        if (p.length >= 4) world.jumpThroughWormholeAt(Double.parseDouble(p[2]), Double.parseDouble(p[3]));
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
                if (unit == null || !playerId.equals(unit.playerId) || unit.wormholeCooldown > 0 || gate == null || !gate.contains(unit.x, unit.y)) {
                    System.out.println("WORMHOLE TOUCH MISS player=" + playerId + " unit=" + unitId + " from=" + fromSystemId + " gate=" + gateId + " worldSys=" + world.activeSystemId());
                    return;
                }
                if (!fromSystemId.equals(gate.fromSystemId) || !WormholeTouchRequest.validSystemId(gate.toSystemId)) {
                    System.out.println("WORMHOLE TOUCH REJECT player=" + playerId + " unit=" + unitId + " from=" + fromSystemId + " gate=" + gateId + " gateFrom=" + gate.fromSystemId + " gateTo=" + gate.toSystemId);
                    return;
                }
                world.transferTouchingShips(playerId);
            } finally {
                world.activateSystem(old);
            }
        } catch (RuntimeException ignored) {
            System.out.println("WORMHOLE TOUCH BAD PACKET");
        }
    }
}
