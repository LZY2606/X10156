package lab.webhook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON parser / writer.
 * Parsed object preserves insertion order via LinkedHashMap.
 */
public final class Json {

    private Json() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, value);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, value, 0);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof Enum<?> en) {
            writeString(sb, en.name());
        } else if (value.getClass().isRecord()) {
            writeRecord(sb, value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeRecord(StringBuilder sb, Object value) {
        java.lang.reflect.RecordComponent[] comps = value.getClass().getRecordComponents();
        sb.append('{');
        boolean first = true;
        for (java.lang.reflect.RecordComponent c : comps) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, c.getName());
            sb.append(':');
            try {
                c.getAccessor().setAccessible(true);
                writeTo(sb, c.getAccessor().invoke(value));
            } catch (Exception e) {
                writeTo(sb, null);
            }
        }
        sb.append('}');
    }

    private static void writePretty(StringBuilder sb, Object value, int indent) {
        if (value.getClass() != null && value.getClass().isRecord()) {
            value = recordToMap(value);
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                indent(sb, indent + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                writePretty(sb, e.getValue(), indent + 1);
            }
            sb.append('\n');
            indent(sb, indent);
            sb.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",\n");
                first = false;
                indent(sb, indent + 1);
                writePretty(sb, item, indent + 1);
            }
            sb.append('\n');
            indent(sb, indent);
            sb.append(']');
        } else {
            writeTo(sb, value);
        }
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static Object recordToMap(Object value) {
        java.lang.reflect.RecordComponent[] comps = value.getClass().getRecordComponents();
        Map<String, Object> m = new LinkedHashMap<>();
        for (java.lang.reflect.RecordComponent c : comps) {
            try {
                c.getAccessor().setAccessible(true);
                Object v = c.getAccessor().invoke(value);
                if (v != null && v.getClass().isRecord()) v = recordToMap(v);
                m.put(c.getName(), v);
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static void indent(StringBuilder sb, int n) {
        sb.append("  ".repeat(n));
    }

    static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static Object parse(String input) {
        Parser p = new Parser(input);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.pos < p.src.length()) throw p.error("trailing data");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String input) {
        Object v = parse(input);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected JSON object");
        return (Map<String, Object>) v;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static long lng(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v == null) return dflt;
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return dflt; }
    }

    public static int integer(Map<String, Object> m, String key, int dflt) {
        return (int) lng(m, key, dflt);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    public static boolean bool(Map<String, Object> m, String key, boolean dflt) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return dflt;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static final class Parser {
        final String src;
        int pos;

        Parser(String src) { this.src = src; }

        RuntimeException error(String msg) {
            return new IllegalArgumentException("JSON parse error at " + pos + ": " + msg);
        }

        void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        Object readValue() {
            skipWs();
            if (pos >= src.length()) throw error("unexpected end");
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                m.put(key, value);
                skipWs();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw error("expected , or }");
            }
            return m;
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw error("expected , or ]");
            }
            return list;
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) throw error("unterminated string");
                char c = src.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > src.length()) throw error("bad unicode escape");
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
                else break;
            }
            if (start == pos) throw error("invalid token");
            String s = src.substring(start, pos);
            if (s.contains(".") || s.contains("e") || s.contains("E")) return Double.parseDouble(s);
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(s);
            }
        }

        Boolean readBoolean() {
            if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw error("invalid literal");
        }

        Object readNull() {
            if (src.startsWith("null", pos)) { pos += 4; return null; }
            throw error("invalid literal");
        }

        char peek() {
            if (pos >= src.length()) throw error("unexpected end");
            return src.charAt(pos);
        }

        char next() {
            if (pos >= src.length()) throw error("unexpected end");
            return src.charAt(pos++);
        }

        void expect(char c) {
            char actual = next();
            if (actual != c) throw error("expected " + c + " got " + actual);
        }
    }
}
