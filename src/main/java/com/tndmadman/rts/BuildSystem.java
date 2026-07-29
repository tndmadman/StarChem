package com.tndmadman.rts;

final class BuildSystem {
    boolean buildShip(World world, String baseId, String shipTypeId) {
        Base base = world.bases.get(baseId);
        if (base == null) return false;
        ShipType shipType = Rules.findShip(shipTypeId);
        if (shipType == null) {
            world.status = "Unknown ship type ID: " + shipTypeId + ".";
            return false;
        }
        if (!base.type().buildableShips.contains(shipTypeId)) {
            world.status = base.type().name + " cannot build " + shipType.name + ".";
            return false;
        }
        boolean free = freeBuild(world, base);
        if (!free && !ResearchRules.shipUnlocked(world, base.playerId, shipTypeId)) {
            ResearchTopic topic = ResearchRules.firstTopicUnlockingShip(shipTypeId);
            world.status = shipType.name + " requires research" + (topic == null ? "." : ": " + topic.name + ".");
            GameNoticeCenter.publish(world, base.playerId, NoticeCategory.WARNING, world.status, true);
            return false;
        }
        if (!free && !HangarStore.canAfford(base.inventory, shipType.buildCost)) {
            if (world.logisticsSystem.queueBuildShip(world, base, shipType)) return true;
            if (ProductionPlanner.queueShip(world, base, shipType)) return true;
            world.status = "Need " + Rules.formatCost(shipType.buildCost) + " in " + base.type().name + " hangar.";
            return false;
        }
        return ProductionSystem.enqueueShip(world, base, shipType, free);
    }

    boolean loadBasePackage(World world, String baseId, String packageType) {
        Base base = world.bases.get(baseId);
        if (base == null) return false;
        BaseType pkg = Rules.findBase(packageType);
        if (pkg == null) {
            world.status = "Unknown station type ID: " + packageType + ".";
            return false;
        }
        if (!base.type().basePackages.contains(packageType)) {
            world.status = base.type().name + " cannot craft that package.";
            return false;
        }
        boolean free = freeBuild(world, base);
        if (!free && !StationPackageResearchRules.unlocked(world, base.playerId, packageType)) {
            String research = StationPackageResearchRules.requiredResearchName(packageType);
            world.status = pkg.name + " requires research" + (research.isBlank() ? "." : ": " + research + ".");
            GameNoticeCenter.publish(world, base.playerId, NoticeCategory.WARNING, world.status, true);
            return false;
        }
        if (!free && !HangarStore.canAfford(base.inventory, pkg.buildCost)) {
            if (world.logisticsSystem.queueBasePackage(world, base, pkg)) return true;
            if (ProductionPlanner.queuePackage(world, base, pkg)) return true;
            world.status = "Need " + Rules.formatCost(pkg.buildCost) + " in " + base.type().name + " hangar.";
            return false;
        }
        return ProductionSystem.enqueuePackage(world, base, pkg, free);
    }

    boolean placePackage(World world, Unit carrier) {
        if (carrier == null || carrier.basePackageType.isBlank()) {
            world.status = "Select a loaded Deployer first.";
            return false;
        }
        if (NpcStationConstructionSystem.ownsBuilder(world, carrier.key())
                || NpcExpeditionSystem.ownsUnit(world, carrier.key())) {
            world.status = "Deployer is committed to an active NPC construction or expedition plan.";
            return false;
        }
        BaseType placed = Rules.findBase(carrier.basePackageType);
        if (placed == null) {
            world.status = "Unknown station type ID: " + carrier.basePackageType + ".";
            return false;
        }
        String baseId = nextBaseId(world, carrier.playerId);
        world.bases.put(baseId, new Base(baseId, carrier.playerId, carrier.basePackageType, carrier.x, carrier.y));
        world.units.remove(carrier.key());
        world.status = "Placed " + placed.name + ". Deployer consumed.";
        SystemAudio.playForPlayer(world, carrier.playerId, SoundCue.PLACE_STATION);
        return true;
    }

    boolean craftItem(World world, String baseId, String craftableId) {
        Base base = world.bases.get(baseId);
        if (base == null) return false;
        CraftableItem item = CraftingRules.item(craftableId);
        if (item == null) {
            world.status = "Unknown craftable item: " + craftableId + ".";
            return false;
        }
        if (!item.canCraftAt(base.typeId)) {
            world.status = base.type().name + " cannot manufacture " + item.name + ".";
            return false;
        }
        boolean free = freeBuild(world, base);
        if (!free && !item.unlockedFor(world, base.playerId)) {
            world.status = item.name + " requires research: " + item.missingResearchLabel(world, base.playerId) + ".";
            GameNoticeCenter.publish(world, base.playerId, NoticeCategory.WARNING, world.status, true);
            return false;
        }
        if (!free && !HangarStore.canAfford(base.inventory, item.requiredResources)) {
            if (world.logisticsSystem.queueCraftable(world, base, item)) return true;
            if (ProductionPlanner.queueCraftable(world, base, item)) return true;
            world.status = "Need " + Rules.formatCost(item.requiredResources) + " in " + base.type().name + " hangar.";
            return false;
        }
        return ProductionSystem.enqueueCraftable(world, base, item, free);
    }

    boolean research(World world, String baseId, String topicId) {
        Base base = world.bases.get(baseId);
        if (base == null) return false;
        ResearchTopic topic = ResearchRules.topic(topicId);
        if (topic == null) {
            world.status = "Unknown research topic: " + topicId + ".";
            return false;
        }
        if (!topic.canResearchAt(base.typeId)) {
            world.status = base.type().name + " cannot research " + topic.name + ".";
            return false;
        }
        if (world.hasResearch(base.playerId, topic.id)) {
            world.status = topic.name + " already researched.";
            return false;
        }
        if (ProductionSystem.researchQueued(world, base.playerId, topic.id)) {
            world.status = topic.name + " is already queued.";
            return false;
        }
        String missing = ProductionSystem.missingResearchPrerequisite(world, base, topic);
        if (!missing.isBlank()) {
            world.status = topic.name + " requires " + missing + " first.";
            GameNoticeCenter.publish(world, base.playerId, NoticeCategory.WARNING, world.status, true);
            return false;
        }
        boolean free = freeBuild(world, base);
        if (!free && !HangarStore.canAfford(base.inventory, topic.requiredResources)) {
            if (world.logisticsSystem.queueResearch(world, base, topic)) return true;
            if (ProductionPlanner.queueResearch(world, base, topic)) return true;
            world.status = "Need " + Rules.formatCost(topic.requiredResources) + " in " + base.type().name + " hangar.";
            return false;
        }
        return ProductionSystem.enqueueResearch(world, base, topic, free);
    }

    private boolean freeBuild(World world, Base base) {
        return world.devFreeBuildFor(base.playerId);
    }

    private String nextBaseId(World world, String playerId) {
        int max = 0;
        String prefix = playerId + ":B";
        for (String id : world.bases.keySet()) {
            if (!id.startsWith(prefix)) continue;
            try { max = Math.max(max, Integer.parseInt(id.substring(prefix.length()))); }
            catch (NumberFormatException ignored) { }
        }
        return prefix + (max + 1);
    }
}
