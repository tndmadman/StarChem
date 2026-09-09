package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Authored ship presentation. Repository art wins when present; otherwise each named hull uses
 * its own deterministic silhouette and a shared physical-material/detail pass.
 */
final class ShipVisualRenderer {
    private ShipVisualRenderer() { }

    static void draw(Graphics2D g2, ShipType type, Color playerColor) {
        if (g2 == null || type == null) return;
        ShipVisualDefinition definition = VisualCatalog.ship(type);
        BufferedImage asset = ArtAssetManager.image(definition.assetPath());
        if (asset != null) {
            drawAuthored(g2, asset, definition, type, playerColor);
            return;
        }
        drawCodeAuthored(g2, type, playerColor, definition);
    }

    static String visualId(ShipType type) { return VisualCatalog.ship(type).id(); }

    /** Local-space engine aperture centers. Positive X is the stern in the existing ship convention. */
    static double[][] engineMounts(ShipType type) {
        double s = type == null ? 1.0 : type.size.scale;
        String id = type == null || type.id == null ? "" : type.id.toLowerCase();
        if (id.equals("scout") || id.equals("frigate")) return mounts(s, 29, 0);
        if (id.equals("destroyer") || id.equals("cruiser") || id.equals("battle_cruiser")) return mounts(s, 34, -7, 34, 7);
        if (id.equals("battleship") || id.equals("dreadnought")) return mounts(s, 39, -11, 39, 0, 39, 11);
        if (id.equals("carrier")) return mounts(s, 39, -18, 39, 18);
        if (id.equals("supercarrier")) return mounts(s, 45, -23, 45, -8, 45, 8, 45, 23);
        if (id.equals("titan") || id.contains("monolith")) return mounts(s, 49, -20, 49, 0, 49, 20);
        if (id.equals("freighter")) return mounts(s, 38, -13, 38, 13);
        if (id.equals("gas_harvester")) return mounts(s, 31, -12, 31, 12);
        if (id.equals("station_builder")) return mounts(s, 32, -13, 32, 13);
        return mounts(s, 31, -8, 31, 8);
    }

    private static double[][] mounts(double s, double... xy) {
        double[][] mounts = new double[xy.length / 2][2];
        for (int i = 0; i < mounts.length; i++) {
            mounts[i][0] = xy[i * 2] * s;
            mounts[i][1] = xy[i * 2 + 1] * s;
        }
        return mounts;
    }

