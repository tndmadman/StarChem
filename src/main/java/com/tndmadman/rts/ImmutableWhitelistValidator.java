package com.tndmadman.rts;

import java.util.List;
import java.util.Set;

/** Validates that whitelist admission is bound only to retained immutable player IDs. */
public final class ImmutableWhitelistValidator {
    private ImmutableWhitelistValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("StarChem immutable whitelist validation passed.");
    }

    static void validate() {
        ServerModerationState state = ServerModerationState.open()
                .addWhitelist("P1", "Alpha")
                .withWhitelistEnabled(true);
        require(state.whitelist().equals(Set.of("p:p1")),
                "whitelist stored anything other than the immutable player ID");
        require(state.whitelisted("P1", "Renamed Alpha"),
                "immutable player ID did not remain whitelisted after a name change");
        require(!state.whitelisted("", "Alpha"),
                "a name-only identity was accepted by the whitelist");
        require(!state.whitelisted("P2", "Alpha"),
                "a different identity reused a whitelisted display name");

        boolean unresolvedRejected = false;
        try {
            state.addWhitelist("", "Future Pilot");
        } catch (IllegalArgumentException ex) {
            unresolvedRejected = ex.getMessage() != null
                    && ex.getMessage().contains("existing retained player identity");
        }
        require(unresolvedRejected, "an unresolved display name could be added to the whitelist");

        ServerModerationState migrated = new ServerModerationState(true,
                Set.of("n:future pilot", "p:P2", "n:alpha"), List.of());
        require(migrated.whitelist().equals(Set.of("p:p2")),
                "legacy name-only whitelist entries survived normalization");
        require(migrated.whitelisted("P2", "Any Name"),
                "normalized legacy player-ID entry was not retained");
        require(!migrated.whitelisted("", "Future Pilot"),
                "legacy name-only whitelist entry still authorized admission");

        ServerModerationState removed = migrated.removeWhitelist("P2");
        require(removed.whitelist().isEmpty(), "immutable whitelist entry could not be removed by player ID");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
