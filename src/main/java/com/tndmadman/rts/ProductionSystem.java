package com.tndmadman.rts;

import java.util.*;

final class ProductionSystem {
    static final String WAITING_FOR_RESOURCES = "waiting for resources";

    private ProductionSystem() { }

    static boolean enqueueShip(World world, Base base, ShipType ship, boolean free) {
        return enqueueShip(world, base, ship, ship == null ? null : WeaponRules.defaultLoadout(ship.id), free);
    }

    static boolean enqueueShip(World world, Base base, ShipType ship, ShipLoadoutDefinition loadout, boolean free) {
        if (world == null || base == null || ship == null || loadout == null || !ship.id.equals(loadout.hullId())) return false;
        return enqueue(world, base, ProductionJobKind.SHIP, ship.id,
                ship.name + " - " + loadout.displayName(), WeaponRules.buildCost(ship, loadout),
                ship.buildTimeSeconds, free, "", loadout.id(), "");
    }

    static boolean enqueueRefit(World world, Base base, Unit unit, ShipLoadoutDefinition loadout, boolean free) {
        if (world == null || base == null || unit == null || loadout == null) return false;
        if (base.hp <= 0 || !base.type().canRefitShips || !unit.playerId.equals(base.playerId)) {
            world.status = "An owned refit-capable station is required.";
            return false;
        }
        if (unit.hp <= 0 || !unit.shipTypeId.equals(loadout.hullId())) {
            world.status = "That loadout is not valid for the selected ship.";
            return false;
        }
        if (loadout.id().equals(unit.loadoutId)) {
            world.status = unit.type().name + " already uses " + loadout.displayName() + ".";
            return false;
        }
        if (!free && !WeaponRules.unlocked(world, base.playerId, loadout)) {
            world.status = loadout.displayName() + " requires research: "
                    + WeaponRules.missingResearchLabel(world, base.playerId, loadout) + ".";
            return false;
        }
        if (refitReserved(world, unit.key())) {
            world.status = "That ship is already reserved for refitting.";
            return false;
        }
        RefitQuote quote = RefitQuote.between(unit, loadout);
        List<Cost> cost = free ? List.of() : quote.requiredMaterials();
        if (!free && !HangarStore.canAfford(base.inventory, cost)) {
            world.status = "Need " + Rules.formatCost(cost) + " in " + base.type().name + " hangar.";
            return false;
        }
        if (!free) HangarStore.spend(base.inventory, cost);
        ProductionJob job = enqueueRefitPrepaid(base, unit, loadout, quote, !free);
        beginRefit(world, base, unit, job);
        world.status = "Recalling " + unit.type().name + " to " + base.type().name
                + " for refit: " + loadout.displayName() + ".";
        AlertCenter.push(world, world.status);
        processBase(world, base, 0);
        return true;
    }

    static ProductionJob enqueueRefitPrepaid(Base base, Unit unit, ShipLoadoutDefinition loadout,
                                             RefitQuote quote, boolean resourcesReserved) {
        if (base == null || unit == null || loadout == null || quote == null) return null;
        ProductionJob job = newJob(base, ProductionJobKind.REFIT, unit.shipTypeId,
                quote.durationSeconds(), resourcesReserved, "");
        job.loadoutId = loadout.id();
        job.subjectUnitKey = unit.key();
        job.sourceLoadoutId = quote.sourceLoadoutId();
        job.reservedCost = resourcesReserved ? quote.requiredMaterials() : List.of();
        job.refitQuoteVersion = quote.version();
        job.blockedReason = "recalling ship to refit";
        base.productionQueue.add(job);
        return job;
    }

    static void beginRefit(World world, Base base, Unit unit, ProductionJob job) {
        if (world == null || base == null || unit == null || job == null) return;
        recall(base, unit, job);
    }

    static void processBaseAfterTransaction(World world, Base base) {
        processBase(world, base, 0);
    }

    static boolean enqueuePackage(World world, Base base, BaseType station, boolean free) {
        if (world == null || base == null || station == null) return false;
        Unit builder = availableBuilder(world, base, null);
        if (builder == null) {
            world.status = "Move an empty Deployer into base range first.";
            return false;
        }
        return enqueue(world, base, ProductionJobKind.STATION_PACKAGE, station.id,
                station.name + " package", station.buildCost, station.buildTimeSeconds, free, builder.key(), "", "");
    }

