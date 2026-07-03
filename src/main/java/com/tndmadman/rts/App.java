package com.tndmadman.rts;

import javax.swing.SwingUtilities;

public final class App {
    private App() { }
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new GameFrame(Config.parse(args)).setVisible(true));
    }
}
