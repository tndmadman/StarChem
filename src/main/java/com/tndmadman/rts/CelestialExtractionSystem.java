package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Point2D;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Gates physical planet/moon deposits behind a dedicated Planetary Extractor and a fracture charge.
 * Survey intel may reveal what a body contains, but ResourceNodes remain sealed/inactive until a
 * charge fired from an extractor anchored to the master planet reaches that specific body.
 *
 * <p>Each surveyed material is fractured into a configured debris field of smaller mineables while
 * preserving the same total resource volume. Celestial nodes are recycled between fracture cycles
 * instead of being removed/re-added, keeping depletion and refire off the allocation-heavy path.</p>
 */
final class CelestialExtractionSystem {
    private static final CelestialGameplayConfig CONFIG = CelestialGameplayConfig.INSTANCE;
    private static final CelestialGameplayConfig.ExtractionRules EXTRACTION = CONFIG.extraction;
    private static final CelestialGameplayConfig.DepositRules DEPOSITS = CONFIG.deposits;
    private static final CelestialGameplayConfig.AnchorRules ANCHORING = CONFIG.anchoring;

    static final String EXTRACTOR_STATION_ID = EXTRACTION.stationTypeId();
    static final int FRAGMENTS_PER_DEPOSIT = EXTRACTION.fragmentsPerDeposit();
    static final double CHARGE_SECONDS = EXTRACTION.chargeSeconds();
    static final double EXTRACTOR_FIRE_COOLDOWN_SECONDS = EXTRACTION.extractorFireCooldownSeconds();
    static final double REFIRE_COOLDOWN_SECONDS = EXTRACTION.refireCooldownSeconds();

    private static final double IMPACT_EFFECT_SECONDS = EXTRACTION.impactEffectSeconds();
    private static final int MAX_WIRE_ROWS = 512;
    private static final int MAX_WIRE_CHARS = 64 * 1024;
    private static final int CELESTIAL_FRAGMENT_ID_BASE = 1 << 30;
    private static final long CELESTIAL_FRAGMENT_ID_SPAN = (long)Integer.MAX_VALUE - CELESTIAL_FRAGMENT_ID_BASE;
    private static final double TAU = Math.PI * 2.0;

    private static final Map<CelestialSystem, WorldSystemState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<WorldSystemState, ExtractionState> DATA =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ResourceNode, WorldSystemState> RESOURCE_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<WorldSystemState, World> WORLDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CelestialExtractionSystem() { }

    static void bindWorld(WorldSystemState state, World world) {
        if (state != null && world != null) WORLDS.put(state, world);
    }

    static void register(WorldSystemState state) {
        if (state == null || state.celestials == null) return;
        STATES.put(state.celestials, state);
        DATA.computeIfAbsent(state, ignored -> new ExtractionState());
    }

    static boolean released(WorldSystemState state, String bodyId) {
        if (state == null || bodyId == null || bodyId.isBlank()) return false;
        return data(state).releasedBodyIds.contains(bodyId);
    }

    static boolean chargeInFlight(WorldSystemState state, String bodyId) {
        if (state == null || bodyId == null || bodyId.isBlank()) return false;
        for (Charge charge : data(state).charges) if (bodyId.equals(charge.bodyId)) return true;
        return false;
    }

    static double chargeProgress(WorldSystemState state, String bodyId) {
        if (state == null || bodyId == null || bodyId.isBlank()) return 0.0;
        for (Charge charge : data(state).charges) {
            if (bodyId.equals(charge.bodyId)) return Math.max(0.0, Math.min(1.0, charge.elapsed / CHARGE_SECONDS));
        }
        return released(state, bodyId) ? 1.0 : 0.0;
    }

    static double cooldownRemaining(WorldSystemState state, String bodyId) {
        if (state == null || bodyId == null || bodyId.isBlank()) return 0.0;
        return Math.max(0.0, data(state).cooldownSecondsByBody.getOrDefault(bodyId, 0.0));
    }

    static double extractorCooldownRemaining(WorldSystemState state, String bodyId, String playerId) {
        Base extractor = extractorFor(state, bodyId, playerId);
        if (extractor == null) return 0.0;
        return Math.max(0.0, data(state).cooldownSecondsByExtractor.getOrDefault(extractor.id, 0.0));
    }

    static int activeFragmentCount(WorldSystemState state, String bodyId) {
        if (state == null || bodyId == null || bodyId.isBlank()) return 0;
        int count = 0;
        for (ResourceNode node : state.resources) {
            if (locatable(node, bodyId)) count++;
        }
        return count;
    }

    static ResourceNode locatableFragment(WorldSystemState state, String bodyId, int cycle) {
        int count = activeFragmentCount(state, bodyId);
        if (count <= 0) return null;
        int wanted = Math.floorMod(cycle, count);
        int seen = 0;
        for (ResourceNode node : state.resources) {
            if (!locatable(node, bodyId)) continue;
            if (seen++ == wanted) return node;
        }
        return null;
    }

    private static boolean locatable(ResourceNode node, String bodyId) {
        return node != null && node.active && node.amount > 0.05
                && bodyId != null && bodyId.equals(node.celestialAnchorBodyId);
    }

    static boolean extractorReady(WorldSystemState state, String bodyId, String playerId) {
        return extractorFor(state, bodyId, playerId) != null;
    }

    static boolean fireReady(WorldSystemState state, String bodyId, String playerId) {
        if (state == null) return false;
        reconcileExhaustedReleasedField(state, bodyId);
        if (released(state, bodyId) || chargeInFlight(state, bodyId)
                || cooldownRemaining(state, bodyId) > 0.001) return false;
        Base extractor = extractorFor(state, bodyId, playerId);
        return extractor != null && data(state).cooldownSecondsByExtractor.getOrDefault(extractor.id, 0.0) <= 0.001;
    }

    /** Player-facing and simulation entry point used by the integrated celestial intel control. */
    static FireResult fireCharge(WorldSystemState state, String bodyId, String playerId) {
        return fireChargeInternal(state, bodyId, playerId);
    }

    static FireResult fireChargeFromButton(WorldSystemState state, String bodyId, String playerId) {
        return fireChargeInternal(state, bodyId, playerId);
    }

