package com.tndmadman.rts;

import javax.swing.JComboBox;
import javax.swing.JPopupMenu;

/** Verifies combo popups used by glass-pane fitting overlays are heavyweight. */
public final class FittingUiPolicyValidator {
    private FittingUiPolicyValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("StarChem fitting popup policy validation passed.");
    }

    static void validate() {
        System.setProperty("java.awt.headless", "true");
        JPopupMenu.setDefaultLightWeightPopupEnabled(true);
        FittingUiPolicy.install();
        require(!JPopupMenu.getDefaultLightWeightPopupEnabled(),
                "fitting popup policy left lightweight popups enabled");

        JComboBox<String> inherited = new JComboBox<>(new String[] { "First", "Second" });
        require(!inherited.isLightWeightPopupEnabled(),
                "a combo created after policy installation did not inherit heavyweight popups");
        inherited.setSelectedIndex(1);
        require("Second".equals(inherited.getSelectedItem()),
                "heavyweight popup policy interfered with combo selection state");

        JComboBox<String> explicit = new JComboBox<>(new String[] { "A", "B" });
        explicit.setLightWeightPopupEnabled(true);
        FittingUiPolicy.prepare(explicit);
        require(!explicit.isLightWeightPopupEnabled(),
                "explicit fitting combo preparation did not force a heavyweight popup");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
