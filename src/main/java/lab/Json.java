package lab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal dependency-free JSON parser/serializer (LinkedHashMap / List / String / Long / Double / Boolean / null). */
public final class Json {
    private Json() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { writeString(s, sb); return; }
        if (v instanceof Boolean b) { sb.append(b); return; }
        if (v instanceof Long || v instanceof Integer) { sb.append(v); return; }
        if (v instanceof Double d) { sb.append(d); return; }
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        if (v instanceof List<?> l) {
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(',');
                first = false;
                write(o, sb);
            }
            sb.append(']');
            return;
        }
        throw new IllegalArgumentException("not serializable: " + v.getClass());
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    public static Object read(String text) {
        return new Parser(text).parse();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String text) {
        return (Map<String, Object>) read(text);
    }

    public static Map<String, Object> obj() { return new LinkedHashMap<>(); }
    public static List<Object> arr() { return new ArrayList<>(); }

    public static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : (String) v; }
    public static long num(Map<String, Object> m, String k) { return ((Number) m.get(k)).longValue(); }

    private static final class Parser {
        private final String s;
        private int pos;
        Parser(String s) { this.s = s; }

        Object parse() {
            Object v = readValue();
            ws();
            if (pos != s.length()) throw err("trailing content");
            return v;
        }

        private Object readValue() {
            ws();
            if (pos >= s.length()) throw err("unexpected end");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObj();
                case '[' -> readArr();
                case '"' -> readStr();
                case 't' -> { expect("true"); yield Boolean.TRUE; }
                case 'f' -> { expect("false"); yield Boolean.FALSE; }
                case 'n' -> { expect("null"); yield null; }
                default -> readNum();
            };
        }

        private Map<String, Object> readObj() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            ws();
            if (peek('}')) { pos++; return m; }
            while (true) {
                ws();
                String k = readStr();
                ws();
                if (s.charAt(pos++) != ':') throw err("expected ':'");
                m.put(k, readValue());
                ws();
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw err("expected ',' or '}'");
            }
        }

        private List<Object> readArr() {
            List<Object> l = new ArrayList<>();
            pos++;
            ws();
            if (peek(']')) { pos++; return l; }
            while (true) {
                l.add(readValue());
                ws();
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw err("expected ',' or ']'");
            }
        }

        private String readStr() {
            if (s.charAt(pos++) != '"') throw err("expected string");
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16)); pos += 4; }
                        default -> throw err("bad escape");
                    }
                } else sb.append(c);
            }
        }

        private Object readNum() {
            int start = pos;
            if (peek('-')) pos++;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.' || s.charAt(pos) == 'e'
                    || s.charAt(pos) == 'E' || s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
            String n = s.substring(start, pos);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw err("expected " + lit);
            pos += lit.length();
        }

        private boolean peek(char c) { return pos < s.length() && s.charAt(pos) == c; }

        private void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private IllegalArgumentException err(String msg) {
            return new IllegalArgumentException(msg + " at " + pos + " in " + s.substring(0, Math.min(s.length(), pos + 20)));
        }
    }
}
