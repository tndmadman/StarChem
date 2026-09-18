package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Authoritative gameplay layer for planets and moons.
 *
 * <p>CelestialSystem owns deterministic orbital presentation. This class owns the mutable gameplay
 * state that sits on top of those bodies: intel, deposits, installations, claims, objectives and
 * strategic bonuses. Physical resources and stations remain normal ResourceNode/Base instances so
 * the existing mining, combat, logistics and replication systems continue to be authoritative.</p>
 */
final class CelestialGameplaySystem {
    private static final CelestialGameplayConfig CONFIG = CelestialGameplayConfig.INSTANCE;

    static final double SCAN_SECONDS = CONFIG.scanSeconds;
    static final double ANALYZE_SECONDS = CONFIG.analyzeSeconds;
    static final double HOLD_OBJECTIVE_SECONDS = CONFIG.holdSeconds;
    static final double EXTRACT_OBJECTIVE_AMOUNT = CONFIG.extractAmount;

    // Keep generated celestial deposits in a stable, deterministic namespace far above the normal
    // sequential galaxy resource allocator. This makes the same body/material resolve to the same
    // id on host, clients and save restore without advancing GalaxyCoordinator.nextResourceId.
    private static final int CELESTIAL_RESOURCE_ID_BASE = 1 << 30;
    private static final long CELESTIAL_RESOURCE_ID_SPAN = (long)Integer.MAX_VALUE - CELESTIAL_RESOURCE_ID_BASE;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final int ORBIT_ANGLE_SEARCH_STEPS = 1440;
    private static final double ORBIT_DISTANCE_EPSILON = 1.0e-6;

    private static final Map<CelestialSystem, WorldSystemState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ResourceNode, WorldSystemState> RESOURCE_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CelestialGameplaySystem() { }

    static void registerState(WorldSystemState state) {
        if (state == null || state.celestials == null) return;
        STATES.put(state.celestials, state);
        ensureBodyStates(state);
    }

    static void onCelestialUpdate(CelestialSystem celestials, double dt) {
        WorldSystemState state = STATES.get(celestials);
        if (state == null) return;
        ensureBodyStates(state);
        ensureBodyDeposits(state);
        sanitizeStationAnchors(state);
        normalizeAnchoredStationOrbits(state);
        updateClaimsAndInstallations(state);
        anchorNearbyStations(state);
        updateClaimsAndInstallations(state);
        updateAnchoredResources(state);
        updateOrbitalStations(state, dt);
        updateScanning(state, dt);
        updateObjectives(state, dt);
    }

    static CelestialBodyState bodyState(WorldSystemState state, String bodyId) {
        if (state == null || bodyId == null) return null;
        ensureBodyStates(state);
        return state.celestialBodies.get(bodyId);
    }

    static List<CelestialBodyState> bodyStates(WorldSystemState state) {
        if (state == null) return List.of();
        ensureBodyStates(state);
        return List.copyOf(state.celestialBodies.values());
    }

    static CelestialIntelLevel intel(WorldSystemState state, String bodyId, String playerId) {
        CelestialBodyState body = bodyState(state, bodyId);
        if (body == null || playerId == null || playerId.isBlank()) return CelestialIntelLevel.VISIBLE;
        return body.intelByPlayer.getOrDefault(playerId, CelestialIntelLevel.VISIBLE);
    }

    static double bonusMultiplier(WorldSystemState state, String playerId, CelestialBonusKind kind) {
        if (state == null || playerId == null || playerId.isBlank() || kind == null) return 1.0;
        ensureBodyStates(state);
        double bonus = 0.0;
        for (CelestialBodyState body : state.celestialBodies.values()) {
            if (body.contested || !playerId.equals(body.claimantId)) continue;
            if (!installationSupports(body.installations, kind)) continue;
            bonus += body.profile.bonuses().getOrDefault(kind, 0.0);
        }
        return 1.0 + Math.min(CONFIG.maxTotalBonus, Math.max(0.0, bonus));
    }

