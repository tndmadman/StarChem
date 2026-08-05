package com.tndmadman.rts;

import java.util.Arrays;
import java.util.Locale;

/** Shared fail-closed parsing for enum-backed configuration values. */
final class StrictConfigEnums {
    private StrictConfigEnums() { }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String label) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) {
            throw new RuleConfigurationException("Missing " + label + ".");
        }
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new RuleConfigurationException(
                    "Unknown " + label + " '" + value + "'. Expected one of "
                            + Arrays.toString(type.getEnumConstants()) + ".");
        }
    }
}
