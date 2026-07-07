package com.tndmadman.rts;

import javax.swing.SwingUtilities;

public final class App {
    private App() { }
    public static void main(String[] args) {
        Config config = Config.parse(args);
        if (config.dedicatedServerMode()) {
            runServer(config);
            return;
        }
        SwingUtilities.invokeLater(() -> new GameFrame(config).setVisible(true));
    }

    private static void runServer(Config config) {
        try {
            HeadlessGameServer server = HeadlessGameServer.start(config);
            long last = System.nanoTime();
            while (true) {
                long now = System.nanoTime();
                double dt = Math.min(0.05, (now - last) / 1_000_000_000.0);
                last = now;
                server.tick(dt);
                Thread.sleep(16);
            }
        } catch (Exception ex) {
            System.err.println("Server failed: " + ex.getMessage());
        }
    }
}
