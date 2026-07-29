package com.tndmadman.rts;

final class LanDiscoveryBootstrap {
    private static LanDiscoveryServer server;

    private LanDiscoveryBootstrap() { }

    static synchronized void initializeFromCommandLine() {
        if (server != null) return;
        String command = System.getProperty("sun.java.command", "");
        String[] args = command.isBlank() ? new String[0] : command.split("\\s+");
        boolean dedicated = false;
        boolean disabled = false;
        int port = 50000;
        String name = "StarChem Server";
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--server".equals(arg)) {
                dedicated = true;
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    try { port = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) { }
                }
            } else if ("--name".equals(arg) || "--id".equals(arg)) {
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) name = args[++i];
            } else if ("--no-lan-discovery".equals(arg)) {
                disabled = true;
            }
        }
        if (!dedicated || disabled || port < 1 || port > 65535) return;
        LanDiscoveryServer responder = new LanDiscoveryServer(name, port);
        if (!responder.start()) return;
        server = responder;
        Runtime.getRuntime().addShutdownHook(new Thread(LanDiscoveryBootstrap::shutdown,
                "starchem-lan-discovery-shutdown"));
    }

    static synchronized void shutdown() {
        LanDiscoveryServer active = server;
        server = null;
        if (active != null) active.close();
    }
}
