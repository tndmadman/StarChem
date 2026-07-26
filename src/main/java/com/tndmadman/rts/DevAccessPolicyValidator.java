package com.tndmadman.rts;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class DevAccessPolicyValidator {
    private static final String TOKEN = "dev-token-0123456789abcdef";

    private DevAccessPolicyValidator() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "child-token-file".equals(args[0])) {
            runTokenFileChild(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        InetAddress remote = InetAddress.getByName("203.0.113.8");

        expectDenied("server dev mode disabled", false, false, remote, true, TOKEN, TOKEN);
        expectDenied("client did not request dev access", true, false, remote, false, TOKEN, TOKEN);
        expectAllowed("local graphical host client", true, false, loopback, true, "", "");
        expectDenied("dedicated loopback without credential", true, true, loopback, true, "", "");
        expectDenied("remote client without configured credential", true, false, remote, true, "", TOKEN);
        expectDenied("remote client without supplied credential", true, false, remote, true, TOKEN, "");
        expectDenied("remote client with wrong credential", true, false, remote, true, TOKEN, TOKEN + "x");
        expectAllowed("remote client with matching credential", true, false, remote, true, TOKEN, TOKEN);
        expectAllowed("dedicated remote client with matching credential", true, true, remote, true, TOKEN, TOKEN);

        expectEquals("normalized valid token", TOKEN, DevAccessPolicy.requireToken("  " + TOKEN + "  "));
        expectInvalid("short token", "short");
        expectInvalid("delimiter token", "0123456789abcdef|bad");
        expectInvalid("space token", "0123456789abcdef bad");

        Config server = parseLegacy(new String[]{"--server", "50000", "--dev", "--dev-token", TOKEN});
        expectTrue("server dev mode", server.devMode);
        expectEquals("server token", TOKEN, server.devToken);
        Config client = parseLegacy(new String[]{"--join", "127.0.0.1", "50000", "--dev", "--dev-token", TOKEN});
        expectTrue("client mode", client.clientMode());
        expectEquals("client token", TOKEN, client.devToken);
        expectMissingTokenValueRejected();
        validateTokenFileSource();

        expectTrue("unauthorized client request is pending", DevAccessRequestState.pending(true, false));
        expectFalse("authorized client request is not pending", DevAccessRequestState.pending(true, true));
        expectFalse("operator grant does not fabricate a request", DevAccessRequestState.pending(false, true));

        Set<String> requests = new LinkedHashSet<>();
        requests.add("P1");
        requests.add("P2");
        DevAccessRequestState.resolve(requests, "P1");
        expectFalse("resolved request removed", requests.contains("P1"));
        expectTrue("unrelated pending request retained", requests.contains("P2"));

        DevPeerAccess pending = new DevPeerAccess("P1", "Pending", true, false, false);
        DevPeerAccess granted = new DevPeerAccess("P2", "Granted", true, true, false);
        expectTrue("pending peer reported as requested", pending.requested());
        expectFalse("granted peer not reported as pending", granted.requested());

        System.out.println("Dev access policy, protected token source, and request-state validation passed.");
    }

    private static void validateTokenFileSource() throws Exception {
        Path dir = Files.createTempDirectory("starchem-dev-token-");
        try {
            Path tokenFile = secureTokenFile(dir.resolve("dev-token"), TOKEN + System.lineSeparator());
            Config server = Config.parse(new String[]{"--server", "50000", "--dev", "--dev-token-file", tokenFile.toString()});
            expectEquals("token file value", TOKEN, server.devToken);

            expectConfigRejected("conflicting token sources", new String[]{"--server", "50000", "--dev",
                    "--dev-token-file", tokenFile.toString(), "--dev-token", TOKEN});
            expectConfigRejected("repeated token file", new String[]{"--server", "50000", "--dev",
                    "--dev-token-file", tokenFile.toString(), "--dev-token-file", tokenFile.toString()});

            Path multiline = secureTokenFile(dir.resolve("multiline"), TOKEN + "\nsecond-token\n");
            expectConfigRejected("multiline token file", new String[]{"--server", "50000", "--dev",
                    "--dev-token-file", multiline.toString()});

            Path oversized = dir.resolve("oversized");
            Files.writeString(oversized, "x".repeat(513), StandardCharsets.UTF_8);
            securePermissions(oversized);
            expectConfigRejected("oversized token file", new String[]{"--server", "50000", "--dev",
                    "--dev-token-file", oversized.toString()});

            Path secretNamed = dir.resolve("must-not-leak-" + TOKEN);
            try {
                Config.parse(new String[]{"--server", "50000", "--dev", "--dev-token-file", secretNamed.toString()});
                throw new IllegalStateException("Expected missing token file rejection.");
            } catch (IllegalArgumentException expected) {
                if (expected.getMessage() == null || !expected.getMessage().contains(secretNamed.toString())) {
                    throw new IllegalStateException("Missing token file diagnostic omitted the path.");
                }
            }

            PosixFileAttributeView posix = Files.getFileAttributeView(tokenFile, PosixFileAttributeView.class);
            if (posix != null) {
                Files.setPosixFilePermissions(tokenFile, EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));
                expectConfigRejected("group-readable token file", new String[]{"--server", "50000", "--dev",
                        "--dev-token-file", tokenFile.toString()});
                securePermissions(tokenFile);
            }

            validateChildProcessArguments(tokenFile);
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (Exception ignored) { }
                });
            }
        }
    }

    private static void validateChildProcessArguments(Path tokenFile) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                DevAccessPolicyValidator.class.getName(), "child-token-file", "--server", "50000", "--dev",
                "--dev-token-file", tokenFile.toString()).redirectErrorStream(true).start();
        byte[] outputBytes = process.getInputStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Token-file child process timed out.");
        }
        String output = new String(outputBytes, StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Token-file child process failed: " + output.trim());
        }
        if (output.contains(TOKEN)) {
            throw new IllegalStateException("Developer token leaked through child process output.");
        }
    }

    private static void runTokenFileChild(String[] args) {
        Config config = Config.parse(args);
        expectEquals("child token file value", TOKEN, config.devToken);
        for (String argument : ProcessHandle.current().info().arguments().orElse(new String[0])) {
            if (argument.contains(TOKEN)) {
                throw new IllegalStateException("Developer token appeared in child process arguments.");
            }
        }
        System.out.println("Protected token child validation passed.");
    }

    private static Path secureTokenFile(Path path, String content) throws Exception {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        securePermissions(path);
        return path;
    }

    private static void securePermissions(Path path) throws Exception {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
    }

    private static Config parseLegacy(String[] args) {
        PrintStream previous = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Config config = Config.parse(args);
            String warning = captured.toString(StandardCharsets.UTF_8);
            if (!warning.contains("--dev-token-file") || warning.contains(TOKEN)) {
                throw new IllegalStateException("Legacy token warning is missing or leaked the token.");
            }
            return config;
        } finally {
            System.setErr(previous);
        }
    }

    private static void expectConfigRejected(String name, String[] args) {
        try {
            Config.parse(args);
            throw new IllegalStateException("Expected invalid configuration: " + name);
        } catch (IllegalArgumentException expected) {
            if (expected.getMessage() != null && expected.getMessage().contains(TOKEN)) {
                throw new IllegalStateException("Configuration error leaked the developer token: " + name);
            }
        }
    }

    private static void expectAllowed(String name, boolean hostDevMode, boolean dedicatedServer, InetAddress address,
                                      boolean requestedDev, String configuredToken, String suppliedToken) {
        if (!DevAccessPolicy.authorize(hostDevMode, dedicatedServer, address, requestedDev, configuredToken, suppliedToken)) {
            throw new IllegalStateException("Expected allowed: " + name);
        }
    }

    private static void expectDenied(String name, boolean hostDevMode, boolean dedicatedServer, InetAddress address,
                                     boolean requestedDev, String configuredToken, String suppliedToken) {
        if (DevAccessPolicy.authorize(hostDevMode, dedicatedServer, address, requestedDev, configuredToken, suppliedToken)) {
            throw new IllegalStateException("Expected denied: " + name);
        }
    }

    private static void expectInvalid(String name, String token) {
        try {
            DevAccessPolicy.requireToken(token);
            throw new IllegalStateException("Expected invalid token: " + name);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectMissingTokenValueRejected() {
        try {
            Config.parse(new String[]{"--host", "50000", "--dev", "--dev-token"});
            throw new IllegalStateException("Expected missing --dev-token value to be rejected.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            Config.parse(new String[]{"--host", "50000", "--dev", "--dev-token-file"});
            throw new IllegalStateException("Expected missing --dev-token-file value to be rejected.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectTrue(String name, boolean value) {
        if (!value) throw new IllegalStateException("Expected true: " + name);
    }

    private static void expectFalse(String name, boolean value) {
        if (value) throw new IllegalStateException("Expected false: " + name);
    }

    private static void expectEquals(String name, String expected, String actual) {
        if (!expected.equals(actual)) throw new IllegalStateException(name + ": expected " + expected + ", got " + actual);
    }
}
