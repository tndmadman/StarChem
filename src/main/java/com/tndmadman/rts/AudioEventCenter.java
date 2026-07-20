package com.tndmadman.rts;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Bridges authoritative simulation audio to the clients currently viewing the
 * system where the event occurred. Player-scoped events can also be delivered
 * regardless of the viewed system for completion feedback.
 */
final class AudioEventCenter {
    private static final int MAX_EVENTS = 512;
    private static final int MAX_EVENTS_PER_DRAIN = 48;
    private static final int MAX_PENDING_REMOTE = 64;
    private static final int MAX_DELAYED_EVENTS = 64;
    private static final int MAX_DELAYED_EVENTS_GLOBAL = 256;
    private static final int MAX_COOLDOWN_KEYS = 128;
    private static final long MAX_EVENT_AGE_MS = 3_000;
    private static final long MAX_DELAYED_LATENESS_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final Map<World, State> STATES = new WeakHashMap<>();
    private static final ScheduledThreadPoolExecutor DELAYED_AUDIO = delayedExecutor();
    private static int delayedEventsGlobal;

    private AudioEventCenter() { }

    static void play(World world, SoundCue cue) {
        play(world, activeSystemId(world), cue);
    }

    static void play(World world, String systemId, SoundCue cue) {
        if (cue == null) return;
        publish(world, systemId, AudioScope.SYSTEM, "", AudioEventKind.CUE, cue.name(), 0);
    }

    static void playForPlayer(World world, String playerId, SoundCue cue) {
        if (cue == null || playerId == null || playerId.isBlank()) return;
        publish(world, activeSystemId(world), AudioScope.PLAYER, playerId, AudioEventKind.CUE, cue.name(), 0);
    }

    static void playForPlayerInSystem(World world, String playerId, SoundCue cue) {
        if (cue == null || playerId == null || playerId.isBlank()) return;
        publish(world, activeSystemId(world), AudioScope.PLAYER_IN_SYSTEM, playerId,
                AudioEventKind.CUE, cue.name(), 0);
    }

    static void playWeaponFire(World world, WeaponType weapon, double distance) {
        if (weapon == null) return;
        publish(world, activeSystemId(world), AudioScope.SYSTEM, "",
                AudioEventKind.WEAPON_FIRE, weapon.id, finite(distance));
    }

    static void playWeaponImpact(World world, WeaponType weapon) {
        if (weapon == null) return;
        publish(world, activeSystemId(world), AudioScope.SYSTEM, "",
                AudioEventKind.WEAPON_IMPACT, weapon.id, 0);
    }

    static void playDestruction(World world, double scale) {
        publish(world, activeSystemId(world), AudioScope.SYSTEM, "",
                AudioEventKind.DESTRUCTION, "", finite(scale));
    }

    static void playResourceDepleted(World world, Material material) {
        publish(world, activeSystemId(world), AudioScope.SYSTEM, "",
                AudioEventKind.RESOURCE_DEPLETED, material == null ? "" : material.name(), 0);
    }

    static boolean claimCooldown(World world, String key, long cooldownNanos) {
        return claimCooldown(world, key, cooldownNanos, System.nanoTime());
    }

    static synchronized boolean claimCooldown(World world, String key, long cooldownNanos, long nowNanos) {
        String normalizedKey = clean(key);
        if (world == null || normalizedKey.isBlank() || cooldownNanos <= 0) return false;
        State state = STATES.computeIfAbsent(world, ignored -> new State());
        Long previous = state.cooldownNanos.get(normalizedKey);
        if (previous != null && nowNanos - previous < cooldownNanos) return false;
        state.cooldownNanos.put(normalizedKey, nowNanos);
        while (state.cooldownNanos.size() > MAX_COOLDOWN_KEYS) {
            Iterator<String> iterator = state.cooldownNanos.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    static boolean scheduleDelayed(World world, String systemId, SoundCue cue, long delayMillis,
                                   String coalesceKey) {
        long delayNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, delayMillis));
        long now = System.nanoTime();
        long due = Long.MAX_VALUE - now < delayNanos ? Long.MAX_VALUE : now + delayNanos;
        return scheduleDelayedInternal(world, systemId, cue, due, coalesceKey, true);
    }

    /** Deterministic validator hook; callers must invoke processDelayed. */
    static boolean scheduleDelayedAt(World world, String systemId, SoundCue cue,
                                     long dueNanos, String coalesceKey) {
        return scheduleDelayedInternal(world, systemId, cue, dueNanos, coalesceKey, false);
    }

