package com.tndmadman.rts;

final class PacketSideA {
    private PacketSideA() { }

    static void handle(PeerServerSide s, String message, NetPacket packet) {
        String[] p = message.split("\\|", -1);
        ConnectionId connectionId = packet.connectionId();
        try {
            if (SideAJoin.handle(s, p, connectionId, packet)) return;
            if (SideADev.handle(s, p, connectionId)) return;
            SideAOrders.handle(s, p, connectionId);
        } catch (Exception ex) {
            System.err.println("Bad packet: " + message + " / " + ex.getMessage());
        }
    }
}
