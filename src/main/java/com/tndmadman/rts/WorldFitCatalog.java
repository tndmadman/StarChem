package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** World-scoped authoritative runtime definitions and server-published fit metadata. */
final class WorldFitCatalog {
    static final int MAX_PUBLISHED_PER_PLAYER = 50;
    static final int MAX_PUBLISHED_TOTAL = 500;
    private static final Map<World,State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private WorldFitCatalog() { }

    static ShipLoadoutDefinition registerRuntime(World world, String name, ShipFitSpec spec) {
        if (world == null) throw new IllegalArgumentException("World is required.");
        ShipLoadoutDefinition definition = PlayerFitRules.register(name, spec);
        State state = state(world);
        ShipFitSpec existing = state.runtime.get(definition.id());
        if (existing != null && !existing.equals(spec)) throw new IllegalArgumentException("Runtime fit ID conflict.");
        if (existing == null) {
            state.runtime.put(definition.id(), spec);
            state.revision++;
        }
        return definition;
    }

    static PublishedFit publish(World world, String ownerId, String ownerName, String name, ShipFitSpec spec) {
        if (world == null || ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("Fit owner is required.");
        State state = state(world);
        if (state.published.size() >= MAX_PUBLISHED_TOTAL) throw new IllegalStateException("The server fit catalog is full.");
        long owned = state.published.values().stream().filter(fit -> ownerId.equals(fit.ownerPlayerId())).count();
        if (owned >= MAX_PUBLISHED_PER_PLAYER) throw new IllegalStateException("You have reached the server fit publication limit.");
        ShipLoadoutDefinition definition = registerRuntime(world, name, spec);
        String displayName = PlayerFitRules.cleanName(name);
        if (displayName.isBlank()) displayName = definition.displayName();
        String id;
        do id = "pub_" + Long.toUnsignedString(state.nextPublishedId++, 36);
        while (state.published.containsKey(id));
        PublishedFit published = new PublishedFit(id, ownerId, Config.clean(ownerName), displayName,
                definition.id(), spec, System.currentTimeMillis(), System.currentTimeMillis());
        state.published.put(id, published);
        state.revision++;
        return published;
    }

    static boolean unpublish(World world, String ownerId, String publishedId) {
        State state = state(world);
        PublishedFit fit = state.published.get(publishedId);
        if (fit == null || !fit.ownerPlayerId().equals(ownerId)) return false;
        state.published.remove(publishedId);
        state.revision++;
        return true;
    }

    static PublishedFit published(World world, String id) { return state(world).published.get(id); }
    static List<PublishedFit> published(World world) { return List.copyOf(state(world).published.values()); }
    static long revision(World world) { return state(world).revision; }

    static Map<String,Object> networkView(World world) {
        State state = state(world);
        Map<String,Object> root = new LinkedHashMap<>();
        root.put("revision", state.revision);
        root.put("definitions", definitionRows(state));
        List<Object> published = new ArrayList<>();
        for (PublishedFit fit : state.published.values()) published.add(fit.toMap());
        root.put("published", published);
        return root;
    }

    static void applyNetworkView(World world, Object saved) {
        if (world == null) return;
        Map<String,Object> root = ServerSaveStore.object(saved);
        State state = state(world);
        state.runtime.clear();
        state.published.clear();
        restoreDefinitions(state, root.get("definitions"));
        for (Object item : ServerSaveStore.list(root.get("published"))) {
            PublishedFit fit = PublishedFit.from(item);
            if (!fit.valid()) continue;
            try {
                ShipLoadoutDefinition definition = PlayerFitRules.register(fit.name(), fit.spec());
                if (!definition.id().equals(fit.runtimeFitId())) continue;
                state.runtime.put(definition.id(), fit.spec());
                state.published.put(fit.id(), fit);
            } catch (RuntimeException ignored) { }
        }
        state.revision = Math.max(0, ServerSaveStore.longValue(root, "revision", 0));
    }

    static Map<String,Object> capture(World world) {
        State state = state(world);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("version", 1);
        out.put("revision", state.revision);
        out.put("nextPublishedId", state.nextPublishedId);
        out.put("definitions", definitionRows(state));
        List<Object> published = new ArrayList<>();
        for (PublishedFit fit : state.published.values()) published.add(fit.toMap());
        out.put("published", published);
        return out;
    }

    static void restore(World world, Object saved) {
        if (world == null) return;
        Map<String,Object> root = ServerSaveStore.object(saved);
        State state = state(world);
        state.runtime.clear();
        state.published.clear();
        restoreDefinitions(state, root.get("definitions"));
        for (Object item : ServerSaveStore.list(root.get("published"))) {
            PublishedFit fit = PublishedFit.from(item);
            if (!fit.valid()) continue;
            try {
                ShipLoadoutDefinition definition = PlayerFitRules.register(fit.name(), fit.spec());
                if (!definition.id().equals(fit.runtimeFitId())) continue;
                state.runtime.put(definition.id(), fit.spec());
                state.published.put(fit.id(), fit);
            } catch (RuntimeException ignored) { }
        }
        state.revision = Math.max(0, ServerSaveStore.longValue(root, "revision", 0));
        state.nextPublishedId = Math.max(1, ServerSaveStore.longValue(root, "nextPublishedId", 1));
    }

    private static List<Object> definitionRows(State state) {
        List<Object> definitions = new ArrayList<>();
        for (Map.Entry<String,ShipFitSpec> entry : state.runtime.entrySet()) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", entry.getKey());
            row.put("spec", entry.getValue().toMap());
            definitions.add(row);
        }
        return definitions;
    }

