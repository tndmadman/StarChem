package com.tndmadman.rts;

import java.awt.Color;

/**
 * Grounded decorative UI palette. Gameplay-semantic colors (player ownership,
 * danger, resources, shields, diplomacy state, etc.) should not be routed here.
 */
final class UiPalette {
    static final Color BACKDROP = new Color(13, 15, 17);
    static final Color BACKDROP_GRID = new Color(42, 46, 48);
    static final Color BACKDROP_GLOW = new Color(132, 101, 59, 46);

    static final Color OVERLAY = new Color(0, 0, 0, 190);
    static final Color PANEL = new Color(24, 27, 29, 242);
    static final Color PANEL_SOFT = new Color(29, 33, 35, 220);
    static final Color BORDER = new Color(101, 108, 108, 180);
    static final Color BORDER_STRONG = new Color(163, 139, 96, 210);

    static final Color CONTROL = new Color(44, 49, 50);
    static final Color CONTROL_HOVER = new Color(61, 66, 65);
    static final Color CONTROL_ACTIVE = new Color(76, 66, 49);
    static final Color CONTROL_DISABLED = new Color(34, 37, 39);

    static final Color TEXT = new Color(232, 230, 221);
    static final Color TEXT_MUTED = new Color(174, 178, 173);
    static final Color TEXT_DIM = new Color(132, 138, 136);

    static final Color ACCENT = new Color(198, 160, 91);
    static final Color ACCENT_SOFT = new Color(145, 121, 78);

    static final Color SCROLL_TRACK = new Color(43, 48, 49, 210);
    static final Color SCROLL_THUMB = new Color(154, 136, 101, 230);

    private UiPalette() { }
}
