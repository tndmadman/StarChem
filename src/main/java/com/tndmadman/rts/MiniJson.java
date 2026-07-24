package com.tndmadman.rts;

import java.util.*;

final class MiniJson {
    static final Limits DEFAULT_LIMITS = new Limits(
            64 * 1024 * 1024,
            256,
            8_000_000L,
            8 * 1024 * 1024,
            1_000_000,
            256,
            false);

    record Limits(int maxDocumentChars, int maxDepth, long maxTokens, int maxStringChars,
                  int maxCollectionEntries, int maxNumberChars, boolean rejectDuplicateKeys) {
        Limits {
            if (maxDocumentChars < 1) throw new IllegalArgumentException("maxDocumentChars must be positive");
            if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be positive");
            if (maxTokens < 1) throw new IllegalArgumentException("maxTokens must be positive");
            if (maxStringChars < 1) throw new IllegalArgumentException("maxStringChars must be positive");
            if (maxCollectionEntries < 1) throw new IllegalArgumentException("maxCollectionEntries must be positive");
            if (maxNumberChars < 1) throw new IllegalArgumentException("maxNumberChars must be positive");
        }
    }

    private final String text;
    private final Limits limits;
    private int pos;
    private long tokens;

    private MiniJson(String text, Limits limits) {
        this.text = text == null ? "" : text;
        this.limits = limits == null ? DEFAULT_LIMITS : limits;
        if (this.text.length() > this.limits.maxDocumentChars()) {
            throw new IllegalArgumentException("JSON document exceeds " + this.limits.maxDocumentChars() + " characters");
        }
    }

    static Object parse(String text) {
        return parse(text, DEFAULT_LIMITS);
    }

    static Object parse(String text, Limits limits) {
        MiniJson parser = new MiniJson(text, limits);
        Object value = parser.value(0);
        parser.space();
        if (!parser.end()) throw parser.error("Trailing data");
        return value;
    }

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeString(out, text);
        } else if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if ((number instanceof Double || number instanceof Float) && !Double.isFinite(numeric)) {
                throw new IllegalArgumentException("JSON numbers must be finite");
            }
            out.append(number);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?,?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?,?> entry : map.entrySet()) {
                if (entry.getKey() == null) continue;
                if (!first) out.append(',');
                first = false;
                writeString(out, entry.getKey().toString());
                out.append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) out.append(',');
                first = false;
                writeValue(out, item);
            }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) out.append(',');
                writeValue(out, java.lang.reflect.Array.get(value, i));
            }
            out.append(']');
        } else {
            writeString(out, value.toString());
        }
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private Object value(int depth) {
        if (depth > limits.maxDepth()) throw error("JSON nesting exceeds " + limits.maxDepth());
        token();
        space();
        if (end()) throw error("Expected value");
        char c = peek();
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (c == '-' || Character.isDigit(c)) return number();
        if (match("true")) return Boolean.TRUE;
        if (match("false")) return Boolean.FALSE;
        if (match("null")) return null;
        throw error("Expected value");
    }

    private Map<String,Object> object(int depth) {
        expect('{');
        Map<String,Object> out = new LinkedHashMap<>();
        space();
        if (take('}')) return out;
        while (true) {
            if (out.size() >= limits.maxCollectionEntries()) {
                throw error("JSON object exceeds " + limits.maxCollectionEntries() + " entries");
            }
            space();
            token();
            String key = string();
            space();
            expect(':');
            Object value = value(depth + 1);
            if (limits.rejectDuplicateKeys() && out.containsKey(key)) throw error("Duplicate object key: " + key);
            out.put(key, value);
            space();
            if (take('}')) return out;
            expect(',');
        }
    }

    private List<Object> array(int depth) {
        expect('[');
        List<Object> out = new ArrayList<>();
        space();
        if (take(']')) return out;
        while (true) {
            if (out.size() >= limits.maxCollectionEntries()) {
                throw error("JSON array exceeds " + limits.maxCollectionEntries() + " entries");
            }
            out.add(value(depth + 1));
            space();
            if (take(']')) return out;
            expect(',');
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (!end()) {
            char c = next();
            if (c == '"') {
                validateSurrogates(b);
                return b.toString();
            }
            if (c < 0x20) throw error("Unescaped control character in string");
            if (c != '\\') {
                appendStringChar(b, c);
                continue;
            }
            if (end()) throw error("Bad escape");
            char e = next();
            switch (e) {
                case '"' -> appendStringChar(b, '"');
                case '\\' -> appendStringChar(b, '\\');
                case '/' -> appendStringChar(b, '/');
                case 'b' -> appendStringChar(b, '\b');
                case 'f' -> appendStringChar(b, '\f');
                case 'n' -> appendStringChar(b, '\n');
                case 'r' -> appendStringChar(b, '\r');
                case 't' -> appendStringChar(b, '\t');
                case 'u' -> appendStringChar(b, (char)Integer.parseInt(readHex4(), 16));
                default -> throw error("Bad escape: " + e);
            }
        }
        throw error("Unterminated string");
    }

    private void appendStringChar(StringBuilder out, char value) {
        if (out.length() >= limits.maxStringChars()) {
            throw error("JSON string exceeds " + limits.maxStringChars() + " characters");
        }
        out.append(value);
    }

    private void validateSurrogates(CharSequence value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw error("Unpaired high surrogate in string");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw error("Unpaired low surrogate in string");
            }
        }
    }

    private String readHex4() {
        if (pos + 4 > text.length()) throw error("Bad unicode escape");
        int start = pos;
        for (int i = 0; i < 4; i++) {
            char c = text.charAt(pos++);
            if (Character.digit(c, 16) < 0) throw error("Bad unicode escape");
        }
        return text.substring(start, start + 4);
    }

    private Number number() {
        int start = pos;
        take('-');
        digits();
        boolean fractional = false;
        if (take('.')) {
            fractional = true;
            digits();
        }
        if (!end() && (peek() == 'e' || peek() == 'E')) {
            fractional = true;
            pos++;
            if (!end() && (peek() == '+' || peek() == '-')) pos++;
            digits();
        }
        int length = pos - start;
        if (length > limits.maxNumberChars()) {
            throw error("JSON number exceeds " + limits.maxNumberChars() + " characters");
        }
        String raw = text.substring(start, pos);
        try {
            if (!fractional) return Long.parseLong(raw);
            double parsed = Double.parseDouble(raw);
            if (!Double.isFinite(parsed)) throw error("Number must be finite");
            return parsed;
        } catch (NumberFormatException ex) {
            throw error("Invalid number");
        }
    }

    private void digits() {
        if (end() || !Character.isDigit(peek())) throw error("Expected digit");
        while (!end() && Character.isDigit(peek())) pos++;
    }

    private void token() {
        tokens++;
        if (tokens > limits.maxTokens()) throw error("JSON token count exceeds " + limits.maxTokens());
    }

    private void space() {
        while (!end() && Character.isWhitespace(peek())) pos++;
    }

    private boolean match(String s) {
        if (!text.startsWith(s, pos)) return false;
        pos += s.length();
        return true;
    }

    private void expect(char c) {
        if (!take(c)) throw error("Expected '" + c + "'");
    }

    private boolean take(char c) {
        if (!end() && peek() == c) {
            pos++;
            return true;
        }
        return false;
    }

    private char peek() { return text.charAt(pos); }
    private char next() { return text.charAt(pos++); }
    private boolean end() { return pos >= text.length(); }
    private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at character " + pos); }
}
