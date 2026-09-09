package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;

/** Headless regression coverage for the contextual station presentation introduced by issue #398. */
public final class StationPresentationValidator {
    private StationPresentationValidator() { }

    public static void main(String[] args) {
        validateNormalStationsStayClean();
        validateCriticalWarningPriority();
        validatePresentationDoesNotMutateStationState();
        validateDenseHoverChoosesNearestStation();
        validateForeignContextDoesNotLeakOperations();
        System.out.println("Station presentation validation passed.");
    }

    private static void validateNormalStationsStayClean() {
        Base base = new Base("TEST:B1", "TEST", Rules.DEFAULT_BASE, 200, 200);
        BaseType def = base.type();
        BufferedImage image = new BufferedImage(420, 420, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            StationPresentation.draw(g2, base, def, 64, Color.CYAN);
        } finally {
            g2.dispose();
        }

        int rangeX = (int)Math.round(base.x + def.unloadRange);
        int rangeY = (int)Math.round(base.y);
        require(alpha(image.getRGB(rangeX, rangeY)) == 0,
                "Unselected station rendered its tactical unload/range overlay.");

        int ownershipX = (int)Math.round(base.x);
        int ownershipY = (int)Math.round(base.y + 64 + 7);
        require(alpha(image.getRGB(ownershipX, ownershipY)) > 0,
                "Normal station lost its subtle ownership cue.");
    }

    private static void validateCriticalWarningPriority() {
        Base lab = new Base("TEST:LAB", "TEST", "laboratory", 0, 0);
        BaseType def = lab.type();
        require("NO FUEL".equals(StationPresentation.criticalWarning(lab, def)),
                "Fuel-required station did not surface NO FUEL.");

        lab.inventory.put(Material.FUEL, 10.0);
        require(StationPresentation.criticalWarning(lab, def) == null,
                "Fueled healthy station reported a critical warning.");

        lab.hp = 0;
        require("OFFLINE".equals(StationPresentation.criticalWarning(lab, def)),
                "Destroyed station must prioritize OFFLINE.");

        lab.hp = def.maxHp;
        lab.shield = Math.max(0, def.maxShield - 1);
        lab.shieldDelayTimer = 1.0;
        require("UNDER ATTACK".equals(StationPresentation.criticalWarning(lab, def)),
                "Recently damaged station did not surface UNDER ATTACK.");

        lab.shieldDelayTimer = 0;
        lab.shield = def.maxShield;
        lab.hp = def.maxHp * 0.30;
        require("CRITICAL DAMAGE".equals(StationPresentation.criticalWarning(lab, def)),
                "Severely damaged station did not surface CRITICAL DAMAGE.");
    }

    private static void validatePresentationDoesNotMutateStationState() {
        Base base = new Base("TEST:B2", "TEST", Rules.DEFAULT_BASE, 120, 120);
        base.inventory.put(Material.IRON, 42.0);
        double hp = base.hp;
        double shield = base.shield;
        double iron = base.inventory.get(Material.IRON);
        int queueSize = base.productionQueue.size();

        BufferedImage image = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            StationPresentation.draw(g2, base, base.type(), 64, Color.CYAN);
        } finally {
            g2.dispose();
        }

        require(base.hp == hp, "Presentation mutated station HP.");
        require(base.shield == shield, "Presentation mutated station shield.");
        require(base.inventory.get(Material.IRON) == iron, "Presentation mutated station inventory.");
        require(base.productionQueue.size() == queueSize, "Presentation mutated production state.");
    }

    private static void validateDenseHoverChoosesNearestStation() {
        Base farther = new Base("TEST:FAR", "TEST", Rules.DEFAULT_BASE, 180, 180);
        Base nearer = new Base("TEST:NEAR", "TEST", Rules.DEFAULT_BASE, 198, 180);
        BufferedImage image = new BufferedImage(320, 320, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            String selected = StationPresentation.resolveNearestHoverIdForTest(
                    g2, new Point(196, 180), farther, nearer);
            require("TEST:NEAR".equals(selected),
                    "Dense station hover did not resolve to exactly the nearest station.");
        } finally {
            g2.dispose();
        }
    }

    private static void validateForeignContextDoesNotLeakOperations() {
        Base foreign = new Base("FOREIGN:B1", "FOREIGN", Rules.DEFAULT_BASE, 0, 0);
        foreign.logisticsStatus = "SECRET ROUTE TO ALPHA";
        foreign.inventory.put(Material.IRON, 9999.0);
        String summary = StationPresentation.conciseStatus(foreign, foreign.type());
        require("Operational".equals(summary),
                "Foreign station hover exposed private operational information: " + summary);
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xff;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
