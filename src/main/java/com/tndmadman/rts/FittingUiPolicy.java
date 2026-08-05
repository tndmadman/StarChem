package com.tndmadman.rts;

import javax.swing.JComboBox;
import javax.swing.JPopupMenu;

/** Keeps fitting combo popups above the root glass-pane overlays. */
final class FittingUiPolicy {
    private FittingUiPolicy() { }

    static void install() {
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
    }

    static <T> JComboBox<T> prepare(JComboBox<T> combo) {
        if (combo != null) combo.setLightWeightPopupEnabled(false);
        return combo;
    }
}
