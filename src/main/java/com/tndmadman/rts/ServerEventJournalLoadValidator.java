package com.tndmadman.rts;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Validates bounded startup loading and repair of the dedicated-server activity journal. */
public final class ServerEventJournalLoadValidator {
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private ServerEventJournalLoadValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem bounded activity journal load validation passed.");
    }

    static void validate() throws Exception {
        Path dir = Files.createTempDirectory("starchem-journal-load-validator-");
        try {
            validateNormalJournal(dir);
            validateBoundedEventCount(dir);
            validateOversizedPrefix(dir);
            validateHugeSingleLine(dir);
            validateMalformedAndMissingNewline(dir);
            validateOversizedField(dir);
        } finally {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static void validateNormalJournal(Path dir) {
        ServerEventJournal journal = new ServerEventJournal(dir, "normal");
        journal.add("ADMIN", "normal", "one");
        journal.add("ADMIN", "normal", "two");
        assertDetails(new ServerEventJournal(dir, "normal"), List.of("two", "one"));
    }

    private static void validateBoundedEventCount(Path dir) throws Exception {
        Path path = dir.resolve("bounded-activity.log");
        ArrayList<String> rows = new ArrayList<>();
        for (int i = 0; i < 520; i++) rows.add(row(i, "bounded-" + i));
        Files.write(path, rows, StandardCharsets.UTF_8);
        List<String> lines = new ServerEventJournal(dir, "bounded").lines(500, "ADMIN", "");
        require(lines.size() == 500, "journal retained more than the configured event bound: " + lines.size());
        require(lines.get(0).endsWith(" | bounded-519"), "journal did not retain the newest event");
        require(lines.get(499).endsWith(" | bounded-20"), "journal retained the wrong oldest bounded event");
    }

    private static void validateOversizedPrefix(Path dir) throws Exception {
        Path path = dir.resolve("oversized-activity.log");
        setLength(path, MAX_FILE_BYTES + 64);
        Files.writeString(path, "\n" + row(1, "recent-one") + "\n" + row(2, "recent-two") + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        ServerEventJournal journal = new ServerEventJournal(dir, "oversized");
        assertDetails(journal, List.of("recent-two", "recent-one"));
        require(Files.size(path) < MAX_FILE_BYTES, "oversized journal was not compacted");
    }

    private static void validateHugeSingleLine(Path dir) throws Exception {
        Path path = dir.resolve("huge-line-activity.log");
        setLength(path, MAX_FILE_BYTES + 128);
        ServerEventJournal journal = new ServerEventJournal(dir, "huge-line");
        require(journal.lines(10, "", "").equals(List.of("No activity matched that filter.")),
                "huge single-line journal produced an event");
        require(Files.size(path) == 0, "huge single-line journal was not safely compacted");
    }

    private static void validateMalformedAndMissingNewline(Path dir) throws Exception {
        Path path = dir.resolve("malformed-activity.log");
        String malformed = "3\t%%%\t" + encoded("malformed") + "\t" + encoded("bad");
        Files.writeString(path, row(1, "before") + "\n" + malformed + "\n" + row(2, "after"),
                StandardCharsets.UTF_8);
        ServerEventJournal journal = new ServerEventJournal(dir, "malformed");
        assertDetails(journal, List.of("after", "before"));
        require(Files.readString(path).lines().count() == 2, "malformed journal line survived repair");
    }

    private static void validateOversizedField(Path dir) throws Exception {
        Path path = dir.resolve("field-activity.log");
        String oversized = "4\t" + "A".repeat(400) + "\t" + encoded("field") + "\t" + encoded("too-long");
        Files.writeString(path, oversized + "\n" + row(5, "valid"), StandardCharsets.UTF_8);
        assertDetails(new ServerEventJournal(dir, "field"), List.of("valid"));
    }

    private static void assertDetails(ServerEventJournal journal, List<String> expected) {
        List<String> lines = journal.lines(10, "ADMIN", "");
        require(lines.size() == expected.size(), "journal loaded the wrong number of events: " + lines);
        for (int i = 0; i < expected.size(); i++) {
            require(lines.get(i).endsWith(" | " + expected.get(i)), "journal loaded the wrong order: " + lines);
        }
    }

    private static String row(long at, String detail) {
        return at + "\t" + encoded("ADMIN") + "\t" + encoded("validator") + "\t" + encoded(detail);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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
