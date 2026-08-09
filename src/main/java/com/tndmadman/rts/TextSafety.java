package com.tndmadman.rts;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/** Central Unicode and terminal-safety rules for identifiers and operator-facing text. */
final class TextSafety {
    static final int MAX_PLAYER_NAME_CODE_POINTS = 18;

    private TextSafety() { }

    static String playerName(String value) {
        if (value == null || value.isBlank()) return "Player";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        boolean baseSinceSpace = false;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (wireDelimiter(codePoint) || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = out.length() > 0;
                baseSinceSpace = false;
                continue;
            }
            if (unsafeNameCodePoint(codePoint)) continue;
            int type = Character.getType(codePoint);
            boolean combining = type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK;
            if (combining && !baseSinceSpace) continue;
            if (pendingSpace && out.length() > 0) out.append(' ');
            pendingSpace = false;
            out.appendCodePoint(codePoint);
            if (!combining) baseSinceSpace = true;
        }
        String cleaned = truncateCodePoints(out.toString(), MAX_PLAYER_NAME_CODE_POINTS).trim();
        return cleaned.isBlank() ? "Player" : cleaned;
    }

    static String playerKey(String value) {
        return Normalizer.normalize(playerName(value), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    /**
     * Normalize untrusted player chat while preserving ordinary Unicode and emoji.
     * Newlines collapse to spaces; unsafe controls, bidi formatting, private-use,
     * surrogate, and unassigned code points are discarded before code-point limiting.
     */
    static String chatText(String value, int maximumCodePoints) {
        if (value == null || value.isBlank() || maximumCodePoints < 1) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(Math.min(normalized.length(), maximumCodePoints * 2));
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                    || codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (unsafeChatCodePoint(codePoint)) continue;
            if (pendingSpace && out.length() > 0) out.append(' ');
            pendingSpace = false;
            out.appendCodePoint(codePoint);
            if (out.codePointCount(0, out.length()) >= maximumCodePoints) break;
        }
        return truncateCodePoints(out.toString(), maximumCodePoints).trim();
    }

    static String terminal(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        StringBuilder out = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n') out.append("\\n");
            else if (codePoint == '\r') out.append("\\r");
            else if (codePoint == '\t') out.append("\\t");
            else if (unsafeTerminalCodePoint(codePoint)) appendVisibleCodePoint(out, codePoint);
            else out.appendCodePoint(codePoint);
        }
        return out.toString();
    }

    static PrintStream terminalPrintStream(PrintStream delegate) {
        PrintStream target = delegate == null ? System.out : delegate;
        return new PrintStream(target, true, StandardCharsets.UTF_8) {
            @Override public void print(String value) { target.print(terminal(value)); }
            @Override public void println(String value) { target.println(terminal(value)); }
            @Override public void print(Object value) { target.print(terminal(String.valueOf(value))); }
            @Override public void println(Object value) { target.println(terminal(String.valueOf(value))); }
            @Override public void println() { target.println(); }
        };
    }

    static boolean containsUnsafeTerminalText(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (unsafeTerminalCodePoint(codePoint)) return true;
        }
        return false;
    }

    private static boolean wireDelimiter(int codePoint) {
        return codePoint == '|' || codePoint == ';' || codePoint == ',';
    }

    private static boolean unsafeNameCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE
                || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED
                || variationSelector(codePoint);
    }

    private static boolean unsafeChatCodePoint(int codePoint) {
        if (codePoint == 0x200C || codePoint == 0x200D) return false; // ZWNJ/ZWJ preserve valid script and emoji shaping.
        int type = Character.getType(codePoint);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE
                || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED;
    }

    private static boolean unsafeTerminalCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE
                || variationSelector(codePoint);
    }

    private static boolean variationSelector(int codePoint) {
        return codePoint >= 0xFE00 && codePoint <= 0xFE0F
                || codePoint >= 0xE0100 && codePoint <= 0xE01EF;
    }

    private static String truncateCodePoints(String value, int maximum) {
        if (value == null || value.isEmpty() || maximum < 1) return "";
        int count = value.codePointCount(0, value.length());
        if (count <= maximum) return value;
        return value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private static void appendVisibleCodePoint(StringBuilder out, int codePoint) {
        out.append("\\u{");
        String hex = Integer.toHexString(codePoint).toUpperCase(Locale.ROOT);
        for (int i = hex.length(); i < 4; i++) out.append('0');
        out.append(hex).append('}');
    }
}
