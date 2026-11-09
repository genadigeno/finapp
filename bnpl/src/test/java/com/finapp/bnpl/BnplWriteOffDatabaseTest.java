package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.count;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.refused;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A BNPL obligation written off against {@code bnpl V020}'s ranks (`P14-TSK-021`; owner decision C6 (A); ADR-0103 F11;
 * {@code INV-BNPL-01}, {@code INV-AUD-04}, {@code INV-REV-04}): T-wof over bnpl's JDBC stores on a moved database clock, to a real
 * default at DPD 90 by the real servicing step. The book is the in-memory one behind a lock, as in {@code BnplWaiverDatabaseTest} - the
 * entry over the real ledger, and the headroom, are {@code app}'s {@code BnplWriteOffLedgerDatabaseTest}.
 *
 * <p>Written by `P14-TSK-021` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction, C5). The database's
 * refusals are asserted raw on a framed application connection, on the SQLSTATE; every race runs concurrently, under B4.
 */
@Tag("database")
@DisplayName("a BNPL obligation written off against bnpl V020's ranks (P14-TSK-021)")
class BnplWriteOffDatabaseTest {

    private final BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
    private final BnplWriteOffs writeOffs = new BnplWriteOffs(BnplCorrectionDatabaseWorld.CORRECTIONS,
            BnplConditionDatabaseWorld.SERVICING, BnplConditionDatabaseWorld.PURCHASES, new JdbcBnplWriteOffStore(),
            BnplConditionDatabaseWorld.CONDITIONS, BnplConditionDatabaseWorld.TERMS_STORE, new JdbcBnplDisputeStore(), world.base.book,
            (uow, actor) -> Optional.ofNullable(world.operatorParties.get(actor.id())), new JdbcOutboxWriter(), new JdbcAuditWriter(),
            BnplConditionDatabaseWorld.IDS, BnplConditionDatabaseWorld.CLOCK);

    /** The real dispute door: a dispute commits beside its claim (V015's deferred bnpl_dispute_is_whole), and openOf reads through it. */
    private final BnplDisputes disputes = new BnplDisputes(new JdbcBnplDisputeStore(), BnplConditionDatabaseWorld.REFUNDS,
            BnplConditionDatabaseWorld.SERVICING, BnplConditionDatabaseWorld.PURCHASES, BnplConditionDatabaseWorld.TERMS_STORE, world.base.refunds,
            world.base.ports, world.base.book, (uow, actor) -> Optional.ofNullable(world.operatorParties.get(actor.id())), new JdbcOutboxWriter(),
            new JdbcAuditWriter(), BnplConditionDatabaseWorld.IDS, BnplConditionDatabaseWorld.CLOCK);

    @BeforeAll
    static void termsAndClock() {
        BnplBillingDatabaseTest.termsAndClock();
    }

    @AfterAll
    static void putTheClockBack() {
        BnplClockFrame.uninstall();
    }

    @Test
    @DisplayName("written off whole: every receivable to zero on the book, the status its own and never closed, one event, one audit record,"
            + " and the rows' figures as the approver named them")
    void writtenOffWhole() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = defaulted();
        LocalDate day = dayOf(bought);
        BnplWriteOffs.Proposed proposed = propose(day, bought, world.operator());
        BnplWriteOffPosition read = proposed.writeOff().position();
        assertThat(read.principalTotal().isPositive()).isTrue();

        BnplWriteOffs.Decided decided = approve(day, bought, proposed.writeOff().id(), read, world.operator());

