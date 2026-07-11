package com.tndmadman.rts;

import java.nio.file.Path;
import javax.swing.SwingUtilities;

public final class App {
    private App() { }
    public static void main(String[] args) {
        if (versionRequested(args)) {
            System.out.println(BuildInfo.display());
            return;
        }
        System.out.println(BuildInfo.display());
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("Invalid startup arguments: " + ex.getMessage());
            System.exit(2);
            return;
        }
        if (config.devMode) RulesValidator.validateOrThrow(Path.of("config/starchem.json"));
        ResourceNetDebug.resetLogs(config);
        if (config.dedicatedServerMode()) {
            int exitCode = runServer(config);
            if (exitCode != 0) System.exit(exitCode);
            return;
        }
        SwingUtilities.invokeLater(() -> new GameFrame(config).setVisible(true));
    }

    private static boolean versionRequested(String[] args) {
        return args != null && args.length == 1
                && ("--version".equalsIgnoreCase(args[0]) || "-V".equals(args[0]));
    }

    private static int runServer(Config config) {
        HeadlessGameServer server = null;
        try {
            server = HeadlessGameServer.start(config);
            long last = System.nanoTime();
            while (true) {
                long now = System.nanoTime();
                double dt = Math.min(0.05, (now - last) / 1_000_000_000.0);
                last = now;
                server.tick(dt);
                Thread.sleep(16);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            System.err.println("Server interrupted; shutting down.");
            return 0;
        } catch (Exception ex) {
            System.err.println("Server failed:");
            ex.printStackTrace(System.err);
            return 1;
        } finally {
            if (server != null) server.stop();
        }
    }
}