    private static FireResult fireChargeInternal(WorldSystemState state, String bodyId, String playerId) {
        if (state == null || state.celestials == null || bodyId == null || bodyId.isBlank()) {
            return new FireResult(false, "No celestial body selected.");
        }
        CelestialSystem.BodyView target = state.celestials.bodyView(bodyId);
        if (target == null || target.visualClass() == CelestialVisualClass.STAR) {
            return new FireResult(false, "Extraction charges can only target planets and moons.");
        }
        reconcileExhaustedReleasedField(state, bodyId);
        if (released(state, bodyId)) {
            return new FireResult(false, target.name() + " still has an exposed extraction field. Deplete it before refiring.");
        }
        if (chargeInFlight(state, bodyId)) {
            return new FireResult(false, "Extraction charge already in flight to " + target.name() + ".");
        }
        double cooldown = cooldownRemaining(state, bodyId);
        if (cooldown > 0.001) {
            return new FireResult(false, "Fracture chamber recycling for " + (int)Math.ceil(cooldown)
                    + "s before " + target.name() + " can be fractured again.");
        }

        Base extractor = extractorFor(state, bodyId, playerId);
        if (extractor == null) {
            String masterId = CelestialMoonInheritance.masterPlanetId(state, bodyId);
            CelestialSystem.BodyView master = state.celestials.bodyView(masterId);
            String masterName = master == null ? "the master planet" : master.name();
            return new FireResult(false, "Deploy and anchor a Planetary Extractor to " + masterName
                    + " before firing a fracture charge at " + target.name() + ".");
        }

        ExtractionState extraction = data(state);
        if (extraction.networkReplica) {
            return new FireResult(false, "Fracture charges must be fired by the authoritative server.");
        }
        double extractorCooldown = Math.max(0.0,
                extraction.cooldownSecondsByExtractor.getOrDefault(extractor.id, 0.0));
        if (extractorCooldown > 0.001) {
            return new FireResult(false, extractor.type().name + " firing systems cooling for "
                    + (int)Math.ceil(extractorCooldown) + "s.");
        }

        extraction.sealedBodyIds.add(bodyId);
        extraction.charges.add(new Charge(bodyId, extractor.id, playerId == null ? "" : playerId));
        extraction.cooldownSecondsByExtractor.put(extractor.id, EXTRACTOR_FIRE_COOLDOWN_SECONDS);
        return new FireResult(true, "Fracture charge fired from " + extractor.type().name + " at " + target.name()
                + ". Planetary fracture sequence: " + (int)CHARGE_SECONDS + " seconds.");
    }

    /** Called immediately when a celestial rock depletes. */
    static boolean onDepositDepleted(ResourceNode node) {
        if (!isCelestial(node)) return false;
        WorldSystemState state = RESOURCE_STATES.get(node);
        if (state == null) state = findState(node);
        if (state == null || data(state).networkReplica) return false;
        return finishFieldIfExhausted(state, node.celestialAnchorBodyId);
    }

    static void update(CelestialSystem celestials, double dt) {
        WorldSystemState state = STATES.get(celestials);
        if (state == null) return;
        ExtractionState extraction = data(state);
        if (extraction.networkReplica) {
            advanceReplicaVisuals(extraction, dt);
            return;
        }

        if (normalizeExtractorAnchors(state)) CelestialMoonInheritance.apply(state);

        ensureFractureFields(state, extraction);

        for (CelestialBodyState body : CelestialGameplaySystem.bodyStates(state)) {
            bindBodyNodes(state, body);
            String bodyId = body.profile.bodyId();
            if (!extraction.releasedBodyIds.contains(bodyId)) sealBody(state, body, extraction);
        }

        if (Double.isFinite(dt) && dt > 0) {
            tickCooldowns(extraction, dt);
            Iterator<Charge> it = extraction.charges.iterator();
            while (it.hasNext()) {
                Charge charge = it.next();
                Base source = state.bases.get(charge.extractorBaseId);
                if (!validExtractorAnchor(state, source, charge.bodyId, charge.playerId)) {
                    it.remove();
                    continue;
                }
                charge.elapsed += dt;
                if (charge.elapsed + 0.000001 >= CHARGE_SECONDS) {
                    extraction.releasedBodyIds.add(charge.bodyId);
                    extraction.sealedBodyIds.remove(charge.bodyId);
                    activateBody(state, charge.bodyId);
                    extraction.impactBodyId = charge.bodyId;
                    extraction.impactAge = 0.0;
                    World world = WORLDS.get(state);
                    if (world != null) SystemAudio.play(world, state.id, SoundCue.EXTRACTION_FRACTURE_IMPACT);
                    it.remove();
                }
            }
            if (extraction.impactAge < IMPACT_EFFECT_SECONDS) extraction.impactAge += dt;
        }

        for (String bodyId : new ArrayList<>(extraction.releasedBodyIds)) {
            finishFieldIfExhausted(state, bodyId);
        }
    }

