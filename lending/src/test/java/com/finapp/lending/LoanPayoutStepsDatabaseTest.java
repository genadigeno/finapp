package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.HOLDS;
import static com.finapp.lending.DisbursementWorld.PAYMENTS;
import static com.finapp.lending.DisbursementWorld.PAYOUT_STEPS;
import static com.finapp.lending.DisbursementWorld.acceptedLoan;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.available;
import static com.finapp.lending.DisbursementWorld.businessDateSql;
import static com.finapp.lending.DisbursementWorld.claimPayouts;
import static com.finapp.lending.DisbursementWorld.claimed;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.disburse;
import static com.finapp.lending.DisbursementWorld.disbursedExternally;
import static com.finapp.lending.DisbursementWorld.execute;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.payoutStep;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.DisbursementWorld.sweepPayouts;
import static com.finapp.lending.DisbursementWorld.walletOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.HoldExceedsAvailableBalanceException;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Path X's disbursement and payout steps against a real database (`P11-TSK-015`; {@code lending V009},
 * {@code PHASE_11_PLAN.md} sections 7.1 T-dis / T-pyo / T-pyr, 7.3 (d), 12.4, {@code LENDING_LIFECYCLES.md} section
 * 4.7; ADR-0097; {@code INV-LND-07}, {@code INV-RAIL-02}, {@code INV-LIFE-03}): T-dis posting rule 1 and holding
 * {@code P - f} on the wallet with no accrual start; the claim committed and one dispatch however many sweepers; a step
 * whose permit lapsed writing nothing; an undecided withdrawal observed and never re-dispatched; the conclusion starting
 * interest on its business date, held within the payout's life; a refusal releasing the hold; the dispatch holding no
 * lending lock - and every rank held for every writer by raw SQL. Payments is {@link DisbursementWorld.Payments}; its real
 * system withdrawal is proven end to end in {@code app}'s {@code LoanPayoutDatabaseTest}.
 */
