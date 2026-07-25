package com.tndmadman.rts;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/** Cross-platform regression coverage for owner-only POSIX and Windows ACL enforcement. */
public final class PrivateFileSecurityValidator {
    private PrivateFileSecurityValidator() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("starchem-private-file-security-");
        try {
            validateDirectoryAndFile(root.resolve("protected"));
            validateTemporaryCleanup(root.resolve("temporary-cleanup"));
            validateBroadAclRejected(root.resolve("broad-acl"));
            validateWrongPathTypeRejected(root.resolve("wrong-type"));
            System.out.println("Private file security validation passed.");
        } catch (Throwable failure) {
            StringWriter text = new StringWriter();
            failure.printStackTrace(new PrintWriter(text));
            Files.writeString(Path.of("private-file-security-failure.txt"), text.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            throw failure;
        } finally {
            deleteTree(root);
        }
    }

    private static void validateDirectoryAndFile(Path directory) throws Exception {
        PrivateFileSecurity.ensurePrivateDirectory(directory);
        PrivateFileSecurity.verifyPrivateDirectory(directory);

        Path file = directory.resolve("sessions.properties");
        Files.writeString(file, "metadata", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        PrivateFileSecurity.secureFile(file);
        PrivateFileSecurity.verifyPrivateRegularFile(file);

        Path temporary = PrivateFileSecurity.createPrivateTempFile(directory,
                "sessions.properties-", ".tmp");
        try {
            PrivateFileSecurity.verifyPrivateRegularFile(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateTemporaryCleanup(Path directory) throws Exception {
        PrivateFileSecurity.ensurePrivateDirectory(directory);
        Path invalidDirectory = directory.resolve("not-a-directory");
        Files.writeString(invalidDirectory, "blocked", StandardCharsets.UTF_8);
        expectIOException(() -> PrivateFileSecurity.createPrivateTempFile(
                invalidDirectory, "private-", ".tmp"),
                "temporary creation accepted a regular file as its directory");
        try (var files = Files.list(directory)) {
            require(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "failed temporary creation left a partial file behind");
        }
    }

    private static void validateBroadAclRejected(Path directory) throws Exception {
        PrivateFileSecurity.ensurePrivateDirectory(directory);
        Path file = directory.resolve("server-tls.password");
        Files.writeString(file, "test-password", StandardCharsets.UTF_8);
        PrivateFileSecurity.secureFile(file);

        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view == null) return;
        UserPrincipal everyone;
        try {
            everyone = file.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName("Everyone");
        } catch (UserPrincipalNotFoundException ignored) {
            return;
        }
        List<AclEntry> exposed = new ArrayList<>(view.getAcl());
        exposed.add(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(everyone)
                .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA,
                        AclEntryPermission.WRITE_DATA))
                .build());
        view.setAcl(exposed);
        expectIOException(() -> PrivateFileSecurity.verifyPrivateRegularFile(file),
                "broad Windows ACL access was accepted");

        PrivateFileSecurity.secureFile(file);
        PrivateFileSecurity.verifyPrivateRegularFile(file);
    }

    private static void validateWrongPathTypeRejected(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "not a directory", StandardCharsets.UTF_8);
        expectIOException(() -> PrivateFileSecurity.ensurePrivateDirectory(path),
                "regular file was accepted as a private directory");
    }

    private static void expectIOException(IoOperation operation, String message) throws Exception {
        try {
            operation.run();
            throw new IllegalStateException(message);
        } catch (IOException expected) {
            // Expected fail-closed behavior.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws Exception;
    }
}
