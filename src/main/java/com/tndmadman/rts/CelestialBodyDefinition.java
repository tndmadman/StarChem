package com.tndmadman.rts;

import java.awt.Color;

record CelestialBodyDefinition(
        String id,
        String name,
        String parentId,
        double orbitRadius,
        double radius,
        double orbitSpeed,
        Color color
) { }