    static CelestialObjectiveStatus objectiveStatus(
            WorldSystemState state, String bodyId, String playerId, CelestialObjectiveType objective) {
        CelestialBodyState body = bodyState(state, bodyId);
        if (body == null || playerId == null || objective == null) {
            return new CelestialObjectiveStatus(objective, bodyId == null ? "" : bodyId, 0, 1, false);
        }
        return switch (objective) {
            case SCAN -> {
                double progress = intel(state, bodyId, playerId).ordinal() >= CelestialIntelLevel.SCANNED.ordinal() ? 1 : 0;
                yield new CelestialObjectiveStatus(objective, bodyId, progress, 1, progress >= 1);
            }
            case CLAIM -> {
                double progress = !body.contested && playerId.equals(body.claimantId) ? 1 : 0;
                yield new CelestialObjectiveStatus(objective, bodyId, progress, 1, progress >= 1);
            }
            case HOLD -> {
                double progress = body.holdSecondsByPlayer.getOrDefault(playerId, 0.0);
                yield new CelestialObjectiveStatus(objective, bodyId, progress, HOLD_OBJECTIVE_SECONDS,
                        progress >= HOLD_OBJECTIVE_SECONDS);
            }
            case EXTRACT -> {
                double progress = body.extractedByPlayer.getOrDefault(playerId, 0.0);
                yield new CelestialObjectiveStatus(objective, bodyId, progress, EXTRACT_OBJECTIVE_AMOUNT,
                        progress >= EXTRACT_OBJECTIVE_AMOUNT);
            }
        };
    }

    /** Attribute extraction to the ship owner that actually mined the body-linked node. */
    static void recordExtraction(ResourceNode node, String playerId, double amount) {
        if (node == null || node.celestialAnchorBodyId == null || node.celestialAnchorBodyId.isBlank()
                || playerId == null || playerId.isBlank() || !Double.isFinite(amount) || amount <= 0) return;
        WorldSystemState state = RESOURCE_STATES.get(node);
        if (state == null) {
            synchronized (STATES) {
                for (WorldSystemState candidate : STATES.values()) {
                    if (candidate != null && candidate.resources.contains(node)) {
                        state = candidate;
                        RESOURCE_STATES.put(node, candidate);
                        break;
                    }
                }
            }
        }
        CelestialBodyState body = state == null ? null : bodyState(state, node.celestialAnchorBodyId);
        if (body != null) body.extractedByPlayer.merge(playerId, amount, Double::sum);
    }

    private static void ensureBodyStates(WorldSystemState state) {
        for (CelestialSystem.BodyView view : state.celestials.bodyViews()) {
            if (view.visualClass() == CelestialVisualClass.STAR) continue;
            state.celestialBodies.computeIfAbsent(view.id(), id ->
                    new CelestialBodyState(profileFor(state, view)));
        }
    }

    private static CelestialGameplayProfile profileFor(WorldSystemState state, CelestialSystem.BodyView view) {
        String visual = view.visualClass() == null ? "ROCKY" : view.visualClass().name();
        List<Material> source = state.definition != null && state.definition.spawnMaterials() != null
                && !state.definition.spawnMaterials().isEmpty()
                ? state.definition.spawnMaterials() : Arrays.asList(Material.values());
        List<Material> deposits = new ArrayList<>();
        if (!source.isEmpty()) {
            int count = Math.min(source.size(), 1 + Math.floorMod(Objects.hash(state.id, view.id(), "count"), 3));
            for (int i = 0; i < count; i++) {
                Material material = source.get(Math.floorMod(Objects.hash(state.id, view.id(), i), source.size()));
                if (!deposits.contains(material)) deposits.add(material);
            }
            if (deposits.isEmpty()) deposits.add(source.get(0));
        }

        CelestialGameplayConfig.BodyProfile configured = CONFIG.bodyProfile(visual);
        List<String> traits = new ArrayList<>(configured.traits());
        List<String> hazards = new ArrayList<>(configured.hazards());
        EnumMap<CelestialBonusKind, Double> bonuses = new EnumMap<>(CelestialBonusKind.class);
        bonuses.putAll(configured.bonuses());
        int slots = configured.slots();

        if (view.moon()) {
            traits.addAll(CONFIG.moonModifier.traits());
            CONFIG.moonModifier.bonuses().forEach((kind, value) -> bonuses.merge(kind, value, Double::sum));
            slots = Math.max(CONFIG.moonModifier.minimumSlots(), slots + CONFIG.moonModifier.slotDelta());
        }
        return new CelestialGameplayProfile(view.id(), view.name(), visual, deposits, traits, hazards, slots, bonuses);
    }

