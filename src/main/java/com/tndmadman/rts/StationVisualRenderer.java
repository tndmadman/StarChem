package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/** Complete presentation-only silhouettes for major production stations. */
final class StationVisualRenderer {
    private StationVisualRenderer() { }

    static boolean draw(Graphics2D source, Base base, Color playerColor) {
        if (source == null || base == null || playerColor == null) return false;
        if (IntelWarfareSystem.radarTier(base.typeId) > 0
                || IntelWarfareSystem.isJammer(base.typeId)
                || IntelWarfareSystem.isDecoy(base.typeId)
                || IntelWarfareSystem.CONTACT_STATION.equals(base.typeId)) return false;
        StationVisualDefinition v = StationVisualCatalog.resolve(base.typeId);
        if (v == null) return false;
        Graphics2D g = (Graphics2D)source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(base.x, base.y);
        switch (v.preset()) {
            case OUTPOST -> outpost(g, v, playerColor);
            case SHIPYARD -> shipyard(g, v, playerColor);
            case LABORATORY -> laboratory(g, v, playerColor);
            case FACTORY -> factory(g, v, playerColor);
        }
        g.dispose();
        return true;
    }

    private static void outpost(Graphics2D g, StationVisualDefinition v, Color player) {
        double s=v.scale(), core=31*s;
        g.setColor(new Color(25,32,39)); g.fill(new Ellipse2D.Double(-core,-core,core*2,core*2));
        g.setColor(new Color(93,103,112)); g.setStroke(new BasicStroke((float)(4*s))); g.draw(new Ellipse2D.Double(-core,-core,core*2,core*2));
        for(int i=0;i<v.armCount();i++){
            double a=i*Math.PI*2/v.armCount()+Math.PI/6; Graphics2D arm=(Graphics2D)g.create(); arm.rotate(a);
            arm.setColor(new Color(52,61,69)); arm.fillRoundRect((int)(core*.7),(int)(-5*s),(int)(45*s),(int)(10*s),5,5);
            arm.setColor(alpha(player,150)); arm.fillRoundRect((int)(core*.82),(int)(-2*s),(int)(27*s),(int)(4*s),3,3);
            arm.setColor(new Color(126,137,145)); arm.fill(new Ellipse2D.Double(67*s,-9*s,18*s,18*s)); arm.dispose();
        }
        rings(g,v,36*s); modulesRadial(g,v,49*s,new Color(66,76,84),player); batteries(g,v,35*s); lights(g,v,58*s);
    }

    private static void shipyard(Graphics2D g, StationVisualDefinition v, Color player) {
        double s=v.scale(), half=76*s, lane=28*s;
        g.setColor(new Color(20,27,34)); g.fill(new Rectangle2D.Double(-half*.48,-18*s,half*.96,36*s));
        g.setColor(new Color(91,103,114)); g.setStroke(new BasicStroke((float)(3*s))); g.draw(new Rectangle2D.Double(-half*.48,-18*s,half*.96,36*s));
        for(int side:new int[]{-1,1}){
            double y=side*lane; g.setColor(new Color(57,68,78)); g.fill(new Rectangle2D.Double(-half,y-7*s,half*2,14*s));
            g.setColor(alpha(player,135)); g.fill(new Rectangle2D.Double(-half*.9,y-2*s,half*1.8,4*s));
            for(int i=-3;i<=3;i++){double x=i*half*.27;g.setColor(new Color(118,128,137));g.drawLine((int)x,(int)(y-8*s),(int)x,(int)(y+8*s));}
        }
        g.setColor(new Color(4,9,13)); g.fill(new Rectangle2D.Double(-half*.60,-lane*.56,half*1.20,lane*1.12));
        for(int i=0;i<v.moduleCount();i++){int side=(i&1)==0?-1:1;double x=-half*.55+(i/2)*23*s,y=side*(lane+14*s);g.setColor(new Color(72,81,89));g.fillRoundRect((int)(x-9*s),(int)(y-6*s),(int)(18*s),(int)(12*s),4,4);}
        rings(g,v,22*s); batteries(g,v,50*s); lights(g,v,68*s);
    }

    private static void laboratory(Graphics2D g, StationVisualDefinition v, Color player) {
        double s=v.scale(), core=23*s;
        g.setColor(new Color(17,31,37)); g.fill(new Ellipse2D.Double(-core,-core,core*2,core*2));
        g.setColor(new Color(95,126,139)); g.setStroke(new BasicStroke((float)(3*s))); g.draw(new Ellipse2D.Double(-core,-core,core*2,core*2));
        rings(g,v,39*s); modulesRadial(g,v,66*s,new Color(42,67,77),player);
        Graphics2D dish=(Graphics2D)g.create(); dish.rotate(-.55);
        dish.setColor(new Color(105,126,136)); dish.fill(new Arc2D.Double(-16*s,-83*s,32*s,20*s,180,180,Arc2D.PIE));
        dish.setColor(v.accent()); dish.setStroke(new BasicStroke((float)(1.6*s))); dish.draw(new Arc2D.Double(-16*s,-83*s,32*s,20*s,180,180,Arc2D.OPEN));
        dish.drawLine(0,(int)(-67*s),0,(int)(-30*s)); dish.dispose(); batteries(g,v,50*s); lights(g,v,61*s);
    }

