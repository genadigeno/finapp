package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.CONDITIONS;
import static com.finapp.lending.DisbursementWorld.LOANS;
import static com.finapp.lending.DisbursementWorld.activate;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.businessDate;
import static com.finapp.lending.DisbursementWorld.businessDateSql;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.fund;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.InstalmentBillingDatabaseTest.advance;
import static com.finapp.lending.InstalmentBillingDatabaseTest.billings;
import static com.finapp.lending.InstalmentBillingDatabaseTest.daysTo;
import static com.finapp.lending.InstalmentBillingDatabaseTest.dues;
import static com.finapp.lending.InstalmentBillingDatabaseTest.partyOf;
import static com.finapp.lending.InstalmentBillingDatabaseTest.serviceUntilCaughtUp;
import static com.finapp.lending.InterestAccrualDatabaseTest.backdate;
import static com.finapp.lending.InterestAccrualDatabaseTest.disbursed;
import static com.finapp.lending.InterestAccrualDatabaseTest.eur;
import static com.finapp.lending.InterestAccrualDatabaseTest.refused;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.RepaymentDatabaseTest.due;
import static com.finapp.lending.RepaymentDatabaseTest.events;
import static com.finapp.lending.RepaymentDatabaseTest.key;
import static com.finapp.lending.RepaymentDatabaseTest.loanStatus;
import static com.finapp.lending.RepaymentDatabaseTest.refusedAlone;
import static com.finapp.lending.RepaymentDatabaseTest.repay;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Delinquency and default conditions against a real database (`P11-TSK-024`; {@code lending V020}, {@code PHASE_11_PLAN.md}
 * sections 5.5, 7.1 T-svc and 12.7, section 13.2's "current to each bucket to cure to default to cure"; ADR-0098; L7, A8,
 * A25; {@code INV-LND-12}, {@code INV-LND-10}, {@code INV-LND-08}, {@code INV-HIST-01}).
 *
 * <p>Loans are disbursed by the real T-dis to the world's wallet and serviced by the real T-svc, whose condition step runs
 * the real {@link LoanConditions} after the real billing, statement and collection steps; repayments are the real T-rep,
 * draws the real T-drw wired with the real derivation ({@link CreditLineDrawDatabaseTest#DRAWS}). Long histories are built
 * by owner-role fixtures (A15): a later business date is {@link InstalmentBillingDatabaseTest#advance} - the fixture's
 * clock, never a path - and every planted date derives from the database clock in the pinned zone
 * ({@link DisbursementWorld#businessDate}), so the suite holds in the shifted zone it runs in. Every recorded row is also
 * held by {@code lending V020}'s trigger to the derivation - a test that passes here passed it too.
 */
@Tag("database")
@DisplayName("delinquency and default conditions (P11-TSK-024)")
class DelinquencyDatabaseTest {

    // ------------------------------------------------------------------ the transitions

    @Test
    @DisplayName("current to each bucket and cure: an instalment unpaid at the end of its due date is past due the next"
            + " business date - DPD 1, B1 recorded once; 29 still B1, nothing written; 30 B2; 60 B3; each change one row"
            + " and one lending.LoanDelinquencyChanged naming the bucket before and after; the borrower pays what is due"
            + " and the step records the cure - CURRENT, DPD 0; no default; the reads show the condition; the loan ACTIVE"
            + " throughout - a condition, never a state")
    void currentToEachBucketAndCure() {
        LoanId loan = dueToday(5_000_00);
        assertThat(service(loan).conditions()).as("due today is not past due").isZero();
        assertThat(rows(loan)).isEmpty();
        assertThat(read(loan).orElseThrow()).isEqualTo(DelinquencyState.CURRENT);

        assertThat(day(loan, 1).conditions()).isEqualTo(1);
        assertThat(last(loan)).isEqualTo(new Row("DELINQUENCY", businessDate(loan), 1, "B1_1_29", "CURRENT", false));
        assertThat(read(loan).orElseThrow().daysPastDue()).isEqualTo(1);
        assertThat(day(loan, 28).conditions()).as("DPD 29: still B1").isZero();
        assertThat(read(loan).orElseThrow().daysPastDue()).isEqualTo(29);
        assertThat(day(loan, 1).conditions()).isEqualTo(1);
        assertThat(last(loan)).isEqualTo(new Row("DELINQUENCY", businessDate(loan), 30, "B2_30_59", "B1_1_29", false));
        assertThat(day(loan, 30).conditions()).isEqualTo(1);
        assertThat(last(loan)).isEqualTo(new Row("DELINQUENCY", businessDate(loan), 60, "B3_60_89", "B2_30_59", false));
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");

        repay(loan, key(), due(loan));
        assertThat(read(loan).orElseThrow()).as("the read derives the cure at once").isEqualTo(DelinquencyState.CURRENT);
        assertThat(service(loan).conditions()).as("B3 was recorded today: the cure is the next business date's").isZero();
        assertThat(day(loan, 1).conditions()).isEqualTo(1);
        assertThat(last(loan)).isEqualTo(new Row("DELINQUENCY", businessDate(loan), 0, "CURRENT", "B3_60_89", false));
        assertThat(rows(loan)).extracting(Row::bucket).containsExactly("B1_1_29", "B2_30_59", "B3_60_89", "CURRENT");
        assertThat(rows(loan)).extracting(Row::kind).containsOnly("DELINQUENCY");
        assertThat(events(loan, LoanConditions.DELINQUENCY_CHANGED_EVENT)).isEqualTo(4);
        assertThat(eventsWith(loan, LoanConditions.DELINQUENCY_CHANGED_EVENT, "\"previousBucket\":\"B3_60_89\"",
                "\"bucket\":\"CURRENT\"")).as("the cure announced").isEqualTo(1);
        assertThat(events(loan, LoanConditions.DEFAULTED_EVENT)).isZero();
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("default at ninety, cleared at cure: DPD 89 B3 and not defaulted; DPD 90 B4 and the flag set - two rows,"
            + " lending.LoanDefaulted once; half the oldest instalment paid - DPD unchanged, nothing written; the oldest"
            + " paid in full - DPD the next oldest's age, B4 to the lower bucket, STILL defaulted (no cure while anything is"
            + " past due, A8); everything paid - CURRENT and the flag cleared, lending.LoanDefaultCleared once; a later"
            + " instalment past due is B1 and not defaulted - no probation, no memory beyond the cure")
    void defaultAtNinetyClearedAtCure() {
        LoanId loan = dueToday(5_000_00);
        service(loan);
        day(loan, 1);
        day(loan, 59);
        assertThat(day(loan, 29).conditions()).as("DPD 89").isZero();
        assertThat(read(loan).orElseThrow()).isEqualTo(new DelinquencyState(89, Optional.of(DelinquencyBucket.B3_60_89),
                false));
        assertThat(day(loan, 1).conditions()).as("DPD 90: the bucket and the flag").isEqualTo(2);
        LocalDate defaultedOn = businessDate(loan);
        assertThat(rows(loan)).filteredOn(row -> row.date().equals(defaultedOn)).containsExactlyInAnyOrder(
                new Row("DELINQUENCY", defaultedOn, 90, "B4_90_PLUS", "B3_60_89", true),
                new Row("DEFAULT", defaultedOn, 90, "B4_90_PLUS", null, true));
        assertThat(events(loan, LoanConditions.DEFAULTED_EVENT)).isEqualTo(1);
        assertThat(loanStatus(loan)).as("default is a flag, never a state - no acceleration").isEqualTo("ACTIVE");

        // A partial payment of the oldest item leaves it past due: DPD does not move.
        long oldest = billings(loan).get(0).outstanding().minorUnits();
        repay(loan, key(), oldest / 2);
        assertThat(service(loan).conditions()).isZero();
        assertThat(read(loan).orElseThrow().daysPastDue()).isEqualTo(90);

        // The oldest paid in full: DPD is the next oldest past-due item's age - still defaulted.
        repay(loan, key(), oldest - oldest / 2);
        assertThat(billings(loan).get(0).outstanding().isZero()).isTrue();
        assertThat(service(loan).conditions()).as("the default was recorded today: the change is tomorrow's").isZero();
        advance(loan, 1);
        int nextAge = Math.toIntExact(ChronoUnit.DAYS.between(billings(loan).get(1).billing().dueDate(),
                businessDate(loan)));
        assertThat(service(loan).conditions()).isEqualTo(1);
        Row lower = last(loan);
        assertThat(lower.kind()).isEqualTo("DELINQUENCY");
        assertThat(lower.dpd()).isEqualTo(nextAge).isLessThan(90).isPositive();
        assertThat(lower.previous()).isEqualTo("B4_90_PLUS");
        assertThat(lower.defaulted()).as("no cure while anything is past due (A8)").isTrue();
        assertThat(read(loan).orElseThrow().defaulted()).isTrue();
        assertThat(events(loan, LoanConditions.DEFAULT_CLEARED_EVENT)).isZero();

        repay(loan, key(), due(loan));
        assertThat(service(loan).conditions()).as("the lower bucket was recorded today").isZero();
        assertThat(day(loan, 1).conditions()).as("the cure: the bucket and the flag").isEqualTo(2);
        LocalDate curedOn = businessDate(loan);
        assertThat(rows(loan)).filteredOn(row -> row.date().equals(curedOn)).containsExactlyInAnyOrder(
                new Row("DELINQUENCY", curedOn, 0, "CURRENT", lower.bucket(), false),
                new Row("DEFAULT", curedOn, 0, "CURRENT", null, false));
        assertThat(events(loan, LoanConditions.DEFAULT_CLEARED_EVENT)).isEqualTo(1);

        // Past due again later: B1, not defaulted - cleared means cleared. (Anything the cure's step billed due today is
        // paid first, so the next instalment is the oldest unpaid.)
        if (due(loan) > 0) {
            repay(loan, key(), due(loan));
        }
        List<LocalDate> dues = dues(loan);
        LocalDate nextDue = dues.stream().filter(date -> date.isAfter(businessDate(loan))).findFirst().orElseThrow();
        day(loan, Math.toIntExact(ChronoUnit.DAYS.between(businessDate(loan), nextDue)) + 1);
        assertThat(last(loan)).isEqualTo(new Row("DELINQUENCY", businessDate(loan), 1, "B1_1_29", "CURRENT", false));
        assertThat(events(loan, LoanConditions.DEFAULTED_EVENT)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ once

    @Test
    @DisplayName("re-running a day writes nothing: the step that recorded B1 run three more times that business date -"
            + " one row, one event; a second change of the kind that date is refused beneath every lock by"
            + " UNIQUE (loan_id, business_date, kind), and a row that changes nothing by the trigger")
    void reRunningADayWritesNothing() throws SQLException {
        LoanId loan = dueToday(5_000_00);
        service(loan);
        assertThat(day(loan, 1).conditions()).isEqualTo(1);
        for (int i = 0; i < 3; i++) {
            assertThat(service(loan).conditions()).isZero();
        }
        assertThat(rows(loan)).hasSize(1);
        assertThat(events(loan, LoanConditions.DELINQUENCY_CHANGED_EVENT)).isEqualTo(1);
        // Beneath L3: a second DELINQUENCY row that date, every trigger suspended - the unique refuses it.
        assertThatThrownBy(() -> asOwner("INSERT INTO lending.loan_condition_event SELECT gen_random_uuid(), loan_id,"
                + " business_date, kind, days_past_due, 'B2_30_59', 'B1_1_29', defaulted, agreement_id, agreement_version,"
                + " created_at, correlation_id FROM lending.loan_condition_event WHERE loan_id = '" + loan.value() + "'"))
                .rootCause().hasMessageContaining("loan_condition_event_once_a_day");
        // The trigger: the same change again (from CURRENT, which is no longer the latest) is no change.
        refusedAlone(raw(loan, "DELINQUENCY", 1, "B1_1_29", "CURRENT", false), "P0001", "only on a change");
        assertThat(rows(loan)).hasSize(1);
    }

    @Test
    @DisplayName("ten sweepers, one condition change: thirty servicing steps racing over three loans on the business date"
            + " each crosses into B1 - L3 serialises them: one row and one event per loan; ten steps racing on a loan"
            + " crossing ninety - exactly its two rows (the bucket and the flag) and one lending.LoanDefaulted")
    void tenSweepersOneConditionChange() throws Exception {
        List<LoanId> loans = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LoanId loan = dueToday(5_000_00 + i * 100_00);
            service(loan);
            advance(loan, 1);
            loans.add(loan);
        }
        List<Integer> changes = race(30, index -> service(loans.get(index % 3)).conditions());
        assertThat(changes.stream().mapToInt(Integer::intValue).sum()).as("one change per loan").isEqualTo(3);
        for (LoanId loan : loans) {
            assertThat(rows(loan)).containsExactly(new Row("DELINQUENCY", businessDate(loan), 1, "B1_1_29", "CURRENT",
                    false));
            assertThat(events(loan, LoanConditions.DELINQUENCY_CHANGED_EVENT)).isEqualTo(1);
        }

        LoanId crossing = dueToday(5_400_00);
        service(crossing);
        day(crossing, 1);
        day(crossing, 59);
        day(crossing, 29);
        advance(crossing, 1);
        List<Integer> crossings = race(10, index -> service(crossing).conditions());
        assertThat(crossings.stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
        assertThat(rows(crossing)).filteredOn(row -> row.date().equals(businessDate(crossing))).hasSize(2);
        assertThat(events(crossing, LoanConditions.DEFAULTED_EVENT)).isEqualTo(1);
    }

    @Test
    @DisplayName("a cure between sweeps is recorded, not overwritten: on three loans recorded B1, the next business date's"
            + " step races the borrower's repayment of everything due - serialised on L3 in either order; after one more"
            + " step each history ends in exactly one CURRENT row dated that date, nothing delinquent after it, and the"
            + " read CURRENT")
    void aCureBetweenSweepsIsRecordedNotOverwritten() throws Exception {
        for (int round = 0; round < 3; round++) {
            LoanId loan = dueToday(4_600_00 + round * 100_00);
            service(loan);
            day(loan, 1);
            advance(loan, 1);
            long owed = due(loan);
            race(2, index -> index == 0 ? service(loan).conditions() : repay(loan, key(), owed).repayment().hashCode());
            service(loan);
            List<Row> rows = rows(loan);
            assertThat(rows).extracting(Row::bucket).containsExactly("B1_1_29", "CURRENT");
            assertThat(rows.get(1).date()).isEqualTo(businessDate(loan));
            assertThat(rows.get(1).dpd()).isZero();
            assertThat(read(loan).orElseThrow()).isEqualTo(DelinquencyState.CURRENT);
            assertThat(service(loan).conditions()).isZero();
        }
    }

    @Test
    @DisplayName("a missed day computes from the dates: a loan recorded B1 at DPD 1 and not serviced for forty days is"
            + " recorded B2 at DPD 41 in one row - never a day at a time; another, B3 at DPD 61 and unserviced for thirty"
            + " four days, is recorded B4 at DPD 95 and defaulted at once")
    void aMissedDayComputesFromTheDates() {
        LoanId loan = dueToday(5_000_00);
        service(loan);
        day(loan, 1);
        assertThat(day(loan, 40).conditions()).isEqualTo(1);
        assertThat(rows(loan)).containsExactly(
                new Row("DELINQUENCY", businessDate(loan).minusDays(40), 1, "B1_1_29", "CURRENT", false),
                new Row("DELINQUENCY", businessDate(loan), 41, "B2_30_59", "B1_1_29", false));

        LoanId later = dueToday(5_100_00);
        service(later);
        day(later, 1);
        day(later, 60);
        assertThat(last(later).bucket()).isEqualTo("B3_60_89");
        assertThat(day(later, 34).conditions()).isEqualTo(2);
        assertThat(rows(later)).filteredOn(row -> row.date().equals(businessDate(later))).containsExactlyInAnyOrder(
                new Row("DELINQUENCY", businessDate(later), 95, "B4_90_PLUS", "B3_60_89", true),
                new Row("DEFAULT", businessDate(later), 95, "B4_90_PLUS", null, true));
    }

    @Test
    @DisplayName("the change and its event are one transaction with the step: an instance dying after the condition's event"
            + " row leaves neither - nor the step's accrual - and the step re-driven records the change once")
    void aCrashAfterTheEventLeavesNothing() {
        LoanId loan = dueToday(5_000_00);
        service(loan);
        advance(loan, 1);
        long accruals = count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?", loan.value());
        OutboxWriter<Connection> crashing = (unitOfWork, envelope, payload, mediaType) -> {
            new JdbcOutboxWriter().write(unitOfWork, envelope, payload, mediaType);
            if (envelope.eventType().equals(LoanConditions.DELINQUENCY_CHANGED_EVENT)) {
                throw new IllegalStateException("the instance died after the condition's event row");
            }
        };
        LoanServicing dying = new LoanServicing(DisbursementWorld.LOAN_STORE, new JdbcLoanServicingStore(),
                DisbursementWorld.TERMS_STORE, DisbursementWorld.LEDGER_ACCOUNTS, DisbursementWorld.BALANCES,
                DisbursementWorld.POSTINGS, DisbursementWorld.IDS, DisbursementWorld.BILLINGS, DisbursementWorld.CLOSURES,
                DisbursementWorld.COLLECTIONS, DisbursementWorld.STATEMENTS, DisbursementWorld.conditions(crashing));
        assertThatThrownBy(() -> service(dying, loan)).hasMessageContaining("instance died");
        assertThat(rows(loan)).isEmpty();
        assertThat(events(loan, LoanConditions.DELINQUENCY_CHANGED_EVENT)).isZero();
        assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?", loan.value()))
                .as("the step's accrual rolled back with it").isEqualTo(accruals);
        assertThat(service(loan).conditions()).isEqualTo(1);
        assertThat(rows(loan)).hasSize(1);
        assertThat(events(loan, LoanConditions.DELINQUENCY_CHANGED_EVENT)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ terminal

    @Test
    @DisplayName("a closed account is skipped: a two-month loan recorded past due is repaid in full and closes - its condition is"
            + " its closure: the step does not service it, writes nothing, the history ends where it was; the read shows"
            + " no condition; a condition row for the closed loan is refused for every writer (INV-LND-08)")
    void aClosedAccountIsSkipped() throws SQLException {
        LoanId loan = disbursed(2_000_00, 2);
        backdate(loan, daysTo(loan, 1));
        service(loan);
        day(loan, 1);
        List<LocalDate> dues = dues(loan);
        day(loan, Math.toIntExact(ChronoUnit.DAYS.between(dues.get(0), dues.get(1))) - 1);
        assertThat(billings(loan)).hasSize(2);
        List<Row> before = rows(loan);
        assertThat(before.get(before.size() - 1).dpd()).as("past due through its second due date").isPositive();
        RepaymentDatabaseTest.topUp(partyOf(loan), 500_00);
        assertThat(repay(loan, key(), due(loan)).closure()).as("the last receivable settled").isPresent();
        assertThat(loanStatus(loan)).isEqualTo("CLOSED");

        assertThat(service(loan).kind()).isEqualTo(LoanServicing.Outcome.Kind.NOT_SERVICED);
        assertThat(rows(loan)).as("the history ends where it was").isEqualTo(before);
        assertThat(read(loan)).as("DPD is never computed on a closed account").isEmpty();
        // A later business date (nothing recorded on it), so only the account's state can refuse the row.
        advance(loan, 1);
        List<Row> moved = rows(loan);
        refusedAlone(raw(loan, "DELINQUENCY", 0, "CURRENT", before.get(before.size() - 1).bucket(), false), "P0001",
                "ACTIVE or CLOSING");
        assertThat(rows(loan)).isEqualTo(moved);
    }

    // ------------------------------------------------------------------ the line's draws (A25)

    @Test
    @DisplayName("a past-due line refuses draws: a line whose statement falls due today still draws (due, not past due);"
            + " one whose minimum went unpaid through its due date is recorded B1 and refuses the draw 409"
            + " lending.DrawsSuspended through the wired derivation, nothing written; the minimum paid, the very next draw"
            + " is admitted - derived under L3 before any step records the cure")
    void aPastDueLineRefusesDraws() {
        // Due today: not past due, drawable.
        LocalDate dueTodayStatement = fixtureToday().minusDays(CreditLineStatementDatabaseTest.dueDaysLandingToday());
        DisbursementWorld.Accepted onTime = statedLine(dueTodayStatement,
                CreditLineStatementDatabaseTest.dueDaysLandingToday());
        assertThat(read(onTime.loan()).orElseThrow().pastDue()).isFalse();
        assertThat(drawn(onTime, 50_00)).isTrue();

        // Unpaid through its due date: suspended.
        DisbursementWorld.Accepted late = statedLine(CreditLineStatementDatabaseTest.onOrBefore(fixtureToday().minusDays(27)),
                25);
        DelinquencyState state = read(late.loan()).orElseThrow();
        assertThat(state.pastDue()).isTrue();
        assertThat(state.defaulted()).isFalse();
        assertThat(last(late.loan()).bucket()).isEqualTo("B1_1_29");
        long drawsBefore = count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ?", late.loan().value());
        assertThatExceptionOfType(CreditLineDraws.DrawsSuspended.class).isThrownBy(() -> drawn(late, 50_00));
        assertThat(count("SELECT count(*) FROM lending.credit_line_draw WHERE loan_id = ?", late.loan().value()))
                .as("nothing written").isEqualTo(drawsBefore);
        assertThat(inOneTransaction(uow -> LOANS.read(uow, late.loan(), late.party())).orElseThrow().drawsSuspended())
                .contains(true);

        repay(late.loan(), key(), due(late.loan()));
        assertThat(drawn(late, 50_00)).as("the cure lifts the suspension at its commit").isTrue();
        assertThat(read(late.loan()).orElseThrow()).isEqualTo(DelinquencyState.CURRENT);
        assertThat(service(late.loan()).conditions()).as("B1 was recorded today: the cure is the next business date's"
                + " evaluation (proven on loans by #currentToEachBucketAndCure)").isZero();
    }

    @Test
    @DisplayName("a defaulted line refuses draws: a line whose oldest minimum is more than ninety days past due is"
            + " recorded B4 and defaulted once its catch-up completes, and refuses the draw; its oldest statement paid -"
            + " DPD the next one's age, still defaulted and still refused; everything due paid - cured, the flag cleared"
            + " in the derivation and the draw admitted at once")
    void aDefaultedLineRefusesDraws() {
        LocalDate statementDate = CreditLineStatementDatabaseTest.onOrBefore(fixtureToday().minusDays(25 + 96));
        DisbursementWorld.Accepted line = statedLine(statementDate, 25);
        DelinquencyState state = read(line.loan()).orElseThrow();
        assertThat(state.daysPastDue()).isGreaterThanOrEqualTo(90);
        assertThat(state.defaulted()).isTrue();
        assertThat(rows(line.loan())).filteredOn(row -> row.kind().equals("DEFAULT")).singleElement()
                .satisfies(row -> assertThat(row.defaulted()).isTrue());
        assertThatExceptionOfType(CreditLineDraws.DrawsSuspended.class).isThrownBy(() -> drawn(line, 50_00));

        long oldest = inOneTransaction(uow -> DisbursementWorld.BILLING_STORE.dueItems(uow, line.loan())).get(0)
                .total().minorUnits();
        repay(line.loan(), key(), oldest);
        DelinquencyState lower = read(line.loan()).orElseThrow();
        assertThat(lower.daysPastDue()).isPositive().isLessThan(state.daysPastDue());
        assertThat(lower.defaulted()).as("no cure while anything is past due").isTrue();
        assertThatExceptionOfType(CreditLineDraws.DrawsSuspended.class).isThrownBy(() -> drawn(line, 50_00));

        repay(line.loan(), key(), due(line.loan()));
        assertThat(drawn(line, 50_00)).isTrue();
        assertThat(read(line.loan()).orElseThrow()).as("cured: the flag cleared in the derivation at once")
                .isEqualTo(DelinquencyState.CURRENT);
    }

    // ------------------------------------------------------------------ grace

    @Test
    @DisplayName("grace delays only the late fee, never DPD: under terms with three days' grace and under terms with the"
            + " maximum thirty, the day after the due date is DPD 1 and B1 - recorded and read - and nothing is assessed")
    void graceDelaysOnlyTheLateFeeNeverDpd() {
        LoanId threeDays = dueToday(5_000_00);
        activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").graceDays(30).lateFeeDay(31).build());
        LoanId thirtyDays;
        try {
            thirtyDays = dueToday(5_100_00);
        } finally {
            activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").build());
        }
        assertThat(count("SELECT t.grace_days FROM lending.loan_terms t JOIN lending.loan_agreement a"
                + " ON a.terms_version_id = t.terms_version_id WHERE a.loan_id = ?", thirtyDays.value())).isEqualTo(30);
        for (LoanId loan : List.of(threeDays, thirtyDays)) {
            service(loan);
            day(loan, 1);
            assertThat(rows(loan)).containsExactly(new Row("DELINQUENCY", businessDate(loan), 1, "B1_1_29", "CURRENT",
                    false));
            assertThat(read(loan).orElseThrow().daysPastDue()).isEqualTo(1);
            assertThat(DisbursementWorld.netDebit(DisbursementWorld.loanAccount(loan,
                    com.finapp.ledger.AccountPurpose.LOAN_FEES_DUE)).isZero()).as("no fee - the late fee is -025's").isTrue();
        }
    }

    // ------------------------------------------------------------------ every writer

    @Test
    @DisplayName("the history is append-only for every role: the application role holds no UPDATE or DELETE; the migrator's"
            + " UPDATE, DELETE and TRUNCATE are refused by the trigger; and an application-role row is refused unless it is"
            + " the derivation - a DPD or bucket not the rows', a default flag against the rule, a date not the business"
            + " date, an agreement version not the governing one")
    void theHistoryIsAppendOnlyForEveryRole() throws SQLException {
        LoanId loan = dueToday(5_000_00);
        service(loan);
        day(loan, 1);
        try (Connection app = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            refused(app, "UPDATE lending.loan_condition_event SET days_past_due = 2 WHERE loan_id = '" + loan.value() + "'",
                    "42501", "");
            refused(app, "DELETE FROM lending.loan_condition_event WHERE loan_id = '" + loan.value() + "'", "42501", "");
            refused(migrator, "UPDATE lending.loan_condition_event SET days_past_due = 2 WHERE loan_id = '"
                    + loan.value() + "'", "P0001", "immutable");
            refused(migrator, "DELETE FROM lending.loan_condition_event WHERE loan_id = '" + loan.value() + "'", "P0001",
                    "immutable");
            refused(migrator, "TRUNCATE lending.loan_condition_event", "P0001", "");
        }
        advance(loan, 1);
        // The derivation, for every writer: today's DPD is 2, still B1 - only a DEFAULT row or no row could be true.
        refusedAlone(raw(loan, "DELINQUENCY", 31, "B2_30_59", "B1_1_29", false), "P0001", "derived");
        refusedAlone(raw(loan, "DELINQUENCY", 2, "B2_30_59", "B1_1_29", false), "P0001", "derived");
        refusedAlone(raw(loan, "DEFAULT", 2, "B1_1_29", null, true), "P0001", "default threshold");
        // A row true of tomorrow, on a loan billed yesterday and never evaluated past due: only its date is wrong.
        LoanId unevaluated = dueToday(5_200_00);
        service(unevaluated);
        advance(unevaluated, 1);
        refusedAlone(rawOn(unevaluated, "DELINQUENCY", 2, "B1_1_29", "CURRENT", false,
                businessDateSql(unevaluated) + " + 1", 1), "P0001", "business date");
        refusedAlone(rawOn(loan, "DEFAULT", 2, "B1_1_29", null, false, businessDateSql(loan), 1), "P0001",
                "only on a change");
        refusedAlone(rawOn(loan, "DEFAULT", 2, "B1_1_29", null, true, businessDateSql(loan), 2), "P0001",
                "governing agreement");
        assertThat(rows(loan)).hasSize(1);
    }

    // ------------------------------------------------------------------ the world

    /** A fresh party's twelve-month loan, its first instalment billed due today - the suite's default terms (no collection). */
    static LoanId dueToday(long minor) {
        LoanId loan = disbursed(minor, 12);
        backdate(loan, daysTo(loan, 1));
        return loan;
    }

    /** {@code days} later in the loan's life, its servicing step. */
    static LoanServicing.Outcome day(LoanId loan, int days) {
        advance(loan, days);
        return service(loan);
    }

    /** The loan's condition as its owner's read derives it now - empty when not in servicing. */
    static Optional<DelinquencyState> read(LoanId loan) {
        UUID party = partyOf(loan);
        return inOneTransaction(uow -> LOANS.read(uow, loan, party)).orElseThrow().condition();
    }

    /**
     * A fresh line stating on {@code statementDate}'s day with {@code dueDays} payment-due days, opened twenty days before
     * it, 800.00 drawn two days later, serviced until caught up - every cycle stated, nothing collected.
     */
    static DisbursementWorld.Accepted statedLine(LocalDate statementDate, int dueDays) {
        DisbursementWorld.Accepted line = CreditLineStatementDatabaseTest.lines(1, 2_000_00,
                statementDate.getDayOfMonth(), dueDays, false).get(0);
        CreditLineStatementDatabaseTest.openOn(line, statementDate.minusDays(20));
        CreditLineStatementDatabaseTest.dateDraw(CreditLineStatementDatabaseTest.draw(line, 800_00),
                statementDate.minusDays(18));
        serviceUntilCaughtUp(line.loan());
        return line;
    }

    /** A draw through the wired T-drw (the real suspension), capital planted for it: true when it drew. */
    static boolean drawn(DisbursementWorld.Accepted line, long minor) {
        fund(eur(minor));
        return !CreditLineDrawDatabaseTest.draw(line, "p11t24-" + UUID.randomUUID(), minor).replayed();
    }

    /** The business date of a fixture naming no loan (the fixtures pin UTC). */
    static LocalDate fixtureToday() {
        return DisbursementWorld.businessDate();
    }

    record Row(String kind, LocalDate date, int dpd, String bucket, String previous, boolean defaulted) {}

    static List<Row> rows(LoanId loan) {
        List<Row> rows = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(
                "SELECT kind, business_date, days_past_due, bucket, previous_bucket, defaulted"
                        + " FROM lending.loan_condition_event WHERE loan_id = ? ORDER BY business_date, kind DESC")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    rows.add(new Row(row.getString(1), row.getObject(2, LocalDate.class), row.getInt(3), row.getString(4),
                            row.getString(5), row.getBoolean(6)));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return rows;
    }

    static Row last(LoanId loan) {
        List<Row> rows = rows(loan);
        return rows.stream().filter(row -> row.kind().equals("DELINQUENCY")).reduce((first, second) -> second)
                .orElseThrow();
    }

    static long eventsWith(LoanId loan, String type, String... fragments) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = ?");
        Object[] parameters = new Object[2 + fragments.length];
        parameters[0] = loan.value();
        parameters[1] = type;
        for (int i = 0; i < fragments.length; i++) {
            sql.append(" AND convert_from(payload, 'UTF8') LIKE ?");
            parameters[2 + i] = "%" + fragments[i] + "%";
        }
        return count(sql.toString(), parameters);
    }

    /** An application-role condition row of {@code loan} today, under agreement version 1. */
    static String raw(LoanId loan, String kind, int dpd, String bucket, String previous, boolean defaulted) {
        return rawOn(loan, kind, dpd, bucket, previous, defaulted, businessDateSql(loan), 1);
    }

    static String rawOn(LoanId loan, String kind, int dpd, String bucket, String previous, boolean defaulted,
            String dateSql, int agreementVersion) {
        return "INSERT INTO lending.loan_condition_event (id, loan_id, business_date, kind, days_past_due, bucket,"
                + " previous_bucket, defaulted, agreement_id, agreement_version, created_at, correlation_id)"
                + " SELECT gen_random_uuid(), a.loan_id, " + dateSql + ", '" + kind + "', " + dpd + ", '" + bucket + "', "
                + (previous == null ? "NULL" : "'" + previous + "'") + ", " + defaulted + ", a.id, " + agreementVersion
                + ", statement_timestamp(), 'p11t24-raw' FROM lending.loan_agreement a WHERE a.loan_id = '"
                + loan.value() + "' AND a.version = 1";
    }
}
