package com.tndmadman.rts;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.JViewport;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;

/** Guard custom solo controls against DPI-scale/long-input horizontal clipping. */
public final class LobbyLayoutValidator {
    private LobbyLayoutValidator() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(LobbyLayoutValidator::validate);
        System.out.println("StarChem lobby viewport, wrapped help and Starting Prospectors input passed.");
    }

    static void validate() {
        LobbyPanel lobby = new LobbyPanel(null);
        JScrollPane settings = findSettingsScroll(lobby);
        require(settings != null, "Settings scroll pane is missing.");
        require(settings.getViewport().getView() instanceof Scrollable scrollable
                        && scrollable.getScrollableTracksViewportWidth(),
                "Settings do not track viewport width; right-hand controls can be clipped.");

        JSpinner miner = find(settings.getViewport().getView(), JSpinner.class);
        require(miner != null, "Starting Prospectors field is missing.");
        require(miner.getEditor() instanceof JSpinner.NumberEditor, "Miner count is not a numeric editor.");
        JFormattedTextField input = ((JSpinner.NumberEditor) miner.getEditor()).getTextField();
        require(input.getColumns() == 3, "Miner count editor is too wide.");

        int baselineWidth = miner.getPreferredSize().width;
        input.setText("123123123123123123123");
        require(miner.getPreferredSize().width <= baselineWidth + 6,
                "Long miner input inflated the width of the entire settings grid.");

        input.setText("10");
        try { miner.commitEdit(); }
        catch (java.text.ParseException ex) {
            throw new IllegalStateException("Cannot enter 10 as the starting fleet count.", ex);
        }
        require(((Number) miner.getValue()).intValue() == 10, "Custom solo miner count did not commit 10.");

        for (Component c : ((Container) settings.getViewport().getView()).getComponents()) {
            if (c instanceof JTextArea help) {
                require(help.getLineWrap() && help.getWrapStyleWord(),
                        "Long settings description is not wrapped.");
            }
        }
        require(lobby.getPreferredSize().width < 1600,
                "Launcher settings still force an excessively wide window.");

        // The game centers a 740–760px menu inside normal 900–1280px
        // windows. Exercise the user-reported failure: large -> small ->
        // large, twice, as well as a narrower nonstandard viewport.
        int originalEditorWidth = -1;
        for (int width : new int[]{760, 740, 760, 740, 760, 640, 760}) {
            lobby.setSize(new Dimension(width, 720));
            layoutTree(lobby);
            JViewport viewport = settings.getViewport();
            int visibleWidth = viewport.getWidth();
            require(visibleWidth > 0,
                    "The settings viewport did not lay out at menu width " + width);
            require(viewport.getView().getWidth() == visibleWidth,
                    "Settings viewport remained at an obsolete width after resize to " + width
                            + ": view=" + viewport.getView().getWidth()
                            + " viewport=" + visibleWidth);
            require(miner.getWidth() > 0, "Starting Prospectors has zero width at " + width);
            java.awt.Point minerOrigin = SwingUtilities.convertPoint(
                    miner, 0, 0, viewport.getView());
            require(minerOrigin.x >= 0 && minerOrigin.x + miner.getWidth() <= visibleWidth,
                    "Starting Prospectors is horizontally clipped at " + width
                            + ": right=" + (minerOrigin.x + miner.getWidth())
                            + " viewport=" + visibleWidth);
            if (width == 760) {
                if (originalEditorWidth == -1) originalEditorWidth = miner.getWidth();
                else require(Math.abs(miner.getWidth() - originalEditorWidth) <= 2,
                        "Starting Prospectors failed to re-expand after shrink: before="
                                + originalEditorWidth + ", after=" + miner.getWidth());
            }
        }
    }

    private static JScrollPane findSettingsScroll(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JScrollPane pane
                    && pane.getViewport().getView() instanceof javax.swing.JPanel
                    && pane.getViewport().getView() instanceof Scrollable) return pane;
            if (child instanceof Container nested) {
                JScrollPane found = findSettingsScroll(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T find(Component component, Class<T> kind) {
        if (kind.isInstance(component)) return kind.cast(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, kind);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) layoutTree(nested);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
