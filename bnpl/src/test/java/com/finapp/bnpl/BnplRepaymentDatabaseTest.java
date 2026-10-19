package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.count;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.correlation;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.customer;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.employee;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.inOneTransaction;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.pid;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.refused;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PurposeBalances;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-rep and the closure against a real database (`P12-TSK-018`; {@code bnpl V010}; {@code INV-BNPL-01},
 * {@code INV-LND-04} and {@code INV-LND-08} by analogy, {@code INV-IDEM-01}, {@code INV-CON-01}, {@code INV-LIFE-04}):
 * {@link BnplRepayments} and {@link BnplServicing} over bnpl's own JDBC stores, on a database clock moved by
 * {@link BnplClockFrame} - scenario 6 (repayments racing on one obligation serialise on B4 and conserve), one key once,
 * ten keys conserved, the repayment racing the billing, the closure from both zeroing transactions, the unaccrued day, the
 * floor - and {@code bnpl V010}'s ranks for every writer: the allocation's target, its billed-only rank, no component
 * over-paid at commit, the closure's rows' side, its tie to {@code CLOSED} and the edge only beside it. The ledger is the
 * in-memory book of {@link BnplApplicationFakes} behind one lock (the real postings, keys and balances are {@code app}'s,
 * {@code BnplRepaymentApiTest}); credit, checkout, merchant, capital and the wallet scripted as
 * {@link BnplAcceptanceDatabaseTest}'s.
 *
 * <p>Written by `P12-TSK-018` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@DisplayName("the customer's repayment and the closure against bnpl V010's ranks (P12-TSK-018)")
class BnplRepaymentDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = BnplTermsFixtures.EUR;
    private static final JdbcBnplOfferStore OFFERS = new JdbcBnplOfferStore();
    private static final JdbcBnplPurchaseStore PURCHASES = new JdbcBnplPurchaseStore();
    private static final JdbcBnplServicingStore SERVICING = new JdbcBnplServicingStore();
    private static final JdbcBnplRepaymentStore REPAYMENTS = new JdbcBnplRepaymentStore();
    private static final JdbcBnplTermsStore TERMS_STORE = new JdbcBnplTermsStore();

    @BeforeAll
    static void termsAndClock() {
        BnplClockFrame.install();
        BnplTermsAdministration terms = new BnplTermsAdministration(TERMS_STORE, new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, CLOCK, BnplTermsFixtures.BOUNDS);
        for (BnplTerms wanted : List.of(BnplTermsFixtures.payIn4().build(), BnplTermsFixtures.payInN("0.1999").build())) {
            if (inOneTransaction(uow -> TERMS_STORE.active(uow, wanted.product())).isEmpty()) {
                BnplTermsAdministration.Proposed proposed = inOneTransaction(uow -> terms.propose(uow, wanted,
                        "the repayment suite's terms", employee(), correlation()));
                inOneTransaction(uow -> terms.approve(uow, proposed.id(), employee(), "in force", correlation()));
            }
        }
    }

    // ------------------------------------------------------------------ scenario 6 and the key

    @Test
    @DisplayName("scenario 6: two repayments on one instalment, concurrently, serialise on B4 - each allocates against"
            + " what it finds: Σ allocations = Σ amounts, the instalment paid exactly once, the rest held, no component"
            + " over-paid")
    void twoRepaymentsSerialiseAndConserve() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceOn(due, bought);
        List<BnplRepayments.Repaid> repaid = world.concurrently(2, index -> world.repayOn(due, bought, "two-" + index,
                Money.ofMinorUnits(100_00, EUR)));
        assertThat(repaid).hasSize(2).noneMatch(BnplRepayments.Repaid::replayed);
        assertConserved(bought, 200_00);
        assertThat(scalar("SELECT sum(l.amount_minor)::text FROM bnpl.bnpl_repayment_allocation l JOIN bnpl.bnpl_repayment r"
                + " ON r.id = l.repayment_id WHERE r.obligation_id = '" + bought.id() + "' AND r.kind = 'CUSTOMER'"
                + " AND l.target = 'DUE_ITEM'")).isEqualTo("15000");
        assertThat(world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_CREDIT_BALANCE))
                .isEqualTo(Money.ofMinorUnits(50_00, EUR));
    }

    @Test
    @DisplayName("the same repayment twice - one key, two instances at once - repays once: one row, one entry, the second"
            + " converges on the first (INV-IDEM-01)")
    void theSameRepaymentTwiceRepaysOnce() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceOn(due, bought);
        List<BnplRepayments.Repaid> repaid = world.concurrently(2, index -> world.repayOn(due, bought, "once",
                Money.ofMinorUnits(60_00, EUR)));
        assertThat(repaid).extracting(BnplRepayments.Repaid::replayed).containsExactlyInAnyOrder(true, false);
        assertThat(repaid.get(0).repayment().id()).isEqualTo(repaid.get(1).repayment().id());
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'CUSTOMER'")).isEqualTo(1);
        assertThat(world.book.keys()).filteredOn(key -> key.startsWith(BnplRepayments.POSTING_KEY_PREFIX)).hasSize(2);
        assertThatThrownBy(() -> world.repayOn(due, bought, "once", Money.ofMinorUnits(61_00, EUR)))
                .isInstanceOf(BnplRepayments.RepaymentKeyReused.class);
    }

    @Test
    @DisplayName("ten keys on one obligation from ten threads serialise and conserve: Σ allocations = Σ amounts, the due"
            + " item paid once in full, the rest held, never over-allocated")
    void tenKeysOneObligationConserve() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceOn(due, bought);
        List<BnplRepayments.Repaid> repaid = world.concurrently(10, index -> world.repayOn(due, bought, "ten-" + index,
                Money.ofMinorUnits(20_00, EUR)));
        assertThat(repaid).hasSize(10).noneMatch(BnplRepayments.Repaid::replayed);
        assertConserved(bought, 200_00);
        assertThat(world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_PRINCIPAL_DUE)).isEqualTo(Money.zero(EUR));
        assertThat(world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_CREDIT_BALANCE))
                .isEqualTo(Money.ofMinorUnits(50_00, EUR));
    }

    // ------------------------------------------------------------------ allocation

    @Test
    @DisplayName("a partial repayment leaves the remainder due: one DUE_ITEM PRINCIPAL line on instalment 2, value-dated"
            + " the business date")
    void partialLeavesTheRemainderDue() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceOn(due, bought);
        BnplRepayments.Repaid repaid = world.repayOn(due.plusDays(1), bought, "partial", Money.ofMinorUnits(100_00, EUR));
        assertThat(scalar("SELECT value_date || '|' || amount_minor FROM bnpl.bnpl_repayment WHERE id = '"
                + repaid.repayment().id() + "'")).isEqualTo(due.plusDays(1) + "|10000");
        assertThat(scalar("SELECT string_agg(target || ':' || coalesce(component, '-') || ':' || amount_minor, ','"
                + " ORDER BY sequence) FROM bnpl.bnpl_repayment_allocation WHERE repayment_id = '" + repaid.repayment().id()
                + "'")).isEqualTo("DUE_ITEM:PRINCIPAL:10000");
        assertThat(scalar("SELECT (bnpl.billing_component_billed(b.id, 'PRINCIPAL') - bnpl.billing_component_paid(b.id,"
                + " 'PRINCIPAL'))::text FROM bnpl.bnpl_billing b WHERE b.instalment_id = '"
                + bought.schedule().instalments().get(1).id() + "'")).isEqualTo("5000");
    }

    @Test
    @DisplayName("more than is due goes to the credit balance, never to principal not yet due, and is applied at the next"
            + " billing's birth")
    void overGoesToTheCreditBalanceAndIsAppliedAtTheNextBilling() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceOn(due, bought);
        BnplRepayments.Repaid repaid = world.repayOn(due, bought, "over", Money.ofMinorUnits(200_00, EUR));
        assertThat(scalar("SELECT string_agg(target || ':' || amount_minor, ',' ORDER BY sequence) FROM"
                + " bnpl.bnpl_repayment_allocation WHERE repayment_id = '" + repaid.repayment().id() + "'"))
                .isEqualTo("DUE_ITEM:15000,CREDIT_BALANCE:5000");
        assertThat(world.serviceOn(due.plusDays(14), bought).appliedAtBilling()).isEqualTo(1);
        assertThat(scalar("SELECT amount_minor::text FROM bnpl.bnpl_credit_balance_application WHERE obligation_id = '"
                + bought.id() + "'")).isEqualTo("5000");
    }

    @Test
    @DisplayName("a repayment racing the billing on the due date conserves: whichever B4 admits first, the instalment is"
            + " paid once and nothing is over-allocated")
    void aRepaymentRacingTheBillingOnTheDueDateConserves() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> billing = pool.submit(() -> {
                start.await();
                return world.serviceOn(due, bought);
            });
            Future<?> repayment = pool.submit(() -> {
                start.await();
                return world.repayOn(due, bought, "race", Money.ofMinorUnits(150_00, EUR));
            });
            start.countDown();
            billing.get(60, TimeUnit.SECONDS);
            repayment.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        if (count("SELECT count(*) FROM bnpl.bnpl_credit_balance_application WHERE obligation_id = '" + bought.id()
                + "'") == 0) {
            // The repayment came after the billing and paid it.
            assertThat(world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_CREDIT_BALANCE))
                    .isEqualTo(Money.zero(EUR));
        }
        assertThat(world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_PRINCIPAL_DUE)).isEqualTo(Money.zero(EUR));
        assertConserved(bought, 150_00);
    }

    // ------------------------------------------------------------------ the closure

    @Test
    @DisplayName("the last repayment closes and refunds: the closure row naming it, the refund's entry, the obligation"
            + " CLOSED (REPAID) with its instant, its history row and BnplAgreementClosed - one commit")
    void theLastRepaymentClosesAndRefunds() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate last = bought.purchaseDate().plusDays(42);
        world.serviceOn(last, bought);
        BnplRepayments.Repaid repaid = world.repayOn(last, bought, "last", Money.ofMinorUnits(480_00, EUR));
        assertThat(repaid.closure()).isPresent();
        assertThat(scalar("SELECT reason || '|' || refund_minor || '|' || (refund_journal_entry_id IS NOT NULL) || '|'"
                + " || (repayment_id = '" + repaid.repayment().id() + "') FROM bnpl.bnpl_obligation_closure WHERE"
                + " obligation_id = '" + bought.id() + "'")).isEqualTo("REPAID|3000|true|true");
        assertThat(scalar("SELECT status || '|' || closure_reason || '|' || (closed_at IS NOT NULL) FROM"
                + " bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'")).isEqualTo("CLOSED|REPAID|true");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_obligation_event WHERE obligation_id = '" + bought.id()
                + "' AND to_status = 'CLOSED' AND reason = 'REPAID'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox WHERE aggregate_id = '" + bought.id()
                + "' AND event_type = 'bnpl.BnplAgreementClosed'")).isEqualTo(1);
        assertThat(world.book.keys()).contains(BnplClosures.REFUND_KEY_PREFIX + bought.id());
    }

    @Test
    @DisplayName("a credit balance that settles the obligation closes it at servicing - the last billing's application"
            + " pays it, the step closes it, no repayment named")
    void aCreditBalanceThatSettlesTheObligationClosesAtServicing() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.repayOn(bought.purchaseDate().plusDays(1), bought, "ahead", Money.ofMinorUnits(500_00, EUR));
        assertThat(world.serviceOn(bought.purchaseDate().plusDays(28), bought).closed()).isFalse();
        assertThat(world.serviceOn(bought.purchaseDate().plusDays(42), bought).closed()).isTrue();
        assertThat(scalar("SELECT (repayment_id IS NULL) || '|' || refund_minor FROM bnpl.bnpl_obligation_closure WHERE"
                + " obligation_id = '" + bought.id() + "'")).isEqualTo("true|5000");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "' AND status ="
                + " 'CLOSED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a pay-in-N obligation repaid in full two days after its final due date closes at once: every date of the"
            + " accrual span accrued (nothing accrues at or after the final due date, P12-TSK-017) - the closure's accrual"
            + " test over real rows; the unaccrued-day case is BnplRepaymentsTest#anObligationIsNotClosedOverAnUnaccruedDay")
    void aPnRepaidAfterItsFinalDueDateCloses() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        List<BnplPurchaseStore.StoredInstalment> instalments = bought.schedule().instalments();
        LocalDate last = instalments.get(instalments.size() - 1).dueDate();
        world.accrueThrough(bought, last.minusDays(1), 10);
        assertThat(world.serviceOn(last, bought).billed()).isEqualTo(instalments.size());
        Money owed = world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_PRINCIPAL_DUE)
                .plus(world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_INTEREST_DUE));
        BnplRepayments.Repaid repaid = world.repayOn(last.plusDays(2), bought, "all", owed);
        assertThat(repaid.closure()).isPresent();
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'"))
                .isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("a closed obligation refuses a repayment - ObligationClosed, nothing posted; a raw repayment, a permit move"
            + " and a second closure (its obligation no longer ACTIVE) are refused for every writer (INV-LIFE-04)")
    void aClosedObligationRefuses() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate last = bought.purchaseDate().plusDays(42);
        world.serviceOn(last, bought);
        world.repayOn(last, bought, "last", Money.ofMinorUnits(450_00, EUR));
        int keys = world.book.keys().size();
        assertThatThrownBy(() -> world.repayOn(last.plusDays(1), bought, "after", Money.ofMinorUnits(10_00, EUR)))
                .isInstanceOf(BnplRepayments.ObligationClosed.class);
        assertThat(world.book.keys()).hasSize(keys);
        String where = " WHERE id = '" + bought.id() + "'";
        try (Connection application = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            application.setAutoCommit(false);
            migrator.setAutoCommit(false);
            refused(application, "UPDATE bnpl.bnpl_obligation SET next_servicing_at = clock_timestamp()" + where, "P0001");
            refused(migrator, "UPDATE bnpl.bnpl_obligation SET status = 'ACTIVE', closure_reason = NULL, closed_at ="
                    + " NULL" + where, "P0001");
            refused(migrator, "INSERT INTO bnpl.bnpl_repayment (id, obligation_id, kind, request_key, currency, scale,"
                    + " amount_minor, agreement_id, agreement_version, allocation_engine, journal_entry_id, value_date,"
                    + " received_at) SELECT gen_random_uuid(), o.id, 'CUSTOMER', 'raw', 'EUR', 2, 100, o.agreement_id, 1,"
                    + " 'ALLOCATION_ENGINE_V1', gen_random_uuid(), current_date, clock_timestamp() FROM"
                    + " bnpl.bnpl_obligation o" + where.replace("id =", "o.id ="), "P0001");
            refused(migrator, "INSERT INTO bnpl.bnpl_obligation_closure (id, obligation_id, reason, currency, scale,"
                    + " refund_minor, created_at, correlation_id) VALUES (gen_random_uuid(), '" + bought.id() + "',"
                    + " 'REPAID', 'EUR', 2, 0, clock_timestamp(), 'raw')", "P0001");
        }
    }

    @Test
    @DisplayName("the closure's ranks for every writer: CLOSED without its closure refused; a closure with a billed item"
            + " unpaid refused; a closure whose obligation is not CLOSED at commit refused")
    void theClosuresRanksHoldForEveryWriter() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.serviceOn(bought.purchaseDate().plusDays(42), bought);
        String where = " WHERE id = '" + bought.id() + "'";
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            refused(migrator, "UPDATE bnpl.bnpl_obligation SET status = 'CLOSED', closure_reason = 'REPAID'" + where,
                    "P0001");
            refused(migrator, "INSERT INTO bnpl.bnpl_obligation_closure (id, obligation_id, reason, currency, scale,"
                    + " refund_minor, created_at, correlation_id) VALUES (gen_random_uuid(), '" + bought.id() + "',"
                    + " 'REPAID', 'EUR', 2, 0, clock_timestamp(), 'raw')", "P0001");
        }
    }

    @Test
    @DisplayName("an allocation's ranks for every writer: no not-due principal (23514), no unbilled instalment (P0001), no"
            + " component paid beyond its billing at commit, no AUTO_COLLECTION or PAYOFF kind before its task")
    void theAllocationsRanksHoldForEveryWriter() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceOn(due, bought);
        BnplRepayments.Repaid repaid = world.repayOn(due, bought, "ranks", Money.ofMinorUnits(10_00, EUR));
        UUID unbilled = bought.schedule().instalments().get(3).id();
        UUID billed = bought.schedule().instalments().get(1).id();
        try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(), world.frameOn(due))) {
            refused(migrator, "INSERT INTO bnpl.bnpl_repayment_allocation (id, repayment_id, sequence, target, currency,"
                    + " scale, amount_minor) VALUES (gen_random_uuid(), '" + repaid.repayment().id()
                    + "', 9, 'NOT_DUE_PRINCIPAL', 'EUR', 2, 1)", "23514");
            refused(migrator, "INSERT INTO bnpl.bnpl_repayment_allocation (id, repayment_id, sequence, target,"
                    + " instalment_id, component, currency, scale, amount_minor) VALUES (gen_random_uuid(), '"
                    + repaid.repayment().id() + "', 9, 'DUE_ITEM', '" + unbilled + "', 'PRINCIPAL', 'EUR', 2, 1)", "P0001");
            refused(migrator, "INSERT INTO bnpl.bnpl_repayment (id, obligation_id, kind, request_key, currency, scale,"
                    + " amount_minor, agreement_id, agreement_version, allocation_engine, journal_entry_id, value_date,"
                    + " received_at) SELECT gen_random_uuid(), o.id, 'AUTO_COLLECTION', 'raw', 'EUR', 2, 100,"
                    + " o.agreement_id, 1, 'ALLOCATION_ENGINE_V1', gen_random_uuid(), DATE '" + due + "',"
                    + " clock_timestamp() FROM bnpl.bnpl_obligation o WHERE o.id = '" + bought.id() + "'", "P0001");
        }
        // Over-paying instalment 2 at commit: a raw repayment of 145.00 + 1 cent on its 140.00 still due.
        try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(), world.frameOn(due))) {
            migrator.setAutoCommit(false);
            UUID raw = UUID.randomUUID();
            try (Statement insert = migrator.createStatement()) {
                insert.execute("INSERT INTO bnpl.bnpl_repayment (id, obligation_id, kind, request_key, currency, scale,"
                        + " amount_minor, agreement_id, agreement_version, allocation_engine, journal_entry_id, value_date,"
                        + " received_at) SELECT '" + raw + "', o.id, 'CUSTOMER', 'raw-over', 'EUR', 2, 14001,"
                        + " o.agreement_id, 1, 'ALLOCATION_ENGINE_V1', gen_random_uuid(), DATE '" + due + "',"
                        + " clock_timestamp() FROM bnpl.bnpl_obligation o WHERE o.id = '" + bought.id() + "'");
                insert.execute("INSERT INTO bnpl.bnpl_repayment_allocation (id, repayment_id, sequence, target,"
                        + " instalment_id, component, currency, scale, amount_minor) VALUES (gen_random_uuid(), '" + raw
                        + "', 1, 'DUE_ITEM', '" + billed + "', 'PRINCIPAL', 'EUR', 2, 14001)");
            }
            assertThatThrownBy(migrator::commit).isInstanceOf(SQLException.class)
                    .hasMessageContaining("no billed component is ever paid beyond what it billed");
        }
    }

    // ------------------------------------------------------------------ the wallet, the floor, the lock

    @Test
    @DisplayName("insufficient funds post nothing - no row, no entry, the claim's key free again")
    void insufficientFundsPostsNothing() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceOn(due, bought);
        world.fakes.book.funds.put(world.ports.wallet, Money.ofMinorUnits(0, EUR));
        int keys = world.book.keys().size();
        assertThatThrownBy(() -> world.repayOn(due, bought, "short", Money.ofMinorUnits(1_000_000_00L, EUR)))
                .isInstanceOf(BnplRepayments.InsufficientFunds.class);
        assertThat(world.book.keys()).hasSize(keys);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'CUSTOMER'")).isZero();
    }

    @Test
    @DisplayName("a suspended customer may still repay - the repayment asks no standing")
    void aSuspendedCustomerMayStillRepay() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.standing = false;
        assertThat(world.repayOn(bought.purchaseDate().plusDays(1), bought, "suspended", Money.ofMinorUnits(10_00, EUR))
                .replayed()).isFalse();
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'CUSTOMER'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the repayment is floored: a clock stepped back across midnight values it at the latest billing's date;"
            + " a raw repayment below the floor is refused")
    void theRepaymentIsFloored() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        Duration justAfter = BnplClockFrame.frameTo(due.atStartOfDay(ZoneOffset.UTC).toInstant().plusMillis(500));
        world.serviceAt(justAfter, bought);
        BnplRepayments.Repaid repaid = BnplClockFrame.inOneTransaction(justAfter, uow -> {
            BnplClockFrame.stepBack(uow, Duration.ofSeconds(2));
            return world.repayments.repay(uow, bought.agreement(), bought.party(),
                    new BnplRepayments.Request("behind", Money.ofMinorUnits(10_00, EUR)), customer(bought.party()),
                    correlation());
        });
        assertThat(repaid.repayment().valueDate()).isEqualTo(due);
        try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(), world.frameOn(due))) {
            refused(migrator, "INSERT INTO bnpl.bnpl_repayment (id, obligation_id, kind, request_key, currency, scale,"
                    + " amount_minor, agreement_id, agreement_version, allocation_engine, journal_entry_id, value_date,"
                    + " received_at) SELECT gen_random_uuid(), o.id, 'CUSTOMER', 'below', 'EUR', 2, 100, o.agreement_id,"
                    + " 1, 'ALLOCATION_ENGINE_V1', gen_random_uuid(), DATE '" + due.minusDays(1) + "', clock_timestamp()"
                    + " FROM bnpl.bnpl_obligation o WHERE o.id = '" + bought.id() + "'", "P0001");
        }
    }

    @Test
    @DisplayName("exposure and headroom fall by the principal repaid, at commit - what is held never counts")
    void exposureAndHeadroomFallByThePrincipalRepaid() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceOn(due, bought);
        Money outstanding = world.fakes.book.bnplPrincipal();
        world.repayOn(due, bought, "exposure", Money.ofMinorUnits(200_00, EUR));
        assertThat(world.fakes.book.bnplPrincipal()).isEqualTo(outstanding.minus(Money.ofMinorUnits(150_00, EUR)));
    }

    @Test
    @DisplayName("the due state is derived from the rows explained by the ledger: a stray due-account line is an anomaly,"
            + " nothing allocated or written")
    void theDueStateIsDerivedFromRowsNotTheProjection() throws SQLException {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceOn(due, bought);
        LedgerAccount principalDue = world.account(bought, com.finapp.ledger.AccountPurpose.BNPL_PRINCIPAL_DUE);
        synchronized (world.book) {
            world.fakes.book.lines.add(new BnplApplicationFakes.Book.Line("fixture:stray", principalDue.id(),
                    Direction.DEBIT, Money.ofMinorUnits(1_00, EUR), due, due));
            world.fakes.book.lines.add(new BnplApplicationFakes.Book.Line("fixture:stray", world.ports.wallet,
                    Direction.CREDIT, Money.ofMinorUnits(1_00, EUR), due, due));
        }
        assertThatThrownBy(() -> world.repayOn(due, bought, "drift", Money.ofMinorUnits(151_00, EUR)))
                .isInstanceOf(AllocationAnomaly.class);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'CUSTOMER'")).isZero();
    }

    @Test
    @DisplayName("the repayment takes the obligation first (B4): another transaction holding the row, the repayment waits"
            + " on bnpl_obligation, then repays once")
    void repaymentTakesTheObligationThenTheWallet() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceOn(due, bought);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("SELECT 1 FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "' FOR UPDATE");
            }
            Future<BnplRepayments.Repaid> repaying = pool.submit(() -> world.repayOn(due, bought, "waits",
                    Money.ofMinorUnits(10_00, EUR)));
            assertThat(BnplAcceptanceDatabaseTest.awaitBlockedBy(pid(holder))).contains("bnpl_obligation");
            holder.rollback();
            assertThat(repaying.get(30, TimeUnit.SECONDS).replayed()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Σ CUSTOMER allocations = Σ CUSTOMER amounts = {@code expectedMinor}; no billed component over-paid. */
    private static void assertConserved(Purchased bought, long expectedMinor) throws SQLException {
        assertThat(scalar("SELECT (SELECT sum(amount_minor) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'CUSTOMER') || '|' || (SELECT sum(l.amount_minor) FROM bnpl.bnpl_repayment_allocation l"
                + " JOIN bnpl.bnpl_repayment r ON r.id = l.repayment_id WHERE r.obligation_id = '" + bought.id()
                + "' AND r.kind = 'CUSTOMER')")).isEqualTo(expectedMinor + "|" + expectedMinor);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing b CROSS JOIN (VALUES ('FEES'), ('INTEREST'),"
                + " ('PRINCIPAL')) AS c (component) WHERE b.obligation_id = '" + bought.id() + "' AND"
                + " bnpl.billing_component_paid(b.id, c.component) > bnpl.billing_component_billed(b.id, c.component)"))
                .isZero();
    }

    /** One purchased obligation - its id, party, purchase date, schedule version 1 and agreement. */
    record Purchased(BnplObligationId obligation, UUID party, LocalDate purchaseDate,
            BnplPurchaseStore.StoredSchedule schedule, BnplAgreementId agreement) {

        String id() {
            return obligation.value().toString();
        }
    }

    /** A step of a concurrent race. */
    @FunctionalInterface
    interface Racer<R> {
        R run(int index) throws Exception;
    }

    /** The suite's world: the scripted ports, the in-memory book behind one lock, T-pur, T-svc and T-rep over JDBC. */
    private static final class World {

        final BnplAcceptanceDatabaseTest.Scripted ports = new BnplAcceptanceDatabaseTest.Scripted();
        final BnplApplicationFakes fakes = new BnplApplicationFakes();
        final SharedBook book = new SharedBook(fakes.book);
        volatile boolean standing = true;
        final BnplClosures closures = new BnplClosures(REPAYMENTS, SERVICING, PURCHASES, ports, book,
                new JdbcOutboxWriter(), IDS, CLOCK);
        final BnplRepayments repayments = new BnplRepayments(REPAYMENTS, SERVICING, PURCHASES, TERMS_STORE, ports, book,
                closures, new JdbcOutboxWriter(), IDS, CLOCK);
        final BnplServicing servicing = new BnplServicing(SERVICING, PURCHASES, TERMS_STORE, book, closures,
                new BnplCollections(new JdbcBnplCollectionStore(), repayments, PURCHASES, ports, book,
                        new JdbcOutboxWriter(), IDS, CLOCK),
                new JdbcOutboxWriter(), IDS, CLOCK);
        private final BnplAcceptances acceptances = new BnplAcceptances(OFFERS, TERMS_STORE, PURCHASES, ports,
                (uow, party) -> standing, ports, ports, ports, ports, book, new JdbcOutboxWriter(), new JdbcAuditWriter(),
                IDS, CLOCK);

        World() {
            fakes.book.external("payable", ports.payable.value());
            fakes.book.external("wallet", ports.wallet.value());
            fakes.book.funds.put(ports.wallet, Money.ofMinorUnits(1_000_000_00L, EUR));
        }

        Purchased purchase(BnplProduct product, BnplPlan plan) {
            BnplAcceptanceDatabaseTest.Offered offered = BnplAcceptanceDatabaseTest.offered(product, plan);
            ports.session = offered.session();
            ports.merchant = offered.merchant();
            String hash = inOneTransaction(uow -> OFFERS.terms(uow, offered.offer()).orElseThrow().termsSha256Hex());
            BnplAcceptances.Accepted accepted = inOneTransaction(uow -> acceptances.accept(uow, offered.offer(),
                    offered.party(), new BnplAcceptances.Acceptance(hash, new BnplAcceptanceEvidence(UUID.randomUUID(),
                            UUID.randomUUID(), "MULTI_FACTOR", Optional.empty(), Optional.empty())),
                    customer(offered.party()), Correlation.startingWith(correlation())));
            BnplPurchaseStore.StoredSchedule schedule = inOneTransaction(uow -> PURCHASES.schedule(uow,
                    accepted.obligation().id(), 1)).orElseThrow();
            return new Purchased(accepted.obligation().id(), offered.party(), accepted.purchase().businessDate(),
                    schedule, accepted.agreement().id());
        }

        Duration frameOn(LocalDate date) {
            return BnplClockFrame.frameTo(date.atTime(12, 0).toInstant(ZoneOffset.UTC));
        }

        @SuppressWarnings("try") // The SecurityContext Scope is used for its close side effect (the idiom).
        ServicingOutcome serviceOn(LocalDate date, Purchased bought) {
            return serviceAt(frameOn(date), bought);
        }

        @SuppressWarnings("try")
        ServicingOutcome serviceAt(Duration frame, Purchased bought) {
            try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
                return BnplClockFrame.inOneTransaction(frame, uow -> servicing.service(uow, bought.obligation()));
            }
        }

        BnplRepayments.Repaid repayOn(LocalDate date, Purchased bought, String key, Money amount) {
            return BnplClockFrame.inOneTransaction(frameOn(date), uow -> repayments.repay(uow, bought.agreement(),
                    bought.party(), new BnplRepayments.Request(key, amount), customer(bought.party()), correlation()));
        }

        <R> List<R> concurrently(int racers, Racer<R> racer) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<R>> running = new ArrayList<>();
                for (int index = 0; index < racers; index++) {
                    int which = index;
                    running.add(pool.submit((Callable<R>) () -> {
                        start.await();
                        return racer.run(which);
                    }));
                }
                start.countDown();
                List<R> results = new ArrayList<>();
                for (Future<R> result : running) {
                    results.add(result.get(60, TimeUnit.SECONDS));
                }
                return results;
            } finally {
                pool.shutdownNow();
            }
        }

        LedgerAccount account(Purchased bought, com.finapp.ledger.AccountPurpose purpose) {
            return book.accountsOf(null, bought.obligation()).stream().filter(account -> account.purpose() == purpose)
                    .findFirst().orElseThrow();
        }

        Money balance(Purchased bought, com.finapp.ledger.AccountPurpose purpose) {
            return book.balanceOf(null, account(bought, purpose));
        }

        /** The owner-role accrual fixture: {@code perDayMinor} on every date from the purchase's through {@code last}. */
        void accrueThrough(Purchased bought, LocalDate last, long perDayMinor) throws SQLException {
            List<BnplPurchaseStore.StoredInstalment> instalments = bought.schedule().instalments();
            try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(), frameOn(last.plusDays(1)));
                    Statement insert = migrator.createStatement()) {
                long cumulative = 0;
                LocalDate periodStart = bought.purchaseDate();
                int period = 0;
                for (LocalDate day = bought.purchaseDate(); !day.isAfter(last); day = day.plusDays(1)) {
                    while (!day.isBefore(instalments.get(period).dueDate())) {
                        periodStart = instalments.get(period).dueDate();
                        period++;
                    }
                    LocalDate end = instalments.get(period).dueDate();
                    cumulative = day.equals(periodStart) ? perDayMinor : cumulative + perDayMinor;
                    insert.execute("INSERT INTO bnpl.bnpl_interest_accrual (id, obligation_id, accrual_date, period_start,"
                            + " period_end, currency, scale, base_minor, cumulative_exact, posted_minor, agreement_id,"
                            + " agreement_version, accrual_engine, journal_entry_id, created_at, correlation_id) VALUES"
                            + " (gen_random_uuid(), '" + bought.id() + "', DATE '" + day + "', DATE '" + periodStart
                            + "', DATE '" + end + "', 'EUR', 2, 60000, " + cumulative + ", " + perDayMinor + ", '"
                            + bought.agreement().value() + "', 1, 'ACCRUAL_ENGINE_V1', gen_random_uuid(),"
                            + " clock_timestamp(), 'owner-role fixture')");
                    synchronized (book) {
                        fakes.servicing.accrue(bought.obligation(), day, Money.ofMinorUnits(perDayMinor, EUR));
                    }
                }
                migrator.commit();
            }
        }
    }

    /** The in-memory book behind one lock - the suite's racers run on threads of their own. */
    private static final class SharedBook implements BnplBook {

        private final BnplApplicationFakes.Book delegate;

        SharedBook(BnplApplicationFakes.Book delegate) {
            this.delegate = delegate;
        }

        synchronized List<String> keys() {
            return List.copyOf(delegate.keys);
        }

        @Override
        public synchronized BnplLedgerAccounts openAccounts(Connection unitOfWork, BnplObligationId obligation,
                CurrencyCode currency) {
            return delegate.openAccounts(unitOfWork, obligation, currency);
        }

        @Override
        public synchronized List<LedgerAccount> accountsOf(Connection unitOfWork, BnplObligationId obligation) {
            return delegate.accountsOf(unitOfWork, obligation);
        }

        @Override
        public synchronized LedgerAccountId operational(Connection unitOfWork, com.finapp.ledger.AccountPurpose purpose,
                CurrencyCode currency) {
            return delegate.operational(unitOfWork, purpose, currency);
        }

        @Override
        public synchronized Optional<LedgerAccountStatus> lockForUpdate(Connection unitOfWork, LedgerAccountId account) {
            return delegate.lockForUpdate(unitOfWork, account);
        }

        @Override
        public synchronized Money availableUnderLock(Connection unitOfWork, LedgerAccountId account) {
            return delegate.availableUnderLock(unitOfWork, account);
        }

        @Override
        public synchronized void lockBalancesInOrder(Connection unitOfWork, Collection<LedgerAccountId> accounts) {
            delegate.lockBalancesInOrder(unitOfWork, accounts);
        }

        @Override
        public synchronized JournalEntryId post(Connection unitOfWork, PostingCommand command) {
            return delegate.post(unitOfWork, command);
        }

        @Override
        public synchronized Money balanceOf(Connection unitOfWork, LedgerAccount account) {
            return delegate.balanceOf(unitOfWork, account);
        }

        @Override
        public synchronized List<PurposeBalances.DatedLine> datedLinesOn(Connection unitOfWork,
                Set<LedgerAccountId> accounts, LocalDate valueDateThrough) {
            return delegate.datedLinesOn(unitOfWork, accounts, valueDateThrough);
        }
    }
}
