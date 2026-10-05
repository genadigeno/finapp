package com.finapp.fx;

import static com.finapp.fx.FxPolicyFixtures.application;
import static com.finapp.fx.FxPolicyFixtures.scalar;
import static com.finapp.fx.FxQuoteFixtures.A;
import static com.finapp.fx.FxQuoteFixtures.B;
import static com.finapp.fx.FxQuoteFixtures.activate;
import static com.finapp.fx.FxQuoteFixtures.awaitDatabaseClock;
import static com.finapp.fx.FxQuoteFixtures.claim;
import static com.finapp.fx.FxQuoteFixtures.eurUsdBothWays;
import static com.finapp.fx.FxQuoteFixtures.issuance;
import static com.finapp.fx.FxQuoteFixtures.issue;
import static com.finapp.fx.FxQuoteFixtures.providers;
import static com.finapp.fx.FxQuoteFixtures.reference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * {@code fx V005}'s ranks for every writer (`P9-TSK-008`; INV-FX-04, INV-FX-05, INV-FX-07,
 * INV-HIST-04, INV-MON-03): a raw writer copying a valid quote with one value changed is refused
 * by the plan identity, the residual bound, the scale, the rate's scale or the pinned terms; the
 * machine's every edge, the freeze, the history's append-only rule and the enum-generated lists.
 */
