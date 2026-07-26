package com.tndmadman.rts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Stores reusable client authentication material outside the ordinary session metadata file. */
final class ClientCredentialVault {
    private static final String MODE_PROPERTY = "starchem.credentialVault";
    private static final String PATH_PROPERTY = "starchem.credentialVaultPath";
    private static final String SESSION_STORE_OVERRIDE = "starchem.sessionStore";
    private static final int MAX_SECRET_BYTES = 4096;
    private static final int MAX_COMMAND_OUTPUT = 8192;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(8);
    private static Backend backend;
    private static String backendSignature = "";
    private static boolean fallbackWarningPrinted;
    private static ProcessObserver processObserverForTests;

    private ClientCredentialVault() { }

    static synchronized String load(String propertyKey) {
        if (propertyKey == null || propertyKey.isBlank()) return null;
        return backend().load(storageId(propertyKey));
    }

    static synchronized void save(String propertyKey, String secret) {
        if (propertyKey == null || propertyKey.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Credential key and secret are required.");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        try {
            if (bytes.length > MAX_SECRET_BYTES) throw new IllegalArgumentException("Saved credential is too large.");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        backend().save(storageId(propertyKey), encodeSecret(secret));
    }

    static synchronized void delete(String propertyKey) {
        if (propertyKey == null || propertyKey.isBlank()) return;
        backend().delete(storageId(propertyKey));
    }

    static synchronized String backendName() { return backend().name(); }

    static synchronized void setProcessObserverForTests(ProcessObserver observer) {
        processObserverForTests = observer;
    }

    static synchronized void resetForTests() {
        backend = null;
        backendSignature = "";
        fallbackWarningPrinted = false;
        processObserverForTests = null;
    }

    static Path fallbackRoot() {
        String override = System.getProperty(PATH_PROPERTY, "").trim();
        if (!override.isBlank()) return Path.of(override).toAbsolutePath().normalize();
        String sessionOverride = System.getProperty(SESSION_STORE_OVERRIDE, "").trim();
        if (!sessionOverride.isBlank()) {
            Path session = Path.of(sessionOverride).toAbsolutePath().normalize();
            Path fileName = session.getFileName();
            if (fileName != null) return session.resolveSibling(fileName + ".credentials");
        }
        return Path.of(System.getProperty("user.home", "."), ".starchem", "credentials")
                .toAbsolutePath().normalize();
    }

    static void ensureOwnerOnlyDirectory(Path directory) throws IOException {
        if (directory == null) throw new IOException("Credential directory is missing.");
        PrivateFileSecurity.ensurePrivateDirectory(directory);
    }

    static void ensureOwnerOnlyFile(Path file) throws IOException {
        if (file == null) throw new IOException("Credential file is missing.");
        if (!Files.exists(file)) Files.createFile(file);
        PrivateFileSecurity.secureFile(file);
    }

    static Path createOwnerOnlyTempFile(Path directory, String prefix, String suffix) throws IOException {
        if (directory == null) throw new IOException("Temporary-file directory is missing.");
        return PrivateFileSecurity.createPrivateTempFile(directory, prefix, suffix);
    }

    private static Backend backend() {
        String mode = System.getProperty(MODE_PROPERTY, "").trim().toLowerCase(Locale.ROOT);
        if (mode.isBlank() && !System.getProperty(SESSION_STORE_OVERRIDE, "").trim().isBlank()) mode = "file";
        if (mode.isBlank()) mode = "auto";
        String signature = mode + "|" + fallbackRoot() + "|" + System.getProperty("os.name", "");
        if (backend != null && signature.equals(backendSignature)) return backend;
        backendSignature = signature;
        backend = switch (mode) {
            case "file" -> fallbackBackend();
            case "memory" -> new MemoryBackend();
            case "auto" -> automaticBackend();
            default -> throw new IllegalStateException("Unknown credential vault mode: " + mode);
        };
        return backend;
    }

    private static Backend automaticBackend() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") && commandExists("powershell.exe")) {
            return new DpapiBackend(fallbackRoot().resolve("dpapi"));
        }
        if (os.contains("mac") && Files.isExecutable(Path.of("/usr/bin/security"))
                && Files.isExecutable(Path.of("/usr/bin/script"))) {
            return new MacKeychainBackend();
        }
        if ((os.contains("linux") || os.contains("unix")) && commandExists("secret-tool")
                && (!System.getenv().getOrDefault("DBUS_SESSION_BUS_ADDRESS", "").isBlank()
                || !System.getenv().getOrDefault("XDG_RUNTIME_DIR", "").isBlank())) {
            return new SecretToolBackend();
        }
        return fallbackBackend();
    }

    private static Backend fallbackBackend() {
        if (!fallbackWarningPrinted) {
            fallbackWarningPrinted = true;
            System.err.println("Secure operating-system credential storage is unavailable; "
                    + "StarChem is using an owner-only credential file fallback.");
        }
        return new FileBackend(fallbackRoot(), "owner-only file fallback");
    }

    private static String storageId(String propertyKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("StarChem client credential v1|".getBytes(StandardCharsets.UTF_8));
            digest.update(propertyKey.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private static String encodeSecret(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String decodeSecret(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Saved credential data is malformed.", ex);
        }
        if (bytes.length > MAX_SECRET_BYTES) {
            Arrays.fill(bytes, (byte) 0);
            throw new IllegalStateException("Saved credential data is too large.");
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalStateException("Saved credential data is not valid UTF-8.", ex);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static boolean commandExists(String command) {
        String path = System.getenv().getOrDefault("PATH", "");
        for (String raw : path.split(java.io.File.pathSeparator)) {
            if (raw.isBlank()) continue;
            Path candidate = Path.of(raw).resolve(command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return true;
        }
        return false;
    }

    private static CommandResult runCommand(List<String> command, String input) {
        byte[] inputBytes = input == null ? new byte[0] : input.getBytes(StandardCharsets.UTF_8);
        return runCommand(command, inputBytes);
    }

    private static CommandResult runCommand(List<String> command, byte[] inputBytes) {
        Process process = null;
        try {
            process = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true).start();
            observeProcess(process, command);
            try (OutputStream output = process.getOutputStream()) {
                output.write(inputBytes);
            }
            Process running = process;
            FutureTask<byte[]> outputTask = new FutureTask<>(() -> readBounded(running.getInputStream()));
            Thread reader = new Thread(outputTask, "starchem-credential-command-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyProcessTree(process);
                throw new IllegalStateException("Credential storage command timed out.");
            }
            byte[] output;
            try {
                output = outputTask.get(2, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException ex) {
                destroyProcessTree(process);
                throw new IllegalStateException("Credential storage command output failed.", ex);
            }
            try {
                return new CommandResult(process.exitValue(), new String(output, StandardCharsets.UTF_8).trim());
            } finally {
                Arrays.fill(output, (byte) 0);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start credential storage command.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) destroyProcessTree(process);
            throw new IllegalStateException("Credential storage command was interrupted.", ex);
        } finally {
            Arrays.fill(inputBytes, (byte) 0);
        }
    }

    private static void runMacKeychainSave(String id, String encodedSecret) {
        Path statusFile = null;
        Process process = null;
        byte[] passwordBytes = encodedSecret.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = new byte[(passwordBytes.length + 1) * 2];
        try {
            int offset = 0;
            System.arraycopy(passwordBytes, 0, inputBytes, offset, passwordBytes.length);
            offset += passwordBytes.length;
            inputBytes[offset++] = (byte) '\n';
            System.arraycopy(passwordBytes, 0, inputBytes, offset, passwordBytes.length);
            inputBytes[inputBytes.length - 1] = (byte) '\n';

            statusFile = PrivateFileSecurity.createPrivateTempFile(
                    fallbackRoot().resolve("keychain-tmp"), "starchem-keychain-status-", ".tmp");
            String shellCommand = "stty -echo; /usr/bin/security add-generic-password -U -a \"$1\" "
                    + "-s \"$2\" -w; status=$?; stty echo; printf '%s' \"$status\" > \"$3\"";
            List<String> command = List.of("/usr/bin/script", "-q", "/dev/null", "/bin/sh", "-c",
                    shellCommand, "starchem-keychain", id, MacKeychainBackend.SERVICE, statusFile.toString());
            process = new ProcessBuilder(new ArrayList<>(command))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            observeProcess(process, command);
            try (OutputStream output = process.getOutputStream()) {
                output.write(inputBytes);
            }
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyProcessTree(process);
                throw new IllegalStateException("macOS Keychain save timed out.");
            }
            String status = readStatusFile(statusFile);
            if (!"0".equals(status)) throw new IllegalStateException("macOS Keychain could not save credentials.");
        } catch (IOException ex) {
            if (process != null) destroyProcessTree(process);
            throw new IllegalStateException("Could not start macOS Keychain credential storage.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) destroyProcessTree(process);
            throw new IllegalStateException("macOS Keychain credential storage was interrupted.", ex);
        } catch (RuntimeException ex) {
            if (process != null) destroyProcessTree(process);
            throw ex;
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            Arrays.fill(inputBytes, (byte) 0);
            if (statusFile != null) {
                try { Files.deleteIfExists(statusFile); }
                catch (IOException ignored) { }
            }
        }
    }

    private static String readStatusFile(Path statusFile) throws IOException {
        if (statusFile == null || !Files.isRegularFile(statusFile)) return "";
        long size = Files.size(statusFile);
        if (size <= 0 || size > 16) return "";
        return Files.readString(statusFile, StandardCharsets.US_ASCII).trim();
    }

    private static void observeProcess(Process process, List<String> command) {
        ProcessObserver observer = processObserverForTests;
        if (observer != null) observer.started(process, List.copyOf(command));
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_COMMAND_OUTPUT) throw new IOException("Credential command output exceeded its limit.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void destroyProcessTree(Process process) {
        if (process == null) return;
        process.descendants().forEach(handle -> {
            try { handle.destroyForcibly(); }
            catch (RuntimeException ignored) { }
        });
        process.destroyForcibly();
    }

    private static String readStoredText(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            ensureOwnerOnlyFile(file);
            long size = Files.size(file);
            if (size <= 0 || size > MAX_COMMAND_OUTPUT) throw new IOException("Credential file has an invalid size.");
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read saved credentials.", ex);
        }
    }

    private static void writeStoredText(Path root, Path file, String value) {
        Path temporary = null;
        try {
            ensureOwnerOnlyDirectory(root);
            temporary = createOwnerOnlyTempFile(root, "credential-", ".tmp");
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
            moveReplace(temporary, file);
            temporary = null;
            PrivateFileSecurity.secureFile(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not save credentials.", ex);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { }
            }
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    interface ProcessObserver {
        void started(Process process, List<String> command);
    }

    private interface Backend {
        String load(String id);
        void save(String id, String encodedSecret);
        void delete(String id);
        String name();
    }

    private static final class MemoryBackend implements Backend {
        private final java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        @Override public String load(String id) { return decodeSecret(values.get(id)); }
        @Override public void save(String id, String encodedSecret) { values.put(id, encodedSecret); }
        @Override public void delete(String id) { values.remove(id); }
        @Override public String name() { return "process memory"; }
    }

    private static class FileBackend implements Backend {
        final Path root;
        private final String name;
        FileBackend(Path root, String name) { this.root = root; this.name = name; }
        Path file(String id) { return root.resolve(id + ".credential"); }
        @Override public String load(String id) { return decodeSecret(readStoredText(file(id))); }
        @Override public void save(String id, String encodedSecret) { writeStoredText(root, file(id), encodedSecret); }
        @Override public void delete(String id) {
            try { Files.deleteIfExists(file(id)); }
            catch (IOException ex) { throw new IllegalStateException("Could not delete saved credentials.", ex); }
        }
        @Override public String name() { return name; }
    }

    private static final class DpapiBackend extends FileBackend {
        private static final String PROTECT = "$v=[Console]::In.ReadToEnd();"
                + "$b=[Text.Encoding]::UTF8.GetBytes($v);"
                + "$p=[Security.Cryptography.ProtectedData]::Protect($b,$null,"
                + "[Security.Cryptography.DataProtectionScope]::CurrentUser);"
                + "[Console]::Out.Write([Convert]::ToBase64String($p))";
        private static final String UNPROTECT = "$v=[Console]::In.ReadToEnd();"
                + "$b=[Convert]::FromBase64String($v);"
                + "$p=[Security.Cryptography.ProtectedData]::Unprotect($b,$null,"
                + "[Security.Cryptography.DataProtectionScope]::CurrentUser);"
                + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($p))";
        DpapiBackend(Path root) { super(root, "Windows DPAPI"); }
        @Override public String load(String id) {
            String protectedValue = readStoredText(file(id));
            if (protectedValue == null) return null;
            CommandResult result = runCommand(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-Command", UNPROTECT), protectedValue);
            if (result.exitCode() != 0 || result.output().isBlank()) {
                throw new IllegalStateException("Windows DPAPI could not decrypt saved credentials.");
            }
            return decodeSecret(result.output());
        }
        @Override public void save(String id, String encodedSecret) {
            CommandResult result = runCommand(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-Command", PROTECT), encodedSecret);
            if (result.exitCode() != 0 || result.output().isBlank()) {
                throw new IllegalStateException("Windows DPAPI could not protect saved credentials.");
            }
            writeStoredText(root, file(id), result.output());
        }
    }

    private static final class MacKeychainBackend implements Backend {
        private static final String SERVICE = "com.tndmadman.starchem.client";
        @Override public String load(String id) {
            CommandResult result = runCommand(List.of("/usr/bin/security", "find-generic-password",
                    "-a", id, "-s", SERVICE, "-w"), "");
            return result.exitCode() == 0 && !result.output().isBlank() ? decodeSecret(result.output()) : null;
        }
        @Override public void save(String id, String encodedSecret) {
            runMacKeychainSave(id, encodedSecret);
        }
        @Override public void delete(String id) {
            runCommand(List.of("/usr/bin/security", "delete-generic-password", "-a", id, "-s", SERVICE), "");
        }
        @Override public String name() { return "macOS Keychain"; }
    }

    private static final class SecretToolBackend implements Backend {
        @Override public String load(String id) {
            CommandResult result = runCommand(List.of("secret-tool", "lookup", "application", "StarChem",
                    "key", id), "");
            return result.exitCode() == 0 && !result.output().isBlank() ? decodeSecret(result.output()) : null;
        }
        @Override public void save(String id, String encodedSecret) {
            CommandResult result = runCommand(List.of("secret-tool", "store", "--label=StarChem saved sign-in",
                    "application", "StarChem", "key", id), encodedSecret);
            if (result.exitCode() != 0) throw new IllegalStateException("Secret Service could not save credentials.");
        }
        @Override public void delete(String id) {
            runCommand(List.of("secret-tool", "clear", "application", "StarChem", "key", id), "");
        }
        @Override public String name() { return "Linux Secret Service"; }
    }

    private record CommandResult(int exitCode, String output) { }
}