    private static void ensureBodyDeposits(WorldSystemState state) {
        Set<Integer> plannedIds = new HashSet<>();
        CelestialGameplayConfig.DepositRules rules = CONFIG.deposits;
        for (CelestialBodyState body : state.celestialBodies.values()) {
            CelestialSystem.BodyView view = state.celestials.bodyView(body.profile.bodyId());
            if (view == null) continue;
            int slot = 0;
            boolean complete = true;
            for (Material material : body.profile.deposits()) {
                int plannedId = plannedResourceId(state, body.profile.bodyId(), material, slot, plannedIds);
                ResourceNode node = findSavedDeposit(state, body, view, material, plannedId);
                if (node == null) {
                    double orbitRadius = Math.max(view.radius() + rules.orbitPadding(), view.radius() * rules.orbitRadiusScale())
                            + slot * rules.orbitSlotSpacing();
                    double angle = normalizedAngle(Objects.hash(state.id, view.id(), material.name(), slot));
                    double speed = rules.orbitBaseSpeed() + rules.orbitSpeedPerSlot() * (slot + 1);
                    double x = view.x() + Math.cos(angle) * orbitRadius;
                    double y = view.y() + Math.sin(angle) * orbitRadius;
                    node = new ResourceNode(
                            plannedId,
                            material.label + " deposit",
                            depositKind(state, material),
                            material,
                            x,
                            y,
                            rules.baseAmount() + slot * rules.amountPerMaterialSlot(),
                            rules.harvestRate(),
                            rules.nodeRadius());
                    node.orbit(view.x(), view.y(), orbitRadius, angle, speed);
                    state.resources.add(node);
                }
                attachDeposit(state, body, view, node);
                complete &= body.resourceNodeIds.contains(node.id);
                slot++;
            }
            body.depositsSeeded = complete && body.resourceNodeIds.size() >= body.profile.deposits().size();
        }
    }

    private static ResourceNode findSavedDeposit(WorldSystemState state, CelestialBodyState body,
                                                  CelestialSystem.BodyView view, Material material, int plannedId) {
        ResourceNode exact = resourceById(state, plannedId);
        if (looksLikeDepositFor(state, exact, body, view, material)) return exact;
        for (ResourceNode node : state.resources) {
            if (looksLikeDepositFor(state, node, body, view, material) && !body.resourceNodeIds.contains(node.id)) return node;
        }
        return null;
    }

    private static boolean looksLikeDepositFor(WorldSystemState state, ResourceNode node, CelestialBodyState body,
                                                CelestialSystem.BodyView view, Material material) {
        if (node == null || node.material != material || node.kind != depositKind(state, material)) return false;
        if (body.profile.bodyId().equals(node.celestialAnchorBodyId)) return true;
        if (node.celestialAnchorBodyId != null && !node.celestialAnchorBodyId.isBlank()) return false;
        if (!node.orbiting || node.name == null || !node.name.equals(material.label + " deposit")) return false;
        // Saves prior to explicit celestial-anchor persistence already retain the moving orbit center.
        // At restore time the deterministic body has been advanced to the same systemTime, so this
        // proximity check safely reattaches old deposits instead of creating duplicates.
        return Math.hypot(node.orbitCenterX - view.x(), node.orbitCenterY - view.y()) <= CONFIG.deposits.reattachTolerance();
    }