    private static void ensureFractureFields(WorldSystemState state, ExtractionState extraction) {
        if (state == null || extraction == null) return;
        Set<Integer> usedIds = new HashSet<>();
        for (ResourceNode node : state.resources) if (node != null) usedIds.add(node.id);

        for (CelestialBodyState body : CelestialGameplaySystem.bodyStates(state)) {
            String bodyId = body.profile.bodyId();
            int expected = body.profile.deposits().size() * FRAGMENTS_PER_DEPOSIT;
            if (expected <= 0) continue;
            if (extraction.fragmentedBodyIds.contains(bodyId) && body.resourceNodeIds.size() >= expected) continue;

            CelestialSystem.BodyView view = state.celestials.bodyView(bodyId);
            if (view == null) continue;
            boolean bodyReleased = extraction.releasedBodyIds.contains(bodyId);
            List<Integer> rebuiltIds = new ArrayList<>(expected);
            boolean complete = true;

            for (int materialSlot = 0; materialSlot < body.profile.deposits().size(); materialSlot++) {
                Material material = body.profile.deposits().get(materialSlot);
                List<ResourceNode> nodes = anchoredNodes(state, bodyId, material);
                if (nodes.isEmpty()) {
                    complete = false;
                    continue;
                }

                ResourceNode seed = preferredSeed(state, body, material, nodes);
                if (seed == null) seed = nodes.get(0);
                nodes.remove(seed);
                nodes.add(0, seed);

                if (nodes.size() > FRAGMENTS_PER_DEPOSIT) {
                    List<ResourceNode> extras = new ArrayList<>(nodes.subList(FRAGMENTS_PER_DEPOSIT, nodes.size()));
                    nodes = new ArrayList<>(nodes.subList(0, FRAGMENTS_PER_DEPOSIT));
                    state.resources.removeAll(extras);
                    for (ResourceNode extra : extras) {
                        usedIds.remove(extra.id);
                        extraction.nodeIndex.remove(extra.id);
                        RESOURCE_STATES.remove(extra);
                    }
                }

                double totalVolume = DEPOSITS.baseAmount() + materialSlot * DEPOSITS.amountPerMaterialSlot();
                double shardAmount = totalVolume / FRAGMENTS_PER_DEPOSIT;
                double shardRadius = DEPOSITS.nodeRadius() / Math.cbrt(FRAGMENTS_PER_DEPOSIT);
                boolean legacyLayout = nodes.size() != FRAGMENTS_PER_DEPOSIT;
                if (!legacyLayout) {
                    for (ResourceNode node : nodes) {
                        if (Math.abs(node.maxAmount - shardAmount) > 0.001) {
                            legacyLayout = true;
                            break;
                        }
                    }
                }

                double remainingVolume = totalVolume;
                if (legacyLayout && bodyReleased) {
                    remainingVolume = 0.0;
                    for (ResourceNode node : nodes) {
                        remainingVolume += Math.max(0.0, Math.min(node.maxAmount, node.amount));
                    }
                    remainingVolume = Math.min(totalVolume, remainingVolume);
                }

                while (nodes.size() < FRAGMENTS_PER_DEPOSIT) {
                    int fragment = nodes.size();
                    int id = fragmentResourceId(state, bodyId, material, materialSlot, fragment, usedIds);
                    ResourceNode node = new ResourceNode(
                            id,
                            material.label + " fracture shard " + (fragment + 1),
                            seed.kind,
                            material,
                            view.x(),
                            view.y(),
                            shardAmount,
                            seed.harvestRate,
                            shardRadius);
                    node.celestialAnchorBodyId = bodyId;
                    state.resources.add(node);
                    nodes.add(node);
                    extraction.nodeIndex.put(node.id, node);
                    RESOURCE_STATES.put(node, state);
                }

                double migratedAmount = remainingVolume / FRAGMENTS_PER_DEPOSIT;
                double baseOrbit = Math.max(view.radius() + DEPOSITS.orbitPadding(),
                        view.radius() * DEPOSITS.orbitRadiusScale())
                        + materialSlot * EXTRACTION.fractureOrbitSlotSpacing();
                double baseAngle = normalizedAngle(stableHash(state.id, bodyId, material.name(), "fracture"));
                double baseSpeed = DEPOSITS.orbitBaseSpeed() + DEPOSITS.orbitSpeedPerSlot() * (materialSlot + 1);

                for (int fragment = 0; fragment < FRAGMENTS_PER_DEPOSIT; fragment++) {
                    ResourceNode node = nodes.get(fragment);
                    node.maxAmount = shardAmount;
                    node.radius = shardRadius;
                    node.celestialAnchorBodyId = bodyId;
                    if (legacyLayout) {
                        node.amount = bodyReleased ? Math.min(shardAmount, migratedAmount) : shardAmount;
                        node.active = bodyReleased && node.amount > 0.05;
                        node.respawnTimer = 0;
                    }

                    double ringOffset = fragment % 2 == 0
                            ? EXTRACTION.evenRingOffset() : EXTRACTION.oddRingOffset();
                    double radialScatter = (fragment / 2) * EXTRACTION.radialScatterPerPair();
                    double orbitRadius = Math.max(view.radius() + EXTRACTION.fractureMinimumOrbitPadding(),
                            baseOrbit + ringOffset + radialScatter);
                    double jitter = EXTRACTION.angleJitterRadians();
                    double angle = baseAngle + TAU * fragment / FRAGMENTS_PER_DEPOSIT
                            + (fragment % 2 == 0 ? -jitter : jitter);
                    double speed = baseSpeed * (EXTRACTION.fragmentSpeedBaseScale()
                            + fragment * EXTRACTION.fragmentSpeedStepScale());
                    node.orbit(view.x(), view.y(), orbitRadius, angle, speed);
                    extraction.nodeIndex.put(node.id, node);
                    RESOURCE_STATES.put(node, state);
                    rebuiltIds.add(node.id);
                }
            }

            if (!complete || rebuiltIds.size() != expected) continue;
            body.resourceNodeIds.clear();
            body.resourceNodeIds.addAll(rebuiltIds);
            body.depositsSeeded = true;
            extraction.fragmentedBodyIds.add(bodyId);
        }
    }

    private static List<ResourceNode> anchoredNodes(WorldSystemState state, String bodyId, Material material) {
        List<ResourceNode> out = new ArrayList<>();
        for (ResourceNode node : state.resources) {
            if (node != null && node.material == material && bodyId.equals(node.celestialAnchorBodyId)) out.add(node);
        }
        return out;
    }

