package com.tndmadman.rts;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates that restored custom fits reach a fresh client before snapshots reference their runtime IDs. */
public final class FitBootstrapValidator {
    private static final String CATALOG_PREFIX = "FIT_CATALOG|";

    private FitBootstrapValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem custom-fit reconnect bootstrap validation passed.");
    }

    static void validateOrThrow() {
        PlayerRegistry.reset("SOLO", "Fit Bootstrap Author", 0x50BEFF);
        World authored = new World("Fit Bootstrap Author");
        PlayerRegistry.activate(authored);
        Unit authoredShip = firstShip(authored);
        ShipFitSpec spec = new ShipFitSpec(authoredShip.shipTypeId, List.of(), List.of("afterburner"));
        ShipLoadoutDefinition authoredFit = WorldFitCatalog.registerRuntime(authored,
                "Restored Afterburner", spec);
        authoredShip.loadoutId = authoredFit.id();
        Map<String,Object> savedCatalog = WorldFitCatalog.capture(authored);

        // Simulate a new server process loading the save: static runtime definitions start empty,
        // then the persisted world catalog must restore them before units resolve their loadout IDs.
        WeaponRules.SHIP_LOADOUTS.remove(authoredFit.id());
        PlayerRegistry.reset("SOLO", "Fit Bootstrap Server", 0x50BEFF);
        World restoredServer = new World("Fit Bootstrap Server");
        PlayerRegistry.activate(restoredServer);
        WorldFitCatalog.restore(restoredServer, savedCatalog);
        require(WeaponRules.findLoadout(authoredFit.id()) != null,
                "saved custom fit was not restored before server unit state");
        Unit restoredShip = firstShip(restoredServer);
        restoredShip.loadoutId = authoredFit.id();

        ClientViewCache views = new ClientViewCache();
        views.setHome(restoredServer, "SOLO");
        ServerPeer peer = new ServerPeer("SOLO", new ConnectionId(1), InetAddress.getLoopbackAddress(),
                50000, System.currentTimeMillis(), false);
        List<Frame> frames = new ArrayList<>();
        PeerSyncBatch.sendInitial(restoredServer, views, peer, 1,
                (message, connectionId, delivery) -> frames.add(new Frame(message, delivery)));

        int catalogIndex = indexOfCatalog(frames);
        int snapshotIndex = indexOfSnapshot(frames);
        require(catalogIndex >= 0, "initial session sync omitted the custom-fit catalog");
        require(snapshotIndex >= 0, "initial session sync omitted its authoritative snapshot");
        require(catalogIndex < snapshotIndex,
                "initial snapshot was queued before the custom-fit catalog");
        require(frames.get(catalogIndex).delivery() == DeliveryClass.ORDERED,
                "custom-fit bootstrap was not sent through the ordered transport lane");

        String snapshotPacket = frames.get(snapshotIndex).message();

        // Simulate a fresh client process. The exact snapshot must fail before bootstrap and pass after it.
        WeaponRules.SHIP_LOADOUTS.remove(authoredFit.id());
        boolean rejectedBeforeCatalog = false;
        try {
            readSnapshot(snapshotPacket);
        } catch (SnapshotDecodeException ex) {
            rejectedBeforeCatalog = ex.getMessage() != null && ex.getMessage().contains("loadout ID");
        }
        require(rejectedBeforeCatalog,
                "fresh-client simulation did not reproduce the unknown custom loadout rejection");

        PlayerRegistry.reset("SOLO", "Fit Bootstrap Client", 0x50BEFF);
        World client = new World("Fit Bootstrap Client", java.util.Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(client);
        String catalogPacket = frames.get(catalogIndex).message();
        WorldFitCatalog.applyNetworkView(client,
                FitStateWire.decode(catalogPacket.substring(CATALOG_PREFIX.length())));
        require(WeaponRules.findLoadout(authoredFit.id()) != null,
                "client did not register the restored custom fit from bootstrap");

        Snapshot accepted = readSnapshot(snapshotPacket);
        require(accepted.units().stream().anyMatch(unit -> authoredFit.id().equals(unit.loadoutId())),
                "accepted bootstrap snapshot did not retain the custom loadout ID");
    }

    private static Unit firstShip(World world) {
        for (Unit unit : world.units.values()) return unit;
        throw new IllegalStateException("Fit bootstrap validation world has no ship.");
    }

    private static int indexOfCatalog(List<Frame> frames) {
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).message().startsWith(CATALOG_PREFIX)) return i;
        }
        return -1;
    }

    private static int indexOfSnapshot(List<Frame> frames) {
        for (int i = 0; i < frames.size(); i++) {
            String message = frames.get(i).message();
            if (message.startsWith("SNAPSHOT|") || SyncFrame.matches(message)) return i;
        }
        return -1;
    }

    private static Snapshot readSnapshot(String message) {
        return SyncFrame.matches(message) ? SyncFrame.read(message) : SnapshotReader.read(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Fit bootstrap validation failed: " + message);
    }

    private record Frame(String message, DeliveryClass delivery) { }
}
