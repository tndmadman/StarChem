#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one exact match, found {count}")
    return text.replace(old, new, 1)


def regex_once(text, pattern, replacement, label):
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected one regex match, found {count}")
    return updated

# Allow FitCommand to insert a fully preflighted ship job before runtime catalog registration.
production = read("src/main/java/com/tndmadman/rts/ProductionSystem.java")
production = replace_once(production,
'''    static boolean enqueueShip(World world, Base base, ShipType ship, ShipLoadoutDefinition loadout, boolean free) {
        if (world == null || base == null || ship == null || loadout == null || !ship.id.equals(loadout.hullId())) return false;
        return enqueue(world, base, ProductionJobKind.SHIP, ship.id,
                ship.name + " - " + loadout.displayName(), WeaponRules.buildCost(ship, loadout),
                ship.buildTimeSeconds, free, "", loadout.id(), "");
    }

''',
'''    static boolean enqueueShip(World world, Base base, ShipType ship, ShipLoadoutDefinition loadout, boolean free) {
        if (world == null || base == null || ship == null || loadout == null || !ship.id.equals(loadout.hullId())) return false;
        return enqueue(world, base, ProductionJobKind.SHIP, ship.id,
                ship.name + " - " + loadout.displayName(), WeaponRules.buildCost(ship, loadout),
                ship.buildTimeSeconds, free, "", loadout.id(), "");
    }

    static ProductionJob enqueueShipPrepaid(Base base, ShipType ship,
                                            ShipLoadoutDefinition loadout,
                                            boolean resourcesReserved) {
        if (base == null || ship == null || loadout == null
                || !ship.id.equals(loadout.hullId())) return null;
        ProductionJob job = newJob(base, ProductionJobKind.SHIP, ship.id,
                ship.buildTimeSeconds, resourcesReserved, "");
        job.loadoutId = loadout.id();
        base.productionQueue.add(job);
        return job;
    }

''', "ProductionSystem prepaid ship insertion")
write("src/main/java/com/tndmadman/rts/ProductionSystem.java", production)

# Make custom construction validate completely before it spends, queues, or registers a runtime fit.
fit = read("src/main/java/com/tndmadman/rts/FitCommand.java")
fit = replace_once(fit, "import java.util.Base64;\n", "import java.util.Base64;\nimport java.util.EnumMap;\n",
                   "FitCommand EnumMap import")
fit = regex_once(fit,
    r'''    private static Result build\(World world, String actorId,\n\s*Map<String,Object> payload\) \{.*?\n    \}\n\n    private static Candidate candidate''',
'''    private static Result build(World world, String actorId,
                                Map<String,Object> payload) {
        Base base = ownedBase(world, actorId,
                ServerSaveStore.string(payload, "baseId", ""));
        Candidate candidate = candidate(payload);
        ShipLoadoutDefinition preview = candidate.definition();
        ShipType ship = Rules.findShip(preview.hullId());
        if (ship == null) return Result.fail("Unknown ship hull.");
        if (base.hp <= 0 || !base.type().buildableShips.contains(ship.id)) {
            return Result.fail(base.type().name + " cannot build " + ship.name + ".");
        }

        boolean free = world.devFreeBuildFor(actorId);
        if (!free && !ResearchRules.shipUnlocked(world, actorId, ship.id)) {
            ResearchTopic topic = ResearchRules.firstTopicUnlockingShip(ship.id);
            return Result.fail(ship.name + " requires research"
                    + (topic == null ? "." : ": " + topic.name + "."));
        }
        if (!free && !WeaponRules.unlocked(world, actorId, preview)) {
            return Result.fail(preview.displayName() + " requires research: "
                    + WeaponRules.missingResearchLabel(world, actorId, preview) + ".");
        }

        List<Cost> cost = WeaponRules.buildCost(ship, preview);
        if (!free && !HangarStore.canAfford(base.inventory, cost)) {
            return Result.fail("Need " + Rules.formatCost(cost) + " in "
                    + base.type().name + " hangar.");
        }

        EnumMap<Material,Double> inventoryBefore = new EnumMap<>(base.inventory);
        int queueSizeBefore = base.productionQueue.size();
        long nextJobBefore = base.nextProductionJobId;
        if (!free) HangarStore.spend(base.inventory, cost);
        ProductionJob job = ProductionSystem.enqueueShipPrepaid(base, ship, preview, !free);
        if (job == null) {
            restoreBuildState(base, inventoryBefore, queueSizeBefore, nextJobBefore);
            return Result.fail("Could not queue the custom-fit ship.");
        }

        ShipLoadoutDefinition installed;
        try {
            installed = WorldFitCatalog.registerRuntime(world, candidate.name(), candidate.spec());
            if (!installed.id().equals(preview.id())) {
                throw new IllegalStateException("Runtime fit registration changed the planned fit ID.");
            }
        } catch (RuntimeException ex) {
            restoreBuildState(base, inventoryBefore, queueSizeBefore, nextJobBefore);
            throw ex;
        }

        int position = base.productionQueue.size();
        world.status = "Queued " + ship.name + " - " + installed.displayName()
                + (position > 1 ? " at position " + position : "") + ".";
        AlertCenter.push(world, "Production queued: " + ship.name + " - "
                + installed.displayName() + ".");
        return Result.ok(world.status, true, true);
    }

    private static void restoreBuildState(Base base,
                                          EnumMap<Material,Double> inventoryBefore,
                                          int queueSizeBefore, long nextJobBefore) {
        while (base.productionQueue.size() > queueSizeBefore) {
            base.productionQueue.remove(base.productionQueue.size() - 1);
        }
        base.nextProductionJobId = nextJobBefore;
        base.inventory.clear();
        base.inventory.putAll(inventoryBefore);
    }

    private static Candidate candidate''', "FitCommand transactional build")
