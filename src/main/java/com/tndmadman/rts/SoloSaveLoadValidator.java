package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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
            long savedSeed = alpha.systemSeed();
            String activeSystem = alpha.activeSystemId();
            int units = alpha.units.size();
            service.save(a.id(), alpha, config, "manual");

            Path archive = root.resolve(a.id()).resolve(SoloSaveService.ARCHIVE);
            require(Files.isRegularFile(archive), "Manual save must produce an archive.");
            var saved = ServerSaveStore.validateArchive(archive);
            require("solo-state".equals(saved.manifest().get("archiveKind")), "Solo archive kind missing.");
            require(saved.players().get("sessions") instanceof List<?> credentials && credentials.isEmpty(),
                    "Solo archives must not contain server authentication sessions.");
            require(service.mostRecentValid() != null, "Continue must find the first valid save.");

            loaded = service.load(a.id(), config);
            PlayerRegistry.activate(loaded);
            require(loaded.systemSeed() == savedSeed, "Restored seed must match.");
            require(activeSystem.equals(loaded.activeSystemId()), "Restored system must match.");
            require(loaded.units.size() == units, "Restored units must match.");
            require(loaded.hasResearch("SOLO", "advanced_industry"), "Research completion was lost.");
            require(PlayerRegistry.color("SOLO").getRGB() == new java.awt.Color(0x2966B0).getRGB(),
                    "Restored player color must match.");

            beta = new World("Solo Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(beta);
            PlayerRegistry.reset("SOLO", "Solo Validator", 0x4499AA);
            SoloSaveService.activate(beta, b.id());
            beta.completeResearch("SOLO", "combat_doctrine");
            service.save(b.id(), beta, config, "manual");

            service.rename(b.id(), "Renamed Campaign");
            require(service.list().stream().anyMatch(slot -> slot.id().equals(b.id())
                    && slot.name().equals("Renamed Campaign")), "Renamed slot should retain its ID.");
            try {
                service.rename(b.id(), "../escape");
                throw new IllegalStateException("Path-like display name must be rejected.");
            } catch (IllegalArgumentException expected) { }

            // A valid manual checkpoint must survive a damaged current archive.
            Files.writeString(archive, "corrupted latest save");
            recovered = service.load(a.id(), config);
            require(recovered.hasResearch("SOLO", "advanced_industry"),
                    "Manual checkpoint was not used after current save corruption.");
            PlayerRegistry.activate(recovered);
            require(!recovered.hasResearch("SOLO", "combat_doctrine"),
                    "Two same-profile campaigns leaked research state.");

            ServerSaveStore server = new ServerSaveStore(root.resolve(a.id()), "solo", 5);
            try {
                server.loadArchive(root.resolve(a.id()).resolve("solo-manual.starchem-save"), config);
                throw new IllegalStateException("Dedicated server must not accept a solo archive.");
            } catch (IOException expected) { }

            service.delete(b.id());
            require(service.list().size() == 1, "Deleting a slot must remove its listing.");
            require(!Files.exists(root.resolve(b.id())), "Deleting a slot must remove its directory.");

            System.out.println("Solo slot create, save/load, seed, research, unit, ownership color,"
                    + " rename, path safety, recovery, isolation and archive-kind validation passed.");
        } finally {
            if (alpha != null) alpha.dispose();
            if (loaded != null) loaded.dispose();
            if (beta != null) beta.dispose();
            if (recovered != null) recovered.dispose();
            GalaxyRuntimeOptions.restore(before);
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
