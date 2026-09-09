package com.tndmadman.rts;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Authored deterministic station architecture for issue #396. */
final class StationRenderer {
    private static final double TWO_PI = Math.PI * 2.0;
    private static final Color SPACE_SHADOW = new Color(7, 12, 18, 245);
    private static final Color HULL_DARK = new Color(18, 27, 37, 248);
    private static final Color HULL = new Color(42, 57, 70, 248);
    private static final Color PANEL = new Color(69, 84, 98, 245);
    private static final Color EDGE = new Color(135, 156, 171, 225);
    private static final Color WINDOW = new Color(180, 232, 250, 220);
    private static final Color INDUSTRIAL = new Color(232, 164, 76, 225);
    private static final Color LAB = new Color(96, 220, 245, 225);
    private static final Color ECM = new Color(205, 105, 246, 225);

    private StationRenderer() { }

    static void draw(Graphics2D source, Base base, Color playerColor) {
        if (source == null || base == null || playerColor == null) return;
        World world = PlayerRegistry.activeWorld();
        double time = world == null ? 0 : Math.max(0, world.systemTime());
        drawAtTime(source, base, playerColor, time, SelectionRenderPolicy.scale(source));
    }

    /** Package-visible deterministic entry point used by the issue #396 validator. */
    static void drawAtTime(Graphics2D source, Base base, Color playerColor, double time, double scale) {
        if (source == null || base == null || playerColor == null) return;
        Graphics2D g = (Graphics2D) source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(base.x, base.y);

        if (scale < 0.20) {
            drawFarSilhouette(g, base, playerColor);
            g.dispose();
            return;
        }

        boolean detailed = scale >= 0.62;
        switch (base.typeId) {
            case "outpost" -> drawOutpost(g, base, playerColor, time, detailed);
            case "shipyard" -> drawShipyard(g, base, playerColor, time, detailed);
            case "manufacturing" -> drawManufacturing(g, base, playerColor, time, detailed);
            case "laboratory" -> drawLaboratory(g, base, playerColor, time, detailed);
            case RadarTowerRules.TIER_ONE -> drawRadar(g, base, playerColor, time, 1, detailed);
            case RadarTowerRules.TIER_TWO -> drawRadar(g, base, playerColor, time, 2, detailed);
            case RadarTowerRules.TIER_THREE -> drawRadar(g, base, playerColor, time, 3, detailed);
            case "signal_jammer" -> drawJammer(g, base, playerColor, time, detailed);
            case "radar_decoy" -> drawDecoy(g, base, playerColor, time, detailed);
            case IntelWarfareSystem.CONTACT_STATION -> drawSensorContact(g, time);
            default -> drawOutpost(g, base, playerColor, time, detailed);
        }
        g.dispose();
    }

    static double visualExtent(Base base) {
        if (base == null) return 64;
        return switch (base.typeId) {
            case "shipyard" -> 132;
            case "manufacturing" -> 104;
            case "laboratory" -> 96;
            case RadarTowerRules.TIER_ONE -> 72;
            case RadarTowerRules.TIER_TWO -> 92;
            case RadarTowerRules.TIER_THREE -> 116;
            case "signal_jammer" -> 94;
            case "radar_decoy" -> decoyExtent(decoyProfile(base));
            case IntelWarfareSystem.CONTACT_STATION -> 48;
            default -> 76;
        };
    }

    private static double decoyExtent(String profile) {
        return switch (profile) {
            case "shipyard" -> 116;
            case "manufacturing" -> 96;
            case "laboratory" -> 90;
            case RadarTowerRules.TIER_THREE -> 108;
            case RadarTowerRules.TIER_TWO -> 88;
            case RadarTowerRules.TIER_ONE -> 70;
            default -> 76;
        };
    }

    private static String decoyProfile(Base base) {
        IntelWarfareSystem.StructureIntelRule rule = IntelWarfareSystem.rule(base.typeId);
        String profile = rule == null ? null : rule.decoyProfile();
        return profile == null || profile.isBlank() ? RadarTowerRules.TIER_THREE : profile;
    }

