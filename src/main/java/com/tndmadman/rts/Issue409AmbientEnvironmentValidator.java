package com.tndmadman.rts;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.Set;

/** Regression coverage for deterministic, bounded ambient environmental rendering in issue #409. */
public final class Issue409AmbientEnvironmentValidator {
    private static final int MAX_DECORATIONS = 280;
    private static final int IMAGE_W = 1280;
    private static final int IMAGE_H = 720;
    private static final double[] CAMERA_FRACTIONS = {0.0, 0.5, 1.0};

    private Issue409AmbientEnvironmentValidator() { }

    public static void main(String[] args) {
        validateDistinctThemes();
        validateDeterministicBoundedFields();
        validateZoomDensityScaling();
        validateParallaxDepth();
        validateDeterministicRendering();
        validateVisibleMotion();
        validateHeadlessRendering();
        System.out.println("Issue #409 ambient environment validator passed.");
    }

    private static void validateDistinctThemes() {
        Set<AmbientSystemRenderer.Theme> themes = EnumSet.noneOf(AmbientSystemRenderer.Theme.class);
        for (StarSystemDefinition definition : StarSystems.options()) {
            themes.add(AmbientSystemRenderer.themeFor(definition));
        }
        require(themes.size() >= 3,
                "Issue #409 requires at least three distinct ambient system treatments; found " + themes.size() + '.');

        require(AmbientSystemRenderer.themeFor(StarSystems.get("ice_belt")) == AmbientSystemRenderer.Theme.ICE,
                "Ice Belt did not resolve to the ICE ambient theme.");
        require(AmbientSystemRenderer.themeFor(StarSystems.get("warzone")) == AmbientSystemRenderer.Theme.WARZONE,
                "Warzone did not resolve to the WARZONE ambient theme.");
        require(AmbientSystemRenderer.themeFor(StarSystems.get("nebula_expanse")) == AmbientSystemRenderer.Theme.NEBULA,
                "Nebula Expanse did not resolve to the NEBULA ambient theme.");
        require(AmbientSystemRenderer.themeFor(StarSystems.get("ancient_graveyard")) == AmbientSystemRenderer.Theme.GRAVEYARD,
                "Ancient Graveyard did not resolve to the GRAVEYARD ambient theme.");

        AmbientSystemRenderer.Snapshot ice = AmbientSystemRenderer.snapshotForTest(StarSystems.get("ice_belt"));
        AmbientSystemRenderer.Snapshot war = AmbientSystemRenderer.snapshotForTest(StarSystems.get("warzone"));
        AmbientSystemRenderer.Snapshot nebula = AmbientSystemRenderer.snapshotForTest(StarSystems.get("nebula_expanse"));
        AmbientSystemRenderer.Snapshot graveyard = AmbientSystemRenderer.snapshotForTest(StarSystems.get("ancient_graveyard"));
        require(ice.wrecks() == 0 && ice.midBits() > 0, "Ice treatment should use fragments rather than wreck silhouettes.");
        require(war.wrecks() > 0, "Warzone treatment is missing wreck/debris dressing.");
        require(nebula.wisps() > 0, "Nebula treatment is missing gas wisps.");
        require(graveyard.wrecks() > war.wrecks(), "Graveyard should be more wreck-dense than Warzone.");
    }

    private static void validateDeterministicBoundedFields() {
        for (StarSystemDefinition definition : StarSystems.options()) {
            AmbientSystemRenderer.Snapshot first = AmbientSystemRenderer.snapshotForTest(definition);
            AmbientSystemRenderer.Snapshot second = AmbientSystemRenderer.snapshotForTest(definition);
            require(first.signature() == second.signature(),
                    "Ambient field was not stable for " + definition.id() + '.');
            require(first.totalDecorations() > 0,
                    "Ambient field was empty for " + definition.id() + '.');
            require(first.totalDecorations() <= MAX_DECORATIONS,
                    "Ambient field exceeded the hard bound for " + definition.id() + ": " + first.totalDecorations());
        }
    }

    private static void validateZoomDensityScaling() {
        int far = AmbientSystemRenderer.densityStrideForTest(0.36);
        int middle = AmbientSystemRenderer.densityStrideForTest(0.50);
        int near = AmbientSystemRenderer.densityStrideForTest(0.712);
        require(far > middle && middle >= near,
                "Ambient density does not reduce as the camera zooms farther out.");
        require(near == 1, "Normal close tactical zoom should retain the full ambient field.");
    }

