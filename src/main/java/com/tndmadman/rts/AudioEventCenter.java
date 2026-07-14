package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Bridges authoritative simulation audio to the clients currently viewing the
 * system where the event occurred. Player-scoped events can also be delivered
 * regardless of the viewed system for completion feedback.
 */
final class AudioEventCenter {
    private static final int MAX_EVENTS = 512;
    private static final int MAX_EVENTS_PER_DRAIN = 48;
    private static final int MAX_PENDING_REMOTE = 64;
    private static final long MAX_EVENT_AGE_MS = 3_000;
    private static final Map<World, State> STATES = new WeakHashMap<>();

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

    static synchronized List<AudioEvent> drain(World world, String playerId, String viewedSystemId) {
        if (world == null || playerId == null || playerId.isBlank()) return List.of();
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
        final Map<String, Long> cursorByPlayer = new LinkedHashMap<>();
    }
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
