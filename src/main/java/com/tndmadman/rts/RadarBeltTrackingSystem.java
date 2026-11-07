package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Authoritative per-system / per-radar belt selections and bounded, selected-only
 * resource-location intelligence. Combat radar ranges and individual resource
 * snapshot permissions are deliberately separate from these long-range bearings.
 */
final class RadarBeltTrackingSystem {
    private static final int MAX_RADARS = 128;
    private static final int MAX_PACKET_CHARS = 196_608;
    private static final long SEND_INTERVAL_NANOS = 250_000_000L;
    private static final long CLIENT_TRACK_STALE_NANOS = 2_000_000_000L;
    private static final Map<World, Map<String, Cache>> AGGREGATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<World, Map<String, ClientView>> CLIENT_VIEWS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<World, Map<String, Long>> SEND_TIMES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<World> CLIENT_WORLDS =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private RadarBeltTrackingSystem() { }

    record Track(String beltId, String name, String kind, int activeCount, int totalCount,
                 double x, double y, double radius, double halfArc) {
        boolean active() { return activeCount > 0 && Double.isFinite(x) && Double.isFinite(y); }
    }

    record RadarView(String radarId, double x, double y, boolean paused, List<String> selected) { }
    record View(String systemId, List<RadarView> radars, Map<String, Track> tracks) {
        static View empty(String id) { return new View(id, List.of(), Map.of()); }
    }
    private record Cache(long quarterTick, Map<String, Track> tracks) { }
    private record ClientView(View view, long receivedAt) { }

    static void markClient(World world) { if (world != null) CLIENT_WORLDS.add(world); }

    static void clear(World world) {
        resetSystem(world);
        CLIENT_WORLDS.remove(world);
    }

    /** A galaxy rebuild invalidates tracks but retains client/server trust classification. */
    static void resetSystem(World world) {
        if (world == null) return;
        AGGREGATES.remove(world);
        CLIENT_VIEWS.remove(world);
        SEND_TIMES.remove(world);
    }

    static List<ResourceBelt> catalog(World world) {
        WorldSystemState state = current(world);
        return state == null || state.definition == null ? List.of() : state.definition.resourceBelts();
    }

    private static WorldSystemState current(World world) {
        return world == null ? null : world.radarBeltState(world.activeSystemId());
    }

    static int capacity(Base radar) {
        return radar == null ? 0 : RadarTowerRules.trackedBeltLimit(radar.typeId);
    }

    static List<String> selected(World world, Base radar) {
        if (world == null || radar == null || radar.hp <= 0 || capacity(radar) == 0) return List.of();
        if (CLIENT_WORLDS.contains(world)) {
            for (RadarView row : view(world, PlayerRegistry.localId()).radars()) {
                if (radar.id.equals(row.radarId())) return row.selected();
            }
            return List.of();
        }
        WorldSystemState state = current(world);
        if (state == null || world.bases.get(radar.id) != radar) return List.of();
        return List.copyOf(selection(state, radar));
    }

    private static LinkedHashSet<String> selection(WorldSystemState state, Base radar) {
        LinkedHashSet<String> selected = state.radarBeltSelections.get(radar.id);
        if (selected == null) {
            selected = new LinkedHashSet<>();
            int limit = capacity(radar);
            for (ResourceBelt belt : state.definition.resourceBelts()) {
                if (selected.size() >= limit) break;
                selected.add(belt.id);
            }
            state.radarBeltSelections.put(radar.id, selected);
        }
        Set<String> valid = new LinkedHashSet<>();
        for (ResourceBelt belt : state.definition.resourceBelts()) valid.add(belt.id);
        selected.removeIf(id -> !valid.contains(id));
        while (selected.size() > capacity(radar)) {
            String last = null;
            for (String id : selected) last = id;
            if (last == null) break;
            selected.remove(last);
        }
        return selected;
    }

    static boolean tracking(World world, Base radar, ResourceNode node) {
        if (world == null || radar == null || node == null || node.resourceBeltId == null
                || node.resourceBeltId.isBlank()) return false;
        return StationControls.radarSearchTarget(world, radar) == StationControls.RadarSearchTarget.AREA
                && selected(world, radar).contains(node.resourceBeltId);
    }