write("src/main/java/com/tndmadman/rts/FitCommand.java", fit)

# Present private and server-published custom fits directly in owned station build menus.
build_menu = read("src/main/java/com/tndmadman/rts/BuildMenu.java")
build_menu = replace_once(build_menu,
'''        }


        for (String packageId : def.basePackages) {
''',
'''        }

        addCustomFitBuildEntries(world, network, base, free);

        for (String packageId : def.basePackages) {
''', "BuildMenu custom build insertion")
build_menu = replace_once(build_menu,
'''    private void addCraftingEntries(World world, PeerNetwork network, Base base, boolean free) {
''',
'''    private void addCustomFitBuildEntries(World world, PeerNetwork network,
                                          Base base, boolean free) {
        if (world == null || base == null || !PlayerRegistry.isLocal(base.playerId)) return;
        String commander = PlayerRegistry.baseName(PlayerRegistry.localId());
        if (commander == null || commander.isBlank()) commander = world.localPlayerName;

        for (String shipId : base.type().buildableShips) {
            ShipType ship = Rules.findShip(shipId);
            if (ship == null) continue;
            for (PrivateShipFit fit : ClientFitStore.fits(commander, shipId)) {
                addCustomFitBuildEntry(world, network, base, ship, fit.name(), fit.spec(),
                        "MY FIT", free);
            }
            for (PublishedFit fit : WorldFitCatalog.published(world)) {
                if (!shipId.equals(fit.spec().hullId())) continue;
                addCustomFitBuildEntry(world, network, base, ship, fit.name(), fit.spec(),
                        "SERVER FIT BY " + fit.ownerName(), free);
            }
        }
    }

    private void addCustomFitBuildEntry(World world, PeerNetwork network, Base base,
                                        ShipType ship, String name, ShipFitSpec spec,
                                        String source, boolean free) {
        PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
        if (!validation.valid() || !ship.id.equals(spec.hullId())) return;
        ShipLoadoutDefinition preview;
        try { preview = PlayerFitRules.previewDefinition(name, spec); }
        catch (RuntimeException ex) { return; }

        boolean hullUnlocked = ResearchRules.shipUnlocked(world, base.playerId, ship.id);
        boolean fitUnlocked = WeaponRules.unlocked(world, base.playerId, preview);
        boolean available = free || hullUnlocked && fitUnlocked;
        List<Cost> cost = WeaponRules.buildCost(ship, preview);
        String weapons = weaponText(weaponBadges(preview));
        String modules = "Utility: " + ShipModuleRules.summary(spec.moduleIds());
        String research = available ? source : "Research required before construction";
        entries.add(new Entry(
                "Build " + ship.name + " - " + source + ": " + preview.displayName(),
                timeDetail("Build", ship.buildTimeSeconds, free),
                modules,
                new ShipPreviewIcon(ship),
                requirementTooltip("Build " + ship.name + " - " + preview.displayName(),
                        cost, free, weapons, modules, research),
                !available,
                false,
                false,
                () -> FitNetworkBridge.submit(network, world, "BUILD", preview.displayName(),
                        spec, base.id, null, null)));
    }

    private void addCraftingEntries(World world, PeerNetwork network, Base base, boolean free) {
''', "BuildMenu custom fit helpers")
write("src/main/java/com/tndmadman/rts/BuildMenu.java", build_menu)

