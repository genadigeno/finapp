package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.count;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.refused;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.scalar;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Repayment reversal against a real database (`P12-TSK-024`; {@code bnpl V016}; scenario 12; {@code INV-REV-01},
 * {@code INV-REV-02}, {@code INV-AUD-04}, {@code INV-HIST-01}, {@code INV-CRD-09}, {@code INV-LND-13}): T-rev over bnpl's
 * JDBC stores on a moved database clock - the reversed amount due again through {@code bnpl.billing_component_paid}, later
 * allocations untouched; ten approvers one reversal; one live proposal per repayment; the four-eyes {@code CHECK}; the
 * closed obligation and the payoff refused by the trigger for every writer; the negating rows mirrored and whole at commit;
 * the value-date rank; L0 taken before B4 (a lock wait). The book is the in-memory one behind a lock and the capital a
 * port whose headroom the case sets; the real ledger's swap, the route negatives and L0 on the real credit profile are
 * {@code app}'s own-container {@code BnplServicingApiTest}.
 *
 * <p>Written by `P12-TSK-024` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@DisplayName("repayment reversal against bnpl V016's ranks (P12-TSK-024)")
class BnplRepaymentReversalDatabaseTest {

    @BeforeAll
    static void termsAndClock() {
        BnplBillingDatabaseTest.termsAndClock();
    }

    /** Instalment 2 repaid whole on its due date (R1), instalment 3 billed and repaid in part (R2). */
    private record TwoRepaid(BnplConditionDatabaseWorld.Purchased bought, BnplPurchaseStore.Repayment first,
            BnplPurchaseStore.Repayment second) {}

    private static TwoRepaid twoRepaid(BnplCorrectionDatabaseWorld world) {
        BnplConditionDatabaseWorld.Purchased bought = world.base.plain();
        world.base.serviceOn(bought.due(2), bought);
        BnplPurchaseStore.Repayment first = world.base.repayOn(bought.due(2), bought, "r1", 150_00).repayment();
        world.base.serviceOn(bought.due(3), bought);
        BnplPurchaseStore.Repayment second = world.base.repayOn(bought.due(3), bought, "r2", 100_00).repayment();
        return new TwoRepaid(bought, first, second);
    }

