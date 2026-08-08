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
    private static final int MAX_OWNER_UNITS = 1000;
    private static final int MAX_TEXT = 128;

    private GalaxyMapWire() { }

    static String encode(int copiesPerTemplate, GalaxyMapSnapshot snapshot) {
        return encode(copiesPerTemplate, snapshot, Map.of());
    }

    static String encode(int copiesPerTemplate, GalaxyMapSnapshot snapshot, Map<String,String> ownerUnitLocations) {
        if (snapshot == null) snapshot = new GalaxyMapSnapshot("", List.of(), List.of());
        StringBuilder out = new StringBuilder(PREFIX)
                .append(Math.max(1, Math.min(2, copiesPerTemplate)))
                .append('|').append(token(snapshot.activeSystemId()));
        if (snapshot.systems() != null) {
            for (GalaxyMapSystem system : snapshot.systems()) {
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
        }
        if (snapshot.links() != null) {
            for (GalaxyMapLink link : snapshot.links()) {
                if (link == null) continue;
                out.append("|L,").append(token(link.fromSystemId())).append(',').append(token(link.toSystemId()));
            }
        }
        TreeMap<String,String> sortedOwnerUnits = new TreeMap<>();
        if (ownerUnitLocations != null) sortedOwnerUnits.putAll(ownerUnitLocations);
        if (sortedOwnerUnits.size() > MAX_OWNER_UNITS) {
            throw new IllegalArgumentException("Owner fleet galaxy projection exceeds safe limits.");
        }
        for (Map.Entry<String,String> entry : sortedOwnerUnits.entrySet()) {
            String unitKey = clean(entry.getKey());
            String systemId = clean(entry.getValue());
            if (unitKey.isBlank() || systemId.isBlank()) continue;
            if (unitKey.length() > MAX_TEXT || systemId.length() > MAX_TEXT) {
                throw new IllegalArgumentException("Owner fleet galaxy projection contains an oversized identity.");
            }
            out.append("|F,").append(token(unitKey)).append(',').append(token(systemId));
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
        Map<String,String> ownerUnits = new LinkedHashMap<>();
        for (int i = 3; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("S,")) systems.add(system(part));
            else if (part.startsWith("L,")) links.add(link(part));
            else if (part.startsWith("F,")) ownerFleet(part, ownerUnits);
            else if (!part.isBlank()) throw new SnapshotDecodeException("Malformed galaxy state row.");
        }
        if (systems.size() > 96 || links.size() > 256 || ownerUnits.size() > MAX_OWNER_UNITS) {
            throw new SnapshotDecodeException("Galaxy state exceeds safe limits.");
        }
        return new Decoded(copies,
                new GalaxyMapSnapshot(activeSystemId, List.copyOf(systems), List.copyOf(links)),
                Map.copyOf(ownerUnits));
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

    record Decoded(int copiesPerTemplate, GalaxyMapSnapshot snapshot, Map<String,String> ownerUnitLocations) { }
}