    static boolean enqueueCraftable(World world, Base base, CraftableItem item, boolean free) {
        if (world == null || base == null || item == null) return false;
        return enqueue(world, base, ProductionJobKind.CRAFTABLE, item.id, item.name,
                item.requiredResources, item.timeSeconds, free, "", "", "");
    }

    static boolean enqueueResearch(World world, Base base, ResearchTopic topic, boolean free) {
        if (world == null || base == null || topic == null) return false;
        if (world.hasResearch(base.playerId, topic.id)) {
            world.status = topic.name + " already researched.";
            return false;
        }
        if (researchQueued(world, base.playerId, topic.id)) {
            world.status = topic.name + " is already queued.";
            return false;
        }
        String missing = missingResearchPrerequisite(world, base, topic);
        if (!missing.isBlank()) {
            world.status = topic.name + " requires " + missing + " first.";
            return false;
        }
        return enqueue(world, base, ProductionJobKind.RESEARCH, topic.id, topic.name,
                topic.requiredResources, topic.timeSeconds, free, "", "", "");
    }

    static boolean enqueuePrepaidResearch(World world, Base base, ResearchTopic topic) {
        if (world == null || base == null || topic == null) return false;
        if (world.hasResearch(base.playerId, topic.id) || researchQueued(world, base.playerId, topic.id)) return false;
        ProductionJob job = newJob(base, ProductionJobKind.RESEARCH, topic.id, topic.timeSeconds,
                false, "");
        base.productionQueue.add(job);
        AlertCenter.push(world, "Research queued: " + topic.name + ".");
        processBase(world, base, 0);
        return true;
    }

    static ProductionJob enqueueWaiting(World world, Base base, ProductionJobKind kind, String itemId,
                                        String itemName, double duration) {
        return enqueueWaiting(world, base, kind, itemId, itemName, duration, "", "");
    }

    static ProductionJob enqueueWaiting(World world, Base base, ProductionJobKind kind, String itemId,
                                        String itemName, double duration, String loadoutId, String subjectUnitKey) {
        if (world == null || base == null || kind == null || itemId == null || itemId.isBlank()) return null;
        ProductionJob job = newJob(base, kind, itemId, duration, false, "");
        job.loadoutId = loadoutId == null ? "" : loadoutId;
        job.subjectUnitKey = subjectUnitKey == null ? "" : subjectUnitKey;
        job.blockedReason = WAITING_FOR_RESOURCES;
        base.productionQueue.add(job);
        int position = base.productionQueue.size();
        world.status = "Queued " + itemName + " at position " + position + " - waiting for resources.";
        AlertCenter.push(world, "Production queued: " + itemName + " - waiting for resources.");
        processBase(world, base, 0);
        return job;
    }

    private static boolean enqueue(World world, Base base, ProductionJobKind kind, String itemId,
                                   String itemName, List<Cost> cost, double duration, boolean free,
                                   String reservedUnitKey, String loadoutId, String subjectUnitKey) {
        if (!free && !HangarStore.canAfford(base.inventory, cost)) {
            world.status = "Need " + Rules.formatCost(cost) + " in " + base.type().name + " hangar.";
            return false;
        }
        if (!free) HangarStore.spend(base.inventory, cost);
        ProductionJob job = newJob(base, kind, itemId, duration, !free, reservedUnitKey);
        job.loadoutId = loadoutId == null ? "" : loadoutId;
        job.subjectUnitKey = subjectUnitKey == null ? "" : subjectUnitKey;
        base.productionQueue.add(job);
        int position = base.productionQueue.size();
        world.status = "Queued " + itemName + (position > 1 ? " at position " + position : "") + ".";
        AlertCenter.push(world, "Production queued: " + itemName + ".");
        processBase(world, base, 0);
        return true;
    }

    private static ProductionJob newJob(Base base, ProductionJobKind kind, String itemId, double duration,
                                        boolean resourcesReserved, String reservedUnitKey) {
        String jobId = "P" + base.nextProductionJobId++;
        return new ProductionJob(jobId, kind, itemId, duration, duration, resourcesReserved,
                reservedUnitKey == null ? "" : reservedUnitKey);
    }

