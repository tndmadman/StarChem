package com.tndmadman.rts;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
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
        validateInputPriority();
        validateOwnerFleetGalaxyWireAndIsolation();
        validateStableKeysAcrossWormholeTransfer();
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

        groups.assign(2, Set.of("P1:9"), FleetFormation.GRID);
        groups.prune(Map.of("P2:9", "ALPHA"));
        require(groups.empty(2), "ownership loss left a stale selectable group member");

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

        require(groups.acceptKeyPress(KeyEvent.VK_4), "fresh number press was rejected");
        require(!groups.acceptKeyPress(KeyEvent.VK_4), "held-key repeat was accepted as a fresh press");
        groups.releaseKey(KeyEvent.VK_4);
        require(groups.acceptKeyPress(KeyEvent.VK_4), "released number key could not be pressed again");
        groups.clearHeldKeys();
        require(groups.acceptKeyPress(KeyEvent.VK_4), "focus-reset key state remained stuck");
        groups.releaseKey(KeyEvent.VK_4);
    }

    private static void validateInputPriority() {
        JPanel game = new JPanel();
        JTextField textField = new JTextField();
        JButton modalButton = new JButton("Modal");
        require(!ControlGroupInputGate.blocked(false, false, game, game),
                "gameplay surface incorrectly blocked its own number input");
        require(ControlGroupInputGate.blocked(false, false, textField, game),
                "focused text field did not take priority over control-group input");
        require(ControlGroupInputGate.blocked(false, false, modalButton, game),
                "focused modal overlay did not take priority over control-group input");
        require(ControlGroupInputGate.blocked(true, false, game, game),
                "galaxy overlay did not block control-group input");
        require(ControlGroupInputGate.blocked(false, true, game, game),
                "ship fitting did not block control-group input");
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
        OwnerFleetLocationRegistry.replace(world, decoded.ownerProjection().ownerId(), decoded.ownerUnitLocations());
        OwnerFleetLocationRegistry.State state = OwnerFleetLocationRegistry.state(world);
        require(state.initialized() && source.equals(state.locations()), "owner fleet registry did not retain state");

        OwnerFleetLocationRegistry.suspendUntilFreshProjection(world);
        require(!OwnerFleetLocationRegistry.state(world).initialized(),
                "pre-reconnect fleet projection remained usable while suspended");
        GalaxyMapWire.Decoded refreshed = GalaxyMapWire.decode(packet);
        OwnerFleetLocationRegistry.replace(world, refreshed.ownerProjection().ownerId(), refreshed.ownerUnitLocations());
        state = OwnerFleetLocationRegistry.state(world);
        require(state.initialized() && source.equals(state.locations()),
                "fresh post-reconnect projection did not re-enable reconciliation");

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

    private static void validateStableKeysAcrossWormholeTransfer() {
        World world = new World("Control Group Projection Validator");
        String owner = "SOLO";
        Unit starting = world.units.values().stream().filter(unit -> owner.equals(unit.playerId)).findFirst().orElseThrow();
        String key = starting.key();
        String activeBeforeCapture = world.activeSystemId();
        Map<String, String> initial = OwnerFleetLocations.capture(world, owner);
        require(activeBeforeCapture.equals(world.activeSystemId()), "owner fleet projection changed the active system");
        require(world.activeSystemId().equals(initial.get(key)), "initial owner fleet projection missed the starting ship");

        WormholeGate gate = world.wormholes.stream().findFirst().orElseThrow();
        String target = gate.toSystemId;
        starting.x = gate.x;
        starting.y = gate.y;
        starting.targetX = gate.x;
        starting.targetY = gate.y;
        starting.wormholeCooldown = 0;
        require(world.transferTouchingShips(owner), "ship did not traverse the real wormhole transfer path");

        Map<String, String> moved = OwnerFleetLocations.capture(world, owner);
        require(target.equals(moved.get(key)), "stable unit key did not follow the ship through its wormhole");
        require(!world.units.containsKey(key), "wormhole transfer incorrectly left the ship in the source system");
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
