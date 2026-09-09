package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/** Dedicated physical station presentation; gameplay/status data stays owned by Base. */
final class StationVisualRenderer {
    private StationVisualRenderer() { }

    static void draw(Graphics2D g2, Base base, double radius, Color ownerColor) {
        if (g2 == null || base == null) return;
        StationVisualDefinition visual = VisualCatalog.station(base.typeId);
        BufferedImage authored = ArtAssetManager.image(visual.assetPath());
        if (authored != null) {
            int w = Math.max(2, (int)Math.round(authored.getWidth() * visual.assetScale()));
            int h = Math.max(2, (int)Math.round(authored.getHeight() * visual.assetScale()));
            g2.drawImage(authored, (int)Math.round(base.x - w / 2.0), (int)Math.round(base.y - h / 2.0), w, h, null);
            drawOwnershipMarks(g2, base, radius, ownerColor);
            drawActivityLights(g2, base, radius);
            return;
        }

        Graphics2D s = (Graphics2D)g2.create();
        s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        String id = base.typeId == null ? "" : base.typeId.toLowerCase();
        if (id.equals("shipyard")) drawShipyard(s, base, radius);
        else if (id.equals("manufacturing")) drawManufacturing(s, base, radius);
        else if (id.equals("laboratory")) drawLaboratory(s, base, radius);
        else if (id.contains("radar") || IntelWarfareSystem.radarTier(base.typeId) > 0) drawRadarPlatform(s, base, radius);
        else if (id.contains("jammer")) drawJammerPlatform(s, base, radius);
        else if (id.contains("decoy")) drawDecoyPlatform(s, base, radius);
        else drawOutpost(s, base, radius);
        drawOwnershipMarks(s, base, radius, ownerColor);
        drawActivityLights(s, base, radius);
        s.dispose();
    }

