package com.finapp.platform.sourcejoin;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * One namespace's source join: which record an entry of this namespace answers to, what is compared, and the read itself - a
 * {@code SELECT} over its own module's tables only, on the caller's connection, with bind parameters only.
 */
public interface SourceJoin {

    /** The namespace this join answers for. */
    SourceNamespace namespace();

    /**
     * The join and the comparison in one sentence, for the guard and a person: which record, by what, what must equal what. Text
     * only: no amount, no identifier.
     */
    String statement();

    /** The SQL statement this join reads with, so a boundary test can hold it to its own schema. */
    String sql();

    /**
     * The record behind each of {@code entries}, keyed by entry id. An entry with no record is simply absent from the answer: that
     * is what the census counts as unsourced.
     *
     * @throws SQLException a read that fails: the census fails the whole batch rather than reading it as absent
     */
    Map<UUID, SourceFigures> read(Connection connection, Collection<EntryRef> entries) throws SQLException;
}
