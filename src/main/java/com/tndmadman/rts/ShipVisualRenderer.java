package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/** Data-directed vector ship rendering with ShipShape as the compatibility fallback. */
final class ShipVisualRenderer {
    private ShipVisualRenderer() { }

    static void draw(Graphics2D g2, ShipType type, Color playerColor) {
        ShipVisualDefinition visual = VisualDefinitionCatalog.ship(type.id);
        if (visual == null) {
            ShipShape.draw(g2, type, playerColor);
            return;
        }

        double s = type.size.scale;
        double length = 40.0 * s * visual.lengthScale();
        double width = 20.0 * s * visual.widthScale();
        Path2D hull = hull(visual, length, width);
        Graphics2D ship = (Graphics2D)g2.create();
        ship.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ship.setStroke(new BasicStroke((float)Math.max(1.25, s * 1.3), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        Color accent = new Color(visual.accentRgb());
        Color glow = new Color(visual.glowRgb());
        Color hullFill = hullColor(visual.designLanguage(), playerColor);
        Color panel = blend(hullFill, accent, .24);

        drawEngineGlow(ship, visual, length, width, glow, s);
        ship.setColor(hullFill);
        ship.fill(hull);
        ship.setColor(blend(playerColor, accent, visual.designLanguage() == ShipDesignLanguage.CAPITAL ? .42 : .28));
        ship.draw(hull);

        drawArmorSections(ship, visual, length, width, panel, accent, s);
        drawDesignLanguage(ship, visual, length, width, accent, s);
        drawPods(ship, visual, length, width, hullFill, accent, s);
        drawEquipment(ship, visual, length, width, hullFill, accent, glow, s);
        drawHardpoints(ship, visual, length, width, playerColor, accent, s);
        drawHangars(ship, visual, length, width, glow, s);
        drawAntennae(ship, visual, length, width, accent, s);
        drawDamageDetail(ship, visual, length, width, s);
        drawMarking(ship, visual, length, width, accent, s);
        ship.dispose();
    }

    private static Color hullColor(ShipDesignLanguage language, Color player) {
        return switch (language) {
            case INDUSTRIAL -> blend(new Color(16, 18, 20), player.darker(), .23);
            case CIVILIAN -> blend(new Color(18, 24, 30), player.darker(), .28);
            case NAVAL -> blend(new Color(8, 14, 23), player.darker(), .34);
            case CAPITAL -> blend(new Color(6, 10, 18), player.darker(), .39);
            case EXOTIC -> blend(new Color(12, 8, 20), player.darker(), .31);
        };
    }

    private static Path2D hull(ShipVisualDefinition visual, double l, double w) {
        double a = visual.asymmetry();
        return switch (visual.preset()) {
            case MINING_CLAW -> miningClaw(l, w, a);
            case DEPLOYER -> deployer(l, w, a);
            case CARGO_FRAME -> cargoFrame(l, w, a);
            case INDUSTRIAL -> industrial(l, w, a);
            case NEEDLE -> needle(l, w, a);
            case WEDGE -> wedge(l, w, a);
            case SPINE -> spine(l, w, a);
            case BATTLESHIP -> battleship(l, w, a);
            case FLIGHT_DECK -> flightDeck(l, w, a);
            case DREAD_SLAB -> dreadSlab(l, w, a);
            case TITAN_SPINE -> titanSpine(l, w, a);
            case MONOLITH -> monolith(l, w, a);
        };
    }

    private static Path2D miningClaw(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l*.78, -w*.55); p.lineTo(-l*.48, -w*.96); p.lineTo(-l*.12, -w*.60);
        p.lineTo(l*.18, -w*(.72+a)); p.lineTo(l*.58, -w*.36); p.lineTo(l*.68, 0);
        p.lineTo(l*.58, w*.36); p.lineTo(l*.18, w*(.72-a)); p.lineTo(-l*.12, w*.60);
        p.lineTo(-l*.48, w*.96); p.lineTo(-l*.78, w*.55); p.lineTo(-l*.52, w*.20);
        p.lineTo(-l*.28, 0); p.lineTo(-l*.52, -w*.20); p.closePath(); return p;
    }

    private static Path2D deployer(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l*.72, 0); p.lineTo(-l*.38, -w*.54); p.lineTo(-l*.05, -w*.70);
        p.lineTo(l*.10, -w*(1+a)); p.lineTo(l*.48, -w*.84); p.lineTo(l*.66, -w*.38);
        p.lineTo(l*.66, w*.38); p.lineTo(l*.48, w*.84); p.lineTo(l*.10, w*(1-a));
        p.lineTo(-l*.05, w*.70); p.lineTo(-l*.38, w*.54); p.closePath(); return p;
    }