    private static NodeKind depositKind(WorldSystemState state, Material material) {
        if (state != null && state.definition != null) {
            for (ResourceBelt belt : state.definition.resourceBelts()) {
                if (belt != null && belt.materials != null && belt.materials.contains(material)) return belt.kind;
            }
        }
        for (ResourceBelt belt : Rules.RESOURCE_BELTS) {
            if (belt != null && belt.materials != null && belt.materials.contains(material)) return belt.kind;
        }
        return NodeKind.SILICATE_ROCK;
    }

    private static void attachDeposit(WorldSystemState state, CelestialBodyState body,
                                      CelestialSystem.BodyView view, ResourceNode node) {
        node.celestialAnchorBodyId = body.profile.bodyId();
        node.orbitCenterX = view.x();
        node.orbitCenterY = view.y();
        if (!body.resourceNodeIds.contains(node.id)) body.resourceNodeIds.add(node.id);
        RESOURCE_STATES.put(node, state);
    }

    private static int plannedResourceId(WorldSystemState state, String bodyId, Material material,
                                         int slot, Set<Integer> plannedIds) {
        long hash = stableHash(state == null ? "" : state.id, bodyId,
                material == null ? "" : material.name(), Integer.toString(slot));
        int candidate = CELESTIAL_RESOURCE_ID_BASE + (int)Math.floorMod(hash, CELESTIAL_RESOURCE_ID_SPAN);
        while (plannedIds.contains(candidate) || resourceIdUsedElsewhere(state, candidate)) {
            candidate++;
            if (candidate <= 0 || candidate == Integer.MAX_VALUE) candidate = CELESTIAL_RESOURCE_ID_BASE;
        }
        plannedIds.add(candidate);
        return candidate;
    }

    private static boolean resourceIdUsedElsewhere(WorldSystemState state, int id) {
        synchronized (STATES) {
            for (WorldSystemState candidate : STATES.values()) {
                if (candidate == null || candidate == state || Objects.equals(candidate.id, state.id)) continue;
                for (ResourceNode node : candidate.resources) if (node.id == id) return true;
            }
        }
        return false;
    }

    private static long stableHash(String... parts) {
        long hash = 0xcbf29ce484222325L;
        for (String part : parts) {
            String text = part == null ? "" : part;
            for (int i = 0; i < text.length(); i++) {
                hash ^= text.charAt(i);
                hash *= 0x100000001b3L;
            }
            hash ^= 0xff;
            hash *= 0x100000001b3L;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        return hash ^ (hash >>> 33);
    }

    static boolean isStationAnchorBody(CelestialSystem.BodyView body) {
        return body != null && body.visualClass() != CelestialVisualClass.STAR && !body.moon();
    }

    static void clearStationAnchor(Base base) {
        if (base == null) return;
        base.celestialAnchorBodyId = "";
        base.celestialOrbitRadius = 0;
        base.celestialOrbitAngle = 0;
        base.celestialOrbitSpeed = 0;
    }

    private static List<Base> sortedBases(WorldSystemState state) {
        List<Base> bases = new ArrayList<>(state.bases.values());
        bases.sort(Comparator.comparing(base -> base.id == null ? "" : base.id));
        return bases;
    }

    private static void sanitizeStationAnchors(WorldSystemState state) {
        for (Base base : sortedBases(state)) {
            if (base.celestialAnchorBodyId == null || base.celestialAnchorBodyId.isBlank()) continue;
            CelestialSystem.BodyView body = state.celestials.bodyView(base.celestialAnchorBodyId);
            if (!isStationAnchorBody(body)) clearStationAnchor(base);
        }
    }

    private static void normalizeAnchoredStationOrbits(WorldSystemState state) {
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (!isStationAnchorBody(body)) continue;
            List<Base> anchored = anchoredBases(state, body.id());
            if (!anchored.isEmpty()) normalizeOrbitLane(body, anchored);
        }
    }

