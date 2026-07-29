package com.tndmadman.rts;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

final class DevMenu {
    private static final int TARGET_Y = 18;
    private static final int FREE_CRAFTING_Y = 62;
    private static final int PRODUCTION_TIMERS_Y = 84;
    private static final int RESOURCE_Y = 126;
    private static final int ROW_H = 19;
    private static final int MAX_DEV_PEERS = 6;
    private static final double SPAWN_AMOUNT = 500.0;

    private final HudWindow window = new HudWindow(18, 205, 292);
    private int targetIndex;
    private int familyIndex;
    private PeerNetwork accessNetwork;

    boolean click(World world, PeerNetwork devAuthorityNetwork, int sx, int sy, boolean canEdit) {
        return click(world, devAuthorityNetwork, sx, sy, canEdit, Integer.MAX_VALUE);
    }

    boolean click(World world, PeerNetwork devAuthorityNetwork, int sx, int sy,
                  boolean canEdit, int screenH) {
        accessNetwork = devAuthorityNetwork;
        if (!window.contains(sx, sy, bodyHeight(), screenH)) return false;
        if (sy <= window.y + 28) return window.press(sx, sy, bodyHeight(), screenH);
        if (window.collapsed || !canEdit) return true;
        int localY = window.contentY(sy);
        if (hit(localY, TARGET_Y)) {
            if (localStationCount(world) > 0) targetIndex++;
            return true;
        }
        if (hit(localY, FREE_CRAFTING_Y)) {
            toggleFreeCrafting(world, devAuthorityNetwork);
            return true;
        }
        if (hit(localY, PRODUCTION_TIMERS_Y)) {
            toggleProductionTimers(world, devAuthorityNetwork);
            return true;
        }
        if (hit(localY, RESOURCE_Y - 22)) {
            familyIndex++;
            return true;
        }

        int devAccessTop = remoteAccessY() - 14;
        if (localY >= devAccessTop) {
            List<DevPeerAccess> peers = remoteAccess(devAuthorityNetwork);
            int row = (localY - devAccessTop) / ROW_H;
            if (row >= 0 && row < peers.size()) {
                DevPeerAccess peer = peers.get(row);
                if (!peer.requested() && !peer.authorized()) {
                    world.status = peer.name() + " has not requested developer access.";
                } else if (devAuthorityNetwork != null) {
                    devAuthorityNetwork.setRemoteDevAccess(peer.playerId(), !peer.authorized());
                }
            }
            return true;
        }

        Base base = target(world);
        if (base == null || localY < RESOURCE_Y - 14) return true;
        int row = (localY - (RESOURCE_Y - 14)) / ROW_H;
        List<Material> visibleMaterials = visibleMaterials();
        if (row >= 0 && row < visibleMaterials.size()) {
            Material material = visibleMaterials.get(row);
            if (devAuthorityNetwork != null) devAuthorityNetwork.devAddHangarResource(PlayerRegistry.localId(), base.id, material, SPAWN_AMOUNT);
            else HangarStore.add(base.inventory, material, SPAWN_AMOUNT);
            world.status = "Dev added " + (int)SPAWN_AMOUNT + " " + material.label + " to " + base.id + " hangar.";
        }
        return true;
    }

    boolean scroll(int sx, int sy, int wheelRotation, int screenH) {
        return window.scroll(sx, sy, wheelRotation, bodyHeight(), screenH);
    }

    void drag(int sx, int sy, int screenW, int screenH) { window.drag(sx, sy, screenW, screenH); }
    void release() { window.release(); }

    void draw(Graphics2D g2, World world, boolean canEdit) {
        draw(g2, world, accessNetwork, canEdit, Integer.MAX_VALUE);
    }

    void draw(Graphics2D g2, World world, boolean canEdit, int screenH) {
        draw(g2, world, accessNetwork, canEdit, screenH);
    }

    void draw(Graphics2D g2, World world, PeerNetwork devAuthorityNetwork, boolean canEdit) {
        draw(g2, world, devAuthorityNetwork, canEdit, Integer.MAX_VALUE);
    }

    void draw(Graphics2D g2, World world, PeerNetwork devAuthorityNetwork,
              boolean canEdit, int screenH) {
        window.draw(g2, "DEV CRAFTING", bodyHeight(), new Color(255, 180, 80, 180), screenH);
        if (window.collapsed) return;
        Graphics2D body = window.bodyGraphics(g2, bodyHeight(), screenH);
        int x = window.x + 12;
        int y = window.bodyY();
        body.setFont(body.getFont().deriveFont(Font.PLAIN, 12f));
        if (!canEdit) {
            body.setColor(new Color(255, 225, 150));
            body.drawString("Host dev approval required", x, y + 16);
            body.dispose();
            return;
        }
        Base base = target(world);
        drawStationLine(body, base, x, y + TARGET_Y);
        drawToggle(body, world.devFreeBuildFor(PlayerRegistry.localId()), "Free crafting", x, y + FREE_CRAFTING_Y);
        drawToggle(body, DevTimerSettings.disabled(world), "Disable production timers", x, y + PRODUCTION_TIMERS_Y);
        if (base == null) {
            body.setColor(new Color(255, 225, 150));
            body.drawString("No local station hangar", x, y + RESOURCE_Y);
        } else {
            body.setColor(new Color(255, 225, 150));
            body.drawString("Add 500 | " + selectedFamily().name() + " (click to cycle)", x, y + RESOURCE_Y - 22);
            int line = y + RESOURCE_Y;
            for (Material material : visibleMaterials()) {
                body.setColor(material.color);
                body.drawString("+500 " + material.label, x + 4, line);
                line += ROW_H;
            }
        }
        drawRemoteAccess(body, devAuthorityNetwork, x, y + remoteAccessY());
        body.dispose();
    }

