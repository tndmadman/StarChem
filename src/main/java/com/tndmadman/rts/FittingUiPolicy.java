package com.tndmadman.rts;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.event.ContainerEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JComboBox;
import javax.swing.JPopupMenu;

/** Keeps fitting combo popups above glass-pane overlays and keyboard-accessible. */
final class FittingUiPolicy {
    private static final AtomicBoolean LISTENER_INSTALLED = new AtomicBoolean();
    private static final String FITTING_WINDOW = ShipFittingWindow.class.getName() + "$";
    private static final String FIT_STUDIO = ShipFitStudioWindow.class.getName() + "$";

    private FittingUiPolicy() { }

    static void install() {
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        if (!LISTENER_INSTALLED.compareAndSet(false, true)) return;
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof ContainerEvent containerEvent)
                    || containerEvent.getID() != ContainerEvent.COMPONENT_ADDED) return;
            Component child = containerEvent.getChild();
            if (isFittingTree(containerEvent.getContainer()) || isFittingTree(child)) {
                prepareTree(child);
            }
        }, AWTEvent.CONTAINER_EVENT_MASK);
    }

    static <T> JComboBox<T> prepare(JComboBox<T> combo) {
        if (combo != null) {
            combo.setFocusable(true);
            combo.setLightWeightPopupEnabled(false);
        }
        return combo;
    }

    static void prepareTree(Component component) {
        if (component == null) return;
        if (component instanceof JComboBox<?> combo) prepare(combo);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) prepareTree(child);
        }
    }

    private static boolean isFittingTree(Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            String name = current.getClass().getName();
            if (name.startsWith(FITTING_WINDOW) || name.startsWith(FIT_STUDIO)) return true;
        }
        return false;
    }
}