    private static Path2D cargoFrame(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l*.72, 0); p.lineTo(-l*.50, -w*.44); p.lineTo(-l*.18, -w*.54);
        p.lineTo(-l*.02, -w*(.86+a)); p.lineTo(l*.44, -w*.86); p.lineTo(l*.64, -w*.44);
        p.lineTo(l*.64, w*.44); p.lineTo(l*.44, w*.86); p.lineTo(-l*.02, w*(.86-a));
        p.lineTo(-l*.18, w*.54); p.lineTo(-l*.50, w*.44); p.closePath(); return p;
    }

    private static Path2D industrial(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l*.70, -w*.18); p.lineTo(-l*.42, -w*.68); p.lineTo(l*.04, -w*(.78+a));
        p.lineTo(l*.22, -w*1.04); p.lineTo(l*.58, -w*.76); p.lineTo(l*.66, -w*.22);
        p.lineTo(l*.60, w*.56); p.lineTo(l*.24, w*(.76-a)); p.lineTo(-l*.12, w*.60);
        p.lineTo(-l*.46, w*.42); p.closePath(); return p;
    }

    private static Path2D needle(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l*.84, 0); p.lineTo(-l*.24, -w*.26); p.lineTo(l*.02, -w*(.90+a));
        p.lineTo(l*.30, -w*.34); p.lineTo(l*.62, -w*.18); p.lineTo(l*.52, 0);
        p.lineTo(l*.62, w*.18); p.lineTo(l*.30, w*.34); p.lineTo(l*.02, w*(.90-a));
        p.lineTo(-l*.24, w*.26); p.closePath(); return p;
    }

    private static Path2D wedge(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l*.78, 0); p.lineTo(l*.46, -w*(1+a)); p.lineTo(l*.64, -w*.44);
        p.lineTo(l*.55, 0); p.lineTo(l*.64, w*.44); p.lineTo(l*.46, w*(1-a)); p.closePath(); return p;
    }

    private static Path2D spine(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l*.78, 0); p.lineTo(-l*.34, -w*.34); p.lineTo(-l*.08, -w*(.90+a));
        p.lineTo(l*.12, -w*.58); p.lineTo(l*.50, -w*.72); p.lineTo(l*.66, -w*.28);
        p.lineTo(l*.60, 0); p.lineTo(l*.66, w*.28); p.lineTo(l*.50, w*.72);
        p.lineTo(l*.12, w*.58); p.lineTo(-l*.08, w*(.90-a)); p.lineTo(-l*.34, w*.34); p.closePath(); return p;
    }

    private static Path2D battleship(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l*.76, 0); p.lineTo(-l*.52, -w*.42); p.lineTo(-l*.20, -w*.62);
        p.lineTo(-l*.04, -w*(1+a)); p.lineTo(l*.40, -w*.88); p.lineTo(l*.64, -w*.50);
        p.lineTo(l*.64, w*.50); p.lineTo(l*.40, w*.88); p.lineTo(-l*.04, w*(1-a));
        p.lineTo(-l*.20, w*.62); p.lineTo(-l*.52, w*.42); p.closePath(); return p;
    }

    private static Path2D flightDeck(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l*.72, -w*.20); p.lineTo(-l*.52, -w*.58); p.lineTo(l*.44, -w*(1+a));
        p.lineTo(l*.66, -w*.72); p.lineTo(l*.66, -w*.16); p.lineTo(l*.48, 0);
        p.lineTo(l*.66, w*.16); p.lineTo(l*.66, w*.72); p.lineTo(l*.44, w*(1-a));
        p.lineTo(-l*.52, w*.58); p.lineTo(-l*.72, w*.20); p.closePath(); return p;
    }

    private static Path2D dreadSlab(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l*.78, -w*.18); p.lineTo(-l*.58, -w*.58); p.lineTo(-l*.20, -w*.78);
        p.lineTo(l*.30, -w*(1+a)); p.lineTo(l*.66, -w*.68); p.lineTo(l*.68, w*.68);
        p.lineTo(l*.30, w*(1-a)); p.lineTo(-l*.20, w*.78); p.lineTo(-l*.58, w*.58);
        p.lineTo(-l*.78, w*.18); p.closePath(); return p;
    }

    private static Path2D titanSpine(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l*.88, 0); p.lineTo(-l*.52, -w*.28); p.lineTo(-l*.28, -w*.62);
        p.lineTo(l*.06, -w*(.82+a)); p.lineTo(l*.22, -w*1.08); p.lineTo(l*.58, -w*.72);
        p.lineTo(l*.70, -w*.26); p.lineTo(l*.64, 0); p.lineTo(l*.70, w*.26);
        p.lineTo(l*.58, w*.72); p.lineTo(l*.22, w*1.08); p.lineTo(l*.06, w*(.82-a));
        p.lineTo(-l*.28, w*.62); p.lineTo(-l*.52, w*.28); p.closePath(); return p;
    }

    private static Path2D monolith(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l*.86, -w*.22); p.lineTo(-l*.70, -w*.60); p.lineTo(-l*.30, -w*.72);
        p.lineTo(-l*.12, -w*(1.04+a)); p.lineTo(l*.18, -w*.86); p.lineTo(l*.42, -w*1.10);
        p.lineTo(l*.72, -w*.72); p.lineTo(l*.74, w*.72); p.lineTo(l*.42, w*1.10);
        p.lineTo(l*.18, w*.86); p.lineTo(-l*.12, w*(1.04-a)); p.lineTo(-l*.30, w*.72);
        p.lineTo(-l*.70, w*.60); p.lineTo(-l*.86, w*.22); p.closePath(); return p;
    }

    private static void drawEngineGlow(Graphics2D g, ShipVisualDefinition v, double l, double w, Color glow, double s) {
        int count = v.engineCount();
        double span = Math.min(w*1.55, Math.max(0, count-1)*w*.34) * v.engineSpread();
        for (int i=0;i<count;i++) {
            double y = count==1 ? 0 : -span*.5 + span*i/(count-1.0);
            double r = Math.max(2.1, 2.8*s) * v.engineScale();
            g.setColor(alpha(glow, 45)); g.fill(new Ellipse2D.Double(l*.53, y-r*1.8, r*4.4, r*3.6));
            g.setColor(alpha(glow, 210)); g.fill(new Ellipse2D.Double(l*.55, y-r*.72, r*1.9, r*1.44));
        }
    }

    private static void drawArmorSections(Graphics2D g, ShipVisualDefinition v, double l, double w, Color panel, Color accent, double s) {
        g.setStroke(new BasicStroke((float)Math.max(.8, s*.72)));
        for (int i=0;i<v.armorSections();i++) {
            double f=(i+1.0)/(v.armorSections()+1.0), x=-l*.45+f*l*.86, half=w*(.22+.34*Math.sin(Math.PI*f));
            g.setColor(alpha(panel, 115)); g.fill(new Rectangle2D.Double(x-1.4*s,-half,2.8*s,half*2));
            g.setColor(alpha(accent, 110)); g.drawLine((int)x,(int)-half,(int)x,(int)half);
        }
    }

    private static void drawDesignLanguage(Graphics2D g, ShipVisualDefinition v, double l, double w, Color accent, double s) {
        g.setColor(alpha(accent, 120));
        g.setStroke(new BasicStroke((float)Math.max(.7, s*.65)));
        switch (v.designLanguage()) {
            case INDUSTRIAL -> { for (int i=-1;i<=1;i++) g.draw(new Rectangle2D.Double(-l*.22+i*l*.18,-w*.34,l*.11,w*.68)); }
            case CIVILIAN -> { g.drawLine((int)(-l*.50),(int)(-w*.18),(int)(l*.42),(int)(-w*.18)); g.drawLine((int)(-l*.50),(int)(w*.18),(int)(l*.42),(int)(w*.18)); }
            case NAVAL -> { g.drawLine((int)(-l*.55),0,(int)(l*.46),0); g.drawLine((int)(-l*.12),(int)(-w*.48),(int)(l*.30),(int)(-w*.48)); g.drawLine((int)(-l*.12),(int)(w*.48),(int)(l*.30),(int)(w*.48)); }
            case CAPITAL -> { g.setStroke(new BasicStroke((float)Math.max(1.2,s*1.1))); g.drawLine((int)(-l*.58),0,(int)(l*.50),0); for (int i=-1;i<=1;i++) g.fill(new Ellipse2D.Double(i*l*.22-2*s,-2*s,4*s,4*s)); }
            case EXOTIC -> { Path2D d=new Path2D.Double(); d.moveTo(-l*.25,0); d.lineTo(0,-w*.38); d.lineTo(l*.25,0); d.lineTo(0,w*.38); d.closePath(); g.draw(d); }
        }
    }

    private static void drawPods(Graphics2D g, ShipVisualDefinition v, double l, double w, Color hull, Color accent, double s) {
        for (int i=0;i<v.podCount();i++) {
            int side=(i&1)==0?-1:1, row=i/2; double x=-l*.08+(row%4)*l*.16, y=side*w*(.78+.08*(row%2));
            double pw=Math.max(5,l*.14), ph=Math.max(3.5,4.2*s);
            g.setColor(hull.brighter()); g.fillRoundRect((int)(x-pw*.5),(int)(y-ph*.5),(int)pw,(int)ph,(int)Math.max(2,s*2),(int)Math.max(2,s*2));
            g.setColor(alpha(accent,185)); g.drawRoundRect((int)(x-pw*.5),(int)(y-ph*.5),(int)pw,(int)ph,(int)Math.max(2,s*2),(int)Math.max(2,s*2));
        }
    }

    private static void drawEquipment(Graphics2D g, ShipVisualDefinition v, double l, double w, Color hull, Color accent, Color glow, double s) {
        switch (v.equipment()) {
            case NONE -> { }
            case MINING -> { for (int side : new int[]{-1,1}) { Path2D arm=new Path2D.Double(); arm.moveTo(-l*.38,side*w*.34); arm.lineTo(-l*.70,side*w*.62); arm.lineTo(-l*.88,side*w*.38); g.setColor(hull.brighter()); g.setStroke(new BasicStroke((float)Math.max(2,s*2.2))); g.draw(arm); g.setColor(alpha(glow,220)); g.fill(new Ellipse2D.Double(-l*.91-2*s,side*w*.38-2*s,4*s,4*s)); } }
            case GAS -> { for (int side : new int[]{-1,1}) { double y=side*w*.88; g.setColor(alpha(accent,130)); g.setStroke(new BasicStroke((float)Math.max(1,s))); g.draw(new Ellipse2D.Double(-l*.24,y-7*s,20*s,14*s)); g.drawLine((int)(-l*.44),(int)(side*w*.56),(int)(-l*.24),(int)y); } }
            case CARGO -> { g.setColor(alpha(accent,125)); g.setStroke(new BasicStroke((float)Math.max(1,s))); g.drawLine((int)(-l*.22),(int)(-w*.72),(int)(l*.42),(int)(-w*.72)); g.drawLine((int)(-l*.22),(int)(w*.72),(int)(l*.42),(int)(w*.72)); }
            case SALVAGE -> { g.setColor(alpha(accent,180)); g.setStroke(new BasicStroke((float)Math.max(1.4,s*1.4))); g.drawLine((int)(-l*.18),(int)(-w*.45),(int)(-l*.72),(int)(-w*.78)); g.drawLine((int)(-l*.10),(int)(w*.34),(int)(-l*.60),(int)(w*.60)); g.draw(new Ellipse2D.Double(-l*.76,-w*.82,8*s,8*s)); }
            case CONSTRUCTION -> { g.setColor(alpha(accent,150)); g.setStroke(new BasicStroke((float)Math.max(1.2,s*1.25))); g.draw(new Rectangle2D.Double(-l*.18,-w*.58,l*.55,w*1.16)); g.drawLine((int)(-l*.18),(int)(-w*.58),(int)(l*.37),(int)(w*.58)); g.drawLine((int)(-l*.18),(int)(w*.58),(int)(l*.37),(int)(-w*.58)); }
        }
    }

    private static void drawHardpoints(Graphics2D g, ShipVisualDefinition v, double l, double w, Color player, Color accent, double s) {
        int count=v.hardpointCount(); if (count<=0) return;
        for (int i=0;i<count;i++) {
            double f=(i+1.0)/(count+1.0), x, y;
            switch (v.hardpointLayout()) {
                case INLINE -> { x=-l*.45+f*l*.72; y=((i&1)==0?-1:1)*w*.12; }
                case BROADSIDE -> { double pairIndex=i/2.0; double pairs=Math.max(1.0,Math.ceil(count/2.0)-1.0); x=-l*.25+(pairs==0?0:pairIndex/pairs)*l*.50; y=((i&1)==0?-1:1)*w*.64; }
                case FORE -> { x=-l*.62+(i%3)*l*.12; y=(i-(count-1)/2.0)*w*.18; }
                default -> { x=-l*.28+f*l*.70; y=((i&1)==0?-1:1)*w*(.31+.15*(i%3)); }
            }
            double r=Math.max(2.1,2.4*s); g.setColor(new Color(18,25,34)); g.fill(new Ellipse2D.Double(x-r,y-r,r*2,r*2)); g.setColor(blend(player,accent,.45)); g.draw(new Ellipse2D.Double(x-r,y-r,r*2,r*2));
        }
    }

    private static void drawHangars(Graphics2D g, ShipVisualDefinition v, double l, double w, Color glow, double s) {
        for (int i=0;i<v.hangarCount();i++) { double x=-l*.20+i*l*.18, y=((i&1)==0?-1:1)*w*.47, ww=Math.max(7,l*.18), hh=Math.max(3,3.8*s); g.setColor(new Color(0,0,0,190)); g.fill(new Rectangle2D.Double(x-ww*.5,y-hh*.5,ww,hh)); g.setColor(alpha(glow,165)); g.draw(new Rectangle2D.Double(x-ww*.5,y-hh*.5,ww,hh)); }
    }

    private static void drawAntennae(Graphics2D g, ShipVisualDefinition v, double l, double w, Color accent, double s) {
        g.setStroke(new BasicStroke((float)Math.max(.7,s*.62))); g.setColor(alpha(accent,190));
        for (int i=0;i<v.antennaCount();i++) { int side=(i&1)==0?-1:1; double x=-l*.08+(i/2)*l*.12, y=side*w*.55, end=y+side*(7+i%3*3)*s; g.drawLine((int)x,(int)y,(int)(x-l*.06),(int)end); g.fill(new Ellipse2D.Double(x-l*.06-1.5*s,end-1.5*s,3*s,3*s)); }
    }

    /** Static authored wear; deliberately does not encode current HP or authoritative state. */
    private static void drawDamageDetail(Graphics2D g, ShipVisualDefinition v, double l, double w, double s) {
        int count=(int)Math.round(v.damageDetail()*7); if (count<=0) return;
        int seed=v.id().hashCode(); g.setStroke(new BasicStroke((float)Math.max(.7,s*.55)));
        for (int i=0;i<count;i++) { double x=signed(seed,i*17+3)*l*.42, y=signed(seed,i*29+7)*w*.48, len=(.05+.08*unit(seed,i*41+11))*l; g.setColor(new Color(0,0,0,75+(int)(unit(seed,i*53+13)*55))); g.drawLine((int)(x-len*.5),(int)y,(int)(x+len*.5),(int)(y+signed(seed,i*61+19)*s*3)); if ((i&1)==0) g.fill(new Ellipse2D.Double(x-2*s,y-2*s,4*s,4*s)); }
    }

    private static void drawMarking(Graphics2D g, ShipVisualDefinition v, double l, double w, Color accent, double s) {
        g.setColor(alpha(accent,210)); g.setStroke(new BasicStroke((float)Math.max(1.1,s*1.05)));
        switch (v.marking()) {
            case NONE -> { }
            case STRIPE -> g.drawLine((int)(-l*.28),(int)(-w*.42),(int)(l*.28),(int)(w*.42));
            case CHEVRON -> { g.drawLine((int)(-l*.34),(int)(-w*.30),(int)(-l*.08),0); g.drawLine((int)(-l*.34),(int)(w*.30),(int)(-l*.08),0); }
            case INDUSTRIAL -> { for (int i=0;i<3;i++) g.drawLine((int)(-l*.30+i*l*.10),(int)(-w*.35),(int)(-l*.20+i*l*.10),(int)(w*.35)); }
            case COMMAND -> { g.drawLine((int)(-l*.42),0,(int)(l*.38),0); g.fill(new Ellipse2D.Double(-3*s,-3*s,6*s,6*s)); }
        }
    }

    private static double unit(int seed, int salt) { long z=((long)seed<<32)^salt*0x9E3779B97F4A7C15L; z^=z>>>33; z*=0xff51afd7ed558ccdL; z^=z>>>33; z*=0xc4ceb9fe1a85ec53L; z^=z>>>33; return (z>>>11)*(1.0/(1L<<53)); }
    private static double signed(int seed,int salt) { return unit(seed,salt)*2-1; }
    private static Color alpha(Color c,int a) { return new Color(c.getRed(),c.getGreen(),c.getBlue(),Math.max(0,Math.min(255,a))); }
    private static Color blend(Color a,Color b,double t) { double u=Math.max(0,Math.min(1,t)); return new Color((int)Math.round(a.getRed()*(1-u)+b.getRed()*u),(int)Math.round(a.getGreen()*(1-u)+b.getGreen()*u),(int)Math.round(a.getBlue()*(1-u)+b.getBlue()*u)); }
}