    private void drawRemoteAccess(Graphics2D g2, PeerNetwork devAuthorityNetwork, int x, int y) {
        List<DevPeerAccess> peers = remoteAccess(devAuthorityNetwork);
        if (peers.isEmpty()) return;
        g2.setColor(Color.WHITE);
        g2.drawString("Remote dev access:", x, y - 22);
        int line = y;
        for (DevPeerAccess peer : peers) {
            g2.setColor(peer.authorized() ? new Color(120, 255, 170) : new Color(255, 225, 150));
            String state = peer.authorized() ? "[x] revoke " : peer.requested() ? "[ ] grant " : "[-] no request ";
            g2.drawString(state + peer.name() + " (" + peer.playerId() + ")", x + 4, line);
            line += ROW_H;
        }
    }

    private List<DevPeerAccess> remoteAccess(PeerNetwork devAuthorityNetwork) {
        if (devAuthorityNetwork == null) return List.of();
        List<DevPeerAccess> out = new ArrayList<>();
        for (DevPeerAccess peer : devAuthorityNetwork.devAccessPeers()) {
            if (peer.local()) continue;
            out.add(peer);
            if (out.size() >= MAX_DEV_PEERS) break;
        }
        return out;
    }

    private void drawStationLine(Graphics2D g2, Base base, int x, int y) {
        g2.setColor(Color.WHITE);
        String target = base == null ? "Station hangar: none" : "Station hangar: " + base.type().name + " " + base.id;
        g2.drawString(target, x, y);
        g2.setColor(new Color(255, 225, 150));
        g2.drawString("Click station line to cycle", x, y + 18);
    }

    private void drawToggle(Graphics2D g2, boolean enabled, String label, int x, int y) {
        g2.setColor(enabled ? new Color(120, 255, 170) : new Color(255, 225, 150));
        g2.drawString((enabled ? "[x]" : "[ ]") + " " + label, x, y);
    }

    private void toggleFreeCrafting(World world, PeerNetwork devAuthorityNetwork) {
        String playerId = PlayerRegistry.localId();
        boolean next = !world.devFreeBuildFor(playerId);
        world.setDevFreeBuild(playerId, next);
        if (devAuthorityNetwork != null) devAuthorityNetwork.devSetFreeCrafting(playerId, next);
        world.status = "Free crafting " + (next ? "enabled." : "disabled.");
    }

    private void toggleProductionTimers(World world, PeerNetwork devAuthorityNetwork) {
        boolean next = !DevTimerSettings.disabled(world);
        DevTimerSettings.configure(world, next);
        if (devAuthorityNetwork != null) {
            devAuthorityNetwork.devAiCommand(PlayerRegistry.localId(),
                    next ? "disableProductionTimers" : "enableProductionTimers");
        }
        world.status = "Production timers " + (next ? "disabled." : "enabled.");
    }

    private boolean hit(int localY, int baseline) { return localY >= baseline - 14 && localY <= baseline + 6; }

    private MaterialFamily selectedFamily() {
        MaterialFamily[] families = MaterialFamily.values();
        return families[Math.floorMod(familyIndex, families.length)];
    }

    private List<Material> visibleMaterials() {
        List<Material> out = new ArrayList<>();
        MaterialFamily family = selectedFamily();
        for (Material material : Material.values()) if (material.family == family) out.add(material);
        return List.copyOf(out);
    }

    private int remoteAccessY() { return RESOURCE_Y + visibleMaterials().size() * ROW_H + 42; }
    private int bodyHeight() { return remoteAccessY() + MAX_DEV_PEERS * ROW_H + 10; }

    private int localStationCount(World world) {
        int count = 0;
        for (Base base : world.bases.values()) if (PlayerRegistry.isLocal(base.playerId)) count++;
        return count;
    }

    private Base target(World world) {
        int count = localStationCount(world);
        if (count == 0) return null;
        int want = Math.floorMod(targetIndex, count);
        int i = 0;
        for (Base base : world.bases.values()) {
            if (!PlayerRegistry.isLocal(base.playerId)) continue;
            if (i++ == want) return base;
        }
        return null;
    }
}
