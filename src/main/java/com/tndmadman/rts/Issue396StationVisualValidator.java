package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Focused headless coverage for issue #396 station architecture and visual/gameplay isolation. */
public final class Issue396StationVisualValidator {
    private static final int IMAGE_SIZE = 360;
    private static final double CENTER = IMAGE_SIZE / 2.0;
    private static final Color OWNER = new Color(70, 180, 245);
    private static final Color OTHER_OWNER = new Color(235, 90, 80);

    private Issue396StationVisualValidator() { }

    public static void main(String[] args) {
        validateInteractionBoundsRemainLegacySized();
        validateVisualScaleHierarchy();
        validateMajorSilhouettesAreUnique();
        validateKeySilhouettesRemainUniqueAtFarLod();
        validateShipyardReadsAsOpenConstructionFrame();
        validateDeterministicAnimation();
        validateOwnershipAccentsAreRestrained();
        validateDecoyProfileIsAuthoredAcrossLod();
        validateRenderingDoesNotMutateStationState();
        System.out.println("Issue 396 station visual validation passed.");
    }

    private static void validateInteractionBoundsRemainLegacySized() {
        Map<String, Double> expected = new LinkedHashMap<>();
        expected.put("outpost", 64.0);
        expected.put("shipyard", 82.0);
        expected.put("laboratory", 74.0);
        expected.put("manufacturing", 74.0);
        expected.put(RadarTowerRules.TIER_ONE, 54.0);
        expected.put(RadarTowerRules.TIER_TWO, 66.0);
        expected.put(RadarTowerRules.TIER_THREE, 78.0);
        expected.put("signal_jammer", 68.0);
        expected.put("radar_decoy", 72.0);
        expected.put(IntelWarfareSystem.CONTACT_STATION, 48.0);

        for (Map.Entry<String, Double> entry : expected.entrySet()) {
            Base base = base(entry.getKey());
            double radius = entry.getValue();
            require(Math.abs(base.interactionRadius() - radius) < 0.001,
                    entry.getKey() + " interaction radius changed from the pre-396 renderer");
            require(base.contains(base.x + radius - 0.01, base.y),
                    entry.getKey() + " should contain a point immediately inside its legacy radius");
            require(!base.contains(base.x + radius + 0.01, base.y),
                    entry.getKey() + " should reject a point immediately outside its legacy radius");
        }
    }

    private static void validateVisualScaleHierarchy() {
        Base outpost = base("outpost");
        Base shipyard = base("shipyard");
        Base manufacturing = base("manufacturing");
        Base laboratory = base("laboratory");
        Base picket = base(RadarTowerRules.TIER_ONE);
        Base array = base(RadarTowerRules.TIER_TWO);
        Base nexus = base(RadarTowerRules.TIER_THREE);

        require(StationRenderer.visualExtent(shipyard) > StationRenderer.visualExtent(manufacturing),
                "shipyard should read larger than manufacturing");
        require(StationRenderer.visualExtent(manufacturing) > StationRenderer.visualExtent(outpost),
                "manufacturing should read larger than the outpost");
        require(StationRenderer.visualExtent(laboratory) > StationRenderer.visualExtent(outpost),
                "research lab should read larger than the outpost");
        require(StationRenderer.visualExtent(picket) < StationRenderer.visualExtent(array)
                        && StationRenderer.visualExtent(array) < StationRenderer.visualExtent(nexus),
                "radar tiers must increase monotonically in visual scale");
        require(StationRenderer.visualExtent(shipyard) >= 1.5 * shipyard.interactionRadius(),
                "shipyard visual architecture should substantially exceed its gameplay hit radius");
    }

    private static void validateMajorSilhouettesAreUnique() {
        String[] types = {
                "outpost", "shipyard", "manufacturing", "laboratory",
                RadarTowerRules.TIER_ONE, RadarTowerRules.TIER_TWO, RadarTowerRules.TIER_THREE,
                "signal_jammer", "radar_decoy"
        };
        Set<Long> hashes = new LinkedHashSet<>();
        for (String type : types) {
            long hash = silhouetteHash(render(type, 12.5, 1.0, OWNER));
            require(hashes.add(hash), "station silhouette unexpectedly duplicates an earlier type: " + type);
        }
    }

    private static void validateKeySilhouettesRemainUniqueAtFarLod() {
        String[] types = {
                "outpost", "shipyard", "manufacturing", "laboratory",
                RadarTowerRules.TIER_ONE, RadarTowerRules.TIER_TWO, RadarTowerRules.TIER_THREE,
                "signal_jammer"
        };
        Set<Long> hashes = new LinkedHashSet<>();
        for (String type : types) {
            long hash = silhouetteHash(render(type, 12.5, 0.12, OWNER));
            require(hashes.add(hash), "far-LOD silhouette collapsed into another station type: " + type);
        }
    }

    private static void validateShipyardReadsAsOpenConstructionFrame() {
        BufferedImage shipyard = render("shipyard", 12.5, 1.0, OWNER);
        BufferedImage manufacturing = render("manufacturing", 12.5, 1.0, OWNER);
        int shipyardCenter = occupiedInRegion(shipyard, -58, -22, 58, 22);
        int manufacturingCenter = occupiedInRegion(manufacturing, -58, -22, 58, 22);
        require(manufacturingCenter > shipyardCenter * 1.45,
                "shipyard center should remain visibly open compared with manufacturing's dense industrial body");
    }

