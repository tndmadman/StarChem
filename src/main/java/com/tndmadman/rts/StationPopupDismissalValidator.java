package com.tndmadman.rts;

import javax.swing.JPopupMenu;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Regression for a native station overview lingering above full-frame dialogs. */
public final class StationPopupDismissalValidator {
    private StationPopupDismissalValidator() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(StationPopupDismissalValidator::validate);
        System.out.println("StarChem station overview popup dismissal passed.");
    }

    static void validate() {
        JPanel game = new JPanel();
        JPanel otherGame = new JPanel();
        TrackedPopup first = new TrackedPopup();
        TrackedPopup second = new TrackedPopup();
        TrackedPopup other = new TrackedPopup();

        StationControlMenu.trackPopup(game, first);
        StationControlMenu.trackPopup(otherGame, other);
        StationControlMenu.trackPopup(game, second);
        require(first.hideCalls == 1,
                "Opening a second station overview failed to dismiss the first.");
        require(second.hideCalls == 0,
                "The newly opened station overview was closed accidentally.");
        require(other.hideCalls == 0,
                "A popup belonging to another game panel was dismissed.");

        // Opening an in-frame menu invokes this same cleanup, regardless of
        // whether Swing's MenuSelectionManager knows about the popup.
        StationControlMenu.dismiss(game);
        require(second.hideCalls == 1,
                "Full-frame UI did not explicitly dismiss the selected-station overview.");
        StationControlMenu.dismiss(game);
        require(second.hideCalls == 1,
                "Dismissal is not idempotent.");
        StationControlMenu.dismiss(otherGame);
        require(other.hideCalls == 1,
                "Independent game panel did not dismiss its own popup.");
        StationControlMenu.dismiss(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    /** Count hide requests without constructing an operating-system popup in headless CI. */
    private static final class TrackedPopup extends JPopupMenu {
        int hideCalls;
        @Override public void setVisible(boolean visible) {
            if (!visible) hideCalls++;
        }
    }
}
