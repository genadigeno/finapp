package com.finapp.platform.testing.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hole {@code P15-TSK-040} closed, opened again <em>in a test database only</em> while a clock-stepping fixture needs it
 * (lending's {@code ClockStep}, for lending's functions alone; {@code THREAT_MODEL.md} T13).
 *
 * <p>A fixture moves the database's clock by shadowing the clock functions in a schema a session puts before {@code pg_catalog}
 * on its {@code search_path}. Since the closing migrations every function of these schemas names {@code pg_catalog} itself, so a
 * shadow no longer reaches a trigger. {@link #reopen} replaces each of them, as the bootstrap superuser, with itself with the
 * qualifier removed, so the production logic runs on the stepped clock, as those suites were written to have it; {@link #restore}
 * puts every captured definition back, byte for byte. A session that has not opted in resolves {@code pg_catalog} either way.
 *
 * <p>The captured definitions are kept in a schema of the bootstrap superuser's in that database, never in a field, so they
 * outlive the suite that captured them and a later {@link #restore} can still find them. Two sets of functions are left alone:
 * any with a {@code search_path} of its own, which no shadow reaches either way, and the one function qualified before the
 * closing migrations, merchant {@code V014}'s pin trigger. The database tier runs one suite at a time, so what one suite opens is
 * closed again before the next begins.
 */
public final class ReopenedClock {

    private static final String CAPTURE = "reopenedclock";

    private static final List<String> SCHEMAS = List.of(
            "accounting", "bnpl", "checkout", "credit", "crossborder", "fx", "ledger", "merchant", "payments", "risk", "settlement");

    /** Already qualified by merchant {@code V014}: the suites written since have only ever run with it closed, and opening it would change what they read. */
    private static final Set<String> STAYS_CLOSED = Set.of("merchant.payment_fee_pin_names_a_version_in_force");

    private static final Pattern QUALIFIED_CLOCK =
            Pattern.compile("(?i)pg_catalog\\.(now|statement_timestamp|clock_timestamp|transaction_timestamp|timeofday)(\\s*\\()");

    private static final String DEFINITIONS =
            "SELECT n.nspname || '.' || p.proname, n.nspname || '.' || p.proname || '(' || pg_catalog.pg_get_function_identity_arguments(p.oid) || ')',"
                    + " pg_catalog.pg_get_functiondef(p.oid) FROM pg_catalog.pg_proc p"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = ANY (ARRAY[" + quoted() + "]) AND p.prokind = 'f' AND p.proconfig IS NULL";

    private ReopenedClock() {}

    /** Opens every qualifying function to the shadow. Idempotent: what is already open reads no qualified clock, and is captured once. */
    public static void reopen() {
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + CAPTURE + ";"
                    + " CREATE TABLE IF NOT EXISTS " + CAPTURE + ".definition (signature text PRIMARY KEY, definition text NOT NULL)");
            Map<String, String> originals = new LinkedHashMap<>();
            List<String> replacements = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(DEFINITIONS)) {
                while (rows.next()) {
                    String definition = rows.getString(3);
                    Matcher qualified = QUALIFIED_CLOCK.matcher(definition);
                    if (!STAYS_CLOSED.contains(rows.getString(1)) && qualified.find()) {
                        originals.put(rows.getString(2), definition);
                        replacements.add(qualified.replaceAll("$1$2"));
                    }
                }
            }
            try (PreparedStatement capture = owner.prepareStatement(
                    "INSERT INTO " + CAPTURE + ".definition (signature, definition) VALUES (?, ?) ON CONFLICT (signature) DO NOTHING")) {
                for (Map.Entry<String, String> original : originals.entrySet()) {
                    capture.setString(1, original.getKey());
                    capture.setString(2, original.getValue());
                    capture.executeUpdate();
                }
            }
            for (String replacement : replacements) {
                statement.execute(replacement);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Puts every function back exactly as the migrations left it, and forgets what it held. */
    public static void restore() {
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement()) {
            List<String> captured = new ArrayList<>();
            try (ResultSet present = statement.executeQuery("SELECT pg_catalog.to_regclass('" + CAPTURE + ".definition') IS NOT NULL")) {
                present.next();
                if (!present.getBoolean(1)) {
                    return;
                }
            }
            try (ResultSet rows = statement.executeQuery("SELECT definition FROM " + CAPTURE + ".definition")) {
                while (rows.next()) {
                    captured.add(rows.getString(1));
                }
            }
            for (String definition : captured) {
                statement.execute(definition);
            }
            statement.execute("DROP SCHEMA " + CAPTURE + " CASCADE");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String quoted() {
        return String.join(", ", SCHEMAS.stream().map(schema -> "'" + schema + "'").toList());
    }
}