    private static synchronized boolean scheduleDelayedInternal(World world, String systemId, SoundCue cue,
                                                                long dueNanos, String coalesceKey,
                                                                boolean scheduleWakeup) {
        String cleanSystemId = clean(systemId);
        String cleanKey = clean(coalesceKey);
        if (world == null || cleanSystemId.isBlank() || cue == null) return false;
        State state = STATES.computeIfAbsent(world, ignored -> new State());
        if (!cleanKey.isBlank()) {
            for (DelayedAudio delayed : state.delayed) {
                if (cleanKey.equals(delayed.coalesceKey())) return false;
            }
        }
        if (state.delayed.size() >= MAX_DELAYED_EVENTS || delayedEventsGlobal >= MAX_DELAYED_EVENTS_GLOBAL) {
            return false;
        }
        DelayedAudio delayed = new DelayedAudio(cleanSystemId, cue, dueNanos, cleanKey);
        state.delayed.addLast(delayed);
        delayedEventsGlobal++;
        if (!scheduleWakeup) return true;
        try {
            scheduleWakeupLocked(world, state);
            return true;
        } catch (RuntimeException ex) {
            state.delayed.removeLastOccurrence(delayed);
            delayedEventsGlobal = Math.max(0, delayedEventsGlobal - 1);
            if (state.delayed.isEmpty() && state.events.isEmpty() && state.pendingRemote.isEmpty()
                    && state.cursorByPlayer.isEmpty() && state.cooldownNanos.isEmpty()) {
                STATES.remove(world);
            }
            return false;
        }
    }

    static void processDelayed(World world) {
        processDelayed(world, System.nanoTime());
    }

    static void processDelayed(World world, long nowNanos) {
        if (world == null) return;
        List<DelayedAudio> ready = new ArrayList<>();
        synchronized (AudioEventCenter.class) {
            State state = STATES.get(world);
            if (state == null || state.delayed.isEmpty()) return;
            Iterator<DelayedAudio> iterator = state.delayed.iterator();
            while (iterator.hasNext()) {
                DelayedAudio delayed = iterator.next();
                if (nowNanos < delayed.dueNanos()) continue;
                iterator.remove();
                delayedEventsGlobal = Math.max(0, delayedEventsGlobal - 1);
                long lateness = nowNanos - delayed.dueNanos();
                if (lateness <= MAX_DELAYED_LATENESS_NANOS) ready.add(delayed);
            }
            if (state.delayed.isEmpty()) cancelWakeupLocked(state);
            else scheduleWakeupLocked(world, state);
        }
        for (DelayedAudio delayed : ready) play(world, delayed.systemId(), delayed.cue());
    }

    static synchronized void discard(World world) {
        if (world == null) return;
        State state = STATES.remove(world);
        if (state == null) return;
        cancelWakeupLocked(state);
        delayedEventsGlobal = Math.max(0, delayedEventsGlobal - state.delayed.size());
        state.delayed.clear();
    }

    static synchronized int delayedCountForTest(World world) {
        State state = world == null ? null : STATES.get(world);
        return state == null ? 0 : state.delayed.size();
    }

    static int maxDelayedForTest() {
        return MAX_DELAYED_EVENTS;
    }

    static synchronized int delayedGlobalCountForTest() {
        return delayedEventsGlobal;
    }

    static List<AudioEvent> drain(World world, String playerId, String viewedSystemId) {
        if (world == null || playerId == null || playerId.isBlank()) return List.of();
        processDelayed(world);
        synchronized (AudioEventCenter.class) {
            State state = STATES.computeIfAbsent(world, ignored -> new State());
            long now = System.currentTimeMillis();
            prune(state, now);
            long latest = state.nextId - 1;
            Long cursor = state.cursorByPlayer.get(playerId);
            if (cursor == null) {
                state.cursorByPlayer.put(playerId, latest);
                return List.of();
            }
            List<AudioEvent> out = new ArrayList<>();
            for (AudioEvent event : state.events) {
                if (event.id() <= cursor || !event.forPlayer(playerId, viewedSystemId)) continue;
                out.add(event);
                if (out.size() >= MAX_EVENTS_PER_DRAIN) break;
            }
            state.cursorByPlayer.put(playerId, latest);
            return List.copyOf(out);
        }
    }

