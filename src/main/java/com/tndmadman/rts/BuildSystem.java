package com.tndmadman.rts;

final class BuildSystem {
    boolean buildShip(World world, String baseId, String requestedId) {
        Base base = world.bases.get(baseId);
        if (base == null) return false;

        // Hull IDs and loadout IDs share the same command field. Prefer an exact hull match so a
        // request for (for example) "prospector" can never be reinterpreted as a different hull by
        // a colliding authored/runtime loadout ID. Variant loadout IDs are still accepted when there
        // is no exact hull with that ID.
        ShipType exactHull = Rules.findShip(requestedId);
        ShipLoadoutDefinition requestedLoadout = exactHull == null
                ? WeaponRules.findLoadout(world, requestedId) : null;
        ShipType shipType = exactHull != null
                ? exactHull
                : requestedLoadout == null ? null : Rules.findShip(requestedLoadout.hullId());
        if (shipType == null) {
            world.status = "Unknown ship or loadout ID: " + requestedId + ".";
            return false;
        }
        ShipLoadoutDefinition loadout = requestedLoadout == null
                ? WeaponRules.defaultLoadout(shipType.id) : requestedLoadout;
        if (loadout == null || !shipType.id.equals(loadout.hullId())) {
            world.status = "No valid loadout is configured for " + shipType.name + ".";
            return false;
        }
        if (!base.type().buildableShips.contains(shipType.id)) {
            world.status = base.type().name + " cannot build " + shipType.name + ".";
            return false;
        }
        boolean free = freeBuild(world, base);
        if (!free && !ResearchPolicy.unlocked(world, base.playerId, ResearchUnlockKind.SHIP, shipType.id)) {
            String research = ResearchPolicy.missingUnlockLabel(world, base.playerId, ResearchUnlockKind.SHIP, shipType.id);
            world.status = shipType.name + " requires research" + (research.isBlank() ? "." : ": " + research + ".");
            GameNoticeCenter.publish(world, base.playerId, NoticeCategory.WARNING, world.status, true);
            return false;
        }
        if (!free && !WeaponRules.unlocked(world, base.playerId, loadout)) {
            world.status = loadout.displayName() + " requires research: "
                    + WeaponRules.missingResearchLabel(world, base.playerId, loadout) + ".";
            GameNoticeCenter.publish(world, base.playerId, NoticeCategory.WARNING, world.status, true);
            return false;
        }
        if (!free) {
            for (String weaponId : loadout.weaponIds()) {
                if (ResearchPolicy.unlocked(world, base.playerId, ResearchUnlockKind.WEAPON, weaponId)) continue;
                String research = ResearchPolicy.missingUnlockLabel(world, base.playerId, ResearchUnlockKind.WEAPON, weaponId);
                world.status = loadout.displayName() + " uses weapon " + weaponId + " which requires research"
                        + (research.isBlank() ? "." : ": " + research + ".");
                GameNoticeCenter.publish(world, base.playerId, NoticeCategory.WARNING, world.status, true);
                return false;
            }
            for (String moduleId : ShipModuleRules.moduleIds(loadout)) {
                if (ResearchPolicy.unlocked(world, base.playerId, ResearchUnlockKind.MODULE, moduleId)) continue;
                String research = ResearchPolicy.missingUnlockLabel(world, base.playerId, ResearchUnlockKind.MODULE, moduleId);
                world.status = loadout.displayName() + " uses module " + moduleId + " which requires research"
                        + (research.isBlank() ? "." : ": " + research + ".");
                GameNoticeCenter.publish(world, base.playerId, NoticeCategory.WARNING, world.status, true);
                return false;
            }
        }
        java.util.List<Cost> cost = WeaponRules.buildCost(shipType, loadout);
        if (!free && !HangarStore.canAfford(base.inventory, cost)) {
            if (world.logisticsSystem.queueBuildShip(world, base, shipType, loadout)) return true;
            if (ProductionPlanner.queueShip(world, base, shipType, loadout)) return true;
            world.status = "Need " + Rules.formatCost(cost) + " in " + base.type().name + " hangar.";
            return false;
        }
        return ProductionSystem.enqueueShip(world, base, shipType, loadout, free);
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
        if (!free && !ResearchPolicy.unlocked(world, base.playerId, ResearchUnlockKind.STATION_PACKAGE, packageType)) {
            String research = ResearchPolicy.missingUnlockLabel(world, base.playerId, ResearchUnlockKind.STATION_PACKAGE, packageType);
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
        boolean free = world.devFreeBuildFor(carrier.playerId);
        if (!free && !ResearchPolicy.unlocked(world, carrier.playerId, ResearchUnlockKind.STATION_PACKAGE, carrier.basePackageType)) {
            String research = ResearchPolicy.missingUnlockLabel(world, carrier.playerId,
                    ResearchUnlockKind.STATION_PACKAGE, carrier.basePackageType);
            world.status = placed.name + " placement requires research"
                    + (research.isBlank() ? "." : ": " + research + ".");
            GameNoticeCenter.publish(world, carrier.playerId, NoticeCategory.WARNING, world.status, true);
            return false;
        }
        if (!free && !ResearchPolicy.unlocked(world, carrier.playerId, ResearchUnlockKind.STATION, carrier.basePackageType)) {
            String research = ResearchPolicy.missingUnlockLabel(world, carrier.playerId,
                    ResearchUnlockKind.STATION, carrier.basePackageType);
            world.status = placed.name + " placement requires research"
                    + (research.isBlank() ? "." : ": " + research + ".");
            GameNoticeCenter.publish(world, carrier.playerId, NoticeCategory.WARNING, world.status, true);
            return false;
        }
        String baseId = nextBaseId(world, carrier.playerId);
        world.bases.put(baseId, new Base(baseId, carrier.playerId, carrier.basePackageType, carrier.x, carrier.y));
        StrategicSupplyService.invalidate(world);
        StrategicSummaryService.invalidate(world);
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
        if (!free && !ResearchPolicy.unlocked(world, base.playerId, ResearchUnlockKind.CRAFTABLE, craftableId)) {
            String research = ResearchPolicy.missingUnlockLabel(world, base.playerId,
                    ResearchUnlockKind.CRAFTABLE, craftableId);
            world.status = item.name + " requires research" + (research.isBlank() ? "." : ": " + research + ".");
            GameNoticeCenter.publish(world, base.playerId, NoticeCategory.WARNING, world.status, true);
            return false;
        }
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
        String blocked = ResearchPolicy.blockedResearchReason(world, base, topic);
        if (!blocked.isBlank()) {
            world.status = topic.name + " is blocked: " + blocked + ".";
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
