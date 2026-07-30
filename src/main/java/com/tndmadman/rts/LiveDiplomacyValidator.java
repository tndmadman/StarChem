package com.tndmadman.rts;

import java.util.List;
import java.util.Map;

final class LiveDiplomacyValidator {
    private LiveDiplomacyValidator() { }

    public static void main(String[] args) {
        validatesOfferAcceptanceAndRelationshipChanges();
        validatesDeclineAndCancellation();
        validatesPersistenceAndScopedClientState();
        validatesLockedModesAndMenuEntry();
        System.out.println("Live diplomacy validation passed.");
    }

    private static void validatesOfferAcceptanceAndRelationshipChanges() {
        World world = world("LiveDiplomacy");
        registerPlayers(world);

        require(DiplomacySystem.liveNegotiationAllowed(world),
                "FFA must allow live diplomacy negotiation.");
        require(DiplomacySystem.hostile(world, "P1", "P2"),
                "FFA players must begin hostile.");

        DiplomacySystem.LiveResult offered = DiplomacyCommand.applyLocal(
                world, "P1", "P2", DiplomacySystem.LiveAction.OFFER_ALLIANCE);
        require(offered == DiplomacySystem.LiveResult.ALLIANCE_OFFERED,
                "Offer action must create an outgoing offer.");
        require(DiplomacySystem.hasAllianceOffer(world, "P1", "P2"),
                "Outgoing alliance offer must be recorded.");
        require(DiplomacySystem.incomingAllianceOffers(world, "P2").contains("P1"),
                "Target player must see the incoming offer.");
        require(DiplomacySystem.hostile(world, "P1", "P2"),
                "An unaccepted alliance offer must not change combat rules.");

        DiplomacySystem.LiveResult accepted = DiplomacyCommand.applyLocal(
                world, "P2", "P1", DiplomacySystem.LiveAction.ACCEPT_ALLIANCE);
        require(accepted == DiplomacySystem.LiveResult.ALLIANCE_ACCEPTED,
                "Explicit accept action must form the alliance.");
        require(DiplomacySystem.allied(world, "P1", "P2"),
                "Accepted alliance must become bilateral.");
        require(!DiplomacySystem.mayTarget(world, "P1", "P2"),
                "Allies must not be valid attack targets with friendly fire disabled.");
        require(IntelWarfareSystem.allied(world, "P1", "P2"),
                "Live allies must share intelligence visibility.");
        require(!DiplomacySystem.hasAllianceOffer(world, "P1", "P2")
                        && !DiplomacySystem.hasAllianceOffer(world, "P2", "P1"),
                "Accepted alliance must clear pending offers.");

        DiplomacySystem.LiveResult neutral = DiplomacyCommand.applyLocal(
                world, "P1", "P2", DiplomacySystem.LiveAction.NEUTRAL);
        require(neutral == DiplomacySystem.LiveResult.NEUTRAL_SET,
                "Neutral declaration must apply immediately.");
        require(DiplomacySystem.neutral(world, "P1", "P2"),
                "Neutral declaration must be bilateral.");
        require(!DiplomacySystem.mayTarget(world, "P1", "P2"),
                "Neutral players must not be valid attack targets.");

        DiplomacyCommand.applyLocal(world, "P2", "P1", DiplomacySystem.LiveAction.OFFER_ALLIANCE);
        require(DiplomacySystem.hasAllianceOffer(world, "P2", "P1"),
                "A new alliance offer must be possible after neutrality.");
        DiplomacySystem.LiveResult hostile = DiplomacyCommand.applyLocal(
                world, "P1", "P2", DiplomacySystem.LiveAction.HOSTILE);
        require(hostile == DiplomacySystem.LiveResult.HOSTILE_SET,
                "Hostile declaration must apply immediately.");
        require(DiplomacySystem.hostile(world, "P1", "P2"),
                "Hostile declaration must be bilateral.");
        require(DiplomacySystem.mayTarget(world, "P1", "P2"),
                "Hostile players must be valid attack targets.");
        require(!DiplomacySystem.hasAllianceOffer(world, "P2", "P1"),
                "Neutral or hostile declarations must clear pending alliance offers.");
    }

