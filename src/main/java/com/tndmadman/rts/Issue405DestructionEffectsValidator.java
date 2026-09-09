package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Regression gate for issue #405 destruction sequencing, budgets, cleanup and damaged-hull cues. */
public final class Issue405DestructionEffectsValidator {
    private Issue405DestructionEffectsValidator() { }

    public static void main(String[] args) {
        validateProfileMapping();
        validateBudgetsAndCleanup();
        validateDamageThreshold();
        validateHeadlessRendering();
        System.out.println("Issue #405 destruction effects validator passed.");
    }

    private static void validateProfileMapping() {
        require(DestructionProfile.forShipSize(ShipSize.SMALL) == DestructionProfile.QUICK,
                "Small ships must use the quick destruction profile.");
        require(DestructionProfile.forShipSize(ShipSize.LARGE) == DestructionProfile.STANDARD,
                "Large ships must use a staged destruction profile.");
        require(DestructionProfile.forShipSize(ShipSize.BATTLESHIP) == DestructionProfile.CAPITAL,
                "Battleships must use the capital destruction profile.");
        require(DestructionProfile.forShipSize(ShipSize.TITAN) == DestructionProfile.MAJOR,
                "Titans must use the major destruction profile.");
        require(DestructionProfile.forShipSize(ShipSize.MONOLITH) == DestructionProfile.MAJOR,
                "Monoliths must use the major destruction profile.");
    }

    private static void validateBudgetsAndCleanup() {
        for (DestructionProfile profile : DestructionProfile.values()) {
            require(profile.secondaryBursts <= 7, profile + " exceeds the secondary-blast cap.");
            require(profile.debrisFragments <= 28, profile + " exceeds the debris cap.");
            require(profile.vents <= 8, profile + " exceeds the vent cap.");
            require(profile.particleBudget <= 240, profile + " exceeds the particle cap.");
            require(profile.lifetimeSeconds > 0 && profile.lifetimeSeconds <= 75,
                    profile + " has an invalid cleanup lifetime.");
            if (profile.persistentWreck) {
                require(profile.wreckStartSeconds > 0 && profile.wreckStartSeconds < profile.lifetimeSeconds,
                        profile + " wreck lifetime is invalid.");
            }
            if (profile.coreFlash) {
                require(profile.coreTimeSeconds > profile.secondarySpanSeconds + 0.15,
                        profile + " core flash must follow its secondary-failure sequence.");
                require(profile.wreckStartSeconds > profile.coreTimeSeconds,
                        profile + " wreck must appear after the core event.");
            }

            ExplosionEffect effect = ExplosionEffect.validationEffect(profile);
            double elapsed = 0;
            int ticks = 0;
            while (effect.update(0.25)) {
                elapsed += 0.25;
                require(++ticks < 420, profile + " failed to clean itself up.");
            }
            require(elapsed >= profile.lifetimeSeconds * 0.90,
                    profile + " cleaned up before its intended visual lifecycle completed.");
            require(elapsed <= profile.lifetimeSeconds * 1.10 + 0.50,
                    profile + " persisted beyond its bounded cleanup window.");
        }
    }

    private static void validateDamageThreshold() {
        require(DamageStateEffects.severity(90, 100) == 0,
                "Healthy ships should not render damage-state effects.");
        require(DamageStateEffects.severity(47, 100) > 0,
                "Heavily damaged ships should render damage-state effects.");
        require(DamageStateEffects.severity(10, 100) > 0.70,
                "Critical hulls should receive a strong visual damage state.");
        require(DamageStateEffects.severity(0, 100) == 0,
                "Destroyed ships must be handed off to destruction effects instead of damage-state rendering.");
        require(DamageStateEffects.severity(Double.NaN, 100) == 0,
                "Invalid HP must not create visual effects.");
    }

    private static void validateHeadlessRendering() {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g2 = image.createGraphics();
        g2.setClip(0, 0, image.getWidth(), image.getHeight());
        try {
            ExplosionEffect effect = ExplosionEffect.validationEffect(DestructionProfile.MAJOR);
            effect.update(0.10);
            effect.draw(g2);
        } finally {
            g2.dispose();
        }
        boolean painted = false;
        for (int y = 100; y < 300 && !painted; y += 4) {
            for (int x = 100; x < 400; x += 4) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    painted = true;
                    break;
                }
            }
        }
        require(painted, "Destruction effect produced no visible headless render output.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