    static boolean acceptRemote(World world, String packet) {
        AudioEvent event = AudioEvent.fromPacket(packet);
        if (world == null || event == null) return false;
        if (canRenderRemote(world, event)) {
            render(event);
            return true;
        }
        if (!SystemAudio.rendered(world)) {
            synchronized (AudioEventCenter.class) {
                State state = STATES.computeIfAbsent(world, ignored -> new State());
                state.pendingRemote.addLast(event);
                while (state.pendingRemote.size() > MAX_PENDING_REMOTE) state.pendingRemote.removeFirst();
            }
        }
        return true;
    }

    static void listenerChanged(World world) {
        if (world == null) return;
        processDelayed(world);
        List<AudioEvent> ready = new ArrayList<>();
        synchronized (AudioEventCenter.class) {
            State state = STATES.get(world);
            if (state == null || state.pendingRemote.isEmpty()) return;
            long now = System.currentTimeMillis();
            state.pendingRemote.removeIf(event -> now - event.createdAt() > MAX_EVENT_AGE_MS);
            var iterator = state.pendingRemote.iterator();
            while (iterator.hasNext()) {
                AudioEvent event = iterator.next();
                if (!canRenderRemote(world, event)) continue;
                ready.add(event);
                iterator.remove();
            }
        }
        for (AudioEvent event : ready) render(event);
    }

    private static synchronized void publish(World world, String systemId, AudioScope scope,
                                             String targetPlayerId, AudioEventKind kind,
                                             String argument, double value) {
        if (world == null || systemId == null || systemId.isBlank() || kind == null) return;
        long now = System.currentTimeMillis();
        State state = STATES.computeIfAbsent(world, ignored -> new State());
        AudioEvent event = new AudioEvent(state.nextId++, now, clean(systemId), scope,
                clean(targetPlayerId), kind, clean(argument), finite(value));

        if (localTarget(event) && canRenderLocal(world, event)) render(event);
        if (!SystemAudio.nonRendered(world)) return;

        state.events.addLast(event);
        prune(state, now);
    }

    private static boolean localTarget(AudioEvent event) {
        return event.targetPlayerId().isBlank() || PlayerRegistry.isLocal(event.targetPlayerId());
    }

    private static boolean canRenderLocal(World world, AudioEvent event) {
        return switch (event.scope()) {
            case PLAYER -> SystemAudio.rendered(world);
            case SYSTEM, PLAYER_IN_SYSTEM -> SystemAudio.audible(world, event.systemId());
        };
    }

    private static boolean canRenderRemote(World world, AudioEvent event) {
        return switch (event.scope()) {
            case PLAYER -> SystemAudio.rendered(world);
            case SYSTEM, PLAYER_IN_SYSTEM -> SystemAudio.audible(world, event.systemId());
        };
    }

    private static void render(AudioEvent event) {
        try {
            switch (event.kind()) {
                case CUE -> ProceduralAudio.play(SoundCue.valueOf(event.argument()));
                case WEAPON_FIRE -> {
                    WeaponType weapon = WeaponRules.WEAPONS.get(event.argument());
                    if (weapon != null) ProceduralAudio.playWeaponFire(weapon, event.value());
                }
                case WEAPON_IMPACT -> {
                    WeaponType weapon = WeaponRules.WEAPONS.get(event.argument());
                    if (weapon != null) ProceduralAudio.playWeaponImpact(weapon);
                }
                case DESTRUCTION -> ProceduralAudio.playDestruction(event.value());
                case RESOURCE_DEPLETED -> {
                    Material material = event.argument().isBlank() ? null : Material.valueOf(event.argument());
                    ProceduralAudio.playResourceDepleted(material);
                }
            }
        } catch (RuntimeException ignored) { }
    }

    private static void scheduleWakeupLocked(World world, State state) {
        long earliest = Long.MAX_VALUE;
        for (DelayedAudio delayed : state.delayed) earliest = Math.min(earliest, delayed.dueNanos());
        if (earliest == Long.MAX_VALUE) {
            cancelWakeupLocked(state);
            return;
        }
        if (state.delayedWakeup != null && !state.delayedWakeup.isDone()
                && state.delayedWakeupNanos <= earliest) return;
        cancelWakeupLocked(state);
        long delayNanos = Math.max(0, earliest - System.nanoTime());
        WeakReference<World> worldRef = new WeakReference<>(world);
        state.delayedWakeupNanos = earliest;
        state.delayedWakeup = DELAYED_AUDIO.schedule(() -> scheduledWakeup(worldRef, state),
                delayNanos, TimeUnit.NANOSECONDS);
    }

