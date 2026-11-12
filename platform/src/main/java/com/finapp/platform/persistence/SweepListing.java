package com.finapp.platform.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a sweep's listing read (`P16-TSK-017`; {@code THREAT_MODEL.md} section 6 item 42 (i) (ii)): the candidates it could
 * rehydrate, in the listing's order, and the raw ids of the rows it could not.
 *
 * <p><strong>Why a listing skips rather than throws.</strong> A sweep lists its candidates by rehydrating them into typed
 * values before its per-row containment starts. A row no domain writer could produce - an id that is not a UUIDv7, which
 * {@code EntityId} refuses at birth, written by a raw writer - threw in the listing, so the whole tick failed, on every
 * instance and every tick, and no candidate behind it was reached ({@code OPERATIONS_RUNBOOK.md} section 11.39 (a)). Here the
 * row is skipped and its id kept, so the sweep reaches every other row and its caller counts, records and pages the skipped
 * one. Nothing is concluded about it: an unread row is never judged.
 *
 * <p><strong>What counts as unreadable, and what never does.</strong> Only an unchecked exception thrown while turning one row
 * into its value - a refused id, an unknown status, a missing required column - is the row's own fault. An
 * {@link SQLException} is the store's (a lost connection, a cancelled statement) and propagates, so a store failure fails the
 * tick and is never misread as a poisoned row.
 *
 * @param candidates the rows the listing could read, in its order
 * @param unreadable the ids of the rows it could not, in its order
 */
public record SweepListing<T>(List<T> candidates, List<UUID> unreadable) {

    public SweepListing {
        candidates = List.copyOf(candidates);
        unreadable = List.copyOf(unreadable);
    }

    /** A listing that read every row it found - what a store double answers. */
    public static <T> SweepListing<T> of(List<T> candidates) {
        return new SweepListing<>(candidates, List.of());
    }

    /** Turns one row into its value; an unchecked exception means the row cannot be read. */
    @FunctionalInterface
    public interface RowReader<T> {
        T read(ResultSet row) throws SQLException;
    }

    /**
     * Reads every remaining row of {@code rows}: each one's {@code idColumn} first, as the raw value the column holds, then the
     * row through {@code reader}. A row the reader refuses with an unchecked exception is skipped and its id kept; an
     * {@link SQLException} propagates.
     */
    public static <T> SweepListing<T> read(ResultSet rows, String idColumn, RowReader<T> reader) throws SQLException {
        Objects.requireNonNull(rows, "rows must not be null");
        Objects.requireNonNull(idColumn, "idColumn must not be null");
        Objects.requireNonNull(reader, "reader must not be null");
        List<T> candidates = new ArrayList<>();
        List<UUID> unreadable = new ArrayList<>();
        while (rows.next()) {
            UUID id = rows.getObject(idColumn, UUID.class);
            T candidate;
            try {
                candidate = reader.read(rows);
            } catch (RuntimeException refused) {
                // The row's own content refused - never an SQLException, which is the store's and fails the tick.
                unreadable.add(id);
                continue;
            }
            candidates.add(candidate);
        }
        return new SweepListing<>(candidates, unreadable);
    }
}
