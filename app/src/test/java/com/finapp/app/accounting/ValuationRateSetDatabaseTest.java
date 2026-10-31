package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.FEBRUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.on;
import static com.finapp.app.accounting.AccountingPeriodFixture.periods;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.JdbcValuationRateSetStore;
import com.finapp.accounting.ValuationRateSet;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Valuation rate sets against a live PostgreSQL (`P14-TSK-014`; {@code accounting V005}, ADR-0116 decision 2;
 * {@code INV-ACC-11}, {@code INV-FX-05}, {@code INV-AUD-04}) - the backlog entry's named cases and the design's: every
 * stored rate equal to its {@code fx.rate_snapshot} row (provenance kept, the rate's text included), the observation
 * after the period's end never chosen; a stale and a missing pair refused with nothing written; the set and its rates
 * immutable for every writer; the capturer refused as approver, by the domain and by the {@code CHECK}; and - last, since a
 * period admits one approved set for good - two captures and <strong>ten concurrent approvals</strong> leaving exactly one
 * approved set, a raw second approval refused by the partial unique.
 *
 * <p>January 1999 is the one period the fixtures can lock (the first; February would need January {@code CLOSED}), so
 * each case locks it and {@link AccountingPeriodFixture#restore} returns it to {@code OPEN}; each case's reference is a
 * source of its own ({@code valuation-test-<uuid>}), so fx's newer-than-latest rule never meets another suite's rows.
 * <strong>Written and owed</strong> (owner decision C5: the database tiers are skipped per task).
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("valuation rate sets against the database (P14-TSK-014)")
class ValuationRateSetDatabaseTest {

    private static final int APPROVERS = 10;
    private static final Instant MONTH_END = Instant.parse("1999-01-31T16:00:00Z");
    private static final Instant AFTER_THE_END = Instant.parse("1999-02-01T10:00:00Z");
    private static final Map<String, String> RATES = Map.of(
            "GBP", "0.7012000000", "USD", "1.1561000000", "JPY", "131.2500000000", "BHD", "0.4358123456");

    private String source;

    @BeforeEach
    void lockJanuary() {
        AccountingPeriodFixture.restore();
        source = "valuation-test-" + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
        inScope(person(), uow -> periods(on("1999-02-15")).lock(uow, id(JANUARY), SecurityContext.require(),
                "valuation test", correlation()));
    }

    @AfterEach
    void leaveOpen() {
        AccountingPeriodFixture.restore();
    }

    @Test
    @Order(1)
    @DisplayName("provenance is kept: every stored rate equals its fx.rate_snapshot row - id, source, both instants, the"
            + " rate's value and text - EUR-based, the observation before the period's end chosen over the one after it")
    void provenanceIsKept() throws SQLException {
        plantAll(MONTH_END);
        plantAll(AFTER_THE_END);

        ValuationRateSets.Valued captured = capture(person());

        assertThat(captured.status()).isEqualTo(ValuationRateSetStatus.PROPOSED);
        assertThat(captured.asOf()).isEqualTo(Instant.parse("1999-02-01T00:00:00Z"));
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT r.base_currency, r.quote_currency,"
                        + " r.rate = s.rate AS same_rate, r.rate::text = s.rate::text AS same_text,"
                        + " r.source = s.source AS same_source, r.observed_at = s.observed_at AS same_observed,"
                        + " r.received_at = s.received_at AS same_received, s.base_currency AS snapshot_base,"
                        + " s.observed_at < v.as_of AS before_the_end"
                        + " FROM accounting.valuation_rate r JOIN fx.rate_snapshot s ON s.id = r.snapshot_id"
                        + " JOIN accounting.valuation_rate_set v ON v.id = r.set_id"
                        + " WHERE r.set_id = ? ORDER BY r.position")) {
            read.setObject(1, captured.id().value());
            List<String> quotes = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    quotes.add(row.getString("quote_currency"));
                    assertThat(row.getString("base_currency")).isEqualTo("EUR");
                    assertThat(row.getString("snapshot_base")).isEqualTo("EUR");
                    for (String same : List.of("same_rate", "same_text", "same_source", "same_observed",
                            "same_received", "before_the_end")) {
                        assertThat(row.getBoolean(same)).as("%s for %s", same, quotes.get(quotes.size() - 1)).isTrue();
                    }
                }
            }
            assertThat(quotes).containsExactly("GBP", "USD", "JPY", "BHD");
        }
        assertThat(captured.rates()).allMatch(rate -> rate.observedAt().equals(MONTH_END));
        assertThat(captured.rates()).extracting(rate -> rate.rate().toPlainString())
                .containsExactly(RATES.get("GBP"), RATES.get("USD"), RATES.get("JPY"), RATES.get("BHD"));
        assertThat(count("SELECT count(*) FROM accounting.valuation_rate_set WHERE id = '" + captured.id().value()
                + "' AND rate_count = 4 AND as_of = timestamptz '1999-02-01 00:00:00+00' AND status = 'PROPOSED'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.ValuationSetCaptured'"
                + " AND target_id = '" + captured.id().value() + "'")).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("a stale pair is refused: one EUR pair last observed more than the bound before the end refuses the"
            + " whole capture, and nothing is written")
    void aStalePairIsRefused() throws SQLException {
        plant("GBP", MONTH_END);
        plant("USD", MONTH_END);
        // The bound's oldest admitted instant is 1999-01-28T00:00Z (96 hours before the end): one second older.
        plant("JPY", Instant.parse("1999-01-27T23:59:59Z"));
        plant("BHD", MONTH_END);
        long before = setCount();

        assertThatThrownBy(() -> capture(person()))
                .isInstanceOfSatisfying(ValuationRateSet.ValuationRateStale.class,
                        stale -> assertThat(stale.pairs()).containsExactly("EUR/JPY"));
        assertThat(setCount()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM accounting.valuation_rate r WHERE NOT EXISTS (SELECT 1 FROM"
                + " accounting.valuation_rate_set s WHERE s.id = r.set_id)")).isZero();
    }

    @Test
    @Order(3)
    @DisplayName("a missing pair is refused: a valued currency the reference never observed refuses the whole capture")
    void aMissingPairIsRefused() throws SQLException {
        plant("GBP", MONTH_END);
        plant("USD", MONTH_END);
        plant("JPY", MONTH_END);
        long before = setCount();

        assertThatThrownBy(() -> capture(person()))
                .isInstanceOfSatisfying(ValuationRateSet.ValuationRateStale.class,
                        stale -> assertThat(stale.pairs()).containsExactly("EUR/BHD"));
        assertThat(setCount()).isEqualTo(before);
    }

    @Test
    @Order(4)
    @DisplayName("a set is immutable for every writer: no rate changed, removed or added late, none outside the window,"
            + " the capture frozen, nothing deleted or truncated, nothing born approved or for an OPEN period")
    void aSetIsImmutable() throws SQLException {
        plantAll(MONTH_END);
        ValuationRateSets.Valued captured = capture(person());
        UUID set = captured.id().value();
        AccountingPeriodId january = id(JANUARY);

        try (Connection app = DatabaseRoles.application()) {
            DatabaseRoles.assertCannotBypassPrivileges(app);
            // The application role holds no UPDATE or DELETE on a rate, and only the decision columns of a set.
            refused(app, "UPDATE accounting.valuation_rate SET rate = rate + 1 WHERE set_id = '" + set + "'", "42501");
            refused(app, "DELETE FROM accounting.valuation_rate WHERE set_id = '" + set + "'", "42501");
            refused(app, "DELETE FROM accounting.valuation_rate_set WHERE id = '" + set + "'", "42501");
            refused(app, "UPDATE accounting.valuation_rate_set SET max_age_hours = 1 WHERE id = '" + set + "'", "42501");
            refused(app, "TRUNCATE accounting.valuation_rate", "42501");
            refused(app, "INSERT INTO accounting.valuation_rate (set_id, position, base_currency, quote_currency, rate,"
                    + " source, snapshot_id, observed_at, received_at) VALUES ('" + set + "', 5, 'EUR', 'CHF', 0.95,"
                    + " 'late-source', '" + UUID.randomUUID() + "', timestamptz '1999-01-31 12:00:00+00',"
                    + " timestamptz '1999-01-31 12:00:01+00')", "P0001");
            refused(app, rawSet(UUID.randomUUID(), january.value(), "APPROVED"), "P0001");
            refused(app, rawSet(UUID.randomUUID(), id(FEBRUARY).value(), "PROPOSED"), "P0001");
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            // The owner holds every privilege: the triggers alone refuse - each in a transaction rolled back after it.
            migrator.setAutoCommit(false);
            for (String sql : List.of(
                    "UPDATE accounting.valuation_rate_set SET max_age_hours = 1 WHERE id = '" + set + "'",
                    "UPDATE accounting.valuation_rate_set SET status = 'PROPOSED' WHERE id = '" + set + "'",
                    "UPDATE accounting.valuation_rate SET rate = rate + 1 WHERE set_id = '" + set + "'",
                    "DELETE FROM accounting.valuation_rate WHERE set_id = '" + set + "'",
                    "DELETE FROM accounting.valuation_rate_set WHERE id = '" + set + "'",
                    "TRUNCATE accounting.valuation_rate")) {
                refused(migrator, sql, "P0001");
                migrator.rollback();
            }
            // A rate observed at the period's end itself is outside the window, even inside a fresh set's transaction.
            UUID fresh = UUID.randomUUID();
            try (Statement statement = migrator.createStatement()) {
                statement.execute(rawSet(fresh, january.value(), "PROPOSED"));
            }
            refused(migrator, "INSERT INTO accounting.valuation_rate (set_id, position, base_currency, quote_currency,"
                    + " rate, source, snapshot_id, observed_at, received_at) VALUES ('" + fresh + "', 1, 'EUR', 'GBP',"
                    + " 0.70, 'window-source', '" + UUID.randomUUID() + "', timestamptz '1999-02-01 00:00:00+00',"
                    + " timestamptz '1999-02-01 00:00:01+00')", "P0001");
            migrator.rollback();
        }
        assertThat(count("SELECT count(*) FROM accounting.valuation_rate WHERE set_id = '" + set + "'")).isEqualTo(4);
    }

    @Test
    @Order(5)
    @DisplayName("the capturer cannot approve: refused by the domain, and by the four-eyes CHECK for a raw writer")
    void theProposerCannotApprove() throws SQLException {
        plantAll(MONTH_END);
        Actor accountant = person();
        ValuationRateSets.Valued captured = capture(accountant);

        assertThatThrownBy(() -> inScope(accountant, uow -> sets().approve(uow, captured.id(), accountant, "my own",
                correlation()))).isInstanceOf(ValuationRateSets.SelfApprovalRefused.class);
        try (Connection app = DatabaseRoles.application()) {
            refused(app, "UPDATE accounting.valuation_rate_set SET status = 'APPROVED', decided_by = captured_by,"
                    + " decided_by_type = 'CUSTOMER', decided_at = clock_timestamp(), decision_reason = 'raw'"
                    + " WHERE id = '" + captured.id().value() + "'", "23514");
        }
        assertThat(count("SELECT count(*) FROM accounting.valuation_rate_set WHERE id = '" + captured.id().value()
                + "' AND status = 'PROPOSED'")).isEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("one approved per period: two captures, ten approvers on ten connections - exactly one approved set, nine"
            + " refused stale, one audit record; a raw second approval refused by the partial unique; no further capture")
    void oneApprovedPerPeriod() throws Exception {
        plantAll(MONTH_END);
        AccountingPeriodId january = id(JANUARY);
        List<ValuationRateSets.Valued> captured = List.of(capture(person()), capture(person()));
        ExecutorService pool = Executors.newFixedThreadPool(APPROVERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Object> outcomes = new ArrayList<>();
        try {
            List<Future<Object>> racing = new ArrayList<>();
            for (int i = 0; i < APPROVERS; i++) {
                ValuationRateSets.Valued target = captured.get(i % 2);
                racing.add(pool.submit(() -> {
                    start.await();
                    Actor controller = person();
                    try {
                        return inScope(controller, uow -> sets().approve(uow, target.id(), controller, "racing",
                                correlation()));
                    } catch (ValuationRateSets.ValuationSetStale stale) {
                        return stale;
                    }
                }));
            }
            start.countDown();
            for (Future<Object> outcome : racing) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(outcomes).filteredOn(ValuationRateSets.Valued.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ValuationRateSets.ValuationSetStale.class::isInstance).hasSize(APPROVERS - 1);
        assertThat(count("SELECT count(*) FROM accounting.valuation_rate_set WHERE period_id = '" + january.value()
                + "' AND status = 'APPROVED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.ValuationSetApproved'"
                + " AND target_id IN ('" + captured.get(0).id().value() + "', '" + captured.get(1).id().value() + "')"))
                .isEqualTo(1);

        UUID other = count("SELECT count(*) FROM accounting.valuation_rate_set WHERE id = '"
                + captured.get(0).id().value() + "' AND status = 'APPROVED'") == 1
                ? captured.get(1).id().value() : captured.get(0).id().value();
        try (Connection app = DatabaseRoles.application()) {
            refused(app, "UPDATE accounting.valuation_rate_set SET status = 'APPROVED', decided_by = 'raw-approver',"
                    + " decided_by_type = 'CUSTOMER', decided_at = clock_timestamp(), decision_reason = 'raw'"
                    + " WHERE id = '" + other + "'", "23505");
        }
        assertThatThrownBy(() -> capture(person())).isInstanceOf(ValuationRateSets.ValuationSetStale.class);
    }

    // -----------------------------------------------------------------

    private ValuationRateSets sets() {
        return new ValuationRateSets(new JdbcValuationRateSetStore(), AccountingPeriodFixture.STORE,
                new FxReferenceValuationRates(source, SupportedCurrencies.ALL), ValuationStaleness.standard(),
                new JdbcAuditWriter(), IDS, NOW);
    }

    private ValuationRateSets.Valued capture(Actor accountant) {
        return inScope(accountant, uow -> sets().capture(uow, id(JANUARY), accountant, null, correlation()));
    }

    private void plantAll(Instant observedAt) throws SQLException {
        for (String currency : List.of("GBP", "USD", "JPY", "BHD")) {
            plant(currency, observedAt);
        }
    }

    /** One reference observation of EUR/{@code currency} under this case's source, as fx's own writer stores it. */
    private void plant(String currency, Instant observedAt) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement insert = app.prepareStatement("INSERT INTO fx.rate_snapshot (id, source,"
                        + " base_currency, quote_currency, rate, observed_at) VALUES (?, ?, 'EUR', ?, ?, ?)")) {
            insert.setObject(1, IDS.next());
            insert.setString(2, source);
            insert.setString(3, currency);
            insert.setBigDecimal(4, new java.math.BigDecimal(RATES.get(currency)));
            insert.setTimestamp(5, Timestamp.from(observedAt));
            assertThat(insert.executeUpdate()).as("EUR/%s stored", currency).isEqualTo(1);
        }
    }

    /** A raw set row for {@code period} - January's as-of, one rate - for the refusals. */
    private static String rawSet(UUID id, UUID period, String status) {
        return "INSERT INTO accounting.valuation_rate_set (id, period_id, functional_currency, as_of, max_age_hours,"
                + " rate_count, status, captured_by, captured_by_type, captured_at, correlation_id) VALUES ('" + id
                + "', '" + period + "', 'EUR', timestamptz '1999-02-01 00:00:00+00', 96, 1, '" + status + "',"
                + " 'raw-capturer', 'CUSTOMER', clock_timestamp(), 'raw')";
    }

    private static long setCount() throws SQLException {
        return count("SELECT count(*) FROM accounting.valuation_rate_set");
    }

    static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }
}
