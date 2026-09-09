package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.EnumSet;

/** Headless acceptance checks for issue #394 ship materials and controlled variation. */
final class Issue394ShipMaterialValidator {
    private static final int IMAGE_SIZE = 320;
    private static final Color RED_OWNER = new Color(220, 62, 62);
    private static final Color BLUE_OWNER = new Color(65, 112, 225);
    private static final double MIN_OWNER_ACCENT_CHANGE = 0.004;
    private static final double MAX_OWNER_ACCENT_CHANGE = 0.24;

    private Issue394ShipMaterialValidator() { }

    public static void main(String[] args) {
        if (Rules.SHIPS.isEmpty()) fail("No configured ships available for visual validation.");

        EnumSet<ShipVisualStyle.HullMaterial> treatments = EnumSet.noneOf(ShipVisualStyle.HullMaterial.class);
        int variantShips = 0;
        int physicalShips = 0;
        int checked = 0;
        int unitId = 7000;
        ShipType cacheVariantSample = Rules.findShip("monolith");

        for (ShipType type : Rules.SHIPS.values()) {
            ShipVisualDefinition visual = ShipVisualCatalog.forType(type);
            boolean anonymous = visual.hasFeature(ShipVisualDefinition.Feature.ANONYMOUS_CONTACT);

            BufferedImage baseline = render(type, RED_OWNER, 0);
            BufferedImage repeat = render(type, RED_OWNER, 0);
            if (!Arrays.equals(pixels(baseline), pixels(repeat))) {
                fail(type.id + " material rendering is not deterministic.");
            }

            if (!anonymous) {
                ShipVisualStyle.Style baseStyle = ShipVisualStyle.resolve(type, visual, RED_OWNER, 0);
                treatments.add(baseStyle.material());
                physicalShips++;
                if (cacheVariantSample == null) cacheVariantSample = type;

                boolean differs = false;
                for (int variant = 1; variant < ShipVisualStyle.VARIANT_COUNT; variant++) {
                    BufferedImage candidate = render(type, RED_OWNER, variant);
                    if (pixelDifferenceFraction(baseline, candidate) > 0.002) differs = true;
                    if (silhouetteDifferenceFraction(baseline, candidate) > 0.01) {
                        fail(type.id + " visual variation changes the authored silhouette.");
                    }
                }
                if (differs) variantShips++;

                BufferedImage otherOwner = render(type, BLUE_OWNER, 0);
                validateOwnershipAccent(type.id + " detailed render",
                        pixelDifferenceFraction(baseline, otherOwner));
            }

            Path2D hullA = ShipShape.create(type);
            Path2D hullB = ShipShape.create(type);
            Rectangle2D a = hullA.getBounds2D();
            Rectangle2D b = hullB.getBounds2D();
            if (!sameBounds(a, b)) fail(type.id + " authored hull geometry is not deterministic.");

            Unit unit = new Unit("ISSUE394", unitId++, type.id, 0, 0);
            ShipSpriteCache.Sprite cachedRed = ShipSpriteCache.sprite(unit, RED_OWNER);
            ShipSpriteCache.Sprite cachedRedRepeat = ShipSpriteCache.sprite(unit, RED_OWNER);
            if (cachedRed == null || cachedRed.image() == null || opaquePixels(cachedRed.image()) == 0) {
                fail(type.id + " medium-LOD cache did not produce a materialized sprite.");
            }
            if (cachedRedRepeat == null || cachedRedRepeat.image() == null
                    || !Arrays.equals(pixels(cachedRed.image()), pixels(cachedRedRepeat.image()))) {
                fail(type.id + " medium-LOD cache is not deterministic for a stable visual key.");
            }
            if (!anonymous) {
                ShipSpriteCache.Sprite cachedBlue = ShipSpriteCache.sprite(unit, BLUE_OWNER);
                if (cachedBlue == null || cachedBlue.image() == null || opaquePixels(cachedBlue.image()) == 0) {
                    fail(type.id + " medium-LOD cache did not render the alternate ownership accent.");
                }
                validateOwnershipAccent(type.id + " medium-LOD render",
                        pixelDifferenceFraction(cachedRed.image(), cachedBlue.image()));
            }
            checked++;
        }

        if (treatments.size() < 3) {
            fail("Expected at least three reusable hull material treatments, found " + treatments);
        }
        if (variantShips < Math.max(1, physicalShips / 2)) {
            fail("Controlled variants are not visibly represented across enough physical ship classes: "
                    + variantShips + "/" + physicalShips);
        }
        validateCachedVariant(cacheVariantSample);

        System.out.println("Issue #394 ship material validation passed for " + checked
                + " configured ships using " + treatments + ".");
    }

