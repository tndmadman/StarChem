package com.tndmadman.rts;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.swing.SwingUtilities;

public final class App {
    private static final long SERVER_TICK_NANOS = 1_000_000_000L / 60;
    private static final double SERVER_TICK_SECONDS = SERVER_TICK_NANOS / 1_000_000_000.0;
    private static final int MAX_SERVER_CATCH_UP_TICKS = 5;
    private static final long SERVER_STATUS_NANOS = 60_000_000_000L;

    private App() { }

    public static void main(String[] args) {
        if (versionRequested(args)) {
            System.out.println(BuildInfo.display());
            return;
        }
        if (helpRequested(args)) {
            printUsage();
            return;
        }
        System.out.println(BuildInfo.display());
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("Invalid startup arguments: " + ex.getMessage());
            System.err.println("Run with --help to view supported options.");
            System.exit(2);
            return;
        }
        if (config.devMode) RulesValidator.validateOrThrow(Path.of("config/starchem.json"));
        ResourceNetDebug.resetLogs(config);
        if (config.dedicatedServerMode()) {
            int exitCode = runServer(config);
            if (exitCode != 0) System.exit(exitCode);
            return;
        }
        SwingUtilities.invokeLater(() -> new GameFrame(config).setVisible(true));
    }

    private static boolean versionRequested(String[] args) {
        return args != null && args.length == 1
                && ("--version".equalsIgnoreCase(args[0]) || "-V".equals(args[0]));
    }

    private static boolean helpRequested(String[] args) {
        if (args == null) return false;
        for (String arg : args) {
            if ("--help".equalsIgnoreCase(arg) || "-h".equals(arg)) return true;
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar StarChem.jar                         Open the graphical lobby");
        System.out.println("  java -jar StarChem.jar --solo [options]        Start a solo game");
        System.out.println("  java -jar StarChem.jar --host PORT [options]   Host with a local client");
        System.out.println("  java -jar StarChem.jar --join HOST PORT [options]");
        System.out.println("  java -Djava.awt.headless=true -jar StarChem.jar --server [PORT] [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --name NAME             Player or server name");
        System.out.println("  --system SYSTEM_ID      Initial star-system template");
        System.out.println("  --galaxy-copies 1|2     Number of copies per galaxy template");
        System.out.println("  --save-dir DIR          Dedicated-server save directory (default: saves)");
        System.out.println("  --save-name NAME        Dedicated-server save name (default: server)");
        System.out.println("  --autosave-seconds N    Dedicated-server autosave interval; 0 disables autosave");
        System.out.println("  --backup-count N        Number of timestamped save backups to retain");
        System.out.println("  --new-world             Ignore existing dedicated-server saves");
        System.out.println("  --dev                   Enable developer mode");
        System.out.println("  --dev-token TOKEN       Authorize requested remote developer access");
        System.out.println("  --enable-timers         Keep production timers enabled in developer mode");
        System.out.println("  --disable-timers        Disable production timers in developer mode");
        System.out.println("  --version, -V           Print build identity");
        System.out.println("  --help, -h              Print this help");
        System.out.println();
        System.out.println("The dedicated server uses TCP. Its default port is 50000.");
    }

    private static int runServer(Config config) {
        AtomicBoolean running = new AtomicBoolean(true);
        HeadlessGameServer server = null;
        Thread shutdownHook = null;
        try {
            server = HeadlessGameServer.start(config);
            HeadlessGameServer activeServer = server;
            shutdownHook = new Thread(() -> {
                if (running.getAndSet(false)) {
                    System.out.println("Server shutdown requested.");
                    activeServer.stop();
                }
            }, "starchem-server-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            System.out.println("Dedicated server ready.");
            System.out.println(activeServer.statusLine());

            long nextTick = System.nanoTime();
            long nextStatus = nextTick + SERVER_STATUS_NANOS;
            while (running.get()) {
                if (Thread.currentThread().isInterrupted()) {
                    running.set(false);
                    break;
                }

                long now = System.nanoTime();
                if (now < nextTick) {
                    LockSupport.parkNanos(nextTick - now);
                    continue;
                }

                int ticks = 0;
                do {
                    activeServer.tick(SERVER_TICK_SECONDS);
                    nextTick += SERVER_TICK_NANOS;
                    ticks++;
                } while (running.get() && ticks < MAX_SERVER_CATCH_UP_TICKS && System.nanoTime() >= nextTick);

                now = System.nanoTime();
                if (now >= nextTick) nextTick = now + SERVER_TICK_NANOS;
                if (now >= nextStatus) {
                    System.out.println(activeServer.statusLine());
                    nextStatus = now + SERVER_STATUS_NANOS;
                }
            }
            return 0;
        } catch (Exception ex) {
            System.err.println("Server failed:");
            ex.printStackTrace(System.err);
            return 1;
        } finally {
            running.set(false);
            if (server != null) server.stop();
            removeShutdownHook(shutdownHook);
        }
    }

    private static void removeShutdownHook(Thread shutdownHook) {
        if (shutdownHook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown is already in progress and the hook is running or has run.
        }
    }
}
