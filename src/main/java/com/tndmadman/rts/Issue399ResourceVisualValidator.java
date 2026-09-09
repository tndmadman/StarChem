package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Headless regression checks for issue #399 resource-environment rendering. */
final class Issue399ResourceVisualValidator {
    private Issue399ResourceVisualValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        deterministicCachedRendering();
        repeatedRenderingReusesCache();
        asteroidIdentityVariesBySeed();
        depletionChangesPresentation();
        selectionRemainsDistinct();
        inactiveNodesRenderNothing();
        renderingDoesNotMutateGameplayState();
        lodReducesBackdropWork();
        cacheRemainsBounded();
        System.out.println("Issue399ResourceVisualValidator: OK");
    }

    private static void deterministicCachedRendering() {
        ResourceNode node = new ResourceNode(77, "Deterministic iron", NodeKind.SILICATE_ROCK,
                Material.IRON, 110, 110, 100, 8, 4.2);
        ResourceFieldRenderer.clearCacheForTesting();
        long first = imageHash(render(node, false));
        ResourceFieldRenderer.clearCacheForTesting();
        long second = imageHash(render(node, false));
        require(first == second, "same authoritative node must render deterministically across cache rebuilds");
    }

    private static void repeatedRenderingReusesCache() {
        ResourceNode node = new ResourceNode(78, "Cached iron", NodeKind.SILICATE_ROCK,
                Material.IRON, 110, 110, 100, 8, 4.2);
        ResourceFieldRenderer.clearCacheForTesting();
        render(node, false);
        int firstSize = ResourceFieldRenderer.cacheSizeForTesting();
        require(firstSize > 0, "first environmental draw should populate the sprite cache");
        for (int i = 0; i < 20; i++) render(node, false);
        require(ResourceFieldRenderer.cacheSizeForTesting() == firstSize,
                "repeated draws of the same region should reuse cached decorative geometry");
    }

    private static void asteroidIdentityVariesBySeed() {
        ResourceNode first = new ResourceNode(81, "Iron A", NodeKind.SILICATE_ROCK,
                Material.IRON, 110, 110, 100, 8, 4.2);
        ResourceNode second = new ResourceNode(82, "Iron B", NodeKind.SILICATE_ROCK,
                Material.IRON, 110, 110, 100, 8, 4.2);
        require(imageHash(render(first, false)) != imageHash(render(second, false)),
                "different asteroid ids should produce visibly different deterministic silhouettes/field dressing");
    }

    private static void depletionChangesPresentation() {
        ResourceNode rock = new ResourceNode(91, "Copper", NodeKind.SILICATE_ROCK,
                Material.COPPER, 110, 110, 100, 8, 4.5);
        long fullRock = imageHash(render(rock, false));
        rock.amount = 14;
        long depletedRock = imageHash(render(rock, false));
        require(fullRock != depletedRock, "rock depletion should change physical presentation");

        ResourceNode gas = new ResourceNode(97, "Methane", NodeKind.GAS_CLOUD,
                Material.METHANE, 110, 110, 100, 8, 5.0);
        long fullGas = imageHash(render(gas, false));
        gas.amount = 12;
        long depletedGas = imageHash(render(gas, false));
        require(fullGas != depletedGas, "gas depletion should change field density/presentation");
    }

    private static void selectionRemainsDistinct() {
        ResourceNode node = new ResourceNode(99, "Selectable nickel", NodeKind.SILICATE_ROCK,
                Material.NICKEL, 110, 110, 100, 8, 4.4);
        BufferedImage idle = render(node, false);
        BufferedImage selected = render(node, true);
        require(imageHash(idle) != imageHash(selected), "selected resource must have a distinct visual state");
        require(nonTransparentPixels(selected) > nonTransparentPixels(idle),
                "selected resource should add a readable target ring rather than replacing its node art");
    }

    private static void inactiveNodesRenderNothing() {
        ResourceNode node = new ResourceNode(100, "Inactive gas", NodeKind.GAS_CLOUD,
                Material.HYDROGEN, 110, 110, 100, 8, 5.0);
        node.active = false;
        require(nonTransparentPixels(render(node, true)) == 0,
                "inactive authoritative nodes must not leave decorative or selectable visuals behind");
    }

    private static void renderingDoesNotMutateGameplayState() {
        ResourceNode node = new ResourceNode(101, "Invariant", NodeKind.SILICATE_ROCK,
                Material.TITANIUM, 70, 85, 240, 9, 5.2);
        node.amount = 131;
        node.respawnTimer = 3.25;
        node.orbit(400, 420, 250, 1.25, 0.014);

        double x = node.x;
        double y = node.y;
        double amount = node.amount;
        double respawn = node.respawnTimer;
        double angle = node.orbitAngle;
        boolean active = node.active;
        boolean orbiting = node.orbiting;

        render(node, true);

        require(Double.compare(x, node.x) == 0, "render changed node x");
        require(Double.compare(y, node.y) == 0, "render changed node y");
        require(Double.compare(amount, node.amount) == 0, "render changed resource amount");
        require(Double.compare(respawn, node.respawnTimer) == 0, "render changed respawn timer");
        require(Double.compare(angle, node.orbitAngle) == 0, "render changed orbit angle");
        require(active == node.active, "render changed active state");
        require(orbiting == node.orbiting, "render changed orbiting state");
    }

    private static void lodReducesBackdropWork() {
        int farEntries = populateLodCache(0.36);
        int nearEntries = populateLodCache(0.70);
        require(farEntries > 0, "far LOD should retain some environmental context");
        require(farEntries < nearEntries,
                "far LOD should generate fewer decorative field variants than near LOD");
    }

    private static int populateLodCache(double scale) {
        ResourceFieldRenderer.clearCacheForTesting();
        BufferedImage image = new BufferedImage(220, 220, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.scale(scale, scale);
        for (int i = 0; i < 96; i++) {
            ResourceNode rock = new ResourceNode(2000 + i, "LOD rock " + i, NodeKind.SILICATE_ROCK,
                    Material.IRON, 110, 110, 100, 8, 4.2);
            ResourceFieldRenderer.draw(g, rock, false);
            ResourceNode gas = new ResourceNode(3000 + i, "LOD gas " + i, NodeKind.GAS_CLOUD,
                    Material.METHANE, 110, 110, 100, 8, 5.0);
            ResourceFieldRenderer.draw(g, gas, false);
        }
        g.dispose();
        return ResourceFieldRenderer.cacheSizeForTesting();
    }

    private static void cacheRemainsBounded() {
        ResourceFieldRenderer.clearCacheForTesting();
        Material[] materials = Material.values();
        for (int i = 0; i < 520; i++) {
            NodeKind kind = (i & 1) == 0 ? NodeKind.SILICATE_ROCK : NodeKind.GAS_CLOUD;
            ResourceNode node = new ResourceNode(1000 + i, "Cache " + i, kind,
                    materials[i % materials.length], 32, 32, 100, 8, kind == NodeKind.GAS_CLOUD ? 5 : 4);
            node.amount = switch (i & 3) {
                case 0 -> 100;
                case 1 -> 60;
                case 2 -> 30;
                default -> 10;
            };
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            ResourceFieldRenderer.draw(g, node, false);
            g.dispose();
        }
        require(ResourceFieldRenderer.cacheSizeForTesting() <= ResourceFieldRenderer.maxCacheEntriesForTesting(),
                "resource field sprite cache exceeded its hard bound");
    }

    private static BufferedImage render(ResourceNode node, boolean selected) {
        BufferedImage image = new BufferedImage(220, 220, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        node.draw(g, selected);
        g.dispose();
        return image;
    }

    private static int nonTransparentPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) count++;
            }
        }
        return count;
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
