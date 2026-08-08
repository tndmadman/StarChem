package com.tndmadman.rts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

final class OwnerFleetLocationSync {
    private static final long REFRESH_MS = 1000;
    private static final Map<World, Map<String, SyncState>> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private OwnerFleetLocationSync() { }

    static void send(World world, ClientViewCache views, ServerPeer peer, NetOutbound out, boolean force) {
        if (world == null || views == null || peer == null || out == null) return;
        long now = System.currentTimeMillis();
        Map<String, SyncState> byPlayer = STATES.computeIfAbsent(world, ignored -> new LinkedHashMap<>());
        SyncState prior = byPlayer.get(peer.playerId());
        if (!force && prior != null && now - prior.lastScanMs < REFRESH_MS) return;

        GalaxyMapSnapshot galaxy = views.galaxySnapshot(world, peer.playerId());
        String packet = GalaxyMapWire.encode(GalaxyRuntimeOptions.copiesPerTemplate(), galaxy,
                peer.playerId(), OwnerFleetLocations.capture(world, peer.playerId()));
        byPlayer.put(peer.playerId(), new SyncState(now, packet));
        if (force || prior == null || !packet.equals(prior.packet)) {
            out.send(packet, peer.connectionId(), DeliveryClass.GALAXY);
        }
    }

    static void clear(World world) {
        if (world != null) STATES.remove(world);
    }

    private record SyncState(long lastScanMs, String packet) { }
}
