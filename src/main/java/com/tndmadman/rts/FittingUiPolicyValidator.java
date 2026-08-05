package com.tndmadman.rts;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/** Verifies fitting combos stay above glass panes and remain keyboard-accessible. */
public final class FittingUiPolicyValidator {
    private FittingUiPolicyValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("StarChem fitting popup and keyboard policy validation passed.");
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
        explicit.setFocusable(false);
        explicit.setLightWeightPopupEnabled(true);
        FittingUiPolicy.prepare(explicit);
        require(explicit.isFocusable(),
                "explicit fitting combo preparation did not restore keyboard focus");
        require(!explicit.isLightWeightPopupEnabled(),
                "explicit fitting combo preparation did not force a heavyweight popup");

        JPanel root = new JPanel();
        JPanel nested = new JPanel();
        JComboBox<String> nestedCombo = new JComboBox<>(new String[] { "One", "Two" });
        nestedCombo.setFocusable(false);
        nestedCombo.setLightWeightPopupEnabled(true);
        nested.add(nestedCombo);
        root.add(nested);
        FittingUiPolicy.prepareTree(root);
        require(nestedCombo.isFocusable(),
                "recursive fitting preparation left a nested combo outside keyboard traversal");
        require(!nestedCombo.isLightWeightPopupEnabled(),
                "recursive fitting preparation left a nested combo lightweight");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
