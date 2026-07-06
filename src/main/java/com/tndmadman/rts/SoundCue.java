package com.tndmadman.rts;

/**
 * Named game events that can be rendered by the procedural audio synthesizer.
 * These are intentionally semantic, not asset names, so the game can stay
 * asset-free in the same spirit as its generated ship graphics.
 */
enum SoundCue {
    SELECT,
    MOVE_ORDER,
    HARVEST_ORDER,
    ATTACK_ORDER,
    BUILD_SHIP,
    PACKAGE_LOAD,
    PLACE_STATION,
    CRAFT_ITEM,
    ERROR,
    RESOURCE_DEPLETED,
    MUTE_OFF
}
