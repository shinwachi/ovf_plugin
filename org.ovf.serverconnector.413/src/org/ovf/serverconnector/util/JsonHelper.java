package org.ovf.serverconnector.util;

import java.util.Collection;
import java.util.Map;

/**
 * Simple JSON serialization/deserialization without external dependencies.
 */
public final class JsonHelper {

    private JsonHelper() {}

    public static String toJson(Object obj) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, obj);
        return sb.toString();
    }

    public static Object parseJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        return new JsonParser(json.trim()).parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonObject(String json) {
        Object result = parseJson(json);
        return (result instanceof Map) ? (Map<String, Object>) result : null;
    }

    @SuppressWarnings("unchecked")
    public static java.util.List<Object> parseJsonArray(String json) {
        Object result = parseJson(json);
        return (result instanceof java.util.List) ? (java.util.List<Object>) result : null;
    }

    private static void writeValue(StringBuilder sb, Object obj) {
        if (obj == null) { sb.append("null"); }
        else if (obj instanceof String) { writeString(sb, (String) obj); }
        else if (obj instanceof Number) { sb.append(obj); }
        else if (obj instanceof Boolean) { sb.append(obj); }
        else if (obj instanceof Map) { writeMap(sb, (Map<?, ?>) obj); }
        else if (obj instanceof Collection) { writeCollection(sb, (Collection<?>) obj); }
        else { writeString(sb, obj.toString()); }
    }

    private static void writeString(StringBuilder sb, String str) {
        sb.append("\"");
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map) {
        sb.append("{");
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(":");
            writeValue(sb, e.getValue());
        }
        sb.append("}");
    }

    private static void writeCollection(StringBuilder sb, Collection<?> coll) {
        sb.append("[");
        boolean first = true;
        for (Object item : coll) {
            if (!first) sb.append(",");
            first = false;
            writeValue(sb, item);
        }
        sb.append("]");
    }

    private static class JsonParser {
        private final String json;
        private int pos = 0;

        JsonParser(String json) { this.json = json; }

        Object parseValue() {
            skipWhitespace();
            if (pos >= json.length()) return null;
            char c = json.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            if (c == '-' || Character.isDigit(c)) return parseNumber();
            throw new IllegalArgumentException("Unexpected char at " + pos + ": " + c);
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == '}') { pos++; return map; }
            while (pos < json.length()) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (json.charAt(pos) != ':') throw new IllegalArgumentException("Expected ':'");
                pos++;
                map.put(key, parseValue());
                skipWhitespace();
                if (pos >= json.length()) break;
                if (json.charAt(pos) == '}') { pos++; break; }
                if (json.charAt(pos) == ',') pos++;
            }
            return map;
        }

        private java.util.List<Object> parseArray() {
            java.util.List<Object> list = new java.util.ArrayList<>();
            pos++;
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == ']') { pos++; return list; }
            while (pos < json.length()) {
                list.add(parseValue());
                skipWhitespace();
                if (pos >= json.length()) break;
                if (json.charAt(pos) == ']') { pos++; break; }
                if (json.charAt(pos) == ',') pos++;
            }
            return list;
        }

        private String parseString() {
            if (json.charAt(pos) != '"') throw new IllegalArgumentException("Expected '\"'");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < json.length()) {
                char c = json.charAt(pos);
                if (c == '"') { pos++; return sb.toString(); }
                if (c == '\\') {
                    pos++;
                    char esc = json.charAt(pos);
                    switch (esc) {
                        case '"': case '\\': case '/': sb.append(esc); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(json.substring(pos + 1, pos + 5), 16));
                            pos += 4; break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
                pos++;
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Number parseNumber() {
            int start = pos;
            if (json.charAt(pos) == '-') pos++;
            while (pos < json.length() && Character.isDigit(json.charAt(pos))) pos++;
            boolean isDouble = false;
            if (pos < json.length() && json.charAt(pos) == '.') { isDouble = true; pos++;
                while (pos < json.length() && Character.isDigit(json.charAt(pos))) pos++; }
            if (pos < json.length() && (json.charAt(pos) == 'e' || json.charAt(pos) == 'E')) { isDouble = true; pos++;
                if (pos < json.length() && (json.charAt(pos) == '+' || json.charAt(pos) == '-')) pos++;
                while (pos < json.length() && Character.isDigit(json.charAt(pos))) pos++; }
            String s = json.substring(start, pos);
            if (isDouble) return Double.parseDouble(s);
            long v = Long.parseLong(s);
            return (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) ? (int) v : v;
        }

        private Boolean parseBoolean() {
            if (json.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (json.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("Expected boolean at " + pos);
        }

        private Object parseNull() {
            if (json.startsWith("null", pos)) { pos += 4; return null; }
            throw new IllegalArgumentException("Expected null at " + pos);
        }

        private void skipWhitespace() {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) pos++;
        }
    }
}
