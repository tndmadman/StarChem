package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.Set;

/** Headless validation for issue #391 system-specific space backgrounds. */
public final class Issue391SpaceBackgroundValidator {
    private static final int SCREEN_W = 1280;
    private static final int SCREEN_H = 720;

    private Issue391SpaceBackgroundValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("Issue #391 deterministic layered space background validation passed.");
    }

    static void validateOrThrow() {
        StarSystemDefinition standard = StarSystems.get(StarSystems.DEFAULT_SYSTEM_ID);
        StarSystemDefinition frontier = StarSystems.get(StarSystems.PLAYER_HOME_SYSTEM_ID);
        StarSystemDefinition nebula = StarSystems.get("nebula_expanse");
        StarSystemDefinition ice = StarSystems.get("ice_belt");
        StarSystemDefinition industrial = StarSystems.get("binary_forge");
        StarSystemDefinition warzone = StarSystems.get("warzone");

        require("STANDARD".equals(new SpaceBackgroundRenderer(standard).themeNameForTest()),
                "standard system did not resolve to the standard visual theme");
        require("FRONTIER".equals(new SpaceBackgroundRenderer(frontier).themeNameForTest()),
                "frontier system did not resolve to the frontier visual theme");
        require("NEBULA".equals(new SpaceBackgroundRenderer(nebula).themeNameForTest()),
                "Nebula Expanse did not resolve to the nebula visual theme");
        require("ICE".equals(new SpaceBackgroundRenderer(ice).themeNameForTest()),
                "Ice Belt did not resolve to the ice visual theme");
        require("INDUSTRIAL".equals(new SpaceBackgroundRenderer(industrial).themeNameForTest()),
                "Binary Forge did not resolve to the industrial visual theme");
        require("WARZONE".equals(new SpaceBackgroundRenderer(warzone).themeNameForTest()),
                "Warzone did not resolve to the warzone visual theme");

        SpaceBackgroundRenderer nebulaA = new SpaceBackgroundRenderer(nebula);
        SpaceBackgroundRenderer nebulaB = new SpaceBackgroundRenderer(nebula);
        require(nebulaA.seedForTest() == nebulaB.seedForTest(),
                "same system definition generated different deterministic seeds");

        long nebulaHashA = imageHash(render(nebulaA, 0.36, 4200, 3400, false));
        long nebulaHashB = imageHash(render(nebulaB, 0.36, 4200, 3400, false));
        require(nebulaHashA == nebulaHashB,
                "same system/camera state did not render deterministically");

        Set<Long> themeHashes = new LinkedHashSet<>();
        themeHashes.add(imageHash(render(new SpaceBackgroundRenderer(nebula), 0.36, 4200, 3400, false)));
        themeHashes.add(imageHash(render(new SpaceBackgroundRenderer(ice), 0.36, 4200, 3400, false)));
        themeHashes.add(imageHash(render(new SpaceBackgroundRenderer(industrial), 0.36, 4200, 3400, false)));
        themeHashes.add(imageHash(render(new SpaceBackgroundRenderer(warzone), 0.36, 4200, 3400, false)));
        require(themeHashes.size() == 4,
                "system themes were not visually distinct under the same camera state");

        BufferedImage gridCovered = render(new SpaceBackgroundRenderer(standard), 0.36, 3500, 2800, true);
        require(countExactRgb(gridCovered, 22, 33, 48) == 0,
                "prototype grid remained visible through the normal background renderer");
        require(allPixelsOpaque(gridCovered),
                "background renderer left transparent holes that could expose the prototype grid");

        long pannedA = imageHash(render(nebulaA, 0.36, 3600, 3000, false));
        long pannedB = imageHash(render(nebulaA, 0.36, 4050, 3320, false));
        require(pannedA != pannedB,
                "camera movement did not change layered background presentation");

        BufferedImage minZoom = render(new SpaceBackgroundRenderer(warzone), 0.36, 5000, 4000, false);
        BufferedImage maxZoom = render(new SpaceBackgroundRenderer(warzone), 0.712, 5000, 4000, false);
        require(allPixelsOpaque(minZoom) && allPixelsOpaque(maxZoom),
                "background failed to cover the viewport at a supported zoom level");
    }

    private static BufferedImage render(SpaceBackgroundRenderer renderer, double zoom,
                                        double cameraX, double cameraY, boolean paintPrototypeGridFirst) {
        BufferedImage image = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        g2.setColor(Color.MAGENTA);
        g2.fillRect(0, 0, SCREEN_W, SCREEN_H);
        g2.scale(zoom, zoom);
        g2.translate(-cameraX, -cameraY);

        if (paintPrototypeGridFirst) {
            g2.setColor(new Color(9, 15, 24));
            g2.fillRect(0, 0, 24000, 20000);
            g2.setColor(new Color(22, 33, 48));
            for (int x = 0; x <= 24000; x += 160) g2.drawLine(x, 0, x, 20000);
            for (int y = 0; y <= 20000; y += 160) g2.drawLine(0, y, 24000, y);
        }

        renderer.draw(g2);
        g2.dispose();
        return image;
    }

    private static long imageHash(BufferedImage image) {
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                hash ^= image.getRGB(x, y);
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private static int countExactRgb(BufferedImage image, int red, int green, int blue) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if (((argb >>> 16) & 0xFF) == red
                        && ((argb >>> 8) & 0xFF) == green
                        && (argb & 0xFF) == blue) count++;
            }
        }
        return count;
    }

    private static boolean allPixelsOpaque(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) != 255) return false;
            }
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
