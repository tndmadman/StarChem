package com.tndmadman.rts;

import java.util.*;

final class MiniJson {
    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text == null ? "" : text;
    }

    static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        Object value = parser.value();
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
        } else if (value instanceof Number || value instanceof Boolean) {
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

    private Object value() {
        space();
        if (end()) throw error("Expected value");
        char c = peek();
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (c == '-' || Character.isDigit(c)) return number();
        if (match("true")) return Boolean.TRUE;
        if (match("false")) return Boolean.FALSE;
        if (match("null")) return null;
        throw error("Expected value");
    }

    private Map<String,Object> object() {
        expect('{');
        Map<String,Object> out = new LinkedHashMap<>();
        space();
        if (take('}')) return out;
        while (true) {
            space();
            String key = string();
            space();
            expect(':');
            out.put(key, value());
            space();
            if (take('}')) return out;
            expect(',');
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> out = new ArrayList<>();
        space();
        if (take(']')) return out;
        while (true) {
            out.add(value());
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
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (end()) throw error("Bad escape");
            char e = next();
            switch (e) {
                case '"' -> b.append('"');
                case '\\' -> b.append('\\');
                case '/' -> b.append('/');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> b.append((char)Integer.parseInt(readHex4(), 16));
                default -> throw error("Bad escape: " + e);
            }
        }
        throw error("Unterminated string");
    }

    private String readHex4() {
        if (pos + 4 > text.length()) throw error("Bad unicode escape");
        String hex = text.substring(pos, pos + 4);
        for (int i = 0; i < 4; i++) {
            char c = hex.charAt(i);
            if (!Character.toString(c).matches("[0-9a-fA-F]")) throw error("Bad unicode escape");
        }
        pos += 4;
        return hex;
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
        String raw = text.substring(start, pos);
        return fractional ? Double.parseDouble(raw) : Long.parseLong(raw);
    }

    private void digits() {
        if (end() || !Character.isDigit(peek())) throw error("Expected digit");
        while (!end() && Character.isDigit(peek())) pos++;
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
