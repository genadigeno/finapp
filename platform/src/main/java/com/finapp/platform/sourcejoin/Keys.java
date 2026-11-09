package com.finapp.platform.sourcejoin;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** How a join takes an entry's identity, and what it binds: only values already proved castable reach the statement. */
public final class Keys {

    /** How one join identifies the row behind an entry. */
    public interface KeyForm {
        /** The key text a row must return in its first column to be this entry's record: empty when the entry cannot name one. */
        Optional<String> keyOf(EntryRef entry);

        /** The array the statement's {@code ANY} binds; every element castable to the statement's type. */
        Object[] bind(Collection<EntryRef> entries);

        /** The array's PostgreSQL element type. */
        String arrayType();
    }

    private Keys() {}

    /** The suffix is the record's id: canonical lower-case UUID, or nothing. */
    public static KeyForm suffixUuid() {
        return new KeyForm() {
            @Override
            public Optional<String> keyOf(EntryRef entry) {
                return uuid(entry.suffix()).map(UUID::toString);
            }

            @Override
            public Object[] bind(Collection<EntryRef> entries) {
                return entries.stream().map(EntryRef::suffix).map(Keys::uuid).flatMap(Optional::stream).toArray();
            }

            @Override
            public String arrayType() {
                return "uuid";
            }
        };
    }

    /**
     * The whole suffix is the key text, bound as text; {@code valid} proves it well-formed first, so the statement's casts of
     * its parts cannot fail on a key a forger made up (a malformed key joins nothing, and is counted unsourced).
     */
    public static KeyForm suffixMatching(Predicate<String> valid) {
        Objects.requireNonNull(valid, "valid");
        return new KeyForm() {
            @Override
            public Optional<String> keyOf(EntryRef entry) {
                return valid.test(entry.suffix()) ? Optional.of(entry.suffix()) : Optional.empty();
            }

            @Override
            public Object[] bind(Collection<EntryRef> entries) {
                return entries.stream().map(EntryRef::suffix).filter(valid).toArray();
            }

            @Override
            public String arrayType() {
                return "text";
            }
        };
    }

    /** The entry's own id is the key: the record names the entry ({@code journal_entry_id}). */
    public static KeyForm entryId() {
        return new KeyForm() {
            @Override
            public Optional<String> keyOf(EntryRef entry) {
                return Optional.of(entry.entry().toString());
            }

            @Override
            public Object[] bind(Collection<EntryRef> entries) {
                return entries.stream().map(EntryRef::entry).toArray();
            }

            @Override
            public String arrayType() {
                return "uuid";
            }
        };
    }

    /** The entry's {@code reference} is the record's id. */
    public static KeyForm referenceUuid() {
        return new KeyForm() {
            @Override
            public Optional<String> keyOf(EntryRef entry) {
                return uuid(entry.reference()).map(UUID::toString);
            }

            @Override
            public Object[] bind(Collection<EntryRef> entries) {
                return entries.stream().map(EntryRef::reference).map(Keys::uuid).flatMap(Optional::stream).toArray();
            }

            @Override
            public String arrayType() {
                return "uuid";
            }
        };
    }

    /** {@code <uuid>:<yyyy-mm-dd>}: a real date, or nothing. */
    public static Predicate<String> uuidThenDate() {
        return suffix -> {
            List<String> parts = parts(suffix, 2);
            if (parts.size() != 2 || uuid(parts.get(0)).isEmpty()) {
                return false;
            }
            // Strictly yyyy-mm-dd: LocalDate also reads "+12345-01-01", which PostgreSQL's ::date refuses. And year 0, which it refuses
            // too. A forger's key joins nothing; it must never reach a cast that fails the batch.
            if (!parts.get(1).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                return false;
            }
            try {
                return LocalDate.parse(parts.get(1)).getYear() >= 1;
            } catch (DateTimeParseException notADate) {
                return false;
            }
        };
    }

    /** {@code <uuid>:<word>} with one of these exact words: {@code <instalment>:LATE}. */
    public static Predicate<String> uuidThenWord(String... words) {
        List<String> allowed = Arrays.asList(words);
        return suffix -> {
            List<String> parts = parts(suffix, 2);
            return parts.size() == 2 && uuid(parts.get(0)).isPresent() && allowed.contains(parts.get(1));
        };
    }

    /** {@code <uuid>:<anything>}: {@code <dispute>:<cause>}, whose cause part the statement never casts. */
    public static Predicate<String> uuidHead() {
        return suffix -> {
            List<String> parts = parts(suffix, 2);
            return parts.size() == 2 && uuid(parts.get(0)).isPresent() && !parts.get(1).isEmpty();
        };
    }

    /** {@code <uuid>:catch-up:<n>}: the catch-up shape of a credit balance application. */
    public static Predicate<String> uuidThenCatchUp() {
        return suffix -> {
            List<String> parts = Arrays.asList(suffix.split(":", 3));
            return parts.size() == 3 && uuid(parts.get(0)).isPresent() && "catch-up".equals(parts.get(1))
                    && parts.get(2).matches("[0-9]{1,9}");
        };
    }

    /** A bare canonical UUID, in a {@code suffixMatching} statement that handles several key shapes. */
    public static Predicate<String> uuidOnly() {
        return suffix -> uuid(suffix).isPresent();
    }

    /** {@code <rail>:<reference>}: the rail's shape and a reference without separators to confuse the split. */
    public static Predicate<String> railReference() {
        return suffix -> {
            List<String> parts = parts(suffix, 2);
            return parts.size() == 2 && parts.get(0).matches("[a-z][a-z0-9-]{0,31}") && !parts.get(1).isEmpty();
        };
    }

    private static List<String> parts(String suffix, int limit) {
        return Arrays.asList(suffix.split(":", limit));
    }

    private static Optional<UUID> uuid(String text) {
        // java.util.UUID.fromString is lenient about short groups ("1-2-3-4-5" parses) and about case: a key is the canonical
        // lower-case form or nothing - an upper-cased copy of a real id's key is a different key and names no record.
        if (text.length() != 36) {
            return Optional.empty();
        }
        try {
            UUID parsed = UUID.fromString(text);
            return parsed.toString().equals(text) ? Optional.of(parsed) : Optional.empty();
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }
}
