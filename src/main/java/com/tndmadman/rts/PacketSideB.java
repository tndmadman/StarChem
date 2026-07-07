package com.tndmadman.rts;

final class PacketSideB {
    private PacketSideB() { }

    static void handle(PeerNetwork net, String message) {
        String[] p = message.split("\\|", -1);
        if (p[0].equals("ENV")) { net.readEnv(p); return; }
        if (p[0].equals("SEED") && p.length >= 2) { net.readSeed(p[1]); return; }
        if (p[0].equals("WELCOME")) { net.readWelcome(p); return; }
        if (p[0].equals("SNAPSHOT")) net.readSnapshot(message);
    }
}
