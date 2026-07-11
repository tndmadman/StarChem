package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WanderingWormholePlanner {
    private static final int MINIMUM_RING_SEPARATION = 3;
    private static final long SEED_SALT = 0x57414E4445524C4FL;

    private WanderingWormholePlanner() { }

    static List<GalaxyLinkSpec> add(List<GalaxyInstanceSpec> systems, List<GalaxyLinkSpec> fixedLinks,
                                    int requestedPairs, long galaxySeed) {
        List<GalaxyLinkSpec> result = new ArrayList<>(fixedLinks == null ? List.of() : fixedLinks);
        if (requestedPairs <= 0 || systems == null || systems.size() < 4) return List.copyOf(result);

        Set<String> linked = new LinkedHashSet<>();
        Map<String,Integer> degree = new LinkedHashMap<>();
        for (GalaxyInstanceSpec system : systems) degree.put(system.id(), 0);
        for (GalaxyLinkSpec link : result) {
            linked.add(key(link.fromSystemId(), link.toSystemId()));
            degree.computeIfPresent(link.fromSystemId(), (ignored, value) -> value + 1);
            degree.computeIfPresent(link.toSystemId(), (ignored, value) -> value + 1);
        }

        List<Candidate> candidates = candidates(systems, linked, galaxySeed);
        for (int added = 0; added < requestedPairs; added++) {
            Candidate best = candidates.stream().min(candidateOrder(degree)).orElse(null);
            if (best == null) {
                throw new IllegalArgumentException("wanderingWormholePairs requested " + requestedPairs
                        + ", but only " + added + " valid additional pairs are available.");
            }
            result.add(new GalaxyLinkSpec(best.from, best.to));
            linked.add(key(best.from, best.to));
            degree.put(best.from, degree.getOrDefault(best.from, 0) + 1);
            degree.put(best.to, degree.getOrDefault(best.to, 0) + 1);
            candidates.remove(best);
        }
        return List.copyOf(result);
    }

    private static List<Candidate> candidates(List<GalaxyInstanceSpec> systems, Set<String> linked, long seed) {
        List<Candidate> out = new ArrayList<>();
        int count = systems.size();
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                String from = systems.get(i).id();
                String to = systems.get(j).id();
                int separation = Math.min(j - i, count - (j - i));
                if (separation < MINIMUM_RING_SEPARATION || linked.contains(key(from, to))) continue;
                out.add(new Candidate(from, to, separation, mix(seed ^ SEED_SALT ^ pairHash(from, to))));
            }
        }
        return out;
    }

    private static Comparator<Candidate> candidateOrder(Map<String,Integer> degree) {
        return Comparator
                .comparingInt((Candidate candidate) -> Math.max(degree.getOrDefault(candidate.from, 0), degree.getOrDefault(candidate.to, 0)))
                .thenComparingInt(candidate -> degree.getOrDefault(candidate.from, 0) + degree.getOrDefault(candidate.to, 0))
                .thenComparing(Comparator.comparingInt((Candidate candidate) -> candidate.separation).reversed())
                .thenComparing((Candidate a, Candidate b) -> Long.compareUnsigned(a.tieBreak, b.tieBreak))
                .thenComparing(candidate -> candidate.from)
                .thenComparing(candidate -> candidate.to);
    }

    private static String key(String from, String to) {
        return from.compareTo(to) <= 0 ? from + "->" + to : to + "->" + from;
    }

    private static long pairHash(String from, String to) {
        String a = from.compareTo(to) <= 0 ? from : to;
        String b = from.compareTo(to) <= 0 ? to : from;
        return ((long)a.hashCode() << 32) ^ (b.hashCode() & 0xFFFFFFFFL);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ value >>> 33;
    }

    private record Candidate(String from, String to, int separation, long tieBreak) { }
}