    static void updateRefitRecalls(World world) {
    if (world == null) return;
    for (Base base : new ArrayList<>(world.bases.values())) {
        cleanupInvalidRefits(world, base);
        recallQueuedRefits(world, base);
    }
}

    static void update(World world, double dt) {
        if (world == null || dt < 0) return;
        for (Base base : new ArrayList<>(world.bases.values())) {
            cleanupInvalidRefits(world, base);
            recallQueuedRefits(world, base);
            processBase(world, base, dt);
        }
    }

    private static void cleanupInvalidRefits(World world, Base base) {
        if (base == null || base.productionQueue.isEmpty()) return;
        Iterator<ProductionJob> iterator = base.productionQueue.iterator();
        while (iterator.hasNext()) {
            ProductionJob job = iterator.next();
            if (job.kind != ProductionJobKind.REFIT) continue;
            Unit unit = world.units.get(job.subjectUnitKey);
            if (unit != null && unit.hp > 0) continue;
            if (job.resourcesReserved) refund(base, costFor(job));
            job.resourcesReserved = false;
            iterator.remove();
            world.status = "Refit cancelled because the target ship was destroyed; reserved resources were refunded.";
        }
    }

    private static void recallQueuedRefits(World world, Base base) {
        if (base == null || !base.type().canRefitShips) return;
        for (ProductionJob job : base.productionQueue) {
            if (job.kind != ProductionJobKind.REFIT) continue;
            Unit unit = world.units.get(job.subjectUnitKey);
            if (unit == null || unit.hp <= 0 || base.canRefit(unit)) continue;
            recall(base, unit, job);
        }
    }

    private static void recall(Base base, Unit unit, ProductionJob job) {
        double angle = (unit.unitId * 1.61803398875) % (Math.PI * 2);
        double radius = Math.max(24, base.type().refitRange * 0.55);
        double dockX = base.x + Math.cos(angle) * radius;
        double dockY = base.y + Math.sin(angle) * radius;
        unit.attackTarget = "";
        unit.automationResourceId = -1;
        unit.clearOrder();
        unit.moveTo(dockX, dockY);
        unit.weaponFlashTimer = 0;
        job.blockedReason = "recalling ship to refit";
    }

    private static void processBase(World world, Base base, double dt) {
        if (base == null || base.productionQueue.isEmpty()) return;
        double availableDt = Math.max(0, dt);
        int guard = 0;
        while (!base.productionQueue.isEmpty() && guard++ < 64) {
            ProductionJob job = base.productionQueue.get(0);
            if (waitingForResources(job)) {
                job.blockedReason = WAITING_FOR_RESOURCES;
                return;
            }
            job.blockedReason = "";
            if (!StationFuelRules.isOperational(base)) {
                StationFuelRequirement requirement = StationFuelRules.requirement(base.typeId);
                job.blockedReason = requirement == null ? "station offline" : "waiting for " + requirement.material().label;
                return;
            }
            if (job.kind == ProductionJobKind.STATION_PACKAGE && !ensureBuilder(world, base, job)) return;
            if (job.kind == ProductionJobKind.REFIT && !prepareRefit(world, base, job)) {
                if (job.blockedReason.equals("refit target destroyed")) {
                    base.productionQueue.remove(0);
                    continue;
                }
                return;
            }

            if (job.remaining > 0 && availableDt > 0) {
                double used = Math.min(job.remaining, availableDt);
                job.remaining -= used;
                availableDt -= used;
            }
            if (job.remaining > 0) return;

            if (!complete(world, base, job)) return;
            base.productionQueue.remove(0);
            if (availableDt <= 0 && !nextIsImmediate(base)) return;
        }
    }

    private static boolean nextIsImmediate(Base base) {
        return !base.productionQueue.isEmpty() && base.productionQueue.get(0).remaining <= 0;
    }

