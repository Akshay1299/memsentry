package io.memsentry.agent.util;

import java.util.Map;

/**
 * Tiny, dependency-free JSON serializer. Supports the handful of types we actually
 * emit from the dashboard API: {@link String}, {@link Number}, {@link Boolean},
 * {@code null}, {@link Map} (object), and {@link Iterable} (array).
 *
 * <p>Non-finite doubles (NaN / Infinity) are emitted as {@code null} so the payload
 * stays valid JSON — these show up for R² when a series is degenerate.
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Double d -> sb.append((d.isNaN() || d.isInfinite()) ? "null" : trimDouble(d));
            case Float f -> sb.append((f.isNaN() || f.isInfinite()) ? "null" : trimDouble(f.doubleValue()));
            case Number n -> sb.append(n.toString());
            case Boolean b -> sb.append(b.toString());
            case Map<?, ?> m -> writeObject(sb, m);
            case Iterable<?> it -> writeArray(sb, it);
            default -> writeString(sb, v.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> items) {
        sb.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, item);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
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

    private static String trimDouble(double d) {
        // Keep payloads compact: drop the trailing ".0" on whole numbers.
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(Math.round(d * 1000.0) / 1000.0);
    }
}
