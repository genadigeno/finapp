package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplRepayments;
import com.finapp.bnpl.BnplServicing;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.bnpl.CollectionOutcome;
import com.finapp.bnpl.ServicingOutcome;
import com.finapp.identity.Authorization;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanWallets;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeSchedules;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Auto-collection over the real ledger (`P12-TSK-019`; {@code PHASE_12_PLAN.md} sections 7.1 T-svc and 7.2; owner decision
 * B6; {@code INV-BAL-04}, {@code INV-IDEM-02}): the wired servicing step collects from the customer's wallet - the wallet's
 * real {@code ledger_account} row {@code FOR UPDATE} and its real available balance - and from nothing else, counted after
 * the commit; two obligations of one customer serialise on that one row, each bounded by what it finds; and the two lock
 * proofs earlier tasks left to the first collection:
 *
 * <ul>
 *   <li>`P12-TSK-017`'s: a pay-in-N step blocked at (P) on a wallet another transaction holds holds <strong>no</strong>
 *       currency-wide {@code BNPL_INTEREST_INCOME} projection row - T-acc committed alone before it - while it does hold
 *       its own obligation's rows (the probe's control);
 *   <li>`P12-TSK-018`'s: a customer's repayment queued behind a collection on one obligation completes without a
 *       {@code 40P01} - T-rep takes the obligation (B4) before the wallet (P), as T-svc does; the wallet first would close
 *       the cycle exactly here (the probe).
 * </ul>
 *
 * <p>Instalments are brought to today by a date-shifted owner-role fixture (the lending precedent,
 * {@code LoanAccountDatabaseTest#toFirstDueDate}): every dated row of the obligation and its entries moved back together.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it brings credit's BNPL policies and terms into force.
 * Written by `P12-TSK-019` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try") // The Scopes are used for their close side effect (the idiom).
@DisplayName("auto-collection from the wallet over the real ledger (P12-TSK-019)")
class BnplCollectionLedgerDatabaseTest {

    private static final long GROSS = 600_00L;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private BnplEnablements enablements;
    @Autowired private BnplPricing pricing;
    @Autowired private FeeSchedules feeSchedules;
    @Autowired private BnplTermsAdministration terms;
    @Autowired private BnplTransactions transactions;
    @Autowired private BnplOrigination origination;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanWallets loanWallets;
    @Autowired private BnplServicing servicing;
    @Autowired private BnplAccruals accruals;
    @Autowired private BnplRepayments repayments;
    @Autowired private BnplServicingMetrics servicingMetrics;
    @Autowired private BnplAccrualMetrics accrualMetrics;
    @Autowired private MeterRegistry meterRegistry;

    private BnplPurchaseWorld world;

    @BeforeEach
    void world() throws Exception {
        world = new BnplPurchaseWorld(port, authorization, enablements, pricing, feeSchedules, terms, transactions,
                origination, postingService, ledgerAccountStore, headroom, loanWallets).ready();
        world.headroomAtLeast(eur(100_000_00L));
    }

    @Test
    @DisplayName("the wired sweep collects a due instalment from the wallet alone: one COLLECTED attempt, one"
            + " AUTO_COLLECTION repayment under <instalment>:<date>, the wallet debited exactly the bill on the real"
            + " ledger, no payment intent or attempt anywhere; counted after the commit; a second sweep collects nothing")
    void theOnlySourceIsTheWallet() throws Exception {
        Bought bought = purchase(BnplApplicationClient::payIn4, 1_000_00);
        long walletBefore = BnplPurchaseWorld.creditBalanceOf(bought.wallet().value());
        toDueDate(bought, 2);
        long railBefore = BnplApplicationClient.count("SELECT (SELECT count(*) FROM payments.payment_intent)"
                + " + (SELECT count(*) FROM payments.payment_attempt)");
        double collectedBefore = collections("collected");
        double repaidBefore = autoRepayments("repaid");
        BnplServicingSchedule sweep = new BnplServicingSchedule(servicing, accruals, transactions, servicingMetrics,
                accrualMetrics, Duration.ofSeconds(5), 500, Duration.ofMinutes(15));
        while (sweep.sweepOnce() == 500) {
            // every due obligation serviced
        }
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_collection_attempt WHERE obligation_id ="
                + " ?::uuid AND outcome = 'COLLECTED' AND collected_minor = 15000", bought.obligationText())).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_repayment r JOIN bnpl.bnpl_collection_attempt"
                + " a ON a.repayment_id = r.id WHERE r.obligation_id = ?::uuid AND r.kind = 'AUTO_COLLECTION' AND"
                + " r.request_key = a.instalment_id || ':' || a.attempt_date", bought.obligationText())).isEqualTo(1);
        assertThat(BnplPurchaseWorld.creditBalanceOf(bought.wallet().value())).isEqualTo(walletBefore - 150_00);
        assertThat(BnplApplicationClient.count("SELECT (SELECT count(*) FROM payments.payment_intent)"
                + " + (SELECT count(*) FROM payments.payment_attempt)"))
                .as("no payment intent or attempt - the wallet only (B6)").isEqualTo(railBefore);
        assertThat(collections("collected")).as("counted after the commit").isEqualTo(collectedBefore + 1);
        assertThat(autoRepayments("repaid")).isEqualTo(repaidBefore + 1);
        sweep.sweepOnce();
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_collection_attempt WHERE obligation_id ="
                + " ?::uuid", bought.obligationText())).as("once a day, however often swept").isEqualTo(1);
    }

    @Test
    @DisplayName("two obligations of one customer collected at once serialise on the one wallet row: each bounded by the"
            + " available balance it finds under the lock - one COLLECTED, one PARTIAL for the rest, the wallet at zero,"
            + " never below (counted)")
    void twoObligationsOfOneCustomerSerialiseOnTheWallet() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(500_00));
        Bought one = purchaseFor(owner, BnplApplicationClient::payIn4);
        Bought two = purchaseFor(owner, BnplApplicationClient::payIn4);
        // 500 - 150 - 150 at the purchases: 200 left for two bills of 150.
        toDueDate(one, 2);
        toDueDate(two, 2);
        List<ServicingOutcome> steps = concurrently(List.of(one, two), bought -> serviceStep(bought.obligation()));
        assertThat(steps).hasSize(2);
        assertThat(BnplApplicationClient.scalar("SELECT string_agg(outcome || ':' || collected_minor, ',' ORDER BY"
                + " collected_minor DESC) FROM bnpl.bnpl_collection_attempt WHERE obligation_id IN (?::uuid, ?::uuid)",
                one.obligationText(), two.obligationText())).isEqualTo("COLLECTED:15000,PARTIAL:5000");
        assertThat(BnplPurchaseWorld.creditBalanceOf(one.wallet().value())).as("never below zero").isZero();
    }

    @Test
    @DisplayName("P12-TSK-017's proof on the real ledger: a pay-in-N step waiting at (P) on a wallet another transaction"
            + " holds holds no BNPL_INTEREST_INCOME projection row - T-acc committed alone before it - though it holds its"
            + " own obligation's rows; released, it collects")
    void aCollectionHoldsNoIncomeRowWhileItWaitsOnTheWallet() throws Exception {
        Bought bought = purchase(token -> BnplApplicationClient.payInN(token, 3), 2_000_00);
        toDueDate(bought, 1);
        // T-acc first, alone and committed - as the sweep runs it: the income row is posted to and released.
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            for (int call = 0; call < 8; call++) {
                if (transactions.inTransaction(uow -> accruals.accrue(uow, bought.obligation())).caughtUp()) {
                    break;
                }
            }
        }
        UUID income = transactions.inTransaction(uow -> ledgerAccountStore
                .findOperational(uow, AccountPurpose.BNPL_INTEREST_INCOME, BnplPurchaseWorld.EUR).orElseThrow().id()
                .value());
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM ledger.account_balance WHERE ledger_account_id ="
                + " ?::uuid", income.toString())).as("the income row exists - T-acc posted to it").isEqualTo(1);
        UUID principal = UUID.fromString(BnplApplicationClient.scalar("SELECT id::text FROM ledger.ledger_account WHERE"
                + " owner_ref = ?::uuid AND purpose = 'BNPL_PRINCIPAL'", bought.obligationText()));

        Connection holder = BnplPurchaseWorld.held();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            lockRow(holder, "SELECT 1 FROM ledger.ledger_account WHERE id = ?::uuid FOR UPDATE", bought.wallet().value());
            Future<ServicingOutcome> step = pool.submit(() -> serviceStep(bought.obligation()));
            awaitWaiters(1);
            assertThat(freeNow("ledger.account_balance", "ledger_account_id", income))
                    .as("no income row held across the wallet wait (the A2-F1 rule)").isTrue();
            assertThat(freeNow("ledger.account_balance", "ledger_account_id", principal))
                    .as("the control: the step holds its own obligation's projection rows (L8 before billing)").isFalse();
            holder.rollback();
            ServicingOutcome serviced = step.get(60, TimeUnit.SECONDS);
            assertThat(serviced.collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
        } finally {
            BnplPurchaseWorld.closeQuietly(holder);
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("P12-TSK-018's probe target: a customer's repayment queued behind the due date's collection on one"
            + " obligation completes without a 40P01 - both take the obligation (B4) before the wallet (P); the collection"
            + " takes the bill, the repayment is held as credit - conserved")
    void aRepaymentRacingACollectionNeverDeadlocks() throws Exception {
        Bought bought = purchase(BnplApplicationClient::payIn4, 1_000_00);
        toDueDate(bought, 2);
        Connection holder = BnplPurchaseWorld.held();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            lockRow(holder, "SELECT 1 FROM bnpl.bnpl_obligation WHERE id = ?::uuid FOR UPDATE", bought.obligation().value());
            // The collection queues first at B4, then the repayment: released, the collection holds B4 and goes for the
            // wallet - which a repayment that took the wallet before B4 would hold: the cycle the order forbids.
            Future<ServicingOutcome> step = pool.submit(() -> serviceStep(bought.obligation()));
            awaitWaiters(1);
            Future<BnplRepayments.Repaid> repaid = pool.submit(() -> repay(bought, "racing", 150_00));
            awaitWaiters(2);
            holder.rollback();
            assertThat(step.get(60, TimeUnit.SECONDS).collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
            assertThat(repaid.get(60, TimeUnit.SECONDS).repayment().allocations())
                    .extracting(line -> line.target()).containsExactly("CREDIT_BALANCE");
        } finally {
            BnplPurchaseWorld.closeQuietly(holder);
            pool.shutdownNow();
        }
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_billing b CROSS JOIN (VALUES ('FEES'),"
                + " ('INTEREST'), ('PRINCIPAL')) AS c (component) WHERE b.obligation_id = ?::uuid AND"
                + " bnpl.billing_component_paid(b.id, c.component) > bnpl.billing_component_billed(b.id, c.component)",
                bought.obligationText())).isZero();
        assertThat(BnplPurchaseWorld.obligationBalance(bought.obligationText(), AccountPurpose.BNPL_CREDIT_BALANCE))
                .as("the repayment held whole").isEqualTo(-150_00);
    }

    @Test
    @DisplayName("P12-TSK-020's proof on the real ledger: on the late fee's day a step waiting at (P) on the wallet - its"
            + " collection's - holds no BNPL_FEE_INCOME projection row (the fee's L8 comes after the wallet, the A2-F1"
            + " rule), though it holds its own obligation's rows; released, it records the failed collection, the"
            + " condition and the one late fee")
    void theFeeIncomeRowIsTakenAfterTheWallet() throws Exception {
        Bought bought = purchase(BnplApplicationClient::payIn4, 150_00);
        toAge(bought, 2, 5);
        UUID income = transactions.inTransaction(uow -> ledgerAccountStore
                .findOperational(uow, AccountPurpose.BNPL_FEE_INCOME, BnplPurchaseWorld.EUR).orElseThrow().id()
                .value());
        UUID principal = UUID.fromString(BnplApplicationClient.scalar("SELECT id::text FROM ledger.ledger_account WHERE"
                + " owner_ref = ?::uuid AND purpose = 'BNPL_PRINCIPAL'", bought.obligationText()));
        Connection holder = BnplPurchaseWorld.held();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            lockRow(holder, "SELECT 1 FROM ledger.ledger_account WHERE id = ?::uuid FOR UPDATE", bought.wallet().value());
            Future<ServicingOutcome> step = pool.submit(() -> serviceStep(bought.obligation()));
            awaitWaiters(1);
            assertThat(freeOrAbsent("ledger.account_balance", "ledger_account_id", income))
                    .as("no fee income row held across the wallet wait").isTrue();
            assertThat(freeNow("ledger.account_balance", "ledger_account_id", principal))
                    .as("the control: the step holds its own obligation's projection rows").isFalse();
            holder.rollback();
            ServicingOutcome serviced = step.get(60, TimeUnit.SECONDS);
            assertThat(serviced.collected(CollectionOutcome.INSUFFICIENT_FUNDS)).isEqualTo(1);
            assertThat(serviced.conditionChanges()).isEqualTo(1);
            assertThat(serviced.fees()).isEqualTo(1);
        } finally {
            BnplPurchaseWorld.closeQuietly(holder);
            pool.shutdownNow();
        }
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_fee_assessment WHERE obligation_id ="
                + " ?::uuid AND amount_minor = 500", bought.obligationText())).isEqualTo(1);
        assertThat(BnplPurchaseWorld.obligationBalance(bought.obligationText(), AccountPurpose.BNPL_FEES_DUE))
                .isEqualTo(500);
    }

    // ------------------------------------------------------------------ the world

    /** An accepted purchase: its obligation, agreement, party and the party's wallet. */
    record Bought(BnplObligationId obligation, BnplAgreementId agreement, UUID party, LedgerAccountId wallet) {

        String obligationText() {
            return obligation.value().toString();
        }
    }

    private Bought purchase(Function<String, String> body, long walletMinor) throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(walletMinor));
        return purchaseFor(owner, body);
    }

    private Bought purchaseFor(CreditFixtures.Customer owner, Function<String, String> body) throws Exception {
        BnplPurchaseWorld.Offer offer = world.offered(owner, world.merchant(), GROSS, body);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String agreement = BnplApplicationClient.field(accepted.body(), "agreementId");
        UUID obligation = UUID.fromString(BnplApplicationClient.scalar("SELECT id::text FROM bnpl.bnpl_obligation WHERE"
                + " agreement_id = ?::uuid", agreement));
        return new Bought(BnplObligationId.of(obligation), BnplAgreementId.of(UUID.fromString(agreement)), owner.party(),
                world.walletOf(owner.party()));
    }

    /**
     * The owner-role date shift: instalment {@code sequence}'s due date brought to today - the purchase's business date,
     * the obligation's, the schedule's start, every instalment's due date, the billings' and repayments' dates and the
     * obligation's journal entries moved back together, so the record reads as if bought that many days ago.
     */
    private static void toDueDate(Bought bought, int sequence) throws Exception {
        toAge(bought, sequence, 0);
    }

    /** The date shift to instalment {@code sequence} being {@code age} days past its due date today (`P12-TSK-020`). */
    private static void toAge(Bought bought, int sequence, int age) throws Exception {
        String obligation = bought.obligationText();
        long days = age + BnplApplicationClient.count("SELECT i.due_date - (now() AT TIME ZONE 'UTC')::date FROM bnpl.bnpl_instalment i WHERE"
                + " i.obligation_id = ?::uuid AND i.sequence = ?", obligation, sequence);
        String o = "'" + obligation + "'";
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("UPDATE bnpl.purchase SET business_date = business_date - " + days + " WHERE id ="
                        + " (SELECT purchase_id FROM bnpl.bnpl_obligation WHERE id = " + o + ");"
                        + " UPDATE bnpl.bnpl_obligation SET purchase_date = purchase_date - " + days + " WHERE id = " + o
                        + "; UPDATE bnpl.bnpl_schedule SET start_date = start_date - " + days + " WHERE obligation_id = "
                        + o + "; UPDATE bnpl.bnpl_instalment SET due_date = due_date - " + days + " WHERE obligation_id = "
                        + o + "; UPDATE bnpl.bnpl_billing SET value_date = value_date - " + days + ", period_start ="
                        + " period_start - " + days + ", business_date = business_date - " + days
                        + " WHERE obligation_id = " + o + "; UPDATE bnpl.bnpl_repayment SET value_date = value_date - "
                        + days + " WHERE obligation_id = " + o + "; UPDATE ledger.journal_entry SET posting_date ="
                        + " posting_date - " + days + ", value_date = value_date - " + days + " WHERE id IN (SELECT"
                        + " l.entry_id FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id ="
                        + " l.ledger_account_id WHERE a.owner_ref = " + o + "::uuid)");
            }
            owner.commit();
        }
    }

    /** One servicing step in a transaction of its own under the system actor - the sweep's. */
    private ServicingOutcome serviceStep(BnplObligationId obligation) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            return transactions.inTransaction(uow -> servicing.service(uow, obligation));
        }
    }

    /** The customer's repayment, as their door commits it. */
    private BnplRepayments.Repaid repay(Bought bought, String key, long minor) {
        Actor customer = new Actor(bought.party().toString(), ActorType.CUSTOMER);
        try (SecurityContext.Scope actor = SecurityContext.enter(customer);
                CorrelationContext.Scope flow = CorrelationContext.enter(BnplPurchaseWorld.flow())) {
            return transactions.inTransaction(uow -> repayments.repay(uow, bought.agreement(), bought.party(),
                    new BnplRepayments.Request(key, eur(minor)), customer, BnplApplicationClient.correlation()));
        }
    }

    private static <T, R> List<R> concurrently(List<T> inputs, Function<T, R> work) throws Exception {
        List<CompletableFuture<R>> running = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(inputs.size());
        try {
            for (T input : inputs) {
                running.add(CompletableFuture.supplyAsync(() -> work.apply(input), pool));
            }
            List<R> results = new ArrayList<>();
            for (CompletableFuture<R> result : running) {
                results.add(result.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void lockRow(Connection holder, String sql, UUID id) throws SQLException {
        try (PreparedStatement lock = holder.prepareStatement(sql)) {
            lock.setString(1, id.toString());
            lock.executeQuery().close();
        }
    }

    /** At least {@code waiters} sessions of this database waiting on a lock - awaited 30 s. */
    private static void awaitWaiters(int waiters) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (BnplApplicationClient.count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                    + " AND wait_event_type = 'Lock'") >= waiters) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no " + waiters + " session(s) waiting on a lock within 30 s");
    }

    /** Whether {@code table}'s row {@code column = id} can be locked at once - false when another transaction holds it. */
    private static boolean freeNow(String table, String column, UUID id) throws SQLException {
        try (Connection probe = DatabaseRoles.application()) {
            probe.setAutoCommit(false);
            try (PreparedStatement lock = probe.prepareStatement(
                    "SELECT 1 FROM " + table + " WHERE " + column + " = ?::uuid FOR UPDATE NOWAIT")) {
                lock.setString(1, id.toString());
                try (ResultSet row = lock.executeQuery()) {
                    assertThat(row.next()).as("the probed row exists").isTrue();
                }
                return true;
            } catch (SQLException held) {
                if ("55P03".equals(held.getSQLState())) {
                    return false;
                }
                throw held;
            } finally {
                probe.rollback();
            }
        }
    }

    /** {@link #freeNow}, or true when no such row exists yet - an income row never posted to cannot be held. */
    private static boolean freeOrAbsent(String table, String column, UUID id) throws Exception {
        if (BnplApplicationClient.count("SELECT count(*) FROM " + table + " WHERE " + column + " = ?::uuid",
                id.toString()) == 0) {
            return true;
        }
        return freeNow(table, column, id);
    }

    private double collections(String outcome) {
        return meterRegistry.find(BnplServicingMetrics.COLLECTION).tag("outcome", outcome).counter().count();
    }

    private double autoRepayments(String outcome) {
        return meterRegistry.find(BnplRepaymentMetrics.REPAYMENT).tag("kind", "auto_collection").tag("outcome", outcome)
                .counter().count();
    }
}
