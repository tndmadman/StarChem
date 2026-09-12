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
    /** Below this zoom, station micro-architecture is sub-pixel/noisy; preserve authored silhouette instead. */
    static final double FAR_LOD_SCALE = 0.55;
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

    private enum RenderLayer { ALL, STATIC, DYNAMIC }

    static void draw(Graphics2D source, Base base, Color playerColor) {
        if (source == null || base == null || playerColor == null) return;
        World world = PlayerRegistry.activeWorld();
        double time = world == null ? 0 : Math.max(0, world.systemTime());
        double scale = SelectionRenderPolicy.scale(source);

        if (scale < FAR_LOD_SCALE) {
            if (!StationSpriteCache.drawStatic(source, base, playerColor, StationSpriteCache.Lod.FAR)) {
                Graphics2D g = (Graphics2D)source.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.translate(base.x, base.y);
                drawFarSilhouette(g, base, playerColor);
                g.dispose();
            }
            return;
        }

        boolean detailed = scale >= 0.62;
        StationSpriteCache.Lod lod = detailed ? StationSpriteCache.Lod.DETAILED : StationSpriteCache.Lod.MEDIUM;
        if (!StationSpriteCache.drawStatic(source, base, playerColor, lod)) {
            Graphics2D g = (Graphics2D)source.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(base.x, base.y);
            drawStationLayer(g, base, playerColor, 0, detailed, RenderLayer.STATIC);
            g.dispose();
        }

        Graphics2D dynamic = (Graphics2D)source.create();
        dynamic.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        dynamic.translate(base.x, base.y);
        drawStationLayer(dynamic, base, playerColor, time, detailed, RenderLayer.DYNAMIC);
        dynamic.dispose();
    }

    /** Package-visible deterministic entry point used by the issue #396 validator. */
    static void drawAtTime(Graphics2D source, Base base, Color playerColor, double time, double scale) {
        if (source == null || base == null || playerColor == null) return;
        Graphics2D g = (Graphics2D) source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(base.x, base.y);

        if (scale < FAR_LOD_SCALE) {
            drawFarSilhouette(g, base, playerColor);
            g.dispose();
            return;
        }

        drawStationLayer(g, base, playerColor, time, scale >= 0.62, RenderLayer.ALL);
        g.dispose();
    }

    /** Renders only deterministic station structure at the origin for the RAM sprite atlas. */
    static void drawStaticAtOrigin(Graphics2D g, Base base, Color playerColor, boolean detailed) {
        if (g == null || base == null || playerColor == null) return;
        drawStationLayer(g, base, playerColor, 0, detailed, RenderLayer.STATIC);
    }

    /** Renders the strategic-zoom silhouette at the origin for the RAM sprite atlas. */
    static void drawFarStaticAtOrigin(Graphics2D g, Base base, Color playerColor) {
        if (g == null || base == null || playerColor == null) return;
        drawFarSilhouette(g, base, playerColor);
    }

    private static void drawStationLayer(Graphics2D g, Base base, Color playerColor, double time,
                                         boolean detailed, RenderLayer layer) {
        switch (base.typeId) {
            case "outpost" -> drawOutpost(g, base, playerColor, time, detailed, layer);
            case "shipyard" -> drawShipyard(g, base, playerColor, time, detailed, layer);
            case "manufacturing" -> drawManufacturing(g, base, playerColor, time, detailed, layer);
            case "laboratory" -> drawLaboratory(g, base, playerColor, time, detailed, layer);
            case RadarTowerRules.TIER_ONE -> drawRadar(g, base, playerColor, time, 1, detailed, layer);
            case RadarTowerRules.TIER_TWO -> drawRadar(g, base, playerColor, time, 2, detailed, layer);
            case RadarTowerRules.TIER_THREE -> drawRadar(g, base, playerColor, time, 3, detailed, layer);
            case "signal_jammer" -> drawJammer(g, base, playerColor, time, detailed, layer);
            case "radar_decoy" -> drawDecoy(g, base, playerColor, time, detailed, layer);
            case IntelWarfareSystem.CONTACT_STATION -> drawSensorContact(g, time, layer);
            default -> drawOutpost(g, base, playerColor, time, detailed, layer);
        }
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
        if ("radar_decoy".equals(base.typeId)) drawFarProfile(g, decoyProfile(base));
        else drawFarProfile(g, base.typeId);
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

    private static void drawOutpost(Graphics2D g, Base base, Color owner, double time,
                                    boolean detailed, RenderLayer layer) {
        if (layer != RenderLayer.DYNAMIC) {
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

            if (detailed) {
                for (int i = 0; i < 4; i++) {
                    g.setColor(i % 2 == 0 ? PANEL : HULL_DARK);
                    g.fillRect(-28 + i * 15, 18, 10, 6);
                }
            }
        }

        if (layer != RenderLayer.STATIC) {
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
                drawNavigationLight(g, -66, -10, owner, time, base, 0);
                drawNavigationLight(g, 66, 10, owner, time, base, 1);
                drawNavigationLight(g, 10, 61, owner, time, base, 2);
            }
        }
    }

    private static void drawShipyard(Graphics2D g, Base base, Color owner, double time,
                                     boolean detailed, RenderLayer layer) {
        if (layer != RenderLayer.DYNAMIC) {
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

            if (detailed) {
                g.setColor(PANEL);
                g.fillRect(-128, -66, 12, 22);
                g.fillRect(-128, 44, 12, 22);
                g.setColor(INDUSTRIAL);
                g.drawLine(-122, -61, -122, -49);
                g.drawLine(-122, 49, -122, 61);
            }
        }

        if (layer != RenderLayer.STATIC) {
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
            }
        }
    }

    private static void drawManufacturing(Graphics2D g, Base base, Color owner, double time,
                                          boolean detailed, RenderLayer layer) {
        if (layer != RenderLayer.DYNAMIC) {
            g.setColor(SPACE_SHADOW);
            g.fillRoundRect(-80, -48, 160, 96, 20, 20);
            g.setColor(HULL);
            g.fillRoundRect(-75, -43, 150, 86, 17, 17);
            g.setColor(PANEL);
            g.fillRoundRect(-58, -29, 116, 58, 13, 13);
            g.setColor(EDGE);
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(-75, -43, 150, 86, 17, 17);
            g.setColor(accent(owner, 210));
            g.fillRect(-70, -4, 140, 8);

            for (int x : new int[]{-47, -15, 17, 49}) {
                g.setColor(HULL_DARK);
                g.fillRoundRect(x - 10, -32, 20, 64, 7, 7);
                g.setColor(INDUSTRIAL);
                g.drawLine(x, -25, x, 25);
            }
            g.setColor(INDUSTRIAL);
            g.fillOval(42, -51, 36, 36);
            g.fillOval(42, 15, 36, 36);
            g.setColor(HULL_DARK);
            g.fillOval(50, -43, 20, 20);
            g.fillOval(50, 23, 20, 20);

            if (detailed) {
                g.setColor(WINDOW);
                for (int x = -44; x <= 36; x += 20) g.fillRoundRect(x, -8, 10, 4, 3, 3);
            }
        }

        if (layer != RenderLayer.STATIC && detailed) {
            for (int x = -60; x <= 56; x += 22) {
                drawNavigationLight(g, x, -42, owner, time, base, 60 + x);
                drawNavigationLight(g, x, 42, owner, time, base, 80 + x);
            }
        }
    }

    private static void drawLaboratory(Graphics2D g, Base base, Color owner, double time,
                                       boolean detailed, RenderLayer layer) {
        if (layer != RenderLayer.DYNAMIC) {
            g.setColor(SPACE_SHADOW);
            g.fillOval(-38, -38, 76, 76);
            g.setColor(HULL);
            g.fillOval(-33, -33, 66, 66);
            g.setColor(PANEL);
            g.fillOval(-20, -20, 40, 40);
            g.setColor(EDGE);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(-33, -33, 66, 66);
            g.setColor(LAB);
            g.fillOval(-9, -9, 18, 18);

            drawPod(g, -60, 0, owner);
            drawPod(g, 60, 0, owner);
            drawPod(g, 0, -60, owner);
            g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(HULL_DARK);
            g.drawLine(-32, 0, -48, 0);
            g.drawLine(32, 0, 48, 0);
            g.drawLine(0, -32, 0, -48);

            if (detailed) {
                g.setColor(WINDOW);
                for (int i = 0; i < 8; i++) {
                    double a = i * TWO_PI / 8.0;
                    g.fillOval((int)Math.round(Math.cos(a) * 27) - 2,
                            (int)Math.round(Math.sin(a) * 27) - 2, 4, 4);
                }
            }
        }

        if (layer != RenderLayer.STATIC) {
            double sweep = positiveMod(time * 18 + phase(base, 11) * 12, 360);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(LAB.getRed(), LAB.getGreen(), LAB.getBlue(), 120));
            g.draw(new Arc2D.Double(-42, -42, 84, 84, sweep, 68, Arc2D.OPEN));

            if (detailed) {
                drawNavigationLight(g, -60, 0, owner, time, base, 100);
                drawNavigationLight(g, 60, 0, owner, time, base, 101);
                drawNavigationLight(g, 0, -60, owner, time, base, 102);
            }
        }
    }

    private static void drawRadar(Graphics2D g, Base base, Color owner, double time, int tier,
                                  boolean detailed, RenderLayer layer) {
        double ring = 25 + tier * 13;
        double dishY = -35 - tier * 8;
        if (layer != RenderLayer.DYNAMIC) {
            g.setColor(SPACE_SHADOW);
            g.fillOval((int)-ring - 12, -21, (int)(ring * 2 + 24), 42);
            g.setColor(HULL);
            g.fillOval((int)-ring - 6, -16, (int)(ring * 2 + 12), 32);
            g.setColor(EDGE);
            g.setStroke(new BasicStroke(2f));
            g.drawOval((int)-ring - 6, -16, (int)(ring * 2 + 12), 32);
            g.setColor(accent(owner, 210));
            g.fillRect((int)-ring, -3, (int)(ring * 2), 6);

            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(PANEL);
            g.drawLine(0, 8, 0, (int)Math.round(-32 - tier * 8));

            if (tier >= 2) {
                g.setStroke(new BasicStroke(3f));
                g.setColor(HULL_DARK);
                g.drawLine((int)-ring, 0, (int)-ring - 14, 25);
                g.drawLine((int)ring, 0, (int)ring + 14, 25);
            }
            if (tier >= 3) {
                g.setColor(PANEL);
                g.fillRoundRect((int)-ring - 20, 18, 28, 18, 6, 6);
                g.fillRoundRect((int)ring - 8, 18, 28, 18, 6, 6);
            }
        }

        if (layer != RenderLayer.STATIC) {
            double angle = time * (0.16 + tier * 0.035) + phase(base, 13);
            Graphics2D dish = (Graphics2D)g.create();
            dish.translate(0, dishY);
            dish.rotate(angle);
            dish.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            dish.setColor(HULL_DARK);
            dish.drawLine(-((int)ring) / 2, 0, ((int)ring) / 2, 0);
            dish.setColor(EDGE);
            dish.setStroke(new BasicStroke(2f));
            dish.draw(new Arc2D.Double(-ring * 0.55, -ring * 0.15, ring * 1.1, ring * 0.55, 8, 164, Arc2D.OPEN));
            dish.setColor(LAB);
            dish.fillOval(-3, -3, 6, 6);
            dish.dispose();

            if (detailed) {
                for (int i = 0; i < tier + 2; i++) {
                    double a = Math.PI + i * Math.PI / (tier + 1.0);
                    drawNavigationLight(g, Math.cos(a) * ring, Math.sin(a) * 14,
                            owner, time, base, 120 + i);
                }
            }
        }
    }

    private static void drawJammer(Graphics2D g, Base base, Color owner, double time,
                                   boolean detailed, RenderLayer layer) {
        if (layer != RenderLayer.DYNAMIC) {
            g.setColor(SPACE_SHADOW);
            g.fillOval(-39, -39, 78, 78);
            g.setColor(HULL);
            g.fillOval(-34, -34, 68, 68);
            g.setColor(ECM);
            g.fillOval(-12, -12, 24, 24);
            g.setColor(accent(owner, 195));
            g.setStroke(new BasicStroke(3f));
            g.drawOval(-28, -28, 56, 56);

            for (int i = 0; i < 4; i++) {
                double a = i * Math.PI / 2.0 + 0.15;
                int ex = (int)Math.round(Math.cos(a) * 68);
                int ey = (int)Math.round(Math.sin(a) * 68);
                g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(HULL_DARK);
                g.drawLine((int)Math.round(Math.cos(a) * 30), (int)Math.round(Math.sin(a) * 30), ex, ey);
                g.setColor(ECM);
                g.fillOval(ex - 6, ey - 6, 12, 12);
            }

            if (detailed) {
                g.setColor(WINDOW);
                for (int i = 0; i < 6; i++) {
                    double a = i * TWO_PI / 6.0;
                    g.fillOval((int)Math.round(Math.cos(a) * 24) - 2,
                            (int)Math.round(Math.sin(a) * 24) - 2, 4, 4);
                }
            }
        }

        if (layer != RenderLayer.STATIC) {
            double pulse = 40 + positiveMod(time * 22 + phase(base, 15) * 5, 38);
            g.setColor(new Color(ECM.getRed(), ECM.getGreen(), ECM.getBlue(), 65));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Ellipse2D.Double(-pulse, -pulse, pulse * 2, pulse * 2));
        }
    }

    private static void drawDecoy(Graphics2D g, Base base, Color owner, double time,
                                  boolean detailed, RenderLayer layer) {
        String profile = decoyProfile(base);
        Color decoyOwner = muted(owner);
        switch (profile) {
            case "shipyard" -> drawShipyard(g, base, decoyOwner, time, detailed, layer);
            case "manufacturing" -> drawManufacturing(g, base, decoyOwner, time, detailed, layer);
            case "laboratory" -> drawLaboratory(g, base, decoyOwner, time, detailed, layer);
            case RadarTowerRules.TIER_ONE -> drawRadar(g, base, decoyOwner, time, 1, detailed, layer);
            case RadarTowerRules.TIER_TWO -> drawRadar(g, base, decoyOwner, time, 2, detailed, layer);
            case RadarTowerRules.TIER_THREE -> drawRadar(g, base, decoyOwner, time, 3, detailed, layer);
            default -> drawOutpost(g, base, decoyOwner, time, detailed, layer);
        }
        if (layer != RenderLayer.STATIC) {
            g.setColor(new Color(ECM.getRed(), ECM.getGreen(), ECM.getBlue(), detailed ? 115 : 75));
            g.setStroke(new BasicStroke(detailed ? 1.6f : 1.2f));
            double r = Math.min(visualExtent(base) * 0.62, 72);
            double offset = Math.sin(time * 0.9 + phase(base, 17)) * 3;
            g.draw(new Ellipse2D.Double(-r + offset, -r - offset, r * 2, r * 2));
        }
    }

    private static void drawSensorContact(Graphics2D g, double time, RenderLayer layer) {
        if (layer != RenderLayer.DYNAMIC) {
            g.setColor(new Color(175, 205, 220, 170));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Ellipse2D.Double(-22, -22, 44, 44));
            g.drawLine(-12, 0, 12, 0);
            g.drawLine(0, -12, 0, 12);
        }
        if (layer != RenderLayer.STATIC) {
            double pulse = 20 + 4 * Math.sin(time * 2.0);
            g.setColor(new Color(120, 160, 185, 90));
            g.fill(new Ellipse2D.Double(-pulse, -pulse, pulse * 2, pulse * 2));
        }
    }

    private static void drawArm(Graphics2D g, double x1, double y1, double x2, double y2, double width, Color owner) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke((float)(width + 5), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(SPACE_SHADOW);
        g.drawLine((int)x1, (int)y1, (int)x2, (int)y2);
        g.setStroke(new BasicStroke((float)width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(HULL_DARK);
        g.drawLine((int)x1, (int)y1, (int)x2, (int)y2);
        g.setStroke(new BasicStroke(2f));
        g.setColor(accent(owner, 175));
        g.drawLine((int)x1, (int)y1, (int)x2, (int)y2);
        g.setStroke(old);
    }

    private static void drawDockPad(Graphics2D g, double x, double y, double rotation, Color owner) {
        Graphics2D pad = (Graphics2D)g.create();
        pad.translate(x, y);
        pad.rotate(rotation);
        pad.setColor(SPACE_SHADOW);
        pad.fillRoundRect(-14, -10, 28, 20, 7, 7);
        pad.setColor(HULL);
        pad.fillRoundRect(-11, -7, 22, 14, 5, 5);
        pad.setColor(accent(owner, 210));
        pad.fillRect(-8, -2, 16, 4);
        pad.dispose();
    }

    private static void drawPod(Graphics2D g, double x, double y, Color owner) {
        g.setColor(SPACE_SHADOW);
        g.fillOval((int)x - 22, (int)y - 22, 44, 44);
        g.setColor(HULL);
        g.fillOval((int)x - 18, (int)y - 18, 36, 36);
        g.setColor(accent(owner, 170));
        g.setStroke(new BasicStroke(2f));
        g.drawOval((int)x - 15, (int)y - 15, 30, 30);
        g.setColor(LAB);
        g.fillOval((int)x - 4, (int)y - 4, 8, 8);
    }

    private static void drawTrussBeam(Graphics2D g, double x1, double y1, double x2, double y2,
                                      Color owner, boolean detailed) {
        g.setStroke(new BasicStroke(10f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(SPACE_SHADOW);
        g.drawLine((int)x1, (int)y1, (int)x2, (int)y2);
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(HULL);
        g.drawLine((int)x1, (int)y1, (int)x2, (int)y2);
        g.setStroke(new BasicStroke(1.6f));
        g.setColor(accent(owner, 145));
        g.drawLine((int)x1, (int)y1, (int)x2, (int)y2);
        if (!detailed) return;
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 20) return;
        int divisions = Math.max(1, (int)(length / 24));
        double nx = -dy / length * 5;
        double ny = dx / length * 5;
        g.setColor(PANEL);
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 1; i < divisions; i++) {
            double t = i / (double)divisions;
            double px = x1 + dx * t;
            double py = y1 + dy * t;
            g.drawLine((int)Math.round(px - nx), (int)Math.round(py - ny),
                    (int)Math.round(px + nx), (int)Math.round(py + ny));
        }
    }

    private static void drawNavigationLight(Graphics2D g, double x, double y, Color owner,
                                            double time, Base base, int salt) {
        double phase = time * 2.6 + phase(base, salt) * TWO_PI;
        double pulse = 0.55 + 0.45 * Math.sin(phase);
        int alpha = clampAlpha(120 + pulse * 110);
        g.setColor(new Color(owner.getRed(), owner.getGreen(), owner.getBlue(), alpha));
        int r = pulse > 0.7 ? 3 : 2;
        g.fillOval((int)Math.round(x) - r, (int)Math.round(y) - r, r * 2, r * 2);
    }

    private static Color accent(Color owner, int alpha) {
        return new Color(
                clampColor((owner.getRed() + 130) / 2),
                clampColor((owner.getGreen() + 155) / 2),
                clampColor((owner.getBlue() + 180) / 2),
                Math.max(0, Math.min(255, alpha)));
    }

    private static Color muted(Color owner) {
        return new Color((owner.getRed() + 95) / 2, (owner.getGreen() + 95) / 2, (owner.getBlue() + 105) / 2);
    }

    private static int clampColor(int v) { return Math.max(0, Math.min(255, v)); }
    private static int clampAlpha(double v) { return Math.max(0, Math.min(255, (int)Math.round(v))); }
    private static double positiveMod(double value, double modulo) {
        double r = value % modulo;
        return r < 0 ? r + modulo : r;
    }

    private static double phase(Base base, int salt) {
        long hash = 0xcbf29ce484222325L;
        String key = base == null ? "" : base.id + '|' + base.typeId;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        hash ^= salt * 0x9E3779B97F4A7C15L;
        return (hash & 0x7fffffffffffffffL) / (double)Long.MAX_VALUE;
    }
}
