package webhooklab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader/writer with deterministic (insertion-ordered) output. */
public final class Json {

    private Json() {}

    public static String esc(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public static String of(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { sb.append(esc(s)); return; }
        if (v instanceof Number || v instanceof Boolean) { sb.append(v.toString()); return; }
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(esc(String.valueOf(e.getKey()))).append(':');
                write(sb, e.getValue());
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
                write(sb, o);
            }
            sb.append(']');
            return;
        }
        throw new IllegalArgumentException("not json-writable: " + v.getClass());
    }

    public static Map<String, Object> obj() { return new LinkedHashMap<>(); }

    public static Map<String, Object> parseObject(String s) {
        Object v = new Parser(s).parse();
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected JSON object");
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, Object> m, String k, String def) {
        String v = str(m, k);
        return v == null ? def : v;
    }

    public static long lng(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v));
    }

    public static int itg(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(v));
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof List ? (List<Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        Object parse() {
            skipWs();
            Object v = value();
            skipWs();
            if (i != s.length()) throw new IllegalArgumentException("trailing content at " + i);
            return v;
        }

        private Object value() {
            skipWs();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> { expect("true"); yield Boolean.TRUE; }
                case 'f' -> { expect("false"); yield Boolean.FALSE; }
                case 'n' -> { expect("null"); yield null; }
                default -> number();
            };
        }

        private Map<String, Object> object() {
            i++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) { i++; return m; }
            while (true) {
                skipWs();
                String k = string();
                skipWs();
                if (s.charAt(i) != ':') throw new IllegalArgumentException("expected ':' at " + i);
                i++;
                m.put(k, value());
                skipWs();
                if (peek(',')) { i++; continue; }
                if (peek('}')) { i++; return m; }
                throw new IllegalArgumentException("expected ',' or '}' at " + i);
            }
        }

        private List<Object> array() {
            i++; // [
            List<Object> l = new ArrayList<>();
            skipWs();
            if (peek(']')) { i++; return l; }
            while (true) {
                l.add(value());
                skipWs();
                if (peek(',')) { i++; continue; }
                if (peek(']')) { i++; return l; }
                throw new IllegalArgumentException("expected ',' or ']' at " + i);
            }
        }

        private String string() {
            if (s.charAt(i) != '"') throw new IllegalArgumentException("expected string at " + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
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
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object number() {
            int start = i;
            if (peek('-')) i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == 'e'
                    || s.charAt(i) == 'E' || s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            String n = s.substring(start, i);
            if (n.isEmpty()) throw new IllegalArgumentException("bad value at " + start);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            try {
                return Long.parseLong(n);
            } catch (NumberFormatException e) {
                return Double.parseDouble(n);
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, i)) throw new IllegalArgumentException("expected " + lit + " at " + i);
            i += lit.length();
        }

        private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
            }
        }
    }
}
