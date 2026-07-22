from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path_name: str) -> str:
    return (ROOT / path_name).read_text()


def write(path_name: str, text: str) -> None:
    (ROOT / path_name).write_text(text)


def replace_once(path_name: str, old: str, new: str) -> None:
    text = read(path_name)
    if old not in text:
        raise RuntimeError(f"expected source block was not found in {path_name}: {old.splitlines()[0]!r}")
    write(path_name, text.replace(old, new, 1))


def replace_regex(path_name: str, pattern: str, replacement: str) -> None:
    text = read(path_name)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"expected source pattern was not found in {path_name}: {pattern!r}")
    write(path_name, updated)


headless = "src/main/java/com/tndmadman/rts/HeadlessGameServer.java"
replace_once(
    headless,
    "    private final AtomicBoolean stopped = new AtomicBoolean();\n",
    "    private final AtomicBoolean stopped = new AtomicBoolean();\n"
    "    private final AtomicBoolean stopping = new AtomicBoolean();\n",
)
replace_once(
    headless,
    "    private ServerConsole console;\n"
    "    private ServerCommandDispatcher consoleCommands;\n",
    "    private ServerConsole console;\n"
    "    private ServerCommandDispatcher consoleCommands;\n"
    "    private volatile ServerShutdownResult lastShutdownResult;\n"
    "    private volatile String lastSaveFailure = \"\";\n",
)
replace_once(
    headless,
    "            @Override public void stop() { HeadlessGameServer.this.stop(); }\n",
    "            @Override public void stop() { HeadlessGameServer.this.stop(); }\n"
    "            @Override public ServerShutdownResult stopResult() { return HeadlessGameServer.this.stop(); }\n",
)
replace_regex(
    headless,
    r"    void tick\(double dt\) \{.*?\n    private void drainConsoleCommands\(\) \{",
    '''    void tick(double dt) {
        if (stopped.get() || stopping.get()) return;
        drainConsoleCommands();
        if (stopped.get() || stopping.get()) return;
        processScheduledShutdown();
        if (stopped.get() || stopping.get()) return;
        network.updateServerWorlds(dt);
        network.tick();
        autosaveIfDue();
    }

    boolean running() { return !stopped.get(); }
    String statusLine() {
        if (stopped.get()) return "SERVER STOPPED";
        if (stopping.get()) return "SERVER STOPPING";
        return network.statusLine();
    }
    int shutdownExitCode() {
        ServerShutdownResult result = lastShutdownResult;
        return stopped.get() && result != null && !result.clean() ? 3 : 0;
    }

    ServerShutdownResult stop() { return stop(false); }
    ServerShutdownResult forceStop() { return stop(true); }

    private ServerShutdownResult stop(boolean forced) {
        if (stopped.get()) {
            ServerShutdownResult result = lastShutdownResult;
            return result == null ? ServerShutdownResult.cleanStop() : result;
        }
        if (!stopping.compareAndSet(false, true)) return ServerShutdownResult.inProgress();
        shutdownDeadlineNanos = NO_SHUTDOWN;
        boolean saved = saveNow("shutdown");
        if (!saved && !forced) {
            ServerShutdownResult result = ServerShutdownResult.aborted(lastSaveFailure);
            lastShutdownResult = result;
            stopping.set(false);
            return result;
        }

        stopped.set(true);
        ServerConsole activeConsole = console;
        console = null;
        consoleCommands = null;
        RuntimeException shutdownFailure = null;
        try {
            if (activeConsole != null) activeConsole.close();
        } catch (RuntimeException ex) {
            shutdownFailure = ex;
        }
        try {
            network.shutdown();
        } catch (RuntimeException ex) {
            if (shutdownFailure == null) shutdownFailure = ex;
            else shutdownFailure.addSuppressed(ex);
        }

        ServerShutdownResult result;
        if (saved && shutdownFailure == null) {
            result = ServerShutdownResult.cleanStop();
        } else {
            String detail = saved ? "" : lastSaveFailure;
            if (shutdownFailure != null) {
                String shutdownDetail = shutdownFailure.getClass().getSimpleName()
                        + (shutdownFailure.getMessage() == null || shutdownFailure.getMessage().isBlank()
                        ? "" : ": " + shutdownFailure.getMessage());
                detail = detail.isBlank() ? shutdownDetail : detail + "; " + shutdownDetail;
                System.err.println("Server shutdown cleanup failed: " + shutdownDetail);
            }
            result = ServerShutdownResult.forcedFailure(detail);
        }
        lastShutdownResult = result;
        stopping.set(false);
        return result;
    }

    private void drainConsoleCommands() {''',
)
replace_once(
    headless,
    '''        if (remaining <= 0) {
            network.broadcastServerNotice("Server is shutting down now" + shutdownReasonSuffix());
            stop();
            return;
        }
''',
    '''        if (remaining <= 0) {
            network.broadcastServerNotice("Server is shutting down now" + shutdownReasonSuffix());
            ServerShutdownResult result = stop();
            if (result.clean()) {
                System.out.println(result.message());
            } else {
                if (!result.stopped()) {
                    network.broadcastServerNotice("Server shutdown was aborted because the final save failed.");
                }
                System.err.println(result.message());
            }
            return;
        }
''',
)
replace_regex(
    headless,
    r"    private boolean saveNow\(String reason\) \{.*?\n    private List<PersistentPlayerSession> sortedSessions\(\) \{",
    '''    private boolean saveNow(String reason) {
        try {
            saves.save(world, config, reason, network.persistentPlayerSessions());
            lastSaveFailure = "";
            lastSuccessfulSaveAt = Instant.now();
            lastSuccessfulSaveReason = reason;
            if ("autosave".equals(reason)) autosaveCount++;
            else if ("manual-console".equals(reason)) manualSaveCount++;
            if (!"autosave".equals(reason)) System.out.println("Server save completed (" + reason + ").");
            return true;
        } catch (IOException | RuntimeException ex) {
            lastSaveFailure = ex.getClass().getSimpleName()
                    + (ex.getMessage() == null || ex.getMessage().isBlank() ? "" : ": " + ex.getMessage());
            System.err.println("Server save failed (" + reason + "): " + lastSaveFailure);
            return false;
        }
    }

    private List<PersistentPlayerSession> sortedSessions() {''',
)
headless_path = ROOT / headless
headless_text = headless_path.read_text()
if "record ServerShutdownResult" not in headless_text:
    headless_path.write_text(
        headless_text.rstrip()
        + '''

record ServerShutdownResult(boolean stopped, boolean clean, String message) {
    static ServerShutdownResult cleanStop() {
        return new ServerShutdownResult(true, true, "Dedicated server stopped cleanly.");
    }

    static ServerShutdownResult aborted(String detail) {
        return new ServerShutdownResult(false, false,
                "Shutdown aborted: final server save failed. The server remains running; correct the save problem and retry"
                        + suffix(detail) + ".");
    }

    static ServerShutdownResult forcedFailure(String detail) {
        return new ServerShutdownResult(true, false,
                "UNCLEAN SHUTDOWN: final server save or shutdown cleanup failed; server resources were stopped"
                        + suffix(detail) + ".");
    }

    static ServerShutdownResult inProgress() {
        return new ServerShutdownResult(false, false, "Shutdown is already in progress.");
    }

    private static String suffix(String detail) {
        return detail == null || detail.isBlank() ? "" : " (" + detail + ")";
    }
}
'''
    )