        assertThat(decided.writeOff().status()).isEqualTo(BnplCorrectionStatus.APPROVED);
        for (AccountPurpose purpose : List.of(AccountPurpose.BNPL_PRINCIPAL, AccountPurpose.BNPL_PRINCIPAL_DUE,
                AccountPurpose.BNPL_INTEREST_ACCRUED, AccountPurpose.BNPL_INTEREST_DUE, AccountPurpose.BNPL_FEES_DUE)) {
            assertThat(world.base.balance(bought, purpose).isZero()).as(purpose.name()).isTrue();
        }
        assertThat(scalar("SELECT status || ':' || (closed_at IS NULL) || ':' || (closure_reason IS NULL) FROM bnpl.bnpl_obligation WHERE id = '"
                + bought.id() + "'")).isEqualTo("WRITTEN_OFF:true:true");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = '" + bought.id() + "' AND event_type = '"
                + BnplWriteOffs.WRITTEN_OFF_EVENT + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'bnpl.WriteOffApproved' AND target_id = '"
                + proposed.writeOff().id() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_write_off_decision WHERE outcome = 'APPROVED' AND value_date = DATE '" + day
                + "' AND obligation_id = '" + bought.id() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a repayment race serialises under B4: five rounds of a repayment against an approval - the repayment wins and the approval is"
            + " stale, or the approval wins and the repayment is refused with nothing written; never both")
    void aRepaymentRaceSerialises() throws Exception {
        for (int round = 0; round < 5; round++) {
            BnplConditionDatabaseWorld.Purchased bought = defaulted();
            LocalDate day = dayOf(bought);
            BnplWriteOffs.Proposed proposed = propose(day, bought, world.operator());
            Actor approver = world.operator();
            String key = "race-" + round;
            List<String> outcomes = world.base.concurrently(2, index -> {
                try {
                    if (index == 0) {
                        world.base.repayOn(day, bought, key, 1_00);
                        return "REPAID";
                    }
                    approve(day, bought, proposed.writeOff().id(), proposed.writeOff().position(), approver);
                    return "APPROVED";
                } catch (BnplWriteOffs.Stale stale) {
                    return "STALE";
                } catch (BnplWrittenOffRefused refusedDoor) {
                    return "REFUSED";
                }
            });
            String status = scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'");
            // The racing repayment's own row, by its kind and key: a pay-in-4 obligation already holds instalment 1's AT_PURCHASE
            // repayment from its birth (bnpl V006), which is neither the race's nor anything a write-off refuses.
            long repayments = count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id()
                    + "' AND kind = 'CUSTOMER' AND request_key = '" + key + "'");
            assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = '" + bought.id() + "' AND kind <> 'AT_PURCHASE'"))
                    .as("no repayment but the race's").isEqualTo(repayments);
            if (status.equals("WRITTEN_OFF")) {
                assertThat(outcomes).containsExactlyInAnyOrder("REFUSED", "APPROVED");
                assertThat(repayments).isZero();
            } else {
                assertThat(outcomes).containsExactlyInAnyOrder("REPAID", "STALE");
                assertThat(repayments).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("the proposer cannot approve: the domain refuses, and the CHECK refuses every writer on the row, on its SQLSTATE")
    void theProposerCannotApprove() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = defaulted();
        LocalDate day = dayOf(bought);
        Actor proposer = world.operator();
        BnplWriteOffs.Proposed proposed = propose(day, bought, proposer);
        assertThatExceptionOfType(BnplWriteOffs.SelfApprovalRefused.class)
                .isThrownBy(() -> approve(day, bought, proposed.writeOff().id(), proposed.writeOff().position(), proposer));
        // What a broken domain would write: every clause the BEFORE trigger judges holds on the framed clock; the CHECK is what refuses.
        refusedFramed(day, "INSERT INTO bnpl.bnpl_write_off_decision (id, write_off_id, obligation_id, proposed_by, outcome, decided_by,"
                + " decided_by_type, decided_at, decision_reason, journal_entry_id, value_date, correlation_id) VALUES ('" + UUID.randomUUID()
                + "', '" + proposed.writeOff().id() + "', '" + bought.id() + "', '" + proposer.id() + "', 'APPROVED', '" + proposer.id()
                + "', 'CUSTOMER', clock_timestamp(), 'mine', '" + UUID.randomUUID() + "', DATE '" + day + "', 'c')",
                "23514", "bnpl_write_off_decision_four_eyes");
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("ten approvers, one decision: B4 orders them, the decision's unique beneath it - one approves, nine find it decided, one event")
    void tenApproversOneDecision() throws Exception {
        BnplConditionDatabaseWorld.Purchased bought = defaulted();
        LocalDate day = dayOf(bought);
        BnplWriteOffs.Proposed proposed = propose(day, bought, world.operator());
        List<Actor> approvers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            approvers.add(world.operator());
        }
        List<String> outcomes = world.base.concurrently(10, index -> {
            try {
                approve(day, bought, proposed.writeOff().id(), proposed.writeOff().position(), approvers.get(index));
                return "APPROVED";
            } catch (BnplWriteOffs.ProposalNotPending decided) {
                return "DECIDED";
            }
        });
        assertThat(outcomes.stream().filter("APPROVED"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter("DECIDED"::equals).count()).isEqualTo(9);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_write_off_decision WHERE obligation_id = '" + bought.id() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = '" + bought.id() + "' AND event_type = '"
                + BnplWriteOffs.WRITTEN_OFF_EVENT + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("an approval against a rejection: B4 orders them, one decision stands and the other finds it decided")
    void anApprovalAgainstARejection() throws Exception {
        BnplConditionDatabaseWorld.Purchased bought = defaulted();
        LocalDate day = dayOf(bought);
        BnplWriteOffs.Proposed proposed = propose(day, bought, world.operator());
        Actor approver = world.operator();
        Actor rejecter = world.operator();
        List<String> outcomes = world.base.concurrently(2, index -> {
            try {
                if (index == 0) {
                    approve(day, bought, proposed.writeOff().id(), proposed.writeOff().position(), approver);
                    return "APPROVED";
                }
                reject(day, bought, proposed.writeOff().id(), rejecter);
                return "REJECTED";
            } catch (BnplWriteOffs.ProposalNotPending decided) {
                return "DECIDED";
            }
        });
        assertThat(outcomes).contains("DECIDED").containsAnyOf("APPROVED", "REJECTED");
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'"))
                .isEqualTo(outcomes.contains("APPROVED") ? "WRITTEN_OFF" : "ACTIVE");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_write_off_decision WHERE obligation_id = '" + bought.id() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a servicing step against the approval, under B4: both complete, the obligation is written off, and the step after it"
            + " finds it out of servicing and writes nothing")
    void aServicingStepRaceSerialises() throws Exception {
        BnplConditionDatabaseWorld.Purchased bought = defaulted();
        LocalDate day = dayOf(bought);
        BnplWriteOffs.Proposed proposed = propose(day, bought, world.operator());
        Actor approver = world.operator();
        List<String> outcomes = world.base.concurrently(2, index -> {
            if (index == 0) {
                return world.base.serviceOn(day, bought).kind().name();
            }
            approve(day, bought, proposed.writeOff().id(), proposed.writeOff().position(), approver);
            return "APPROVED";
        });
        assertThat(outcomes).contains("APPROVED");
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'")).isEqualTo("WRITTEN_OFF");
        assertThat(world.base.serviceOn(day.plusDays(1), bought).kind()).isEqualTo(ServicingOutcome.Kind.NOT_SERVICED);
    }

    @Test
    @DisplayName("an open dispute blocks both acts: the proposal is refused, and an approval whose dispute opened after its proposal is refused,"
            + " by the domain under B4; V020's two ranks refuse the same raw rows for every writer; the rejection is the door out")
    void anOpenDisputeBlocksBothActs() throws SQLException {
        BnplConditionDatabaseWorld.Purchased disputedFirst = defaulted();
        LocalDate day = dayOf(disputedFirst);
        opened(day, disputedFirst);
        assertThatExceptionOfType(BnplWriteOffs.DisputeOpen.class).isThrownBy(() -> propose(day, disputedFirst, world.operator()));
        refusedFramed(day, rawProposal(disputedFirst), "P0001", "proposed only with no dispute open");

        BnplConditionDatabaseWorld.Purchased disputedAfter = defaulted();
        LocalDate later = dayOf(disputedAfter);
        BnplWriteOffs.Proposed proposed = propose(later, disputedAfter, world.operator());
        opened(later, disputedAfter);
        assertThatExceptionOfType(BnplWriteOffs.DisputeOpen.class).isThrownBy(() ->
                approve(later, disputedAfter, proposed.writeOff().id(), proposed.writeOff().position(), world.operator()));
        refusedFramed(later, rawApproval(later, disputedAfter, proposed), "P0001", "approved only with no dispute open");
        assertThat(reject(later, disputedAfter, proposed.writeOff().id(), world.operator()).writeOff().status())
                .as("the door out").isEqualTo(BnplCorrectionStatus.REJECTED);
    }

    @Test
    @DisplayName("a refund moves the agreement's version with its lines: what was proposed before it is stale in the domain, and V020's rank refuses"
            + " the same raw decision on the version alone")
    void aRefundMovesTheVersion() throws SQLException {
        // A refund moves the agreement's version only when it cancels or shortens an unbilled instalment (BnplRefunds; ADR-0105
        // decision 2.4, INV-HIST-04) - a billed instalment is kept as its billing billed it. A pay-in-4 obligation at DPD 90 has
        // every instalment billed (its last falls due 28 days after its second), so no refund of it can move the version: the
        // case is a pay-in-6 obligation defaulted on its first instalment, whose last instalments are still unbilled.
        BnplConditionDatabaseWorld.Purchased bought = defaultedPayInSix();
        LocalDate day = bought.due(1).plusDays(90);
        BnplWriteOffs.Proposed proposed = propose(day, bought, world.operator());
        long version = count("SELECT max(version) FROM bnpl.bnpl_agreement WHERE obligation_id = '" + bought.id() + "'");
        String billedDue = "SELECT coalesce(sum(bnpl.billing_component_billed(b.id, 'PRINCIPAL') - bnpl.billing_component_paid(b.id,"
                + " 'PRINCIPAL')), 0) || ':' || coalesce(sum(bnpl.billing_component_billed(b.id, 'INTEREST')"
                + " - bnpl.billing_component_paid(b.id, 'INTEREST')), 0) || ':' || coalesce(sum(bnpl.billing_component_billed(b.id, 'FEES')"
                + " - bnpl.billing_component_paid(b.id, 'FEES')), 0) FROM bnpl.bnpl_billing b WHERE b.obligation_id = '" + bought.id() + "'";
        String dueBefore = scalar(billedDue);
        world.base.refundOn(day, bought, "refund-before", 50_00);
        assertThat(count("SELECT max(version) FROM bnpl.bnpl_agreement WHERE obligation_id = '" + bought.id() + "'")).isEqualTo(version + 1);
        assertThat(scalar(billedDue)).as("the refund shortened an unbilled instalment only: what the billed items have due stands, so the"
                + " raw decision below differs from an admissible one on the version alone").isEqualTo(dueBefore);
        assertThat(dueBefore).isEqualTo(proposed.writeOff().position().principalDue().minorUnits() + ":"
                + proposed.writeOff().position().interestDue().minorUnits() + ":" + proposed.writeOff().position().feesDue().minorUnits());
        assertThatExceptionOfType(BnplWriteOffs.Stale.class).isThrownBy(() ->
                approve(day, bought, proposed.writeOff().id(), proposed.writeOff().position(), world.operator()));
        refusedFramed(day, rawApproval(day, bought, proposed), "P0001", "under the agreement version it was proposed on");
    }

    @Test
    @DisplayName("after it: a repayment, a merchant refund, a payoff quoted before it, a reversal and a waiver left standing and a new waiver are"
            + " each refused by the domain with nothing written, and a raw dispute by the trigger for every writer; the merchant's money stays on"
            + " its payable")
    void afterItEveryDoorRefuses() throws SQLException {
        BnplConditionDatabaseWorld.Purchased bought = world.base.feeBearing();
        world.base.serviceOn(bought.due(2), bought);
        world.base.serviceOn(bought.due(2).plusDays(5), bought);
        BnplFeeAssessment fee = world.fees(bought).get(0);
        world.base.serviceOn(dayOf(bought), bought);
        LocalDate day = dayOf(bought);
        BnplRepayments.Repaid earlier = world.base.repayOn(day, bought, "earlier", 1_00);
        BnplRepaymentReversal standing = world.propose(day, bought, earlier.repayment().id(), world.operator());
        BnplWaiver waiver = world.proposeWaiver(day, bought, fee.id(), 1_00, world.operator());
        BnplPayoffQuote quote = BnplClockFrame.inOneTransaction(world.base.frameOn(day), uow -> world.base.payoffs.quote(uow, bought.agreement(),
                bought.party(), new BnplPayoffs.QuoteRequest("quote-before", Optional.empty()), BnplAcceptanceDatabaseTest.correlation())).quote();
        BnplWriteOffs.Proposed proposed = propose(day, bought, world.operator());
        approve(day, bought, proposed.writeOff().id(), proposed.writeOff().position(), world.operator());

        assertThatExceptionOfType(BnplWrittenOffRefused.class).isThrownBy(() -> world.approve(day, bought, earlier.repayment().id(), standing.id(),
                world.operator()));
        assertThatExceptionOfType(BnplWrittenOffRefused.class).isThrownBy(() -> world.approveWaiver(day, bought, waiver.id(), world.operator()));
        assertThatExceptionOfType(BnplWrittenOffRefused.class).isThrownBy(() -> world.proposeWaiver(day, bought, fee.id(), 1_00, world.operator()));
        assertThatExceptionOfType(BnplWrittenOffRefused.class).isThrownBy(() -> BnplClockFrame.inOneTransaction(world.base.frameOn(day),
                uow -> world.base.payoffs.execute(uow, bought.agreement(), bought.party(), "execute-after", quote.id(),
                        BnplAcceptanceDatabaseTest.customer(bought.party()), BnplAcceptanceDatabaseTest.correlation())));

        assertThatExceptionOfType(BnplWrittenOffRefused.class).isThrownBy(() -> world.base.repayOn(day, bought, "after", 1_00));
        assertThatExceptionOfType(BnplWrittenOffRefused.class).isThrownBy(() -> world.base.refundOn(day, bought, "refund", 1_00));
        assertThatExceptionOfType(BnplWrittenOffRefused.class)
                .isThrownBy(() -> world.propose(day, bought, earlier.repayment().id(), world.operator()));
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_refund WHERE obligation_id = '" + bought.id() + "'")).isZero();
        refusedFramed(day, rawDispute(bought), "P0001", "takes no refund and no dispute");
    }

    @Test
    @DisplayName("a proposal that can no longer post has a door out: a repayment moves the figures, the approval is stale and the proposal"
            + " stands; a second proposal is refused while it awaits; the rejection decides it, and the next proposal reads what is there")
    void aProposalThatCanNoLongerPostHasADoorOut() {
        BnplConditionDatabaseWorld.Purchased bought = defaulted();
        LocalDate day = dayOf(bought);
        BnplWriteOffs.Proposed stale = propose(day, bought, world.operator());
        world.base.repayOn(day, bought, "moves-it", 1_00);
        assertThatExceptionOfType(BnplWriteOffs.Stale.class).isThrownBy(() ->
                approve(day, bought, stale.writeOff().id(), stale.writeOff().position(), world.operator()));
        assertThatExceptionOfType(BnplWriteOffs.ProposalPending.class).isThrownBy(() -> propose(day, bought, world.operator()));
        reject(day, bought, stale.writeOff().id(), world.operator());
        BnplWriteOffs.Proposed next = propose(day, bought, world.operator());
        assertThat(next.writeOff().position().total().minorUnits()).isEqualTo(stale.writeOff().position().total().minorUnits() - 1_00);
        assertThat(next.writeOff().position().revision()).isGreaterThan(stale.writeOff().position().revision());
    }

    @Test
    @DisplayName("the ranks beneath the domain: a raw proposal on no default and the edge without its approved decision are refused, nothing"
            + " leaves WRITTEN_OFF, and neither table takes an UPDATE or a DELETE")
    void theDatabaseHoldsTheRanks() throws SQLException {
        BnplConditionDatabaseWorld.Purchased current = world.base.plain();
        LocalDate purchased = current.purchaseDate();
        assertThatExceptionOfType(BnplWriteOffs.NotDefaulted.class).isThrownBy(() -> propose(purchased, current, world.operator()));
        refusedFramed(purchased, "UPDATE bnpl.bnpl_obligation SET status = 'WRITTEN_OFF' WHERE id = '" + current.id() + "'", "P0001",
                "only beside its approved write-off");
        refusedFramed(purchased, "INSERT INTO bnpl.bnpl_write_off (id, obligation_id, agreement_id, currency, scale, principal_minor,"
                + " principal_due_minor, interest_accrued_minor, interest_due_minor, fees_due_minor, revision, default_event_id,"
                + " agreement_version, proposed_by, proposed_by_type, proposed_at, proposal_reason, correlation_id) SELECT '" + UUID.randomUUID()
                + "', o.id, o.agreement_id, o.currency, 2, 1, 0, 0, 0, 0, 1, '" + UUID.randomUUID() + "', (SELECT max(a.version) FROM"
                + " bnpl.bnpl_agreement a WHERE a.agreement_id = o.agreement_id), 'a', 'CUSTOMER', clock_timestamp(), 'r', 'c'"
                + " FROM bnpl.bnpl_obligation o WHERE o.id = '" + current.id() + "'", "P0001", "latest DEFAULT change");

        BnplConditionDatabaseWorld.Purchased bought = defaulted();
        LocalDate day = dayOf(bought);
        BnplWriteOffs.Proposed proposed = propose(day, bought, world.operator());
        approve(day, bought, proposed.writeOff().id(), proposed.writeOff().position(), world.operator());
        refusedFramed(day, "UPDATE bnpl.bnpl_obligation SET status = 'ACTIVE' WHERE id = '" + bought.id() + "'", "P0001", "not an edge");
        refusedFramed(day, "UPDATE bnpl.bnpl_write_off SET revision = 1 WHERE id = '" + proposed.writeOff().id() + "'", "42501",
                "permission denied");
        refusedFramed(day, "DELETE FROM bnpl.bnpl_write_off_decision WHERE write_off_id = '" + proposed.writeOff().id() + "'", "42501",
                "permission denied");
    }

    // ------------------------------------------------------------------ plumbing

    /** A fresh pay-in-4 purchase past its second instalment by DPD 90, by the real step - as {@code BnplConditionDatabaseTest} walks it. */
    private BnplConditionDatabaseWorld.Purchased defaulted() {
        BnplConditionDatabaseWorld.Purchased bought = world.base.plain();
        world.base.serviceOn(dayOf(bought), bought);
        try {
            assertThat(count("SELECT count(*) FROM bnpl.bnpl_condition_event WHERE kind = 'DEFAULT' AND defaulted AND obligation_id = '"
                    + bought.id() + "'")).as("defaulted at ninety").isEqualTo(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return bought;
    }

    /**
     * A fresh pay-in-6 obligation, its first instalment unpaid 90 days, as {@code BnplConditionDatabaseTest} walks one: every instalment
     * due before the default day billed on its due date, its period accrued first (T-acc, then T-svc); then the default day's step.
     * Its last instalments fall due after the default day, so they are still unbilled - what a refund can shorten.
     */
    private BnplConditionDatabaseWorld.Purchased defaultedPayInSix() {
        BnplConditionDatabaseWorld.Purchased bought = world.base.payInN(BnplPlan.PAY_IN_6_MONTHS);
        LocalDate defaultDay = bought.due(1).plusDays(90);
        for (BnplPurchaseStore.StoredInstalment instalment : bought.schedule().instalments()) {
            if (instalment.dueDate().isBefore(defaultDay)) {
                world.base.catchUpOn(instalment.dueDate(), bought);
                world.base.serviceOn(instalment.dueDate(), bought);
            }
        }
        world.base.catchUpOn(defaultDay, bought);
        world.base.serviceOn(defaultDay, bought);
        try {
            assertThat(count("SELECT count(*) FROM bnpl.bnpl_condition_event WHERE kind = 'DEFAULT' AND defaulted AND obligation_id = '"
                    + bought.id() + "'")).as("defaulted at ninety").isEqualTo(1);
            assertThat(count("SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = '" + bought.id() + "'"))
                    .as("an instalment still unbilled").isLessThan(bought.schedule().instalments().size());
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return bought;
    }

    private static LocalDate dayOf(BnplConditionDatabaseWorld.Purchased bought) {
        return bought.due(2).plusDays(90);
    }

    private BnplWriteOffs.Proposed propose(LocalDate day, BnplConditionDatabaseWorld.Purchased bought, Actor actor) {
        return world.on(day, actor, uow -> writeOffs.propose(uow, bought.agreement(), "uncollectable after collections", actor,
                BnplAcceptanceDatabaseTest.correlation()));
    }

    private BnplWriteOffs.Decided approve(LocalDate day, BnplConditionDatabaseWorld.Purchased bought, UUID id, BnplWriteOffPosition attested,
            Actor actor) {
        return world.on(day, actor, uow -> writeOffs.approve(uow, bought.agreement(), id, attested, "read and agreed", actor,
                BnplAcceptanceDatabaseTest.correlation()));
    }

    private BnplWriteOffs.Decided reject(LocalDate day, BnplConditionDatabaseWorld.Purchased bought, UUID id, Actor actor) {
        return world.on(day, actor, uow -> writeOffs.reject(uow, bought.agreement(), id, "not this one", actor,
                BnplAcceptanceDatabaseTest.correlation()));
    }

    private void refusedFramed(LocalDate day, String sql, String sqlState, String reason) throws SQLException {
        try (Connection connection = BnplClockFrame.framed(DatabaseRoles.application(), world.base.frameOn(day))) {
            refused(connection, sql, sqlState, reason);
        }
    }

    /** A real dispute of part of the purchase, on {@code day}: beside its claim and its hold, as the door commits one. */
    private void opened(LocalDate day, BnplConditionDatabaseWorld.Purchased bought) {
        BnplClockFrame.inOneTransaction(world.base.frameOn(day), uow -> disputes.open(uow, bought.agreement(), bought.party(),
                new BnplDisputes.OpenRequest("open-" + bought.id(), BnplConditionDatabaseWorld.eur(150_00), BnplDisputeReason.NOT_RECEIVED,
                        "it never came"), BnplAcceptanceDatabaseTest.customer(bought.party()), BnplAcceptanceDatabaseTest.correlation()));
    }

    /** A raw proposal every rank but the dispute's admits: an ACTIVE obligation, its agreement in force, its latest DEFAULT change. */
    private static String rawProposal(BnplConditionDatabaseWorld.Purchased bought) {
        return "INSERT INTO bnpl.bnpl_write_off (id, obligation_id, agreement_id, currency, scale, principal_minor, principal_due_minor,"
                + " interest_accrued_minor, interest_due_minor, fees_due_minor, revision, default_event_id, agreement_version, proposed_by,"
                + " proposed_by_type, proposed_at, proposal_reason, correlation_id) SELECT '" + UUID.randomUUID() + "', o.id, o.agreement_id,"
                + " o.currency, 2, 1, 0, 0, 0, 0, 1, (SELECT e.id FROM bnpl.bnpl_condition_event e WHERE e.obligation_id = o.id AND e.kind ="
                + " 'DEFAULT' ORDER BY e.business_date DESC LIMIT 1), (SELECT max(a.version) FROM bnpl.bnpl_agreement a WHERE a.agreement_id ="
                + " o.agreement_id), 'a', 'CUSTOMER', clock_timestamp(), 'r', 'c' FROM bnpl.bnpl_obligation o WHERE o.id = '" + bought.id() + "'";
    }

    /** A raw approval of {@code proposed} by another person, valued {@code day}. */
    private static String rawApproval(LocalDate day, BnplConditionDatabaseWorld.Purchased bought, BnplWriteOffs.Proposed proposed) {
        return "INSERT INTO bnpl.bnpl_write_off_decision (id, write_off_id, obligation_id, proposed_by, outcome, decided_by, decided_by_type,"
                + " decided_at, decision_reason, journal_entry_id, value_date, correlation_id) VALUES ('" + UUID.randomUUID() + "', '"
                + proposed.writeOff().id() + "', '" + bought.id() + "', '" + proposed.writeOff().proposedBy() + "', 'APPROVED', 'someone else',"
                + " 'CUSTOMER', clock_timestamp(), 'agreed', '" + UUID.randomUUID() + "', DATE '" + day + "', 'c')";
    }

    private static String rawDispute(BnplConditionDatabaseWorld.Purchased bought) {
        return "INSERT INTO bnpl.bnpl_dispute (id, purchase_id, obligation_id, merchant_id, party_id, agreement_id,"
                + " terms_version_id, status, reason_category, reason, request_key, opened_at, opened_business_date,"
                + " response_deadline_at, next_sweep_at, correlation_id) SELECT gen_random_uuid(), p.id, o.id, p.merchant_id,"
                + " p.party_id, o.agreement_id, (SELECT a.terms_version_id FROM bnpl.bnpl_agreement a WHERE a.obligation_id ="
                + " o.id ORDER BY a.version DESC LIMIT 1), 'OPEN', 'OTHER', 'raw', 'raw-key', clock_timestamp(), current_date,"
                + " clock_timestamp() + interval '1 day', clock_timestamp(), 'raw' FROM bnpl.purchase p JOIN"
                + " bnpl.bnpl_obligation o ON o.purchase_id = p.id WHERE p.id = '" + bought.purchase().value() + "'";
    }
}
