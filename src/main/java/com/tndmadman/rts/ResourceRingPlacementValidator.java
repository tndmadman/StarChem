package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Regression tests for authored belt boundaries, depletion and save migration. */
public final class ResourceRingPlacementValidator {
    private ResourceRingPlacementValidator() { }

    public static void main(String[] args) {
        verifyAllTemplates();
        verifyAluminumExhaustion();
        verifyAuthoritativeRespawn();
        verifySavedAndLegacyBeltIdentity();
        System.out.println("Resource ring placement, respawn, and save migration validation passed.");
    }

    private static void verifyAllTemplates() {
        int count = 0;
        for (StarSystemDefinition system : StarSystems.options()) {
            for (int seed = 0; seed < 3; seed++) {
                Random random = new Random(391_171L + seed * 199L + system.id().hashCode());
                CelestialSystem celestial = new CelestialSystem(system, new Random(seed + 11L));
                List<ResourceNode> nodes = new ArrayList<>();
                ResourceSpawner.seed(nodes, celestial, random, 1, system.resourceBelts());
                Map<String,Integer> byBelt = new HashMap<>();
                for (ResourceNode node : nodes) {
                    ResourceBelt belt = ResourceRingPlacement.resolve(node, system);
                    require(belt != null, "Unassigned authored node in " + system.id());
                    require(belt.materials.contains(node.material), "Wrong material in belt " + belt.id);
                    require(node.resourceBeltId.equals(belt.id), "Missing stable belt ID");
                    require(ResourceRingPlacement.withinRadius(node, belt), "Initial node outside " + belt.id);
                    require(ResourceRingPlacement.withinArc(node.orbitAngle, node.resourceBeltCenterAngle,
                            belt.arc), "Initial node outside configured arc " + belt.id);
                    byBelt.merge(belt.id, 1, Integer::sum);
                    count++;
                }
                for (ResourceBelt belt : system.resourceBelts()) {
                    require(byBelt.getOrDefault(belt.id, 0) == belt.count, "Wrong node count in " + belt.id);
                    ResourceNode sample = nodes.stream().filter(n -> n.resourceBeltId.equals(belt.id))
                            .findFirst().orElse(null);
                    if (sample == null) continue;
                    int id = sample.id;
                    Material material = sample.material;
                    double capacity = sample.maxAmount;
                    sample.deplete();
                    ResourceSpawner.relocate(sample, nodes, List.of(), celestial, random, system, 6_000.0);
                    require(sample.active && sample.id == id && sample.material == material,
                            "Depletion changed material or identity in " + belt.id);
                    require(Math.abs(sample.amount - capacity) < 1e-7, "Capacity not restored in " + belt.id);
                    require(ResourceRingPlacement.withinRadius(sample, belt), "Respawn escaped " + belt.id);
                    double phase = sample.resourceBeltCenterAngle + ResourceSpawner.speedFor(belt.orbit) * 6_000.0;
                    require(ResourceRingPlacement.withinArc(sample.orbitAngle, phase, belt.arc),
                            "Respawn escaped angular cluster " + belt.id);
                }
            }
        }
        require(count > 1_000, "Too few authored nodes checked");
    }

    private static void verifyAluminumExhaustion() {
        StarSystemDefinition system = StarSystems.defaultSystem();
        CelestialSystem celestial = new CelestialSystem(system, new Random(800));
        List<ResourceNode> nodes = new ArrayList<>();
        Random random = new Random(101);
        ResourceSpawner.seed(nodes, celestial, random, 1, system.resourceBelts());
        ResourceBelt aluminumBelt = system.resourceBelts().stream()
                .filter(b -> b.materials.contains(Material.ALUMINUM)).findFirst().orElseThrow();
        List<ResourceNode> aluminum = nodes.stream()
                .filter(n -> n.material == Material.ALUMINUM && n.resourceBeltId.equals(aluminumBelt.id))
                .toList();
        require(!aluminum.isEmpty(), "Aluminum fixture did not spawn");
        for (ResourceNode node : aluminum) node.deplete();
        for (int cycle = 0; cycle < 3; cycle++) {
            for (ResourceNode node : aluminum) {
                // There are no active aluminum neighbors on the first respawn;
                // the belt definition itself must be sufficient.
                ResourceSpawner.relocate(node, nodes, List.of(), celestial, random, system, 500.0 + cycle * 500.0);
                require(node.active && node.material == Material.ALUMINUM, "Aluminum did not return");
                require(ResourceRingPlacement.withinRadius(node, aluminumBelt), "Aluminum escaped home belt");
            }
            for (ResourceNode node : aluminum) node.deplete();
        }
    }

