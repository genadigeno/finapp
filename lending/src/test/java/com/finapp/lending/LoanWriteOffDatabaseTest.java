package com.finapp.lending;

import static com.finapp.lending.DelinquencyDatabaseTest.dueToday;
import static com.finapp.lending.DisbursementWorld.BALANCES;
import static com.finapp.lending.DisbursementWorld.BILLING_STORE;
import static com.finapp.lending.DisbursementWorld.CLOCK;
import static com.finapp.lending.DisbursementWorld.CLOSURES;
import static com.finapp.lending.DisbursementWorld.CONDITION_STORE;
import static com.finapp.lending.DisbursementWorld.HEADROOM;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.LOANS;
import static com.finapp.lending.DisbursementWorld.LOAN_STORE;
import static com.finapp.lending.DisbursementWorld.POSTINGS;
import static com.finapp.lending.DisbursementWorld.TERMS_STORE;
import static com.finapp.lending.DisbursementWorld.businessDate;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.employee;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.InstalmentBillingDatabaseTest.partyOf;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.RepaymentDatabaseTest.due;
import static com.finapp.lending.RepaymentDatabaseTest.key;
import static com.finapp.lending.RepaymentDatabaseTest.loanStatus;
import static com.finapp.lending.RepaymentDatabaseTest.refusedAlone;
import static com.finapp.lending.RepaymentDatabaseTest.repay;
import static com.finapp.lending.RepaymentDatabaseTest.walletEntries;
import static com.finapp.lending.RepaymentReversalDatabaseTest.ACTING_PARTY;
import static com.finapp.lending.RepaymentReversalDatabaseTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A loan written off against a real database (`P14-TSK-021`; {@code lending V032}; owner decision C6 (A); ADR-0096 rule 14,
 * ADR-0104's Follow-up; {@code INV-LND-01}, {@code INV-AUD-04}, {@code INV-REV-04}, {@code INV-BAL-03}). OWED: written and walked by
 * hand, it has not yet run on PostgreSQL (the phase's standing instruction, C5). The loans are disbursed by the real T-dis, serviced
 * by the real T-svc to a real default at DPD 90 and repaid by the real T-rep. Refusals of the database are asserted raw on the
 * application role's own connection, on the SQLSTATE - never through a store, which drops the driver's text. Every race is a real one,
 * on {@link DisbursementWorld#race}.
 */
@Tag("database")
@DisplayName("a loan written off against a real database (P14-TSK-021)")
class LoanWriteOffDatabaseTest {

    private static final LoanWriteOffs WRITE_OFFS = new LoanWriteOffs(LOAN_STORE, new JdbcLoanWriteOffStore(), CONDITION_STORE,
            TERMS_STORE, BILLING_STORE, LEDGER_ACCOUNTS, BALANCES, CLOSURES, POSTINGS, ACTING_PARTY, new JdbcOutboxWriter(),
            new JdbcAuditWriter(), IDS, CLOCK);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    // ------------------------------------------------------------------ the entry's three

    @Test
    @DisplayName("the headroom does not rise: the principal leaves the outstanding term, enters the written-off term from the journal, and"
            + " the figure stands; interest and fees touch neither; capital carries no line; the proofs hold")
    void theHeadroomDoesNotRise() {
        LoanId loan = defaulted();
        LendingCapitalHeadroom.Headroom before = inOneTransaction(uow -> HEADROOM.atRest(uow, EUR));
        LoanWriteOffs.Proposed proposed = propose(loan, employee());
        LoanWriteOffPosition read = proposed.writeOff().position();
        assertThat(read.principalTotal().isPositive()).isTrue();
        assertThat(read.total().minorUnits()).as("something past principal is written off too, which the headroom must not count")
                .isGreaterThan(read.principalTotal().minorUnits());

        approve(loan, proposed.writeOff().id(), read, employee());

        LendingCapitalHeadroom.Headroom after = inOneTransaction(uow -> HEADROOM.atRest(uow, EUR));
        assertThat(after.headroom()).as("unchanged by the loss").isEqualTo(before.headroom());
        assertThat(after.capital()).isEqualTo(before.capital());
        assertThat(before.outstanding().minus(after.outstanding())).isEqualTo(read.principalTotal());
        assertThat(after.writtenOff().minus(before.writtenOff())).as("principal only, from journal lines").isEqualTo(read.principalTotal());
        assertThat(after.lendingFunded()).as("the wallet money it created stays capital's").isEqualTo(before.lendingFunded());
        assertThat(loanStatus(loan)).isEqualTo("WRITTEN_OFF");
        assertThat(scalar("SELECT coalesce(closed_at::text, 'open') FROM lending.loan WHERE id = ?", loan.value())).isEqualTo("open");
        LendingProofs.Proven proven = LendingProofsWorld.proven(loan);
        assertThat(proven.subledger().holds()).as("%s", proven.subledger()).isTrue();
        assertThat(proven.replay().verdict()).as("%s", proven.replay().findings()).isEqualTo(LoanReplayProof.Verdict.IDENTICAL);
    }

    @Test
    @DisplayName("a repayment race serialises: ten rounds of a repayment against an approval under L3, no deadlock; either the repayment"
            + " wins and the approval is stale, or the approval wins and the repayment is refused with nothing taken - never both, and"
            + " never a repayment lost")
    void aRepaymentRaceSerialises() throws Exception {
        for (int round = 0; round < 10; round++) {
            LoanId loan = defaulted();
            UUID party = partyOf(loan);
            long walletBefore = walletEntries(party);
            LoanWriteOffs.Proposed proposed = propose(loan, employee());
            Actor approver = employee();
            String key = key();
            List<String> outcomes = race(2, index -> {
                try {
                    if (index == 0) {
                        repay(loan, key, 1_00);
                        return "REPAID";
                    }
                    approve(loan, proposed.writeOff().id(), proposed.writeOff().position(), approver);
                    return "APPROVED";
                } catch (LoanWriteOffs.Stale stale) {
                    return "STALE";
                } catch (LoanWrittenOffRefused refused) {
                    return "REFUSED";
                }
            });
            long repayments = count("SELECT count(*) FROM lending.repayment WHERE loan_id = ? AND request_key = ?", loan.value(), key);
            if (loanStatus(loan).equals("WRITTEN_OFF")) {
                assertThat(outcomes).containsExactlyInAnyOrder("REFUSED", "APPROVED");
                assertThat(repayments).as("no repayment beside a write-off").isZero();
                assertThat(walletEntries(party)).as("nothing taken from the wallet").isEqualTo(walletBefore);
            } else {
                assertThat(outcomes).containsExactlyInAnyOrder("REPAID", "STALE");
                assertThat(repayments).isEqualTo(1);
                assertThat(walletEntries(party)).isEqualTo(walletBefore + 1);
            }
            LendingProofs.Proven proven = LendingProofsWorld.proven(loan);
            assertThat(proven.subledger().holds()).as("%s", proven.subledger()).isTrue();
            assertThat(proven.replay().verdict()).as("%s", proven.replay().findings()).isEqualTo(LoanReplayProof.Verdict.IDENTICAL);
        }
    }

    @Test
    @DisplayName("the proposer cannot approve: the domain refuses, and the CHECK refuses every writer on the row, on its SQLSTATE")
    void theProposerCannotApprove() throws Exception {
        LoanId loan = defaulted();
        Actor proposer = employee();
        LoanWriteOffs.Proposed proposed = propose(loan, proposer);
        UUID id = proposed.writeOff().id();
        assertThatExceptionOfType(LoanWriteOffs.SelfApprovalRefused.class)
                .isThrownBy(() -> approve(loan, id, proposed.writeOff().position(), proposer));
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        // What a broken domain would write: its own proposer on an approval, every other clause satisfied. The BEFORE trigger passes
        // it (ACTIVE, the DEFAULT change still the latest, the billed items as named, today's date); the CHECK is what refuses (23514).
        refusedAlone("INSERT INTO lending.loan_write_off_decision (id, write_off_id, loan_id, proposed_by, outcome, decided_by,"
                + " decided_by_type, decided_at, decision_reason, journal_entry_id, value_date, correlation_id) VALUES ('" + IDS.next()
                + "', '" + id + "', '" + loan.value() + "', '" + proposer.id() + "', 'APPROVED', '" + proposer.id() + "', 'EMPLOYEE',"
                + " statement_timestamp(), 'mine', '" + IDS.next() + "', '" + businessDate(loan) + "', 'c')",
                "23514", "loan_write_off_decision_four_eyes");
    }

    // ------------------------------------------------------------------ the races

    @Test
    @DisplayName("ten approvers, one entry: L3 orders them, the decision's unique beneath it, the ledger key beneath that - one approves,"
            + " nine find it decided, one journal entry, one event, one approval's audit record")
    void tenApproversOneEntry() throws Exception {
        LoanId loan = defaulted();
        LoanWriteOffs.Proposed proposed = propose(loan, employee());
        List<Actor> approvers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            approvers.add(employee());
        }
        List<String> outcomes = race(10, index -> {
            try {
                approve(loan, proposed.writeOff().id(), proposed.writeOff().position(), approvers.get(index));
                return "APPROVED";
            } catch (LoanWriteOffs.ProposalNotPending decided) {
                return "DECIDED";
            }
        });
        assertThat(outcomes.stream().filter("APPROVED"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter("DECIDED"::equals).count()).isEqualTo(9);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?", "ledger.post:lending.write-off:"
                + loan.value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_write_off_decision WHERE loan_id = ?", loan.value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.WriteOffApproved' AND target_id = ?",
                proposed.writeOff().id().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_event WHERE to_status = 'WRITTEN_OFF' AND loan_id = ?", loan.value()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an approval against a rejection: L3 orders them, one decision stands and the other finds it decided - nothing half done")
    void anApprovalAgainstARejection() throws Exception {
        LoanId loan = defaulted();
        LoanWriteOffs.Proposed proposed = propose(loan, employee());
        Actor approver = employee();
        Actor rejecter = employee();
        List<String> outcomes = race(2, index -> {
            try {
                if (index == 0) {
                    approve(loan, proposed.writeOff().id(), proposed.writeOff().position(), approver);
                    return "APPROVED";
                }
                reject(loan, proposed.writeOff().id(), rejecter);
                return "REJECTED";
            } catch (LoanWriteOffs.ProposalNotPending decided) {
                return "DECIDED";
            }
        });
        assertThat(outcomes).contains("DECIDED").containsAnyOf("APPROVED", "REJECTED");
        assertThat(loanStatus(loan)).isEqualTo(outcomes.contains("APPROVED") ? "WRITTEN_OFF" : "ACTIVE");
        assertThat(count("SELECT count(*) FROM lending.loan_write_off_decision WHERE loan_id = ?", loan.value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?", "ledger.post:lending.write-off:"
                + loan.value())).isEqualTo(outcomes.contains("APPROVED") ? 1 : 0);
    }

    @Test
    @DisplayName("a servicing step against the approval, under L3: the step finds the account written off and writes nothing, or it runs"
            + " first with nothing left to post that day and the approval goes on - the sweep's step never services it again")
    void aServicingStepRaceSerialises() throws Exception {
        LoanId loan = defaulted();
        LoanWriteOffs.Proposed proposed = propose(loan, employee());
        Actor approver = employee();
        List<String> outcomes = race(2, index -> {
            if (index == 0) {
                return service(loan).kind().name();
            }
            approve(loan, proposed.writeOff().id(), proposed.writeOff().position(), approver);
            return "APPROVED";
        });
        assertThat(outcomes).contains("APPROVED").containsAnyOf("SERVICED", "NOT_SERVICED");
        assertThat(loanStatus(loan)).isEqualTo("WRITTEN_OFF");
        String lines = "SELECT count(*) FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                + " WHERE a.owner_ref = ?";
        long before = count(lines, loan.value());
        assertThat(service(loan).kind()).isEqualTo(LoanServicing.Outcome.Kind.NOT_SERVICED);
        assertThat(count(lines, loan.value())).isEqualTo(before);
        assertThat(LendingProofsWorld.proven(loan).replay().verdict()).isEqualTo(LoanReplayProof.Verdict.IDENTICAL);
    }

    @Test
    @DisplayName("a restructuring accepted between the two acts, arrears not re-scheduled: no journal line moves, the agreement's version does - the"
            + " approval is stale, and V032's trigger refuses the same raw decision for every writer")
    void aRestructuringBetweenTheTwoActsIsStale() throws Exception {
        LoanId loan = defaulted();
        LoanAmendment offered = AmendmentDatabaseTest.propose(loan, AmendmentDatabaseTest.lowerRate(loan), 14, false, employee());
        AmendmentDatabaseTest.approve(loan, offered.id(), employee());
        LoanWriteOffs.Proposed proposed = propose(loan, employee());
        String lines = "SELECT count(*) FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id WHERE a.owner_ref = ?";
        long before = count(lines, loan.value());
        AmendmentDatabaseTest.accept(loan, offered.id());
        assertThat(count(lines, loan.value())).as("the customer's acceptance moved no journal line").isEqualTo(before);

        assertThatExceptionOfType(LoanWriteOffs.Stale.class)
                .isThrownBy(() -> approve(loan, proposed.writeOff().id(), proposed.writeOff().position(), employee()));
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        refusedAlone("INSERT INTO lending.loan_write_off_decision (id, write_off_id, loan_id, proposed_by, outcome, decided_by,"
                + " decided_by_type, decided_at, decision_reason, journal_entry_id, value_date, correlation_id) VALUES ('" + IDS.next()
                + "', '" + proposed.writeOff().id() + "', '" + loan.value() + "', '" + proposed.writeOff().proposedBy() + "', 'APPROVED', 'someone else',"
                + " 'EMPLOYEE', statement_timestamp(), 'agreed', '" + IDS.next() + "', '" + businessDate(loan) + "', 'c')",
                "P0001", "under the agreement version it was proposed on");
    }

    @Test
    @DisplayName("after it every door refuses, nothing moved: a payoff quoted before it, a new quote, a prepayment, a waiver and a reversal left"
            + " standing and a new waiver; an amendment's proposal is refused, and one already proposed or offered reads lapsed at its approval"
            + " and its acceptance, because the status it derives from is no longer ACTIVE")
    void everyDoorRefusesAWrittenOffLoan() throws Exception {
        LoanId loan = defaulted();
        Repayments.Repaid earlier = repay(loan, key(), 1_00);
        RepaymentReversal reversal = RepaymentReversalDatabaseTest.propose(loan, earlier.repayment().id(), employee());
        UUID item = InstalmentBillingDatabaseTest.billings(loan).get(0).billing().id();
        Waiver waiver = FeeAndWaiverDatabaseTest.propose(loan, WaiverKind.INTEREST, item, 1_00, employee());
        PayoffQuote quote = PayoffDatabaseTest.quote(loan, key(), Optional.empty()).quote();
        LoanAmendment offered = AmendmentDatabaseTest.propose(loan, AmendmentDatabaseTest.lowerRate(loan), 14, false, employee());
        AmendmentDatabaseTest.approve(loan, offered.id(), employee());
        LoanId other = defaulted();
        LoanAmendment proposedOnly = AmendmentDatabaseTest.propose(other, AmendmentDatabaseTest.lowerRate(other), 14, false, employee());
        for (LoanId account : List.of(loan, other)) {
            LoanWriteOffs.Proposed proposed = propose(account, employee());
            approve(account, proposed.writeOff().id(), proposed.writeOff().position(), employee());
        }
        String lines = "SELECT count(*) FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id WHERE a.owner_ref = ?";
        long before = count(lines, loan.value());

        assertThatThrownBy(() -> PayoffDatabaseTest.quote(loan, key(), Optional.empty())).isInstanceOf(LoanWrittenOffRefused.class);
        assertThatThrownBy(() -> PayoffDatabaseTest.execute(loan, quote, key())).isInstanceOf(LoanWrittenOffRefused.class);
        assertThatThrownBy(() -> PrepaymentDatabaseTest.prepay(loan, key(), 1_00)).isInstanceOf(LoanWrittenOffRefused.class);
        assertThatThrownBy(() -> FeeAndWaiverDatabaseTest.approve(loan, waiver.id(), employee())).isInstanceOf(LoanWrittenOffRefused.class);
        assertThatThrownBy(() -> FeeAndWaiverDatabaseTest.propose(loan, WaiverKind.INTEREST, item, 1_00, employee()))
                .isInstanceOf(LoanWrittenOffRefused.class);
        assertThatThrownBy(() -> RepaymentReversalDatabaseTest.approve(loan, earlier.repayment().id(), reversal.id(), employee()))
                .isInstanceOf(LoanWrittenOffRefused.class);
        assertThatThrownBy(() -> AmendmentDatabaseTest.propose(loan, AmendmentDatabaseTest.lowerRate(loan), 13, false, employee()))
                .isInstanceOf(LoanWrittenOffRefused.class);
        assertThatExceptionOfType(LoanAmendments.AmendmentLapsed.class).as("the offer's acceptance")
                .isThrownBy(() -> AmendmentDatabaseTest.accept(loan, offered.id(), offered.documentSha256Hex()));
        assertThatExceptionOfType(LoanAmendments.AmendmentLapsed.class).as("the proposal's approval")
                .isThrownBy(() -> AmendmentDatabaseTest.approve(other, proposedOnly.id(), employee()));
        assertThat(count(lines, loan.value())).as("nothing moved").isEqualTo(before);
    }

    // ------------------------------------------------------------------ what it refuses, and the database beneath

    @Test
    @DisplayName("a current loan is refused by the domain, and a raw proposal naming no DEFAULT change by the trigger; the edge without its"
            + " approved decision is refused, nothing leaves WRITTEN_OFF, and neither table takes an UPDATE or a DELETE")
    void theDatabaseHoldsTheRanks() throws Exception {
        LoanId current = dueToday(1_000_00);
        assertThatExceptionOfType(LoanWriteOffs.NotDefaulted.class).isThrownBy(() -> propose(current, employee()));
        refusedAlone("UPDATE lending.loan SET status = 'WRITTEN_OFF' WHERE id = '" + current.value() + "'", "P0001",
                "only beside its approved write-off");
        String version = scalar("SELECT max(version)::text FROM lending.loan_agreement WHERE loan_id = ?", current.value());
        refusedAlone("INSERT INTO lending.loan_write_off (id, loan_id, currency, scale, principal_minor, principal_due_minor,"
                + " interest_accrued_minor, interest_due_minor, fees_due_minor, revision, default_event_id, agreement_version, proposed_by,"
                + " proposed_by_type, proposed_at, proposal_reason, correlation_id) VALUES ('" + IDS.next() + "', '" + current.value()
                + "', 'EUR', 2, 1, 0, 0, 0, 0, 1, '" + IDS.next() + "', " + version + ", 'a', 'EMPLOYEE', statement_timestamp(), 'r',"
                + " 'c')", "P0001", "latest DEFAULT change");

        LoanId loan = defaulted();
        LoanWriteOffs.Proposed proposed = propose(loan, employee());
        approve(loan, proposed.writeOff().id(), proposed.writeOff().position(), employee());
        refusedAlone("UPDATE lending.loan SET status = 'ACTIVE' WHERE id = '" + loan.value() + "'", "P0001", "not an edge");
        refusedAlone("UPDATE lending.loan SET next_servicing_at = statement_timestamp() WHERE id = '" + loan.value() + "'", "P0001",
                "moves only its servicing permit");
        refusedAlone("UPDATE lending.loan_write_off SET revision = 1 WHERE id = '" + proposed.writeOff().id() + "'", "42501",
                "permission denied");
        refusedAlone("DELETE FROM lending.loan_write_off_decision WHERE write_off_id = '" + proposed.writeOff().id() + "'", "42501",
                "permission denied");
    }

    @Test
    @DisplayName("a proposal that can no longer post has a door out: a repayment moves the figures, the approval is stale and the proposal"
            + " stands; a second proposal is refused while it awaits; the rejection decides it, and the next proposal reads the figures as"
            + " they are")
    void aProposalThatCanNoLongerPostHasADoorOut() {
        LoanId loan = defaulted();
        LoanWriteOffs.Proposed stale = propose(loan, employee());
        repay(loan, key(), 1_00);
        assertThatExceptionOfType(LoanWriteOffs.Stale.class)
                .isThrownBy(() -> approve(loan, stale.writeOff().id(), stale.writeOff().position(), employee()));
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        assertThatExceptionOfType(LoanWriteOffs.ProposalPending.class).isThrownBy(() -> propose(loan, employee()));
        assertThat(reject(loan, stale.writeOff().id(), employee()).writeOff().status()).isEqualTo(LoanWriteOffStatus.REJECTED);
        LoanWriteOffs.Proposed next = propose(loan, employee());
        assertThat(next.writeOff().position().total().minorUnits())
                .isEqualTo(stale.writeOff().position().total().minorUnits() - 1_00);
        assertThat(next.writeOff().position().revision()).isGreaterThan(stale.writeOff().position().revision());
        approve(loan, next.writeOff().id(), next.writeOff().position(), employee());
        assertThat(loanStatus(loan)).isEqualTo("WRITTEN_OFF");
    }

    @Test
    @DisplayName("after it: a repayment is refused with nothing taken from the wallet, a reversal of an earlier repayment is refused, and the"
            + " customer's read names what stands owed - never zero - though the ledger's due accounts are empty")
    void afterItTheCustomerStillOwes() {
        LoanId loan = defaulted();
        Repayments.Repaid earlier = repay(loan, key(), 1_00);
        LoanWriteOffs.Proposed proposed = propose(loan, employee());
        LoanWriteOffPosition read = proposed.writeOff().position();
        approve(loan, proposed.writeOff().id(), read, employee());

        UUID party = partyOf(loan);
        long walletBefore = walletEntries(party);
        assertThatThrownBy(() -> repay(loan, key(), 1_00)).isInstanceOf(LoanWrittenOffRefused.class);
        assertThat(walletEntries(party)).isEqualTo(walletBefore);
        assertThatThrownBy(() -> RepaymentReversalDatabaseTest.propose(loan, earlier.repayment().id(), employee()))
                .isInstanceOf(LoanWrittenOffRefused.class);

        Loans.Read customer = inOneTransaction(uow -> LOANS.read(uow, loan, party)).orElseThrow();
        assertThat(customer.balances().principalOutstanding()).isEqualTo(read.principalTotal());
        assertThat(customer.owned().loan().closedAt()).isEmpty();
        assertThat(customer.condition()).as("no condition is derived once out of servicing").isEmpty();
        assertThat(due(loan)).as("on the ledger the due accounts are empty; what is owed is the customer's read").isZero();
    }

    @Test
    @DisplayName("an earlier date does not move: the entry is dated today, so a fold through yesterday is what it was, and the status history"
            + " has no WRITTEN_OFF as of yesterday")
    void anEarlierDateDoesNotMove() {
        LoanId loan = defaulted();
        String yesterday = "DATE '" + businessDate(loan).minusDays(1) + "'";
        String before = lineFold(loan, yesterday);
        LoanWriteOffs.Proposed proposed = propose(loan, employee());
        approve(loan, proposed.writeOff().id(), proposed.writeOff().position(), employee());
        assertThat(lineFold(loan, yesterday)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM lending.loan_event e WHERE e.loan_id = ? AND e.to_status = 'WRITTEN_OFF'"
                + " AND (e.occurred_at AT TIME ZONE 'UTC')::date <= " + yesterday, loan.value())).isZero();
        assertThat(count("SELECT count(*) FROM lending.loan_write_off_decision WHERE outcome = 'APPROVED' AND loan_id = ? AND value_date = ?",
                loan.value(), businessDate(loan))).as("the row is dated as the entry is").isEqualTo(1);
    }

    // ------------------------------------------------------------------ plumbing

    private static String lineFold(LoanId loan, String through) {
        return scalar("SELECT coalesce(sum(CASE WHEN ln.direction = 'DEBIT' THEN ln.amount_minor ELSE -ln.amount_minor END), 0)::text"
                + " FROM ledger.ledger_account a JOIN ledger.journal_line ln ON ln.ledger_account_id = a.id"
                + " JOIN ledger.journal_entry e ON e.id = ln.entry_id WHERE a.owner_ref = ? AND e.posting_date <= " + through, loan.value());
    }

    /**
     * A fresh loan defaulted by the real servicing step, its calendar on its repayment day: disbursed by T-dis, its schedule
     * re-projected from a month before the latest repayment day at least ninety days back (as {@code AmendmentDatabaseTest#onItsDay}
     * places one), then serviced until caught up - every elapsed date accrued, every instalment due billed, the condition derived at
     * today's DPD of ninety or more. Re-projected, never shifted: {@code dueToday} and {@code advance} move every date by the same
     * number of days, which leaves the monthly calendar off its day - no real loan is; the replay names such a schedule SCHEDULE
     * ({@code RepaymentDatabaseTest#assertApplicationsReplay}) and an amendment's recalculation refuses it INVALID_REQUEST, so a
     * case asserting either read the fixture, never the write-off.
     */
    private static LoanId defaulted() {
        LoanId loan = InterestAccrualDatabaseTest.disbursed(5_000_00, 12);
        int repaymentDay = Math.toIntExact(count("SELECT repayment_day FROM lending.loan_agreement WHERE loan_id = ? AND version = 1",
                loan.value()));
        LocalDate limit = businessDate(loan).minusDays(90);
        LocalDate due = limit.withDayOfMonth(Math.min(repaymentDay, limit.lengthOfMonth()));
        if (due.isAfter(limit)) {
            due = due.minusMonths(1);
        }
        InstalmentBillingDatabaseTest.reproject(loan, due.minusMonths(1), repaymentDay);
        InstalmentBillingDatabaseTest.serviceUntilCaughtUp(loan);
        assertThat(scalar("SELECT min(due_date)::text FROM lending.loan_instalment WHERE loan_id = ?", loan.value()))
                .as("the first instalment fell due ninety days or more before today").isEqualTo(due.toString());
        assertThat(count("SELECT count(*) FROM lending.loan_condition_event WHERE kind = 'DEFAULT' AND defaulted AND loan_id = ?",
                loan.value())).as("defaulted at ninety").isEqualTo(1);
        return loan;
    }

    private static LoanWriteOffs.Proposed propose(LoanId loan, Actor actor) {
        return as(actor, correlation -> inOneTransaction(uow ->
                WRITE_OFFS.propose(uow, loan, "uncollectable after collections", actor, correlation)));
    }

    private static LoanWriteOffs.Decided approve(LoanId loan, UUID id, LoanWriteOffPosition attested, Actor actor) {
        return as(actor, correlation -> inOneTransaction(uow ->
                WRITE_OFFS.approve(uow, loan, id, attested, "read and agreed", actor, correlation)));
    }

    private static LoanWriteOffs.Decided reject(LoanId loan, UUID id, Actor actor) {
        return as(actor, correlation -> inOneTransaction(uow -> WRITE_OFFS.reject(uow, loan, id, "not this one", actor, correlation)));
    }
}
