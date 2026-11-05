package com.finapp.app.accounting;

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

import com.finapp.accounting.JdbcValuationRateSetStore;
import com.finapp.accounting.ValuationRate;
import com.finapp.accounting.ValuationRateSet;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * A reference gap's way on against a live PostgreSQL (`P14-TSK-026`; {@code accounting V012}, ADR-0119 decision 2;
 * {@code INV-ACC-11}, {@code INV-AUD-04}) - owed to the Phase 14 database pass (C5): V012's ranks for a raw writer (a flagged
 * rate only within its set's declared count, only older than the bound, a set declaring a gap only with its reason and only
 * committing with exactly that many flagged, the count frozen); the exception end to end over {@code fx.rate_snapshot} - the
 * real observation stored with its provenance and flagged, refused at the ordinary door and to its capturer; a late
 * observation refusing the approval; and, last, the one approval.
 *
 * <p><strong>Why a database of its own</strong> ({@code own-container}): it approves a set for January 1999, and a period
 * holds one approved set for good - {@code ValuationRateSetDatabaseTest}'s own January must stay unapproved until its
 * {@code oneApprovedPerPeriod}. <strong>Order</strong>: every case locks January afresh with a reference source of its own
 * ({@code @BeforeEach}) and returns it {@code OPEN} ({@code @AfterEach}); (1) and (2) approve nothing, (2) leaves a
 * {@code PROPOSED} set that (100) never names; only (100) approves.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("a reference gap's way on against the database (P14-TSK-026)")
class ValuationReferenceGapDatabaseTest {

    private static final Instant MONTH_END = Instant.parse("1999-01-31T16:00:00Z");
    /** Four hundred hours before January's end (1999-02-01T00:00Z) - beyond the 96-hour bound. */
    private static final Instant SILENT_SINCE = Instant.parse("1999-01-15T08:00:00Z");
    private static final Map<String, String> RATES = Map.of(
            "GBP", "0.7012000000", "USD", "1.1561000000", "JPY", "131.2500000000", "BHD", "0.4358123456");

    private String source;

    @BeforeEach
    void lockJanuary() {
        AccountingPeriodFixture.restore();
        source = "gap-test-" + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
        inScope(person(), uow -> periods(on("1999-02-15")).lock(uow, id(JANUARY), SecurityContext.require(),
                "reference gap test", correlation()));
    }

    @AfterEach
    void leaveOpen() {
        AccountingPeriodFixture.restore();
    }

