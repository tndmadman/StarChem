package com.tndmadman.rts;

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
        ServerFogOfWarState.configureForTest(world, ServerFogOfWarStore.forConfig(config));
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