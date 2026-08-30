package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class GalaxyMapWire {
    private static final String PREFIX = "GALAXY|";
    private static final int MAX_OWNER_UNITS = 10_000;
    private static final int MAX_TEXT = 128;

    private GalaxyMapWire() { }

    static String encode(int copiesPerTemplate, GalaxyMapSnapshot snapshot) {
        return encodeInternal(copiesPerTemplate, snapshot, OwnerProjection.ABSENT);
    }

    static String encode(int copiesPerTemplate, GalaxyMapSnapshot snapshot, Map<String,String> ownerUnitLocations) {
        String ownerId = inferOwnerId(ownerUnitLocations);
        return ownerId.isBlank()
                ? encodeInternal(copiesPerTemplate, snapshot, OwnerProjection.ABSENT)
                : encodeInternal(copiesPerTemplate, snapshot, ownerProjection(ownerId, ownerUnitLocations));
    }

    static String encode(int copiesPerTemplate, GalaxyMapSnapshot snapshot, String ownerId,
                         Map<String,String> ownerUnitLocations) {
        return encodeInternal(copiesPerTemplate, snapshot, ownerProjection(ownerId, ownerUnitLocations));
    }

    private static String encodeInternal(int copiesPerTemplate, GalaxyMapSnapshot snapshot, OwnerProjection owner) {
        if (snapshot == null) snapshot = new GalaxyMapSnapshot("", List.of(), List.of());
        World activeWorld = owner.present() ? PlayerRegistry.activeWorld() : null;
        String ownerId = owner.present() ? owner.ownerId() : "";
        StringBuilder out = new StringBuilder(PREFIX)
                .append(Math.max(1, Math.min(2, copiesPerTemplate)))
                .append('|').append(token(snapshot.activeSystemId()));

        List<GalaxyMapSystem> projectedSystems = GalaxyTopology.effectiveSystems(activeWorld, ownerId, snapshot.systems());
        if (projectedSystems.size() > 96) {
            throw new IllegalArgumentException("Galaxy system projection exceeds safe limits.");
        }
        for (GalaxyMapSystem system : projectedSystems) {
            if (system == null) continue;
            out.append("|S,")
                    .append(token(system.id())).append(',')
                    .append(token(system.name())).append(',')
                    .append(token(system.templateId())).append(',')
                    .append(system.lifetime().name()).append(',')
                    .append(system.ships()).append(',')
                    .append(system.bases()).append(',')
                    .append(system.resources()).append(',')
                    .append(system.localShips()).append(',')
                    .append(system.localBases()).append(',')
                    .append(flag(system.active())).append(',')
                    .append(flag(system.home())).append(',')
                    .append(flag(system.special())).append(',')
                    .append(token(system.controllerId())).append(',')
                    .append(token(system.controllerName())).append(',')
                    .append(system.controlStatus().name()).append(',')
                    .append(Calc.round(system.captureProgress())).append(',')
                    .append(system.controlColorRgb() & 0xFFFFFF);
        }

        List<GalaxyMapLink> projectedLinks = GalaxyTopology.effectiveLinks(activeWorld, ownerId, snapshot.links());
        if (projectedLinks.size() > 256) {
            throw new IllegalArgumentException("Galaxy link projection exceeds safe limits.");
        }
        for (GalaxyMapLink link : projectedLinks) {
            out.append("|L,").append(token(link.fromSystemId())).append(',').append(token(link.toSystemId()));
        }

        if (owner.present()) {
            out.append("|O,").append(token(owner.ownerId()));
            for (Map.Entry<String,String> entry : new TreeMap<>(owner.locations()).entrySet()) {
                out.append("|F,").append(token(entry.getKey())).append(',').append(token(entry.getValue()));
            }
            if (activeWorld != null) {
                StrategicSummarySnapshot strategic = StrategicSummaryService.capture(activeWorld, owner.ownerId());
                out.append("|E,").append(StrategicSummaryWire.encodeToken(strategic));
                for (String row : GalaxyEventWire.encodeRows(activeWorld, owner.ownerId())) out.append('|').append(row);
            }
        }
        return out.toString();
    }

    static Decoded decode(String message) {
        if (message == null || !message.startsWith(PREFIX)) throw new SnapshotDecodeException("Malformed galaxy state packet.");
        String[] parts = message.split("\\|", -1);
        if (parts.length < 3) throw new SnapshotDecodeException("Malformed galaxy state header.");
        int copies = parseInt(parts[1], 1, 2, "galaxy copies");
        String activeSystemId = text(parts[2]);
        List<GalaxyMapSystem> systems = new ArrayList<>();
        List<GalaxyMapLink> links = new ArrayList<>();
        List<GalaxyEventView> eventViews = new ArrayList<>();
        Map<String,String> ownerUnits = new LinkedHashMap<>();
        String ownerId = "";
        boolean ownerMarker = false;
        StrategicSummarySnapshot strategic = null;
        boolean strategicMarker = false;
        for (int i = 3; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("S,")) systems.add(system(part));
            else if (part.startsWith("L,")) links.add(link(part));
            else if (part.startsWith("O,")) {
                if (ownerMarker) throw new SnapshotDecodeException("Duplicate owner fleet galaxy marker.");
                ownerId = ownerMarker(part);
                ownerMarker = true;
            } else if (part.startsWith("F,")) ownerFleet(part, ownerUnits);
            else if (part.startsWith("E,")) {
                if (strategicMarker) throw new SnapshotDecodeException("Duplicate strategic empire summary.");
                String[] fields = part.split(",", -1);
                if (fields.length != 2) throw new SnapshotDecodeException("Malformed strategic empire summary row.");
                strategic = StrategicSummaryWire.decodeToken(fields[1]);
                strategicMarker = true;
            } else if (part.startsWith("V,")) {
                if (eventViews.size() >= GalaxyEventWire.MAX_EVENT_VIEWS) {
                    throw new SnapshotDecodeException("Galaxy event projection exceeds safe limits.");
                }
                eventViews.add(GalaxyEventWire.decodeRow(part));
            } else if (!part.isBlank()) throw new SnapshotDecodeException("Malformed galaxy state row.");
        }
        if (systems.size() > 96 || links.size() > 256 || ownerUnits.size() > MAX_OWNER_UNITS) {
            throw new SnapshotDecodeException("Galaxy state exceeds safe limits.");
        }
        if (!ownerUnits.isEmpty() && !ownerMarker) {
            throw new SnapshotDecodeException("Owner fleet galaxy rows are missing their owner marker.");
        }
        if (!eventViews.isEmpty() && !ownerMarker) {
            throw new SnapshotDecodeException("Galaxy event rows are missing their owner marker.");
        }
        if (ownerMarker) validateOwnerRows(ownerId, ownerUnits, true);
        if (strategic != null) {
            if (!ownerMarker || !ownerId.equals(strategic.ownerId())) {
                throw new SnapshotDecodeException("Strategic empire summary does not match the owner projection.");
            }
            World activeWorld = PlayerRegistry.activeWorld();
            if (activeWorld != null) StrategicSummaryRegistry.replace(activeWorld, strategic);
        }
        World activeWorld = PlayerRegistry.activeWorld();
        if (activeWorld != null && ownerMarker) GalaxyEventDirector.replaceRemoteViews(activeWorld, eventViews);
        GalaxyMapSnapshot snapshot = new GalaxyMapSnapshot(activeSystemId, List.copyOf(systems), List.copyOf(links));
        OwnerProjection owner = ownerMarker
                ? new OwnerProjection(true, ownerId, Map.copyOf(ownerUnits))
                : OwnerProjection.ABSENT;
        return new Decoded(copies, snapshot, owner, strategic);
    }

    static GalaxyMapSnapshot withActive(GalaxyMapSnapshot snapshot, String activeSystemId) {
        if (snapshot == null || snapshot.empty()) return snapshot;
        List<GalaxyMapSystem> systems = new ArrayList<>();
        for (GalaxyMapSystem system : snapshot.systems()) {
            systems.add(new GalaxyMapSystem(system.id(), system.name(), system.templateId(), system.lifetime(),
                    system.ships(), system.bases(), system.resources(), system.localShips(), system.localBases(),
                    system.id().equals(activeSystemId), system.home(), system.special(), system.controllerId(),
                    system.controllerName(), system.controlStatus(), system.captureProgress(), system.controlColorRgb()));
        }
        return new GalaxyMapSnapshot(activeSystemId, List.copyOf(systems), snapshot.links());
    }

    private static OwnerProjection ownerProjection(String ownerId, Map<String,String> locations) {
        String owner = clean(ownerId);
        if (owner.isBlank() || owner.length() > MAX_TEXT) {
            throw new IllegalArgumentException("Owner fleet galaxy projection has an invalid owner identity.");
        }
        Map<String,String> safe = locations == null ? Map.of() : Map.copyOf(locations);
        if (safe.size() > MAX_OWNER_UNITS) {
            throw new IllegalArgumentException("Owner fleet galaxy projection exceeds safe limits.");
        }
        validateOwnerRows(owner, safe, false);
        return new OwnerProjection(true, owner, safe);
    }

    private static void validateOwnerRows(String ownerId, Map<String,String> locations, boolean decoding) {
        String prefix = ownerId + ":";
        for (Map.Entry<String,String> entry : locations.entrySet()) {
            String unitKey = clean(entry.getKey());
            String systemId = clean(entry.getValue());
            boolean invalid = unitKey.isBlank() || systemId.isBlank()
                    || unitKey.length() > MAX_TEXT || systemId.length() > MAX_TEXT
                    || !unitKey.startsWith(prefix);
            if (!invalid) continue;
            if (decoding) throw new SnapshotDecodeException("Owner fleet galaxy projection contains a foreign or invalid unit.");
            throw new IllegalArgumentException("Owner fleet galaxy projection contains a foreign or invalid unit.");
        }
    }

    private static String inferOwnerId(Map<String,String> locations) {
        if (locations == null || locations.isEmpty()) return "";
        String owner = "";
        for (String key : locations.keySet()) {
            if (key == null) return "";
            int separator = key.indexOf(':');
            if (separator <= 0) return "";
            String candidate = clean(key.substring(0, separator));
            if (owner.isBlank()) owner = candidate;
            else if (!owner.equals(candidate)) return "";
        }
        return owner;
    }

    private static GalaxyMapSystem system(String row) {
        String[] f = row.split(",", -1);
        if (f.length != 18) throw new SnapshotDecodeException("Malformed galaxy system row.");
        String id = text(f[1]);
        String name = text(f[2]);
        String templateId = text(f[3]);
        if (id.isBlank() || id.length() > MAX_TEXT || name.length() > MAX_TEXT || templateId.length() > MAX_TEXT) {
            throw new SnapshotDecodeException("Malformed galaxy system identity.");
        }
        SystemLifetime lifetime = enumValue(SystemLifetime.class, f[4], "system lifetime");
        int ships = parseInt(f[5], 0, 100_000, "ship count");
        int bases = parseInt(f[6], 0, 100_000, "base count");
        int resources = parseInt(f[7], 0, 1_000_000, "resource count");
        int localShips = parseInt(f[8], 0, 100_000, "local ship count");
        int localBases = parseInt(f[9], 0, 100_000, "local base count");
        boolean active = flag(f[10]);
        boolean home = flag(f[11]);
        boolean special = flag(f[12]);
        String controllerId = text(f[13]);
        String controllerName = text(f[14]);
        SystemControlStatus status = enumValue(SystemControlStatus.class, f[15], "control status");
        double progress = parseDouble(f[16], 0, 1, "capture progress");
        int rgb = parseInt(f[17], 0, 0xFFFFFF, "control color");
        return new GalaxyMapSystem(id, name, templateId, lifetime, ships, bases, resources, localShips, localBases,
                active, home, special, controllerId, controllerName, status, progress, rgb);
    }

    private static GalaxyMapLink link(String row) {
        String[] f = row.split(",", -1);
        if (f.length != 3) throw new SnapshotDecodeException("Malformed galaxy link row.");
        String from = text(f[1]);
        String to = text(f[2]);
        if (from.isBlank() || to.isBlank() || from.length() > MAX_TEXT || to.length() > MAX_TEXT || from.equals(to)) {
            throw new SnapshotDecodeException("Malformed galaxy link identity.");
        }
        return new GalaxyMapLink(from, to);
    }

    private static String ownerMarker(String row) {
        String[] f = row.split(",", -1);
        if (f.length != 2) throw new SnapshotDecodeException("Malformed owner fleet galaxy marker.");
        String ownerId = text(f[1]);
        if (ownerId.isBlank() || ownerId.length() > MAX_TEXT) {
            throw new SnapshotDecodeException("Malformed owner fleet galaxy owner identity.");
        }
        return ownerId;
    }

    private static void ownerFleet(String row, Map<String,String> ownerUnits) {
        String[] f = row.split(",", -1);
        if (f.length != 3) throw new SnapshotDecodeException("Malformed owner fleet galaxy row.");
        String unitKey = text(f[1]);
        String systemId = text(f[2]);
        if (unitKey.isBlank() || systemId.isBlank() || unitKey.length() > MAX_TEXT || systemId.length() > MAX_TEXT) {
            throw new SnapshotDecodeException("Malformed owner fleet galaxy identity.");
        }
        if (ownerUnits.putIfAbsent(unitKey, systemId) != null) {
            throw new SnapshotDecodeException("Duplicate owner fleet galaxy unit key.");
        }
        if (ownerUnits.size() > MAX_OWNER_UNITS) throw new SnapshotDecodeException("Owner fleet galaxy projection exceeds safe limits.");
    }

    private static String token(String value) {
        String safe = clean(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String token) {
        try {
            if (token == null || token.isBlank()) return "";
            return clean(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            throw new SnapshotDecodeException("Malformed galaxy text token.");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static int parseInt(String value, int min, int max, String label) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new SnapshotDecodeException("Malformed " + label + ".");
        }
    }

    private static double parseDouble(String value, double min, double max, String label) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new SnapshotDecodeException("Malformed " + label + ".");
        }
    }

    private static boolean flag(String value) {
        if ("1".equals(value)) return true;
        if ("0".equals(value)) return false;
        throw new SnapshotDecodeException("Malformed galaxy flag.");
    }

    private static String flag(boolean value) { return value ? "1" : "0"; }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String label) {
        try { return Enum.valueOf(type, value); }
        catch (RuntimeException ex) { throw new SnapshotDecodeException("Malformed " + label + "."); }
    }

    record OwnerProjection(boolean present, String ownerId, Map<String,String> locations) {
        static final OwnerProjection ABSENT = new OwnerProjection(false, "", Map.of());
        OwnerProjection {
            ownerId = ownerId == null ? "" : ownerId;
            locations = locations == null ? Map.of() : Map.copyOf(locations);
        }
    }

    record Decoded(int copiesPerTemplate, GalaxyMapSnapshot snapshot, OwnerProjection ownerProjection,
                   StrategicSummarySnapshot strategicSummary) {
        Map<String,String> ownerUnitLocations() { return ownerProjection.locations(); }
    }
}
