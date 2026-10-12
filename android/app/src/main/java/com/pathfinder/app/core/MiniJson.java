package com.pathfinder.app.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small strict JSON parser (RFC 8259) with limits, so server and AI answers can be read safely
 * without any library. Objects become LinkedHashMap, arrays ArrayList, numbers Long (when written
 * as an integer that fits) or Double, strings String, true/false Boolean, null Java null.
 */
public final class MiniJson {
    public static final class JsonException extends Exception {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    private static final int MAX_DEPTH = 32;
    private static final int MAX_CHARS = 2 * 1024 * 1024;

    private final String s;
    private int pos = 0;

    private MiniJson(String s) {
        this.s = s;
    }

    public static Object parse(String text) throws JsonException {
        if (text == null) throw new JsonException("null input");
        if (text.length() > MAX_CHARS) throw new JsonException("too large");
        MiniJson p = new MiniJson(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != p.s.length()) throw new JsonException("trailing data");
        return v;
    }

    /** Escapes text for use inside a JSON string and adds the quotes. */
    public static String quote(String text) {
        StringBuilder b = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    private char peek() throws JsonException {
        if (pos >= s.length()) throw new JsonException("unexpected end");
        return s.charAt(pos);
    }

    private Object value(int depth) throws JsonException {
        if (depth > MAX_DEPTH) throw new JsonException("too deep");
        char c = peek();
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (c == 't') { literal("true"); return Boolean.TRUE; }
        if (c == 'f') { literal("false"); return Boolean.FALSE; }
        if (c == 'n') { literal("null"); return null; }
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        throw new JsonException("unexpected character");
    }

    private void literal(String word) throws JsonException {
        if (!s.startsWith(word, pos)) throw new JsonException("bad literal");
        pos += word.length();
    }

    private Map<String, Object> object(int depth) throws JsonException {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') { pos++; return m; }
        while (true) {
            skipWs();
            if (peek() != '"') throw new JsonException("key expected");
            String k = string();
            skipWs();
            if (peek() != ':') throw new JsonException("colon expected");
            pos++;
            skipWs();
            m.put(k, value(depth + 1));
            skipWs();
            char c = peek();
            pos++;
            if (c == '}') return m;
            if (c != ',') throw new JsonException("comma expected");
        }
    }

    private List<Object> array(int depth) throws JsonException {
        List<Object> l = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') { pos++; return l; }
        while (true) {
            skipWs();
            l.add(value(depth + 1));
            skipWs();
            char c = peek();
            pos++;
            if (c == ']') return l;
            if (c != ',') throw new JsonException("comma expected");
        }
    }

    private String string() throws JsonException {
        pos++; // opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (pos >= s.length()) throw new JsonException("unterminated string");
            char c = s.charAt(pos++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw new JsonException("control character in string");
            if (c != '\\') { b.append(c); continue; }
            if (pos >= s.length()) throw new JsonException("bad escape");
            char e = s.charAt(pos++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (pos + 4 > s.length()) throw new JsonException("bad unicode escape");
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos + i), 16);
                        if (d < 0) throw new JsonException("bad unicode escape");
                        code = code * 16 + d;
                    }
                    pos += 4;
                    b.append((char) code);
                    break;
                default: throw new JsonException("bad escape");
            }
        }
    }

    private Object number() throws JsonException {
        int start = pos;
        if (peek() == '-') pos++;
        if (pos >= s.length()) throw new JsonException("bad number");
        char c = s.charAt(pos);
        if (c == '0') {
            pos++;
        } else if (c >= '1' && c <= '9') {
            while (pos < s.length() && Character.isDigit(s.charAt(pos)) && s.charAt(pos) < 128) pos++;
        } else {
            throw new JsonException("bad number");
        }
        boolean integer = true;
        if (pos < s.length() && s.charAt(pos) == '.') {
            integer = false;
            pos++;
            int digits = 0;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') { pos++; digits++; }
            if (digits == 0) throw new JsonException("bad number");
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            integer = false;
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
            int digits = 0;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') { pos++; digits++; }
            if (digits == 0) throw new JsonException("bad number");
        }
        String t = s.substring(start, pos);
        if (integer) {
            try {
                return Long.valueOf(t);
            } catch (NumberFormatException overflow) {
                // fall through to double
            }
        }
        try {
            double d = Double.parseDouble(t);
            if (Double.isInfinite(d) || Double.isNaN(d)) throw new JsonException("bad number");
            return d;
        } catch (NumberFormatException e) {
            throw new JsonException("bad number");
        }
    }
}