dispatcher = "src/main/java/com/tndmadman/rts/ServerCommandDispatcher.java"
replace_once(
    dispatcher,
    '''        boolean save();
        void stop();
        boolean running();
        default Object extensionContext() { return null; }
''',
    '''        boolean save();
        void stop();
        default ServerShutdownResult stopResult() {
            stop();
            return running()
                    ? ServerShutdownResult.aborted("shutdown target is still running")
                    : ServerShutdownResult.cleanStop();
        }
        boolean running();
        default Object extensionContext() { return null; }
''',
)
replace_once(
    dispatcher,
    '''        if (args.isEmpty() || "now".equalsIgnoreCase(args.get(0))) {
            if (!target.running()) output.println("Server is already stopped.");
            else {
                output.println("Stopping dedicated server.");
                target.stop();
            }
            return;
        }
''',
    '''        if (args.isEmpty() || "now".equalsIgnoreCase(args.get(0))) {
            if (!target.running()) output.println("Server is already stopped.");
            else printShutdownResult(target.stopResult());
            return;
        }
''',
)
replace_regex(
    dispatcher,
    r"    private void stop\(List<String> args\) \{.*?\n    private void printLines\(List<String> lines\) \{",
    '''    private void stop(List<String> args) {
        if (!requireNoArgs("stop", args)) return;
        if (!target.running()) output.println("Server is already stopped.");
        else printShutdownResult(target.stopResult());
    }

    private void printShutdownResult(ServerShutdownResult result) {
        if (result == null) {
            errors.println("Shutdown failed without a result.");
        } else if (result.clean()) {
            output.println(result.message());
        } else {
            errors.println(result.message());
        }
    }

    private void printLines(List<String> lines) {''',
)


