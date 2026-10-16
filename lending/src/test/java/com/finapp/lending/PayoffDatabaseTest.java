package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.BALANCES;
import static com.finapp.lending.DisbursementWorld.BILLINGS;
import static com.finapp.lending.DisbursementWorld.BILLING_STORE;
import static com.finapp.lending.DisbursementWorld.CLOSURES;
import static com.finapp.lending.DisbursementWorld.HOLD_STORE;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.LOAN_STORE;
import static com.finapp.lending.DisbursementWorld.REPAYMENTS;
import static com.finapp.lending.DisbursementWorld.REPAYMENT_STORE;
import static com.finapp.lending.DisbursementWorld.STATEMENTS;
import static com.finapp.lending.DisbursementWorld.TERMS_STORE;
import static com.finapp.lending.DisbursementWorld.WALLETS;
import static com.finapp.lending.DisbursementWorld.awaitBlockedBy;
import static com.finapp.lending.DisbursementWorld.businessDate;
import static com.finapp.lending.DisbursementWorld.businessDateSql;
import static com.finapp.lending.DisbursementWorld.correlation;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.customer;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.pid;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.DisbursementWorld.walletOf;
import static com.finapp.lending.InstalmentBillingDatabaseTest.advance;
import static com.finapp.lending.InstalmentBillingDatabaseTest.billings;
import static com.finapp.lending.InstalmentBillingDatabaseTest.daysTo;
import static com.finapp.lending.InstalmentBillingDatabaseTest.partyOf;
import static com.finapp.lending.InterestAccrualDatabaseTest.SERVICING;
import static com.finapp.lending.InterestAccrualDatabaseTest.SERVICING_STORE;
import static com.finapp.lending.InterestAccrualDatabaseTest.backdate;
import static com.finapp.lending.InterestAccrualDatabaseTest.disbursed;
import static com.finapp.lending.InterestAccrualDatabaseTest.eur;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.RepaymentDatabaseTest.assertSubledgerProves;
import static com.finapp.lending.RepaymentDatabaseTest.held;
import static com.finapp.lending.RepaymentDatabaseTest.key;
import static com.finapp.lending.RepaymentDatabaseTest.loanStatus;
import static com.finapp.lending.RepaymentDatabaseTest.refusedAlone;
import static com.finapp.lending.RepaymentDatabaseTest.repay;
import static com.finapp.lending.RepaymentDatabaseTest.topUp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The payoff - its quote, its execution and the closure it brings, both products - against a real database (`P11-TSK-026`;
 * {@code lending V022}, {@code PHASE_11_PLAN.md} sections 7.1 T-pay, 7.4, 12.5 and 12.8 rule 7, section 13.2 scenarios 7
 * and 10; ADR-0094 point 10, ADR-0095 point 8; {@code INV-LND-08}, {@code INV-LND-02}, {@code INV-LND-04},
 * {@code INV-IDEM-01}, {@code INV-LIFE-04}): every figure read from the rows and the ledger, every payoff closing on the
 * subledger proof's arms and on all six balances zero.
 *
 * <p>Loans are disbursed by the real T-dis, serviced by the real T-svc, repaid by the real T-rep; a payoff runs the real
 * {@link Payoffs} over the real ledger. An elapsed history is the owner's fixture moving the loan's dated facts into the
 * past ({@link InstalmentBillingDatabaseTest#advance}, which moves a quote's dates with them) - never a path.
 */
@Tag("database")
@DisplayName("the payoff quote, execution and closure, both products (P11-TSK-026)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class PayoffDatabaseTest {

    static final JdbcPayoffStore PAYOFF_STORE = new JdbcPayoffStore();
    static final JdbcLineClosureStore LINE_CLOSURE_STORE = new JdbcLineClosureStore();
    static final LineClosures LINE_CLOSURES = new LineClosures(LOAN_STORE, LINE_CLOSURE_STORE, TERMS_STORE, CLOSURES,
            new JdbcOutboxWriter(), IDS, DisbursementWorld.CLOCK);
    static final Payoffs PAYOFFS = payoffs(SERVICING);

    /**
     * A credit line proposal another suite left pending (`LoanTermsDatabaseTest#tenProposersOneProposal` ends on one) is
     * rejected through the domain, so this suite's lines can bring their terms into force whatever ran before it.
     */
    @BeforeEach
    void noLineTermsProposalPending() {
        DisbursementWorld.clearPendingTerms(LoanProduct.CREDIT_LINE);
    }

    static Payoffs payoffs(LoanServicing servicing) {
        return new Payoffs(LOAN_STORE, PAYOFF_STORE, TERMS_STORE, servicing, SERVICING_STORE, BILLINGS, STATEMENTS,
                BILLING_STORE, REPAYMENT_STORE, WALLETS, new AvailableBalance<>(new JdbcBalanceDerivation(), HOLD_STORE),
                LEDGER_ACCOUNTS, BALANCES, REPAYMENTS, CLOSURES, LINE_CLOSURES, IDS);
    }

    // ------------------------------------------------------------------ the money

    @Test
    @DisplayName("a payoff zeroes every balance: a loan ten days into its second period, its first instalment part-paid and"
            + " its ten days not yet accrued - the quote accrues them and names P + PD + IA + ID - CB; the execution bills"
            + " every unbilled instalment, repays everything once and closes PAID_OFF_EARLY - all six balances zero, the"
            + " wallet debited exactly the quote, every instalment billed and paid, the subledger proven")
    void payoffZeroesEveryBalance() {
        LoanId loan = midLife(5_000_00);
        UUID party = partyOf(loan);
        Money walletBefore = netDebit(walletOf(party)).negated();
        long accrualsBefore = accruals(loan);

        PayoffQuote quote = quote(loan, key(), Optional.empty()).quote();
        assertThat(accruals(loan)).as("the quote accrued the elapsed dates itself").isEqualTo(accrualsBefore + 10);
        assertThat(quote.goodThrough()).isEqualTo(businessDate(loan));
        assertThat(quote.projectedDays()).isZero();
        assertThat(quote.principal().isPositive()).isTrue();
        assertThat(quote.interestAccrued().isPositive()).as("the ten days accrued, unbilled").isTrue();
        assertThat(quote.outstanding()).isEqualTo(owedNow(loan));

        Payoffs.Executed executed = execute(loan, quote, key());
        assertThat(executed.replayed()).isFalse();
        assertThat(executed.repayment()).isPresent();
        assertThat(executed.repayment().get().kind()).isEqualTo(RepaymentKind.PAYOFF);
        assertThat(executed.repayment().get().amount()).isEqualTo(quote.outstanding());
        assertThat(executed.closure().reason()).isEqualTo(ClosureReason.PAID_OFF_EARLY);
        assertPaidOff(loan, ClosureReason.PAID_OFF_EARLY);
        assertThat(netDebit(walletOf(party)).negated()).as("the wallet paid exactly the quote")
                .isEqualTo(walletBefore.minus(quote.outstanding()));
        assertThat(count("SELECT count(*) FROM lending.loan_instalment i WHERE i.loan_id = ? AND NOT EXISTS"
                + " (SELECT 1 FROM lending.instalment_billing b WHERE b.instalment_id = i.id)", loan.value()))
                .as("every instalment billed").isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                loan.value(), LoanClosures.CLOSED_EVENT)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                loan.value(), Repayments.RECEIVED_EVENT)).as("the part payment's and the payoff's").isEqualTo(2);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("no rebate arithmetic exists: paid off mid-period, the period's interest is exactly its accrued days - below"
            + " the schedule's figure for the whole period by the unelapsed days - and the loan's every entry is under a"
            + " rule key (disbursement, accrual, billing, credit application, repayment, refund): nothing is computed back")
    void noRebateArithmeticExists() {
        LoanId loan = midLife(4_200_00);
        PayoffQuote quote = quote(loan, key(), Optional.empty()).quote();
        execute(loan, quote, key());
        long scheduled = count("SELECT interest_minor FROM lending.loan_instalment WHERE loan_id = ? AND sequence = 2",
                loan.value());
        long billed = count("SELECT b.interest_due_minor FROM lending.instalment_billing b WHERE b.loan_id = ?"
                + " AND b.sequence = 2", loan.value());
        long accrued = count("SELECT coalesce(sum(a.posted_minor), 0) FROM lending.interest_accrual a"
                + " JOIN lending.loan_instalment i ON i.loan_id = a.loan_id AND i.sequence = 2"
                + " AND a.period_start = i.period_start AND a.period_end = i.due_date WHERE a.loan_id = ?", loan.value());
        assertThat(billed).as("the period's interest is its accrued days").isEqualTo(accrued).isPositive();
        assertThat(billed).as("less than the whole period's").isLessThan(scheduled);
        assertThat(count("SELECT coalesce(sum(b.interest_due_minor), 0) FROM lending.instalment_billing b"
                + " WHERE b.loan_id = ? AND b.sequence > 2", loan.value())).as("no interest on periods not begun").isZero();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry e WHERE e.id IN (SELECT l.entry_id FROM"
                + " ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id WHERE a.owner_ref = ?)"
                + " AND e.idempotency_scope NOT LIKE 'ledger.post:lending.disbursement:%'"
                + " AND e.idempotency_scope NOT LIKE 'ledger.post:lending.accrual:%'"
                + " AND e.idempotency_scope NOT LIKE 'ledger.post:lending.billing:%'"
                + " AND e.idempotency_scope NOT LIKE 'ledger.post:lending.credit-application:%'"
                + " AND e.idempotency_scope NOT LIKE 'ledger.post:lending.repayment:%'"
                + " AND e.idempotency_scope NOT LIKE 'ledger.post:lending.closure-refund:%'"
                + " AND e.idempotency_scope NOT LIKE 'ledger.post:p11t18-topup:%'", loan.value()))
                .as("no entry outside the posting rules - no rebate").isZero();
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_ACCRUED)).isZero()).isTrue();
    }

    @Test
    @DisplayName("a quote is executable only on its date: good through five days ahead, it is refused today as stale and"
            + " posts nothing; on its date it pays exactly what it projected - the payoff's own accrual posting the projected"
            + " interest to the minor unit; a day late it is stale; G before today or past the pinned horizon is refused")
    void aQuoteIsExecutableOnlyOnItsDate() {
        LoanId loan = midLife(3_600_00);
        LocalDate today = businessDate(loan);
        PayoffQuote quote = quote(loan, key(), Optional.of(today.plusDays(5))).quote();
        assertThat(quote.projectedDays()).isEqualTo(5);
        assertThat(quote.projectedInterest().isPositive()).isTrue();
        long entries = loanEntries(loan);
        assertThatExceptionOfType(Payoffs.PayoffQuoteStale.class).isThrownBy(() -> execute(loan, quote, key()))
                .satisfies(stale -> assertThat(stale.reason()).isEqualTo(Payoffs.PayoffQuoteStale.Reason.NOT_ITS_DATE));
        assertThat(loanEntries(loan)).as("nothing posted").isEqualTo(entries);
        assertThat(count("SELECT count(*) FROM lending.payoff_execution WHERE loan_id = ?", loan.value())).isZero();

        advance(loan, 5);
        Payoffs.Executed executed = execute(loan, quote, key());
        assertThat(executed.repayment().orElseThrow().amount()).isEqualTo(quote.outstanding());
        long projectedPosted = count("SELECT coalesce(sum(posted_minor), 0) FROM lending.interest_accrual"
                + " WHERE loan_id = ? AND accrual_date >= ?", loan.value(), quote.businessDate().minusDays(5));
        assertThat(projectedPosted).as("the payoff's accrual posted exactly the projection")
                .isEqualTo(quote.projectedInterest().minorUnits());
        assertPaidOff(loan, ClosureReason.PAID_OFF_EARLY);

        LoanId late = midLife(3_700_00);
        PayoffQuote lateQuote = quote(late, key(), Optional.of(businessDate(late).plusDays(2))).quote();
        advance(late, 3);
        assertThatExceptionOfType(Payoffs.PayoffQuoteStale.class).isThrownBy(() -> execute(late, lateQuote, key()));
        assertThat(loanStatus(late)).isEqualTo("ACTIVE");

        assertThatExceptionOfType(Payoffs.GoodThroughInvalid.class)
                .isThrownBy(() -> quote(late, key(), Optional.of(businessDate(late).minusDays(1))));
        assertThatExceptionOfType(Payoffs.GoodThroughInvalid.class)
                .isThrownBy(() -> quote(late, key(), Optional.of(businessDate(late).plusDays(31))))
                .satisfies(invalid -> assertThat(invalid.horizonDays()).isEqualTo(30));
        assertThat(quote(late, key(), Optional.of(businessDate(late).plusDays(30))).quote().projectedDays())
                .as("the horizon itself is admitted").isEqualTo(30);
    }

    @Test
    @DisplayName("insufficient funds posts nothing: a wallet short of the quote by a cent is refused before posting - no"
            + " execution, no billing, no accrual, no entry; topped up, the same quote pays off")
    void insufficientFundsPostsNothing() {
        LoanId loan = disbursed(2_800_00, 12);
        backdate(loan, 20);
        UUID party = partyOf(loan);
        PayoffQuote quote = quote(loan, key(), Optional.empty()).quote();
        Money available = netDebit(walletOf(party)).negated();
        Money shortBy = quote.outstanding().minus(available).minus(eur(1));
        // Leave the wallet exactly one cent short of the quote.
        if (shortBy.isNegative()) {
            drain(party, shortBy.negated());
        } else if (shortBy.isPositive()) {
            topUp(party, shortBy.minorUnits());
        }
        long entries = loanEntries(loan);
        long billingsBefore = count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?", loan.value());
        assertThatExceptionOfType(Repayments.InsufficientFunds.class).isThrownBy(() -> execute(loan, quote, key()));
        assertThat(loanEntries(loan)).as("nothing posted").isEqualTo(entries);
        assertThat(count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?", loan.value()))
                .isEqualTo(billingsBefore);
        assertThat(count("SELECT count(*) FROM lending.payoff_execution WHERE loan_id = ?", loan.value())).isZero();
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ?", loan.value())).isZero();
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        topUp(party, 1);
        execute(loan, quote, key());
        assertPaidOff(loan, ClosureReason.PAID_OFF_EARLY);
        assertThat(netDebit(walletOf(party)).isZero()).as("the wallet held exactly the quote").isTrue();
    }

    @Test
    @DisplayName("a credit balance that covers everything pays nothing and is refunded: the whole principal repaid early and"
            + " held as credit - the quote is negative; the execution bills everything, applies the credit, posts no"
            + " repayment and refunds the excess - closed PAID_OFF_EARLY, all six balances zero")
    void aCreditBalanceThatCoversEverythingIsRefunded() {
        LoanId loan = disbursed(2_000_00, 12);
        backdate(loan, 6);
        UUID party = partyOf(loan);
        topUp(party, 400_00);
        Repayments.Repaid early = repay(loan, key(), 2_300_00);
        assertThat(early.closure()).isEmpty();
        PayoffQuote quote = quote(loan, key(), Optional.empty()).quote();
        assertThat(quote.outstanding().isNegative()).isTrue();
        assertThat(quote.amount().isZero()).isTrue();
        Money walletBefore = netDebit(walletOf(party)).negated();
        Payoffs.Executed executed = execute(loan, quote, key());
        assertThat(executed.repayment()).as("nothing to pay").isEmpty();
        assertThat(executed.closure().refund()).isEqualTo(quote.refund());
        assertPaidOff(loan, ClosureReason.PAID_OFF_EARLY);
        assertThat(netDebit(walletOf(party)).negated()).isEqualTo(walletBefore.plus(quote.refund()));
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("a payoff beside credit held and a due item debits the wallet only the net (the Phase 11 gate's A4-M1): a"
            + " matured loan - nothing left to bill - owing its final interest I beside a refund of 15.00 held; the"
            + " execution applies the credit first (a catch-up application), so its PAYOFF repayment is I - 15.00 and its"
            + " closure refunds nothing - never I debited with 15.00 refunded beside it")
    void aPayoffBesideCreditHeldDebitsOnlyTheNet() {
        LoanId loan = FeeAndWaiverDatabaseTest.maturedWithInterestDueAndRefundHeld(10_000_00);
        long interest = billings(loan).get(1).dueItem().interest().minorUnits();
        assertThat(interest).as("more than the refund held").isGreaterThan(15_00);
        UUID party = partyOf(loan);
        Money walletBefore = netDebit(walletOf(party)).negated();
        PayoffQuote quote = quote(loan, key(), Optional.empty()).quote();
        assertThat(quote.outstanding()).isEqualTo(eur(interest - 15_00));

        Payoffs.Executed executed = execute(loan, quote, key());
        assertThat(executed.repayment()).hasValueSatisfying(repayment -> assertThat(repayment.amount())
                .as("the net, never the gross receivable").isEqualTo(eur(interest - 15_00)));
        assertThat(executed.closure().refund().isZero()).as("nothing left to refund").isTrue();
        assertThat(RepaymentDatabaseTest.catchUps(loan)).isEqualTo(1);
        assertPaidOff(loan, ClosureReason.PAID_OFF_EARLY);
        assertThat(netDebit(walletOf(party)).negated()).isEqualTo(walletBefore.minus(eur(interest - 15_00)));
        assertSubledgerProves(loan);
        RepaymentDatabaseTest.assertApplicationsReplay(loan);
    }

    // ------------------------------------------------------------------ the races

    @Test
    @DisplayName("scenario 7 - a payoff during a repayment is stale or closed, never wrong: a repayment committed first makes"
            + " the quote stale (409, nothing posted); a repayment waiting on the payoff's L3 finds the loan CLOSED"
            + " (LoanNotRepayable, nothing posted); ten rounds racing both - exactly one outcome each, the balances zero"
            + " only after a payoff")
    void payoffDuringARepaymentIsStaleOrClosedNeverWrong() throws Exception {
        // The repayment first: the quote's figure moved - stale, nothing posted.
        LoanId first = midLife(3_000_00);
        PayoffQuote stale = quote(first, key(), Optional.empty()).quote();
        repay(first, key(), 50_00);
        long entries = loanEntries(first);
        assertThatExceptionOfType(Payoffs.PayoffQuoteStale.class).isThrownBy(() -> execute(first, stale, key()))
                .satisfies(refused -> assertThat(refused.reason())
                        .isEqualTo(Payoffs.PayoffQuoteStale.Reason.AMOUNT_CHANGED));
        assertThat(loanEntries(first)).as("nothing posted").isEqualTo(entries);
        assertThat(loanStatus(first)).isEqualTo("ACTIVE");
        execute(first, quote(first, key(), Optional.empty()).quote(), key());
        assertPaidOff(first, ClosureReason.PAID_OFF_EARLY);

        // The payoff first, the repayment waiting on its L3: CLOSED, nothing posted.
        LoanId second = midLife(3_100_00);
        PayoffQuote quote = quote(second, key(), Optional.empty()).quote();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection payoff = DatabaseRoles.application()) {
            payoff.setAutoCommit(false);
            executeOn(payoff, second, quote, key());
            Future<Repayments.Repaid> repayment = pool.submit(() -> repay(second, key(), 50_00));
            awaitBlockedBy(pid(payoff));
            payoff.commit();
            assertThatThrownBy(() -> repayment.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(Repayments.LoanNotRepayable.class);
        } finally {
            pool.shutdownNow();
        }
        assertPaidOff(second, ClosureReason.PAID_OFF_EARLY);
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ? AND kind = 'CUSTOMER'",
                second.value())).as("the waiting repayment posted nothing").isEqualTo(1);

        // Ten rounds racing both: exactly one of the two outcomes, never a wrong payoff.
        for (int round = 0; round < 10; round++) {
            LoanId loan = midLife(3_200_00 + round * 10_00);
            PayoffQuote raced = quote(loan, key(), Optional.empty()).quote();
            List<String> outcomes = race(2, index -> {
                if (index == 0) {
                    try {
                        execute(loan, raced, key());
                        return "PAID_OFF";
                    } catch (Payoffs.PayoffQuoteStale staleQuote) {
                        return "STALE";
                    }
                }
                try {
                    repay(loan, key(), 40_00);
                    return "REPAID";
                } catch (Repayments.LoanNotRepayable closed) {
                    return "CLOSED";
                }
            });
            assertThat(outcomes).as("one order or the other").isIn(List.of("PAID_OFF", "CLOSED"),
                    List.of("STALE", "REPAID"));
            if (outcomes.get(0).equals("PAID_OFF")) {
                assertPaidOff(loan, ClosureReason.PAID_OFF_EARLY);
            } else {
                assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
                assertThat(count("SELECT count(*) FROM lending.payoff_execution WHERE loan_id = ?", loan.value()))
                        .isZero();
                assertSubledgerProves(loan);
            }
        }
    }

    @Test
    @DisplayName("scenario 10 - a payoff beside the delinquency worker writes no condition after closure: a loan 35 days past"
            + " due; the servicing step first (holding L3) records its condition and its late fee, and the payoff waiting on"
            + " it finds its quote stale (the fee moved the figure) - a fresh quote then closes it; the payoff first (holding L3) closes and the step, waiting, finds the account CLOSED and evaluates nothing - in"
            + " neither order a condition row after the closure, and the database refuses one on a closed account")
    void payoffBesideTheDelinquencyWorkerWritesNoConditionAfterClosure() throws Exception {
        // The worker first: its condition, then the closure.
        LoanId workerFirst = pastDue(2_600_00, 35);
        PayoffQuote quote = quote(workerFirst, key(), Optional.empty()).quote();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection worker = DatabaseRoles.application(); SecurityContext.Scope system = SecurityContext.enterSystem()) {
            worker.setAutoCommit(false);
            LoanServicing.Outcome outcome = SERVICING.service(worker, workerFirst);
            assertThat(outcome.conditions()).as("the worker records the change").isPositive();
            Future<Payoffs.Executed> payoff = pool.submit(() -> execute(workerFirst, quote, key()));
            awaitBlockedBy(pid(worker));
            worker.commit();
            // The worker's step also assessed the late fee due at 35 days (P11-TSK-025, after the condition step): what the
            // loan owes moved under the waiting payoff, which therefore finds its quote stale - scenario 7's rule, never a
            // wrong payoff.
            assertThatThrownBy(() -> payoff.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(Payoffs.PayoffQuoteStale.class);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM lending.fee_assessment WHERE loan_id = ?", workerFirst.value()))
                .as("the fees that moved the figure").isPositive();
        execute(workerFirst, quote(workerFirst, key(), Optional.empty()).quote(), key());
        assertPaidOff(workerFirst, ClosureReason.PAID_OFF_EARLY);
        assertNoConditionAfterClosure(workerFirst);
        assertThat(conditions(workerFirst)).isPositive();

        // The payoff first: the worker finds the account closed.
        LoanId payoffFirst = pastDue(2_700_00, 35);
        PayoffQuote first = quote(payoffFirst, key(), Optional.empty()).quote();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (Connection payoff = DatabaseRoles.application()) {
            payoff.setAutoCommit(false);
            executeOn(payoff, payoffFirst, first, key());
            Future<LoanServicing.Outcome> step = workers.submit(() -> service(payoffFirst));
            awaitBlockedBy(pid(payoff));
            payoff.commit();
            assertThat(step.get(30, TimeUnit.SECONDS).kind()).isEqualTo(LoanServicing.Outcome.Kind.NOT_SERVICED);
        } finally {
            workers.shutdownNow();
        }
        assertPaidOff(payoffFirst, ClosureReason.PAID_OFF_EARLY);
        assertThat(conditions(payoffFirst)).as("DPD never computed on the closed account").isZero();
        refusedAlone(DelinquencyDatabaseTest.raw(payoffFirst, "DELINQUENCY", 35, "B2_30_59", "CURRENT", false), "P0001",
                "only for an ACTIVE or CLOSING account");
    }

    @Test
    @DisplayName("the sweep and the payoff accrue the same date once: a quote good through four days ahead, the loan moved"
            + " to that date with its four days unaccrued, the servicing step racing the execution - one accrual row and one"
            + " entry per date in either order, and the payoff exact")
    void theSweepAndThePayoffAccrueTheSameDateOnce() throws Exception {
        for (int round = 0; round < 4; round++) {
            LoanId loan = midLife(3_300_00 + round * 10_00, true);
            PayoffQuote quote = quote(loan, key(), Optional.of(businessDate(loan).plusDays(4))).quote();
            advance(loan, 4);
            List<String> outcomes = race(2, index -> {
                if (index == 0) {
                    return execute(loan, quote, key()).closure().reason().name();
                }
                return service(loan).kind().name();
            });
            assertThat(outcomes.get(0)).isEqualTo("PAID_OFF_EARLY");
            assertPaidOff(loan, ClosureReason.PAID_OFF_EARLY);
            assertThat(count("SELECT count(*) - count(DISTINCT accrual_date) FROM lending.interest_accrual"
                    + " WHERE loan_id = ?", loan.value())).as("one row per date").isZero();
            assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ? AND accrual_date >= ?",
                    loan.value(), quote.businessDate().minusDays(4))).as("the four dates accrued").isEqualTo(4);
            assertThat(count("SELECT count(*) FROM lending.interest_accrual a WHERE a.loan_id = ? AND a.posted_minor > 0"
                    + " AND NOT EXISTS (SELECT 1 FROM ledger.journal_entry e WHERE e.idempotency_scope ="
                    + " 'ledger.post:lending.accrual:' || a.loan_id || ':' || a.accrual_date)", loan.value()))
                    .as("each posting date its one entry").isZero();
            assertSubledgerProves(loan);
        }
    }

    @Test
    @DisplayName("ten executions, one closure: ten keys executing one quote at once - one execution, one repayment, one"
            + " refund at most, one closure; nine converge on it; a later execution of another quote finds the loan closed,"
            + " and the quote's own key still replays it")
    void tenExecutionsOneClosure() throws Exception {
        LoanId loan = midLife(3_400_00);
        PayoffQuote quote = quote(loan, key(), Optional.empty()).quote();
        List<Payoffs.Executed> all = race(10, index -> execute(loan, quote, key()));
        assertThat(all).filteredOn(executed -> !executed.replayed()).hasSize(1);
        assertThat(all.stream().map(executed -> executed.execution().id()).distinct()).hasSize(1);
        assertThat(all.stream().map(executed -> executed.closure().id()).distinct()).hasSize(1);
        assertThat(count("SELECT count(*) FROM lending.payoff_execution WHERE loan_id = ?", loan.value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ? AND kind = 'PAYOFF'", loan.value()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_closure WHERE loan_id = ?", loan.value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                loan.value(), LoanClosures.CLOSED_EVENT)).isEqualTo(1);
        assertPaidOff(loan, ClosureReason.PAID_OFF_EARLY);

        PayoffQuote replayed = quote(loan, quote.requestKey(), Optional.empty()).quote();
        assertThat(replayed.id()).as("the key replays its quote, closed or not").isEqualTo(quote.id());
        assertThatExceptionOfType(Repayments.LoanNotRepayable.class).isThrownBy(() -> quote(loan, key(), Optional.empty()));
        assertThatExceptionOfType(Repayments.LoanNotRepayable.class)
                .isThrownBy(() -> execute(loan, otherQuoteOf(loan), key()));
    }

    @Test
    @DisplayName("a crash after the payoff's posting is re-driven by another instance with nothing doubled: an instance dying"
            + " after the execution, the catch-up accruals, the early billings, the repayment's entry, the closure and the"
            + " event rows leaves none of them - the loan ACTIVE, the wallet and every balance unmoved; the same quote under"
            + " the same key, re-driven on another thread, pays off once - one execution, one PAYOFF repayment and entry,"
            + " one closure, one LoanClosed, one accrual row and entry per date, all six balances zero")
    void aCrashAfterThePayoffsPostingIsReDrivenOnce() throws Exception {
        LoanId loan = midLife(3_500_00);
        UUID party = partyOf(loan);
        PayoffQuote quote = quote(loan, key(), Optional.empty()).quote();
        assertThat(quote.interestAccrued().isPositive()).as("the quote accrued unbilled days the execution re-uses")
                .isTrue();
        long entries = loanEntries(loan);
        long accrualsBefore = accruals(loan);
        long billingsBefore = count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?", loan.value());
        Money walletBefore = netDebit(walletOf(party)).negated();
        Money owedBefore = owedNow(loan);

        // The instance dies after everything is written - the repayment's event row is the last write of the payoff.
        com.finapp.platform.outbox.OutboxWriter<Connection> crashing = (unitOfWork, envelope, payload, mediaType) -> {
            new JdbcOutboxWriter().write(unitOfWork, envelope, payload, mediaType);
            if (envelope.eventType().equals(Repayments.RECEIVED_EVENT)) {
                throw new IllegalStateException("the instance died after the posting, the closure and the event rows");
            }
        };
        Payoffs dying = new Payoffs(LOAN_STORE, PAYOFF_STORE, TERMS_STORE, SERVICING, SERVICING_STORE, BILLINGS,
                STATEMENTS, BILLING_STORE, REPAYMENT_STORE, WALLETS,
                new AvailableBalance<>(new JdbcBalanceDerivation(), HOLD_STORE), LEDGER_ACCOUNTS, BALANCES,
                DisbursementWorld.repayments(REPAYMENT_STORE, crashing), CLOSURES, LINE_CLOSURES, IDS);
        String executionKey = key();
        assertThatThrownBy(() -> inOneTransaction(uow -> {
            Actor actor = customer(party);
            CorrelationId correlation = correlation();
            try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                    CorrelationContext.Scope flow = CorrelationContext.enter(
                            Correlation.startingWith(correlation).causing(CausationId.of(correlation.value())))) {
                return dying.execute(uow, loan, party, executionKey, quote.id(), actor, correlation);
            }
        })).hasMessageContaining("instance died");

        assertThat(count("SELECT count(*) FROM lending.payoff_execution WHERE loan_id = ?", loan.value()))
                .as("no execution").isZero();
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ? AND kind = 'PAYOFF'", loan.value()))
                .as("no payoff repayment").isZero();
        assertThat(count("SELECT count(*) FROM lending.loan_closure WHERE loan_id = ?", loan.value())).as("no closure")
                .isZero();
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        assertThat(loanEntries(loan)).as("no entry on the loan's accounts").isEqualTo(entries);
        assertThat(accruals(loan)).as("no catch-up accrual").isEqualTo(accrualsBefore);
        assertThat(count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?", loan.value()))
                .as("no early billing").isEqualTo(billingsBefore);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                loan.value(), LoanClosures.CLOSED_EVENT)).as("no LoanClosed").isZero();
        assertThat(netDebit(walletOf(party)).negated()).as("the wallet unmoved").isEqualTo(walletBefore);
        assertThat(owedNow(loan)).as("every balance unmoved").isEqualTo(owedBefore);

        // Another instance re-drives the same quote under the same key - once.
        ExecutorService instance = Executors.newSingleThreadExecutor();
        Payoffs.Executed redriven;
        try {
            redriven = instance.submit(() -> execute(loan, quote, executionKey)).get(60, TimeUnit.SECONDS);
        } finally {
            instance.shutdownNow();
        }
        assertThat(redriven.replayed()).as("re-driven: paid off, not replayed").isFalse();
        assertThat(redriven.repayment().orElseThrow().amount()).isEqualTo(quote.outstanding());
        assertPaidOff(loan, ClosureReason.PAID_OFF_EARLY);
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ? AND kind = 'PAYOFF'", loan.value()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:lending.repayment:" + redriven.repayment().orElseThrow().id()))
                .as("one entry for the payoff's repayment").isEqualTo(1);
        assertThat(count("SELECT count(DISTINCT e.id) FROM ledger.journal_entry e JOIN ledger.journal_line l"
                + " ON l.entry_id = e.id JOIN ledger.ledger_account a ON a.id = l.ledger_account_id WHERE a.owner_ref = ?"
                + " AND e.idempotency_scope LIKE 'ledger.post:lending.repayment:%'", loan.value()))
                .as("the part payment's entry and the payoff's - none left by the dead instance").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                loan.value(), LoanClosures.CLOSED_EVENT)).isEqualTo(1);
        assertThat(count("SELECT count(*) - count(DISTINCT accrual_date) FROM lending.interest_accrual WHERE loan_id = ?",
                loan.value())).as("one accrual row per date").isZero();
        assertThat(netDebit(walletOf(party)).negated()).as("the wallet paid exactly the quote, once")
                .isEqualTo(walletBefore.minus(quote.outstanding()));
        assertThat(execute(loan, quote, executionKey).replayed()).as("a later retry converges").isTrue();
        assertThat(count("SELECT count(*) FROM lending.payoff_execution WHERE loan_id = ?", loan.value())).isEqualTo(1);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("payoff is permitted in any condition: a loan past 90 days and defaulted is paid off and closed - its"
            + " default history kept, nothing evaluated after the closure")
    void payoffIsPermittedInAnyCondition() {
        LoanId loan = pastDue(2_500_00, 95);
        int recorded = 0;
        for (int step = 0; step < 5; step++) {
            LoanServicing.Outcome outcome = service(loan);
            recorded += outcome.conditions();
            if (outcome.caughtUp()) {
                break;
            }
        }
        assertThat(recorded).as("its delinquency and its default recorded").isPositive();
        assertThat(count("SELECT count(*) FROM lending.loan_condition_event WHERE loan_id = ? AND kind = 'DEFAULT'"
                + " AND defaulted", loan.value())).as("defaulted").isEqualTo(1);
        topUp(partyOf(loan), 600_00);
        execute(loan, quote(loan, key(), Optional.empty()).quote(), key());
        assertPaidOff(loan, ClosureReason.PAID_OFF_EARLY);
        assertNoConditionAfterClosure(loan);
        assertThat(service(loan).kind()).as("a closed account is never serviced")
                .isEqualTo(LoanServicing.Outcome.Kind.NOT_SERVICED);
    }

    // ------------------------------------------------------------------ the line

    @Test
    @DisplayName("a line's payoff closes it: drawn, stated once and ten days into its next cycle - the payoff states the"
            + " current cycle early, pays the statements and the principal not yet due, births the line's closure request"
            + " and closes it CLOSED_BY_CUSTOMER, all six balances zero; a CLOSING line's payoff closes it too")
    void aLinesPayoffClosesIt() {
        LocalDate today = businessDate();
        DisbursementWorld.Accepted line = DelinquencyDatabaseTest.statedLine(
                CreditLineStatementDatabaseTest.onOrBefore(today.minusDays(10)), 25);
        topUp(line.party(), 200_00);
        PayoffQuote quote = quote(line.loan(), key(), Optional.empty()).quote();
        assertThat(quote.interestAccrued().isPositive()).as("the current cycle's accrued interest").isTrue();
        Payoffs.Executed executed = execute(line.loan(), quote, key());
        assertThat(executed.closure().reason()).isEqualTo(ClosureReason.CLOSED_BY_CUSTOMER);
        assertPaidOff(line.loan(), ClosureReason.CLOSED_BY_CUSTOMER);
        assertThat(scalar("SELECT outcome FROM lending.line_closure_request WHERE loan_id = ?", line.loan().value()))
                .isEqualTo("CLOSED");
        assertThat(count("SELECT count(*) FROM lending.credit_line_statement WHERE loan_id = ? AND cycle_end > ?",
                line.loan().value(), today)).as("the current cycle stated early").isEqualTo(1);
        CreditLineStatementDatabaseTest.assertLineSubledgerProves(line, 0);

        DisbursementWorld.Accepted closing = DelinquencyDatabaseTest.statedLine(
                CreditLineStatementDatabaseTest.onOrBefore(today.minusDays(8)), 25);
        topUp(closing.party(), 200_00);
        assertThat(CreditLineRepaymentDatabaseTest.requestClosure(closing, key()).request().outcome())
                .isEqualTo(LineClosureOutcome.CLOSING);
        execute(closing.loan(), quote(closing.loan(), key(), Optional.empty()).quote(), key());
        assertPaidOff(closing.loan(), ClosureReason.CLOSED_BY_CUSTOMER);
        CreditLineStatementDatabaseTest.assertLineSubledgerProves(closing, 0);
    }

    @Test
    @DisplayName("a line's credit beyond its statements is refunded at the payoff: repaid far beyond everything it owed and"
            + " drawn again, its quote is negative; the payoff states the current cycle, the credit pays the statement,"
            + " the one repayment pays the principal not yet due (rule 6 never does) and the closure refunds the rest - the"
            + " wallet's net movement exactly the quote, all six balances zero")
    void aLinesCreditBeyondItsStatementsIsRefundedAtThePayoff() {
        LocalDate today = businessDate();
        DisbursementWorld.Accepted line = DelinquencyDatabaseTest.statedLine(
                CreditLineStatementDatabaseTest.onOrBefore(today.minusDays(10)), 25);
        topUp(line.party(), 1_300_00);
        Repayments.Repaid beyond = CreditLineRepaymentDatabaseTest.repay(line, 2_000_00);
        assertThat(beyond.repayment().to(AllocationLine.Target.CREDIT_BALANCE).isPositive()).as("credit held").isTrue();
        CreditLineStatementDatabaseTest.draw(line, 500_00);
        PayoffQuote quote = quote(line.loan(), key(), Optional.empty()).quote();
        assertThat(quote.outstanding().isNegative()).as("the credit exceeds what the line owes").isTrue();
        Money walletBefore = netDebit(walletOf(line.party())).negated();

        Payoffs.Executed executed = execute(line.loan(), quote, key());
        Money paid = executed.repayment().orElseThrow().amount();
        assertThat(paid.isPositive()).as("the principal not yet due paid by the repayment").isTrue();
        assertThat(executed.closure().refund().isPositive()).as("the rest refunded").isTrue();
        assertThat(paid.minus(executed.closure().refund())).as("net, exactly the quote").isEqualTo(quote.outstanding());
        assertThat(netDebit(walletOf(line.party())).negated()).isEqualTo(walletBefore.minus(quote.outstanding()));
        assertPaidOff(line.loan(), ClosureReason.CLOSED_BY_CUSTOMER);
        CreditLineStatementDatabaseTest.assertLineSubledgerProves(line, 0);
    }

    @Test
    @DisplayName("a line's quote after days with nothing drawn projects only its future: accrued for four days, repaid to"
            + " zero, drawn again today - the quote good through three days ahead projects exactly today and the two"
            + " after it, chained onto the cycle's last accrual, never the days nothing was drawn")
    void aLinesQuoteAfterDaysWithNothingDrawnProjectsOnlyItsFuture() {
        LocalDate today = businessDate();
        LocalDate cycleStart = CreditLineStatementDatabaseTest.onOrBefore(today.minusDays(12));
        DisbursementWorld.Accepted line = CreditLineStatementDatabaseTest.lines(1, 2_000_00,
                cycleStart.getDayOfMonth(), 25, false).get(0);
        CreditLineStatementDatabaseTest.openOn(line, cycleStart);
        CreditLineStatementDatabaseTest.dateDraw(CreditLineStatementDatabaseTest.draw(line, 800_00),
                cycleStart.plusDays(1));
        Repayment repaid = CreditLineRepaymentDatabaseTest.repay(line, 800_00).repayment();
        CreditLineRepaymentDatabaseTest.dateRepayment(repaid, cycleStart.plusDays(5));
        InstalmentBillingDatabaseTest.serviceUntilCaughtUp(line.loan());
        assertThat(accruals(line.loan())).as("the four drawn days").isEqualTo(4);
        CreditLineStatementDatabaseTest.draw(line, 500_00);

        PayoffQuote quote = quote(line.loan(), key(), Optional.of(today.plusDays(3))).quote();
        InterestAccrual last = inOneTransaction(uow -> SERVICING_STORE.latestAccrual(uow, line.loan())).orElseThrow()
                .accrual();
        AccrualTerms terms = new AccrualTerms(DisbursementWorld.EUR, CreditLineStatementDatabaseTest.RATE,
                DayCountConvention.ACT_365F, java.math.RoundingMode.HALF_EVEN, EngineVersion.ACCRUAL_ENGINE_V1);
        Optional<InterestAccrual> previous = Optional.of(last);
        Money expected = eur(0);
        for (LocalDate date = today; date.isBefore(today.plusDays(3)); date = date.plusDays(1)) {
            InterestAccrual accrual = AccrualEngineV1.accrue(terms, last.period(), previous, date, eur(500_00));
            expected = expected.plus(accrual.posted());
            previous = Optional.of(accrual);
        }
        assertThat(quote.projectionBase()).isEqualTo(eur(500_00));
        assertThat(quote.projectedInterest()).as("today and the two after it, chained onto the last accrual")
                .isEqualTo(expected);
        assertThat(AccrualEngineV1.project(terms, List.of(last.period()), Optional.of(last), today.plusDays(2),
                eur(500_00)).interest()).as("projecting the undrawn days too would ask more")
                .isGreaterThan(expected);
    }

    // ------------------------------------------------------------------ the database's rules

    @Test
    @DisplayName("the rows are held for every writer: an early billing, a PAYOFF repayment and a PAID_OFF_EARLY closure are"
            + " refused without an execution; an execution off its quote's date, or committed without its closure, is"
            + " refused; a quote past the horizon or with a figure not its parts is refused; neither row is ever updated")
    void theRowsAreHeldForEveryWriter() throws Exception {
        LoanId loan = midLife(2_900_00);
        refusedAlone(InstalmentBillingDatabaseTest.rawBilling(loan, 5, 0, 100_00), "P0001", "payoff");
        refusedAlone(RepaymentDatabaseTest.rawClosure(loan).replace("'REPAID'", "'PAID_OFF_EARLY'"), "P0001",
                "PAID_OFF_EARLY");
        refusedAlone(rawQuote(loan, "1", 31, 0), "P0001", "horizon");
        refusedAlone(rawQuote(loan, "1", 0, 1), "23514", "payoff_quote_outstanding_is_its_parts");
        PayoffQuote quote = quote(loan, key(), Optional.of(businessDate(loan).plusDays(2))).quote();
        refusedAlone(rawExecution(loan, quote), "P0001", "good-through");
        PayoffQuote today = quote(loan, key(), Optional.empty()).quote();
        refusedAlone(rawExecution(loan, today), "P0001", "CLOSED by the payoff");
        refusedAlone("UPDATE lending.payoff_quote SET outstanding_minor = 0 WHERE id = '" + today.id() + "'", "42501",
                "permission denied");
        refusedAlone(RepaymentDatabaseTest.rawRepayment(loan, 100_00, List.of("CREDIT_BALANCE:::10000"))
                .replace("'CUSTOMER'", "'PAYOFF'"), "P0001", "payoff");
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ the world

    /**
     * A fresh party's twelve-month loan, its first instalment billed and part paid on its due date, then moved ten days
     * into its second period with those days unaccrued; the wallet topped up beyond what the loan owes.
     */
    static LoanId midLife(long minor) {
        return midLife(minor, false);
    }

    /** As {@link #midLife(long)}, its first instalment paid in full when {@code current} - nothing past due, no late fee. */
    static LoanId midLife(long minor, boolean current) {
        LoanId loan = disbursed(minor, 12);
        backdate(loan, daysTo(loan, 1));
        assertThat(service(loan).billed()).isEqualTo(1);
        long bill = billings(loan).get(0).billing().billed().minorUnits();
        repay(loan, key(), current ? bill : bill / 2);
        advance(loan, 10);
        topUp(partyOf(loan), minor / 5);
        return loan;
    }

    /** A fresh loan billed on its due date, then {@code days} later with nothing serviced since - past due by them. */
    static LoanId pastDue(long minor, int days) {
        LoanId loan = DelinquencyDatabaseTest.dueToday(minor);
        assertThat(service(loan).billed()).isEqualTo(1);
        advance(loan, days);
        topUp(partyOf(loan), minor / 4);
        return loan;
    }

    static Payoffs.Quoted quote(LoanId loan, String key, Optional<LocalDate> goodThrough) {
        UUID party = partyOf(loan);
        Actor actor = customer(party);
        CorrelationId correlation = correlation();
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation).causing(CausationId.of(correlation.value())))) {
            return inOneTransaction(uow -> PAYOFFS.quote(uow, loan, party, new Payoffs.QuoteRequest(key, goodThrough),
                    correlation));
        }
    }

    static Payoffs.Executed execute(LoanId loan, PayoffQuote quote, String key) {
        return inOneTransaction(uow -> executeOn(uow, loan, quote, key));
    }

    static Payoffs.Executed executeOn(Connection uow, LoanId loan, PayoffQuote quote, String key) {
        UUID party = partyOf(loan);
        Actor actor = customer(party);
        CorrelationId correlation = correlation();
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation).causing(CausationId.of(correlation.value())))) {
            return PAYOFFS.execute(uow, loan, party, key, quote.id(), actor, correlation);
        }
    }

    /** All six balances zero, the loan CLOSED for {@code reason} beside its one closure and its one execution. */
    static void assertPaidOff(LoanId loan, ClosureReason reason) {
        for (AccountPurpose purpose : List.of(AccountPurpose.LOAN_PRINCIPAL, AccountPurpose.LOAN_PRINCIPAL_DUE,
                AccountPurpose.LOAN_INTEREST_ACCRUED, AccountPurpose.LOAN_INTEREST_DUE, AccountPurpose.LOAN_FEES_DUE,
                AccountPurpose.LOAN_CREDIT_BALANCE)) {
            assertThat(netDebit(loanAccount(loan, purpose)).isZero()).as(purpose + " zero").isTrue();
        }
        assertThat(loanStatus(loan)).isEqualTo("CLOSED");
        assertThat(scalar("SELECT closure_reason FROM lending.loan WHERE id = ?", loan.value())).isEqualTo(reason.name());
        assertThat(count("SELECT count(*) FROM lending.loan_closure WHERE loan_id = ? AND reason = ?", loan.value(),
                reason.name())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.payoff_execution WHERE loan_id = ?", loan.value())).isEqualTo(1);
        assertThat(held(loan)).isZero();
    }

    /**
     * No condition change written by the closure's transaction or any after it - judged by the writing transactions' ids
     * (assigned in order, never reused in a fresh test database), never by the rows' {@code created_at}: a database clock
     * stepped back between the last servicing step and the payoff stamps the closure before conditions written before it
     * (`P11-TSK-036` - met once as "expected 0 but was 2").
     */
    static void assertNoConditionAfterClosure(LoanId loan) {
        assertThat(count("SELECT count(*) FROM lending.loan_condition_event e JOIN lending.loan_closure c"
                + " ON c.loan_id = e.loan_id WHERE e.loan_id = ? AND e.xmin::text::bigint >= c.xmin::text::bigint",
                loan.value())).as("no condition after the closure").isZero();
    }

    static long conditions(LoanId loan) {
        return count("SELECT count(*) FROM lending.loan_condition_event WHERE loan_id = ?", loan.value());
    }

    /** What the loan owes now, from the ledger: the five receivables less the credit balance. */
    static Money owedNow(LoanId loan) {
        Money owed = eur(0);
        for (AccountPurpose purpose : List.of(AccountPurpose.LOAN_PRINCIPAL, AccountPurpose.LOAN_PRINCIPAL_DUE,
                AccountPurpose.LOAN_INTEREST_ACCRUED, AccountPurpose.LOAN_INTEREST_DUE, AccountPurpose.LOAN_FEES_DUE)) {
            owed = owed.plus(netDebit(loanAccount(loan, purpose)));
        }
        return owed.minus(eur(held(loan)));
    }

    static long accruals(LoanId loan) {
        return count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?", loan.value());
    }

    /** Every journal entry touching one of the loan's own accounts. */
    static long loanEntries(LoanId loan) {
        return count("SELECT count(DISTINCT l.entry_id) FROM ledger.journal_line l JOIN ledger.ledger_account a"
                + " ON a.id = l.ledger_account_id WHERE a.owner_ref = ?", loan.value());
    }

    /** Money taken out of the wallet - the owner's fixture standing in for a spend elsewhere. */
    static void drain(UUID party, Money amount) {
        InterestAccrualDatabaseTest.postFixture("p11t26-drain:" + UUID.randomUUID(), DisbursementWorld.businessDate(),
                List.of(new com.finapp.ledger.JournalLine(walletOf(party), com.finapp.ledger.Direction.DEBIT, amount),
                        new com.finapp.ledger.JournalLine(DisbursementWorld.cashAtBank(),
                                com.finapp.ledger.Direction.CREDIT, amount)));
    }

    /** A quote of the loan under another key - planted as the owner, the loan already closed. */
    static PayoffQuote otherQuoteOf(LoanId loan) {
        String id = UUID.randomUUID().toString();
        DisbursementWorld.asOwner("INSERT INTO lending.payoff_quote (id, loan_id, request_key, business_date, good_through,"
                + " currency, scale, principal_minor, principal_due_minor, interest_accrued_minor, interest_due_minor,"
                + " fees_due_minor, credit_balance_minor, projection_base_minor, projected_days, projected_interest_minor,"
                + " outstanding_minor, agreement_id, agreement_version, accrual_engine_version, allocation_engine_version,"
                + " created_at, correlation_id) SELECT '" + id + "', a.loan_id, 'p11t26-other', " + businessDateSql(loan)
                + ", " + businessDateSql(loan) + ", 'EUR', 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, a.id, 1, 1, 1,"
                + " statement_timestamp(), 'p11t26-raw' FROM lending.loan_agreement a WHERE a.loan_id = '" + loan.value()
                + "' AND a.version = 1");
        return inOneTransaction(uow -> PAYOFF_STORE.findQuote(uow, loan, UUID.fromString(id))).orElseThrow();
    }

    /** An application-role quote of {@code loan} good through {@code days} ahead, its outstanding off by {@code off}. */
    static String rawQuote(LoanId loan, String agreementVersion, int days, long off) {
        return "INSERT INTO lending.payoff_quote (id, loan_id, request_key, business_date, good_through, currency, scale,"
                + " principal_minor, principal_due_minor, interest_accrued_minor, interest_due_minor, fees_due_minor,"
                + " credit_balance_minor, projection_base_minor, projected_days, projected_interest_minor,"
                + " outstanding_minor, agreement_id, agreement_version, accrual_engine_version, allocation_engine_version,"
                + " created_at, correlation_id) SELECT gen_random_uuid(), a.loan_id, '" + key() + "', "
                + businessDateSql(loan) + ", " + businessDateSql(loan) + " + " + days + ", 'EUR', 2, 100, 0, 0, 0, 0, 0,"
                + " 100, " + days + ", 0, " + (100 + off) + ", a.id, " + agreementVersion + ", 1, 1,"
                + " statement_timestamp(), 'p11t26-raw' FROM lending.loan_agreement a WHERE a.loan_id = '" + loan.value()
                + "' AND a.version = 1";
    }

    /** An application-role execution of {@code quote}, today, alone - no closure beside it. */
    static String rawExecution(LoanId loan, PayoffQuote quote) {
        return "INSERT INTO lending.payoff_execution (id, quote_id, loan_id, request_key, business_date, executed_at,"
                + " correlation_id) VALUES (gen_random_uuid(), '" + quote.id() + "', '" + loan.value() + "', '" + key()
                + "', " + businessDateSql(loan) + ", statement_timestamp(), 'p11t26-raw')";
    }
}