    private static void verifyAuthoritativeRespawn() {
        World world = new World("Ring validation", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        try {
            world.activateSystem(StarSystems.DEFAULT_SYSTEM_ID);
            ResourceNode sample = world.resources.stream().filter(n -> n.resourceBeltId.equals("inner_industrial_belt"))
                    .findFirst().orElseThrow();
            sample.deplete();
            new ResourceRespawnSystem().update(world, 19.0);
            require(sample.active, "Authoritative respawn timer did not reactivate the resource");
            ResourceBelt belt = ResourceRingPlacement.resolve(sample, StarSystems.defaultSystem());
            require(belt != null && ResourceRingPlacement.withinRadius(sample, belt),
                    "Authoritative world respawn escaped its belt");
        } finally {
            world.dispose();
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifySavedAndLegacyBeltIdentity() {
        World world = new World("Belt save source", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        World restored = null;
        try {
            world.activateSystem(StarSystems.DEFAULT_SYSTEM_ID);
            ResourceNode original = world.resources.stream()
                    .filter(n -> n.resourceBeltId.equals("inner_industrial_belt"))
                    .findFirst().orElseThrow();
            int id = original.id;
            String beltId = original.resourceBeltId;
            Map<String,Object> saved = world.captureServerSaveGalaxy();
            restored = new World("Belt save restored", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            restored.restoreServerSaveGalaxy(saved);
            restored.activateSystem(StarSystems.DEFAULT_SYSTEM_ID);
            ResourceNode preserved = restored.findResource(id);
            require(preserved != null && beltId.equals(preserved.resourceBeltId),
                    "Explicit belt identity did not survive an authoritative save");
            require(Double.isFinite(preserved.resourceBeltCenterAngle), "Belt phase was not saved");
            restored.dispose();
            restored = null;

            // Simulate a real pre-migration archive: missing belt metadata and an
            // aluminum/iron rock previously scattered to the wrong orbital radius.
            for (Object entry : (List<Object>)saved.get("systems")) {
                Map<String,Object> system = (Map<String,Object>)entry;
                if (!StarSystems.DEFAULT_SYSTEM_ID.equals(system.get("systemId"))) continue;
                for (Object item : (List<Object>)system.get("resources")) {
                    Map<String,Object> resource = (Map<String,Object>)item;
                    if (((Number)resource.get("id")).intValue() != id) continue;
                    resource.remove("resourceBeltId");
                    resource.remove("resourceBeltCenterAngle");
                    resource.put("orbitRadius", 6_000.0);
                    resource.put("amount", 3.25);
                }
            }
            restored = new World("Belt legacy restored", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            restored.restoreServerSaveGalaxy(saved);
            restored.activateSystem(StarSystems.DEFAULT_SYSTEM_ID);
            ResourceNode migrated = restored.findResource(id);
            ResourceBelt belt = ResourceRingPlacement.resolve(migrated, StarSystems.defaultSystem());
            require(belt != null && beltId.equals(migrated.resourceBeltId), "Legacy belt identity not recovered");
            require(ResourceRingPlacement.withinRadius(migrated, belt), "Legacy escaped node not returned to belt");
            require(Math.abs(migrated.amount - 3.25) < 1e-7, "Legacy migration changed inventory");
            require(Double.isFinite(migrated.resourceBeltCenterAngle), "Legacy belt phase not recovered");
            require(Math.abs(migrated.orbitSpeed - ResourceSpawner.speedFor(belt.orbit)) < 1e-9,
                    "Legacy belt kept independent speed shear");
            double migratedPhase = migrated.resourceBeltCenterAngle
                    + ResourceSpawner.speedFor(belt.orbit) * restored.systemTime();
            require(ResourceRingPlacement.withinArc(migrated.orbitAngle, migratedPhase, belt.arc),
                    "Migrated resource remained outside its original partial arc");
        } finally {
            if (restored != null) restored.dispose();
            world.dispose();
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