    private static void scheduledWakeup(WeakReference<World> worldRef, State expectedState) {
        World world = worldRef.get();
        synchronized (AudioEventCenter.class) {
            if (world == null) {
                cancelWakeupLocked(expectedState);
                delayedEventsGlobal = Math.max(0, delayedEventsGlobal - expectedState.delayed.size());
                expectedState.delayed.clear();
                STATES.size();
                return;
            }
            if (STATES.get(world) != expectedState) return;
            expectedState.delayedWakeup = null;
            expectedState.delayedWakeupNanos = Long.MAX_VALUE;
        }
        processDelayed(world, System.nanoTime());
    }

    private static void cancelWakeupLocked(State state) {
        ScheduledFuture<?> wakeup = state.delayedWakeup;
        state.delayedWakeup = null;
        state.delayedWakeupNanos = Long.MAX_VALUE;
        if (wakeup != null) wakeup.cancel(false);
    }

    private static ScheduledThreadPoolExecutor delayedExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "StarChem Audio Scheduler");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, factory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static void prune(State state, long now) {
        while (!state.events.isEmpty() && now - state.events.peekFirst().createdAt() > MAX_EVENT_AGE_MS) {
            state.events.removeFirst();
        }
        while (state.events.size() > MAX_EVENTS) state.events.removeFirst();
    }

    private static String activeSystemId(World world) {
        return world == null ? "" : clean(world.activeSystemId());
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.replace('|', '_').trim();
        return clean.length() <= 128 ? clean : clean.substring(0, 128);
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0;
    }

    private static final class State {
        long nextId = 1;
        final Deque<AudioEvent> events = new ArrayDeque<>();
        final Deque<AudioEvent> pendingRemote = new ArrayDeque<>();
        final Deque<DelayedAudio> delayed = new ArrayDeque<>();
        final Map<String, Long> cursorByPlayer = new LinkedHashMap<>();
        final Map<String, Long> cooldownNanos = new LinkedHashMap<>();
        ScheduledFuture<?> delayedWakeup;
        long delayedWakeupNanos = Long.MAX_VALUE;
    }

    private record DelayedAudio(String systemId, SoundCue cue, long dueNanos, String coalesceKey) { }
}

enum AudioScope {
    SYSTEM,
    PLAYER,
    PLAYER_IN_SYSTEM
}

enum AudioEventKind {
    CUE,
    WEAPON_FIRE,
    WEAPON_IMPACT,
    DESTRUCTION,
    RESOURCE_DEPLETED
}

record AudioEvent(long id, long createdAt, String systemId, AudioScope scope, String targetPlayerId,
                  AudioEventKind kind, String argument, double value) {
    String packet() {
        return "AUDIO|" + id + "|" + scope.name() + "|" + encode(systemId) + "|"
                + kind.name() + "|" + encode(argument) + "|" + value;
    }

    boolean forPlayer(String playerId, String viewedSystemId) {
        if (!targetPlayerId.isBlank() && !targetPlayerId.equals(playerId)) return false;
        return scope == AudioScope.PLAYER || systemId.equals(viewedSystemId);
    }

    static AudioEvent fromPacket(String packet) {
        if (packet == null || !packet.startsWith("AUDIO|")) return null;
        String[] parts = packet.split("\\|", 7);
        if (parts.length != 7) return null;
        try {
            long id = Math.max(0, Long.parseLong(parts[1]));
            AudioScope scope = AudioScope.valueOf(parts[2]);
            String systemId = decode(parts[3]);
            AudioEventKind kind = AudioEventKind.valueOf(parts[4]);
            String argument = decode(parts[5]);
            double value = Double.parseDouble(parts[6]);
            if (systemId.isBlank() || !Double.isFinite(value)) return null;
            return new AudioEvent(id, System.currentTimeMillis(), systemId, scope, "", kind, argument, value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        byte[] bytes = Base64.getUrlDecoder().decode(value == null ? "" : value);
        String decoded = new String(bytes, StandardCharsets.UTF_8).replace('|', '_').trim();
        return decoded.length() <= 128 ? decoded : decoded.substring(0, 128);
    }
}