    static boolean fundWaitingJob(World world, Base base, String jobId) {
        ProductionJob job = findJob(base, jobId);
        if (world == null || base == null || !waitingForResources(job)) return false;
        List<Cost> cost = costFor(job);
        if (!HangarStore.canAfford(base.inventory, cost)) return false;
        HangarStore.spend(base.inventory, cost);
        job.resourcesReserved = true;
        job.blockedReason = "";
        int position = base.productionQueue.indexOf(job) + 1;
        world.status = "Logistics delivered resources. Funded " + displayName(job)
                + (position > 1 ? " at queue position " + position : "") + ".";
        AlertCenter.push(world, "Resources delivered: " + displayName(job) + ".");
        processBase(world, base, 0);
        return true;
    }

    static ProductionJob findJob(Base base, String jobId) {
        if (base == null || jobId == null || jobId.isBlank()) return null;
        for (ProductionJob job : base.productionQueue) if (job.id.equals(jobId)) return job;
        return null;
    }

    static boolean waitingForResources(ProductionJob job) {
        return job != null && !job.resourcesReserved && WAITING_FOR_RESOURCES.equals(job.blockedReason);
    }

    private static boolean ensureBuilder(World world, Base base, ProductionJob job) {
        Unit builder = world.units.get(job.reservedUnitKey);
        if (!validBuilder(world, base, builder, job)) {
            builder = availableBuilder(world, base, job);
            if (builder != null) job.reservedUnitKey = builder.key();
        }
        if (builder == null) {
            job.blockedReason = "waiting for an empty Deployer";
            return false;
        }
        return true;
    }

    private static boolean complete(World world, Base base, ProductionJob job) {
        return switch (job.kind) {
            case SHIP -> completeShip(world, base, job);
            case STATION_PACKAGE -> completePackage(world, base, job);
            case CRAFTABLE -> completeCraftable(world, base, job);
            case RESEARCH -> completeResearch(world, base, job);
            case REFIT -> completeRefit(world, base, job);
        };
    }

    private static boolean completeShip(World world, Base base, ProductionJob job) {
        ShipType ship = Rules.ship(job.itemId);
        if (ship == null) return failUnknown(world, job);
        int n = nextUnitId(world, base.playerId);
        double a = n * 1.35;
        Unit unit = new Unit(base.playerId, n, job.itemId,
                base.x + Math.cos(a) * (base.type().buildRadius + 40),
                base.y + Math.sin(a) * (base.type().buildRadius + 40));
        unit.loadoutId = WeaponRules.resolveForHull(ship.id, job.loadoutId).id();
        world.units.put(unit.key(), unit);
        completed(world, base, job, "Built " + ship.name + " - "
                + WeaponRules.resolveForHull(ship.id, unit.loadoutId).displayName() + ".", SoundCue.BUILD_SHIP);
        return true;
    }

    private static boolean completePackage(World world, Base base, ProductionJob job) {
        if (!ensureBuilder(world, base, job)) return false;
        Unit builder = world.units.get(job.reservedUnitKey);
        BaseType station = Rules.base(job.itemId);
        if (builder == null || station == null) return failUnknown(world, job);
        builder.basePackageType = job.itemId;
        completed(world, base, job, "Loaded " + station.name + " package into Deployer.", SoundCue.PACKAGE_LOAD);
        return true;
    }

    private static boolean completeCraftable(World world, Base base, ProductionJob job) {
        CraftableItem item = CraftingRules.item(job.itemId);
        if (item == null) return failUnknown(world, job);
        HangarStore.add(base.inventory, item.outputMaterial, item.outputAmount);
        completed(world, base, job, "Manufactured " + item.outputLabel() + ".", SoundCue.CRAFT_ITEM);
        return true;
    }

    private static boolean completeResearch(World world, Base base, ProductionJob job) {
        ResearchTopic topic = ResearchRules.topic(job.itemId);
        if (topic == null) return failUnknown(world, job);
        world.completeResearch(base.playerId, topic.id);
        completed(world, base, job, "Research completed: " + topic.name + ".", SoundCue.CRAFT_ITEM);
        return true;
    }

