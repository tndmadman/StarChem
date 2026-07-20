package com.tndmadman.rts;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** Classifies save archives without allowing one save-name prefix to capture another save's files. */
final class ServerSaveArchiveNames {
    static final String EXTENSION = ".starchem-save";
    private static final Pattern TIMESTAMPED_SUFFIX = Pattern.compile(
            "\\d{8}-\\d{6}(?:-[A-Za-z0-9._-]{1,64})?");

    enum Kind {
        CURRENT,
        PREVIOUS,
        TIMESTAMPED,
        NONE
    }

    private ServerSaveArchiveNames() { }

    static Kind classify(String saveName, Path path) {
        if (path == null || path.getFileName() == null) return Kind.NONE;
        return classify(saveName, path.getFileName().toString());
    }

    static Kind classify(String saveName, String filename) {
        String cleanName = Config.cleanSaveName(saveName);
        if (filename == null || !filename.endsWith(EXTENSION)) return Kind.NONE;
        if (filename.equals(cleanName + "-current" + EXTENSION)) return Kind.CURRENT;
        if (filename.equals(cleanName + "-previous" + EXTENSION)) return Kind.PREVIOUS;

        String prefix = cleanName + "-";
        if (!filename.startsWith(prefix)) return Kind.NONE;
        String suffix = filename.substring(prefix.length(), filename.length() - EXTENSION.length());

        // Never classify another save's active recovery files as timestamped backups,
        // including save names that happen to begin with a timestamp-shaped segment.
        if (suffix.endsWith("-current") || suffix.endsWith("-previous")) return Kind.NONE;
        return TIMESTAMPED_SUFFIX.matcher(suffix).matches() ? Kind.TIMESTAMPED : Kind.NONE;
    }

    static boolean belongsTo(String saveName, Path path) {
        return classify(saveName, path) != Kind.NONE;
    }

    static boolean isTimestampedBackup(String saveName, Path path) {
        return classify(saveName, path) == Kind.TIMESTAMPED;
    }
}