@Tag("database")
@DisplayName("the quote's database rank, for every writer (P9-TSK-008)")
class QuoteSchemaDatabaseTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String RAISED = "P0001";

    private final FakeFxProvider a = new FakeFxProvider(A).rate("EUR", "USD", "1.085024");
    private final FakeFxProvider b = new FakeFxProvider(B).rate("EUR", "USD", "1.085024");

    @BeforeEach
    void freshReference() throws SQLException {
        reference("EUR", "USD", "1.0850000000");
    }

    @Test
    @DisplayName("a raw writer copying a valid quote is refused by each rank alone: plan identity, residual,"
            + " scale, rate scale, the pinned terms, a birth other than ISSUED - the unchanged copy is stored")
    void aBadPlanIsUnstorable() throws SQLException {
        activate(eurUsdBothWays(Duration.ofSeconds(30), Duration.ofSeconds(10)), 5);
        QuoteStore.QuoteRow quote = issue(issuance(providers(a, b), Clock.systemUTC()), UUID.randomUUID(), claim(),
                "EUR", "USD", FixedSide.FIXED_SOURCE, "1000.00").quote();
        try (Connection app = application()) {
            assertThat(copy(app, quote.id(), Map.of())).as("the control copy").isEqualTo(1);
            app.rollback();
            refused(app, quote.id(), Map.of("customer_destination_minor", "customer_destination_minor + 1"),
                    CHECK_VIOLATION, "quote_plan_identity");
            refused(app, quote.id(), Map.of("residual_minor", "3",
                            "position_destination_minor", "position_destination_minor - residual_minor + 3"),
                    CHECK_VIOLATION, "quote_residual_bounded");
            refused(app, quote.id(), Map.of("source_scale", "3"), CHECK_VIOLATION, "quote_source_scale_is_the_currency_s");
            refused(app, quote.id(), Map.of("customer_rate", "customer_rate + 0.0000001"),
                    CHECK_VIOLATION, "quote_customer_rate_at_its_scale");
            refused(app, quote.id(), Map.of("spread", "spread + 0.000001"), RAISED, "pinned version");
            refused(app, quote.id(), Map.of("status", "'EXPIRED'", "closed_at", "now()"), RAISED, "born ISSUED");
        }
    }

    @Test
    @DisplayName("every edge of the machine, by raw SQL: from ISSUED only ACCEPTED and CANCELLED while live,"
            + " from ACCEPTED only ABANDONED (EXECUTED waits for a trade), from a terminal state nothing;"
            + " the plan frozen, no deletion, closed_at stamped by the database")
    void everyEdge() throws SQLException {
        activate(eurUsdBothWays(Duration.ofSeconds(30), Duration.ofSeconds(10)), 5);
        QuoteIssuance issuance = issuance(providers(a, b), Clock.systemUTC());
        QuoteStore.QuoteRow quote = issue(issuance, UUID.randomUUID(), claim(), "EUR", "USD",
                FixedSide.FIXED_SOURCE, "100.00").quote();
        String id = quote.id().value().toString();
        try (Connection app = application()) {
            for (String to : List.of("ISSUED", "EXECUTED", "ABANDONED", "EXPIRED")) {
                refusedSql(app, "UPDATE fx.quote SET status = '" + to + "' WHERE id = '" + id + "'", RAISED);
            }
            refusedSql(app, "UPDATE fx.quote SET closed_at = now() WHERE id = '" + id + "'", RAISED);
            refusedSql(app, "DELETE FROM fx.quote WHERE id = '" + id + "'", "42501");
            execute(app, "UPDATE fx.quote SET status = 'CANCELLED' WHERE id = '" + id + "'");
            assertThat(scalar(app, "SELECT closed_at IS NOT NULL FROM fx.quote WHERE id = '" + id + "'")).isEqualTo("t");
            app.rollback();
            execute(app, "UPDATE fx.quote SET status = 'ACCEPTED' WHERE id = '" + id + "'");
            for (String to : List.of("ISSUED", "EXECUTED", "EXPIRED", "CANCELLED")) {
                refusedSql(app, "UPDATE fx.quote SET status = '" + to + "' WHERE id = '" + id + "'", RAISED);
            }
            execute(app, "UPDATE fx.quote SET status = 'ABANDONED' WHERE id = '" + id + "'");
            for (String to : List.of("ISSUED", "ACCEPTED", "EXECUTED", "EXPIRED", "CANCELLED")) {
                refusedSql(app, "UPDATE fx.quote SET status = '" + to + "' WHERE id = '" + id + "'", RAISED);
            }
            app.rollback();
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            refusedSql(migrator, "UPDATE fx.quote SET customer_destination_minor = customer_destination_minor + 1"
                    + " WHERE id = '" + id + "'", RAISED);
            // The freeze alone: a frozen column changed together with a LEGAL edge - the edge rule
            // admits ISSUED -> CANCELLED on a live quote, so only the freeze can refuse this.
            String live = issue(issuance, UUID.randomUUID(), claim(), "EUR", "USD", FixedSide.FIXED_SOURCE, "100.00")
                    .quote().id().value().toString();
            assertThatThrownBy(() -> execute(migrator, "UPDATE fx.quote SET status = 'CANCELLED',"
                            + " customer_destination_minor = customer_destination_minor + 1 WHERE id = '" + live + "'"))
                    .isInstanceOfSatisfying(SQLException.class, e -> {
                        assertThat(e.getSQLState()).isEqualTo(RAISED);
                        assertThat(e.getMessage()).contains("frozen plan");
                    });
            assertThat(scalar(migrator, "SELECT status FROM fx.quote WHERE id = '" + live + "'")).isEqualTo("ISSUED");
            refusedSql(migrator, "DELETE FROM fx.quote WHERE id = '" + id + "'", RAISED);
        }
    }

    @Test
    @DisplayName("on the database clock: a lapsed quote may only EXPIRE - never be accepted or cancelled")
    void aLapsedQuoteOnlyExpires() throws Exception {
        activate(eurUsdBothWays(FxQuoteFixtures.MINIMUM_WINDOW, Duration.ZERO), 5);
        QuoteStore.QuoteRow quote = issue(issuance(providers(a, b), Clock.systemUTC()), UUID.randomUUID(), claim(),
                "EUR", "USD", FixedSide.FIXED_SOURCE, "100.00").quote();
        FxQuoteFixtures.awaitDatabaseClockPast(quote.expiresAt());
        String id = quote.id().value().toString();
        try (Connection app = application()) {
            refusedSql(app, "UPDATE fx.quote SET status = 'ACCEPTED' WHERE id = '" + id + "'", RAISED);
            refusedSql(app, "UPDATE fx.quote SET status = 'CANCELLED' WHERE id = '" + id + "'", RAISED);
            execute(app, "UPDATE fx.quote SET status = 'EXPIRED' WHERE id = '" + id + "'");
            app.rollback();
        }
    }

    @Test
    @DisplayName("the history is append-only for every writer, and requested_at is the database's own")
    void theHistoryIsAppendOnly() throws SQLException {
        activate(eurUsdBothWays(Duration.ofSeconds(30), Duration.ofSeconds(10)), 5);
        QuoteStore.QuoteRow quote = issue(issuance(providers(a, b), Clock.systemUTC()), UUID.randomUUID(), claim(),
                "EUR", "USD", FixedSide.FIXED_SOURCE, "100.00").quote();
        String id = quote.id().value().toString();
        try (Connection migrator = DatabaseRoles.migrator()) {
            refusedSql(migrator, "UPDATE fx.quote_event SET actor_id = 'x' WHERE quote_id = '" + id + "'", RAISED);
            refusedSql(migrator, "UPDATE fx.quote_sourcing_step SET detail = 'X' WHERE quote_request_id ="
                    + " (SELECT quote_request_id FROM fx.quote WHERE id = '" + id + "')", RAISED);
            refusedSql(migrator, "DELETE FROM fx.quote_request WHERE id ="
                    + " (SELECT quote_request_id FROM fx.quote WHERE id = '" + id + "')", RAISED);
        }
        try (Connection app = application()) {
            UUID request = UUID.randomUUID();
            execute(app, "INSERT INTO fx.quote_request (id, reference, claim_key, owner_party_id, purpose,"
                    + " source_currency, destination_currency, fixed_side, fixed_amount_minor, fixed_scale,"
                    + " pricing_policy_version_id, requested_at, correlation_id) SELECT '" + request + "', 'QR-"
                    + request.toString().replace("-", "") + "', 'k" + request + "', owner_party_id, purpose,"
                    + " source_currency, destination_currency, fixed_side, fixed_amount_minor, fixed_scale,"
                    + " pricing_policy_version_id, TIMESTAMPTZ '2000-01-01T00:00:00Z', correlation_id FROM"
                    + " fx.quote_request WHERE id = (SELECT quote_request_id FROM fx.quote WHERE id = '" + id + "')");
            assertThat(scalar(app, "SELECT requested_at > now() - INTERVAL '1 minute' FROM fx.quote_request WHERE id = '"
                            + request + "'"))
                    .as("a writer's requested_at is overwritten by the database's")
                    .isEqualTo("t");
            app.rollback();
        }
    }

    @Test
    @DisplayName("the cap trigger takes advisory namespace 5 - registered in DISTRIBUTED_EXECUTION.md - keyed"
            + " by the owner, in the blocking form")
    void theCapLocksNamespaceFive() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThat(scalar(migrator, "SELECT pg_get_functiondef('fx.quote_is_born_issued'::regproc)"))
                    .containsIgnoringCase("pg_advisory_xact_lock(5, hashtext(NEW.owner_party_id::text))");
        }
    }

    @Test
    @DisplayName("the closed lists are their enums: the rounding names, the statuses, the step outcomes")
    void theListsAreTheEnums() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator()) {
            for (String constraint : List.of("quote_rate_rounding_is_named", "quote_amount_rounding_is_named",
                    "quote_margin_rounding_is_named")) {
                assertThat(listed(migrator, constraint)).as(constraint).containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(RoundingPolicy.values()).map(Enum::name).toList());
            }
            assertThat(listed(migrator, "quote_status_is_known")).containsExactlyInAnyOrderElementsOf(
                    Arrays.stream(QuoteStatus.values()).map(Enum::name).toList());
            assertThat(listed(migrator, "quote_sourcing_step_outcome_is_known")).containsExactlyInAnyOrderElementsOf(
                    Arrays.stream(QuoteStore.StepOutcome.values()).map(Enum::name).toList());
        }
    }

    // -----------------------------------------------------------------

    /** Copies quote {@code id} with a fresh request and owner, applying {@code overrides} as SQL. */
    private static int copy(Connection app, FxQuoteId id, Map<String, String> overrides) throws SQLException {
        UUID request = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        execute(app, "INSERT INTO fx.quote_request (id, reference, claim_key, owner_party_id, purpose, source_currency,"
                + " destination_currency, fixed_side, fixed_amount_minor, fixed_scale, pricing_policy_version_id,"
                + " correlation_id) SELECT '" + request + "', 'QR-" + request.toString().replace("-", "") + "', 'k"
                + request + "', '" + owner + "', purpose, source_currency, destination_currency, fixed_side,"
                + " fixed_amount_minor, fixed_scale, pricing_policy_version_id, correlation_id FROM fx.quote_request"
                + " WHERE id = (SELECT quote_request_id FROM fx.quote WHERE id = '" + id.value() + "')");
        List<String> columns = new ArrayList<>();
        try (PreparedStatement select = app.prepareStatement(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'fx'"
                                + " AND table_name = 'quote' ORDER BY ordinal_position");
                ResultSet row = select.executeQuery()) {
            while (row.next()) {
                columns.add(row.getString(1));
            }
        }
        Map<String, String> fixed = new java.util.HashMap<>(Map.of(
                "id", "'" + quote + "'",
                "quote_request_id", "'" + request + "'",
                "owner_party_id", "'" + owner + "'",
                "requested_at", "(SELECT requested_at FROM fx.quote_request WHERE id = '" + request + "')",
                "issued_event_id", "'" + UUID.randomUUID() + "'"));
        fixed.putAll(overrides);
        String expressions = String.join(", ", columns.stream().map(c -> fixed.getOrDefault(c, c)).toList());
        try (Statement insert = app.createStatement()) {
            return insert.executeUpdate("INSERT INTO fx.quote (" + String.join(", ", columns) + ") SELECT "
                    + expressions + " FROM fx.quote WHERE id = '" + id.value() + "'");
        }
    }

    private static void refused(Connection app, FxQuoteId id, Map<String, String> overrides, String state, String named)
            throws SQLException {
        Savepoint before = app.setSavepoint();
        assertThatThrownBy(() -> copy(app, id, overrides))
                .as(overrides.toString())
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