    private static ResourceNode preferredSeed(WorldSystemState state, CelestialBodyState body,
                                              Material material, List<ResourceNode> nodes) {
        for (int id : body.resourceNodeIds) {
            ResourceNode node = resourceById(state, id);
            if (node != null && node.material == material && nodes.contains(node)) return node;
        }
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    private static int fragmentResourceId(WorldSystemState state, String bodyId, Material material,
                                          int materialSlot, int fragment, Set<Integer> usedIds) {
        long hash = stableHash(state.id, bodyId, material.name(), Integer.toString(materialSlot),
                "fragment-" + fragment);
        int candidate = CELESTIAL_FRAGMENT_ID_BASE
                + (int)Math.floorMod(hash, CELESTIAL_FRAGMENT_ID_SPAN);
        while (usedIds.contains(candidate)) {
            candidate++;
            if (candidate <= 0 || candidate == Integer.MAX_VALUE) candidate = CELESTIAL_FRAGMENT_ID_BASE;
        }
        usedIds.add(candidate);
        return candidate;
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

    private static double normalizedAngle(long hash) {
        long positive = hash & Long.MAX_VALUE;
        return (positive / (double)Long.MAX_VALUE) * TAU;
    }

    private static void tickCooldowns(ExtractionState extraction, double dt) {
        tickCooldownMap(extraction.cooldownSecondsByBody, dt);
        tickCooldownMap(extraction.cooldownSecondsByExtractor, dt);
    }

    private static void tickCooldownMap(Map<String,Double> cooldowns, double dt) {
        Iterator<Map.Entry<String,Double>> it = cooldowns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String,Double> entry = it.next();
            double remaining = entry.getValue() - dt;
            if (remaining <= 0.001) it.remove();
            else entry.setValue(remaining);
        }
    }

    private static void advanceReplicaVisuals(ExtractionState extraction, double dt) {
        if (!Double.isFinite(dt) || dt <= 0) return;
        tickCooldowns(extraction, dt);
        for (Charge charge : extraction.charges) charge.elapsed = Math.min(CHARGE_SECONDS, charge.elapsed + dt);
        if (extraction.impactAge < IMPACT_EFFECT_SECONDS) {
            extraction.impactAge = Math.min(IMPACT_EFFECT_SECONDS, extraction.impactAge + dt);
        }
    }

    private static void bindBodyNodes(WorldSystemState state, CelestialBodyState body) {
        if (state == null || body == null) return;
        for (int id : body.resourceNodeIds) {
            ResourceNode node = resourceById(state, id);
            if (node != null) RESOURCE_STATES.put(node, state);
        }
    }

    private static void sealBody(WorldSystemState state, CelestialBodyState body, ExtractionState extraction) {
        if (state == null || body == null || extraction == null) return;
        String bodyId = body.profile.bodyId();
        if (extraction.sealedBodyIds.contains(bodyId)) return;
        boolean found = false;
        for (int id : body.resourceNodeIds) {
            ResourceNode node = resourceById(state, id);
            if (node == null) continue;
            found = true;
            node.active = false;
            node.amount = node.maxAmount;
            node.respawnTimer = 0;
            RESOURCE_STATES.put(node, state);
        }
        if (found) extraction.sealedBodyIds.add(bodyId);
    }

    private static void activateBody(WorldSystemState state, String bodyId) {
        CelestialBodyState body = CelestialGameplaySystem.bodyState(state, bodyId);
        if (body == null) return;
        for (int id : body.resourceNodeIds) {
            ResourceNode node = resourceById(state, id);
            if (node == null) continue;
            node.amount = node.maxAmount;
            node.active = true;
            node.respawnTimer = 0;
            RESOURCE_STATES.put(node, state);
        }
    }

    /**
     * Recover if the final mining/depletion callback was missed or arrived against stale tactical
     * state. Authoritative readiness/command checks must never leave an empty released field stuck
     * forever just because one lifecycle notification was lost. Network replicas remain read-only.
     */
    private static void reconcileExhaustedReleasedField(WorldSystemState state, String bodyId) {
        if (state == null || bodyId == null || bodyId.isBlank()) return;
        ExtractionState extraction = data(state);
        if (extraction.networkReplica || !extraction.releasedBodyIds.contains(bodyId)) return;
        finishFieldIfExhausted(state, bodyId);
    }

    private static boolean finishFieldIfExhausted(WorldSystemState state, String bodyId) {
        if (state == null || bodyId == null || bodyId.isBlank()) return false;
        ExtractionState extraction = data(state);
        if (!extraction.releasedBodyIds.contains(bodyId)) return false;
        CelestialBodyState body = CelestialGameplaySystem.bodyState(state, bodyId);
        if (body == null || body.resourceNodeIds.isEmpty()) return false;

        boolean found = false;
        for (int id : body.resourceNodeIds) {
            ResourceNode node = resourceById(state, id);
            if (node == null) continue;
            found = true;
            RESOURCE_STATES.put(node, state);
            if (node.active && node.amount > 0.05) return false;
        }
        if (!found) return false;

        extraction.releasedBodyIds.remove(bodyId);
        extraction.sealedBodyIds.remove(bodyId);
        sealBody(state, body, extraction);
        clearFieldTargets(state, body);
        extraction.cooldownSecondsByBody.put(bodyId, REFIRE_COOLDOWN_SECONDS);
        return true;
    }

    private static void clearFieldTargets(WorldSystemState state, CelestialBodyState body) {
        if (state == null || body == null) return;
        for (Unit unit : state.units.values()) {
            if (unit == null || !body.resourceNodeIds.contains(unit.automationResourceId)) continue;
            unit.automationResourceId = -1;
            if (unit.task == UnitTask.AUTO_HARVEST) unit.task = UnitTask.IDLE;
        }
        World world = WORLDS.get(state);
        if (world == null) world = PlayerRegistry.activeWorld();
        if (world != null && state.id.equals(world.activeSystemId())
                && body.resourceNodeIds.contains(world.selectedResourceId)) world.selectedResourceId = -1;
    }

    static String networkState(CelestialSystem celestials) {
        WorldSystemState state = STATES.get(celestials);
        if (state == null) return "";
        ExtractionState extraction = data(state);
        StringBuilder out = new StringBuilder("v1");
        int rows = 0;

        Set<String> bodies = new LinkedHashSet<>(extraction.releasedBodyIds);
        bodies.addAll(extraction.cooldownSecondsByBody.keySet());
        for (String bodyId : bodies) {
            if (rows++ >= MAX_WIRE_ROWS || bodyId == null || bodyId.isBlank()) break;
            out.append(";B,").append(wireToken(bodyId)).append(',')
                    .append(extraction.releasedBodyIds.contains(bodyId) ? '1' : '0').append(',')
                    .append(Calc.round(Math.max(0, extraction.cooldownSecondsByBody.getOrDefault(bodyId, 0.0))));
        }
        for (Map.Entry<String,Double> entry : extraction.cooldownSecondsByExtractor.entrySet()) {
            if (rows++ >= MAX_WIRE_ROWS) break;
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() <= 0) continue;
            out.append(";E,").append(wireToken(entry.getKey())).append(',')
                    .append(Calc.round(Math.min(EXTRACTOR_FIRE_COOLDOWN_SECONDS, entry.getValue())));
        }
        for (Charge charge : extraction.charges) {
            if (rows++ >= MAX_WIRE_ROWS) break;
            out.append(";C,").append(wireToken(charge.bodyId)).append(',')
                    .append(wireToken(charge.extractorBaseId)).append(',')
                    .append(wireToken(charge.playerId)).append(',')
                    .append(Calc.round(Math.max(0, Math.min(CHARGE_SECONDS, charge.elapsed))));
        }
        if (rows < MAX_WIRE_ROWS && extraction.impactBodyId != null && !extraction.impactBodyId.isBlank()
                && extraction.impactAge < IMPACT_EFFECT_SECONDS) {
            out.append(";I,").append(wireToken(extraction.impactBodyId)).append(',')
                    .append(Calc.round(Math.max(0, extraction.impactAge)));
        }
        return out.length() <= MAX_WIRE_CHARS ? out.toString() : "v1";
    }