    private static void anchorNearbyStations(WorldSystemState state) {
        List<CelestialSystem.BodyView> bodies = state.celestials.bodyViews();
        CelestialGameplayConfig.AnchorRules rules = CONFIG.anchoring;
        for (Base base : sortedBases(state)) {
            if (base.celestialAnchorBodyId != null && !base.celestialAnchorBodyId.isBlank()) continue;
            CelestialSystem.BodyView nearest = null;
            double nearestDistance = Double.POSITIVE_INFINITY;
            for (CelestialSystem.BodyView body : bodies) {
                if (!isStationAnchorBody(body) || !canAnchorToPlanet(state, body, base)) continue;
                double distance = Math.hypot(base.x - body.x(), base.y - body.y());
                double captureDistance = Math.max(rules.captureMinDistance(), body.radius() + rules.captureRadiusPadding());
                if (distance > captureDistance) continue;
                if (distance < nearestDistance - ORBIT_DISTANCE_EPSILON
                        || (Math.abs(distance - nearestDistance) <= ORBIT_DISTANCE_EPSILON
                        && (nearest == null || body.id().compareTo(nearest.id()) < 0))) {
                    nearest = body;
                    nearestDistance = distance;
                }
            }
            if (nearest == null) continue;

            double preferredAngle = normalizedRadians(Math.atan2(base.y - nearest.y(), base.x - nearest.x()));
            base.celestialAnchorBodyId = nearest.id();
            base.celestialOrbitAngle = preferredAngle;
            placeNewStationOnOrbit(state, nearest, base, preferredAngle);
        }
    }

    private static boolean canAnchorToPlanet(WorldSystemState state, CelestialSystem.BodyView body, Base candidate) {
        if (!isStationAnchorBody(body) || candidate == null) return false;
        CelestialBodyState bodyState = state.celestialBodies.get(body.id());
        if (bodyState == null || bodyState.profile.installationSlots() <= 0) return false;

        int anchoredCount = 0;
        String claimant = null;
        for (Base base : state.bases.values()) {
            if (!body.id().equals(base.celestialAnchorBodyId)) continue;
            anchoredCount++;
            if (claimant == null) claimant = base.playerId;
            else if (!Objects.equals(claimant, base.playerId)) return false;
        }
        if (anchoredCount >= bodyState.profile.installationSlots()) return false;
        return claimant == null || Objects.equals(claimant, candidate.playerId);
    }

    private static List<Base> anchoredBases(WorldSystemState state, String bodyId) {
        List<Base> anchored = new ArrayList<>();
        for (Base base : state.bases.values()) {
            if (bodyId.equals(base.celestialAnchorBodyId)) anchored.add(base);
        }
        anchored.sort(Comparator.comparing(base -> base.id == null ? "" : base.id));
        return anchored;
    }

    private static void placeNewStationOnOrbit(
            WorldSystemState state, CelestialSystem.BodyView body, Base candidate, double preferredAngle) {
        List<Base> anchored = anchoredBases(state, body.id());
        double radius = canonicalStationOrbitRadius(body, anchored);
        double speed = stationOrbitSpeed(radius);
        for (Base base : anchored) {
            base.celestialOrbitRadius = radius;
            base.celestialOrbitSpeed = speed;
            base.celestialOrbitAngle = normalizedRadians(base.celestialOrbitAngle);
        }

        List<Base> existing = new ArrayList<>();
        for (Base base : anchored) if (base != candidate) existing.add(base);
        Double safe = nearestSafeAngle(candidate, preferredAngle, existing, radius);
        if (safe != null) {
            candidate.celestialOrbitAngle = safe;
            return;
        }

        // A new installation can make a previously irregular layout impossible without moving an
        // older station. Only in that case do a deterministic even re-layout of the shared lane.
        normalizeOrbitLane(body, anchored);
    }