# Use the full refit network in the ship fitting UI rather than the geometrically nearest station.
fitting = read("src/main/java/com/tndmadman/rts/ShipFittingWindow.java")
fitting = regex_once(fitting,
    r'''    static FittingOption evaluate\(World world, Unit unit, ShipLoadoutDefinition loadout\) \{.*?\n    \}\n\n    static Base nearestRefitBase''',
'''    static FittingOption evaluate(World world, Unit unit, ShipLoadoutDefinition loadout) {
        if (world == null || unit == null || loadout == null
                || !unit.shipTypeId.equals(loadout.hullId())) {
            return new FittingOption(null, false, false, "Fit does not match this hull.");
        }
        boolean free = world.devFreeBuildFor(unit.playerId);
        Base currentStation = nearestRefitBase(world, unit);
        if (loadout.id().equals(unit.loadoutId)) {
            return new FittingOption(currentStation, true, false, "Currently installed.");
        }
        ActiveRefit active = activeRefit(world, unit);
        if (active != null) {
            return new FittingOption(active.base, false, false, "A refit is already queued.");
        }
        if (!free && !WeaponRules.unlocked(world, unit.playerId, loadout)) {
            return new FittingOption(null, false, false,
                    "Research required: "
                            + WeaponRules.missingResearchLabel(world, unit.playerId, loadout) + ".");
        }
        Base base = RefitQueuePlanner.bestStation(world, unit, loadout, free);
        if (base == null) {
            return new FittingOption(null, false, false,
                    "No owned refit-capable station can fund this conversion.");
        }
        return new FittingOption(base, false, true,
                "Ship will be recalled automatically to " + base.type().name + ".");
    }

    static Base nearestRefitBase''', "ShipFittingWindow evaluate")
fitting = regex_once(fitting,
    r'''    private void submitRefit\(String name, ShipFitSpec spec, boolean entireClass\) \{.*?\n    \}\n\n    private boolean submitNetwork''',
'''    private void submitRefit(String name, ShipFitSpec spec, boolean entireClass) {
        PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
        if (!validation.valid()) { setNotice(validation.reason(), BAD, false); return; }
        ShipLoadoutDefinition definition = PlayerFitRules.definition(name, spec);
        FittingOption option = evaluate(world, unit, definition);
        if (!entireClass && !option.ready()) {
            setNotice(option.reason(), option.current() ? MUTED : BAD, false);
            return;
        }
        Base base = option.base();
        if (base == null) {
            base = RefitQueuePlanner.bestStation(world, unit, definition,
                    world.devFreeBuildFor(unit.playerId));
        }
        if (base == null) {
            setNotice("No owned refit-capable station can service this fit.", BAD, false);
            return;
        }
        submitNetwork(entireClass ? "REFIT_CLASS" : "REFIT", name, spec, base.id,
                entireClass ? null : unit.key(), null, true);
    }

    private boolean submitNetwork''', "ShipFittingWindow submitRefit")
write("src/main/java/com/tndmadman/rts/ShipFittingWindow.java", fitting)

# Keep the standalone studio's selected-ship path aligned with evaluated station choice.
studio = read("src/main/java/com/tndmadman/rts/ShipFitStudioWindow.java")
studio = replace_once(studio,
'''        Base base = ShipFittingWindow.nearestRefitBase(world, live);
        if (base == null) { setNotice("No owned refit-capable shipyard exists in this system.", RED); return; }
        submit("REFIT", cleanName, spec, base.id, live.key(), null);
''',
'''        Base base = option.base();
        if (base == null) {
            base = RefitQueuePlanner.bestStation(world, live, definition,
                    world.devFreeBuildFor(live.playerId));
        }
        if (base == null) { setNotice("No owned refit-capable station can service this fit.", RED); return; }
        submit("REFIT", cleanName, spec, base.id, live.key(), null);
''', "ShipFitStudioWindow selected station")
studio = studio.replace("No owned refit-capable shipyard exists in this system.",
                        "No owned refit-capable station exists in this system.")
write("src/main/java/com/tndmadman/rts/ShipFitStudioWindow.java", studio)

