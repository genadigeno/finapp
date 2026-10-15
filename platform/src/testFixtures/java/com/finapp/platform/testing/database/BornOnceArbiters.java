package com.finapp.platform.testing.database;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lock-bypass probe of every born-once arbiter of a schema, read from the catalogue (`P11-DOC-001`).
 *
 * <p>A born-once fact is protected three ways - the domain's precondition under a row lock, the every-writer trigger, and
 * beneath them the {@code UNIQUE} itself - and the gate asks that the last stand alone: with every user trigger and every
 * foreign-key trigger suspended ({@code session_replication_role = replica}, as the bootstrap superuser), a copy of an
 * existing row under a fresh primary key must be refused {@code 23505} by THAT constraint. Each copy keeps the probed
 * constraint's columns and freshens the primary key and every column of every OTHER unique of the table, so no sibling
 * can refuse it first - the copy is refused by the probed unique alone, or the probe says so.
 *
 * <p>Read from {@code pg_index}, never from a list: a unique added later is probed without anyone remembering, and one
 * dropped by a mutation is found admitting its copy. A unique whose columns include the whole primary key is a foreign-key
 * target ("of its loan", "proposer pinned"), implied by the key, and reported as such. The source row is one whose probed
 * columns are all non-null (nulls never collide) and, for a partial index, one inside its predicate. Everything runs in one
 * transaction, each probe in a savepoint, and is rolled back: nothing the probe writes survives.
 */
public final class BornOnceArbiters {

    private BornOnceArbiters() {}

    /** What one unique did with its copy. */
    public enum Verdict {
        /** The copy was refused {@code 23505} by the probed constraint itself - the arbiter stands alone. */
        REFUSED_ALONE,
        /** The copy was ADMITTED: the constraint did not refuse a duplicate of its own columns. */
        ADMITTED,
        /** The copy was refused by something else first - the probe could not isolate the unique. */
        REFUSED_BY_ANOTHER,
        /** No row of the table carried the probed columns (non-null, inside the predicate) to copy. */
        NO_SOURCE_ROW,
        /** The unique's columns include the whole primary key: a foreign-key target the key already implies. */
        PRIMARY_KEY_BOUND,
        /** An expression index, which the probe cannot copy column by column. */
        EXPRESSION_INDEX
    }

    /** One unique's probe: its name, its table, its verdict, and what refused the copy when it was not the unique. */
    public record Probe(String constraint, String table, Verdict verdict, String detail) {

        @Override
        public String toString() {
            return table + "." + constraint + "=" + verdict + (detail.isEmpty() ? "" : "(" + detail + ")");
        }
    }

    private record Unique(String table, String name, List<String> columns, String predicate, boolean expression) {}

    private record Column(String name, String type) {}

    private static final Pattern DUPLICATE = Pattern.compile("unique constraint \"([^\"]+)\"");

