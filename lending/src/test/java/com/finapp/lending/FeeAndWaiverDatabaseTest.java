package com.finapp.lending;

import static com.finapp.lending.DelinquencyDatabaseTest.day;
import static com.finapp.lending.DelinquencyDatabaseTest.dueToday;
import static com.finapp.lending.DelinquencyDatabaseTest.fixtureToday;
import static com.finapp.lending.DelinquencyDatabaseTest.read;
import static com.finapp.lending.DelinquencyDatabaseTest.statedLine;
import static com.finapp.lending.DisbursementWorld.BALANCES;
import static com.finapp.lending.DisbursementWorld.BILLING_STORE;
import static com.finapp.lending.DisbursementWorld.CLOCK;
import static com.finapp.lending.DisbursementWorld.CLOSURES;
import static com.finapp.lending.DisbursementWorld.FEE_STORE;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.LOAN_STORE;
import static com.finapp.lending.DisbursementWorld.POSTINGS;
import static com.finapp.lending.DisbursementWorld.TERMS_STORE;
import static com.finapp.lending.DisbursementWorld.WALLETS;
import static com.finapp.lending.DisbursementWorld.activate;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.businessDate;
import static com.finapp.lending.DisbursementWorld.businessDateSql;
import static com.finapp.lending.DisbursementWorld.correlation;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.employee;
import static com.finapp.lending.DisbursementWorld.execute;
import static com.finapp.lending.DisbursementWorld.feeIncome;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.InstalmentBillingDatabaseTest.advance;
import static com.finapp.lending.InstalmentBillingDatabaseTest.billings;
import static com.finapp.lending.InstalmentBillingDatabaseTest.daysTo;
import static com.finapp.lending.InstalmentBillingDatabaseTest.dues;
import static com.finapp.lending.InstalmentBillingDatabaseTest.partyOf;
import static com.finapp.lending.InstalmentBillingDatabaseTest.serviceUntilCaughtUp;
import static com.finapp.lending.InterestAccrualDatabaseTest.backdate;
import static com.finapp.lending.InterestAccrualDatabaseTest.disbursed;
import static com.finapp.lending.InterestAccrualDatabaseTest.eur;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.RepaymentDatabaseTest.assertSubledgerProves;
import static com.finapp.lending.RepaymentDatabaseTest.due;
import static com.finapp.lending.RepaymentDatabaseTest.events;
import static com.finapp.lending.RepaymentDatabaseTest.held;
import static com.finapp.lending.RepaymentDatabaseTest.key;
import static com.finapp.lending.RepaymentDatabaseTest.loanStatus;
import static com.finapp.lending.RepaymentDatabaseTest.refusedAlone;
import static com.finapp.lending.RepaymentDatabaseTest.repay;
import static com.finapp.lending.RepaymentReversalDatabaseTest.ACTING;
import static com.finapp.lending.RepaymentReversalDatabaseTest.ACTING_PARTY;
import static com.finapp.lending.RepaymentReversalDatabaseTest.REVERSAL_SERVICE;
import static com.finapp.lending.RepaymentReversalDatabaseTest.UNPLACEABLE;
import static com.finapp.lending.RepaymentReversalDatabaseTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * Late fees and waivers against a real database (`P11-TSK-025`; {@code lending V021}, {@code PHASE_11_PLAN.md} sections 7.1
 * T-svc and T-wvr, 7.4, 12.3 and 12.8 rules 4, 8, 8a and 9; A14, A18, ADR-0099; {@code INV-LND-11}, {@code INV-IDEM-02},
 * {@code INV-REV-02}, {@code INV-REV-04}, {@code INV-AUD-04}, {@code INV-LND-08}).
 *
 * <p>Loans are disbursed by the real T-dis, serviced by the real T-svc - whose late-fee step runs the real {@link LateFees}
 * after the real condition step - and repaid by the real T-rep; waivers go through the real {@link Waivers} over the real
 * ledger, its {@link com.finapp.ledger.PostingService} and {@link com.finapp.ledger.ReversalService}. A later business date
 * is {@link InstalmentBillingDatabaseTest#advance} - the fixture's clock - and every planted date derives from the database
 * clock in the pinned zone. The acting party is {@link RepaymentReversalDatabaseTest}'s map. Every case on a loan closes on
 * the subledger proof's arms, fees and waivers included.
 */
@Tag("database")
@DisplayName("late fees and waivers (P11-TSK-025)")
class FeeAndWaiverDatabaseTest {

    static final JdbcWaiverStore WAIVER_STORE = new JdbcWaiverStore();

    static final Waivers WAIVERS = new Waivers(LOAN_STORE, WAIVER_STORE, FEE_STORE, BILLING_STORE, TERMS_STORE, WALLETS,
            LEDGER_ACCOUNTS, BALANCES, POSTINGS, REVERSAL_SERVICE, CLOSURES, ACTING_PARTY, new JdbcOutboxWriter(),
            new JdbcAuditWriter(), IDS, CLOCK);

    // ------------------------------------------------------------------ the fee

    @Test
    @DisplayName("assessed once at the late-fee day: an instalment due today and left unpaid - ages 1 to 4 assess"
            + " nothing; at age 5 (the pinned day) one fee of the pinned 15.00 on that item, dated the business date,"
            + " posting rule 4 (DR LOAN_FEES_DUE / CR LOAN_FEE_INCOME) under lending.fee:<item>:LATE, one"
            + " lending.LoanFeeAssessed; the step re-run that day and the next assesses nothing; the fee joins the item -"
            + " under an order paying fees last, the item's interest and principal paid and the fee not, it stays past due"
            + " until the fee is paid")
    void assessedOnceAtTheLateFeeDay() {
        LoanId loan = dueToday(5_000_00);
        service(loan);
        for (int age = 1; age <= 4; age++) {
            assertThat(day(loan, 1).fees()).as("age " + age).isZero();
        }
        assertThat(fees(loan)).isEmpty();
        assertThat(day(loan, 1).fees()).isEqualTo(1);
        FeeAssessment fee = fees(loan).get(0);
        UUID item = billings(loan).get(0).billing().id();
        assertThat(fee.dueItem().value()).isEqualTo(item);
        assertThat(fee.kind()).isEqualTo(FeeKind.LATE);
        assertThat(fee.amount()).isEqualTo(eur(15_00));
        assertThat(fee.businessDate()).isEqualTo(businessDate(loan));
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_FEES_DUE))).isEqualTo(eur(15_00));
        assertThat(lines("ledger.post:" + LateFees.POSTING_KEY_PREFIX + item + ":LATE")).containsExactlyInAnyOrder(
                loanAccount(loan, AccountPurpose.LOAN_FEES_DUE).value() + ":DEBIT:1500",
                feeIncome().value() + ":CREDIT:1500");
        assertThat(scalar("SELECT journal_entry_id::text FROM lending.fee_assessment WHERE id = ?", fee.id()))
                .isEqualTo(fee.entry().value().toString());
        assertThat(events(loan, LateFees.ASSESSED_EVENT)).isEqualTo(1);
        assertThat(billings(loan).get(0).feesBilled()).as("the fee joins its item").isEqualTo(eur(15_00));
        assertThat(service(loan).fees()).as("re-run that day").isZero();
        assertThat(day(loan, 1).fees()).as("the next day - once per item").isZero();
        assertThat(fees(loan)).hasSize(1);
        assertSubledgerProves(loan);

        // An unpaid fee keeps its item past due (-024's recommendation, adopted).
        LoanId feesLast;
        activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").allocationOrder(List.of(
                AllocationComponent.INTEREST, AllocationComponent.PRINCIPAL, AllocationComponent.FEES)).build());
        try {
            feesLast = dueToday(5_050_00);
        } finally {
            activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").build());
        }
        service(feesLast);
        advance(feesLast, 5);
        assertThat(service(feesLast).fees()).isEqualTo(1);
        repay(feesLast, key(), due(feesLast) - 15_00);
        assertThat(billings(feesLast).get(0).dueItem().interest().isZero()).isTrue();
        assertThat(billings(feesLast).get(0).dueItem().principal().isZero()).isTrue();
        assertThat(billings(feesLast).get(0).dueItem().fees()).isEqualTo(eur(15_00));
        assertThat(read(feesLast).orElseThrow().daysPastDue()).as("the unpaid fee keeps the item past due").isEqualTo(5);
        assertThat(count("SELECT lending.days_past_due(?, " + businessDateSql(feesLast) + ")", feesLast.value()))
                .as("the database's derivation agrees").isEqualTo(5);
        repay(feesLast, key(), 15_00);
        assertThat(read(feesLast).orElseThrow().daysPastDue()).as("the fee paid, cured").isZero();
        assertSubledgerProves(feesLast);
    }

    @Test
    @DisplayName("ten sweepers assess once: thirty servicing steps racing over three loans each at its fee day - L3"
            + " serialises them: one fee, one entry and one event per loan; a second fee of the item beneath every lock -"
            + " the triggers suspended - is refused by UNIQUE (due_item_id, kind)")
    void tenSweepersAssessOnce() throws Exception {
        List<LoanId> loans = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LoanId loan = dueToday(5_000_00 + i * 100_00);
            service(loan);
            advance(loan, 5);
            loans.add(loan);
        }
        List<Integer> assessed = race(30, index -> service(loans.get(index % 3)).fees());
        assertThat(assessed.stream().mapToInt(Integer::intValue).sum()).as("one fee per loan").isEqualTo(3);
        for (LoanId loan : loans) {
            assertThat(fees(loan)).hasSize(1);
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope LIKE ? AND reference = ?",
                    "ledger.post:" + LateFees.POSTING_KEY_PREFIX + "%", loan.value().toString())).isEqualTo(1);
            assertThat(events(loan, LateFees.ASSESSED_EVENT)).isEqualTo(1);
            assertSubledgerProves(loan);
        }
        LoanId first = loans.get(0);
        assertThatThrownBy(() -> asOwner("INSERT INTO lending.fee_assessment SELECT gen_random_uuid(), loan_id,"
                + " due_item_id, kind, business_date, currency, scale, amount_minor, agreement_id, agreement_version,"
                + " gen_random_uuid(), created_at, correlation_id FROM lending.fee_assessment WHERE loan_id = '"
                + first.value() + "'"))
                .rootCause().hasMessageContaining("fee_assessment_once_per_item");
        assertThat(fees(first)).hasSize(1);
    }

    @Test
    @DisplayName("the cap holds: under a 40.00 cap and a 15.00 fee, a loan left unpaid four months is charged 15.00,"
            + " 15.00 and then the 10.00 the cap admits (one capped) - then nothing, ever, however many items age; a raw"
            + " fee beyond the cap is refused by the trigger, for every writer")
    void theCapHolds() throws SQLException {
        LoanId loan;
        activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").lateFeeCap(eur(40_00)).build());
        try {
            loan = dueToday(5_000_00);
        } finally {
            activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").build());
        }
        service(loan);
        List<LocalDate> dues = dues(loan);
        advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(businessDate(loan), dues.get(3))) + 5);
        LoanServicing.Outcome last = service(loan);
        int assessed = last.fees();
        int capped = last.feesCapped();
        for (int step = 0; step < 30 && !last.caughtUp(); step++) {
            last = service(loan);
            assessed += last.fees();
            capped += last.feesCapped();
        }
        assertThat(last.caughtUp()).isTrue();
        assertThat(fees(loan)).extracting(fee -> fee.amount().minorUnits()).containsExactly(15_00L, 15_00L, 10_00L);
        assertThat(assessed).isEqualTo(3);
        assertThat(capped).as("the third fee is what the cap admits").isEqualTo(1);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_FEES_DUE))).isEqualTo(eur(40_00));
        assertThat(day(loan, 40).fees()).as("the cap reached assesses nothing").isZero();
        assertThat(fees(loan)).hasSize(3);
        UUID unassessed = billings(loan).get(3).billing().id();
        refusedAlone(rawFee(loan, unassessed, 1), "P0001", "cap");
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("grace delays the fee, never DPD: under seven days' grace and a late-fee day of 8, every day of grace is"
            + " past due - DPD 1 to 7, the condition recorded - and assesses nothing; a raw fee within grace is refused by"
            + " the trigger; the eighth day assesses the fee")
    void graceDelaysTheFee() throws SQLException {
        LoanId loan;
        activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").graceDays(7).lateFeeDay(8).build());
        try {
            loan = dueToday(5_000_00);
        } finally {
            activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").build());
        }
        service(loan);
        for (int age = 1; age <= 7; age++) {
            assertThat(day(loan, 1).fees()).as("age " + age + " - in grace").isZero();
            assertThat(read(loan).orElseThrow().daysPastDue()).as("grace never delays DPD").isEqualTo(age);
        }
        assertThat(DelinquencyDatabaseTest.last(loan).bucket()).isEqualTo("B1_1_29");
        refusedAlone(rawFee(loan, billings(loan).get(0).billing().id(), 15_00), "P0001", "late-fee day");
        assertThat(day(loan, 1).fees()).as("the late-fee day").isEqualTo(1);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("a line is charged and its statement carries its fees: a line whose minimum went unpaid six days past its"
            + " due date is charged the fee on that statement, which owes it (FEES billed); a statement of a cycle in which"
            + " a fee was assessed - less what a waiver forgave - carries the net figure into its minimum payment, and a"
            + " statement carrying any other figure is refused by the trigger")
    void aLineIsChargedAndItsStatementCarriesItsFees() throws SQLException {
        DisbursementWorld.Accepted line = statedLine(
                CreditLineStatementDatabaseTest.onOrBefore(fixtureToday().minusDays(25 + 6)), 25);
        List<FeeAssessment> charged = fees(line.loan());
        assertThat(charged).hasSize(1);
        CreditLineStatementStore.StatedItem oldest = CreditLineStatementDatabaseTest.statements(line).get(0);
        assertThat(charged.get(0).dueItem().value()).isEqualTo(oldest.statement().id());
        assertThat(oldest.feesBilled()).isEqualTo(eur(15_00));
        assertThat(oldest.dueItem().fees()).isEqualTo(eur(15_00));
        assertThat(netDebit(loanAccount(line.loan(), AccountPurpose.LOAN_FEES_DUE))).isEqualTo(eur(15_00));
        CreditLineStatementDatabaseTest.assertLineSubledgerProves(line, 0);

        // The carry: a fee assessed inside a cycle not yet stated (the owner's fixture standing in for an earlier day's
        // step), 5.00 of it waived; the real statement step then states the cycle.
        LocalDate statementDate = CreditLineStatementDatabaseTest.onOrBefore(fixtureToday().minusDays(2));
        DisbursementWorld.Accepted fresh = CreditLineStatementDatabaseTest.lines(1, 2_000_00,
                statementDate.getDayOfMonth(), 25, false).get(0);
        LocalDate opened = statementDate.minusDays(20);
        CreditLineStatementDatabaseTest.openOn(fresh, opened);
        UUID id = fresh.loan().value();
        UUID planted = IDS.next();
        asOwner("INSERT INTO lending.fee_assessment SELECT '" + planted + "', l.id, '" + IDS.next() + "', 'LATE', DATE '"
                + statementDate.minusDays(5) + "', 'EUR', 2, 1500, a.id, 1, '" + IDS.next() + "', statement_timestamp(),"
                + " 'p11t25-planted' FROM lending.loan l JOIN lending.loan_agreement a ON a.loan_id = l.id"
                + " WHERE l.id = '" + id + "';"
                + " INSERT INTO lending.waiver SELECT gen_random_uuid(), f.loan_id, 'FEE', f.id, f.due_item_id, 'EUR', 2,"
                + " 500, 1, 'p11t25-proposer', 'EMPLOYEE', statement_timestamp(), 'planted', 'p11t25-planted'"
                + " FROM lending.fee_assessment f WHERE f.id = '" + planted + "';"
                + " INSERT INTO lending.waiver_decision SELECT gen_random_uuid(), w.id, w.proposed_by, 'APPROVED',"
                + " 'p11t25-approver', 'EMPLOYEE', statement_timestamp(), 'planted', gen_random_uuid(), 'p11t25-planted'"
                + " FROM lending.waiver w WHERE w.fee_assessment_id = '" + planted + "'");
        String agreement = scalar("SELECT id::text FROM lending.loan_agreement WHERE loan_id = ?", id);
        try (Connection app = DatabaseRoles.application()) {
            InterestAccrualDatabaseTest.refused(app, CreditLineStatementDatabaseTest.rawStatement(id, agreement, opened,
                    statementDate, 0), "P0001", "carries exactly");
        }
        com.finapp.sharedkernel.money.Money carried = inOneTransaction(uow -> DisbursementWorld.STATEMENT_STORE
                .feesCarriedIn(uow, fresh.loan(), new AccrualPeriod(opened, statementDate), eur(0)));
        assertThat(carried).as("the store's read, the trigger's sum").isEqualTo(eur(10_00));
        serviceUntilCaughtUp(fresh.loan());
        Statement stated = CreditLineStatementDatabaseTest.statements(fresh).get(0).statement().statement();
        assertThat(stated.feesCarried()).as("the cycle's fees, less the waiver").isEqualTo(eur(10_00));
        assertThat(stated.minimumPayment())
                .isEqualTo(stated.interestBilled().plus(stated.feesCarried()).plus(stated.principalBilled()));
        assertThat(count("SELECT fees_carried_minor FROM lending.credit_line_statement WHERE loan_id = ?", id))
                .isEqualTo(10_00);
    }

    // ------------------------------------------------------------------ the waivers

    @Test
    @DisplayName("a fee waiver reverses only the unpaid remainder: 10.00 of the 15.00 fee paid - a waiver of 15.00 is"
            + " 422 WaiverExceedsDue and never born; 5.00 proposed and approved by another operator - rule 8, the"
            + " ReversalService of the fee's own entry for 5.00 (reverses_entry_id the assessment's), LOAN_FEES_DUE zero,"
            + " the item's fees billed 10.00 and paid 10.00; one lending.LoanFeeWaived, the reasoned audit trail; nothing"
            + " more can be waived")
    void aFeeWaiverReversesOnlyTheUnpaidRemainder() {
        LoanId loan = feeAssessed(5_000_00);
        FeeAssessment fee = fees(loan).get(0);
        repay(loan, key(), 10_00);
        assertThat(billings(loan).get(0).feesPaid()).as("fees first by the pinned order").isEqualTo(eur(10_00));
        assertThatExceptionOfType(Waivers.WaiverExceedsDue.class)
                .isThrownBy(() -> propose(loan, WaiverKind.FEE, fee.id(), 15_00, employee()));
        assertThat(count("SELECT count(*) FROM lending.waiver WHERE loan_id = ?", loan.value())).isZero();

        Waiver proposal = propose(loan, WaiverKind.FEE, fee.id(), 5_00, employee());
        assertThat(proposal.status()).isEqualTo(WaiverStatus.PROPOSED);
        Waivers.Decided decided = approve(loan, proposal.id(), employee());
        Waiver approved = decided.waiver();
        assertThat(approved.status()).isEqualTo(WaiverStatus.APPROVED);
        UUID entry = approved.journalEntry().orElseThrow().value();
        assertThat(scalar("SELECT reverses_entry_id::text FROM ledger.journal_entry WHERE id = ?", entry))
                .as("rule 8: a reversal of the fee's own entry").isEqualTo(fee.entry().value().toString());
        assertThat(scalar("SELECT idempotency_scope FROM ledger.journal_entry WHERE id = ?", entry))
                .isEqualTo("ledger.reverse:" + Waivers.WAIVER_KEY_PREFIX + approved.id());
        assertThat(entryLines(entry)).containsExactlyInAnyOrder(feeIncome().value() + ":DEBIT:500",
                loanAccount(loan, AccountPurpose.LOAN_FEES_DUE).value() + ":CREDIT:500");
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_FEES_DUE)).isZero()).isTrue();
        assertThat(billings(loan).get(0).feesBilled()).isEqualTo(eur(10_00));
        assertThat(billings(loan).get(0).dueItem().fees().isZero()).isTrue();
        assertThat(eventsWith(loan, Waivers.FEE_WAIVED_EVENT, "\"kind\":\"FEE\"")).isEqualTo(1);
        assertThat(audits(proposal.id(), "lending.WaiverProposed")).isEqualTo(1);
        assertThat(audits(proposal.id(), "lending.WaiverApproved")).isEqualTo(1);
        assertThatExceptionOfType(Waivers.WaiverExceedsDue.class)
                .isThrownBy(() -> propose(loan, WaiverKind.FEE, fee.id(), 1, employee()));
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("a waiver of a paid fee refunds to the credit balance: the fee paid in full - a fee waiver of 0.01 is"
            + " WaiverExceedsDue; a refund of 15.01 too; a refund of 15.00 approved - rule 8a, DR LOAN_FEE_INCOME / CR"
            + " LOAN_CREDIT_BALANCE under lending.waiver:<waiver>, the credit balance up by 15.00, lending.LoanFeeWaived"
            + " (FEE_REFUND); a second refund of anything exceeds; the loan stays open")
    void aWaiverOfAPaidFeeRefundsToTheCreditBalance() {
        LoanId loan = feeAssessed(5_100_00);
        FeeAssessment fee = fees(loan).get(0);
        repay(loan, key(), due(loan));
        assertThat(billings(loan).get(0).feesPaid()).isEqualTo(eur(15_00));
        long heldBefore = held(loan);
        assertThatExceptionOfType(Waivers.WaiverExceedsDue.class)
                .isThrownBy(() -> propose(loan, WaiverKind.FEE, fee.id(), 1, employee()));
        assertThatExceptionOfType(Waivers.WaiverExceedsDue.class)
                .isThrownBy(() -> propose(loan, WaiverKind.FEE_REFUND, fee.id(), 15_01, employee()));
        Waiver refund = approve(loan, propose(loan, WaiverKind.FEE_REFUND, fee.id(), 15_00, employee()).id(), employee())
                .waiver();
        UUID entry = refund.journalEntry().orElseThrow().value();
        assertThat(scalar("SELECT idempotency_scope FROM ledger.journal_entry WHERE id = ?", entry))
                .isEqualTo("ledger.post:" + Waivers.WAIVER_KEY_PREFIX + refund.id());
        assertThat(entryLines(entry)).containsExactlyInAnyOrder(feeIncome().value() + ":DEBIT:1500",
                loanAccount(loan, AccountPurpose.LOAN_CREDIT_BALANCE).value() + ":CREDIT:1500");
        assertThat(held(loan)).isEqualTo(heldBefore + 15_00);
        assertThat(eventsWith(loan, Waivers.FEE_WAIVED_EVENT, "\"kind\":\"FEE_REFUND\"")).isEqualTo(1);
        assertThatExceptionOfType(Waivers.WaiverExceedsDue.class)
                .isThrownBy(() -> propose(loan, WaiverKind.FEE_REFUND, fee.id(), 1, employee()));
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("an interest waiver posts explicitly: the billed item's interest I - a waiver of I + 0.01 exceeds; of I,"
            + " approved - rule 9, DR LOAN_INTEREST_INCOME / CR LOAN_INTEREST_DUE, the item's interest waived and owing"
            + " nothing of it, lending.LoanInterestWaived; accrued interest untouched; an item of another loan, or no item,"
            + " is not found")
    void anInterestWaiverPostsExplicitly() {
        LoanId loan = dueToday(5_200_00);
        service(loan);
        InstalmentBillingStore.BilledItem item = billings(loan).get(0);
        long interest = item.billing().interestDue().minorUnits();
        assertThat(interest).isPositive();
        long accruedBefore = netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_ACCRUED)).minorUnits();
        assertThatExceptionOfType(Waivers.WaiverExceedsDue.class)
                .isThrownBy(() -> propose(loan, WaiverKind.INTEREST, item.billing().id(), interest + 1, employee()));
        Waiver waived = approve(loan, propose(loan, WaiverKind.INTEREST, item.billing().id(), interest, employee()).id(),
                employee()).waiver();
        UUID entry = waived.journalEntry().orElseThrow().value();
        assertThat(entryLines(entry)).containsExactlyInAnyOrder(
                interestIncome().value() + ":DEBIT:" + interest,
                loanAccount(loan, AccountPurpose.LOAN_INTEREST_DUE).value() + ":CREDIT:" + interest);
        assertThat(billings(loan).get(0).interestWaived()).isEqualTo(eur(interest));
        assertThat(billings(loan).get(0).dueItem().interest().isZero()).isTrue();
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_DUE)).isZero()).isTrue();
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_ACCRUED)).minorUnits())
                .as("accrued interest is never waived").isEqualTo(accruedBefore);
        assertThat(events(loan, Waivers.INTEREST_WAIVED_EVENT)).isEqualTo(1);
        LoanId other = dueToday(5_250_00);
        service(other);
        assertThatExceptionOfType(Waivers.WaiverNotFound.class).isThrownBy(() -> propose(loan, WaiverKind.INTEREST,
                billings(other).get(0).billing().id(), 1, employee()));
        assertThatExceptionOfType(Waivers.WaiverNotFound.class)
                .isThrownBy(() -> propose(loan, WaiverKind.INTEREST, UUID.randomUUID(), 1, employee()));
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("an interest waiver takes the shared interest-income row before the wallet (P11-DOC-001): with"
            + " LOAN_INTEREST_INCOME's balance row held by another session, the approval waits on exactly that row - its"
            + " statement the projection's FOR UPDATE - holding nothing of the borrower's wallet (a third session takes it"
            + " FOR UPDATE NOWAIT); released, it approves once. The order the servicing step's accrual, the payoff and the"
            + " prepayment take it in ahead of their wallet, so no cycle runs through two accounts of one party")
    void anInterestWaiverTakesTheIncomeRowBeforeTheWallet() throws Exception {
        LoanId loan = dueToday(5_150_00);
        service(loan);
        InstalmentBillingStore.BilledItem item = billings(loan).get(0);
        long interest = item.billing().interestDue().minorUnits();
        Waiver proposal = propose(loan, WaiverKind.INTEREST, item.billing().id(), interest, employee());
        com.finapp.ledger.LedgerAccountId wallet = DisbursementWorld.walletOf(partyOf(loan));
        ExecutorService approver = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            execute(holder, "SELECT 1 FROM ledger.account_balance WHERE ledger_account_id = '" + interestIncome().value()
                    + "' FOR UPDATE");
            Future<Waivers.Decided> approval = approver.submit(() -> approve(loan, proposal.id(), employee()));
            DisbursementWorld.Blocked waiting = DisbursementWorld.awaitBlockedBy(DisbursementWorld.pid(holder));
            assertThat(waiting.query()).as("the approval waits on the income row's projection lock")
                    .contains("ledger.account_balance").contains("FOR UPDATE");
            try (Connection third = DatabaseRoles.application()) {
                third.setAutoCommit(false);
                execute(third, "SELECT 1 FROM ledger.ledger_account WHERE id = '" + wallet.value() + "' FOR UPDATE NOWAIT");
                third.rollback();
            }
            holder.rollback();
            assertThat(approval.get(1, TimeUnit.MINUTES).waiver().status()).isEqualTo(WaiverStatus.APPROVED);
        } finally {
            approver.shutdownNow();
        }
        assertThat(decisions(proposal.id())).isEqualTo(1);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_DUE)).isZero()).isTrue();
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("ten approvers, one waiver: ten different operators race to approve one proposal - one approves, nine"
            + " 409 ProposalNotPending; one entry, one event, one decision; the winner's retry converges and writes"
            + " nothing; a second proposal of the kind on the item while one awaits is ProposalPending, and of ten proposers"
            + " racing on one item one proposes and nine are ProposalPending")
    void tenApproversOneWaiver() throws Exception {
        LoanId loan = feeAssessed(5_300_00);
        FeeAssessment fee = fees(loan).get(0);
        Waiver proposal = propose(loan, WaiverKind.FEE, fee.id(), 15_00, employee());
        assertThatExceptionOfType(Waivers.ProposalPending.class)
                .isThrownBy(() -> propose(loan, WaiverKind.FEE, fee.id(), 1_00, employee()));
        List<String> outcomes = race(10, index -> {
            try {
                return approve(loan, proposal.id(), employee()).replayed() ? "REPLAYED" : "APPROVED";
            } catch (Waivers.ProposalNotPending decided) {
                return "NOT_PENDING";
            }
        });
        assertThat(outcomes).filteredOn("APPROVED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("NOT_PENDING"::equals).hasSize(9);
        assertWaivedOnce(loan, proposal.id());
        Actor winner = new Actor(scalar("SELECT decided_by FROM lending.waiver_decision WHERE waiver_id = ?",
                proposal.id()), ActorType.EMPLOYEE);
        assertThat(approve(loan, proposal.id(), winner).replayed()).as("the same approver's retry converges").isTrue();
        assertWaivedOnce(loan, proposal.id());

        // Ten proposers of one kind on one item: the decision-pending rule beneath L3 (the trigger takes the lock itself).
        UUID item = billings(loan).get(0).billing().id();
        List<String> proposals = race(10, index -> {
            try {
                propose(loan, WaiverKind.INTEREST, item, 1_00, employee());
                return "PROPOSED";
            } catch (Waivers.ProposalPending pending) {
                return "PENDING";
            }
        });
        assertThat(proposals).filteredOn("PROPOSED"::equals).hasSize(1);
        assertThat(proposals).filteredOn("PENDING"::equals).hasSize(9);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("self-approval refused at both ranks: the proposer's approval 403 SelfApprovalRefused, nothing written;"
            + " a raw self-approval refused by the four-eyes CHECK, while the same raw approval by another decider passes"
            + " it (rolled back); a decision copying another proposer refused by the composite key; reasons screened for"
            + " instrument shapes by the domain and by the database; the proposer may withdraw it")
    void selfApprovalRefusedAtBothRanks() throws SQLException {
        LoanId loan = feeAssessed(5_400_00);
        FeeAssessment fee = fees(loan).get(0);
        Actor proposer = employee();
        Waiver proposal = propose(loan, WaiverKind.FEE, fee.id(), 15_00, proposer);
        assertThatExceptionOfType(Waivers.SelfApprovalRefused.class)
                .isThrownBy(() -> approve(loan, proposal.id(), proposer));
        assertThat(decisions(proposal.id())).isZero();
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_FEES_DUE))).isEqualTo(eur(15_00));

        refusedAlone(rawDecision(proposal.id(), "w.proposed_by", "w.proposed_by"), "23514", "waiver_decision_four_eyes");
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "BEGIN; " + rawDecision(proposal.id(), "w.proposed_by", "'another-decider'") + "; ROLLBACK;");
        }
        refusedAlone(rawDecision(proposal.id(), "'not-the-proposer'", "'another-decider'"), "23503",
                "waiver_decision_waiver_fk");
        assertThat(decisions(proposal.id())).isZero();

        assertThatExceptionOfType(LendingCapitalAdministration.ReasonRequired.class).isThrownBy(() -> as(employee(), c ->
                inOneTransaction(uow -> WAIVERS.propose(uow, loan, WaiverKind.INTEREST,
                        billings(loan).get(0).billing().id(), eur(1_00), "card 4111 1111 1111 1111 waived", employee(), c))));
        refusedAlone("INSERT INTO lending.waiver SELECT gen_random_uuid(), loan_id, 'INTEREST', NULL, due_item_id, 'EUR', 2,"
                + " 100, 1, 'raw', 'EMPLOYEE', now(), 'to DE89 3704 0044 0532 0130 00', 'p11t25-raw' FROM lending.fee_assessment"
                + " WHERE id = '" + fee.id() + "'", "23514", "no_instrument_shape");
        Waiver withdrawn = reject(loan, proposal.id(), proposer).waiver();
        assertThat(withdrawn.status()).isEqualTo(WaiverStatus.REJECTED);
        assertThat(audits(proposal.id(), "lending.WaiverRejected")).isEqualTo(1);
        assertThatExceptionOfType(Waivers.ProposalNotPending.class)
                .isThrownBy(() -> approve(loan, proposal.id(), employee()));
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("a waiver racing a repayment of the fee: a repayment of everything due that commits before the approval"
            + " makes it 422 WaiverExceedsDue - nothing posted, the proposal still PROPOSED - and the paid fee is then"
            + " refunded instead; raced on three more loans in either order, the fee is waived or paid, never both, and"
            + " LOAN_FEES_DUE never goes below zero")
    void waiverRacingARepaymentOfTheFee() throws Exception {
        LoanId loan = feeAssessed(5_500_00);
        FeeAssessment fee = fees(loan).get(0);
        Waiver proposal = propose(loan, WaiverKind.FEE, fee.id(), 15_00, employee());
        repay(loan, key(), due(loan));
        assertThatExceptionOfType(Waivers.WaiverExceedsDue.class)
                .isThrownBy(() -> approve(loan, proposal.id(), employee()));
        assertThat(decisions(proposal.id())).as("still PROPOSED").isZero();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope LIKE ?",
                "%" + Waivers.WAIVER_KEY_PREFIX + proposal.id())).as("nothing posted").isZero();
        // Beneath the domain: a raw approval of the same proposal, for every writer, meets the bound in the trigger.
        refusedAlone(rawDecision(proposal.id(), "w.proposed_by", "'raw-approver'"), "P0001", "bounded by what is due");
        assertThat(decisions(proposal.id())).isZero();
        reject(loan, proposal.id(), employee());
        approve(loan, propose(loan, WaiverKind.FEE_REFUND, fee.id(), 15_00, employee()).id(), employee());
        assertSubledgerProves(loan);

        for (int round = 0; round < 3; round++) {
            LoanId raced = feeAssessed(5_600_00 + round * 100_00);
            FeeAssessment raceFee = fees(raced).get(0);
            Waiver racing = propose(raced, WaiverKind.FEE, raceFee.id(), 15_00, employee());
            long owed = due(raced);
            List<String> outcomes = race(2, index -> {
                if (index == 0) {
                    try {
                        approve(raced, racing.id(), employee());
                        return "WAIVED";
                    } catch (Waivers.WaiverExceedsDue exceeds) {
                        return "EXCEEDS";
                    }
                }
                repay(raced, key(), owed);
                return "REPAID";
            });
            assertThat(outcomes).contains("REPAID");
            InstalmentBillingStore.BilledItem item = billings(raced).get(0);
            long waived = outcomes.contains("WAIVED") ? 15_00 : 0;
            assertThat(waived + item.feesPaid().minorUnits()).as("the fee waived or paid, never both").isEqualTo(15_00);
            assertThat(netDebit(loanAccount(raced, AccountPurpose.LOAN_FEES_DUE)).isZero()).isTrue();
            assertSubledgerProves(raced);
        }
    }

    @Test
    @DisplayName("a waiver of the last receivable closes: a two-instalment loan whose every instalment is billed and its"
            + " interest and principal paid (fees last by its order), the late fee alone owed - the approved fee waiver"
            + " closes it REPAID in the same transaction, all six balances zero, one lending.LoanClosed - and then takes no"
            + " waiver and, from any writer, no fee; raced against a"
            + " repayment of the fee on another such loan, exactly one of them closes it and the other is refused")
    void aWaiverOfTheLastReceivableCloses() throws Exception {
        LoanId loan = onlyTheFeeOwed(2_000_00);
        FeeAssessment fee = fees(loan).get(0);
        Waivers.Decided decided = approve(loan, propose(loan, WaiverKind.FEE, fee.id(), 15_00, employee()).id(),
                employee());
        assertThat(decided.closure()).isPresent();
        assertThat(decided.closure().get().reason()).isEqualTo(ClosureReason.REPAID);
        assertThat(loanStatus(loan)).isEqualTo("CLOSED");
        assertEverythingZero(loan);
        assertThat(events(loan, LoanClosures.CLOSED_EVENT)).isEqualTo(1);
        assertSubledgerProves(loan);
        // Closed is closed (INV-LND-08): no waiver is proposed on it, and no fee is assessed on it by any writer.
        assertThatExceptionOfType(Waivers.LoanClosed.class)
                .isThrownBy(() -> propose(loan, WaiverKind.FEE_REFUND, fee.id(), 1, employee()));
        refusedAlone(rawFee(loan, billings(loan).get(0).billing().id(), 1), "P0001", "ACTIVE or CLOSING");

        LoanId raced = onlyTheFeeOwed(2_100_00);
        FeeAssessment raceFee = fees(raced).get(0);
        Waiver racing = propose(raced, WaiverKind.FEE, raceFee.id(), 15_00, employee());
        List<String> outcomes = race(2, index -> {
            if (index == 0) {
                try {
                    return approve(raced, racing.id(), employee()).closure().isPresent() ? "WAIVER_CLOSED" : "WAIVED";
                } catch (Waivers.LoanClosed | Waivers.WaiverExceedsDue lost) {
                    return "WAIVER_REFUSED";
                }
            }
            try {
                return repay(raced, key(), 15_00).closure().isPresent() ? "REPAYMENT_CLOSED" : "REPAID";
            } catch (Repayments.LoanNotRepayable lost) {
                return "REPAYMENT_REFUSED";
            }
        });
        assertThat(outcomes).containsAnyOf("WAIVER_CLOSED", "REPAYMENT_CLOSED");
        assertThat(outcomes).containsAnyOf("WAIVER_REFUSED", "REPAYMENT_REFUSED");
        assertThat(count("SELECT count(*) FROM lending.loan_closure WHERE loan_id = ?", raced.value())).isEqualTo(1);
        assertThat(loanStatus(raced)).isEqualTo("CLOSED");
        assertEverythingZero(raced);
        assertSubledgerProves(raced);
    }

    @Test
    @DisplayName("an employee never waives on their own party's loan: an insider's proposal, approval and rejection each"
            + " SelfDealingRefused before anything is written, the attempt recordable FAILED naming the act; an operator"
            + " the platform cannot place in a party is refused too (fail closed)")
    void anEmployeeNeverWaivesOnTheirOwnPartysLoan() {
        LoanId loan = feeAssessed(5_700_00);
        FeeAssessment fee = fees(loan).get(0);
        Actor insider = employee();
        ACTING.put(insider.id(), partyOf(loan));
        RepaymentReversals.SelfDealingRefused refused = null;
        try {
            propose(loan, WaiverKind.FEE, fee.id(), 15_00, insider);
        } catch (RepaymentReversals.SelfDealingRefused selfDealing) {
            refused = selfDealing;
        }
        assertThat(refused).as("the insider's proposal refused").isNotNull();
        RepaymentReversals.SelfDealingRefused attempt = refused;
        assertThat(count("SELECT count(*) FROM lending.waiver WHERE loan_id = ?", loan.value())).isZero();
        inOneTransaction(uow -> {
            WAIVERS.recordRefusal(uow, attempt, insider, correlation());
            return null;
        });
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.SelfDealingRefused'"
                + " AND outcome = 'FAILED' AND target_id = ? AND actor_id = ? AND change_summary LIKE '%act=waiver:propose%'",
                loan.value().toString(), insider.id())).isEqualTo(1);

        Waiver proposal = propose(loan, WaiverKind.FEE, fee.id(), 15_00, employee());
        assertThatExceptionOfType(RepaymentReversals.SelfDealingRefused.class)
                .isThrownBy(() -> approve(loan, proposal.id(), insider));
        assertThatExceptionOfType(RepaymentReversals.SelfDealingRefused.class)
                .isThrownBy(() -> reject(loan, proposal.id(), insider));
        Actor nobody = employee();
        UNPLACEABLE.add(nobody.id());
        assertThatExceptionOfType(RepaymentReversals.SelfDealingRefused.class)
                .isThrownBy(() -> approve(loan, proposal.id(), nobody));
        assertThat(decisions(proposal.id())).isZero();
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_FEES_DUE))).isEqualTo(eur(15_00));
    }

    // ------------------------------------------------------------------ the world

    /** A fresh twelve-month loan whose first instalment, unpaid, reached the late-fee day today: one fee assessed. */
    static LoanId feeAssessed(long minor) {
        LoanId loan = dueToday(minor);
        service(loan);
        advance(loan, 5);
        assertThat(service(loan).fees()).isEqualTo(1);
        return loan;
    }

    /**
     * A fresh two-instalment loan under terms paying principal first and fees last: the first instalment paid on its due
     * date; the final one's principal paid on its due date (so nothing accrues after it - no principal outstanding), its
     * interest left five days and charged the late fee, then the interest paid - only the fee owed.
     */
    static LoanId onlyTheFeeOwed(long minor) {
        LoanId loan;
        activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").allocationOrder(List.of(
                AllocationComponent.PRINCIPAL, AllocationComponent.INTEREST, AllocationComponent.FEES)).build());
        try {
            loan = disbursed(minor, 2);
        } finally {
            activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").build());
        }
        backdate(loan, daysTo(loan, 1));
        RepaymentDatabaseTest.topUp(partyOf(loan), 500_00);
        service(loan);
        repay(loan, key(), due(loan));
        List<LocalDate> dues = dues(loan);
        advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(dues.get(0), dues.get(1))));
        assertThat(service(loan).billed()).isEqualTo(1);
        repay(loan, key(), billings(loan).get(1).billing().principalDue().minorUnits());
        advance(loan, 5);
        assertThat(service(loan).fees()).as("the interest still owed at the late-fee day").isEqualTo(1);
        repay(loan, key(), due(loan) - 15_00);
        assertThat(due(loan)).as("only the fee owed").isEqualTo(15_00);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).isZero()).isTrue();
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        return loan;
    }

    static List<FeeAssessment> fees(LoanId loan) {
        return inOneTransaction(uow -> FEE_STORE.ofLoan(uow, loan));
    }

    static Waiver propose(LoanId loan, WaiverKind kind, UUID target, long minor, Actor actor) {
        return as(actor, correlation -> inOneTransaction(uow -> WAIVERS.propose(uow, loan, kind, target, eur(minor),
                "goodwill after a documented branch outage", actor, correlation)));
    }

    static Waivers.Decided approve(LoanId loan, UUID waiver, Actor actor) {
        return as(actor, correlation -> inOneTransaction(uow ->
                WAIVERS.approve(uow, loan, waiver, "checked against the outage log", actor, correlation)));
    }

    static Waivers.Decided reject(LoanId loan, UUID waiver, Actor actor) {
        return as(actor, correlation -> inOneTransaction(uow ->
                WAIVERS.reject(uow, loan, waiver, "not warranted", actor, correlation)));
    }

    private static void assertWaivedOnce(LoanId loan, UUID waiver) {
        assertThat(decisions(waiver)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.reverse:" + Waivers.WAIVER_KEY_PREFIX + waiver)).isEqualTo(1);
        assertThat(eventsWith(loan, Waivers.FEE_WAIVED_EVENT, waiver.toString())).isEqualTo(1);
        assertThat(audits(waiver, "lending.WaiverApproved")).isEqualTo(1);
    }

    private static void assertEverythingZero(LoanId loan) {
        for (AccountPurpose purpose : List.of(AccountPurpose.LOAN_PRINCIPAL, AccountPurpose.LOAN_PRINCIPAL_DUE,
                AccountPurpose.LOAN_INTEREST_ACCRUED, AccountPurpose.LOAN_INTEREST_DUE, AccountPurpose.LOAN_FEES_DUE,
                AccountPurpose.LOAN_CREDIT_BALANCE)) {
            assertThat(netDebit(loanAccount(loan, purpose)).isZero()).as(purpose + " zero").isTrue();
        }
    }

    private static long decisions(UUID waiver) {
        return count("SELECT count(*) FROM lending.waiver_decision WHERE waiver_id = ?", waiver);
    }

    private static long audits(UUID target, String action) {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND target_id = ?", action,
                target.toString());
    }

    private static long eventsWith(LoanId loan, String type, String fragment) {
        return count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?"
                + " AND convert_from(payload, 'UTF8') LIKE ?", loan.value(), type, "%" + fragment + "%");
    }

    private static com.finapp.ledger.LedgerAccountId interestIncome() {
        return inOneTransaction(uow -> LEDGER_ACCOUNTS.findOperational(uow, AccountPurpose.LOAN_INTEREST_INCOME,
                DisbursementWorld.EUR).orElseThrow().id());
    }

    /** The lines of the entry posted under {@code scope}, as account:direction:amount. */
    private static List<String> lines(String scope) {
        return entryLines(UUID.fromString(scalar("SELECT id::text FROM ledger.journal_entry WHERE idempotency_scope = ?",
                scope)));
    }

    private static List<String> entryLines(UUID entry) {
        List<String> lines = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(
                "SELECT ledger_account_id, direction, amount_minor FROM ledger.journal_line WHERE entry_id = ?")) {
            select.setObject(1, entry);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    lines.add(row.getString(1) + ":" + row.getString(2) + ":" + row.getLong(3));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return lines;
    }

    /** An application-role late fee of {@code minor} on {@code item} of {@code loan} today, under agreement version 1. */
    static String rawFee(LoanId loan, UUID item, long minor) {
        return "INSERT INTO lending.fee_assessment (id, loan_id, due_item_id, kind, business_date, currency, scale,"
                + " amount_minor, agreement_id, agreement_version, journal_entry_id, created_at, correlation_id)"
                + " SELECT gen_random_uuid(), a.loan_id, '" + item + "', 'LATE', " + businessDateSql(loan) + ", 'EUR', 2, "
                + minor + ", a.id, 1, gen_random_uuid(), statement_timestamp(), 'p11t25-raw' FROM lending.loan_agreement a"
                + " WHERE a.loan_id = '" + loan.value() + "' AND a.version = 1";
    }

    /** An application-role decision approving {@code waiver}, copying {@code proposer} and decided by {@code decider}. */
    static String rawDecision(UUID waiver, String proposer, String decider) {
        return "INSERT INTO lending.waiver_decision (id, waiver_id, proposed_by, outcome, decided_by, decided_by_type,"
                + " decided_at, decision_reason, journal_entry_id, correlation_id) SELECT gen_random_uuid(), w.id, "
                + proposer + ", 'APPROVED', " + decider + ", 'EMPLOYEE', now(), 'raw', gen_random_uuid(), 'p11t25-raw'"
                + " FROM lending.waiver w WHERE w.id = '" + waiver + "'";
    }
}