    private static void normalizeOrbitLane(CelestialSystem.BodyView body, List<Base> anchored) {
        if (anchored == null || anchored.isEmpty()) return;
        anchored.sort(Comparator.comparing(base -> base.id == null ? "" : base.id));
        double radius = canonicalStationOrbitRadius(body, anchored);
        double speed = stationOrbitSpeed(radius);
        List<Base> placed = new ArrayList<>();
        for (Base base : anchored) {
            base.celestialOrbitRadius = radius;
            base.celestialOrbitSpeed = speed;
            double preferred = Double.isFinite(base.celestialOrbitAngle)
                    ? normalizedRadians(base.celestialOrbitAngle)
                    : normalizedRadians(Math.atan2(base.y - body.y(), base.x - body.x()));
            Double safe = nearestSafeAngle(base, preferred, placed, radius);
            if (safe == null) {
                applyEvenOrbitLayout(body, anchored, radius, speed);
                return;
            }
            base.celestialOrbitAngle = safe;
            placed.add(base);
        }
    }

    private static double canonicalStationOrbitRadius(CelestialSystem.BodyView body, List<Base> anchored) {
        CelestialGameplayConfig.AnchorRules rules = CONFIG.anchoring;
        double maxExtent = 0;
        for (Base base : anchored) maxExtent = Math.max(maxExtent, StationRenderer.visualExtent(base));
        double radius = body.radius() + maxExtent + rules.orbitPadding();
        int count = anchored.size();
        if (count > 1) {
            double conservativeSeparation = maxExtent * 2.0 + rules.stationSeparationPadding();
            double denominator = 2.0 * Math.sin(Math.PI / count);
            if (denominator > 1.0e-9) radius = Math.max(radius, conservativeSeparation / denominator);
        }
        return Math.max(rules.orbitSpeedMinimumRadius(), radius);
    }

    private static double stationOrbitSpeed(double radius) {
        CelestialGameplayConfig.AnchorRules rules = CONFIG.anchoring;
        return rules.orbitSpeedBase()
                * Math.sqrt(rules.orbitSpeedReferenceRadius()
                / Math.max(rules.orbitSpeedMinimumRadius(), radius));
    }

    private static Double nearestSafeAngle(Base candidate, double preferredAngle, List<Base> placed, double radius) {
        double preferred = normalizedRadians(preferredAngle);
        if (angleIsSafe(candidate, preferred, placed, radius)) return preferred;
        double step = TWO_PI / ORBIT_ANGLE_SEARCH_STEPS;
        for (int i = 1; i <= ORBIT_ANGLE_SEARCH_STEPS / 2; i++) {
            double plus = normalizedRadians(preferred + i * step);
            if (angleIsSafe(candidate, plus, placed, radius)) return plus;
            double minus = normalizedRadians(preferred - i * step);
            if (angleIsSafe(candidate, minus, placed, radius)) return minus;
        }
        return null;
    }

    private static boolean angleIsSafe(Base candidate, double angle, List<Base> placed, double radius) {
        CelestialGameplayConfig.AnchorRules rules = CONFIG.anchoring;
        for (Base other : placed) {
            double delta = angularDistance(angle, other.celestialOrbitAngle);
            double chord = 2.0 * radius * Math.sin(delta * 0.5);
            double minimum = StationRenderer.visualExtent(candidate)
                    + StationRenderer.visualExtent(other)
                    + rules.stationSeparationPadding();
            if (chord + ORBIT_DISTANCE_EPSILON < minimum) return false;
        }
        return true;
    }

    private static void applyEvenOrbitLayout(
            CelestialSystem.BodyView body, List<Base> anchored, double radius, double speed) {
        anchored.sort(Comparator.comparing(base -> base.id == null ? "" : base.id));
        double start = anchored.isEmpty() ? 0 : normalizedRadians(anchored.get(0).celestialOrbitAngle);
        if (!Double.isFinite(start)) {
            start = normalizedAngle(Objects.hash(body.id(), anchored.get(0).id, "station-orbit"));
        }
        double spacing = TWO_PI / anchored.size();
        for (int i = 0; i < anchored.size(); i++) {
            Base base = anchored.get(i);
            base.celestialOrbitRadius = radius;
            base.celestialOrbitSpeed = speed;
            base.celestialOrbitAngle = normalizedRadians(start + i * spacing);
        }
    }

