package com.tndmadman.rts;

/** Exercises live diplomacy through authenticated production TCP clients and the authoritative server. */
public final class TcpDiplomacyValidator {
    private TcpDiplomacyValidator() { }

    public static void main(String[] args) throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host()) {
            TcpIntegrationHarness.TestClient one = harness.addClient("Diplomacy One");
            TcpIntegrationHarness.TestClient two = harness.addClient("Diplomacy Two");
            TcpIntegrationHarness.TestClient three = harness.addClient("Diplomacy Three");
            harness.awaitJoined(one);
            harness.awaitJoined(two);
            harness.awaitJoined(three);

            require(DiplomacyNetworkBridge.refresh(one.network(), one.world()),
                    "first client could not request the authoritative diplomacy roster");
            require(DiplomacyNetworkBridge.refresh(two.network(), two.world()),
                    "second client could not request the authoritative diplomacy roster");
            require(DiplomacyNetworkBridge.refresh(three.network(), three.world()),
                    "third client could not request the authoritative diplomacy roster");
            harness.await(() -> DiplomacyClientState.player(one.world(), two.playerId()) != null
                            && DiplomacyClientState.player(two.world(), one.playerId()) != null
                            && DiplomacyClientState.player(three.world(), one.playerId()) != null,
                    5_000, "authoritative retained-player roster did not reach every client");

            require(DiplomacyNetworkBridge.send(one.network(), one.world(), two.playerId(),
                            DiplomacySystem.LiveAction.OFFER_ALLIANCE),
                    "alliance offer was not submitted by the first client");
            harness.await(() -> DiplomacySystem.hasAllianceOffer(
                            harness.serverWorld, one.playerId(), two.playerId()),
                    5_000, "alliance offer did not reach authoritative server state");
            harness.await(() -> {
                DiplomacyClientState.PlayerView recipient = DiplomacyClientState.player(two.world(), one.playerId());
                DiplomacyClientState.PlayerView sender = DiplomacyClientState.player(one.world(), two.playerId());
                return recipient != null && recipient.incomingOffer()
                        && sender != null && sender.outgoingOffer();
            }, 5_000, "recipient and sender did not receive scoped offer state");
            harness.await(() -> AlertCenter.list(two.world()).stream()
                            .anyMatch(notification -> notification.text.contains("offered you an alliance")),
                    5_000, "recipient did not receive the visible alliance-offer notification");
            long offerRevision = DiplomacyClientState.revision(one.world());
            harness.await(() -> DiplomacyClientState.revision(three.world()) >= offerRevision,
                    5_000, "unrelated client did not receive the scoped diplomacy revision");
            require(!DiplomacySystem.hasAllianceOffer(three.world(), one.playerId(), two.playerId()),
                    "unrelated client received another pair's private alliance offer");

            require(DiplomacyNetworkBridge.send(two.network(), two.world(), one.playerId(),
                            DiplomacySystem.LiveAction.ACCEPT_ALLIANCE),
                    "recipient could not submit alliance acceptance");
            harness.await(() -> DiplomacySystem.allied(
                            harness.serverWorld, one.playerId(), two.playerId()),
                    5_000, "authoritative server did not form the accepted alliance");
            harness.await(() -> DiplomacySystem.allied(one.world(), one.playerId(), two.playerId())
                            && DiplomacySystem.allied(two.world(), one.playerId(), two.playerId()),
                    5_000, "accepted alliance did not converge on both clients");
            require(!DiplomacySystem.mayTarget(harness.serverWorld, one.playerId(), two.playerId()),
                    "server combat authorization still allows allied targeting");

            require(DiplomacyNetworkBridge.send(one.network(), one.world(), two.playerId(),
                            DiplomacySystem.LiveAction.NEUTRAL),
                    "neutral relationship request was not submitted");
            harness.await(() -> DiplomacySystem.neutral(
                            harness.serverWorld, one.playerId(), two.playerId()),
                    5_000, "server did not authorize neutral relationship state");

            // Legacy/spoof-shaped packet: P2 appears in the request-id field, but the authenticated
            // connection belongs to P1. The server must create P1 -> P3, never P2 -> P3.
            require(DiplomacyNetworkBridge.sendRawForTest(one.network(), one.world(),
                            "DIPLOMACY|" + two.playerId() + "|" + three.playerId() + "|OFFER_ALLIANCE"),
                    "raw authenticated diplomacy test packet could not be submitted");
            harness.await(() -> DiplomacySystem.hasAllianceOffer(
                            harness.serverWorld, one.playerId(), three.playerId()),
                    5_000, "server did not derive diplomacy actor from authenticated connection");
            require(!DiplomacySystem.hasAllianceOffer(
                            harness.serverWorld, two.playerId(), three.playerId()),
                    "server trusted a client-supplied actor identity");

            require(DiplomacyNetworkBridge.send(three.network(), three.world(), one.playerId(),
                            DiplomacySystem.LiveAction.DECLINE_ALLIANCE),
                    "recipient could not decline the authenticated offer");
            harness.await(() -> !DiplomacySystem.hasAllianceOffer(
                            harness.serverWorld, one.playerId(), three.playerId()),
                    5_000, "declined offer remained in authoritative state");

            System.out.println("StarChem TCP diplomacy validation passed.");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
