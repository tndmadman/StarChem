package com.tndmadman.rts;

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
