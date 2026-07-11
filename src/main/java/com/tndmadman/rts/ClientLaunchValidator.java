package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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

        expectInvalidArgs("missing join address and port", new String[]{"--join"});
        expectInvalidArgs("missing join port", new String[]{"--join", "127.0.0.1"});
        expectInvalidArgs("join option used as host", new String[]{"--join", "--dev", "50000"});
        expectInvalidPort("non-numeric port", "abc");
        expectInvalidPort("zero port", "0");
        expectInvalidPort("port above range", "65536");

        validateLauncher(Path.of("client-remote.bat"));
        validateLauncher(Path.of("packaging/client-remote.bat"));

        System.out.println("Client launch validation passed.");
    }

    private static void validateLauncher(Path path) throws Exception {
        String content = Files.readString(path).toLowerCase(Locale.ROOT);
        expectFalse(path + " reads raw console input", content.contains("set /p"));
        expectFalse(path + " expands a host variable", content.contains("%host%"));
        expectTrue(path + " routes through run-starchem.bat", content.contains("run-starchem.bat"));
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
