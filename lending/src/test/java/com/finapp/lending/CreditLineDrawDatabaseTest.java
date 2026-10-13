package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.ACCEPTANCES;
import static com.finapp.lending.DisbursementWorld.ADMINISTRATION;
import static com.finapp.lending.DisbursementWorld.APPLICATIONS;
import static com.finapp.lending.DisbursementWorld.BALANCES;
import static com.finapp.lending.DisbursementWorld.CLOCK;
import static com.finapp.lending.DisbursementWorld.CREDIT;
import static com.finapp.lending.DisbursementWorld.EUR;
import static com.finapp.lending.DisbursementWorld.HEADROOM;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.LOAN_STORE;
import static com.finapp.lending.DisbursementWorld.ORIGINATION;
import static com.finapp.lending.DisbursementWorld.PAYOUT_STORE;
import static com.finapp.lending.DisbursementWorld.POSTINGS;
import static com.finapp.lending.DisbursementWorld.STANDING;
import static com.finapp.lending.DisbursementWorld.STANDING_PORT;
import static com.finapp.lending.DisbursementWorld.SYSTEM;
import static com.finapp.lending.DisbursementWorld.TERMS_STORE;
import static com.finapp.lending.DisbursementWorld.UNREACHABLE_WALLETS;
import static com.finapp.lending.DisbursementWorld.WALLETS;
import static com.finapp.lending.DisbursementWorld.acceptedLine;
import static com.finapp.lending.DisbursementWorld.acceptedLoan;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.awaitBlockedBy;
import static com.finapp.lending.DisbursementWorld.cashAtBank;
import static com.finapp.lending.DisbursementWorld.correlation;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.customer;
import static com.finapp.lending.DisbursementWorld.databaseNow;
import static com.finapp.lending.DisbursementWorld.ensureActive;
import static com.finapp.lending.DisbursementWorld.fund;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.pid;
import static com.finapp.lending.DisbursementWorld.post;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.DisbursementWorld.walletOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The credit line draw against a real database (`P11-TSK-021`; {@code lending V013}, {@code PHASE_11_PLAN.md} sections
 * 7.1 T-drw, 7.2, 7.4, 12.6, 12.8 and 12.9, ADR-0100 points 3, 4 and 10, ADR-0096; {@code INV-LND-14},
 * {@code INV-LND-13}, {@code INV-LND-01}, {@code INV-IDEM-01}, {@code INV-BAL-05}): lines accepted by the real acceptance
 * and drawn by the real {@link CreditLineDraws} over the real ledger, every race counted from the rows - two draws and ten
 * for the last of the limit, ten under one key (with the claim and without it), ten draws and acceptances for the last
 * capital in the currency; the order of the two locks proven by lock-waits; the limit at its boundary, never consumed by
 * interest or fees, restored by a principal repayment; a closing line, a lost standing, a suspended line and an
 * unreachable wallet refused with nothing written; the entry, the row and the event in one transaction; a draw accruing
 * from its value date; and the database's own ranks for every writer. Credit, the standing and the wallet are
 * {@link DisbursementWorld}'s doubles; the real door, MFA and ownership are {@code app}'s {@code CreditLineDrawApiTest} and
 * {@code LoanAccountDatabaseTest}.
 */