@Tag("database")
@DisplayName("the external payout's steps (P11-TSK-015)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class LoanPayoutStepsDatabaseTest {

    /** The race's permit: longer than any step, so a racing sweeper never re-drives a live claim. */
    private static final Duration PERMIT = Duration.ofMinutes(1);

    /** Every other sweep's permit: lapsed at once, so the next sweep takes the payout again. */
    private static final Duration AT_ONCE = Duration.ofMillis(1);

    // ------------------------------------------------------------------ T-dis, path X

    @Test
    @DisplayName("T-dis on path X posts rule 1 and holds P - f on the wallet under its lock: the loan ACTIVE, the payout"
            + " PENDING naming the hold and the method, no accrual start and no schedule; the funds cannot be spent - a"
            + " hold of one cent more is refused - and LoanDisbursed names the external path")
    void pathXDisbursesWithTheHoldAndAwaitsThePayout() {
        DisbursementWorld.Accepted loan = disbursedExternally(4_000_00, 12);
        UUID id = loan.loan().value();
        LedgerAccountId wallet = walletOf(loan.party());
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?", id)).isEqualTo("ACTIVE");
        assertThat(netDebit(loanAccount(loan.loan(), AccountPurpose.LOAN_PRINCIPAL))).isEqualTo(loan.principal());
        assertThat(netDebit(wallet)).as("the wallet credited P").isEqualTo(loan.principal().negated());
        assertThat(scalar("SELECT status || '|' || payment_method_id::text || '|' || wallet_account_id::text"
                + " FROM lending.loan_payout WHERE loan_id = ?", id))
                .isEqualTo("PENDING|" + loan.payoutMethod().orElseThrow() + "|" + wallet.value());
        assertThat(scalar("SELECT h.status || '|' || h.amount_minor::text FROM ledger.hold h JOIN lending.loan_payout p"
                + " ON p.hold_id = h.id WHERE p.loan_id = ?", id)).as("one hold of P - f (no fee here)")
                .isEqualTo("ACTIVE|400000");
        assertThat(count("SELECT count(*) FROM lending.accrual_start WHERE loan_id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?", id)).isZero();
        assertThat(available(wallet).isZero()).as("nothing of the disbursement is spendable").isTrue();
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(
                        DisbursementWorld.correlation()).causing(CausationId.of("p11t15")))) {
            assertThatThrownBy(() -> inOneTransaction(uow -> HOLDS.place(uow, wallet, Money.ofMinorUnits(1,
                    DisbursementWorld.EUR)))).isInstanceOf(HoldExceedsAvailableBalanceException.class);
        }
        assertThat(scalar("SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanDisbursed'", id)).contains("\"path\":\"EXTERNAL_ACCOUNT\"");
        assertThat(disburse(loan.loan()).kind()).as("a second step posts nothing")
                .isEqualTo(LoanDisbursements.Outcome.Kind.NOTHING);
        assertThat(count("SELECT count(*) FROM ledger.hold h JOIN lending.loan_payout p ON p.wallet_account_id"
                + " = h.ledger_account_id WHERE p.loan_id = ? AND h.status = 'ACTIVE'", id)).as("one hold").isEqualTo(1);
    }

    // ------------------------------------------------------------------ T-pyo

    @Test
    @DisplayName("ten sweepers dispatch one payout once: each claims and steps what it claimed; payments asked once; the"
            + " payout DISPATCHED naming its withdrawal, with the instruction lending:<loan> for P - f and the hold")
    void tenSweepersDispatchOnce() throws Exception {
        DisbursementWorld.Accepted loan = disbursedExternally(2_500_00, 12);
        List<LoanPayoutSteps.Outcome.Kind> mine = Collections.synchronizedList(new ArrayList<>());
        race(10, index -> {
            mine.addAll(sweepPayouts(PERMIT).getOrDefault(loan.loan(), List.of()));
            return null;
        });
        assertThat(PAYMENTS.dispatchesOf(loan.loan())).as("one dispatch, however many sweepers").isEqualTo(1);
        assertThat(mine).containsExactly(LoanPayoutSteps.Outcome.Kind.DISPATCHED, LoanPayoutSteps.Outcome.Kind.AWAITING);
        UUID id = loan.loan().value();
        assertThat(scalar("SELECT status || '|' || withdrawal_id::text FROM lending.loan_payout WHERE loan_id = ?", id))
                .isEqualTo("DISPATCHED|" + PAYMENTS.withdrawals.get(loan.loan()));
        LoanPayouts.Instruction instruction = PAYMENTS.instructions.get(loan.loan());
        assertThat(instruction.reference()).isEqualTo("lending:" + id);
        assertThat(instruction.amount()).isEqualTo(loan.principal());
        assertThat(instruction.hold().value().toString())
                .isEqualTo(scalar("SELECT hold_id::text FROM lending.loan_payout WHERE loan_id = ?", id));
    }

    @Test
    @DisplayName("a lapsed claim re-drives under the same instruction and a step on an overtaken permit writes nothing:"
            + " the first claimer's late answer - accepted or refused - is NOTHING, the re-claimer's is DISPATCHED")
    void aLapsedClaimReDrivesAndAnOvertakenStepWritesNothing() throws Exception {
        DisbursementWorld.Accepted loan = disbursedExternally(1_700_00, 12);
        LoanPayoutStore.Claimed first = claimed(claimPayouts(AT_ONCE), loan.loan()).orElseThrow();
        assertThat(first.status()).isEqualTo(PayoutStatus.DISPATCHING);
        Thread.sleep(20);
        LoanPayoutStore.Claimed second = claimed(claimPayouts(AT_ONCE), loan.loan()).orElseThrow();
        assertThat(second.status()).as("re-driven, still DISPATCHING").isEqualTo(PayoutStatus.DISPATCHING);
        assertThat(second.permit()).isAfter(first.permit());
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            for (LoanPayouts.Dispatch late : List.<LoanPayouts.Dispatch>of(
                    new LoanPayouts.Dispatch.Accepted(UUID.randomUUID()),
                    new LoanPayouts.Dispatch.Refused(NotDispatchedReason.NO_ELIGIBLE_RAIL))) {
                assertThat(inOneTransaction(uow -> PAYOUT_STEPS.recordDispatch(uow, first, late,
                        DisbursementWorld.SYSTEM)).kind()).isEqualTo(LoanPayoutSteps.Outcome.Kind.NOTHING);
            }
        }
        assertThat(payoutStep(first)).extracting(LoanPayoutSteps.Outcome::kind)
                .as("the overtaken claim does not even ask payments").containsExactly(LoanPayoutSteps.Outcome.Kind.NOTHING);
        assertThat(PAYMENTS.dispatchesOf(loan.loan())).isZero();
        assertThat(payoutStep(second)).extracting(LoanPayoutSteps.Outcome::kind)
                .startsWith(LoanPayoutSteps.Outcome.Kind.DISPATCHED);
        assertThat(PAYMENTS.dispatchesOf(loan.loan())).isEqualTo(1);
        assertThat(scalar("SELECT h.status FROM ledger.hold h JOIN lending.loan_payout p ON p.hold_id = h.id"
                + " WHERE p.loan_id = ?", loan.loan().value())).as("the late refusal released nothing").isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("payments gives no answer now: nothing written, the payout still DISPATCHING, re-driven on the permit")
    void noAnswerNowWritesNothing() throws Exception {
        DisbursementWorld.Accepted loan = disbursedExternally(1_250_00, 12);
        PAYMENTS.answers.put(loan.loan(), new LoanPayouts.Dispatch.NotNow());
        assertThat(sweepPayouts(AT_ONCE).get(loan.loan())).containsExactly(LoanPayoutSteps.Outcome.Kind.RETRYING);
        assertThat(scalar("SELECT status FROM lending.loan_payout WHERE loan_id = ?", loan.loan().value()))
                .isEqualTo("DISPATCHING");
        PAYMENTS.answers.remove(loan.loan());
        Thread.sleep(20);
        assertThat(sweepPayouts(AT_ONCE).get(loan.loan())).startsWith(LoanPayoutSteps.Outcome.Kind.DISPATCHED);
        assertThat(PAYMENTS.dispatchesOf(loan.loan())).isEqualTo(2);
    }

    @Test
    @DisplayName("the dispatch holds no lending lock (section 7.3 (d)): while payments holds the wire, the loan and its"
            + " payout are lockable NOWAIT from another connection")
    void theDispatchHoldsNoLendingLock() throws Exception {
        DisbursementWorld.Accepted loan = disbursedExternally(1_150_00, 12);
        AtomicReference<String> seen = new AtomicReference<>();
        PAYMENTS.duringDispatch = instruction -> {
            if (instruction.loan().equals(loan.loan())) {
                seen.set(lockableNoWait("SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE NOWAIT", loan.loan())
                        + "|" + lockableNoWait("SELECT 1 FROM lending.loan_payout WHERE loan_id = ? FOR UPDATE NOWAIT",
                        loan.loan()));
            }
        };
        try {
            sweepPayouts(AT_ONCE);
        } finally {
            PAYMENTS.duringDispatch = instruction -> { };
        }
        assertThat(seen.get()).isEqualTo("true|true");
    }

    // ------------------------------------------------------------------ T-pyr

    @Test
    @DisplayName("an undecided withdrawal is observed and never re-dispatched: the payout stays DISPATCHED, the observation"
            + " stamped, payments asked once, no accrual start - interest waits")
    void anUndecidedWithdrawalIsObservedNeverReDispatched() throws Exception {
        DisbursementWorld.Accepted loan = dispatched(1_900_00);
        for (int round = 0; round < 3; round++) {
            Thread.sleep(20);
            assertThat(sweepPayouts(AT_ONCE).get(loan.loan())).containsExactly(LoanPayoutSteps.Outcome.Kind.AWAITING);
        }
        UUID id = loan.loan().value();
        assertThat(PAYMENTS.dispatchesOf(loan.loan())).isEqualTo(1);
        assertThat(scalar("SELECT status FROM lending.loan_payout WHERE loan_id = ?", id)).isEqualTo("DISPATCHED");
        assertThat(scalar("SELECT last_observed_at IS NOT NULL FROM lending.loan_payout WHERE loan_id = ?", id))
                .isEqualTo("t");
        assertThat(count("SELECT count(*) FROM lending.accrual_start WHERE loan_id = ?", id)).isZero();
    }

    @Test
    @DisplayName("interest starts at the terminal outcome: PAID_OUT with payments' conclusion instant - the accrual start"
            + " its business date in the pinned zone, schedule v1 from it, LoanPayoutConcluded - and the receivable"
            + " untouched")
    void interestStartsAtTheTerminalOutcome() throws Exception {
        DisbursementWorld.Accepted loan = dispatched(2_200_00);
        UUID id = loan.loan().value();
        Instant concluded = Instant.parse(scalar("SELECT to_char(created_at AT TIME ZONE 'UTC',"
                + " 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM lending.loan_payout WHERE loan_id = ?", id)).plusMillis(5);
        PAYMENTS.outcomes.put(loan.loan(), new LoanPayouts.Outcome.Concluded(PayoutStatus.PAID_OUT, concluded));
        Thread.sleep(20);
        assertThat(sweepPayouts(AT_ONCE).get(loan.loan())).containsExactly(LoanPayoutSteps.Outcome.Kind.PAID_OUT);
        assertThat(scalar("SELECT status FROM lending.loan_payout WHERE loan_id = ?", id)).isEqualTo("PAID_OUT");
        assertThat(Instant.parse(scalar("SELECT to_char(concluded_at AT TIME ZONE 'UTC',"
                + " 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM lending.loan_payout WHERE loan_id = ?", id)))
                .isEqualTo(concluded);
        assertThat(scalar("SELECT origin || '|' || start_date::text || '|' || (journal_entry_id IS NULL)::text"
                + " FROM lending.accrual_start WHERE loan_id = ?", id))
                .isEqualTo("PAYOUT_CONCLUDED|" + concluded.atZone(java.time.ZoneOffset.UTC).toLocalDate() + "|true");
        assertThat(scalar("SELECT start_date::text FROM lending.repayment_schedule WHERE loan_id = ?"
                + " AND schedule_version = 1", id))
                .isEqualTo(scalar("SELECT start_date::text FROM lending.accrual_start WHERE loan_id = ?", id));
        assertThat(scalar("SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanPayoutConcluded'", id))
                .contains("\"outcome\":\"PAID_OUT\"", "\"withdrawalId\":\"" + PAYMENTS.withdrawals.get(loan.loan()));
        assertThat(netDebit(loanAccount(loan.loan(), AccountPurpose.LOAN_PRINCIPAL))).as("INV-LND-07")
                .isEqualTo(loan.principal());
        Thread.sleep(20);
        assertThat(sweepPayouts(AT_ONCE)).as("a terminal payout is never claimed").doesNotContainKey(loan.loan());
    }

    @Test
    @DisplayName("payments' conclusion instant is held within the payout's life on the database clock: one before the"
            + " payout's birth becomes its birth, one in the future becomes now - the accrual start never precedes the"
            + " disbursement's value date nor follows today")
    void theConclusionInstantIsHeldWithinThePayoutsLife() throws Exception {
        DisbursementWorld.Accepted early = dispatched(1_350_00);
        DisbursementWorld.Accepted late = dispatched(1_450_00);
        PAYMENTS.outcomes.put(early.loan(), new LoanPayouts.Outcome.Concluded(PayoutStatus.FAILED,
                Instant.parse("2001-01-01T00:00:00Z")));
        PAYMENTS.outcomes.put(late.loan(), new LoanPayouts.Outcome.Concluded(PayoutStatus.PAID_OUT,
                Instant.parse("2999-01-01T00:00:00Z")));
        Thread.sleep(20);
        var effects = sweepPayouts(AT_ONCE);
        assertThat(effects.get(early.loan())).containsExactly(LoanPayoutSteps.Outcome.Kind.FAILED);
        assertThat(effects.get(late.loan())).containsExactly(LoanPayoutSteps.Outcome.Kind.PAID_OUT);
        assertThat(scalar("SELECT (concluded_at = created_at)::text FROM lending.loan_payout WHERE loan_id = ?",
                early.loan().value())).isEqualTo("true");
        // The recording statement's clock is decided_at (the same statement_timestamp()): read against it and the birth,
        // never against a later now() or a separate clock reading, which a database clock stepping back would contradict.
        assertThat(scalar("SELECT (concluded_at = GREATEST(decided_at, created_at))::text FROM lending.loan_payout"
                + " WHERE loan_id = ?", late.loan().value())).as("the future instant becomes the recording clock")
                .isEqualTo("true");
        for (DisbursementWorld.Accepted loan : List.of(early, late)) {
            assertThat(scalar("SELECT (s.start_date = d.value_date)::text FROM lending.accrual_start s"
                    + " JOIN lending.loan_disbursement d ON d.loan_id = s.loan_id WHERE s.loan_id = ?",
                    loan.loan().value())).as("today, the disbursement's value date").isEqualTo("true");
        }
    }

    @Test
    @DisplayName("a conclusion recorded on a database clock behind the payout's birth is held at the birth"
            + " (P11-TSK-033): payments' instant before it, at the instance's now and in the future, and a refusal on"
            + " the clock itself, each concluded at created_at - never before the payout - with the accrual start the"
            + " birth's business date, admitted though the recording clock's own date is earlier")
    void aConclusionOnAClockBehindThePayoutsBirthIsHeldAtTheBirth() {
        DisbursementWorld.Accepted early = dispatched(1_360_00);
        DisbursementWorld.Accepted present = dispatched(1_370_00);
        DisbursementWorld.Accepted late = dispatched(1_380_00);
        DisbursementWorld.Accepted refused = disbursedExternally(1_390_00, 12);
        List<DisbursementWorld.Accepted> all = List.of(early, present, late, refused);
        // The database clock stepped back below each payout's birth, deterministically: the birth moved a day ahead of
        // the clock (so into the pinned zone's next business date too), the permit made due - as the bootstrap superuser
        // with the triggers suspended, since no writer can move a birth.
        for (DisbursementWorld.Accepted loan : all) {
            asOwner("UPDATE lending.loan_payout SET created_at = statement_timestamp() + interval '1 day',"
                    + " next_attempt_at = statement_timestamp() - interval '1 hour' WHERE loan_id = '"
                    + loan.loan().value() + "'");
        }
        PAYMENTS.outcomes.put(early.loan(), new LoanPayouts.Outcome.Concluded(PayoutStatus.FAILED,
                Instant.parse("2001-01-01T00:00:00Z")));
        PAYMENTS.outcomes.put(present.loan(), new LoanPayouts.Outcome.Concluded(PayoutStatus.PAID_OUT, Instant.now()));
        PAYMENTS.outcomes.put(late.loan(), new LoanPayouts.Outcome.Concluded(PayoutStatus.PAID_OUT,
                Instant.parse("2999-01-01T00:00:00Z")));
        PAYMENTS.answers.put(refused.loan(), new LoanPayouts.Dispatch.Refused(NotDispatchedReason.NO_ELIGIBLE_RAIL));
        var effects = sweepPayouts(AT_ONCE);
        assertThat(effects.get(early.loan())).containsExactly(LoanPayoutSteps.Outcome.Kind.FAILED);
        assertThat(effects.get(present.loan())).containsExactly(LoanPayoutSteps.Outcome.Kind.PAID_OUT);
        assertThat(effects.get(late.loan())).containsExactly(LoanPayoutSteps.Outcome.Kind.PAID_OUT);
        assertThat(effects.get(refused.loan())).containsExactly(LoanPayoutSteps.Outcome.Kind.NOT_DISPATCHED);
        for (DisbursementWorld.Accepted loan : all) {
            assertThat(scalar("SELECT (decided_at < created_at)::text || '|' || (concluded_at = created_at)::text"
                    + " FROM lending.loan_payout WHERE loan_id = ?", loan.loan().value()))
                    .as("recorded on a clock behind the birth, concluded at the birth").isEqualTo("true|true");
            assertThat(scalar("SELECT (s.start_date = lending.business_date(p.created_at, 'UTC')"
                    + " AND s.start_date > lending.business_date(p.decided_at, 'UTC')"
                    + " AND r.start_date = s.start_date)::text FROM lending.accrual_start s"
                    + " JOIN lending.loan_payout p ON p.loan_id = s.loan_id JOIN lending.repayment_schedule r"
                    + " ON r.loan_id = s.loan_id AND r.schedule_version = 1 WHERE s.loan_id = ?"
                    + " AND s.origin = 'PAYOUT_CONCLUDED'", loan.loan().value()))
                    .as("interest from the birth's business date, never before it, schedule v1 from it (A30)")
                    .isEqualTo("true");
        }
    }

    @Test
    @DisplayName("an unroutable payout releases the hold: NOT_DISPATCHED with its reason, the hold RELEASED, the funds"
            + " spendable, interest from today with schedule v1, LoanPayoutConcluded naming the reason; nothing re-claimed")
    void anUnroutablePayoutReleasesTheHold() throws Exception {
        DisbursementWorld.Accepted loan = disbursedExternally(1_600_00, 12);
        PAYMENTS.answers.put(loan.loan(), new LoanPayouts.Dispatch.Refused(NotDispatchedReason.NO_ELIGIBLE_RAIL));
        assertThat(sweepPayouts(AT_ONCE).get(loan.loan())).containsExactly(LoanPayoutSteps.Outcome.Kind.NOT_DISPATCHED);
        UUID id = loan.loan().value();
        assertThat(scalar("SELECT status || '|' || not_dispatched_reason || '|' || (withdrawal_id IS NULL)::text"
                + " FROM lending.loan_payout WHERE loan_id = ?", id)).isEqualTo("NOT_DISPATCHED|NO_ELIGIBLE_RAIL|true");
        assertThat(scalar("SELECT h.status FROM ledger.hold h JOIN lending.loan_payout p ON p.hold_id = h.id"
                + " WHERE p.loan_id = ?", id)).isEqualTo("RELEASED");
        assertThat(available(walletOf(loan.party()))).isEqualTo(loan.principal());
        assertThat(scalar("SELECT (s.start_date = lending.business_date(now(), 'UTC'))::text FROM lending.accrual_start s"
                + " WHERE s.loan_id = ? AND s.origin = 'PAYOUT_CONCLUDED'", id)).isEqualTo("true");
        assertThat(count("SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?", id)).isEqualTo(1);
        assertThat(scalar("SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanPayoutConcluded'", id))
                .contains("\"outcome\":\"NOT_DISPATCHED\"", "\"reason\":\"NO_ELIGIBLE_RAIL\"");
        assertThat(netDebit(loanAccount(loan.loan(), AccountPurpose.LOAN_PRINCIPAL))).isEqualTo(loan.principal());
    }

    // ------------------------------------------------------------------ the ranks

    @Test
    @DisplayName("the payout's edges are held for every writer: no payout beside a wallet loan; PENDING -> DISPATCHED"
            + " refused; DISPATCHED without a withdrawal refused; an accrual start before the conclusion refused; a"
            + " conclusion without its schedule refused at commit; the frozen columns beyond every role; out of a terminal"
            + " state nothing; never deleted")
    void theEdgesAreHeldForEveryWriter() throws Exception {
        DisbursementWorld.Accepted wallet = acceptedLoan(1_050_00, 12);
        assertThat(disburse(wallet.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
        DisbursementWorld.Accepted pending = disbursedExternally(1_060_00, 12);
        String p = pending.loan().value().toString();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "INSERT INTO lending.loan_payout (id, loan_id, payment_method_id, wallet_account_id, hold_id,"
                    + " status, next_attempt_at, created_at, correlation_id) VALUES (gen_random_uuid(), '"
                    + wallet.loan().value() + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'PENDING',"
                    + " now(), now(), 'c')", "P0001");
            app.rollback();
            refused(app, "UPDATE lending.loan_payout SET status = 'DISPATCHED', withdrawal_id = gen_random_uuid()"
                    + " WHERE loan_id = '" + p + "'", "P0001");
            app.rollback();
            // Dated today's business date in the pinned zone - the date a conclusion now would be born on - so only the
            // payout's not being terminal refuses it (P11-TSK-032: never current_date, the session's zone).
            refused(app, "INSERT INTO lending.accrual_start (id, loan_id, start_date, origin, created_at, correlation_id)"
                    + " VALUES (gen_random_uuid(), '" + p + "', " + businessDateSql(pending.loan())
                    + ", 'PAYOUT_CONCLUDED', now(), 'c')", "P0001");
            app.rollback();
            refused(app, "UPDATE lending.loan_payout SET hold_id = gen_random_uuid() WHERE loan_id = '" + p + "'",
                    "42501");
            app.rollback();
            refused(app, "DELETE FROM lending.loan_payout WHERE loan_id = '" + p + "'", "42501");
            app.rollback();
            // Claimed, then DISPATCHED without its withdrawal; and NOT_DISPATCHED without its schedule at commit.
            execute(app, "UPDATE lending.loan_payout SET status = 'DISPATCHING', next_attempt_at = now() + interval"
                    + " '1 minute' WHERE loan_id = '" + p + "'");
            refused(app, "UPDATE lending.loan_payout SET status = 'DISPATCHED' WHERE loan_id = '" + p + "'", "P0001");
            app.rollback();
            execute(app, "UPDATE lending.loan_payout SET status = 'DISPATCHING', next_attempt_at = now() + interval"
                    + " '1 minute' WHERE loan_id = '" + p + "'");
            execute(app, "UPDATE lending.loan_payout SET status = 'NOT_DISPATCHED', not_dispatched_reason ="
                    + " 'NO_ELIGIBLE_RAIL' WHERE loan_id = '" + p + "'");
            assertThatThrownBy(app::commit).as("the outcome commits only beside its accrual start and schedule")
                    .isInstanceOf(SQLException.class).hasMessageContaining("without its accrual start");
            app.rollback();
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            refused(owner, "UPDATE lending.loan_payout SET hold_id = gen_random_uuid() WHERE loan_id = '" + p + "'",
                    "P0001");
            owner.rollback();
            refused(owner, "UPDATE lending.loan_payout SET status = 'PAID_OUT', concluded_at = now()"
                    + " WHERE loan_id = '" + p + "'", "P0001");
            owner.rollback();
        }
        PAYMENTS.answers.put(pending.loan(), new LoanPayouts.Dispatch.Refused(NotDispatchedReason.DESTINATION_UNAVAILABLE));
        assertThat(sweepPayouts(AT_ONCE).get(pending.loan()))
                .containsExactly(LoanPayoutSteps.Outcome.Kind.NOT_DISPATCHED);
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            refused(owner, "UPDATE lending.loan_payout SET status = 'DISPATCHING', not_dispatched_reason = NULL,"
                    + " concluded_at = NULL WHERE loan_id = '" + p + "'", "P0001");
            owner.rollback();
            refused(owner, "UPDATE lending.loan_payout SET next_attempt_at = now() + interval '1 day'"
                    + " WHERE loan_id = '" + p + "'", "P0001");
            owner.rollback();
            // A second accrual start on exactly the date the trigger admits - the business date of the conclusion in the
            // pinned zone, as production births it - so only the unique key refuses it (P11-TSK-032).
            refused(owner, "INSERT INTO lending.accrual_start (id, loan_id, start_date, origin, created_at, correlation_id)"
                    + " SELECT gen_random_uuid(), p.loan_id, lending.business_date(p.concluded_at, t.servicing_zone),"
                    + " 'PAYOUT_CONCLUDED', now(), 'c' FROM lending.loan_payout p JOIN lending.loan l ON l.id = p.loan_id"
                    + " JOIN lending.loan_offer o ON o.id = l.offer_id"
                    + " JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id WHERE p.loan_id = '" + p + "'",
                    "23505");
            owner.rollback();
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** A path X loan disbursed and its payout dispatched (payments' answer undecided). */
    private static DisbursementWorld.Accepted dispatched(long minor) {
        DisbursementWorld.Accepted loan = disbursedExternally(minor, 12);
        assertThat(sweepPayouts(AT_ONCE).get(loan.loan()))
                .containsExactly(LoanPayoutSteps.Outcome.Kind.DISPATCHED, LoanPayoutSteps.Outcome.Kind.AWAITING);
        return loan;
    }

    private static boolean lockableNoWait(String sql, LoanId loan) {
        try (Connection probe = DatabaseRoles.application()) {
            probe.setAutoCommit(false);
            try (PreparedStatement lock = probe.prepareStatement(sql)) {
                lock.setObject(1, loan.value());
                lock.executeQuery().close();
                return true;
            } catch (SQLException held) {
                if ("55P03".equals(held.getSQLState())) {
                    return false;
                }
                throw held;
            } finally {
                probe.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** {@code sql}, run alone by {@code connection}, is refused with {@code sqlState}. */
    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> execute(connection, sql))
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }
}