# Keep fallback rules behavior aligned with split station configuration.
rules = read("src/main/java/com/tndmadman/rts/Rules.java")
rules = replace_once(rules,
'''        this(id, name, maxHp, unloadRange, unloadRate, buildRadius, maxShield, shieldRegen, shieldRegenDelay,
                buildableShips, basePackages, buildCost, buildTimeSeconds, "shipyard".equals(id), unloadRange);
    }

    BaseType(String id, String name, double maxHp, double unloadRange, double unloadRate, double buildRadius,
''',
'''        this(id, name, maxHp, unloadRange, unloadRate, buildRadius, maxShield, shieldRegen, shieldRegenDelay,
                buildableShips, basePackages, buildCost, buildTimeSeconds,
                fallbackCanRefit(id), fallbackRefitRange(id, unloadRange));
    }

    static boolean fallbackCanRefit(String id) {
        return "shipyard".equals(id) || "outpost".equals(id);
    }

    static double fallbackRefitRange(String id, double unloadRange) {
        if ("shipyard".equals(id)) return 520;
        if ("outpost".equals(id)) return 420;
        return unloadRange;
    }

    BaseType(String id, String name, double maxHp, double unloadRange, double unloadRate, double buildRadius,
''', "BaseType fallback refit capability")
write("src/main/java/com/tndmadman/rts/Rules.java", rules)

