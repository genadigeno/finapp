package com.finapp.app.database;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A migration's version, compared the way Flyway compares it (`P15-TSK-005`): part by part as whole numbers, a missing
 * part counting as zero. So {@code 10} is above {@code 9}, {@code 033} is {@code 33}, and {@code 1.0} is {@code 1}. As
 * text, {@code 9} would sort after {@code 10}: a readiness guard comparing text would hold back a schema that is
 * current and pass one that is behind.
 *
 * <p>Trailing zero parts are dropped at construction, so that {@code equals} agrees with {@code compareTo}.
 *
 * @param parts the version's parts, at least one, none negative
 */
public record SchemaVersion(List<BigInteger> parts) implements Comparable<SchemaVersion> {

    /** What Flyway accepts as a version: whole numbers separated by a dot, or by the underscore a file name uses. */
    private static final Pattern FORM = Pattern.compile("[0-9]+(?:[._][0-9]+)*");

    /** The version below every migration: a schema with no history. */
    public static final SchemaVersion ZERO = new SchemaVersion(List.of(BigInteger.ZERO));

    public SchemaVersion {
        Objects.requireNonNull(parts, "parts");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("a version has at least one part");
        }
        List<BigInteger> kept = new ArrayList<>(parts);
        while (kept.size() > 1 && kept.getLast().signum() == 0) {
            kept.removeLast();
        }
        parts = List.copyOf(kept);
    }

    /**
     * @param text a version as a file name or Flyway's history writes it
     * @throws IllegalArgumentException if it is not one: a version read back from a database is never guessed at
     */
    public static SchemaVersion parse(String text) {
        if (text == null || !FORM.matcher(text).matches()) {
            throw new IllegalArgumentException("not a migration version: " + text);
        }
        return new SchemaVersion(Arrays.stream(text.split("[._]")).map(BigInteger::new).toList());
    }

    @Override
    public int compareTo(SchemaVersion other) {
        int longest = Math.max(parts.size(), other.parts.size());
        for (int index = 0; index < longest; index++) {
            int compared = part(index).compareTo(other.part(index));
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    private BigInteger part(int index) {
        return index < parts.size() ? parts.get(index) : BigInteger.ZERO;
    }

    @Override
    public String toString() {
        return parts.stream().map(BigInteger::toString).collect(Collectors.joining("."));
    }
}
