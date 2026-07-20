package com.tndmadman.rts;

/** Validates post-authentication admission side effects across registration, reclaim, and resume. */
public final class AdmissionRecordingValidator {
    private static final String PLAYER_NAME = "Admission Recording Client";
    private static final String PASSWORD = "validator-password";

    private AdmissionRecordingValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem admission recording validation passed.");
    }

    static void validate() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host()) {
            harness.serverNetwork.serverJournal().clear();

            TcpIntegrationHarness.TestClient registered = harness.addClient(PLAYER_NAME);
            harness.awaitJoined(registered);
            String playerId = registered.playerId();
            TcpIntegrationHarness.require(admissionCount(harness, "JOIN", playerId) == 1,
                    "new registration did not record exactly one JOIN event");
            TcpIntegrationHarness.require(admissionCount(harness, "RECONNECT", playerId) == 0,
                    "new registration incorrectly recorded a RECONNECT event");
            ServerPlayerObservationStore.PlayerObservation registeredObservation =
                    requireObservation(harness, playerId, "new registration");

            disconnect(harness, registered, playerId);
            SessionTokenStore.clear(registered.config());
            SessionTokenStore.saveAuthDigest(registered.config(),
                    PasswordAuth.verifier(PLAYER_NAME, PASSWORD));
            Thread.sleep(10);

            TcpIntegrationHarness.TestClient reclaimed = harness.addClient(PLAYER_NAME);
            harness.awaitJoined(reclaimed);
            TcpIntegrationHarness.require(playerId.equals(reclaimed.playerId()),
                    "password challenge reclaim created a different player identity");
            TcpIntegrationHarness.require(admissionCount(harness, "JOIN", playerId) == 2,
                    "password challenge reclaim did not record exactly one additional JOIN event");
            TcpIntegrationHarness.require(admissionCount(harness, "RECONNECT", playerId) == 0,
                    "password challenge reclaim incorrectly recorded a RECONNECT event");
            ServerPlayerObservationStore.PlayerObservation reclaimedObservation =
                    requireObservation(harness, playerId, "password challenge reclaim");
            TcpIntegrationHarness.require(reclaimedObservation.lastSeenAt() > registeredObservation.lastSeenAt(),
                    "password challenge reclaim did not refresh the player observation");

            disconnect(harness, reclaimed, playerId);
            Thread.sleep(10);

            TcpIntegrationHarness.TestClient resumed = harness.addClient(PLAYER_NAME);
            harness.awaitJoined(resumed);
            TcpIntegrationHarness.require(playerId.equals(resumed.playerId()),
                    "session challenge resume created a different player identity");
            TcpIntegrationHarness.require(admissionCount(harness, "JOIN", playerId) == 2,
                    "session challenge resume duplicated a JOIN event");
            TcpIntegrationHarness.require(admissionCount(harness, "RECONNECT", playerId) == 1,
                    "session challenge resume did not record exactly one RECONNECT event");
            ServerPlayerObservationStore.PlayerObservation resumedObservation =
                    requireObservation(harness, playerId, "session challenge resume");
            TcpIntegrationHarness.require(resumedObservation.lastSeenAt() > reclaimedObservation.lastSeenAt(),
                    "session challenge resume did not refresh the player observation");
        }
    }

    private static void disconnect(TcpIntegrationHarness harness,
                                   TcpIntegrationHarness.TestClient client,
                                   String playerId) throws Exception {
        client.network().shutdown();
        harness.clients.remove(client);
        harness.await(() -> !harness.serverNetwork.serverSessionConnected(playerId),
                5_000, "server did not process the client disconnect");
    }

    private static ServerPlayerObservationStore.PlayerObservation requireObservation(
            TcpIntegrationHarness harness, String playerId, String stage) {
        ServerPlayerObservationStore.PlayerObservation observation =
                harness.serverNetwork.playerObservation(playerId);
        TcpIntegrationHarness.require(observation != null,
                stage + " did not record a player observation");
        return observation;
    }

    private static long admissionCount(TcpIntegrationHarness harness, String type, String playerId) {
        String marker = " | " + type + " | " + playerId + " | accepted";
        return harness.serverNetwork.serverJournal().lines(500, type, playerId).stream()
                .filter(line -> line.contains(marker))
                .count();
    }
}