# End-to-end authority, UI, fallback, and TCP construction validation.
write("src/main/java/com/tndmadman/rts/CustomFitConstructionValidator.java", '''package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CustomFitConstructionValidator {
    private CustomFitConstructionValidator() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("starchem-custom-build-");
        System.setProperty("starchem.fitStore", root.resolve("fits.json").toString());
        try {
            validateLocalConstructionAndMenu();
            validateFundedStationSelection();
            validateFallbackOutpost();
            validateAuthenticatedTcpConstruction();
            System.out.println("StarChem custom-fit construction and station-selection validation passed.");
        } finally {
            System.clearProperty("starchem.fitStore");
            deleteTree(root);
        }
    }

    private static void validateLocalConstructionAndMenu() {
        String player = "CUSTOM_BUILD";
        PlayerRegistry.reset(player, "Custom Builder", 0x50BEFF);
        World world = new World("Custom Builder", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        Base base = new Base(player + ":B1", player, "outpost", 900, 900);
        world.bases.put(base.id, base);
        fund(base);

        ShipFitSpec privateSpec = new ShipFitSpec("prospector", List.of(),
                List.of("afterburner"));
        ShipFitSpec serverSpec = new ShipFitSpec("prospector", List.of(),
                List.of("micro_jump_drive"));
        grant(world, player, privateSpec);
        grant(world, player, serverSpec);
        String commander = PlayerRegistry.baseName(player);
        ClientFitStore.save(commander, "", "Private Burner", privateSpec);
        WorldFitCatalog.publish(world, player, commander,
                "Published Jumper", serverSpec);

        BuildMenu menu = new BuildMenu();
        menu.showForBase(world, null, base, 120, 120);
        List<String> titles = menu.entryTitlesForTest();
        require(titles.stream().anyMatch(value -> value.contains("MY FIT: Private Burner")),
                "station menu did not expose a private custom fit");
        require(titles.stream().anyMatch(value -> value.contains("SERVER FIT")
                        && value.contains("Published Jumper")),
                "station menu did not expose a server-published custom fit");

        long revision = WorldFitCatalog.revision(world);
        int queueBefore = base.productionQueue.size();
        EnumMap<Material,Double> inventoryBefore = new EnumMap<>(base.inventory);
        ShipFitSpec unaffordable = new ShipFitSpec("prospector", List.of(),
                List.of("jump_scrambler"));
        grant(world, player, unaffordable);
        base.inventory.clear();
        FitCommand.Result rejected = FitCommand.applyLocal(world, player, "BUILD",
                payload("Rejected Build", unaffordable, base.id));
        require(!rejected.success(), "unaffordable custom build was accepted");
        require(base.productionQueue.size() == queueBefore,
                "rejected custom build changed the production queue");
        require(WorldFitCatalog.revision(world) == revision
                        && !catalogContains(world, unaffordable.runtimeId()),
                "rejected custom build polluted the runtime catalog");
        base.inventory.putAll(inventoryBefore);

        FitCommand.Result accepted = FitCommand.applyLocal(world, player, "BUILD",
                payload("Private Burner", privateSpec, base.id));
        require(accepted.success(), "valid private custom build was rejected: " + accepted.message());
        require(catalogContains(world, privateSpec.runtimeId()),
                "successful custom build did not register its runtime fit");
        ProductionJob job = base.productionQueue.get(base.productionQueue.size() - 1);
        require(job.kind == ProductionJobKind.SHIP
                        && privateSpec.runtimeId().equals(job.loadoutId),
                "custom construction job did not retain the selected fit");
        job.remaining = 0;
        ProductionSystem.update(world, 0.1);
        require(world.units.values().stream().anyMatch(unit ->
                        privateSpec.runtimeId().equals(unit.loadoutId)),
                "completed custom construction did not install the exact fit");

        FitCommand.Result publishedBuild = FitCommand.applyLocal(world, player, "BUILD",
                payload("Published Jumper", serverSpec, base.id));
        require(publishedBuild.success(),
                "published custom build was rejected: " + publishedBuild.message());
        require(base.productionQueue.stream().anyMatch(value ->
                        serverSpec.runtimeId().equals(value.loadoutId)),
                "published custom fit was not placed in production");
    }

    private static void validateFundedStationSelection() {
        String player = "STATION_SELECT";
        PlayerRegistry.reset(player, "Station Select", 0x50BEFF);
        World world = new World("Station Select", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        ShipFitSpec spec = new ShipFitSpec("prospector", List.of(),
                List.of("afterburner"));
        grant(world, player, spec);
        ShipLoadoutDefinition fit = PlayerFitRules.definition("Funded Far Fit", spec);

        Base near = new Base(player + ":B1", player, "outpost", 500, 500);
        Base far = new Base(player + ":B2", player, "shipyard", 3500, 500);
        world.bases.put(near.id, near);
        world.bases.put(far.id, far);
        fund(far);
        Unit unit = new Unit(player, 1, "prospector", 540, 500);
        world.units.put(unit.key(), unit);

        ShipFittingWindow.FittingOption option = ShipFittingWindow.evaluate(world, unit, fit);
        require(option.ready() && option.base() == far,
                "fitting UI rejected or misrouted a farther funded station");
    }

    private static void validateFallbackOutpost() {
        require(BaseType.fallbackCanRefit("outpost"),
                "fallback Outpost is not refit-capable");
        require(BaseType.fallbackCanRefit("shipyard"),
                "fallback Shipyard is not refit-capable");
        require(!BaseType.fallbackCanRefit("research_station"),
                "fallback non-refit station was incorrectly enabled");
        require(BaseType.fallbackRefitRange("outpost", 118) == 420,
                "fallback Outpost refit range is incorrect");
        require(BaseType.fallbackRefitRange("shipyard", 150) == 520,
                "fallback Shipyard refit range is incorrect");
    }

    private static void validateAuthenticatedTcpConstruction() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host()) {
            TcpIntegrationHarness.TestClient client = harness.addClient("TCP Custom Builder");
            harness.awaitJoined(client);
            String player = client.playerId();
            String home = harness.serverWorld.playerHomeSystemId(player);
            harness.serverWorld.activateSystem(home);
            Base base = harness.serverWorld.bases.values().stream()
                    .filter(value -> player.equals(value.playerId)
                            && value.type().buildableShips.contains("prospector"))
                    .findFirst().orElseThrow();
            fund(base);
            ShipFitSpec spec = new ShipFitSpec("prospector", List.of(),
                    List.of("afterburner"));
            grant(harness.serverWorld, player, spec);

            require(FitNetworkBridge.submit(client.network(), client.world(), "BUILD",
                            "TCP Burner", spec, base.id, null, null),
                    "client could not submit authenticated custom construction");
            harness.await(() -> base.productionQueue.stream().anyMatch(job ->
                            job.kind == ProductionJobKind.SHIP
                                    && spec.runtimeId().equals(job.loadoutId)),
                    10_000, "server did not authorize the custom construction job");
            ProductionJob job = base.productionQueue.stream().filter(value ->
                            spec.runtimeId().equals(value.loadoutId))
                    .findFirst().orElseThrow();
            job.remaining = 0;
            harness.await(() -> harness.serverWorld.units.values().stream().anyMatch(unit ->
                            player.equals(unit.playerId)
                                    && spec.runtimeId().equals(unit.loadoutId)),
                    10_000, "authenticated custom construction did not complete");
            harness.await(() -> catalogContains(client.world(), spec.runtimeId()),
                    10_000, "client did not receive the custom build fit catalog");
        }
    }

    private static Map<String,Object> payload(String name, ShipFitSpec spec, String baseId) {
        return Map.of("name", name, "spec", spec.toMap(), "baseId", baseId);
    }

    private static void grant(World world, String player, ShipFitSpec spec) {
        for (String topic : PlayerFitRules.requiredResearch(spec)) {
            world.completeResearch(player, topic);
        }
    }

    private static void fund(Base base) {
        for (Material material : Material.values()) base.inventory.put(material, 100_000.0);
    }

    private static boolean catalogContains(World world, String id) {
        for (Object item : ServerSaveStore.list(
                WorldFitCatalog.networkView(world).get("definitions"))) {
            if (id.equals(ServerSaveStore.string(
                    ServerSaveStore.object(item), "id", ""))) return true;
        }
        return false;
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
''')

print("Applied issue #289 final custom-construction and station-selection fixes.")
