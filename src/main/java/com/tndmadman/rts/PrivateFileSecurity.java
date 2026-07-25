package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Applies and verifies owner-only storage for local server and client secrets. */
final class PrivateFileSecurity {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private static final Set<AclEntryPermission> DIRECTORY_ACL_PERMISSIONS =
            EnumSet.allOf(AclEntryPermission.class);
    private static final Set<AclEntryPermission> FILE_ACL_PERMISSIONS = EnumSet.of(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.DELETE,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.SYNCHRONIZE);
    private static final Set<AclEntryPermission> DIRECTORY_REQUIRED_ACL_PERMISSIONS = EnumSet.of(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.WRITE_ACL);
    private static final Set<AclEntryPermission> FILE_REQUIRED_ACL_PERMISSIONS = EnumSet.of(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.WRITE_ACL);
    private static final Set<AclEntryPermission> SECRET_EXPOSURE_PERMISSIONS = EnumSet.of(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.DELETE,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER);

    private PrivateFileSecurity() { }

    static Path normalized(Path path) {
        if (path == null) throw new IllegalArgumentException("Private file path is missing.");
        return path.toAbsolutePath().normalize();
    }

    static void ensurePrivateDirectory(Path supplied) throws IOException {
        Path directory = normalized(supplied);
        rejectSymbolicLink(directory, true);
        Files.createDirectories(directory);
        rejectSymbolicLink(directory, true);
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException(label(directory, true) + " is not a directory: " + directory);
        }
        applyPrivatePermissions(directory, true);
        verifyPrivateDirectory(directory);
    }

    static Path createPrivateTempFile(Path suppliedDirectory, String prefix, String suffix) throws IOException {
        Path directory = normalized(suppliedDirectory);
        ensurePrivateDirectory(directory);
        Path temporary = null;
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(directory, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                temporary = Files.createTempFile(directory, safePrefix(prefix), suffix,
                        PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
            } else {
                temporary = Files.createTempFile(directory, safePrefix(prefix), suffix);
                secureFile(temporary);
            }
            verifyPrivateRegularFile(temporary);
            return temporary;
        } catch (IOException | RuntimeException ex) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    ex.addSuppressed(cleanupFailure);
                }
            }
            throw ex;
        }
    }

    static void secureFile(Path supplied) throws IOException {
        Path file = normalized(supplied);
        rejectSymbolicLink(file, false);
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException(label(file, false) + " is not a regular file: " + file);
        }
        applyPrivatePermissions(file, false);
        verifyPrivateRegularFile(file);
    }

    static void verifyPrivateRegularFile(Path supplied) throws IOException {
        Path file = normalized(supplied);
        rejectSymbolicLink(file, false);
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException(label(file, false) + " is not a regular file: " + file);
        }
        verifyPrivatePath(file, false);
    }

    static void verifyPrivateDirectory(Path supplied) throws IOException {
        Path directory = normalized(supplied);
        rejectSymbolicLink(directory, true);
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException(label(directory, true) + " is not a directory: " + directory);
        }
        verifyPrivatePath(directory, true);
    }

    static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void applyPrivatePermissions(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS);
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            throw new IOException("The file system cannot enforce owner-only permissions for "
                    + label(path, directory) + ": " + path);
        }
        applyPrivateAcl(path, acl, directory);
    }

    private static void applyPrivateAcl(Path path, AclFileAttributeView acl, boolean directory) throws IOException {
        UserPrincipal current = currentPrincipal(path, directory);
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        if (owner == null) {
            throw new IOException("Could not determine the owner of " + label(path, directory) + ": " + path);
        }
        if (!samePrincipal(owner, current) && !trustedSystem(owner)) {
            throw new IOException(label(path, directory) + " is owned by an unrelated principal "
                    + owner.getName() + ": " + path);
        }

        Map<UserPrincipal, PreservedAcl> trusted = new LinkedHashMap<>();
        for (AclEntry entry : acl.getAcl()) {
            UserPrincipal principal = entry.principal();
            if (principal == null || samePrincipal(principal, current)
                    || entry.type() != AclEntryType.ALLOW || !trustedSystem(principal)) {
                continue;
            }
            PreservedAcl preserved = trusted.computeIfAbsent(principal, ignored -> new PreservedAcl());
            preserved.permissions.addAll(entry.permissions());
            if (directory) preserved.flags.addAll(entry.flags());
        }

        disableWindowsInheritance(path, directory);

        List<AclEntry> replacement = new ArrayList<>();
        AclEntry.Builder currentEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(current)
                .setPermissions(directory ? DIRECTORY_ACL_PERMISSIONS : FILE_ACL_PERMISSIONS);
        if (directory) currentEntry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        replacement.add(currentEntry.build());

        for (Map.Entry<UserPrincipal, PreservedAcl> entry : trusted.entrySet()) {
            if (entry.getValue().permissions.isEmpty()) continue;
            AclEntry.Builder trustedEntry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(entry.getKey())
                    .setPermissions(entry.getValue().permissions);
            if (directory && !entry.getValue().flags.isEmpty()) {
                trustedEntry.setFlags(entry.getValue().flags);
            }
            replacement.add(trustedEntry.build());
        }

        acl.setAcl(replacement);
    }

    private static void disableWindowsInheritance(Path path, boolean directory) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) return;
        Process process = null;
        try {
            process = new ProcessBuilder("icacls.exe", path.toString(), "/inheritance:d")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Timed out while disabling inherited ACL entries for "
                        + label(path, directory) + ": " + path);
            }
            if (process.exitValue() != 0) {
                throw new IOException("Could not disable inherited ACL entries for "
                        + label(path, directory) + ": " + path);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            throw new IOException("Interrupted while disabling inherited ACL entries for "
                    + label(path, directory) + ": " + path, ex);
        }
    }

    private static void verifyPrivatePath(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            verifyPosixOwner(path, directory);
            Set<PosixFilePermission> actual = posix.readAttributes().permissions();
            Set<PosixFilePermission> required = directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS;
            if (!actual.containsAll(required)) {
                throw new IOException(label(path, directory) + " does not grant its owner required access: " + path);
            }
            for (PosixFilePermission permission : actual) {
                if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")) {
                    throw new IOException(label(path, directory) + " permissions are not owner-only: " + path);
                }
            }
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            throw new IOException("The file system cannot verify owner-only permissions for "
                    + label(path, directory) + ": " + path);
        }
        verifyPrivateAcl(path, acl, directory);
    }

    private static void verifyPrivateAcl(Path path, AclFileAttributeView acl, boolean directory) throws IOException {
        UserPrincipal current = currentPrincipal(path, directory);
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        if (owner == null) {
            throw new IOException("Could not determine the owner of " + label(path, directory) + ": " + path);
        }
        if (!samePrincipal(owner, current) && !trustedSystem(owner)) {
            throw new IOException(label(path, directory) + " is owned by an unrelated principal "
                    + owner.getName() + ": " + path);
        }

        Set<AclEntryPermission> currentAllowed = EnumSet.noneOf(AclEntryPermission.class);
        Set<AclEntryPermission> currentDenied = EnumSet.noneOf(AclEntryPermission.class);
        for (AclEntry entry : acl.getAcl()) {
            UserPrincipal principal = entry.principal();
            if (principal != null && samePrincipal(principal, current)) {
                if (entry.type() == AclEntryType.ALLOW) currentAllowed.addAll(entry.permissions());
                else if (entry.type() == AclEntryType.DENY) currentDenied.addAll(entry.permissions());
                continue;
            }
            if (principal != null && trustedSystem(principal)) continue;
            if (entry.type() == AclEntryType.ALLOW && exposesSecret(entry.permissions())) {
                String principalName = principal == null ? "an unknown principal" : principal.getName();
                throw new IOException(label(path, directory) + " ACL grants access to "
                        + principalName + ": " + path);
            }
        }

        Set<AclEntryPermission> required = directory
                ? DIRECTORY_REQUIRED_ACL_PERMISSIONS : FILE_REQUIRED_ACL_PERMISSIONS;
        if (!currentAllowed.containsAll(required)) {
            throw new IOException(label(path, directory) + " ACL does not grant the current user required access: " + path);
        }
        for (AclEntryPermission permission : required) {
            if (currentDenied.contains(permission)) {
                throw new IOException(label(path, directory) + " ACL denies required current-user access: " + path);
            }
        }
    }

    private static void verifyPosixOwner(Path path, boolean directory) throws IOException {
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        if (owner == null) {
            throw new IOException("Could not determine the owner of " + label(path, directory) + ": " + path);
        }
        String currentUser = currentUserName(path, directory);
        if (!principalNameMatches(owner, currentUser)) {
            throw new IOException(label(path, directory) + " must be owned by the current user: " + path);
        }
    }

    private static UserPrincipal currentPrincipal(Path path, boolean directory) throws IOException {
        String currentUser = currentUserName(path, directory);
        try {
            return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(currentUser);
        } catch (UserPrincipalNotFoundException ex) {
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            if (owner != null && principalNameMatches(owner, currentUser)) return owner;
            throw new IOException("Could not resolve the current user while securing "
                    + label(path, directory) + ": " + path, ex);
        }
    }

    private static String currentUserName(Path path, boolean directory) throws IOException {
        String currentUser = System.getProperty("user.name", "").trim();
        if (currentUser.isBlank()) {
            throw new IOException("Could not determine the current user while securing "
                    + label(path, directory) + ": " + path);
        }
        return currentUser;
    }

    private static boolean samePrincipal(UserPrincipal left, UserPrincipal right) {
        if (left == null || right == null) return false;
        return left.equals(right) || normalizedPrincipalName(left).equals(normalizedPrincipalName(right));
    }

    private static boolean principalNameMatches(UserPrincipal principal, String currentUser) {
        String ownerName = normalizedPrincipalName(principal);
        String normalizedCurrent = currentUser.toLowerCase(Locale.ROOT);
        return ownerName.equals(normalizedCurrent)
                || ownerName.endsWith("\\" + normalizedCurrent)
                || ownerName.endsWith("/" + normalizedCurrent);
    }

    private static String normalizedPrincipalName(UserPrincipal principal) {
        return principal == null || principal.getName() == null
                ? "" : principal.getName().toLowerCase(Locale.ROOT);
    }

    private static boolean exposesSecret(Set<AclEntryPermission> permissions) {
        for (AclEntryPermission permission : SECRET_EXPOSURE_PERMISSIONS) {
            if (permissions.contains(permission)) return true;
        }
        return false;
    }

    private static boolean trustedSystem(UserPrincipal principal) {
        String name = normalizedPrincipalName(principal);
        return name.equals("system")
                || name.endsWith("\\system")
                || name.equals("administrators")
                || name.endsWith("\\administrators");
    }

    private static void rejectSymbolicLink(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException(label(path, directory) + " must not be a symbolic link: " + path);
        }
    }

    private static String label(Path path, boolean directory) {
        if (directory) return "Private directory";
        Path namePath = path == null ? null : path.getFileName();
        String name = namePath == null ? "" : namePath.toString().toLowerCase(Locale.ROOT);
        boolean temporary = name.endsWith(".tmp");
        if (name.contains("-tls.password")) return temporary ? "TLS password temporary file" : "TLS password file";
        if (name.contains("-tls.p12")) return temporary ? "TLS keystore temporary file" : "TLS keystore";
        if (name.endsWith(".credential")) return "Client credential file";
        if (name.endsWith(".lock")) return "Client session lock file";
        if (name.endsWith(".previous")) return "Client session recovery file";
        if (name.contains("sessions.properties")) return temporary ? "Client session temporary file" : "Client session metadata file";
        return temporary ? "Private temporary file" : "Private file";
    }

    private static String safePrefix(String value) {
        String cleaned = value == null ? "private" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() >= 3 ? cleaned : "private";
    }

    private static final class PreservedAcl {
        private final Set<AclEntryPermission> permissions = EnumSet.noneOf(AclEntryPermission.class);
        private final Set<AclEntryFlag> flags = EnumSet.noneOf(AclEntryFlag.class);
    }
}
