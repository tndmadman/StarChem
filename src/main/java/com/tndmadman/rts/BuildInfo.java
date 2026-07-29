package com.tndmadman.rts;

import java.io.File;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

final class BuildInfo {
    private static final String FALLBACK_VERSION = "1.7.0-dev";
    private static final String UNKNOWN_COMMIT = "unknown";
    private static final ManifestData MANIFEST = loadManifest();

    static {
        LanDiscoveryBootstrap.initializeFromCommandLine();
    }

    private BuildInfo() { }

    static String version() {
        String packageVersion = BuildInfo.class.getPackage().getImplementationVersion();
        return firstValue(packageVersion, MANIFEST.version(), FALLBACK_VERSION);
    }

    static String commit() {
        return firstValue(MANIFEST.commit(), UNKNOWN_COMMIT);
    }

    static String shortCommit() {
        String value = commit();
        if (UNKNOWN_COMMIT.equals(value)) return value;
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    static String display() {
        String commit = shortCommit();
        return "StarChem " + version() + (UNKNOWN_COMMIT.equals(commit) ? "" : " (" + commit + ")");
    }

    static boolean compatible(String otherVersion) {
        return version().equals(clean(otherVersion));
    }

    private static ManifestData loadManifest() {
        try {
            File source = new File(BuildInfo.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!source.isFile()) return ManifestData.EMPTY;
            try (JarFile jar = new JarFile(source)) {
                Manifest manifest = jar.getManifest();
                if (manifest == null) return ManifestData.EMPTY;
                Attributes attributes = manifest.getMainAttributes();
                return new ManifestData(
                        clean(attributes.getValue("Implementation-Version")),
                        clean(attributes.getValue("Build-Commit"))
                );
            }
        } catch (Exception ignored) {
            return ManifestData.EMPTY;
        }
    }

    private static String firstValue(String... values) {
        for (String value : values) {
            String clean = clean(value);
            if (!clean.isBlank()) return clean;
        }
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private record ManifestData(String version, String commit) {
        private static final ManifestData EMPTY = new ManifestData("", "");
    }
}
