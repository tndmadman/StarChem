package com.tndmadman.rts;

import java.awt.Container;
import java.lang.reflect.Field;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/** Verifies fitting combos stay above glass panes without changing unrelated popup policy. */
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
        require(JPopupMenu.getDefaultLightWeightPopupEnabled(),
                "fitting popup policy changed the global Swing popup mode");

        JComboBox<String> unrelated = new JComboBox<>(new String[] { "First", "Second" });
        require(unrelated.isLightWeightPopupEnabled(),
                "an unrelated combo inherited the fitting-only heavyweight popup policy");
        unrelated.setSelectedIndex(1);
        require("Second".equals(unrelated.getSelectedItem()),
                "popup policy interfered with combo selection state");

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
        JComboBox<String> nestedCombo = inaccessibleCombo("One", "Two");
        nested.add(nestedCombo);
        root.add(nested);
        FittingUiPolicy.prepareTree(root);
        requireAccessible(nestedCombo,
                "recursive fitting preparation left a nested combo outside the fitting policy");

        verifyActualWindowTree(new ShipFittingWindow(), "per-ship fitting window");
        verifyActualWindowTree(new ShipFitStudioWindow(), "standalone fit studio");
    }

    private static void verifyActualWindowTree(Object window, String label) {
        Container glass = glass(window);

        JPanel prebuiltPanel = new JPanel();
        JComboBox<String> prebuiltCombo = inaccessibleCombo("Alpha", "Beta");
        prebuiltPanel.add(prebuiltCombo);
        require(!prebuiltCombo.isFocusable() && prebuiltCombo.isLightWeightPopupEnabled(),
                label + " test fixture was prepared before attachment");

        glass.add(prebuiltPanel);
        requireAccessible(prebuiltCombo,
                label + " did not prepare a combo inside a prebuilt attached subtree");

        JComboBox<String> lateCombo = inaccessibleCombo("Gamma", "Delta");
        prebuiltPanel.add(lateCombo);
        requireAccessible(lateCombo,
                label + " did not prepare a combo added after the fitting tree was attached");
    }

    private static JComboBox<String> inaccessibleCombo(String first, String second) {
        JComboBox<String> combo = new JComboBox<>(new String[] { first, second });
        combo.setFocusable(false);
        combo.setLightWeightPopupEnabled(true);
        return combo;
    }

    private static Container glass(Object window) {
        try {
            Field field = window.getClass().getDeclaredField("glass");
            field.setAccessible(true);
            Object value = field.get(window);
            if (value instanceof Container container) return container;
            throw new IllegalStateException(window.getClass().getSimpleName()
                    + " glass field is not a Container");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not inspect the fitting glass pane for "
                    + window.getClass().getSimpleName(), ex);
        }
    }

    private static void requireAccessible(JComboBox<?> combo, String message) {
        require(combo.isFocusable(), message + ": keyboard focus was disabled");
        require(!combo.isLightWeightPopupEnabled(), message + ": popup remained lightweight");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