    private static boolean prepareRefit(World world, Base base, ProductionJob job) {
        Unit unit = world.units.get(job.subjectUnitKey);
        if (unit == null || unit.hp <= 0) {
            if (job.resourcesReserved) refund(base, costFor(job));
            job.resourcesReserved = false;
            job.blockedReason = "refit target destroyed";
            world.status = "Refit cancelled because the target ship was destroyed; reserved resources were refunded.";
            return false;
        }
        ShipLoadoutDefinition loadout = WeaponRules.findLoadout(job.loadoutId);
        if (loadout == null || !unit.shipTypeId.equals(loadout.hullId())) {
            job.blockedReason = "invalid refit target";
            return false;
        }
        if (!base.canRefit(unit)) {
            recall(base, unit, job);
            return false;
        }
        unit.attackTarget = "";
        unit.automationResourceId = -1;
        unit.clearOrder();
        unit.task = UnitTask.IDLE;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        unit.afterburnerActive = false;
        job.blockedReason = "";
        return true;
    }

    private static boolean completeRefit(World world, Base base, ProductionJob job) {
        Unit unit = world.units.get(job.subjectUnitKey);
        ShipLoadoutDefinition loadout = WeaponRules.findLoadout(job.loadoutId);
        if (unit == null || loadout == null || !unit.shipTypeId.equals(loadout.hullId())) return failUnknown(world, job);
        unit.loadoutId = loadout.id();
        unit.attackTarget = "";
        unit.automationResourceId = -1;
        unit.clearOrder();
        unit.task = UnitTask.IDLE;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        unit.weaponFlashTimer = 0;
        unit.weaponCooldown = WeaponRules.maxCooldown(unit);
        unit.microJumpCooldown = 0;
        unit.microJumpFlashTimer = 0;
        unit.afterburnerActive = false;
        completed(world, base, job, "Refit completed: " + unit.type().name + " - " + loadout.displayName() + ".", SoundCue.CRAFT_ITEM);
        return true;
    }

    static boolean refitReserved(World world, String unitKey) {
        if (world == null || unitKey == null || unitKey.isBlank()) return false;
        for (Base base : world.bases.values()) for (ProductionJob job : base.productionQueue) {
            if (job.kind == ProductionJobKind.REFIT && unitKey.equals(job.subjectUnitKey)) return true;
        }
        return false;
    }

    static boolean refitLocked(World world, String unitKey) {
        if (world == null || unitKey == null || unitKey.isBlank()) return false;
        Unit unit = world.units.get(unitKey);
        if (unit == null) return false;
        for (Base base : world.bases.values()) for (ProductionJob job : base.productionQueue) {
            if (job.kind == ProductionJobKind.REFIT && unitKey.equals(job.subjectUnitKey)
                    && base.canRefit(unit)) return true;
        }
        return false;
    }

    private static boolean failUnknown(World world, ProductionJob job) {
        world.status = "Production failed: unknown " + job.kind.name().toLowerCase(Locale.ROOT) + " " + job.itemId + ".";
        return true;
    }

    private static void completed(World world, Base base, ProductionJob job, String message, SoundCue cue) {
        world.status = message;
        AlertCenter.push(world, message);
        SystemAudio.playForPlayer(world, base.playerId, cue);
        job.resourcesReserved = false;
    }

    static boolean cancel(World world, String playerId, String baseId, String jobId) {
        Base base = world == null ? null : world.bases.get(baseId);
        if (base == null || !base.playerId.equals(playerId) || jobId == null || jobId.isBlank()) return false;
        for (int i = 0; i < base.productionQueue.size(); i++) {
            ProductionJob job = base.productionQueue.get(i);
            if (!job.id.equals(jobId)) continue;
            base.productionQueue.remove(i);
            if (!validResearchOrder(world, base)) {
                base.productionQueue.add(i, job);
                world.status = "Cancel dependent research first.";
                return false;
            }
            world.logisticsSystem.cancelJob(base, job.id);
            boolean refunded = job.resourcesReserved;
            if (refunded) refund(base, costFor(job));
            if (job.kind == ProductionJobKind.REFIT) {
                Unit unit = world.units.get(job.subjectUnitKey);
                if (unit != null) {
                    unit.task = UnitTask.IDLE;
                    unit.targetX = unit.x;
                    unit.targetY = unit.y;
                    unit.afterburnerActive = false;
                }
            }
            world.status = "Cancelled " + displayName(job) + (refunded ? " and refunded reserved resources." : ".");
            processBase(world, base, 0);
            return true;
        }
        world.status = "Production job not found.";
        return false;
    }

