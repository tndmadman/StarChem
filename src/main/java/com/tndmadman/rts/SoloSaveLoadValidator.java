package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.BitSet;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Stream;

/** Headless regression suite for solo slot isolation and existing archive recovery. */
public final class SoloSaveLoadValidator {
    private SoloSaveLoadValidator() { }

    public static void main(String[] args) throws Exception {
        GalaxyRuntimeOptions.State before = GalaxyRuntimeOptions.snapshot();
        Path root = Files.createTempDirectory("starchem-solo-validator-");
        World alpha = null;
        World loaded = null;
        World beta = null;
        World recovered = null;
        World autosaveLoaded = null;
        World selectedCheckpoint = null;
        World proceduralWorld = null;
        World proceduralLoaded = null;
        try {
            SoloSaveService service = new SoloSaveService(root);
            Config config = Config.solo("Solo Validator");
            GalaxyRuntimeOptions.configure(config);

            SoloSaveService.Slot a = service.create("First Campaign");
            SoloSaveService.Slot b = service.create("Second Campaign");
            require(!a.id().equals(b.id()), "Slots must have independent UUIDs.");
            require(service.list().size() == 2, "Slot browser should list both slots.");

            alpha = new World("Solo Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
            PlayerRegistry.activate(alpha);
            PlayerRegistry.reset("SOLO", "Solo Validator", 0x2966B0);
            SoloSaveService.activate(alpha, a.id());
            alpha.completeResearch("SOLO", "advanced_industry");
            ScenarioDirector.start(alpha, ScenarioRules.require("guided_start"), false);
            Map<String,Object> initialScenario = ScenarioDirector.capture(alpha);
            require("guided_start".equals(initialScenario.get("scenarioId")),
                    "Guided Start scenario must activate before save.");
            // Save a genuinely paid, unfinished manufacturing job. Loading must
            // retain the queue, partial remaining time and consumed reservations.
            CraftableItem oldRecipe = CraftingRules.item("research_matrix");
            require(oldRecipe != null, "Historical Research Matrix recipe ID was removed.");
            Base queuedFactory = new Base("SOLO:B8000", "SOLO", "manufacturing",
                    alpha.width / 2.0 + 300, alpha.height / 2.0);
            alpha.bases.put(queuedFactory.id, queuedFactory);
            for (Cost cost : oldRecipe.requiredResources) {
                queuedFactory.inventory.merge(cost.material(), cost.amount(), Double::sum);
            }
            require(ProductionSystem.enqueueCraftable(alpha, queuedFactory, oldRecipe, false),
                    "Could not queue actual paid Research Matrix manufacturing.");
            require(queuedFactory.productionQueue.size() == 1
                            && queuedFactory.productionQueue.get(0).resourcesReserved,
                    "Manufacturing job was not reserved before saving.");
            double queuedRemaining = queuedFactory.productionQueue.get(0).remaining;

            Base legacyFactory = new Base("SOLO:B8001", "SOLO", "manufacturing",
                    alpha.width / 2.0 + 500, alpha.height / 2.0);
            ProductionSystem.readInto("P42^CRAFTABLE^research_matrix^16^8^1^-", legacyFactory);
            require(legacyFactory.productionQueue.size() == 1
                            && "research_matrix".equals(legacyFactory.productionQueue.get(0).itemId),
                    "Seven-column legacy manufacturing queue cannot be migrated.");

            long savedSeed = alpha.systemSeed();
            String activeSystem = alpha.activeSystemId();
            int units = alpha.units.size();
            alpha.setSoloUiSaveState(Map.of("camera", Map.of("x", 123.0, "y", 456.0, "zoom", 0.6),
                    "galaxyMapOpen", true));
            int columns = Math.max(1, (int)Math.ceil(alpha.width / (double)FogOfWarView.CELL_SIZE));
            int rows = Math.max(1, (int)Math.ceil(alpha.height / (double)FogOfWarView.CELL_SIZE));
            BitSet discovered = new BitSet();
            discovered.set(1);
            FogOfWarView.restore(alpha, Map.of("SOLO|" + activeSystem,
                    Map.of("systemId", activeSystem, "seed", savedSeed,
                            "columns", columns, "rows", rows, "width", alpha.width,
                            "height", alpha.height, "bits",
                            Base64.getEncoder().encodeToString(discovered.toByteArray()))));
            service.save(a.id(), alpha, config, "manual");

            Path archive = root.resolve(a.id()).resolve(SoloSaveService.ARCHIVE);
            require(Files.isRegularFile(archive), "Manual save must produce an archive.");
            var saved = ServerSaveStore.validateArchive(archive);
            require("solo-state".equals(saved.manifest().get("archiveKind")), "Solo archive kind missing.");
            require(saved.players().get("sessions") instanceof List<?> credentials && credentials.isEmpty(),
                    "Solo archives must not contain server authentication sessions.");
            require(service.mostRecentValid() != null, "Continue must find the first valid save.");

            World previousRegistry = PlayerRegistry.activeWorld();
            SystemAudio.listenTo(alpha);
            GalaxyRuntimeOptions.State previousOptions = GalaxyRuntimeOptions.snapshot();
            loaded = service.load(a.id(), config);
            require(PlayerRegistry.activeWorld() == previousRegistry,
                    "Loading a candidate changed the active thread's registry before commit.");
            require(SystemAudio.rendered(alpha),
                    "Loading a candidate stole the currently audible world.");
            require(GalaxyRuntimeOptions.snapshot().equals(previousOptions),
                    "Candidate options were published to the live campaign before commit.");
            PlayerRegistry.activate(loaded);
            require(loaded.systemSeed() == savedSeed, "Restored seed must match.");
            require(activeSystem.equals(loaded.activeSystemId()), "Restored system must match.");
            require(loaded.units.size() == units, "Restored units must match.");
            Base restoredFactory = loaded.bases.get(queuedFactory.id);
            require(restoredFactory != null && restoredFactory.productionQueue.size() == 1,
                    "Unfinished manufacturing job was lost on save/load.");
            ProductionJob restoredJob = restoredFactory.productionQueue.get(0);
            require(restoredJob.resourcesReserved
                            && "research_matrix".equals(restoredJob.itemId)
                            && Math.abs(restoredJob.remaining - queuedRemaining) < 0.001,
                    "Paid manufacturing reservations or remaining time changed on restore.");
            require(!HangarStore.canAfford(restoredFactory.inventory, oldRecipe.requiredResources),
                    "Production input resources were refunded or duplicated across save/load.");
            ProductionSystem.update(loaded, queuedRemaining + 1);
            require(restoredFactory.productionQueue.isEmpty()
                            && restoredFactory.inventory.getOrDefault(oldRecipe.outputMaterial, 0.0)
                                >= oldRecipe.outputAmount,
                    "Restored legacy-compatible manufacturing job did not produce its output.");
            require(loaded.soloUiSaveState().containsKey("camera"), "Solo camera data was lost.");
            require(FogOfWarView.capture(loaded).containsKey("SOLO|" + activeSystem),
                    "Explored fog cells were lost.");
            require(loaded.hasResearch("SOLO", "advanced_industry"), "Research completion was lost.");
            Map<String,Object> restoredScenario = ScenarioDirector.capture(loaded);
            require("guided_start".equals(restoredScenario.get("scenarioId")),
                    "Guided Start scenario ID was lost in solo archive.");
            require(initialScenario.get("firedActions").equals(restoredScenario.get("firedActions")),
                    "Guided Start applied starting-state actions again during restore.");
            require(PlayerRegistry.color("SOLO").getRGB() == new java.awt.Color(0x2966B0).getRGB(),
                    "Restored player color must match.");

            // Force failures only AFTER a candidate World/registry and galaxy
            // have been constructed. Check that ten rejected candidates cannot
            // steal the process-wide generation or audio state from this session.
            SystemAudio.listenTo(loaded);
            GalaxyRuntimeOptions.State goodOptions = GalaxyRuntimeOptions.snapshot();
            for (int failedAttempt = 0; failedAttempt < 10; failedAttempt++) {
                ServerSaveStore.failSoloCandidateRestoreForTest = true;
                try {
                    service.loadSnapshot(a.id(), SoloSaveService.ARCHIVE, config);
                    throw new IllegalStateException("Injected restoration failure was not rejected.");
                } catch (IOException expected) {
                    require(expected.getMessage().contains("Injected candidate restoration failure"),
                            "The rollback probe did not reach post-construction restoration.");
                } finally {
                    ServerSaveStore.failSoloCandidateRestoreForTest = false;
                }
                require(PlayerRegistry.activeWorld() == loaded,
                        "Rejected candidate replaced the active player registry.");
                require(GalaxyRuntimeOptions.snapshot().equals(goodOptions),
                        "Rejected candidate changed process-wide generation options.");
                require(SystemAudio.rendered(loaded),
                        "Rejected candidate stole playback from the active campaign.");
                require(loaded.hasResearch("SOLO", "advanced_industry"),
                        "Rejected candidate modified the working campaign.");
            }


            beta = new World("Solo Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(beta);
            PlayerRegistry.reset("SOLO", "Solo Validator", 0x4499AA);
            SoloSaveService.activate(beta, b.id());
            beta.completeResearch("SOLO", "combat_doctrine");
            service.save(b.id(), beta, config, "manual");

            require(!FogOfWarView.capture(beta).containsKey("SOLO|" + activeSystem),
                    "Fog state leaked into an independent slot.");
            service.rename(b.id(), "Renamed Campaign");
            require(service.list().stream().anyMatch(slot -> slot.id().equals(b.id())
                    && slot.name().equals("Renamed Campaign")), "Renamed slot should retain its ID.");
            try {
                service.rename(b.id(), "../escape");
                throw new IllegalStateException("Path-like display name must be rejected.");
            } catch (IllegalArgumentException expected) { }

            // New autosaves must not overwrite an intentional manual checkpoint.
            alpha.completeResearch("SOLO", "combat_doctrine");
            service.save(a.id(), alpha, config, "autosave");
            autosaveLoaded = service.load(a.id(), config);
            require(autosaveLoaded.hasResearch("SOLO", "combat_doctrine"),
                    "Autosave must preserve newer state.");
            selectedCheckpoint = service.loadSnapshot(a.id(), "solo-manual.starchem-save", config);
            require(!selectedCheckpoint.hasResearch("SOLO", "combat_doctrine"),
                    "Autosave incorrectly overwrote the manual checkpoint.");
            require(service.recoveryPoints(a.id()).size() >= 3,
                    "Current, previous and manual recovery points should be visible.");

            // An older valid 'current' must not win over a newer valid 'manual'.
            // Swap exact existing verified archives without changing their signed manifests.
            Path manual = root.resolve(a.id()).resolve("solo-manual.starchem-save");
            Path oldManual = root.resolve(a.id()).resolve("older-manual.tmp");
            Files.copy(manual, oldManual, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(archive, manual, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(oldManual, archive, StandardCopyOption.REPLACE_EXISTING);
            SoloSaveService.LatestSnapshot newest = service.mostRecentValid();
            require(newest != null && newest.slot().id().equals(a.id())
                            && newest.fileName().equals("solo-manual.starchem-save"),
                    "Continue must select the exact newest validated manual snapshot, not older current.");
            World exact = service.loadSnapshot(newest.slot().id(), newest.fileName(), config);
            try {
                require(exact.hasResearch("SOLO", "combat_doctrine"),
                        "Exact Continue selection restored older current instead of newest manual.");
            } finally { exact.dispose(); }
            Files.copy(manual, archive, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(oldManual, manual, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(oldManual);
            try {
                service.loadSnapshot(a.id(), "../unsafe", config);
                throw new IllegalStateException("Unsafe recovery filename should be rejected.");
            } catch (IOException expected) { }

            // A valid manual/previous checkpoint must survive a damaged current archive.
            Files.writeString(archive, "corrupted latest save");
            recovered = service.load(a.id(), config);
            require(recovered.hasResearch("SOLO", "advanced_industry"),
                    "Manual checkpoint was not used after current save corruption.");
            PlayerRegistry.activate(recovered);
            require(!recovered.hasResearch("SOLO", "combat_doctrine"),
                    "Recovery used corrupted autosave instead of the original manual checkpoint.");
            require(alpha.hasResearch("SOLO", "combat_doctrine"),
                    "Failed load or fallback mutated the original active World.");

            PlayerRegistry.activate(alpha);
            TutorialOverlay migrated = new TutorialOverlay(alpha, true, false);
            migrated.restoreSoloState(Map.of("step", "WORMHOLE", "coreCompleted", true,
                    "advancedCompleted", true));
            Map<String,Object> migratedState = migrated.captureSoloState();
            require("LOGISTICS_RESEARCH".equals(migratedState.get("step"))
                            && Boolean.FALSE.equals(migratedState.get("coreCompleted")),
                    "Legacy tutorial core completion must migrate to actual v3 economic progress.");
            TutorialOverlay v3 = new TutorialOverlay(alpha, true, false);
            v3.restoreSoloState(Map.of("schemaVersion", 3, "step", "HAULER_COMPLETE",
                    "coreCompleted", false));
            require("HAULER_COMPLETE".equals(v3.captureSoloState().get("step")),
                    "v3 campaign tutorial checkpoint must not migrate or use global preferences.");

            ServerSaveStore server = new ServerSaveStore(root.resolve(a.id()), "solo", 5);
            try {
                server.loadArchive(root.resolve(a.id()).resolve("solo-manual.starchem-save"), config);
                throw new IllegalStateException("Dedicated server must not accept a solo archive.");
            } catch (IOException expected) { }

            service.delete(b.id());
            require(service.list().size() == 1, "Deleting a slot must remove its listing.");
            require(!Files.exists(root.resolve(b.id())), "Deleting a slot must remove its directory.");

            // A lobby-selected procedural seed is distinct from World.systemSeed;
            // both must survive exact archive round trips.
            GalaxyGenerationSettings procedural = new GalaxyGenerationSettings(
                    true, 8, GalaxyTopologyStyle.MIXED, 0.31, 0.18, 1.3,
                    0.17, 0.15, 0.55, 3, Map.of());
            long selectedSeed = 987654321L;
            GalaxyRuntimeOptions.configureGeneration(procedural, selectedSeed);
            SoloSaveService.Slot proceduralSlot = service.create("Procedural Campaign");
            proceduralWorld = new World("Solo Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(proceduralWorld);
            PlayerRegistry.reset("SOLO", "Solo Validator", 0x2966B0);
            SoloSaveService.activate(proceduralWorld, proceduralSlot.id());
            long proceduralWorldSeed = proceduralWorld.systemSeed();
            service.save(proceduralSlot.id(), proceduralWorld, config, "manual");
            GalaxyRuntimeOptions.clearGenerationOverride();
            GalaxyRuntimeOptions.State lobbyOptions = GalaxyRuntimeOptions.snapshot();
            proceduralLoaded = service.load(proceduralSlot.id(), config);
            require(GalaxyRuntimeOptions.snapshot().equals(lobbyOptions),
                    "Restoring an uncommitted procedural candidate changed global lobby settings.");
            proceduralLoaded.commitStagedSoloGenerationOptions();
            require(proceduralLoaded.systemSeed() == proceduralWorldSeed,
                    "Procedural World.systemSeed changed during restore.");
            require(GalaxyRuntimeOptions.generationSeed(-1) == selectedSeed,
                    "Lobby-selected procedural seed was not restored.");
            require(GalaxyRuntimeOptions.generationSettings().equals(procedural),
                    "Advanced procedural generation settings changed during restore.");

            System.out.println("Solo slot create, exact Continue snapshot, Guided Start runtime, migrated tutorial, save/load, seed, research, unit, ownership color,"
                    + " rename, path safety, recovery, isolation and archive-kind validation passed.");
        } finally {
            if (alpha != null) alpha.dispose();
            if (loaded != null) loaded.dispose();
            if (beta != null) beta.dispose();
            if (recovered != null) recovered.dispose();
            if (autosaveLoaded != null) autosaveLoaded.dispose();
            if (selectedCheckpoint != null) selectedCheckpoint.dispose();
            if (proceduralWorld != null) proceduralWorld.dispose();
            if (proceduralLoaded != null) proceduralLoaded.dispose();
            GalaxyRuntimeOptions.restore(before);
            ServerSaveStore.failSoloCandidateRestoreForTest = false;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void require(boolean accepted, String reason) {
        if (!accepted) throw new IllegalStateException(reason);
    }
}
