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
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.JdbcValuationRateSetStore;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.ValuationRateSet;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.Optional;
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
 * immutable for every writer; the capturer refused as approver, by the domain and by the {@code CHECK}; a capture racing
 * an unlock (the unlock waits on the capture's {@code FOR SHARE}, then opens the period - the set stays, unapprovable) and
 * an approval racing an unlock (the approval waits on the unlock's row lock, then re-reads {@code OPEN} and is refused);
 * and - last, since a period admits one approved set for good - two captures and <strong>ten concurrent approvals</strong>
 * leaving exactly one approved set, a raw second approval refused by the partial unique.
 *
 * <p>January 1999 is the one period the fixtures can lock (the first; February would need January {@code CLOSED}, which
 * `P14-TSK-012` builds, and the contiguity trigger refuses a far period without every month before it), so each case
 * locks it and {@link AccountingPeriodFixture#restore} returns it to {@code OPEN}; each case's reference is a source of
 * its own ({@code valuation-test-<uuid>}), so fx's newer-than-latest rule never meets another suite's rows.
 * <strong>January 1999 ends this suite with an approved set for good</strong> - a set is never deleted and a period has
 * one approved set - so a later database suite that must approve a set of its own (`-011`'s close, `-015`'s translation)
 * either uses another period (February, once `-012` can close January) or runs before this one; one that needs only
 * <em>an</em> approved set can read January's ({@code ValuationRateSets.approvedFor}). Only {@link #oneApprovedPerPeriod},
 * ordered last, approves; every race before it is arranged so that no approval succeeds.
 *
 * <p><strong>Translation (`P14-TSK-015`) lives here for that order</strong>: the suite holds the one sequence in which
 * January 1999 has no approved set and then has exactly one, so a refusal "no approved set" and a pin of a real approved
 * set are both proven, whatever other suites ran. Before {@link #oneApprovedPerPeriod}: a FINAL translated run naming a
 * proposed or an unknown set refused, with nothing written, and a PROVISIONAL one in an open February refused. After it: a
 * FINAL translated run pinning January's set - the key to it, the journal untouched, regenerating IDENTICAL - beside the
 * plain run, and a PROVISIONAL one at the set an unlock left approved.
 * <strong>Written and owed</strong> (owner decision C5: the database tiers are skipped per task).
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("valuation rate sets against the database (P14-TSK-014)")
class ValuationRateSetDatabaseTest {

    private static final int APPROVERS = 10;
    private static final TrialBalanceDefinition TRIAL_BALANCE = new TrialBalanceDefinition(
            new LedgerTrialBalanceReads(new JdbcGlMappingStore()), new PinnedValuationRates(new JdbcValuationRateSetStore()));
    private static final ReportDefinitions DEFINITIONS = ReportDefinitions.of(TRIAL_BALANCE);
    private static final JdbcReportSnapshots SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::application,
            ReportRuns.Leases.STANDARD.generationTimeout());
    private static final AccountingTransactions TRANSACTIONS = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
            try (Connection connection = DatabaseRoles.application()) {
                connection.setAutoCommit(false);
                try {
                    R result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };
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
    @DisplayName("a capture racing an unlock: the unlock's approval waits on the capture's FOR SHARE and opens the period"
            + " after it commits - the set stays PROPOSED and unapprovable; an unlock committed first refuses the capture")
    void aCaptureRacingAnUnlock() throws Exception {
        plantAll(MONTH_END);
        AccountingPeriodId january = id(JANUARY);
        inScope(person(), uow -> periods().proposeUnlock(uow, january, SecurityContext.require(), "a late invoice",
                correlation()));
        Actor accountant = person();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection capturing = held(accountant,
                uow -> sets().capture(uow, january, accountant, null, correlation()))) {
            Future<?> unlock = pool.submit(() -> inScope(person(), uow -> periods().approveUnlock(uow, january,
                    SecurityContext.require(), "agreed", correlation())));
            awaitAWaiter(unlock);
            capturing.commit();
            unlock.get(2, TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM accounting.accounting_period WHERE id = '" + january.value()
                + "' AND status = 'OPEN'")).isEqualTo(1);
        UUID captured = latestSet();
        assertThat(count("SELECT count(*) FROM accounting.valuation_rate_set WHERE id = '" + captured
                + "' AND status = 'PROPOSED' AND rate_count = 4")).isEqualTo(1);
        assertThatThrownBy(() -> inScope(person(), uow -> sets().approve(uow,
                com.finapp.accounting.ValuationRateSetId.of(captured), SecurityContext.require(), "late",
                correlation()))).isInstanceOf(com.finapp.accounting.AccountingPeriods.PeriodStale.class);

        assertThatThrownBy(() -> capture(person())).as("the unlock committed first: the capture reads OPEN")
                .isInstanceOf(com.finapp.accounting.AccountingPeriods.PeriodStale.class);
    }

    @Test
    @Order(7)
    @DisplayName("an approval racing an unlock: the approval waits on the unlock's row lock, re-reads the period OPEN and"
            + " is refused - nothing approved")
    void anApprovalRacingAnUnlock() throws Exception {
        plantAll(MONTH_END);
        AccountingPeriodId january = id(JANUARY);
        ValuationRateSets.Valued captured = capture(person());
        inScope(person(), uow -> periods().proposeUnlock(uow, january, SecurityContext.require(), "a late invoice",
                correlation()));
        Actor approver = person();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Object> approval;
        try (Connection unlocking = held(person(), uow -> periods().approveUnlock(uow, january,
                SecurityContext.require(), "agreed", correlation()))) {
            approval = pool.submit(() -> {
                try {
                    return inScope(approver, uow -> sets().approve(uow, captured.id(), approver, "racing",
                            correlation()));
                } catch (RuntimeException refused) {
                    return refused;
                }
            });
            awaitAWaiter(approval);
            unlocking.commit();
            assertThat(approval.get(2, TimeUnit.MINUTES))
                    .isInstanceOf(com.finapp.accounting.AccountingPeriods.PeriodStale.class);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM accounting.valuation_rate_set WHERE id = '" + captured.id().value()
                + "' AND status = 'PROPOSED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.ValuationSetApproved'"
                + " AND target_id = '" + captured.id().value() + "'")).isZero();
    }

    @Test
    @Order(8)
    @DisplayName("P14-TSK-015: January has no approved set, so a FINAL translated run is refused - naming the proposed set or"
            + " an unknown id, the unknown one refused before the key ever sees it - nothing written; asked plain, it"
            + " never needs a set")
    void aFinalTranslatedRunWithNoApprovedSetIsRefused() throws SQLException {
        plantAll(MONTH_END);
        ValuationRateSets.Valued proposed = capture(person());
        AccountingPeriodId january = id(JANUARY);
        long runs = count("SELECT count(*) FROM accounting.report_run WHERE valuation_rate_set_id IS NOT NULL");
        for (UUID named : List.of(proposed.id().value(), UUID.randomUUID())) {
            Actor accountant = person();
            assertThatThrownBy(() -> inScope(accountant, uow -> reportRuns().claim(uow, TrialBalanceDefinition.CODE, january,
                    RunParameters.of(Map.of(), Optional.of(named)), accountant, correlation())))
                    .isInstanceOf(ReportRuns.ValuationSetNotApproved.class);
        }
        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE valuation_rate_set_id IS NOT NULL"))
                .isEqualTo(runs);
        ReportRunStore.RunRow plain = ask(january, RunParameters.none());
        assertThat(plain.status()).isEqualTo(RunStatus.FINAL);
        assertThat(plain.valuationRateSetId()).isEmpty();
        assertThat(stored(plain, "{body,verdict,passes}")).isEqualTo("true");
        assertThat(stored(plain, "{body,translation}")).isNull();
    }

    @Test
    @Order(9)
    @DisplayName("P14-TSK-015: an open period has no approved set, so a PROVISIONAL translated run is refused too - never"
            + " translated at another period's rates")
    void aProvisionalTranslatedRunInAnOpenPeriodIsRefused() throws SQLException {
        plantAll(MONTH_END);
        ValuationRateSets.Valued januarys = capture(person());
        Actor accountant = person();
        assertThatThrownBy(() -> inScope(accountant, uow -> reportRuns().claim(uow, TrialBalanceDefinition.CODE,
                id(FEBRUARY), RunParameters.of(Map.of(), Optional.of(januarys.id().value())), accountant, correlation())))
                .isInstanceOf(ReportRuns.ValuationSetNotApproved.class);
    }

    @Test
    @Order(100)
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

    @Test
    @Order(101)
    @DisplayName("P14-TSK-015: a FINAL translated run pins January's approved set and regenerates IDENTICAL: the key to the"
            + " set, the originals beside the translation, translated debits equal to credits, the verdict at its path, not"
            + " one journal line added or changed - beside the plain run, a question of its own")
    void aFinalTranslatedRunPinsItsSetAndRegeneratesIdentical() throws SQLException {
        // Lines in every currency, dated inside January: the month reopens through the unlock, its approved set stays.
        AccountingPeriodFixture.restore();
        for (Map.Entry<String, Long> posted : Map.of("EUR", 100_001L, "GBP", 12_345L, "USD", 20_003L, "JPY", 1_234L, "BHD",
                12_345L).entrySet()) {
            CurrencyCode currency = CurrencyCode.of(posted.getKey());
            LedgerAccount clearing = TrialBalanceRunDatabaseTest.operational(AccountPurpose.SETTLEMENT_CLEARING, currency);
            TrialBalanceRunDatabaseTest.post(JANUARY.atDay(15), clearing, TrialBalanceRunDatabaseTest.wallet(currency),
                    posted.getValue(), currency, NOW);
        }
        inScope(person(), uow -> periods(on("1999-02-15")).lock(uow, id(JANUARY), SecurityContext.require(),
                "translation test", correlation()));
        AccountingPeriodId january = id(JANUARY);
        UUID set = inScope(person(), uow -> sets().approvedFor(uow, january)).orElseThrow().id().value();
        long lines = count("SELECT count(*) FROM ledger.journal_line");
        long entries = count("SELECT count(*) FROM ledger.journal_entry");

        ReportRunStore.RunRow plain = ask(january, RunParameters.none());
        ReportRunStore.RunRow translated = ask(january, RunParameters.of(Map.of(), Optional.of(set)));

        assertThat(translated.id()).as("two questions, two runs").isNotEqualTo(plain.id());
        assertThat(translated.basis()).isEqualTo(RunBasis.FINAL);
        assertThat(translated.status()).isEqualTo(RunStatus.FINAL);
        assertThat(translated.valuationRateSetId()).contains(set);
        assertThat(count("SELECT count(*) FROM accounting.report_run r JOIN accounting.valuation_rate_set s"
                + " ON s.id = r.valuation_rate_set_id WHERE r.id = '" + translated.id().value() + "' AND s.id = '" + set
                + "' AND s.status = 'APPROVED'")).isEqualTo(1);
        assertThat(stored(translated, "{body,verdict,passes}")).isEqualTo(stored(plain, "{body,verdict,passes}"))
                .isEqualTo("true");
        assertThat(stored(translated, "{valuationRateSet}")).isEqualTo(set.toString());
        assertThat(stored(translated, "{body,translation,rateSet,id}")).isEqualTo(set.toString());
        assertThat(stored(translated, "{body,translation,complete}")).isEqualTo("true");
        assertThat(stored(translated, "{body,translation,rounding}")).isEqualTo("HALF_EVEN");
        assertThat(stored(translated, "{body,translation,total,debits,translatedTotal,minor}"))
                .as("translated debits equal credits, through the difference lines")
                .isEqualTo(stored(translated, "{body,translation,total,credits,translatedTotal,minor}"));
        assertThat(stored(plain, "{body,translation}")).as("the plain run holds no translation").isNull();

        ReportRunGenerator.Compared compared = generator().regenerate(translated.id());
        assertThat(compared.identical()).as("a regeneration of the FINAL translated run").isTrue();
        assertThat(compared.regeneratedSha256()).isEqualTo(translated.contentSha256().orElseThrow());
        assertThat(generator().regenerate(plain.id()).identical()).isTrue();

        assertThat(count("SELECT count(*) FROM ledger.journal_line")).as("nothing posted").isEqualTo(lines);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry")).isEqualTo(entries);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.ReportGenerated'"
                + " AND target_id = '" + translated.id().value() + "' AND change_summary LIKE '%valuationRateSet=" + set + "%'"))
                .as("the record names the set").isEqualTo(1);
    }

    @Test
    @Order(102)
    @DisplayName("P14-TSK-015: an unlock leaves an approved set approved, so the period's PROVISIONAL translated run reads it"
            + " - labelled by its basis and its cut")
    void aProvisionalTranslatedRunAfterAnUnlockReadsItsOwnSet() throws SQLException {
        AccountingPeriodId january = id(JANUARY);
        UUID set = inScope(person(), uow -> sets().approvedFor(uow, january)).orElseThrow().id().value();
        AccountingPeriodFixture.restore();
        ReportRunStore.RunRow run = ask(january, RunParameters.of(Map.of(), Optional.of(set)));
        assertThat(run.basis()).isEqualTo(RunBasis.PROVISIONAL);
        assertThat(run.status()).isEqualTo(RunStatus.PROVISIONAL);
        assertThat(run.valuationRateSetId()).contains(set);
        assertThat(stored(run, "{basis}")).isEqualTo("PROVISIONAL");
        assertThat(stored(run, "{cut}")).isNotNull();
        assertThat(stored(run, "{body,translation,rateSet,id}")).isEqualTo(set.toString());
    }

    // ------------------------------------------------------------------ the report runs (P14-TSK-015)

    private static ReportRuns reportRuns() {
        return new ReportRuns(new JdbcReportRunStore(), AccountingPeriodFixture.STORE, new JdbcGlMappingStore(), DEFINITIONS,
                ReportRuns.Leases.STANDARD, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW,
                ReportRuns.Provisional.STANDARD, ReportRuns.ApprovedSets.of(new JdbcValuationRateSetStore()));
    }

    private static ReportRunGenerator generator() {
        return new ReportRunGenerator(reportRuns(), new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT,
                ReportRuns.Leases.STANDARD.generationTimeout(), NOW), SNAPSHOTS, TRANSACTIONS,
                new ReportRunMetrics(new SimpleMeterRegistry(), DEFINITIONS));
    }

    /** Claim, then generate and complete when this caller holds the run - the desk's path without the key. */
    private static ReportRunStore.RunRow ask(AccountingPeriodId period, RunParameters parameters) {
        Actor accountant = person();
        ReportRuns.Claimed claimed = inScope(accountant, uow -> reportRuns().claim(uow, TrialBalanceDefinition.CODE, period,
                parameters, accountant, correlation()));
        return claimed.mine() ? generator().generateAndComplete(claimed.run(), accountant)
                : generator().drive(claimed.run().id(), accountant);
    }

    /** The stored content at {@code path}, read by the database - {@code null} when the content holds nothing there. */
    private static String stored(ReportRunStore.RunRow run, String path) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement read = migrator.prepareStatement(
                        "SELECT content::jsonb #>> '" + path + "' FROM accounting.report_run WHERE id = ?")) {
            read.setObject(1, run.id().value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
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

    /**
     * {@code work} on a connection of its own, as {@code actor} in a correlation scope (the ledger's move reads both),
     * <strong>left uncommitted</strong> - its row locks held until the caller commits or closes it.
     */
    @SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
    private static Connection held(Actor actor, java.util.function.Function<Connection, ?> work) throws SQLException {
        Connection connection = DatabaseRoles.application();
        connection.setAutoCommit(false);
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                com.finapp.platform.correlation.CorrelationContext.Scope flow =
                        com.finapp.platform.correlation.CorrelationContext.enter(
                                com.finapp.sharedkernel.correlation.Correlation.startingWith(correlation()))) {
            work.apply(connection);
            return connection;
        } catch (RuntimeException failed) {
            connection.rollback();
            connection.close();
            throw failed;
        }
    }

    /** Waits until a backend waits on a row lock - the racing thread blocked behind the held transaction. */
    private static void awaitAWaiter(Future<?> racing) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (count("SELECT count(*) FROM pg_locks WHERE NOT granted") == 0) {
            assertThat(racing.isDone()).as("the racer waits on the held row - it must not finish first").isFalse();
            assertThat(System.nanoTime()).as("a waiter within a minute").isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(racing.isDone()).isFalse();
    }

    /** The newest set of January 1999 - the one a case just captured. */
    private static UUID latestSet() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery("SELECT s.id FROM accounting.valuation_rate_set s"
                        + " JOIN accounting.accounting_period p ON p.id = s.period_id"
                        + " WHERE p.period_start = DATE '1999-01-01' ORDER BY s.captured_at DESC LIMIT 1")) {
            row.next();
            return row.getObject(1, UUID.class);
        }
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
