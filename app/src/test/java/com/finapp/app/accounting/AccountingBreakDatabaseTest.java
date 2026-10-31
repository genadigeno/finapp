package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingBreakId;
import com.finapp.accounting.AccountingBreaks;
import com.finapp.accounting.AccountingChecks;
import com.finapp.accounting.BreakFinding;
import com.finapp.accounting.BreakType;
import com.finapp.accounting.CheckOutcome;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.GlPins;
import com.finapp.accounting.JdbcAccountingBreakStore;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcPeriodPins;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.accounting.SourceProofs;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Accounting breaks against the real schema and journal (`P14-TSK-010`; ADR-0117 decision 3; {@code INV-ACC-12},
 * {@code INV-AUD-04}; accounting {@code V006}; {@code PHASE_14_PLAN.md} section 8.3 row 10): the entry's named cases -
 * two raises one break, a planted shape mismatch, an unregistered namespace and a source discrepancy each one break, the
 * proposer refused at the domain and by the {@code CHECK} - and ten concurrent raisers one break, a resolution posting
 * nothing, the case file insert-only. <strong>Written and owed</strong> (owner decision C5: the database tiers are skipped
 * per task; the owner-authorised pass before the exit review runs it).
 *
 * <p>The journal is shared by every suite, so each case posts into a month of its own chosen at random in 2097-2099 - past
 * every month another suite dates into ({@code GlReadModelDatabaseTest}'s February 2096, the period suites' 1999) - and
 * judges only its own planted subject, whatever else the month holds on a rerun. The planted entries go through the
 * ledger's own {@code PostingService} onto a fresh wallet and {@code FEE_REVENUE}, neither a reconciled position nor a
 * product book, so no other suite's proof reads them. The source case plants the proof's <em>finding</em> through a
 * {@code SourceProofs} double: planting a real product discrepancy in the shared database would fail every other suite's
 * proof for good; the real adapter's reading of each proof's verdict is {@code ProductSourceProofsTest}'s.
 */
