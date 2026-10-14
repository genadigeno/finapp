package com.finapp.lending;

import static com.finapp.lending.AmendmentDatabaseTest.accept;
import static com.finapp.lending.AmendmentDatabaseTest.approve;
import static com.finapp.lending.AmendmentDatabaseTest.current;
import static com.finapp.lending.AmendmentDatabaseTest.dueOf;
import static com.finapp.lending.AmendmentDatabaseTest.exposure;
import static com.finapp.lending.AmendmentDatabaseTest.inArrears;
import static com.finapp.lending.AmendmentDatabaseTest.lowerRate;
import static com.finapp.lending.AmendmentDatabaseTest.onItsDay;
import static com.finapp.lending.AmendmentDatabaseTest.propose;
import static com.finapp.lending.DisbursementWorld.BALANCES;
import static com.finapp.lending.DisbursementWorld.BILLINGS;
import static com.finapp.lending.DisbursementWorld.BILLING_STORE;
import static com.finapp.lending.DisbursementWorld.CLOCK;
import static com.finapp.lending.DisbursementWorld.DISBURSEMENT_STORE;
import static com.finapp.lending.DisbursementWorld.HOLD_STORE;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.LOAN_STORE;
import static com.finapp.lending.DisbursementWorld.OFFER_STORE;
import static com.finapp.lending.DisbursementWorld.REPAYMENTS;
import static com.finapp.lending.DisbursementWorld.REPAYMENT_STORE;
import static com.finapp.lending.DisbursementWorld.TERMS_STORE;
import static com.finapp.lending.DisbursementWorld.WALLETS;
import static com.finapp.lending.DisbursementWorld.acceptedLine;
import static com.finapp.lending.DisbursementWorld.businessDate;
import static com.finapp.lending.DisbursementWorld.businessDateSql;
import static com.finapp.lending.DisbursementWorld.correlation;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.customer;
import static com.finapp.lending.DisbursementWorld.employee;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.DisbursementWorld.walletOf;
import static com.finapp.lending.InstalmentBillingDatabaseTest.advance;
import static com.finapp.lending.InstalmentBillingDatabaseTest.partyOf;
import static com.finapp.lending.InstalmentBillingDatabaseTest.serviceUntilCaughtUp;
import static com.finapp.lending.InterestAccrualDatabaseTest.SERVICING;
import static com.finapp.lending.InterestAccrualDatabaseTest.SERVICING_STORE;
import static com.finapp.lending.InterestAccrualDatabaseTest.eur;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.PayoffDatabaseTest.execute;
import static com.finapp.lending.PayoffDatabaseTest.quote;
import static com.finapp.lending.RepaymentDatabaseTest.assertSubledgerProves;
import static com.finapp.lending.RepaymentDatabaseTest.events;
import static com.finapp.lending.RepaymentDatabaseTest.held;
import static com.finapp.lending.RepaymentDatabaseTest.key;
import static com.finapp.lending.RepaymentDatabaseTest.loanStatus;
import static com.finapp.lending.RepaymentDatabaseTest.refusedAlone;
import static com.finapp.lending.RepaymentDatabaseTest.repay;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Partial prepayment with recalculation against a real database (`P11-TSK-028`; {@code lending V024},
 * {@code PHASE_11_PLAN.md} sections 7.1, 7.4, 12.2 step 7, 12.5 and 12.8 rule 5; ADR-0099 point 6; {@code INV-LND-03},
 * {@code INV-LND-05}, {@code INV-LND-04}, {@code INV-IDEM-01}).
 *
 * <p>Loans are disbursed by the real T-dis, serviced by the real T-svc and repaid by the real T-rep; prepayments go through
 * the real {@link Prepayments} over the real ledger. A later business date is {@link InstalmentBillingDatabaseTest#advance}
 * - the fixture's clock - and every planted date derives from the database clock in the pinned zone. Every case on a loan
 * closes on the subledger proof's arms, the prepaid principal included.
 */
@Tag("database")
@DisplayName("partial prepayment with recalculation (P11-TSK-028)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class PrepaymentDatabaseTest {

    static final Prepayments PREPAYMENTS = new Prepayments(LOAN_STORE, REPAYMENT_STORE, TERMS_STORE, OFFER_STORE, SERVICING,
            SERVICING_STORE, BILLINGS, BILLING_STORE, DISBURSEMENT_STORE, WALLETS,
            new AvailableBalance<>(new JdbcBalanceDerivation(), HOLD_STORE), LEDGER_ACCOUNTS, BALANCES, REPAYMENTS,
            new JdbcOutboxWriter(), IDS, CLOCK);

    // ------------------------------------------------------------------ the recalculation

    @Test
    @DisplayName("a prepayment shortens the term and conserves: on a current twelve-month loan (one instalment billed and"
            + " paid) EUR 2,000.00 prepaid - one rule 5 entry DR CUSTOMER_WALLET / CR LOAN_PRINCIPAL, one PREPAYMENT"
            + " repayment with its one NOT_DUE_PRINCIPAL line; agreement version 2 (the same principal, rate, day and"
            + " engines, term = billed + the new schedule's, canonical terms format 4 naming the prepayment, no acceptance"
            + " evidence) and schedule version 2 amortising exactly the principal left (LOAN_PRINCIPAL) with the SAME level"
            + " instalment over fewer instalments, from the open period; exposure falls by the amount; the next day accrues"
            + " on the lower base and the old second due date bills version 2's first instalment under agreement 2")
    void prepaymentShortensTheTermAndConserves() {
        LoanId loan = current(6_000_00);
        long notDue = netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits();
        long instalment = instalmentOf(loan, 1);
        long remainingBefore = count("SELECT count(*) FROM lending.loan_instalment i JOIN lending.repayment_schedule s"
                + " ON s.id = i.schedule_id WHERE s.loan_id = ? AND s.schedule_version = 1", loan.value()) - 1;
        LocalDate secondDue = dueOf(loan, 1, 2);
        long wallet = -netDebit(walletOf(partyOf(loan))).minorUnits();
        long exposureBefore = exposure(loan);
        String rate = scalar("SELECT nominal_annual_rate FROM lending.loan_agreement WHERE loan_id = ? AND version = 1",
                loan.value());

        Prepayments.Prepaid prepaid = prepay(loan, key(), 2_000_00);
        assertThat(prepaid.replayed()).isFalse();
        assertThat(prepaid.rescheduled().agreementVersion()).isEqualTo(2);
        assertThat(prepaid.rescheduled().scheduleVersion()).isEqualTo(2);
        int term = prepaid.rescheduled().termMonths();
        assertThat(term).as("the term shortened").isLessThan(Math.toIntExact(remainingBefore));
        assertThat(prepaid.rescheduled().instalment().minorUnits()).as("the instalment unchanged").isEqualTo(instalment);

        // The money: one entry, wallet to principal not yet due.
        UUID repayment = prepaid.repayment().id();
        assertThat(prepaid.repayment().kind()).isEqualTo(RepaymentKind.PREPAYMENT);
        assertThat(count("SELECT count(*) FROM lending.repayment_allocation WHERE repayment_id = ?"
                + " AND target = 'NOT_DUE_PRINCIPAL' AND amount_minor = 200000", repayment)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.repayment_allocation WHERE repayment_id = ?", repayment))
                .isEqualTo(1);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits()).isEqualTo(notDue - 2_000_00);
        assertThat(-netDebit(walletOf(partyOf(loan))).minorUnits()).isEqualTo(wallet - 2_000_00);
        assertThat(exposure(loan)).as("exposure falls by the amount prepaid").isEqualTo(exposureBefore - 2_000_00);
        assertThat(held(loan)).as("nothing held as credit").isZero();

        // Agreement version 2: a prepayment's - nothing changed but the term.
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ? AND version = 2"
                + " AND prepayment_id = ? AND amendment_id IS NULL AND offer_id IS NULL AND amount_minor = 600000"
                + " AND nominal_annual_rate = ? AND canonical_format = 4"
                + " AND terms_sha256 = sha256(convert_to(canonical_terms, 'UTF8'))"
                + " AND canonical_terms LIKE '%\"prepayment\":\"" + repayment + "\"%'", loan.value(), repayment, rate))
                .isEqualTo(1);
        assertThat(count("SELECT term_months FROM lending.loan_agreement WHERE loan_id = ? AND version = 2", loan.value()))
                .as("one billed plus the new schedule's").isEqualTo(1 + term);
        assertThat(count("SELECT count(*) FROM lending.loan_acceptance e JOIN lending.loan_agreement a ON a.id ="
                + " e.agreement_id WHERE a.loan_id = ? AND a.version = 2", loan.value()))
                .as("no acceptance evidence: the customer's own command is the act").isZero();

        // Schedule version 2: the principal left, the same instalment, the least term, from the open period.
        assertThat(count("SELECT principal_minor FROM lending.repayment_schedule WHERE loan_id = ? AND schedule_version = 2",
                loan.value())).as("the principal left - never the original").isEqualTo(notDue - 2_000_00);
        assertThat(count("SELECT coalesce(sum(i.principal_minor), 0) FROM lending.loan_instalment i JOIN"
                + " lending.repayment_schedule s ON s.id = i.schedule_id WHERE s.loan_id = ? AND s.schedule_version = 2",
                loan.value())).as("conserved").isEqualTo(notDue - 2_000_00);
        assertThat(instalmentOf(loan, 2)).isEqualTo(instalment);
        assertThat(count("SELECT final_instalment_minor FROM lending.repayment_schedule WHERE loan_id = ?"
                + " AND schedule_version = 2", loan.value())).isLessThanOrEqualTo(instalment);
        assertThat(count("SELECT count(*) FROM lending.loan_instalment i JOIN lending.repayment_schedule s"
                + " ON s.id = i.schedule_id WHERE s.loan_id = ? AND s.schedule_version = 2", loan.value())).isEqualTo(term);
        assertThat(dueOf(loan, 2, 1)).as("the open period's due date - the old second one").isEqualTo(secondDue);
        assertThat(events(loan, Repayments.RECEIVED_EVENT)).as("the first repayment and the prepayment").isEqualTo(2);
        assertThat(events(loan, LoanAmendments.AMENDED_EVENT)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?"
                + " AND convert_from(payload, 'UTF8') LIKE '%\"kind\":\"PREPAYMENT\"%'", loan.value(),
                Repayments.RECEIVED_EVENT)).as("RepaymentReceived, kind PREPAYMENT").isEqualTo(1);
        assertThat(scalar("SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = ?", loan.value(), LoanAmendments.AMENDED_EVENT))
                .as("LoanAmended names its origin and the prepayment - never an amount, a rate or a party")
                .contains("\"origin\":\"PREPAYMENT\"", "\"prepaymentId\":\"" + repayment + "\"",
                        "\"agreementVersion\":\"2\"", "\"amendsVersion\":\"1\"", "\"scheduleVersion\":\"2\"")
                .doesNotContain("amount", "Rate", "party", "200000");
        assertSubledgerProves(loan);

        // The next day accrues on the lower base under version 2.
        advance(loan, 1);
        service(loan);
        assertThat(count("SELECT base_minor FROM lending.interest_accrual WHERE loan_id = ? ORDER BY accrual_date DESC"
                + " LIMIT 1", loan.value())).as("future interest on the lower base").isEqualTo(notDue - 2_000_00);
        assertThat(count("SELECT agreement_version FROM lending.interest_accrual WHERE loan_id = ?"
                + " ORDER BY accrual_date DESC LIMIT 1", loan.value())).isEqualTo(2);
        // The old second due date bills version 2's first instalment under agreement 2: the same instalment, less interest.
        advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(businessDate(loan), dueOf(loan, 2, 1))));
        serviceUntilCaughtUp(loan);
        assertThat(count("SELECT count(*) FROM lending.instalment_billing b WHERE b.loan_id = ? AND b.schedule_version = 2"
                + " AND b.agreement_version = 2 AND b.sequence = 1 AND b.interest_due_minor + b.principal_due_minor = ?",
                loan.value(), instalment)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.instalment_billing b WHERE b.loan_id = ? AND b.schedule_version = 1",
                loan.value())).as("version 1's unbilled instalments are superseded, never billed").isEqualTo(1);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("the schedule version once: one prepayment makes exactly one agreement version and one schedule version -"
            + " a second schedule for its agreement refused by UNIQUE (agreement_id), a second agreement version for the"
            + " same prepayment by the trigger; a second prepayment makes version 3 over version 2, again the same"
            + " instalment over a shorter term, every version numbered without a gap")
    void scheduleVersionOnce() throws Exception {
        LoanId loan = current(6_200_00);
        long instalment = instalmentOf(loan, 1);
        Prepayments.Prepaid first = prepay(loan, key(), 1_000_00);
        refusedAlone("INSERT INTO lending.repayment_schedule SELECT gen_random_uuid(), loan_id, schedule_version + 1,"
                + " agreement_id, agreement_version, schedule_engine_version, currency, scale, principal_minor,"
                + " instalment_minor, final_instalment_minor, total_interest_minor, term_months, start_date, intended_day,"
                + " now(), 'p11t28-raw' FROM lending.repayment_schedule WHERE loan_id = '" + loan.value()
                + "' AND schedule_version = 2", "23505", "repayment_schedule_agreement_unique");
        refusedAlone(rawAgreementFor(first.repayment().id()), "P0001", "the next version of the agreement it was received");
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?", loan.value())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?", loan.value())).isEqualTo(2);

        Prepayments.Prepaid second = prepay(loan, key(), 1_000_00);
        assertThat(second.rescheduled().agreementVersion()).isEqualTo(3);
        assertThat(second.rescheduled().scheduleVersion()).isEqualTo(3);
        assertThat(second.rescheduled().termMonths()).isLessThan(first.rescheduled().termMonths());
        assertThat(instalmentOf(loan, 3)).isEqualTo(instalment);
        assertThat(scalar("SELECT string_agg(version::text, ',' ORDER BY version) FROM lending.loan_agreement"
                + " WHERE loan_id = ?", loan.value())).isEqualTo("1,2,3");
        assertThat(scalar("SELECT string_agg(schedule_version::text || ':' || agreement_version, ','"
                + " ORDER BY schedule_version) FROM lending.repayment_schedule WHERE loan_id = ?", loan.value()))
                .isEqualTo("1:1,2:2,3:3");
        assertThat(count("SELECT principal_minor FROM lending.repayment_schedule WHERE loan_id = ? AND schedule_version = 3",
                loan.value())).isEqualTo(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits());
        assertSubledgerProves(loan);
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    @DisplayName("refused while past due: a loan whose first instalment is unpaid past its due date is 409"
            + " lending.ArrearsFirst and nothing is written - not even the catch-up; an instalment billed and unpaid on its"
            + " own day too; an amount reaching the principal not yet due is PrepaymentExceedsPrincipal (the whole is the"
            + " payoff's); a wallet that cannot fund it InsufficientFunds; a credit line LoanNotRepayable; and the"
            + " database refuses a raw prepayment over arrears for every writer")
    void refusedWhilePastDue() throws Exception {
        LoanId arrears = inArrears(5_000_00);
        advance(arrears, 1);
        String before = footprint(arrears);
        assertThatExceptionOfType(Prepayments.ArrearsFirst.class).isThrownBy(() -> prepay(arrears, key(), 500_00));
        assertThat(footprint(arrears)).as("nothing written - the catch-up rolled back with the refusal").isEqualTo(before);
        refusedAlone(rawPrepayment(arrears, 100), "P0001", "arrears first");

        LoanId onItsDay = onItsDay(5_100_00, false);
        assertThatExceptionOfType(Prepayments.ArrearsFirst.class).isThrownBy(() -> prepay(onItsDay, key(), 500_00));

        LoanId whole = current(5_200_00);
        long notDue = netDebit(loanAccount(whole, AccountPurpose.LOAN_PRINCIPAL)).minorUnits();
        assertThatExceptionOfType(Prepayments.PrepaymentExceedsPrincipal.class)
                .isThrownBy(() -> prepay(whole, key(), notDue));
        assertThatExceptionOfType(Prepayments.PrepaymentExceedsPrincipal.class)
                .isThrownBy(() -> prepay(whole, key(), notDue + 1));
        refusedAlone(rawPrepayment(whole, notDue), "P0001", "the whole is the payoff");
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ? AND kind = 'PREPAYMENT'", whole.value()))
                .isZero();

        // The wallet moved to the credit balance by an early repayment: what is left cannot fund the prepayment.
        long wallet = -netDebit(walletOf(partyOf(whole))).minorUnits();
        repay(whole, key(), wallet - 100_00);
        String funded = footprint(whole);
        assertThatExceptionOfType(Repayments.InsufficientFunds.class).isThrownBy(() -> prepay(whole, key(), 500_00));
        assertThat(footprint(whole)).isEqualTo(funded);

        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        assertThatExceptionOfType(Repayments.LoanNotRepayable.class)
                .isThrownBy(() -> prepayAs(line.party(), line.loan(), key(), 100_00));
        assertSubledgerProves(whole);
    }

    @Test
    @DisplayName("ten prepayments under one key are one: one PREPAYMENT repayment, one rule 5 entry, one agreement version"
            + " and one schedule version - nine answered with the same repayment and versions; another amount under the"
            + " key is RepaymentKeyReused")
    void tenPrepaymentsOneKey() throws Exception {
        LoanId loan = current(6_300_00);
        String key = key();
        List<Prepayments.Prepaid> outcomes = race(10, index -> prepay(loan, key, 1_500_00));
        assertThat(outcomes).filteredOn(prepaid -> !prepaid.replayed()).hasSize(1);
        assertThat(outcomes).extracting(prepaid -> prepaid.repayment().id()).containsOnly(outcomes.get(0).repayment().id());
        assertThat(outcomes).extracting(prepaid -> prepaid.rescheduled().agreementVersion()).containsOnly(2);
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ? AND kind = 'PREPAYMENT'", loan.value()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:" + Repayments.POSTING_KEY_PREFIX + outcomes.get(0).repayment().id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?", loan.value())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?", loan.value())).isEqualTo(2);
        assertThat(events(loan, LoanAmendments.AMENDED_EVENT)).isEqualTo(1);
        assertThatExceptionOfType(Repayments.RepaymentKeyReused.class).isThrownBy(() -> prepay(loan, key, 1_500_01));
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("ten prepayments under ten keys serialise on L3: each sees the version the one before it committed while"
            + " it waited (the agreement read after the lock), so all ten succeed as versions 2 to 11 without a gap, each"
            + " schedule version naming its own agreement version and amortising exactly LOAN_PRINCIPAL, the level"
            + " instalment unchanged throughout")
    void tenPrepaymentsUnderTenKeysSerialise() throws Exception {
        LoanId loan = current(6_350_00);
        long instalment = instalmentOf(loan, 1);
        List<Prepayments.Prepaid> outcomes = race(10, index -> prepay(loan, key(), 100_00));
        assertThat(outcomes).filteredOn(Prepayments.Prepaid::replayed).isEmpty();
        assertThat(outcomes).extracting(prepaid -> prepaid.rescheduled().agreementVersion())
                .containsExactlyInAnyOrder(2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(scalar("SELECT string_agg(schedule_version::text || ':' || agreement_version, ','"
                + " ORDER BY schedule_version) FROM lending.repayment_schedule WHERE loan_id = ?", loan.value()))
                .isEqualTo("1:1,2:2,3:3,4:4,5:5,6:6,7:7,8:8,9:9,10:10,11:11");
        assertThat(count("SELECT count(DISTINCT instalment_minor) FROM lending.repayment_schedule WHERE loan_id = ?",
                loan.value())).isEqualTo(1);
        assertThat(instalmentOf(loan, 11)).isEqualTo(instalment);
        assertThat(count("SELECT principal_minor FROM lending.repayment_schedule WHERE loan_id = ? AND schedule_version = 11",
                loan.value())).isEqualTo(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits());
        assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ? AND kind = 'PREPAYMENT'", loan.value()))
                .isEqualTo(10);
        assertSubledgerProves(loan);
    }

    // ------------------------------------------------------------------ beside the other acts

    @Test
    @DisplayName("a prepayment lapses a pending amendment: an approved amendment of version 1 is LAPSED once a prepayment"
            + " made version 2, its acceptance 409 AmendmentLapsed; a prepayment waiting on L3 behind an acceptance sees the"
            + " version the acceptance made and makes version 3; a prepayment racing an acceptance gives one version 2 - the"
            + " amendment's then the prepayment's version 3, or the prepayment's and the amendment lapsed - never two")
    void aPrepaymentLapsesAPendingAmendment() throws Exception {
        LoanId loan = current(6_400_00);
        BigDecimal rate = lowerRate(loan);
        LoanAmendment amendment = propose(loan, rate, 14, false, employee());
        approve(loan, amendment.id(), employee());
        prepay(loan, key(), 1_000_00);
        assertThat(inOneTransaction(uow -> AmendmentDatabaseTest.AMENDMENT_STORE.find(uow, loan, amendment.id()))
                .orElseThrow().status()).isEqualTo(LoanAmendmentStatus.LAPSED);
        assertThatExceptionOfType(LoanAmendments.AmendmentLapsed.class).isThrownBy(() -> accept(loan, amendment.id()));
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?", loan.value())).isEqualTo(2);

        // Deterministically behind the lock: the acceptance holds L3 uncommitted while the prepayment waits on it.
        LoanId behind = current(6_450_00);
        LoanAmendment held = propose(behind, lowerRate(behind), 14, false, employee());
        approve(behind, held.id(), employee());
        String heldHash = AmendmentDatabaseTest.read(behind, held.id()).amendment().documentSha256Hex();
        try (java.sql.Connection acceptance = com.finapp.platform.testing.database.DatabaseRoles.application()) {
            acceptance.setAutoCommit(false);
            UUID party = partyOf(behind);
            Actor customer = customer(party);
            CorrelationId correlation = correlation();
            try (SecurityContext.Scope scope = SecurityContext.enter(customer);
                    CorrelationContext.Scope flow = CorrelationContext.enter(
                            Correlation.startingWith(correlation).causing(CausationId.of(correlation.value())))) {
                AmendmentDatabaseTest.AMENDMENTS.accept(acceptance, behind, party, held.id(),
                        new LoanAmendments.Acceptance(heldHash, new AcceptanceEvidence(UUID.randomUUID(),
                                UUID.randomUUID(), "MULTI_FACTOR", Optional.empty(), Optional.empty())),
                        customer, correlation);
            }
            java.util.concurrent.ExecutorService waiter = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<Prepayments.Prepaid> waiting =
                        waiter.submit(() -> prepay(behind, key(), 1_000_00));
                DisbursementWorld.awaitBlockedBy(DisbursementWorld.pid(acceptance));
                acceptance.commit();
                assertThat(waiting.get(2, java.util.concurrent.TimeUnit.MINUTES).rescheduled().agreementVersion())
                        .as("the version the acceptance committed while it waited, then its own").isEqualTo(3);
            } finally {
                waiter.shutdownNow();
            }
        }
        assertSubledgerProves(behind);

        LoanId raced = current(6_500_00);
        LoanAmendment racing = propose(raced, lowerRate(raced), 14, false, employee());
        approve(raced, racing.id(), employee());
        String hash = AmendmentDatabaseTest.read(raced, racing.id()).amendment().documentSha256Hex();
        List<String> outcomes = race(2, index -> {
            if (index == 0) {
                return "PREPAID:v" + prepay(raced, key(), 1_000_00).rescheduled().agreementVersion();
            }
            try {
                return "ACCEPTED:v" + accept(raced, racing.id(), hash).agreementVersion();
            } catch (LoanAmendments.AmendmentLapsed lapsed) {
                return "LAPSED";
            }
        });
        assertThat(String.join(",", outcomes.stream().sorted().toList()))
                .as("one version 2, never two").isIn("ACCEPTED:v2,PREPAID:v3", "LAPSED,PREPAID:v2");
        assertThat(scalar("SELECT string_agg(schedule_version::text || ':' || agreement_version, ','"
                + " ORDER BY schedule_version) FROM lending.repayment_schedule WHERE loan_id = ?", raced.value()))
                .isIn("1:1,2:2", "1:1,2:2,3:3");
        assertSubledgerProves(loan);
        assertSubledgerProves(raced);
    }

    @Test
    @DisplayName("a prepayment and the billing on the same date serialise on L3, the billing first by the step order: a"
            + " loan whose first due date has come and is not yet billed, its credit balance covering the instalment - the"
            + " sweep then the prepayment, the prepayment then the sweep, and the two racing: each bills the due date"
            + " exactly once (the prepayment's own catch-up when it runs first), the credit balance pays it, and schedule"
            + " version 2 starts at that due date; a writer that skips the billing is refused by the database - an"
            + " instalment already due is never superseded unbilled")
    void prepaymentAndBillingOnTheSameDateSerialise() throws Exception {
        // The billing first for EVERY writer: a writer that skips the billing - here the real T-ppy wired to a billing
        // step that sees no schedule - is refused by lending V024's schedule trigger: an instalment already due is never
        // superseded unbilled (the storage error names the statement and its SQLSTATE only; the guard's own probe in
        // MUTATION_TESTING.md shows this refusal is that guard's). Nothing is written.
        LoanId blind = dueAndUnbilled(6_550_00);
        String untouched = footprint(blind);
        assertThatExceptionOfType(LendingStorageException.class)
                .isThrownBy(() -> prepayWith(blindToTheSchedule(), blind, key(), 1_000_00))
                .withMessageContaining("inserting a repayment schedule: SQLSTATE P0001");
        assertThat(footprint(blind)).as("nothing written").isEqualTo(untouched);

        for (String order : List.of("SWEEP_FIRST", "PREPAYMENT_FIRST", "RACE")) {
            LoanId loan = dueAndUnbilled(6_600_00);
            LocalDate due = dueOf(loan, 1, 1);
            switch (order) {
                case "SWEEP_FIRST" -> {
                    serviceUntilCaughtUp(loan);
                    prepay(loan, key(), 1_000_00);
                }
                case "PREPAYMENT_FIRST" -> {
                    prepay(loan, key(), 1_000_00);
                    serviceUntilCaughtUp(loan);
                }
                default -> race(2, index -> {
                    if (index == 0) {
                        serviceUntilCaughtUp(loan);
                    } else {
                        prepay(loan, key(), 1_000_00);
                    }
                    return index;
                });
            }
            assertThat(count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ? AND due_date = ?",
                    loan.value(), due)).as(order + ": the date billed once").isEqualTo(1);
            assertThat(count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?", loan.value()))
                    .as(order).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM lending.repayment WHERE loan_id = ? AND kind = 'PREPAYMENT'",
                    loan.value())).as(order).isEqualTo(1);
            assertThat(scalar("SELECT start_date::text FROM lending.repayment_schedule WHERE loan_id = ?"
                    + " AND schedule_version = 2", loan.value())).as(order + ": from the billed due date")
                    .isEqualTo(due.toString());
            assertThat(RepaymentDatabaseTest.due(loan)).as(order + ": the credit balance paid the date").isZero();
            assertSubledgerProves(loan);
        }
    }

    @Test
    @DisplayName("a prepayment is never reversed: its proposal is RepaymentNotReversible (it moved the agreement - a"
            + " correction is an amendment) and the database refuses a raw reversal of it for every writer")
    void aPrepaymentIsNeverReversed() throws Exception {
        LoanId loan = current(6_700_00);
        UUID repayment = prepay(loan, key(), 1_000_00).repayment().id();
        Actor operator = employee();
        assertThatExceptionOfType(RepaymentReversals.RepaymentNotReversible.class)
                .isThrownBy(() -> RepaymentReversalDatabaseTest.propose(loan, repayment, operator));
        refusedAlone("INSERT INTO lending.repayment_reversal (id, loan_id, repayment_id, agreement_version, status,"
                + " proposed_by, proposed_by_type, proposed_at, proposal_reason, correlation_id) VALUES (gen_random_uuid(),"
                + " '" + loan.value() + "', '" + repayment + "', 1, 'PROPOSED', 'raw-operator', 'EMPLOYEE', now(), 'raw',"
                + " 'p11t28-raw')", "P0001", "a prepayment is never reversed");
        assertThat(count("SELECT count(*) FROM lending.repayment_reversal WHERE repayment_id = ?", repayment)).isZero();
    }

    @Test
    @DisplayName("a prepayment makes an outstanding payoff quote stale: a quote made before it no longer ends the account -"
            + " 409 PayoffQuoteStale, nothing posted, the loan ACTIVE; a new quote after it does")
    void aPrepaymentMakesAnOutstandingQuoteStale() {
        LoanId loan = current(6_800_00);
        PayoffQuote before = quote(loan, key(), Optional.empty()).quote();
        prepay(loan, key(), 1_000_00);
        assertThatExceptionOfType(Payoffs.PayoffQuoteStale.class).isThrownBy(() -> execute(loan, before, key()));
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM lending.payoff_execution WHERE loan_id = ?", loan.value())).isZero();
        assertSubledgerProves(loan);
    }

    // ------------------------------------------------------------------ the world

    static Prepayments.Prepaid prepay(LoanId loan, String key, long minor) {
        return prepayAs(partyOf(loan), loan, key, minor);
    }

    static Prepayments.Prepaid prepayAs(UUID party, LoanId loan, String key, long minor) {
        return prepayAs(PREPAYMENTS, party, loan, key, minor);
    }

    static Prepayments.Prepaid prepayWith(Prepayments prepayments, LoanId loan, String key, long minor) {
        return prepayAs(prepayments, partyOf(loan), loan, key, minor);
    }

    private static Prepayments.Prepaid prepayAs(Prepayments prepayments, UUID party, LoanId loan, String key, long minor) {
        Actor actor = customer(party);
        CorrelationId correlation = correlation();
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation).causing(CausationId.of(correlation.value())))) {
            return inOneTransaction(uow -> prepayments.prepay(uow, loan, party, new Prepayments.Request(key, eur(minor)),
                    actor, correlation));
        }
    }

    /**
     * A fresh loan re-projected on its day (10) so its first due date is the latest 10th up to today - not yet billed,
     * nothing accrued (a uniform backdate would leave the calendar off its day, which the recalculation refuses) - and an
     * early repayment held as credit, covering the instalment.
     */
    static LoanId dueAndUnbilled(long principal) {
        LoanId loan = InterestAccrualDatabaseTest.disbursed(principal, 12);
        LocalDate today = businessDate(loan);
        LocalDate due = today.withDayOfMonth(10).isAfter(today) ? today.withDayOfMonth(10).minusMonths(1)
                : today.withDayOfMonth(10);
        InstalmentBillingDatabaseTest.reproject(loan, due.minusMonths(1), 10);
        assertThat(dueOf(loan, 1, 1)).isEqualTo(due);
        repay(loan, key(), 2 * instalmentOf(loan, 1));
        assertThat(count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?", loan.value())).isZero();
        return loan;
    }

    /**
     * The real T-ppy whose billing step sees no schedule - so it bills nothing: a writer that skips the billing, which the
     * database must refuse beneath the domain.
     */
    static Prepayments blindToTheSchedule() {
        InstalmentBillingStore blind = (InstalmentBillingStore) java.lang.reflect.Proxy.newProxyInstance(
                InstalmentBillingStore.class.getClassLoader(), new Class<?>[] {InstalmentBillingStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("latestSchedule")) {
                        return Optional.empty();
                    }
                    try {
                        return method.invoke(BILLING_STORE, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        InstalmentBillings billings = new InstalmentBillings(blind, LEDGER_ACCOUNTS, BALANCES, DisbursementWorld.POSTINGS,
                new JdbcOutboxWriter(), IDS, CLOCK);
        return new Prepayments(LOAN_STORE, REPAYMENT_STORE, TERMS_STORE, OFFER_STORE, SERVICING, SERVICING_STORE, billings,
                BILLING_STORE, DISBURSEMENT_STORE, WALLETS, new AvailableBalance<>(new JdbcBalanceDerivation(), HOLD_STORE),
                LEDGER_ACCOUNTS, BALANCES, REPAYMENTS, new JdbcOutboxWriter(), IDS, CLOCK);
    }

    /** The level instalment of the loan's schedule version {@code version}. */
    static long instalmentOf(LoanId loan, int version) {
        return count("SELECT instalment_minor FROM lending.repayment_schedule WHERE loan_id = ? AND schedule_version = ?",
                loan.value(), version);
    }

    /** Every row a prepayment or its catch-up could write, counted - equal before and after a refusal. */
    static String footprint(LoanId loan) {
        UUID id = loan.value();
        return count("SELECT count(*) FROM lending.repayment WHERE loan_id = ?", id) + "/"
                + count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?", id) + "/"
                + count("SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?", id) + "/"
                + count("SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?", id) + "/"
                + count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?", id) + "/"
                + count("SELECT count(*) FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                        + " WHERE a.owner_ref = ?", id);
    }

    /** An application-role PREPAYMENT of {@code minor} on the loan's governing version, dated its business date. */
    static String rawPrepayment(LoanId loan, long minor) {
        return "INSERT INTO lending.repayment (id, loan_id, kind, request_key, currency, scale, amount_minor, agreement_id,"
                + " agreement_version, allocation_engine_version, journal_entry_id, value_date, created_at, correlation_id)"
                + " SELECT gen_random_uuid(), a.loan_id, 'PREPAYMENT', 'p11t28-raw', 'EUR', 2, " + minor + ", a.id, a.version,"
                + " a.allocation_engine_version, gen_random_uuid(), " + businessDateSql(loan) + ", now(), 'p11t28-raw'"
                + " FROM lending.loan_agreement a WHERE a.loan_id = '" + loan.value() + "' ORDER BY a.version DESC LIMIT 1";
    }

    /** An application-role agreement version for {@code prepayment} after the one it made: the latest copied, numbered next. */
    static String rawAgreementFor(UUID prepayment) {
        return "INSERT INTO lending.loan_agreement (id, loan_id, version, offer_id, amendment_id, prepayment_id, decision_id,"
                + " terms_version_id, product, currency, scale, amount_minor, term_months, nominal_annual_rate,"
                + " repayment_day, schedule_engine_version, accrual_engine_version, allocation_engine_version,"
                + " statement_engine_version, agreement_template_id, agreement_template_version, canonical_format,"
                + " canonical_terms, terms_sha256, effective_from, created_at) SELECT gen_random_uuid(), a.loan_id,"
                + " a.version + 1, NULL, NULL, a.prepayment_id, a.decision_id, a.terms_version_id, a.product, a.currency,"
                + " a.scale, a.amount_minor, a.term_months, a.nominal_annual_rate, a.repayment_day,"
                + " a.schedule_engine_version, a.accrual_engine_version, a.allocation_engine_version,"
                + " a.statement_engine_version, a.agreement_template_id, a.agreement_template_version, 4,"
                + " a.canonical_terms, a.terms_sha256, now(), now() FROM lending.loan_agreement a WHERE a.prepayment_id = '"
                + prepayment + "'";
    }
}
