package com.tndmadman.rts;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Applies and verifies owner-only storage for local server secrets. */
final class PrivateFileSecurity {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private PrivateFileSecurity() { }

    static Path normalized(Path path) {
        if (path == null) throw new IllegalArgumentException("Private file path is missing.");
        return path.toAbsolutePath().normalize();
    }

    static void ensurePrivateDirectory(Path supplied) throws IOException {
        Path directory = normalized(supplied);
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Private directory must not be a symbolic link: " + directory);
        }
        Files.createDirectories(directory);
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) throw new IOException("Private path is not a directory: " + directory);
        secureDirectory(directory);
    }

    static Path createPrivateTempFile(Path suppliedDirectory, String prefix, String suffix) throws IOException {
        Path directory = normalized(suppliedDirectory);
        ensurePrivateDirectory(directory);
        PosixFileAttributeView posix = Files.getFileAttributeView(directory, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        Path temporary;
        if (posix != null) {
            temporary = Files.createTempFile(directory, safePrefix(prefix), suffix,
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        } else {
            temporary = Files.createTempFile(directory, safePrefix(prefix), suffix);
            secureFile(temporary);
        }
        verifyPrivateRegularFile(temporary);
        return temporary;
    }

    static void secureFile(Path supplied) throws IOException {
        Path file = normalized(supplied);
        if (Files.isSymbolicLink(file)) throw new IOException("Private file must not be a symbolic link: " + file);
        try {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            secureWithFileApi(file.toFile(), false);
        }
        verifyPrivateRegularFile(file);
    }

    static void verifyPrivateRegularFile(Path supplied) throws IOException {
        Path file = normalized(supplied);
        if (Files.isSymbolicLink(file)) throw new IOException("Private file must not be a symbolic link: " + file);
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Private path is not a regular file: " + file);
        verifyOwner(file);

        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            for (PosixFilePermission permission : posix.readAttributes().permissions()) {
                if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")) {
                    throw new IOException("Private file permissions must be owner-only: " + file);
                }
            }
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            System.err.println("WARNING: Could not verify private file ACL for " + file + ".");
            return;
        }
        UserPrincipal owner = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS);
        for (AclEntry entry : acl.getAcl()) {
            if (entry.type() != AclEntryType.ALLOW || entry.principal().equals(owner)) continue;
            String principal = entry.principal().getName().toLowerCase(Locale.ROOT);
            boolean trustedSystem = principal.endsWith("\\system") || principal.equals("system")
                    || principal.endsWith("\\administrators") || principal.equals("administrators");
            if (!trustedSystem && exposesSecret(entry.permissions())) {
                throw new IOException("Private file ACL grants access beyond its owner: " + file);
            }
        }
    }

    static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void secureDirectory(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            secureWithFileApi(directory.toFile(), true);
        }
    }

    private static void verifyOwner(Path path) throws IOException {
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        String currentUser = System.getProperty("user.name", "").trim().toLowerCase(Locale.ROOT);
        String ownerName = owner == null ? "" : owner.getName().toLowerCase(Locale.ROOT);
        boolean ownedByCurrentUser = currentUser.isBlank() || ownerName.equals(currentUser)
                || ownerName.endsWith("\\" + currentUser) || ownerName.endsWith("/" + currentUser);
        if (!ownedByCurrentUser) throw new IOException("Private file must be owned by the current user: " + path);
    }

    private static boolean exposesSecret(Set<AclEntryPermission> permissions) {
        return permissions.contains(AclEntryPermission.READ_DATA)
                || permissions.contains(AclEntryPermission.WRITE_DATA)
                || permissions.contains(AclEntryPermission.APPEND_DATA)
                || permissions.contains(AclEntryPermission.EXECUTE)
                || permissions.contains(AclEntryPermission.DELETE);
    }

    private static void secureWithFileApi(File file, boolean directory) throws IOException {
        boolean changed = file.setReadable(false, false) && file.setWritable(false, false)
                && file.setExecutable(false, false) && file.setReadable(true, true)
                && file.setWritable(true, true) && (!directory || file.setExecutable(true, true));
        if (!changed) throw new IOException("Could not apply owner-only permissions to " + file);
    }

    private static String safePrefix(String value) {
        String cleaned = value == null ? "private" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() >= 3 ? cleaned : "private";
    }
}
