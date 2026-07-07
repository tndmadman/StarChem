package com.tndmadman.rts;

final class PeerHostPackets {
    private PeerHostPackets() { }

    static void handle(PeerNetwork net, String message, NetPacket packet) {
        String[] p = message.split("\\|", -1);
        String ep = net.endpoint(packet.address(), packet.port());
        try {
            switch (p[0]) {
                case "JOIN" -> net.joinPeer(ep, packet.address(), packet.port(), p.length > 1 ? p[1] : "Player", net.requestedDev(p));
                case "PING" -> net.touch(ep);
                case "MOVE" -> { net.touch(ep); if (net.owns(ep, p[1])) net.change(p[1], () -> net.applyMove(new MoveCommand(p[1], Integer.parseInt(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4])))); }
                case "WORK" -> { net.touch(ep); if (net.owns(ep, p[1])) net.change(p[1], () -> net.applyWork(new HarvestCommand(p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3])))); }
                case "ATTACK" -> { net.touch(ep); if (net.owns(ep, p[1])) net.change(p[1], () -> { if (CommandAuth.unit(net.world, p[1], Unit.key(p[1], Integer.parseInt(p[2])))) net.applyAttack(new AttackCommand(p[1], Integer.parseInt(p[2]), p[3])); }); }
                case "RESPAWN" -> { net.touch(ep); if (net.owns(ep, p[1])) { net.change(p[1], () -> WorldNetAccess.respawnPlayer(net.world, p[1])); net.broadcastNow(); } }
                case "BUILD" -> { net.touch(ep); if (net.owns(ep, p[1])) net.change(p[1], () -> { if (CommandAuth.base(net.world, p[1], p[2])) net.world.buildShip(p[2], p[3]); }); }
                case "PACK" -> { net.touch(ep); if (net.owns(ep, p[1])) net.change(p[1], () -> { if (CommandAuth.pack(net.world, p[1], p[2], p[3])) net.applyPack(p[2], p[3], p[4]); }); }
                case "JUMP" -> { net.touch(ep); if (net.owns(ep, p[1])) { net.change(p[1], () -> net.world.jumpThroughWormholeAt(Double.parseDouble(p[2]), Double.parseDouble(p[3]))); net.broadcastNow(); } }
                case "WHTOUCH" -> { net.touch(ep); if (net.owns(ep, p[1])) { net.change(p[1], () -> net.world.transferTouchingShips()); net.broadcastNow(); } }
                case "LEAVE" -> net.removePeer(ep);
            }
        } catch (Exception ex) {
            System.err.println("Bad packet: " + message + " / " + ex.getMessage());
        }
    }
}
