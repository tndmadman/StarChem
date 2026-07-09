package com.tndmadman.rts;

final class PeerServerPackets {
    private PeerServerPackets() { }

    static void handle(PeerServerSide s, String message, NetPacket packet) {
        PacketSideA.handle(s, message, packet);
    }
}