    static boolean apply(World world, Base radar, String action, String beltId) {
        if (world == null || radar == null || radar.hp <= 0 || capacity(radar) <= 0
                || world.bases.get(radar.id) != radar) return false;
        WorldSystemState state = current(world);
        if (state == null || action == null) return false;
        LinkedHashSet<String> selected = selection(state, radar);
        String id = beltId == null ? "" : beltId.trim();
        boolean valid = false;
        for (ResourceBelt belt : state.definition.resourceBelts()) {
            if (belt.id.equals(id)) { valid = true; break; }
        }
        return switch (action) {
            case "RADAR_BELT_SELECT" -> {
                if (!valid) yield false;
                if (capacity(radar) == 1) {
                    selected.clear();
                    selected.add(id);
                    yield true;
                }
                if (selected.contains(id)) yield true;
                if (selected.size() >= capacity(radar)) yield false;
                yield selected.add(id);
            }
            case "RADAR_BELT_ADD" -> valid && (selected.contains(id)
                    || selected.size() < capacity(radar) && selected.add(id));
            case "RADAR_BELT_REMOVE" -> valid && selected.remove(id);
            case "RADAR_BELT_CLEAR" -> { selected.clear(); yield true; }
            default -> false;
        };
    }

    /** Save only explicitly initialized selections; absent means legacy defaults, empty means cleared. */
    static Map<String, Object> capture(WorldSystemState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (state == null) return result;
        for (Map.Entry<String, LinkedHashSet<String>> entry : state.radarBeltSelections.entrySet()) {
            Base radar = state.bases.get(entry.getKey());
            if (radar != null && radar.hp > 0 && capacity(radar) > 0) {
                result.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        return result;
    }

    static void restore(WorldSystemState state, Object payload) {
        if (payload == null || state == null) return;
        if (!(payload instanceof Map<?, ?> data) || data.size() > 1024) {
            throw new IllegalArgumentException("Invalid saved radar belt selections");
        }
        for (Map.Entry<?, ?> entry : data.entrySet()) {
            if (!(entry.getKey() instanceof String radarId) || radarId.length() > 128
                    || !(entry.getValue() instanceof List<?> ids) || ids.size() > 3) {
                throw new IllegalArgumentException("Invalid saved radar belt entry");
            }
            Base radar = state.bases.get(radarId);
            if (radar == null || radar.hp <= 0 || capacity(radar) == 0) continue;
            LinkedHashSet<String> selected = new LinkedHashSet<>();
            for (Object item : ids) {
                if (!(item instanceof String id) || id.length() > 80) {
                    throw new IllegalArgumentException("Invalid saved resource belt ID");
                }
                boolean exists = false;
                for (ResourceBelt belt : state.definition.resourceBelts()) {
                    if (belt.id.equals(id)) { exists = true; break; }
                }
                if (exists && !selected.add(id)) throw new IllegalArgumentException("Duplicate radar belt ID");
            }
            if (selected.size() > capacity(radar)) throw new IllegalArgumentException("Radar belt cap exceeded in save");
            state.radarBeltSelections.put(radarId, selected);
        }
    }

    static Map<String, Object> captureSearchTargets(WorldSystemState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (state == null) return out;
        for (Map.Entry<String, StationControls.RadarSearchTarget> entry : state.radarSearchTargets.entrySet()) {
            if (state.bases.containsKey(entry.getKey()) && entry.getValue() != null) {
                out.put(entry.getKey(), entry.getValue().name());
            }
        }
        return out;
    }

    static void restoreSearchTargets(WorldSystemState state, Object payload) {
        if (payload == null || state == null) return;
        if (!(payload instanceof Map<?, ?> modes) || modes.size() > 1024)
            throw new IllegalArgumentException("Invalid saved radar search modes");
        for (Map.Entry<?, ?> entry : modes.entrySet()) {
            if (!(entry.getKey() instanceof String id) || id.length() > 128
                    || !(entry.getValue() instanceof String text))
                throw new IllegalArgumentException("Invalid saved radar search-mode entry");
            Base base = state.bases.get(id);
            if (base == null || base.hp <= 0 || capacity(base) == 0) continue;
            try {
                state.radarSearchTargets.put(id, StationControls.RadarSearchTarget.valueOf(text));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown saved radar search mode");
            }
        }
    }

    static Boolean remotePaused(World world, Base radar) {
        if (world == null || radar == null || !CLIENT_WORLDS.contains(world)) return null;
        for (RadarView row : view(world, PlayerRegistry.localId()).radars()) {
            if (radar.id.equals(row.radarId())) return row.paused();
        }
        return null;
    }

    static View view(World world, String viewer) {
        if (world == null) return View.empty("");
        String systemId = world.activeSystemId();
        if (CLIENT_WORLDS.contains(world)) {
            ClientView received = CLIENT_VIEWS.getOrDefault(world, Map.of()).get(systemId);
            return received != null && System.nanoTime() - received.receivedAt() < CLIENT_TRACK_STALE_NANOS
                    ? received.view() : View.empty(systemId);
        }
        return authoritative(world, viewer, systemId);
    }

    private static View authoritative(World world, String viewer, String systemId) {
        WorldSystemState state = world.radarBeltState(systemId);
        if (state == null || viewer == null || viewer.isBlank()) return View.empty(systemId);
        List<RadarView> radars = new ArrayList<>();
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        // Owner only. No implicit allied sharing of remote ore intel.
        Map<String, Base> liveBases = state.id.equals(world.activeSystemId()) ? world.bases : state.bases;
        for (Base radar : liveBases.values()) {
            if (radars.size() >= MAX_RADARS) break;
            if (radar.hp <= 0 || !viewer.equals(radar.playerId) || capacity(radar) == 0) continue;
            boolean paused = StationControls.radarSearchTargetForSystem(state, radar)
                    == StationControls.RadarSearchTarget.WORMHOLES;
            List<String> selected = List.copyOf(selection(state, radar));
            radars.add(new RadarView(radar.id, radar.x, radar.y, paused, selected));
            if (!paused) wanted.addAll(selected);
        }
        Map<String, Track> aggregates = aggregates(world, state);
        Map<String, Track> shown = new LinkedHashMap<>();
        for (String id : wanted) {
            Track track = aggregates.get(id);
            if (track != null) shown.put(id, track);
        }
        return new View(systemId, List.copyOf(radars), Map.copyOf(shown));
    }

    private static Map<String, Track> aggregates(World world, WorldSystemState state) {
        double time = state.id.equals(world.activeSystemId()) ? world.systemTime() : state.systemTime;
        long quarter = (long)Math.floor(time * 4.0);
        Map<String, Cache> bySystem = AGGREGATES.computeIfAbsent(world, ignored -> new HashMap<>());
        Cache cached = bySystem.get(state.id);
        if (cached != null && cached.quarterTick == quarter) return cached.tracks;

        Map<String, Accumulator> accum = new LinkedHashMap<>();
        for (ResourceBelt belt : state.definition.resourceBelts()) {
            accum.put(belt.id, new Accumulator(belt, state.celestials.sunX(), state.celestials.sunY()));
        }
        List<ResourceNode> resources = state.id.equals(world.activeSystemId()) ? world.resources : state.resources;
        for (ResourceNode node : resources) {
            if (node == null || node.resourceBeltId == null) continue;
            Accumulator a = accum.get(node.resourceBeltId);
            if (a != null) a.accept(node);
        }
        Map<String, Track> built = new LinkedHashMap<>();
        for (Accumulator value : accum.values()) built.put(value.belt.id, value.result(state.id));
        Cache fresh = new Cache(quarter, Map.copyOf(built));
        bySystem.put(state.id, fresh);
        return fresh.tracks;
    }

    private static final class Accumulator {
        final ResourceBelt belt;
        final double sunX, sunY;
        double sin, cos, radiusSum, weightSum;
        int active, total;
        private record Sample(double sin, double cos, double radius, double weight, double angle) { }
        final List<Sample> samples = new ArrayList<>();
        Accumulator(ResourceBelt belt, double sunX, double sunY) {
            this.belt = belt; this.sunX = sunX; this.sunY = sunY;
        }
        void accept(ResourceNode node) {
            if (node.celestialAnchorBodyId != null && !node.celestialAnchorBodyId.isBlank()) return;
            total++;
            if (!node.active || node.amount <= 0 || !Double.isFinite(node.x) || !Double.isFinite(node.y)) return;
            double dx = node.x - sunX, dy = node.y - sunY;
            double r = Math.hypot(dx, dy);
            if (!Double.isFinite(r) || r < 1) return;
            double a = Math.atan2(dy, dx);
            double w = Math.max(1, node.amount);
            sin += Math.sin(a) * w;
            cos += Math.cos(a) * w;
            radiusSum += r * w;
            weightSum += w;
            samples.add(new Sample(Math.sin(a), Math.cos(a), r, w, a));
            active++;
        }
        Track result(String systemId) {
            if (active == 0 || weightSum <= 0) {
                return new Track(belt.id, belt.name, belt.kind.name(), 0, total,
                        Double.NaN, Double.NaN, 0, 0);
            }
            double angle = Math.atan2(sin, cos);
            double halfArc = 0;
            for (Sample sample : samples) {
                double delta = Math.abs(Math.atan2(Math.sin(sample.angle() - angle),
                        Math.cos(sample.angle() - angle)));
                halfArc = Math.max(halfArc, delta);
            }
            // Historical saves can have independently sheared resources. Rather than
            // aim at an empty weighted midpoint of several orbital patches, point to
            // the densest real contiguous sector (not the authored orbital radius).
            double allowed = Math.max(0.05, Math.min(Math.PI * 0.95, belt.arc * 0.5));
            if (halfArc > allowed + 0.02 || Math.hypot(sin, cos) / weightSum < 0.25) {
                Sample winner = samples.get(0);
                double bestWeight = -1;
                double cosWindow = Math.cos(allowed);
                for (Sample candidate : samples) {
                    double nearby = 0;
                    for (Sample sample : samples) {
                        double dot = candidate.sin() * sample.sin()
                                + candidate.cos() * sample.cos();
                        if (dot >= cosWindow) nearby += sample.weight();
                    }
                    if (nearby > bestWeight) { bestWeight = nearby; winner = candidate; }
                }
                double selectedSin = 0, selectedCos = 0, selectedRadius = 0, selectedWeight = 0;
                for (Sample sample : samples) {
                    double dot = winner.sin() * sample.sin() + winner.cos() * sample.cos();
                    if (dot < cosWindow) continue;
                    selectedSin += sample.sin() * sample.weight();
                    selectedCos += sample.cos() * sample.weight();
                    selectedRadius += sample.radius() * sample.weight();
                    selectedWeight += sample.weight();
                }
                if (selectedWeight > 0) {
                    sin = selectedSin;
                    cos = selectedCos;
                    radiusSum = selectedRadius;
                    weightSum = selectedWeight;
                    angle = Math.atan2(sin, cos);
                    halfArc = 0;
                    for (Sample sample : samples) {
                        double dot = winner.sin() * sample.sin() + winner.cos() * sample.cos();
                        if (dot < cosWindow) continue;
                        halfArc = Math.max(halfArc, Math.abs(Math.atan2(
                                Math.sin(sample.angle() - angle), Math.cos(sample.angle() - angle))));
                    }
                }
            }
            double radius = radiusSum / weightSum;
            return new Track(belt.id, belt.name, belt.kind.name(), active, total,
                    sunX + Math.cos(angle) * radius, sunY + Math.sin(angle) * radius,
                    radius, Math.min(Math.PI, halfArc));
        }
    }

    static boolean shouldSend(World world, String viewer, String systemId, boolean initial) {
        if (world == null || viewer == null || systemId == null) return false;
        String key = viewer + "|" + systemId;
        Map<String, Long> times = SEND_TIMES.computeIfAbsent(world, ignored -> new HashMap<>());
        long now = System.nanoTime();
        Long last = times.get(key);
        if (!initial && last != null && now - last < SEND_INTERVAL_NANOS) return false;
        times.put(key, now);
        return true;
    }

    static String packet(World world, String viewer, String systemId) {
        View view = authoritative(world, viewer, systemId);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("systemId", view.systemId());
        List<Object> radars = new ArrayList<>();
        for (RadarView radar : view.radars()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", radar.radarId());
            row.put("x", radar.x()); row.put("y", radar.y());
            row.put("paused", radar.paused());
            row.put("selected", radar.selected());
            radars.add(row);
        }
        root.put("radars", radars);
        List<Object> tracks = new ArrayList<>();
        for (Track track : view.tracks().values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", track.beltId()); row.put("name", track.name());
            row.put("kind", track.kind()); row.put("active", track.activeCount());
            row.put("total", track.totalCount());
            if (track.active()) {
                row.put("x", track.x()); row.put("y", track.y());
                row.put("radius", track.radius()); row.put("halfArc", track.halfArc());
            }
            tracks.add(row);
        }
        root.put("tracks", tracks);
        String json = MiniJson.stringify(root);
        if (json.length() > MAX_PACKET_CHARS) throw new IllegalStateException("Radar track view exceeds packet limit");
        return "RADAR_BELT_VIEW|" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static void applyClient(World world, String encoded) {
        if (world == null || encoded == null || encoded.length() > MAX_PACKET_CHARS * 2) return;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > MAX_PACKET_CHARS) return;
            Object parsed = MiniJson.parse(new String(bytes, StandardCharsets.UTF_8),
                    new MiniJson.Limits(MAX_PACKET_CHARS, 12, 10000, 1024, 1024, 64, true));
            if (!(parsed instanceof Map<?, ?> root) || !(root.get("version") instanceof Number version)
                    || version.intValue() != 1
                    || !(root.get("systemId") instanceof String systemId) || systemId.length() > 128
                    || !(root.get("radars") instanceof List<?> sourceRadars) || sourceRadars.size() > MAX_RADARS
                    || !(root.get("tracks") instanceof List<?> sourceTracks) || sourceTracks.size() > MAX_RADARS * 3) return;
            List<RadarView> radars = new ArrayList<>();
            Set<String> wanted = new LinkedHashSet<>();
            for (Object item : sourceRadars) {
                if (!(item instanceof Map<?, ?> row) || !(row.get("id") instanceof String id)
                        || id.length() > 128 || !(row.get("x") instanceof Number x)
                        || !(row.get("y") instanceof Number y)
                        || !(row.get("paused") instanceof Boolean paused)
                        || !(row.get("selected") instanceof List<?> ids) || ids.size() > 3) return;
                List<String> selected = new ArrayList<>();
                for (Object value : ids) {
                    if (!(value instanceof String belt) || belt.length() > 80 || selected.contains(belt)) return;
                    selected.add(belt);
                    if (!paused) wanted.add(belt);
                }
                if (!Double.isFinite(x.doubleValue()) || !Double.isFinite(y.doubleValue())) return;
                radars.add(new RadarView(id, x.doubleValue(), y.doubleValue(), paused, List.copyOf(selected)));
            }
            Map<String, Track> tracks = new LinkedHashMap<>();
            for (Object item : sourceTracks) {
                if (!(item instanceof Map<?, ?> row) || !(row.get("id") instanceof String id)
                        || !wanted.contains(id) || !(row.get("name") instanceof String name)
                        || name.length() > 128 || !(row.get("kind") instanceof String kind)
                        || !(row.get("active") instanceof Number active) || !(row.get("total") instanceof Number total)
                        || active.intValue() < 0 || total.intValue() < active.intValue()) return;
                double x = Double.NaN, y = Double.NaN, radius = 0, halfArc = 0;
                if (active.intValue() > 0) {
                    if (!(row.get("x") instanceof Number nx) || !(row.get("y") instanceof Number ny)
                            || !(row.get("radius") instanceof Number nr) || !(row.get("halfArc") instanceof Number na)) return;
                    x = nx.doubleValue(); y = ny.doubleValue(); radius = nr.doubleValue(); halfArc = na.doubleValue();
                    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(radius)
                            || !Double.isFinite(halfArc) || halfArc < 0 || halfArc > Math.PI) return;
                }
                if (tracks.put(id, new Track(id, name, kind, active.intValue(), total.intValue(), x, y, radius, halfArc)) != null) return;
            }
            markClient(world);
            CLIENT_VIEWS.computeIfAbsent(world, ignored -> new HashMap<>())
                    .put(systemId, new ClientView(
                            new View(systemId, List.copyOf(radars), Map.copyOf(tracks)), System.nanoTime()));
        } catch (RuntimeException ignored) {
            // Malformed or stale server view cannot grant local permissions or reveal anything.
        }
    }
}
