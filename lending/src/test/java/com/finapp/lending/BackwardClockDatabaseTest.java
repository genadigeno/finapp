package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.ADMINISTRATION;
import static com.finapp.lending.DisbursementWorld.APPLICATIONS;
import static com.finapp.lending.DisbursementWorld.CLOCK;
import static com.finapp.lending.DisbursementWorld.CREDIT;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.OFFER_STORE;
import static com.finapp.lending.DisbursementWorld.REPAYMENT_STORE;
import static com.finapp.lending.DisbursementWorld.SYSTEM;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.ensureActive;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.InstalmentBillingDatabaseTest.billings;
import static com.finapp.lending.InstalmentBillingDatabaseTest.daysTo;
import static com.finapp.lending.InstalmentBillingDatabaseTest.partyOf;
import static com.finapp.lending.InterestAccrualDatabaseTest.SERVICING;
import static com.finapp.lending.InterestAccrualDatabaseTest.SERVICING_STORE;
import static com.finapp.lending.InterestAccrualDatabaseTest.backdate;
import static com.finapp.lending.InterestAccrualDatabaseTest.disbursed;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The lending write path on a database clock stepped back (`P11-TSK-036`; ADR-0063 decision 2 and its follow-ups,
 * `INV-LND-10`). The test database's clock is corrected in steps of up to 1.7 s, so within ONE transaction a later
 * statement can read the clock earlier than an earlier statement did, and the transaction's start can stand after both.
 * {@link ClockStep} makes such a step on demand, between a step's own clock read and the writes it makes from it, through
 * the production code and the production triggers - so each case below is deterministic, never a wait:
 *
 * <ul>
 *   <li><strong>the offering instant</strong> - {@code lending V005}/{@code V006} refused an offer whose instant, read by
 *       an earlier statement, stood after the insert's clock (or before the transaction's start): the recurring
 *       "inserting a loan offer: SQLSTATE P0001" fixture failure. {@code lending V028} stamps it in the insert itself;
 *   <li><strong>the business dates</strong> - every "never after today" and "never before today" rank judged a date
 *       the step read from one clock reading against ANOTHER single reading ({@code statement_timestamp()} or
 *       {@code transaction_timestamp()}), which a step across a midnight in the pinned zone puts on different days. They
 *       now judge against the earliest and latest business dates the transaction read ({@code V028}'s two functions);
 *   <li><strong>the servicing permit</strong> - a sweep on a clock behind the permit an account was born with claims
 *       nothing until the clock passes it: a liveness delay of at most the step, kept (ADR-0063 decision 4) - and the
 *       billing suite's "no billing found" symptom, which its fixture now cannot meet.
 * </ul>
 */
@Tag("database")
@DisplayName("the lending write path on a database clock stepped back (P11-TSK-036)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class BackwardClockDatabaseTest {

    @BeforeAll
    static void installTheShadowClock() {
        ClockStep.install();
    }

    @AfterAll
    static void dropTheShadowClock() {
        ClockStep.uninstall();
    }

    // ------------------------------------------------------------------ the offering instant

    @Test
    @DisplayName("an origination on a clock stepped back after its read is offered: the offering instant the insert's own"
            + " - never before the transaction's start - the expiry, the permit and the illustration from it")
    void anOriginationSteppedBackAfterItsReadIsOffered() {
        DisbursementWorld.Approved approved = approvedLoan();
        LoanOrigination.Outcome outcome = ClockStep.inOneTransaction(Duration.ZERO, uow -> origination(
                ClockStep.afterRead(LoanOfferStore.class, OFFER_STORE, ClockStep.STEP)).originate(uow,
                approved.application(), SYSTEM));
        assertThat(outcome.kind()).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        assertTheOfferIsCoherent(approved);
    }

    @Test
    @DisplayName("an origination on a clock stepped back before its read is offered: the read before the transaction's"
            + " start, the offering instant held at the start")
    void anOriginationSteppedBackBeforeItsReadIsOffered() {
        DisbursementWorld.Approved approved = approvedLoan();
        LoanOrigination.Outcome outcome = ClockStep.inOneTransaction(Duration.ZERO, uow -> origination(
                ClockStep.beforeRead(LoanOfferStore.class, OFFER_STORE, ClockStep.STEP)).originate(uow,
                approved.application(), SYSTEM));
        assertThat(outcome.kind()).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        assertTheOfferIsCoherent(approved);
    }

    @Test
    @DisplayName("an origination whose read a step puts on the day before its transaction's start illustrates the offering"
            + " instant's date: the schedule's illustrative start is the business date of offered_at, never the read's")
    void anOriginationAcrossAMidnightIllustratesTheOfferingDate() {
        DisbursementWorld.Approved approved = approvedLoan();
        LoanOrigination.Outcome outcome = ClockStep.inOneTransaction(ClockStep.afterTomorrowsMidnight(),
                uow -> origination(ClockStep.beforeRead(LoanOfferStore.class, OFFER_STORE, ClockStep.STEP))
                        .originate(uow, approved.application(), SYSTEM));
        assertThat(outcome.kind()).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        assertThat(LocalDate.parse(scalar("SELECT lending.business_date(o.offered_at, 'UTC')::text FROM lending.loan_offer o"
                + " WHERE o.application_id = ?", approved.application().value())))
                .as("offered at the transaction's start, after the midnight").isEqualTo(realToday().plusDays(1));
        assertTheOfferIsCoherent(approved);
    }

    // ------------------------------------------------------------------ the business dates

    @Test
    @DisplayName("a repayment whose insert a step puts on the day before its read is admitted, dated the read's business"
            + " date - the latest the transaction read - never refused as a date not yet come")
    void aRepaymentSteppedBackAcrossAMidnightAfterItsReadIsAdmitted() {
        LoanId loan = RepaymentDatabaseTest.billedLoan(5_300_00);
        Repayments.Repaid repaid = ClockStep.inOneTransaction(ClockStep.afterTodaysMidnight(), uow ->
                RepaymentDatabaseTest.repayAs(uow, DisbursementWorld.repayments(ClockStep.afterRead(RepaymentStore.class,
                        REPAYMENT_STORE, ClockStep.STEP), new JdbcOutboxWriter()), partyOf(loan), loan,
                        RepaymentDatabaseTest.key(), 100_00));
        assertThat(repaid.replayed()).isFalse();
        assertThat(valueDateOf(repaid)).isEqualTo(realToday());
    }

    @Test
    @DisplayName("a repayment whose read a step puts on the day before its transaction's start is admitted, dated the"
            + " read's business date - the earliest the transaction read - never refused as backdated")
    void aRepaymentSteppedBackAcrossAMidnightBeforeItsReadIsAdmitted() {
        LoanId loan = RepaymentDatabaseTest.billedLoan(5_400_00);
        Repayments.Repaid repaid = ClockStep.inOneTransaction(ClockStep.afterTomorrowsMidnight(), uow ->
                RepaymentDatabaseTest.repayAs(uow, DisbursementWorld.repayments(ClockStep.beforeRead(RepaymentStore.class,
                        REPAYMENT_STORE, ClockStep.STEP), new JdbcOutboxWriter()), partyOf(loan), loan,
                        RepaymentDatabaseTest.key(), 100_00));
        assertThat(repaid.replayed()).isFalse();
        assertThat(valueDateOf(repaid)).isEqualTo(realToday());
    }

    @Test
    @DisplayName("a servicing step whose writes a step puts on the day before its read accrues every elapsed date and bills"
            + " the instalment due today - never refused as a date not yet ended or not yet due")
    void aServicingStepSteppedBackAcrossAMidnightAccruesAndBills() {
        LoanId loan = disbursed(3_400_00, 12);
        backdate(loan, daysTo(loan, 1));
        LoanServicing stepped = InterestAccrualDatabaseTest.servicing(
                ClockStep.afterRead(LoanServicingStore.class, SERVICING_STORE, ClockStep.STEP));
        LoanServicing.Outcome outcome;
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            outcome = ClockStep.inOneTransaction(ClockStep.afterTodaysMidnight(), uow -> stepped.service(uow, loan));
        }
        assertThat(outcome.kind()).isEqualTo(LoanServicing.Outcome.Kind.SERVICED);
        assertThat(outcome.billed()).as("the instalment due today, billed").isEqualTo(1);
        assertThat(LocalDate.parse(scalar("SELECT max(accrual_date)::text FROM lending.interest_accrual WHERE loan_id = ?",
                loan.value()))).as("every date before today accrued").isEqualTo(realToday().minusDays(1));
        assertThat(billings(loan)).singleElement().satisfies(billed ->
                assertThat(billed.billing().dueDate()).isEqualTo(realToday()));
    }

    @Test
    @DisplayName("a transaction's business date is a range: the earliest and the latest of its start's and this"
            + " statement's - one reading each side of a midnight step, the two functions answer both days")
    void aTransactionsBusinessDatesAreItsEarliestAndLatestReadings() {
        String dates = ClockStep.inOneTransaction(ClockStep.afterTodaysMidnight(), uow -> {
            ClockStep.stepBack(uow, ClockStep.STEP);
            return text(uow, "SELECT lending.business_date_earliest('UTC') || '|' || lending.business_date_latest('UTC')"
                    + " || '|' || lending.business_date_earliest('+14:00') || '|' || lending.business_date_latest('+14:00')");
        });
        LocalDate today = realToday();
        assertThat(dates).isEqualTo(today.minusDays(1) + "|" + today + "|" + today + "|" + today);
        String unstepped = ClockStep.inOneTransaction(Duration.ZERO, uow -> text(uow,
                "SELECT (lending.business_date_earliest('UTC') = lending.business_date(statement_timestamp(), 'UTC')"
                        + " AND lending.business_date_latest('UTC') = lending.business_date(statement_timestamp(), 'UTC'))"
                        + "::text"));
        assertThat(unstepped).as("on a clock that did not step, both are today").isEqualTo("true");
    }

    @Test
    @DisplayName("no lending rank judges a business date on ONE clock reading: every function compares a date with"
            + " today only through business_date_earliest / business_date_latest")
    void noRankJudgesABusinessDateOnOneClockReading() {
        List<String> offenders = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet rows = statement.executeQuery("SELECT p.proname FROM pg_proc p JOIN pg_namespace n"
                        + " ON n.oid = p.pronamespace WHERE n.nspname = 'lending'"
                        + " AND p.proname NOT IN ('business_date_earliest', 'business_date_latest')"
                        + " AND p.prosrc ~* 'business_date\\(\\s*(GREATEST\\(\\s*|LEAST\\(\\s*)?"
                        + "(statement_timestamp|transaction_timestamp|clock_timestamp|now)\\(\\)'"
                        + " ORDER BY p.proname")) {
            while (rows.next()) {
                offenders.add(rows.getString(1));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("the borrower's repayment history reads newest first in the record's order: a later repayment a clock"
            + " stepped back stamped before the earlier one is still listed first")
    void theRepaymentHistoryReadsInTheRecordsOrder() {
        LoanId loan = RepaymentDatabaseTest.billedLoan(5_500_00);
        Repayments.Repaid earlier = RepaymentDatabaseTest.repay(loan, RepaymentDatabaseTest.key(), 50_00);
        Repayments.Repaid later = RepaymentDatabaseTest.repay(loan, RepaymentDatabaseTest.key(), 60_00);
        // The clock stepped back between the two: the later one's stamp a second before the earlier one's.
        asOwner("UPDATE lending.repayment SET created_at = (SELECT created_at FROM lending.repayment WHERE id = '"
                + earlier.repayment().id() + "') - interval '1 second' WHERE id = '" + later.repayment().id() + "'");
        List<Repayment> history = DisbursementWorld.inOneTransaction(uow -> REPAYMENT_STORE.ownedBy(uow, loan,
                partyOf(loan), 10));
        assertThat(history).extracting(Repayment::id)
                .containsExactly(later.repayment().id(), earlier.repayment().id());
    }

    // ------------------------------------------------------------------ the order of facts floors a principal value date

    @Test
    @DisplayName("a repayment on a clock stepped back behind a date already accrued is value-dated the day after it -"
            + " never into the accrued date, whose base would keep the principal it paid and over-charge a day's interest"
            + " (the Phase 11 gate R1, ADR-0063 decision 2); the replay proves the account")
    void aRepaymentSteppedBackBehindAnAccruedDateIsValuedAfterIt() {
        // On its calendar's day, so the replay re-derives its schedule (a uniform backdate leaves it off its day).
        LoanId loan = AmendmentDatabaseTest.onItsDay(5_600_00, true);
        LocalDate accrued = accruedThroughTodayJustAfterMidnight(loan);
        Repayments.Repaid repaid = ClockStep.inOneTransaction(steppedBackAcrossThatMidnight(), uow ->
                RepaymentDatabaseTest.repayAs(uow, DisbursementWorld.REPAYMENTS, partyOf(loan), loan,
                        RepaymentDatabaseTest.key(), 100_00));
        assertThat(repaid.repayment().paid(AllocationComponent.PRINCIPAL).isPositive()).as("it pays principal").isTrue();
        assertThat(valueDateOf(repaid)).isEqualTo(accrued.plusDays(1));
        assertThat(entryValueDate(repaid.repayment().journalEntry().value())).isEqualTo(accrued.plusDays(1));
        LendingProofDatabaseTest.assertProven(loan);
    }

    @Test
    @DisplayName("a draw on a clock stepped back behind a date already accrued is value-dated the day after it - never"
            + " into the accrued date, whose base would miss it and under-charge a day's interest (the Phase 11 gate R1);"
            + " the replay proves the line")
    void aDrawSteppedBackBehindAnAccruedDateIsValuedAfterIt() throws SQLException {
        DisbursementWorld.Accepted line = DisbursementWorld.acceptedLine(4_000_00);
        LocalDate opened = DisbursementWorld.businessDate(line.loan()).minusDays(5);
        CreditLineStatementDatabaseTest.openOn(line, opened);
        CreditLineStatementDatabaseTest.dateDraw(CreditLineStatementDatabaseTest.draw(line, 1_000_00), opened.plusDays(1));
        LocalDate accrued = accruedThroughTodayJustAfterMidnight(line.loan());
        DisbursementWorld.fund(InterestAccrualDatabaseTest.eur(200_00));
        CreditLineDraws.Drawn drawn = ClockStep.inOneTransaction(steppedBackAcrossThatMidnight(), uow ->
                CreditLineDrawDatabaseTest.drawAs(uow, CreditLineDrawDatabaseTest.DRAWS, line.party(), line.loan(),
                        "p11gate-r1-" + UUID.randomUUID(), 200_00));
        assertThat(drawn.replayed()).isFalse();
        assertThat(drawn.draw().valueDate()).isEqualTo(accrued.plusDays(1));
        assertThat(entryValueDate(drawn.draw().journalEntry().value())).isEqualTo(accrued.plusDays(1));
        LendingProofDatabaseTest.assertProven(line.loan());
        // The database's floor, for every writer: a draw dated the accrued date is refused though the clock shows it.
        RepaymentDatabaseTest.refusedAlone("INSERT INTO lending.credit_line_draw (id, loan_id, draw_key, currency, scale,"
                + " amount_minor, agreement_id, agreement_version, journal_entry_id, value_date, correlation_id)"
                + " SELECT gen_random_uuid(), d.loan_id, 'p11gate-r1-raw', d.currency, d.scale, 1, d.agreement_id,"
                + " d.agreement_version, d.journal_entry_id, DATE '" + accrued + "', 'p11gate'"
                + " FROM lending.credit_line_draw d WHERE d.id = '" + drawn.draw().id() + "'",
                "P0001", "never value-dated a business date its line's record has closed");
    }

    @Test
    @DisplayName("a repayment reversal approved on a clock stepped back behind a date already accrued re-instates its"
            + " principal the day after it - never into the accrued date (the Phase 11 gate R1); the replay proves it")
    void aReversalSteppedBackBehindAnAccruedDateIsValuedAfterIt() {
        // On its calendar's day, so the replay re-derives its schedule (a uniform backdate leaves it off its day).
        LoanId loan = AmendmentDatabaseTest.onItsDay(5_700_00, true);
        Repayment repayment = RepaymentDatabaseTest.repay(loan, RepaymentDatabaseTest.key(), 100_00).repayment();
        assertThat(repayment.paid(AllocationComponent.PRINCIPAL).isPositive()).as("it paid principal").isTrue();
        LocalDate accrued = accruedThroughTodayJustAfterMidnight(loan);
        DisbursementWorld.fund(repayment.paid(AllocationComponent.PRINCIPAL)
                .plus(repayment.to(AllocationLine.Target.NOT_DUE_PRINCIPAL)));
        RepaymentReversal proposal = RepaymentReversalDatabaseTest.propose(loan, repayment.id(),
                DisbursementWorld.employee());
        Actor approver = DisbursementWorld.employee();
        RepaymentReversals.Decided decided = ClockStep.inOneTransaction(steppedBackAcrossThatMidnight(), uow ->
                RepaymentReversalDatabaseTest.as(approver, correlation -> RepaymentReversalDatabaseTest.REVERSALS.approve(
                        uow, loan, repayment.id(), proposal.id(), "checked against the bank statement", approver,
                        correlation)));
        assertThat(decided.reversal().status()).isEqualTo(RepaymentReversalStatus.APPROVED);
        assertThat(entryValueDate(decided.reversal().journalEntry().orElseThrow().value()))
                .isEqualTo(accrued.plusDays(1));
        LendingProofDatabaseTest.assertProven(loan);
    }

    @Test
    @DisplayName("a prepayment on a clock stepped back behind a date already accrued is value-dated - and re-schedules"
            + " from - the day after it, never the accrued date (the Phase 11 gate R1); the replay proves the account")
    void aPrepaymentSteppedBackBehindAnAccruedDateIsValuedAfterIt() {
        LoanId loan = AmendmentDatabaseTest.current(6_300_00);
        LocalDate today = DisbursementWorld.businessDate(loan);
        Assumptions.assumeTrue(count("SELECT count(*) FROM lending.loan_instalment WHERE loan_id = ? AND due_date = ?::date",
                loan.value(), today.plusDays(1).toString()) == 0,
                "tomorrow is no due date, so the step after midnight bills nothing the prepayment must wait for");
        LocalDate accrued = accruedThroughTodayJustAfterMidnight(loan);
        UUID party = partyOf(loan);
        Actor actor = DisbursementWorld.customer(party);
        Prepayments.Prepaid prepaid = ClockStep.inOneTransaction(steppedBackAcrossThatMidnight(), uow ->
                RepaymentReversalDatabaseTest.as(actor, correlation -> PrepaymentDatabaseTest.PREPAYMENTS.prepay(uow, loan,
                        party, new Prepayments.Request(RepaymentDatabaseTest.key(),
                                InterestAccrualDatabaseTest.eur(1_000_00)), actor, correlation)));
        assertThat(prepaid.replayed()).isFalse();
        assertThat(prepaid.repayment().valueDate()).isEqualTo(accrued.plusDays(1));
        assertThat(entryValueDate(prepaid.repayment().journalEntry().value())).isEqualTo(accrued.plusDays(1));
        LendingProofDatabaseTest.assertProven(loan);
    }

    @Test
    @DisplayName("a servicing step on a clock stepped back behind its account's record writes nothing - no collection"
            + " attempt, no condition, no fee dated a day the record has closed - and is due again at the record's date"
            + " (the Phase 11 gate R1)")
    void aServicingStepBehindItsRecordWritesNothing() {
        LoanId loan = RepaymentDatabaseTest.billedLoan(5_800_00);
        LocalDate accrued = accruedThroughTodayJustAfterMidnight(loan);
        String before = servicingFootprint(loan);
        LoanServicing.Outcome outcome;
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            outcome = ClockStep.inOneTransaction(steppedBackAcrossThatMidnight(), uow -> SERVICING.service(uow, loan));
        }
        assertThat(outcome.kind()).isEqualTo(LoanServicing.Outcome.Kind.SERVICED);
        assertThat(outcome.caughtUp()).as("behind its record - not caught up").isFalse();
        assertThat(servicingFootprint(loan)).as("nothing written").isEqualTo(before);
        assertThat(scalar("SELECT (next_servicing_at = (DATE '" + accrued.plusDays(1) + "')::timestamp AT TIME ZONE 'UTC')"
                + "::text FROM lending.loan WHERE id = ?", loan.value()))
                .as("due again when the clock reaches the record's date").isEqualTo("true");
    }

    @Test
    @DisplayName("the database refuses a repayment value-dated a date the record has closed, for every writer, and its"
            + " floor stands after every date a clock stepped back behind the record shows (the Phase 11 gate R1)")
    void theDatabaseFloorsAPrincipalValueDate() throws SQLException {
        LoanId loan = RepaymentDatabaseTest.billedLoan(5_900_00);
        LocalDate accrued = accruedThroughTodayJustAfterMidnight(loan);
        String seen = ClockStep.inOneTransaction(steppedBackAcrossThatMidnight(), uow -> text(uow,
                "SELECT lending.principal_value_floor('" + loan.value() + "')::text || '|'"
                        + " || lending.business_date_latest('UTC')::text"));
        assertThat(seen).as("the floor, and the latest date the stepped clock shows").isEqualTo(accrued.plusDays(1) + "|"
                + accrued);
        assertThat(scalar("SELECT (lending.principal_value_floor(gen_random_uuid()) IS NULL)::text")).isEqualTo("true");
        RepaymentDatabaseTest.repay(loan, RepaymentDatabaseTest.key(), 10_00);
        RepaymentDatabaseTest.refusedAlone("INSERT INTO lending.repayment (id, loan_id, kind, request_key, currency, scale,"
                + " amount_minor, agreement_id, agreement_version, allocation_engine_version, journal_entry_id, value_date,"
                + " correlation_id) SELECT gen_random_uuid(), r.loan_id, 'CUSTOMER', 'p11gate-r1-raw', r.currency, r.scale,"
                + " 1, r.agreement_id, r.agreement_version, r.allocation_engine_version, r.journal_entry_id, DATE '"
                + accrued + "', 'p11gate' FROM lending.repayment r WHERE r.loan_id = '" + loan.value() + "' LIMIT 1",
                "P0001", "never value-dated a business date its loan's record has closed");
    }

    // ------------------------------------------------------------------ the servicing permit

    @Test
    @DisplayName("a sweep on a clock behind an account's opening permit claims nothing until the clock passes it - nothing"
            + " billed, a delay of at most the step (kept, ADR-0063 decision 4); a fixture's history moved into the past"
            + " moves the permit with it, so the same sweep claims and bills")
    void aSweepBehindTheOpeningPermitClaimsNothingUntilItPasses() {
        LoanId loan = disbursed(3_500_00, 12);
        int days = daysTo(loan, 2);
        // The permit the account was born with, ahead of the sweep's clock - as a step back after the opening leaves it,
        // and as InstalmentBillingDatabaseTest#tenSweepersBillOnce's fixture left it before P11-TSK-036 (backdate moved
        // the history, never the permit). Planted a minute ahead, so the case never races the real clock past it.
        asOwner("UPDATE lending.loan SET next_servicing_at = statement_timestamp() + interval '1 minute' WHERE id = '"
                + loan.value() + "'");
        assertThat(claimedBehind(loan)).as("a clock behind the permit claims nothing").isFalse();
        assertThat(billings(loan)).as("the P11-TSK-030 symptom: no billing found").isEmpty();
        backdate(loan, days);
        assertThat(claimedBehind(loan)).as("the permit moved with the history - due on the same clock").isTrue();
        assertThat(service(loan).billed()).isEqualTo(2);
        assertThat(billings(loan)).hasSize(2);
    }

    // ------------------------------------------------------------------ plumbing

    /** A fresh instalment application approved and awaiting its offer, under the terms in force. */
    private static DisbursementWorld.Approved approvedLoan() {
        ensureActive();
        return DisbursementWorld.approved(LoanProduct.PERSONAL_LOAN, 4_200_00, Optional.of(12), PayoutPreference.WALLET,
                10);
    }

    /** The origination step over {@code offers} - the world's otherwise. */
    private static LoanOrigination origination(LoanOfferStore offers) {
        return new LoanOrigination(APPLICATIONS, offers, ADMINISTRATION, CREDIT, new JdbcOutboxWriter(), IDS, CLOCK);
    }

    /**
     * The offer is one clock's: offered within its transaction and never before its start, expiring at the earlier of
     * the decision's validity and the version's validity from it, its permit at the expiry, its illustration starting on
     * offered_at's business date, one OFFERED history row, the application OFFERED.
     */
    private static void assertTheOfferIsCoherent(DisbursementWorld.Approved approved) {
        assertThat(scalar("SELECT (o.offered_at >= a.closed_at"
                + " AND o.expires_at = LEAST(o.decision_valid_until, o.offered_at + make_interval(days => t.offer_validity_days))"
                + " AND o.next_step_at = o.expires_at"
                + " AND ot.illustrative_start = lending.business_date(o.offered_at, t.servicing_zone)"
                + " AND a.status = 'OFFERED')::text"
                + " FROM lending.loan_offer o JOIN lending.loan_application a ON a.id = o.application_id"
                + " JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id"
                + " JOIN lending.loan_offer_terms ot ON ot.offer_id = o.id WHERE o.application_id = ?",
                approved.application().value())).isEqualTo("true");
        assertThat(count("SELECT count(*) FROM lending.loan_offer_event e JOIN lending.loan_offer o ON o.id = e.offer_id"
                + " WHERE o.application_id = ? AND e.to_status = 'OFFERED'", approved.application().value())).isEqualTo(1);
    }

    /**
     * Claims every due account page by page on a clock {@link ClockStep#STEP} behind the real one - as a sweep would just
     * after a step - and answers whether {@code loan} was among them (the claimed are left on their claim's permit).
     */
    private static boolean claimedBehind(LoanId loan) {
        Set<LoanId> claimed = new HashSet<>();
        List<LoanId> page;
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            do {
                page = ClockStep.inOneTransaction(ClockStep.STEP.negated(),
                        uow -> SERVICING.claim(uow, 200, Duration.ofMinutes(5)));
                claimed.addAll(page);
            } while (page.size() == 200);
        }
        return claimed.contains(loan);
    }

    /**
     * Every elapsed date of {@code loan} accrued THROUGH today by a servicing step just after tomorrow's midnight (UTC, the
     * fixtures' pinned zone) - the clock a date ahead, as just before a step back across that midnight. Answers today.
     */
    private static LocalDate accruedThroughTodayJustAfterMidnight(LoanId loan) {
        LocalDate today = DisbursementWorld.businessDate(loan);
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            ClockStep.inOneTransaction(ClockStep.afterTomorrowsMidnight(), uow -> SERVICING.service(uow, loan));
        }
        assertThat(LocalDate.parse(scalar("SELECT max(accrual_date)::text FROM lending.interest_accrual WHERE loan_id = ?",
                loan.value()))).as("today accrued, just after its midnight").isEqualTo(today);
        return today;
    }

    /**
     * The frame placing a whole transaction {@link ClockStep#STEP} behind just-after-tomorrow's-midnight: the clock stepped
     * back across the midnight the accrual above was made after, so every reading shows today again.
     */
    private static Duration steppedBackAcrossThatMidnight() {
        return ClockStep.afterTomorrowsMidnight().minus(ClockStep.STEP);
    }

    private static LocalDate entryValueDate(UUID entry) {
        return LocalDate.parse(scalar("SELECT value_date::text FROM ledger.journal_entry WHERE id = ?", entry));
    }

    /** Every row a servicing step of {@code loan} can write, counted - and its status. */
    private static String servicingFootprint(LoanId loan) {
        return scalar("SELECT (SELECT count(*) FROM lending.interest_accrual WHERE loan_id = l.id) || '|'"
                + " || (SELECT count(*) FROM lending.instalment_billing WHERE loan_id = l.id) || '|'"
                + " || (SELECT count(*) FROM lending.credit_balance_application WHERE loan_id = l.id) || '|'"
                + " || (SELECT count(*) FROM lending.collection_attempt WHERE loan_id = l.id) || '|'"
                + " || (SELECT count(*) FROM lending.repayment WHERE loan_id = l.id) || '|'"
                + " || (SELECT count(*) FROM lending.loan_condition_event WHERE loan_id = l.id) || '|'"
                + " || (SELECT count(*) FROM lending.fee_assessment WHERE loan_id = l.id) || '|' || l.status"
                + " FROM lending.loan l WHERE l.id = ?", loan.value());
    }

    private static LocalDate valueDateOf(Repayments.Repaid repaid) {
        return LocalDate.parse(scalar("SELECT value_date::text FROM lending.repayment WHERE id = ?",
                repaid.repayment().id()));
    }

    /** Today's business date in UTC on the real clock. */
    private static LocalDate realToday() {
        return LocalDate.ofInstant(Instant.parse(scalar("SELECT to_char(pg_catalog.clock_timestamp() AT TIME ZONE 'UTC',"
                + " 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')")), ZoneOffset.UTC);
    }

    private static String text(Connection connection, String sql) {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
