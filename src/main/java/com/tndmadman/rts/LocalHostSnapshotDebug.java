package com.tndmadman.rts;

import java.util.Set;

public final class LocalHostSnapshotDebug {
    private LocalHostSnapshotDebug() { }

    public static void main(String[] args) {
        StarSystemDefinition selected = StarSystems.get(StarSystems.DEFAULT_SYSTEM_ID);
        World server = new World("Local Host Debug", Set.of(), selected.id(), false);
        PlayerRegistry.activate(server);
        PlayerRegistry.reset("SOLO", "Local Host Debug", 0x50BEFF);
        PlayerRegistry.register("P1", "Local Host Debug", 0xFF5F55, false);
        WorldNetAccess.addPeerGroup(server, "P1");
        server.activateSystem(StarSystems.PLAYER_HOME_SYSTEM_ID + "_P1");
        Snapshot snapshot = WorldNetAccess.snapshot(server, 1);

        System.out.println("SERVER active=" + server.activeSystemId()
                + " units=" + server.units.keySet()
                + " bases=" + server.bases.keySet()
                + " snapshotSystem=" + snapshot.systemId());

        World client = new World("Local Host Debug", Set.of(), selected.id(), false);
        PlayerRegistry.activate(client);
        PlayerRegistry.reset("WAIT", "Local Host Debug", 0x50BEFF);
        PlayerRegistry.register("P1", "Local Host Debug", 0xFF5F55, true);
        client.ensurePlayerHome("P1", WorldNetAccess.usesPrimaryHome("P1"));
        client.activateSystem(client.playerHomeSystemId("P1"));

        System.out.println("CLIENT before active=" + client.activeSystemId()
                + " units=" + client.units.keySet()
                + " bases=" + client.bases.keySet());

        WorldNetAccess.apply(client, snapshot);

        System.out.println("CLIENT after apply active=" + client.activeSystemId()
                + " units=" + client.units.keySet()
                + " bases=" + client.bases.keySet());
        System.out.println("CLIENT map=" + client.galaxyMapSnapshot());
        System.out.println("CLIENT after map active=" + client.activeSystemId()
                + " units=" + client.units.keySet()
                + " bases=" + client.bases.keySet());
    }
}
