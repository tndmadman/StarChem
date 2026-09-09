package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Headless regression checks for issue #399 resource-environment rendering. */
final class Issue399ResourceVisualValidator {
    private Issue399ResourceVisualValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        deterministicCachedRendering();
        asteroidIdentityVariesBySeed();
        depletionChangesPresentation();
        renderingDoesNotMutateGameplayState();
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