    @Test
    @DisplayName("scenario 12: a reversal after allocation, a later repayment and a later billing reopens exactly what it"
            + " paid - the negating rows mirror R1's lines, R2's allocation and every billing untouched, instalment 2 due"
            + " again, the proposal APPROVED naming its entry and value date, the event and the audit records committed")
    void aReversalReopensWhatItPaid() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        TwoRepaid repaid = twoRepaid(world);
        LocalDate day = repaid.bought().due(3).plusDays(1);
        BnplRepaymentReversal proposed = world.propose(day, repaid.bought(), repaid.first().id(), world.operator());
        BnplRepaymentReversal approved = world.approve(day, repaid.bought(), repaid.first().id(), proposed.id(),
                world.operator()).reversal();
        assertThat(approved.status()).isEqualTo(BnplCorrectionStatus.APPROVED);
        assertThat(approved.valueDate()).contains(day);
        assertThat(world.due(repaid.bought(), 2, "PRINCIPAL")).isEqualTo(150_00);
        assertThat(world.due(repaid.bought(), 3, "PRINCIPAL")).isEqualTo(50_00);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment_allocation_reversal n JOIN bnpl.bnpl_repayment_allocation"
                + " a ON a.id = n.allocation_id WHERE a.repayment_id = '" + repaid.first().id() + "' AND n.amount_minor ="
                + " a.amount_minor")).isEqualTo(repaid.first().allocations().size());
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment_allocation_reversal n JOIN bnpl.bnpl_repayment_allocation"
                + " a ON a.id = n.allocation_id WHERE a.repayment_id = '" + repaid.second().id() + "'")).isZero();
        assertThat(world.base.balance(repaid.bought(), AccountPurpose.BNPL_PRINCIPAL_DUE)).isEqualTo(eur(200_00));
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = '" + repaid.bought().id()
                + "' AND event_type = '" + BnplRepaymentReversals.REVERSED_EVENT + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = '" + proposed.id()
                + "' AND operation IN ('bnpl.RepaymentReversalProposed', 'bnpl.RepaymentReversalApproved')"))
                .isEqualTo(2);
        // The next step reads instalment 2 past due again: DPD from its own due date.
        world.base.serviceOn(day.plusDays(1), repaid.bought());
        assertThat(Integer.parseInt(scalar("SELECT bnpl.days_past_due('" + repaid.bought().id() + "', DATE '"
                + day.plusDays(1) + "')::text"))).isEqualTo((int) (day.plusDays(1).toEpochDay()
                - repaid.bought().due(2).toEpochDay()));
    }

    @Test
    @DisplayName("ten approvers on ten instances, one reversal - B4 then B6 order them; the losers find it decided; one"
            + " entry, one set of negating rows")
    void tenApproversOneReversal() throws Exception {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        TwoRepaid repaid = twoRepaid(world);
        LocalDate day = repaid.bought().due(3);
        BnplRepaymentReversal proposed = world.propose(day, repaid.bought(), repaid.first().id(), world.operator());
        List<String> outcomes = world.base.concurrently(10, index -> {
            try {
                world.approve(day, repaid.bought(), repaid.first().id(), proposed.id(), world.operator());
                return "approved";
            } catch (BnplRepaymentReversals.ProposalNotPending lost) {
                return "lost";
            }
        });
        assertThat(outcomes).filteredOn("approved"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("lost"::equals).hasSize(9);
        assertThat(world.base.book.keys()).filteredOn(key -> key.contains(BnplRepaymentReversals.REVERSAL_KEY_PREFIX))
                .hasSize(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment_allocation_reversal n JOIN bnpl.bnpl_repayment_reversal"
                + " x ON x.id = n.reversal_id WHERE x.repayment_id = '" + repaid.first().id() + "'"))
                .isEqualTo(repaid.first().allocations().size());
    }

    @Test
    @DisplayName("one live proposal per repayment: ten proposers race, one PROPOSED row - the partial unique beneath B4;"
            + " a REJECTED one leaves room for another; an APPROVED one for none")
    void oneLiveProposalPerRepayment() throws Exception {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        TwoRepaid repaid = twoRepaid(world);
        LocalDate day = repaid.bought().due(3);
        List<String> outcomes = world.base.concurrently(10, index -> {
            try {
                world.propose(day, repaid.bought(), repaid.first().id(), world.operator());
                return "proposed";
            } catch (BnplRepaymentReversals.ProposalPending pending) {
                return "pending";
            }
        });
        assertThat(outcomes).filteredOn("proposed"::equals).hasSize(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment_reversal WHERE repayment_id = '"
                + repaid.first().id() + "' AND status IN ('PROPOSED', 'APPROVED')")).isEqualTo(1);
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            refused(migrator, "INSERT INTO bnpl.bnpl_repayment_reversal (id, obligation_id, repayment_id, agreement_id,"
                    + " agreement_version, status, proposed_by, proposed_by_type, proposed_at, proposal_reason,"
                    + " correlation_id) SELECT gen_random_uuid(), obligation_id, repayment_id, agreement_id,"
                    + " agreement_version, 'PROPOSED', 'someone', 'EMPLOYEE', clock_timestamp(), 'again', 'c'"
                    + " FROM bnpl.bnpl_repayment_reversal WHERE repayment_id = '" + repaid.first().id() + "'", "23505");
            migrator.rollback();
        }
    }

    @Test
    @DisplayName("self-approval refused at both ranks and audited: the domain refuses and the door's own transaction records"
            + " it FAILED; beneath, the four-eyes CHECK refuses a row approved by its proposer for every writer")
    void selfApprovalRefusedAtBothRanksAndAudited() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        TwoRepaid repaid = twoRepaid(world);
        Actor proposer = world.operator();
        LocalDate day = repaid.bought().due(3);
        BnplRepaymentReversal proposed = world.propose(day, repaid.bought(), repaid.first().id(), proposer);
        BnplRepaymentReversals.SelfApprovalRefused refused = org.junit.jupiter.api.Assertions.assertThrows(
                BnplRepaymentReversals.SelfApprovalRefused.class,
                () -> world.approve(day, repaid.bought(), repaid.first().id(), proposed.id(), proposer));
        world.on(day, proposer, uow -> {
            world.reversals.recordRefusal(uow, refused, proposer, BnplAcceptanceDatabaseTest.correlation());
            return null;
        });
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'bnpl.SelfApprovalRefused'"
                + " AND outcome = 'FAILED' AND target_id = '" + proposed.id() + "'")).isEqualTo(1);
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            // Valued at the obligation's floor - inside the edge trigger's [floor, max(today, floor)] - so the row reaches
            // the CHECK: the suite's clock stands on instalment 3's due date, so the real current_date lies below the
            // floor and the trigger refused first (P0001; P12-TSK-027).
            String selfApproved = "UPDATE bnpl.bnpl_repayment_reversal SET status = 'APPROVED', decided_by = proposed_by,"
                    + " decided_by_type = proposed_by_type, decision_reason = 'mine', journal_entry_id = gen_random_uuid(),"
                    + " value_date = bnpl.principal_value_floor(obligation_id) WHERE id = '" + proposed.id() + "'";
            assertThatThrownBy(() -> {
                        try (Statement statement = migrator.createStatement()) {
                            statement.execute(selfApproved);
                        }
                    }).isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"))
                    .hasMessageContaining("bnpl_repayment_reversal_four_eyes");
            migrator.rollback();
        }
    }

    @Test
    @DisplayName("an employee never services their own party's agreement: refused before any lock, nothing written")
    void anEmployeeNeverServicesTheirOwnPartysAgreement() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        TwoRepaid repaid = twoRepaid(world);
        assertThatThrownBy(() -> world.propose(repaid.bought().due(3), repaid.bought(), repaid.first().id(),
                world.insider(repaid.bought().party()))).isInstanceOf(BnplRepaymentReversals.SelfDealingRefused.class);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment_reversal WHERE obligation_id = '"
                + repaid.bought().id() + "'")).isZero();
    }

    @Test
    @DisplayName("a closing repayment is not reversible: its obligation CLOSED, the domain refuses - and the trigger refuses"
            + " a proposal on a closed obligation, and one naming a PAYOFF repayment, for every writer")
    void aClosingRepaymentIsNotReversible() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.base.plain();
        BnplPayoffs.Executed paidOff = world.base.payOffOn(bought.purchaseDate().plusDays(1), bought, "off");
        UUID payoff = paidOff.repayment().orElseThrow().id();
        assertThatThrownBy(() -> world.propose(bought.purchaseDate().plusDays(1), bought, payoff, world.operator()))
                .isInstanceOf(BnplRepaymentReversals.ObligationClosed.class);
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            refused(migrator, "INSERT INTO bnpl.bnpl_repayment_reversal (id, obligation_id, repayment_id, agreement_id,"
                    + " agreement_version, status, proposed_by, proposed_by_type, proposed_at, proposal_reason,"
                    + " correlation_id) SELECT gen_random_uuid(), r.obligation_id, r.id, r.agreement_id,"
                    + " r.agreement_version, 'PROPOSED', 'someone', 'EMPLOYEE', clock_timestamp(), 'r', 'c'"
                    + " FROM bnpl.bnpl_repayment r WHERE r.id = '" + payoff + "'", "P0001");
            migrator.rollback();
        }
    }

    @Test
    @DisplayName("a repayment whose held credit was applied since is not reversible - nothing written")
    void aRepaymentWhoseHeldCreditWasAppliedIsNotReversible() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.base.plain();
        BnplPurchaseStore.Repayment early = world.base.repayOn(bought.purchaseDate().plusDays(1), bought, "early",
                40_00).repayment();
        world.base.serviceOn(bought.due(2), bought);
        assertThatThrownBy(() -> world.propose(bought.due(2), bought, early.id(), world.operator()))
                .isInstanceOf(BnplRepaymentReversals.RepaymentNotReversible.class);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment_reversal WHERE repayment_id = '" + early.id() + "'"))
                .isZero();
    }

    @Test
    @DisplayName("the original is byte-identical after: the repayment row, its allocation and F3's lines unchanged")
    void theOriginalIsByteIdenticalAfter() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        TwoRepaid repaid = twoRepaid(world);
        String row = "SELECT md5(r::text || coalesce((SELECT string_agg(a::text, '|' ORDER BY a.sequence)"
                + " FROM bnpl.bnpl_repayment_allocation a WHERE a.repayment_id = r.id), '')) FROM bnpl.bnpl_repayment r"
                + " WHERE r.id = '" + repaid.first().id() + "'";
        String before = scalar(row);
        List<com.finapp.ledger.JournalLine> lines = world.base.book.entryLines(null, repaid.first().entry());
        LocalDate day = repaid.bought().due(3);
        BnplRepaymentReversal proposed = world.propose(day, repaid.bought(), repaid.first().id(), world.operator());
        world.approve(day, repaid.bought(), repaid.first().id(), proposed.id(), world.operator());
        assertThat(scalar(row)).isEqualTo(before);
        assertThat(world.base.book.entryLines(null, repaid.first().entry())).isEqualTo(lines);
    }

    @Test
    @DisplayName("the approval takes the profile first: with the obligation held by a rival (B4), the approval has taken L0"
            + " and waits - it completes once the rival commits")
    void reversalTakesTheProfileFirst() throws Exception {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        TwoRepaid repaid = twoRepaid(world);
        LocalDate day = repaid.bought().due(3);
        BnplRepaymentReversal proposed = world.propose(day, repaid.bought(), repaid.first().id(), world.operator());
        world.profilesLocked.clear();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection rival = DatabaseRoles.application()) {
            rival.setAutoCommit(false);
            try (Statement lock = rival.createStatement()) {
                lock.execute("SELECT 1 FROM bnpl.bnpl_obligation WHERE id = '" + repaid.bought().id() + "' FOR UPDATE");
            }
            Future<BnplRepaymentReversals.Decided> approving = pool.submit(() -> world.approve(day, repaid.bought(),
                    repaid.first().id(), proposed.id(), world.operator()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (world.profilesLocked.isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(world.profilesLocked).containsExactly(repaid.bought().party());
            assertThat(approving.isDone()).as("waiting on B4 behind the rival").isFalse();
            rival.commit();
            assertThat(approving.get(30, TimeUnit.SECONDS).reversal().status()).isEqualTo(BnplCorrectionStatus.APPROVED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("exposure rises by the principal reopened: the obligation's principal accounts by exactly 150.00")
    void exposureRisesByThePrincipalReopened() {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        TwoRepaid repaid = twoRepaid(world);
        com.finapp.sharedkernel.money.Money before = world.base.balance(repaid.bought(), AccountPurpose.BNPL_PRINCIPAL)
                .plus(world.base.balance(repaid.bought(), AccountPurpose.BNPL_PRINCIPAL_DUE));
        LocalDate day = repaid.bought().due(3);
        BnplRepaymentReversal proposed = world.propose(day, repaid.bought(), repaid.first().id(), world.operator());
        world.approve(day, repaid.bought(), repaid.first().id(), proposed.id(), world.operator());
        assertThat(world.base.balance(repaid.bought(), AccountPurpose.BNPL_PRINCIPAL)
                .plus(world.base.balance(repaid.bought(), AccountPurpose.BNPL_PRINCIPAL_DUE)))
                .isEqualTo(before.plus(eur(150_00)));
    }

    @Test
    @DisplayName("a reversal never over-deploys capital: headroom short of the principal re-instated refuses it, nothing"
            + " written, the proposal still PROPOSED")
    void aReversalNeverOverDeploysCapital() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        TwoRepaid repaid = twoRepaid(world);
        LocalDate day = repaid.bought().due(3);
        BnplRepaymentReversal proposed = world.propose(day, repaid.bought(), repaid.first().id(), world.operator());
        world.headroom = eur(149_99);
        assertThatThrownBy(() -> world.approve(day, repaid.bought(), repaid.first().id(), proposed.id(),
                world.operator())).isInstanceOf(BnplRepaymentReversals.CapitalUnavailable.class);
        assertThat(scalar("SELECT status FROM bnpl.bnpl_repayment_reversal WHERE id = '" + proposed.id() + "'"))
                .isEqualTo("PROPOSED");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_repayment_allocation_reversal WHERE reversal_id = '"
                + proposed.id() + "'")).isZero();
    }

    // ------------------------------------------------------------------ A7-3: OQ-14 kept across the two orders

    /** Instalment 3's late fee paid exactly by R; instalment 2's repayment reversed, so a refund of 300.00 leaves it owing. */
    private record FeePaid(BnplConditionDatabaseWorld.Purchased bought, BnplPurchaseStore.Repayment fee, LocalDate paid) {}

    private static FeePaid feePaid(BnplCorrectionDatabaseWorld world) {
        BnplConditionDatabaseWorld.Purchased bought = world.base.feeBearing();
        world.base.serviceOn(bought.due(2), bought);
        BnplPurchaseStore.Repayment second = world.base.repayOn(bought.due(2), bought, "p1", 150_00).repayment();
        world.base.serviceOn(bought.due(3), bought);
        world.base.serviceOn(bought.due(3).plusDays(5), bought);
        assertThat(world.fees(bought)).as("instalment 3's late fee").singleElement()
                .satisfies(fee -> assertThat(fee.instalment()).isEqualTo(bought.instalment(3)));
        LocalDate paid = bought.due(3).plusDays(6);
        BnplPurchaseStore.Repayment fee = world.base.repayOn(paid, bought, "fee", 5_00).repayment();
        assertThat(fee.allocations()).as("R paid the fee and nothing else").singleElement()
                .satisfies(line -> assertThat(line.component()).contains("FEES"));
        reverse(world, paid, bought, second);
        return new FeePaid(bought, fee, paid);
    }

    private static BnplRepaymentReversal reverse(BnplCorrectionDatabaseWorld world, LocalDate date,
            BnplConditionDatabaseWorld.Purchased bought, BnplPurchaseStore.Repayment repayment) {
        BnplRepaymentReversal proposed = world.propose(date, bought, repayment.id(), world.operator());
        return world.approve(date, bought, repayment.id(), proposed.id(), world.operator()).reversal();
    }

    private static BnplRefund refund(BnplCorrectionDatabaseWorld world, FeePaid feePaid, LocalDate date) {
        BnplRefund refund = world.base.refundOn(date, feePaid.bought(), "refund-" + date, 300_00).refund();
        assertThat(refund.adjustments()).as("instalments 4 and 3 cancelled whole, latest first")
                .extracting(BnplRefund.Adjustment::sequence, BnplRefund.Adjustment::whole)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(4, true), org.assertj.core.groups.Tuple.tuple(3, true));
        return refund;
    }

    @Test
    @DisplayName("A7-3 (the Phase 12 transition gate), the refund first: the refund releases nothing of instalment 3's"
            + " paid fee; R's reversal re-opens it and releases it in its own transaction - one bnpl V019 row, one entry"
            + " of its own - nothing owed on the wholly cancelled instalment, its waiver room zero")
    void theRefundFirstThenTheReversalReleasesTheFee() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        FeePaid feePaid = feePaid(world);
        assertThat(refund(world, feePaid, feePaid.paid().plusDays(1)).lateFeeReleased().isZero()).isTrue();
        BnplRepaymentReversal reversal = reverse(world, feePaid.paid().plusDays(2), feePaid.bought(), feePaid.fee());

        assertThat(scalar("SELECT instalment_id || '|' || amount_minor FROM bnpl.bnpl_reversal_fee_release"
                + " WHERE reversal_id = '" + reversal.id() + "'")).isEqualTo(feePaid.bought().instalment(3) + "|500");
        assertThat(world.base.book.keys()).contains(BnplRepaymentReversals.FEE_RELEASE_KEY_PREFIX + reversal.id());
        assertThat(endState(world, feePaid)).containsEntry("FEES 3", 0L).containsEntry("room", 0L);
    }

    @Test
    @DisplayName("A7-3, the reversal first: R's reversal re-opens the fee and releases nothing (no refund has cancelled"
            + " instalment 3); the refund then cancels it whole and releases it (OQ-14, bnpl_refund_conserves at its"
            + " commit) - and both orders reach one end state")
    void theReversalFirstThenTheRefundReachesTheSameEndState() throws SQLException {
        BnplCorrectionDatabaseWorld first = new BnplCorrectionDatabaseWorld();
        FeePaid refundFirst = feePaid(first);
        refund(first, refundFirst, refundFirst.paid().plusDays(1));
        reverse(first, refundFirst.paid().plusDays(2), refundFirst.bought(), refundFirst.fee());

        BnplCorrectionDatabaseWorld second = new BnplCorrectionDatabaseWorld();
        FeePaid reversalFirst = feePaid(second);
        BnplRepaymentReversal reversal = reverse(second, reversalFirst.paid().plusDays(1), reversalFirst.bought(),
                reversalFirst.fee());
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_reversal_fee_release WHERE reversal_id = '" + reversal.id()
                + "'")).as("nothing to release yet").isZero();
        assertThat(second.due(reversalFirst.bought(), 3, "FEES")).isEqualTo(5_00);
        assertThat(refund(second, reversalFirst, reversalFirst.paid().plusDays(2)).lateFeeReleased())
                .isEqualTo(eur(5_00));

        assertThat(endState(first, refundFirst)).isEqualTo(endState(second, reversalFirst))
                .containsEntry("FEES 3", 0L).containsEntry("PRINCIPAL 2", 150_00L).containsEntry("room", 0L);
    }

    @Test
    @DisplayName("A7-3's ranks for every writer: a raw approval re-opening the fee without its release never commits; with"
            + " the exact release it does (rolled back); a release on an instalment no refund cancelled whole, or of"
            + " another amount, is refused; the release is INSERT-only for every role")
    void theReleaseRanksHoldForEveryWriter() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        FeePaid feePaid = feePaid(world);
        refund(world, feePaid, feePaid.paid().plusDays(1));
        BnplRepaymentReversal proposed = world.propose(feePaid.paid().plusDays(1), feePaid.bought(), feePaid.fee().id(),
                world.operator());
        String approve = "UPDATE bnpl.bnpl_repayment_reversal SET status = 'APPROVED', decided_by = 'other',"
                + " decided_by_type = 'EMPLOYEE', decision_reason = 'r', journal_entry_id = gen_random_uuid(),"
                + " value_date = (SELECT GREATEST(r.value_date, bnpl.principal_value_floor(r.obligation_id))"
                + " FROM bnpl.bnpl_repayment r WHERE r.id = '" + feePaid.fee().id() + "') WHERE id = '" + proposed.id() + "'";
        String negate = "INSERT INTO bnpl.bnpl_repayment_allocation_reversal (id, reversal_id, allocation_id, currency,"
                + " scale, amount_minor) SELECT gen_random_uuid(), '" + proposed.id() + "', a.id, a.currency, a.scale,"
                + " a.amount_minor FROM bnpl.bnpl_repayment_allocation a WHERE a.repayment_id = '" + feePaid.fee().id() + "'";
        String release = "INSERT INTO bnpl.bnpl_reversal_fee_release (id, reversal_id, obligation_id, instalment_id,"
                + " currency, scale, amount_minor, journal_entry_id) VALUES (gen_random_uuid(), '" + proposed.id() + "', '"
                + feePaid.bought().id() + "', '%s', 'EUR', 2, %d, '" + UUID.randomUUID() + "')";
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try (Statement statement = migrator.createStatement()) {
                statement.execute(approve);
                statement.execute(negate);
            }
            assertThatThrownBy(migrator::commit).isInstanceOf(SQLException.class)
                    .hasMessageContaining("leaves no late fee due on an instalment a refund cancelled whole");

            // The release alone, exact: the commit-time rank is met (judged now, rolled back).
            try (Statement statement = migrator.createStatement()) {
                statement.execute(approve);
                statement.execute(negate);
                statement.execute(String.format(release, feePaid.bought().instalment(3), 5_00));
                statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
            }
            migrator.rollback();

            // Never another amount, never an instalment no refund cancelled whole.
            for (String[] refusal : List.of(
                    new String[] {String.format(release, feePaid.bought().instalment(3), 4_99), "exactly the late fee"},
                    new String[] {String.format(release, feePaid.bought().instalment(2), 5_00), "cancelled whole"})) {
                try (Statement statement = migrator.createStatement()) {
                    statement.execute(approve);
                    statement.execute(negate);
                }
                assertThatThrownBy(() -> {
                            try (Statement statement = migrator.createStatement()) {
                                statement.execute(refusal[0]);
                            }
                        }).as(refusal[1]).isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("P0001"))
                        .hasMessageContaining(refusal[1]);
                migrator.rollback();
            }
        }
        // INSERT-only: a domain release, then neither UPDATE nor DELETE - the application role holds no privilege, the
        // owner meets the trigger.
        world.approve(feePaid.paid().plusDays(2), feePaid.bought(), feePaid.fee().id(), proposed.id(), world.operator());
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_reversal_fee_release WHERE reversal_id = '" + proposed.id() + "'"))
                .isEqualTo(1);
        try (Connection application = DatabaseRoles.application()) {
            refused(application, "UPDATE bnpl.bnpl_reversal_fee_release SET amount_minor = 1", "42501");
            refused(application, "DELETE FROM bnpl.bnpl_reversal_fee_release", "42501");
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            refused(migrator, "UPDATE bnpl.bnpl_reversal_fee_release SET amount_minor = 1 WHERE obligation_id = '"
                    + feePaid.bought().id() + "'", "P0001");
            migrator.rollback();
        }
    }

    /** What both orders must agree on: the due components of instalments 2 and 3, the fee's room, the six balances. */
    private static Map<String, Long> endState(BnplCorrectionDatabaseWorld world, FeePaid feePaid) throws SQLException {
        Map<String, Long> state = new java.util.LinkedHashMap<>();
        for (int sequence = 2; sequence <= 3; sequence++) {
            for (String component : List.of("FEES", "INTEREST", "PRINCIPAL")) {
                state.put(component + " " + sequence, world.due(feePaid.bought(), sequence, component));
            }
        }
        state.put("room", Long.parseLong(scalar("SELECT bnpl.fee_waiver_room(f.id)::text FROM bnpl.bnpl_fee_assessment f"
                + " WHERE f.instalment_id = '" + feePaid.bought().instalment(3) + "'")));
        for (AccountPurpose purpose : BnplSubledgerProof.PURPOSES) {
            state.put(purpose.name(), world.base.balance(feePaid.bought(), purpose).minorUnits());
        }
        state.put("status " + scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + feePaid.bought().id() + "'"),
                1L);
        return state;
    }

    @Test
    @DisplayName("A2-1 (the Phase 12 transition gate): approved on a clock frame stepped back behind the repayment it"
            + " reverses - the floor further back - the reversal is valued the repayment's own date, its F8 entry with it;"
            + " beneath, bnpl V019 refuses a raw approval valued between the floor and the repayment, for every writer")
    void aReversalSteppedBackBehindItsRepaymentIsValuedOnIt() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.base.plain();
        world.base.serviceOn(bought.due(2), bought);
        LocalDate paidOn = bought.due(2).plusDays(4);
        BnplPurchaseStore.Repayment late = world.base.repayOn(paidOn, bought, "late", 150_00).repayment();
        assertThat(late.valueDate()).isEqualTo(paidOn);
        assertThat(LocalDate.parse(scalar("SELECT bnpl.principal_value_floor('" + bought.id() + "')::text")))
                .as("the floor stands before the day the clock steps back to").isBefore(paidOn.minusDays(1));

        // The rank alone, first: a raw approval valued the day before the repayment - on or after the floor, inside the
        // ceiling - is refused by V019's own arm, for the owner too.
        BnplRepaymentReversal raw = world.propose(paidOn, bought, late.id(), world.operator());
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            assertThatThrownBy(() -> {
                        try (Statement statement = migrator.createStatement()) {
                            statement.execute("UPDATE bnpl.bnpl_repayment_reversal SET status = 'APPROVED',"
                                    + " decided_by = 'other', decided_by_type = 'EMPLOYEE', decision_reason = 'r',"
                                    + " journal_entry_id = gen_random_uuid(), value_date = DATE '" + paidOn.minusDays(1)
                                    + "' WHERE id = '" + raw.id() + "'");
                        }
                    }).isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("P0001"))
                    .hasMessageContaining("never valued before the repayment it reverses");
            migrator.rollback();
        }

        // The domain, on the clock stepped back to the day before the repayment.
        BnplRepaymentReversal approved = world.approve(paidOn.minusDays(1), bought, late.id(), raw.id(),
                world.operator()).reversal();
        assertThat(approved.valueDate()).contains(paidOn);
        assertThat(LocalDate.parse(scalar("SELECT value_date::text FROM bnpl.bnpl_repayment_reversal WHERE id = '"
                + approved.id() + "'"))).isEqualTo(paidOn);
        assertThat(world.base.book.entryLines(null, approved.entry().orElseThrow())).isNotEmpty();
        assertThat(world.due(bought, 2, "PRINCIPAL")).as("what it paid is due again").isEqualTo(150_00);
    }

    @Test
    @DisplayName("the trigger values a reversal never before the floor, and an approval negating nothing fails at commit")
    void theTriggersRanks() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        TwoRepaid repaid = twoRepaid(world);
        BnplRepaymentReversal proposed = world.propose(repaid.bought().due(3), repaid.bought(), repaid.first().id(),
                world.operator());
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            refused(migrator, "UPDATE bnpl.bnpl_repayment_reversal SET status = 'APPROVED', decided_by = 'other',"
                    + " decided_by_type = 'EMPLOYEE', decision_reason = 'r', journal_entry_id = gen_random_uuid(),"
                    + " value_date = DATE '" + repaid.bought().due(3).minusDays(1) + "' WHERE id = '" + proposed.id() + "'",
                    "P0001");
            migrator.rollback();
            try (Statement statement = migrator.createStatement()) {
                statement.execute("UPDATE bnpl.bnpl_repayment_reversal SET status = 'APPROVED', decided_by = 'other',"
                        + " decided_by_type = 'EMPLOYEE', decision_reason = 'r', journal_entry_id = gen_random_uuid(),"
                        + " value_date = DATE '" + repaid.bought().due(3) + "' WHERE id = '" + proposed.id() + "'");
            }
            assertThatThrownBy(migrator::commit).isInstanceOf(SQLException.class)
                    .hasMessageContaining("negates every allocation row");
        }
    }
}