    private static void validateParallaxDepth() {
        double cameraMove = 400;
        double far = AmbientSystemRenderer.screenParallaxDeltaForTest(cameraMove, 0.42);
        double mid = AmbientSystemRenderer.screenParallaxDeltaForTest(cameraMove, 0.68);
        require(far > 0 && far < mid, "Far layer should move less on screen than the mid layer.");
        require(mid < cameraMove, "Ambient parallax must remain slower than ordinary world-space motion.");
    }

    private static void validateDeterministicRendering() {
        for (String id : new String[]{"ice_belt", "warzone", "nebula_expanse", "ancient_graveyard", "pulsar_reach"}) {
            long first = renderHash(StarSystems.get(id), 37.25, 0.50);
            long second = renderHash(StarSystems.get(id), 37.25, 0.50);
            require(first == second, "Ambient rendering was not deterministic for " + id + '.');
        }
    }

    private static void validateVisibleMotion() {
        int changed = 0;
        for (String id : new String[]{"ice_belt", "warzone", "nebula_expanse", "volcanic_crucible"}) {
            StarSystemDefinition definition = StarSystems.get(id);
            long before = renderHash(definition, 11.0, 0.60);
            long after = renderHash(definition, 28.0, 0.60);
            if (before != after) changed++;
        }
        require(changed >= 3,
                "Ambient treatments are not producing enough visible environmental motion; changed themes=" + changed + '.');
    }

    private static void validateHeadlessRendering() {
        BufferedImage image = new BufferedImage(IMAGE_W, IMAGE_H, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g2 = image.createGraphics();
        try {
            g2.setClip(0, 0, image.getWidth(), image.getHeight());
            g2.scale(0.50, 0.50);
            g2.translate(-2400, -1800);
            double time = 37.25;
            for (String id : new String[]{"ice_belt", "warzone", "nebula_expanse", "ancient_graveyard", "pulsar_reach"}) {
                StarSystemDefinition definition = StarSystems.get(id);
                AmbientSystemRenderer.drawBackdrop(g2, definition, time);
                AmbientSystemRenderer.drawForeground(g2, definition, time);
                time += 11.5;
            }
        } finally {
            g2.dispose();
        }
    }

    /**
     * Hashes a deterministic 3x3 grid of tactical viewports instead of requiring the arbitrary
     * system-center viewport to contain decoration. Ambient fields are intentionally sparse and
     * bounded, so an empty individual slice is valid; a theme that cannot render anywhere is not.
     */
    private static long renderHash(StarSystemDefinition definition, double time, double scale) {
        BufferedImage image = new BufferedImage(IMAGE_W, IMAGE_H, BufferedImage.TYPE_INT_ARGB_PRE);
        double viewW = IMAGE_W / scale;
        double viewH = IMAGE_H / scale;
        double maxCameraX = Math.max(0, definition.width() - viewW);
        double maxCameraY = Math.max(0, definition.height() - viewH);

        long combinedHash = 0xcbf29ce484222325L;
        int totalVisiblePixels = 0;
        int viewportIndex = 0;

        for (double fy : CAMERA_FRACTIONS) {
            for (double fx : CAMERA_FRACTIONS) {
                Graphics2D g2 = image.createGraphics();
                try {
                    g2.setComposite(AlphaComposite.Clear);
                    g2.fillRect(0, 0, IMAGE_W, IMAGE_H);
                    g2.setComposite(AlphaComposite.SrcOver);
                    g2.setClip(0, 0, IMAGE_W, IMAGE_H);
                    double cameraX = maxCameraX * fx;
                    double cameraY = maxCameraY * fy;
                    g2.scale(scale, scale);
                    g2.translate(-cameraX, -cameraY);
                    AmbientSystemRenderer.drawBackdrop(g2, definition, time);
                    AmbientSystemRenderer.drawForeground(g2, definition, time);
                } finally {
                    g2.dispose();
                }

                HashSample sample = hashImage(image);
                totalVisiblePixels += sample.nonTransparentPixels();
                combinedHash ^= sample.hash() + 0x9E3779B97F4A7C15L + viewportIndex++;
                combinedHash *= 0x100000001b3L;
            }
        }

        require(totalVisiblePixels > 0,
                "Ambient renderer produced no visible pixels across sampled tactical viewports for " + definition.id() + '.');
        return combinedHash;
    }

    private static HashSample hashImage(BufferedImage image) {
        long hash = 0xcbf29ce484222325L;
        int nonTransparent = 0;
        for (int y = 0; y < IMAGE_H; y += 2) {
            for (int x = 0; x < IMAGE_W; x += 2) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) != 0) nonTransparent++;
                hash ^= argb & 0xffffffffL;
                hash *= 0x100000001b3L;
            }
        }
        return new HashSample(hash, nonTransparent);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record HashSample(long hash, int nonTransparentPixels) { }
}
