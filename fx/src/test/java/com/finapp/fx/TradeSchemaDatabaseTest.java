package com.finapp.fx;

import static com.finapp.fx.FxPolicyFixtures.application;
import static com.finapp.fx.FxPolicyFixtures.scalar;
import static com.finapp.fx.FxQuoteFixtures.A;
import static com.finapp.fx.FxQuoteFixtures.B;
import static com.finapp.fx.FxQuoteFixtures.activate;
import static com.finapp.fx.FxQuoteFixtures.claim;
import static com.finapp.fx.FxQuoteFixtures.eurUsdBothWays;
import static com.finapp.fx.FxQuoteFixtures.issuance;
import static com.finapp.fx.FxQuoteFixtures.issue;
import static com.finapp.fx.FxQuoteFixtures.providers;
import static com.finapp.fx.FxQuoteFixtures.reference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code fx V006}'s ranks for every writer (`P9-TSK-009`; INV-FX-01, INV-FX-04, INV-FX-07,
 * INV-FX-08): a trade books only an ACCEPTED quote's exact plan, once, at the customer rate, never
 * commits without its entry, and is frozen; the quote executes only beside its trade; the cover is
 * born DISPATCHED, moves only along its machine, and its permit is the database's, strictly
 * forward.
 */
@Tag("database")
@DisplayName("the trade and the cover: the database rank, for every writer (P9-TSK-009)")
class TradeSchemaDatabaseTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String RAISED = "P0001";

    private final FakeFxProvider a = new FakeFxProvider(A).rate("EUR", "USD", "1.085024");
    private final FakeFxProvider b = new FakeFxProvider(B).rate("EUR", "USD", "1.085024");

    @BeforeEach
    void freshReference() throws SQLException {
        reference("EUR", "USD", "1.0850000000");
    }

    @Test
    @DisplayName("a trade books exactly the ACCEPTED quote's plan, once, at the customer rate, and never"
            + " commits without its entry - each rank alone")
    void aTradeBooksThePlanOnce() throws SQLException {
        String quote = acceptedQuote();
        try (Connection app = application()) {
            refused(app, () -> insertTrade(app, quote, Map.of("executed_rate", "customer_rate + 0.000001")),
                    CHECK_VIOLATION, "trade_executed_at_the_customer_rate");
            refused(app, () -> insertTrade(app, quote, Map.of("customer_destination_minor", "customer_destination_minor + 1",
                            "position_destination_minor", "position_destination_minor + 1")),
                    RAISED, "no re-pricing");
            String trade = insertTrade(app, quote, Map.of());
            refused(app, () -> insertTrade(app, quote, Map.of()), UNIQUE_VIOLATION, "trade_quote_unique");
            assertThatThrownBy(app::commit)
                    .as("a trade committed without its entry")
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getMessage()).contains("with its entry"));
            app.rollback();
            assertThat(scalar(app, "SELECT count(*) FROM fx.trade WHERE id = '" + trade + "'")).isEqualTo("0");
        }
        String issued = issuedQuote();
        try (Connection app = application()) {
            refused(app, () -> insertTrade(app, issued, Map.of()), RAISED, "books an ACCEPTED quote");
        }
    }

    @Test
    @DisplayName("the booked trade is frozen: its entry attached once, BOOKED -> REVERSED its one edge,"
            + " never deleted; the quote executes only beside its trade")
    void theTradeIsFrozen() throws SQLException {
        String quote = acceptedQuote();
        String trade;
        try (Connection app = application()) {
            refusedSql(app, "UPDATE fx.quote SET status = 'EXECUTED' WHERE id = '" + quote + "'", RAISED);
            trade = insertTrade(app, quote, Map.of());
            execute(app, "UPDATE fx.trade SET journal_entry_id = '" + UUID.randomUUID() + "' WHERE id = '" + trade + "'");
            execute(app, "UPDATE fx.quote SET status = 'EXECUTED' WHERE id = '" + quote + "'");
            app.commit();
            refusedSql(app, "UPDATE fx.trade SET journal_entry_id = '" + UUID.randomUUID() + "' WHERE id = '" + trade + "'", RAISED);
            refusedSql(app, "DELETE FROM fx.trade WHERE id = '" + trade + "'", "42501");
            execute(app, "UPDATE fx.trade SET status = 'REVERSED' WHERE id = '" + trade + "'");
            refusedSql(app, "UPDATE fx.trade SET status = 'BOOKED' WHERE id = '" + trade + "'", RAISED);
            app.rollback();
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            refusedSql(migrator, "UPDATE fx.trade SET customer_destination_minor = customer_destination_minor + 1"
                    + " WHERE id = '" + trade + "'", RAISED);
            refusedSql(migrator, "DELETE FROM fx.trade WHERE id = '" + trade + "'", RAISED);
            assertThat(scalar(migrator, "SELECT booked_on = (booked_at AT TIME ZONE 'UTC')::date FROM fx.trade WHERE id = '"
                            + trade + "'"))
                    .as("the booking's date is the database's")
                    .isEqualTo("t");
        }
    }

    @Test
    @DisplayName("the cover is born DISPATCHED at attempt 1, once per (quote, kind), moves only along its"
            + " machine - EXECUTED waits for its execution fact - and its permit is the database's, forward")
    void theCoverMachine() throws SQLException {
        String quote = acceptedQuote();
        String cover = UUID.randomUUID().toString();
        try (Connection app = application()) {
            refusedSql(app, coverInsert(UUID.randomUUID().toString(), quote, "EXECUTED", 1), RAISED);
            execute(app, coverInsert(cover, quote, "DISPATCHED", 1));
            refusedSql(app, coverInsert(UUID.randomUUID().toString(), quote, "DISPATCHED", 1), UNIQUE_VIOLATION);
            execute(app, "INSERT INTO fx.cover_attempt (cover_id, attempt, client_reference, provider_quote_ref)"
                    + " VALUES ('" + cover + "', 1, 'T-" + UUID.randomUUID().toString().replace("-", "") + "', 'PQ-1')");
            refusedSql(app, "INSERT INTO fx.cover_attempt (cover_id, attempt, client_reference, provider_quote_ref,"
                    + " stated_counter_minor) VALUES ('" + cover + "', 2, 'not-a-reference', 'PQ-1', 108410)", CHECK_VIOLATION);
            app.commit();
            for (String to : List.of("EXECUTED", "VOIDED")) {
                refusedSql(app, "UPDATE fx.cover SET status = '" + to + "' WHERE id = '" + cover + "'", RAISED);
            }
            refusedSql(app, "UPDATE fx.cover SET attempts = 2 WHERE id = '" + cover + "'", RAISED);
            String before = scalar(app, "SELECT last_dispatched_at FROM fx.cover WHERE id = '" + cover + "'");
            execute(app, "UPDATE fx.cover SET last_dispatched_at = TIMESTAMPTZ '2000-01-01T00:00:00Z' WHERE id = '" + cover + "'");
            assertThat(scalar(app, "SELECT last_dispatched_at > '" + before + "'::timestamptz FROM fx.cover WHERE id = '"
                            + cover + "'"))
                    .as("a writer's instant is overwritten - the permit moves only forward, to the database's")
                    .isEqualTo("t");
            execute(app, "UPDATE fx.cover SET status = 'REJECTED' WHERE id = '" + cover + "'");
            refusedSql(app, "UPDATE fx.cover SET status = 'DISPATCHED' WHERE id = '" + cover + "'", RAISED);
            refusedSql(app, "UPDATE fx.cover SET status = 'DISPATCHED', attempts = 2 WHERE id = '" + cover + "'", RAISED);
            // P9-TSK-012: a requote's new reference is stored BEFORE the edge that licenses sending it - and (fx V010)
            // with the counter its fresh firm quote stated, which the execution is judged against.
            refusedSql(app, "INSERT INTO fx.cover_attempt (cover_id, attempt, client_reference, provider_quote_ref)"
                    + " VALUES ('" + cover + "', 2, 'T-" + UUID.randomUUID().toString().replace("-", "") + "', 'PQ-2')",
                    RAISED);
            refusedSql(app, "INSERT INTO fx.cover_attempt (cover_id, attempt, client_reference, provider_quote_ref,"
                    + " stated_counter_minor) VALUES ('" + cover + "', 2, 'T-" + UUID.randomUUID().toString().replace("-", "")
                    + "', 'PQ-2', 0)", CHECK_VIOLATION);
            execute(app, "INSERT INTO fx.cover_attempt (cover_id, attempt, client_reference, provider_quote_ref,"
                    + " stated_counter_minor) VALUES ('" + cover + "', 2, 'T-" + UUID.randomUUID().toString().replace("-", "")
                    + "', 'PQ-2', 108410)");
            execute(app, "UPDATE fx.cover SET status = 'DISPATCHED', attempts = 2 WHERE id = '" + cover + "'");
            execute(app, "UPDATE fx.cover SET status = 'UNKNOWN' WHERE id = '" + cover + "'");
            refusedSql(app, "UPDATE fx.cover SET status = 'VOIDED' WHERE id = '" + cover + "'", RAISED);
            refusedSql(app, "UPDATE fx.cover SET fixed_amount_minor = 1 WHERE id = '" + cover + "'", "42501");
            app.rollback();
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            refusedSql(migrator, "UPDATE fx.cover SET fixed_amount_minor = 1 WHERE id = '" + cover + "'", RAISED);
            refusedSql(migrator, "DELETE FROM fx.cover WHERE id = '" + cover + "'", RAISED);
            refusedSql(migrator, "UPDATE fx.cover_attempt SET provider_quote_ref = 'X' WHERE cover_id = '" + cover + "'", RAISED);
        }
    }

    @Test
    @DisplayName("a COVER is born only for a quote that wants it and only as its exposure (the Phase 9 to 10 transition,"
            + " fx V010): an ISSUED quote's refused; the provider, a currency, the fixed side, the fixed amount or its scale"
            + " other than the quote's plan refused; the quote's own exposure admitted")
    void theCoverIsBornFromItsQuote() throws SQLException {
        String issued = issuedQuote();
        String accepted = acceptedQuote();
        try (Connection app = application()) {
            refused(app, () -> {
                execute(app, coverInsert(UUID.randomUUID().toString(), issued, "DISPATCHED", 1));
                return null;
            }, RAISED, "wants it");
            for (Map.Entry<String, String> wrong : Map.of(
                    "provider_code", "'fx-sim-z'",
                    "destination_currency", "'GBP'",
                    "fixed_side", "'FIXED_DESTINATION'",
                    "position_source_minor", "position_source_minor + 1",
                    "source_scale", "3").entrySet()) {
                // Each alone, the rest the quote's own - refused by the birth trigger, which speaks before any CHECK.
                refused(app, () -> {
                    execute(app, coverInsert(UUID.randomUUID().toString(), accepted, "DISPATCHED", 1,
                            Map.of(wrong.getKey(), wrong.getValue())));
                    return null;
                }, RAISED, "exposure");
            }
            execute(app, coverInsert(UUID.randomUUID().toString(), accepted, "DISPATCHED", 1));
            app.rollback();
        }
    }

    @Test
    @DisplayName("the closed lists are their enums: trade statuses, cover statuses and kinds")
    void theListsAreTheEnums() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThat(listed(migrator, "trade_status_is_known"))
                    .containsExactlyInAnyOrderElementsOf(Arrays.stream(TradeStatus.values()).map(Enum::name).toList());
            assertThat(listed(migrator, "cover_status_is_known"))
                    .containsExactlyInAnyOrderElementsOf(Arrays.stream(CoverStatus.values()).map(Enum::name).toList());
            assertThat(listed(migrator, "cover_kind_is_known"))
                    .containsExactlyInAnyOrderElementsOf(Arrays.stream(CoverKind.values()).map(Enum::name).toList());
        }
    }

    // -----------------------------------------------------------------

    private String issuedQuote() throws SQLException {
        activate(eurUsdBothWays(Duration.ofSeconds(30), Duration.ofSeconds(10)), 5);
        return issue(issuance(providers(a, b), Clock.systemUTC()), UUID.randomUUID(), claim(), "EUR", "USD",
                FixedSide.FIXED_SOURCE, "1000.00").quote().id().value().toString();
    }

    private String acceptedQuote() throws SQLException {
        String quote = issuedQuote();
        try (Connection app = application()) {
            execute(app, "UPDATE fx.quote SET status = 'ACCEPTED' WHERE id = '" + quote + "'");
            app.commit();
        }
        return quote;
    }

    private static String coverInsert(String id, String quote, String status, int attempts) {
        return coverInsert(id, quote, status, attempts, Map.of());
    }

    /** A COVER copying quote {@code quote}'s exposure (a fixed-source quote), each override replacing one copied column. */
    private static String coverInsert(String id, String quote, String status, int attempts, Map<String, String> overrides) {
        List<String> copied = new ArrayList<>();
        for (String column : List.of("provider_code", "source_currency", "destination_currency", "fixed_side",
                "position_source_minor", "source_scale")) {
            copied.add(overrides.getOrDefault(column, column));
        }
        return "INSERT INTO fx.cover (id, quote_id, kind, status, provider_code, source_currency, destination_currency,"
                + " fixed_side, fixed_amount_minor, fixed_scale, attempts, last_dispatched_at, caused_by_event_id,"
                + " correlation_id)"
                + " SELECT '" + id + "', id, 'COVER', '" + status + "', " + String.join(", ", copied) + ", " + attempts
                + ", now(), issued_event_id, correlation_id FROM fx.quote WHERE id = '" + quote + "'";
    }

    /** A trade copying quote {@code quote}, {@code overrides} applied as SQL; returns its id. */
    private static String insertTrade(Connection app, String quote, Map<String, String> overrides) throws SQLException {
        String id = UUID.randomUUID().toString();
        Map<String, String> columns = new java.util.LinkedHashMap<>();
        for (String c : List.of("quote_id", "owner_party_id", "purpose", "source_currency", "destination_currency",
                "fixed_side", "pricing_policy_version_id", "provider_code", "customer_rate", "executed_rate", "source_scale",
                "destination_scale", "customer_source_minor", "customer_destination_minor", "position_source_minor",
                "position_destination_minor", "margin_minor", "spread_margin_minor", "markup_margin_minor",
                "residual_minor", "status", "correlation_id")) {
            columns.put(c, c);
        }
        columns.put("quote_id", "id");
        columns.put("executed_rate", "customer_rate");
        columns.put("status", "'BOOKED'");
        columns.putAll(overrides);
        execute(app, "INSERT INTO fx.trade (id, " + String.join(", ", columns.keySet()) + ") SELECT '" + id + "', "
                + String.join(", ", columns.values()) + " FROM fx.quote WHERE id = '" + quote + "'");
        return id;
    }

    @FunctionalInterface
    private interface Write {
        Object run() throws SQLException;
    }

    private static void refused(Connection app, Write write, String state, String named) throws SQLException {
        Savepoint before = app.setSavepoint();
        assertThatThrownBy(write::run)
                .isInstanceOfSatisfying(SQLException.class, e -> {
                    assertThat(e.getSQLState()).isEqualTo(state);
                    assertThat(e.getMessage()).contains(named);
                });
        app.rollback(before);
    }

    private static void refusedSql(Connection connection, String sql, String state) throws SQLException {
        Savepoint before = connection.getAutoCommit() ? null : connection.setSavepoint();
        assertThatThrownBy(() -> execute(connection, sql))
                .as(sql)
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(state));
        if (before != null) {
            connection.rollback(before);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> listed(Connection connection, String constraint) throws SQLException {
        String definition = scalar(connection,
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '" + constraint + "'");
        List<String> names = new ArrayList<>();
        Matcher name = Pattern.compile("'([A-Z_]+)'::text").matcher(definition);
        while (name.find()) {
            names.add(name.group(1));
        }
        return names;
    }
}
