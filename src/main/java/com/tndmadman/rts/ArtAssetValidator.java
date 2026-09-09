package com.tndmadman.rts;

import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Headless smoke validation for issue #401's reusable art-asset pipeline. */
public final class ArtAssetValidator {
    private static final String KNOWN_ASSET = "materials/hull-panels-01.png";
    private static final String CORRUPT_ASSET = "validation/corrupt-image.png";

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

        Logger logger = Logger.getLogger(ArtAssetCache.class.getName());
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            int entriesBeforeMissing = ArtAssetCache.cacheEntryCountForValidation();
            BufferedImage missing = ArtAssetCache.image("materials/does-not-exist.png");
            require(ArtAssetCache.isFallback(missing), "missing art did not use deterministic fallback");
            BufferedImage missingAgain = ArtAssetCache.image("materials/does-not-exist.png");
            require(missing == missingAgain, "missing art fallback was not deterministic");
            require(ArtAssetCache.cacheEntryCountForValidation() == entriesBeforeMissing + 1,
                    "missing art was not negative-cached");
            require(handler.countContaining("Missing art asset") == 1,
                    "missing art did not log exactly one warning");

            BufferedImage corrupt = ArtAssetCache.image(CORRUPT_ASSET);
            require(ArtAssetCache.isFallback(corrupt), "corrupt PNG did not use deterministic fallback");
            BufferedImage corruptAgain = ArtAssetCache.image(CORRUPT_ASSET);
            require(corrupt == corruptAgain, "corrupt PNG fallback was not negative-cached");
            require(handler.countContaining("Could not decode art asset") == 1,
                    "corrupt PNG did not log exactly one decode warning");
        } finally {
            logger.removeHandler(handler);
            handler.close();
        }

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

    private static final class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override public void publish(LogRecord record) {
            if (record != null && isLoggable(record)) messages.add(record.getMessage());
        }

        int countContaining(String fragment) {
            int count = 0;
            for (String message : messages) {
                if (message != null && message.contains(fragment)) count++;
            }
            return count;
        }

        @Override public void flush() { }
        @Override public void close() { messages.clear(); }
    }
}
