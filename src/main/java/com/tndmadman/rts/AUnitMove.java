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
        switch (p[0]) {
            case "MOVE" -> { s.touch(ep); if (s.owns(ep, p[1])) s.change(p[1], () -> AUnitMove.apply(s.world, new MoveCommand(p[1], Integer.parseInt(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4])))); }
            case "WORK" -> { s.touch(ep); if (s.owns(ep, p[1])) s.change(p[1], () -> AUnitWork.apply(s.world, new HarvestCommand(p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3])))); }
            case "ATTACK" -> { s.touch(ep); if (s.owns(ep, p[1])) s.change(p[1], () -> AUnitAttack.apply(s.world, new AttackCommand(p[1], Integer.parseInt(p[2]), p[3]))); }
            case "RESPAWN" -> { s.touch(ep); if (s.owns(ep, p[1])) { s.change(p[1], () -> WorldNetAccess.respawnPlayer(s.world, p[1])); s.broadcastNow(); } }
            case "BUILD" -> { s.touch(ep); if (s.owns(ep, p[1])) s.change(p[1], () -> { if (CommandAuth.base(s.world, p[1], p[2])) s.world.buildShip(p[2], p[3]); }); }
            case "PACK" -> { s.touch(ep); if (s.owns(ep, p[1])) s.change(p[1], () -> { if (CommandAuth.pack(s.world, p[1], p[2], p[3])) AUnitPack.apply(s.world, p[2], p[3], p[4]); }); }
            case "JUMP" -> { s.touch(ep); if (s.owns(ep, p[1])) { s.change(p[1], () -> s.world.jumpThroughWormholeAt(Double.parseDouble(p[2]), Double.parseDouble(p[3]))); s.sendInitialTo(ep); } }
            case "WHTOUCH" -> { s.touch(ep); if (s.owns(ep, p[1])) { s.change(p[1], s.world::transferTouchingShips); s.sendInitialTo(ep); } }
        }
    }
}
