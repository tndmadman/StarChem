package com.tndmadman.rts;

final class ClientPackets {
    private ClientPackets() { }

    static void handle(PeerClientSide c, String message) {
        if (BRoute0.apply(c, message)) return;
        String[] p = message.split("\\|", -1);
        if (p[0].equals("ENV")) { c.readEnv(p); return; }
        if (p[0].equals("SEED") && p.length >= 2) { c.readSeed(p[1]); return; }
        if (p[0].equals("WELCOME")) { c.readWelcome(p); return; }
        if (p[0].equals("SNAPSHOT")) c.readSnapshot(message);
    }
}