    private static void restoreDefinitions(State state, Object saved) {
        for (Object item : ServerSaveStore.list(saved)) {
            Map<String,Object> row = ServerSaveStore.object(item);
            ShipFitSpec spec = ShipFitSpec.from(row.get("spec"));
            try {
                ShipLoadoutDefinition definition = PlayerFitRules.register("Custom Fit", spec);
                String savedId = ServerSaveStore.string(row, "id", definition.id());
                if (definition.id().equals(savedId)) state.runtime.put(definition.id(), spec);
            } catch (RuntimeException ignored) { }
        }
    }

    private static State state(World world) {
        if (world == null) throw new IllegalArgumentException("World is required.");
        return STATES.computeIfAbsent(world, ignored -> new State());
    }

    private static final class State {
        final Map<String,ShipFitSpec> runtime = new LinkedHashMap<>();
        final Map<String,PublishedFit> published = new LinkedHashMap<>();
        long revision;
        long nextPublishedId = 1;
    }
}

record PublishedFit(String id, String ownerPlayerId, String ownerName, String name,
                    String runtimeFitId, ShipFitSpec spec, long createdAt, long updatedAt) {
    PublishedFit {
        id = id == null ? "" : id.trim();
        ownerPlayerId = ownerPlayerId == null ? "" : ownerPlayerId.trim();
        ownerName = Config.clean(ownerName);
        name = PlayerFitRules.cleanName(name);
        runtimeFitId = runtimeFitId == null ? "" : runtimeFitId.trim();
        spec = spec == null ? new ShipFitSpec("", List.of()) : spec;
        createdAt = Math.max(0, createdAt);
        updatedAt = Math.max(createdAt, updatedAt);
    }

    boolean valid() {
        return !id.isBlank() && !ownerPlayerId.isBlank() && !name.isBlank()
                && runtimeFitId.equals(spec.runtimeId()) && PlayerFitRules.validate(spec).valid();
    }

    Map<String,Object> toMap() {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("ownerPlayerId", ownerPlayerId);
        row.put("ownerName", ownerName);
        row.put("name", name);
        row.put("runtimeFitId", runtimeFitId);
        row.put("spec", spec.toMap());
        row.put("createdAt", createdAt);
        row.put("updatedAt", updatedAt);
        return row;
    }

    static PublishedFit from(Object value) {
        Map<String,Object> row = ServerSaveStore.object(value);
        return new PublishedFit(ServerSaveStore.string(row, "id", ""),
                ServerSaveStore.string(row, "ownerPlayerId", ""),
                ServerSaveStore.string(row, "ownerName", ""),
                ServerSaveStore.string(row, "name", ""),
                ServerSaveStore.string(row, "runtimeFitId", ""), ShipFitSpec.from(row.get("spec")),
                ServerSaveStore.longValue(row, "createdAt", 0),
                ServerSaveStore.longValue(row, "updatedAt", 0));
    }
}