@Tag("database")
@DisplayName("accounting breaks against the database: born once, four eyes, nothing posted (P14-TSK-010)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AccountingBreakDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final int RAISERS = 10;

    private final YearMonth month = YearMonth.of(2097 + RANDOM.nextInt(3), 1 + RANDOM.nextInt(12));
    private final JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();
    private final GlSnapshots snapshots = new GlSnapshots(DatabaseRoles::application, Duration.ofSeconds(30));
    private SourceProofs proofs = (snapshot, source) -> source.proofs().stream()
            .map(proof -> new SourceProofs.Reading(proof, true, List.of())).toList();

    @Test
    @DisplayName("two raises, one break: the check run twice leaves one row for the subject, one RAISED case-file row,"
            + " one AccountingBreakRaised and one audit record")
    void twoRaisesOneBreak() throws Exception {
        UUID planted = post("transfer:" + IDS.next(), wallet(), operational(AccountPurpose.FEE_REVENUE));

        CheckOutcome first = checks().run(month, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        CheckOutcome second = checks().run(month, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());

        assertThat(first.raised()).isGreaterThanOrEqualTo(1);
        assertThat(second.passed()).isFalse();
        String breakId = breakOf("shape:" + planted);
        assertThat(count("SELECT count(*) FROM accounting.accounting_break WHERE period_start = '" + month.atDay(1)
                + "' AND check_code = 'RULE_CONFORMANCE' AND subject_ref = 'shape:" + planted + "'")).isEqualTo(1);
        assertOnce(breakId);
    }

    @Test
    @DisplayName("contention row 10: ten raisers of one finding on ten connections - one break, one RAISED row, one"
            + " event, one audit record; the other nine insert nothing")
    void tenRaisersOneBreak() throws Exception {
        BreakFinding finding = new BreakFinding(CloseCheckCode.RULE_CONFORMANCE, BreakType.SHAPE_MISMATCH,
                "shape:" + IDS.next(), "entry planted by the race", "posting-rules:v2");
        ExecutorService pool = Executors.newFixedThreadPool(RAISERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> racing = new ArrayList<>();
        try {
            for (int i = 0; i < RAISERS; i++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    return inTransaction(uow -> breaks().raise(uow, month, finding, Actor.SYSTEM, correlation()));
                }));
            }
            start.countDown();
            int raised = 0;
            for (Future<Boolean> outcome : racing) {
                raised += outcome.get(2, TimeUnit.MINUTES) ? 1 : 0;
            }
            assertThat(raised).as("exactly one raiser inserted").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertOnce(breakOf(finding.subject()));
    }

    @Test
    @DisplayName("a planted shape mismatch - a transfer crediting fee revenue - raises one SHAPE_MISMATCH, judged by the"
            + " register's version; the check FAILED")
    void aPlantedShapeMismatchRaisesOneBreak() throws Exception {
        UUID planted = post("transfer:" + IDS.next(), wallet(), operational(AccountPurpose.FEE_REVENUE));

        CheckOutcome outcome = checks().run(month, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());

        assertThat(outcome.result()).isEqualTo(CheckOutcome.Result.FAILED);
        assertThat(outcome.judgedBy()).isEqualTo("posting-rules:v" + PostingRuleRegister.CATALOGUE_VERSION);
        String breakId = breakOf("shape:" + planted);
        assertThat(column("SELECT break_type || '|' || judged_by || '|' || status FROM accounting.accounting_break"
                + " WHERE id = '" + breakId + "'")).isEqualTo("SHAPE_MISMATCH|posting-rules:v"
                + PostingRuleRegister.CATALOGUE_VERSION + "|OPEN");
        assertThat(column("SELECT evidence FROM accounting.accounting_break WHERE id = '" + breakId + "'"))
                .contains("TRANSFER v1").contains("CREDIT FEE_REVENUE").doesNotContain("1234");
    }

    @Test
    @DisplayName("an unregistered namespace raises one UNREGISTERED_POSTING however many entries carry it")
    void anUnregisteredNamespaceRaisesOneBreak() throws Exception {
        String namespace = "p14-010-" + HexFormat.of().formatHex(RANDOM.generateSeed(4)) + ":";
        LedgerAccount wallet = wallet();
        for (int i = 0; i < 3; i++) {
            post(namespace + IDS.next(), wallet, operational(AccountPurpose.FEE_REVENUE));
        }
        checks().run(month, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        checks().run(month, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());

        String subject = "namespace:POSTING " + namespace;
        String breakId = breakOf(subject);
        assertThat(column("SELECT break_type || '|' || evidence FROM accounting.accounting_break WHERE id = '" + breakId
                + "'")).startsWith("UNREGISTERED_POSTING|").contains("entries=3");
        assertOnce(breakId);
    }

    @Test
    @DisplayName("a source discrepancy - a proof's finding - raises one SOURCE_DISCREPANCY on the proof's subject; a"
            + " second run none")
    void aSourceDiscrepancyRaisesOneBreak() throws Exception {
        String loan = IDS.next().toString();
        proofs = (snapshot, source) -> source.proofs().stream().map(proof -> new SourceProofs.Reading(proof, true,
                proof == SourceProofs.Proof.LOAN_SUBLEDGER
                        ? List.of(new SourceProofs.Discrepancy(loan, "fails=[UNEXPLAINED_ENTRIES]"))
                        : List.of())).toList();

        CheckOutcome first = checks().run(month, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM, correlation());
        CheckOutcome second = checks().run(month, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM, correlation());

        assertThat(first.raised()).isEqualTo(1);
        assertThat(second.raised()).isZero();
        assertThat(first.judgedBy()).isEqualTo("source-proofs:v1");
        String breakId = breakOf("LOAN_SUBLEDGER:" + loan);
        assertThat(column("SELECT break_type FROM accounting.accounting_break WHERE id = '" + breakId + "'"))
                .isEqualTo("SOURCE_DISCREPANCY");
        assertOnce(breakId);
    }

    @Test
    @DisplayName("the proposer cannot acknowledge: refused at the domain, and an UPDATE bypassing it refused by the"
            + " four-eyes CHECK; a second person acknowledges")
    void theProposerCannotAcknowledge() throws Exception {
        AccountingBreakId id = raisedBreak();
        Actor accountant = person();
        inScope(accountant, uow -> breaks().proposeAcknowledgement(uow, id, accountant,
                "a fee line the poster wrote, corrected in the next period", correlation()));

        assertThatThrownBy(() -> inScope(accountant, uow -> breaks().approveAcknowledgement(uow, id, accountant,
                "my own", correlation()))).isInstanceOf(AccountingBreaks.SelfApprovalRefused.class);
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE accounting.accounting_break SET status ="
                    + " 'ACKNOWLEDGED', ack_approved_by = ack_proposed_by, ack_approved_by_type = 'CUSTOMER',"
                    + " ack_approved_at = now(), ack_approval_reason = 'bypass' WHERE id = '" + id.value() + "'"))
                    .isInstanceOfSatisfying(SQLException.class, refused -> {
                        assertThat(refused.getSQLState()).isEqualTo("23514");
                        assertThat(refused.getMessage()).contains("accounting_break_acknowledgement_four_eyes");
                    });
        }
        Actor controller = person();
        AccountingBreaks.Acted acknowledged = inScope(controller, uow -> breaks().approveAcknowledgement(uow, id,
                controller, "disclosed at close", correlation()));
        assertThat(acknowledged.status().name()).isEqualTo("ACKNOWLEDGED");
        assertThat(count("SELECT count(*) FROM accounting.break_event WHERE break_id = '" + id.value()
                + "' AND act = 'ACKNOWLEDGED' AND proposed_by = '" + accountant.id() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a check run and a resolution post nothing: the journal's count is unchanged by both; the resolution"
            + " names the correcting entry")
    void aResolutionPostsNothing() throws Exception {
        UUID planted = post("transfer:" + IDS.next(), wallet(), operational(AccountPurpose.FEE_REVENUE));
        UUID correcting = post("p14-010-correction:" + IDS.next(), operational(AccountPurpose.FEE_REVENUE), wallet());
        // The month's own count: no other suite dates into it, so a concurrent suite's postings cannot move it.
        String monthsEntries = "SELECT count(*) FROM ledger.journal_entry WHERE posting_date BETWEEN '"
                + month.atDay(1) + "' AND '" + month.atEndOfMonth() + "'";
        long journal = count(monthsEntries);
        checks().run(month, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        AccountingBreakId id = AccountingBreakId.of(UUID.fromString(breakOf("shape:" + planted)));
        Actor controller = person();
        inScope(controller, uow -> breaks().resolve(uow, id, controller, correcting, "reversed by its correction",
                correlation()));

        assertThat(count(monthsEntries)).as("nothing posted (INV-ACC-12)").isEqualTo(journal);
        assertThat(column("SELECT status || '|' || resolving_entry_id FROM accounting.accounting_break WHERE id = '"
                + id.value() + "'")).isEqualTo("RESOLVED|" + correcting);
    }

    @Test
    @DisplayName("the case file is insert-only and the break is never deleted - for the application role")
    void theCaseFileIsInsertOnly() throws Exception {
        AccountingBreakId id = raisedBreak();
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            for (String refused : List.of(
                    "UPDATE accounting.break_event SET reason = 'x' WHERE break_id = '" + id.value() + "'",
                    "DELETE FROM accounting.break_event WHERE break_id = '" + id.value() + "'",
                    "DELETE FROM accounting.accounting_break WHERE id = '" + id.value() + "'",
                    "UPDATE accounting.accounting_break SET subject_ref = 'moved' WHERE id = '" + id.value() + "'")) {
                assertThatThrownBy(() -> statement.executeUpdate(refused)).as(refused)
                        .isInstanceOfSatisfying(SQLException.class, failure ->
                                assertThat(failure.getSQLState()).as(refused).isEqualTo("42501"));
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

    private AccountingChecks checks() {
        return new AccountingChecks(new GeneralLedger(new LedgerJournalReads()),
                new GlPins(new JdbcGlMappingStore(), new JdbcPeriodPins()), new LedgerJournalShapes(), proofs,
                PostingRuleRegister.CURRENT, breaks(), snapshots::read, AccountingBreakDatabaseTest::inTransaction);
    }

    private static AccountingBreaks breaks() {
        return new AccountingBreaks(new JdbcAccountingBreakStore(), new LedgerJournalReads(), new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, CLOCK);
    }

    /** A break raised in this case's month, by the system - its id. */
    private AccountingBreakId raisedBreak() throws SQLException {
        BreakFinding finding = new BreakFinding(CloseCheckCode.RULE_CONFORMANCE, BreakType.SHAPE_MISMATCH,
                "shape:" + IDS.next(), "planted", "posting-rules:v2");
        boolean raised = inTransaction(uow -> breaks().raise(uow, month, finding, Actor.SYSTEM, correlation()));
        assertThat(raised).isTrue();
        return AccountingBreakId.of(UUID.fromString(breakOf(finding.subject())));
    }

    /** One break, one RAISED case-file row, one outbox event and one audit record for {@code breakId}. */
    private static void assertOnce(String breakId) throws SQLException {
        assertThat(count("SELECT count(*) FROM accounting.break_event WHERE break_id = '" + breakId
                + "' AND act = 'RAISED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '"
                + AccountingBreaks.RAISED_EVENT + "' AND aggregate_id = '" + breakId + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.BreakRaised'"
                + " AND target_id = '" + breakId + "'")).isEqualTo(1);
    }

    private String breakOf(String subject) throws SQLException {
        return column("SELECT id::text FROM accounting.accounting_break WHERE period_start = '" + month.atDay(1)
                + "' AND subject_ref = '" + subject.replace("'", "''") + "'");
    }

    private LedgerAccount wallet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount account = accounts.createOrConverge(app, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                    AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next())).account();
            app.commit();
            return account;
        }
    }

    private LedgerAccount operational(AccountPurpose purpose) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return accounts.findOperational(app, purpose, EUR).orElseThrow();
        }
    }

    /** One balanced entry keyed {@code key}, dated in this case's month, through the ledger's own door - its id. */
    private UUID post(String key, LedgerAccount debit, LedgerAccount credit) throws SQLException {
        LocalDate day = month.atDay(1 + RANDOM.nextInt(28));
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            UUID entry = new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK,
                    Duration.ofDays(1), Duration.ofMinutes(5)), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(),
                    new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE)
                    .post(app, new PostingCommand(key, day, day, "accounting-breaks", List.of(
                            new JournalLine(debit.id(), Direction.DEBIT, Money.ofMinorUnits(1234, EUR)),
                            new JournalLine(credit.id(), Direction.CREDIT, Money.ofMinorUnits(1234, EUR)))))
                    .entryId().value();
            app.commit();
            return entry;
        }
    }

    private static <R> R inTransaction(Function<Connection, R> work) {
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

    private static <R> R inScope(Actor actor, Function<Connection, R> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return inTransaction(work);
        }
    }

    private static Actor person() {
        return new Actor("operator-" + UUID.randomUUID(), ActorType.CUSTOMER);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String column(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).as("one row from %s", sql).isTrue();
            return row.getString(1);
        }
    }
}
