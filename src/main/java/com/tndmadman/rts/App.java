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
        FittingUiPolicy.install();
        GameSwingUi.install();
        FittingAccessController.install();
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
        System.out.println("  java -jar StarChem.jar --join HOST PORT [options]");
        System.out.println("  java -Djava.awt.headless=true -jar StarChem.jar --server [PORT] [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --name NAME             Player or server name");
        System.out.println("  --system SYSTEM_ID      Initial star-system template");
        System.out.println("  --galaxy-copies 1|2     Number of copies per galaxy template");
        System.out.println("  --skirmish-preset ID    peaceful, standard, hostile, or sandbox");
        System.out.println("  --npc-difficulty ID     relaxed, normal, hard, or brutal");
        System.out.println("  --victory-condition ID  Select a victory preset from config/victory-conditions.json");
        System.out.println("                          " + victoryConditionIds());
        System.out.println("  --save-dir DIR          Dedicated-server save directory (default: saves)");
        System.out.println("  --save-name NAME        Dedicated-server save name (default: server)");
        System.out.println("  --autosave-seconds N    Dedicated-server autosave interval; 0 disables autosave");
        System.out.println("  --backup-count N        Number of timestamped save backups to retain");
        System.out.println("  --new-world             Ignore existing dedicated-server saves");
        System.out.println("  --dev                   Enable developer mode");
        System.out.println("  --dev-token-file FILE   Load the developer token from a protected file");
        System.out.println("  --dev-token TOKEN       Legacy unsafe token argument; prefer --dev-token-file");
        System.out.println("  --enable-timers         Keep production timers enabled in developer mode");
        System.out.println("  --disable-timers        Disable production timers in developer mode");
        System.out.println("  --version, -V           Print build identity");
        System.out.println("  --help, -h              Print this help");
        System.out.println();
        System.out.println("The dedicated server uses TCP. Its default port is 50000.");
    }

    private static String victoryConditionIds() {
        StringBuilder out = new StringBuilder();
        for (VictoryConditionDefinition definition : VictoryConditionRules.all()) {
            if (!out.isEmpty()) out.append('|');
            out.append(definition.id());
        }
        return out.toString();
    }

    private static int runServer(Config config) {
        System.setOut(TextSafety.terminalPrintStream(System.out));
        System.setErr(TextSafety.terminalPrintStream(System.err));
        AtomicBoolean running = new AtomicBoolean(true);
        HeadlessGameServer server = null;
        Thread shutdownHook = null;
        int exitCode = 0;
        try {
            server = HeadlessGameServer.start(config);
            HeadlessGameServer activeServer = server;
            shutdownHook = new Thread(() -> {
                if (running.getAndSet(false)) {
                    System.out.println("Server shutdown requested.");
                    printServerShutdownResult(activeServer.forceStop());
                }
            }, "starchem-server-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            System.out.println(activeServer.statusLine());
            activeServer.attachConsole(ServerConsole.start(System.in, System.err));
            System.out.println("Type 'help' for dedicated server commands.");
            System.out.println("Dedicated server ready.");

            long nextTick = System.nanoTime();
            long nextStatus = nextTick + SERVER_STATUS_NANOS;
            while (running.get() && activeServer.running()) {
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
                } while (running.get() && activeServer.running() && ticks < MAX_SERVER_CATCH_UP_TICKS
                        && System.nanoTime() >= nextTick);

                if (!activeServer.running()) {
                    running.set(false);
                    break;
                }
                now = System.nanoTime();
                if (now >= nextTick) nextTick = now + SERVER_TICK_NANOS;
                if (now >= nextStatus) {
                    System.out.println(activeServer.statusLine());
                    nextStatus = now + SERVER_STATUS_NANOS;
                }
            }
            exitCode = activeServer.shutdownExitCode();
        } catch (Exception ex) {
            System.err.println("Server failed:");
            ex.printStackTrace(System.err);
            exitCode = 1;
        } finally {
            running.set(false);
            if (server != null && server.running()) {
                ServerShutdownResult result = server.forceStop();
                printServerShutdownResult(result);
                if (!result.clean()) exitCode = 3;
            } else if (server != null && exitCode == 0) {
                exitCode = server.shutdownExitCode();
            }
            removeShutdownHook(shutdownHook);
        }
        return exitCode;
    }

    private static void printServerShutdownResult(ServerShutdownResult result) {
        if (result == null) {
            System.err.println("UNCLEAN SHUTDOWN: no shutdown result was available.");
        } else if (result.clean()) {
            System.out.println(result.message());
        } else {
            System.err.println(result.message());
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
