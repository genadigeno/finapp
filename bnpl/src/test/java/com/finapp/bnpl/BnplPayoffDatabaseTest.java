package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.count;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.correlation;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.customer;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.employee;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.inOneTransaction;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PurposeBalances;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
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
 * T-pay against a real database (`P12-TSK-022`; {@code bnpl V014}; {@code INV-LND-08} by analogy, {@code INV-LND-02},
 * {@code INV-IDEM-01}, {@code INV-LIFE-04}, {@code INV-BNPL-01}): {@link BnplPayoffs} over bnpl's own JDBC stores, on a
 * database clock moved by {@link BnplClockFrame} - scenario 11 (a payoff beside a refund, both orders, racing), a payoff
 * beside a repayment, ten executions of one quote from ten threads, the quote's date, the floor on a clock stepped back,
 * the sweep and the payoff accruing a date once - and {@code bnpl V014}'s ranks for every writer: the early billing, the
 * {@code PAYOFF} repayment and the {@code PAID_OFF_EARLY} closure only beside an execution, the execution's date and value
 * date, its deferred tie to the closure, and the quote's amount {@code CHECK}. The ledger is the in-memory book of
 * {@link BnplApplicationFakes} behind one lock (the real postings are {@code app}'s, {@code BnplPayoffApiTest}); credit,
 * checkout, merchant, capital and the wallet scripted as {@link BnplAcceptanceDatabaseTest}'s.
 *
 * <p>Written by `P12-TSK-022` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@DisplayName("early payoff against bnpl V014's ranks (P12-TSK-022)")
class BnplPayoffDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = BnplTermsFixtures.EUR;
    private static final JdbcBnplOfferStore OFFERS = new JdbcBnplOfferStore();
    private static final JdbcBnplPurchaseStore PURCHASES = new JdbcBnplPurchaseStore();
    private static final JdbcBnplServicingStore SERVICING = new JdbcBnplServicingStore();
    private static final JdbcBnplRepaymentStore REPAYMENTS = new JdbcBnplRepaymentStore();
    private static final JdbcBnplRefundStore REFUNDS = new JdbcBnplRefundStore();
    private static final JdbcBnplPayoffStore PAYOFFS = new JdbcBnplPayoffStore();
    private static final JdbcBnplAccrualStore ACCRUALS = new JdbcBnplAccrualStore();
    private static final JdbcBnplTermsStore TERMS_STORE = new JdbcBnplTermsStore();

    @BeforeAll
    static void termsAndClock() {
        BnplClockFrame.install();
        BnplTermsAdministration terms = new BnplTermsAdministration(TERMS_STORE, new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, CLOCK, BnplTermsFixtures.BOUNDS);
        for (BnplTerms wanted : List.of(BnplTermsFixtures.payIn4().build(), BnplTermsFixtures.payInN("0.1999").build())) {
            if (inOneTransaction(uow -> TERMS_STORE.active(uow, wanted.product())).isEmpty()) {
                BnplTermsAdministration.Proposed proposed = inOneTransaction(uow -> terms.propose(uow, wanted,
                        "the payoff suite's terms", employee(), correlation()));
                inOneTransaction(uow -> terms.approve(uow, proposed.id(), employee(), "in force", correlation()));
            }
        }
    }

    // ------------------------------------------------------------------ scenario 11 and the races

    @Test
    @DisplayName("scenario 11: a payoff and a refund of one purchase from two threads - exactly one outcome in both orders:"
            + " the refund first makes the quote stale (nothing posted), the payoff first leaves the refund to find CLOSED"
            + " and credit the customer only; every BNPL account zero either way, never a wrong payoff")
    void payoffBesideARefundIsStaleOrClosedNeverWrong() throws Exception {
        // Both orders must occur (the review's finding: four unstaggered rounds proved neither was ever taken): the first
        // two rounds race unstaggered, then the side that starts late alternates - the refund late in even rounds, the
        // payoff late in odd ones - and each order is counted.
        int refundFirst = 0;
        int payoffFirst = 0;
        for (int round = 0; round < 8; round++) {
            World world = new World();
            Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
            LocalDate day = bought.purchaseDate().plusDays(3);
            int which = round;
            BnplPayoffQuote quote = world.quoteOn(day, bought, "q-" + round, Optional.empty()).quote();
            List<Object> outcomes = world.concurrently(2, index -> {
                if (which >= 2 && index == (which % 2 == 0 ? 1 : 0)) {
                    Thread.sleep(150);
                }
                try {
                    return index == 0 ? world.executeOn(day, bought, "x-" + which, quote.id())
                            : world.refundOn(day, bought, "r-" + which, 150_00);
                } catch (BnplPayoffs.PayoffQuoteStale stale) {
                    return stale;
                }
            });
            String reason = scalar("SELECT closure_reason FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'");
            long executions = count("SELECT count(*) FROM bnpl.bnpl_payoff_execution WHERE obligation_id = '"
                    + bought.id() + "'");
            if (outcomes.get(0) instanceof BnplPayoffs.PayoffQuoteStale) {
                // The refund committed first: cancelled latest first, version 2, nothing of the payoff posted.
                assertThat(executions).isZero();
                assertThat(reason).isNull();
                assertThat(world.book.keys()).noneMatch(key -> key.startsWith(BnplRepayments.POSTING_KEY_PREFIX)
                        && key.contains(quote.id().toString()));
                assertThat(outcomes.get(1)).isInstanceOf(BnplRefunds.Refunded.class);
                refundFirst++;
            } else {
                payoffFirst++;
                // The payoff committed first: the refund waited on B5 -> B4 and paid the customer only (OQ-15).
                assertThat(executions).isEqualTo(1);
                assertThat(reason).isEqualTo("PAID_OFF_EARLY");
                BnplRefunds.Refunded refunded = (BnplRefunds.Refunded) outcomes.get(1);
                assertThat(refunded.refund().adjustments()).isEmpty();
                assertThat(refunded.refund().excess()).isEqualTo(Money.ofMinorUnits(150_00, EUR));
                world.assertEveryAccountZero(bought);
            }
        }
        assertThat(refundFirst).as("the refund committed first in some round - the quote went stale").isPositive();
        assertThat(payoffFirst).as("the payoff committed first in some round - the refund found it CLOSED").isPositive();
    }

    @Test
    @DisplayName("a payoff beside a repayment: the repayment committed first makes the quote stale; one after the payoff"
            + " finds the obligation CLOSED - nothing posted either way")
    void payoffDuringARepaymentIsStaleOrClosedNeverWrong() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate day = bought.purchaseDate().plusDays(14);
        world.serviceOn(day, bought);
        BnplPayoffQuote quote = world.quoteOn(day, bought, "q", Optional.empty()).quote();
        world.repayOn(day, bought, "rep", 10_00);
        assertThatThrownBy(() -> world.executeOn(day, bought, "x", quote.id()))
                .isInstanceOf(BnplPayoffs.PayoffQuoteStale.class);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_payoff_execution WHERE obligation_id = '" + bought.id() + "'"))
                .isZero();
        BnplPayoffQuote fresh = world.quoteOn(day, bought, "q2", Optional.empty()).quote();
        world.executeOn(day, bought, "x2", fresh.id());
        assertThatThrownBy(() -> world.repayOn(day, bought, "late", 10_00))
                .isInstanceOf(BnplRepayments.ObligationClosed.class);
        world.assertEveryAccountZero(bought);
    }

    @Test
    @DisplayName("a payoff whose instance dies after its execution, PAYOFF repayment, closure and postings is re-driven by"
            + " another, once: the dead transaction leaves nothing; the re-drive executes once - one execution, one PAYOFF"
            + " repayment, one closure, one repayment entry - and a re-drive after a lost response converges on it")
    void aCrashedPayoffIsReDrivenOnceByAnotherInstance() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate day = bought.purchaseDate().plusDays(5);
        BnplPayoffQuote quote = world.quoteOn(day, bought, "q", Optional.empty()).quote();
        String executions = "SELECT count(*) FROM bnpl.bnpl_payoff_execution WHERE obligation_id = '" + bought.id() + "'";
        String payoffs = "SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'PAYOFF'";
        String closures = "SELECT count(*) FROM bnpl.bnpl_obligation_closure WHERE obligation_id = '" + bought.id() + "'";
        long base = world.repaymentPostings(); // instalment 1's AT_PURCHASE repayment
        long[] seen = BnplInstanceCrash.crashAfter(world.frameOn(day), uow -> world.payoffs.execute(uow,
                bought.agreement(), bought.party(), "crash", quote.id(), customer(bought.party()), correlation()),
                uow -> new long[] {BnplInstanceCrash.countOn(uow, executions), BnplInstanceCrash.countOn(uow, payoffs),
                        BnplInstanceCrash.countOn(uow, closures), world.repaymentPostings()});
        assertThat(seen).as("the dead instance had executed, repaid, closed and posted")
                .containsExactly(1, 1, 1, base + 1);
        assertThat(count(executions)).as("nothing of the dead instance stands").isZero();
        assertThat(count(payoffs)).isZero();
        assertThat(count(closures)).isZero();
        assertThat(world.repaymentPostings()).isEqualTo(base);
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'"))
                .isEqualTo("ACTIVE");

        BnplPayoffs.Executed redriven = world.executeOn(day, bought, "crash", quote.id()); // another instance
        BnplPayoffs.Executed again = world.executeOn(day, bought, "crash", quote.id()); // the response lost
        assertThat(redriven.replayed()).isFalse();
        assertThat(again.replayed()).isTrue();
        assertThat(again.execution().id()).isEqualTo(redriven.execution().id());
        assertThat(count(executions)).isEqualTo(1);
        assertThat(count(payoffs)).isEqualTo(1);
        assertThat(count(closures)).isEqualTo(1);
        assertThat(world.repaymentPostings()).as("one entry").isEqualTo(base + 1);
        world.assertEveryAccountZero(bought);
    }

    @Test
    @DisplayName("ten executions of one quote from ten threads under ten keys make one closure: one execution row, one"
            + " PAYOFF repayment, one closure; nine converge on it")
    void tenExecutionsOneClosure() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate day = bought.purchaseDate().plusDays(5);
        BnplPayoffQuote quote = world.quoteOn(day, bought, "q", Optional.empty()).quote();
        List<BnplPayoffs.Executed> executed = world.concurrently(10, index -> world.executeOn(day, bought, "k-" + index,
                quote.id()));
        assertThat(executed).filteredOn(run -> !run.replayed()).hasSize(1);
        assertThat(executed).extracting(run -> run.execution().id()).containsOnly(executed.get(0).execution().id());
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_payoff_execution WHERE obligation_id = '" + bought.id() + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'PAYOFF'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_obligation_closure WHERE obligation_id = '" + bought.id() + "'"))
                .isEqualTo(1);
        world.assertEveryAccountZero(bought);
    }

    // ------------------------------------------------------------------ the figures

    @Test
    @DisplayName("pay-in-4's payoff is its unpaid instalments: 450 on day 3 - instalments 2..4 billed early, their rows"
            + " dated their due dates, one PAYOFF repayment of 450, CLOSED (PAID_OFF_EARLY)")
    void p4PayoffIsTheUnpaidInstalments() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate day = bought.purchaseDate().plusDays(3);
        BnplPayoffQuote quote = world.quoteOn(day, bought, "q", Optional.empty()).quote();
        assertThat(quote.amount()).isEqualTo(Money.ofMinorUnits(450_00, EUR));
        BnplPayoffs.Executed executed = world.executeOn(day, bought, "x", quote.id());
        assertThat(executed.repayment().orElseThrow().amount()).isEqualTo(Money.ofMinorUnits(450_00, EUR));
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = '" + bought.id() + "'"))
                .isEqualTo(4);
        assertThat(scalar("SELECT string_agg(value_date::text || '/' || business_date::text, ',' ORDER BY value_date)"
                + " FROM bnpl.bnpl_billing WHERE obligation_id = '" + bought.id() + "' AND value_date > DATE '" + day
                + "'")).isEqualTo(bought.schedule().instalments().subList(1, 4).stream()
                        .map(row -> row.dueDate() + "/" + day).reduce((a, b) -> a + "," + b).orElseThrow());
        assertThat(scalar("SELECT reason FROM bnpl.bnpl_obligation_closure WHERE obligation_id = '" + bought.id() + "'"))
                .isEqualTo("PAID_OFF_EARLY");
        world.assertEveryAccountZero(bought);
    }

    @Test
    @DisplayName("pay-in-N: the quote waits for T-acc, projects [today, G - 1]; on G T-acc posts exactly the projection and"
            + " the payoff pays the quoted figure")
    void pnPayoffAccruesFirstAndProjectsToTheGoodThroughDate() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        LocalDate day = bought.purchaseDate().plusDays(10);
        assertThatThrownBy(() -> world.quoteOn(day, bought, "q", Optional.of(day.plusDays(5))))
                .isInstanceOf(BnplPayoffs.BehindAccrual.class);
        world.catchUpOn(day, bought);
        BnplPayoffQuote quote = world.quoteOn(day, bought, "q", Optional.of(day.plusDays(5))).quote();
        assertThat(quote.projectedDays()).isEqualTo(5);
        assertThat(quote.projectedInterest().isPositive()).isTrue();
        world.catchUpOn(day.plusDays(5), bought);
        assertThat(scalar("SELECT sum(posted_minor)::text FROM bnpl.bnpl_interest_accrual WHERE obligation_id = '"
                + bought.id() + "' AND accrual_date >= DATE '" + day + "'"))
                .isEqualTo(Long.toString(quote.projectedInterest().minorUnits()));
        BnplPayoffs.Executed executed = world.executeOn(day.plusDays(5), bought, "x", quote.id());
        assertThat(executed.repayment().orElseThrow().amount()).isEqualTo(quote.amount());
        world.assertEveryAccountZero(bought);
    }

    @Test
    @DisplayName("held credit beside an item already due is applied by the catch-up before the wallet is debited (A4-M1)")
    void heldCreditIsAppliedBeforeTheWalletIsDebited() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate day = bought.purchaseDate().plusDays(3);
        world.repayOn(day, bought, "early", 40_00);
        BnplPayoffQuote quote = world.quoteOn(day, bought, "q", Optional.empty()).quote();
        assertThat(quote.amount()).isEqualTo(Money.ofMinorUnits(410_00, EUR));
        BnplPayoffs.Executed executed = world.executeOn(day, bought, "x", quote.id());
        assertThat(executed.repayment().orElseThrow().amount()).isEqualTo(Money.ofMinorUnits(410_00, EUR));
        assertThat(executed.closure().refund().isZero()).isTrue();
        world.assertEveryAccountZero(bought);
    }

    @Test
    @DisplayName("every payoff application is floored (RG-M1): a clock stepped back across midnight behind the latest"
            + " billing - the execution, every billing application and the repayment dated the floor, never today")
    void everyPayoffApplicationIsFloored() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate due = bought.purchaseDate().plusDays(14);
        world.serviceAt(BnplClockFrame.frameTo(due.atTime(0, 0, 5).toInstant(ZoneOffset.UTC)), bought);
        world.repayAt(BnplClockFrame.frameTo(due.atTime(0, 0, 10).toInstant(ZoneOffset.UTC)), bought, "over", 200_00);
        Duration behind = BnplClockFrame.frameTo(due.minusDays(1).atTime(23, 59, 50).toInstant(ZoneOffset.UTC));
        BnplPayoffQuote quote = BnplClockFrame.inOneTransaction(behind, uow -> world.payoffs.quote(uow,
                bought.agreement(), bought.party(), new BnplPayoffs.QuoteRequest("q", Optional.empty()),
                correlation())).quote();
        BnplPayoffs.Executed executed = BnplClockFrame.inOneTransaction(behind, uow -> world.payoffs.execute(uow,
                bought.agreement(), bought.party(), "x", quote.id(), customer(bought.party()), correlation()));
        assertThat(executed.execution().valueDate()).isEqualTo(due);
        assertThat(executed.repayment().orElseThrow().valueDate()).isEqualTo(due);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_credit_balance_application WHERE obligation_id = '"
                + bought.id() + "' AND value_date < DATE '" + due + "'")).isZero();
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                + "' AND value_date < DATE '" + due + "' AND kind <> 'AT_PURCHASE'")).isZero();
        world.assertEveryAccountZero(bought);
    }

    @Test
    @DisplayName("the sweep and the payoff accrue the same date once: T-acc from two threads, one row per date, the payoff"
            + " over it exact")
    void theSweepAndThePayoffAccrueTheSameDateOnce() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        LocalDate day = bought.purchaseDate().plusDays(4);
        world.concurrently(2, index -> BnplClockFrame.inOneTransaction(world.frameOn(day),
                uow -> world.accruals.accrue(uow, bought.obligation())));
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_interest_accrual WHERE obligation_id = '" + bought.id() + "'"))
                .isEqualTo(4);
        assertThat(count("SELECT count(DISTINCT accrual_date) FROM bnpl.bnpl_interest_accrual WHERE obligation_id = '"
                + bought.id() + "'")).isEqualTo(4);
        BnplPayoffQuote quote = world.quoteOn(day, bought, "q", Optional.empty()).quote();
        world.executeOn(day, bought, "x", quote.id());
        world.assertEveryAccountZero(bought);
    }

    @Test
    @DisplayName("no rebate arithmetic exists: interest income is only ever credited, and equals the accruals posted")
    void noRebateArithmeticExists() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        LocalDate day = bought.purchaseDate().plusDays(20);
        world.catchUpOn(day, bought);
        BnplPayoffQuote quote = world.quoteOn(day, bought, "q", Optional.empty()).quote();
        world.executeOn(day, bought, "x", quote.id());
        long accrued = Long.parseLong(scalar("SELECT coalesce(sum(posted_minor), 0)::text FROM bnpl.bnpl_interest_accrual"
                + " WHERE obligation_id = '" + bought.id() + "'"));
        assertThat(quote.interestAccrued().plus(quote.interestDue()).minorUnits()).isEqualTo(accrued);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = '" + bought.id()
                + "' AND interest_minor < 0")).isZero();
        world.assertEveryAccountZero(bought);
    }

    @Test
    @DisplayName("a quote is executable only on its date: the day before and after are stale, nothing posted")
    void aQuoteIsExecutableOnlyOnItsDate() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate day = bought.purchaseDate().plusDays(2);
        BnplPayoffQuote quote = world.quoteOn(day, bought, "q", Optional.of(day.plusDays(2))).quote();
        for (LocalDate wrong : List.of(day.plusDays(1), day.plusDays(3))) {
            assertThatThrownBy(() -> world.executeOn(wrong, bought, "x", quote.id()))
                    .isInstanceOf(BnplPayoffs.PayoffQuoteStale.class);
        }
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_payoff_execution WHERE obligation_id = '" + bought.id() + "'"))
                .isZero();
        world.executeOn(day.plusDays(2), bought, "x", quote.id());
        world.assertEveryAccountZero(bought);
    }

    @Test
    @DisplayName("insufficient funds post nothing - no execution, billing or entry; the obligation ACTIVE")
    void insufficientFundsPostsNothing() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate day = bought.purchaseDate().plusDays(2);
        BnplPayoffQuote quote = world.quoteOn(day, bought, "q", Optional.empty()).quote();
        synchronized (world.book) {
            world.fakes.book.statuses.put(world.ports.wallet, LedgerAccountStatus.POSTING_SUSPENDED);
        }
        int keys = world.book.keys().size();
        assertThatThrownBy(() -> world.executeOn(day, bought, "x", quote.id()))
                .isInstanceOf(BnplRepayments.InsufficientFunds.class);
        assertThat(world.book.keys()).hasSize(keys);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = '" + bought.id() + "'"))
                .isEqualTo(1);
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'"))
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a payoff is permitted in any condition: instalment 2 overdue and unpaid, the obligation paid off")
    void payoffIsPermittedInAnyCondition() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate overdue = bought.purchaseDate().plusDays(24);
        world.serviceOn(overdue, bought);
        BnplPayoffQuote quote = world.quoteOn(overdue, bought, "q", Optional.empty()).quote();
        world.executeOn(overdue, bought, "x", quote.id());
        world.assertEveryAccountZero(bought);
    }

    @Test
    @DisplayName("payoff zeroes every balance, both products - and the database ties the execution to its closure at"
            + " commit")
    void payoffZeroesEveryBalance() throws Exception {
        World world = new World();
        for (BnplProduct product : BnplProduct.values()) {
            Purchased bought = world.purchase(product, product == BnplProduct.BNPL_PAY_IN_4 ? BnplPlan.PAY_IN_4
                    : BnplPlan.PAY_IN_3_MONTHS);
            LocalDate day = bought.purchaseDate().plusDays(6);
            world.catchUpOn(day, bought);
            BnplPayoffQuote quote = world.quoteOn(day, bought, "q", Optional.empty()).quote();
            world.executeOn(day, bought, "x", quote.id());
            world.assertEveryAccountZero(bought);
            assertThat(scalar("SELECT o.closure_reason || '|' || e.value_date FROM bnpl.bnpl_obligation o JOIN"
                    + " bnpl.bnpl_payoff_execution e ON e.obligation_id = o.id WHERE o.id = '" + bought.id() + "'"))
                    .isEqualTo("PAID_OFF_EARLY|" + day);
        }
    }

    @Test
    @DisplayName("bnpl V014's ranks hold for every writer: an early billing, a PAYOFF repayment and a PAID_OFF_EARLY"
            + " closure without an execution are refused; an execution off its date, off max(today, floor), or committed"
            + " without its closure is refused; the quote's amount is its parts; the quote and execution are immutable")
    void theRanksHoldForEveryWriter() throws Exception {
        World world = new World();
        Purchased bought = world.purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        LocalDate day = bought.purchaseDate().plusDays(2);
        BnplPayoffQuote quote = world.quoteOn(day, bought, "q", Optional.of(day.plusDays(1))).quote();
        String agreement = bought.agreement().value().toString();
        BnplPurchaseStore.StoredInstalment second = bought.schedule().instalments().get(1);
        try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(), world.frameOn(day));
                Statement insert = migrator.createStatement()) {
            assertRefused(migrator, insert, "INSERT INTO bnpl.bnpl_billing (id, instalment_id, obligation_id, value_date,"
                    + " currency, scale, principal_minor, interest_minor, journal_entry_id, billed_at, schedule_id,"
                    + " schedule_version, schedule_engine, agreement_id, agreement_version, accrual_engine, period_start,"
                    + " business_date, correlation_id) VALUES (gen_random_uuid(), '" + second.id() + "', '" + bought.id()
                    + "', DATE '" + second.dueDate() + "', 'EUR', 2, 15000, 0, gen_random_uuid(), clock_timestamp(), '"
                    + bought.schedule().id() + "', 1, 'BNPL_SCHEDULE_ENGINE_V1', '" + agreement + "', 1, NULL, DATE '"
                    + bought.schedule().instalments().get(0).dueDate() + "', DATE '" + day + "', 'rank')", "early");
            assertRefused(migrator, insert, "INSERT INTO bnpl.bnpl_repayment (id, obligation_id, kind, request_key,"
                    + " currency, scale, amount_minor, agreement_id, agreement_version, allocation_engine,"
                    + " journal_entry_id, value_date, received_at) VALUES (gen_random_uuid(), '" + bought.id()
                    + "', 'PAYOFF', '" + quote.id() + "', 'EUR', 2, 100, '" + agreement + "', 1, 'ALLOCATION_ENGINE_V1',"
                    + " gen_random_uuid(), DATE '" + day + "', clock_timestamp())", "PAYOFF");
            assertRefused(migrator, insert, "INSERT INTO bnpl.bnpl_payoff_execution (id, quote_id, obligation_id,"
                    + " request_key, business_date, value_date, executed_at, correlation_id) VALUES (gen_random_uuid(), '"
                    + quote.id() + "', '" + bought.id() + "', 'k', DATE '" + day + "', DATE '" + day
                    + "', clock_timestamp(), 'rank')", "good-through");
            assertRefused(migrator, insert, "UPDATE bnpl.bnpl_payoff_quote SET amount_minor = 1 WHERE id = '" + quote.id()
                    + "'", "immutable");
        }
        try (Connection migrator = BnplClockFrame.framed(DatabaseRoles.migrator(), world.frameOn(day.plusDays(1)));
                Statement insert = migrator.createStatement()) {
            assertRefused(migrator, insert, "INSERT INTO bnpl.bnpl_payoff_execution (id, quote_id, obligation_id,"
                    + " request_key, business_date, value_date, executed_at, correlation_id) VALUES (gen_random_uuid(), '"
                    + quote.id() + "', '" + bought.id() + "', 'k', DATE '" + day.plusDays(1) + "', DATE '"
                    + day.plusDays(2) + "', clock_timestamp(), 'rank')", "floor");
            // Born on its date, committed without its closure: the deferred check refuses the commit.
            insert.execute("INSERT INTO bnpl.bnpl_payoff_execution (id, quote_id, obligation_id, request_key,"
                    + " business_date, value_date, executed_at, correlation_id) VALUES (gen_random_uuid(), '" + quote.id()
                    + "', '" + bought.id() + "', 'k', DATE '" + day.plusDays(1) + "', DATE '" + day.plusDays(1)
                    + "', clock_timestamp(), 'rank')");
            assertThatThrownBy(migrator::commit).isInstanceOf(SQLException.class)
                    .hasMessageContaining("PAID_OFF_EARLY");
            migrator.rollback();
        }
        try (Connection migrator = DatabaseRoles.migrator(); Statement insert = migrator.createStatement()) {
            migrator.setAutoCommit(false); // assertRefused rolls each refusal back, as the two framed blocks above do
            assertRefused(migrator, insert, "INSERT INTO bnpl.bnpl_obligation_closure (id, obligation_id, reason,"
                    + " currency, scale, refund_minor, created_at, correlation_id) VALUES (gen_random_uuid(), '"
                    + bought.id() + "', 'PAID_OFF_EARLY', 'EUR', 2, 0, clock_timestamp(), 'rank')", "PAID_OFF_EARLY");
            assertRefused(migrator, insert, "INSERT INTO bnpl.bnpl_payoff_quote (id, obligation_id, agreement_id,"
                    + " agreement_version, request_key, business_date, good_through, currency, scale, principal_minor,"
                    + " principal_due_minor, interest_accrued_minor, interest_due_minor, fees_due_minor,"
                    + " credit_balance_minor, projection_base_minor, projected_days, projected_interest_minor,"
                    + " amount_minor, schedule_engine, accrual_engine, allocation_engine, created_at, correlation_id)"
                    + " VALUES (gen_random_uuid(), '" + bought.id() + "', '" + agreement + "', 1, 'parts',"
                    + " (clock_timestamp() AT TIME ZONE 'UTC')::date, (clock_timestamp() AT TIME ZONE 'UTC')::date, 'EUR', 2, 45000, 0, 0, 0, 0, 0, 45000, 0, 0, 44999, 'BNPL_SCHEDULE_ENGINE_V1',"
                    + " NULL, 'ALLOCATION_ENGINE_V1', clock_timestamp(), 'rank')", "amount_is_its_parts");
        }
    }

    private static void assertRefused(Connection connection, Statement statement, String sql, String fragment)
            throws SQLException {
        assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(SQLException.class)
                .hasMessageContaining(fragment);
        connection.rollback();
    }

    // ------------------------------------------------------------------ the world

    record Purchased(BnplPurchaseId purchase, BnplObligationId obligation, UUID party, UUID merchant,
            LocalDate purchaseDate, BnplPurchaseStore.StoredSchedule schedule, BnplAgreementId agreement) {

        String id() {
            return obligation.value().toString();
        }
    }

    /** A step of a concurrent race. */
    interface Racer<R> {
        R run(int index) throws Exception;
    }

    /** The suite's world: the scripted ports, the in-memory book behind one lock, T-pur, T-acc, T-svc, T-rep, T-ref, T-pay. */
    private static final class World {

        final BnplAcceptanceDatabaseTest.Scripted ports = new BnplAcceptanceDatabaseTest.Scripted();
        final BnplApplicationFakes fakes = new BnplApplicationFakes();
        /** The billing suite's book, inside its callers' transactions - a rolled-back payoff leaves nothing (P12-TSK-027). */
        final BnplBillingDatabaseTest.SharedBook book = new BnplBillingDatabaseTest.SharedBook(fakes.book);
        final BnplClosures closures = new BnplClosures(REPAYMENTS, SERVICING, PURCHASES, ports, book,
                new JdbcOutboxWriter(), IDS, CLOCK);
        final BnplRepayments repayments = new BnplRepayments(REPAYMENTS, SERVICING, PURCHASES, TERMS_STORE, ports, book,
                closures, new JdbcOutboxWriter(), IDS, CLOCK);
        final BnplServicing servicing = new BnplServicing(SERVICING, PURCHASES, TERMS_STORE, book, closures,
                new BnplCollections(new JdbcBnplCollectionStore(), repayments, PURCHASES, ports, book,
                        new JdbcOutboxWriter(), IDS, CLOCK),
                new BnplConditions(new JdbcBnplConditionStore(), PURCHASES, TERMS_STORE, book, new JdbcOutboxWriter(),
                        IDS, CLOCK),
                new JdbcOutboxWriter(), IDS, CLOCK);
        final BnplRefunds refunds = new BnplRefunds(REFUNDS, SERVICING, PURCHASES, TERMS_STORE, ports, ports, book,
                closures, (uow, actor) -> Optional.empty(), new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS, CLOCK);
        final BnplAccruals accruals = new BnplAccruals(SERVICING, ACCRUALS, PURCHASES, TERMS_STORE, book, IDS);
        final BnplPayoffs payoffs = new BnplPayoffs(REPAYMENTS, PAYOFFS, SERVICING, PURCHASES, TERMS_STORE, ACCRUALS,
                ports, book, servicing, repayments, closures, IDS);
        private final BnplAcceptances acceptances = new BnplAcceptances(OFFERS, TERMS_STORE, PURCHASES, ports,
                (uow, party) -> true, ports, ports, ports, ports, book, new JdbcOutboxWriter(), new JdbcAuditWriter(),
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
            return new Purchased(accepted.purchase().id(), accepted.obligation().id(), offered.party(),
                    offered.merchant(), accepted.purchase().businessDate(), schedule, accepted.agreement().id());
        }

        Duration frameOn(LocalDate date) {
            return BnplClockFrame.frameTo(date.atTime(12, 0).toInstant(ZoneOffset.UTC));
        }

        BnplPayoffs.Quoted quoteOn(LocalDate date, Purchased bought, String key, Optional<LocalDate> goodThrough) {
            return BnplClockFrame.inOneTransaction(frameOn(date), uow -> payoffs.quote(uow, bought.agreement(),
                    bought.party(), new BnplPayoffs.QuoteRequest(key, goodThrough), correlation()));
        }

        BnplPayoffs.Executed executeOn(LocalDate date, Purchased bought, String key, UUID quote) {
            return BnplClockFrame.inOneTransaction(frameOn(date), uow -> payoffs.execute(uow, bought.agreement(),
                    bought.party(), key, quote, customer(bought.party()), correlation()));
        }

        /** T-acc until caught up, in transactions of its own - the desk's rounds. */
        void catchUpOn(LocalDate date, Purchased bought) {
            for (int round = 0; round < 8; round++) {
                if (BnplClockFrame.inOneTransaction(frameOn(date), uow -> accruals.accrue(uow, bought.obligation()))
                        .caughtUp()) {
                    return;
                }
            }
            throw new AssertionError("T-acc did not catch up");
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

        BnplRepayments.Repaid repayOn(LocalDate date, Purchased bought, String key, long minor) {
            return repayAt(frameOn(date), bought, key, minor);
        }

        BnplRepayments.Repaid repayAt(Duration frame, Purchased bought, String key, long minor) {
            return BnplClockFrame.inOneTransaction(frame, uow -> repayments.repay(uow, bought.agreement(),
                    bought.party(), new BnplRepayments.Request(key, Money.ofMinorUnits(minor, EUR)),
                    customer(bought.party()), correlation()));
        }

        BnplRefunds.Refunded refundOn(LocalDate date, Purchased bought, String key, long minor) {
            Actor merchant = new Actor(bought.merchant().toString(), ActorType.MERCHANT);
            return BnplClockFrame.inOneTransaction(frameOn(date), uow -> refunds.atMerchant(uow, bought.purchase(),
                    bought.merchant(), new BnplRefunds.Request(key, Money.ofMinorUnits(minor, EUR), "goods returned",
                            Optional.of("key-under-test")), merchant, correlation()));
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

        /** The book's repayment postings (a PAYOFF's among them) - the entries, as the ledger keys them. */
        long repaymentPostings() {
            return book.keys().stream().filter(key -> key.startsWith(BnplRepayments.POSTING_KEY_PREFIX)).count();
        }

        void assertEveryAccountZero(Purchased bought) throws SQLException {
            for (LedgerAccount account : book.accountsOf(null, bought.obligation())) {
                assertThat(book.balanceOf(null, account).isZero()).as(account.purpose().name()).isTrue();
            }
            assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'"))
                    .isEqualTo("CLOSED");
            assertThat(book.accountsOf(null, bought.obligation())).extracting(LedgerAccount::purpose)
                    .contains(AccountPurpose.BNPL_PRINCIPAL);
        }
    }
}
