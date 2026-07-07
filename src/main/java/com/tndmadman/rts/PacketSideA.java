package com.tndmadman.rts;

final class PacketSideA {
    private PacketSideA() { }

    static void handle(PeerServerSide s, String message, NetPacket packet) {
        String[] p = message.split("\\|", -1);
        String ep = s.endpoint(packet.address(), packet.port());
        try {
            if (SideAJoin.handle(s, p, ep, packet)) return;
            SideAOrders.handle(s, p, ep);
        } catch (Exception ex) {
            System.err.println("Bad packet: " + message + " / " + ex.getMessage());
        }
    }
}