    private static void drawFarSilhouette(Graphics2D g, Base base, Color owner) {
        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(4, 8, 12, 220));
        if ("radar_decoy".equals(base.typeId)) {
            drawFarProfile(g, decoyProfile(base));
        } else {
            drawFarProfile(g, base.typeId);
        }
        g.setStroke(new BasicStroke(2.4f));
        g.setColor(accent(owner, 220));
        double r = Math.min(visualExtent(base) * 0.72, 82);
        g.draw(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
    }

    private static void drawFarProfile(Graphics2D g, String typeId) {
        switch (typeId) {
            case "shipyard" -> {
                g.drawLine(-86, -54, 74, -54);
                g.drawLine(-86, 54, 74, 54);
                g.drawLine(-86, -54, -86, 54);
            }
            case "manufacturing" -> {
                g.fillRoundRect(-72, -32, 144, 64, 16, 16);
                g.fillOval(44, -51, 36, 36);
                g.fillOval(44, 15, 36, 36);
            }
            case "laboratory" -> {
                g.fillOval(-24, -24, 48, 48);
                g.fillOval(-75, -20, 40, 40);
                g.fillOval(35, -20, 40, 40);
                g.fillOval(-20, -75, 40, 40);
            }
            case RadarTowerRules.TIER_ONE, RadarTowerRules.TIER_TWO, RadarTowerRules.TIER_THREE -> {
                int tier = RadarTowerRules.TIER_THREE.equals(typeId) ? 3
                        : RadarTowerRules.TIER_TWO.equals(typeId) ? 2 : 1;
                double r = 25 + tier * 13;
                g.draw(new Ellipse2D.Double(-r, -r * 0.58, r * 2, r * 1.16));
                g.drawLine(0, 12, 0, (int)Math.round(-32 - tier * 8));
            }
            case "signal_jammer" -> {
                for (int i = 0; i < 4; i++) {
                    double a = i * Math.PI / 2.0;
                    g.drawLine(0, 0, (int)Math.round(Math.cos(a) * 66), (int)Math.round(Math.sin(a) * 66));
                }
            }
            default -> {
                g.fillRoundRect(-37, -29, 74, 58, 18, 18);
                g.drawLine(-60, 0, 60, 0);
                g.drawLine(0, -52, 0, 52);
            }
        }
    }

    private static void drawOutpost(Graphics2D g, Base base, Color owner, double time, boolean detailed) {
        drawArm(g, -64, 0, -31, 0, 15, owner);
        drawArm(g, 31, 0, 67, 0, 15, owner);
        drawArm(g, 0, 27, 0, 61, 13, owner);

        g.setColor(SPACE_SHADOW);
        g.fill(new RoundRectangle2D.Double(-39, -31, 78, 62, 18, 18));
        g.setColor(HULL);
        g.fill(new RoundRectangle2D.Double(-35, -27, 70, 54, 15, 15));
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(-35, -27, 70, 54, 15, 15));
        g.setColor(accent(owner, 210));
        g.fillRoundRect(-31, -3, 62, 6, 4, 4);

        drawDockPad(g, -69, 0, 0, owner);
        drawDockPad(g, 69, 0, Math.PI, owner);
        drawDockPad(g, 0, 64, -Math.PI / 2, owner);

        g.setColor(PANEL);
        g.fillRoundRect(-17, -17, 34, 34, 10, 10);
        g.setColor(WINDOW);
        g.fillRoundRect(-10, -8, 20, 6, 4, 4);
        g.fillRoundRect(-10, 3, 20, 5, 4, 4);

        Graphics2D antenna = (Graphics2D)g.create();
        antenna.rotate(Math.sin(time * 0.22 + phase(base, 2)) * 0.12, 0, -27);
        antenna.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        antenna.setColor(EDGE);
        antenna.drawLine(0, -27, 0, -55);
        antenna.setStroke(new BasicStroke(1.6f));
        antenna.drawLine(-14, -47, 14, -47);
        antenna.drawLine(-9, -54, 9, -54);
        antenna.setColor(accent(owner, 230));
        antenna.fillOval(-3, -61, 6, 6);
        antenna.dispose();

