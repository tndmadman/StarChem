package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Rejects current-format saves that reference missing or hull-mismatched fit definitions. */
final class SavedFitReferenceValidator {
    private SavedFitReferenceValidator() { }

    static void validate(int sourceVersion, Map<String,Object> galaxy, Map<String,Object> runtime) {
        // Versions before dynamic fits intentionally receive hull defaults during migration.
        if (sourceVersion < 4) return;

        Map<String,String> hullByLoadout = new LinkedHashMap<>();
        for (ShipLoadoutDefinition definition : WeaponRules.SHIP_LOADOUTS.values()) {
            hullByLoadout.put(definition.id(), definition.hullId());
        }

        Map<String,Object> fitCatalog = ServerSaveStore.object(runtime == null ? null : runtime.get("shipFits"));
        for (Object item : ServerSaveStore.list(fitCatalog.get("definitions"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            String savedId = ServerSaveStore.string(row, "id", "").trim();
            ShipFitSpec spec = ShipFitSpec.from(row.get("spec"));
            PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
            if (savedId.isBlank() || !validation.valid() || !savedId.equals(spec.runtimeId())) {
                throw new IllegalArgumentException("Current save contains an invalid runtime fit definition: "
                        + (savedId.isBlank() ? "<blank>" : savedId) + ".");
            }
            String previous = hullByLoadout.putIfAbsent(savedId, spec.hullId());
            if (previous != null && !previous.equals(spec.hullId())) {
                throw new IllegalArgumentException("Current save contains a conflicting runtime fit ID: " + savedId + ".");
            }
        }

        Set<String> unitKeys = new LinkedHashSet<>();
        for (Object systemItem : ServerSaveStore.list(galaxy == null ? null : galaxy.get("systems"))) {
            Map<String,Object> system = ServerSaveStore.object(systemItem);
            String systemId = ServerSaveStore.string(system, "systemId", "<unknown-system>");
            for (Object unitItem : ServerSaveStore.list(system.get("units"))) {
                Map<String,Object> unit = ServerSaveStore.object(unitItem);
                String playerId = ServerSaveStore.string(unit, "playerId", "");
                int unitId = ServerSaveStore.intValue(unit, "unitId", -1);
                String unitKey = Unit.key(playerId, unitId);
                if (!unitKeys.add(systemId + '|' + unitKey)) {
                    throw new IllegalArgumentException("Current save contains duplicate unit " + unitKey
                            + " in system " + systemId + ".");
                }
                validateReference("unit " + unitKey + " in system " + systemId,
                        ServerSaveStore.string(unit, "shipTypeId", ""),
                        ServerSaveStore.string(unit, "loadoutId", ""), hullByLoadout);
            }

            for (Object baseItem : ServerSaveStore.list(system.get("bases"))) {
                Map<String,Object> base = ServerSaveStore.object(baseItem);
                String baseId = ServerSaveStore.string(base, "id", "<unknown-base>");
                for (Object jobItem : ServerSaveStore.list(base.get("productionQueue"))) {
                    Map<String,Object> job = ServerSaveStore.object(jobItem);
                    ProductionJobKind kind = ServerSaveStore.enumValue(ProductionJobKind.class,
                            job.get("kind"), ProductionJobKind.SHIP);
                    if (kind != ProductionJobKind.SHIP && kind != ProductionJobKind.REFIT) continue;
                    String jobId = ServerSaveStore.string(job, "id", "<unknown-job>");
                    validateReference(kind.name().toLowerCase() + " job " + jobId + " at " + baseId,
                            ServerSaveStore.string(job, "itemId", ""),
                            ServerSaveStore.string(job, "loadoutId", ""), hullByLoadout);
                    if (kind == ProductionJobKind.REFIT
                            && ServerSaveStore.string(job, "subjectUnitKey", "").isBlank()) {
                        throw new IllegalArgumentException("Current save contains refit job " + jobId
                                + " without a subject ship.");
                    }
                }
            }
        }
    }

    private static void validateReference(String subject, String hullId, String loadoutId,
                                          Map<String,String> hullByLoadout) {
        String hull = hullId == null ? "" : hullId.trim();
        String fit = loadoutId == null ? "" : loadoutId.trim();
        if (Rules.findShip(hull) == null) {
            throw new IllegalArgumentException("Current save contains " + subject
                    + " with unknown hull " + printable(hull) + ".");
        }
        if (fit.isBlank()) {
            throw new IllegalArgumentException("Current save contains " + subject + " without a loadout ID.");
        }
        String expectedHull = hullByLoadout.get(fit);
        if (expectedHull == null) {
            throw new IllegalArgumentException("Current save contains " + subject
                    + " referencing missing loadout " + printable(fit) + ".");
        }
        if (!hull.equals(expectedHull)) {
            throw new IllegalArgumentException("Current save contains " + subject + " using loadout "
                    + printable(fit) + " for hull " + printable(expectedHull)
                    + " instead of " + printable(hull) + ".");
        }
    }

    private static String printable(String value) {
        if (value == null || value.isBlank()) return "<blank>";
        return value.length() <= 128 ? value : value.substring(0, 128) + "…";
    }
}
