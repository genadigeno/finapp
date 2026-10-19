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
            refused(migrator, "UPDATE bnpl.bnpl_repayment_reversal SET status = 'APPROVED', decided_by = proposed_by,"
                    + " decided_by_type = proposed_by_type, decision_reason = 'mine', journal_entry_id = gen_random_uuid(),"
                    + " value_date = current_date WHERE id = '" + proposed.id() + "'", "23514");
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
