package com.tndmadman.rts;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Validates activity-journal rollover ordering and duplicate prevention. */
public final class ServerEventJournalRolloverValidator {
    private static final long ROLLOVER_BYTES = 2L * 1024 * 1024;

    private ServerEventJournalRolloverValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem activity journal rollover validation passed.");
    }

    static void validate() throws Exception {
        Path dir = Files.createTempDirectory("starchem-journal-rollover-validator-");
        try {
            validateRepeatedRollover(dir);
            validateExactBoundary(dir);
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