    private static void factory(Graphics2D g, StationVisualDefinition v, Color player) {
        double s=v.scale(), w=170*s,h=104*s;
        Path2D hull=new Path2D.Double(); hull.moveTo(-w*.5,-h*.26);hull.lineTo(-w*.34,-h*.5);hull.lineTo(w*.32,-h*.5);hull.lineTo(w*.5,-h*.27);hull.lineTo(w*.5,h*.27);hull.lineTo(w*.34,h*.5);hull.lineTo(-w*.34,h*.5);hull.lineTo(-w*.5,h*.26);hull.closePath();
        g.setColor(new Color(35,34,33)); g.fill(hull); g.setColor(new Color(102,99,94)); g.setStroke(new BasicStroke((float)(3*s))); g.draw(hull);
        for(int i=0;i<v.moduleCount();i++){int side=(i&1)==0?-1:1;double x=-55*s+(i/2)*28*s,y=side*31*s;g.setColor(new Color(75,71,66));g.fill(new Rectangle2D.Double(x-9*s,y-8*s,18*s,16*s));g.setColor(alpha(player,120));g.fill(new Rectangle2D.Double(x-7*s,y-2*s,14*s,4*s));}
        for(int side:new int[]{-1,1}){double x=side*42*s;g.setColor(new Color(64,62,59));g.fill(new Rectangle2D.Double(x-7*s,-88*s,14*s,48*s));g.setColor(v.accent());g.fill(new Ellipse2D.Double(x-9*s,-92*s,18*s,8*s));}
        Path2D conveyor=new Path2D.Double();conveyor.moveTo(-92*s,10*s);conveyor.lineTo(-42*s,10*s);conveyor.lineTo(-27*s,18*s);conveyor.lineTo(27*s,18*s);conveyor.lineTo(42*s,10*s);conveyor.lineTo(92*s,10*s);g.setColor(new Color(93,84,72));g.setStroke(new BasicStroke((float)(6*s)));g.draw(conveyor);
        batteries(g,v,53*s); lights(g,v,62*s);
    }

    private static void rings(Graphics2D g, StationVisualDefinition v,double base){for(int i=0;i<v.ringCount();i++){double r=base+i*10*v.scale();g.setColor(alpha(v.accent(),100-i*15));g.setStroke(new BasicStroke(1.3f));g.draw(new Ellipse2D.Double(-r,-r*.56,r*2,r*1.12));}}
    private static void modulesRadial(Graphics2D g,StationVisualDefinition v,double radius,Color material,Color player){for(int i=0;i<v.moduleCount();i++){double a=i*Math.PI*2/v.moduleCount()+.2,x=Math.cos(a)*radius,y=Math.sin(a)*radius;g.setColor(material);g.fill(new Ellipse2D.Double(x-8*v.scale(),y-6*v.scale(),16*v.scale(),12*v.scale()));g.setColor(alpha(player,135));g.draw(new Ellipse2D.Double(x-8*v.scale(),y-6*v.scale(),16*v.scale(),12*v.scale()));g.drawLine((int)(Math.cos(a)*25*v.scale()),(int)(Math.sin(a)*25*v.scale()),(int)x,(int)y);}}
    private static void batteries(Graphics2D g,StationVisualDefinition v,double radius){for(int i=0;i<v.batteryCount();i++){double a=(i+.35)*Math.PI*2/Math.max(1,v.batteryCount()),x=Math.cos(a)*radius,y=Math.sin(a)*radius*.72,r=4*v.scale();g.setColor(new Color(145,145,135));g.fill(new Ellipse2D.Double(x-r,y-r,r*2,r*2));g.setColor(new Color(210,198,160));g.drawLine((int)x,(int)y,(int)(x-Math.cos(a)*8*v.scale()),(int)(y-Math.sin(a)*8*v.scale()));}}
    private static void lights(Graphics2D g,StationVisualDefinition v,double radius){for(int i=0;i<v.lightCount();i++){double a=i*Math.PI*2/v.lightCount(),x=Math.cos(a)*radius,y=Math.sin(a)*radius*.68,r=1.6*v.scale();g.setColor(alpha(v.accent(),205));g.fill(new Ellipse2D.Double(x-r,y-r,r*2,r*2));}}
    private static Color alpha(Color c,int a){return new Color(c.getRed(),c.getGreen(),c.getBlue(),Math.max(0,Math.min(255,a)));}
}
