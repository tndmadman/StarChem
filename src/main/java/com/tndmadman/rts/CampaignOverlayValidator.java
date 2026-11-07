package com.tndmadman.rts;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;

/** Headless EDT regression for modal campaign controls (no native dialog/windows). */
public final class CampaignOverlayValidator {
    private CampaignOverlayValidator() { }

    public static void main(String[] args) throws Exception {
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger focusRestored = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            CampaignOverlayController overlay =
                    new CampaignOverlayController(focusRestored::incrementAndGet);
            overlay.setBounds(0, 0, 900, 720);
            overlay.showBusy("Saving Campaign", "Writing and verifying archive...");
            require(overlay.isOpen(), "busy campaign interaction not visible");
            overlay.cancel();
            require(overlay.isOpen(), "Escape dismissed an in-progress archive operation");
            overlay.hideDialog();
            require(!overlay.isOpen(), "completed operation overlay stayed visible");

            overlay.showChoices("Exit StarChem", "Save before exiting?",
                    CampaignOverlayController.choice("Save & Exit", () -> { }),
                    CampaignOverlayController.choice("Exit Without Saving", () -> { }),
                    CampaignOverlayController.choice("Cancel", cancelled::incrementAndGet));
            require(overlay.isOpen(), "exit confirmation not in frame");
            overlay.cancel();
            require(!overlay.isOpen(), "Cancel did not hide campaign confirmation");
            require(cancelled.get() == 1, "Cancel did not execute its callback exactly once");
            overlay.cancel();
            require(cancelled.get() == 1, "hidden dialog repeated Cancel callback");

            overlay.showChoices("Load Campaign", "Pick a slot",
                    CampaignOverlayController.choice("Back", () -> { }),
                    CampaignOverlayController.choice("Cancel", null));
            require(overlay.isOpen(), "follow-up in-frame dialog did not render");
            overlay.hideDialog();
        });
        System.out.println("StarChem campaign overlay validator passed (EDT, Escape, busy and cancel).");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
