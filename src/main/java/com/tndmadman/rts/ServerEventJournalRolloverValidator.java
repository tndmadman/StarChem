package com.tndmadman.rts;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Validates activity-journal rollover, durable clearing, and failure consistency. */
public final class ServerEventJournalRolloverValidator {
    private static final long ROLLOVER_BYTES = 2L * 1024 * 1024;

    private ServerEventJournalRolloverValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem activity journal rollover and clear validation passed.");
    }

    static void validate() throws Exception {
        Path dir = Files.createTempDirectory("starchem-journal-rollover-validator-");
        try {
            validateRepeatedRollover(dir);
            validateExactBoundary(dir);
            validateDurableClear(dir.resolve("clear-success"));
            validateFailedClearPreservesState(dir.resolve("clear-failure"));
        } finally {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static void validateRepeatedRollover(Path dir) throws Exception {
        Path path = dir.resolve("repeated-activity.log");
        ServerEventJournal journal = new ServerEventJournal(dir, "repeated");
        journal.add("ADMIN", "rollover", "before");

        setLength(path, ROLLOVER_BYTES + 1);
        journal.add("ADMIN", "rollover", "trigger-one");
        assertDetails(path, dir, "repeated", List.of("trigger-one", "before"));

        ServerEventJournal restored = new ServerEventJournal(dir, "repeated");
        setLength(path, ROLLOVER_BYTES + 1);
        restored.add("ADMIN", "rollover", "trigger-two");
        assertDetails(path, dir, "repeated", List.of("trigger-two", "trigger-one", "before"));
    }

    private static void validateExactBoundary(Path dir) throws Exception {
        Path path = dir.resolve("boundary-activity.log");
        ServerEventJournal journal = new ServerEventJournal(dir, "boundary");
        journal.add("ADMIN", "boundary", "before");

        setLength(path, ROLLOVER_BYTES);
        journal.add("ADMIN", "boundary", "at-boundary");
        require(Files.size(path) > ROLLOVER_BYTES,
                "an activity journal at the exact rollover boundary was rewritten early");

        journal.add("ADMIN", "boundary", "after-boundary");
        assertDetails(path, dir, "boundary", List.of("after-boundary", "at-boundary", "before"));
    }

    private static void validateDurableClear(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path path = dir.resolve("clear-success-activity.log");
        ServerEventJournal journal = new ServerEventJournal(dir, "clear-success");
        journal.add("ADMIN", "before", "first");
        journal.add("MODERATION", "before", "second");

        journal.clear();
        assertClearMarker(journal.lines(10, "", ""), "in-memory journal");
        require(Files.isRegularFile(path), "successful activity clear removed the journal file");

        ServerEventJournal restored = new ServerEventJournal(dir, "clear-success");
        assertClearMarker(restored.lines(10, "", ""), "restarted journal");
    }

    private static void validateFailedClearPreservesState(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path path = dir.resolve("clear-failure-activity.log");
        Path saved = dir.resolve("clear-failure-original.log");
        ServerEventJournal journal = new ServerEventJournal(dir, "clear-failure");
        journal.add("ADMIN", "before", "must-survive");
        List<String> before = journal.lines(10, "", "");

        Files.move(path, saved);
        Files.createDirectory(path);
        Files.writeString(path.resolve("blocker"), "prevent replacement");
        boolean failed = false;
        try {
            journal.clear();
        } catch (IllegalStateException expected) {
            failed = expected.getMessage() != null
                    && expected.getMessage().contains("Could not clear server activity journal");
        } finally {
            if (Files.isDirectory(path)) {
                Files.deleteIfExists(path.resolve("blocker"));
                Files.deleteIfExists(path);
            } else {
                Files.deleteIfExists(path);
            }
            Files.move(saved, path);
        }

        require(failed, "activity clear did not report a persistent replacement failure");
        require(before.equals(journal.lines(10, "", "")),
                "failed activity clear changed in-memory history");
        try (var stream = Files.list(dir)) {
            require(stream.noneMatch(candidate -> candidate.getFileName().toString().startsWith(
                            path.getFileName().toString() + ".")
                            && candidate.getFileName().toString().endsWith(".tmp")),
                    "failed activity clear left a temporary journal file");
        }

        ServerEventJournal restored = new ServerEventJournal(dir, "clear-failure");
        require(before.equals(restored.lines(10, "", "")),
                "failed activity clear changed persisted history");
    }

    private static void assertClearMarker(List<String> lines, String source) {
        require(lines.size() == 1, source + " did not retain exactly one clear marker: " + lines);
        require(lines.get(0).contains(" | ADMIN | activity | previous activity history cleared"),
                source + " retained the wrong clear marker: " + lines);
    }

    private static void assertDetails(Path path, Path dir, String saveName, List<String> expected) throws Exception {
        require(Files.size(path) < ROLLOVER_BYTES,
                "activity journal rollover did not replace the oversized file");
        List<String> lines = new ServerEventJournal(dir, saveName).lines(10, "ADMIN", "");
        require(lines.size() == expected.size(),
                "activity journal rollover duplicated or omitted an event: " + lines);
        for (int i = 0; i < expected.size(); i++) {
            require(lines.get(i).endsWith(" | " + expected.get(i)),
                    "activity journal rollover changed event order: " + lines);
        }
    }

    private static void setLength(Path path, long length) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(length);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
