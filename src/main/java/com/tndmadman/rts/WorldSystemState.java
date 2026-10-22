package com.tndmadman.rts;

import java.util.*;

final class WorldSystemState {
    final String id;
    final String templateId;
    final StarSystemDefinition definition;
    final SystemLifetime lifetime;
    final SystemControlState control;
    final SystemControlPoint controlPoint;
    final CelestialSystem celestials;
    final List<ResourceNode> resources = new ArrayList<>();
    final Map<String, Unit> units = new LinkedHashMap<>();
    final Map<String, Base> bases = new LinkedHashMap<>();
    final List<ProjectileShot> shots = new ArrayList<>();
    final List<WorldItem> items = new ArrayList<>();
    final List<WormholeGate> wormholes = new ArrayList<>();
    final Map<String, CelestialBodyState> celestialBodies = new LinkedHashMap<>();
    double systemTime;

    WorldSystemState(String id, StarSystemDefinition definition, CelestialSystem celestials) {
        this(id, definition == null ? "" : definition.id(), definition, SystemLifetime.STATIC, "", celestials);
    }

    WorldSystemState(String id, String templateId, StarSystemDefinition definition, SystemLifetime lifetime,
                     String initialControllerId, CelestialSystem celestials) {
        this.id = id;
        this.templateId = templateId == null || templateId.isBlank() ? id : templateId;
        this.definition = definition;
        this.lifetime = lifetime == null ? SystemLifetime.STATIC : lifetime;
        this.control = new SystemControlState(this.lifetime, initialControllerId);
        this.controlPoint = new SystemControlPoint(definition);
        this.celestials = celestials;
        CelestialGameplaySystem.registerState(this);
        CelestialMoonInheritance.register(this);
        CelestialExtractionSystem.register(this);
        CelestialResourceBridge.register(this);
    }

    /** A state stays registered while its owning galaxy is live, including system switches. */
    void dispose() {
        CelestialResourceBridge.unregister(this);
        CelestialExtractionSystem.unregister(this);
        CelestialMoonInheritance.unregister(this);
        CelestialGameplaySystem.unregisterState(this);
    }

    boolean registeredForTesting() {
        return CelestialResourceBridge.registeredForTesting(this)
                && CelestialExtractionSystem.registeredForTesting(this)
                && CelestialMoonInheritance.registeredForTesting(this)
                && CelestialGameplaySystem.registeredForTesting(this);
    }

    int width() { return definition.width(); }
    int height() { return definition.height(); }
    boolean isStatic() { return lifetime == SystemLifetime.STATIC; }
    boolean isPlayerHome() { return lifetime == SystemLifetime.PLAYER_HOME; }
}
