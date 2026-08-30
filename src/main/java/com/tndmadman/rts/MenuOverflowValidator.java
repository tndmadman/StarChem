package com.tndmadman.rts;

import javax.swing.JLabel;
import javax.swing.JScrollPane;
import java.awt.Dimension;

/** Headless regression check for scrollable popup menus with long descriptive text. */
public final class MenuOverflowValidator {
    private MenuOverflowValidator() { }

    public static void main(String[] args) {
        MenuScrollPanel content = new MenuScrollPanel();
        content.add(new JLabel("This deliberately long menu description must not force a station popup wider than its viewport or require a horizontal scrollbar."));

        JScrollPane scroll = new JScrollPane(content);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(520, 240));
        scroll.setSize(520, 240);
        scroll.doLayout();

        if (!content.getScrollableTracksViewportWidth()) {
            throw new AssertionError("Menu content must track the viewport width.");
        }
        if (content.getScrollableTracksViewportHeight()) {
            throw new AssertionError("Menu content must remain vertically scrollable.");
        }
        if (scroll.getHorizontalScrollBarPolicy() != JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            throw new AssertionError("Horizontal menu scrolling must remain disabled.");
        }

        System.out.println("Menu overflow validation passed.");
    }
}