    private static double angularDistance(double a, double b) {
        double delta = Math.abs(normalizedRadians(a) - normalizedRadians(b));
        return Math.min(delta, TWO_PI - delta);
    }

    private static double normalizedRadians(double angle) {
        if (!Double.isFinite(angle)) return 0;
        double normalized = angle % TWO_PI;
        return normalized < 0 ? normalized + TWO_PI : normalized;
    }

    private static void updateAnchoredResources(WorldSystemState state) {
        for (ResourceNode node : state.resources) {
            if (node.celestialAnchorBodyId == null || node.celestialAnchorBodyId.isBlank()) continue;
            CelestialSystem.BodyView body = state.celestials.bodyView(node.celestialAnchorBodyId);
            if (body == null) continue;
            node.orbitCenterX = body.x();
            node.orbitCenterY = body.y();
            RESOURCE_STATES.put(node, state);
            if (!node.orbiting) {
                double radius = Math.max(body.radius() + CONFIG.deposits.orbitPadding(),
                        Math.hypot(node.x - body.x(), node.y - body.y()));
                double angle = Math.atan2(node.y - body.y(), node.x - body.x());
                node.orbit(body.x(), body.y(), radius, angle, CONFIG.deposits.fallbackOrbitSpeed());
            }
        }
    }

    private static void updateOrbitalStations(WorldSystemState state, double dt) {
        if (!Double.isFinite(dt)) dt = 0;
        for (Base base : state.bases.values()) {
            if (base.celestialAnchorBodyId == null || base.celestialAnchorBodyId.isBlank()) continue;
            CelestialSystem.BodyView body = state.celestials.bodyView(base.celestialAnchorBodyId);
            if (body == null) continue;
            base.celestialOrbitAngle = normalizedRadians(base.celestialOrbitAngle + base.celestialOrbitSpeed * dt);
            base.x = body.x() + Math.cos(base.celestialOrbitAngle) * base.celestialOrbitRadius;
            base.y = body.y() + Math.sin(base.celestialOrbitAngle) * base.celestialOrbitRadius;
        }
    }

    private static void updateClaimsAndInstallations(WorldSystemState state) {
        for (CelestialBodyState body : state.celestialBodies.values()) {
            body.orbitalBaseIds.clear();
            body.installations.clear();
            Set<String> owners = new HashSet<>();
            for (Base base : sortedBases(state)) {
                if (!body.profile.bodyId().equals(base.celestialAnchorBodyId)) continue;
                body.orbitalBaseIds.add(base.id);
                owners.add(base.playerId);
                if (body.installations.size() < body.profile.installationSlots()) {
                    body.installations.add(installationType(base));
                }
            }
            body.contested = owners.size() > 1;
            body.claimantId = owners.size() == 1 ? owners.iterator().next() : "";
        }
    }

    private static void updateScanning(WorldSystemState state, double dt) {
        if (!Double.isFinite(dt) || dt <= 0) return;
        for (CelestialBodyState body : state.celestialBodies.values()) {
            CelestialSystem.BodyView view = state.celestials.bodyView(body.profile.bodyId());
            if (view == null) continue;
            Set<String> scanners = new HashSet<>();
            double unitRange = view.radius() + CONFIG.scanning.unitRangePadding();
            double stationRange = view.radius() + CONFIG.scanning.stationRangePadding();
            for (Unit unit : state.units.values()) {
                if (Math.hypot(unit.x - view.x(), unit.y - view.y()) <= unitRange) scanners.add(unit.playerId);
            }
            for (Base base : state.bases.values()) {
                if (Math.hypot(base.x - view.x(), base.y - view.y()) <= stationRange) scanners.add(base.playerId);
            }
            for (String playerId : scanners) {
                double seconds = body.scanSecondsByPlayer.merge(playerId, dt, Double::sum);
                CelestialIntelLevel level = seconds >= ANALYZE_SECONDS ? CelestialIntelLevel.ANALYZED
                        : seconds >= SCAN_SECONDS ? CelestialIntelLevel.SCANNED : CelestialIntelLevel.VISIBLE;
                body.intelByPlayer.put(playerId, level);
            }
        }
    }

