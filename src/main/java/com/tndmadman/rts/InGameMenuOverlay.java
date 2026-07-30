package com.tndmadman.rts;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

/** Swing host for the feature/game-menu renderer. */
final class InGameMenuOverlay extends JComponent {
    private final GameMenuOverlay menu;
    private final Runnable resumeAction;
    private final Runnable settingsAction;
    private final Runnable tutorialAction;
    private final Runnable lobbyAction;
    private final Runnable quitAction;

    InGameMenuOverlay(boolean tutorialAvailable,
                      Runnable resumeAction,
                      Runnable settingsAction,
                      Runnable tutorialAction,
                      Runnable lobbyAction,
                      Runnable quitAction) {
        this.menu = new GameMenuOverlay(tutorialAvailable);
        this.resumeAction = resumeAction;
        this.settingsAction = settingsAction;
        this.tutorialAction = tutorialAction;
        this.lobbyAction = lobbyAction;
        this.quitAction = quitAction;
        setOpaque(false);
        setFocusable(true);
        setVisible(false);

        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                run(menu.click(event.getX(), event.getY()));
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                menu.updateHover(event.getX(), event.getY());
                repaint();
            }

            @Override public void mouseDragged(MouseEvent event) {
                menu.updateHover(event.getX(), event.getY());
                repaint();
            }
        });

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-game-menu");
        getActionMap().put("close-game-menu", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (isVisible()) run(resumeAction);
            }
        });
    }

    void open() {
        setVisible(true);
        requestFocusInWindow();
        repaint();
    }

    void close() {
        setVisible(false);
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (!isVisible()) return;
        menu.draw((Graphics2D) graphics, getWidth(), getHeight());
    }

    private void run(GameMenuOverlay.Action action) {
        switch (action) {
            case RESUME -> run(resumeAction);
            case SETTINGS -> run(settingsAction);
            case DIPLOMACY -> {
                close();
                DiplomacyDialog.open(this);
            }
            case TUTORIAL -> run(tutorialAction);
            case RETURN_TO_MAIN_MENU -> run(lobbyAction);
            case QUIT -> run(quitAction);
            case NONE -> { }
        }
    }

    private static void run(Runnable action) {
        if (action != null) action.run();
    }

    static boolean pausesSimulation(boolean soloMode) { return soloMode; }
    GameMenuOverlay menuForTest() { return menu; }
}
