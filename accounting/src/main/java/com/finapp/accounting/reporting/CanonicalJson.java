package com.finapp.accounting.reporting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The canonical JSON form of a report run's content (`P14-TSK-008`; ADR-0115 decision 2; {@code INV-ACC-04},
 * {@code INV-MON-01}): the same value always yields the same bytes, and so the same SHA-256 - on any JVM, in any locale,
 * any time zone - which is what lets a FINAL run's regeneration be compared byte for byte, and lets {@code accounting V004}
 * verify the stored hash against the stored text for every writer.
 *
 * <p><strong>The form.</strong> UTF-8; no whitespace; object members sorted by key in {@link String#compareTo} order (UTF-16
 * code units - RFC 8785's ordering), a key held once; strings escaped as ECMAScript's {@code JSON.stringify} escapes them
 * (quote and backslash escaped by a backslash; backspace, form feed, line feed, carriage return and tab by their
 * one-letter escapes; every other control character by a six-character backslash-u escape in lowercase hex; every other
 * character literal). <strong>There are no JSON numbers at all</strong>: a whole number is written as its decimal
 * string, an amount as its minor units in a string beside its currency and scale ({@link Obj#putMoney}) - so no floating
 * point can enter a report, and no reader can parse an amount into one. Booleans and {@code null} are literals.
 *
 * <p><strong>Refused, not escaped:</strong> {@code U+0000} (PostgreSQL's {@code jsonb} refuses its escape, so the stored
 * text could not be checked as JSON) and an unpaired surrogate (no UTF-8 encoding exists). A refusal fails the run's
 * generation - never a silently altered report.
 *
 * <p>Report code: this class is in {@code com.finapp.accounting.reporting} and reads and writes nothing
 * ({@code ReportingNeverWritesTest}). A change to the form is a new definition version, never an edit of this one.
 */
public final class CanonicalJson {

    private static final HexFormat HEX = HexFormat.of();

    private CanonicalJson() {}

    /** A value of the form: an object, an array, a string, a boolean or {@code null}. */
    public sealed interface Value permits Obj, Arr, Text, Flag, Null {

        /** Appends this value's canonical text. */
        void writeTo(StringBuilder out);
    }

    /** A new, empty object. */
    public static Obj object() {
        return new Obj();
    }

    /** A new, empty array. */
    public static Arr array() {
        return new Arr();
    }

    /** {@code value}'s canonical text. */
    public static String text(Value value) {
        Objects.requireNonNull(value, "value");
        StringBuilder out = new StringBuilder(256);
        value.writeTo(out);
        return out.toString();
    }

    /** {@code value}'s canonical bytes - its text in UTF-8. */
    public static byte[] bytes(Value value) {
        return text(value).getBytes(StandardCharsets.UTF_8);
    }

    /** The lowercase hex SHA-256 of {@code text}'s UTF-8 bytes - what {@code accounting V004} recomputes. */
    public static String sha256(String text) {
        Objects.requireNonNull(text, "text");
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", impossible);
        }
    }

    // ------------------------------------------------------------------ the values

    /** An object: members sorted by key, each key once. Built by its {@code put} calls; written in key order. */
    public static final class Obj implements Value {

        private final TreeMap<String, Value> members = new TreeMap<>();

        private Obj() {}

        /** A string member. */
        public Obj put(String key, String text) {
            return member(key, new Text(Objects.requireNonNull(text, "text - an absence is putNull")));
        }

        /** A whole number, written as its decimal string - never a JSON number. */
        public Obj put(String key, long whole) {
            return member(key, new Text(Long.toString(whole)));
        }

        /** A boolean member. */
        public Obj put(String key, boolean flag) {
            return member(key, flag ? Flag.TRUE : Flag.FALSE);
        }

        /** A nested object or array. */
        public Obj put(String key, Value value) {
            return member(key, Objects.requireNonNull(value, "value - an absence is putNull"));
        }

        /** An explicit absence. */
        public Obj putNull(String key) {
            return member(key, Null.INSTANCE);
        }

        /**
         * An amount: {@code {"currency":"EUR","minor":"12345","scale":"2"}} - the minor units a string, the currency and
         * scale explicit ({@code INV-MON-01}, {@code MoneyColumns}' shape).
         */
        public Obj putMoney(String key, long minorUnits, String currency, int scale) {
            Objects.requireNonNull(currency, "currency");
            if (!currency.matches("[A-Z]{3}") || scale < 0 || scale > 18) {
                throw new IllegalArgumentException("an amount names an ISO 4217 currency and a scale of 0 to 18");
            }
            return put(key, object().put("currency", currency).put("minor", minorUnits).put("scale", scale));
        }

        /** The members, in key order - read-only. */
        public Map<String, Value> members() {
            return Collections.unmodifiableMap(members);
        }

        private Obj member(String key, Value value) {
            Objects.requireNonNull(key, "key");
            checked(key);
            if (members.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("a canonical object holds each key once");
            }
            return this;
        }

        @Override
        public void writeTo(StringBuilder out) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Value> member : members.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(out, member.getKey());
                out.append(':');
                member.getValue().writeTo(out);
            }
            out.append('}');
        }
    }

    /** An array: its elements in the order they were added. */
    public static final class Arr implements Value {

        private final List<Value> elements = new ArrayList<>();

        private Arr() {}

        public Arr add(String text) {
            elements.add(new Text(Objects.requireNonNull(text, "text - an absence is addNull")));
            return this;
        }

        public Arr add(long whole) {
            elements.add(new Text(Long.toString(whole)));
            return this;
        }

        public Arr add(boolean flag) {
            elements.add(flag ? Flag.TRUE : Flag.FALSE);
            return this;
        }

        public Arr add(Value value) {
            elements.add(Objects.requireNonNull(value, "value - an absence is addNull"));
            return this;
        }

        public Arr addNull() {
            elements.add(Null.INSTANCE);
            return this;
        }

        /** The elements, in order - read-only. */
        public List<Value> elements() {
            return Collections.unmodifiableList(elements);
        }

        @Override
        public void writeTo(StringBuilder out) {
            out.append('[');
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                elements.get(i).writeTo(out);
            }
            out.append(']');
        }
    }

    /** A string. */
    public record Text(String value) implements Value {

        public Text {
            checked(Objects.requireNonNull(value, "value"));
        }

        @Override
        public void writeTo(StringBuilder out) {
            string(out, value);
        }
    }

    /** {@code true} or {@code false}. */
    public enum Flag implements Value {
        TRUE,
        FALSE;

        @Override
        public void writeTo(StringBuilder out) {
            out.append(this == TRUE ? "true" : "false");
        }
    }

    /** {@code null}. */
    public enum Null implements Value {
        INSTANCE;

        @Override
        public void writeTo(StringBuilder out) {
            out.append("null");
        }
    }

    // ------------------------------------------------------------------ strings

    /** Refuses {@code U+0000} and an unpaired surrogate - the two characters the stored form cannot hold. */
    private static String checked(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u0000') {
                throw new IllegalArgumentException("a canonical string holds no U+0000");
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("a canonical string holds no unpaired surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("a canonical string holds no unpaired surrogate");
            }
        }
        return value;
    }

    /** {@code value} quoted and escaped as {@code JSON.stringify} escapes it. */
    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xF, 16));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
