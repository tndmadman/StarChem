package com.tndmadman.rts;

import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;

/** Read-only label-like text that word-wraps to a bounded menu width. */
final class MenuTextBlock extends JTextArea {
    MenuTextBlock(String text, int wrapWidth, int style, int size, Color color) {
        super(text == null ? "" : text);
        setEditable(false);
        setFocusable(false);
        setOpaque(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setForeground(color);
        Font labelFont = UIManager.getFont("Label.font");
        if (labelFont != null) setFont(labelFont.deriveFont(style, (float)size));
        else setFont(getFont().deriveFont(style, (float)size));
        setBorder(null);
        setMargin(new Insets(0, 0, 0, 0));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        int width = Math.max(80, wrapWidth);
        setSize(new Dimension(width, Short.MAX_VALUE));
        Dimension preferred = super.getPreferredSize();
        int height = Math.max(getFontMetrics(getFont()).getHeight(), preferred.height);
        setPreferredSize(new Dimension(width, height));
        setMinimumSize(new Dimension(1, height));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }
}