    static void drawContextRange(Graphics2D g, Base base, Color ownerColor) {
        if (g == null || base == null || ownerColor == null) return;
        BaseType def = base.type();
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0,
                new float[]{9f, 9f}, 0));
        g.setColor(new Color(ownerColor.getRed(), ownerColor.getGreen(), ownerColor.getBlue(), 44));
        g.draw(new Ellipse2D.Double(base.x - def.unloadRange, base.y - def.unloadRange,
                def.unloadRange * 2, def.unloadRange * 2));
        g.setStroke(old);
    }

    private static void drawOutpost(Graphics2D g, Base base, double r) {
        Color hull = new Color(68, 78, 86);
        Color panel = new Color(91, 101, 107);
        drawShadow(g, base, r * .76);
        Polygon hub = polygon(base.x, base.y, r * .62, 8, Math.PI / 8);
        g.setColor(hull); g.fillPolygon(hub);
        g.setColor(new Color(31, 38, 43)); g.setStroke(new BasicStroke(2.2f)); g.drawPolygon(hub);
        g.setColor(panel);
        g.fill(new Ellipse2D.Double(base.x-r*.29, base.y-r*.29, r*.58, r*.58));
        g.setColor(new Color(28, 35, 40));
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2;
            drawArm(g, base.x, base.y, a, r*.36, r*.82, r*.15, new Color(78, 88, 94));
            double px = base.x + Math.cos(a) * r * .86;
            double py = base.y + Math.sin(a) * r * .86;
            g.fill(new Ellipse2D.Double(px-r*.12, py-r*.12, r*.24, r*.24));
        }
        g.setColor(new Color(158, 172, 180, 95));
        g.draw(new Ellipse2D.Double(base.x-r*.46, base.y-r*.46, r*.92, r*.92));
    }

    private static void drawShipyard(Graphics2D g, Base base, double r) {
        drawShadow(g, base, r*.95);
        Color frame = new Color(70, 80, 89);
        Color deck = new Color(91, 101, 108);
        g.setColor(frame);
        g.fill(new Rectangle2D.Double(base.x-r*.82, base.y-r*.18, r*1.64, r*.36));
        g.setColor(deck);
        g.fill(new Rectangle2D.Double(base.x-r*.62, base.y-r*.74, r*1.14, r*.20));
        g.fill(new Rectangle2D.Double(base.x-r*.62, base.y+r*.54, r*1.14, r*.20));
        g.setColor(new Color(35, 42, 47));
        g.setStroke(new BasicStroke(3f));
        g.draw(new Rectangle2D.Double(base.x-r*.70, base.y-r*.64, r*1.36, r*1.28));
        for (int i = 0; i < 4; i++) {
            double xx = base.x-r*.48 + i*r*.31;
            g.setColor(new Color(118, 128, 133));
            g.fill(new Rectangle2D.Double(xx, base.y-r*.58, r*.08, r*1.16));
        }
        // Open dark center reads as a real construction berth.
        g.setColor(new Color(8, 14, 18, 220));
        g.fill(new Rectangle2D.Double(base.x-r*.48, base.y-r*.34, r*.96, r*.68));
        g.setColor(new Color(177, 192, 198, 90));
        g.drawLine((int)(base.x-r*.42), (int)base.y, (int)(base.x+r*.42), (int)base.y);
        drawHazardMarks(g, base.x-r*.50, base.y-r*.40, r, new Color(235, 180, 65));
    }

    private static void drawManufacturing(Graphics2D g, Base base, double r) {
        drawShadow(g, base, r*.82);
        g.setColor(new Color(76, 74, 67));
        g.fillRoundRect((int)(base.x-r*.68), (int)(base.y-r*.48), (int)(r*1.36), (int)(r*.96), 10, 10);
        g.setColor(new Color(43, 46, 46));
        g.setStroke(new BasicStroke(2.5f));
        g.drawRoundRect((int)(base.x-r*.68), (int)(base.y-r*.48), (int)(r*1.36), (int)(r*.96), 10, 10);
        g.setColor(new Color(33, 38, 40));
        for (int i = 0; i < 3; i++) {
            double yy = base.y-r*.32+i*r*.32;
            g.fill(new Rectangle2D.Double(base.x-r*.58, yy, r*1.02, r*.17));
        }
        g.setColor(new Color(121, 128, 126));
        g.fill(new Rectangle2D.Double(base.x+r*.34, base.y-r*.74, r*.13, r*.48));
        g.fill(new Rectangle2D.Double(base.x+r*.53, base.y-r*.66, r*.10, r*.40));
        g.setColor(new Color(255, 145, 58, 95));
        g.fill(new Ellipse2D.Double(base.x+r*.31, base.y-r*.82, r*.19, r*.19));
        drawHazardMarks(g, base.x-r*.54, base.y+r*.31, r*1.02, new Color(238, 178, 58));
    }

    private static void drawLaboratory(Graphics2D g, Base base, double r) {
        drawShadow(g, base, r*.82);
        g.setColor(new Color(63, 77, 88));
        g.fill(new Ellipse2D.Double(base.x-r*.63, base.y-r*.63, r*1.26, r*1.26));
        g.setColor(new Color(23, 34, 42));
        g.fill(new Ellipse2D.Double(base.x-r*.37, base.y-r*.37, r*.74, r*.74));
        g.setStroke(new BasicStroke(2.2f));
        g.setColor(new Color(125, 174, 196));
        g.draw(new Ellipse2D.Double(base.x-r*.63, base.y-r*.63, r*1.26, r*1.26));
        for (int i = 0; i < 5; i++) {
            double a = i*Math.PI*2/5.0;
            double px = base.x+Math.cos(a)*r*.51;
            double py = base.y+Math.sin(a)*r*.51;
            g.setColor(new Color(83, 100, 111));
            g.fill(new Ellipse2D.Double(px-r*.13, py-r*.13, r*.26, r*.26));
            g.setColor(new Color(105, 232, 255, 105));
            g.fill(new Ellipse2D.Double(px-r*.065, py-r*.065, r*.13, r*.13));
        }
        g.setColor(new Color(195, 238, 250, 160));
        g.drawLine((int)base.x, (int)(base.y-r*.30), (int)base.x, (int)(base.y-r*.83));
    }

    private static void drawRadarPlatform(Graphics2D g, Base base, double r) {
        drawShadow(g, base, r*.70);
        Polygon p = polygon(base.x, base.y, r*.62, 8, Math.PI/8);
        g.setColor(new Color(57, 72, 82)); g.fillPolygon(p);
        g.setColor(new Color(111, 133, 145)); g.setStroke(new BasicStroke(2f)); g.drawPolygon(p);
        g.setColor(new Color(25, 35, 41));
        g.fill(new Ellipse2D.Double(base.x-r*.30, base.y-r*.22, r*.60, r*.44));
    }

    private static void drawJammerPlatform(Graphics2D g, Base base, double r) {
        drawRadarPlatform(g, base, r);
        g.setColor(new Color(174, 94, 220, 70));
        g.setStroke(new BasicStroke(2f));
        for (int i=0;i<3;i++) {
            double rr=r*(.38+i*.17);
            g.draw(new Arc2D.Double(base.x-rr, base.y-rr, rr*2, rr*2, 205, 130, Arc2D.OPEN));
        }
    }

    private static void drawDecoyPlatform(Graphics2D g, Base base, double r) {
        drawRadarPlatform(g, base, r);
        g.setColor(new Color(95, 215, 255, 100));
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{5,5}, 0));
        g.draw(new Ellipse2D.Double(base.x-r*.52, base.y-r*.52, r*1.04, r*1.04));
    }

    private static void drawOwnershipMarks(Graphics2D g, Base base, double r, Color owner) {
        if (owner == null) return;
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(owner.getRed(), owner.getGreen(), owner.getBlue(), 190));
        g.draw(new Arc2D.Double(base.x-r*.72, base.y-r*.72, r*1.44, r*1.44, 20, 42, Arc2D.OPEN));
        g.draw(new Arc2D.Double(base.x-r*.72, base.y-r*.72, r*1.44, r*1.44, 200, 42, Arc2D.OPEN));
    }

    private static void drawActivityLights(Graphics2D g, Base base, double r) {
        World world = PlayerRegistry.activeWorld();
        double time = world == null ? 0 : world.systemTime();
        double pulse = .55 + .45*Math.sin(time*2.1 + base.id.hashCode()*.17);
        int alpha = (int)Math.round(80 + Math.max(0,pulse)*130);
        for (int i=0;i<6;i++) {
            double a=i*Math.PI*2/6.0;
            double rr=r*.63;
            double px=base.x+Math.cos(a)*rr;
            double py=base.y+Math.sin(a)*rr;
            g.setColor(new Color(185, 232, 245, Math.max(35,Math.min(230,alpha-(i%2)*35))));
            g.fill(new Ellipse2D.Double(px-2,py-2,4,4));
        }
    }

    private static void drawShadow(Graphics2D g, Base base, double r) {
        g.setColor(new Color(0,0,0,105));
        g.fill(new Ellipse2D.Double(base.x-r*.92, base.y-r*.58+r*.16, r*1.84, r*1.16));
    }

    private static void drawArm(Graphics2D g, double cx, double cy, double angle, double from, double to, double width, Color color) {
        Graphics2D arm=(Graphics2D)g.create();
        arm.translate(cx,cy); arm.rotate(angle);
        arm.setColor(color);
        arm.fill(new Rectangle2D.Double(from,-width*.5,to-from,width));
        arm.dispose();
    }

    private static Polygon polygon(double cx,double cy,double radius,int points,double rotation) {
        Polygon p=new Polygon();
        for(int i=0;i<points;i++) {
            double a=rotation+i*Math.PI*2/points;
            p.addPoint((int)Math.round(cx+Math.cos(a)*radius),(int)Math.round(cy+Math.sin(a)*radius));
        }
        return p;
    }

    private static void drawHazardMarks(Graphics2D g,double x,double y,double width,Color c) {
        g.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),150));
        g.setStroke(new BasicStroke(2f));
        int marks=7;
        for(int i=0;i<marks;i++) {
            double xx=x+i*width/(marks-1.0);
            g.drawLine((int)xx,(int)y,(int)(xx+6),(int)(y-6));
        }
    }
}
