package com.finapp.platform.testing.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The "no mutable money column" floor of a schema, judged against the catalogue (`P11-TSK-002`; moved here from
 * lending's {@code LendingSchemaHasNoMutableMoneyTest} by `P12-TSK-002`, unchanged but for the schema it judges being an
 * argument): one judgement for every module whose schema holds that floor - lending's ({@code INV-LND-01}) and
 * bnpl's ({@code INV-BNPL-01}), each module's own {@code ...SchemaHasNoMutableMoneyTest} planting its shapes in its own
 * schema. Never a second copy of the rule.
 *
 * <p><strong>The rule.</strong> Every money column is {@code *_minor bigint}, and every relation carrying one is
 * INSERT-only: owned by the migrator, and no other grantee - the application role, any other role, {@code PUBLIC} -
 * holds {@code UPDATE} (on the table or on any of its columns), {@code DELETE} or {@code TRUNCATE} on it. And no column
 * anywhere in the schema is binary floating point or PostgreSQL's {@code money} type ({@code INV-MON-01}).
 *
 * <p><strong>Read from the catalogue</strong> - {@code pg_class.relacl} and {@code pg_attribute.attacl}, what the
 * database will enforce - as seen by the caller's connection, so a module's test can plant a relation in an uncommitted
 * transaction and judge it with the very query the live check runs.
 */
public final class MoneyColumnFloor {

    /** The role every schema-owning module's objects belong to (P0-TSK-022). */
    public static final String OWNER = "finapp_migrator";

    /**
     * Relations a money column can sit on: tables, partitioned tables, views (an updatable view is a write path),
     * materialised views and foreign tables.
     */
    private static final String RELATIONS = "('r', 'p', 'v', 'm', 'f')";

    /** The relation {@code c} has a live column named {@code *_minor} - the schema's spelling of money. */
    private static final String CARRIES_MONEY = "EXISTS (SELECT 1 FROM pg_attribute m WHERE m.attrelid = c.oid"
            + " AND m.attnum > 0 AND NOT m.attisdropped AND m.attname LIKE '%!_minor' ESCAPE '!')";

    private MoneyColumnFloor() {}

    /**
     * Every breach of the rule in {@code schema}, as seen by {@code connection} - one line each, naming the relation (and
     * column), the grantee and the privilege, so a failure says what to fix.
     */
    public static List<String> violations(Connection connection, String schema) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(schema, "schema");
        List<String> violations = new ArrayList<>();

        // 1. A money column is bigint minor units - never numeric, text or a float.
        collect(connection, schema, violations, """
                SELECT c.relname || '.' || a.attname || ': a money column is bigint minor units, never '
                       || format_type(a.atttypid, a.atttypmod)
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                 WHERE n.nspname = ? AND c.relkind IN %s
                   AND a.attname LIKE '%%!_minor' ESCAPE '!'
                   AND a.atttypid <> 'bigint'::regtype
                """.formatted(RELATIONS));

        // 2. No binary floating point (or the money type) anywhere in the schema, arrays included.
        collect(connection, schema, violations, """
                SELECT c.relname || '.' || a.attname || ': ' || format_type(a.atttypid, a.atttypmod)
                       || ' is binary floating point or the money type (INV-MON-01)'
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                  JOIN pg_type t ON t.oid = a.atttypid
                 WHERE n.nspname = ? AND c.relkind IN %s
                   AND COALESCE(NULLIF(t.typelem, 0), t.oid)
                       IN ('real'::regtype, 'double precision'::regtype, 'money'::regtype)
                """.formatted(RELATIONS));

        // 3. A relation carrying money is owned by the migrator - its owner can always update it.
        collect(connection, schema, violations, """
                SELECT c.relname || ': carries money and is owned by ' || pg_get_userbyid(c.relowner)
                       || ', not %s - an owner can always update'
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ? AND c.relkind IN %s AND %s
                   AND pg_get_userbyid(c.relowner) <> '%s'
                """.formatted(OWNER, RELATIONS, CARRIES_MONEY, OWNER));

        // 4. No other grantee - any role, or PUBLIC - holds UPDATE, DELETE or TRUNCATE on it...
        collect(connection, schema, violations, """
                SELECT c.relname || ': carries money and ' || COALESCE(r.rolname, 'PUBLIC') || ' holds '
                       || acl.privilege_type || ' - not INSERT-only'
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 CROSS JOIN LATERAL aclexplode(c.relacl) acl
                  LEFT JOIN pg_roles r ON r.oid = acl.grantee
                 WHERE n.nspname = ? AND c.relkind IN %s AND %s
                   AND acl.grantee <> c.relowner
                   AND acl.privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE')
                """.formatted(RELATIONS, CARRIES_MONEY));

        // 5. ...nor UPDATE on any one of its columns - the money column or any other.
        collect(connection, schema, violations, """
                SELECT c.relname || '.' || a.attname || ': carries money and ' || COALESCE(r.rolname, 'PUBLIC')
                       || ' holds column ' || acl.privilege_type || ' - not INSERT-only'
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                 CROSS JOIN LATERAL aclexplode(a.attacl) acl
                  LEFT JOIN pg_roles r ON r.oid = acl.grantee
                 WHERE n.nspname = ? AND c.relkind IN %s AND %s
                   AND acl.grantee <> c.relowner
                   AND acl.privilege_type = 'UPDATE'
                """.formatted(RELATIONS, CARRIES_MONEY));

        return violations;
    }

    /** A single-statement query whose one bind is the schema. */
    private static void collect(Connection connection, String schema, List<String> into, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    into.add(rows.getString(1));
                }
            }
        }
    }
}
