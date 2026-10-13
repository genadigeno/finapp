package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.BALANCES;
import static com.finapp.lending.DisbursementWorld.BILLINGS;
import static com.finapp.lending.DisbursementWorld.BILLING_STORE;
import static com.finapp.lending.DisbursementWorld.CLOCK;
import static com.finapp.lending.DisbursementWorld.DISBURSEMENT_STORE;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.LOAN_STORE;
import static com.finapp.lending.DisbursementWorld.OFFER_STORE;
import static com.finapp.lending.DisbursementWorld.POSTINGS;
import static com.finapp.lending.DisbursementWorld.TERMS_STORE;
import static com.finapp.lending.DisbursementWorld.acceptedLine;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.businessDate;
import static com.finapp.lending.DisbursementWorld.correlation;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.customer;
import static com.finapp.lending.DisbursementWorld.employee;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.InstalmentBillingDatabaseTest.advance;
import static com.finapp.lending.InstalmentBillingDatabaseTest.partyOf;
import static com.finapp.lending.InstalmentBillingDatabaseTest.serviceUntilCaughtUp;
import static com.finapp.lending.InterestAccrualDatabaseTest.SERVICING;
import static com.finapp.lending.InterestAccrualDatabaseTest.SERVICING_STORE;
import static com.finapp.lending.InterestAccrualDatabaseTest.eur;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.RepaymentDatabaseTest.assertSubledgerProves;
import static com.finapp.lending.RepaymentDatabaseTest.due;
import static com.finapp.lending.RepaymentDatabaseTest.events;
import static com.finapp.lending.RepaymentDatabaseTest.key;
import static com.finapp.lending.RepaymentDatabaseTest.loanStatus;
import static com.finapp.lending.RepaymentDatabaseTest.refusedAlone;
import static com.finapp.lending.RepaymentDatabaseTest.repay;
import static com.finapp.lending.RepaymentReversalDatabaseTest.ACTING;
import static com.finapp.lending.RepaymentReversalDatabaseTest.ACTING_PARTY;
import static com.finapp.lending.RepaymentReversalDatabaseTest.UNPLACEABLE;
import static com.finapp.lending.RepaymentReversalDatabaseTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Restructuring by agreement amendment against a real database (`P11-TSK-027`; {@code lending V023}, {@code PHASE_11_PLAN.md}
 * sections 5.3, 7.1 T-amd, 7.4 scenario 8, 12.2 step 7 and 12.8 rule 13; ADR-0099 point 5; {@code INV-LND-05},
 * {@code INV-LND-11}, {@code INV-LND-03}, {@code INV-AUD-04}, {@code INV-HIST-04}).
 *
 * <p>Loans are disbursed by the real T-dis, serviced by the real T-svc and repaid by the real T-rep; amendments go through
 * the real {@link LoanAmendments} over the real ledger. A later business date is {@link InstalmentBillingDatabaseTest#advance}
 * - the fixture's clock - and every planted date derives from the database clock in the pinned zone. The acting party is
 * {@link RepaymentReversalDatabaseTest}'s map. Every case on a loan closes on the subledger proof's arms, rule 13 included.
 */
@Tag("database")
@DisplayName("restructuring by agreement amendment (P11-TSK-027)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class AmendmentDatabaseTest {

    static final JdbcLoanAmendmentStore AMENDMENT_STORE = new JdbcLoanAmendmentStore();

    static final LoanAmendments AMENDMENTS = new LoanAmendments(LOAN_STORE, AMENDMENT_STORE, OFFER_STORE, TERMS_STORE,
            LoanTermsFixtures.BOUNDS, SERVICING, SERVICING_STORE, BILLINGS, BILLING_STORE, DISBURSEMENT_STORE,
            LEDGER_ACCOUNTS, BALANCES, POSTINGS, ACTING_PARTY, new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS, CLOCK);

    // ------------------------------------------------------------------ scenario 8

    @Test
    @DisplayName("scenario 8 - an approved amendment changes the schedule once: on a current loan (its first instalment"
            + " billed and paid) a lower rate over a longer term is proposed, approved by another and accepted by the"
            + " customer - agreement version 2 (the amended rate, the same principal, term = billed + remaining, canonical"
            + " terms format 3 and their hash, an amendment's evidence) and schedule version 2 (from the principal not yet"
            + " billed, starting at the open period, its first due date the old second one) exactly once, one"
            + " lending.LoanAmended, the audits; the next accrual names version 2 and the next billing bills version 2's"
            + " first instalment under agreement 2 - never a version 1 instalment")
    void anApprovedAmendmentChangesTheScheduleOnce() {
        LoanId loan = current(6_000_00);
        BigDecimal rate = lowerRate(loan);
        LocalDate secondDue = dueOf(loan, 1, 2);
        long notDue = netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits();
        LoanAmendment proposed = propose(loan, rate, 18, false, employee());
        assertThat(proposed.status()).isEqualTo(LoanAmendmentStatus.PROPOSED);
        LoanAmendment approved = approve(loan, proposed.id(), employee()).amendment();
        assertThat(approved.status()).isEqualTo(LoanAmendmentStatus.APPROVED);
        assertThat(approved.expiresAt()).isPresent();
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?", loan.value()))
                .as("nothing amended before the customer accepts").isEqualTo(1);

        LoanAmendments.Accepted accepted = accept(loan, proposed.id());
        assertThat(accepted.replayed()).isFalse();
        assertThat(accepted.agreementVersion()).isEqualTo(2);
        assertThat(accepted.scheduleVersion()).isEqualTo(2);
        assertThat(accepted.amendment().status()).isEqualTo(LoanAmendmentStatus.ACCEPTED);
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?", loan.value())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?", loan.value())).isEqualTo(2);
        assertThat(scalar("SELECT nominal_annual_rate FROM lending.loan_agreement WHERE loan_id = ? AND version = 2",
                loan.value())).isEqualTo(rate.toPlainString());
        assertThat(count("SELECT amount_minor FROM lending.loan_agreement WHERE loan_id = ? AND version = 2", loan.value()))
                .as("the same principal").isEqualTo(6_000_00);
        assertThat(count("SELECT term_months FROM lending.loan_agreement WHERE loan_id = ? AND version = 2", loan.value()))
                .as("one billed and eighteen remaining").isEqualTo(19);
        assertThat(count("SELECT canonical_format FROM lending.loan_agreement WHERE loan_id = ? AND version = 2",
                loan.value())).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ? AND version = 2 AND amendment_id = ?"
                + " AND terms_sha256 = sha256(convert_to(canonical_terms, 'UTF8'))"
                + " AND canonical_terms LIKE '%\"amendment\":\"" + proposed.id() + "\"%'", loan.value(), proposed.id()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_acceptance e JOIN lending.loan_agreement a ON a.id ="
                + " e.agreement_id WHERE a.loan_id = ? AND a.version = 2 AND e.offer_id IS NULL"
                + " AND e.document_sha256 = (SELECT document_sha256 FROM lending.loan_amendment WHERE id = ?)",
                loan.value(), proposed.id())).isEqualTo(1);
        assertThat(count("SELECT principal_minor FROM lending.repayment_schedule WHERE loan_id = ? AND schedule_version = 2",
                loan.value())).as("schedule 2 amortises the principal not yet billed").isEqualTo(notDue);
        assertThat(count("SELECT count(*) FROM lending.loan_instalment i JOIN lending.repayment_schedule s ON s.id ="
                + " i.schedule_id WHERE s.loan_id = ? AND s.schedule_version = 2", loan.value())).isEqualTo(18);
        assertThat(dueOf(loan, 2, 1)).as("the open period's due date - the old second one").isEqualTo(secondDue);
        assertThat(events(loan, LoanAmendments.AMENDED_EVENT)).isEqualTo(1);
        for (String action : List.of("lending.AmendmentProposed", "lending.AmendmentApproved", "lending.AmendmentAccepted")) {
            assertThat(audits(proposed.id(), action)).as(action).isEqualTo(1);
        }
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits())
                .as("no arrears re-scheduled: rule 13 not posted").isEqualTo(notDue);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:" + LoanAmendments.POSTING_KEY_PREFIX + proposed.id())).isZero();

        // The next day accrues under version 2; the old second due date bills version 2's first instalment under it.
        advance(loan, 1);
        service(loan);
        assertThat(count("SELECT agreement_version FROM lending.interest_accrual WHERE loan_id = ?"
                + " ORDER BY accrual_date DESC LIMIT 1", loan.value())).isEqualTo(2);
        advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(businessDate(loan), dueOf(loan, 2, 1))));
        serviceUntilCaughtUp(loan);
        assertThat(count("SELECT count(*) FROM lending.instalment_billing b JOIN lending.loan_instalment i"
                + " ON i.id = b.instalment_id JOIN lending.repayment_schedule s ON s.id = i.schedule_id"
                + " WHERE b.loan_id = ? AND s.schedule_version = 2 AND b.agreement_version = 2 AND i.sequence = 1",
                loan.value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.instalment_billing b WHERE b.loan_id = ? AND b.schedule_version = 1",
                loan.value())).as("version 1's unbilled instalments are superseded, never billed").isEqualTo(1);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("four eyes at both ranks: the proposer's approval 403 SelfApprovalRefused, nothing decided; a raw"
            + " self-approval refused by the four-eyes CHECK, a decision copying another proposer by the composite key;"
            + " reasons screened by the domain and by the database; ten approvers, one decision (nine ProposalNotPending)"
            + " and the winner's retry converges; one open amendment per loan (ProposalPending), ten proposers one")
    void fourEyesAtBothRanks() throws Exception {
        LoanId loan = current(6_100_00);
        BigDecimal rate = lowerRate(loan);
        Actor proposer = employee();
        LoanAmendment proposal = propose(loan, rate, 14, false, proposer);
        assertThatExceptionOfType(LoanAmendments.SelfApprovalRefused.class)
                .isThrownBy(() -> approve(loan, proposal.id(), proposer));
        assertThat(decisions(proposal.id())).isZero();
        refusedAlone(rawDecision(proposal.id(), "m.proposed_by", "m.proposed_by"), "23514",
                "loan_amendment_decision_four_eyes");
        refusedAlone(rawDecision(proposal.id(), "'not-the-proposer'", "'another-decider'"), "23503",
                "loan_amendment_decision_amendment_fk");
        assertThatExceptionOfType(LendingCapitalAdministration.ReasonRequired.class).isThrownBy(() -> as(employee(), c ->
                inOneTransaction(uow -> AMENDMENTS.approve(uow, loan, proposal.id(), "per 4111 1111 1111 1111",
                        employee(), c))));
        assertThatExceptionOfType(LoanAmendments.ProposalPending.class)
                .isThrownBy(() -> propose(loan, rate, 13, false, employee()));

        List<String> outcomes = race(10, index -> {
            try {
                return approve(loan, proposal.id(), employee()).replayed() ? "REPLAYED" : "APPROVED";
            } catch (LoanAmendments.ProposalNotPending decided) {
                return "NOT_PENDING";
            }
        });
        assertThat(outcomes).filteredOn("APPROVED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("NOT_PENDING"::equals).hasSize(9);
        assertThat(decisions(proposal.id())).isEqualTo(1);
        assertThat(audits(proposal.id(), "lending.AmendmentApproved")).isEqualTo(1);
        Actor winner = new Actor(scalar("SELECT decided_by FROM lending.loan_amendment_decision WHERE amendment_id = ?",
                proposal.id()), com.finapp.platform.security.ActorType.EMPLOYEE);
        assertThat(approve(loan, proposal.id(), winner).replayed()).as("the same approver's retry converges").isTrue();
        assertThat(decisions(proposal.id())).isEqualTo(1);

        // Ten proposers on another loan: the one-open rule beneath L3 (the trigger takes the lock itself).
        LoanId other = current(6_150_00);
        BigDecimal otherRate = lowerRate(other);
        List<String> proposals = race(10, index -> {
            try {
                propose(other, otherRate, 12 + index, false, employee());
                return "PROPOSED";
            } catch (LoanAmendments.ProposalPending pending) {
                return "PENDING";
            }
        });
        assertThat(proposals).filteredOn("PROPOSED"::equals).hasSize(1);
        assertThat(proposals).filteredOn("PENDING"::equals).hasSize(9);
        LoanAmendment withdrawn = reject(other, AMENDMENT_STORE_ofLoan(other).get(0).id(),
                new Actor(scalar("SELECT proposed_by FROM lending.loan_amendment WHERE loan_id = ?", other.value()),
                        com.finapp.platform.security.ActorType.EMPLOYEE)).amendment();
        assertThat(withdrawn.status()).as("the proposer may withdraw it").isEqualTo(LoanAmendmentStatus.REJECTED);
        refusedAlone("INSERT INTO lending.loan_amendment SELECT gen_random_uuid(), m.loan_id, m.amends_agreement_id,"
                + " m.amends_version, m.nominal_annual_rate, m.remaining_instalments, m.reschedule_arrears,"
                + " m.document_sha256, 'raw', 'EMPLOYEE', now(), 'to DE89 3704 0044 0532 0130 00', 'p11t27-raw'"
                + " FROM lending.loan_amendment m WHERE m.id = '" + withdrawn.id() + "'", "23514", "no_instrument_shape");
        assertSubledgerProves(loan);
        assertSubledgerProves(other);
    }

    @Test
    @DisplayName("a moved agreement lapses the amendment: A approved and parked (its offer expired), B proposed, approved"
            + " and accepted - agreement version 2; A's offer revived, its acceptance is 409 AmendmentLapsed (the version"
            + " conditional, not the clock) and nothing is written; a raw version 3 for A is refused by the database for"
            + " every writer; A reads LAPSED")
    void aMovedAgreementLapsesTheAmendment() throws SQLException {
        LoanId loan = current(6_200_00);
        BigDecimal rate = lowerRate(loan);
        LoanAmendment a = propose(loan, rate, 14, false, employee());
        approve(loan, a.id(), employee());
        expire(a.id());
        LoanAmendment b = propose(loan, rate, 16, false, employee());
        approve(loan, b.id(), employee());
        accept(loan, b.id());
        revive(a.id());
        String hash = read(loan, a.id()).amendment().documentSha256Hex();
        assertThatExceptionOfType(LoanAmendments.AmendmentLapsed.class).isThrownBy(() -> accept(loan, a.id(), hash));
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?", loan.value())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?", loan.value())).isEqualTo(2);
        refusedAlone(rawAmendedAgreement(a.id(), 0), "P0001", "it lapsed");
        assertThat(read(loan, a.id()).amendment().status()).isEqualTo(LoanAmendmentStatus.LAPSED);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("a payoff lapses the amendment: an approved amendment of a loan the customer then pays off - its"
            + " acceptance is 409 AmendmentLapsed on the CLOSED loan (serialised on L3) and nothing is written; and the other"
            + " way round, a payoff quote made before an accepted amendment is 409 PayoffQuoteStale - the agreement moved")
    void aPayoffLapsesTheAmendment() {
        LoanId loan = current(6_300_00);
        LoanAmendment amendment = propose(loan, lowerRate(loan), 14, false, employee());
        approve(loan, amendment.id(), employee());
        String hash = read(loan, amendment.id()).amendment().documentSha256Hex();
        PayoffQuote quote = PayoffDatabaseTest.quote(loan, key(), Optional.empty()).quote();
        RepaymentDatabaseTest.topUp(partyOf(loan), quote.amount().minorUnits());
        PayoffDatabaseTest.execute(loan, quote, key());
        assertThat(loanStatus(loan)).isEqualTo("CLOSED");
        assertThatExceptionOfType(LoanAmendments.AmendmentLapsed.class).isThrownBy(() -> accept(loan, amendment.id(), hash));
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?", loan.value())).isEqualTo(1);
        assertThat(read(loan, amendment.id()).amendment().status()).isEqualTo(LoanAmendmentStatus.LAPSED);

        LoanId other = current(6_350_00);
        PayoffQuote stale = PayoffDatabaseTest.quote(other, key(), Optional.empty()).quote();
        LoanAmendment accepted = propose(other, lowerRate(other), 14, false, employee());
        approve(other, accepted.id(), employee());
        accept(other, accepted.id());
        assertThatExceptionOfType(Payoffs.PayoffQuoteStale.class)
                .isThrownBy(() -> PayoffDatabaseTest.execute(other, stale, key()));
        assertThat(loanStatus(other)).isEqualTo("ACTIVE");
        assertSubledgerProves(other);
    }

    @Test
    @DisplayName("arrears re-scheduled conserve principal: a first instalment ten days past due, its principal unpaid -"
            + " the amendment re-scheduling arrears posts rule 13 once (DR LOAN_PRINCIPAL / CR LOAN_PRINCIPAL_DUE of exactly"
            + " the item's unpaid principal, lending.amendment:<id>), one re-scheduling row naming it; LOAN_PRINCIPAL_DUE is"
            + " left with no principal, LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE unchanged, schedule version 2 amortises exactly"
            + " the new LOAN_PRINCIPAL (its portions summing to it), and the subledger proof's principal arms hold")
    void arrearsRescheduledConservePrincipal() throws SQLException {
        LoanId loan = inArrears(6_400_00);
        long principalDue = netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE)).minorUnits();
        long notDue = netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits();
        assertThat(principalDue).as("billed principal past due").isPositive();
        LoanAmendment amendment = propose(loan, rate(loan), 14, true, employee());
        approve(loan, amendment.id(), employee());
        LoanAmendments.Accepted accepted = accept(loan, amendment.id());

        assertThat(accepted.arrearsRescheduled()).isEqualTo(eur(principalDue));
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE)).minorUnits()).isZero();
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits())
                .isEqualTo(notDue + principalDue);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:" + LoanAmendments.POSTING_KEY_PREFIX + amendment.id())).isEqualTo(1);
        assertThat(count("SELECT coalesce(sum(principal_minor), 0) FROM lending.loan_amendment_rescheduling"
                + " WHERE amendment_id = ?", amendment.id())).isEqualTo(principalDue);
        assertThat(count("SELECT principal_minor FROM lending.repayment_schedule WHERE loan_id = ? AND schedule_version = 2",
                loan.value())).isEqualTo(notDue + principalDue);
        assertThat(count("SELECT sum(i.principal_minor) FROM lending.loan_instalment i JOIN lending.repayment_schedule s"
                + " ON s.id = i.schedule_id WHERE s.loan_id = ? AND s.schedule_version = 2", loan.value()))
                .isEqualTo(notDue + principalDue);
        // Beneath the domain: a schedule of any other principal than the one outstanding is refused for every writer.
        refusedAlone("INSERT INTO lending.repayment_schedule SELECT gen_random_uuid(), s.loan_id, 3, s.agreement_id,"
                + " s.agreement_version, s.schedule_engine_version, s.currency, s.scale, s.principal_minor + 1, s.instalment_minor,"
                + " s.final_instalment_minor, s.total_interest_minor, s.term_months, s.start_date, s.intended_day, now(),"
                + " 'p11t27-raw' FROM lending.repayment_schedule s WHERE s.loan_id = '" + loan.value()
                + "' AND s.schedule_version = 2", "P0001", "principal outstanding not yet billed");
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("billed instalments are kept: the billings, their allocations and their entries are byte-for-byte the"
            + " same after the acceptance; version 1's instalments are kept as rows, the billed one billed, the rest never"
            + " billed afterwards; the billing trigger bills only the latest version, under the agreement version it"
            + " projects")
    void billedInstalmentsAreKept() throws SQLException {
        LoanId loan = current(6_500_00);
        String before = billingRows(loan);
        long version1Rows = count("SELECT count(*) FROM lending.loan_instalment WHERE loan_id = ?", loan.value());
        LoanAmendment amendment = propose(loan, lowerRate(loan), 15, false, employee());
        approve(loan, amendment.id(), employee());
        accept(loan, amendment.id());
        assertThat(billingRows(loan)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM lending.loan_instalment i JOIN lending.repayment_schedule s ON s.id ="
                + " i.schedule_id WHERE s.loan_id = ? AND s.schedule_version = 1", loan.value())).isEqualTo(version1Rows);
        // A raw billing of version 1's next instalment - its due date come - is refused: only the latest version bills.
        refusedAlone("INSERT INTO lending.instalment_billing (id, loan_id, kind, schedule_id, schedule_version, instalment_id,"
                + " sequence, period_start, due_date, currency, scale, interest_due_minor, principal_due_minor,"
                + " agreement_id, agreement_version, schedule_engine_version, accrual_engine_version, journal_entry_id,"
                + " created_at, correlation_id) SELECT gen_random_uuid(), i.loan_id, 'INSTALMENT', s.id, 1, i.id,"
                + " i.sequence, i.period_start, i.period_start, 'EUR', 2, 0, 1, s.agreement_id, 1, 1, 1, NULL, now(),"
                + " 'p11t27-raw' FROM lending.loan_instalment i JOIN lending.repayment_schedule s ON s.id = i.schedule_id"
                + " WHERE s.loan_id = '" + loan.value() + "' AND s.schedule_version = 1 AND i.sequence = 2", "P0001",
                "latest schedule version");
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("an amendment never raises principal or a limit: a credit line's amendment is 422 TermsInvalid, a higher"
            + " rate is 422 TermsInvalid, a shorter remaining term is 422 TermsInvalid, a whole term past the product's"
            + " is 422 TermsInvalid; the accepted version keeps the principal, exposure (LOAN_PRINCIPAL +"
            + " LOAN_PRINCIPAL_DUE) is unchanged; a raw version with a larger principal is refused for every writer")
    void anAmendmentNeverRaisesPrincipalOrLimit() throws SQLException {
        LoanId line = acceptedLine(2_000_00).loan();
        assertThatExceptionOfType(LoanTerms.TermsInvalid.class).isThrownBy(() -> propose(line, new BigDecimal("0.01"), 12,
                false, employee()));
        assertThat(count("SELECT count(*) FROM lending.loan_amendment WHERE loan_id = ?", line.value())).isZero();

        LoanId loan = current(6_600_00);
        BigDecimal current = rate(loan);
        assertThatExceptionOfType(LoanTerms.TermsInvalid.class)
                .isThrownBy(() -> propose(loan, current.add(new BigDecimal("0.01")), 14, false, employee()));
        assertThatExceptionOfType(LoanTerms.TermsInvalid.class)
                .isThrownBy(() -> propose(loan, current, 10, false, employee()));
        assertThatExceptionOfType(LoanTerms.TermsInvalid.class)
                .isThrownBy(() -> propose(loan, current, 60, false, employee()));
        assertThatExceptionOfType(LoanTerms.TermsInvalid.class)
                .as("an amendment that changes nothing").isThrownBy(() -> propose(loan, current, 11, true, employee()));
        long exposure = exposure(loan);
        LoanAmendment amendment = propose(loan, current, 20, false, employee());
        approve(loan, amendment.id(), employee());
        String hash = read(loan, amendment.id()).amendment().documentSha256Hex();
        refusedAlone(rawAmendedAgreement(amendment.id(), 1), "P0001", "never more credit");
        accept(loan, amendment.id(), hash);
        assertThat(count("SELECT count(DISTINCT amount_minor) FROM lending.loan_agreement WHERE loan_id = ?",
                loan.value())).isEqualTo(1);
        assertThat(exposure(loan)).isEqualTo(exposure);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("ten acceptances, one version: ten acceptances of one approved amendment race - one accepts, nine"
            + " converge on it; one agreement version 2, one schedule version 2, one evidence row, one lending.LoanAmended,"
            + " one rule 13 entry")
    void tenAcceptancesOneVersion() throws Exception {
        LoanId loan = inArrears(6_700_00);
        LoanAmendment amendment = propose(loan, lowerRate(loan), 14, true, employee());
        approve(loan, amendment.id(), employee());
        String hash = read(loan, amendment.id()).amendment().documentSha256Hex();
        List<Boolean> replayed = race(10, index -> accept(loan, amendment.id(), hash).replayed());
        assertThat(replayed).filteredOn(Boolean.FALSE::equals).hasSize(1);
        assertThat(replayed).filteredOn(Boolean.TRUE::equals).hasSize(9);
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?", loan.value())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?", loan.value())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lending.loan_acceptance WHERE loan_id = ?", loan.value())).isEqualTo(2);
        assertThat(events(loan, LoanAmendments.AMENDED_EVENT)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:" + LoanAmendments.POSTING_KEY_PREFIX + amendment.id())).isEqualTo(1);
        assertThat(audits(amendment.id(), "lending.AmendmentAccepted")).isEqualTo(1);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("overdue interest is never capitalised: re-scheduling arrears moves principal only - LOAN_INTEREST_DUE and"
            + " LOAN_FEES_DUE are unchanged, the item still owes its interest (and fee), so the loan stays past due until it"
            + " is paid - then it cures; schedule version 2's principal excludes the interest; a raw re-scheduling above"
            + " the item's unpaid principal is refused")
    void overdueInterestIsNeverCapitalised() throws SQLException {
        LoanId loan = inArrears(6_800_00);
        long interestDue = netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_DUE)).minorUnits();
        long feesDue = netDebit(loanAccount(loan, AccountPurpose.LOAN_FEES_DUE)).minorUnits();
        long principal = netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits()
                + netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE)).minorUnits();
        assertThat(interestDue).isPositive();
        LoanAmendment amendment = propose(loan, rate(loan), 13, true, employee());
        approve(loan, amendment.id(), employee());
        // Beneath the domain, inside an acceptance's own writes: a re-scheduling of one unit more than the item's unpaid
        // principal is refused, while exactly the unpaid principal passes (both rolled back).
        try (java.sql.Connection app = com.finapp.platform.testing.database.DatabaseRoles.application()) {
            InterestAccrualDatabaseTest.refused(app, "BEGIN; " + rawAmendedAgreement(amendment.id(), 0) + "; "
                    + rawRescheduling(loan, amendment.id(), 1) + "; ROLLBACK;", "P0001",
                    "exactly its item's unpaid billed principal");
        }
        try (java.sql.Connection app = com.finapp.platform.testing.database.DatabaseRoles.application()) {
            DisbursementWorld.execute(app, "BEGIN; " + rawAmendedAgreement(amendment.id(), 0) + "; "
                    + rawRescheduling(loan, amendment.id(), 0) + "; ROLLBACK;");
        }
        accept(loan, amendment.id());
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_INTEREST_DUE)).minorUnits()).isEqualTo(interestDue);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_FEES_DUE)).minorUnits()).isEqualTo(feesDue);
        assertThat(count("SELECT principal_minor FROM lending.repayment_schedule WHERE loan_id = ? AND schedule_version = 2",
                loan.value())).isEqualTo(principal);
        assertThat(DelinquencyDatabaseTest.read(loan).orElseThrow().daysPastDue())
                .as("the interest still owed keeps the item past due").isPositive();
        refusedAlone(rawRescheduling(loan, amendment.id(), 0), "P0001", "only in the acceptance");
        repay(loan, key(), due(loan));
        assertThat(DelinquencyDatabaseTest.read(loan).orElseThrow().daysPastDue()).as("paid - cured").isZero();
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("the amendment expires unaccepted: an approved amendment whose offer passed its expiry on the database"
            + " clock reads LAPSED, its acceptance is 409 AmendmentLapsed and a raw version for it is refused; it no"
            + " longer holds the loan - a new amendment may be proposed")
    void theAmendmentExpiresUnaccepted() throws SQLException {
        LoanId loan = current(6_900_00);
        BigDecimal rate = lowerRate(loan);
        LoanAmendment amendment = propose(loan, rate, 14, false, employee());
        approve(loan, amendment.id(), employee());
        String hash = read(loan, amendment.id()).amendment().documentSha256Hex();
        expire(amendment.id());
        assertThat(read(loan, amendment.id()).amendment().status()).isEqualTo(LoanAmendmentStatus.LAPSED);
        assertThatExceptionOfType(LoanAmendments.AmendmentLapsed.class).isThrownBy(() -> accept(loan, amendment.id(), hash));
        refusedAlone(rawAmendedAgreement(amendment.id(), 0), "P0001", "unexpired");
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?", loan.value())).isEqualTo(1);
        assertThat(propose(loan, rate, 15, false, employee()).status()).isEqualTo(LoanAmendmentStatus.PROPOSED);
    }

    @Test
    @DisplayName("an employee never amends their own party's loan: an insider's proposal, approval and rejection each"
            + " SelfDealingRefused before anything is written, the attempt recordable FAILED naming the act; an operator"
            + " the platform cannot place in a party is refused too (fail closed); and a customer reads only an amendment"
            + " offered to them - a PROPOSED one is not found")
    void anEmployeeNeverAmendsTheirOwnPartysLoan() {
        LoanId loan = current(7_000_00);
        BigDecimal rate = lowerRate(loan);
        Actor insider = employee();
        ACTING.put(insider.id(), partyOf(loan));
        RepaymentReversals.SelfDealingRefused refused = null;
        try {
            propose(loan, rate, 14, false, insider);
        } catch (RepaymentReversals.SelfDealingRefused selfDealing) {
            refused = selfDealing;
        }
        assertThat(refused).as("the insider's proposal refused").isNotNull();
        RepaymentReversals.SelfDealingRefused attempt = refused;
        assertThat(count("SELECT count(*) FROM lending.loan_amendment WHERE loan_id = ?", loan.value())).isZero();
        inOneTransaction(uow -> {
            AMENDMENTS.recordRefusal(uow, attempt, insider, correlation());
            return null;
        });
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.SelfDealingRefused'"
                + " AND outcome = 'FAILED' AND target_id = ? AND actor_id = ? AND change_summary LIKE '%act=amendment:propose%'",
                loan.value().toString(), insider.id())).isEqualTo(1);

        LoanAmendment proposal = propose(loan, rate, 14, false, employee());
        assertThatExceptionOfType(LoanAmendments.AmendmentNotFound.class).as("not yet offered to the customer")
                .isThrownBy(() -> read(loan, proposal.id()));
        assertThatExceptionOfType(RepaymentReversals.SelfDealingRefused.class)
                .isThrownBy(() -> approve(loan, proposal.id(), insider));
        assertThatExceptionOfType(RepaymentReversals.SelfDealingRefused.class)
                .isThrownBy(() -> reject(loan, proposal.id(), insider));
        Actor nobody = employee();
        UNPLACEABLE.add(nobody.id());
        assertThatExceptionOfType(RepaymentReversals.SelfDealingRefused.class)
                .isThrownBy(() -> approve(loan, proposal.id(), nobody));
        assertThat(decisions(proposal.id())).isZero();
    }

    // ------------------------------------------------------------------ the world

    /**
     * A fresh twelve-month loan whose first instalment fell due on the latest 10th up to today - billed and paid: current,
     * one billed. Its calendar is re-projected on its repayment day (10) - a uniform backdate would leave the monthly
     * calendar off its day, which no real loan is and the recalculation refuses.
     */
    static LoanId current(long minor) {
        LoanId loan = onItsDay(minor, false);
        repay(loan, key(), due(loan));
        assertThat(due(loan)).isZero();
        return loan;
    }

    /** A fresh twelve-month loan whose first instalment, unpaid, fell due on the latest 10th before today: in arrears. */
    static LoanId inArrears(long minor) {
        LoanId loan = onItsDay(minor, true);
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE)).isPositive()).isTrue();
        return loan;
    }

    /**
     * A fresh twelve-month loan disbursed a month before the latest 10th up to today ({@code pastDue}: before today), its
     * calendar on day 10, serviced until caught up - the first instalment billed on that 10th.
     */
    static LoanId onItsDay(long minor, boolean pastDue) {
        LoanId loan = InterestAccrualDatabaseTest.disbursed(minor, 12);
        LocalDate limit = pastDue ? businessDate(loan).minusDays(1) : businessDate(loan);
        LocalDate due = limit.withDayOfMonth(10);
        if (due.isAfter(limit)) {
            due = due.minusMonths(1);
        }
        InstalmentBillingDatabaseTest.reproject(loan, due.minusMonths(1), 10);
        serviceUntilCaughtUp(loan);
        assertThat(dueOf(loan, 1, 1)).isEqualTo(due);
        return loan;
    }

    static BigDecimal rate(LoanId loan) {
        return new BigDecimal(scalar("SELECT nominal_annual_rate FROM lending.loan_agreement WHERE loan_id = ?"
                + " ORDER BY version DESC LIMIT 1", loan.value()));
    }

    /** Half the loan's governing rate, at four places - lower whatever terms another suite left in force. */
    static BigDecimal lowerRate(LoanId loan) {
        BigDecimal half = rate(loan).divide(BigDecimal.valueOf(2), 4, RoundingMode.DOWN);
        return half.signum() == 0 ? BigDecimal.ZERO : half.stripTrailingZeros();
    }

    static LocalDate dueOf(LoanId loan, int scheduleVersion, int sequence) {
        return LocalDate.parse(scalar("SELECT i.due_date::text FROM lending.loan_instalment i JOIN lending.repayment_schedule"
                + " s ON s.id = i.schedule_id WHERE s.loan_id = ? AND s.schedule_version = ? AND i.sequence = ?",
                loan.value(), scheduleVersion, sequence));
    }

    static LoanAmendment propose(LoanId loan, BigDecimal rate, int remaining, boolean arrears, Actor actor) {
        return as(actor, correlation -> inOneTransaction(uow -> AMENDMENTS.propose(uow, loan, rate, remaining, arrears,
                "hardship after a documented job loss", actor, correlation)));
    }

    static LoanAmendments.Decided approve(LoanId loan, UUID amendment, Actor actor) {
        return as(actor, correlation -> inOneTransaction(uow ->
                AMENDMENTS.approve(uow, loan, amendment, "affordability reviewed", actor, correlation)));
    }

    static LoanAmendments.Decided reject(LoanId loan, UUID amendment, Actor actor) {
        return as(actor, correlation -> inOneTransaction(uow ->
                AMENDMENTS.reject(uow, loan, amendment, "not warranted", actor, correlation)));
    }

    static LoanAmendments.Offered read(LoanId loan, UUID amendment) {
        return inOneTransaction(uow -> AMENDMENTS.read(uow, loan, partyOf(loan), amendment));
    }

    static LoanAmendments.Accepted accept(LoanId loan, UUID amendment) {
        return accept(loan, amendment, read(loan, amendment).amendment().documentSha256Hex());
    }

    static LoanAmendments.Accepted accept(LoanId loan, UUID amendment, String hash) {
        UUID party = partyOf(loan);
        Actor actor = customer(party);
        CorrelationId correlation = correlation();
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation).causing(CausationId.of(correlation.value())))) {
            return inOneTransaction(uow -> AMENDMENTS.accept(uow, loan, party, amendment,
                    new LoanAmendments.Acceptance(hash, new AcceptanceEvidence(UUID.randomUUID(), UUID.randomUUID(),
                            "MULTI_FACTOR", Optional.empty(), Optional.empty())), actor, correlation));
        }
    }

    static List<LoanAmendment> AMENDMENT_STORE_ofLoan(LoanId loan) {
        return inOneTransaction(uow -> AMENDMENT_STORE.ofLoan(uow, loan, 10));
    }

    /** The approval's offer moved past its expiry - the fixture's clock moving, never a path. */
    static void expire(UUID amendment) {
        asOwner("UPDATE lending.loan_amendment_decision SET decided_at = statement_timestamp() - interval '20 days',"
                + " expires_at = statement_timestamp() - interval '1 second' WHERE amendment_id = '" + amendment + "'");
    }

    /** The approval's offer moved back within its validity. */
    static void revive(UUID amendment) {
        asOwner("UPDATE lending.loan_amendment_decision SET decided_at = statement_timestamp(),"
                + " expires_at = statement_timestamp() + interval '14 days' WHERE amendment_id = '" + amendment + "'");
    }

    /** LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE - the exposure measure's arm for one loan. */
    static long exposure(LoanId loan) {
        return netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL)).minorUnits()
                + netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE)).minorUnits();
    }

    static String billingRows(LoanId loan) {
        return scalar("SELECT string_agg(row_to_json(b)::text, '|' ORDER BY b.due_date) FROM lending.instalment_billing b"
                + " WHERE b.loan_id = ?", loan.value()) + "#"
                + scalar("SELECT string_agg(row_to_json(a)::text, '|' ORDER BY a.id) FROM lending.repayment_allocation a"
                + " JOIN lending.repayment r ON r.id = a.repayment_id WHERE r.loan_id = ?", loan.value());
    }

    /** An application-role re-scheduling of the loan's oldest past-due item: its unpaid principal plus {@code more}. */
    static String rawRescheduling(LoanId loan, UUID amendment, long more) {
        return "INSERT INTO lending.loan_amendment_rescheduling SELECT gen_random_uuid(), '" + amendment + "', b.loan_id,"
                + " b.id, 'EUR', 2, lending.billing_component_billed(b.id, 'PRINCIPAL')"
                + " - lending.billing_component_paid(b.id, 'PRINCIPAL') + " + more + ", gen_random_uuid(), now(),"
                + " 'p11t27-raw' FROM lending.instalment_billing b WHERE b.loan_id = '" + loan.value() + "' AND b.due_date < "
                + DisbursementWorld.businessDateSql(loan) + " ORDER BY b.due_date LIMIT 1";
    }

    static long decisions(UUID amendment) {
        return count("SELECT count(*) FROM lending.loan_amendment_decision WHERE amendment_id = ?", amendment);
    }

    static long audits(UUID target, String action) {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND target_id = ?", action,
                target.toString());
    }

    /** An application-role decision approving {@code amendment}, copying {@code proposer} and decided by {@code decider}. */
    static String rawDecision(UUID amendment, String proposer, String decider) {
        return "INSERT INTO lending.loan_amendment_decision (id, amendment_id, proposed_by, outcome, decided_by,"
                + " decided_by_type, decided_at, expires_at, decision_reason, correlation_id) SELECT gen_random_uuid(),"
                + " m.id, " + proposer + ", 'APPROVED', " + decider + ", 'EMPLOYEE', now(), now() + interval '1 day',"
                + " 'raw', 'p11t27-raw' FROM lending.loan_amendment m WHERE m.id = '" + amendment + "'";
    }

    /**
     * An application-role agreement version for {@code amendment}: the loan's latest version copied, numbered next, its
     * principal raised by {@code raise} minor units, as format 3 with its hash.
     */
    static String rawAmendedAgreement(UUID amendment, long raise) {
        String canonical = "replace(a.canonical_terms, '{\"format\":\"' || a.canonical_format || '\",', '{\"format\":\"3\",')";
        return "INSERT INTO lending.loan_agreement (id, loan_id, version, offer_id, amendment_id, decision_id,"
                + " terms_version_id, product, currency, scale, amount_minor, term_months, nominal_annual_rate,"
                + " repayment_day, schedule_engine_version, accrual_engine_version, allocation_engine_version,"
                + " statement_engine_version, agreement_template_id, agreement_template_version, canonical_format,"
                + " canonical_terms, terms_sha256, effective_from, created_at) SELECT gen_random_uuid(), a.loan_id,"
                + " a.version + 1, NULL, m.id, a.decision_id, a.terms_version_id, a.product, a.currency, a.scale,"
                + " a.amount_minor + " + raise + ", (SELECT count(*) FROM lending.instalment_billing b WHERE b.loan_id ="
                + " a.loan_id AND b.kind = 'INSTALMENT') + m.remaining_instalments, m.nominal_annual_rate, a.repayment_day,"
                + " a.schedule_engine_version, a.accrual_engine_version, a.allocation_engine_version,"
                + " a.statement_engine_version, a.agreement_template_id, a.agreement_template_version, 3, " + canonical
                + ", sha256(convert_to(" + canonical + ", 'UTF8')), now(), now() FROM lending.loan_amendment m"
                + " JOIN lending.loan_agreement a ON a.loan_id = m.loan_id WHERE m.id = '" + amendment + "'"
                + " AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = m.loan_id)";
    }
}