    static boolean move(World world, String playerId, String baseId, String jobId, int delta) {
        Base base = world == null ? null : world.bases.get(baseId);
        if (base == null || !base.playerId.equals(playerId) || delta == 0) return false;
        int from = indexOf(base, jobId);
        if (from <= 0) {
            if (world != null) world.status = from == 0 ? "The active job cannot be reordered." : "Production job not found.";
            return false;
        }
        int to = Math.max(1, Math.min(base.productionQueue.size() - 1, from + delta));
        if (from == to) return true;
        ProductionJob job = base.productionQueue.remove(from);
        base.productionQueue.add(to, job);
        if (!validResearchOrder(world, base)) {
            base.productionQueue.remove(to);
            base.productionQueue.add(from, job);
            world.status = "Cannot move research ahead of its prerequisite.";
            return false;
        }
        world.status = "Moved " + displayName(job) + " to queue position " + (to + 1) + ".";
        return true;
    }

    private static int indexOf(Base base, String jobId) {
        for (int i = 0; i < base.productionQueue.size(); i++) if (base.productionQueue.get(i).id.equals(jobId)) return i;
        return -1;
    }

    private static boolean validResearchOrder(World world, Base base) {
        Set<String> available = new HashSet<>(world.completedResearch.getOrDefault(base.playerId, Set.of()));
        for (ProductionJob job : base.productionQueue) {
            if (job.kind != ProductionJobKind.RESEARCH) continue;
            ResearchTopic topic = ResearchRules.topic(job.itemId);
            if (topic == null || !available.containsAll(topic.requires)) return false;
            available.add(topic.id);
        }
        return true;
    }

    static boolean researchQueued(World world, String playerId, String topicId) {
        return researchJob(world, playerId, topicId) != null;
    }

    static ProductionJob researchJob(World world, String playerId, String topicId) {
        if (world == null) return null;
        for (Base base : world.bases.values()) {
            if (!base.playerId.equals(playerId)) continue;
            for (ProductionJob job : base.productionQueue) {
                if (job.kind == ProductionJobKind.RESEARCH && job.itemId.equals(topicId)) return job;
            }
        }
        return null;
    }

    static String missingResearchPrerequisite(World world, Base base, ResearchTopic topic) {
        Set<String> available = new HashSet<>(world.completedResearch.getOrDefault(base.playerId, Set.of()));
        for (ProductionJob job : base.productionQueue) {
            if (job.kind == ProductionJobKind.RESEARCH) available.add(job.itemId);
        }
        for (String required : topic.requires) {
            if (available.contains(required)) continue;
            ResearchTopic missing = ResearchRules.topic(required);
            return missing == null ? required : missing.name;
        }
        return "";
    }

    static ProductionJob active(Base base) {
        return base == null || base.productionQueue.isEmpty() ? null : base.productionQueue.get(0);
    }

    static String displayName(ProductionJob job) {
        if (job == null) return "Production";
        return switch (job.kind) {
            case SHIP -> Rules.ship(job.itemId).name;
            case STATION_PACKAGE -> Rules.base(job.itemId).name + " package";
            case CRAFTABLE -> {
                CraftableItem item = CraftingRules.item(job.itemId);
                yield item == null ? job.itemId : item.name;
            }
            case RESEARCH -> {
                ResearchTopic topic = ResearchRules.topic(job.itemId);
                yield topic == null ? job.itemId : topic.name + " research";
            }
            case REFIT -> {
                ShipLoadoutDefinition loadout = WeaponRules.findLoadout(job.loadoutId);
                ShipType ship = Rules.findShip(job.itemId);
                yield "Refit " + (ship == null ? job.itemId : ship.name) + " - "
                        + (loadout == null ? job.loadoutId : loadout.displayName());
            }
        };
    }

    static String detail(Base base, ProductionJob job) {
        if (job == null) return "";
        int position = base == null ? -1 : base.productionQueue.indexOf(job);
        String state;
        if (position == 0) {
            state = job.blockedReason == null || job.blockedReason.isBlank()
                    ? Math.max(0, (int)Math.ceil(job.remaining)) + "s left"
                    : job.blockedReason;
        } else if (waitingForResources(job)) {
            state = "queued #" + (position + 1) + " | " + WAITING_FOR_RESOURCES;
        } else state = "queued #" + (position + 1) + " | " + Math.max(0, (int)Math.ceil(job.duration)) + "s";
        return state;
    }