    private static void validatesDeclineAndCancellation() {
        World world = world("LiveDiplomacyResolution");
        registerPlayers(world);

        DiplomacyCommand.applyLocal(world, "P1", "P2", DiplomacySystem.LiveAction.OFFER_ALLIANCE);
        require(DiplomacyCommand.applyLocal(world, "P2", "P1",
                        DiplomacySystem.LiveAction.DECLINE_ALLIANCE)
                        == DiplomacySystem.LiveResult.ALLIANCE_DECLINED,
                "Recipient must be able to decline an incoming offer.");
        require(!DiplomacySystem.hasAllianceOffer(world, "P1", "P2"),
                "Declining must remove the incoming offer.");

        DiplomacyCommand.applyLocal(world, "P1", "P2", DiplomacySystem.LiveAction.OFFER_ALLIANCE);
        require(DiplomacyCommand.applyLocal(world, "P1", "P2",
                        DiplomacySystem.LiveAction.CANCEL_ALLIANCE)
                        == DiplomacySystem.LiveResult.ALLIANCE_CANCELED,
                "Sender must be able to cancel an outgoing offer.");
        require(!DiplomacySystem.hasAllianceOffer(world, "P1", "P2"),
                "Canceling must remove the outgoing offer.");
        require(DiplomacyCommand.applyLocal(world, "P2", "P1",
                        DiplomacySystem.LiveAction.ACCEPT_ALLIANCE)
                        == DiplomacySystem.LiveResult.NO_PENDING_OFFER,
                "Acceptance without a matching incoming offer must be rejected.");
    }

    private static void validatesPersistenceAndScopedClientState() {
        World source = world("LiveDiplomacySave");
        registerPlayers(source);
        DiplomacyCommand.applyLocal(source, "P1", "P2", DiplomacySystem.LiveAction.OFFER_ALLIANCE);
        DiplomacyCommand.applyLocal(source, "P1", "P3", DiplomacySystem.LiveAction.NEUTRAL);

        Map<String,Object> saved = DiplomacySystem.capture(source);
        World restored = world("LiveDiplomacyRestore");
        registerPlayers(restored);
        DiplomacySystem.restore(restored, saved);
        DiplomacyBootstrap.refreshIntelAlliances(restored);
        require(DiplomacySystem.hasAllianceOffer(restored, "P1", "P2"),
                "Pending alliance offers must survive server save restore.");
        require(DiplomacySystem.neutral(restored, "P1", "P3"),
                "Live relationship overrides must survive server save restore.");

        Map<String,Object> view = Map.of(
                "revision", 8,
                "selfId", "P2",
                "mode", "FFA",
                "negotiationAllowed", true,
                "state", saved,
                "players", List.of(Map.of(
                        "id", "P1",
                        "name", "One",
                        "rgb", 0x50BEFF,
                        "online", true,
                        "relationship", "HOSTILE",
                        "incomingOffer", true,
                        "outgoingOffer", false)));
        String encoded = DiplomacyStateWire.encode(view);
        World client = world("LiveDiplomacyClient");
        registerPlayers(client);
        Map<String,Object> decoded = DiplomacyStateWire.decode(encoded);
        DiplomacySystem.restore(client, decoded.get("state"));
        DiplomacyClientState.apply(client, decoded);
        DiplomacyBootstrap.refreshIntelAlliances(client);
        require(DiplomacySystem.hasAllianceOffer(client, "P1", "P2"),
                "Recipient-scoped synchronization must preserve its incoming offer.");
        require(DiplomacyClientState.revision(client) == 8,
                "Client diplomacy revision must be applied.");
        DiplomacyClientState.PlayerView player = DiplomacyClientState.player(client, "P1");
        require(player != null && player.incomingOffer() && player.online(),
                "Client roster must expose the incoming offer and online state.");
        WorldRuntimeCleanup.discard(client);
        require(!DiplomacyClientState.containsWorldForTest(client),
                "World cleanup must discard scoped diplomacy client state.");
    }

    private static void validatesLockedModesAndMenuEntry() {
        World fixed = world("LiveDiplomacyFixed");
        registerPlayers(fixed);
        DiplomacySystem.configure(fixed, DiplomacySystem.MatchMode.FIXED_TEAMS,
                false, true, true);
        DiplomacySystem.LiveResult locked = DiplomacySystem.applyLiveAction(
                fixed, "P1", "P2", DiplomacySystem.LiveAction.OFFER_ALLIANCE);
        require(locked == DiplomacySystem.LiveResult.MODE_LOCKED,
                "Fixed teams must reject live relationship negotiation.");
        require(DiplomacySystem.applyLiveAction(fixed, "P1", Config.RAIDERS_ID,
                        DiplomacySystem.LiveAction.OFFER_ALLIANCE)
                        == DiplomacySystem.LiveResult.INVALID_TARGET,
                "NPC factions must not be valid live diplomacy targets.");

        GameMenuOverlay menu = new GameMenuOverlay(false);
        require(menu.labelsForTest().contains("Diplomacy"),
                "The in-game menu must expose the diplomacy screen.");
    }

    private static World world(String name) {
        return new World(name, java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
    }

    private static void registerPlayers(World world) {
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "One", 0x50BEFF);
        PlayerRegistry.register("P2", "Two", 0x77DD88, false);
        PlayerRegistry.register("P3", "Three", 0xFF7050, false);
        PlayerRegistry.register(Config.RAIDERS_ID, "Raiders", 0xFF4444, false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
