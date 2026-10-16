package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.BALANCES;
import static com.finapp.lending.DisbursementWorld.EUR;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.LOAN_STORE;
import static com.finapp.lending.DisbursementWorld.PAYOUT_STORE;
import static com.finapp.lending.DisbursementWorld.STATEMENTS;
import static com.finapp.lending.DisbursementWorld.STATEMENT_STORE;
import static com.finapp.lending.DisbursementWorld.acceptedLineOnDay;
import static com.finapp.lending.DisbursementWorld.activate;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.awaitBlockedBy;
import static com.finapp.lending.DisbursementWorld.cashAtBank;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.fund;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.pid;
import static com.finapp.lending.DisbursementWorld.post;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.DisbursementWorld.walletOf;
import static com.finapp.lending.InterestAccrualDatabaseTest.disbursed;
import static com.finapp.lending.InterestAccrualDatabaseTest.eur;
import static com.finapp.lending.InterestAccrualDatabaseTest.refused;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.RepaymentDatabaseTest.events;
import static com.finapp.lending.RepaymentDatabaseTest.held;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.EngineVersion;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.lending.CreditLineStatementStore.StatedItem;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A credit line's statements and its minimum payment against a real database (`P11-TSK-022`; {@code lending V017},
 * {@code PHASE_11_PLAN.md} sections 7.1 T-svc, 12.6 and 12.8 rules 3, 5 and 6; ADR-0100 point 6; A23, A24;
 * {@code INV-LND-01}, {@code INV-LND-04}, {@code INV-LND-09}, {@code INV-LND-10}, {@code INV-IDEM-02}): every figure read from
 * the rows and the ledger, every case closing on the line's subledger arms ({@link #assertLineSubledgerProves}).
 *
 * <p>Lines are accepted by the real origination and acceptance under credit line terms this suite activates ({@link #lines}
 * - every day of the month allowed, the payment-due days chosen, auto-collection as the case needs - the suite's default
 * restored at once, so a line keeps the terms it pinned), drawn through the real {@link CreditLineDraws} to the world's
 * wallet, and serviced by the real T-svc. A line's past is the fixture's clock, never a path: its opening and its draws'
 * value dates moved back by the bootstrap role ({@link #openOn}, {@link #dateDraw}); the statement date is chosen so the
 * database's business date falls where the case needs it.
 */
@Tag("database")
@DisplayName("credit line statements and the minimum payment (P11-TSK-022)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class CreditLineStatementDatabaseTest {

    static final BigDecimal RATE = new BigDecimal("0.1299");

    // ------------------------------------------------------------------ once, at the cycle's end

    @Test
    @DisplayName("produced once at the cycle end: a line whose statement date has come is stated in the step - the cycle"
            + " from its opening, the due date the pinned days after, its versions, one entry under"
            + " lending.statement:<line>:<date>, one event; a second step states nothing; a line whose statement date is"
            + " tomorrow is not stated; a line drawn nothing is stated with nothing due - its row, no entry")
    void producedOnceAtTheCycleEnd() {
        LocalDate statementDate = onOrBefore(today());
        List<DisbursementWorld.Accepted> lines = lines(3, 2_000_00, statementDate.getDayOfMonth(), 25, false);
        DisbursementWorld.Accepted drawn = lines.get(0);
        openOn(drawn, statementDate.minusDays(20));
        dateDraw(draw(drawn, 800_00), statementDate.minusDays(18));
        LoanServicing.Outcome step = service(drawn.loan());
        assertThat(step.stated()).isEqualTo(1);
        StatedItem item = statements(drawn).get(0);
        Statement figures = item.statement().statement();
        assertThat(figures.cycle()).isEqualTo(new AccrualPeriod(statementDate.minusDays(20), statementDate));
        assertThat(figures.dueDate()).isEqualTo(statementDate.plusDays(25));
        assertThat(figures.engine()).isEqualTo(EngineVersion.STATEMENT_ENGINE_V1);
        assertThat(item.statement().agreementVersion()).isEqualTo(1);
        assertThat(item.statement().entry()).isPresent();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:" + CreditLineStatements.POSTING_KEY_PREFIX + drawn.loan().value() + ":" + statementDate))
                .as("one entry under the statement's natural key").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE id = ? AND posting_date = ? AND value_date = ?",
                item.statement().entry().get().value(), today(), today())).as("dated its transaction's business date")
                .isEqualTo(1);
        assertThat(events(drawn.loan(), CreditLineStatements.ISSUED_EVENT)).isEqualTo(1);
        assertThat(service(drawn.loan()).stated()).as("a second step states nothing").isZero();
        assertThat(statements(drawn)).hasSize(1);
        assertThat(events(drawn.loan(), CreditLineStatements.ISSUED_EVENT)).isEqualTo(1);
        assertLineSubledgerProves(drawn, 0);

        // A statement date not yet come: accrued, not stated.
        LocalDate ahead = today().plusDays(1);
        while (ahead.getDayOfMonth() > LoanTerms.LAST_ALLOWED_DAY) {
            ahead = ahead.plusDays(1);
        }
        DisbursementWorld.Accepted early = lines(1, 2_000_00, ahead.getDayOfMonth(), 25, false).get(0);
        openOn(early, ahead.minusDays(20));
        dateDraw(draw(early, 500_00), ahead.minusDays(19));
        LoanServicing.Outcome notYet = service(early.loan());
        assertThat(notYet.posted()).as("its dates accrued").isPositive();
        assertThat(notYet.stated()).as("its statement date not yet come").isZero();
        assertThat(statements(early)).isEmpty();

        // Nothing drawn: a statement with nothing due - the row, no entry, the event.
        DisbursementWorld.Accepted empty = lines.get(1);
        openOn(empty, statementDate.minusDays(20));
        assertThat(service(empty.loan()).stated()).isEqualTo(1);
        StatedItem nothing = statements(empty).get(0);
        assertThat(nothing.statement().statement().minimumPayment()).isEqualTo(eur(0));
        assertThat(nothing.statement().entry()).isEmpty();
        assertThat(nothing.outstanding()).isEqualTo(eur(0));
        assertThat(events(empty.loan(), CreditLineStatements.ISSUED_EVENT)).isEqualTo(1);
        assertLineSubledgerProves(empty, 0);
    }

    @Test
    @DisplayName("ten sweepers, one statement: ten servicing steps racing on each of three lines at the cycle end - L3"
            + " serialises them: one statement, one entry, one event per line; beneath L3, UNIQUE (loan_id, cycle_end)"
            + " refuses a second statement of the cycle written with every trigger suspended")
    void tenSweepersOneStatement() throws Exception {
        LocalDate statementDate = onOrBefore(today());
        List<DisbursementWorld.Accepted> lines = lines(3, 3_000_00, statementDate.getDayOfMonth(), 25, false);
        for (DisbursementWorld.Accepted line : lines) {
            openOn(line, statementDate.minusDays(20));
            dateDraw(draw(line, 1_200_00), statementDate.minusDays(19));
        }
        List<Integer> stated = race(30, index -> service(lines.get(index % 3).loan()).stated());
        assertThat(stated.stream().mapToInt(Integer::intValue).sum()).as("one statement per line").isEqualTo(3);
        for (DisbursementWorld.Accepted line : lines) {
            assertThat(statements(line)).hasSize(1);
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope LIKE ?",
                    "ledger.post:" + CreditLineStatements.POSTING_KEY_PREFIX + line.loan().value() + ":%")).isEqualTo(1);
            assertThat(events(line.loan(), CreditLineStatements.ISSUED_EVENT)).isEqualTo(1);
            assertLineSubledgerProves(line, 0);
        }
        // The unique beneath L3: a second, otherwise well-formed statement of the cycle, every trigger suspended.
        UUID line = lines.get(0).loan().value();
        assertThatThrownBy(() -> asOwner("INSERT INTO lending.credit_line_statement (id, loan_id, cycle_start, cycle_end,"
                + " due_date, currency, scale, interest_billed_minor, fees_carried_minor, principal_billed_minor,"
                + " minimum_payment_minor, agreement_id, agreement_version, statement_engine_version, accrual_engine_version,"
                + " journal_entry_id, created_at, correlation_id) SELECT gen_random_uuid(), loan_id, cycle_start, cycle_end,"
                + " due_date + 1, currency, scale, 0, 0, 0, 0, agreement_id, agreement_version, statement_engine_version,"
                + " accrual_engine_version, NULL, created_at, correlation_id FROM lending.credit_line_statement"
                + " WHERE loan_id = '" + line + "'"))
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().hasMessageContaining("credit_line_statement_cycle_unique");
        assertThat(count("SELECT count(*) FROM lending.credit_line_statement WHERE loan_id = ?", line)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ what it bills

    @Test
    @DisplayName("the statement bills the cycle's accrued interest: exactly the sum of the cycle's accrual rows - which is the"
            + " drawn principal at the rate over the drawn dates, rounded once - moved from accrued to due (rule 3), the"
            + " accrued account left with nothing of the cycle")
    void theStatementBillsTheCyclesAccruedInterest() {
        LocalDate statementDate = onOrBefore(today());
        DisbursementWorld.Accepted line = lines(1, 5_000_00, statementDate.getDayOfMonth(), 25, false).get(0);
        openOn(line, statementDate.minusDays(25));
        dateDraw(draw(line, 3_000_00), statementDate.minusDays(22));
        service(line.loan());
        Statement figures = statements(line).get(0).statement().statement();
        long rows = count("SELECT coalesce(sum(posted_minor), 0) FROM lending.interest_accrual WHERE loan_id = ?"
                + " AND period_start = ? AND period_end = ?", line.loan().value(), figures.cycle().start(), statementDate);
        assertThat(figures.interestBilled().minorUnits()).as("the cycle's accrual rows").isEqualTo(rows);
        long independent = new BigDecimal("3000.00").multiply(RATE).multiply(BigDecimal.valueOf(22))
                .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_EVEN).movePointRight(2).longValueExact();
        assertThat(figures.interestBilled().minorUnits()).as("22 drawn dates at 12.99 %, rounded once").isEqualTo(independent);
        assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_INTEREST_DUE)).minorUnits()).isEqualTo(independent);
        assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_INTEREST_ACCRUED)).minorUnits())
                .as("accrual through the day before the statement date: nothing of the cycle left accrued").isZero();
        assertLineSubledgerProves(line, 0);
    }

    @Test
    @DisplayName("the minimum payment follows the rule (A24): principal = min(drawn not yet due, max(round_UP(3 % x drawn),"
            + " 25.00 - interest - fees, 0)) on the drawn principal at the cycle's close - a small balance pays the floor,"
            + " a large one 3 %, a balance below the floor all of it - minimum = interest + fees + principal, never the"
            + " limit's 3 %")
    void theMinimumPaymentFollowsTheRule() {
        LocalDate statementDate = onOrBefore(today());
        long[] drawnMinor = {400_00, 7_000_00, 10_00};
        List<DisbursementWorld.Accepted> lines = lines(3, 9_000_00, statementDate.getDayOfMonth(), 25, false);
        for (int i = 0; i < 3; i++) {
            DisbursementWorld.Accepted line = lines.get(i);
            openOn(line, statementDate.minusDays(20));
            dateDraw(draw(line, drawnMinor[i]), statementDate.minusDays(15));
            service(line.loan());
            Statement figures = statements(line).get(0).statement().statement();
            long interest = figures.interestBilled().minorUnits();
            long ratio = BigDecimal.valueOf(drawnMinor[i]).multiply(new BigDecimal("0.03"))
                    .setScale(0, RoundingMode.UP).longValueExact();
            long principal = Math.min(drawnMinor[i], Math.max(Math.max(ratio, 25_00 - interest), 0));
            assertThat(figures.principalBilled().minorUnits()).as("the principal part of " + drawnMinor[i])
                    .isEqualTo(principal);
            assertThat(figures.minimumPayment().minorUnits()).isEqualTo(interest + principal);
            assertThat(figures.feesCarried()).isEqualTo(eur(0));
            assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL_DUE)).minorUnits())
                    .isEqualTo(principal);
            assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL)).minorUnits())
                    .isEqualTo(drawnMinor[i] - principal);
            assertThat(available(line)).as("billed principal is still drawn - the limit unchanged")
                    .isEqualTo(eur(9_000_00 - drawnMinor[i]));
            assertLineSubledgerProves(line, 0);
        }
        // The three regimes, as the figures show them.
        assertThat(statements(lines.get(0)).get(0).statement().statement().minimumPayment()).as("the floor")
                .isEqualTo(eur(25_00));
        assertThat(statements(lines.get(1)).get(0).statement().statement().principalBilled()).as("3 % of 7,000.00")
                .isEqualTo(eur(210_00));
        assertThat(statements(lines.get(2)).get(0).statement().statement().principalBilled()).as("all of a balance below")
                .isEqualTo(eur(10_00));
    }

    // ------------------------------------------------------------------ paid

    @Test
    @DisplayName("the minimum payment is auto-collected on its due date: a statement due today is collected from the wallet"
            + " in the step - one COLLECTED attempt, one AUTO_COLLECTION repayment of exactly the minimum, the due accounts"
            + " zero, the limit restored by the principal paid; a statement due tomorrow is not attempted; ten racing steps"
            + " collect once")
    void theMinimumPaymentIsAutoCollectedOnItsDueDate() throws Exception {
        int dueDays = dueDaysLandingToday();
        LocalDate statementDate = today().minusDays(dueDays);
        int day = statementDate.getDayOfMonth();
        List<DisbursementWorld.Accepted> dueToday = lines(2, 4_000_00, day, dueDays, true);
        DisbursementWorld.Accepted tomorrow = lines(1, 4_000_00, day, dueDays + 1, true).get(0);
        for (DisbursementWorld.Accepted line : List.of(dueToday.get(0), dueToday.get(1), tomorrow)) {
            openOn(line, statementDate.minusDays(20));
            dateDraw(draw(line, 1_500_00), statementDate.minusDays(19));
        }
        LoanServicing.Outcome step = service(dueToday.get(0).loan());
        assertThat(step.stated()).isEqualTo(1);
        assertThat(step.collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
        assertCollectedOnce(dueToday.get(0));

        List<LoanServicing.Outcome> racing = race(10, index -> service(dueToday.get(1).loan()));
        assertThat(racing.stream().mapToInt(outcome -> outcome.collected(CollectionOutcome.COLLECTED)).sum())
                .as("ten racing steps collect once").isEqualTo(1);
        assertCollectedOnce(dueToday.get(1));

        LoanServicing.Outcome notDue = service(tomorrow.loan());
        assertThat(notDue.stated()).isEqualTo(1);
        assertThat(notDue.collections()).as("due tomorrow - no attempt").isEmpty();
        assertThat(statements(tomorrow).get(0).outstanding()).isEqualTo(statements(tomorrow).get(0).statement()
                .statement().minimumPayment());
        assertLineSubledgerProves(tomorrow, 0);
    }

    @Test
    @DisplayName("a credit balance pays the statement (rule 6): money held on the line is applied at the statement, once,"
            + " under lending.credit-application:<statement> - a smaller balance pays part of the minimum, a larger one all"
            + " of it with the rest still held, never paying down principal not yet due")
    void aCreditBalancePaysTheStatement() {
        LocalDate statementDate = onOrBefore(today());
        List<DisbursementWorld.Accepted> lines = lines(2, 4_000_00, statementDate.getDayOfMonth(), 25, false);
        long[] creditMinor = {10_00, 300_00};
        for (int i = 0; i < 2; i++) {
            DisbursementWorld.Accepted line = lines.get(i);
            openOn(line, statementDate.minusDays(20));
            dateDraw(draw(line, 2_000_00), statementDate.minusDays(19));
            // An owner-role journal fixture standing in for P11-TSK-023's excess over every receivable, held.
            post("p11t22-credit:" + UUID.randomUUID(), List.of(
                    new JournalLine(cashAtBank(), Direction.DEBIT, eur(creditMinor[i])),
                    new JournalLine(loanAccount(line.loan(), AccountPurpose.LOAN_CREDIT_BALANCE), Direction.CREDIT,
                            eur(creditMinor[i]))));
            LoanServicing.Outcome step = service(line.loan());
            assertThat(step.stated()).isEqualTo(1);
            assertThat(step.statedApplied()).isEqualTo(1);
            StatedItem item = statements(line).get(0);
            long minimum = item.statement().statement().minimumPayment().minorUnits();
            long applied = Math.min(minimum, creditMinor[i]);
            assertThat(item.paid().minorUnits()).isEqualTo(applied);
            assertThat(item.outstanding().minorUnits()).isEqualTo(minimum - applied);
            assertThat(held(line.loan())).as("what the minimum did not take stays held").isEqualTo(creditMinor[i] - applied);
            assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL)).minorUnits())
                    .as("never a paydown of principal not yet due")
                    .isEqualTo(2_000_00 - item.statement().statement().principalBilled().minorUnits());
            assertThat(count("SELECT count(*) FROM lending.credit_balance_application WHERE billing_id = ?",
                    item.statement().id())).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                    "ledger.post:" + CreditBalanceApplications.KEY_PREFIX + item.statement().id())).isEqualTo(1);
            assertThat(scalar("SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event WHERE aggregate_id = ?"
                    + " AND event_type = ?", line.loan().value(), CreditLineStatements.ISSUED_EVENT))
                    .contains("\"creditAppliedMinor\":\"" + applied + "\"");
            assertThat(service(line.loan()).statedApplied()).as("never applied twice").isZero();
            assertLineSubledgerProves(line, creditMinor[i]);
        }
    }

    // ------------------------------------------------------------------ completeness

    @Test
    @DisplayName("the statement waits for every accrual of the cycle: a catch-up stopped at its bound states only the cycles"
            + " its accruals reach, the next step the rest - oldest first, contiguous, each billing exactly its cycle's"
            + " rows; once a cycle is stated, no date inside it ever accrues (raw SQL refused)")
    void statementWaitsForEveryAccrualOfTheCycle() throws Exception {
        LocalDate statementDate = onOrBefore(today());
        List<DisbursementWorld.Accepted> lines = lines(2, 3_000_00, statementDate.getDayOfMonth(), 25, false);
        DisbursementWorld.Accepted line = lines.get(0);
        LocalDate opened = statementDate.minusDays(100);
        openOn(line, opened);
        dateDraw(draw(line, 1_000_00), opened);
        LoanServicing.Outcome first = service(line.loan());
        assertThat(first.caughtUp()).as("the catch-up stops at its bound").isFalse();
        LocalDate accruedThrough = opened.plusDays(LoanServicing.MAX_ACCRUALS_PER_STEP - 1);
        List<StatedItem> afterFirst = statements(line);
        assertThat(afterFirst).isNotEmpty();
        assertThat(afterFirst).allSatisfy(item -> assertThat(item.statement().statementDate())
                .as("only cycles its accruals reach").isBeforeOrEqualTo(accruedThrough.plusDays(1)));
        LoanServicing.Outcome second = service(line.loan());
        assertThat(second.caughtUp()).isTrue();
        List<StatedItem> all = statements(line);
        assertThat(all.size()).as("the rest in the next step").isGreaterThan(afterFirst.size());
        assertThat(all.get(all.size() - 1).statement().statementDate()).isEqualTo(statementDate);
        LocalDate start = opened;
        for (StatedItem item : all) {
            Statement figures = item.statement().statement();
            assertThat(figures.cycle().start()).as("contiguous from the opening").isEqualTo(start);
            assertThat(figures.statementDate().getDayOfMonth()).isEqualTo(statementDate.getDayOfMonth());
            assertThat(figures.interestBilled().minorUnits()).isEqualTo(count("SELECT coalesce(sum(posted_minor), 0)"
                    + " FROM lending.interest_accrual WHERE loan_id = ? AND period_start = ? AND period_end = ?",
                    line.loan().value(), figures.cycle().start(), figures.statementDate()));
            start = figures.statementDate();
        }
        assertLineSubledgerProves(line, 0);

        // A line drawn nothing accrues no row; its stated cycle takes none afterwards, for every writer.
        DisbursementWorld.Accepted idle = lines.get(1);
        openOn(idle, statementDate.minusDays(20));
        service(idle.loan());
        assertThat(statements(idle)).hasSize(1);
        try (Connection app = DatabaseRoles.application()) {
            refused(app, InterestAccrualDatabaseTest.rawAccrual(idle.loan(), "DATE '" + statementDate.minusDays(5) + "'"),
                    "P0001", "cycle already stated");
        }
    }

    // ------------------------------------------------------------------ the read

    @Test
    @DisplayName("balances are derived, not stored: the statement row holds only what it billed (no balance column); the"
            + " read's opening and closing balances are every receivable less the credit balance over the journal lines"
            + " valued before each date - the next statement opening where the last closed; owner-scoped - another party's"
            + " line and an instalment loan read nothing")
    void balancesAreDerivedNotStored() {
        assertThat(columns()).containsExactly("id", "loan_id", "cycle_start", "cycle_end", "due_date", "currency", "scale",
                "interest_billed_minor", "fees_carried_minor", "principal_billed_minor", "minimum_payment_minor",
                "agreement_id", "agreement_version", "statement_engine_version", "accrual_engine_version",
                "journal_entry_id", "created_at", "correlation_id",
                // P11-TSK-029 (lending V025): the statement's place in its loan's history - an order, never a balance.
                "recorded_order");
        LocalDate statementDate = onOrBefore(today());
        DisbursementWorld.Accepted line = lines(1, 3_000_00, statementDate.getDayOfMonth(), 25, false).get(0);
        LocalDate opened = statementDate.minusMonths(1).minusDays(10);
        openOn(line, opened);
        dateDraw(draw(line, 1_000_00), opened.plusDays(2));
        while (!service(line.loan()).caughtUp()) {
            // Catching up.
        }
        List<CreditLineStatements.StatementRead> reads =
                inOneTransaction(uow -> STATEMENTS.read(uow, line.loan(), line.party())).orElseThrow();
        assertThat(reads).hasSize(2);
        assertThat(reads.get(0).openingBalance()).as("nothing owed before the opening").isEqualTo(eur(0));
        for (CreditLineStatements.StatementRead read : reads) {
            Statement figures = read.item().statement().statement();
            assertThat(read.closingBalance()).as("drawn plus interest accrued before " + figures.statementDate())
                    .isEqualTo(eur(1_000_00 + count("SELECT coalesce(sum(posted_minor), 0) FROM lending.interest_accrual"
                            + " WHERE loan_id = ? AND accrual_date < ?", line.loan().value(), figures.statementDate())));
        }
        assertThat(reads.get(1).openingBalance()).as("the next statement opens where the last closed")
                .isEqualTo(reads.get(0).closingBalance());
        Optional<List<CreditLineStatements.StatementRead>> theirs =
                inOneTransaction(uow -> STATEMENTS.read(uow, line.loan(), UUID.randomUUID()));
        assertThat(theirs).as("another party's line").isEmpty();
        LoanId loan = disbursed(2_000_00, 12);
        Optional<List<CreditLineStatements.StatementRead>> instalment =
                inOneTransaction(uow -> STATEMENTS.read(uow, loan, InstalmentBillingDatabaseTest.partyOf(loan)));
        assertThat(instalment).as("an instalment loan has no statements").isEmpty();
    }

    @Test
    @DisplayName("statement day 28 in February: a line stating on the 28th - the cycle ending 28 February runs from 28"
            + " January (31 dates), the next to 28 March (28 dates); every cycle ends on the 28th, falls due 25 days later,"
            + " and bills exactly its own rows")
    void statementDay28InFebruary() {
        DisbursementWorld.Accepted line = lines(1, 3_000_00, 28, 25, false).get(0);
        LocalDate opened = LocalDate.of(2026, 1, 15);
        openOn(line, opened);
        dateDraw(draw(line, 2_000_00), opened.plusDays(1));
        while (!service(line.loan()).caughtUp()) {
            // Catching up, 92 dates a step.
        }
        List<StatedItem> all = statements(line);
        Statement february = all.stream().map(item -> item.statement().statement())
                .filter(figures -> figures.statementDate().equals(LocalDate.of(2026, 2, 28))).findFirst().orElseThrow();
        assertThat(february.cycle().start()).isEqualTo(LocalDate.of(2026, 1, 28));
        assertThat(february.dueDate()).isEqualTo(LocalDate.of(2026, 3, 25));
        assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ? AND period_end = ?",
                line.loan().value(), LocalDate.of(2026, 2, 28))).isEqualTo(31);
        assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ? AND period_end = ?",
                line.loan().value(), LocalDate.of(2026, 3, 28))).isEqualTo(28);
        for (StatedItem item : all) {
            Statement figures = item.statement().statement();
            assertThat(figures.statementDate().getDayOfMonth()).isEqualTo(28);
            assertThat(figures.dueDate()).isEqualTo(figures.statementDate().plusDays(25));
            assertThat(figures.interestBilled().minorUnits()).isEqualTo(count("SELECT coalesce(sum(posted_minor), 0)"
                    + " FROM lending.interest_accrual WHERE loan_id = ? AND period_start = ? AND period_end = ?",
                    line.loan().value(), figures.cycle().start(), figures.statementDate()));
        }
        assertLineSubledgerProves(line, 0);
    }

    // ------------------------------------------------------------------ races at the boundary

    @Test
    @DisplayName("a statement racing a draw at the cycle end serialises: the step waits on the line's lock (L3); a draw on"
            + " the statement date belongs to the next cycle whichever order the two meet L3 in - the statement drawn after"
            + " the draw equals its twin's drawn before it, and two racing on three more lines bill the same; the limit"
            + " counts both draws")
    void aStatementRacingADrawAtTheCycleEndSerialises() throws Exception {
        LocalDate statementDate = onOrBefore(today());
        List<DisbursementWorld.Accepted> lines = lines(6, 5_000_00, statementDate.getDayOfMonth(), 25, false);
        for (DisbursementWorld.Accepted line : lines) {
            openOn(line, statementDate.minusDays(20));
            dateDraw(draw(line, 2_000_00), statementDate.minusDays(20));
        }
        // The lock: a step queues behind the line's row held FOR UPDATE, and states once it is released.
        DisbursementWorld.Accepted held = lines.get(0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, held.loan().value());
                lock.executeQuery().close();
            }
            Future<LoanServicing.Outcome> waiting = pool.submit(() -> service(held.loan()));
            assertThat(awaitBlockedBy(pid(holder)).query()).contains("lending.loan");
            assertThat(statements(held)).isEmpty();
            holder.commit();
            assertThat(waiting.get(1, TimeUnit.MINUTES).stated()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        // Both orders on twins: the draw first, the statement first.
        draw(lines.get(1), 700_00);
        service(lines.get(1).loan());
        service(lines.get(2).loan());
        draw(lines.get(2), 700_00);
        Statement drawFirst = statements(lines.get(1)).get(0).statement().statement();
        Statement statementFirst = statements(lines.get(2)).get(0).statement().statement();
        assertThat(drawFirst.principalBilled()).isEqualTo(statementFirst.principalBilled());
        assertThat(drawFirst.interestBilled()).isEqualTo(statementFirst.interestBilled());
        assertThat(drawFirst.principalBilled()).as("3 % of the cycle's 2,000.00, never of 2,700.00")
                .isEqualTo(eur(60_00));
        // Racing on three more lines: the same statement, both draws counted in the limit.
        for (DisbursementWorld.Accepted line : lines.subList(3, 6)) {
            race(2, index -> index == 0 ? service(line.loan()).stated() : draw(line, 700_00).hashCode());
            assertThat(statements(line).get(0).statement().statement().principalBilled()).isEqualTo(eur(60_00));
            assertThat(available(line)).isEqualTo(eur(5_000_00 - 2_700_00));
            assertLineSubledgerProves(line, 0);
        }
    }

    // ------------------------------------------------------------------ every writer

    @Test
    @DisplayName("the rows are held for every writer: a statement is never updated, deleted or truncated (the application"
            + " role holds no grant, the migrator meets the trigger); an early statement, one whose interest is not its"
            + " cycle's, one off the statement day and one for an instalment loan are refused")
    void theRowsAreHeldForEveryWriter() throws SQLException {
        LocalDate statementDate = onOrBefore(today());
        DisbursementWorld.Accepted line = lines(1, 2_000_00, statementDate.getDayOfMonth(), 25, false).get(0);
        LocalDate opened = statementDate.minusDays(20);
        openOn(line, opened);
        UUID id = line.loan().value();
        String agreement = scalar("SELECT id::text FROM lending.loan_agreement WHERE loan_id = ?", id);
        try (Connection app = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            // Not yet serviced - nothing accrued: each defect meets its own rule.
            // Early: a well-formed cycle of a line whose statement date is still ahead - only the clock refuses it.
            LocalDate ahead = today().plusDays(1);
            while (ahead.getDayOfMonth() > LoanTerms.LAST_ALLOWED_DAY) {
                ahead = ahead.plusDays(1);
            }
            DisbursementWorld.Accepted early = lines(1, 2_000_00, ahead.getDayOfMonth(), 25, false).get(0);
            openOn(early, ahead.minusDays(20));
            refused(app, rawStatement(early.loan().value(), scalar("SELECT id::text FROM lending.loan_agreement"
                    + " WHERE loan_id = ?", early.loan().value()), ahead.minusDays(20), ahead, 0), "P0001", "has come");
            refused(app, rawStatement(id, agreement, opened, statementDate, 1), "P0001", "exactly its cycle");
            refused(app, rawStatement(id, agreement, opened, statementDate.minusDays(1), 0), "P0001", "statement day");
            refused(app, rawStatement(id, agreement, opened.plusDays(1), statementDate, 0), "P0001", "contiguous");
            refused(app, rawStatement(id, agreement, opened, statementDate, 0).replace(", 1, 1, 1, NULL,", ", 1, 2, 1, NULL,"),
                    "P0001", "pinned accrual and statement engines");
            LoanId loan = disbursed(1_500_00, 12);
            String loanAgreement = scalar("SELECT id::text FROM lending.loan_agreement WHERE loan_id = ?", loan.value());
            refused(app, rawStatement(loan.value(), loanAgreement, statementDate.minusMonths(1), statementDate, 0),
                    "P0001", "credit line");
            // Stated: never changed, for every role.
            assertThat(service(line.loan()).stated()).isEqualTo(1);
            refused(app, "UPDATE lending.credit_line_statement SET interest_billed_minor = 0 WHERE loan_id = '" + id + "'",
                    "42501", "");
            refused(app, "DELETE FROM lending.credit_line_statement WHERE loan_id = '" + id + "'", "42501", "");
            refused(migrator, "UPDATE lending.credit_line_statement SET interest_billed_minor = 0 WHERE loan_id = '" + id
                    + "'", "P0001", "immutable");
            refused(migrator, "DELETE FROM lending.credit_line_statement WHERE loan_id = '" + id + "'", "P0001",
                    "immutable");
            refused(migrator, "TRUNCATE lending.credit_line_statement", "P0001", "");
        }
        assertThat(statements(line)).hasSize(1);
        // No statement component is ever paid beyond what it billed, at commit (INV-LND-04): an application paying one
        // minor unit more principal than the statement billed is refused by the deferred check.
        Statement figures = statements(line).get(0).statement().statement();
        long over = figures.principalBilled().minorUnits() + 1;
        UUID statementId = statements(line).get(0).statement().id();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID application = UUID.randomUUID();
            DisbursementWorld.execute(app, "INSERT INTO lending.credit_balance_application (id, billing_id, loan_id, currency,"
                    + " scale, amount_minor, agreement_id, agreement_version, allocation_engine_version, journal_entry_id,"
                    + " created_at, correlation_id) VALUES ('" + application + "', '" + statementId + "', '" + id + "', 'EUR',"
                    + " 2, " + over + ", '" + agreement + "', 1, 1, gen_random_uuid(), statement_timestamp(), 'p11t22-raw')");
            DisbursementWorld.execute(app, "INSERT INTO lending.credit_balance_allocation (id, application_id, ordinal,"
                    + " billing_id, component, currency, scale, amount_minor) VALUES (gen_random_uuid(), '" + application
                    + "', 1, '" + statementId + "', 'PRINCIPAL', 'EUR', 2, " + over + ")");
            assertThatThrownBy(app::commit).isInstanceOf(SQLException.class).hasMessageContaining("beyond what it billed");
        }
        assertThat(count("SELECT count(*) FROM lending.credit_balance_application WHERE billing_id = ?", statementId))
                .isZero();
    }

    // ------------------------------------------------------------------ the world

    /** The business date of the database clock in UTC - the suite's lines' pinned zone. */
    static LocalDate today() {
        return InstalmentBillingDatabaseTest.today();
    }

    /** {@code date}, or the latest date before it with a day every month has (a statement day is 1 to 28). */
    static LocalDate onOrBefore(LocalDate date) {
        LocalDate candidate = date;
        while (candidate.getDayOfMonth() > LoanTerms.LAST_ALLOWED_DAY) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    /** Payment-due days placing a statement's due date on today, its statement date on a day every month has. */
    static int dueDaysLandingToday() {
        for (int days = 25; days >= 18; days--) {
            if (today().minusDays(days).getDayOfMonth() <= LoanTerms.LAST_ALLOWED_DAY) {
                return days;
            }
        }
        throw new IllegalStateException("some day in a week has a day of the month every month has");
    }

    /** Credit line terms allowing every statement day, with {@code dueDays} and auto-collection as asked. */
    static LoanTerms lineTerms(int dueDays, boolean autoCollection) {
        return LoanTermsFixtures.of(LoanProduct.CREDIT_LINE, RATE.toPlainString())
                .allowedDays(IntStream.rangeClosed(1, LoanTerms.LAST_ALLOWED_DAY).boxed().toList())
                .revolving(Optional.of(new LoanTerms.RevolvingTerms(Money.ofMinorUnits(25_00, EUR), 300, dueDays,
                        EngineVersion.STATEMENT_ENGINE_V1)))
                .autoCollection(autoCollection)
                .build();
    }

    /** {@code n} fresh lines stating on {@code statementDay}, accepted under terms activated for them, then restored. */
    static List<DisbursementWorld.Accepted> lines(int n, long limit, int statementDay, int dueDays, boolean auto) {
        activate(lineTerms(dueDays, auto));
        try {
            List<DisbursementWorld.Accepted> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                lines.add(acceptedLineOnDay(limit, statementDay));
            }
            return lines;
        } finally {
            activate(LoanTermsFixtures.of(LoanProduct.CREDIT_LINE, "0.1299").build());
        }
    }

    /** The line's opening moved to 01:00 UTC of {@code opened} - the fixture's clock, never a path. */
    static void openOn(DisbursementWorld.Accepted line, LocalDate opened) {
        asOwner("UPDATE lending.loan SET opened_at = TIMESTAMPTZ '" + opened + " 01:00:00+00' WHERE id = '"
                + line.loan().value() + "'");
    }

    /** A real draw through T-drw, capital planted for it. */
    static CreditLineDraw draw(DisbursementWorld.Accepted line, long minor) {
        fund(eur(minor));
        return CreditLineDrawDatabaseTest.draw(line, "p11t22-" + UUID.randomUUID(), minor).draw();
    }

    /** The draw's entry and row valued {@code date} - the fixture's clock, never a path. */
    static void dateDraw(CreditLineDraw draw, LocalDate date) {
        asOwner("UPDATE ledger.journal_entry SET posting_date = DATE '" + date + "', value_date = DATE '" + date
                + "' WHERE id = '" + draw.journalEntry().value() + "';"
                + " UPDATE lending.credit_line_draw SET value_date = DATE '" + date + "' WHERE id = '" + draw.id() + "'");
    }

    static List<StatedItem> statements(DisbursementWorld.Accepted line) {
        return inOneTransaction(uow -> STATEMENT_STORE.statementsOwnedBy(uow, line.loan(), line.party()));
    }

    static Money available(DisbursementWorld.Accepted line) {
        return inOneTransaction(uow -> DisbursementWorld.LOANS
                .read(uow, line.loan(), line.party())).orElseThrow().availableLimit().orElseThrow();
    }

    /** One COLLECTED attempt of exactly the minimum, its repayment paying the statement alone; the limit restored. */
    private static void assertCollectedOnce(DisbursementWorld.Accepted line) {
        StatedItem item = statements(line).get(0);
        long minimum = item.statement().statement().minimumPayment().minorUnits();
        UUID id = line.loan().value();
        assertThat(count("SELECT count(*) FROM lending.collection_attempt WHERE loan_id = ?", id)).isEqualTo(1);
        assertThat(scalar("SELECT outcome || ':' || collected_minor || ':' || attempt_date FROM lending.collection_attempt"
                + " WHERE loan_id = ? AND due_item_id = ?", id, item.statement().id()))
                .isEqualTo("COLLECTED:" + minimum + ":" + today());
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ? AND kind = 'AUTO_COLLECTION'"
                + " AND amount_minor = ?", id, minimum)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.repayment_allocation a JOIN lending.repayment r"
                + " ON r.id = a.repayment_id WHERE r.loan_id = ? AND (a.target <> 'DUE_ITEM' OR a.billing_id <> ?)",
                id, item.statement().id())).as("every line pays the statement").isZero();
        assertThat(item.outstanding()).isEqualTo(eur(0));
        assertThat(RepaymentDatabaseTest.due(line.loan())).isZero();
        long principalBilled = item.statement().statement().principalBilled().minorUnits();
        assertThat(available(line)).as("the principal paid restores the limit")
                .isEqualTo(eur(4_000_00 - (1_500_00 - principalBilled)));
        assertThat(-netDebit(walletOf(line.party())).minorUnits()).as("the wallet debited the minimum once")
                .isEqualTo(1_500_00 - minimum);
        assertThat(service(line.loan()).collections()).as("a later step that day attempts nothing").isEmpty();
        assertLineSubledgerProves(line, 0);
    }

    /**
     * The line's subledger arms: each due account is what its statements billed less every payer's allocations plus what
     * reversals re-opened; the accrued interest the accruals less what statements billed; principal not yet due the draws
     * less what statements billed and what repayments paid down, plus what reversals re-opened of a paydown
     * (`P11-TSK-023`); the credit balance the fixture's credit plus what repayments held, less what reversals re-opened of
     * it, the applications and the closure's refund (`P11-TSK-023`).
     */
    static void assertLineSubledgerProves(DisbursementWorld.Accepted line, long creditFixture) {
        UUID id = line.loan().value();
        for (String component : List.of("FEES", "INTEREST", "PRINCIPAL")) {
            // P11-TSK-025: fees are what was assessed on the statements; fee and interest waivers forgive what they billed.
            long billed = component.equals("FEES")
                    ? count("SELECT coalesce(sum(amount_minor), 0) FROM lending.fee_assessment WHERE loan_id = ?", id)
                    : count("SELECT coalesce(sum(" + component.toLowerCase(java.util.Locale.ROOT) + "_billed_minor),"
                            + " 0) FROM lending.credit_line_statement WHERE loan_id = ?", id);
            if (!component.equals("PRINCIPAL")) {
                billed -= count("SELECT coalesce(sum(w.amount_minor), 0) FROM lending.waiver w JOIN lending.waiver_decision d"
                        + " ON d.waiver_id = w.id AND d.outcome = 'APPROVED' WHERE w.loan_id = ? AND w.kind = ?", id,
                        component.equals("FEES") ? "FEE" : "INTEREST");
            }
            long applied = count("SELECT coalesce(sum(a.amount_minor), 0) FROM lending.credit_balance_allocation a"
                    + " JOIN lending.credit_line_statement s ON s.id = a.billing_id WHERE s.loan_id = ? AND a.component = ?",
                    id, component);
            long repaid = count("SELECT coalesce(sum(a.amount_minor), 0) FROM lending.repayment_allocation a"
                    + " JOIN lending.repayment r ON r.id = a.repayment_id WHERE r.loan_id = ? AND a.component = ?",
                    id, component);
            long reversed = count("SELECT coalesce(sum(n.amount_minor), 0) FROM lending.repayment_allocation_reversal n"
                    + " JOIN lending.repayment_allocation a ON a.id = n.allocation_id"
                    + " JOIN lending.repayment r ON r.id = a.repayment_id WHERE r.loan_id = ? AND a.component = ?",
                    id, component);
            AccountPurpose due = switch (component) {
                case "FEES" -> AccountPurpose.LOAN_FEES_DUE;
                case "INTEREST" -> AccountPurpose.LOAN_INTEREST_DUE;
                default -> AccountPurpose.LOAN_PRINCIPAL_DUE;
            };
            long balance = netDebit(loanAccount(line.loan(), due)).minorUnits();
            assertThat(balance).as(due + ": stated less waived, less every payer, plus what reversals re-opened")
                    .isEqualTo(billed - applied - repaid + reversed);
            assertThat(balance).as(due + " never negative").isGreaterThanOrEqualTo(0);
        }
        assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_INTEREST_ACCRUED)).minorUnits())
                .isEqualTo(count("SELECT coalesce(sum(posted_minor), 0) FROM lending.interest_accrual WHERE loan_id = ?", id)
                        - count("SELECT coalesce(sum(interest_billed_minor), 0) FROM lending.credit_line_statement"
                                + " WHERE loan_id = ?", id));
        assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_PRINCIPAL)).minorUnits())
                .as("principal not yet due: draws less statements' principal and paydowns, plus reversed paydowns")
                .isEqualTo(count("SELECT coalesce(sum(amount_minor), 0) FROM lending.credit_line_draw WHERE loan_id = ?", id)
                        - count("SELECT coalesce(sum(principal_billed_minor), 0) FROM lending.credit_line_statement"
                                + " WHERE loan_id = ?", id)
                        - target(id, "NOT_DUE_PRINCIPAL", false) + target(id, "NOT_DUE_PRINCIPAL", true));
        assertThat(held(line.loan())).as("the credit balance: the fixture's and the repayments' excess, less reversals,"
                + " applications and the closure's refund").isEqualTo(creditFixture
                + target(id, "CREDIT_BALANCE", false) - target(id, "CREDIT_BALANCE", true)
                + count("SELECT coalesce(sum(w.amount_minor), 0) FROM lending.waiver w JOIN lending.waiver_decision d"
                        + " ON d.waiver_id = w.id AND d.outcome = 'APPROVED' WHERE w.loan_id = ? AND w.kind = 'FEE_REFUND'",
                        id)
                - count("SELECT coalesce(sum(amount_minor), 0) FROM lending.credit_balance_application WHERE loan_id = ?",
                        id)
                - count("SELECT coalesce(sum(refund_minor), 0) FROM lending.loan_closure WHERE loan_id = ?", id));
        assertThat(count("SELECT count(*) FROM lending.credit_line_statement s WHERE s.loan_id = ?"
                + " AND s.journal_entry_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ledger.journal_entry e"
                + " WHERE e.id = s.journal_entry_id AND e.idempotency_scope = 'ledger.post:lending.statement:'"
                + " || s.loan_id || ':' || s.cycle_end)", id)).as("each statement's entry under its natural key").isZero();
        if (creditFixture == 0) {
            // P11-TSK-029: the production subledger proof agrees with these arms on every line a suite proves - a
            // fixture's credit, posted by no lending row, it would rightly fail.
            LoanSubledgerProof.Verdict proof = LendingProofsWorld.SUBLEDGER.prove(LendingProofsWorld.record(line.loan()));
            assertThat(proof.holds()).as("LoanSubledgerProof: %s", proof).isTrue();
        }
    }

    /** The line's repayment rows of {@code target} - or, {@code reversed}, their negating rows (`P11-TSK-020`). */
    private static long target(UUID loan, String target, boolean reversed) {
        return count(reversed
                ? "SELECT coalesce(sum(n.amount_minor), 0) FROM lending.repayment_allocation_reversal n"
                        + " JOIN lending.repayment_allocation a ON a.id = n.allocation_id"
                        + " JOIN lending.repayment r ON r.id = a.repayment_id WHERE r.loan_id = ? AND a.target = ?"
                : "SELECT coalesce(sum(a.amount_minor), 0) FROM lending.repayment_allocation a"
                        + " JOIN lending.repayment r ON r.id = a.repayment_id WHERE r.loan_id = ? AND a.target = ?",
                loan, target);
    }

    private static List<String> columns() {
        List<String> columns = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'lending'"
                        + " AND table_name = 'credit_line_statement' ORDER BY ordinal_position");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return columns;
    }

    /** An application-role insert of a statement of {@code loan} for {@code [start, end)}, interest {@code interest}. */
    static String rawStatement(UUID loan, String agreement, LocalDate start, LocalDate end, long interest) {
        return "INSERT INTO lending.credit_line_statement (id, loan_id, cycle_start, cycle_end, due_date, currency, scale,"
                + " interest_billed_minor, fees_carried_minor, principal_billed_minor, minimum_payment_minor, agreement_id,"
                + " agreement_version, statement_engine_version, accrual_engine_version, journal_entry_id, created_at,"
                + " correlation_id) VALUES (gen_random_uuid(), '" + loan + "', DATE '" + start + "', DATE '" + end
                + "', DATE '" + end.plusDays(25) + "', 'EUR', 2, " + interest + ", 0, 0, " + interest + ", '" + agreement
                + "', 1, 1, 1, " + (interest > 0 ? "gen_random_uuid()" : "NULL") + ", statement_timestamp(), 'p11t22-raw')";
    }
}
