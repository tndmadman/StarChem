package com.tndmadman.rts;

import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ControlGroupValidator {
    private ControlGroupValidator() { }

    public static void main(String[] args) {
        validateGroupEditingAndRecall();
        validateDoubleTapAndKeyMapping();
        validateOwnerFleetGalaxyWireAndIsolation();
        validateStableKeysAcrossSystems();
        System.out.println("Control group validation passed.");
    }

    private static void validateGroupEditingAndRecall() {
        ControlGroupManager groups = new ControlGroupManager();
        groups.assign(1, Set.of("P1:1", "P1:2"), FleetFormation.LINE);
        require(groups.size(1) == 2, "assignment did not retain both units");
        require(groups.formation(1) == FleetFormation.LINE, "assignment did not retain formation");

        groups.add(1, Set.of("P1:2", "P1:3"));
        require(groups.size(1) == 3, "add did not de-duplicate group membership");
        groups.remove(1, Set.of("P1:2"));
        require(groups.size(1) == 2 && !groups.contains(1, "P1:2"), "remove did not update membership");

        Map<String, String> locations = Map.of("P1:1", "ALPHA", "P1:3", "BETA");
        ControlGroupManager.GroupView view = groups.view(1, "ALPHA", locations);
        require(view.livingShips() == 2 && view.systemCount() == 2 && view.shipsInActiveSystem() == 1,
                "split-system group counts are wrong");
        require("ALPHA".equals(groups.focusSystem(1, "ALPHA", locations)),
                "equal split did not prefer the current system");

        groups.markActive(1, "BETA");
        require("BETA".equals(groups.focusSystem(1, "GAMMA", locations)),
                "equal split did not retain the preferred system");
        require(groups.rememberFormationIfSelectionMatches(Set.of("P1:3"), "BETA", locations, FleetFormation.WEDGE),
                "recalled selection did not update group formation");
        require(groups.formation(1) == FleetFormation.WEDGE, "updated formation was not retained");

        groups.prune(Map.of("P1:3", "BETA"));
        require(groups.size(1) == 1 && groups.contains(1, "P1:3"), "destroyed unit was not pruned");
        groups.clear(1);
        require(groups.empty(1), "group clear failed");
    }

    private static void validateDoubleTapAndKeyMapping() {
        ControlGroupManager groups = new ControlGroupManager();
        long start = 10_000_000_000L;
        require(!groups.registerTap(4, start), "first tap was treated as a double tap");
        require(groups.registerTap(4, start + ControlGroupManager.DOUBLE_TAP_NANOS - 1),
                "second tap inside threshold was not detected");
        require(!groups.registerTap(4, start + ControlGroupManager.DOUBLE_TAP_NANOS * 2),
                "tap after completed double tap was incorrectly reused");
        require(ControlGroupManager.numberForKeyCode(KeyEvent.VK_0) == 0, "top-row zero mapping failed");
        require(ControlGroupManager.numberForKeyCode(KeyEvent.VK_9) == 9, "top-row nine mapping failed");
        require(ControlGroupManager.numberForKeyCode(KeyEvent.VK_NUMPAD3) == 3, "numpad mapping failed");
        require(ControlGroupManager.numberForKeyCode(KeyEvent.VK_A) < 0, "non-number key mapped to a group");
    }

    private static void validateOwnerFleetGalaxyWireAndIsolation() {
        Map<String, String> source = Map.of("P1:17", "HOME_P1", "P1:22", "BELT_2");
        GalaxyMapSnapshot empty = new GalaxyMapSnapshot("ALPHA", List.of(), List.of());
        String packet = GalaxyMapWire.encode(1, empty, "P1", source);
        GalaxyMapWire.Decoded decoded = GalaxyMapWire.decode(packet);
        require(decoded.ownerProjection().present() && "P1".equals(decoded.ownerProjection().ownerId()),
                "owner fleet galaxy marker was not retained");
        require(source.equals(decoded.ownerUnitLocations()), "owner fleet galaxy round-trip failed");

        World world = new World("Control Group Validator");
        OwnerFleetLocationRegistry.replace(world, "P1", decoded.ownerUnitLocations());
        OwnerFleetLocationRegistry.State state = OwnerFleetLocationRegistry.state(world);
        require(state.initialized() && source.equals(state.locations()), "owner fleet registry did not retain state");
        expectFailure(() -> GalaxyMapWire.encode(1, empty, "P1", Map.of("P2:9", "BELT_2")),
                "foreign owner fleet key was accepted for encoding");

        String fleetRow = Arrays.stream(packet.split("\\|"))
                .filter(row -> row.startsWith("F,"))
                .findFirst().orElseThrow();
        expectFailure(() -> GalaxyMapWire.decode(packet + "|" + fleetRow),
                "duplicate owner fleet galaxy row was accepted");

        String emptyPacket = GalaxyMapWire.encode(1, empty, "P1", Map.of());
        GalaxyMapWire.Decoded emptyDecoded = GalaxyMapWire.decode(emptyPacket);
        require(emptyDecoded.ownerProjection().present() && emptyDecoded.ownerUnitLocations().isEmpty(),
                "empty owner fleet projection did not retain its owner marker");
    }

    private static void validateStableKeysAcrossSystems() {
        World world = new World("Control Group Projection Validator");
        String owner = "SOLO";
        Unit starting = world.units.values().stream().filter(unit -> owner.equals(unit.playerId)).findFirst().orElseThrow();
        String key = starting.key();
        String activeBeforeCapture = world.activeSystemId();
        Map<String, String> initial = OwnerFleetLocations.capture(world, owner);
        require(activeBeforeCapture.equals(world.activeSystemId()), "owner fleet projection changed the active system");
        require(world.activeSystemId().equals(initial.get(key)), "initial owner fleet projection missed the starting ship");

        String target = world.authoritativeGalaxyMapSnapshot().systems().stream()
                .map(GalaxyMapSystem::id)
                .filter(id -> !id.equals(world.activeSystemId()))
                .findFirst().orElseThrow();
        world.movePlayerAssetsToSystem(owner, target);
        Map<String, String> moved = OwnerFleetLocations.capture(world, owner);
        require(target.equals(moved.get(key)), "stable unit key did not follow the ship to its new system");
    }

    private static void expectFailure(Runnable action, String message) {
        boolean failed = false;
        try { action.run(); }
        catch (RuntimeException ex) { failed = true; }
        require(failed, message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Control group validation failed: " + message);
    }
}
