package com.tndmadman.rts;

import java.awt.Component;

/**
 * Compatibility entry point retained for callers compiled against the former policy popup.
 * Player-facing policy management now lives entirely in the centralized manufacturing UI.
 */
final class ProductionPolicyMenu {
    private ProductionPolicyMenu() { }

    static void show(Component invoker, World world, PeerNetwork network, Base base, int x, int y) {
        if (world == null || base == null || StationControls.nonProduction(base.typeId)) return;
        ManufacturingOverlay.openForStation(world, base.id);
        ManufacturingPolicyManager.show(invoker, world, network, base);
    }
}
