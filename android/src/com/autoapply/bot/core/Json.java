package com.autoapply.bot.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON reader/writer: objects become LinkedHashMap, arrays ArrayList, numbers Long or Double. */
public final class Json {
    private Json() {
    }

    // ------------------------------------------------------------------ parse
    public static Object parse(String text) {
        if (text == null) {
            return null;
        }
        Parser p = new Parser(text);
        p.ws();
        if (p.i >= p.s.length()) {
            return null;
        }
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("Extra text after JSON at " + p.i);
        }
        return v;
    }

    /** Parse, returning null instead of throwing. */
    public static Object tryParse(String text) {
        try {
            return parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The first {...} object found in a reply (models sometimes add words around the JSON). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> firstObject(String text) {
        String s = text == null ? "" : text;
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            return new LinkedHashMap<String, Object>();
        }
        Object v = tryParse(s.substring(a, b + 1));
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<String, Object>();
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                } else {
                    break;
                }
            }
        }

        IllegalArgumentException err(String m) {
            return new IllegalArgumentException(m + " at " + i);
        }

        Object value() {
            ws();
            if (i >= s.length()) {
                throw err("Unexpected end");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    return word("true", Boolean.TRUE);
                case 'f':
                    return word("false", Boolean.FALSE);
                case 'n':
                    return word("null", null);
                default:
                    return number();
            }
        }

        Object word(String w, Object v) {
            if (!s.startsWith(w, i)) {
                throw err("Bad literal");
            }
            i += w.length();
            return v;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw err("Expected key");
                }
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw err("Expected :");
                }
                i++;
                m.put(k, value());
                ws();
                if (i >= s.length()) {
                    throw err("Unclosed object");
                }
                char c = s.charAt(i++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw err("Expected , or }");
                }
            }
        }

        List<Object> array() {
            List<Object> a = new ArrayList<Object>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return a;
            }
            while (true) {
                a.add(value());
                ws();
                if (i >= s.length()) {
                    throw err("Unclosed array");
                }
                char c = s.charAt(i++);
                if (c == ']') {
                    return a;
                }
                if (c != ',') {
                    throw err("Expected , or ]");
                }
            }
        }

        String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) {
                    throw err("Unclosed string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw err("Bad escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        b.append(e);
                        break;
                    case 'b':
                        b.append('\b');
                        break;
                    case 'f':
                        b.append('\f');
                        break;
                    case 'n':
                        b.append('\n');
                        break;
                    case 'r':
                        b.append('\r');
                        break;
                    case 't':
                        b.append('\t');
                        break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw err("Bad unicode escape");
                        }
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw err("Bad escape");
                }
            }
        }

        Object number() {
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                i++;
            }
            boolean frac = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || ((c == '-' || c == '+') && frac)) {
                    frac = true;
                    i++;
                } else {
                    break;
                }
            }
            String t = s.substring(start, i);
            if (t.isEmpty() || t.equals("-")) {
                throw err("Bad value");
            }
            if (!frac) {
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException e) {
                    return Double.parseDouble(t);
                }
            }
            return Double.parseDouble(t);
        }
    }

    // ------------------------------------------------------------------ write
    public static String stringify(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, -1, 0);
        return b.toString();
    }

    public static String pretty(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 2, 0);
        return b.toString();
    }

    private static void newline(StringBuilder b, int indent, int level) {
        if (indent < 0) {
            return;
        }
        b.append('\n');
        for (int k = 0; k < indent * level; k++) {
            b.append(' ');
        }
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v, int indent, int level) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Boolean) {
            b.append(v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                b.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                b.append((long) d);
            } else {
                b.append(Double.toString(d));
            }
        } else if (v instanceof Number) {
            b.append(v.toString());
        } else if (v instanceof Map) {
            Map<Object, Object> m = (Map<Object, Object>) v;
            b.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                newline(b, indent, level + 1);
                quote(b, String.valueOf(e.getKey()));
                b.append(indent < 0 ? ":" : ": ");
                write(b, e.getValue(), indent, level + 1);
            }
            if (!m.isEmpty()) {
                newline(b, indent, level);
            }
            b.append('}');
        } else if (v instanceof Iterable) {
            b.append('[');
            boolean first = true;
            Iterator<Object> it = ((Iterable<Object>) v).iterator();
            boolean any = false;
            while (it.hasNext()) {
                any = true;
                if (!first) {
                    b.append(',');
                }
                first = false;
                newline(b, indent, level + 1);
                write(b, it.next(), indent, level + 1);
            }
            if (any) {
                newline(b, indent, level);
            }
            b.append(']');
        } else if (v instanceof Object[]) {
            List<Object> list = new ArrayList<Object>();
            for (Object o : (Object[]) v) {
                list.add(o);
            }
            write(b, list, indent, level);
        } else {
            quote(b, v.toString());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }

    // ------------------------------------------------------------------ helpers
    /** Build an ordered map from key, value, key, value... */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int k = 0; k + 1 < kv.length; k += 2) {
            m.put(String.valueOf(kv[k]), kv[k + 1]);
        }
        return m;
    }

    public static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<Object>();
        for (Object o : items) {
            l.add(o);
        }
        return l;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
    }

    public static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? "" : (v instanceof Double && (Double) v == Math.rint((Double) v)
                ? String.valueOf(((Double) v).longValue()) : String.valueOf(v));
    }

    public static long num(Map<String, Object> m, String k, long dflt) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return (long) Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return dflt;
            }
        }
        return dflt;
    }

    public static double dbl(Map<String, Object> m, String k, double dflt) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : dflt;
    }

    public static boolean bool(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0;
        }
        return v instanceof String && !((String) v).isEmpty() && !"false".equalsIgnoreCase((String) v);
    }

    /** Truthy like JavaScript: non-null, non-false, non-zero, non-empty. */
    public static boolean truthy(Object v) {
        if (v == null || Boolean.FALSE.equals(v)) {
            return false;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0;
        }
        if (v instanceof String) {
            return !((String) v).isEmpty();
        }
        return true;
    }

    /** Deep copy of maps and lists. */
    @SuppressWarnings("unchecked")
    public static Object copy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                out.put(e.getKey(), copy(e.getValue()));
            }
            return out;
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object o : (List<Object>) v) {
                out.add(copy(o));
            }
            return out;
        }
        return v;
    }

    public static List<String> strings(Object v) {
        List<String> out = new ArrayList<String>();
        for (Object o : arr(v)) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
