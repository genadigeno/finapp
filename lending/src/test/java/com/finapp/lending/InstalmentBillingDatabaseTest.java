package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.BILLING_STORE;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.TERMS_STORE;
import static com.finapp.lending.DisbursementWorld.acceptedLine;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.cashAtBank;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.disbursedExternally;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.InterestAccrualDatabaseTest.RATE;
import static com.finapp.lending.InterestAccrualDatabaseTest.SERVICING;
import static com.finapp.lending.InterestAccrualDatabaseTest.SERVICING_STORE;
import static com.finapp.lending.InterestAccrualDatabaseTest.backdate;
import static com.finapp.lending.InterestAccrualDatabaseTest.disbursed;
import static com.finapp.lending.InterestAccrualDatabaseTest.eur;
import static com.finapp.lending.InterestAccrualDatabaseTest.postFixture;
import static com.finapp.lending.InterestAccrualDatabaseTest.refused;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.InterestAccrualDatabaseTest.sweep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.lending.InstalmentBillingStore.BilledItem;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Instalment billing and the credit-balance application against a real database (`P11-TSK-017`; {@code lending V012},
 * {@code PHASE_11_PLAN.md} sections 7.1 T-svc, 12.2 step 5, 12.3, 12.5 and 12.8 rules 3 and 6; ADR-0094 point 6, ADR-0095
 * point 5; {@code INV-LND-01}, {@code INV-LND-03}, {@code INV-LND-04}, {@code INV-LND-10}, {@code INV-IDEM-02}): every
 * figure read from the rows and the ledger. Billed on the due date once; ten sweepers bill once; on an on-time path the
 * bills equal the projection to the minor unit; a late repayment raises the next interest due; the final instalment
 * clears principal after an irregular history; billing waits for every accrual of its period; a credit balance pays the
 * bill; the month-end clamp and the post-maturity months; and every rank held for every writer by raw SQL.
 *
 * <p>Loans are disbursed by the real T-dis and serviced by the real T-svc. An elapsed history is made by moving a loan's
 * dated facts into the past as the bootstrap owner with the triggers suspended ({@link InterestAccrualDatabaseTest}'s
 * {@code backdate}, and {@link #advance} here - every dated fact, the accrual and post-maturity keys re-dated with them -
 * so a step can run on each due date in turn): the fixture's clock moving, never a path the platform has. A repayment is
 * {@code -018}'s, so money arriving is an owner-role ledger fixture - a credit balance, or a late payment of a bill.
 */
@Tag("database")
@DisplayName("instalment billing and the credit-balance application (P11-TSK-017)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class InstalmentBillingDatabaseTest {

    // ------------------------------------------------------------------ once

    @Test
    @DisplayName("billed on the due date once: the day before nothing is billed (and a raw billing of a date not yet"
            + " come is refused for every writer); on the due date one billing - interest the period's accruals, the"
            + " principal the instalment less it - one entry dated the day under lending.billing:<instalment>, one"
            + " lending.InstalmentBilled; servicing again bills nothing more")
    void billedOnTheDueDateOnce() throws Exception {
        LoanId loan = disbursed(5_000_00, 12);
        LocalDate start = start(loan);
        LocalDate first = due(loan, 1);
        // The day before the due date: the period is accrued but nothing falls due.
        backdate(loan, Math.toIntExact(ChronoUnit.DAYS.between(start, first)) - 1);
        assertThat(service(loan).billed()).isZero();
        assertThat(billings(loan)).isEmpty();
        try (Connection app = DatabaseRoles.application()) {
            refused(app, rawBilling(loan, 1, 0, 0), "P0001", "due date has come");
        }
        // The due date.
        advance(loan, 1);
        LoanServicing.Outcome step = service(loan);
        assertThat(step.billed()).isEqualTo(1);
        assertThat(step.applied()).isZero();
        List<BilledItem> billed = billings(loan);
        assertThat(billed).hasSize(1);
        InstalmentBilling billing = billed.get(0).billing();
        assertThat(billing.kind()).isEqualTo(BillingKind.INSTALMENT);
        assertThat(billing.sequence()).isEqualTo(1);
        assertThat(billing.dueDate()).isEqualTo(today());
        long accrued = count("SELECT sum(posted_minor) FROM lending.interest_accrual WHERE loan_id = ? AND period_end = ?",
                loan.value(), billing.dueDate());
        assertThat(billing.interestDue().minorUnits()).as("interest actually accrued over the period").isEqualTo(accrued);
        long level = count("SELECT instalment_minor FROM lending.repayment_schedule WHERE loan_id = ?", loan.value());
        assertThat(billing.principalDue().minorUnits()).isEqualTo(level - accrued);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ? AND posting_date = ?"
                + " AND value_date = ? AND id = ?", "ledger.post:" + InstalmentBillings.POSTING_KEY_PREFIX
                + billing.instalment().orElseThrow(), today(), today(), billing.entry().orElseThrow().value()))
                .as("one entry dated the day it was billed, under the instalment's key").isEqualTo(1);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_DUE)).minorUnits()).isEqualTo(accrued);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE)).minorUnits()).isEqualTo(level - accrued);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_ACCRUED)).minorUnits()).isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?"
                + " AND convert_from(payload, 'UTF8') LIKE ?", loan.value(), InstalmentBillings.BILLED_EVENT,
                "%" + billing.id() + "%")).isEqualTo(1);

        LoanServicing.Outcome again = service(loan);
        assertThat(again.billed()).as("servicing again bills nothing more").isZero();
        assertThat(billings(loan)).hasSize(1);
        assertThat(billingEntries(loan)).isEqualTo(1);
    }

    @Test
    @DisplayName("ten sweepers bill once: three accounts past two due dates swept by ten racing sweepers - one billing"
            + " per instalment, one entry per key, one event per billing; ten racing steps on one account the same;"
            + " beneath L3 a second billing of an instalment or a due date is a unique violation and an entry already"
            + " under the key is a defect, never a second posting")
    void tenSweepersBillOnce() throws Exception {
        List<LoanId> three = List.of(disbursed(3_000_00, 12), disbursed(3_100_00, 12), disbursed(3_200_00, 12));
        for (LoanId each : three) {
            backdate(each, daysTo(each, 2));
        }
        race(10, index -> {
            sweep(SERVICING, Duration.ofMinutes(5));
            return null;
        });
        for (LoanId each : three) {
            assertBilledOnce(each, 2);
        }
        LoanId raced = disbursed(3_300_00, 12);
        backdate(raced, daysTo(raced, 2));
        List<LoanServicing.Outcome> outcomes = race(10, index -> service(raced));
        assertThat(outcomes.stream().mapToInt(LoanServicing.Outcome::billed).sum()).isEqualTo(2);
        assertBilledOnce(raced, 2);

        // Beneath L3: the uniques, with the triggers suspended.
        LoanId loan = three.get(0);
        String copy = "INSERT INTO lending.instalment_billing SELECT gen_random_uuid(), loan_id, %s, schedule_id,"
                + " schedule_version, %s, sequence, period_start, %s, currency, scale, interest_due_minor + 1, %s,"
                + " agreement_id, agreement_version, schedule_engine_version, accrual_engine_version, gen_random_uuid(),"
                + " created_at, correlation_id FROM lending.instalment_billing WHERE loan_id = '" + loan.value()
                + "' AND sequence = 1";
        assertUniqueViolation(() -> asOwner(String.format(copy, "kind", "instalment_id", "due_date + 400",
                "principal_due_minor")));
        assertUniqueViolation(() -> asOwner(String.format(copy, "'POST_MATURITY'", "NULL", "due_date", "0")));

        // Beneath L3: the ledger key. The second billing removed by the owner leaves its entry under the key; the step
        // that reaches the instalment again finds the key replayed - a defect, refused, nothing written.
        asOwner("DELETE FROM lending.instalment_billing WHERE loan_id = '" + loan.value() + "' AND sequence = 2");
        assertThatThrownBy(() -> service(loan)).isInstanceOf(IllegalStateException.class).hasMessageContaining("INV-LND-01");
        assertThat(billingEntries(loan)).as("the key's one entry, never a second").isEqualTo(2);
        assertThat(billings(loan)).hasSize(1);
    }

    // ------------------------------------------------------------------ what is billed

    @Test
    @DisplayName("on-time billing equals the projection: a six-month loan whose every bill is paid on its due date by a"
            + " credit balance held from the start, serviced on each due date in turn, is billed exactly the schedule's"
            + " interest and principal, to the minor unit; the principal billed over its life is its principal, every"
            + " bill paid, every receivable and the credit balance zero at the end")
    void onTimeBillingEqualsTheProjection() throws Exception {
        LoanId loan = disbursed(6_000_00, 6);
        List<long[]> projection = projection(loan);
        List<LocalDate> dues = dues(loan);
        long payable = projection.stream().mapToLong(row -> row[0] + row[1]).sum();
        backdate(loan, Math.toIntExact(ChronoUnit.DAYS.between(start(loan), dues.get(0))));
        credit(loan, payable, start(loan));
        for (int j = 0; j < dues.size(); j++) {
            if (j > 0) {
                advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(dues.get(j - 1), dues.get(j))));
            }
            LoanServicing.Outcome step = service(loan);
            assertThat(step.billed()).as("instalment " + (j + 1) + " billed on its due date").isEqualTo(1);
            assertThat(step.applied()).as("and paid at once by the credit balance").isEqualTo(1);
        }
        List<BilledItem> billed = billings(loan);
        assertThat(billed).hasSize(6);
        long principal = 0;
        for (int j = 0; j < 6; j++) {
            InstalmentBilling billing = billed.get(j).billing();
            assertThat(billing.interestDue().minorUnits()).as("interest of instalment " + (j + 1))
                    .isEqualTo(projection.get(j)[0]);
            assertThat(billing.principalDue().minorUnits()).as("principal of instalment " + (j + 1))
                    .isEqualTo(projection.get(j)[1]);
            assertThat(billed.get(j).outstanding().isZero()).as("paid").isTrue();
            principal += billing.principalDue().minorUnits();
        }
        assertThat(principal).as("the principal billed over the loan's life is its principal").isEqualTo(6_000_00L);
        for (AccountPurpose purpose : List.of(AccountPurpose.LOAN_PRINCIPAL, AccountPurpose.LOAN_PRINCIPAL_DUE,
                AccountPurpose.LOAN_INTEREST_DUE, AccountPurpose.LOAN_CREDIT_BALANCE)) {
            assertThat(netDebit(loanAccount(loan, purpose)).minorUnits()).as(purpose.name()).isZero();
        }
        assertSubledgerExplains(loan);
    }

    @Test
    @DisplayName("a late repayment raises the next interest due: instalment 1 unpaid on its due date and paid five days"
            + " late (an owner-role fixture - -018 re-asserts it through real repayments) - instalment 2 bills the"
            + " interest actually accrued, five days more on the whole principal, above the projection by exactly that,"
            + " and its principal portion lower by the same")
    void lateRepaymentRaisesTheNextInterestDue() throws Exception {
        LoanId loan = disbursed(8_000_00, 12);
        List<long[]> projection = projection(loan);
        List<LocalDate> dues = dues(loan);
        backdate(loan, Math.toIntExact(ChronoUnit.DAYS.between(start(loan), dues.get(0))));
        assertThat(service(loan).billed()).isEqualTo(1);
        InstalmentBilling first = billings(loan).get(0).billing();
        assertThat(first.interestDue().minorUnits()).isEqualTo(projection.get(0)[0]);
        advance(loan, 5);
        repaid(loan, first.interestDue().minorUnits(), first.principalDue().minorUnits(), today());
        int gap = Math.toIntExact(ChronoUnit.DAYS.between(dues.get(0), dues.get(1)));
        advance(loan, gap - 5);
        assertThat(service(loan).billed()).isEqualTo(1);
        InstalmentBilling second = billings(loan).get(1).billing();
        long principalAfter = 8_000_00L - projection.get(0)[1];
        BigDecimal exact = BigDecimal.valueOf(8_000_00L * 5 + principalAfter * (gap - 5), 2).multiply(RATE)
                .divide(BigDecimal.valueOf(365), 20, RoundingMode.HALF_EVEN);
        long expected = exact.setScale(2, RoundingMode.HALF_EVEN).movePointRight(2).longValueExact();
        assertThat(second.interestDue().minorUnits()).as("the interest actually accrued").isEqualTo(expected);
        assertThat(second.interestDue().minorUnits()).as("above the projection").isGreaterThan(projection.get(1)[0]);
        long level = count("SELECT instalment_minor FROM lending.repayment_schedule WHERE loan_id = ?", loan.value());
        assertThat(second.principalDue().minorUnits()).isEqualTo(level - expected);
        assertThat(second.principalDue().minorUnits()).isLessThan(projection.get(1)[1]);
    }

    @Test
    @DisplayName("the final instalment clears principal: a six-month loan never paid, caught up after its maturity -"
            + " every interest due above the projection, each principal portion A less it, the last all the principal"
            + " left; the principal billed over its life is its principal and LOAN_PRINCIPAL is zero")
    void theFinalInstalmentClearsPrincipal() throws Exception {
        LoanId loan = disbursed(4_500_00, 6);
        List<long[]> projection = projection(loan);
        backdate(loan, daysTo(loan, 6));
        serviceUntilCaughtUp(loan);
        List<BilledItem> billed = billings(loan);
        assertThat(billed).hasSize(6);
        long level = count("SELECT instalment_minor FROM lending.repayment_schedule WHERE loan_id = ?", loan.value());
        long principal = 0;
        for (int j = 0; j < 5; j++) {
            InstalmentBilling billing = billed.get(j).billing();
            assertThat(billing.principalDue().minorUnits()).isEqualTo(level - billing.interestDue().minorUnits());
            principal += billing.principalDue().minorUnits();
        }
        InstalmentBilling last = billed.get(5).billing();
        assertThat(last.principalDue().minorUnits()).as("all the principal left").isEqualTo(4_500_00L - principal);
        assertThat(last.principalDue().minorUnits()).as("above the projection's, every bill having been late")
                .isGreaterThan(projection.get(5)[1]);
        assertThat(principal + last.principalDue().minorUnits()).isEqualTo(4_500_00L);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits()).isZero();
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE)).minorUnits()).isEqualTo(4_500_00L);
        assertSubledgerExplains(loan);
        // Every rank: the final billing removed by the owner, a raw one billing a minor unit more principal than is left
        // is refused for every writer - and one billing other than the period's accrued interest too.
        long finalInterest = last.interestDue().minorUnits();
        asOwner("DELETE FROM lending.instalment_billing WHERE id = '" + last.id() + "'");
        try (Connection app = DatabaseRoles.application()) {
            refused(app, rawBilling(loan, 6, finalInterest, last.principalDue().minorUnits() + 1), "P0001",
                    "never exceeds its principal");
            refused(app, rawBilling(loan, 6, finalInterest + 1, last.principalDue().minorUnits()), "P0001",
                    "accrued interest");
        }
    }

    @Test
    @DisplayName("billing waits for every accrual of its period: a catch-up stopped at its bound bills only the due dates"
            + " whose periods it reached - a due date already come waits for the next step - and a date inside a period"
            + " already billed never accrues afterwards, for every writer")
    void billingWaitsForEveryAccrualOfThePeriod() throws Exception {
        LoanId loan = disbursed(7_000_00, 12);
        List<LocalDate> dues = dues(loan);
        LocalDate start = start(loan);
        // The first due date whose period ends beyond the first step's bound, and the history long enough for it to come.
        LocalDate bound = start.plusDays(LoanServicing.MAX_ACCRUALS_PER_STEP);
        int waiting = 0;
        while (!dues.get(waiting).isAfter(bound)) {
            waiting++;
        }
        backdate(loan, Math.toIntExact(ChronoUnit.DAYS.between(start, dues.get(waiting))));
        LoanServicing.Outcome first = service(loan);
        assertThat(first.caughtUp()).isFalse();
        assertThat(first.billed()).as("only the due dates whose periods the bounded catch-up reached").isEqualTo(waiting);
        assertThat(billings(loan)).hasSize(waiting);
        LoanServicing.Outcome second = service(loan);
        assertThat(second.caughtUp()).isTrue();
        assertThat(second.billed()).as("the waiting due date, once its period is accrued").isEqualTo(1);
        assertSubledgerExplains(loan);

        // For every writer: the period's last date removed by the owner, a raw accrual of it is refused - billed.
        LocalDate last = today().minusDays(1);
        asOwner("DELETE FROM lending.interest_accrual WHERE loan_id = '" + loan.value() + "' AND accrual_date = '" + last
                + "'");
        try (Connection app = DatabaseRoles.application()) {
            String agreement = scalar("SELECT id::text FROM lending.loan_agreement WHERE loan_id = ? AND version = 1",
                    loan.value());
            String period = scalar("SELECT period_start::text FROM lending.instalment_billing WHERE loan_id = ?"
                    + " AND due_date = ?", loan.value(), today());
            refused(app, "INSERT INTO lending.interest_accrual (id, loan_id, accrual_date, period_start, period_end,"
                    + " currency, scale, base_minor, cumulative_exact, posted_minor, agreement_id, agreement_version,"
                    + " accrual_engine_version, journal_entry_id, created_at, correlation_id) VALUES (gen_random_uuid(), '"
                    + loan.value() + "', '" + last + "', '" + period + "', '" + today() + "', 'EUR', 2, 100, 0, 0, '"
                    + agreement + "', 1, 1, NULL, statement_timestamp(), 'p11t17-raw')", "P0001", "already billed");
        }
    }

    // ------------------------------------------------------------------ the credit balance

    @Test
    @DisplayName("a credit balance pays the bill: held money below the bill pays its interest then part of its principal"
            + " (the pinned order), the rest stays due; above it, the bill is paid and the rest stays held; across two"
            + " due dates caught up at once, oldest first, one application per billing; the rows sum to each"
            + " application, rule 6's entry aggregates them per due account; a subledger that does not explain the"
            + " ledger is refused, nothing applied")
    void aCreditBalancePaysTheBill() throws Exception {
        // Below the bill.
        LoanId below = disbursed(5_500_00, 12);
        backdate(below, daysTo(below, 1));
        credit(below, 100_00, start(below));
        LoanServicing.Outcome step = service(below);
        assertThat(step.applied()).isEqualTo(1);
        BilledItem item = billings(below).get(0);
        long interest = item.billing().interestDue().minorUnits();
        assertThat(item.interestPaid().minorUnits()).as("interest first").isEqualTo(interest);
        assertThat(item.principalPaid().minorUnits()).isEqualTo(100_00 - interest);
        assertThat(item.outstanding().minorUnits()).isEqualTo(item.billing().billed().minorUnits() - 100_00);
        assertThat(netDebit(loanAccount(below, AccountPurpose.LOAN_CREDIT_BALANCE)).minorUnits()).isZero();
        assertThat(netDebit(loanAccount(below, AccountPurpose.LOAN_INTEREST_DUE)).minorUnits()).isZero();
        assertThat(count("SELECT count(*) FROM lending.credit_balance_allocation a JOIN lending.credit_balance_application c"
                + " ON c.id = a.application_id WHERE c.loan_id = ?", below.value())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:" + InstalmentBillings.APPLICATION_KEY_PREFIX + item.billing().id())).isEqualTo(1);
        assertThat(scalar("SELECT kind FROM lending.instalment_billing WHERE loan_id = ?", below.value()))
                .isEqualTo("INSTALMENT");
        assertSubledgerExplains(below);

        // Above the bill, across two due dates caught up in one step: each billing applies, oldest first.
        LoanId above = disbursed(5_600_00, 12);
        backdate(above, daysTo(above, 2));
        long held = 2_000_00;
        credit(above, held, start(above));
        LoanServicing.Outcome caughtUp = service(above);
        assertThat(caughtUp.billed()).isEqualTo(2);
        assertThat(caughtUp.applied()).isEqualTo(2);
        List<BilledItem> both = billings(above);
        long billedTotal = both.stream().mapToLong(each -> each.billing().billed().minorUnits()).sum();
        assertThat(both).allSatisfy(each -> assertThat(each.outstanding().isZero()).isTrue());
        assertThat(-netDebit(loanAccount(above, AccountPurpose.LOAN_CREDIT_BALANCE)).minorUnits())
                .as("the rest stays held").isEqualTo(held - billedTotal);
        assertThat(count("SELECT count(*) FROM lending.credit_balance_application WHERE loan_id = ?", above.value()))
                .as("one application per billing").isEqualTo(2);
        assertSubledgerExplains(above);
        // The schedule read: billed and paid status.
        LoanSchedules.Read read = inOneTransaction(uow -> DisbursementWorld.SCHEDULES.read(uow, above,
                partyOf(above), Optional.empty())).orElseThrow();
        assertThat(read.billed()).hasSize(2);
        Optional<LoanSchedules.Read> foreign = inOneTransaction(uow -> DisbursementWorld.SCHEDULES.read(uow, above,
                UUID.randomUUID(), Optional.empty()));
        assertThat(foreign).as("another party's loan").isEmpty();
        List<BilledItem> foreignItems = inOneTransaction(uow -> BILLING_STORE.billedItemsOwnedBy(uow, above,
                UUID.randomUUID()));
        assertThat(foreignItems).as("another party's billed items: the owner is a predicate in the statement").isEmpty();
        List<BilledItem> ownItems = inOneTransaction(uow -> BILLING_STORE.billedItemsOwnedBy(uow, above, partyOf(above)));
        assertThat(ownItems).hasSize(2);

        // A subledger that does not explain the ledger: refused before anything is applied.
        LoanId broken = disbursed(5_700_00, 12);
        backdate(broken, daysTo(broken, 1));
        credit(broken, 50_00, start(broken));
        postFixture("p11t17-unexplained:" + UUID.randomUUID(), start(broken), List.of(
                new JournalLine(loanAccount(broken, AccountPurpose.LOAN_INTEREST_DUE), Direction.DEBIT, eur(1_00)),
                new JournalLine(cashAtBank(), Direction.CREDIT, eur(1_00))));
        assertThatThrownBy(() -> service(broken)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INV-LND-01");
        assertThat(billings(broken)).as("the whole step rolled back").isEmpty();
        assertThat(count("SELECT count(*) FROM lending.credit_balance_application WHERE loan_id = ?", broken.value()))
                .isZero();
    }

    @Test
    @DisplayName("the application's rows are held for every writer: an application whose rows do not sum to it, or that"
            + " pays a billed component beyond what it billed, is refused at commit; a fee - which a billing never"
            + " bills - cannot be paid; the rows are immutable")
    void theApplicationIsHeldForEveryWriter() throws Exception {
        LoanId loan = disbursed(5_800_00, 12);
        backdate(loan, daysTo(loan, 1));
        credit(loan, 10_00, start(loan));
        service(loan);
        String billing = scalar("SELECT id::text FROM lending.instalment_billing WHERE loan_id = ?", loan.value());
        String agreement = scalar("SELECT id::text FROM lending.loan_agreement WHERE loan_id = ? AND version = 1",
                loan.value());
        long interest = count("SELECT interest_due_minor FROM lending.instalment_billing WHERE id = ?::uuid", billing);
        try (Connection app = DatabaseRoles.application()) {
            // A second application at the billing: the unique.
            refused(app, application(loan, billing, agreement, 1_00, List.of("INTEREST:1")), "23505", "");
        }
        // Rows that do not sum, an over-paid component and a fee: each refused at commit (or at once).
        LoanId fresh = disbursedAndBilled();
        String freshBilling = scalar("SELECT id::text FROM lending.instalment_billing WHERE loan_id = ?", fresh.value());
        String freshAgreement = scalar("SELECT id::text FROM lending.loan_agreement WHERE loan_id = ? AND version = 1",
                fresh.value());
        long freshInterest = count("SELECT interest_due_minor FROM lending.instalment_billing WHERE id = ?::uuid",
                freshBilling);
        try (Connection app = DatabaseRoles.application()) {
            refused(app, application(fresh, freshBilling, freshAgreement, 2_00, List.of("INTEREST:1")), "P0001",
                    "sum to it");
            refused(app, application(fresh, freshBilling, freshAgreement, freshInterest + 1,
                    List.of("INTEREST:" + (freshInterest + 1))), "P0001", "beyond what it billed");
            refused(app, application(fresh, freshBilling, freshAgreement, 1, List.of("FEES:1")), "P0001",
                    "beyond what it billed");
            refused(app, "UPDATE lending.credit_balance_allocation SET amount_minor = amount_minor WHERE application_id"
                    + " IN (SELECT id FROM lending.credit_balance_application WHERE loan_id = '" + loan.value() + "')",
                    "42501", "");
            refused(app, "UPDATE lending.instalment_billing SET interest_due_minor = interest_due_minor WHERE id = '"
                    + billing + "'", "42501", "");
            // An allocation paying another loan's billed item.
            refused(app, application(fresh, freshBilling, freshAgreement, 1, List.of("INTEREST:1"), billing), "P0001",
                    "its application's own loan");
        }
        assertThat(interest).isPositive();
    }

    // ------------------------------------------------------------------ the calendar

    @Test
    @DisplayName("billing at the month-end clamp: a schedule on the 31st (31 January, 28 February, 31 March, 30 April"
            + " ...) caught up after its maturity bills each instalment exactly its own period's accruals - February's"
            + " 28 days, March's 31 - then each post-maturity month on the clamped calendar continued, interest only;"
            + " the principal billed is the principal and the accrued account holds only the open period")
    void billingAtTheMonthEndClamp() throws Exception {
        LoanId loan = disbursed(6_200_00, 6);
        LocalDate now = today();
        int year = now.getMonthValue() >= 8 ? now.getYear() : now.getYear() - 1;
        reproject(loan, LocalDate.of(year, 1, 10), 31);
        serviceUntilCaughtUp(loan);
        List<BilledItem> billed = billings(loan);
        List<LocalDate> expected = new ArrayList<>();
        for (int month = 1; ; month++) {
            LocalDate end = LocalDate.of(year, 1, 1).plusMonths(month - 1L);
            LocalDate due = end.withDayOfMonth(end.lengthOfMonth());
            if (due.isAfter(now)) {
                break;
            }
            expected.add(due);
        }
        assertThat(billed.stream().map(each -> each.billing().dueDate()).toList()).as("every due date come, clamped")
                .containsExactlyElementsOf(expected);
        assertThat(billed.get(1).billing().period().days()).as("February").isEqualTo(
                LocalDate.of(year, 2, 1).lengthOfMonth());
        for (BilledItem each : billed) {
            InstalmentBilling billing = each.billing();
            assertThat(billing.interestDue().minorUnits()).as("the period's accruals, " + billing.dueDate())
                    .isEqualTo(count("SELECT coalesce(sum(posted_minor), 0) FROM lending.interest_accrual WHERE loan_id = ?"
                            + " AND period_start = ? AND period_end = ?", loan.value(), billing.period().start(),
                            billing.dueDate()));
            assertThat(billing.kind()).isEqualTo(billing.sequence() <= 6 ? BillingKind.INSTALMENT : BillingKind.POST_MATURITY);
            if (billing.kind() == BillingKind.POST_MATURITY) {
                assertThat(billing.principalDue().isZero()).isTrue();
                assertThat(billing.interestDue().isPositive()).as("interest on the principal still outstanding").isTrue();
                assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                        "ledger.post:" + InstalmentBillings.POSTING_KEY_PREFIX + loan.value() + ":" + billing.dueDate()))
                        .isEqualTo(1);
            }
        }
        assertThat(billed.stream().mapToLong(each -> each.billing().principalDue().minorUnits()).sum())
                .isEqualTo(6_200_00L);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_ACCRUED)).minorUnits())
                .as("only the open period's accruals are not yet due")
                .isEqualTo(count("SELECT coalesce(sum(posted_minor), 0) FROM lending.interest_accrual WHERE loan_id = ?"
                        + " AND period_end > ?", loan.value(), billed.get(billed.size() - 1).billing().dueDate()));
        assertSubledgerExplains(loan);
    }

    // ------------------------------------------------------------------ nothing to bill

    @Test
    @DisplayName("nothing is billed without a schedule or for a line: a path X loan awaiting its payout and a credit line"
            + " are serviced and billed nothing; a crash mid-billing rolls the whole step back, and the restarted step"
            + " bills once")
    void nothingIsBilledWithoutAScheduleAndACrashRollsBack() throws Exception {
        DisbursementWorld.Accepted external = disbursedExternally(2_000_00, 12);
        assertThat(service(external.loan()).billed()).isZero();
        LoanId line = acceptedLine(2_000_00).loan();
        InterestAccrualDatabaseTest.backdateOpening(line, 70);
        assertThat(service(line).billed()).isZero();
        assertThat(count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id IN (?, ?)",
                external.loan().value(), line.value())).isZero();

        LoanId crashed = disbursed(2_100_00, 12);
        backdate(crashed, daysTo(crashed, 2));
        AtomicInteger inserts = new AtomicInteger();
        InstalmentBillings crashing = new InstalmentBillings(new CrashingBillingStore(BILLING_STORE, inserts, 2),
                DisbursementWorld.LEDGER_ACCOUNTS, DisbursementWorld.BALANCES, DisbursementWorld.POSTINGS,
                new com.finapp.platform.outbox.JdbcOutboxWriter(), IDS, DisbursementWorld.CLOCK);
        LoanServicing crashingServicing = new LoanServicing(DisbursementWorld.LOAN_STORE, SERVICING_STORE, TERMS_STORE,
                DisbursementWorld.LEDGER_ACCOUNTS, DisbursementWorld.BALANCES, DisbursementWorld.POSTINGS, IDS, crashing);
        assertThatThrownBy(() -> InterestAccrualDatabaseTest.service(crashingServicing, crashed)).hasMessageContaining("crash");
        assertThat(billings(crashed)).isEmpty();
        assertThat(billingEntries(crashed)).isZero();
        assertThat(count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?", crashed.value()))
                .as("the accruals of the step rolled back with it").isZero();
        assertThat(service(crashed).billed()).isEqualTo(2);
        assertBilledOnce(crashed, 2);
    }

    // ------------------------------------------------------------------ the world

    static LocalDate today() {
        return LocalDate.parse(scalar("SELECT lending.business_date(statement_timestamp(), 'UTC')::text"));
    }

    static LocalDate start(LoanId loan) {
        return LocalDate.parse(scalar("SELECT start_date::text FROM lending.accrual_start WHERE loan_id = ?", loan.value()));
    }

    static LocalDate due(LoanId loan, int sequence) {
        return LocalDate.parse(scalar("SELECT due_date::text FROM lending.loan_instalment WHERE loan_id = ? AND sequence = ?",
                loan.value(), sequence));
    }

    static List<LocalDate> dues(LoanId loan) {
        List<LocalDate> dues = new ArrayList<>();
        long n = count("SELECT count(*) FROM lending.loan_instalment WHERE loan_id = ?", loan.value());
        for (int j = 1; j <= n; j++) {
            dues.add(due(loan, j));
        }
        return dues;
    }

    /** The days from the accrual start to instalment {@code sequence}'s due date - backdated by it, that date is today. */
    static int daysTo(LoanId loan, int sequence) {
        return Math.toIntExact(ChronoUnit.DAYS.between(start(loan), due(loan, sequence)));
    }

    /** The projection: each instalment's interest and principal, by sequence. */
    static List<long[]> projection(LoanId loan) {
        List<long[]> rows = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(
                "SELECT interest_minor, principal_minor FROM lending.loan_instalment WHERE loan_id = ? ORDER BY sequence")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    rows.add(new long[] {row.getLong(1), row.getLong(2)});
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return rows;
    }

    static List<BilledItem> billings(LoanId loan) {
        return inOneTransaction(uow -> BILLING_STORE.billedItems(uow, loan));
    }

    static long billingEntries(LoanId loan) {
        return count("SELECT count(*) FROM ledger.journal_entry e JOIN lending.loan_instalment i"
                + " ON e.idempotency_scope = 'ledger.post:" + InstalmentBillings.POSTING_KEY_PREFIX + "' || i.id"
                + " WHERE i.loan_id = ?", loan.value());
    }

    static UUID partyOf(LoanId loan) {
        return UUID.fromString(scalar("SELECT party_id::text FROM lending.loan WHERE id = ?", loan.value()));
    }

    /** Services {@code loan} until its catch-up is complete. */
    static void serviceUntilCaughtUp(LoanId loan) {
        for (int step = 0; step < 30; step++) {
            if (service(loan).caughtUp()) {
                return;
            }
        }
        throw new AssertionError("the catch-up never completed");
    }

    /** Money held for the borrower - the owner's fixture standing in for -018's overpayment, dated {@code date}. */
    static void credit(LoanId loan, long minor, LocalDate date) {
        postFixture("p11t17-credit:" + UUID.randomUUID(), date, List.of(
                new JournalLine(cashAtBank(), Direction.DEBIT, eur(minor)),
                new JournalLine(loanAccount(loan, AccountPurpose.LOAN_CREDIT_BALANCE), Direction.CREDIT, eur(minor))));
    }

    /** A bill paid - the owner's ledger fixture standing in for -018's repayment, dated {@code date}. */
    static void repaid(LoanId loan, long interest, long principal, LocalDate date) {
        postFixture("p11t17-repaid:" + UUID.randomUUID(), date, List.of(
                new JournalLine(cashAtBank(), Direction.DEBIT, eur(interest + principal)),
                new JournalLine(loanAccount(loan, AccountPurpose.LOAN_INTEREST_DUE), Direction.CREDIT, eur(interest)),
                new JournalLine(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE), Direction.CREDIT, eur(principal))));
    }

    /** One billing per instalment through {@code instalments}, one entry under each key, one event per billing. */
    static void assertBilledOnce(LoanId loan, int instalments) {
        List<BilledItem> billed = billings(loan);
        assertThat(billed).hasSize(instalments);
        assertThat(billed.stream().map(each -> each.billing().sequence()).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, instalments).boxed().toList());
        assertThat(billingEntries(loan)).isEqualTo(instalments);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                loan.value(), InstalmentBillings.BILLED_EVENT)).isEqualTo(instalments);
        assertSubledgerExplains(loan);
    }

    /**
     * The subledger proof's billing arms (`P11-TSK-029` gauges them): LOAN_INTEREST_DUE and LOAN_PRINCIPAL_DUE are the
     * billings less the allocations; LOAN_INTEREST_ACCRUED the accruals less the billings; LOAN_PRINCIPAL the principal
     * less the billings (no principal moves otherwise here).
     */
    static void assertSubledgerExplains(LoanId loan) {
        UUID id = loan.value();
        long allocatedInterest = count("SELECT coalesce(sum(a.amount_minor), 0) FROM lending.credit_balance_allocation a"
                + " JOIN lending.instalment_billing b ON b.id = a.billing_id WHERE b.loan_id = ? AND a.component = 'INTEREST'",
                id);
        long allocatedPrincipal = count("SELECT coalesce(sum(a.amount_minor), 0) FROM lending.credit_balance_allocation a"
                + " JOIN lending.instalment_billing b ON b.id = a.billing_id WHERE b.loan_id = ? AND a.component = 'PRINCIPAL'",
                id);
        long billedInterest = count("SELECT coalesce(sum(interest_due_minor), 0) FROM lending.instalment_billing"
                + " WHERE loan_id = ?", id);
        long billedPrincipal = count("SELECT coalesce(sum(principal_due_minor), 0) FROM lending.instalment_billing"
                + " WHERE loan_id = ?", id);
        long accrued = count("SELECT coalesce(sum(posted_minor), 0) FROM lending.interest_accrual WHERE loan_id = ?", id);
        long repaidInterest = fixtureCredits(loan, AccountPurpose.LOAN_INTEREST_DUE);
        long repaidPrincipal = fixtureCredits(loan, AccountPurpose.LOAN_PRINCIPAL_DUE);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_DUE)).minorUnits())
                .isEqualTo(billedInterest - allocatedInterest - repaidInterest);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE)).minorUnits())
                .isEqualTo(billedPrincipal - allocatedPrincipal - repaidPrincipal);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_ACCRUED)).minorUnits())
                .isEqualTo(accrued - billedInterest);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits())
                .isEqualTo(count("SELECT amount_minor FROM lending.loan_agreement WHERE loan_id = ? AND version = 1", id)
                        - billedPrincipal);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_CREDIT_BALANCE)).minorUnits())
                .as("the credit balance never debit")
                .isLessThanOrEqualTo(0L);
    }

    /** What the suite's own repayment fixtures credited to {@code purpose} - never a platform path. */
    private static long fixtureCredits(LoanId loan, AccountPurpose purpose) {
        return count("SELECT coalesce(sum(l.amount_minor), 0) FROM ledger.journal_line l JOIN ledger.journal_entry e"
                + " ON e.id = l.entry_id WHERE l.ledger_account_id = ? AND l.direction = 'CREDIT'"
                + " AND e.idempotency_scope LIKE 'ledger.post:p11t17-repaid:%'", loanAccount(loan, purpose).value());
    }

    /**
     * Moves every dated fact of {@code loan} {@code days} further into the past - its journal entries' dates, the
     * disbursement, the accrual start, the schedule, the accruals, the billings, the opening instant - and re-dates the
     * keys that carry a date (the accruals', a post-maturity billing's) with them: the fixture's clock moving, so the next
     * step runs {@code days} later in the loan's life. Never a path the platform has.
     */
    static void advance(LoanId loan, int days) {
        UUID id = loan.value();
        String dated = "substring(%1$s from '(\\d{4}-\\d{2}-\\d{2})#?$')::date";
        asOwner("UPDATE ledger.journal_entry SET posting_date = posting_date - " + days + ", value_date = value_date - "
                + days + " WHERE id IN (SELECT l.entry_id FROM ledger.journal_line l JOIN ledger.ledger_account a"
                + " ON a.id = l.ledger_account_id WHERE a.owner_ref = '" + id + "');"
                + " UPDATE ledger.journal_entry SET idempotency_scope = 'ledger.post:lending.accrual:" + id + ":'"
                + " || value_date WHERE idempotency_scope LIKE 'ledger.post:lending.accrual:" + id + ":%';"
                + " UPDATE ledger.journal_entry SET idempotency_scope = 'ledger.post:lending.billing:" + id + ":' || ("
                + String.format(dated, "idempotency_scope") + " - " + days + ") WHERE idempotency_scope LIKE"
                + " 'ledger.post:lending.billing:" + id + ":%';"
                + " UPDATE platform.idempotency_record SET idempotency_key = idempotency_key || '#' WHERE scope = 'ledger.post'"
                + " AND (idempotency_key LIKE 'lending.accrual:" + id + ":%' OR idempotency_key LIKE 'lending.billing:" + id
                + ":%');"
                + " UPDATE platform.idempotency_record SET idempotency_key = regexp_replace(idempotency_key,"
                + " '\\d{4}-\\d{2}-\\d{2}#$', '') || (" + String.format(dated, "idempotency_key") + " - " + days
                + ")::text WHERE scope = 'ledger.post' AND idempotency_key LIKE '%#' AND (idempotency_key LIKE"
                + " 'lending.accrual:" + id + ":%' OR idempotency_key LIKE 'lending.billing:" + id + ":%');"
                + " UPDATE lending.loan_disbursement SET value_date = value_date - " + days + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.accrual_start SET start_date = start_date - " + days + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.repayment_schedule SET start_date = start_date - " + days + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.loan_instalment SET period_start = period_start - " + days + ", due_date = due_date - "
                + days + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.interest_accrual SET accrual_date = accrual_date - " + days + ", period_start ="
                + " period_start - " + days + ", period_end = period_end - " + days + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.instalment_billing SET period_start = period_start - " + days + ", due_date = due_date - "
                + days + " WHERE loan_id = '" + id + "';"
                + " UPDATE lending.loan SET opened_at = opened_at - interval '" + days + " days' WHERE id = '" + id + "'");
    }

    /**
     * Re-projects {@code loan}'s schedule version 1 from {@code start} on {@code intendedDay} by the pinned engine, and
     * moves its disbursement and accrual start there - the fixture standing in for a loan disbursed on {@code start}
     * with that repayment day (a uniform backdate cannot keep a monthly calendar on its day).
     */
    static void reproject(LoanId loan, LocalDate start, int intendedDay) {
        UUID id = loan.value();
        LoanTerms pinned = inOneTransaction(uow -> TERMS_STORE.version(uow, LoanTermsVersionId.of(UUID.fromString(scalar(
                "SELECT terms_version_id::text FROM lending.loan_agreement WHERE loan_id = ?", id)))).orElseThrow().terms());
        long principal = count("SELECT amount_minor FROM lending.loan_agreement WHERE loan_id = ? AND version = 1", id);
        int term = Math.toIntExact(count("SELECT term_months FROM lending.loan_agreement WHERE loan_id = ? AND version = 1",
                id));
        Schedule schedule = ((ScheduleOutcome.Projected) ScheduleEngineV1.project(pinned, eur(principal), term,
                intendedDay, start)).schedule();
        String scheduleId = scalar("SELECT id::text FROM lending.repayment_schedule WHERE loan_id = ?", id);
        StringBuilder sql = new StringBuilder("DELETE FROM lending.loan_instalment WHERE loan_id = '" + id + "';");
        for (SchedulePeriod period : schedule.periods()) {
            sql.append(" INSERT INTO lending.loan_instalment VALUES (gen_random_uuid(), '").append(scheduleId).append("', '")
                    .append(id).append("', ").append(period.sequence()).append(", '").append(period.periodStart())
                    .append("', '").append(period.dueDate()).append("', ").append(period.days()).append(", 'EUR', 2, ")
                    .append(period.openingBalance().minorUnits()).append(", ").append(period.interest().minorUnits())
                    .append(", ").append(period.principal().minorUnits()).append(", ").append(period.amount().minorUnits())
                    .append(", ").append(period.closingBalance().minorUnits()).append(");");
        }
        sql.append(" UPDATE lending.repayment_schedule SET start_date = '").append(start).append("', intended_day = ")
                .append(intendedDay).append(", instalment_minor = ").append(schedule.instalment().minorUnits())
                .append(", final_instalment_minor = ").append(schedule.finalInstalment().minorUnits())
                .append(", total_interest_minor = ").append(schedule.totalInterest().minorUnits())
                .append(" WHERE loan_id = '").append(id).append("';");
        sql.append(" UPDATE ledger.journal_entry SET posting_date = '").append(start).append("', value_date = '")
                .append(start).append("' WHERE id = (SELECT journal_entry_id FROM lending.loan_disbursement WHERE loan_id = '")
                .append(id).append("');");
        sql.append(" UPDATE lending.loan_disbursement SET value_date = '").append(start).append("' WHERE loan_id = '")
                .append(id).append("';");
        sql.append(" UPDATE lending.accrual_start SET start_date = '").append(start).append("' WHERE loan_id = '")
                .append(id).append("';");
        sql.append(" UPDATE lending.loan SET opened_at = '").append(start).append("'::timestamp AT TIME ZONE 'UTC'")
                .append(" WHERE id = '").append(id).append("'");
        asOwner(sql.toString());
    }

    /** An application-role insert of a billing of instalment {@code sequence} of {@code loan}, as stated. */
    static String rawBilling(LoanId loan, int sequence, long interest, long principal) {
        return "INSERT INTO lending.instalment_billing (id, loan_id, kind, schedule_id, schedule_version, instalment_id,"
                + " sequence, period_start, due_date, currency, scale, interest_due_minor, principal_due_minor, agreement_id,"
                + " agreement_version, schedule_engine_version, accrual_engine_version, journal_entry_id, created_at,"
                + " correlation_id) SELECT gen_random_uuid(), i.loan_id, 'INSTALMENT', s.id, 1, i.id, i.sequence,"
                + " i.period_start, i.due_date, 'EUR', 2, " + interest + ", " + principal + ", a.id, 1, 1, 1, "
                + (interest + principal > 0 ? "gen_random_uuid()" : "NULL") + ","
                + " statement_timestamp(), 'p11t17-raw' FROM lending.loan_instalment i JOIN lending.repayment_schedule s"
                + " ON s.id = i.schedule_id JOIN lending.loan_agreement a ON a.loan_id = i.loan_id AND a.version = 1"
                + " WHERE i.loan_id = '" + loan.value() + "' AND i.sequence = " + sequence;
    }

    /** A raw application of {@code amount} at {@code billing}, with lines {@code COMPONENT:minor}, in one transaction. */
    static String application(LoanId loan, String billing, String agreement, long amount, List<String> lines) {
        return application(loan, billing, agreement, amount, lines, billing);
    }

    /** As above, its lines paying {@code paid} - another billing, for the refusal. */
    static String application(LoanId loan, String billing, String agreement, long amount, List<String> lines,
            String paid) {
        String application = UUID.randomUUID().toString();
        StringBuilder sql = new StringBuilder("BEGIN; INSERT INTO lending.credit_balance_application (id, billing_id,"
                + " loan_id, currency, scale, amount_minor, agreement_id, agreement_version, allocation_engine_version,"
                + " journal_entry_id, created_at, correlation_id) VALUES ('" + application + "', '" + billing + "', '"
                + loan.value() + "', 'EUR', 2, " + amount + ", '" + agreement + "', 1, 1, gen_random_uuid(),"
                + " statement_timestamp(), 'p11t17-raw');");
        int ordinal = 0;
        for (String line : lines) {
            String[] parts = line.split(":");
            sql.append(" INSERT INTO lending.credit_balance_allocation VALUES (gen_random_uuid(), '").append(application)
                    .append("', ").append(++ordinal).append(", '").append(paid).append("', '").append(parts[0])
                    .append("', 'EUR', 2, ").append(parts[1]).append(");");
        }
        return sql.append(" COMMIT;").toString();
    }

    /** A fresh loan billed its first instalment, no credit held. */
    static LoanId disbursedAndBilled() {
        LoanId loan = disbursed(2_400_00, 12);
        backdate(loan, daysTo(loan, 1));
        assertThat(service(loan).billed()).isEqualTo(1);
        return loan;
    }

    static void assertUniqueViolation(Runnable insert) {
        assertThatThrownBy(insert::run)
                .hasCauseInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo("23505"));
    }

    /** A store whose {@code crashAt}-th billing insert crashes - after its posting, inside the step's transaction. */
    static final class CrashingBillingStore implements InstalmentBillingStore {

        private final InstalmentBillingStore delegate;
        private final AtomicInteger inserts;
        private final int crashAt;

        CrashingBillingStore(InstalmentBillingStore delegate, AtomicInteger inserts, int crashAt) {
            this.delegate = delegate;
            this.inserts = inserts;
            this.crashAt = crashAt;
        }

        @Override
        public Optional<BillableSchedule> latestSchedule(Connection unitOfWork, LoanId loan) {
            return delegate.latestSchedule(unitOfWork, loan);
        }

        @Override
        public PeriodAccruals periodAccruals(Connection unitOfWork, LoanId loan, AccrualPeriod period, Money zero) {
            return delegate.periodAccruals(unitOfWork, loan, period, zero);
        }

        @Override
        public List<BilledItem> billedItems(Connection unitOfWork, LoanId loan) {
            return delegate.billedItems(unitOfWork, loan);
        }

        @Override
        public List<BilledItem> billedItemsOwnedBy(Connection unitOfWork, LoanId loan, UUID party) {
            return delegate.billedItemsOwnedBy(unitOfWork, loan, party);
        }

        @Override
        public void insertBilling(Connection unitOfWork, InstalmentBilling billing, CorrelationId correlation) {
            if (inserts.incrementAndGet() == crashAt) {
                throw new IllegalStateException("a crash mid-billing");
            }
            delegate.insertBilling(unitOfWork, billing, correlation);
        }

        @Override
        public void insertApplication(Connection unitOfWork, CreditBalanceApplication application,
                CorrelationId correlation) {
            delegate.insertApplication(unitOfWork, application, correlation);
        }
    }
}
