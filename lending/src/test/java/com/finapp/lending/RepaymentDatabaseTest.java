package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.CLOCK;
import static com.finapp.lending.DisbursementWorld.EUR;
import static com.finapp.lending.DisbursementWorld.HOLDS;
import static com.finapp.lending.DisbursementWorld.REPAYMENTS;
import static com.finapp.lending.DisbursementWorld.REPAYMENT_STORE;
import static com.finapp.lending.DisbursementWorld.STANDING;
import static com.finapp.lending.DisbursementWorld.UNREACHABLE_WALLETS;
import static com.finapp.lending.DisbursementWorld.acceptedLoan;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.awaitBlockedBy;
import static com.finapp.lending.DisbursementWorld.cashAtBank;
import static com.finapp.lending.DisbursementWorld.correlation;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.customer;
import static com.finapp.lending.DisbursementWorld.execute;
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
import static com.finapp.lending.InstalmentBillingDatabaseTest.dues;
import static com.finapp.lending.InstalmentBillingDatabaseTest.partyOf;
import static com.finapp.lending.InstalmentBillingDatabaseTest.projection;
import static com.finapp.lending.InstalmentBillingDatabaseTest.start;
import static com.finapp.lending.InterestAccrualDatabaseTest.backdate;
import static com.finapp.lending.InterestAccrualDatabaseTest.disbursed;
import static com.finapp.lending.InterestAccrualDatabaseTest.eur;
import static com.finapp.lending.InterestAccrualDatabaseTest.postFixture;
import static com.finapp.lending.InterestAccrualDatabaseTest.refused;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.lending.InstalmentBillingStore.BilledItem;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyInProgressException;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The customer's repayment from the wallet and the closure it brings, against a real database (`P11-TSK-018`;
 * {@code lending V014}, {@code PHASE_11_PLAN.md} sections 7.1 T-rep, 7.4, 12.5 and 12.8 rule 5, {@code PHASE_11_PLAN.md}
 * section 13.2 scenarios 2 and 3; ADR-0095 points 3 and 4; {@code INV-LND-04}, {@code INV-LND-08}, {@code INV-LND-01},
 * {@code INV-IDEM-01}, {@code INV-CON-01}, {@code INV-BAL-04}, {@code INV-BAL-05}, {@code INV-LIFE-04}): every figure read
 * from the rows and the ledger, and every case closing on the subledger proof's arms ({@link #assertSubledgerProves}).
 *
 * <p>Loans are disbursed by the real T-dis to the world's real wallet ledger account and billed by the real T-svc; a
 * repayment runs the real {@link Repayments} over the real ledger, its {@link com.finapp.ledger.AvailableBalance} and its
 * holds. An elapsed history is made by moving a loan's dated facts into the past as the bootstrap owner
 * ({@link InstalmentBillingDatabaseTest#advance}, which moves the repayments' value dates with them) - the fixture's clock,
 * never a path. A wallet topped up beyond the disbursed principal is an owner-role ledger fixture (the wallet's pay-ins are
 * payments', not lending's).
 */
@Tag("database")
@DisplayName("the customer's repayment from the wallet and the closure (P11-TSK-018)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class RepaymentDatabaseTest {

    static final IdempotentExecutor DOOR_CLAIM = new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK,
            Duration.ofDays(1), Duration.ofMinutes(5));

    // ------------------------------------------------------------------ scenario 2: the same repayment twice

    @Test
    @DisplayName("scenario 2 - the same repayment twice repays once: ten attempts under one key through the door's claim -"
            + " one runs, the rest replay its receipt or hear it in progress, a later retry replays the same answer; with"
            + " the claim lapsed, UNIQUE (loan_id, kind, request_key) behind L3 converges nine on the one; another amount"
            + " under the key is refused - one repayment, one entry, one event")
    void theSameRepaymentTwiceRepaysOnce() throws Exception {
        LoanId loan = billedLoan(5_000_00);
        long bill = billings(loan).get(0).billing().billed().minorUnits();
        String key = key();
        List<String> outcomes = race(10, index -> {
            try {
                return throughTheClaim(loan, key, 300_00).executed() ? "EXECUTED" : "REPLAYED";
            } catch (IdempotencyInProgressException inProgress) {
                return "IN_PROGRESS";
            }
        });
        assertThat(outcomes).filteredOn("EXECUTED"::equals).hasSize(1);
        assertRepaidOnce(loan, key, 300_00);
        IdempotentExecutor.ExecutionOutcome retry = throughTheClaim(loan, key, 300_00);
        assertThat(retry.executed()).as("a later retry replays").isFalse();
        assertThat(new String(retry.body().orElseThrow(), StandardCharsets.UTF_8)).as("the same answer")
                .isEqualTo(REPAYMENT_STORE.findByKey(DatabaseRoles.application(), loan, RepaymentKind.CUSTOMER, key)
                        .orElseThrow().id().toString());
        assertThat(due(loan)).isEqualTo(bill - 300_00);

        LoanId lapsed = billedLoan(5_100_00);
        String lapsedKey = key();
        List<Boolean> replayed = race(10, index -> repay(lapsed, lapsedKey, 250_00).replayed());
        assertThat(replayed).filteredOn(Boolean.FALSE::equals).as("one repayment made").hasSize(1);
        assertThat(replayed).filteredOn(Boolean.TRUE::equals).as("nine converged on it").hasSize(9);
        assertRepaidOnce(lapsed, lapsedKey, 250_00);
        assertThatExceptionOfType(Repayments.RepaymentKeyReused.class).isThrownBy(() -> repay(lapsed, lapsedKey, 250_01));
        assertRepaidOnce(lapsed, lapsedKey, 250_00);
        assertSubledgerProves(loan);
        assertSubledgerProves(lapsed);
    }

    // ------------------------------------------------------------------ scenario 3: two repayments on one instalment

    @Test
    @DisplayName("scenario 3 - two repayments on one instalment serialise and conserve: two keys, each 70 % of the bill,"
            + " racing beside a writer that takes the loan then the wallet (the servicing step's closure order) - both"
            + " admitted, the second allocating what the first left: the bill paid exactly, the rest held as credit,"
            + " sum of allocations = sum of amounts, no due account negative, never over-allocated, no deadlock")
    void twoRepaymentsOnOneInstalmentSerialiseAndConserve() throws Exception {
        for (int round = 0; round < 3; round++) {
            LoanId loan = billedLoan(5_200_00 + round * 100_00);
            UUID party = partyOf(loan);
            LedgerAccountId wallet = walletOf(party);
            long bill = billings(loan).get(0).billing().billed().minorUnits();
            long each = bill * 7 / 10;
            List<String> outcomes = race(3, index -> {
                if (index == 2) {
                    holdTheLoanThenTheWallet(loan, wallet);
                    return "WRITER";
                }
                return repay(loan, key(), each).replayed() ? "REPLAYED" : "REPAID";
            });
            assertThat(outcomes).containsExactlyInAnyOrder("REPAID", "REPAID", "WRITER");
            List<Repayment> both = repayments(loan);
            assertThat(both).hasSize(2);
            assertThat(both).allSatisfy(repayment -> assertThat(sum(repayment)).isEqualTo(repayment.amount()));
            BilledItem item = billings(loan).get(0);
            assertThat(item.outstanding().isZero()).as("the bill paid exactly").isTrue();
            assertThat(item.paid().minorUnits()).as("never over-allocated").isEqualTo(bill);
            assertThat(due(loan)).isZero();
            assertThat(held(loan)).as("the rest held").isEqualTo(2 * each - bill);
            Repayment first = both.get(1);
            Repayment second = both.get(0);
            assertThat(first.to(AllocationLine.Target.CREDIT_BALANCE).isZero()).as("the first finds the whole bill due")
                    .isTrue();
            assertThat(second.to(AllocationLine.Target.CREDIT_BALANCE).minorUnits())
                    .as("the second allocates what the first left, and holds the rest").isEqualTo(2 * each - bill);
            assertSubledgerProves(loan);
        }
    }

    @Test
    @DisplayName("ten keys on one loan serialise and conserve: ten repayments racing across a bill - every one admitted in"
            + " some order, each allocating what it found; the bill paid exactly once over, the rest held; ten entries,"
            + " ten events, sum of allocations = sum of amounts")
    void tenKeysOneLoanConserve() throws Exception {
        LoanId loan = billedLoan(4_800_00);
        long bill = billings(loan).get(0).billing().billed().minorUnits();
        long each = bill / 7;
        List<Boolean> replayed = race(10, index -> repay(loan, key(), each).replayed());
        assertThat(replayed).containsOnly(false).hasSize(10);
        List<Repayment> all = repayments(loan);
        assertThat(all).hasSize(10);
        assertThat(all).allSatisfy(repayment -> assertThat(sum(repayment)).isEqualTo(repayment.amount()));
        assertThat(billings(loan).get(0).outstanding().isZero()).isTrue();
        assertThat(held(loan)).isEqualTo(10 * each - bill);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope LIKE 'ledger.post:"
                + Repayments.POSTING_KEY_PREFIX + "%' AND reference = ?", loan.value().toString())).isEqualTo(10);
        assertThat(events(loan, Repayments.RECEIVED_EVENT)).isEqualTo(10);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("a repayment racing the billing on the due date serialises on L3 and ends the same in either order:"
            + " first, it is held and the billing's credit-balance application pays the bill; second, it pays the bill"
            + " and holds the rest - either way the bill is paid exactly and the excess held, never twice")
    void aRepaymentRacingTheBillingOnTheDueDateConserves() throws Exception {
        for (int round = 0; round < 3; round++) {
            LoanId loan = disbursed(4_600_00 + round * 100_00, 12);
            backdate(loan, daysTo(loan, 1));
            long projected = projection(loan).get(0)[0] + projection(loan).get(0)[1];
            long amount = projected + 50_00;
            race(2, index -> index == 0 ? service(loan).billed() : repay(loan, key(), amount).repayment().hashCode());
            List<BilledItem> billed = billings(loan);
            assertThat(billed).hasSize(1);
            long bill = billed.get(0).billing().billed().minorUnits();
            assertThat(billed.get(0).outstanding().isZero()).as("the bill paid, once").isTrue();
            assertThat(held(loan)).as("the excess held").isEqualTo(amount - bill);
            assertThat(due(loan)).isZero();
            assertSubledgerProves(loan);
        }
    }

    // ------------------------------------------------------------------ the cases of the allocation

    @Test
    @DisplayName("a partial repayment leaves the remainder due: interest first, then part of the principal (the pinned"
            + " order) - the item's remainder stays due on the ledger and in its rows, nothing held; rule 5's entry"
            + " debits the wallet and credits each due account once, dated today, under lending.repayment:<id>")
    void partialLeavesTheRemainderDue() {
        LoanId loan = billedLoan(5_300_00);
        BilledItem before = billings(loan).get(0);
        long interest = before.billing().interestDue().minorUnits();
        long amount = interest + 100_00;
        Repayment repayment = repay(loan, key(), amount).repayment();
        assertThat(repayment.allocations()).extracting(allocation -> allocation.line().component().orElseThrow())
                .containsExactly(AllocationComponent.INTEREST, AllocationComponent.PRINCIPAL);
        assertThat(repayment.paid(AllocationComponent.INTEREST).minorUnits()).isEqualTo(interest);
        assertThat(repayment.paid(AllocationComponent.PRINCIPAL).minorUnits()).isEqualTo(100_00);
        BilledItem after = billings(loan).get(0);
        assertThat(after.outstanding().minorUnits()).isEqualTo(before.billing().billed().minorUnits() - amount);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_DUE)).minorUnits()).isZero();
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE)).minorUnits())
                .isEqualTo(after.outstanding().minorUnits());
        assertThat(held(loan)).isZero();
        assertThat(count("SELECT count(*) FROM ledger.journal_line l JOIN ledger.journal_entry e ON e.id = l.entry_id"
                + " WHERE e.id = ? AND e.idempotency_scope = ? AND e.posting_date = ? AND e.value_date = ?",
                repayment.journalEntry().value(), "ledger.post:" + Repayments.POSTING_KEY_PREFIX + repayment.id(),
                today(), today())).as("one wallet debit, one credit per due account").isEqualTo(3);
        assertThat(repayment.valueDate()).isEqualTo(today());
        assertThat(-netDebit(walletOf(partyOf(loan))).minorUnits()).isEqualTo(5_300_00 - amount);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("an over-payment goes to the credit balance: the bill paid, the rest held (HOLD_AS_CREDIT - an instalment"
            + " loan's principal is never paid down by an overpayment); RepaymentReceived summarises each component")
    void overGoesToTheCreditBalance() {
        LoanId loan = billedLoan(5_400_00);
        BilledItem item = billings(loan).get(0);
        long bill = item.billing().billed().minorUnits();
        Repayment repayment = repay(loan, key(), bill + 123_45).repayment();
        assertThat(repayment.to(AllocationLine.Target.CREDIT_BALANCE).minorUnits()).isEqualTo(123_45);
        assertThat(repayment.to(AllocationLine.Target.NOT_DUE_PRINCIPAL).isZero()).isTrue();
        assertThat(billings(loan).get(0).outstanding().isZero()).isTrue();
        assertThat(held(loan)).isEqualTo(123_45);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits())
                .as("principal not yet due untouched").isEqualTo(5_400_00 - item.billing().principalDue().minorUnits());
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?"
                + " AND convert_from(payload, 'UTF8') LIKE ? AND convert_from(payload, 'UTF8') LIKE ?"
                + " AND convert_from(payload, 'UTF8') LIKE ?", loan.value(), Repayments.RECEIVED_EVENT,
                "%" + repayment.id() + "%", "%\"creditHeldMinor\":\"12345\"%", "%\"closed\":\"false\"%")).isEqualTo(1);
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("an early repayment is held, then applied: before any billing nothing is due - all of it held as credit;"
            + " on the due date the servicing step bills the instalment and the credit balance pays it (rule 6, the same"
            + " engine), the rest still held")
    void earlyRepaymentIsHeldThenApplied() {
        LoanId loan = disbursed(5_500_00, 12);
        Repayment early = repay(loan, key(), 1_000_00).repayment();
        assertThat(early.allocations()).hasSize(1);
        assertThat(early.to(AllocationLine.Target.CREDIT_BALANCE).minorUnits()).isEqualTo(1_000_00);
        assertThat(held(loan)).isEqualTo(1_000_00);
        backdate(loan, daysTo(loan, 1));
        LoanServicing.Outcome step = service(loan);
        assertThat(step.billed()).isEqualTo(1);
        assertThat(step.applied()).isEqualTo(1);
        long bill = billings(loan).get(0).billing().billed().minorUnits();
        assertThat(billings(loan).get(0).outstanding().isZero()).isTrue();
        assertThat(held(loan)).isEqualTo(1_000_00 - bill);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("a late repayment raises the next interest due (-017's owner-role fixture re-asserted through a real"
            + " repayment): instalment 1 paid five days late - instalment 2 bills five days more interest on the whole"
            + " principal, and its principal portion is lower by the same")
    void aLateRepaymentRaisesTheNextInterestDue() {
        LoanId loan = disbursed(8_000_00, 12);
        List<long[]> projection = projection(loan);
        List<LocalDate> dues = dues(loan);
        backdate(loan, Math.toIntExact(ChronoUnit.DAYS.between(start(loan), dues.get(0))));
        assertThat(service(loan).billed()).isEqualTo(1);
        InstalmentBilling first = billings(loan).get(0).billing();
        advance(loan, 5);
        repay(loan, key(), first.billed().minorUnits());
        int gap = Math.toIntExact(ChronoUnit.DAYS.between(dues.get(0), dues.get(1)));
        advance(loan, gap - 5);
        assertThat(service(loan).billed()).isEqualTo(1);
        InstalmentBilling second = billings(loan).get(1).billing();
        long principalAfter = 8_000_00L - projection.get(0)[1];
        // The loan's own pinned rate - another suite may have brought a different terms version into force meanwhile.
        BigDecimal rate = new BigDecimal(scalar("SELECT nominal_annual_rate::text FROM lending.loan_agreement"
                + " WHERE loan_id = ? AND version = 1", loan.value()));
        BigDecimal exact = BigDecimal.valueOf(8_000_00L * 5 + principalAfter * (gap - 5), 2).multiply(rate)
                .divide(BigDecimal.valueOf(365), 20, RoundingMode.HALF_EVEN);
        long expected = exact.setScale(2, RoundingMode.HALF_EVEN).movePointRight(2).longValueExact();
        assertThat(second.interestDue().minorUnits()).as("the interest actually accrued").isEqualTo(expected);
        assertThat(second.interestDue().minorUnits()).isGreaterThan(projection.get(1)[0]);
        long level = count("SELECT instalment_minor FROM lending.repayment_schedule WHERE loan_id = ?", loan.value());
        assertThat(second.principalDue().minorUnits()).isEqualTo(level - expected);
        assertSubledgerProves(loan);
    }

    // ------------------------------------------------------------------ closure

    @Test
    @DisplayName("the last repayment closes and refunds: a six-month loan billed and paid on each due date, the last"
            + " repayment 50.00 over - in its transaction the loan CLOSED (REPAID) beside its loan_closure, the 50.00"
            + " refunded to the wallet under lending.closure-refund:<loan>, every one of the six balances zero, the history"
            + " row and lending.LoanClosed; servicing never claims it again")
    void theLastRepaymentClosesAndRefunds() {
        LoanId loan = disbursed(3_000_00, 6);
        UUID party = partyOf(loan);
        topUp(party, 500_00);
        Repayments.Repaid last = payEveryInstalment(loan, 50_00);
        assertThat(last.closure()).isPresent();
        LoanClosure closure = last.closure().get();
        assertThat(closure.reason()).isEqualTo(ClosureReason.REPAID);
        assertThat(closure.refund().minorUnits()).isEqualTo(50_00);
        assertThat(closure.repayment()).contains(last.repayment().id());
        assertThat(loanStatus(loan)).isEqualTo("CLOSED");
        assertThat(scalar("SELECT closure_reason FROM lending.loan WHERE id = ?", loan.value())).isEqualTo("REPAID");
        assertThat(count("SELECT count(*) FROM lending.loan WHERE id = ? AND closed_at IS NOT NULL", loan.value()))
                .isEqualTo(1);
        for (AccountPurpose purpose : List.of(AccountPurpose.LOAN_PRINCIPAL, AccountPurpose.LOAN_PRINCIPAL_DUE,
                AccountPurpose.LOAN_INTEREST_ACCRUED, AccountPurpose.LOAN_INTEREST_DUE, AccountPurpose.LOAN_FEES_DUE,
                AccountPurpose.LOAN_CREDIT_BALANCE)) {
            assertThat(netDebit(loanAccount(loan, purpose)).minorUnits()).as(purpose + " zero after closure").isZero();
        }
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ? AND id = ?",
                "ledger.post:" + LoanClosures.REFUND_KEY_PREFIX + loan.value(), closure.refundEntry().orElseThrow().value()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_event WHERE loan_id = ? AND from_status = 'ACTIVE'"
                + " AND to_status = 'CLOSED' AND reason = 'REPAID'", loan.value())).isEqualTo(1);
        assertThat(events(loan, LoanClosures.CLOSED_EVENT)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?"
                + " AND convert_from(payload, 'UTF8') LIKE '%\"closed\":\"true\"%'", loan.value(),
                Repayments.RECEIVED_EVENT)).isEqualTo(1);
        long repaid = count("SELECT sum(amount_minor) FROM lending.repayment WHERE loan_id = ?", loan.value());
        assertThat(-netDebit(walletOf(party)).minorUnits()).as("the wallet: disbursed, topped up, repaid, refunded")
                .isEqualTo(3_000_00 + 500_00 - repaid + 50_00);
        assertThat(service(loan).kind()).as("a closed account leaves servicing")
                .isEqualTo(LoanServicing.Outcome.Kind.NOT_SERVICED);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("a credit balance that settles the loan closes it at the servicing step: a six-month loan's whole"
            + " schedule paid ahead plus 75.00 - each billing applies the credit balance, and the step that applies the"
            + " last closes the loan REPAID with no repayment named, refunding what is left; the account leaves servicing")
    void aCreditBalanceThatSettlesTheLoanClosesAtServicing() {
        LoanId loan = disbursed(3_100_00, 6);
        UUID party = partyOf(loan);
        topUp(party, 500_00);
        long scheduled = count("SELECT sum(amount_minor) FROM lending.loan_instalment WHERE loan_id = ?", loan.value());
        repay(loan, key(), scheduled + 75_00);
        List<LocalDate> dues = dues(loan);
        LoanServicing.Outcome step = null;
        for (int j = 0; j < dues.size(); j++) {
            if (j == 0) {
                backdate(loan, daysTo(loan, 1));
            } else {
                advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(dues.get(j - 1), dues.get(j))));
            }
            step = service(loan);
            assertThat(step.applied()).isEqualTo(1);
            assertThat(step.closed()).as("closed only by the last").isEqualTo(j == dues.size() - 1);
        }
        long billed = count("SELECT sum(interest_due_minor + principal_due_minor) FROM lending.instalment_billing"
                + " WHERE loan_id = ?", loan.value());
        assertThat(loanStatus(loan)).isEqualTo("CLOSED");
        long refund = count("SELECT refund_minor FROM lending.loan_closure WHERE loan_id = ? AND repayment_id IS NULL",
                loan.value());
        assertThat(refund).isEqualTo(scheduled + 75_00 - billed);
        assertThat(held(loan)).isZero();
        assertThat(service(loan).kind()).isEqualTo(LoanServicing.Outcome.Kind.NOT_SERVICED);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("a loan is not closed over an unaccrued day: the final instalment paid two days late before the servicing"
            + " step has accrued those days - the recorded receivables reach zero but principal was outstanding at the"
            + " end of each, so the loan stays ACTIVE, the excess held; the step then accrues the two days' interest")
    void aLoanIsNotClosedOverAnUnaccruedDay() {
        LoanId loan = disbursed(3_200_00, 6);
        topUp(partyOf(loan), 500_00);
        List<LocalDate> dues = dues(loan);
        backdate(loan, daysTo(loan, 1));
        for (int j = 0; j < dues.size(); j++) {
            if (j > 0) {
                advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(dues.get(j - 1), dues.get(j))));
            }
            service(loan);
            if (j < dues.size() - 1) {
                repay(loan, key(), billings(loan).get(j).outstanding().minorUnits());
            }
        }
        advance(loan, 2);
        long owed = billings(loan).get(dues.size() - 1).outstanding().minorUnits();
        Repayments.Repaid late = repay(loan, key(), owed + 10_00);
        assertThat(late.closure()).as("two ended dates carried principal and were never accrued").isEmpty();
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        assertThat(due(loan)).isZero();
        assertThat(held(loan)).isEqualTo(10_00);
        LoanServicing.Outcome step = service(loan);
        assertThat(step.posted()).as("the two days accrued on the principal that was due").isEqualTo(2);
        assertThat(step.closed()).isFalse();
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_ACCRUED)).isPositive()).isTrue();
        assertSubledgerProves(loan);
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    @Test
    @DisplayName("a closed loan refuses: a repayment to a loan closed a moment earlier is 409 lending.LoanNotRepayable with"
            + " nothing posted - as is one awaiting its disbursement (a closed credit line's refusal is"
            + " CreditLineRepaymentDatabaseTest's, P11-TSK-023); another party's loan is not found and never locked")
    void aClosedLoanRefuses() {
        LoanId loan = disbursed(3_300_00, 6);
        topUp(partyOf(loan), 500_00);
        payEveryInstalment(loan, 0);
        assertThat(loanStatus(loan)).isEqualTo("CLOSED");
        long entries = walletEntries(partyOf(loan));
        assertThatExceptionOfType(Repayments.LoanNotRepayable.class).isThrownBy(() -> repay(loan, key(), 10_00));
        assertThat(walletEntries(partyOf(loan))).as("nothing posted").isEqualTo(entries);

        DisbursementWorld.Accepted pending = acceptedLoan(1_000_00, 12);
        assertThatExceptionOfType(Repayments.LoanNotRepayable.class)
                .isThrownBy(() -> repayAs(REPAYMENTS, pending.party(), pending.loan(), key(), 10_00));
        LoanId active = billedLoan(3_400_00);
        assertThatExceptionOfType(Repayments.LoanNotFound.class)
                .isThrownBy(() -> repayAs(REPAYMENTS, UUID.randomUUID(), active, key(), 10_00));
        assertThat(repayments(active)).isEmpty();
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ?", pending.loan().value())).isZero();
    }

    @Test
    @DisplayName("insufficient funds post nothing: an amount above the wallet's available balance - settled less its"
            + " standing holds, judged under the wallet's row lock - is 422 lending.InsufficientFunds, as is a wallet"
            + " that does not exist or is suspended; no repayment, no entry, no event, the key unspent")
    void insufficientFundsPostsNothing() {
        LoanId loan = billedLoan(3_500_00);
        UUID party = partyOf(loan);
        LedgerAccountId wallet = walletOf(party);
        assertThatExceptionOfType(Repayments.InsufficientFunds.class)
                .isThrownBy(() -> repay(loan, key(), 3_500_01));
        // A hold makes settled money unavailable (INV-BAL-04).
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation()).causing(CausationId.of("p11t18")))) {
            inOneTransaction(uow -> HOLDS.place(uow, wallet, eur(3_000_00)));
        }
        String key = key();
        assertThatExceptionOfType(Repayments.InsufficientFunds.class).isThrownBy(() -> repay(loan, key, 600_00));
        assertThat(repayments(loan)).isEmpty();
        assertThat(events(loan, Repayments.RECEIVED_EVENT)).isZero();
        assertThat(repay(loan, key, 500_00).replayed()).as("the key was not spent; within the available it repays")
                .isFalse();
        // A suspended wallet, and none at all.
        asOwner("UPDATE ledger.ledger_account SET status = 'POSTING_SUSPENDED' WHERE id = '" + wallet.value() + "'");
        try {
            assertThatExceptionOfType(Repayments.InsufficientFunds.class).isThrownBy(() -> repay(loan, key(), 1_00));
        } finally {
            asOwner("UPDATE ledger.ledger_account SET status = 'ACTIVE' WHERE id = '" + wallet.value() + "'");
        }
        UNREACHABLE_WALLETS.add(party);
        try {
            assertThatExceptionOfType(Repayments.InsufficientFunds.class).isThrownBy(() -> repay(loan, key(), 1_00));
        } finally {
            UNREACHABLE_WALLETS.remove(party);
        }
        assertThat(repayments(loan)).hasSize(1);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("a suspended customer may still repay: no standing check (unlike a draw or an acceptance) - the"
            + " repayment allocates and posts")
    void aSuspendedCustomerMayStillRepay() {
        LoanId loan = billedLoan(3_600_00);
        UUID party = partyOf(loan);
        STANDING.put(party, false);
        try {
            assertThat(repay(loan, key(), 100_00).replayed()).isFalse();
        } finally {
            STANDING.remove(party);
        }
        assertThat(repayments(loan)).hasSize(1);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("the repayment takes the loan, then the wallet: behind a transaction holding the loan (L3) it waits there"
            + " with the wallet's row free; behind one holding the wallet's row (L7) it waits there holding the loan")
    void repaymentTakesTheLoanThenTheWallet() throws Exception {
        LoanId loan = billedLoan(3_700_00);
        LedgerAccountId wallet = walletOf(partyOf(loan));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            try (Connection holder = DatabaseRoles.application()) {
                holder.setAutoCommit(false);
                lock(holder, "SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE", loan.value());
                Future<Repayments.Repaid> repaying = pool.submit(() -> repay(loan, key(), 10_00));
                assertThat(awaitBlockedBy(pid(holder)).query()).as("waits on the loan (L3)").contains("lending.loan");
                assertThat(lockableNoWait("SELECT 1 FROM ledger.ledger_account WHERE id = ? FOR UPDATE NOWAIT",
                        wallet.value())).as("before the loan, no wallet row is held").isTrue();
                holder.rollback();
                assertThat(repaying.get(60, TimeUnit.SECONDS).replayed()).isFalse();
            }
            try (Connection holder = DatabaseRoles.application()) {
                holder.setAutoCommit(false);
                lock(holder, "SELECT 1 FROM ledger.ledger_account WHERE id = ? FOR UPDATE", wallet.value());
                Future<Repayments.Repaid> repaying = pool.submit(() -> repay(loan, key(), 10_00));
                assertThat(awaitBlockedBy(pid(holder)).query()).as("waits on the wallet's row (L7)")
                        .contains("ledger_account");
                assertThat(lockableNoWait("SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE NOWAIT", loan.value()))
                        .as("holding the loan while it waits").isFalse();
                holder.rollback();
                assertThat(repaying.get(60, TimeUnit.SECONDS).replayed()).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(repayments(loan)).hasSize(2);
    }

    @Test
    @DisplayName("the repayment, its rows, its entry and its event are one transaction: an instance dying after the event"
            + " row leaves none of them - the wallet and the bill unmoved - and the same key, re-driven, repays once")
    void aCrashAfterThePostingLeavesNothing() {
        LoanId loan = billedLoan(3_800_00);
        long bill = billings(loan).get(0).billing().billed().minorUnits();
        OutboxWriter<Connection> crashing = (unitOfWork, envelope, payload, mediaType) -> {
            new JdbcOutboxWriter().write(unitOfWork, envelope, payload, mediaType);
            if (envelope.eventType().equals(Repayments.RECEIVED_EVENT)) {
                throw new IllegalStateException("the instance died after the posting, the rows and the event row");
            }
        };
        Repayments dying = DisbursementWorld.repayments(REPAYMENT_STORE, crashing);
        String key = key();
        assertThatThrownBy(() -> repayAs(dying, partyOf(loan), loan, key, 200_00)).hasMessageContaining("instance died");
        assertThat(repayments(loan)).isEmpty();
        assertThat(events(loan, Repayments.RECEIVED_EVENT)).isZero();
        assertThat(due(loan)).isEqualTo(bill);
        assertThat(-netDebit(walletOf(partyOf(loan))).minorUnits()).isEqualTo(3_800_00);
        assertThat(repay(loan, key, 200_00).replayed()).as("re-driven: repaid, once").isFalse();
        assertRepaidOnce(loan, key, 200_00);
    }

    @Test
    @DisplayName("a subledger that does not explain the ledger is refused: a due account moved by a planted entry - the"
            + " repayment is an AllocationAnomaly, nothing allocated over it, nothing written")
    void anUnexplainedSubledgerRefuses() {
        LoanId loan = billedLoan(3_900_00);
        postFixture("p11t18-unexplained:" + UUID.randomUUID(), today(), List.of(
                new JournalLine(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE), Direction.DEBIT, eur(1_00)),
                new JournalLine(cashAtBank(), Direction.CREDIT, eur(1_00))));
        assertThatExceptionOfType(AllocationAnomaly.class).isThrownBy(() -> repay(loan, key(), 100_00))
                .withMessageContaining("INV-LND-01");
        assertThat(repayments(loan)).isEmpty();
    }

    @Test
    @DisplayName("the due state is derived from the rows and the journal lines, never the projection: a drifted"
            + " projection row of the loan's interest due (the display-only balance, planted) changes nothing - the"
            + " repayment pays exactly what was billed and holds the rest")
    void theDueStateIsDerivedFromRowsNotTheProjection() {
        LoanId loan = billedLoan(4_100_00);
        BilledItem item = billings(loan).get(0);
        long bill = item.billing().billed().minorUnits();
        LedgerAccountId interestDue = loanAccount(loan, AccountPurpose.LOAN_INTEREST_DUE);
        asOwner("UPDATE ledger.account_balance SET posted_minor = posted_minor + 50000 WHERE ledger_account_id = '"
                + interestDue.value() + "'");
        Repayment repayment = repay(loan, key(), bill + 10_00).repayment();
        assertThat(repayment.paid(AllocationComponent.INTEREST)).isEqualTo(item.billing().interestDue());
        assertThat(repayment.paid(AllocationComponent.PRINCIPAL)).isEqualTo(item.billing().principalDue());
        assertThat(repayment.to(AllocationLine.Target.CREDIT_BALANCE).minorUnits()).isEqualTo(10_00);
        assertThat(due(loan)).isZero();
        assertSubledgerProves(loan);
    }

    // ------------------------------------------------------------------ every writer

    @Test
    @DisplayName("the rows are held for every writer: a repayment's rows not summing to it, or paying a billed component"
            + " beyond what it billed, refused at commit; principal not yet due on an instalment loan refused; an"
            + " incoherent row refused; the rows immutable; a closure while anything is unpaid refused; CLOSED only beside"
            + " a closure; a closed loan takes no repayment and never moves again")
    void theRowsAreHeldForEveryWriter() throws Exception {
        LoanId loan = billedLoan(4_000_00);
        BilledItem item = billings(loan).get(0);
        String billing = item.billing().id().toString();
        long interest = item.billing().interestDue().minorUnits();
        refusedAlone(rawRepayment(loan, 100_00, List.of("DUE_ITEM:" + billing + ":INTEREST:" + Math.min(interest, 50_00),
                "CREDIT_BALANCE::: 1")), "P0001", "sum to it exactly");
        refusedAlone(rawRepayment(loan, interest + 1, List.of("DUE_ITEM:" + billing + ":INTEREST:" + (interest + 1))),
                "P0001", "beyond what it billed");
        refusedAlone(rawRepayment(loan, 1_00, List.of("NOT_DUE_PRINCIPAL:::100")), "P0001", "never pays principal");
        refusedAlone(rawRepayment(loan, 1_00, List.of("CREDIT_BALANCE:" + billing + ":INTEREST:100")), "23514", "");
        refusedAlone(rawClosure(loan), "P0001", "every instalment of its schedule is billed");
        LoanId billedThrough = disbursed(2_000_00, 6);
        backdate(billedThrough, daysTo(billedThrough, 6));
        InstalmentBillingDatabaseTest.serviceUntilCaughtUp(billedThrough);
        assertThat(billings(billedThrough)).hasSize(6);
        refusedAlone(rawClosure(billedThrough), "P0001", "paid in full");
        refusedAlone("UPDATE lending.loan SET status = 'CLOSED', closure_reason = 'REPAID' WHERE id = '" + loan.value()
                + "'", "P0001", "only beside its closure");
        repay(loan, key(), 10_00);
        try (Connection app = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            refused(app, "UPDATE lending.repayment SET amount_minor = 1 WHERE loan_id = '" + loan.value() + "'", "42501", "");
            refused(app, "DELETE FROM lending.repayment_allocation", "42501", "");
            refused(migrator, "UPDATE lending.repayment SET amount_minor = 1 WHERE loan_id = '" + loan.value() + "'",
                    "P0001", "immutable");
            refused(migrator, "DELETE FROM lending.repayment_allocation WHERE repayment_id IN (SELECT id FROM"
                    + " lending.repayment WHERE loan_id = '" + loan.value() + "')", "P0001", "immutable");
            // CASCADE since lending V016 (P11-TSK-020): the negating rows reference the allocation rows, so a plain
            // TRUNCATE is refused by the foreign key (0A000) before any trigger; with CASCADE the triggers refuse it.
            refused(migrator, "TRUNCATE lending.repayment_allocation CASCADE", "P0001", "");
        }

        LoanId closed = disbursed(3_000_00, 6);
        topUp(partyOf(closed), 500_00);
        payEveryInstalment(closed, 0);
        refusedAlone(rawRepayment(closed, 1_00, List.of("CREDIT_BALANCE:::100")), "P0001", "ACTIVE instalment loan");
        refusedAlone("UPDATE lending.loan SET status = 'ACTIVE', closure_reason = NULL WHERE id = '" + closed.value()
                + "'", "P0001", "");
        refusedAlone(rawClosure(closed), "P0001", "");
        try (Connection migrator = DatabaseRoles.migrator()) {
            refused(migrator, "UPDATE lending.loan_closure SET refund_minor = 1 WHERE loan_id = '" + closed.value() + "'",
                    "P0001", "immutable");
        }
    }

    // ------------------------------------------------------------------ the world's door

    /** A fresh party's loan, disbursed to its wallet and billed its first instalment, nothing paid. */
    static LoanId billedLoan(long minor) {
        LoanId loan = disbursed(minor, 12);
        backdate(loan, daysTo(loan, 1));
        assertThat(service(loan).billed()).isEqualTo(1);
        return loan;
    }

    /**
     * Bills and pays each instalment of {@code loan} on its due date - the last {@code over} more - and answers the last
     * repayment. The loan is fresh (nothing billed); its wallet must hold the schedule.
     */
    static Repayments.Repaid payEveryInstalment(LoanId loan, long over) {
        List<LocalDate> dues = dues(loan);
        Repayments.Repaid last = null;
        for (int j = 0; j < dues.size(); j++) {
            if (j == 0) {
                backdate(loan, daysTo(loan, 1));
            } else {
                advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(dues.get(j - 1), dues.get(j))));
            }
            assertThat(service(loan).billed()).isEqualTo(1);
            long owed = billings(loan).get(j).outstanding().minorUnits();
            last = repay(loan, key(), owed + (j == dues.size() - 1 ? over : 0));
            assertThat(last.closure().isPresent()).as("closed only by the last").isEqualTo(j == dues.size() - 1);
        }
        return last;
    }

    /** A repayment in its own transaction under the loan's party - the door's transaction, its claim aside. */
    static Repayments.Repaid repay(LoanId loan, String key, long minor) {
        return repayAs(REPAYMENTS, partyOf(loan), loan, key, minor);
    }

    static Repayments.Repaid repayAs(Repayments repayments, UUID party, LoanId loan, String key, long minor) {
        return inOneTransaction(uow -> repayAs(uow, repayments, party, loan, key, minor));
    }

    static Repayments.Repaid repayAs(
            Connection uow, Repayments repayments, UUID party, LoanId loan, String key, long minor) {
        Actor actor = customer(party);
        CorrelationId correlation = correlation();
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation).causing(CausationId.of(correlation.value())))) {
            return repayments.repay(uow, loan, party, new Repayments.Request(key, eur(minor)), actor, correlation);
        }
    }

    /** The repayment behind the door's claim, as {@code app}'s desk runs it: one transaction, the claim first. */
    static IdempotentExecutor.ExecutionOutcome throughTheClaim(LoanId loan, String key, long minor) {
        UUID party = partyOf(loan);
        IdempotencyKey claim = new IdempotencyKey("lending.repayment:CUSTOMER:" + party, key);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("lending.repayment|" + loan.value() + "|" + minor)
                .getBytes(StandardCharsets.UTF_8));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                Correlation.startingWith(correlation()).causing(CausationId.of("p11t18")))) {
            return inOneTransaction(uow -> DOOR_CLAIM.execute(uow, claim, fingerprint, claimed -> CommandResult.succeeded(
                    StoredResponse.of(repayAs(claimed, REPAYMENTS, party, loan, key, minor).repayment().id().toString()
                            .getBytes(StandardCharsets.UTF_8), "text/plain"))));
        }
    }

    /** A writer in the servicing step's closure order: the loan (L3), a pause, then the wallet's row (L7). */
    private static void holdTheLoanThenTheWallet(LoanId loan, LedgerAccountId wallet) throws SQLException {
        try (Connection writer = DatabaseRoles.application()) {
            writer.setAutoCommit(false);
            lock(writer, "SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE", loan.value());
            execute(writer, "SELECT pg_sleep(0.3)");
            lock(writer, "SELECT 1 FROM ledger.ledger_account WHERE id = ? FOR UPDATE", wallet.value());
            writer.commit();
        }
    }

    /** Money paid into the wallet - the owner's fixture standing in for payments' pay-in. */
    static void topUp(UUID party, long minor) {
        postFixture("p11t18-topup:" + UUID.randomUUID(), today(), List.of(
                new JournalLine(cashAtBank(), Direction.DEBIT, eur(minor)),
                new JournalLine(walletOf(party), Direction.CREDIT, eur(minor))));
    }

    // ------------------------------------------------------------------ the proof

    /**
     * The subledger proof's arms over every payer (`P11-TSK-029` gauges them): each due account is its billings less every
     * allocation that paid them (the credit-balance applications' and the repayments'); the accrued interest the accruals
     * less the billed interest; the principal not yet due the agreement less the billed principal; the credit balance the
     * repayments' held lines less the applications and the closure's refund; every repayment's rows sum to it; no due
     * account and no credit balance ever on the wrong side.
     */
    static void assertSubledgerProves(LoanId loan) {
        UUID id = loan.value();
        for (String component : List.of("FEES", "INTEREST", "PRINCIPAL")) {
            // P11-TSK-025: fees are what was assessed on the billed items; fee and interest waivers forgive what they billed.
            long billed = component.equals("FEES")
                    ? count("SELECT coalesce(sum(amount_minor), 0) FROM lending.fee_assessment WHERE loan_id = ?", id)
                    : count("SELECT coalesce(sum(" + component.toLowerCase(java.util.Locale.ROOT) + "_due_minor), 0)"
                            + " FROM lending.instalment_billing WHERE loan_id = ?", id);
            if (component.equals("PRINCIPAL")) {
                // P11-TSK-027: what accepted amendments re-scheduled of the billed principal is no longer due (rule 13).
                billed -= count("SELECT coalesce(sum(principal_minor), 0) FROM lending.loan_amendment_rescheduling"
                        + " WHERE loan_id = ?", id);
            } else {
                billed -= count("SELECT coalesce(sum(w.amount_minor), 0) FROM lending.waiver w JOIN lending.waiver_decision d"
                        + " ON d.waiver_id = w.id AND d.outcome = 'APPROVED' WHERE w.loan_id = ? AND w.kind = ?", id,
                        component.equals("FEES") ? "FEE" : "INTEREST");
            }
            long applied = count("SELECT coalesce(sum(a.amount_minor), 0) FROM lending.credit_balance_allocation a"
                    + " JOIN lending.instalment_billing b ON b.id = a.billing_id WHERE b.loan_id = ? AND a.component = ?",
                    id, component);
            long repaid = count("SELECT coalesce(sum(a.amount_minor), 0) FROM lending.repayment_allocation a"
                    + " JOIN lending.repayment r ON r.id = a.repayment_id WHERE r.loan_id = ? AND a.component = ?",
                    id, component);
            // P11-TSK-020: what an approved reversal negated is due again.
            long reversed = count("SELECT coalesce(sum(n.amount_minor), 0) FROM lending.repayment_allocation_reversal n"
                    + " JOIN lending.repayment_allocation a ON a.id = n.allocation_id"
                    + " JOIN lending.repayment r ON r.id = a.repayment_id WHERE r.loan_id = ? AND a.component = ?",
                    id, component);
            AccountPurpose due = switch (component) {
                case "FEES" -> AccountPurpose.LOAN_FEES_DUE;
                case "INTEREST" -> AccountPurpose.LOAN_INTEREST_DUE;
                default -> AccountPurpose.LOAN_PRINCIPAL_DUE;
            };
            long balance = netDebit(loanAccount(loan, due)).minorUnits();
            assertThat(balance).as(due + ": billed less waived, less every payer, plus what reversals re-opened")
                    .isEqualTo(billed - applied - repaid + reversed);
            assertThat(balance).as(due + " never negative").isGreaterThanOrEqualTo(0);
        }
        long accrued = count("SELECT coalesce(sum(posted_minor), 0) FROM lending.interest_accrual WHERE loan_id = ?", id);
        long billedInterest = count("SELECT coalesce(sum(interest_due_minor), 0) FROM lending.instalment_billing"
                + " WHERE loan_id = ?", id);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_ACCRUED)).minorUnits())
                .isEqualTo(accrued - billedInterest);
        // P11-TSK-027: rule 13 returns re-scheduled arrears to LOAN_PRINCIPAL - the principal not yet billed, net.
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits())
                .isEqualTo(count("SELECT amount_minor FROM lending.loan_agreement WHERE loan_id = ? AND version = 1", id)
                        - count("SELECT coalesce(sum(principal_due_minor), 0) FROM lending.instalment_billing"
                                + " WHERE loan_id = ?", id)
                        + count("SELECT coalesce(sum(principal_minor), 0) FROM lending.loan_amendment_rescheduling"
                                + " WHERE loan_id = ?", id));
        long heldLines = count("SELECT coalesce(sum(a.amount_minor), 0) FROM lending.repayment_allocation a"
                + " JOIN lending.repayment r ON r.id = a.repayment_id WHERE r.loan_id = ? AND a.target = 'CREDIT_BALANCE'",
                id);
        long applications = count("SELECT coalesce(sum(amount_minor), 0) FROM lending.credit_balance_application"
                + " WHERE loan_id = ?", id);
        long refunds = count("SELECT coalesce(sum(refund_minor), 0) FROM lending.loan_closure WHERE loan_id = ?", id);
        long heldReversed = count("SELECT coalesce(sum(n.amount_minor), 0) FROM lending.repayment_allocation_reversal n"
                + " JOIN lending.repayment_allocation a ON a.id = n.allocation_id JOIN lending.repayment r"
                + " ON r.id = a.repayment_id WHERE r.loan_id = ? AND a.target = 'CREDIT_BALANCE'", id);
        // P11-TSK-025: a paid fee refunded to the credit balance (rule 8a) is held like an overpayment.
        long feeRefunds = count("SELECT coalesce(sum(w.amount_minor), 0) FROM lending.waiver w JOIN lending.waiver_decision d"
                + " ON d.waiver_id = w.id AND d.outcome = 'APPROVED' WHERE w.loan_id = ? AND w.kind = 'FEE_REFUND'", id);
        long credit = held(loan);
        assertThat(credit).as("the credit balance: overpayments and fee refunds less reversed overpayments, applications"
                + " and refunds").isEqualTo(heldLines + feeRefunds - heldReversed - applications - refunds);
        assertThat(credit).isGreaterThanOrEqualTo(0);
        assertThat(count("SELECT count(*) FROM lending.repayment r WHERE r.loan_id = ? AND r.amount_minor <>"
                + " (SELECT coalesce(sum(a.amount_minor), 0) FROM lending.repayment_allocation a"
                + " WHERE a.repayment_id = r.id)", id)).as("every repayment's rows sum to it").isZero();
        assertThat(count("SELECT count(*) FROM lending.repayment r JOIN ledger.journal_line l"
                + " ON l.entry_id = r.journal_entry_id AND l.direction = 'DEBIT' WHERE r.loan_id = ?"
                + " AND l.amount_minor <> r.amount_minor", id)).as("each entry debits the wallet the amount").isZero();
    }

    private static void assertRepaidOnce(LoanId loan, String key, long minor) {
        Repayment repayment = inOneTransaction(uow -> REPAYMENT_STORE.findByKey(uow, loan, RepaymentKind.CUSTOMER, key))
                .orElseThrow();
        assertThat(repayment.amount()).isEqualTo(eur(minor));
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ?", loan.value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:" + Repayments.POSTING_KEY_PREFIX + repayment.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope LIKE 'ledger.post:"
                + Repayments.POSTING_KEY_PREFIX + "%' AND reference = ?", loan.value().toString())).isEqualTo(1);
        assertThat(events(loan, Repayments.RECEIVED_EVENT)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ plumbing

    static String key() {
        return "p11t18-" + UUID.randomUUID();
    }

    static LocalDate today() {
        return InstalmentBillingDatabaseTest.today();
    }

    static List<Repayment> repayments(LoanId loan) {
        return inOneTransaction(uow -> REPAYMENT_STORE.ownedBy(uow, loan, partyOf(loan), 100));
    }

    static Money sum(Repayment repayment) {
        Money total = eur(0);
        for (RepaymentAllocation allocation : repayment.allocations()) {
            total = total.plus(allocation.line().amount());
        }
        return total;
    }

    /** What the loan has billed and unpaid, from the ledger: its three due accounts. */
    static long due(LoanId loan) {
        return netDebit(loanAccount(loan, AccountPurpose.LOAN_FEES_DUE)).minorUnits()
                + netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_DUE)).minorUnits()
                + netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE)).minorUnits();
    }

    /** The credit balance - CR less DR. */
    static long held(LoanId loan) {
        return -netDebit(loanAccount(loan, AccountPurpose.LOAN_CREDIT_BALANCE)).minorUnits();
    }

    static String loanStatus(LoanId loan) {
        return scalar("SELECT status FROM lending.loan WHERE id = ?", loan.value());
    }

    static long events(LoanId loan, String type) {
        return count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?", loan.value(),
                type);
    }

    static long walletEntries(UUID party) {
        return count("SELECT count(*) FROM ledger.journal_line WHERE ledger_account_id = ?", walletOf(party).value());
    }

    /** {@code sql}, run alone by the application role on a connection of its own, is refused as stated. */
    static void refusedAlone(String sql, String sqlState, String text) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            refused(app, sql, sqlState, text);
        }
    }

    static void lock(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(sql)) {
            lock.setObject(1, id);
            lock.executeQuery().close();
        }
    }

    static boolean lockableNoWait(String sql, UUID id) throws SQLException {
        try (Connection probe = DatabaseRoles.application()) {
            probe.setAutoCommit(false);
            try {
                lock(probe, sql, id);
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

    /**
     * An application-role repayment of {@code loan} as raw SQL in one transaction - {@code amount} and its rows
     * {@code TARGET:billing:COMPONENT:minor} - otherwise well formed (today's value date, the governing agreement).
     */
    static String rawRepayment(LoanId loan, long amount, List<String> rows) {
        String repayment = UUID.randomUUID().toString();
        String agreement = scalar("SELECT id::text FROM lending.loan_agreement WHERE loan_id = ? AND version = 1",
                loan.value());
        StringBuilder sql = new StringBuilder("BEGIN; INSERT INTO lending.repayment (id, loan_id, kind, request_key,"
                + " currency, scale, amount_minor, agreement_id, agreement_version, allocation_engine_version,"
                + " journal_entry_id, value_date, created_at, correlation_id) VALUES ('" + repayment + "', '"
                + loan.value() + "', 'CUSTOMER', '" + key() + "', 'EUR', 2, " + amount + ", '" + agreement + "', 1, 1,"
                + " gen_random_uuid(), lending.business_date(statement_timestamp(), 'UTC'), statement_timestamp(),"
                + " 'p11t18-raw');");
        List<String> values = new ArrayList<>();
        int ordinal = 0;
        for (String row : rows) {
            String[] parts = row.split(":", -1);
            values.add("(gen_random_uuid(), '" + repayment + "', " + (++ordinal) + ", '" + parts[0] + "', "
                    + (parts[1].isBlank() ? "NULL" : "'" + parts[1] + "'") + ", "
                    + (parts[2].isBlank() ? "NULL" : "'" + parts[2] + "'") + ", 'EUR', 2, " + parts[3].strip() + ")");
        }
        sql.append(" INSERT INTO lending.repayment_allocation (id, repayment_id, ordinal, target, billing_id, component,"
                + " currency, scale, amount_minor) VALUES ").append(String.join(", ", values)).append(";");
        return sql.append(" COMMIT;").toString();
    }

    /** An application-role closure of {@code loan}, refunding nothing, as raw SQL. */
    static String rawClosure(LoanId loan) {
        return "INSERT INTO lending.loan_closure (id, loan_id, reason, currency, scale, refund_minor,"
                + " refund_journal_entry_id, repayment_id, created_at, correlation_id) VALUES (gen_random_uuid(), '"
                + loan.value() + "', 'REPAID', 'EUR', 2, 0, NULL, NULL, statement_timestamp(), 'p11t18-raw')";
    }
}
