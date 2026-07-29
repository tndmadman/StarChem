package com.tndmadman.rts;

import javax.swing.*;

/** Validates StarChem scrollbar chrome and deterministic hover tooltip routing. */
public final class GameSwingUiValidator {
    private GameSwingUiValidator() { }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(GameSwingUiValidator::validate);
        System.out.println("StarChem game-styled scrollbars and immediate hover tooltip validation passed.");
    }

    private static void validate() {
        GameSwingUi.install();

        require(GameSwingUi.GameScrollBarUI.class.getName().equals(UIManager.get("ScrollBarUI")),
                "Swing scrollbar UI is not routed through the StarChem renderer.");
        require(GameSwingUi.GameToolTipUI.class.getName().equals(UIManager.get("ToolTipUI")),
                "Swing tooltip UI is not routed through the StarChem renderer.");

        JScrollBar scrollBar = new JScrollBar(JScrollBar.VERTICAL);
        scrollBar.updateUI();
        require(scrollBar.getUI() instanceof GameSwingUi.GameScrollBarUI,
                "A newly created scrollbar still uses the platform look and feel.");

        JToolTip tooltip = new JToolTip();
        tooltip.setTipText("Required resources: Iron 10");
        tooltip.updateUI();
        require(tooltip.getUI() instanceof GameSwingUi.GameToolTipUI,
                "A newly created tooltip still uses the platform look and feel.");
        require(!tooltip.isOpaque(), "Game tooltip must keep rounded transparent corners.");

        ToolTipManager manager = ToolTipManager.sharedInstance();
        require(!manager.isEnabled(),
                "The delayed platform tooltip manager must be disabled in favor of deterministic routing.");
        require(manager.getInitialDelay() == 0 && manager.getReshowDelay() == 0,
                "Tooltip delays must remain at zero.");

        JButton disabledRow = new JButton("Completed research");
        disabledRow.setToolTipText("Required resources: Carbon 20");
        disabledRow.setEnabled(false);
        require(GameSwingUi.tooltipOwner(disabledRow) == disabledRow,
                "Disabled production rows must still resolve as tooltip owners.");
        require(GameSwingUi.customTooltipRoutingInstalledForTest(),
                "Custom tooltip routing was not installed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Game Swing UI validation failed: " + message);
    }
}
