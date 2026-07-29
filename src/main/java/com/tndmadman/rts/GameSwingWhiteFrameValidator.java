package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pixel-level guard against operating-system white frames around game UI chrome. */
public final class GameSwingWhiteFrameValidator {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/tndmadman/rts/GameSwingUi.java");

    private GameSwingWhiteFrameValidator() { }

    public static void main(String[] args) throws Exception {
        GameSwingUi.install();
        SwingUtilities.invokeAndWait(() -> {
            validateScrollbarPixels();
            validateTooltipPixels();
            validateFallbackBackground();
        });
        validateDedicatedTooltipWindowSource();
        System.out.println("Scrollbar and tooltip white-frame pixel validation passed.");
    }

    private static void validateScrollbarPixels() {
        JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL, 18, 28, 0, 100);
        bar.updateUI();
        bar.setSize(14, 190);
        bar.doLayout();

        require(bar.getUI() instanceof GameSwingUi.GameScrollBarUI,
                "Scrollbar is not using the StarChem UI delegate.");
        require(bar.isOpaque(),
                "Scrollbar must be opaque so the operating-system background cannot bleed through.");

        BufferedImage image = new BufferedImage(14, 190, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        bar.paint(graphics);
        graphics.dispose();

        assertNoNearWhitePixels(image, false, "scrollbar");
        assertDark(image.getRGB(0, 0), "Scrollbar top-left edge was not fully painted.");
        assertDark(image.getRGB(image.getWidth() - 1, image.getHeight() - 1),
                "Scrollbar bottom-right edge was not fully painted.");
    }

    private static void validateTooltipPixels() {
        JComponent panel = GameSwingUi.tooltipPanelForTest(
                "<html><b>BUILD CORVETTE</b><br>Required resources: Iron 20, Carbon 8</html>");
        Dimension preferred = panel.getPreferredSize();
        panel.setSize(preferred);
        panel.doLayout();

        BufferedImage image = new BufferedImage(
                Math.max(1, preferred.width), Math.max(1, preferred.height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(AlphaComposite.Clear);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setComposite(AlphaComposite.SrcOver);
        panel.paint(graphics);
        graphics.dispose();

        assertNoNearWhitePixels(image, true, "tooltip");
        require(alpha(image.getRGB(0, image.getHeight() - 1)) < 48,
                "Rounded tooltip corner is opaque instead of transparent.");
        int center = image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
        require(alpha(center) > 0, "Tooltip center was not painted.");
    }

    private static void validateFallbackBackground() {
        Color fallback = GameSwingUi.tooltipFallbackBackgroundForTest();
        require(!(fallback.getRed() > 220 && fallback.getGreen() > 220 && fallback.getBlue() > 220),
                "Non-translucent tooltip fallback is still a white window.");
    }

    private static void validateDedicatedTooltipWindowSource() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        require(source.contains("new JWindow"),
                "Hover details are not using a dedicated borderless tooltip window.");
        require(!source.contains("PopupFactory.getSharedInstance().getPopup"),
                "Hover details still use Swing PopupFactory, which can create a white heavyweight frame.");
        require(source.contains("g.fillRect(0, 0, component.getWidth(), component.getHeight())"),
                "Scrollbar does not paint its complete component area.");
    }

    private static void assertNoNearWhitePixels(
            BufferedImage image, boolean ignoreTransparent, String componentName) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if (ignoreTransparent && alpha(argb) < 32) continue;
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;
                if (red > 247 && green > 247 && blue > 247) {
                    throw new IllegalStateException(
                            componentName + " rendered a near-white frame pixel at " + x + "," + y + ".");
                }
            }
        }
    }

    private static void assertDark(int argb, String message) {
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        require(red < 80 && green < 100 && blue < 120, message);
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
