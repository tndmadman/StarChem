package com.tndmadman.rts;

final class ResourceOrbitSync {
    private ResourceOrbitSync() { }

    static void apply(ResourceNode node, ResourceState state) {
        applyAmounts(node, state);
        node.x = state.x();
        node.y = state.y();
        if (state.orbiting()) {
            node.orbitCenterX = state.orbitCenterX();
            node.orbitCenterY = state.orbitCenterY();
            node.orbitRadius = state.orbitRadius();
            node.orbitAngle = state.orbitAngle();
            node.orbitSpeed = state.orbitSpeed();
            node.orbiting = true;
            if (node.active) node.updateOrbit(0);
            ResourceNetDebug.orbitRecomputed(node, state, Calc.distance(node.x, node.y, state.x(), state.y()));
        } else node.orbiting = false;
    }

    static void applyAmounts(ResourceNode node, ResourceState state) {
        node.amount = state.amount();
        node.active = state.active();
        node.respawnTimer = state.respawnTimer();
    }
}
