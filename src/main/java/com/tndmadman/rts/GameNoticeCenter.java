package com.tndmadman.rts;

import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class GameNoticeCenter {
    private static final int MAX_PENDING_PER_PLAYER = 64;
    private static final long DUPLICATE_WINDOW_MS = 4_000;
    private static final Map<World, State> STATES = new WeakHashMap<>();

    private GameNoticeCenter() { }

    static synchronized void publish(World world, String playerId, NoticeCategory category,
                                     String text, boolean narrate) {
        if (world == null || playerId == null || playerId.isBlank() || text == null || text.isBlank()) return;
        String cleanText = cleanText(text);
        NoticeCategory safeCategory = category == null ? NoticeCategory.SYSTEM : category;
        State state = STATES.computeIfAbsent(world, ignored -> new State());
        long now = System.currentTimeMillis();
        pruneDuplicateState(state, now);
        String duplicateKey = playerId + "|" + safeCategory + "|" + cleanText;
        Long last = state.lastPublished.get(duplicateKey);
        if (last != null && now - last < DUPLICATE_WINDOW_MS) return;
        state.lastPublished.put(duplicateKey, now);

        GameNotice notice = new GameNotice(state.nextId++, safeCategory, cleanText, narrate);
        if (PlayerRegistry.isLocal(playerId) && !GraphicsEnvironment.isHeadless()) {
            deliverLocal(world, notice);
            return;
        }

        Deque<GameNotice> queue = state.pending.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        queue.addLast(notice);
        while (queue.size() > MAX_PENDING_PER_PLAYER) queue.removeFirst();
    }

    static synchronized List<GameNotice> drain(World world, String playerId) {
        State state = STATES.get(world);
        if (state == null || playerId == null || playerId.isBlank()) return List.of();
        long now = System.currentTimeMillis();
        pruneDuplicateState(state, now);
        Deque<GameNotice> queue = state.pending.get(playerId);
        if (queue == null || queue.isEmpty()) {
            removeIfEmpty(world, state);
            return List.of();
        }
        List<GameNotice> out = new ArrayList<>(queue);
        queue.clear();
        state.pending.remove(playerId);
        removeIfEmpty(world, state);
        return List.copyOf(out);
    }

    static synchronized void clear(World world) {
        if (world != null) STATES.remove(world);
    }

    static synchronized boolean containsWorldForTest(World world) {
        return world != null && STATES.containsKey(world);
    }

    static synchronized boolean usesWeakKeysForTest() {
        return STATES instanceof WeakHashMap;
    }

    static boolean acceptRemote(World world, String packet) {
        GameNotice notice = GameNotice.fromPacket(packet);
        if (notice == null || world == null) return false;
        deliverLocal(world, notice);
        return true;
    }

    private static void deliverLocal(World world, GameNotice notice) {
        AlertCenter.push(world, notice.text());
        if (notice.narrate()) NarrationService.speak(notice.text());
    }

    private static void pruneDuplicateState(State state, long now) {
        state.lastPublished.entrySet().removeIf(entry -> now - entry.getValue() >= DUPLICATE_WINDOW_MS);
    }

    private static void removeIfEmpty(World world, State state) {
        if (state.pending.isEmpty() && state.lastPublished.isEmpty()) STATES.remove(world);
    }

    private static String cleanText(String text) {
        String clean = text.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 497) + "...";
    }

    private static final class State {
        long nextId = 1;
        final Map<String, Deque<GameNotice>> pending = new LinkedHashMap<>();
        final Map<String, Long> lastPublished = new LinkedHashMap<>();
    }
}

enum NoticeCategory {
    PRODUCTION,
    LOGISTICS,
    SHORTAGE,
    WARNING,
    SYSTEM
}

record GameNotice(long id, NoticeCategory category, String text, boolean narrate) {
    String packet() {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
        return "NOTICE|" + id + "|" + category.name() + "|" + (narrate ? "1" : "0") + "|" + encoded;
    }

    static GameNotice fromPacket(String packet) {
        if (packet == null || !packet.startsWith("NOTICE|")) return null;
        String[] parts = packet.split("\\|", 5);
        if (parts.length < 5) return null;
        try {
            long id = Math.max(0, Long.parseLong(parts[1]));
            NoticeCategory category = NoticeCategory.valueOf(parts[2]);
            boolean narrate = "1".equals(parts[3]);
            String text = new String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
            if (text.isBlank() || text.length() > 500) return null;
            return new GameNotice(id, category, text, narrate);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
