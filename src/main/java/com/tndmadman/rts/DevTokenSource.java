package com.tndmadman.rts;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

final class DevTokenSource {
    private static final int MAX_FILE_BYTES = 512;

    private DevTokenSource() { }

    static String load(Path suppliedPath) {
        if (suppliedPath == null) throw new IllegalArgumentException("Dev token file path is missing.");
        Path path = suppliedPath.toAbsolutePath().normalize();
        byte[] bytes = null;
        try {
            if (Files.isSymbolicLink(path)) {
                throw new IllegalArgumentException("Dev token file must not be a symbolic link: " + path);
            }
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IllegalArgumentException("Dev token file is not a regular file: " + path);
            }
            if (attributes.size() > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("Dev token file is too large: " + path);
            }
            verifyPermissions(path);
            bytes = Files.readAllBytes(path);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("Dev token file is too large: " + path);
            }
            String text = decode(bytes, path);
            if (text.endsWith("\r\n")) text = text.substring(0, text.length() - 2);
            else if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
            if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Dev token file must contain exactly one token: " + path);
            }
            return DevAccessPolicy.requireToken(text);
        } catch (IOException | SecurityException ex) {
            throw new IllegalArgumentException("Could not read dev token file " + path + ": "
                    + ex.getClass().getSimpleName(), ex);
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String decode(byte[] bytes, Path path) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("Dev token file is not valid UTF-8: " + path, ex);
        }
    }

    private static void verifyPermissions(Path path) throws IOException {
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        String currentUser = System.getProperty("user.name", "").trim().toLowerCase(Locale.ROOT);
        String ownerName = owner == null ? "" : owner.getName().toLowerCase(Locale.ROOT);
        boolean ownedByCurrentUser = currentUser.isBlank() || ownerName.equals(currentUser)
                || ownerName.endsWith("\\" + currentUser) || ownerName.endsWith("/" + currentUser);
        if (!ownedByCurrentUser) {
            throw new IllegalArgumentException("Dev token file must be owned by the current user: " + path);
        }

        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
            for (PosixFilePermission permission : permissions) {
                if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")) {
                    throw new IllegalArgumentException("Dev token file permissions must be owner-only: " + path);
                }
            }
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            for (AclEntry entry : acl.getAcl()) {
                if (entry.type() != AclEntryType.ALLOW || entry.principal().equals(owner)) continue;
                String principal = entry.principal().getName().toLowerCase(Locale.ROOT);
                boolean trustedSystem = principal.endsWith("\\system") || principal.equals("system")
                        || principal.endsWith("\\administrators") || principal.equals("administrators");
                if (!trustedSystem && exposesSecret(entry.permissions())) {
                    throw new IllegalArgumentException("Dev token file ACL grants access beyond its owner: " + path);
                }
            }
            return;
        }

        System.err.println("WARNING: Could not verify dev token file permissions for " + path + ".");
    }

    private static boolean exposesSecret(Set<AclEntryPermission> permissions) {
        return permissions.contains(AclEntryPermission.READ_DATA)
                || permissions.contains(AclEntryPermission.WRITE_DATA)
                || permissions.contains(AclEntryPermission.APPEND_DATA)
                || permissions.contains(AclEntryPermission.EXECUTE)
                || permissions.contains(AclEntryPermission.DELETE);
    }
}
