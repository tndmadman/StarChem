package com.tndmadman.rts;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** macOS integration coverage for Keychain credential storage without argv exposure. */
public final class MacCredentialVaultValidator {
    private static final String VAULT_MODE = "starchem.credentialVault";
    private static final String VAULT_PATH = "starchem.credentialVaultPath";
    private static final String SESSION_STORE = "starchem.sessionStore";

    private MacCredentialVaultValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("macOS Keychain credential validation passed.");
    }

    static void validate() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        require(os.contains("mac"), "validator requires macOS");
        require(Files.isExecutable(Path.of("/usr/bin/security")), "/usr/bin/security is unavailable");

        String previousMode = System.getProperty(VAULT_MODE);
        String previousPath = System.getProperty(VAULT_PATH);
        String previousStore = System.getProperty(SESSION_STORE);
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        String key = "mac-keychain-validator-" + UUID.randomUUID();
        String secret = "StarChem-keychain-canary-" + UUID.randomUUID();
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(secret.getBytes(StandardCharsets.UTF_8));
        AtomicBoolean observedSecurityProcess = new AtomicBoolean();

        try {
            System.setProperty(VAULT_MODE, "auto");
            System.clearProperty(SESSION_STORE);
            System.setProperty(VAULT_PATH,
                    Path.of(System.getProperty("java.io.tmpdir"), "starchem-keychain-validator-" + UUID.randomUUID())
                            .toString());
            ClientCredentialVault.resetForTests();
            ClientCredentialVault.setProcessObserverForTests((process, command) ->
                    inspectArguments(process, command, secret, encoded, observedSecurityProcess));
            System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));

            ClientCredentialVault.save(key, secret);
            require("macOS Keychain".equals(ClientCredentialVault.backendName()),
                    "validator did not select the macOS Keychain backend");
            require(observedSecurityProcess.get(), "the credential helper process was not observed");
            require(secret.equals(ClientCredentialVault.load(key)), "saved Keychain credential did not load");

            ClientCredentialVault.resetForTests();
            require(secret.equals(ClientCredentialVault.load(key)),
                    "Keychain credential did not survive a vault restart");
            ClientCredentialVault.delete(key);
            require(ClientCredentialVault.load(key) == null, "Keychain credential was not deleted");

            String stdout = capturedOut.toString(StandardCharsets.UTF_8);
            String stderr = capturedErr.toString(StandardCharsets.UTF_8);
            require(!stdout.contains(secret) && !stdout.contains(encoded), "credential leaked to stdout");
            require(!stderr.contains(secret) && !stderr.contains(encoded), "credential leaked to stderr");

            String oversized = secret.repeat(300);
            try {
                ClientCredentialVault.save(key + "-oversized", oversized);
                throw new IllegalStateException("oversized credential was accepted");
            } catch (IllegalArgumentException expected) {
                require(!String.valueOf(expected.getMessage()).contains(secret),
                        "credential leaked into a validation exception");
            }
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
            try { ClientCredentialVault.delete(key); } catch (RuntimeException ignored) { }
            restore(VAULT_MODE, previousMode);
            restore(VAULT_PATH, previousPath);
            restore(SESSION_STORE, previousStore);
            ClientCredentialVault.resetForTests();
        }
    }

    private static void inspectArguments(Process process, List<String> command, String secret,
                                         String encoded, AtomicBoolean observedSecurityProcess) {
        assertNoSecret(command, secret, encoded, "requested command arguments");
        require(command.equals(List.of("/usr/bin/security", "-i")),
                "macOS Keychain save did not use security interactive mode");
        ProcessHandle.Info info = process.info();
        String executable = info.command().orElse("");
        String commandLine = info.commandLine().orElse("");
        assertNoSecret(List.of(executable, commandLine), secret, encoded, "spawned process arguments");
        info.arguments().ifPresent(arguments ->
                assertNoSecret(List.of(arguments), secret, encoded, "spawned process arguments"));
        observedSecurityProcess.set(true);
    }

    private static void assertNoSecret(List<String> values, String secret, String encoded, String location) {
        for (String value : values) {
            if (value == null) continue;
            require(!value.contains(secret) && !value.contains(encoded), "credential leaked into " + location);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
