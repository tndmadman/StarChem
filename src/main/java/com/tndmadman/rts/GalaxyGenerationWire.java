package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

final class GalaxyGenerationWire {
    private static final int VERSION = 1;
    private static final int MAX_TOKEN_LENGTH = 8192;

    private GalaxyGenerationWire() { }

    static String encode(GalaxyGenerationSettings settings, long generationSeed, long worldSeed) {
        if (settings == null || !settings.procedural()) {
            throw new IllegalArgumentException("Procedural galaxy settings are required for the generation wire descriptor.");
        }
        StringBuilder weights = new StringBuilder();
        for (Map.Entry<String,Double> entry : new TreeMap<>(settings.templateWeights()).entrySet()) {
            if (weights.length() > 0) weights.append(';');
            weights.append(entry.getKey()).append('=').append(entry.getValue());
        }
        String raw = VERSION + "," + generationSeed + "," + worldSeed + ","
                + settings.targetSystemCount() + "," + settings.topologyStyle().id() + ","
                + settings.permanentConnectivityDensity() + "," + settings.frontierFrequency() + ","
                + settings.resourceRichness() + "," + settings.rareResourceFrequency() + ","
                + settings.hazardFrequency() + "," + settings.npcDensity() + ","
                + settings.startingSeparation() + "," + weights;
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Procedural galaxy generation descriptor exceeds safe limits.");
        }
        return token;
    }

    static Descriptor decode(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new SnapshotDecodeException("Malformed procedural galaxy generation descriptor.");
        }
        final String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new SnapshotDecodeException("Malformed procedural galaxy generation descriptor.");
        }
        String[] fields = raw.split(",", -1);
        if (fields.length != 13) throw new SnapshotDecodeException("Malformed procedural galaxy generation descriptor.");
        try {
            int version = Integer.parseInt(fields[0]);
            if (version != VERSION) throw new SnapshotDecodeException("Unsupported procedural galaxy generation descriptor version.");
            long generationSeed = Long.parseLong(fields[1]);
            long worldSeed = Long.parseLong(fields[2]);
            int target = Integer.parseInt(fields[3]);
            GalaxyTopologyStyle topology = GalaxyTopologyStyle.parse(fields[4]);
            double connectivity = finite(fields[5]);
            double frontier = finite(fields[6]);
            double richness = finite(fields[7]);
            double rare = finite(fields[8]);
            double hazard = finite(fields[9]);
            double npc = finite(fields[10]);
            int separation = Integer.parseInt(fields[11]);
            Map<String,Double> weights = parseWeights(fields[12]);
            GalaxyGenerationSettings settings = new GalaxyGenerationSettings(true, target, topology,
                    connectivity, frontier, richness, rare, hazard, npc, separation, weights);
            return new Descriptor(settings, generationSeed, worldSeed);
        } catch (SnapshotDecodeException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new SnapshotDecodeException("Malformed procedural galaxy generation descriptor.");
        }
    }

    private static double finite(String value) {
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) throw new NumberFormatException();
        return parsed;
    }

    private static Map<String,Double> parseWeights(String value) {
        if (value == null || value.isBlank()) return Map.of();
        Map<String,Double> out = new LinkedHashMap<>();
        for (String row : value.split(";")) {
            int separator = row.indexOf('=');
            if (separator <= 0 || separator == row.length() - 1) {
                throw new SnapshotDecodeException("Malformed procedural galaxy template weights.");
            }
            String id = row.substring(0, separator).trim();
            double weight = finite(row.substring(separator + 1));
            if (out.putIfAbsent(id, weight) != null) {
                throw new SnapshotDecodeException("Duplicate procedural galaxy template weight.");
            }
        }
        return Map.copyOf(out);
    }

    record Descriptor(GalaxyGenerationSettings settings, long generationSeed, long worldSeed) { }
}
