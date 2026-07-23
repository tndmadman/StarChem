package com.tndmadman.rts;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Validates Unicode normalization and terminal-safe rendering of untrusted text. */
public final class TextSafetyValidator {
    private TextSafetyValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("StarChem text safety validation passed.");
    }

    static void validate() {
        validatePlayerNames();
        validateTerminalEscaping();
        validateTerminalPrintStream();
        validateModerationAndJournalText();
    }

    private static void validatePlayerNames() {
        require("Alice".equals(Config.clean("Ａlice")),
                "NFKC compatibility normalization did not canonicalize a player name");
        require(Config.clean("Cafe\u0301").equals(Config.clean("Café")),
                "canonically equivalent player names did not normalize consistently");
        require(ServerModeration.normalizeName("Ｃaptain").equals(ServerModeration.normalizeName("Captain")),
                "name lookup key did not use normalized player text");

        String controls = Config.clean("Al\u001B[2J\u0007\u202E\u200Bice");
        require(!TextSafety.containsUnsafeTerminalText(controls),
                "player-name cleanup retained terminal or Unicode formatting controls");
        require(controls.contains("Alice") || controls.contains("Al[2Jice"),
                "player-name cleanup removed ordinary visible text");

        String international = Config.clean("山田 太郎");
        require("山田 太郎".equals(international),
                "ordinary international player names were not preserved");
        require("Player".equals(Config.clean("\u202E\u200B\u0007")),
                "an invisible-only player name did not fall back safely");

        String supplementary = Config.clean("12345678901234567🚀X");
        require(supplementary.codePointCount(0, supplementary.length()) == 18,
                "player-name limit was not enforced by Unicode code point");
        require(supplementary.endsWith("🚀"),
                "player-name truncation split or discarded the final supplementary code point");
        require(validSurrogates(supplementary),
                "player-name truncation produced an unpaired surrogate");
    }

    private static void validateTerminalEscaping() {
        String unsafe = "name\u001B[31m\u0007\u009B\u202E\u2066\u200B\n\tend";
        String escaped = TextSafety.terminal(unsafe);
        require(escaped.contains("\\u{001B}") && escaped.contains("\\u{0007}")
                        && escaped.contains("\\u{009B}") && escaped.contains("\\u{202E}")
                        && escaped.contains("\\u{2066}") && escaped.contains("\\u{200B}"),
                "terminal escaping did not expose all control and formatting characters");
        require(escaped.contains("\\n") && escaped.contains("\\t"),
                "terminal escaping did not render whitespace controls visibly");
        require(!TextSafety.containsUnsafeTerminalText(escaped),
                "terminal escaping retained an active terminal control");
    }

    private static void validateTerminalPrintStream() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream target = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PrintStream safe = TextSafety.terminalPrintStream(target);
        safe.println("player\u001B]0;owned\u0007\u202E");
        safe.flush();
        String rendered = bytes.toString(StandardCharsets.UTF_8);
        require(rendered.contains("\\u{001B}") && rendered.contains("\\u{0007}")
                        && rendered.contains("\\u{202E}"),
                "terminal-safe PrintStream did not escape untrusted output");
        require(rendered.indexOf('\u001B') < 0 && rendered.indexOf('\u0007') < 0
                        && rendered.indexOf('\u202E') < 0,
                "terminal-safe PrintStream emitted active control characters");
    }

    private static void validateModerationAndJournalText() {
        String cleaned = ServerModeration.clean("reason\u001B[2J\u0007\u202E\nnext");
        require(cleaned.contains("\\u{001B}") && cleaned.contains("\\u{0007}")
                        && cleaned.contains("\\u{202E}") && cleaned.contains("\\n"),
                "moderation text did not preserve dangerous characters visibly and safely");
        require(!TextSafety.containsUnsafeTerminalText(cleaned),
                "moderation text retained active terminal controls");

        ServerEventJournal journal = new ServerEventJournal();
        journal.add("AUTH", "P12", "Alice\u001B[31m\u0007\u202E");
        List<String> lines = journal.lines(10, "", "");
        require(lines.size() == 1, "activity journal did not retain the validation event");
        String line = lines.get(0);
        require(line.contains("P12") && line.contains("\\u{001B}")
                        && line.contains("\\u{0007}") && line.contains("\\u{202E}"),
                "activity journal did not retain a stable ID and terminal-safe detail");
        require(!TextSafety.containsUnsafeTerminalText(line),
                "activity journal rendered active terminal controls");
    }

    private static boolean validSurrogates(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(current)) return false;
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
