package com.tndmadman.rts;

public final class ClientLaunchValidator {
    private ClientLaunchValidator() { }

    public static void main(String[] args) throws Exception {
        expectEquals("blank host default", "127.0.0.1", Config.parseHost("   "));
        expectEquals("DNS host", "server.example", Config.parseHost(" server.example "));
        expectEquals("raw IPv6", "::1", Config.parseHost("::1"));
        expectEquals("bracketed IPv6", "::1", Config.parseHost("[::1]"));
        expectEquals("IPv6 zone", "fe80::1%12", Config.parseHost("[fe80::1%12]"));

        expectInvalidHost("internal whitespace", "server example");
        expectInvalidHost("batch command separator", "server.example&whoami");
        expectInvalidHost("pipe", "server.example|more");
        expectInvalidHost("mismatched IPv6 brackets", "[::1");
        expectInvalidHost("empty IPv6 brackets", "[]");

        Config client = Config.parse(new String[]{"--join", "127.0.0.1", "50000", "--name", "Client"});
        expectTrue("client mode", client.clientMode());
        expectEquals("client port", 50000, client.serverAddress.getPort());
        expectEquals("blank programmatic host", "127.0.0.1", Config.join("Client", "", 50000).serverAddress.getHostString());
        Config doubleGalaxy = Config.parse(new String[]{"--host", "50000", "--galaxy-copies", "2"});
        expectEquals("double galaxy copies", 2, doubleGalaxy.galaxyCopies);

        Config loopbackClient = Config.join("Local Client", "127.0.0.1", 50000);
        expectEquals("loopback password prompt", LobbyPanel.PasswordPromptMode.LOCAL_ACCOUNT,
                LobbyPanel.passwordPromptMode(loopbackClient));
        expectTrue("loopback password confirmation", LobbyPanel.passwordConfirmationRequired(loopbackClient));
        Config ipv6LoopbackClient = Config.join("IPv6 Local Client", "::1", 50000);
        expectEquals("IPv6 loopback password prompt", LobbyPanel.PasswordPromptMode.LOCAL_ACCOUNT,
                LobbyPanel.passwordPromptMode(ipv6LoopbackClient));
        Config remoteClient = Config.join("Remote Client", "192.0.2.10", 50000);
        expectEquals("remote password prompt", LobbyPanel.PasswordPromptMode.REMOTE_SIGN_IN,
                LobbyPanel.passwordPromptMode(remoteClient));
        expectFalse("remote password confirmation", LobbyPanel.passwordConfirmationRequired(remoteClient));

        Config server = Config.parse(new String[]{"--server", "50123", "--name", "Test Server"});
        expectTrue("dedicated server mode", server.dedicatedServerMode());
        expectEquals("dedicated server port", 50123, server.port);
        expectEquals("dedicated server name", "Test Server", server.playerName);
        Config defaultServer = Config.parse(new String[]{"--server", "--name", "Default Server"});
        expectEquals("dedicated server default port", 50000, defaultServer.port);

        expectInvalidArgs("galaxy copies below range", new String[]{"--solo", "--galaxy-copies", "0"});
        expectInvalidArgs("galaxy copies above range", new String[]{"--solo", "--galaxy-copies", "3"});
        expectInvalidArgs("non-numeric galaxy copies", new String[]{"--solo", "--galaxy-copies", "many"});
        expectInvalidArgs("unknown option", new String[]{"--sever", "50000"});
        expectInvalidArgs("missing name", new String[]{"--solo", "--name"});
        expectInvalidArgs("missing system", new String[]{"--solo", "--system"});
        expectInvalidArgs("missing host port", new String[]{"--host"});

        expectInvalidArgs("missing join address and port", new String[]{"--join"});
        expectInvalidArgs("missing join port", new String[]{"--join", "127.0.0.1"});
        expectInvalidArgs("join option used as host", new String[]{"--join", "--dev", "50000"});
        expectInvalidPort("non-numeric port", "abc");
        expectInvalidPort("zero port", "0");
        expectInvalidPort("port above range", "65536");

        expectEquals("duplicate active-session notice",
                "Duplicate player names are not allowed on this server. Choose a different name.",
                GameFrame.connectionNotice("Session is already active on another connection. Waiting to resume."));
        expectEquals("unrelated connection status", "",
                GameFrame.connectionNotice("Connection interrupted. Reconnecting to server."));
        expectEquals("null connection status", "", GameFrame.connectionNotice(null));

        ClientSessionPropertiesStoreValidator.validate();
        System.out.println("Client launch, login prompt, and coordinated session-store validation passed.");
    }

    private static void expectInvalidHost(String name, String host) {
        try {
            Config.parseHost(host);
            throw new IllegalStateException("Expected invalid host: " + name);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectInvalidArgs(String name, String[] args) {
        try {
            Config.parse(args);
            throw new IllegalStateException("Expected invalid arguments: " + name);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectInvalidPort(String name, String port) {
        try {
            Config.parsePort(port);
            throw new IllegalStateException("Expected invalid port: " + name);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectTrue(String name, boolean actual) {
        if (!actual) throw new IllegalStateException("Expected true: " + name);
    }

    private static void expectFalse(String name, boolean actual) {
        if (actual) throw new IllegalStateException("Expected false: " + name);
    }

    private static void expectEquals(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(name + " expected " + expected + " but was " + actual);
        }
    }
}
