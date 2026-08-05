package com.tndmadman.rts;

/** Verifies current configuration loading and fail-closed enum parsing. */
public final class ConfigurationEnumValidator {
    private ConfigurationEnumValidator() { }

    public static void main(String[] args) {
        require(!Rules.SHIPS.isEmpty(), "ship configuration did not load");
        require(!NpcRules.baseFactions().isEmpty(), "NPC configuration did not load");

        require(StrictConfigEnums.parse(ShipSize.class, "small", "ship size") == ShipSize.SMALL,
                "valid ship size was not normalized");
        require(StrictConfigEnums.parse(NpcBehavior.class, "faction", "NPC behavior") == NpcBehavior.FACTION,
                "valid NPC behavior was not normalized");
        require(StrictConfigEnums.parse(Material.class, "iron", "material") == Material.IRON,
                "valid material was not normalized");
        require(StrictConfigEnums.parse(NodeKind.class, "gas_cloud", "node kind") == NodeKind.GAS_CLOUD,
                "valid node kind was not normalized");

        expectRejected(ShipSize.class, "SMAL", "ship size");
        expectRejected(NpcBehavior.class, "RADER", "NPC behavior");
        expectRejected(Material.class, "UNOBTAINIUM", "material");
        expectRejected(NodeKind.class, "GAS_CLODU", "node kind");

        FittingUiPolicyValidator.validate();
        System.out.println("StarChem strict configuration enum and fitting popup validation passed.");
    }

    private static <E extends Enum<E>> void expectRejected(Class<E> type, String value, String label) {
        try {
            StrictConfigEnums.parse(type, value, label);
            throw new IllegalStateException("Invalid " + label + " was silently accepted: " + value);
        } catch (RuleConfigurationException expected) {
            if (!expected.getMessage().contains(value)) {
                throw new IllegalStateException("Invalid " + label + " diagnostic omitted the bad value", expected);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