    private static void drawAuthored(Graphics2D g2, BufferedImage image,
                                     ShipVisualDefinition definition, ShipType type, Color playerColor) {
        double scale = definition.assetScale();
        int width = Math.max(1, (int)Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int)Math.round(image.getHeight() * scale));
        g2.drawImage(image, -width / 2, -height / 2, width, height, null);
        drawOwnershipMarks(g2, type, playerColor, Math.max(width, height) / 70.0);
        drawNavigationLights(g2, type);
    }

    private static void drawCodeAuthored(Graphics2D g2, ShipType type, Color playerColor,
                                         ShipVisualDefinition definition) {
        Graphics2D ship = (Graphics2D) g2.create();
        ship.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Shape hull = hull(type);
        MaterialPalette palette = MaterialPalette.forShip(type);
        double s = type.size.scale;

        ship.setColor(new Color(0, 0, 0, 115));
        ship.translate(s * 1.8, s * 2.2);
        ship.fill(hull);
        ship.translate(-s * 1.8, -s * 2.2);

        ship.setColor(palette.primary);
        ship.fill(hull);
        ship.setStroke(new BasicStroke((float)Math.max(1.25, s * 1.25), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        ship.setColor(palette.edge);
        ship.draw(hull);

        Graphics2D details = (Graphics2D) ship.create();
        details.clip(hull);
        drawArmorPanels(details, type, palette);
        drawRecessedMachinery(details, type, palette);
        drawDeterministicWear(details, type, palette);
        details.dispose();

        drawRoleHardware(ship, type, palette);
        drawOwnershipMarks(ship, type, playerColor, s);
        drawNavigationLights(ship, type);
        ship.dispose();
    }

    private static Shape hull(ShipType type) {
        String id = type.id == null ? "" : type.id.toLowerCase();
        double s = type.size.scale;
        return switch (id) {
            case "prospector" -> prospector(s);
            case "station_builder" -> deployer(s);
            case "scout" -> scout(s);
            case "hauler" -> hauler(s, false);
            case "deep_miner" -> deepMiner(s);
            case "gas_harvester" -> gasHarvester(s);
            case "freighter" -> hauler(s, true);
            case "salvager" -> salvager(s);
            case "frigate" -> frigate(s);
            case "destroyer" -> destroyer(s);
            case "cruiser" -> cruiser(s);
            case "battle_cruiser" -> battleCruiser(s);
            case "battleship" -> battleship(s);
            case "carrier" -> carrier(s, false);
            case "dreadnought" -> dreadnought(s);
            case "supercarrier" -> carrier(s, true);
            case "titan" -> titan(s);
            default -> id.contains("monolith") ? monolith(s) : conventional(type);
        };
    }

    private static Shape prospector(double s) {
        Area a = new Area(poly(s, -32,0, -18,-11, 5,-13, 27,-8, 31,-3, 24,0, 31,3, 27,8, 5,13, -18,11));
        a.add(new Area(new Rectangle2D.Double(-32*s, -18*s, 18*s, 5*s)));
        a.add(new Area(new Rectangle2D.Double(-32*s, 13*s, 18*s, 5*s)));
        return a;
    }

    private static Shape deployer(double s) {
        Area a = new Area(poly(s, -27,-13, -15,-25, 13,-25, 31,-15, 31,15, 13,25, -15,25, -27,13));
        a.add(new Area(new Rectangle2D.Double(-10*s, -32*s, 8*s, 64*s)));
        return a;
    }

    private static Shape scout(double s) {
        return poly(s, -40,0, -8,-7, 8,-19, 17,-7, 32,-3, 25,0, 32,3, 17,7, 8,19, -8,7);
    }

    private static Shape hauler(double s, boolean heavy) {
        double len = heavy ? 41 : 34;
        double w = heavy ? 24 : 19;
        Area a = new Area(poly(s, -len,0, -27,-8, 28,-8, len,-4, len,4, 28,8, -27,8));
        int pods = heavy ? 4 : 3;
        for (int i = 0; i < pods; i++) {
            double px = (-20 + i * (heavy ? 13 : 14)) * s;
            a.add(new Area(new Rectangle2D.Double(px, -w*s, 10*s, (w-9)*s)));
            a.add(new Area(new Rectangle2D.Double(px, 9*s, 10*s, (w-9)*s)));
        }
        return a;
    }

    private static Shape deepMiner(double s) {
        Area a = new Area(poly(s, -28,0, -14,-15, 9,-17, 31,-10, 34,0, 31,10, 9,17, -14,15));
        a.add(new Area(poly(s, -39,-12, -23,-9, -18,-3, -38,-5)));
        a.add(new Area(poly(s, -39,12, -23,9, -18,3, -38,5)));
        return a;
    }

    private static Shape gasHarvester(double s) {
        Area a = new Area(poly(s, -31,0, -17,-9, 9,-11, 31,-5, 25,0, 31,5, 9,11, -17,9));
        a.add(new Area(new Ellipse2D.Double(-18*s, -26*s, 36*s, 14*s)));
        a.add(new Area(new Ellipse2D.Double(-18*s, 12*s, 36*s, 14*s)));
        return a;
    }

    private static Shape salvager(double s) {
        Area a = new Area(poly(s, -26,0, -12,-13, 14,-14, 31,-7, 34,0, 31,7, 14,14, -12,13));
        a.add(new Area(poly(s, -38,-20, -19,-14, -12,-8, -35,-12)));
        a.add(new Area(poly(s, -38,20, -19,14, -12,8, -35,12)));
        return a;
    }

    private static Shape frigate(double s) {
        return poly(s, -38,0, -16,-10, 4,-13, 17,-8, 31,-4, 27,0, 31,4, 17,8, 4,13, -16,10);
    }

    private static Shape destroyer(double s) {
        Area a = new Area(poly(s, -38,-7, -18,-16, 8,-18, 33,-9, 38,-3, 27,0, 38,3, 33,9, 8,18, -18,16, -38,7, -25,0));
        a.subtract(new Area(new Rectangle2D.Double(-40*s, -3*s, 19*s, 6*s)));
        return a;
    }

    private static Shape cruiser(double s) {
        Area a = new Area(poly(s, -38,0, -24,-16, -3,-22, 17,-18, 37,-8, 31,0, 37,8, 17,18, -3,22, -24,16));
        a.add(new Area(new Rectangle2D.Double(-18*s, -5*s, 49*s, 10*s)));
        return a;
    }

    private static Shape battleCruiser(double s) {
        Area a = new Area(poly(s, -43,-5, -25,-19, 1,-18, 16,-27, 24,-16, 40,-8, 34,0, 40,8, 24,16, 16,27, 1,18, -25,19, -43,5, -29,0));
        return a;
    }

    private static Shape battleship(double s) {
        Area a = new Area(poly(s, -45,0, -32,-17, -7,-24, 18,-22, 39,-13, 45,-5, 36,0, 45,5, 39,13, 18,22, -7,24, -32,17));
        a.add(new Area(new Rectangle2D.Double(-29*s, -8*s, 65*s, 16*s)));
        return a;
    }

    private static Shape carrier(double s, boolean superCarrier) {
        double len = superCarrier ? 49 : 42;
        double half = superCarrier ? 29 : 24;
        Area a = new Area(poly(s, -len,0, -35,-half, 23,-half, len,-14, len,14, 23,half, -35,half));
        double gap = (superCarrier ? 9 : 8) * s;
        a.subtract(new Area(new Rectangle2D.Double(-32*s, -gap/2, 62*s, gap)));
        return a;
    }

    private static Shape dreadnought(double s) {
        Area a = new Area(poly(s, -51,0, -32,-14, -5,-21, 27,-18, 46,-10, 51,-4, 42,0, 51,4, 46,10, 27,18, -5,21, -32,14));
        a.add(new Area(new Rectangle2D.Double(-48*s, -4*s, 76*s, 8*s)));
        return a;
    }

    private static Shape titan(double s) {
        Area a = new Area(poly(s, -55,0, -38,-14, -10,-18, 1,-34, 12,-20, 39,-17, 53,-8, 46,0, 53,8, 39,17, 12,20, 1,34, -10,18, -38,14));
        a.add(new Area(new Rectangle2D.Double(-43*s, -7*s, 86*s, 14*s)));
        return a;
    }

    private static Shape monolith(double s) {
        Area a = new Area(poly(s, -51,-25, 41,-25, 52,-14, 52,14, 41,25, -51,25, -57,14, -57,-14));
        a.subtract(new Area(new Rectangle2D.Double(-31*s, -4*s, 64*s, 8*s)));
        return a;
    }

    private static Shape conventional(ShipType type) {
        double s = type.size.scale;
        int hash = type.id == null ? type.seed : type.id.hashCode();
        double wing = 15 + Math.floorMod(hash, 8);
        double tail = 29 + Math.floorMod(hash >>> 4, 7);
        return poly(s, -35,0, -18,-12, 0,-wing, 18,-10, tail,-4, 25,0, tail,4, 18,10, 0,wing, -18,12);
    }

    private static Path2D poly(double s, double... xy) {
        Path2D p = new Path2D.Double();
        if (xy.length < 4) return p;
        p.moveTo(xy[0] * s, xy[1] * s);
        for (int i = 2; i + 1 < xy.length; i += 2) p.lineTo(xy[i] * s, xy[i + 1] * s);
        p.closePath();
        return p;
    }

    private static void drawArmorPanels(Graphics2D g, ShipType type, MaterialPalette palette) {
        double s = type.size.scale;
        g.setStroke(new BasicStroke((float)Math.max(0.75, s * 0.72), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(palette.panel);
        g.fillRoundRect((int)(-17*s), (int)(-6*s), (int)(35*s), (int)(12*s), (int)(5*s), (int)(5*s));
        g.setColor(new Color(palette.seam.getRed(), palette.seam.getGreen(), palette.seam.getBlue(), 150));
        int seams = type.size.scale >= 1.7 ? 5 : type.size.scale >= 1.15 ? 4 : 3;
        for (int i = 0; i < seams; i++) {
            double xx = (-22 + i * 12.0) * s;
            g.drawLine((int)xx, (int)(-17*s), (int)(xx + 5*s), (int)(17*s));
        }
        g.setColor(new Color(220, 232, 238, 42));
        g.drawLine((int)(-28*s), (int)(-8*s), (int)(22*s), (int)(-8*s));
    }

    private static void drawRecessedMachinery(Graphics2D g, ShipType type, MaterialPalette palette) {
        double s = type.size.scale;
        g.setColor(palette.recess);
        g.fillRoundRect((int)(-3*s), (int)(-4*s), (int)(28*s), (int)(8*s), (int)(3*s), (int)(3*s));
        g.setColor(new Color(145, 205, 220, 110));
        g.fillRoundRect((int)(-22*s), (int)(-3*s), (int)(12*s), (int)(6*s), (int)(4*s), (int)(4*s));
        g.setColor(new Color(55, 66, 72, 160));
        g.drawLine((int)(8*s), (int)(-12*s), (int)(8*s), (int)(12*s));
    }

    private static void drawDeterministicWear(Graphics2D g, ShipType type, MaterialPalette palette) {
        double s = type.size.scale;
        Random random = new Random(type.seed ^ 0x47A9C21DL);
        g.setStroke(new BasicStroke((float)Math.max(0.65, s * 0.45), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 5 + Math.min(5, (int)Math.ceil(s)); i++) {
            double x = (-24 + random.nextDouble() * 50) * s;
            double y = (-13 + random.nextDouble() * 26) * s;
            double len = (3 + random.nextDouble() * 7) * s;
            g.setColor(new Color(palette.wear.getRed(), palette.wear.getGreen(), palette.wear.getBlue(), 35 + random.nextInt(35)));
            g.drawLine((int)x, (int)y, (int)(x + len), (int)(y + (random.nextDouble() - .5) * 3 * s));
        }
    }

    private static void drawRoleHardware(Graphics2D g, ShipType type, MaterialPalette palette) {
        String id = type.id == null ? "" : type.id.toLowerCase();
        double s = type.size.scale;
        if (id.equals("prospector") || id.equals("deep_miner")) {
            g.setStroke(new BasicStroke((float)Math.max(1.1, 1.25*s)));
            g.setColor(new Color(195, 175, 118));
            g.drawLine((int)(-31*s), (int)(-15*s), (int)(-42*s), (int)(-21*s));
            g.drawLine((int)(-31*s), (int)(15*s), (int)(-42*s), (int)(21*s));
            g.setColor(new Color(240, 190, 75, 175));
            g.fillOval((int)(-45*s), (int)(-24*s), (int)(6*s), (int)(6*s));
            g.fillOval((int)(-45*s), (int)(18*s), (int)(6*s), (int)(6*s));
        } else if (id.equals("gas_harvester")) {
            g.setColor(new Color(78, 122, 128));
            g.drawOval((int)(-18*s), (int)(-25*s), (int)(36*s), (int)(13*s));
            g.drawOval((int)(-18*s), (int)(12*s), (int)(36*s), (int)(13*s));
        } else if (id.equals("salvager")) {
            g.setColor(new Color(190, 178, 120));
            g.setStroke(new BasicStroke((float)Math.max(1.0, s)));
            g.drawArc((int)(-45*s), (int)(-26*s), (int)(19*s), (int)(15*s), 185, 140);
            g.drawArc((int)(-45*s), (int)(11*s), (int)(19*s), (int)(15*s), 35, 140);
        } else if (id.equals("station_builder")) {
            g.setColor(new Color(220, 173, 72, 130));
            g.drawRect((int)(-9*s), (int)(-30*s), (int)(7*s), (int)(60*s));
        }

        if (id.contains("carrier")) {
            g.setColor(new Color(10, 16, 21, 220));
            g.fillRoundRect((int)(-29*s), (int)(-5*s), (int)(57*s), (int)(10*s), (int)(4*s), (int)(4*s));
            g.setColor(new Color(180, 210, 224, 100));
            for (int i = -2; i <= 2; i++) g.drawLine((int)(i*10*s), (int)(-3*s), (int)(i*10*s), (int)(3*s));
        }

        if (id.equals("dreadnought") || id.equals("titan")) {
            g.setColor(new Color(235, 201, 125, 145));
            g.setStroke(new BasicStroke((float)Math.max(1.0, s * 0.75)));
            g.drawLine((int)(-47*s), 0, (int)(-15*s), 0);
        }
    }

    private static void drawOwnershipMarks(Graphics2D g, ShipType type, Color playerColor, double scale) {
        if (playerColor == null) return;
        double s = Math.max(0.65, scale);
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke((float)Math.max(1.0, 1.25*s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 205));
        g.drawLine((int)(-12*s), (int)(-9*s), (int)(7*s), (int)(-9*s));
        g.drawLine((int)(-12*s), (int)(9*s), (int)(7*s), (int)(9*s));
        g.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 125));
        g.fillRoundRect((int)(11*s), (int)(-3*s), (int)(10*s), (int)(6*s), (int)(3*s), (int)(3*s));
        g.setStroke(old);
    }

    private static void drawNavigationLights(Graphics2D g, ShipType type) {
        double s = type.size.scale;
        g.setColor(new Color(115, 230, 160, 220));
        g.fill(new Ellipse2D.Double(-2.2*s, -17*s, 3.2*s, 3.2*s));
        g.setColor(new Color(235, 85, 78, 220));
        g.fill(new Ellipse2D.Double(-2.2*s, 13.8*s, 3.2*s, 3.2*s));
        g.setColor(new Color(220, 238, 250, 180));
        g.fill(new Ellipse2D.Double(-24*s, -1.8*s, 3.6*s, 3.6*s));
    }

    private record MaterialPalette(Color primary, Color panel, Color edge, Color seam, Color recess, Color wear) {
        static MaterialPalette forShip(ShipType type) {
            String id = type.id == null ? "" : type.id.toLowerCase();
            boolean industrial = id.contains("miner") || id.contains("harvest") || id.contains("hauler") ||
                    id.contains("freighter") || id.contains("salvag") || id.contains("prospector") || id.contains("builder");
            boolean capital = id.contains("carrier") || id.contains("dread") || id.contains("titan") || id.contains("monolith");
            if (industrial) return new MaterialPalette(new Color(91, 94, 91), new Color(118, 109, 86),
                    new Color(38, 44, 47), new Color(62, 65, 61), new Color(31, 37, 39), new Color(42, 35, 29));
            if (capital) return new MaterialPalette(new Color(61, 68, 76), new Color(78, 87, 96),
                    new Color(25, 30, 36), new Color(45, 52, 59), new Color(20, 25, 30), new Color(34, 29, 28));
            return new MaterialPalette(new Color(101, 111, 120), new Color(125, 135, 143),
                    new Color(36, 43, 49), new Color(65, 74, 80), new Color(27, 33, 38), new Color(49, 42, 38));
        }
    }
}
