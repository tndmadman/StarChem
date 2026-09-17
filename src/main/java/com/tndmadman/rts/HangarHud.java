package com.tndmadman.rts;

import java.awt.*;

/**
 * Retains the notification HUD routing that historically lived with the hangar overlay.
 * The always-visible HANGARS inventory window has been removed; inventory is exposed
 * through the normal station/ship UI instead.
 */
final class HangarHud {
    private final NotificationHud notificationHud = new NotificationHud();

    void draw(Graphics2D g2, World world, int screenW) {
        Rectangle clip = g2.getClipBounds();
        notificationHud.draw(g2, world, clip == null ? 720 : clip.height);
    }

    boolean mousePressed(World world, int x, int y) { return false; }
    void mouseDragged(int x, int y, int screenW, int screenH) { }
    void mouseReleased() { }
}
