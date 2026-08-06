package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Binds authoritative fog persistence to the validated dedicated-server lifecycle. */
final class ServerFogOfWarPersistence {
    private static final Map<World, Attachment> ATTACHMENTS = new WeakHashMap<>();

    private ServerFogOfWarPersistence() { }

    static synchronized void attach(World world, Config config) {
        if (world == null || config == null || !config.dedicatedServerMode()) return;
        close(world);
        ServerFogOfWarStore store = ServerFogOfWarStore.forConfig(config);
        migrateGenerations(world, store);
        ServerFogOfWarState.configureForTest(world, store);
        Attachment attachment = new Attachment(world);
        attachment.shutdownHook = new Thread(attachment::flushSafely, "starchem-server-fog-shutdown");
        Runtime.getRuntime().addShutdownHook(attachment.shutdownHook);
        ATTACHMENTS.put(world, attachment);
    }

    static void flushNow(World world) {
        if (world == null) return;
        ServerFogOfWarState.flushForTest(world);
    }

    static synchronized void close(World world) {
        Attachment attachment = ATTACHMENTS.remove(world);
        if (attachment != null) attachment.close();
    }

    private static void migrateGenerations(World world, ServerFogOfWarStore store) {
        if (world == null || store == null || !store.enabled()) return;
        List<ServerFogOfWarState.Stored> loaded = store.load();
        if (loaded.isEmpty()) return;
        boolean changed = false;
        List<ServerFogOfWarState.Stored> migrated = new ArrayList<>(loaded.size());
        for (ServerFogOfWarState.Stored stored : loaded) {
            if (stored == null) continue;
            long generation = ClientEnvironmentSeed.forSystem(world.systemSeed(), stored.systemId());
            if (generation != stored.generation()) changed = true;
            migrated.add(new ServerFogOfWarState.Stored(
                    stored.playerId(),
                    stored.systemId(),
                    generation,
                    stored.columns(),
                    stored.rows(),
                    stored.revision(),
                    stored.explored(),
                    stored.wormholes()));
        }
        if (changed) store.save(migrated);
    }

    private static final class Attachment {
        private final World world;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Thread shutdownHook;

        private Attachment(World world) {
            this.world = world;
        }

        private void flushSafely() {
            if (closed.get()) return;
            try {
                flushNow(world);
            } catch (RuntimeException ex) {
                System.err.println("Could not flush server fog state: " + ex.getMessage());
            }
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                ServerFogOfWarState.flushForTest(world);
            } catch (RuntimeException ex) {
                System.err.println("Could not flush server fog state during shutdown: " + ex.getMessage());
            } finally {
                ServerFogOfWarState.configureForTest(world, ServerFogOfWarStore.disabled());
            }
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already running; the hook is executing or has executed.
                }
            }
        }
    }
}
