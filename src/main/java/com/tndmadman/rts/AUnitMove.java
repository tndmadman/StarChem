package com.tndmadman.rts;

final class AUnitMove {
    private AUnitMove() { }

    static void apply(World world, MoveCommand c) {
        Unit u = world.units.get(Unit.key(c.playerId(), c.unitId()));
        if (u != null) u.moveTo(c.x(), c.y());
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
            case "RESPAWN" -> { s.touch(ep); if (s.owns(ep, id)) { s.change(id, () -> WorldNetAccess.respawnPlayer(s.world, id)); s.broadcastNow(); } }
            case "BUILD" -> { s.touch(ep); if (s.owns(ep, id)) s.change(id, () -> { if (CommandAuth.base(s.world, id, p[2])) s.world.buildShip(p[2], p[3]); }); }
            case "PACK" -> { s.touch(ep); if (s.owns(ep, id)) s.change(id, () -> { if (CommandAuth.pack(s.world, id, p[2], p[3])) AUnitPack.apply(s.world, p[2], p[3], p[4]); }); }
            case "JUMP" -> { s.touch(ep); if (s.owns(ep, id)) { s.change(id, () -> s.world.jumpThroughWormholeAt(Double.parseDouble(p[2]), Double.parseDouble(p[3]))); s.sendInitialTo(ep); } }
            case "WHTOUCH" -> { s.touch(ep); if (s.owns(ep, id)) { s.change(id, s.world::transferTouchingShips); s.sendInitialTo(ep); } }
        }
    }
}
