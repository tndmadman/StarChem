package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Headless acceptance coverage for issue #385 station identity and visual config safety. */
public final class StationVisualIdentityValidator {
    private static final String[] REQUIRED = {"outpost", "shipyard", "laboratory", "manufacturing"};
    private static final Color TEST_COLOR = new Color(72, 164, 224);

    private StationVisualIdentityValidator() { }

    public static void main(String[] args) throws Exception {
        Path path = args.length > 0 ? Path.of(args[0]) : StationVisualCatalog.CONFIG;
        Map<String,StationVisualDefinition> definitions = StationVisualCatalog.loadForValidation(path);
        validateCoverage(definitions);
        validateDistinctRendering(definitions);
        validateFallback();
        validateMalformedRejection();
        validateOutOfBoundsRejection();
        System.out.println("Station visual identity validation passed: " + path + " | stations=" + REQUIRED.length);
    }

    private static void validateCoverage(Map<String,StationVisualDefinition> definitions) {
        Set<StationVisualPreset> presets = new HashSet<>();
        for (String id : REQUIRED) {
            require(Rules.findBase(id) != null, "Required station is missing from rules: " + id);
            StationVisualDefinition visual = definitions.get(id);
            require(visual != null, "Missing station visual definition: " + id);
            presets.add(visual.preset());
            require(visual.armCount() + visual.ringCount() + visual.moduleCount() > 0,
                    "Station visual has no functional structure: " + id);
        }
        require(presets.size() == REQUIRED.length, "Major station types must use distinct macro-silhouette presets.");
    }

    private static void validateDistinctRendering(Map<String,StationVisualDefinition> definitions) {
        Set<Long> hashes = new HashSet<>();
        for (String id : REQUIRED) {
            Base base = new Base("visual-validator-" + id, "VISUAL_VALIDATOR", id, 180, 180);
            BufferedImage image = new BufferedImage(360, 360, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean drawn;
            try { drawn = StationVisualRenderer.draw(g, base, TEST_COLOR); }
            finally { g.dispose(); }
            require(drawn, "Station visual renderer declined configured station: " + id);
            require(!empty(image), "Station visual rendered no pixels: " + id);
            require(hashes.add(pixelHash(image)), "Station visual raster is not distinct: " + id);
        }
    }

    private static void validateFallback() {
        Base radar = new Base("visual-validator-radar", "VISUAL_VALIDATOR", RadarTowerRules.TIER_ONE, 180, 180);
        BufferedImage image = new BufferedImage(360, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        boolean rich;
        try { rich = StationVisualRenderer.draw(g, radar, TEST_COLOR); }
        finally { g.dispose(); }
        require(!rich, "Specialized radar stations must remain on their established renderer fallback.");
    }

    private static void validateMalformedRejection() throws IOException {
        Path temp = Files.createTempFile("starchem-station-visual-malformed-", ".json");
        try {
            Files.writeString(temp, "{\"stations\":{\"outpost\":{\"preset\":\"NOT_A_STATION\"}}}");
            boolean rejected = false;
            try { StationVisualCatalog.loadForValidation(temp); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "Malformed station visual presets must be rejected by strict validation.");
        } finally { Files.deleteIfExists(temp); }
    }

    private static void validateOutOfBoundsRejection() throws IOException {
        Path temp = Files.createTempFile("starchem-station-visual-bounds-", ".json");
        try {
            Files.writeString(temp, "{\"stations\":{\"shipyard\":{\"scale\":99}}}");
            boolean rejected = false;
            try { StationVisualCatalog.loadForValidation(temp); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "Out-of-bounds station visual values must be rejected by strict validation.");
        } finally { Files.deleteIfExists(temp); }
    }

    private static long pixelHash(BufferedImage image) {
        long hash = 0xcbf29ce484222325L;
        for (int y=0;y<image.getHeight();y+=3) for (int x=0;x<image.getWidth();x+=3) {
            hash ^= image.getRGB(x,y); hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static boolean empty(BufferedImage image) {
        for (int y=0;y<image.getHeight();y++) for (int x=0;x<image.getWidth();x++)
            if ((image.getRGB(x,y) >>> 24) != 0) return false;
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
