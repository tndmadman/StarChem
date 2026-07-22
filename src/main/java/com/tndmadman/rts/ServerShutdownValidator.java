package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Validates clean, aborted, retried, and forced dedicated-server shutdown paths. */
public final class ServerShutdownValidator {
    private ServerShutdownValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem server shutdown validation passed.");
    }

    static void validate() throws Exception {
        require(ServerShutdownResult.cleanStop().message().contains("Dedicated server stopped."),
                "clean shutdown output no longer satisfies headless smoke checks");
        validateGracefulFailureAndRetry();
        validateForcedFailureStatus();
        validateConcurrentForcedStop();
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

    private static void validateConcurrentForcedStop() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.dedicated()) {
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<ServerShutdownResult> first = new AtomicReference<>();
            AtomicReference<ServerShutdownResult> second = new AtomicReference<>();
            Thread one = new Thread(() -> forceAfter(start, harness, first), "shutdown-validator-one");
            Thread two = new Thread(() -> forceAfter(start, harness, second), "shutdown-validator-two");
            one.start();
            two.start();
            start.countDown();
            one.join(15_000);
            two.join(15_000);

            require(!one.isAlive() && !two.isAlive(),
                    "concurrent forced shutdown callers did not complete");
            require(first.get() != null && first.get().stopped() && first.get().clean(),
                    "first concurrent shutdown caller did not receive the clean final result");
            require(second.get() != null && second.get().stopped() && second.get().clean(),
                    "second concurrent shutdown caller did not receive the clean final result");
            require(harness.headlessServer.shutdownExitCode() == 0,
                    "concurrent clean shutdown produced an unclean process status");
        }
    }

    private static void forceAfter(CountDownLatch start, TcpIntegrationHarness harness,
                                   AtomicReference<ServerShutdownResult> result) {
        try {
            start.await();
            result.set(harness.headlessServer.forceStop());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
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
