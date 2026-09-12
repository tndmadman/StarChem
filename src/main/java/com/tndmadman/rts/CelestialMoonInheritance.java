package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Planet/moon master-slave sovereignty and strategic-benefit rules.
 *
 * <p>A moon is not an independent celestial territory. Its nearest non-moon parent planet is the
 * sovereignty master: claim ownership, contest state and hold progress mirror that planet. The
 * master's orbital installation roles also unlock the moon's own authored strategic bonuses. This
 * makes moon-rich planets more valuable without flattening every moon into the same bonus profile.</p>
 */
final class CelestialMoonInheritance {
    private static final double MAX_CELESTIAL_BONUS = 0.35;
    private static final Map<CelestialSystem, WorldSystemState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<CelestialSystem, Map<String,String>> CLAIM_RESERVATIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CelestialMoonInheritance() { }

    static void register(WorldSystemState state) {
        if (state == null || state.celestials == null) return;
        STATES.put(state.celestials, state);
        CLAIM_RESERVATIONS.computeIfAbsent(state.celestials, ignored -> new LinkedHashMap<>());
        CelestialObjectiveTooltipOverlay.ensureInstalled();
    }

    static void apply(CelestialSystem celestials) {
        if (celestials == null) return;
        WorldSystemState state = STATES.get(celestials);
        if (state == null) return;
        apply(state);
    }