    static List<Cost> costFor(ProductionJob job) {
        if (job == null) return List.of();
        return switch (job.kind) {
            case SHIP -> WeaponRules.buildCost(Rules.findShip(job.itemId),
                    WeaponRules.resolveForHull(job.itemId, job.loadoutId));
            case STATION_PACKAGE -> Rules.base(job.itemId).buildCost;
            case CRAFTABLE -> {
                CraftableItem item = CraftingRules.item(job.itemId);
                yield item == null ? List.of() : item.requiredResources;
            }
            case RESEARCH -> {
                ResearchTopic topic = ResearchRules.topic(job.itemId);
                yield topic == null ? List.of() : topic.requiredResources;
            }
            case REFIT -> job.refitQuoteVersion > 0
                    ? job.reservedCost
                    : RefitQuote.legacyReservedCost(job);
        };
    }

    private static void refund(Base base, List<Cost> cost) {
        for (Cost c : cost) HangarStore.add(base.inventory, c.material(), c.amount());
    }

    private static Unit availableBuilder(World world, Base base, ProductionJob current) {
        Unit best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (!validBuilder(world, base, unit, current)) continue;
            double distance = Calc.distance(unit.x, unit.y, base.x, base.y);
            if (distance < bestDistance) {
                best = unit;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean validBuilder(World world, Base base, Unit unit, ProductionJob current) {
        if (unit == null || !unit.playerId.equals(base.playerId) || !unit.type().baseBuilder || !unit.basePackageType.isBlank()) return false;
        if (Calc.distance(unit.x, unit.y, base.x, base.y) > base.type().unloadRange) return false;
        for (Base other : world.bases.values()) {
            for (ProductionJob job : other.productionQueue) {
                if (job == current || job.kind != ProductionJobKind.STATION_PACKAGE) continue;
                if (!job.reservedUnitKey.isBlank() && job.reservedUnitKey.equals(unit.key())) return false;
            }
        }
        return true;
    }

    private static int nextUnitId(World world, String playerId) {
        int max = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(playerId)) max = Math.max(max, unit.unitId);
        return max + 1;
    }
}

enum ProductionJobKind { SHIP, STATION_PACKAGE, CRAFTABLE, RESEARCH, REFIT }

final class ProductionJob {
    final String id;
    final ProductionJobKind kind;
    final String itemId;
    final double duration;
    double remaining;
    boolean resourcesReserved;
    String reservedUnitKey;
    String loadoutId = "";
    String subjectUnitKey = "";
    String sourceLoadoutId = "";
    List<Cost> reservedCost = List.of();
    int refitQuoteVersion;
    String blockedReason = "";

    ProductionJob(String id, ProductionJobKind kind, String itemId, double duration, double remaining,
                  boolean resourcesReserved, String reservedUnitKey) {
        this.id = id;
        this.kind = kind;
        this.itemId = itemId;
        this.duration = Math.max(0, duration);
        this.remaining = Math.max(0, Math.min(this.duration, remaining));
        this.resourcesReserved = resourcesReserved;
        this.reservedUnitKey = reservedUnitKey == null ? "" : reservedUnitKey;
    }

    double progress() {
        if (duration <= 0) return 1.0;
        return 1.0 - Math.max(0, remaining) / duration;
    }
}

final class ProductionQueueCodec {
    private ProductionQueueCodec() { }

    static String write(List<ProductionJob> jobs) {
        if (jobs == null || jobs.isEmpty()) return "-";
        StringBuilder out = new StringBuilder();
        for (ProductionJob job : jobs) {
            if (!out.isEmpty()) out.append('~');
            out.append(clean(job.id)).append('^').append(job.kind.name()).append('^').append(clean(job.itemId)).append('^')
                    .append(job.duration).append('^').append(job.remaining).append('^')
                    .append(job.resourcesReserved ? '1' : '0').append('^').append(clean(job.reservedUnitKey)).append('^')
                    .append(clean(job.blockedReason)).append('^').append(clean(job.loadoutId)).append('^')
                    .append(clean(job.subjectUnitKey)).append('^').append(clean(job.sourceLoadoutId)).append('^')
                    .append(job.refitQuoteVersion).append('^').append(RefitQuote.encodeCosts(job.reservedCost));
        }
        return out.toString();
    }