    static void applyNetworkState(CelestialSystem celestials, String wire) {
        WorldSystemState state = STATES.get(celestials);
        if (state == null || wire == null || wire.isBlank() || wire.length() > MAX_WIRE_CHARS) return;
        String[] rows = wire.split(";", -1);
        if (rows.length == 0 || !"v1".equals(rows[0]) || rows.length > MAX_WIRE_ROWS + 1) return;

        ExtractionState extraction = data(state);
        extraction.networkReplica = true;
        extraction.releasedBodyIds.clear();
        extraction.sealedBodyIds.clear();
        extraction.cooldownSecondsByBody.clear();
        extraction.cooldownSecondsByExtractor.clear();
        extraction.charges.clear();
        extraction.impactBodyId = "";
        extraction.impactAge = IMPACT_EFFECT_SECONDS;

        for (int i = 1; i < rows.length; i++) {
            if (rows[i].isBlank()) continue;
            String[] parts = rows[i].split(",", -1);
            try {
                if (parts.length == 4 && "B".equals(parts[0])) {
                    String bodyId = wireUntoken(parts[1]);
                    if (bodyId.isBlank() || state.celestials.bodyView(bodyId) == null) continue;
                    if ("1".equals(parts[2])) extraction.releasedBodyIds.add(bodyId);
                    double seconds = wireDouble(parts[3], REFIRE_COOLDOWN_SECONDS);
                    if (seconds > 0) extraction.cooldownSecondsByBody.put(bodyId, seconds);
                } else if (parts.length == 3 && "E".equals(parts[0])) {
                    String baseId = wireUntoken(parts[1]);
                    double seconds = wireDouble(parts[2], EXTRACTOR_FIRE_COOLDOWN_SECONDS);
                    if (!baseId.isBlank() && seconds > 0) extraction.cooldownSecondsByExtractor.put(baseId, seconds);
                } else if (parts.length == 5 && "C".equals(parts[0])) {
                    String bodyId = wireUntoken(parts[1]);
                    String baseId = wireUntoken(parts[2]);
                    String playerId = wireUntoken(parts[3]);
                    if (bodyId.isBlank() || baseId.isBlank() || state.celestials.bodyView(bodyId) == null) continue;
                    Charge charge = new Charge(bodyId, baseId, playerId);
                    charge.elapsed = wireDouble(parts[4], CHARGE_SECONDS);
                    extraction.charges.add(charge);
                } else if (parts.length == 3 && "I".equals(parts[0])) {
                    String bodyId = wireUntoken(parts[1]);
                    if (bodyId.isBlank() || state.celestials.bodyView(bodyId) == null) continue;
                    extraction.impactBodyId = bodyId;
                    extraction.impactAge = wireDouble(parts[2], IMPACT_EFFECT_SECONDS);
                }
            } catch (RuntimeException ignored) { }
        }
    }

    private static String wireToken(String value) {
        String clean = value == null ? "" : value;
        if (clean.length() > 128) clean = clean.substring(0, 128);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(clean.getBytes(StandardCharsets.UTF_8));
    }

