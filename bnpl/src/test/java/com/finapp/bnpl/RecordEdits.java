package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.PurposeBalances;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Plants in a {@link BnplRecord} - a world broken on purpose, one fact at a time (`P12-TSK-025`): a copy of any record (the
 * record's own or one of its rows) with one component replaced, and the journal edits a plant needs beside a row's. The
 * hermetic probes of the proofs read these; the database's are {@code BnplProofDatabaseTest}'s raw SQL.
 */
final class RecordEdits {

    private RecordEdits() {}

    /** {@code record} - any Java record - with its component {@code name} replaced by {@code value}. */
    static <R extends Record> R with(R record, String name, Object value) {
        return with(record, java.util.Map.of(name, value));
    }

    /** {@code record} with every named component replaced at once - a plant that keeps a row's own invariants. */
    static <R extends Record> R with(R record, String name, Object value, String other, Object otherValue) {
        return with(record, java.util.Map.of(name, value, other, otherValue));
    }

    @SuppressWarnings("unchecked")
    private static <R extends Record> R with(R record, java.util.Map<String, Object> replaced) {
        try {
            RecordComponent[] components = record.getClass().getRecordComponents();
            Object[] values = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            int found = 0;
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                if (replaced.containsKey(components[i].getName())) {
                    values[i] = replaced.get(components[i].getName());
                    found++;
                } else {
                    values[i] = components[i].getAccessor().invoke(record);
                }
            }
            if (found != replaced.size()) {
                throw new IllegalArgumentException(record.getClass().getSimpleName() + " lacks a component of " + replaced.keySet());
            }
            Constructor<?> canonical = record.getClass().getDeclaredConstructor(types);
            canonical.setAccessible(true);
            return (R) canonical.newInstance(values);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** {@code rows} with the element at {@code index} replaced by {@code edit}'s answer. */
    static <T> List<T> edited(List<T> rows, int index, UnaryOperator<T> edit) {
        List<T> copy = new ArrayList<>(rows);
        copy.set(index, edit.apply(copy.get(index)));
        return copy;
    }

    /** {@code rows} without the element at {@code index}. */
    static <T> List<T> without(List<T> rows, int index) {
        List<T> copy = new ArrayList<>(rows);
        copy.remove(index);
        return copy;
    }

    /** {@code record}'s journal lines without every line of {@code entry}. */
    static BnplRecord withoutEntry(BnplRecord record, JournalEntryId entry) {
        return with(record, "journalLines", record.journalLines().stream().filter(line -> !line.entry().equals(entry))
                .toList());
    }

    /** {@code record}'s journal lines, each line of {@code entry} re-dated {@code date}. */
    static BnplRecord redated(BnplRecord record, JournalEntryId entry, LocalDate date) {
        return with(record, "journalLines", record.journalLines().stream().map(line -> line.entry().equals(entry)
                ? new PurposeBalances.DatedLine(line.entry(), line.account(), date, line.direction(), line.amount())
                : line).toList());
    }

    /** {@code record}'s journal lines, each mapped by {@code edit}. */
    static BnplRecord lines(BnplRecord record, Function<PurposeBalances.DatedLine, PurposeBalances.DatedLine> edit) {
        return with(record, "journalLines", record.journalLines().stream().map(edit).toList());
    }
}
