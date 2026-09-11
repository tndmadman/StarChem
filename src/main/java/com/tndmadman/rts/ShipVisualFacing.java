package com.tndmadman.rts;

/**
 * Converts the authoritative movement heading into the authored ship-art heading.
 *
 * Authored hulls, hardpoints, and engines use local -X as the bow and +X as the stern,
 * while Unit.heading points in the direction of travel (+X at heading 0). Therefore all
 * ship-local artwork must be rotated 180 degrees relative to the movement heading.
 */
final class ShipVisualFacing {
    private ShipVisualFacing() { }

    static double heading(double movementHeading) {
        double safe = Double.isFinite(movementHeading) ? movementHeading : 0.0;
        return safe + Math.PI;
    }
}
