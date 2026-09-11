package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Player-facing bridge from the Galaxy Map to the existing authoritative FleetWire command surface.
 *
 * The map never mutates fleet state directly. Solo play applies the exact FleetWire packet locally;
 * multiplayer clients submit that packet to the server, which re-binds ownership to the authenticated
 * connection and enforces the expected fleet revision before any strategic order is accepted.
 */
final class GalaxyMapFleetCommandBridge {
    enum Action { MOVE, RALLY }

    record DispatchResult(boolean submitted, boolean applied, String packet, String message) {
        static DispatchResult rejected(String message) {
            return new DispatchResult(false, false, "", message == null ? "Fleet order rejected." : message);
        }
    }

    private static final Map<World, PeerNetwork> NETWORKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private GalaxyMapFleetCommandBridge() { }

    static void bind(World world, PeerNetwork network) {
        if (world == null) return;
        if (network == null) NETWORKS.remove(world);
        else NETWORKS.put(world, network);
    }

    static List<FleetView> fleetsAtSystem(World world, String ownerId, String systemId) {
        if (world == null || ownerId == null || ownerId.isBlank() || systemId == null || systemId.isBlank()) {
            return List.of();
        }
        List<FleetView> out = new ArrayList<>();
        for (FleetView fleet : FleetManager.viewsForOwner(world, ownerId)) {
            if (fleet.shipsBySystem().getOrDefault(systemId, 0) > 0) out.add(fleet);
        }
        out.sort(Comparator.comparingLong(FleetView::fleetId));
        return List.copyOf(out);
    }

    static DispatchResult dispatch(World world, String ownerId, long fleetId,
                                   String targetSystemId, Action action) {
        if (world == null || ownerId == null || ownerId.isBlank() || fleetId <= 0
                || targetSystemId == null || targetSystemId.isBlank() || action == null) {
            return DispatchResult.rejected("Fleet order is incomplete.");
        }
        FleetView fleet = FleetManager.view(world, ownerId, fleetId).orElse(null);
        if (fleet == null || !ownerId.equals(fleet.ownerId())) {
            return DispatchResult.rejected("Selected fleet is no longer available.");
        }

        String packet = command(world, ownerId, fleet, targetSystemId, action);
        if (packet.isBlank()) return DispatchResult.rejected("Target system is unavailable.");

        PeerNetwork network = NETWORKS.get(world);
        if (network != null && network.clientMode()) {
            if (network.clientObserver()) return DispatchResult.rejected("Observer sessions are read-only.");
            network.fleet(packet);
            String message = actionLabel(action) + " order submitted for " + fleet.name()
                    + " -> " + systemName(targetSystemId) + ".";
            world.status = message;
            return new DispatchResult(true, false, packet, message);
        }

        FleetWire.Outcome outcome = FleetWire.applyLocal(world, ownerId, packet);
        return new DispatchResult(true, outcome.mutated(), packet, outcome.message());
    }

    static String commandForTest(World world, String ownerId, long fleetId,
                                 String targetSystemId, Action action) {
        FleetView fleet = FleetManager.view(world, ownerId, fleetId).orElse(null);
        return fleet == null ? "" : command(world, ownerId, fleet, targetSystemId, action);
    }

    private static String command(World world, String ownerId, FleetView fleet,
                                  String targetSystemId, Action action) {
        if (action == Action.MOVE) {
            return FleetWire.command(ownerId, "MOVE", fleet.fleetId(), fleet.revision(), targetSystemId);
        }
        StarSystemDefinition definition = StarSystems.get(GalaxySystemIdentity.templateId(targetSystemId));
        if (definition == null) return "";
        double x = definition.width() / 2.0;
        double y = definition.height() / 2.0;
        return FleetWire.command(ownerId, "RALLY", fleet.fleetId(), fleet.revision(), targetSystemId, x, y);
    }

    private static String actionLabel(Action action) {
        return action == Action.RALLY ? "Rally" : "Move";
    }

    private static String systemName(String systemId) {
        StarSystemDefinition definition = StarSystems.get(GalaxySystemIdentity.templateId(systemId));
        return definition == null ? systemId : definition.name();
    }
}
