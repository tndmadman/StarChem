package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Gates physical planet/moon deposits behind a dedicated Planetary Extractor and a fracture charge.
 * Survey intel may reveal what a body contains, but ResourceNodes do not physically exist until a
 * charge fired from an extractor anchored to the master planet reaches that specific body.
 */
final class CelestialExtractionSystem {
    static final String EXTRACTOR_STATION_ID = "extractor";
    static final double CHARGE_SECONDS = 1.4;

    private static final Map<CelestialSystem, WorldSystemState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<WorldSystemState, ExtractionState> DATA =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CelestialExtractionSystem() { }

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

    static boolean extractorReady(WorldSystemState state, String bodyId, String playerId) {
        return extractorFor(state, bodyId, playerId) != null;
    }

    static FireResult fireCharge(WorldSystemState state, String bodyId, String playerId) {
        if (state == null || state.celestials == null || bodyId == null || bodyId.isBlank()) {
            return new FireResult(false, "No celestial body selected.");
        }
        CelestialSystem.BodyView target = state.celestials.bodyView(bodyId);
        if (target == null || target.visualClass() == CelestialVisualClass.STAR) {
            return new FireResult(false, "Extraction charges can only target planets and moons.");
        }
        if (released(state, bodyId)) {
            return new FireResult(false, target.name() + " is already fractured. Use LOCATE to find an exposed deposit.");
        }
        if (chargeInFlight(state, bodyId)) {
            return new FireResult(false, "Extraction charge already in flight to " + target.name() + ".");
        }

        Base extractor = extractorFor(state, bodyId, playerId);
        if (extractor == null) {
            String masterId = CelestialMoonInheritance.masterPlanetId(state, bodyId);
            CelestialSystem.BodyView master = state.celestials.bodyView(masterId);
            String masterName = master == null ? "the master planet" : master.name();
            return new FireResult(false, "Deploy and anchor a Planetary Extractor to " + masterName
                    + " before firing a fracture charge at " + target.name() + ".");
        }

        data(state).charges.add(new Charge(bodyId, extractor.id, playerId == null ? "" : playerId));
        return new FireResult(true, "Fracture charge fired from " + extractor.type().name + " at " + target.name()
                + ". Deposits will be exposed on impact.");
    }

    static void update(CelestialSystem celestials, double dt) {
        WorldSystemState state = STATES.get(celestials);
        if (state == null) return;
        ExtractionState extraction = data(state);

        if (Double.isFinite(dt) && dt > 0) {
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
                    extraction.impactBodyId = charge.bodyId;
                    extraction.impactAge = 0.0;
                    it.remove();
                }
            }
            if (extraction.impactAge < 0.5) extraction.impactAge += dt;
        }

        // CelestialGameplaySystem seeds deterministic deposits before this pass. Keep them only for
        // bodies whose fracture charge has actually impacted. Unreleased bodies therefore have no
        // physical rocks to render, select, mine, save, or bridge into the active tactical world.
        for (CelestialBodyState body : CelestialGameplaySystem.bodyStates(state)) {
            String bodyId = body.profile.bodyId();
            if (extraction.releasedBodyIds.contains(bodyId)) continue;
            state.resources.removeIf(node -> node != null && bodyId.equals(node.celestialAnchorBodyId));
            body.resourceNodeIds.clear();
            body.depositsSeeded = false;
        }
    }

    static void draw(CelestialSystem celestials, Graphics2D g) {
        WorldSystemState state = STATES.get(celestials);
        if (state == null || g == null) return;
        ExtractionState extraction = data(state);
        Graphics2D c = (Graphics2D) g.create();
        c.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (Charge charge : extraction.charges) {
            Base source = state.bases.get(charge.extractorBaseId);
            CelestialSystem.BodyView target = celestials.bodyView(charge.bodyId);
            if (source == null || target == null) continue;
            double t = Math.max(0.0, Math.min(1.0, charge.elapsed / CHARGE_SECONDS));
            double eased = t * t * (3.0 - 2.0 * t);
            double px = source.x + (target.x() - source.x) * eased;
            double py = source.y + (target.y() - source.y) * eased;

            c.setColor(new Color(73, 204, 255, 75));
            c.drawLine((int)Math.round(source.x), (int)Math.round(source.y),
                    (int)Math.round(px), (int)Math.round(py));
            c.setColor(new Color(126, 229, 255, 210));
            c.fillOval((int)Math.round(px - 7), (int)Math.round(py - 7), 14, 14);
            c.setColor(new Color(255, 255, 255, 235));
            c.fillOval((int)Math.round(px - 3), (int)Math.round(py - 3), 6, 6);
        }

        if (extraction.impactBodyId != null && !extraction.impactBodyId.isBlank() && extraction.impactAge < 0.5) {
            CelestialSystem.BodyView target = celestials.bodyView(extraction.impactBodyId);
            if (target != null) {
                float age = (float)Math.max(0.0, Math.min(1.0, extraction.impactAge / 0.5));
                float radius = (float)(target.radius() * (0.9 + age * 0.9));
                float alpha = 1.0f - age;
                RadialGradientPaint flash = new RadialGradientPaint(
                        new Point2D.Double(target.x(), target.y()), Math.max(8f, radius),
                        new float[]{0f, 0.45f, 1f},
                        new Color[]{new Color(255, 255, 255, Math.round(190 * alpha)),
                                new Color(110, 224, 255, Math.round(105 * alpha)),
                                new Color(110, 224, 255, 0)});
                c.setPaint(flash);
                c.fillOval((int)Math.round(target.x() - radius), (int)Math.round(target.y() - radius),
                        Math.round(radius * 2), Math.round(radius * 2));
            }
        }
        c.dispose();
    }

    static Map<String,Object> captureState(WorldSystemState state) {
        if (state == null) return Map.of();
        ExtractionState extraction = data(state);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("releasedBodyIds", new ArrayList<>(extraction.releasedBodyIds));
        return out;
    }

    static void restoreState(WorldSystemState state, Object raw) {
        if (state == null) return;
        ExtractionState extraction = data(state);
        extraction.releasedBodyIds.clear();
        extraction.charges.clear();
        extraction.impactBodyId = "";
        extraction.impactAge = 1.0;
        Map<String,Object> saved = ServerSaveStore.object(raw);
        for (Object value : ServerSaveStore.list(saved.get("releasedBodyIds"))) {
            String id = ServerSaveStore.asString(value, "");
            if (!id.isBlank() && state.celestials.bodyView(id) != null) extraction.releasedBodyIds.add(id);
        }
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
        final List<Charge> charges = new ArrayList<>();
        String impactBodyId = "";
        double impactAge = 1.0;
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
