package com.finapp.platform.sourcejoin;

import com.finapp.platform.sourcejoin.Keys.KeyForm;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A join that is one parameterised {@code SELECT}: the shape every namespace of the register takes. The statement binds one
 * array, the {@code ANY} (or the {@code unnest}) of its key, and returns one or more rows per key: the key text in its first
 * column, the figures from the rest. Rows of one key are combined: a proposal's lines, one row per currency and side.
 *
 * <p><strong>A key that cannot name a record joins nothing.</strong> A suffix that is not the identifier its namespace expects
 * (a forger's free text, a corrupt key) binds nothing and comes back with no record, which is what the census counts as
 * unsourced: it never throws into the batch.
 */
public final class SqlSourceJoin implements SourceJoin {

    /** One row: its figures. */
    @FunctionalInterface
    public interface RowFigures {
        SourceFigures read(ResultSet row) throws SQLException;
    }

    private final SourceNamespace namespace;
    private final String statement;
    private final String sql;
    private final KeyForm keyForm;
    private final RowFigures figures;

    /** @param sql one parameter, the key array; the first column is the key text */
    public SqlSourceJoin(SourceDoor door, String namespace, String statement, String sql, KeyForm keyForm, RowFigures figures) {
        this.namespace = new SourceNamespace(door, namespace);
        if (Objects.requireNonNull(statement, "statement").isBlank()) {
            throw new IllegalArgumentException("a join states itself");
        }
        this.statement = statement;
        this.sql = Objects.requireNonNull(sql, "sql");
        this.keyForm = Objects.requireNonNull(keyForm, "keyForm");
        this.figures = Objects.requireNonNull(figures, "figures");
    }

    @Override
    public SourceNamespace namespace() {
        return namespace;
    }

    @Override
    public String statement() {
        return statement;
    }

    @Override
    public String sql() {
        return sql;
    }

    @Override
    public Map<UUID, SourceFigures> read(Connection connection, Collection<EntryRef> entries) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(entries, "entries");
        Map<UUID, SourceFigures> found = new HashMap<>();
        Map<String, List<UUID>> byKey = new HashMap<>();
        for (EntryRef entry : entries) {
            Optional<String> key = keyForm.keyOf(entry);
            key.ifPresent(text -> byKey.computeIfAbsent(text, k -> new ArrayList<>()).add(entry.entry()));
        }
        if (byKey.isEmpty()) {
            return found;
        }
        Object[] bound = keyForm.bind(entries);
        if (bound.length == 0) {
            return found;
        }
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setArray(1, connection.createArrayOf(keyForm.arrayType(), bound));
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    List<UUID> named = byKey.get(row.getString(1));
                    if (named == null) {
                        continue;
                    }
                    SourceFigures read = figuresOf(row);
                    named.forEach(entry -> found.merge(entry, read, SqlSourceJoin::combined));
                }
            }
        }
        return found;
    }

    /**
     * A row that cannot be read into figures (a value outside what a figure may hold) is this one record's trouble, not the batch's: it
     * reads as a record with no comparable figure, counted, and the walk goes on. A failure of the read itself still throws.
     */
    private SourceFigures figuresOf(ResultSet row) throws SQLException {
        try {
            return figures.read(row);
        } catch (RuntimeException unreadable) {
            return SourceFigures.noFigure();
        }
    }

    /** Several rows for one key: the legs of each. */
    private static SourceFigures combined(SourceFigures left, SourceFigures right) {
        if (!left.compares() || !right.compares()) {
            return left.compares() ? right : left;
        }
        List<ExpectedLeg> legs = new ArrayList<>(left.legs());
        legs.addAll(right.legs());
        return SourceFigures.of(legs);
    }
}