@Tag("database")
@DisplayName("the credit line draw (P11-TSK-021)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class CreditLineDrawDatabaseTest {

    static final JdbcCreditLineDrawStore DRAW_STORE = new JdbcCreditLineDrawStore();
    static final CreditLineDraws DRAWS = draws(DrawSuspension.BEFORE_ANY_CONDITION);
    static final IdempotentExecutor DOOR_CLAIM = new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK,
            Duration.ofDays(1), Duration.ofMinutes(5));

    // ------------------------------------------------------------------ the available limit (INV-LND-14)

    @Test
    @DisplayName("two draws racing for the available limit admit one - the other 422 lending.LimitExceeded - and ten"
            + " racing for its last 300.00 admit exactly three: the drawn principal never passes the limit")
    void twoDrawsRacingForTheLimitAdmitOne() throws Exception {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        fund(eur(10_000_00));
        List<String> two = race(2, index -> outcome(() -> draw(line, key(), 600_00)));
        assertThat(two).containsExactlyInAnyOrder("DRAWN", "LIMIT_EXCEEDED");
        assertThat(drawn(line)).isEqualTo(eur(600_00));

        DisbursementWorld.Accepted last = acceptedLine(1_000_00);
        assertThat(draw(last, key(), 700_00).replayed()).isFalse();
        List<String> ten = race(10, index -> outcome(() -> draw(last, key(), 100_00)));
        assertThat(ten).filteredOn("DRAWN"::equals).as("exactly the last 300.00").hasSize(3);
        assertThat(ten).filteredOn("LIMIT_EXCEEDED"::equals).hasSize(7);
        assertThat(drawn(last)).as("INV-LND-14: drawn principal at the limit, never past it").isEqualTo(eur(1_000_00));
        assertThat(count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ?", last.loan().value()))
                .isEqualTo(4);
        assertSubledger(last);
    }

    @Test
    @DisplayName("a draw beyond the available limit is refused at the boundary - the exact remainder drawn, one minor unit"
            + " more 422 - and the refusal writes nothing: no row, no entry, no event, the wallet unmoved")
    void aDrawBeyondTheAvailableLimitIsRefused() {
        DisbursementWorld.Accepted line = acceptedLine(2_500_00);
        fund(eur(10_000_00));
        draw(line, key(), 1_000_00);
        Money walletBefore = netDebit(walletOf(line.party()));
        long entriesBefore = entries(line);
        assertThatExceptionOfType(CreditLineDraws.LimitExceeded.class).isThrownBy(() -> draw(line, key(), 1_500_01));
        assertThat(count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ?", line.loan().value()))
                .isEqualTo(1);
        assertThat(entries(line)).isEqualTo(entriesBefore);
        assertThat(netDebit(walletOf(line.party()))).isEqualTo(walletBefore);
        assertThat(events(line)).isEqualTo(1);
        assertThat(available(line)).as("the read derives the same limit").isEqualTo(eur(1_500_00));
        assertThat(draw(line, key(), 1_500_00).draw().amount()).isEqualTo(eur(1_500_00));
        assertThat(available(line)).isEqualTo(eur(0));
        assertThatExceptionOfType(CreditLineDraws.LimitExceeded.class).as("zero left: even one minor unit")
                .isThrownBy(() -> draw(line, key(), 1));
    }

    @Test
    @DisplayName("interest and fees never consume the limit; principal billed due still does, and a principal repayment -"
            + " due or not yet due - restores it at its commit")
    void interestAndFeesNeverConsumeTheLimit() {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        fund(eur(10_000_00));
        draw(line, key(), 400_00);
        // Owner-role journal fixtures standing in for accrual, a fee, a statement's billing and repayments (-016, -022,
        // -023): the limit is read from the two principal accounts alone.
        post("p11t21-interest:" + UUID.randomUUID(), List.of(
                line(loanAccount(line.loan(), AccountPurpose.LOAN_INTEREST_ACCRUED), Direction.DEBIT, 37_15),
                line(cashAtBank(), Direction.CREDIT, 37_15)));
        post("p11t21-due:" + UUID.randomUUID(), List.of(
                line(loanAccount(line.loan(), AccountPurpose.LOAN_INTEREST_DUE), Direction.DEBIT, 12_00),
                line(loanAccount(line.loan(), AccountPurpose.LOAN_FEES_DUE), Direction.DEBIT, 25_00),
                line(cashAtBank(), Direction.CREDIT, 37_00)));
        assertThat(available(line)).as("interest and fees are not principal").isEqualTo(eur(600_00));
        post("p11t21-billing:" + UUID.randomUUID(), List.of(
                line(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL_DUE), Direction.DEBIT, 50_00),
                line(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL), Direction.CREDIT, 50_00)));
        assertThat(available(line)).as("billed principal is still drawn").isEqualTo(eur(600_00));
        assertThatExceptionOfType(CreditLineDraws.LimitExceeded.class).isThrownBy(() -> draw(line, key(), 600_01));
        post("p11t21-paid-due:" + UUID.randomUUID(), List.of(
                line(cashAtBank(), Direction.DEBIT, 50_00),
                line(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL_DUE), Direction.CREDIT, 50_00)));
        post("p11t21-paid-down:" + UUID.randomUUID(), List.of(
                line(cashAtBank(), Direction.DEBIT, 100_00),
                line(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL), Direction.CREDIT, 100_00)));
        assertThat(available(line)).as("repaid principal, due and not due, restored").isEqualTo(eur(750_00));
        assertThat(draw(line, key(), 750_00).replayed()).as("the whole restored limit draws").isFalse();
        assertThat(available(line)).isEqualTo(eur(0));
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    @DisplayName("ten draws under one key draw once: through the door's claim one runs and the rest replay its receipt or"
            + " hear it in progress; with the claim lapsed, UNIQUE (loan_id, draw_key) converges nine on the one draw;"
            + " another amount under the key is refused - one row, one entry, one event")
    void tenDrawsOneKey() throws Exception {
        DisbursementWorld.Accepted line = acceptedLine(5_000_00);
        fund(eur(10_000_00));
        String key = key();
        List<String> claimed = race(10, index -> {
            try {
                IdempotentExecutor.ExecutionOutcome outcome = throughTheClaim(line, key, 1_234_56);
                return outcome.executed() ? "EXECUTED" : "REPLAYED";
            } catch (IdempotencyInProgressException inProgress) {
                return "IN_PROGRESS";
            }
        });
        assertThat(claimed).filteredOn("EXECUTED"::equals).hasSize(1);
        assertDrawnOnce(line, key, eur(1_234_56));
        assertThat(throughTheClaim(line, key, 1_234_56).executed()).as("a later retry replays").isFalse();

        DisbursementWorld.Accepted lapsed = acceptedLine(5_000_00);
        String lapsedKey = key();
        List<Boolean> replayed = race(10, index -> draw(lapsed, lapsedKey, 2_000_00).replayed());
        assertThat(replayed).filteredOn(Boolean.FALSE::equals).as("one draw made").hasSize(1);
        assertThat(replayed).filteredOn(Boolean.TRUE::equals).as("nine converged on it").hasSize(9);
        assertDrawnOnce(lapsed, lapsedKey, eur(2_000_00));
        assertThatExceptionOfType(CreditLineDraws.DrawKeyReused.class).isThrownBy(() -> draw(lapsed, lapsedKey, 2_000_01));
        assertDrawnOnce(lapsed, lapsedKey, eur(2_000_00));
    }

    // ------------------------------------------------------------------ capital (INV-LND-13)

    @Test
    @DisplayName("draws racing for the last capital admit one: behind a transaction holding L6 a draw waits on the capital"
            + " row holding its line; then five draws and five acceptances raced for exactly one's headroom - one"
            + " admitted, nine 422 lending.CapitalUnavailable, headroom exactly zero")
    void drawsRacingForTheLastCapitalAdmitOne() throws Exception {
        DisbursementWorld.Accepted waiter = acceptedLine(1_000_00);
        fund(eur(1_000_00));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            HEADROOM.underLock(holder, EUR);
            Future<CreditLineDraws.Drawn> drawing = pool.submit(() -> draw(waiter, key(), 1_000_00));
            DisbursementWorld.Blocked blocked = awaitBlockedBy(pid(holder));
            assertThat(blocked.query()).as("the draw waits on the capital row (L6)").contains("ledger.ledger_account");
            assertThat(lineLockableNoWait(waiter)).as("holding its line (L3) while it waits on L6").isFalse();
            holder.commit();
            assertThat(drawing.get(60, TimeUnit.SECONDS).replayed()).isFalse();
        } finally {
            pool.shutdownNow();
        }

        // Exactly 750.00 of headroom left: a surplus deployed by a filler line's draw (a line has no amount bounds), a
        // shortfall planted.
        Money last = eur(750_00);
        long surplus = headroomNow().headroom().minorUnits() - last.minorUnits();
        if (surplus > 0) {
            draw(acceptedLine(surplus), key(), surplus);
        } else if (surplus < 0) {
            plantCapital(eur(-surplus));
        }
        assertThat(headroomNow().headroom()).isEqualTo(last);
        Money outstandingBefore = headroomNow().outstanding();
        Money committedBefore = headroomNow().committed();
        List<DisbursementWorld.Accepted> lines = new ArrayList<>();
        List<Offered> offers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            lines.add(acceptedLine(last.minorUnits()));
            offers.add(offeredLoan(last.minorUnits()));
        }
        List<String> outcomes = race(10, index -> {
            try {
                if (index % 2 == 0) {
                    draw(lines.get(index / 2), key(), last.minorUnits());
                } else {
                    accept(offers.get(index / 2));
                }
                return "ADMITTED";
            } catch (LoanAcceptances.CapitalUnavailable short_) {
                return "CAPITAL_UNAVAILABLE";
            }
        });
        assertThat(outcomes).as("exactly one act takes the last capital").filteredOn("ADMITTED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("CAPITAL_UNAVAILABLE"::equals).hasSize(9);
        LendingCapitalHeadroom.Headroom after = headroomNow();
        assertThat(after.headroom().minorUnits()).as("INV-LND-13: never over-deployed").isZero();
        assertThat(after.outstanding().plus(after.committed()))
                .isEqualTo(outstandingBefore.plus(committedBefore).plus(last));
    }

    @Test
    @DisplayName("drawing takes the line, then the capital: a draw waits on the line (L3) while the capital row stays free,"
            + " and waits on the capital (L6) only while holding the line")
    void drawingTakesTheLineThenTheCapital() throws Exception {
        DisbursementWorld.Accepted line = acceptedLine(800_00);
        fund(eur(10_000_00));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            try (Connection holder = DatabaseRoles.application()) {
                holder.setAutoCommit(false);
                lock(holder, "SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE", line.loan().value());
                Future<CreditLineDraws.Drawn> drawing = pool.submit(() -> draw(line, key(), 100_00));
                assertThat(awaitBlockedBy(pid(holder)).query()).as("the draw waits on its line (L3)")
                        .contains("lending.loan");
                assertThat(capitalLockableNoWait()).as("before the line, no capital is held").isTrue();
                holder.rollback();
                assertThat(drawing.get(60, TimeUnit.SECONDS).replayed()).isFalse();
            }
            try (Connection holder = DatabaseRoles.application()) {
                holder.setAutoCommit(false);
                HEADROOM.underLock(holder, EUR);
                Future<CreditLineDraws.Drawn> drawing = pool.submit(() -> draw(line, key(), 100_00));
                assertThat(awaitBlockedBy(pid(holder)).query()).as("the draw waits on the capital row (L6)")
                        .contains("ledger.ledger_account");
                assertThat(lineLockableNoWait(line)).as("holding its line while it waits").isFalse();
                holder.rollback();
                assertThat(drawing.get(60, TimeUnit.SECONDS).replayed()).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(drawn(line)).isEqualTo(eur(200_00));
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    @Test
    @DisplayName("a closing line refuses draws, as does a closed line and an instalment loan - 409 lending.LineNotDrawable,"
            + " nothing written; another party's line is not found and is never locked")
    void aClosingLineRefusesDraws() {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        fund(eur(10_000_00));
        draw(line, key(), 300_00);
        asOwner("UPDATE lending.loan SET status = 'CLOSING' WHERE id = '" + line.loan().value() + "'");
        assertThatExceptionOfType(CreditLineDraws.LineNotDrawable.class).isThrownBy(() -> draw(line, key(), 100_00));
        assertThat(available(line)).as("a closing line shows nothing to draw").isEqualTo(eur(0));
        asOwner("UPDATE lending.loan SET status = 'CLOSED', closed_at = statement_timestamp(),"
                + " closure_reason = 'CLOSED_BY_CUSTOMER' WHERE id = '" + line.loan().value() + "'");
        assertThatExceptionOfType(CreditLineDraws.LineNotDrawable.class).isThrownBy(() -> draw(line, key(), 100_00));
        assertThat(drawn(line)).isEqualTo(eur(300_00));

        DisbursementWorld.Accepted loan = acceptedLoan(1_000_00, 12);
        assertThatExceptionOfType(CreditLineDraws.LineNotDrawable.class).isThrownBy(() -> draw(loan, key(), 100_00));
        assertThat(count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ?", loan.loan().value())).isZero();

        DisbursementWorld.Accepted mine = acceptedLine(1_000_00);
        DisbursementWorld.Accepted theirs = acceptedLine(1_000_00);
        assertThatExceptionOfType(CreditLineDraws.LineNotFound.class).isThrownBy(() -> inOneTransaction(uow ->
                drawAs(uow, DRAWS, mine.party(), theirs.loan(), key(), 100_00)));
        assertThat(drawn(theirs)).isEqualTo(eur(0));
    }

    @Test
    @DisplayName("a lost standing refuses the draw in its transaction (credit's own answer), nothing written; restored, the"
            + " same key draws")
    void standingLostRefuses() {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        fund(eur(10_000_00));
        String key = key();
        STANDING.put(line.party(), false);
        try {
            assertThatExceptionOfType(LoanApplications.NotInGoodStanding.class).isThrownBy(() -> draw(line, key, 100_00));
        } finally {
            STANDING.remove(line.party());
        }
        assertThat(entries(line)).isZero();
        assertThat(count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ?", line.loan().value())).isZero();
        assertThat(draw(line, key, 100_00).replayed()).as("the refusal spent nothing - the key draws").isFalse();
    }

    @Test
    @DisplayName("a suspended line refuses the draw 409 lending.DrawsSuspended under its lock, nothing written - the check"
            + " -024's condition read brings into force (until then nothing is past due or defaulted)")
    void aSuspendedLineRefusesDraws() {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        fund(eur(10_000_00));
        CreditLineDraws suspended = draws((unitOfWork, account, businessDate) -> account.equals(line.loan()));
        assertThatExceptionOfType(CreditLineDraws.DrawsSuspended.class).isThrownBy(() -> inOneTransaction(uow ->
                drawAs(uow, suspended, line.party(), line.loan(), key(), 100_00)));
        assertThat(entries(line)).isZero();
        assertThat(DrawSuspension.BEFORE_ANY_CONDITION.suspended(null, line.loan(), LocalDate.now(CLOCK)))
                .as("before statements and the condition step, no line is suspended").isFalse();
        assertThat(draw(line, key(), 100_00).replayed()).isFalse();
    }

    @Test
    @DisplayName("a wallet that cannot be had refuses the draw 409 lending.WalletUnavailable, nothing written - capital"
            + " and limit untouched; reachable again, it draws")
    void anUnreachableWalletRefuses() {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        fund(eur(10_000_00));
        Money headroomBefore = headroomNow().headroom();
        UNREACHABLE_WALLETS.add(line.party());
        try {
            assertThatExceptionOfType(CreditLineDraws.WalletUnavailable.class).isThrownBy(() -> draw(line, key(), 100_00));
        } finally {
            UNREACHABLE_WALLETS.remove(line.party());
        }
        assertThat(entries(line)).isZero();
        assertThat(headroomNow().headroom()).isEqualTo(headroomBefore);
        assertThat(draw(line, key(), 100_00).replayed()).isFalse();
    }

    // ------------------------------------------------------------------ the money

    @Test
    @DisplayName("a draw is posting rule 1, its row and its event in one transaction: DR LOAN_PRINCIPAL / CR the wallet,"
            + " key lending.draw:<id>, dated the business date, the reference the line; the row names the entry and the"
            + " agreement version; capital headroom falls by the amount; the event carries the entry and no wallet")
    void aDrawIsRuleOneItsRowAndItsEvent() {
        DisbursementWorld.Accepted line = acceptedLine(3_000_00);
        fund(eur(10_000_00));
        Money headroomBefore = headroomNow().headroom();
        CreditLineDraw draw = draw(line, key(), 1_250_75).draw();
        String entry = draw.journalEntry().value().toString();
        assertThat(count("SELECT count(*) FROM ledger.journal_line WHERE entry_id = ?::uuid", entry)).isEqualTo(2);
        assertThat(scalar("SELECT sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END)::text"
                + " FROM ledger.journal_line WHERE entry_id = ?::uuid", entry)).as("INV-LED-01").isEqualTo("0");
        assertThat(scalar("SELECT idempotency_scope FROM ledger.journal_entry WHERE id = ?::uuid", entry))
                .isEqualTo("ledger.post:lending.draw:" + draw.id());
        assertThat(scalar("SELECT reference FROM ledger.journal_entry WHERE id = ?::uuid", entry))
                .isEqualTo(line.loan().value().toString());
        assertThat(scalar("SELECT posting_date::text || '|' || value_date::text FROM ledger.journal_entry"
                + " WHERE id = ?::uuid", entry)).isEqualTo(draw.valueDate() + "|" + draw.valueDate());
        assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL))).isEqualTo(eur(1_250_75));
        assertThat(netDebit(walletOf(line.party()))).as("the wallet credited").isEqualTo(eur(-1_250_75));
        assertThat(scalar("SELECT journal_entry_id::text || '|' || agreement_version || '|' || amount_minor || '|'"
                + " || value_date FROM lending.credit_line_draw WHERE id = ?", draw.id()))
                .isEqualTo(entry + "|1|125075|" + draw.valueDate());
        assertThat(headroomNow().headroom()).as("A28: capital is the money deployed")
                .isEqualTo(headroomBefore.minus(eur(1_250_75)));
        String payload = scalar("SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.CreditLineDrawn'", line.loan().value());
        assertThat(payload).contains(entry, draw.id().toString(), "\"amountMinor\":\"125075\"", "\"currency\":\"EUR\"")
                .doesNotContain(walletOf(line.party()).value().toString());
        assertThat(draw.toString()).doesNotContain("1250.75", "125075", draw.drawKey());
        assertThat(count("SELECT count(*) FROM lending.loan_event WHERE loan_id = ?", line.loan().value()))
                .as("the line changes no state: its history holds only its birth").isEqualTo(1);
        assertSubledger(line);
    }

    @Test
    @DisplayName("the draw, its entry, its row and its event are one transaction: an instance dying after the posting, the"
            + " row and the event row leaves none of them - the wallet, the limit and the capital unmoved - and the same"
            + " key, re-driven, draws once")
    void aCrashAfterThePostingLeavesNothing() {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        fund(eur(10_000_00));
        Money headroomBefore = headroomNow().headroom();
        OutboxWriter<Connection> crashing = (unitOfWork, envelope, payload, mediaType) -> {
            new JdbcOutboxWriter().write(unitOfWork, envelope, payload, mediaType);
            if (envelope.eventType().equals(CreditLineDraws.DRAWN_EVENT)) {
                throw new IllegalStateException("the instance died after the posting, the row and the event row");
            }
        };
        CreditLineDraws dying = draws(DrawSuspension.BEFORE_ANY_CONDITION, crashing);
        String key = key();
        assertThatThrownBy(() -> inOneTransaction(uow -> drawAs(uow, dying, line.party(), line.loan(), key, 400_00)))
                .hasMessageContaining("instance died");
        assertThat(entries(line)).as("no posting without its row and event").isZero();
        assertThat(count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ?", line.loan().value())).isZero();
        assertThat(events(line)).isZero();
        assertThat(netDebit(walletOf(line.party()))).isEqualTo(eur(0));
        assertThat(available(line)).isEqualTo(eur(1_000_00));
        assertThat(headroomNow().headroom()).isEqualTo(headroomBefore);
        assertThat(draw(line, key, 400_00).replayed()).as("re-driven under the same key: drawn, once").isFalse();
        assertDrawnOnce(line, key, eur(400_00));
    }

    @Test
    @DisplayName("a draw accrues from its value date (-016's line accrual over real draws): two draws, moved back in time"
            + " as a fixture, accrue from each one's date on the drawn principal - nothing before the first")
    void aDrawAccruesFromItsValueDate() {
        DisbursementWorld.Accepted line = acceptedLine(10_000_00);
        fund(eur(10_000_00));
        CreditLineDraw first = draw(line, key(), 1_000_00).draw();
        CreditLineDraw second = draw(line, key(), 500_00).draw();
        LocalDate today = first.valueDate();
        InterestAccrualDatabaseTest.backdateOpening(line.loan(), 40);
        moveBack(first, 38);
        moveBack(second, 10);
        LoanServicing.Outcome step = InterestAccrualDatabaseTest.service(line.loan());
        assertThat(step.posted() + step.zero()).as("from day -38 to yesterday").isEqualTo(38);
        UUID id = line.loan().value();
        assertThat(scalar("SELECT min(accrual_date)::text FROM lending.interest_accrual WHERE loan_id = ?", id))
                .as("nothing accrues before the first draw's value date").isEqualTo(today.minusDays(38).toString());
        assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ? AND base_minor = 100000", id))
                .isEqualTo(28);
        assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ? AND base_minor = 150000"
                + " AND accrual_date >= ?", id, today.minusDays(10))).as("the second draw from its own date").isEqualTo(10);
        // The accrued account is the rows' postings less what the line's statements made due (P11-TSK-022: the step
        // states every cycle whose statement date has come; billed principal is still drawn).
        assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_INTEREST_ACCRUED)).minorUnits())
                .isEqualTo(Long.parseLong(scalar("SELECT (sum(posted_minor) - (SELECT coalesce(sum(interest_billed_minor), 0)"
                        + " FROM lending.credit_line_statement WHERE loan_id = ?))::text FROM lending.interest_accrual"
                        + " WHERE loan_id = ?", id, id)));
        assertThat(available(line)).as("accrued interest consumes nothing").isEqualTo(eur(8_500_00));
    }

    // ------------------------------------------------------------------ every writer

    @Test
    @DisplayName("the database holds every writer: a draw only on an ACTIVE line, under its governing agreement version,"
            + " in its currency and scale, never above its limit, dated this transaction's business date; one per key"
            + " and per entry; never updated, deleted or truncated")
    void theDatabaseHoldsEveryWriter() throws Exception {
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        DisbursementWorld.Accepted loan = acceptedLoan(1_000_00, 12);
        String agreement = scalar("SELECT id::text FROM lending.loan_agreement WHERE loan_id = ?", line.loan().value());
        String loanAgreement = scalar("SELECT id::text FROM lending.loan_agreement WHERE loan_id = ?", loan.loan().value());
        String today = scalar("SELECT lending.business_date(statement_timestamp(), 'UTC')::text");
        String key = key();
        String entry = UUID.randomUUID().toString();
        assertThat(insert(line.loan(), key, 500_00, agreement, 1, entry, today)).isNull();
        assertThat(insert(line.loan(), key, 100_00, agreement, 1, UUID.randomUUID().toString(), today))
                .as("one per key").isEqualTo("23505");
        assertThat(insert(line.loan(), key(), 100_00, agreement, 1, entry, today)).as("one per entry").isEqualTo("23505");
        assertThat(insert(line.loan(), key(), 1_000_01, agreement, 1, UUID.randomUUID().toString(), today))
                .as("never above the limit").isNotNull();
        assertThat(insert(line.loan(), key(), 100_00, agreement, 2, UUID.randomUUID().toString(), today))
                .as("its governing version").isNotNull();
        assertThat(insert(line.loan(), key(), 100_00, agreement, 1, UUID.randomUUID().toString(),
                LocalDate.parse(today).minusDays(1).toString())).as("dated today").isNotNull();
        assertThat(insert(loan.loan(), key(), 100_00, loanAgreement, 1, UUID.randomUUID().toString(), today))
                .as("never an instalment loan").isNotNull();
        assertThat(insert(line.loan(), key(), 0, agreement, 1, UUID.randomUUID().toString(), today))
                .as("a positive amount").isEqualTo("23514");
        asOwner("UPDATE lending.loan SET status = 'CLOSING' WHERE id = '" + line.loan().value() + "'");
        assertThat(insert(line.loan(), key(), 100_00, agreement, 1, UUID.randomUUID().toString(), today))
                .as("never a closing line").isNotNull();

        try (Connection application = DatabaseRoles.application()) {
            assertThatThrownBy(() -> execute(application, "UPDATE lending.credit_line_draw SET amount_minor = 1"))
                    .as("no UPDATE grant").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(application, "DELETE FROM lending.credit_line_draw"))
                    .as("no DELETE grant").isInstanceOf(SQLException.class);
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatThrownBy(() -> execute(migrator, "UPDATE lending.credit_line_draw SET amount_minor = 1 WHERE loan_id = '"
                    + line.loan().value() + "'")).as("immutable for every role").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(migrator, "DELETE FROM lending.credit_line_draw WHERE loan_id = '"
                    + line.loan().value() + "'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(migrator, "TRUNCATE lending.credit_line_draw"))
                    .isInstanceOf(SQLException.class);
        }
        assertThat(count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ?", line.loan().value()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ the world's door

    static CreditLineDraws draws(DrawSuspension suspension) {
        return draws(suspension, new JdbcOutboxWriter());
    }

    static CreditLineDraws draws(DrawSuspension suspension, OutboxWriter<Connection> outbox) {
        return new CreditLineDraws(LOAN_STORE, DRAW_STORE, TERMS_STORE, suspension, STANDING_PORT, HEADROOM, WALLETS,
                LEDGER_ACCOUNTS, BALANCES, POSTINGS, outbox, IDS, CLOCK);
    }

    /** A draw in its own transaction under the line's party - the door's transaction, its claim aside. */
    static CreditLineDraws.Drawn draw(DisbursementWorld.Accepted line, String key, long minor) {
        return inOneTransaction(uow -> drawAs(uow, DRAWS, line.party(), line.loan(), key, minor));
    }

    static CreditLineDraws.Drawn drawAs(
            Connection uow, CreditLineDraws draws, UUID party, LoanId line, String key, long minor) {
        Actor actor = customer(party);
        CorrelationId correlation = correlation();
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation).causing(CausationId.of(correlation.value())))) {
            return draws.draw(uow, line, party, new CreditLineDraws.Request(key, eur(minor)), actor, correlation);
        }
    }

    /** The draw behind the door's claim, as {@code app}'s desk runs it: one transaction, the claim first. */
    private static IdempotentExecutor.ExecutionOutcome throughTheClaim(
            DisbursementWorld.Accepted line, String key, long minor) {
        IdempotencyKey claim = new IdempotencyKey("lending.draw:CUSTOMER:" + line.party(), key);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("lending.draw|" + line.loan().value() + "|" + minor)
                .getBytes(StandardCharsets.UTF_8));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                Correlation.startingWith(correlation()).causing(CausationId.of("p11t21")))) {
            return inOneTransaction(uow -> DOOR_CLAIM.execute(uow, claim, fingerprint, claimed -> CommandResult.succeeded(
                    StoredResponse.of(drawAs(claimed, DRAWS, line.party(), line.loan(), key, minor).draw().id().toString()
                            .getBytes(StandardCharsets.UTF_8), "text/plain"))));
        }
    }

    private static String outcome(java.util.function.Supplier<CreditLineDraws.Drawn> attempt) {
        try {
            attempt.get();
            return "DRAWN";
        } catch (CreditLineDraws.LimitExceeded exceeded) {
            return "LIMIT_EXCEEDED";
        }
    }

    // ------------------------------------------------------------------ acceptances for the capital race

    record Offered(LoanOfferId offer, UUID party, String hash) {}

    /** A fresh party's personal loan offered by the real origination step, not yet accepted. */
    static Offered offeredLoan(long minor) {
        ensureActive();
        LoanApplicationId application = LoanApplicationId.next(IDS);
        UUID party = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        inOneTransaction(uow -> {
            LoanTermsStore.LoanTermsVersion version = ADMINISTRATION.pinActive(uow, LoanProduct.PERSONAL_LOAN);
            Actor customer = customer(party);
            CorrelationId correlation = correlation();
            APPLICATIONS.insert(uow, new LoanApplicationStore.NewApplication(application, party,
                    LoanProduct.PERSONAL_LOAN, request, version.row().id(), 10, RepaymentMethod.WALLET,
                    PayoutPreference.WALLET, customer, correlation));
            APPLICATIONS.appendEvent(uow, new LoanApplicationStore.HistoryRow(IDS.next(), application,
                    Optional.empty(), LoanApplicationStatus.SUBMITTED, "APPLICATION_SUBMITTED", customer, correlation));
            APPLICATIONS.transition(uow, application, LoanApplicationStatus.SUBMITTED,
                    LoanApplicationStatus.AWAITING_DECISION);
            APPLICATIONS.appendEvent(uow, new LoanApplicationStore.HistoryRow(IDS.next(), application,
                    Optional.of(LoanApplicationStatus.SUBMITTED), LoanApplicationStatus.AWAITING_DECISION,
                    "CREDIT_REQUEST_OPENED", customer, correlation));
            return null;
        });
        CREDIT.approve(request, party, eur(minor), Optional.of(12), databaseNow().plus(Duration.ofDays(30)));
        assertThat(inOneTransaction(uow -> ORIGINATION.originate(uow, application, SYSTEM)).kind())
                .isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        LoanOfferId offer = LoanOfferId.of(UUID.fromString(scalar(
                "SELECT id::text FROM lending.loan_offer WHERE application_id = ?", application.value())));
        return new Offered(offer, party, scalar(
                "SELECT encode(terms_sha256, 'hex') FROM lending.loan_offer_terms WHERE offer_id = ?", offer.value()));
    }

    static void accept(Offered offered) {
        inOneTransaction(uow -> ACCEPTANCES.accept(uow, offered.offer(), offered.party(),
                new LoanAcceptances.Acceptance(offered.hash(), Optional.empty(), new AcceptanceEvidence(UUID.randomUUID(),
                        UUID.randomUUID(), "MULTI_FACTOR", Optional.empty(), Optional.empty())),
                customer(offered.party()), correlation()));
    }

    // ------------------------------------------------------------------ the figures

    /** The line's drawn principal - LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE - from its journal lines. */
    private static Money drawn(DisbursementWorld.Accepted line) {
        return netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL))
                .plus(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL_DUE)));
    }

    /** The available limit as the customer's read derives it. */
    private static Money available(DisbursementWorld.Accepted line) {
        return inOneTransaction(uow -> new Loans(LOAN_STORE, LEDGER_ACCOUNTS, BALANCES, PAYOUT_STORE)
                .read(uow, line.loan(), line.party())).orElseThrow().availableLimit().orElseThrow();
    }

    private static LendingCapitalHeadroom.Headroom headroomNow() {
        return inOneTransaction(uow -> HEADROOM.atRest(uow, EUR));
    }

    private static void plantCapital(Money amount) {
        post("p11t21-capital:" + UUID.randomUUID(), List.of(
                new JournalLine(cashAtBank(), Direction.DEBIT, amount),
                new JournalLine(inOneTransaction(uow -> HEADROOM.capitalAccount(uow, EUR).id()), Direction.CREDIT,
                        amount)));
    }

    /** Every draw entry the ledger holds for the line - under the draw key's prefix, its reference the line. */
    private static long entries(DisbursementWorld.Accepted line) {
        return count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?"
                + " AND idempotency_scope LIKE 'ledger.post:lending.draw:%'", line.loan().value().toString());
    }

    private static long events(DisbursementWorld.Accepted line) {
        return count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.CreditLineDrawn'", line.loan().value());
    }

    /** One row, one entry under its key naming it, one event, the drawn principal the amount. */
    private static void assertDrawnOnce(DisbursementWorld.Accepted line, String key, Money amount) {
        UUID id = line.loan().value();
        assertThat(count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ? AND draw_key = ?", id, key))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ?", id)).isEqualTo(1);
        assertThat(entries(line)).as("one entry, however often attempted").isEqualTo(1);
        assertThat(events(line)).isEqualTo(1);
        assertThat(drawn(line)).isEqualTo(amount);
        assertThat(netDebit(walletOf(line.party()))).as("the wallet credited once").isEqualTo(amount.negated());
    }

    /** {@code LOAN_PRINCIPAL}'s debits are exactly the line's draws (the subledger proof's draw arm, `-029`). */
    private static void assertSubledger(DisbursementWorld.Accepted line) {
        assertThat(scalar("SELECT coalesce(sum(amount_minor), 0)::text FROM ledger.journal_line"
                + " WHERE ledger_account_id = ?::uuid AND direction = 'DEBIT'",
                loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL).value().toString()))
                .isEqualTo(scalar("SELECT coalesce(sum(amount_minor), 0)::text FROM lending.credit_line_draw"
                        + " WHERE loan_id = ?", line.loan().value()));
    }

    // ------------------------------------------------------------------ plumbing

    /** The draw's entry and row moved {@code days} back - the fixture's clock moving, never a path. */
    private static void moveBack(CreditLineDraw draw, int days) {
        asOwner("UPDATE ledger.journal_entry SET posting_date = posting_date - " + days + ", value_date = value_date - "
                + days + " WHERE id = '" + draw.journalEntry().value() + "';"
                + " UPDATE lending.credit_line_draw SET value_date = value_date - " + days + " WHERE id = '" + draw.id()
                + "'");
    }

    /** A raw insert as the application role; the SQLSTATE it failed with, or null. */
    private static String insert(
            LoanId loan, String key, long minor, String agreement, int version, String entry, String valueDate)
            throws SQLException {
        try (Connection application = DatabaseRoles.application();
                PreparedStatement insert = application.prepareStatement(
                        "INSERT INTO lending.credit_line_draw (id, loan_id, draw_key, currency, scale, amount_minor,"
                                + " agreement_id, agreement_version, journal_entry_id, value_date, created_at,"
                                + " correlation_id) VALUES (?, ?, ?, 'EUR', 2, ?, ?::uuid, ?, ?::uuid, ?::date,"
                                + " statement_timestamp(), 'p11t21')")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, loan.value());
            insert.setString(3, key);
            insert.setLong(4, minor);
            insert.setString(5, agreement);
            insert.setInt(6, version);
            insert.setString(7, entry);
            insert.setString(8, valueDate);
            insert.executeUpdate();
            return null;
        } catch (SQLException refused) {
            return refused.getSQLState();
        }
    }

    private static boolean lineLockableNoWait(DisbursementWorld.Accepted line) throws SQLException {
        return lockableNoWait("SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE NOWAIT", line.loan().value());
    }

    private static boolean capitalLockableNoWait() throws SQLException {
        LedgerAccountId capital = inOneTransaction(uow -> HEADROOM.capitalAccount(uow, EUR).id());
        return lockableNoWait("SELECT 1 FROM ledger.ledger_account WHERE id = ? FOR UPDATE NOWAIT", capital.value());
    }

    private static boolean lockableNoWait(String sql, UUID id) throws SQLException {
        try (Connection probe = DatabaseRoles.application()) {
            probe.setAutoCommit(false);
            try (PreparedStatement lock = probe.prepareStatement(sql)) {
                lock.setObject(1, id);
                lock.executeQuery().close();
                return true;
            } catch (SQLException busy) {
                if ("55P03".equals(busy.getSQLState())) {
                    return false;
                }
                throw busy;
            } finally {
                probe.rollback();
            }
        }
    }

    private static void lock(Connection holder, String sql, UUID id) throws SQLException {
        try (PreparedStatement lock = holder.prepareStatement(sql)) {
            lock.setObject(1, id);
            try (ResultSet row = lock.executeQuery()) {
                assertThat(row.next()).isTrue();
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static JournalLine line(LedgerAccountId account, Direction direction, long minor) {
        return new JournalLine(account, direction, eur(minor));
    }

    private static String key() {
        return "draw-" + UUID.randomUUID();
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
