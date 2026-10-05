package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The FX provider report's matching (`P9-TSK-011`, PHASE_9_PLAN.md sections 12.9.1-12.9.2): each
 * leg settles its cover leg's expectation by {@code COVER_REF} on the provider's OWN position, and
 * the provider's fee is judged against v1's 0 + 0 schedule - any reported FX fee a
 * {@code FEE_MISMATCH}. A private FX-shaped source carries the FX v1 rules (the real source's v1 is
 * a four-eyes act, never a seed); the position is {@code fx-sim-a}'s seeded EUR account (ledger
 * `V022`). Reconciliation never converts: each leg is judged in its own currency.
 */
@Tag("database")
@DisplayName("the FX provider report's matching: legs by COVER_REF, the fee against 0 + 0 (P9-TSK-011)")
class FxMatchingDatabaseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final UUID SOURCE = UUID.fromString("01a0e2bc-8200-7019-8000-000000000019");
    private static final UUID RULE_SET = UUID.fromString("01a0e2bd-8300-7019-8000-000000000019");
    private static final LocalDate BASE = LocalDate.parse("2026-11-01").plusDays(100L * new SecureRandom().nextInt(30));
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 70_000);

    private static Connection application;
    private static JdbcReconciliationRuns runs;
    private static JdbcExternalItems items;
    private static JdbcExpectationRegister expectations;
    private static Matching matching;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        runs = new JdbcReconciliationRuns();
        items = new JdbcExternalItems();
        expectations = new JdbcExpectationRegister(IDS);
        JdbcBreakRegister register = new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS);
        Suspense suspense = new Suspense(postingService(), new JdbcLedgerAccountStore(), IDS);
        matching = new Matching(
                new JdbcMatchingStore(), new MatchingRules(), register, suspense,
                ResolutionFixtures.resolutions(IDS, CLOCK),
                (unitOfWork, subject) -> InternalReferenceLookup.InternalReference.unknown(),
                new JdbcLedgerAccountStore(), new JdbcOutboxWriter(),
                new JdbcAuditWriter(), IDS, CLOCK, new Matching.Config(200, 2), runner(),
                ReconciliationTelemetry.NONE,
                // The composed answer, made pure: this suite's source settles fx-sim-a's OWN account.
                (unitOfWork, sourceId, position, currency) -> new JdbcLedgerAccountStore()
                        .findCounterpartyAccount(unitOfWork, position, "fx-sim-a", currency)
                        .map(account -> account.id().value()));
        seedPrivateFxSource();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    @Test
    @DisplayName("(a) each leg settles its cover leg by COVER_REF on fx-sim-a's own position - sold OUTBOUND,"
            + " bought INBOUND - the trade reference only an alias")
    void eachLegSettlesByItsCoverReference() throws SQLException {
        // One leg per cover in this currency's file; (d) settles ONE cover's two legs.
        LocalDate day = BASE.plusDays(1);
        String sellCover = coverRef();
        String buyCover = coverRef();
        UUID sold = open(ExpectationKind.FX_SELL_LEG, sellCover, ExpectationDirection.OUTBOUND, 100_000, day);
        UUID bought = open(ExpectationKind.FX_BUY_LEG, buyCover, ExpectationDirection.INBOUND, 92_500, day);
        UUID runId = seedRun(day,
                line(1, ExternalLineType.FX_SOLD, ExpectationDirection.OUTBOUND, 100_000, day,
                        Map.of(ItemKeyKind.COVER_REF, sellCover, ItemKeyKind.FX_TRADE_REF, "fxt_" + sellCover.substring(2, 10))),
                line(2, ExternalLineType.FX_BOUGHT, ExpectationDirection.INBOUND, 92_500, day,
                        Map.of(ItemKeyKind.COVER_REF, buyCover, ItemKeyKind.FX_TRADE_REF, "fxt_" + buyCover.substring(2, 10))));

        matching.sweep();

        assertThat(string("SELECT status FROM reconciliation.external_item WHERE run_id = ? AND line_no = 1", runId))
                .isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE run_id = ? AND line_no = 2", runId))
                .isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?", sold)).isEqualTo("SETTLED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?", bought)).isEqualTo("SETTLED");
        assertThat(string("SELECT d.matched_key_kind FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE i.run_id = ? AND i.line_no = 1", runId))
                .isEqualTo("COVER_REF");
    }

    @Test
    @DisplayName("(b) the provider's fee is CHECKED against v1's 0 + 0 schedule: any reported FX fee is a"
            + " FEE_MISMATCH of its whole value - judged, never allocating, never parked")
    void anFxFeeIsAFeeMismatch() throws SQLException {
        LocalDate day = BASE.plusDays(2);
        String cover = coverRef();
        open(ExpectationKind.FX_SELL_LEG, cover, ExpectationDirection.OUTBOUND, 50_000, day);
        UUID runId = seedRun(day,
                line(1, ExternalLineType.FX_SOLD, ExpectationDirection.OUTBOUND, 50_000, day,
                        Map.of(ItemKeyKind.COVER_REF, cover)),
                line(2, ExternalLineType.FX_FEE, ExpectationDirection.OUTBOUND, 40, day,
                        Map.of(ItemKeyKind.ORIGINAL_REF, cover)));

        matching.sweep();

        assertThat(string("SELECT status FROM reconciliation.external_item WHERE run_id = ? AND line_no = 2", runId))
                .isEqualTo("CHECKED");
        UUID fee = (UUID) one("SELECT id FROM reconciliation.external_item WHERE run_id = ? AND line_no = 2", runId);
        assertThat(row("SELECT type, cause, value_at_issue_minor FROM reconciliation.break WHERE external_item_id = ?", fee))
                .containsExactly("FEE_MISMATCH", "FEE_BEYOND_TOLERANCE", 40L);
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE external_item_id = ?", fee)).isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE external_item_id = ?", fee)).isZero();
    }

    @Test
    @DisplayName("(c) a leg naming no cover waits, then at grace parks on fx-sim-a's OWN position - the account"
            + " its source settles, never a shared one (none exists for a counterparty purpose)")
    void anUnknownLegParksOnTheCounterpartysOwnPosition() throws SQLException {
        LocalDate day = BASE.plusDays(3);
        UUID runId = seedRun(day,
                line(1, ExternalLineType.FX_BOUGHT, ExpectationDirection.INBOUND, 31_500, day,
                        Map.of(ItemKeyKind.COVER_REF, coverRef())));
        matching.sweep();
        UUID item = (UUID) one("SELECT id FROM reconciliation.external_item WHERE run_id = ? AND line_no = 1", runId);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?", item)).isEqualTo("UNMATCHED");

        runner().inTransaction(unitOfWork -> {
            execute(unitOfWork, "UPDATE reconciliation.external_item SET grace_until = now() - interval '1 hour'"
                    + " WHERE run_id = ?", runId);
            return null;
        });
        matching.sweep();

        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?", item)).isEqualTo("PARKED");
        assertThat(row("SELECT origin, amount_minor - released_minor FROM reconciliation.suspense_item"
                + " WHERE external_item_id = ?", item)).containsExactly("RECON_PARK", 31_500L);
        UUID parkedOn = (UUID) one("SELECT position_account_id FROM reconciliation.suspense_item"
                + " WHERE external_item_id = ?", item);
        assertThat(parkedOn).isEqualTo(runner().inTransaction(FxMatchingDatabaseTest::position));
    }

    @Test
    @DisplayName("(d) ONE cover's two legs - the same T in the EUR file and the USD file - each hold their own key"
            + " (qualified by the leg's currency, P9-TSK-012) and each settles; no KEY_COLLISION, nothing parked")
    void oneCoversTwoLegsBothSettle() throws SQLException {
        LocalDate day = BASE.plusDays(4);
        String cover = coverRef();
        UUID sold = open(ExpectationKind.FX_SELL_LEG, cover, ExpectationDirection.OUTBOUND, 100_000, day, EUR);
        UUID bought = open(ExpectationKind.FX_BUY_LEG, cover, ExpectationDirection.INBOUND, 108_502, day, USD);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_key WHERE key_kind = 'COVER_REF'"
                + " AND key_value IN (?, ?)", cover + ":EUR", cover + ":USD"))
                .as("two keys, one per leg").isEqualTo(2);
        UUID eurRun = seedRun(day, line(1, ExternalLineType.FX_SOLD, ExpectationDirection.OUTBOUND, 100_000, day,
                Map.of(ItemKeyKind.COVER_REF, cover, ItemKeyKind.FX_TRADE_REF, "FT-d1"), EUR));
        UUID usdRun = seedRun(day, line(1, ExternalLineType.FX_BOUGHT, ExpectationDirection.INBOUND, 108_502, day,
                Map.of(ItemKeyKind.COVER_REF, cover, ItemKeyKind.FX_TRADE_REF, "FT-d1"), USD));

        matching.sweep();

        assertThat(string("SELECT status FROM reconciliation.external_item WHERE run_id = ?", eurRun)).isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE run_id = ?", usdRun)).isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?", sold)).isEqualTo("SETTLED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?", bought)).isEqualTo("SETTLED");
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN reconciliation.external_item i"
                + " ON i.id = b.external_item_id WHERE i.run_id IN (?, ?)", eurRun, usdRun)).isZero();
    }

    // -----------------------------------------------------------------

    private record Line(int lineNo, ExternalLineType type, ExpectationDirection direction, long minor,
            LocalDate day, Map<ItemKeyKind, String> keys, CurrencyCode currency) {}

    private static Line line(int lineNo, ExternalLineType type, ExpectationDirection direction, long minor,
            LocalDate day, Map<ItemKeyKind, String> keys) {
        return new Line(lineNo, type, direction, minor, day, keys, EUR);
    }

    private static Line line(int lineNo, ExternalLineType type, ExpectationDirection direction, long minor,
            LocalDate day, Map<ItemKeyKind, String> keys, CurrencyCode currency) {
        return new Line(lineNo, type, direction, minor, day, keys, currency);
    }

    private static String coverRef() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return "T-" + HexFormat.of().formatHex(bytes);
    }

    private static UUID position(Connection unitOfWork) {
        return position(unitOfWork, EUR);
    }

    private static UUID position(Connection unitOfWork, CurrencyCode currency) {
        return new JdbcLedgerAccountStore()
                .findCounterpartyAccount(unitOfWork, AccountPurpose.FX_PROVIDER_CLEARING, "fx-sim-a", currency)
                .orElseThrow(() -> new IllegalStateException("ledger V022 seeds fx-sim-a's account in " + currency))
                .id().value();
    }

    /** A cover leg's expectation, as the cover outcome opens it (P9-TSK-012), keyed COVER_REF. */
    private static UUID open(ExpectationKind kind, String cover, ExpectationDirection direction, long minor,
            LocalDate expectedBy) {
        return open(kind, cover, direction, minor, expectedBy, EUR);
    }

    private static UUID open(ExpectationKind kind, String cover, ExpectationDirection direction, long minor,
            LocalDate expectedBy, CurrencyCode currency) {
        String operationRef = cover + ":" + kind;
        return runner().inTransaction(unitOfWork -> {
            expectations.open(unitOfWork, new NewExpectation(
                    kind, operationRef, "fx-cover-test:" + operationRef, SOURCE, AccountPurpose.FX_PROVIDER_CLEARING,
                    position(unitOfWork, currency), direction, Money.ofPersisted(minor, currency, 2), Optional.of(IDS.next()),
                    expectedBy.minusDays(2), Optional.empty(), expectedBy, RULE_SET,
                    List.of(new NewExpectation.ExpectationKey(KeyKind.COVER_REF, cover)),
                    PLATFORM, Instant.now(), CorrelationId.generate(IDS)));
            try (PreparedStatement read = unitOfWork.prepareStatement(
                    "SELECT id FROM reconciliation.expectation WHERE kind = ? AND operation_ref = ?")) {
                read.setString(1, kind.name());
                read.setString(2, operationRef);
                try (ResultSet row = read.executeQuery()) {
                    row.next();
                    return row.getObject("id", UUID.class);
                }
            } catch (SQLException failure) {
                throw new ReconciliationStorageException("could not seed", failure);
            }
        });
    }

    private static UUID seedRun(LocalDate day, Line... lines) {
        UUID runId = IDS.next();
        return runner().inTransaction(unitOfWork -> {
            runs.birth(unitOfWork, new ReconciliationRuns.NewRun(
                    runId, SOURCE, Optional.of(IDS.next()), RunKind.BATCH, RULE_SET, day,
                    Optional.of(SEQUENCES.incrementAndGet()), lines.length, Optional.empty(), Optional.empty(),
                    PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS), Optional.empty()));
            List<ExternalItems.NewItem> newItems = new ArrayList<>();
            for (Line line : lines) {
                byte[] print = new byte[32];
                new SecureRandom().nextBytes(print);
                newItems.add(new ExternalItems.NewItem(
                        IDS.next(), runId, SOURCE, IDS.next(), line.lineNo(), line.type(), line.direction(),
                        Money.ofPersisted(line.minor(), line.currency(), 2), AccountPurpose.FX_PROVIDER_CLEARING, line.day(),
                        Optional.of(line.day()), Optional.of(line.day()), print, line.keys(),
                        Instant.now(CLOCK), CorrelationId.generate(IDS)));
            }
            items.birthAll(unitOfWork, PLATFORM, newItems);
            return runId;
        });
    }

    /** The FX v1 rules on a private source, seeded the V012 way: born PROPOSED with its members, then activated. */
    private static void seedPrivateFxSource() {
        runner().inTransaction(unitOfWork -> {
            execute(unitOfWork, "INSERT INTO reconciliation.rule_set (id, source_id, version, status, funding_lag_days,"
                    + " gain_min_age_days, effective_from, proposed_by, decided_by, reason, created_at, correlation_id)"
                    + " VALUES (?, ?, 1, 'PROPOSED', 2, 90, ?, 'test', NULL, 'FxMatchingDatabaseTest private FX source',"
                    + " now(), 'p9-tsk-011-test') ON CONFLICT (id) DO NOTHING",
                    RULE_SET, SOURCE, java.sql.Date.valueOf(LocalDate.parse("2026-09-29")));
            execute(unitOfWork, "INSERT INTO reconciliation.rule_set_lag (rule_set_id, expectation_kind, lag_days)"
                    + " VALUES (?, 'FX_SELL_LEG', 2), (?, 'FX_BUY_LEG', 2) ON CONFLICT DO NOTHING", RULE_SET, RULE_SET);
            execute(unitOfWork, "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type, key_kind,"
                    + " expectation_kind, cardinality, operation_anchored, grace_hours) VALUES"
                    + " (?, 1, 'FX_SOLD', 'COVER_REF', 'FX_SELL_LEG', 'ONE_TO_ONE', false, 24),"
                    + " (?, 2, 'FX_BOUGHT', 'COVER_REF', 'FX_BUY_LEG', 'ONE_TO_ONE', false, 24),"
                    + " (?, 3, 'FX_FEE', 'ORIGINAL_REF', NULL, 'CHECK', false, 24) ON CONFLICT DO NOTHING",
                    RULE_SET, RULE_SET, RULE_SET);
            execute(unitOfWork, "INSERT INTO reconciliation.tolerance (rule_set_id, comparison, currency, absolute_minor, days)"
                    + " SELECT ?, 'SETTLEMENT_DATE_DAYS', NULL::char(3), NULL::bigint, 2 WHERE NOT EXISTS (SELECT 1 FROM"
                    + " reconciliation.tolerance WHERE rule_set_id = ? AND comparison = 'SETTLEMENT_DATE_DAYS')",
                    RULE_SET, RULE_SET);
            execute(unitOfWork, "INSERT INTO reconciliation.provider_fee_schedule (rule_set_id, line_type, currency, rate,"
                    + " fixed_minor, scale, rounding_policy) VALUES (?, 'FX_FEE', 'EUR', 0.000000, 0, 2, 'HALF_UP')"
                    + " ON CONFLICT DO NOTHING", RULE_SET);
            execute(unitOfWork, "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency, high_value_minor)"
                    + " VALUES (?, 'EUR', 100000) ON CONFLICT DO NOTHING", RULE_SET);
            execute(unitOfWork, "UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by = 'test-activator',"
                    + " decided_at = now() WHERE id = ? AND status = 'PROPOSED'", RULE_SET);
            return null;
        });
    }

    private static PostingService postingService() {
        return new PostingService(
                new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE);
    }

    private static TransactionRunner runner() {
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                try (Connection unitOfWork = DatabaseRoles.application()) {
                    unitOfWork.setAutoCommit(false);
                    try {
                        R result = work.apply(unitOfWork);
                        unitOfWork.commit();
                        return result;
                    } catch (RuntimeException failure) {
                        unitOfWork.rollback();
                        throw failure;
                    }
                } catch (SQLException failure) {
                    throw new ReconciliationStorageException("the test transaction failed", failure);
                }
            }
        };
    }

    private static void execute(Connection unitOfWork, String sql, Object... args) {
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("statement failed: " + sql, failure);
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }

    private static String string(String sql, Object... args) throws SQLException {
        return (String) one(sql, args);
    }

    private static Object[] row(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                Object[] values = new Object[result.getMetaData().getColumnCount()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = result.getObject(i + 1);
                }
                application.rollback();
                return values;
            }
        }
    }

    private static Object one(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                Object value = row.next() ? row.getObject(1) : null;
                application.rollback();
                return value;
            }
        }
    }
}
