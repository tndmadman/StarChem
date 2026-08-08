package com.tndmadman.rts;

import java.awt.Component;

final class ControlGroupInputGate {
    private ControlGroupInputGate() { }

    static boolean blocked(boolean galaxyMapOpen, boolean fittingActive,
                           Component focusOwner, Component gameplaySurface) {
        if (galaxyMapOpen || fittingActive) return true;
        return focusOwner != null && focusOwner != gameplaySurface;
    }
}