    static void readInto(String text, Base base) {
        base.productionQueue.clear();
        base.nextProductionJobId = 1;
        if (text == null || text.isBlank() || text.equals("-")) return;
        for (String row : text.split("~", -1)) {
            String[] c = row.split("\\^", -1);
            if (c.length < 7) continue;
            try {
                String id = c[0];
                ProductionJobKind kind = ProductionJobKind.valueOf(c[1]);
                ProductionJob job = new ProductionJob(id, kind, c[2], Double.parseDouble(c[3]),
                        Double.parseDouble(c[4]), "1".equals(c[5]), unclean(c[6]));
                if (c.length >= 8) job.blockedReason = unclean(c[7]);
                if (c.length >= 9) job.loadoutId = unclean(c[8]);
                if (c.length >= 10) job.subjectUnitKey = unclean(c[9]);
                if (c.length >= 13) {
                    job.sourceLoadoutId = unclean(c[10]);
                    job.refitQuoteVersion = Integer.parseInt(c[11]);
                    job.reservedCost = RefitQuote.decodeCosts(c[12]);
                } else RefitQuote.migrateLegacy(job);
                base.productionQueue.add(job);
                base.nextProductionJobId = Math.max(base.nextProductionJobId, numericSuffix(id) + 1);
            } catch (RuntimeException ignored) { }
        }
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return "-";
        return value.replace('^', '_').replace('~', '_').replace(',', '_').replace(';', '_').replace('|', '_');
    }

    private static String unclean(String value) { return value == null || value.equals("-") ? "" : value; }

    private static long numericSuffix(String value) {
        if (value == null) return 0;
        int i = value.length() - 1;
        while (i >= 0 && Character.isDigit(value.charAt(i))) i--;
        try { return Long.parseLong(value.substring(i + 1)); }
        catch (RuntimeException ignored) { return 0; }
    }
}

final class ProductionCommands {
    private ProductionCommands() { }

    static boolean apply(World world, String playerId, String action, String baseId, String value, String extra) {
        if (world == null || playerId == null || action == null || baseId == null) return false;
        Base base = world.bases.get(baseId);
        if (base == null || !playerId.equals(base.playerId)) return false;
        String normalized = action.toUpperCase(Locale.ROOT);
        if (!"CONTROL".equals(normalized) && StationControls.nonProduction(base.typeId)) {
            world.status = base.type().name + " is a non-production station.";
            return false;
        }
        return switch (normalized) {
            case "CONTROL" -> StationControlCommands.apply(world, playerId, baseId, value, extra);
            case "ENQUEUE" -> enqueue(world, value, baseId, extra);
            case "CANCEL" -> ProductionSystem.cancel(world, playerId, baseId, value);
            case "MOVE" -> ProductionSystem.move(world, playerId, baseId, value, parseInt(extra));
            case "REFIT" -> refit(world, base, value, extra);
            default -> false;
        };
    }

    private static boolean enqueue(World world, String kindValue, String baseId, String itemId) {
        ProductionJobKind kind;
        try { kind = ProductionJobKind.valueOf(kindValue.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { return false; }
        return switch (kind) {
            case SHIP -> world.buildShip(baseId, itemId);
            case STATION_PACKAGE -> world.loadBasePackage(baseId, itemId);
            case CRAFTABLE -> world.craftItem(baseId, itemId);
            case RESEARCH -> world.research(baseId, itemId);
            case REFIT -> false;
        };
    }

    private static boolean refit(World world, Base base, String unitKey, String loadoutId) {
        Unit unit = world.units.get(unitKey);
        ShipLoadoutDefinition loadout = WeaponRules.findLoadout(loadoutId);
        boolean free = world.devFreeBuildFor(base.playerId);
        return ProductionSystem.enqueueRefit(world, base, unit, loadout, free);
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); }
        catch (RuntimeException ignored) { return 0; }
    }
}
