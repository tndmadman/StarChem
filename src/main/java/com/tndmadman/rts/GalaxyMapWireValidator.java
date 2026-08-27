package com.tndmadman.rts;

import java.util.Set;

public final class GalaxyMapWireValidator {
    private GalaxyMapWireValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        Issue298EmpireOverviewValidator.validateOrThrow();
        System.out.println("StarChem galaxy map wire validation passed.");
    }

    static void validateOrThrow() {
        GalaxyRuntimeOptions.configureCopies(2);
        World world = new World("Wire Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        GalaxyMapSnapshot original = world.authoritativeGalaxyMapSnapshot();
        String encoded = GalaxyMapWire.encode(2, original);
        GalaxyMapWire.Decoded decoded = GalaxyMapWire.decode(encoded);
        require(decoded.copiesPerTemplate() == 2, "galaxy copy count did not survive wire encoding");
        require(decoded.snapshot().systems().size() == original.systems().size(), "galaxy system count changed on wire");
        require(decoded.snapshot().links().size() == original.links().size(), "galaxy link count changed on wire");
        GalaxyMapSystem originalCorsair = system(original, StarSystems.CORSAIR_SYSTEM_ID);
        GalaxyMapSystem decodedCorsair = system(decoded.snapshot(), StarSystems.CORSAIR_SYSTEM_ID);
        require(originalCorsair.controllerId().equals(decodedCorsair.controllerId()), "controller identity changed on wire");
        require(originalCorsair.controlColorRgb() == decodedCorsair.controlColorRgb(), "controller color changed on wire");
        expectRejected("GALAXY|3|bad", "out-of-range copy count was accepted");
        expectRejected("GALAXY|1||S,bad", "malformed system row was accepted");
        expectRejected("GALAXY|1||L,YQ,YQ", "self-link was accepted");
        GalaxyRuntimeOptions.configureCopies(1);
    }

    private static void expectRejected(String packet, String message) {
        try { GalaxyMapWire.decode(packet); }
        catch (SnapshotDecodeException expected) { return; }
        throw new IllegalStateException(message);
    }

    private static GalaxyMapSystem system(GalaxyMapSnapshot snapshot, String id) {
        for (GalaxyMapSystem system : snapshot.systems()) if (id.equals(system.id())) return system;
        throw new IllegalStateException("Missing system " + id);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
