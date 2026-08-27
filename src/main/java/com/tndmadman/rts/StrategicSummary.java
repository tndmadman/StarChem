package com.tndmadman.rts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

record StrategicSummarySnapshot(
        String ownerId,
        List<StrategicSystemRow> systems,
        List<StrategicFleetRow> fleets,
        List<StrategicStationRow> stations,
        List<StrategicProductionRow> production,
        List<StrategicResearchRow> research,
        List<StrategicAlertRow> alerts,
        boolean truncated) {
    StrategicSummarySnapshot {
        ownerId = clean(ownerId);
        systems = immutable(systems);
        fleets = immutable(fleets);
        stations = immutable(stations);
        production = immutable(production);
        research = immutable(research);
        alerts = immutable(alerts);
    }

    static StrategicSummarySnapshot empty(String ownerId) {
        return new StrategicSummarySnapshot(ownerId, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false);
    }

    boolean empty() {
        return systems.isEmpty() && fleets.isEmpty() && stations.isEmpty() && production.isEmpty()
                && research.isEmpty() && alerts.isEmpty();
    }

    private static <T> List<T> immutable(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}

record StrategicSystemRow(String systemId, String name, boolean controlled,
                          int ships, int stations, int productionJobs, int damagedAssets, int alerts) { }
record StrategicFleetRow(String unitKey, String systemId, String hullId, String hullName,
                         String status, double hullFraction, double shieldFraction, double x, double y) { }
record StrategicStationRow(String baseId, String systemId, String typeId, String typeName,
                           String status, int queueSize, double hullFraction, double shieldFraction,
                           double inventoryTotal, String logisticsStatus, double x, double y) { }
record StrategicProductionRow(String baseId, String systemId, String jobId, String kind,
                              String itemId, String itemName, int queuePosition,
                              double progress, double remaining, String blockedReason,
                              double x, double y) { }
record StrategicResearchRow(String topicId, String name, String status, String detail) { }
record StrategicAlertRow(String systemId, String assetKey, String category, String text, double x, double y) { }

final class StrategicSummaryService {
    static final long REFRESH_NANOS = 750_000_000L;
    static final int MAX_FLEET_ROWS = 768;
    static final int MAX_STATION_ROWS = 256;
    static final int MAX_PRODUCTION_ROWS = 512;
    static final int MAX_ALERT_ROWS = 160;

    private static final Map<World, Map<String, CacheEntry>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<World, Integer> SCANS = Collections.synchronizedMap(new WeakHashMap<>());

    private StrategicSummaryService() { }

    static StrategicSummarySnapshot capture(World world, String ownerId) {
        return capture(world, ownerId, System.nanoTime(), false);
    }

    static StrategicSummarySnapshot captureFresh(World world, String ownerId) {
        return capture(world, ownerId, System.nanoTime(), true);
    }

    static void invalidate(World world) {
        if (world == null) return;
        CACHE.remove(world);
    }

    static void clear(World world) {
        if (world == null) return;
        CACHE.remove(world);
        SCANS.remove(world);
        StrategicSummaryRegistry.clear(world);
    }

    static int scanCountForTest(World world) {
        return world == null ? 0 : SCANS.getOrDefault(world, 0);
    }

    private static StrategicSummarySnapshot capture(World world, String ownerId, long now, boolean force) {
        String owner = clean(ownerId);
        if (world == null || owner.isBlank() || "WAIT".equals(owner)) return StrategicSummarySnapshot.empty(owner);
        synchronized (CACHE) {
            Map<String, CacheEntry> byOwner = CACHE.computeIfAbsent(world, ignored -> new LinkedHashMap<>());
            CacheEntry cached = byOwner.get(owner);
            if (!force && cached != null && now - cached.capturedAtNanos < REFRESH_NANOS) return cached.snapshot;
            StrategicSummarySnapshot snapshot = build(world, owner);
            byOwner.put(owner, new CacheEntry(now, snapshot));
            SCANS.put(world, SCANS.getOrDefault(world, 0) + 1);
            return snapshot;
        }
    }

    private static StrategicSummarySnapshot build(World world, String ownerId) {
        Map<String,Object> galaxy = world.captureServerSaveGalaxy();
        List<StrategicFleetRow> fleets = new ArrayList<>();
        List<StrategicStationRow> stations = new ArrayList<>();
        List<StrategicProductionRow> production = new ArrayList<>();
        List<StrategicAlertRow> alerts = new ArrayList<>();
        Map<String, SystemAccumulator> systems = new LinkedHashMap<>();
        Set<String> ownedStationTypes = new LinkedHashSet<>();
        Map<String, StrategicProductionRow> queuedResearch = new LinkedHashMap<>();
        boolean truncated = false;

        for (Object rawSystem : ServerSaveStore.list(galaxy.get("systems"))) {
            Map<String,Object> system = ServerSaveStore.object(rawSystem);
            String systemId = text(system.get("systemId"));
            if (systemId.isBlank()) continue;
            String templateId = text(system.get("templateId"));
            SystemAccumulator aggregate = new SystemAccumulator(systemId, systemName(systemId, templateId));
            Map<String,Object> control = ServerSaveStore.object(system.get("control"));
            aggregate.controlled = ownerId.equals(text(control.get("controllerId")));

            for (Object rawUnit : ServerSaveStore.list(system.get("units"))) {
                Map<String,Object> row = ServerSaveStore.object(rawUnit);
                if (!ownerId.equals(text(row.get("playerId")))) continue;
                double hp = number(row.get("hp"));
                if (hp <= 0) continue;
                aggregate.ships++;
                int unitId = integer(row.get("unitId"));
                String unitKey = ownerId + ":" + unitId;
                String hullId = text(row.get("shipTypeId"));
                ShipType hull = safeShip(hullId);
                double hullFraction = fraction(hp, hull == null ? hp : hull.maxHp);
                double shieldFraction = fraction(number(row.get("shield")), hull == null ? 0 : hull.maxShield);
                String status = fleetStatus(row, hullFraction);
                if (hullFraction < 0.72) aggregate.damagedAssets++;
                double x = number(row.get("x"));
                double y = number(row.get("y"));
                if (fleets.size() < MAX_FLEET_ROWS) {
                    fleets.add(new StrategicFleetRow(unitKey, systemId, hullId,
                            hull == null ? hullId : hull.name, status, hullFraction, shieldFraction, x, y));
                } else truncated = true;
                if (hullFraction < 0.50) {
                    truncated |= !addAlert(alerts, new StrategicAlertRow(systemId, unitKey, "DAMAGE",
                            (hull == null ? hullId : hull.name) + " is critically damaged (" + percent(hullFraction) + " hull).", x, y));
                } else if ("FIGHTING".equals(status)) {
                    truncated |= !addAlert(alerts, new StrategicAlertRow(systemId, unitKey, "COMBAT",
                            (hull == null ? hullId : hull.name) + " is engaged in combat.", x, y));
                }
            }

            for (Object rawBase : ServerSaveStore.list(system.get("bases"))) {
                Map<String,Object> row = ServerSaveStore.object(rawBase);
                if (!ownerId.equals(text(row.get("playerId")))) continue;
                double hp = number(row.get("hp"));
                if (hp <= 0) continue;
                aggregate.stations++;
                String baseId = text(row.get("id"));
                String typeId = text(row.get("typeId"));
                BaseType type = safeBase(typeId);
                ownedStationTypes.add(typeId);
                List<Object> jobs = ServerSaveStore.list(row.get("productionQueue"));
                aggregate.productionJobs += jobs.size();
                double hullFraction = fraction(hp, type == null ? hp : type.maxHp);
                double shieldFraction = fraction(number(row.get("shield")), type == null ? 0 : type.maxShield);
                if (hullFraction < 0.72) aggregate.damagedAssets++;
                String logistics = text(row.get("logisticsStatus"));
                String stationStatus = stationStatus(jobs, logistics, hullFraction);
                double x = number(row.get("x"));
                double y = number(row.get("y"));
                if (stations.size() < MAX_STATION_ROWS) {
                    stations.add(new StrategicStationRow(baseId, systemId, typeId,
                            type == null ? typeId : type.name, stationStatus, jobs.size(), hullFraction,
                            shieldFraction, inventoryTotal(row.get("inventory")), logistics, x, y));
                } else truncated = true;
                if (hullFraction < 0.50) {
                    truncated |= !addAlert(alerts, new StrategicAlertRow(systemId, baseId, "DAMAGE",
                            (type == null ? typeId : type.name) + " is critically damaged (" + percent(hullFraction) + " hull).", x, y));
                }

                int queuePosition = 0;
                for (Object rawJob : jobs) {
                    queuePosition++;
                    Map<String,Object> job = ServerSaveStore.object(rawJob);
                    String kind = text(job.get("kind"));
                    String itemId = text(job.get("itemId"));
                    String jobId = text(job.get("id"));
                    double duration = Math.max(0, number(job.get("duration")));
                    double remaining = Math.max(0, number(job.get("remaining")));
                    String blocked = text(job.get("blockedReason"));
                    StrategicProductionRow productionRow = new StrategicProductionRow(baseId, systemId, jobId, kind,
                            itemId, productionName(kind, itemId, job), queuePosition,
                            duration <= 0 ? 1.0 : fraction(duration - remaining, duration), remaining, blocked, x, y);
                    if (production.size() < MAX_PRODUCTION_ROWS) production.add(productionRow);
                    else truncated = true;
                    if ("RESEARCH".equals(kind)) queuedResearch.putIfAbsent(itemId, productionRow);
                    if (!blocked.isBlank()) {
                        truncated |= !addAlert(alerts, new StrategicAlertRow(systemId, baseId, "PRODUCTION",
                                productionRow.itemName() + " blocked: " + blocked + ".", x, y));
                    }
                }
            }

            if (aggregate.controlled || aggregate.ships > 0 || aggregate.stations > 0) systems.put(systemId, aggregate);
        }

        List<StrategicResearchRow> research = buildResearch(world, ownerId, ownedStationTypes, queuedResearch);
        Map<String,Integer> alertsBySystem = new LinkedHashMap<>();
        for (StrategicAlertRow alert : alerts) alertsBySystem.merge(alert.systemId(), 1, Integer::sum);
        List<StrategicSystemRow> systemRows = new ArrayList<>();
        for (SystemAccumulator aggregate : systems.values()) {
            systemRows.add(new StrategicSystemRow(aggregate.systemId, aggregate.name, aggregate.controlled,
                    aggregate.ships, aggregate.stations, aggregate.productionJobs, aggregate.damagedAssets,
                    alertsBySystem.getOrDefault(aggregate.systemId, 0)));
        }

        fleets.sort(Comparator.comparing(StrategicFleetRow::systemId).thenComparing(StrategicFleetRow::unitKey));
        stations.sort(Comparator.comparing(StrategicStationRow::systemId).thenComparing(StrategicStationRow::baseId));
        production.sort(Comparator.comparing(StrategicProductionRow::systemId)
                .thenComparing(StrategicProductionRow::baseId).thenComparingInt(StrategicProductionRow::queuePosition));
        research.sort(Comparator.comparing(StrategicResearchRow::name, String.CASE_INSENSITIVE_ORDER));
        alerts.sort(Comparator.comparing(StrategicAlertRow::systemId).thenComparing(StrategicAlertRow::assetKey));
        systemRows.sort(Comparator.comparing(StrategicSystemRow::name, String.CASE_INSENSITIVE_ORDER));
        return new StrategicSummarySnapshot(ownerId, systemRows, fleets, stations, production, research, alerts, truncated);
    }

    private static List<StrategicResearchRow> buildResearch(World world, String ownerId,
                                                             Set<String> stationTypes,
                                                             Map<String, StrategicProductionRow> queued) {
        List<StrategicResearchRow> out = new ArrayList<>();
        for (ResearchTopic topic : ResearchRules.all()) {
            if (world.hasResearch(ownerId, topic.id)) {
                out.add(new StrategicResearchRow(topic.id, topic.name, "COMPLETE", topic.unlockLabel()));
                continue;
            }
            StrategicProductionRow job = queued.get(topic.id);
            if (job != null) {
                String status = job.queuePosition() == 1 && job.blockedReason().isBlank() ? "ACTIVE" : "QUEUED";
                String detail = job.systemId() + " / " + job.baseId()
                        + (job.blockedReason().isBlank() ? "" : " - " + job.blockedReason());
                out.add(new StrategicResearchRow(topic.id, topic.name, status, detail));
                continue;
            }
            String missing = ResearchRules.missingPrerequisite(world, ownerId, topic);
            boolean stationAvailable = false;
            for (String stationType : topic.stationTypes) if (stationTypes.contains(stationType)) { stationAvailable = true; break; }
            if (missing.isBlank() && stationAvailable) {
                out.add(new StrategicResearchRow(topic.id, topic.name, "AVAILABLE", topic.unlockLabel()));
            } else {
                String detail = !missing.isBlank() ? "Requires " + missing : "Requires a compatible research station";
                out.add(new StrategicResearchRow(topic.id, topic.name, "LOCKED", detail));
            }
        }
        return out;
    }

    private static boolean addAlert(List<StrategicAlertRow> alerts, StrategicAlertRow alert) {
        if (alerts.size() >= MAX_ALERT_ROWS) return false;
        alerts.add(alert);
        return true;
    }

    private static String fleetStatus(Map<String,Object> row, double hullFraction) {
        if (hullFraction < 0.72) return "DAMAGED";
        String task = text(row.get("task"));
        String order = text(row.get("orderType"));
        if ("ATTACK".equals(task) || "ATTACK_MOVE".equals(order)) return "FIGHTING";
        if (!text(row.get("logisticsTargetBaseId")).isBlank()) return "HAULING";
        if ("AUTO_HARVEST".equals(task)) return "MINING";
        if ("RETURN_TO_STATION".equals(task)) return "RETURNING";
        if (EnumSet.of(UnitOrderType.PATROL, UnitOrderType.GUARD, UnitOrderType.ESCORT, UnitOrderType.HOLD).stream()
                .anyMatch(value -> value.name().equals(order))) return order.replace('_', ' ');
        if ("MOVE".equals(task)) return "MOVING";
        return "IDLE";
    }

    private static String stationStatus(List<Object> jobs, String logistics, double hullFraction) {
        if (hullFraction < 0.72) return "DAMAGED";
        if (!jobs.isEmpty()) {
            Map<String,Object> first = ServerSaveStore.object(jobs.get(0));
            if (!text(first.get("blockedReason")).isBlank()) return "BLOCKED";
            return "PRODUCING";
        }
        if (!logistics.isBlank()) return "LOGISTICS";
        return "IDLE";
    }

    private static String productionName(String kind, String itemId, Map<String,Object> job) {
        try {
            return switch (kind) {
                case "SHIP" -> {
                    ShipType ship = Rules.ship(itemId);
                    yield ship == null ? itemId : ship.name;
                }
                case "STATION_PACKAGE" -> {
                    BaseType base = Rules.base(itemId);
                    yield (base == null ? itemId : base.name) + " package";
                }
                case "CRAFTABLE" -> {
                    CraftableItem item = CraftingRules.item(itemId);
                    yield item == null ? itemId : item.name;
                }
                case "RESEARCH" -> {
                    ResearchTopic topic = ResearchRules.topic(itemId);
                    yield topic == null ? itemId : topic.name;
                }
                case "REFIT" -> {
                    ShipType ship = Rules.ship(itemId);
                    yield (ship == null ? itemId : ship.name) + " refit";
                }
                default -> itemId;
            };
        } catch (RuntimeException ex) {
            return itemId;
        }
    }

    private static double inventoryTotal(Object raw) {
        double total = 0;
        for (Object value : ServerSaveStore.object(raw).values()) {
            if (value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() > 0) total += number.doubleValue();
        }
        return total;
    }

    private static ShipType safeShip(String id) {
        try { return Rules.ship(id); } catch (RuntimeException ex) { return null; }
    }

    private static BaseType safeBase(String id) {
        try { return Rules.base(id); } catch (RuntimeException ex) { return null; }
    }

    private static String systemName(String systemId, String templateId) {
        try {
            StarSystemDefinition definition = StarSystems.get(templateId);
            String name = definition == null ? systemId : definition.name();
            return systemId.endsWith("_2") ? name + " II" : name;
        } catch (RuntimeException ex) {
            return systemId;
        }
    }

    private static double fraction(double value, double max) {
        if (!Double.isFinite(value) || !Double.isFinite(max) || max <= 0) return 0;
        return Math.max(0, Math.min(1, value / max));
    }

    private static String percent(double value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 100) + "%";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String text(Object value) { return clean(value == null ? "" : String.valueOf(value)); }
    private static int integer(Object value) { return value instanceof Number n ? n.intValue() : parseInt(text(value)); }
    private static int parseInt(String value) { try { return Integer.parseInt(value); } catch (RuntimeException ex) { return 0; } }
    private static double number(Object value) { return value instanceof Number n ? finite(n.doubleValue()) : parseDouble(text(value)); }
    private static double parseDouble(String value) { try { return finite(Double.parseDouble(value)); } catch (RuntimeException ex) { return 0; } }
    private static double finite(double value) { return Double.isFinite(value) ? value : 0; }

    private record CacheEntry(long capturedAtNanos, StrategicSummarySnapshot snapshot) { }

    private static final class SystemAccumulator {
        final String systemId;
        final String name;
        boolean controlled;
        int ships;
        int stations;
        int productionJobs;
        int damagedAssets;
        SystemAccumulator(String systemId, String name) { this.systemId = systemId; this.name = name; }
    }
}

final class StrategicSummaryRegistry {
    private static final Map<World, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private StrategicSummaryRegistry() { }

    static void replace(World world, StrategicSummarySnapshot snapshot) {
        if (world == null || snapshot == null || snapshot.ownerId().isBlank() || "WAIT".equals(snapshot.ownerId())) return;
        String local = PlayerRegistry.localId();
        if (local != null && !local.isBlank() && !"WAIT".equals(local) && !local.equals(snapshot.ownerId())) {
            throw new SnapshotDecodeException("Strategic summary owner does not match the local player.");
        }
        STATES.put(world, new State(true, snapshot.ownerId(), snapshot));
    }

    static State state(World world) {
        State state = world == null ? null : STATES.get(world);
        return state == null ? State.EMPTY : state;
    }

    static void clear(World world) { if (world != null) STATES.remove(world); }

    record State(boolean initialized, String ownerId, StrategicSummarySnapshot snapshot) {
        static final State EMPTY = new State(false, "", StrategicSummarySnapshot.empty(""));
        State {
            ownerId = ownerId == null ? "" : ownerId;
            snapshot = snapshot == null ? StrategicSummarySnapshot.empty(ownerId) : snapshot;
        }
    }
}

final class StrategicSummaryWire {
    private static final int MAX_COMPRESSED_CHARS = 480_000;
    private static final int MAX_JSON_BYTES = 2_000_000;
    private static final MiniJson.Limits JSON_LIMITS = new MiniJson.Limits(
            MAX_JSON_BYTES, 16, 300_000, 8_192, 30_000, 64, true);

    private StrategicSummaryWire() { }

    static String encodeToken(StrategicSummarySnapshot snapshot) {
        if (snapshot == null) return "";
        byte[] json = MiniJson.stringify(toMap(snapshot)).getBytes(StandardCharsets.UTF_8);
        if (json.length > MAX_JSON_BYTES) throw new IllegalArgumentException("Strategic summary exceeds safe limits.");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(json); }
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
            if (token.length() > MAX_COMPRESSED_CHARS) throw new IllegalArgumentException("Strategic summary exceeds TCP-safe limits.");
            return token;
        } catch (IOException ex) {
            throw new IllegalStateException("Could not encode strategic summary.", ex);
        }
    }

    static StrategicSummarySnapshot decodeToken(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_COMPRESSED_CHARS) {
            throw new SnapshotDecodeException("Malformed strategic summary payload.");
        }
        try {
            byte[] compressed = Base64.getUrlDecoder().decode(token);
            ByteArrayOutputStream json = new ByteArrayOutputStream();
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = gzip.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    if (json.size() + read > MAX_JSON_BYTES) throw new SnapshotDecodeException("Strategic summary expands beyond safe limits.");
                    json.write(buffer, 0, read);
                }
            }
            Object parsed = MiniJson.parse(json.toString(StandardCharsets.UTF_8), JSON_LIMITS);
            return fromMap(ServerSaveStore.object(parsed));
        } catch (SnapshotDecodeException ex) {
            throw ex;
        } catch (RuntimeException | IOException ex) {
            throw new SnapshotDecodeException("Malformed strategic summary payload.");
        }
    }

    private static Map<String,Object> toMap(StrategicSummarySnapshot snapshot) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("owner", snapshot.ownerId());
        out.put("truncated", snapshot.truncated());
        out.put("systems", mapSystems(snapshot.systems()));
        out.put("fleets", mapFleets(snapshot.fleets()));
        out.put("stations", mapStations(snapshot.stations()));
        out.put("production", mapProduction(snapshot.production()));
        out.put("research", mapResearch(snapshot.research()));
        out.put("alerts", mapAlerts(snapshot.alerts()));
        return out;
    }

    private static List<Object> mapSystems(List<StrategicSystemRow> rows) {
        List<Object> out = new ArrayList<>();
        for (StrategicSystemRow r : rows) out.add(Map.of("id", r.systemId(), "name", r.name(), "controlled", r.controlled(),
                "ships", r.ships(), "stations", r.stations(), "jobs", r.productionJobs(), "damaged", r.damagedAssets(), "alerts", r.alerts()));
        return out;
    }

    private static List<Object> mapFleets(List<StrategicFleetRow> rows) {
        List<Object> out = new ArrayList<>();
        for (StrategicFleetRow r : rows) out.add(Map.of("key", r.unitKey(), "system", r.systemId(), "hull", r.hullId(), "name", r.hullName(),
                "status", r.status(), "hp", r.hullFraction(), "shield", r.shieldFraction(), "x", r.x(), "y", r.y()));
        return out;
    }

    private static List<Object> mapStations(List<StrategicStationRow> rows) {
        List<Object> out = new ArrayList<>();
        for (StrategicStationRow r : rows) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", r.baseId()); row.put("system", r.systemId()); row.put("type", r.typeId()); row.put("name", r.typeName());
            row.put("status", r.status()); row.put("queue", r.queueSize()); row.put("hp", r.hullFraction()); row.put("shield", r.shieldFraction());
            row.put("inventory", r.inventoryTotal()); row.put("logistics", r.logisticsStatus()); row.put("x", r.x()); row.put("y", r.y());
            out.add(row);
        }
        return out;
    }

    private static List<Object> mapProduction(List<StrategicProductionRow> rows) {
        List<Object> out = new ArrayList<>();
        for (StrategicProductionRow r : rows) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("base", r.baseId()); row.put("system", r.systemId()); row.put("job", r.jobId()); row.put("kind", r.kind());
            row.put("item", r.itemId()); row.put("name", r.itemName()); row.put("position", r.queuePosition()); row.put("progress", r.progress());
            row.put("remaining", r.remaining()); row.put("blocked", r.blockedReason()); row.put("x", r.x()); row.put("y", r.y());
            out.add(row);
        }
        return out;
    }

    private static List<Object> mapResearch(List<StrategicResearchRow> rows) {
        List<Object> out = new ArrayList<>();
        for (StrategicResearchRow r : rows) out.add(Map.of("id", r.topicId(), "name", r.name(), "status", r.status(), "detail", r.detail()));
        return out;
    }

    private static List<Object> mapAlerts(List<StrategicAlertRow> rows) {
        List<Object> out = new ArrayList<>();
        for (StrategicAlertRow r : rows) out.add(Map.of("system", r.systemId(), "asset", r.assetKey(), "category", r.category(),
                "text", r.text(), "x", r.x(), "y", r.y()));
        return out;
    }

    private static StrategicSummarySnapshot fromMap(Map<String,Object> root) {
        String owner = requiredText(root, "owner");
        boolean truncated = bool(root.get("truncated"));
        List<StrategicSystemRow> systems = new ArrayList<>();
        for (Object raw : bounded(root, "systems", 128)) {
            Map<String,Object> r = ServerSaveStore.object(raw);
            systems.add(new StrategicSystemRow(requiredText(r, "id"), text(r.get("name")), bool(r.get("controlled")),
                    nonNegativeInt(r.get("ships")), nonNegativeInt(r.get("stations")), nonNegativeInt(r.get("jobs")),
                    nonNegativeInt(r.get("damaged")), nonNegativeInt(r.get("alerts"))));
        }
        List<StrategicFleetRow> fleets = new ArrayList<>();
        for (Object raw : bounded(root, "fleets", StrategicSummaryService.MAX_FLEET_ROWS)) {
            Map<String,Object> r = ServerSaveStore.object(raw);
            String key = requiredText(r, "key");
            if (!key.startsWith(owner + ":")) throw new SnapshotDecodeException("Strategic summary contains a foreign fleet asset.");
            fleets.add(new StrategicFleetRow(key, requiredText(r, "system"), text(r.get("hull")), text(r.get("name")),
                    text(r.get("status")), fractionValue(r.get("hp")), fractionValue(r.get("shield")), finite(r.get("x")), finite(r.get("y"))));
        }
        List<StrategicStationRow> stations = new ArrayList<>();
        for (Object raw : bounded(root, "stations", StrategicSummaryService.MAX_STATION_ROWS)) {
            Map<String,Object> r = ServerSaveStore.object(raw);
            String id = requiredText(r, "id");
            if (!id.startsWith(owner + ":")) throw new SnapshotDecodeException("Strategic summary contains a foreign station asset.");
            stations.add(new StrategicStationRow(id, requiredText(r, "system"), text(r.get("type")), text(r.get("name")), text(r.get("status")),
                    nonNegativeInt(r.get("queue")), fractionValue(r.get("hp")), fractionValue(r.get("shield")), nonNegative(r.get("inventory")),
                    text(r.get("logistics")), finite(r.get("x")), finite(r.get("y"))));
        }
        List<StrategicProductionRow> production = new ArrayList<>();
        for (Object raw : bounded(root, "production", StrategicSummaryService.MAX_PRODUCTION_ROWS)) {
            Map<String,Object> r = ServerSaveStore.object(raw);
            String base = requiredText(r, "base");
            if (!base.startsWith(owner + ":")) throw new SnapshotDecodeException("Strategic summary contains foreign production state.");
            production.add(new StrategicProductionRow(base, requiredText(r, "system"), text(r.get("job")), text(r.get("kind")), text(r.get("item")),
                    text(r.get("name")), positiveInt(r.get("position")), fractionValue(r.get("progress")), nonNegative(r.get("remaining")),
                    text(r.get("blocked")), finite(r.get("x")), finite(r.get("y"))));
        }
        List<StrategicResearchRow> research = new ArrayList<>();
        for (Object raw : bounded(root, "research", 512)) {
            Map<String,Object> r = ServerSaveStore.object(raw);
            research.add(new StrategicResearchRow(requiredText(r, "id"), text(r.get("name")), text(r.get("status")), text(r.get("detail"))));
        }
        List<StrategicAlertRow> alerts = new ArrayList<>();
        for (Object raw : bounded(root, "alerts", StrategicSummaryService.MAX_ALERT_ROWS)) {
            Map<String,Object> r = ServerSaveStore.object(raw);
            String asset = requiredText(r, "asset");
            if (!asset.startsWith(owner + ":")) throw new SnapshotDecodeException("Strategic summary contains a foreign alert asset.");
            alerts.add(new StrategicAlertRow(requiredText(r, "system"), asset, text(r.get("category")), text(r.get("text")),
                    finite(r.get("x")), finite(r.get("y"))));
        }
        return new StrategicSummarySnapshot(owner, systems, fleets, stations, production, research, alerts, truncated);
    }

    private static List<Object> bounded(Map<String,Object> root, String key, int max) {
        List<Object> rows = ServerSaveStore.list(root.get(key));
        if (rows.size() > max) throw new SnapshotDecodeException("Strategic summary " + key + " rows exceed safe limits.");
        return rows;
    }

    private static String requiredText(Map<String,Object> map, String key) {
        String value = text(map.get(key));
        if (value.isBlank() || value.length() > 512) throw new SnapshotDecodeException("Malformed strategic summary identity.");
        return value;
    }

    private static String text(Object value) {
        String text = value == null ? "" : String.valueOf(value).replace('\n', ' ').replace('\r', ' ').trim();
        if (text.length() > 4096) throw new SnapshotDecodeException("Strategic summary text exceeds safe limits.");
        return text;
    }

    private static int positiveInt(Object value) { int v = nonNegativeInt(value); if (v < 1) throw new SnapshotDecodeException("Malformed strategic summary index."); return v; }
    private static int nonNegativeInt(Object value) { double n = nonNegative(value); if (n > 1_000_000 || n != Math.rint(n)) throw new SnapshotDecodeException("Malformed strategic summary count."); return (int)n; }
    private static double nonNegative(Object value) { double n = finite(value); if (n < 0) throw new SnapshotDecodeException("Malformed strategic summary number."); return n; }
    private static double fractionValue(Object value) { double n = finite(value); if (n < 0 || n > 1) throw new SnapshotDecodeException("Malformed strategic summary fraction."); return n; }
    private static double finite(Object value) { if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new SnapshotDecodeException("Malformed strategic summary number."); return n.doubleValue(); }
    private static boolean bool(Object value) { if (value instanceof Boolean b) return b; throw new SnapshotDecodeException("Malformed strategic summary flag."); }
}
