package webhooklab;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Append-only, fsync'd JSON-lines journal. Every state mutation is one line:
 * {@code {"seq":N,"type":"...","crc":C,"data":<raw data json>}}.
 *
 * The CRC covers the exact UTF-8 bytes of the "data" value, so a torn tail
 * write (process killed mid-append) is detected and ignored on recovery
 * instead of corrupting state. The raw data text is preserved byte-for-byte
 * on read so the checksum can be recomputed over identical bytes.
 */
public final class Journal implements AutoCloseable {

    /** A read record: header fields plus the exact raw data text. */
    public record ReadRecord(long seq, String type, long crc, String rawData) {}

    private final Path path;
    private final RandomAccessFile raf;
    private long seq;

    private Journal(Path path, RandomAccessFile raf, long seq) {
        this.path = path;
        this.raf = raf;
        this.seq = seq;
    }

    public static Journal open(Path path) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
        return new Journal(path, raf, 0);
    }

    /** Appends one record and forces it to stable storage before returning. */
    public synchronized void append(String type, Map<String, Object> data) {
        try {
            seq++;
            String dataJson = Json.of(data);
            byte[] dataBytes = dataJson.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(dataBytes);
            String line = "{\"seq\":" + seq + ",\"type\":" + Json.esc(type)
                    + ",\"crc\":" + crc.getValue() + ",\"data\":" + dataJson + "}\n";
            raf.write(line.getBytes(StandardCharsets.UTF_8));
            raf.getChannel().force(true);
        } catch (IOException e) {
            throw new IllegalStateException("journal append failed: " + path, e);
        }
    }

    /** Reads all valid records; silently drops a torn/corrupt tail. */
    public static List<ReadRecord> readAll(Path path) throws IOException {
        List<ReadRecord> out = new ArrayList<>();
        if (!Files.exists(path)) return out;
        byte[] raw = Files.readAllBytes(path);
        String content = new String(raw, StandardCharsets.UTF_8);
        int start = 0;
        while (start < content.length()) {
            int nl = content.indexOf('\n', start);
            if (nl < 0) break; // incomplete tail line: ignore
            String line = content.substring(start, nl).trim();
            start = nl + 1;
            if (line.isEmpty()) continue;
            ReadRecord rec;
            try {
                rec = parseLine(line);
            } catch (Exception e) {
                break; // unparseable line: treat the rest as a torn tail
            }
            if (rec == null) break; // checksum mismatch: torn tail
            out.add(rec);
        }
        return out;
    }

    private static ReadRecord parseLine(String line) {
        // Parse header fields with a small state machine that remembers the
        // exact span of the "data" value, then verify CRC over those bytes.
        int i = 0, n = line.length();
        Long seq = null;
        String type = null;
        Long crc = null;
        int dataStart = -1, dataEnd = -1;
        i = skipWs(line, i);
        if (i >= n || line.charAt(i) != '{') return null;
        i++;
        while (true) {
            i = skipWs(line, i);
            if (line.charAt(i) == '}') break;
            String key = parseString(line, i);
            i = skipStr(line, i);
            i = skipWs(line, i);
            if (line.charAt(i) != ':') return null;
            i = skipWs(line, i + 1);
            if (key.equals("data")) {
                dataStart = i;
                i = skipValue(line, i);
                dataEnd = i;
            } else if (line.charAt(i) == '"') {
                String v = parseString(line, i);
                i = skipStr(line, i);
                if (key.equals("type")) type = v;
            } else {
                int vs = i;
                i = skipValue(line, i);
                long num;
                try {
                    num = Long.parseLong(line.substring(vs, i).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (key.equals("seq")) seq = num;
                else if (key.equals("crc")) crc = num;
            }
            i = skipWs(line, i);
            if (line.charAt(i) == ',') { i++; continue; }
            if (line.charAt(i) == '}') break;
            return null;
        }
        if (seq == null || type == null || crc == null || dataStart < 0) return null;
        String rawData = line.substring(dataStart, dataEnd).trim();
        CRC32 computed = new CRC32();
        computed.update(rawData.getBytes(StandardCharsets.UTF_8));
        if (computed.getValue() != crc) return null;
        return new ReadRecord(seq, type, crc, rawData);
    }

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
        }
        return i;
    }

    private static int skipStr(String s, int i) {
        i++; // opening quote
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '\\') i++;
            else if (c == '"') return i;
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private static String parseString(String s, int i) {
        int end = skipStr(s, i);
        Map<String, Object> m = Json.parseObject("{\"v\":" + s.substring(i, end) + "}");
        return Json.str(m, "v");
    }

    private static int skipValue(String s, int i) {
        char c = s.charAt(i);
        if (c == '"') return skipStr(s, i);
        if (c == '{' || c == '[') {
            char open = c, close = c == '{' ? '}' : ']';
            int depth = 0;
            while (i < s.length()) {
                char x = s.charAt(i);
                if (x == '"') i = skipStr(s, i);
                else {
                    if (x == open) depth++;
                    else if (x == close) {
                        depth--;
                        i++;
                        if (depth == 0) return i;
                        continue;
                    }
                    i++;
                }
            }
            throw new IllegalArgumentException("unterminated value");
        }
        while (i < s.length() && ",}".indexOf(s.charAt(i)) < 0) i++;
        return i;
    }

    @Override
    public synchronized void close() {
        try {
            raf.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Convenience: read records as parsed data maps. */
    public static List<Map<String, Object>> readMaps(Path path) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReadRecord r : readAll(path)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", r.seq());
            m.put("type", r.type());
            m.put("data", Json.parseObject(r.rawData()));
            out.add(m);
        }
        return out;
    }
}
