package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;

/** Turns harvest nodes into readable environmental rocks/gas while preserving node gameplay geometry. */
final class ResourceVisualRenderer {
    private ResourceVisualRenderer() { }

    static void draw(Graphics2D g2, ResourceNode node, boolean selected) {
        if (g2 == null || node == null || !node.active) return;
        double vr = visualRadius(node);
        double cullRadius = node.kind == NodeKind.GAS_CLOUD ? vr * 3.2 : vr * 2.2;
        if (!RenderCulling.visible(g2, node.x, node.y, cullRadius + (selected ? 20 : 12))) return;
        Graphics2D r = (Graphics2D) g2.create();
        r.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (node.kind == NodeKind.GAS_CLOUD) drawGasField(r, node, vr);
        else drawAsteroid(r, node, vr);
        if (selected) drawSelection(r, node, vr);
        if (selected || amountPercent(node) < 0.22) drawAmountBar(r, node, vr);
        r.dispose();
    }

    private static void drawAsteroid(Graphics2D g, ResourceNode node, double vr) {
        ResourceVisualDefinition visual = VisualCatalog.resource(node.material.name());
        BufferedImage detailAsset = ArtAssetManager.image(visual.detailAssetPath());
        Polygon hull = asteroidPolygon(node, vr);
        Color mineral = node.material.color;
        Color rock = mix(new Color(72, 70, 66), mineral, 0.18);

        g.setColor(new Color(0, 0, 0, 95));
        Polygon shadow = translated(hull, vr * .16, vr * .22);
        g.fillPolygon(shadow);
        g.setColor(rock);
        g.fillPolygon(hull);
        g.setColor(new Color(38, 40, 41));
        g.setStroke(new BasicStroke((float)Math.max(.8, vr * .055), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolygon(hull);

        int hash = node.id * 0x45d9f3b + node.material.ordinal() * 131;
        for (int i = 0; i < 4; i++) {
            double a = i * 2.27 + hash * 0.00017;
            double rr = vr * (0.18 + ((hash >>> (i * 4)) & 7) * 0.045);
            double cx = node.x + Math.cos(a) * rr;
            double cy = node.y + Math.sin(a * 1.23) * rr;
            double crater = Math.max(1.0, vr * (0.08 + (i % 3) * 0.03));
            g.setColor(new Color(28, 30, 31, 105));
            g.fill(new Ellipse2D.Double(cx - crater, cy - crater * .75, crater * 2, crater * 1.5));
            g.setColor(new Color(175, 180, 178, 28));
            g.draw(new Ellipse2D.Double(cx - crater * .8, cy - crater * .58, crater * 1.6, crater * 1.16));
        }

        // Mineral seams read as embedded material rather than a full resource-color outline.
        Stroke previous = g.getStroke();
        g.setStroke(new BasicStroke((float)Math.max(.8, vr * .05), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(mineral.getRed(), mineral.getGreen(), mineral.getBlue(), 145));
        for (int i = 0; i < 3; i++) {
            double sy = node.y + (i - 1) * vr * .23;
            double sx = node.x - vr * (.48 - i * .09);
            g.draw(new Line2D.Double(sx, sy, sx + vr * (.58 + i * .07), sy - vr * (.12 + i * .04)));
        }
        g.setStroke(previous);

        if (detailAsset != null) {
            int d = Math.max(2, (int)Math.round(vr * 1.65));
            g.drawImage(detailAsset, (int)Math.round(node.x - d / 2.0), (int)Math.round(node.y - d / 2.0), d, d, null);
        }

        // A few deterministic fragments make belt nodes read as one rubble field without adding gameplay targets.
        for (int i = 0; i < 3; i++) {
            double a = node.id * .71 + i * 2.14;
            double rr = vr * (1.35 + i * .32);
            double size = Math.max(1.0, vr * (.10 + i * .035));
            g.setColor(new Color(rock.getRed(), rock.getGreen(), rock.getBlue(), 105 - i * 18));
            g.fill(new Ellipse2D.Double(node.x + Math.cos(a) * rr - size, node.y + Math.sin(a) * rr - size,
                    size * 2, size * 1.45));
        }
    }

    private static void drawGasField(Graphics2D g, ResourceNode node, double vr) {
        Color gas = node.material.color;
        int hash = node.id * 73428767 ^ node.material.ordinal() * 912931;
        for (int i = 0; i < 10; i++) {
            double a = i * 2.399 + hash * 0.000013;
            double distance = vr * (.22 + (i % 5) * .27);
            double cx = node.x + Math.cos(a) * distance;
            double cy = node.y + Math.sin(a * 1.11) * distance * .72;
            double w = vr * (1.15 + ((hash >>> (i % 16)) & 7) * .13);
            double h = vr * (.55 + (i % 4) * .17);
            int alpha = 14 + (i % 5) * 6;
            g.setColor(new Color(gas.getRed(), gas.getGreen(), gas.getBlue(), alpha));
            g.fill(new Ellipse2D.Double(cx - w, cy - h, w * 2, h * 2));
        }

        // Wisps link the central node to adjacent visual volume; nodes overlap into continuous clouds in dense belts.
        g.setStroke(new BasicStroke((float)Math.max(1.0, vr * .10), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 4; i++) {
            double a = hash * .00017 + i * 1.55;
            double x1 = node.x + Math.cos(a) * vr * .25;
            double y1 = node.y + Math.sin(a) * vr * .18;
            double x2 = node.x + Math.cos(a + .4) * vr * 2.15;
            double y2 = node.y + Math.sin(a + .4) * vr * 1.15;
            g.setColor(new Color(gas.getRed(), gas.getGreen(), gas.getBlue(), 34 + i * 5));
            g.draw(new Line2D.Double(x1, y1, x2, y2));
        }
        g.setColor(new Color(gas.getRed(), gas.getGreen(), gas.getBlue(), 72));
        g.draw(new Ellipse2D.Double(node.x - vr * .72, node.y - vr * .52, vr * 1.44, vr * 1.04));
    }

    private static Polygon asteroidPolygon(ResourceNode node, double vr) {
        Polygon poly = new Polygon();
        int points = 10 + Math.floorMod(node.id, 3);
        double rotation = Math.floorMod(node.id * 37, 360) * Math.PI / 180.0;
        for (int i = 0; i < points; i++) {
            double a = rotation + i * Math.PI * 2 / points;
            int noise = Math.floorMod(node.id * 31 + i * 67 + node.material.ordinal() * 17, 37);
            double wobble = .72 + noise / 100.0;
            poly.addPoint((int)Math.round(node.x + Math.cos(a) * vr * wobble),
                    (int)Math.round(node.y + Math.sin(a) * vr * wobble));
        }
        return poly;
    }

    private static Polygon translated(Polygon source, double dx, double dy) {
        Polygon out = new Polygon();
        for (int i = 0; i < source.npoints; i++) out.addPoint((int)Math.round(source.xpoints[i] + dx), (int)Math.round(source.ypoints[i] + dy));
        return out;
    }

    private static void drawSelection(Graphics2D g, ResourceNode node, double vr) {
        g.setColor(new Color(255, 239, 145, 205));
        g.setStroke(new BasicStroke(1.7f));
        double ring = node.kind == NodeKind.GAS_CLOUD ? vr * .92 : vr + 7;
        g.draw(new Ellipse2D.Double(node.x - ring, node.y - ring, ring * 2, ring * 2));
    }

    private static void drawAmountBar(Graphics2D g, ResourceNode node, double vr) {
        int w = Math.max(14, (int)Math.round(vr * 4.4)), h = 3;
        int bx = (int)Math.round(node.x - w / 2.0);
        int by = (int)Math.round(node.y + (node.kind == NodeKind.GAS_CLOUD ? vr * .95 : vr + 5));
        double pct = amountPercent(node);
        g.setColor(new Color(0, 0, 0, 145)); g.fillRoundRect(bx, by, w, h, 3, 3);
        Color c = node.material.color;
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 190));
        g.fillRoundRect(bx, by, (int)Math.round(w * pct), h, 3, 3);
    }

    private static double visualRadius(ResourceNode node) {
        return node.radius * (.38 + .62 * Math.sqrt(amountPercent(node)));
    }

    private static double amountPercent(ResourceNode node) {
        if (!(node.maxAmount > 0)) return 0;
        return Math.max(0, Math.min(1, node.amount / node.maxAmount));
    }

    private static Color mix(Color a, Color b, double t) {
        double u = 1.0 - t;
        return new Color((int)Math.round(a.getRed()*u + b.getRed()*t),
                (int)Math.round(a.getGreen()*u + b.getGreen()*t),
                (int)Math.round(a.getBlue()*u + b.getBlue()*t));
    }
}