    /** Probes every unique index of every ordinary table of {@code schema}. */
    public static List<Probe> probe(String schema) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try {
                try (Statement statement = owner.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                    statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
                }
                List<Unique> uniques = uniques(owner, schema);
                Map<String, List<String>> primaryKeys = primaryKeys(owner, schema);
                List<Probe> probes = new ArrayList<>();
                for (Unique unique : uniques) {
                    probes.add(probe(owner, schema, unique, primaryKeys.getOrDefault(unique.table(), List.of()),
                            uniques));
                }
                return probes;
            } finally {
                owner.rollback();
            }
        }
    }

    private static Probe probe(Connection owner, String schema, Unique unique, List<String> primaryKey,
            List<Unique> all) throws SQLException {
        if (unique.expression()) {
            return new Probe(unique.name(), unique.table(), Verdict.EXPRESSION_INDEX, "");
        }
        if (!primaryKey.isEmpty() && unique.columns().containsAll(primaryKey)) {
            return new Probe(unique.name(), unique.table(), Verdict.PRIMARY_KEY_BOUND, "");
        }
        Set<String> fresh = new java.util.HashSet<>(primaryKey);
        for (Unique other : all) {
            // A sibling binding the whole primary key is satisfied by the fresh key already: its other columns stay.
            boolean keyBound = !primaryKey.isEmpty() && other.columns().containsAll(primaryKey);
            if (other.table().equals(unique.table()) && !other.name().equals(unique.name()) && !other.expression()
                    && !keyBound) {
                fresh.addAll(other.columns());
            }
        }
        fresh.removeAll(unique.columns());
        List<Column> columns = columns(owner, schema, unique.table());
        StringBuilder names = new StringBuilder();
        StringBuilder values = new StringBuilder();
        for (Column column : columns) {
            if (names.length() > 0) {
                names.append(", ");
                values.append(", ");
            }
            names.append('"').append(column.name()).append('"');
            values.append(fresh.contains(column.name()) ? freshened(column) : "\"" + column.name() + "\"");
        }
        StringBuilder source = new StringBuilder("TRUE");
        for (String column : unique.columns()) {
            source.append(" AND \"").append(column).append("\" IS NOT NULL");
        }
        if (unique.predicate() != null) {
            source.append(" AND (").append(unique.predicate()).append(')');
        }
        String table = schema + "." + unique.table();
        String insert = "INSERT INTO " + table + " (" + names + ") OVERRIDING SYSTEM VALUE SELECT " + values + " FROM "
                + table + " WHERE " + source + " LIMIT 1";
        Savepoint savepoint = owner.setSavepoint();
        try (Statement statement = owner.createStatement()) {
            int copied = statement.executeUpdate(insert);
            return copied == 0
                    ? new Probe(unique.name(), unique.table(), Verdict.NO_SOURCE_ROW, "")
                    : new Probe(unique.name(), unique.table(), Verdict.ADMITTED, "");
        } catch (SQLException refused) {
            String message = String.valueOf(refused.getMessage());
            Matcher duplicate = DUPLICATE.matcher(message);
            if ("23505".equals(refused.getSQLState()) && duplicate.find()) {
                return duplicate.group(1).equals(unique.name())
                        ? new Probe(unique.name(), unique.table(), Verdict.REFUSED_ALONE, "")
                        : new Probe(unique.name(), unique.table(), Verdict.REFUSED_BY_ANOTHER, duplicate.group(1));
            }
            return new Probe(unique.name(), unique.table(), Verdict.REFUSED_BY_ANOTHER,
                    refused.getSQLState() + ": " + message.lines().findFirst().orElse(""));
        } finally {
            owner.rollback(savepoint);
        }
    }

    /** A value of {@code column}'s type that collides with nothing the copy's source row holds. */
    private static String freshened(Column column) {
        String quoted = "\"" + column.name() + "\"";
        String type = column.type();
        if (type.equals("uuid")) {
            return "gen_random_uuid()";
        }
        if (type.equals("text") || type.startsWith("character varying")) {
            return quoted + " || '-copy-' || substr(md5(random()::text), 1, 8)";
        }
        if (type.equals("smallint")) {
            return "(" + quoted + " + 7000)::smallint";
        }
        if (type.equals("integer") || type.equals("bigint") || type.startsWith("numeric")) {
            return quoted + " + 700000";
        }
        if (type.equals("date")) {
            return quoted + " + 3650";
        }
        if (type.startsWith("timestamp")) {
            return quoted + " + interval '3650 days'";
        }
        throw new IllegalStateException("the probe cannot freshen a column of type " + type + " (" + column.name()
                + ") - extend BornOnceArbiters.freshened");
    }

    private static List<Unique> uniques(Connection connection, String schema) throws SQLException {
        String sql = "SELECT c.relname, i.relname, pg_get_expr(x.indpred, x.indrelid), x.indkey::int2[],"
                + " ARRAY(SELECT a.attname FROM unnest(x.indkey::int2[]) WITH ORDINALITY k(attnum, ord)"
                + " JOIN pg_attribute a ON a.attrelid = x.indrelid AND a.attnum = k.attnum ORDER BY k.ord)"
                + " FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid JOIN pg_class c ON c.oid = x.indrelid"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = ? AND c.relkind = 'r' AND x.indisunique AND NOT x.indisprimary ORDER BY 1, 2";
        List<Unique> uniques = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    Short[] keys = (Short[]) row.getArray(4).getArray();
                    boolean expression = Arrays.stream(keys).anyMatch(key -> key == 0);
                    Array names = row.getArray(5);
                    uniques.add(new Unique(row.getString(1), row.getString(2), List.of((String[]) names.getArray()),
                            row.getString(3), expression));
                }
            }
        }
        return uniques;
    }

    private static Map<String, List<String>> primaryKeys(Connection connection, String schema) throws SQLException {
        String sql = "SELECT c.relname, ARRAY(SELECT a.attname FROM unnest(x.indkey::int2[]) k(attnum)"
                + " JOIN pg_attribute a ON a.attrelid = x.indrelid AND a.attnum = k.attnum)"
                + " FROM pg_index x JOIN pg_class c ON c.oid = x.indrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = ? AND c.relkind = 'r' AND x.indisprimary";
        Map<String, List<String>> keys = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    keys.put(row.getString(1), List.of((String[]) row.getArray(2).getArray()));
                }
            }
        }
        return keys;
    }

    private static List<Column> columns(Connection connection, String schema, String table) throws SQLException {
        String sql = "SELECT a.attname, format_type(a.atttypid, a.atttypmod) FROM pg_attribute a"
                + " JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped"
                + " AND a.attgenerated = '' ORDER BY a.attnum";
        List<Column> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    columns.add(new Column(row.getString(1), row.getString(2)));
                }
            }
        }
        return columns;
    }
}
