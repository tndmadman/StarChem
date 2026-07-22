package com.tndmadman.rts;

final class PacketSideA {
    private PacketSideA() { }

    static void handle(PeerServerSide s, String message, NetPacket packet) {
        try {
            String[] p = message == null ? new String[0] : message.split("\\|", -1);
            ConnectionId connectionId = packet == null ? ConnectionId.NONE : packet.connectionId();
            if (SideAJoin.handle(s, p, connectionId, packet)) return;
            if (SideADev.handle(s, p, connectionId)) return;
            SideAOrders.handle(s, p, connectionId);
        } catch (Exception ex) {
            System.err.println(PacketDiagnostics.rejectedInbound(message, packet, ex));
        }
    }
}