    private static String wireUntoken(String value) {
        if (value == null || value.isBlank() || value.length() > 256) return "";
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            return decoded.length() <= 128 ? decoded : decoded.substring(0, 128);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private static double wireDouble(String value, double max) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? Math.max(0, Math.min(max, parsed)) : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    static void draw(CelestialSystem celestials, Graphics2D g) {
        WorldSystemState state = STATES.get(celestials);
        if (state == null || g == null) return;
        ExtractionState extraction = data(state);
        if (!hasRenderableEffects(extraction)) return;

        Graphics2D c = (Graphics2D) g.create();
        try {
            drawEffects(celestials, state, extraction, c);
        } finally {
            c.dispose();
        }
    }

    /**
     * Keep the overwhelmingly common idle render path tiny. The detailed fracture renderer is
     * intentionally isolated so background-only frames do not allocate a child graphics context
     * or execute/JIT the much larger active-effect method.
     */
    private static boolean hasRenderableEffects(ExtractionState extraction) {
        if (extraction == null) return false;
        if (!extraction.charges.isEmpty()) return true;
        return extraction.impactBodyId != null && !extraction.impactBodyId.isBlank()
                && extraction.impactAge < IMPACT_EFFECT_SECONDS;
    }

    private static void drawEffects(CelestialSystem celestials, WorldSystemState state,
                                    ExtractionState extraction, Graphics2D c) {

            for (Charge charge : extraction.charges) {
                Base source = state.bases.get(charge.extractorBaseId);
                CelestialSystem.BodyView target = celestials.bodyView(charge.bodyId);
                if (source == null || target == null) continue;
                double t = Math.max(0.0, Math.min(1.0, charge.elapsed / CHARGE_SECONDS));
                double eased = smooth(t);
                double px = source.x + (target.x() - source.x) * eased;
                double py = source.y + (target.y() - source.y) * eased;
                double pulse = 0.5 + 0.5 * Math.sin(charge.elapsed * 11.0);

                if (t < 0.18) {
                    double launchT = t / 0.18;
                    double ring = 10 + launchT * 52;
                    int alpha = (int)Math.round(210 * (1.0 - launchT));
                    c.setStroke(new BasicStroke(2.6f));
                    c.setColor(new Color(125, 226, 255, Math.max(0, alpha)));
                    c.drawOval((int)Math.round(source.x - ring), (int)Math.round(source.y - ring),
                            (int)Math.round(ring * 2), (int)Math.round(ring * 2));
                    c.setColor(new Color(255, 255, 255, Math.max(0, (int)(235 * (1.0 - launchT)))));
                    double core = 18 * (1.0 - launchT) + 4;
                    c.fillOval((int)Math.round(source.x - core), (int)Math.round(source.y - core),
                            (int)Math.round(core * 2), (int)Math.round(core * 2));
                    long seed = stableHash(state.id, charge.bodyId, charge.extractorBaseId, "launch");
                    for (int spark = 0; spark < 10; spark++) {
                        double angle = normalizedAngle(seed + spark * 0x9E3779B97F4A7C15L);
                        double inner = 12 + launchT * 10;
                        double outer = inner + 18 + (spark % 4) * 5;
                        c.setColor(new Color(177, 238, 255, Math.max(0, (int)(180 * (1.0 - launchT)))));
                        c.drawLine((int)Math.round(source.x + Math.cos(angle) * inner),
                                (int)Math.round(source.y + Math.sin(angle) * inner),
                                (int)Math.round(source.x + Math.cos(angle) * outer),
                                (int)Math.round(source.y + Math.sin(angle) * outer));
                    }
                }

                double previousX = px;
                double previousY = py;
                for (int segment = 1; segment <= 14; segment++) {
                    double st = Math.max(0, t - segment * 0.022);
                    double se = smooth(st);
                    double sx = source.x + (target.x() - source.x) * se;
                    double sy = source.y + (target.y() - source.y) * se;
                    int alpha = Math.max(12, 150 - segment * 9);
                    c.setStroke(new BasicStroke(Math.max(1.0f, 5.2f - segment * 0.28f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    c.setColor(new Color(79, 205, 255, alpha));
                    c.drawLine((int)Math.round(previousX), (int)Math.round(previousY),
                            (int)Math.round(sx), (int)Math.round(sy));
                    previousX = sx;
                    previousY = sy;
                }

                double halo = 13 + pulse * 7;
                c.setColor(new Color(72, 198, 255, 70));
                c.fillOval((int)Math.round(px - halo), (int)Math.round(py - halo),
                        (int)Math.round(halo * 2), (int)Math.round(halo * 2));
                c.setColor(new Color(113, 225, 255, 220));
                c.fillOval((int)Math.round(px - 8), (int)Math.round(py - 8), 16, 16);
                c.setColor(new Color(255, 255, 255, 250));
                c.fillOval((int)Math.round(px - 3.5), (int)Math.round(py - 3.5), 7, 7);

                long flightSeed = stableHash(state.id, charge.bodyId, "flight");
                for (int spark = 0; spark < 5; spark++) {
                    double angle = normalizedAngle(flightSeed + spark * 0xD1B54A32D192ED03L + (long)(t * 17));
                    double radius = 10 + spark * 3 + pulse * 4;
                    double sx = px + Math.cos(angle) * radius;
                    double sy = py + Math.sin(angle) * radius;
                    c.setColor(new Color(190, 242, 255, 125 - spark * 16));
                    c.fillOval((int)Math.round(sx - 1.5), (int)Math.round(sy - 1.5), 3, 3);
                }

                double stressStart = EXTRACTION.stressStartProgress();
                if (t > stressStart) {
                    float fracture = (float)Math.min(1.0, (t - stressStart) / Math.max(0.0001, 1.0 - stressStart));
                    int rays = Math.max(EXTRACTION.stressRayCount(), 12);
                    long seed = stableHash(state.id, charge.bodyId, "stress");
                    for (int ray = 0; ray < rays; ray++) {
                        double angle = normalizedAngle(seed + ray * 0x9E3779B97F4A7C15L);
                        double inner = target.radius() * (0.72 + (ray % 3) * 0.04);
                        double outer = target.radius() * (1.02 + 0.44 * fracture + (ray % 4) * 0.035);
                        c.setStroke(new BasicStroke(1.0f + fracture * 1.8f));
                        c.setColor(new Color(207, 244, 255, Math.round(65 + 175 * fracture)));
                        c.drawLine((int)Math.round(target.x() + Math.cos(angle) * inner),
                                (int)Math.round(target.y() + Math.sin(angle) * inner),
                                (int)Math.round(target.x() + Math.cos(angle + 0.05 * Math.sin(ray)) * outer),
                                (int)Math.round(target.y() + Math.sin(angle + 0.05 * Math.sin(ray)) * outer));
                    }
                    for (int ringIndex = 0; ringIndex < 3; ringIndex++) {
                        double radius = target.radius() * (1.55 - fracture * (0.38 + ringIndex * 0.12));
                        int alpha = Math.min(220, Math.round(55 + fracture * 125 - ringIndex * 18));
                        c.setStroke(new BasicStroke(1.5f + fracture));
                        c.setColor(new Color(112, 222, 255, Math.max(20, alpha)));
                        c.drawOval((int)Math.round(target.x() - radius), (int)Math.round(target.y() - radius),
                                (int)Math.round(radius * 2), (int)Math.round(radius * 2));
                    }
                }
            }

            if (extraction.impactBodyId != null && !extraction.impactBodyId.isBlank()
                    && extraction.impactAge < IMPACT_EFFECT_SECONDS) {
                CelestialSystem.BodyView target = celestials.bodyView(extraction.impactBodyId);
                if (target != null) {
                    float age = (float)Math.max(0.0, Math.min(1.0, extraction.impactAge / IMPACT_EFFECT_SECONDS));
                    float alpha = 1.0f - age;
                    float radius = (float)(target.radius() * (1.0 + age * 2.7));
                    RadialGradientPaint flash = new RadialGradientPaint(
                            new Point2D.Double(target.x(), target.y()), Math.max(10f, radius),
                            new float[]{0f, 0.16f, 0.48f, 1f},
                            new Color[]{new Color(255, 255, 255, Math.round(250 * alpha)),
                                    new Color(153, 235, 255, Math.round(205 * alpha)),
                                    new Color(68, 177, 236, Math.round(105 * alpha)),
                                    new Color(50, 150, 230, 0)});
                    c.setPaint(flash);
                    c.fillOval((int)Math.round(target.x() - radius), (int)Math.round(target.y() - radius),
                            Math.round(radius * 2), Math.round(radius * 2));

                    for (int ringIndex = 0; ringIndex < 3; ringIndex++) {
                        double ringAge = Math.max(0, Math.min(1, age * (1.1 + ringIndex * 0.22) - ringIndex * 0.08));
                        double rr = target.radius() * (1.0 + ringAge * (2.0 + ringIndex * 0.55));
                        int ringAlpha = Math.max(0, (int)(205 * (1.0 - ringAge) * alpha));
                        c.setColor(new Color(185, 239, 255, ringAlpha));
                        c.setStroke(new BasicStroke(Math.max(1.0f, 3.4f - ringIndex * 0.7f)));
                        c.drawOval((int)Math.round(target.x() - rr), (int)Math.round(target.y() - rr),
                                (int)Math.round(rr * 2), (int)Math.round(rr * 2));
                    }

                    long seed = stableHash(state.id, extraction.impactBodyId, "impact");
                    for (int ray = 0; ray < 20; ray++) {
                        double angle = normalizedAngle(seed + ray * 0x9E3779B97F4A7C15L);
                        double inner = target.radius() * (0.9 + age * 0.5);
                        double outer = target.radius() * (1.35 + age * (2.4 + (ray % 5) * 0.12));
                        int rayAlpha = Math.max(0, (int)(220 * alpha));
                        c.setColor(new Color(190 + ray % 3 * 18, 235, 255, rayAlpha));
                        c.setStroke(new BasicStroke(ray % 3 == 0 ? 2.5f : 1.3f));
                        c.drawLine((int)Math.round(target.x() + Math.cos(angle) * inner),
                                (int)Math.round(target.y() + Math.sin(angle) * inner),
                                (int)Math.round(target.x() + Math.cos(angle) * outer),
                                (int)Math.round(target.y() + Math.sin(angle) * outer));
                    }

                    if (age < 0.18f) {
                        double white = target.radius() * (1.05 + age * 3.2);
                        c.setColor(new Color(255, 255, 255, Math.max(0, (int)(245 * (1.0 - age / 0.18f)))));
                        c.fillOval((int)Math.round(target.x() - white), (int)Math.round(target.y() - white),
                                (int)Math.round(white * 2), (int)Math.round(white * 2));
                    }
                }
            }

    }

    private static double smooth(double value) {
        double t = Math.max(0, Math.min(1, value));
        return t * t * (3.0 - 2.0 * t);
    }

    static Map<String,Object> captureState(WorldSystemState state) {
        if (state == null) return Map.of();
        ExtractionState extraction = data(state);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("releasedBodyIds", new ArrayList<>(extraction.releasedBodyIds));
        if (!extraction.cooldownSecondsByBody.isEmpty()) {
            Map<String,Object> cooldowns = new LinkedHashMap<>();
            extraction.cooldownSecondsByBody.forEach((bodyId, seconds) -> {
                if (bodyId != null && !bodyId.isBlank() && seconds != null && seconds > 0) cooldowns.put(bodyId, seconds);
            });
            if (!cooldowns.isEmpty()) out.put("cooldowns", cooldowns);
        }
        if (!extraction.cooldownSecondsByExtractor.isEmpty()) {
            Map<String,Object> cooldowns = new LinkedHashMap<>();
            extraction.cooldownSecondsByExtractor.forEach((baseId, seconds) -> {
                if (baseId != null && !baseId.isBlank() && seconds != null && seconds > 0) cooldowns.put(baseId, seconds);
            });
            if (!cooldowns.isEmpty()) out.put("extractorCooldowns", cooldowns);
        }
        if (!extraction.charges.isEmpty()) {
            List<Object> charges = new ArrayList<>();
            for (Charge charge : extraction.charges) {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("bodyId", charge.bodyId);
                row.put("extractorBaseId", charge.extractorBaseId);
                row.put("playerId", charge.playerId);
                row.put("elapsed", charge.elapsed);
                charges.add(row);
            }
            out.put("charges", charges);
        }
        return out;
    }

    static void restoreState(WorldSystemState state, Object raw) {
        if (state == null) return;
        ExtractionState extraction = data(state);
        extraction.releasedBodyIds.clear();
        extraction.sealedBodyIds.clear();
        extraction.fragmentedBodyIds.clear();
        extraction.cooldownSecondsByBody.clear();
        extraction.cooldownSecondsByExtractor.clear();
        extraction.nodeIndex.clear();
        extraction.charges.clear();
        extraction.impactBodyId = "";
        extraction.impactAge = IMPACT_EFFECT_SECONDS;
        extraction.networkReplica = false;
        Map<String,Object> saved = ServerSaveStore.object(raw);
        for (Object value : ServerSaveStore.list(saved.get("releasedBodyIds"))) {
            String id = ServerSaveStore.asString(value, "");
            if (!id.isBlank() && state.celestials.bodyView(id) != null) extraction.releasedBodyIds.add(id);
        }
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(saved.get("cooldowns")).entrySet()) {
            String bodyId = entry.getKey();
            double seconds = ServerSaveStore.asDouble(entry.getValue(), 0);
            if (bodyId != null && !bodyId.isBlank() && seconds > 0 && state.celestials.bodyView(bodyId) != null) {
                extraction.cooldownSecondsByBody.put(bodyId, Math.min(REFIRE_COOLDOWN_SECONDS, seconds));
            }
        }
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(saved.get("extractorCooldowns")).entrySet()) {
            String baseId = entry.getKey();
            double seconds = ServerSaveStore.asDouble(entry.getValue(), 0);
            if (baseId != null && !baseId.isBlank() && seconds > 0) {
                extraction.cooldownSecondsByExtractor.put(baseId, Math.min(EXTRACTOR_FIRE_COOLDOWN_SECONDS, seconds));
            }
        }
        for (Object rawCharge : ServerSaveStore.list(saved.get("charges"))) {
            Map<String,Object> row = ServerSaveStore.object(rawCharge);
            String bodyId = ServerSaveStore.asString(row.get("bodyId"), "");
            String baseId = ServerSaveStore.asString(row.get("extractorBaseId"), "");
            String playerId = ServerSaveStore.asString(row.get("playerId"), "");
            double elapsed = ServerSaveStore.asDouble(row.get("elapsed"), 0);
            if (bodyId.isBlank() || baseId.isBlank() || state.celestials.bodyView(bodyId) == null) continue;
            Charge charge = new Charge(bodyId, baseId, playerId);
            charge.elapsed = Math.max(0, Math.min(CHARGE_SECONDS, elapsed));
            extraction.charges.add(charge);
        }
    }

    private static boolean normalizeExtractorAnchors(WorldSystemState state) {
        boolean changed = false;
        for (Base base : state.bases.values()) {
            if (base == null || !EXTRACTOR_STATION_ID.equals(base.typeId)
                    || base.celestialAnchorBodyId == null || base.celestialAnchorBodyId.isBlank()) continue;
            CelestialSystem.BodyView anchored = state.celestials.bodyView(base.celestialAnchorBodyId);
            if (anchored == null || !anchored.moon()) continue;
            String masterId = CelestialMoonInheritance.masterPlanetId(state, anchored.id());
            CelestialSystem.BodyView master = state.celestials.bodyView(masterId);
            if (master == null) continue;
            double distance = Math.hypot(base.x - master.x(), base.y - master.y());
            base.celestialAnchorBodyId = master.id();
            base.celestialOrbitRadius = Math.max(master.radius() + base.interactionRadius() + ANCHORING.orbitPadding(), distance);
            base.celestialOrbitAngle = Math.atan2(base.y - master.y(), base.x - master.x());
            base.celestialOrbitSpeed = ANCHORING.orbitSpeedBase()
                    * Math.sqrt(ANCHORING.orbitSpeedReferenceRadius()
                    / Math.max(ANCHORING.orbitSpeedMinimumRadius(), base.celestialOrbitRadius));
            base.x = master.x() + Math.cos(base.celestialOrbitAngle) * base.celestialOrbitRadius;
            base.y = master.y() + Math.sin(base.celestialOrbitAngle) * base.celestialOrbitRadius;
            changed = true;
        }
        return changed;
    }

    private static Base extractorFor(WorldSystemState state, String bodyId, String playerId) {
        if (state == null || bodyId == null || bodyId.isBlank()) return null;
        String masterId = CelestialMoonInheritance.masterPlanetId(state, bodyId);
        if (masterId.isBlank()) return null;
        CelestialBodyState masterState = CelestialGameplaySystem.bodyState(state, masterId);
        if (masterState == null || masterState.contested || masterState.claimantId == null
                || masterState.claimantId.isBlank() || !friendly(masterState.claimantId, playerId)) return null;
        for (Base base : state.bases.values()) {
            if (validExtractorAnchor(state, base, bodyId, playerId)) return base;
        }
        return null;
    }

    private static boolean validExtractorAnchor(WorldSystemState state, Base base, String bodyId, String playerId) {
        if (state == null || base == null || base.hp <= 0 || !EXTRACTOR_STATION_ID.equals(base.typeId)) return false;
        String masterId = CelestialMoonInheritance.masterPlanetId(state, bodyId);
        if (masterId.isBlank() || !masterId.equals(base.celestialAnchorBodyId)) return false;
        CelestialBodyState master = CelestialGameplaySystem.bodyState(state, masterId);
        if (master == null || master.contested || master.claimantId == null || master.claimantId.isBlank()) return false;
        return friendly(master.claimantId, base.playerId) && friendly(base.playerId, playerId);
    }

    private static WorldSystemState findState(ResourceNode node) {
        synchronized (STATES) {
            for (WorldSystemState candidate : STATES.values()) {
                if (candidate != null && candidate.resources.contains(node)) {
                    RESOURCE_STATES.put(node, candidate);
                    data(candidate).nodeIndex.put(node.id, node);
                    return candidate;
                }
            }
        }
        return null;
    }

    private static ResourceNode resourceById(WorldSystemState state, int id) {
        if (state == null) return null;
        ExtractionState extraction = DATA.get(state);
        if (extraction != null) {
            ResourceNode cached = extraction.nodeIndex.get(id);
            if (cached != null) return cached;
        }
        for (ResourceNode node : state.resources) {
            if (node != null && node.id == id) {
                if (extraction != null) extraction.nodeIndex.put(id, node);
                return node;
            }
        }
        return null;
    }

    private static boolean isCelestial(ResourceNode node) {
        return node != null && node.celestialAnchorBodyId != null && !node.celestialAnchorBodyId.isBlank();
    }

    private static boolean friendly(String first, String second) {
        if (first == null || first.isBlank() || second == null || second.isBlank()) return false;
        if (first.equals(second)) return true;
        World world = PlayerRegistry.activeWorld();
        return world != null && DiplomacySystem.allied(world, first, second);
    }

    private static ExtractionState data(WorldSystemState state) {
        return DATA.computeIfAbsent(state, ignored -> new ExtractionState());
    }

    record FireResult(boolean fired, String message) { }

    private static final class ExtractionState {
        final Set<String> releasedBodyIds = new LinkedHashSet<>();
        final Set<String> sealedBodyIds = new LinkedHashSet<>();
        final Set<String> fragmentedBodyIds = new LinkedHashSet<>();
        final Map<String,Double> cooldownSecondsByBody = new LinkedHashMap<>();
        final Map<String,Double> cooldownSecondsByExtractor = new LinkedHashMap<>();
        final Map<Integer,ResourceNode> nodeIndex = new LinkedHashMap<>();
        final List<Charge> charges = new ArrayList<>();
        String impactBodyId = "";
        double impactAge = IMPACT_EFFECT_SECONDS;
        boolean networkReplica;
    }

    private static final class Charge {
        final String bodyId;
        final String extractorBaseId;
        final String playerId;
        double elapsed;

        Charge(String bodyId, String extractorBaseId, String playerId) {
            this.bodyId = bodyId;
            this.extractorBaseId = extractorBaseId;
            this.playerId = playerId == null ? "" : playerId;
        }
    }
}