    private static void updateObjectives(WorldSystemState state, double dt) {
        if (!Double.isFinite(dt) || dt < 0) dt = 0;
        for (CelestialBodyState body : state.celestialBodies.values()) {
            if (!body.contested && body.claimantId != null && !body.claimantId.isBlank()) {
                body.holdSecondsByPlayer.merge(body.claimantId, dt, Double::sum);
            }
        }
    }

    private static ResourceNode resourceById(WorldSystemState state, int id) {
        for (ResourceNode node : state.resources) if (node.id == id) return node;
        return null;
    }

    private static CelestialInstallationType installationType(Base base) {
        return CONFIG.installationType(base == null ? "" : base.typeId);
    }

    private static boolean installationSupports(List<CelestialInstallationType> installations, CelestialBonusKind kind) {
        return CONFIG.installationSupports(installations, kind);
    }

    private static double normalizedAngle(int seed) {
        long unsigned = Integer.toUnsignedLong(seed);
        return (unsigned / (double) 0x1_0000_0000L) * Math.PI * 2.0;
    }
}

enum CelestialIntelLevel {
    VISIBLE,
    SCANNED,
    ANALYZED
}

enum CelestialInstallationType {
    EXTRACTOR,
    RESEARCH_SITE,
    SENSOR_ARRAY,
    LOGISTICS_HUB
}

enum CelestialBonusKind {
    MINING,
    RESEARCH,
    SENSOR,
    LOGISTICS,
    PRODUCTION,
    REPAIR,
    SHIELD
}

enum CelestialObjectiveType {
    SCAN,
    CLAIM,
    HOLD,
    EXTRACT
}

record CelestialGameplayProfile(
        String bodyId,
        String bodyName,
        String bodyType,
        List<Material> deposits,
        List<String> traits,
        List<String> hazards,
        int installationSlots,
        Map<CelestialBonusKind, Double> bonuses
) {
    CelestialGameplayProfile {
        bodyId = bodyId == null ? "" : bodyId;
        bodyName = bodyName == null ? bodyId : bodyName;
        bodyType = bodyType == null ? "ROCKY" : bodyType;
        deposits = deposits == null ? List.of() : List.copyOf(deposits);
        traits = traits == null ? List.of() : List.copyOf(traits);
        hazards = hazards == null ? List.of() : List.copyOf(hazards);
        installationSlots = Math.max(1, installationSlots);
        bonuses = bonuses == null ? Map.of() : Map.copyOf(bonuses);
    }
}

record CelestialObjectiveStatus(
        CelestialObjectiveType objective,
        String bodyId,
        double progress,
        double target,
        boolean complete
) { }

final class CelestialBodyState {
    final CelestialGameplayProfile profile;
    String claimantId = "";
    boolean contested;
    final Map<String, CelestialIntelLevel> intelByPlayer = new HashMap<>();
    final Map<String, Double> scanSecondsByPlayer = new HashMap<>();
    final Map<String, Double> holdSecondsByPlayer = new HashMap<>();
    final Map<String, Double> extractedByPlayer = new HashMap<>();
    final List<CelestialInstallationType> installations = new ArrayList<>();
    final List<Integer> resourceNodeIds = new ArrayList<>();
    final List<String> orbitalBaseIds = new ArrayList<>();
    boolean depositsSeeded;

    CelestialBodyState(CelestialGameplayProfile profile) {
        this.profile = profile;
    }
}
