package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Tracks one organized-NPC budget frame across the active-system traversal that
 * makes up an authoritative world update. A frame advances when simulation time
 * advances in the same system, or when traversal cycles back to a system that
 * was already visited. Switching to a not-yet-visited system stays in the same
 * frame so galaxy-wide plans can be reused instead of rebuilt per system.
 */
final class NpcBudgetFrameTracker {
    private static final Map<World, Map<String, FrameState>> STATES = new WeakHashMap<>();

    private NpcBudgetFrameTracker() { }

    static synchronized long observe(World world, NpcFaction faction, long localFingerprint) {
        if (world == null || faction == null) return 0;
        Map<String, FrameState> byFaction = STATES.computeIfAbsent(
                world, ignored -> new LinkedHashMap<>());
        String systemId = normalizedSystemId(world);
        long timeBits = Double.doubleToLongBits(world.systemTime());
        FrameState state = byFaction.get(faction.id());
        if (state == null) {
            state = new FrameState(1L, systemId, timeBits, localFingerprint);
            byFaction.put(faction.id(), state);
            return state.revision;
        }

        if (systemId.equals(state.currentSystemId)) {
            if (timeBits != state.currentTimeBits || localFingerprint != state.currentFingerprint) {
                state.reset(nextRevision(state.revision), systemId, timeBits, localFingerprint);
            }
            return state.revision;
        }

        if (state.visitedSystemIds.contains(systemId)) {
            state.reset(nextRevision(state.revision), systemId, timeBits, localFingerprint);
        } else {
            state.visit(systemId, timeBits, localFingerprint);
        }
        return state.revision;
    }

    static synchronized long acceptMutation(World world, NpcFaction faction, long localFingerprint) {
        if (world == null || faction == null) return 0;
        Map<String, FrameState> byFaction = STATES.computeIfAbsent(
                world, ignored -> new LinkedHashMap<>());
        String systemId = normalizedSystemId(world);
        long timeBits = Double.doubleToLongBits(world.systemTime());
        FrameState state = byFaction.get(faction.id());
        if (state == null) {
            state = new FrameState(1L, systemId, timeBits, localFingerprint);
            byFaction.put(faction.id(), state);
        } else {
            state.visit(systemId, timeBits, localFingerprint);
        }
        return state.revision;
    }

    static synchronized void invalidate(World world, NpcFaction faction) {
        if (world == null) return;
        if (faction == null) {
            STATES.remove(world);
            return;
        }
        Map<String, FrameState> byFaction = STATES.get(world);
        if (byFaction == null) return;
        byFaction.remove(faction.id());
        if (byFaction.isEmpty()) STATES.remove(world);
    }

    private static String normalizedSystemId(World world) {
        String systemId = world.activeSystemId();
        if (systemId == null || systemId.isBlank()) systemId = world.systemId();
        return systemId == null ? "" : systemId;
    }

    private static long nextRevision(long revision) {
        return revision == Long.MAX_VALUE ? 1L : revision + 1L;
    }

    private static final class FrameState {
        long revision;
        String currentSystemId;
        long currentTimeBits;
        long currentFingerprint;
        final Set<String> visitedSystemIds = new LinkedHashSet<>();

        FrameState(long revision, String systemId, long timeBits, long fingerprint) {
            reset(revision, systemId, timeBits, fingerprint);
        }

        void reset(long nextRevision, String systemId, long timeBits, long fingerprint) {
            revision = nextRevision;
            visitedSystemIds.clear();
            visit(systemId, timeBits, fingerprint);
        }

        void visit(String systemId, long timeBits, long fingerprint) {
            currentSystemId = systemId;
            currentTimeBits = timeBits;
            currentFingerprint = fingerprint;
            visitedSystemIds.add(systemId);
        }
    }
}