    private static void validateDeterministicAnimation() {
        String[] types = {
                "outpost", "shipyard", "manufacturing", "laboratory",
                RadarTowerRules.TIER_ONE, RadarTowerRules.TIER_TWO, RadarTowerRules.TIER_THREE,
                "signal_jammer", "radar_decoy"
        };
        for (String type : types) {
            int[] first = pixels(render(type, 8.25, 1.0, OWNER));
            int[] repeat = pixels(render(type, 8.25, 1.0, OWNER));
            require(Arrays.equals(first, repeat), type + " rendering is not deterministic at a fixed simulation time");
        }

        String[] animated = {
                "outpost", "shipyard", "manufacturing", "laboratory",
                RadarTowerRules.TIER_ONE, RadarTowerRules.TIER_TWO, RadarTowerRules.TIER_THREE,
                "signal_jammer", "radar_decoy"
        };
        for (String type : animated) {
            int[] first = pixels(render(type, 3.0, 1.0, OWNER));
            int[] later = pixels(render(type, 5.4, 1.0, OWNER));
            require(pixelDifference(first, later) > 12,
                    type + " should contain visible ambient animation driven by simulation time");
        }
    }

    private static void validateOwnershipAccentsAreRestrained() {
        String[] types = {"shipyard", "manufacturing", "laboratory", RadarTowerRules.TIER_THREE};
        for (String type : types) {
            BufferedImage friendly = render(type, 6.75, 1.0, OWNER);
            BufferedImage hostile = render(type, 6.75, 1.0, OTHER_OWNER);
            require(silhouetteHash(friendly) == silhouetteHash(hostile),
                    type + " ownership color must not alter station geometry");
            int changed = pixelDifference(pixels(friendly), pixels(hostile));
            int occupied = occupiedPixels(friendly);
            require(changed > 20, type + " needs readable ownership accents");
            require(changed < occupied * 0.55,
                    type + " ownership color is too dominant; structural material should remain neutral");
        }
    }

    private static void validateDecoyProfileIsAuthoredAcrossLod() {
        String profile = IntelWarfareSystem.rule("radar_decoy").decoyProfile();
        require(profile != null && !profile.isBlank(), "strategic decoy must expose its configured visual profile");
        require(Set.of(RadarTowerRules.TIER_THREE, RadarTowerRules.TIER_TWO, RadarTowerRules.TIER_ONE,
                        "shipyard", "manufacturing", "laboratory", "outpost").contains(profile),
                "strategic decoy profile is not one of the supported authored station silhouettes: " + profile);

        Base decoy = base("radar_decoy");
        Base target = base(profile);
        require(Math.abs(StationRenderer.visualExtent(decoy) - StationRenderer.visualExtent(target)) <= 20,
                "decoy scale should approximate its configured profile");

        BufferedImage farDecoy = render("radar_decoy", 4.0, 0.12, OWNER);
        BufferedImage farTarget = render(profile, 4.0, 0.12, OWNER);
        require(maskSimilarity(farDecoy, farTarget) > 0.42,
                "far-LOD strategic decoy no longer resembles its configured profile: " + profile);
    }

    private static void validateRenderingDoesNotMutateStationState() {
        Base station = base("manufacturing");
        station.hp -= 73;
        station.shield -= 41;
        station.logisticsStatus = "visual-state-sentinel";
        double hp = station.hp;
        double shield = station.shield;
        String logistics = station.logisticsStatus;
        int queueSize = station.productionQueue.size();
        int inventoryHash = station.inventory.hashCode();

        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        StationRenderer.drawAtTime(g, station, OWNER, 9.5, 1.0);
        g.dispose();

        require(station.hp == hp && station.shield == shield,
                "station rendering must not mutate combat state");
        require(logistics.equals(station.logisticsStatus),
                "station rendering must not mutate logistics state");
        require(station.productionQueue.size() == queueSize && station.inventory.hashCode() == inventoryHash,
                "station rendering must not mutate production or inventory state");
    }

    private static Base base(String type) {
        return new Base("VIS-" + type, "VISUAL_TEST", type, CENTER, CENTER);
    }

    private static BufferedImage render(String type, double time, double scale, Color owner) {
        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        StationRenderer.drawAtTime(g, base(type), owner, time, scale);
        g.dispose();
        return image;
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    /** Hash only occupied pixels so colors/details do not substitute for a genuinely different silhouette. */
    private static long silhouetteHash(BufferedImage image) {
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                int alpha = (image.getRGB(x, y) >>> 24) & 0xff;
                hash ^= alpha > 18 ? 1 : 0;
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private static int occupiedPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xff) > 18) count++;
            }
        }
        return count;
    }

    private static int occupiedInRegion(BufferedImage image, int minX, int minY, int maxX, int maxY) {
        int count = 0;
        for (int dy = minY; dy <= maxY; dy++) {
            for (int dx = minX; dx <= maxX; dx++) {
                int x = (int)Math.round(CENTER + dx);
                int y = (int)Math.round(CENTER + dy);
                if (((image.getRGB(x, y) >>> 24) & 0xff) > 18) count++;
            }
        }
        return count;
    }

    private static double maskSimilarity(BufferedImage a, BufferedImage b) {
        int intersection = 0;
        int union = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                boolean aa = ((a.getRGB(x, y) >>> 24) & 0xff) > 18;
                boolean bb = ((b.getRGB(x, y) >>> 24) & 0xff) > 18;
                if (aa && bb) intersection++;
                if (aa || bb) union++;
            }
        }
        return union == 0 ? 1.0 : intersection / (double)union;
    }

    private static int pixelDifference(int[] first, int[] second) {
        require(first.length == second.length, "image buffers have different lengths");
        int changed = 0;
        for (int i = 0; i < first.length; i++) if (first[i] != second[i]) changed++;
        return changed;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
