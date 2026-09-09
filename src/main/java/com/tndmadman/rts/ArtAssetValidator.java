package com.tndmadman.rts;

import java.awt.image.BufferedImage;
import java.net.URL;

/** Headless smoke validation for issue #401's reusable art-asset pipeline. */
public final class ArtAssetValidator {
    private static final String KNOWN_ASSET = "materials/hull-panels-01.png";

    private ArtAssetValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        boolean requireJar = args.length > 0 && "--require-jar".equals(args[0]);
        validate(requireJar);
        URL resource = ArtAssetValidator.class.getResource("/art/" + KNOWN_ASSET);
        System.out.println("StarChem art asset validation passed (protocol=" + resource.getProtocol() + ").");
    }

    static void validate(boolean requireJar) {
        ArtAssetCache.clearForValidation();
        URL resource = ArtAssetValidator.class.getResource("/art/" + KNOWN_ASSET);
        require(resource != null, "known art asset is missing from the runtime classpath");
        if (requireJar) require("jar".equalsIgnoreCase(resource.getProtocol()),
                "known art asset did not resolve from a packaged JAR: " + resource);

        BufferedImage first = ArtAssetCache.image(KNOWN_ASSET);
        require(!ArtAssetCache.isFallback(first), "known art asset decoded to fallback");
        require(first.getWidth() == 32 && first.getHeight() == 32, "known texture dimensions changed unexpectedly");
        require(hasTransparentAndVisiblePixels(first), "known texture does not preserve alpha");

        BufferedImage second = ArtAssetCache.image(KNOWN_ASSET);
        require(first == second, "repeated art loads did not reuse the cached image");

        int entriesBeforeMissing = ArtAssetCache.cacheEntryCountForValidation();
        BufferedImage missing = ArtAssetCache.image("materials/does-not-exist.png");
        require(ArtAssetCache.isFallback(missing), "missing art did not use deterministic fallback");
        BufferedImage missingAgain = ArtAssetCache.image("materials/does-not-exist.png");
        require(missing == missingAgain, "missing art fallback was not deterministic");
        require(ArtAssetCache.cacheEntryCountForValidation() == entriesBeforeMissing + 1,
                "missing art was not negative-cached");

        require(ArtAssetCache.isFallback(ArtAssetCache.image("../outside.png")),
                "path traversal was not rejected");
        require(ArtAssetCache.isFallback(ArtAssetCache.image("materials/not-an-image.txt")),
                "unsupported asset format did not use fallback");
        require(ArtAssetCache.cachedBytesForValidation() <= ArtAssetCache.maxBytesForValidation(),
                "art cache exceeded its configured memory budget");
    }

    private static boolean hasTransparentAndVisiblePixels(BufferedImage image) {
        boolean transparent = false;
        boolean visible = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = (image.getRGB(x, y) >>> 24) & 0xFF;
                transparent |= alpha == 0;
                visible |= alpha > 0;
            }
        }
        return transparent && visible;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Art asset validation failed: " + message);
    }
}
