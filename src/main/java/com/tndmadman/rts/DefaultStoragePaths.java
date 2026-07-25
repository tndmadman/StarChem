package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/** Resolves writable, owner-only storage for graphical games without trusting the launch directory. */
final class DefaultStoragePaths {
    static final String SAVE_DIR_PROPERTY = "starchem.saveDir";
    static final String SAVE_DIR_ENV = "STARCHEM_SAVE_DIR";

    private DefaultStoragePaths() { }

    static Path graphicalSaveDirectory() {
        String property = System.getProperty(SAVE_DIR_PROPERTY, "").trim();
        if (!property.isBlank()) return normalized(Path.of(property));

        String environment = System.getenv().getOrDefault(SAVE_DIR_ENV, "").trim();
        if (!environment.isBlank()) return normalized(Path.of(environment));

        Path portable = normalized(Path.of("saves"));
        if (!windows()) return portable;

        boolean portableExists = Files.exists(portable, LinkOption.NOFOLLOW_LINKS);
        Path perUser = windowsDataRoot().resolve("StarChem").resolve("saves").toAbsolutePath().normalize();
        Path selected = selectWindowsDirectory(portable, perUser);
        if (portableExists && !selected.equals(portable)) {
            System.err.println("StarChem could not securely use the launch-folder save directory: " + portable);
            System.err.println("Using the per-user save directory instead: " + selected);
        }
        return selected;
    }

    static Path selectWindowsDirectory(Path portable, Path perUser) {
        Path normalizedPortable = normalized(portable);
        Path normalizedPerUser = normalized(perUser);
        return usableExistingPortableDirectory(normalizedPortable) ? normalizedPortable : normalizedPerUser;
    }

    static boolean usableExistingPortableDirectory(Path directory) {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false;

        Path probe = null;
        try {
            PrivateFileSecurity.ensurePrivateDirectory(directory);
            secureExistingTlsFiles(directory);
            probe = PrivateFileSecurity.createPrivateTempFile(directory, "starchem-storage-probe-", ".tmp");
            PrivateFileSecurity.verifyPrivateRegularFile(probe);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        } finally {
            if (probe != null) {
                try { Files.deleteIfExists(probe); }
                catch (IOException ignored) { }
            }
        }
    }

    private static void secureExistingTlsFiles(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName() == null
                        ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith("-tls.p12") && !name.endsWith("-tls.password")) continue;
                PrivateFileSecurity.secureFile(file);
            }
        }
    }

    private static Path windowsDataRoot() {
        String localAppData = System.getenv().getOrDefault("LOCALAPPDATA", "").trim();
        if (!localAppData.isBlank()) return normalized(Path.of(localAppData));

        String userHome = System.getProperty("user.home", "").trim();
        if (!userHome.isBlank()) return normalized(Path.of(userHome, "AppData", "Local"));

        String temporary = System.getProperty("java.io.tmpdir", ".").trim();
        return normalized(Path.of(temporary));
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path normalized(Path path) {
        if (path == null) throw new IllegalArgumentException("Save directory is missing.");
        return path.toAbsolutePath().normalize();
    }
}
