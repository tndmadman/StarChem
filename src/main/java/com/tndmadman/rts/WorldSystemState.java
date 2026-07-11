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
    }

    int width() { return definition.width(); }
    int height() { return definition.height(); }
    boolean isStatic() { return lifetime == SystemLifetime.STATIC; }
    boolean isPlayerHome() { return lifetime == SystemLifetime.PLAYER_HOME; }
}