        if (detailed) {
            for (int i = 0; i < 4; i++) {
                g.setColor(i % 2 == 0 ? PANEL : HULL_DARK);
                g.fillRect(-28 + i * 15, 18, 10, 6);
            }
            drawNavigationLight(g, -66, -10, owner, time, base, 0);
            drawNavigationLight(g, 66, 10, owner, time, base, 1);
            drawNavigationLight(g, 10, 61, owner, time, base, 2);
        }
    }

    private static void drawShipyard(Graphics2D g, Base base, Color owner, double time, boolean detailed) {
        drawTrussBeam(g, -112, -72, 92, -72, owner, detailed);
        drawTrussBeam(g, -112, 72, 92, 72, owner, detailed);
        drawTrussBeam(g, -112, -72, -112, 72, owner, detailed);
        drawTrussBeam(g, 92, -72, 92, -36, owner, detailed);
        drawTrussBeam(g, 92, 36, 92, 72, owner, detailed);

        g.setColor(SPACE_SHADOW);
        g.fillRoundRect(-126, -42, 42, 84, 12, 12);
        g.setColor(HULL);
        g.fillRoundRect(-122, -38, 34, 76, 10, 10);
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(-122, -38, 34, 76, 10, 10);
        g.setColor(accent(owner, 220));
        g.fillRect(-118, -4, 26, 8);

        for (int y : new int[]{-43, 0, 43}) {
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{7f, 6f}, 0));
            g.setColor(new Color(120, 155, 175, 115));
            g.drawLine(-77, y, 78, y);
            g.setColor(new Color(owner.getRed(), owner.getGreen(), owner.getBlue(), 90));
            g.drawLine(-64, y - 9, 65, y - 9);
            g.drawLine(-64, y + 9, 65, y + 9);
            g.setColor(HULL_DARK);
            g.fillRoundRect(-83, y - 13, 10, 26, 5, 5);
        }

        double gantryX = -73 + positiveMod(time * 12 + phase(base, 7) * 9, 150);
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(PANEL);
        g.drawLine((int)Math.round(gantryX), -65, (int)Math.round(gantryX), 65);
        g.setStroke(new BasicStroke(2f));
        g.setColor(INDUSTRIAL);
        g.drawLine((int)Math.round(gantryX - 4), -60, (int)Math.round(gantryX - 4), 60);
        g.drawLine((int)Math.round(gantryX + 4), -60, (int)Math.round(gantryX + 4), 60);
        double craneY = Math.sin(time * 0.55 + phase(base, 8)) * 38;
        g.setColor(WINDOW);
        g.fillRoundRect((int)Math.round(gantryX - 8), (int)Math.round(craneY - 5), 16, 10, 4, 4);

        if (!base.productionQueue.isEmpty()) {
            double glow = 0.45 + 0.35 * Math.sin(time * 3.2 + phase(base, 9));
            g.setColor(new Color(255, 190, 85, clampAlpha(80 + glow * 90)));
            g.fillRoundRect(-61, -12, 125, 24, 10, 10);
            g.setColor(new Color(255, 226, 150, 205));
            g.drawRoundRect(-61, -12, 125, 24, 10, 10);
        }

        if (detailed) {
            for (int x = -91; x <= 76; x += 24) {
                drawNavigationLight(g, x, -73, owner, time, base, 20 + x);
                drawNavigationLight(g, x, 73, owner, time, base, 40 + x);
            }
            g.setColor(PANEL);
            g.fillRect(-128, -66, 12, 22);
            g.fillRect(-128, 44, 12, 22);
            g.setColor(INDUSTRIAL);
            g.drawLine(-122, -61, -122, -49);
            g.drawLine(-122, 49, -122, 61);
        }
    }

    private static void drawManufacturing(Graphics2D g, Base base, Color owner, double time, boolean detailed) {
        g.setColor(SPACE_SHADOW);
        g.fillRoundRect(-86, -35, 154, 70, 14, 14);
        g.setColor(HULL_DARK);
        g.fillRoundRect(-82, -31, 146, 62, 12, 12);
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(-82, -31, 146, 62, 12, 12);

        int[] blockX = {-72, -28, 15};
        int[] blockW = {35, 36, 42};
        for (int i = 0; i < blockX.length; i++) {
            g.setColor(i == 1 ? PANEL : HULL);
            g.fillRoundRect(blockX[i], -23 + i * 5, blockW[i], 46 - i * 8, 8, 8);
            g.setColor(INDUSTRIAL);
            g.drawLine(blockX[i] + 5, -17 + i * 5, blockX[i] + blockW[i] - 5, -17 + i * 5);
        }

        drawReactor(g, 72, -28, 22, time, base, 0);
        drawReactor(g, 72, 28, 22, time, base, 1);
        drawRadiator(g, -45, -54, 70, 18, owner);
        drawRadiator(g, -45, 54, 70, 18, owner);
        g.setColor(accent(owner, 220));
        g.fillRoundRect(-79, -4, 136, 8, 4, 4);

        double cargoX = -66 + positiveMod(time * 8 + phase(base, 21) * 5, 112);
        g.setColor(new Color(255, 205, 105, 230));
        g.fillRoundRect((int)Math.round(cargoX), -7, 14, 14, 4, 4);

        if (detailed) {
            for (int x = -68; x < 49; x += 19) {
                g.setColor(new Color(116, 136, 150, 190));
                g.drawLine(x, 11, x + 9, 11);
                g.drawLine(x + 4, 11, x + 4, 21);
            }
            double vent = 12 + 5 * (0.5 + 0.5 * Math.sin(time * 2.6 + phase(base, 22)));
            g.setColor(new Color(255, 150, 75, 55));
            g.fill(new Ellipse2D.Double(-18 - vent, -47 - vent * 0.5, vent * 2, vent));
            g.fill(new Ellipse2D.Double(20 - vent, 39 - vent * 0.5, vent * 2, vent));
        }
    }

    private static void drawLaboratory(Graphics2D g, Base base, Color owner, double time, boolean detailed) {
        double[][] pods = {{-57, -17}, {57, -17}, {-37, 48}, {37, 48}};
        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(HULL_DARK);
        for (double[] pod : pods) g.drawLine(0, 0, (int)pod[0], (int)pod[1]);
        g.setStroke(new BasicStroke(2f));
        g.setColor(EDGE);
        for (double[] pod : pods) g.drawLine(0, 0, (int)pod[0], (int)pod[1]);

        for (int i = 0; i < pods.length; i++) {
            double px = pods[i][0], py = pods[i][1];
            g.setColor(SPACE_SHADOW);
            g.fill(new Ellipse2D.Double(px - 23, py - 17, 46, 34));
            g.setColor(i % 2 == 0 ? HULL : PANEL);
            g.fill(new Ellipse2D.Double(px - 20, py - 14, 40, 28));
            g.setColor(LAB);
            g.draw(new Ellipse2D.Double(px - 20, py - 14, 40, 28));
            if (detailed) {
                g.setColor(WINDOW);
                g.fill(new Ellipse2D.Double(px - 5, py - 5, 10, 10));
            }
        }

        double pulse = 14 + Math.sin(time * 2.2 + phase(base, 31)) * 2.5;
        g.setColor(new Color(50, 210, 255, 45));
        g.fill(new Ellipse2D.Double(-pulse * 1.7, -pulse * 1.7, pulse * 3.4, pulse * 3.4));
        g.setColor(HULL_DARK);
        g.fill(new Ellipse2D.Double(-22, -22, 44, 44));
        g.setColor(accent(owner, 210));
        g.draw(new Ellipse2D.Double(-22, -22, 44, 44));
        g.setColor(new Color(180, 245, 255, 235));
        g.fill(new Ellipse2D.Double(-8, -8, 16, 16));
        drawDish(g, 0, -70, time * 0.28 + phase(base, 32), 25, LAB, detailed);
        g.setColor(accent(owner, 215));
        g.fillRoundRect(-28, 66, 56, 6, 3, 3);
    }

    private static void drawRadar(Graphics2D g, Base base, Color owner, double time, int tier, boolean detailed) {
        World world = PlayerRegistry.activeWorld();
        IntelWarfareSystem.RadarMode mode = world == null ? IntelWarfareSystem.RadarMode.ACTIVE
                : IntelWarfareSystem.radarMode(world, base);
        double speed = switch (mode) {
            case PASSIVE -> 0.55;
            case ACTIVE -> 1.0;
            case FOCUSED -> 1.65;
        };
        double spin = time * (0.48 + tier * 0.16) * speed + phase(base, 40);
        double platform = 23 + tier * 12;

        g.setColor(SPACE_SHADOW);
        g.fill(new Ellipse2D.Double(-platform - 7, -platform * 0.62 - 7, (platform + 7) * 2, platform * 1.24 + 14));
        g.setColor(HULL_DARK);
        g.fill(new Ellipse2D.Double(-platform, -platform * 0.62, platform * 2, platform * 1.24));
        g.setColor(accent(owner, 215));
        g.setStroke(new BasicStroke(2.3f));
        g.draw(new Ellipse2D.Double(-platform, -platform * 0.62, platform * 2, platform * 1.24));

        int pylonCount = 2 + tier * 2;
        for (int i = 0; i < pylonCount; i++) {
            double a = i * TWO_PI / pylonCount;
            double r = platform + 9 + tier * 6;
            double px = Math.cos(a) * r;
            double py = Math.sin(a) * r * 0.58;
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(PANEL);
            g.drawLine((int)(Math.cos(a) * platform * 0.72), (int)(Math.sin(a) * platform * 0.42), (int)px, (int)py);
            g.setColor(i % 2 == 0 ? LAB : accent(owner, 230));
            g.fill(new Ellipse2D.Double(px - 4.5, py - 4.5, 9, 9));
        }

        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(EDGE);
        g.drawLine(0, 8, 0, -35 - tier * 9);
        g.setStroke(new BasicStroke(2f));
        g.setColor(LAB);
        g.drawLine(0, 8, 0, -37 - tier * 9);
        drawDish(g, 0, -39 - tier * 9, spin, 27 + tier * 7, LAB, detailed);

        if (tier >= 2) drawDish(g, -platform - 10, 9, -spin * 0.62, 18 + tier * 4, LAB, detailed);
        if (tier >= 3) {
            drawDish(g, platform + 12, 11, spin * 0.43 + 1.2, 21, LAB, detailed);
            double ringR = platform + 31;
            Graphics2D ring = (Graphics2D)g.create();
            ring.rotate(-spin * 0.23);
            ring.setColor(new Color(90, 220, 255, 100));
            ring.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{8f, 7f}, 0));
            ring.draw(new Ellipse2D.Double(-ringR, -ringR * 0.5, ringR * 2, ringR));
            for (int i = 0; i < 4; i++) {
                double a = i * Math.PI / 2.0;
                double x = Math.cos(a) * ringR;
                double y = Math.sin(a) * ringR * 0.5;
                ring.setColor(WINDOW);
                ring.fill(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
            }
            ring.dispose();
        }

        if (detailed) {
            double pulse = positiveMod(time * (0.38 + tier * 0.06) * speed + phase(base, 41), 1.0);
            for (int i = 0; i < tier; i++) {
                double p = positiveMod(pulse + i / (double)tier, 1.0);
                double r = platform + 14 + p * (21 + tier * 7);
                g.setColor(new Color(80, 225, 255, clampAlpha(90 * (1 - p))));
                g.setStroke(new BasicStroke((float)Math.max(0.8, 2.0 - p)));
                g.draw(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
            }
        }
    }

    private static void drawJammer(Graphics2D g, Base base, Color owner, double time, boolean detailed) {
        double spin = time * 0.34 + phase(base, 51);
        for (int i = 0; i < 4; i++) {
            double a = spin + i * TWO_PI / 4.0;
            double ex = Math.cos(a) * 68;
            double ey = Math.sin(a) * 68;
            g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(HULL_DARK);
            g.drawLine((int)(Math.cos(a) * 22), (int)(Math.sin(a) * 22), (int)ex, (int)ey);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(ECM);
            g.drawLine((int)(Math.cos(a) * 28), (int)(Math.sin(a) * 28), (int)ex, (int)ey);
            g.setColor(i % 2 == 0 ? accent(owner, 225) : ECM);
            g.fill(new Ellipse2D.Double(ex - 9, ey - 9, 18, 18));
            g.setColor(WINDOW);
            g.fill(new Ellipse2D.Double(ex - 3, ey - 3, 6, 6));
        }

        Polygon core = regularPolygon(0, 0, 30, 8, Math.PI / 8);
        g.setColor(SPACE_SHADOW);
        g.fill(regularPolygon(0, 0, 36, 8, Math.PI / 8));
        g.setColor(HULL);
        g.fill(core);
        g.setColor(accent(owner, 220));
        g.setStroke(new BasicStroke(2f));
        g.draw(core);
        g.setColor(new Color(238, 183, 255, 235));
        g.fill(new Ellipse2D.Double(-7, -7, 14, 14));

        if (detailed) {
            double pulse = positiveMod(time * 0.8 + phase(base, 52), 1.0);
            for (int i = 0; i < 3; i++) {
                double p = positiveMod(pulse + i / 3.0, 1.0);
                double r = 34 + p * 60;
                g.setColor(new Color(210, 90, 255, clampAlpha(100 * (1 - p))));
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{8f, 6f}, (float)(p * 12)));
                g.draw(new Ellipse2D.Double(-r, -r * 0.62, r * 2, r * 1.24));
            }
        }
    }

    private static void drawDecoy(Graphics2D g, Base base, Color owner, double time, boolean detailed) {
        String profile = decoyProfile(base);
        double flicker = 0.78 + Math.sin(time * 6.2 + phase(base, 61)) * 0.13
                + Math.sin(time * 17.0 + phase(base, 62)) * 0.06;
        float alpha = (float)Math.max(0.28, Math.min(0.92, clampAlpha(185 * flicker) / 220.0));
        Graphics2D ghost = (Graphics2D)g.create();
        ghost.setComposite(AlphaComposite.SrcOver.derive(alpha));
        drawDecoyProfile(ghost, profile, owner, time, detailed);
        ghost.dispose();

        if (detailed) {
            double drift = Math.sin(time * 9.0 + phase(base, 63)) * 3.0;
            double extent = visualExtent(base);
            g.setColor(new Color(255, 181, 94, 100));
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{7f, 5f}, 0));
            g.draw(new Ellipse2D.Double(-extent * 0.68 + drift, -extent * 0.42, extent * 1.36, extent * 0.84));
            for (int i = 0; i < 4; i++) {
                double a = time * 0.45 + phase(base, 64 + i) + i * Math.PI / 2;
                double r = extent * 0.62;
                double x = Math.cos(a) * r;
                double y = Math.sin(a) * r * 0.55;
                g.setColor(new Color(255, 205, 118, 190));
                g.fill(new Ellipse2D.Double(x - 2.5, y - 2.5, 5, 5));
            }
        }
    }

    private static void drawDecoyProfile(Graphics2D g, String profile, Color owner, double time, boolean detailed) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(63, 72, 80, 220));
        switch (profile) {
            case "shipyard" -> {
                g.drawLine(-96, -61, 80, -61);
                g.drawLine(-96, 61, 80, 61);
                g.drawLine(-96, -61, -96, 61);
                g.setColor(accent(owner, 185));
                g.drawLine(-73, 0, 66, 0);
            }
            case "manufacturing" -> {
                g.fillRoundRect(-70, -30, 126, 60, 12, 12);
                g.fillOval(46, -46, 34, 34);
                g.fillOval(46, 12, 34, 34);
                g.setColor(accent(owner, 180));
                g.drawLine(-60, 0, 52, 0);
            }
            case "laboratory" -> {
                for (int i = 0; i < 4; i++) {
                    double a = Math.PI / 4 + i * Math.PI / 2;
                    int x = (int)Math.round(Math.cos(a) * 55);
                    int y = (int)Math.round(Math.sin(a) * 55);
                    g.drawLine(0, 0, x, y);
                    g.fillOval(x - 14, y - 10, 28, 20);
                }
                g.setColor(accent(owner, 190));
                g.fillOval(-12, -12, 24, 24);
            }
            case RadarTowerRules.TIER_ONE, RadarTowerRules.TIER_TWO, RadarTowerRules.TIER_THREE -> {
                int tier = RadarTowerRules.TIER_THREE.equals(profile) ? 3
                        : RadarTowerRules.TIER_TWO.equals(profile) ? 2 : 1;
                double r = 31 + tier * 11;
                g.draw(new Ellipse2D.Double(-r, -r * 0.55, r * 2, r * 1.1));
                g.drawLine(0, 0, 0, (int)(-38 - tier * 8));
                g.setColor(accent(owner, 190));
                double a = time * 0.35;
                g.drawLine(0, (int)(-38 - tier * 8), (int)(Math.cos(a) * 34), (int)(-38 - tier * 8 + Math.sin(a) * 18));
            }
            default -> {
                g.fillRoundRect(-37, -28, 74, 56, 15, 15);
                g.drawLine(-62, 0, 62, 0);
                g.drawLine(0, -48, 0, 52);
                g.setColor(accent(owner, 190));
                g.fillOval(-8, -8, 16, 16);
            }
        }
        if (detailed) {
            g.setColor(new Color(255, 193, 105, 140));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{6f, 5f}, 0));
            g.draw(new Ellipse2D.Double(-44, -25, 88, 50));
        }
        g.setStroke(old);
    }

    private static void drawSensorContact(Graphics2D g, double time) {
        double pulse = positiveMod(time * 0.75, 1.0);
        double radius = 22 + pulse * 19;
        g.setColor(new Color(175, 190, 205, clampAlpha(145 * (1 - pulse))));
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{5f, 5f}, 0));
        g.draw(new Ellipse2D.Double(-radius, -radius, radius * 2, radius * 2));
        g.setColor(new Color(118, 132, 145, 205));
        g.fill(new Ellipse2D.Double(-17, -17, 34, 34));
        g.setColor(new Color(230, 240, 248, 235));
        g.setStroke(new BasicStroke(3f));
        g.draw(new Arc2D.Double(-8, -10, 16, 16, 20, 215, Arc2D.OPEN));
        g.fill(new Ellipse2D.Double(-2.5, 8, 5, 5));
    }

    private static void drawArm(Graphics2D g, int x1, int y1, int x2, int y2, int width, Color owner) {
        g.setStroke(new BasicStroke(width + 6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(SPACE_SHADOW);
        g.drawLine(x1, y1, x2, y2);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(HULL);
        g.drawLine(x1, y1, x2, y2);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(accent(owner, 185));
        g.drawLine(x1, y1, x2, y2);
    }

    private static void drawDockPad(Graphics2D g, double x, double y, double angle, Color owner) {
        Graphics2D d = (Graphics2D)g.create();
        d.translate(x, y);
        d.rotate(angle);
        d.setColor(SPACE_SHADOW);
        d.fillRoundRect(-13, -12, 26, 24, 8, 8);
        d.setColor(PANEL);
        d.fillRoundRect(-10, -9, 20, 18, 6, 6);
        d.setColor(accent(owner, 220));
        d.drawLine(-7, 0, 8, 0);
        d.setColor(WINDOW);
        d.fillOval(5, -3, 6, 6);
        d.dispose();
    }

    private static void drawTrussBeam(Graphics2D g, int x1, int y1, int x2, int y2, Color owner, boolean detailed) {
        g.setStroke(new BasicStroke(12f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(SPACE_SHADOW);
        g.drawLine(x1, y1, x2, y2);
        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(PANEL);
        g.drawLine(x1, y1, x2, y2);
        g.setStroke(new BasicStroke(1.8f));
        g.setColor(accent(owner, 150));
        g.drawLine(x1, y1, x2, y2);
        if (!detailed) return;
        double dx = x2 - x1, dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 20) return;
        double nx = -dy / length * 5.5;
        double ny = dx / length * 5.5;
        int segments = Math.max(1, (int)(length / 24));
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= segments; i++) {
            double p = i / (double)segments;
            double x = x1 + dx * p;
            double y = y1 + dy * p;
            g.drawLine((int)Math.round(x - nx), (int)Math.round(y - ny), (int)Math.round(x + nx), (int)Math.round(y + ny));
        }
    }

    private static void drawReactor(Graphics2D g, double x, double y, double radius, double time, Base base, int index) {
        g.setColor(SPACE_SHADOW);
        g.fill(new Ellipse2D.Double(x - radius - 5, y - radius - 5, radius * 2 + 10, radius * 2 + 10));
        g.setColor(HULL);
        g.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
        g.setColor(INDUSTRIAL);
        g.setStroke(new BasicStroke(2f));
        g.draw(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
        double glow = radius * (0.35 + 0.07 * Math.sin(time * 2.8 + phase(base, 70 + index)));
        g.setColor(new Color(255, 190, 95, 90));
        g.fill(new Ellipse2D.Double(x - glow, y - glow, glow * 2, glow * 2));
        g.setColor(new Color(255, 225, 165, 225));
        g.fill(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
    }

    private static void drawRadiator(Graphics2D g, int cx, int cy, int width, int height, Color owner) {
        int x = cx - width / 2;
        int y = cy - height / 2;
        g.setColor(SPACE_SHADOW);
        g.fillRoundRect(x - 3, y - 3, width + 6, height + 6, 5, 5);
        g.setColor(new Color(46, 59, 70, 245));
        g.fillRect(x, y, width, height);
        g.setColor(EDGE);
        g.drawRect(x, y, width, height);
        g.setColor(accent(owner, 110));
        for (int i = 7; i < width; i += 10) g.drawLine(x + i, y + 2, x + i, y + height - 2);
    }

    private static void drawDish(Graphics2D g, double x, double y, double angle, double size, Color glow, boolean detailed) {
        Graphics2D dish = (Graphics2D)g.create();
        dish.translate(x, y);
        dish.rotate(angle);
        double h = size * 0.45;
        Path2D bowl = new Path2D.Double();
        bowl.moveTo(-size * 0.5, 0);
        bowl.quadTo(0, h, size * 0.5, 0);
        bowl.quadTo(0, h * 0.42, -size * 0.5, 0);
        bowl.closePath();
        dish.setColor(HULL);
        dish.fill(bowl);
        dish.setColor(glow);
        dish.setStroke(new BasicStroke(1.8f));
        dish.draw(bowl);
        dish.setColor(EDGE);
        dish.setStroke(new BasicStroke(2.5f));
        dish.drawLine(0, 0, (int)Math.round(size * 0.58), 0);
        dish.setColor(WINDOW);
        dish.fill(new Ellipse2D.Double(size * 0.53 - 3, -3, 6, 6));
        if (detailed) {
            dish.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 42));
            dish.fill(new Arc2D.Double(-size * 0.8, -size * 0.8, size * 1.6, size * 1.6, -17, 34, Arc2D.PIE));
        }
        dish.dispose();
    }

    private static void drawNavigationLight(Graphics2D g, double x, double y, Color owner, double time, Base base, int salt) {
        double cycle = positiveMod(time + phase(base, salt) / TWO_PI * 2.2, 1.35);
        boolean on = cycle < 0.28;
        g.setColor(on ? WINDOW : new Color(80, 103, 116, 130));
        g.fill(new Ellipse2D.Double(x - 2.4, y - 2.4, 4.8, 4.8));
        if (on) {
            g.setColor(new Color(owner.getRed(), owner.getGreen(), owner.getBlue(), 70));
            g.fill(new Ellipse2D.Double(x - 5, y - 5, 10, 10));
        }
    }

    private static Polygon regularPolygon(double cx, double cy, double radius, int sides, double rotation) {
        Polygon polygon = new Polygon();
        for (int i = 0; i < sides; i++) {
            double a = rotation + i * TWO_PI / sides;
            polygon.addPoint((int)Math.round(cx + Math.cos(a) * radius), (int)Math.round(cy + Math.sin(a) * radius));
        }
        return polygon;
    }

    private static Color accent(Color owner, int alpha) {
        return new Color(owner.getRed(), owner.getGreen(), owner.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static int clampAlpha(double alpha) {
        return (int)Math.max(0, Math.min(255, Math.round(alpha)));
    }

    private static double positiveMod(double value, double modulus) {
        if (modulus <= 0) return 0;
        double out = value % modulus;
        return out < 0 ? out + modulus : out;
    }

    private static double phase(Base base, int salt) {
        String key = (base.id == null ? "" : base.id) + "|" + base.typeId + "|" + salt;
        return Math.floorMod(key.hashCode(), 10_000) / 10_000.0 * TWO_PI;
    }
}