    private static void validateCachedVariant(ShipType type) {
        if (type == null) fail("No physical ship available for medium-LOD variant validation.");
        Unit first = new Unit("ISSUE394_VARIANT", 9100, type.id, 0, 0);
        int firstVariant = ShipVisualStyle.variantIndex(first);
        Unit second = null;
        for (int id = 9101; id < 9300; id++) {
            Unit candidate = new Unit("ISSUE394_VARIANT", id, type.id, 0, 0);
            if (ShipVisualStyle.variantIndex(candidate) != firstVariant) {
                second = candidate;
                break;
            }
        }
        if (second == null) fail("Could not resolve two bounded visual variants for " + type.id + ".");
        ShipSpriteCache.Sprite firstSprite = ShipSpriteCache.sprite(first, RED_OWNER);
        ShipSpriteCache.Sprite secondSprite = ShipSpriteCache.sprite(second, RED_OWNER);
        if (firstSprite == null || secondSprite == null
                || Arrays.equals(pixels(firstSprite.image()), pixels(secondSprite.image()))) {
            fail(type.id + " medium-LOD cache does not include deterministic visual variation.");
        }
    }

    private static void validateOwnershipAccent(String label, double ownershipChange) {
        if (ownershipChange < MIN_OWNER_ACCENT_CHANGE) {
            fail(label + " does not show a readable ownership accent: " + ownershipChange);
        }
        if (ownershipChange > MAX_OWNER_ACCENT_CHANGE) {
            fail(label + " uses player color across too much of the visible ship: " + ownershipChange);
        }
    }

    private static BufferedImage render(ShipType type, Color owner, int variant) {
        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(IMAGE_SIZE / 2.0, IMAGE_SIZE / 2.0);
        ShipShape.draw(g, type, owner, variant);
        g.dispose();
        return image;
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static double pixelDifferenceFraction(BufferedImage a, BufferedImage b) {
        int[] left = pixels(a);
        int[] right = pixels(b);
        int visible = 0;
        int changed = 0;
        for (int i = 0; i < left.length; i++) {
            int alphaA = left[i] >>> 24;
            int alphaB = right[i] >>> 24;
            if (alphaA == 0 && alphaB == 0) continue;
            visible++;
            if (colorDistance(left[i], right[i]) > 36 || Math.abs(alphaA - alphaB) > 24) changed++;
        }
        return visible == 0 ? 0 : changed / (double)visible;
    }

    private static double silhouetteDifferenceFraction(BufferedImage a, BufferedImage b) {
        int[] left = pixels(a);
        int[] right = pixels(b);
        int visible = 0;
        int changed = 0;
        for (int i = 0; i < left.length; i++) {
            boolean opaqueA = (left[i] >>> 24) >= 32;
            boolean opaqueB = (right[i] >>> 24) >= 32;
            if (!opaqueA && !opaqueB) continue;
            visible++;
            if (opaqueA != opaqueB) changed++;
        }
        return visible == 0 ? 0 : changed / (double)visible;
    }

    private static int opaquePixels(BufferedImage image) {
        int count = 0;
        for (int pixel : pixels(image)) if ((pixel >>> 24) >= 32) count++;
        return count;
    }

    private static int colorDistance(int a, int b) {
        int dr = ((a >>> 16) & 0xFF) - ((b >>> 16) & 0xFF);
        int dg = ((a >>> 8) & 0xFF) - ((b >>> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return Math.abs(dr) + Math.abs(dg) + Math.abs(db);
    }

    private static boolean sameBounds(Rectangle2D a, Rectangle2D b) {
        return Math.abs(a.getX() - b.getX()) < 0.0001
                && Math.abs(a.getY() - b.getY()) < 0.0001
                && Math.abs(a.getWidth() - b.getWidth()) < 0.0001
                && Math.abs(a.getHeight() - b.getHeight()) < 0.0001;
    }

    private static void fail(String message) {
        throw new IllegalStateException(message);
    }
}
