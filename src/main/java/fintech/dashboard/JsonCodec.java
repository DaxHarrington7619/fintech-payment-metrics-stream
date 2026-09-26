package fintech.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonCodec {
    private JsonCodec() {}

    static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return '"' + escape(text) + '"';
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (out.length() > 1) out.append(',');
                out.append(write(entry.getKey().toString())).append(':').append(write(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> values) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : values) {
                if (out.length() > 1) out.append(',');
                out.append(write(item));
            }
            return out.append(']').toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    static Object read(String json) { return new Parser(json).parse(); }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static final class Parser {
        private final String input;
        private int cursor;
        Parser(String input) { this.input = input; }

        Object parse() {
            Object value = value();
            whitespace();
            if (cursor != input.length()) throw error("Trailing JSON content");
            return value;
        }

        private Object value() {
            whitespace();
            if (cursor >= input.length()) throw error("Expected JSON value");
            char c = input.charAt(cursor);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (input.startsWith("true", cursor)) { cursor += 4; return true; }
            if (input.startsWith("false", cursor)) { cursor += 5; return false; }
            if (input.startsWith("null", cursor)) { cursor += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> result = new LinkedHashMap<>();
            cursor++;
            whitespace();
            if (take('}')) return result;
            do {
                whitespace();
                String key = string();
                whitespace();
                expect(':');
                result.put(key, value());
                whitespace();
            } while (take(','));
            expect('}');
            return result;
        }

        private List<Object> array() {
            List<Object> result = new ArrayList<>();
            cursor++;
            whitespace();
            if (take(']')) return result;
            do { result.add(value()); whitespace(); } while (take(','));
            expect(']');
            return result;
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (cursor < input.length()) {
                char c = input.charAt(cursor++);
                if (c == '"') return result.toString();
                if (c != '\\') { result.append(c); continue; }
                if (cursor >= input.length()) throw error("Incomplete escape");
                char e = input.charAt(cursor++);
                switch (e) {
                    case '"', '\\', '/' -> result.append(e);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (cursor + 4 > input.length()) throw error("Incomplete unicode escape");
                        result.append((char) Integer.parseInt(input.substring(cursor, cursor + 4), 16));
                        cursor += 4;
                    }
                    default -> throw error("Invalid escape");
                }
            }
            throw error("Unclosed string");
        }

        private Number number() {
            int start = cursor;
            while (cursor < input.length() && "-+0123456789.eE".indexOf(input.charAt(cursor)) >= 0) cursor++;
            String token = input.substring(start, cursor);
            try { return token.contains(".") || token.contains("e") || token.contains("E")
                    ? Double.parseDouble(token) : Long.parseLong(token); }
            catch (NumberFormatException e) { throw error("Invalid number"); }
        }

        private void whitespace() { while (cursor < input.length() && Character.isWhitespace(input.charAt(cursor))) cursor++; }
        private boolean take(char expected) { if (cursor < input.length() && input.charAt(cursor) == expected) { cursor++; return true; } return false; }
        private void expect(char expected) { if (!take(expected)) throw error("Expected " + expected); }
        private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at " + cursor); }
    }
}