app = "src/main/java/com/tndmadman/rts/App.java"
replace_regex(
    app,
    r"    private static int runServer\(Config config\) \{.*?\n    private static void removeShutdownHook\(Thread shutdownHook\) \{",
    '''    private static int runServer(Config config) {
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

            System.out.println("Dedicated server ready.");
            System.out.println(activeServer.statusLine());
            activeServer.attachConsole(ServerConsole.start(System.in, System.err));
            System.out.println("Type 'help' for dedicated server commands.");

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

    private static void removeShutdownHook(Thread shutdownHook) {''',
)


harness = "src/main/java/com/tndmadman/rts/TcpIntegrationHarness.java"
replace_once(harness, "    private final Path serverSaveDir;\n", "    final Path serverSaveDir;\n")
replace_once(
    harness,
    "            if (headlessServer != null) headlessServer.stop();\n",
    "            if (headlessServer != null) headlessServer.forceStop();\n",
)


dedicated = "src/main/java/com/tndmadman/rts/DedicatedTcpServerValidator.java"
replace_once(
    dedicated,
    "        ServerSaveStoreBackupCollisionValidator.validate();\n"
    "        AdmissionRecordingValidator.validate();\n",
    "        ServerSaveStoreBackupCollisionValidator.validate();\n"
    "        ServerShutdownValidator.validate();\n"
    "        AdmissionRecordingValidator.validate();\n",
)


validator = ROOT / "src/main/java/com/tndmadman/rts/ServerShutdownValidator.java"
validator.write_text('''package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;

/** Validates clean, aborted, retried, and forced dedicated-server shutdown paths. */
public final class ServerShutdownValidator {
    private ServerShutdownValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem server shutdown validation passed.");
    }

    static void validate() throws Exception {
        validateGracefulFailureAndRetry();
        validateForcedFailureStatus();
    }

    private static void validateGracefulFailureAndRetry() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.dedicated()) {
            Path displaced = blockSaveDirectory(harness.serverSaveDir);
            try {
                ServerShutdownResult failed = harness.headlessServer.stop();
                require(!failed.stopped() && !failed.clean(),
                        "failed graceful shutdown was reported as stopped or clean");
                require(harness.headlessServer.running(),
                        "failed graceful shutdown did not leave the server running");
                require(failed.message().contains("remains running"),
                        "failed graceful shutdown did not explain recovery behavior");

                TcpIntegrationHarness.TestClient client = harness.addClient("Shutdown Retry Client");
                harness.awaitJoined(client);
                require(harness.serverNetwork.serverPeerCount() == 1,
                        "network was torn down after the graceful shutdown save failed");
            } finally {
                restoreSaveDirectory(harness.serverSaveDir, displaced);
            }

            ServerShutdownResult retried = harness.headlessServer.stop();
            require(retried.stopped() && retried.clean(),
                    "shutdown did not succeed after the save directory was restored");
            require(!harness.headlessServer.running(),
                    "successful retry left the server running");
            require(harness.headlessServer.shutdownExitCode() == 0,
                    "clean retry returned an unclean process status");
        }
    }

    private static void validateForcedFailureStatus() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.dedicated()) {
            Path displaced = blockSaveDirectory(harness.serverSaveDir);
            ServerShutdownResult forced;
            try {
                forced = harness.headlessServer.forceStop();
            } finally {
                restoreSaveDirectory(harness.serverSaveDir, displaced);
            }
            require(forced.stopped() && !forced.clean(),
                    "forced shutdown did not stop with an unclean result after save failure");
            require(!harness.headlessServer.running(),
                    "forced shutdown left the server running");
            require(harness.headlessServer.shutdownExitCode() == 3,
                    "forced save failure did not expose the unclean shutdown exit status");
            require(forced.message().contains("UNCLEAN SHUTDOWN"),
                    "forced save failure did not emit an explicit unclean-shutdown result");
        }
    }

    private static Path blockSaveDirectory(Path saveDir) throws Exception {
        Path displaced = saveDir.resolveSibling(saveDir.getFileName() + "-available");
        Files.move(saveDir, displaced);
        Files.writeString(saveDir, "save directory intentionally blocked for validation");
        return displaced;
    }

    private static void restoreSaveDirectory(Path saveDir, Path displaced) throws Exception {
        Files.deleteIfExists(saveDir);
        Files.move(displaced, saveDir);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
''')

print("Applied final-save shutdown fix.")