    @Test
    @Order(1)
    @DisplayName("V012 for a raw writer: a flagged rate refused without a declared gap, inside the bound, or past its count; a"
            + " gap declared without a reason refused; a set declaring a gap commits only with it flagged; the count frozen")
    void theSchemaHoldsTheGapForEveryWriter() throws SQLException {
        UUID period = id(JANUARY).value();
        String old = "timestamptz '1999-01-15 08:00:00+00'";
        String recent = "timestamptz '1999-01-31 16:00:00+00'";
        // No declared gap: a flagged rate is refused.
        refusedInTransaction(List.of(rawSet(UUID.randomUUID(), period, 0, "NULL"), "%RATE%" + old + ", true"), "P0001");
        // A gap declared with no reason.
        refusedInTransaction(List.of(rawSet(UUID.randomUUID(), period, 1, "NULL")), "23514");
        // A flagged rate inside the bound: a gap that is none.
        refusedInTransaction(List.of(rawSet(UUID.randomUUID(), period, 1, "'silent'"), "%RATE%" + recent + ", true"), "P0001");
        // Declared and flagged: born; then committed without its flagged rate - the deferred check.
        refusedInTransaction(List.of(rawSet(UUID.randomUUID(), period, 1, "'silent'"), "%RATE%" + recent + ", false"),
                "P0001");
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            assertThatThrownBy(() -> statement.execute("UPDATE accounting.valuation_rate_set SET reference_gaps = 0"))
                    .isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    @Order(2)
    @DisplayName("a late observation after the capture refuses the gap's approval, and the set stays PROPOSED")
    void aLateObservationRefusesTheApproval() throws SQLException {
        plantAllBut("JPY", MONTH_END);
        UUID jpy = plant("JPY", SILENT_SINCE);
        Actor accountant = person();
        ValuationRateSets.Valued captured = inScope(accountant, uow -> sets().captureAcrossGap(uow, id(JANUARY), accountant,
                "the reference was silent", List.of(new ValuationRateSet.ReferenceGap(CurrencyCode.of("JPY"), jpy)),
                correlation()));
        plant("JPY", Instant.parse("1999-01-30T12:00:00Z"));
        Actor controller = person();
        assertThatThrownBy(() -> inScope(controller, uow -> sets().approveAcrossGap(uow, captured.id(), controller, "fine",
                correlation()))).isInstanceOf(ValuationRateSet.ReferenceGapRefused.class);
        assertThat(ValuationRateSetDatabaseTest.count("SELECT count(*) FROM accounting.valuation_rate_set WHERE id = '"
                + captured.id().value() + "' AND status = 'PROPOSED'")).isEqualTo(1);
    }

    @Test
    @Order(100)
    @DisplayName("the exception end to end: the refusal names the snapshot; the real observation stored flagged with its"
            + " provenance; refused at the ordinary door and to its capturer; approved by another at its own door")
    void aGapIsCapturedAndApprovedAtItsOwnDoor() throws SQLException {
        plantAllBut("JPY", MONTH_END);
        UUID jpy = plant("JPY", SILENT_SINCE);
        Actor accountant = person();
        assertThatThrownBy(() -> inScope(accountant, uow -> sets().capture(uow, id(JANUARY), accountant, null,
                correlation()))).isInstanceOf(ValuationRateSet.ValuationRateStale.class).hasMessageContaining(jpy.toString());

        ValuationRateSets.Valued captured = inScope(accountant, uow -> sets().captureAcrossGap(uow, id(JANUARY), accountant,
                "the reference published no yen for sixteen days",
                List.of(new ValuationRateSet.ReferenceGap(CurrencyCode.of("JPY"), jpy)), correlation()));
        assertThat(captured.rates()).filteredOn(ValuationRate::beyondBound).extracting(ValuationRate::snapshotId)
                .containsExactly(jpy);
        assertThat(ValuationRateSetDatabaseTest.count("SELECT count(*) FROM accounting.valuation_rate r JOIN fx.rate_snapshot s"
                + " ON s.id = r.snapshot_id JOIN accounting.valuation_rate_set v ON v.id = r.set_id WHERE r.set_id = '"
                + captured.id().value() + "' AND r.beyond_bound AND r.rate::text = s.rate::text AND r.observed_at = s.observed_at"
                + " AND r.source = s.source AND v.reference_gaps = 1 AND v.capture_reason IS NOT NULL")).isEqualTo(1);

        Actor controller = person();
        assertThatThrownBy(() -> inScope(controller, uow -> sets().approve(uow, captured.id(), controller, "ordinary",
                correlation()))).isInstanceOf(ValuationRateSets.ValuationDoorMismatch.class);
        assertThatThrownBy(() -> inScope(accountant, uow -> sets().approveAcrossGap(uow, captured.id(), accountant, "mine",
                correlation()))).isInstanceOf(ValuationRateSets.SelfApprovalRefused.class);
        ValuationRateSets.Valued approved = inScope(controller, uow -> sets().approveAcrossGap(uow, captured.id(), controller,
                "a real observation, its age disclosed", correlation()));
        assertThat(approved.status()).isEqualTo(ValuationRateSetStatus.APPROVED);
        assertThat(ValuationRateSetDatabaseTest.count("SELECT count(*) FROM platform.audit_record WHERE target_id = '"
                + captured.id().value() + "' AND operation IN ('accounting.ValuationSetCapturedAcrossGap',"
                + " 'accounting.ValuationSetApprovedAcrossGap')")).isEqualTo(2);
    }

    // -----------------------------------------------------------------

    private ValuationRateSets sets() {
        return new ValuationRateSets(new JdbcValuationRateSetStore(), AccountingPeriodFixture.STORE,
                new FxReferenceValuationRates(source, SupportedCurrencies.ALL), ValuationStaleness.standard(),
                new JdbcAuditWriter(), IDS, NOW);
    }

    private void plantAllBut(String skipped, Instant observedAt) throws SQLException {
        for (String currency : List.of("GBP", "USD", "JPY", "BHD")) {
            if (!currency.equals(skipped)) {
                plant(currency, observedAt);
            }
        }
    }

    /** One reference observation of EUR/{@code currency} under this case's source - its id returned. */
    private UUID plant(String currency, Instant observedAt) throws SQLException {
        UUID id = IDS.next();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement insert = app.prepareStatement("INSERT INTO fx.rate_snapshot (id, source,"
                        + " base_currency, quote_currency, rate, observed_at) VALUES (?, ?, 'EUR', ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setString(2, source);
            insert.setString(3, currency);
            insert.setBigDecimal(4, new java.math.BigDecimal(RATES.get(currency)));
            insert.setTimestamp(5, Timestamp.from(observedAt));
            assertThat(insert.executeUpdate()).as("EUR/%s stored", currency).isEqualTo(1);
        }
        return id;
    }

    /** A raw PROPOSED set of January with one rate and {@code gaps} declared, the rate written as {@code %RATE%} names it. */
    private static String rawSet(UUID id, UUID period, int gaps, String reason) {
        return "INSERT INTO accounting.valuation_rate_set (id, period_id, functional_currency, as_of, max_age_hours,"
                + " rate_count, status, captured_by, captured_by_type, captured_at, capture_reason, correlation_id,"
                + " reference_gaps) VALUES ('" + id + "', '" + period + "', 'EUR', timestamptz '1999-02-01 00:00:00+00', 96,"
                + " 1, 'PROPOSED', 'raw-capturer', 'CUSTOMER', clock_timestamp(), " + reason + ", 'raw', " + gaps + ")"
                + "#" + id;
    }

    /**
     * The statements in one transaction and its commit - refused with {@code sqlState} at some statement or at the commit.
     * A {@code %RATE%} statement inserts the one rate of the set before it: its observed instant and flag follow the marker.
     */
    private static void refusedInTransaction(List<String> statements, String sqlState) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            String set = null;
            try (Statement statement = app.createStatement()) {
                for (String sql : statements) {
                    if (sql.startsWith("%RATE%")) {
                        statement.execute("INSERT INTO accounting.valuation_rate (set_id, position, base_currency,"
                                + " quote_currency, rate, source, snapshot_id, observed_at, received_at, beyond_bound) VALUES ('"
                                + set + "', 1, 'EUR', 'JPY', 131.25, 'gap-raw', '" + UUID.randomUUID() + "', "
                                + sql.substring("%RATE%".length()).replace(", true", ", " + observedPlusOne(sql) + ", true")
                                        .replace(", false", ", " + observedPlusOne(sql) + ", false") + ")");
                    } else {
                        set = sql.substring(sql.indexOf('#') + 1);
                        statement.execute(sql.substring(0, sql.indexOf('#')));
                    }
                }
                app.commit();
                throw new AssertionError("committed what V012 must refuse: " + statements);
            } catch (SQLException refused) {
                assertThat(refused.getSQLState()).as(statements.toString()).isEqualTo(sqlState);
                app.rollback();
            }
        }
    }

    /** The received instant: the observed one plus a second (the rate's CHECK holds observed within a minute of it). */
    private static String observedPlusOne(String sql) {
        String observed = sql.substring("%RATE%".length(), sql.lastIndexOf(", "));
        return observed + " + interval '1 second'";
    }
}