    static void apply(WorldSystemState state) {
        if (state == null || state.celestials == null) return;

        // GameplaySystem performs the broad proximity anchor pass first. This second authoritative
        // gate converts that broad pass into planetary sovereignty: the first accepted station on
        // a planet reserves the entire planet/moon group, and only the claimant or an ALLIED owner
        // may retain a new anchor after that point. Neutral/hostile owners are immediately rejected.
        enforceAnchorExclusivity(state);
        reconcileClaimsAndInstallations(state);

        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (!body.moon()) continue;
            CelestialSystem.BodyView masterView = masterPlanet(state.celestials, body);
            if (masterView == null || masterView.id().equals(body.id())) continue;
            CelestialBodyState master = CelestialGameplaySystem.bodyState(state, masterView.id());
            CelestialBodyState slave = CelestialGameplaySystem.bodyState(state, body.id());
            if (master == null || slave == null) continue;

            // Sovereignty is strictly inherited from the planet. A station on a moon can still be
            // a physical/combat station, but it cannot create an independent moon polity.
            slave.contested = master.contested;
            slave.claimantId = master.claimantId == null ? "" : master.claimantId;

            // HOLD is sovereignty time, so a slave moon advances with its master rather than
            // requiring the player to separately hold every satellite in the same planetary group.
            slave.holdSecondsByPlayer.clear();
            slave.holdSecondsByPlayer.putAll(master.holdSecondsByPlayer);
        }
    }

    private static void enforceAnchorExclusivity(WorldSystemState state) {
        Map<String,String> reservations = CLAIM_RESERVATIONS.computeIfAbsent(
                state.celestials, ignored -> new LinkedHashMap<>());
        Map<String,List<Base>> directMasterBases = new LinkedHashMap<>();
        Map<String,List<Base>> groupBases = new LinkedHashMap<>();

        for (Base base : state.bases.values()) {
            String anchoredBodyId = base.celestialAnchorBodyId;
            if (anchoredBodyId == null || anchoredBodyId.isBlank()) continue;
            String masterId = masterPlanetId(state, anchoredBodyId);
            if (masterId.isBlank()) continue;
            groupBases.computeIfAbsent(masterId, ignored -> new ArrayList<>()).add(base);
            if (masterId.equals(anchoredBodyId)) {
                directMasterBases.computeIfAbsent(masterId, ignored -> new ArrayList<>()).add(base);
            }
        }

        // A moon station cannot start or preserve sovereignty on its own. Reservations exist only
        // while at least one station remains directly anchored to the master planet.
        reservations.keySet().removeIf(masterId -> directMasterBases.getOrDefault(masterId, List.of()).isEmpty());

        for (Map.Entry<String,List<Base>> entry : directMasterBases.entrySet()) {
            String masterId = entry.getKey();
            List<Base> direct = entry.getValue();
            if (direct.isEmpty()) continue;

            String owner = reservations.getOrDefault(masterId, "");
            if (owner.isBlank()) {
                CelestialBodyState master = CelestialGameplaySystem.bodyState(state, masterId);
                if (master != null && !master.contested && master.claimantId != null && !master.claimantId.isBlank()) {
                    owner = master.claimantId;
                }
            }
            if (owner.isBlank()) owner = direct.get(0).playerId;
            if (owner == null || owner.isBlank()) continue;
            reservations.put(masterId, owner);
        }

        for (Map.Entry<String,List<Base>> entry : groupBases.entrySet()) {
            String masterId = entry.getKey();
            String owner = reservations.getOrDefault(masterId, "");
            if (owner.isBlank()) continue;
            for (Base base : entry.getValue()) {
                if (!friendly(owner, base.playerId)) clearAnchor(base);
            }
        }

        // If all direct master anchors were rejected/destroyed during this pass, release the lock.
        reservations.keySet().removeIf(masterId -> !hasDirectMasterAnchor(state, masterId));
    }

    private static boolean hasDirectMasterAnchor(WorldSystemState state, String masterId) {
        for (Base base : state.bases.values()) {
            if (masterId.equals(base.celestialAnchorBodyId)) return true;
        }
        return false;
    }

    private static void clearAnchor(Base base) {
        if (base == null) return;
        base.celestialAnchorBodyId = "";
        base.celestialOrbitRadius = 0;
        base.celestialOrbitSpeed = 0;
    }

    private static boolean friendly(String claimantId, String candidateId) {
        if (claimantId == null || claimantId.isBlank() || candidateId == null || candidateId.isBlank()) return false;
        if (claimantId.equals(candidateId)) return true;
        World world = PlayerRegistry.activeWorld();
        return world != null && DiplomacySystem.allied(world, claimantId, candidateId);
    }

    private static void reconcileClaimsAndInstallations(WorldSystemState state) {
        Map<String,String> reservations = CLAIM_RESERVATIONS.computeIfAbsent(
                state.celestials, ignored -> new LinkedHashMap<>());

        for (CelestialBodyState body : CelestialGameplaySystem.bodyStates(state)) {
            body.orbitalBaseIds.clear();
            body.installations.clear();
            CelestialSystem.BodyView view = state.celestials.bodyView(body.profile.bodyId());
            if (view == null) continue;

            for (Base base : state.bases.values()) {
                if (!body.profile.bodyId().equals(base.celestialAnchorBodyId)) continue;
                body.orbitalBaseIds.add(base.id);
                if (body.installations.size() < body.profile.installationSlots()) {
                    body.installations.add(installationType(base));
                }
            }

            if (view.moon()) {
                // The inheritance pass below overwrites moon sovereignty from its master.
                body.contested = false;
                body.claimantId = "";
                continue;
            }

            String claimant = reservations.getOrDefault(view.id(), "");
            if (body.orbitalBaseIds.isEmpty()) claimant = "";
            body.contested = false;
            body.claimantId = claimant;
        }
    }

    private static CelestialInstallationType installationType(Base base) {
        String type = base == null || base.typeId == null ? "" : base.typeId.toLowerCase(Locale.ROOT);
        if (type.contains("research") || type.contains("lab")) return CelestialInstallationType.RESEARCH_SITE;
        if (type.contains("sensor") || type.contains("radar") || type.contains("observ")
                || type.contains("jam") || type.contains("decoy")) return CelestialInstallationType.SENSOR_ARRAY;
        if (type.contains("log") || type.contains("cargo") || type.contains("depot") || type.contains("repair")
                || type.contains("outpost") || type.contains("shipyard")) return CelestialInstallationType.LOGISTICS_HUB;
        return CelestialInstallationType.EXTRACTOR;
    }

    /**
     * Full celestial multiplier with planetary installation roles extended through slave moons.
     * Each moon keeps its own profile, so its individual moon bonuses stack into the planetary
     * system value. The existing global celestial cap is preserved for balance.
     */
    static double bonusMultiplier(WorldSystemState state, String playerId, CelestialBonusKind kind) {
        if (state == null || playerId == null || playerId.isBlank() || kind == null) return 1.0;
        apply(state);

        double bonus = 0.0;
        for (CelestialBodyState bodyState : CelestialGameplaySystem.bodyStates(state)) {
            CelestialSystem.BodyView view = state.celestials.bodyView(bodyState.profile.bodyId());
            if (view == null) continue;

            if (!view.moon()) {
                if (bodyState.contested || !playerId.equals(bodyState.claimantId)) continue;
                if (!installationSupports(bodyState.installations, kind)) continue;
                bonus += bodyState.profile.bonuses().getOrDefault(kind, 0.0);
                continue;
            }

            CelestialSystem.BodyView masterView = masterPlanet(state.celestials, view);
            CelestialBodyState master = masterView == null
                    ? null : CelestialGameplaySystem.bodyState(state, masterView.id());
            if (master == null || master.contested || !playerId.equals(master.claimantId)) continue;
            if (!installationSupports(master.installations, kind)) continue;

            // The installation capability comes from the master planet, but the amount/type of the
            // reward comes from the moon itself. More moons therefore means more total value.
            bonus += bodyState.profile.bonuses().getOrDefault(kind, 0.0);
        }
        return 1.0 + Math.min(MAX_CELESTIAL_BONUS, Math.max(0.0, bonus));
    }

    static String masterPlanetId(WorldSystemState state, String bodyId) {
        if (state == null || state.celestials == null || bodyId == null || bodyId.isBlank()) return "";
        CelestialSystem.BodyView body = state.celestials.bodyView(bodyId);
        CelestialSystem.BodyView master = masterPlanet(state.celestials, body);
        return master == null ? "" : master.id();
    }

    private static CelestialSystem.BodyView masterPlanet(CelestialSystem celestials, CelestialSystem.BodyView body) {
        if (celestials == null || body == null) return null;
        CelestialSystem.BodyView current = body;
        int guard = 0;
        while (current.moon() && current.parentId() != null && !current.parentId().isBlank() && guard++ < 16) {
            CelestialSystem.BodyView parent = celestials.bodyView(current.parentId());
            if (parent == null) break;
            current = parent;
        }
        return current.visualClass() == CelestialVisualClass.STAR ? null : current;
    }

    private static boolean installationSupports(
            List<CelestialInstallationType> installations, CelestialBonusKind kind) {
        if (installations == null || installations.isEmpty()) return false;
        return switch (kind) {
            case MINING -> installations.contains(CelestialInstallationType.EXTRACTOR);
            case RESEARCH -> installations.contains(CelestialInstallationType.RESEARCH_SITE);
            case SENSOR -> installations.contains(CelestialInstallationType.SENSOR_ARRAY);
            case LOGISTICS, REPAIR -> installations.contains(CelestialInstallationType.LOGISTICS_HUB);
            case PRODUCTION, SHIELD -> true;
        };
    }
}
