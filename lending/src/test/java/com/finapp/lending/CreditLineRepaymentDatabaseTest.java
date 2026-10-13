package com.finapp.lending;

import static com.finapp.lending.CreditLineStatementDatabaseTest.assertLineSubledgerProves;
import static com.finapp.lending.CreditLineStatementDatabaseTest.dateDraw;
import static com.finapp.lending.CreditLineStatementDatabaseTest.dueDaysLandingToday;
import static com.finapp.lending.CreditLineStatementDatabaseTest.lines;
import static com.finapp.lending.CreditLineStatementDatabaseTest.onOrBefore;
import static com.finapp.lending.CreditLineStatementDatabaseTest.openOn;
import static com.finapp.lending.CreditLineStatementDatabaseTest.statements;
import static com.finapp.lending.CreditLineStatementDatabaseTest.today;
import static com.finapp.lending.DisbursementWorld.BALANCES;
import static com.finapp.lending.DisbursementWorld.CLOCK;
import static com.finapp.lending.DisbursementWorld.CLOSURES;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.LOAN_STORE;
import static com.finapp.lending.DisbursementWorld.PAYOUT_STORE;
import static com.finapp.lending.DisbursementWorld.REPAYMENTS;
import static com.finapp.lending.DisbursementWorld.TERMS_STORE;
import static com.finapp.lending.DisbursementWorld.acceptedLine;
import static com.finapp.lending.DisbursementWorld.acceptedLoan;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.awaitBlockedBy;
import static com.finapp.lending.DisbursementWorld.correlation;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.customer;
import static com.finapp.lending.DisbursementWorld.fund;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.pid;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.DisbursementWorld.walletOf;
import static com.finapp.lending.InterestAccrualDatabaseTest.eur;
import static com.finapp.lending.InterestAccrualDatabaseTest.refused;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.RepaymentDatabaseTest.due;
import static com.finapp.lending.RepaymentDatabaseTest.events;
import static com.finapp.lending.RepaymentDatabaseTest.held;
import static com.finapp.lending.RepaymentDatabaseTest.key;
import static com.finapp.lending.RepaymentDatabaseTest.loanStatus;
import static com.finapp.lending.RepaymentDatabaseTest.repayAs;
import static com.finapp.lending.RepaymentDatabaseTest.topUp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.ledger.AccountPurpose;
import com.finapp.lending.CreditLineStatementStore.StatedItem;
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
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A credit line's repayment and closure against a real database (`P11-TSK-023`; {@code lending V018},
 * {@code PHASE_11_PLAN.md} sections 5.3, 7.1 T-rep, T-svc and T-clo, 12.5, 12.6 and 12.8 rule 5; ADR-0100 points 4, 8 and
 * 9, ADR-0095 point 2; A26; {@code INV-LND-04}, {@code INV-LND-08}, {@code INV-LND-14}, {@code INV-LIFE-04},
 * {@code INV-IDEM-01}): every figure read from the rows and the ledger, and every case closing on the line's subledger arms
 * ({@link CreditLineStatementDatabaseTest#assertLineSubledgerProves}, paydowns, held excess and refunds included).
 *
 * <p>Lines are accepted by the real origination and acceptance, drawn through the real {@link CreditLineDraws}, stated and
 * collected by the real T-svc, repaid by the real {@link Repayments} (the one posting half, `-018`'s) and closed by the real
 * {@link LineClosures} and {@link LoanClosures}; a paydown's reversal runs `-020`'s real {@link RepaymentReversals}. A line's
 * past is the fixture's clock, never a path: its opening and its draws' and repayments' value dates moved back by the
 * bootstrap role ({@link CreditLineStatementDatabaseTest#openOn}, {@link CreditLineStatementDatabaseTest#dateDraw},
 * {@link #dateRepayment}).
 */
@Tag("database")
@DisplayName("credit line repayment and closure (P11-TSK-023)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class CreditLineRepaymentDatabaseTest {

    static final JdbcLineClosureStore CLOSURE_STORE = new JdbcLineClosureStore();
    static final LineClosures LINE_CLOSURES = new LineClosures(LOAN_STORE, CLOSURE_STORE, TERMS_STORE, CLOSURES,
            new JdbcOutboxWriter(), IDS, CLOCK);

    // ------------------------------------------------------------------ the paydown (A26)

    @Test
    @DisplayName("a payment beyond what is due pays down principal: the statement's interest and principal first (FEES ->"
            + " INTEREST -> PRINCIPAL), then 200.00 of principal not yet due - one entry crediting LOAN_PRINCIPAL the"
            + " paydown, the event's principalPaidDownMinor, nothing held; the line stays ACTIVE; a payment below the"
            + " minimum pays the oldest component first and nothing down")
    void aPaymentBeyondWhatIsDuePaysDownPrincipal() {
        LocalDate statementDate = onOrBefore(today());
        List<DisbursementWorld.Accepted> lines = lines(2, 4_000_00, statementDate.getDayOfMonth(), 25, false);
        for (DisbursementWorld.Accepted line : lines) {
            openOn(line, statementDate.minusDays(20));
            dateDraw(CreditLineStatementDatabaseTest.draw(line, 2_000_00), statementDate.minusDays(19));
            assertThat(service(line.loan()).stated()).isEqualTo(1);
        }
        DisbursementWorld.Accepted line = lines.get(0);
        Statement figures = statements(line).get(0).statement().statement();
        long interest = figures.interestBilled().minorUnits();
        long principalBilled = figures.principalBilled().minorUnits();
        assertThat(interest).as("the cycle earned interest").isPositive();
        long principalBefore = netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL)).minorUnits();

        Repayment repaid = repay(line, interest + principalBilled + 200_00).repayment();
        assertThat(repaid.allocations()).extracting(allocation -> allocation.line().target() + ":"
                        + allocation.line().component().map(Enum::name).orElse("-") + ":"
                        + allocation.line().amount().minorUnits())
                .containsExactly("DUE_ITEM:INTEREST:" + interest, "DUE_ITEM:PRINCIPAL:" + principalBilled,
                        "NOT_DUE_PRINCIPAL:-:" + 200_00);
        assertThat(statements(line).get(0).outstanding()).isEqualTo(eur(0));
        assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL)).minorUnits())
                .as("the paydown credits principal not yet due").isEqualTo(principalBefore - 200_00);
        assertThat(count("SELECT count(*) FROM ledger.journal_line WHERE entry_id = ? AND ledger_account_id = ?"
                + " AND direction = 'CREDIT' AND amount_minor = 20000", repaid.journalEntry().value(),
                loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL).value()))
                .as("rule 5's CR LOAN_PRINCIPAL line, in the repayment's one entry").isEqualTo(1);
        assertThat(held(line.loan())).as("nothing held: principal remained to pay down").isZero();
        assertThat(scalar("SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = ?", line.loan().value(), Repayments.RECEIVED_EVENT))
                .contains("\"principalPaidDownMinor\":\"20000\"", "\"creditHeldMinor\":\"0\"", "\"closed\":\"false\"");
        assertThat(loanStatus(line.loan())).as("an open line paid down stays open").isEqualTo("ACTIVE");
        assertLineSubledgerProves(line, 0);

        // Below the minimum: the oldest component first, nothing paid down.
        DisbursementWorld.Accepted small = lines.get(1);
        Repayment part = repay(small, 1_00).repayment();
        assertThat(part.allocations()).extracting(allocation -> allocation.line().target() + ":"
                + allocation.line().component().map(Enum::name).orElse("-")).containsExactly("DUE_ITEM:INTEREST");
        assertLineSubledgerProves(small, 0);
    }

    @Test
    @DisplayName("a paydown restores the available limit at its commit: 800.00 drawn of 1,000.00 leaves 200.00; 300.00"
            + " repaid with nothing due pays principal down and leaves 500.00 - a draw of 500.01 refused, one of 500.00"
            + " admitted; a paydown racing a draw for the restored limit serialises on L3 - the draw admitted only after"
            + " the paydown, never beyond the limit")
    void aPaydownRestoresTheAvailableLimit() throws Exception {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        CreditLineStatementDatabaseTest.draw(line, 800_00);
        assertThat(available(line)).isEqualTo(eur(200_00));
        Money headroomBefore = inOneTransaction(uow -> DisbursementWorld.HEADROOM.atRest(uow, DisbursementWorld.EUR))
                .headroom();
        Repayment paydown = repay(line, 300_00).repayment();
        Money headroomAfter = inOneTransaction(uow -> DisbursementWorld.HEADROOM.atRest(uow, DisbursementWorld.EUR))
                .headroom();
        assertThat(headroomAfter).as("capital headroom restored by the principal repaid, at commit")
                .isEqualTo(headroomBefore.plus(eur(300_00)));
        assertThat(paydown.allocations()).extracting(allocation -> allocation.line().target())
                .containsExactly(AllocationLine.Target.NOT_DUE_PRINCIPAL);
        assertThat(available(line)).as("restored at the paydown's commit").isEqualTo(eur(500_00));
        fund(eur(1_000_00));
        assertThatExceptionOfType(CreditLineDraws.LimitExceeded.class)
                .isThrownBy(() -> CreditLineDrawDatabaseTest.draw(line, key(), 500_01));
        CreditLineDrawDatabaseTest.draw(line, key(), 500_00);
        assertThat(available(line)).isEqualTo(eur(0));
        assertLineSubledgerProves(line, 0);

        // Racing: a paydown of 400.00 and a draw of 400.00 on lines drawn to their limit.
        for (int i = 0; i < 3; i++) {
            DisbursementWorld.Accepted full = acceptedLine(1_000_00);
            CreditLineStatementDatabaseTest.draw(full, 1_000_00);
            fund(eur(400_00));
            List<String> outcomes = race(2, index -> {
                if (index == 0) {
                    repay(full, 400_00);
                    return "REPAID";
                }
                try {
                    CreditLineDrawDatabaseTest.draw(full, key(), 400_00);
                    return "DRAWN";
                } catch (CreditLineDraws.LimitExceeded exceeded) {
                    return "LIMIT_EXCEEDED";
                }
            });
            Money drawn = drawn(full);
            assertThat(drawn.compareTo(eur(1_000_00))).as("never beyond the limit").isLessThanOrEqualTo(0);
            assertThat(drawn).isEqualTo(outcomes.get(1).equals("DRAWN") ? eur(1_000_00) : eur(600_00));
            assertLineSubledgerProves(full, 0);
        }
    }

    @Test
    @DisplayName("only the excess over every receivable is held: a payment of the statement, all principal not yet due and"
            + " 25.00 more - the statement paid, principal zero, exactly 25.00 held as credit; the line stays ACTIVE")
    void onlyTheExcessOverEveryReceivableIsHeld() {
        LocalDate statementDate = onOrBefore(today());
        DisbursementWorld.Accepted line = lines(1, 4_000_00, statementDate.getDayOfMonth(), 25, false).get(0);
        openOn(line, statementDate.minusDays(20));
        dateDraw(CreditLineStatementDatabaseTest.draw(line, 1_000_00), statementDate.minusDays(19));
        assertThat(service(line.loan()).stated()).isEqualTo(1);
        long minimum = statements(line).get(0).outstanding().minorUnits();
        long notDue = netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL)).minorUnits();
        topUp(line.party(), 100_00);
        Repayment repaid = repay(line, minimum + notDue + 25_00).repayment();
        assertThat(repaid.to(AllocationLine.Target.NOT_DUE_PRINCIPAL)).isEqualTo(eur(notDue));
        assertThat(repaid.to(AllocationLine.Target.CREDIT_BALANCE)).isEqualTo(eur(25_00));
        assertThat(held(line.loan())).as("exactly the excess over every receivable it reaches").isEqualTo(25_00);
        assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL))).isEqualTo(eur(0));
        assertThat(due(line.loan())).isZero();
        assertThat(loanStatus(line.loan())).isEqualTo("ACTIVE");
        assertLineSubledgerProves(line, 0);
    }

    // ------------------------------------------------------------------ the reversal (-020 over a line)

    @Test
    @DisplayName("a line's paydown reversed after a re-draw is admitted: 1,000.00 drawn, 600.00 paid down, 600.00 drawn"
            + " again - the paydown's reversal under four eyes re-opens its principal: drawn 1,600.00 above the 1,000.00"
            + " limit, the available limit zero, a further draw refused; the wallet credited back; the arms prove")
    void aLinePaydownReversedAfterARedrawIsAdmitted() {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        CreditLineStatementDatabaseTest.draw(line, 1_000_00);
        Repayment paydown = repay(line, 600_00).repayment();
        assertThat(available(line)).isEqualTo(eur(600_00));
        CreditLineStatementDatabaseTest.draw(line, 600_00);
        assertThat(available(line)).isEqualTo(eur(0));
        long walletBefore = -netDebit(walletOf(line.party())).minorUnits();

        RepaymentReversalDatabaseTest.Decided decided = RepaymentReversalDatabaseTest.proposeAndApprove(line.loan(), paydown);
        assertThat(decided.reversal().status()).isEqualTo(RepaymentReversalStatus.APPROVED);
        assertThat(drawn(line)).as("drawn principal above the limit - a correction stays possible").isEqualTo(eur(1_600_00));
        assertThat(available(line)).as("the available limit zero until repaid below the limit").isEqualTo(eur(0));
        assertThat(-netDebit(walletOf(line.party())).minorUnits()).isEqualTo(walletBefore + 600_00);
        fund(eur(100_00));
        assertThatExceptionOfType(CreditLineDraws.LimitExceeded.class)
                .isThrownBy(() -> CreditLineDrawDatabaseTest.draw(line, key(), 1_00));
        assertLineSubledgerProves(line, 0);
    }

    @Test
    @DisplayName("a line's automatic collection is reversible (carried from -022): the statement's minimum collected on its"
            + " due date, then the collection reversed under four eyes - the statement due again by exactly what it paid,"
            + " the principal it paid drawn again")
    void aLinesAutoCollectionIsReversible() {
        int dueDays = dueDaysLandingToday();
        LocalDate statementDate = today().minusDays(dueDays);
        DisbursementWorld.Accepted line = lines(1, 4_000_00, statementDate.getDayOfMonth(), dueDays, true).get(0);
        openOn(line, statementDate.minusDays(20));
        dateDraw(CreditLineStatementDatabaseTest.draw(line, 1_500_00), statementDate.minusDays(19));
        assertThat(service(line.loan()).collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
        StatedItem item = statements(line).get(0);
        assertThat(item.outstanding()).isEqualTo(eur(0));
        Repayment collection = RepaymentDatabaseTest.repayments(line.loan()).get(0);
        assertThat(collection.kind()).isEqualTo(RepaymentKind.AUTO_COLLECTION);
        Money availableBefore = available(line);

        RepaymentReversalDatabaseTest.Decided decided =
                RepaymentReversalDatabaseTest.proposeAndApprove(line.loan(), collection);
        assertThat(decided.reversal().status()).isEqualTo(RepaymentReversalStatus.APPROVED);
        assertThat(statements(line).get(0).outstanding()).as("due again by exactly what it paid")
                .isEqualTo(item.statement().statement().minimumPayment());
        assertThat(available(line)).isEqualTo(availableBefore.minus(item.statement().statement().principalBilled()));
        assertLineSubledgerProves(line, 0);
    }

    // ------------------------------------------------------------------ the closure

    @Test
    @DisplayName("closure stops draws: a drawn line's closure request moves it ACTIVE -> CLOSING (the request born CLOSING,"
            + " its history row CLOSURE_REQUESTED under the customer, lending.LineClosureRequested); a draw is 409"
            + " lending.LineNotDrawable and the read shows nothing to draw; the closing line still takes repayment")
    void closureStopsDraws() {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        CreditLineStatementDatabaseTest.draw(line, 300_00);
        LineClosures.Requested requested = requestClosure(line, key());
        assertThat(requested.request().outcome()).isEqualTo(LineClosureOutcome.CLOSING);
        assertThat(requested.closure()).isEmpty();
        assertThat(loanStatus(line.loan())).isEqualTo("CLOSING");
        assertThat(count("SELECT count(*) FROM lending.loan_event WHERE loan_id = ? AND from_status = 'ACTIVE'"
                + " AND to_status = 'CLOSING' AND reason = 'CLOSURE_REQUESTED' AND actor = ? AND actor_type = 'CUSTOMER'",
                line.loan().value(), line.party().toString())).isEqualTo(1);
        assertThat(scalar("SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = ?", line.loan().value(), LineClosures.REQUESTED_EVENT)).contains("\"outcome\":\"CLOSING\"");
        fund(eur(100_00));
        assertThatExceptionOfType(CreditLineDraws.LineNotDrawable.class)
                .isThrownBy(() -> CreditLineDrawDatabaseTest.draw(line, key(), 100_00));
        assertThat(available(line)).as("a closing line shows nothing to draw").isEqualTo(eur(0));
        Repayment part = repay(line, 100_00).repayment();
        assertThat(part.to(AllocationLine.Target.NOT_DUE_PRINCIPAL)).isEqualTo(eur(100_00));
        assertThat(loanStatus(line.loan())).as("200.00 still drawn - still closing").isEqualTo("CLOSING");
        assertThat(count("SELECT count(*) FROM lending.loan_closure WHERE loan_id = ?", line.loan().value())).isZero();
        assertLineSubledgerProves(line, 0);
    }

    @Test
    @DisplayName("a line with nothing outstanding closes at the request: never drawn - CLOSED (CLOSED_BY_CUSTOMER) at once,"
            + " nothing refunded; paid beyond everything - the 30.00 held refunded to the wallet under"
            + " lending.closure-refund:<line>, all six balances zero; a closed line refuses a repayment (409"
            + " lending.LoanNotRepayable), a draw and a second request (409 lending.LoanClosed), its own key replays")
    void aLineWithNothingOutstandingClosesAtTheRequest() {
        DisbursementWorld.Accepted never = acceptedLine(1_000_00);
        String key = key();
        LineClosures.Requested empty = requestClosure(never, key);
        assertThat(empty.request().outcome()).isEqualTo(LineClosureOutcome.CLOSED);
        LoanClosure closed = empty.closure().orElseThrow();
        assertThat(closed.reason()).isEqualTo(ClosureReason.CLOSED_BY_CUSTOMER);
        assertThat(closed.refund()).isEqualTo(eur(0));
        assertThat(closed.refundEntry()).isEmpty();
        assertThat(closed.repayment()).isEmpty();
        assertThat(scalar("SELECT status || ':' || closure_reason || ':' || (closed_at IS NOT NULL) FROM lending.loan"
                + " WHERE id = ?", never.loan().value())).isEqualTo("CLOSED:CLOSED_BY_CUSTOMER:true");
        assertThat(events(never.loan(), LoanClosures.CLOSED_EVENT)).isEqualTo(1);
        assertThat(events(never.loan(), LineClosures.REQUESTED_EVENT)).isEqualTo(1);

        DisbursementWorld.Accepted paid = acceptedLine(1_000_00);
        CreditLineStatementDatabaseTest.draw(paid, 100_00);
        topUp(paid.party(), 30_00);
        Repayment over = repay(paid, 130_00).repayment();
        assertThat(over.to(AllocationLine.Target.CREDIT_BALANCE)).isEqualTo(eur(30_00));
        assertThat(loanStatus(paid.loan())).as("an open line paid to zero stays open").isEqualTo("ACTIVE");
        long walletBefore = -netDebit(walletOf(paid.party())).minorUnits();
        LoanClosure refunded = requestClosure(paid, key()).closure().orElseThrow();
        assertThat(refunded.refund()).isEqualTo(eur(30_00));
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE id = ? AND idempotency_scope = ?",
                refunded.refundEntry().orElseThrow().value(),
                "ledger.post:" + LoanClosures.REFUND_KEY_PREFIX + paid.loan().value())).isEqualTo(1);
        assertThat(-netDebit(walletOf(paid.party())).minorUnits()).isEqualTo(walletBefore + 30_00);
        for (AccountPurpose purpose : List.of(AccountPurpose.LOAN_PRINCIPAL, AccountPurpose.LOAN_PRINCIPAL_DUE,
                AccountPurpose.LOAN_INTEREST_ACCRUED, AccountPurpose.LOAN_INTEREST_DUE, AccountPurpose.LOAN_FEES_DUE,
                AccountPurpose.LOAN_CREDIT_BALANCE)) {
            assertThat(netDebit(loanAccount(paid.loan(), purpose))).as(purpose + " zero after closure").isEqualTo(eur(0));
        }
        assertLineSubledgerProves(paid, 0);

        // Settled, but the refund's wallet cannot be credited now: CLOSING, and the servicing step closes it once it can.
        DisbursementWorld.Accepted blocked = acceptedLine(1_000_00);
        CreditLineStatementDatabaseTest.draw(blocked, 100_00);
        topUp(blocked.party(), 10_00);
        repay(blocked, 110_00);
        DisbursementWorld.UNREACHABLE_WALLETS.add(blocked.party());
        try {
            assertThat(requestClosure(blocked, key()).request().outcome()).isEqualTo(LineClosureOutcome.CLOSING);
            assertThat(held(blocked.loan())).as("nothing refunded, nothing written beyond the request").isEqualTo(10_00);
        } finally {
            DisbursementWorld.UNREACHABLE_WALLETS.remove(blocked.party());
        }
        assertThat(service(blocked.loan()).closed()).as("closed by the step once the wallet takes the refund").isTrue();
        assertThat(loanStatus(blocked.loan())).isEqualTo("CLOSED");
        assertThat(held(blocked.loan())).isZero();
        assertLineSubledgerProves(blocked, 0);

        // Closed: terminal.
        assertThatExceptionOfType(Repayments.LoanNotRepayable.class).isThrownBy(() -> repay(never, 1_00));
        fund(eur(100_00));
        assertThatExceptionOfType(CreditLineDraws.LineNotDrawable.class)
                .isThrownBy(() -> CreditLineDrawDatabaseTest.draw(never, key(), 1_00));
        assertThatExceptionOfType(LineClosures.LoanClosed.class).isThrownBy(() -> requestClosure(never, key()));
        LineClosures.Requested replay = requestClosure(never, key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.request().id()).isEqualTo(empty.request().id());
        assertThat(count("SELECT count(*) FROM lending.line_closure_request WHERE loan_id = ?", never.loan().value()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ?", never.loan().value())).isZero();
    }

    @Test
    @DisplayName("the last payment closes a closing line: a partial payment leaves it CLOSING; the payment that zeroes the"
            + " last receivable closes it CLOSED_BY_CUSTOMER in its own transaction - the closure naming it, the excess"
            + " refunded, the RepaymentReceived event saying closed")
    void theLastPaymentClosesAClosingLine() {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        CreditLineStatementDatabaseTest.draw(line, 500_00);
        requestClosure(line, key());
        Repayments.Repaid part = repay(line, 200_00);
        assertThat(part.closure()).isEmpty();
        assertThat(loanStatus(line.loan())).isEqualTo("CLOSING");
        topUp(line.party(), 20_00);
        Repayments.Repaid last = repay(line, 320_00);
        LoanClosure closure = last.closure().orElseThrow();
        assertThat(closure.reason()).isEqualTo(ClosureReason.CLOSED_BY_CUSTOMER);
        assertThat(closure.repayment()).contains(last.repayment().id());
        assertThat(closure.refund()).isEqualTo(eur(20_00));
        assertThat(scalar("SELECT status || ':' || closure_reason FROM lending.loan WHERE id = ?", line.loan().value()))
                .isEqualTo("CLOSED:CLOSED_BY_CUSTOMER");
        assertThat(count("SELECT count(*) FROM lending.loan_event WHERE loan_id = ? AND from_status = 'CLOSING'"
                + " AND to_status = 'CLOSED' AND reason = 'CLOSED_BY_CUSTOMER'", line.loan().value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?"
                + " AND convert_from(payload, 'UTF8') LIKE '%\"closed\":\"true\"%'", line.loan().value(),
                Repayments.RECEIVED_EVENT)).isEqualTo(1);
        assertThat(held(line.loan())).isZero();
        assertLineSubledgerProves(line, 0);
    }

    @Test
    @DisplayName("a closing line closes at servicing once its statement is settled: paid down before its statement, the"
            + " step states the cycle's interest and applies the held credit to it (rule 6), then closes the line and"
            + " refunds the rest; with auto-collection on, the collection on the due date that settles the statement"
            + " closes it - the attempt naming the closing collection")
    void aClosingLineClosesAtServicingWhenItsStatementIsSettled() {
        LocalDate statementDate = onOrBefore(today());
        DisbursementWorld.Accepted line = lines(1, 4_000_00, statementDate.getDayOfMonth(), 25, false).get(0);
        openOn(line, statementDate.minusDays(20));
        dateDraw(CreditLineStatementDatabaseTest.draw(line, 1_000_00), statementDate.minusDays(19));
        requestClosure(line, key());
        topUp(line.party(), 50_00);
        Repayment paidDown = repay(line, 1_050_00).repayment();
        assertThat(paidDown.to(AllocationLine.Target.CREDIT_BALANCE)).isEqualTo(eur(50_00));
        assertThat(loanStatus(line.loan())).as("days drawn not yet accrued: not settled").isEqualTo("CLOSING");
        dateRepayment(paidDown, statementDate.minusDays(1));
        LoanServicing.Outcome step = service(line.loan());
        assertThat(step.stated()).isEqualTo(1);
        assertThat(step.statedApplied()).isEqualTo(1);
        assertThat(step.closed()).isTrue();
        long interest = statements(line).get(0).statement().statement().interestBilled().minorUnits();
        assertThat(interest).isPositive();
        assertThat(count("SELECT refund_minor FROM lending.loan_closure WHERE loan_id = ? AND reason = 'CLOSED_BY_CUSTOMER'"
                + " AND repayment_id IS NULL", line.loan().value())).as("the held credit less the interest it paid")
                .isEqualTo(50_00 - interest);
        assertThat(loanStatus(line.loan())).isEqualTo("CLOSED");
        assertLineSubledgerProves(line, 0);

        // Auto-collection: the statement's interest collected on its due date closes the line.
        int dueDays = dueDaysLandingToday();
        LocalDate stated = today().minusDays(dueDays);
        DisbursementWorld.Accepted auto = lines(1, 4_000_00, stated.getDayOfMonth(), dueDays, true).get(0);
        openOn(auto, stated.minusDays(20));
        dateDraw(CreditLineStatementDatabaseTest.draw(auto, 1_500_00), stated.minusDays(19));
        requestClosure(auto, key());
        dateRepayment(repay(auto, 1_500_00).repayment(), stated.minusDays(1));
        topUp(auto.party(), 100_00);
        LoanServicing.Outcome collected = service(auto.loan());
        assertThat(collected.collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
        assertThat(collected.closed()).isTrue();
        assertThat(loanStatus(auto.loan())).isEqualTo("CLOSED");
        assertThat(count("SELECT count(*) FROM lending.collection_attempt a JOIN lending.loan_closure c"
                + " ON c.repayment_id = a.repayment_id WHERE a.loan_id = ? AND a.outcome = 'COLLECTED'", auto.loan().value()))
                .as("the attempt names the collection that closed the line").isEqualTo(1);
        assertLineSubledgerProves(auto, 0);
    }

    @Test
    @DisplayName("a line is not closed over an unaccrued day: drawn four days ago and repaid in full today before any"
            + " servicing step - every receivable reads zero, but drawn days are not yet accrued, so the request moves it"
            + " CLOSING, never CLOSED; the step then accrues those days and the line stays CLOSING, owing their interest")
    void aLineIsNotClosedOverAnUnaccruedDay() {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        openOn(line, today().minusDays(5));
        dateDraw(CreditLineStatementDatabaseTest.draw(line, 400_00), today().minusDays(4));
        repay(line, 400_00);
        assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_INTEREST_ACCRUED))).isEqualTo(eur(0));
        assertThat(requestClosure(line, key()).request().outcome()).isEqualTo(LineClosureOutcome.CLOSING);
        assertThat(service(line.loan()).posted()).as("the drawn days accrued").isPositive();
        assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_INTEREST_ACCRUED)).isPositive()).isTrue();
        assertThat(loanStatus(line.loan())).isEqualTo("CLOSING");
        assertLineSubledgerProves(line, 0);
    }

    @Test
    @DisplayName("a closing line is never reopened: CLOSING -> ACTIVE is refused for every role (the application role's"
            + " granted status column meets the edge function, as does the migrator), and nothing leaves CLOSED -"
            + " neither to ACTIVE nor to CLOSING")
    void aClosingLineIsNeverReopened() throws Exception {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        CreditLineStatementDatabaseTest.draw(line, 200_00);
        requestClosure(line, key());
        UUID id = line.loan().value();
        try (Connection app = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            refused(app, "UPDATE lending.loan SET status = 'ACTIVE' WHERE id = '" + id + "'", "P0001", "not an edge");
            refused(migrator, "UPDATE lending.loan SET status = 'ACTIVE' WHERE id = '" + id + "'", "P0001", "not an edge");
        }
        assertThat(loanStatus(line.loan())).isEqualTo("CLOSING");
        assertThat(repay(line, 200_00).closure()).isPresent();
        try (Connection app = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            for (Connection role : List.of(app, migrator)) {
                refused(role, "UPDATE lending.loan SET status = 'ACTIVE', closure_reason = NULL WHERE id = '" + id + "'",
                        "P0001", "not an edge");
                refused(role, "UPDATE lending.loan SET status = 'CLOSING', closure_reason = NULL WHERE id = '" + id + "'",
                        "P0001", "not an edge");
            }
        }
        assertThat(loanStatus(line.loan())).isEqualTo("CLOSED");
    }

    // ------------------------------------------------------------------ the races

    @Test
    @DisplayName("a closure racing a draw serialises on L3: the request waits behind a holder of the line's row and then"
            + " moves it CLOSING; on four more lines a draw and a request released together - the draw admitted before"
            + " CLOSING or refused after it, never a draw born after the request")
    void closureRacingADrawSerialises() throws Exception {
        DisbursementWorld.Accepted held = acceptedLine(1_000_00);
        CreditLineStatementDatabaseTest.draw(held, 100_00);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, held.loan().value());
                lock.executeQuery().close();
            }
            Future<LineClosures.Requested> waiting = pool.submit(() -> requestClosure(held, key()));
            assertThat(awaitBlockedBy(pid(holder)).query()).contains("lending.loan");
            assertThat(loanStatus(held.loan())).isEqualTo("ACTIVE");
            holder.commit();
            assertThat(waiting.get(1, TimeUnit.MINUTES).request().outcome()).isEqualTo(LineClosureOutcome.CLOSING);
        } finally {
            pool.shutdownNow();
        }

        for (int i = 0; i < 4; i++) {
            DisbursementWorld.Accepted line = acceptedLine(1_000_00);
            CreditLineStatementDatabaseTest.draw(line, 100_00);
            fund(eur(200_00));
            List<String> outcomes = race(2, index -> {
                if (index == 0) {
                    return requestClosure(line, key()).request().outcome().name();
                }
                try {
                    CreditLineDrawDatabaseTest.draw(line, key(), 200_00);
                    return "DRAWN";
                } catch (CreditLineDraws.LineNotDrawable refused) {
                    return "NOT_DRAWABLE";
                }
            });
            assertThat(outcomes.get(0)).isEqualTo("CLOSING");
            assertThat(loanStatus(line.loan())).isEqualTo("CLOSING");
            assertThat(drawn(line)).isEqualTo(outcomes.get(1).equals("DRAWN") ? eur(300_00) : eur(100_00));
            assertThat(count("SELECT count(*) FROM lending.credit_line_draw d JOIN lending.line_closure_request q"
                    + " ON q.loan_id = d.loan_id WHERE d.loan_id = ? AND d.created_at > q.requested_at", line.loan().value()))
                    .as("no draw born after the request").isZero();
            assertLineSubledgerProves(line, 0);
        }
    }

    @Test
    @DisplayName("ten closure requests, one request: ten keys racing on a drawn line - one request born (UNIQUE (loan_id)"
            + " behind L3), the other nine answered with it, one ACTIVE -> CLOSING edge and one event; ten on a line with"
            + " nothing drawn - one closes it, nine are 409 lending.LoanClosed, one closure")
    void tenClosureRequestsOneRequest() throws Exception {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        CreditLineStatementDatabaseTest.draw(line, 100_00);
        List<LineClosures.Requested> answers = race(10, index -> requestClosure(line, key()));
        Set<UUID> requests = new HashSet<>();
        answers.forEach(answer -> requests.add(answer.request().id()));
        assertThat(requests).as("one request answers all ten").hasSize(1);
        assertThat(answers.stream().filter(answer -> !answer.replayed()).count()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.line_closure_request WHERE loan_id = ?", line.loan().value()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_event WHERE loan_id = ? AND to_status = 'CLOSING'",
                line.loan().value())).isEqualTo(1);
        assertThat(events(line.loan(), LineClosures.REQUESTED_EVENT)).isEqualTo(1);

        DisbursementWorld.Accepted empty = acceptedLine(1_000_00);
        List<String> outcomes = race(10, index -> {
            try {
                return requestClosure(empty, key()).request().outcome().name();
            } catch (LineClosures.LoanClosed closed) {
                return "LOAN_CLOSED";
            }
        });
        assertThat(outcomes.stream().filter("CLOSED"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter("LOAN_CLOSED"::equals).count()).isEqualTo(9);
        assertThat(count("SELECT count(*) FROM lending.loan_closure WHERE loan_id = ?", empty.loan().value())).isEqualTo(1);
        assertThat(events(empty.loan(), LoanClosures.CLOSED_EVENT)).isEqualTo(1);
    }

    @Test
    @DisplayName("the last payment and a statement on one date serialise on L3: a repayment and the servicing step released"
            + " together on lines whose statement date has come - in either order the payment is allocated against what"
            + " the other left, conserved, and the arms prove")
    void theLastPaymentAndAStatementOnOneDateSerialise() throws Exception {
        LocalDate statementDate = onOrBefore(today());
        List<DisbursementWorld.Accepted> lines = lines(3, 4_000_00, statementDate.getDayOfMonth(), 25, false);
        for (DisbursementWorld.Accepted line : lines) {
            openOn(line, statementDate.minusDays(20));
            dateDraw(CreditLineStatementDatabaseTest.draw(line, 1_000_00), statementDate.minusDays(19));
            race(2, index -> index == 0 ? service(line.loan()).stated() : repay(line, 300_00).repayment().hashCode());
            assertThat(statements(line)).hasSize(1);
            assertThat(count("SELECT sum(a.amount_minor) FROM lending.repayment_allocation a JOIN lending.repayment r"
                    + " ON r.id = a.repayment_id WHERE r.loan_id = ?", line.loan().value())).isEqualTo(300_00);
            assertLineSubledgerProves(line, 0);
        }
    }

    // ------------------------------------------------------------------ every writer

    @Test
    @DisplayName("the rows are held for every writer: a request only for an ACTIVE line (never a closing one, never an"
            + " instalment loan), never committed without its line moved, never updated, deleted or truncated; a line"
            + " moves CLOSING only beside its request and closes only beside it with every statement paid; an instalment"
            + " loan's closure request is 409 lending.LineNotClosable")
    void theRowsAreHeldForEveryWriter() throws Exception {
        DisbursementWorld.Accepted active = acceptedLine(1_000_00);
        DisbursementWorld.Accepted closing = acceptedLine(1_000_00);
        CreditLineStatementDatabaseTest.draw(closing, 100_00);
        requestClosure(closing, key());
        DisbursementWorld.Accepted loan = acceptedLoan(1_000_00, 12);
        try (Connection app = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            refused(app, rawRequest(closing.loan().value(), "CLOSING"), "P0001", "only for an ACTIVE credit line");
            refused(app, rawRequest(loan.loan().value(), "CLOSING"), "P0001", "only for an ACTIVE credit line");
            refused(app, rawRequest(active.loan().value(), "CLOSING"), "P0001", "the state its outcome names");
            refused(app, "UPDATE lending.loan SET status = 'CLOSING' WHERE id = '" + active.loan().value() + "'", "P0001",
                    "only beside the customer's closure request");
            refused(app, "INSERT INTO lending.loan_closure (id, loan_id, reason, currency, scale, refund_minor,"
                    + " refund_journal_entry_id, repayment_id, created_at, correlation_id) VALUES (gen_random_uuid(), '"
                    + active.loan().value() + "', 'CLOSED_BY_CUSTOMER', 'EUR', 2, 0, NULL, NULL, statement_timestamp(),"
                    + " 'p11t23-raw')", "P0001", "beside its closure request");
            refused(app, "UPDATE lending.line_closure_request SET outcome = 'CLOSED' WHERE loan_id = '"
                    + closing.loan().value() + "'", "42501", "");
            refused(app, "DELETE FROM lending.line_closure_request WHERE loan_id = '" + closing.loan().value() + "'",
                    "42501", "");
            refused(migrator, "UPDATE lending.line_closure_request SET outcome = 'CLOSED' WHERE loan_id = '"
                    + closing.loan().value() + "'", "P0001", "immutable");
            refused(migrator, "DELETE FROM lending.line_closure_request WHERE loan_id = '" + closing.loan().value() + "'",
                    "P0001", "immutable");
            refused(migrator, "TRUNCATE lending.line_closure_request", "P0001", "");
        }
        assertThat(loanStatus(active.loan())).isEqualTo("ACTIVE");
        // One request per line beneath the lock and the triggers: a second, the triggers suspended, meets the unique.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> asOwner(rawRequest(closing.loan().value(), "CLOSING")))
                .satisfies(failure -> assertThat(((java.sql.SQLException) failure.getCause()).getSQLState())
                        .isEqualTo("23505"));
        assertThat(count("SELECT count(*) FROM lending.line_closure_request WHERE loan_id = ?", closing.loan().value()))
                .isEqualTo(1);
        assertThatExceptionOfType(LineClosures.LineNotClosable.class).isThrownBy(() -> requestClosure(loan, key()));
        assertThat(count("SELECT count(*) FROM lending.line_closure_request WHERE loan_id = ?", loan.loan().value()))
                .isZero();

        // A closing line with a statement unpaid: its closure is refused by the rows' side.
        LocalDate statementDate = onOrBefore(today());
        DisbursementWorld.Accepted stated = lines(1, 4_000_00, statementDate.getDayOfMonth(), 25, false).get(0);
        openOn(stated, statementDate.minusDays(20));
        dateDraw(CreditLineStatementDatabaseTest.draw(stated, 1_000_00), statementDate.minusDays(19));
        assertThat(service(stated.loan()).stated()).isEqualTo(1);
        requestClosure(stated, key());
        try (Connection app = DatabaseRoles.application()) {
            refused(app, "INSERT INTO lending.loan_closure (id, loan_id, reason, currency, scale, refund_minor,"
                    + " refund_journal_entry_id, repayment_id, created_at, correlation_id) VALUES (gen_random_uuid(), '"
                    + stated.loan().value() + "', 'CLOSED_BY_CUSTOMER', 'EUR', 2, 0, NULL, NULL, statement_timestamp(),"
                    + " 'p11t23-raw')", "P0001", "every statement is paid in full");
        }
        assertThat(loanStatus(stated.loan())).isEqualTo("CLOSING");
    }

    // ------------------------------------------------------------------ the accrual lag's line arm (carried from -021)

    @Test
    @DisplayName("the accrual lag counts only a line's days with principal drawn: a line drawn eight days ago and never"
            + " serviced lags from the end of its draw date; a line paid down to zero and serviced - its zero days"
            + " unaccrued by rule - does not lag; a line never drawn is not even a candidate")
    void theAccrualLagCountsOnlyALinesDaysWithPrincipal() {
        DisbursementWorld.Accepted lagging = acceptedLine(1_000_00);
        openOn(lagging, today().minusDays(10));
        dateDraw(CreditLineStatementDatabaseTest.draw(lagging, 300_00), today().minusDays(8));
        DisbursementWorld.Accepted paidDown = acceptedLine(1_000_00);
        openOn(paidDown, today().minusDays(10));
        dateDraw(CreditLineStatementDatabaseTest.draw(paidDown, 300_00), today().minusDays(8));
        dateRepayment(repay(paidDown, 300_00).repayment(), today().minusDays(6));
        assertThat(service(paidDown.loan()).posted()).as("its two drawn days accrued").isEqualTo(2);
        DisbursementWorld.Accepted never = acceptedLine(1_000_00);
        openOn(never, today().minusDays(10));

        AccrualLags lags = new AccrualLags(InterestAccrualDatabaseTest.SERVICING_STORE, LEDGER_ACCOUNTS, BALANCES);
        Instant now = inOneTransaction(uow -> InterestAccrualDatabaseTest.SERVICING_STORE.databaseNow(uow));
        List<LoanServicingStore.LineAwaitingAccrual> candidates =
                inOneTransaction(uow -> InterestAccrualDatabaseTest.SERVICING_STORE.linesAwaitingAccrual(uow));
        assertThat(candidates).extracting(LoanServicingStore.LineAwaitingAccrual::loan)
                .contains(lagging.loan(), paidDown.loan()).doesNotContain(never.loan());
        LoanServicingStore.LineAwaitingAccrual laggingLine = candidate(candidates, lagging.loan());
        assertThat(laggingLine.nextDate()).isEqualTo(today().minusDays(10));
        Duration lag = inOneTransaction(uow -> lags.lineLag(uow, laggingLine, now)).orElseThrow();
        Duration expected = Duration.between(today().minusDays(7).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), now);
        assertThat(lag).as("from the end of the draw date, the first date with principal").isEqualTo(expected);
        java.util.Optional<Duration> zeroDays =
                inOneTransaction(uow -> lags.lineLag(uow, candidate(candidates, paidDown.loan()), now));
        assertThat(zeroDays).as("its zero days are not lag").isEmpty();
        java.util.Optional<Duration> oldest = inOneTransaction(lags::oldest);
        assertThat(oldest).isPresent();
    }

    // ------------------------------------------------------------------ the world

    /** The line's closure requested under its party in its own transaction - the door's, its claim aside. */
    static LineClosures.Requested requestClosure(DisbursementWorld.Accepted line, String key) {
        Actor actor = customer(line.party());
        CorrelationId correlation = correlation();
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation).causing(CausationId.of(correlation.value())))) {
            return inOneTransaction(uow -> LINE_CLOSURES.request(uow, line.loan(), line.party(), key, actor, correlation));
        }
    }

    static Repayments.Repaid repay(DisbursementWorld.Accepted line, long minor) {
        return repayAs(REPAYMENTS, line.party(), line.loan(), key(), minor);
    }

    /** The repayment's entry and row valued {@code date} - the fixture's clock, never a path. */
    static void dateRepayment(Repayment repayment, LocalDate date) {
        asOwner("UPDATE ledger.journal_entry SET posting_date = DATE '" + date + "', value_date = DATE '" + date
                + "' WHERE id = '" + repayment.journalEntry().value() + "';"
                + " UPDATE lending.repayment SET value_date = DATE '" + date + "' WHERE id = '" + repayment.id() + "'");
    }

    static Money drawn(DisbursementWorld.Accepted line) {
        return netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL))
                .plus(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL_DUE)));
    }

    static Money available(DisbursementWorld.Accepted line) {
        return inOneTransaction(uow -> new Loans(LOAN_STORE, LEDGER_ACCOUNTS, BALANCES, PAYOUT_STORE)
                .read(uow, line.loan(), line.party())).orElseThrow().availableLimit().orElseThrow();
    }

    private static LoanServicingStore.LineAwaitingAccrual candidate(
            List<LoanServicingStore.LineAwaitingAccrual> candidates, LoanId loan) {
        return candidates.stream().filter(line -> line.loan().equals(loan)).findFirst().orElseThrow();
    }

    /** An application-role closure request of {@code loan} for {@code outcome}, as raw SQL. */
    private static String rawRequest(UUID loan, String outcome) {
        return "INSERT INTO lending.line_closure_request (id, loan_id, request_key, outcome, requested_at, correlation_id)"
                + " VALUES (gen_random_uuid(), '" + loan + "', 'p11t23-raw-" + UUID.randomUUID() + "', '" + outcome
                + "', statement_timestamp(), 'p11t23-raw')";
    }
}
