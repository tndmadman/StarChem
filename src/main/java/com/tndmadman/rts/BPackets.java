package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

final class ClientPackets {
    private ClientPackets() { }

    static void handle(PeerClientSide c, String message) {
        if (BRoute0.apply(c, message)) return;
        String[] p = message.split("\\|", -1);
        if (p[0].equals("ENV")) { c.readEnv(p); return; }
        if (p[0].equals("SEED") && p.length >= 2) { c.readSeed(p[1]); return; }
        if (p[0].equals("WELCOME")) { c.readWelcome(p); return; }
        if (p[0].equals("FOG_STATE") && p.length == 2) {
            ServerFogOfWarState.applyClient(c.world, c.localPlayerId(), p[1]);
            return;
        }
        if (p[0].equals("DIPLOMACY_VIEW") && p.length == 2) {
            Map<String,Object> view = DiplomacyStateWire.decode(p[1]);
            DiplomacySystem.restore(c.world, view.get("state"));
            DiplomacyClientState.apply(c.world, view);
            DiplomacyBootstrap.refreshIntelAlliances(c.world);
            return;
        }
        if (p[0].equals("DIPLOMACY_STATE") && p.length == 2) {
            // Compatibility with saves or transitional servers from the previous rules version.
            DiplomacySystem.restore(c.world, DiplomacyStateWire.decode(p[1]));
            DiplomacyBootstrap.refreshIntelAlliances(c.world);
            return;
        }
        if (p[0].equals("DIPLOMACY_RESULT") && p.length == 4) {
            String notice = decodeText(p[3]);
            if (notice.isBlank()) notice = "The server processed the diplomacy request.";
            AlertCenter.push(c.world, notice);
            System.out.println("DIPLOMACY " + p[2] + ": " + notice);
            return;
        }
        if (p[0].equals("FIT_CATALOG") && p.length == 2) {
            WorldFitCatalog.applyNetworkView(c.world, FitStateWire.decode(p[1]));
            return;
        }
        if (p[0].equals("FIT_RESULT") && p.length == 4) {
            String notice = decodeText(p[3]);
            if (notice.isBlank()) notice = "The server processed the fit request.";
            AlertCenter.push(c.world, notice);
            c.world.status = notice;
            System.out.println("FIT " + p[2] + ": " + notice);
            return;
        }
        if (p[0].equals("SERVER_NOTICE")) {
            String notice = message.length() > 14 ? message.substring(14).trim() : "";
            notice = notice.isBlank() ? "Server notice." : notice;
            AlertCenter.push(c.world, "SERVER: " + notice);
            System.out.println(c.world.status);
            return;
        }
        if (p[0].equals("SNAPSHOT")) c.readSnapshot(message);
    }

    private static String decodeText(String value) {
        if (value == null || value.isBlank() || value.length() > 2_048) return "";
        try {
            String text = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            text = text.replace('\n', ' ').replace('\r', ' ').trim();
            return text.length() <= 512 ? text : text.substring(0, 512);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }
}
