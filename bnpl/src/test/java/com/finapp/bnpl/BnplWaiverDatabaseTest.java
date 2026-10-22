package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.count;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.refused;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.scalar;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Fee waivers against a real database (`P12-TSK-024`; {@code bnpl V016}; {@code INV-REV-02}, {@code INV-AUD-04}; the Phase
 * 11 gate's R3): T-wvr over bnpl's JDBC stores on a moved database clock - only the unpaid remainder forgiven, a released or
 * waived fee never again ({@code bnpl.fee_waiver_room}, both arms, for every writer), ten approvers one waiver, a waiver
 * racing a repayment of the fee, a waiver of the last receivable closing, the income row taken last. The book is the
 * in-memory one behind a lock; the income row's place in the order (after (P), inside the one L8 call) is proven
 * hermetically by {@code BnplWaiversTest#theWaiverTakesTheIncomeRowLast}.
 *
 * <p>Written by `P12-TSK-024` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@DisplayName("fee waivers against bnpl V016's ranks (P12-TSK-024)")
class BnplWaiverDatabaseTest {

    @BeforeAll
    static void termsAndClock() {
        BnplBillingDatabaseTest.termsAndClock();
    }

    private static BnplFeeAssessment lateFee(BnplCorrectionDatabaseWorld world,
            BnplConditionDatabaseWorld.Purchased bought) {
        world.base.serviceOn(bought.due(2), bought);
        world.base.serviceOn(bought.due(2).plusDays(5), bought);
        return world.fees(bought).get(0);
    }

    @Test
    @DisplayName("a fee waiver reverses only the unpaid remainder: 3.00 paid, 5.00 refused, 2.00 waived - FEES billed 3.00,"
            + " nothing due; the trigger refuses a proposal beyond the room for every writer")
    void aFeeWaiverReversesOnlyTheUnpaidRemainder() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.base.feeBearing();
        BnplFeeAssessment fee = lateFee(world, bought);
        LocalDate day = bought.due(2).plusDays(5);
        world.base.repayOn(day, bought, "part", 3_00);
        assertThatThrownBy(() -> world.proposeWaiver(day, bought, fee.id(), 5_00, world.operator()))
                .isInstanceOf(BnplWaivers.WaiverExceedsDue.class);
        BnplWaiver proposed = world.proposeWaiver(day, bought, fee.id(), 2_00, world.operator());
        world.approveWaiver(day, bought, proposed.id(), world.operator());
        assertThat(scalar("SELECT bnpl.billing_component_billed(b.id, 'FEES')::text FROM bnpl.bnpl_billing b WHERE"
                + " b.instalment_id = '" + bought.instalment(2) + "'")).isEqualTo("300");
        assertThat(world.due(bought, 2, AllocationComponent.FEES.name())).isZero();
        assertThat(world.base.balance(bought, AccountPurpose.BNPL_FEES_DUE)).isEqualTo(eur(0));
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            refused(migrator, "INSERT INTO bnpl.bnpl_waiver (id, obligation_id, kind, fee_assessment_id, instalment_id,"
                    + " currency, scale, amount_minor, agreement_id, agreement_version, proposed_by, proposed_by_type,"
                    + " proposed_at, proposal_reason, correlation_id) SELECT gen_random_uuid(), f.obligation_id, 'FEE',"
                    + " f.id, f.instalment_id, f.currency, f.scale, 1, f.agreement_id, f.agreement_version, 'someone',"
                    + " 'EMPLOYEE', clock_timestamp(), 'again', 'c' FROM bnpl.bnpl_fee_assessment f WHERE f.id = '"
                    + fee.id() + "'", "P0001", "bounded by what its fee still has due and was never forgiven");
            migrator.rollback();
        }
    }

    @Test
    @DisplayName("R3: a fee a refund released, and one already waived, is never waived again - refused by the domain and,"
            + " for every writer, by the decision's trigger")
    void aReleasedOrRefundedFeeIsNeverWaivedAgain() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.base.feeBearing();
        world.base.serviceOn(bought.due(2), bought);
        world.base.serviceOn(bought.due(3), bought);
        world.base.serviceOn(bought.due(3).plusDays(5), bought);
        List<BnplFeeAssessment> fees = world.fees(bought);
        assertThat(fees).hasSize(2);
        BnplFeeAssessment standing = fees.get(0);
        BnplFeeAssessment released = fees.get(1);
        LocalDate day = bought.due(3).plusDays(5);
        world.base.refundOn(day, bought, "ref", 350_00);
        assertThatThrownBy(() -> world.proposeWaiver(day, bought, released.id(), 1, world.operator()))
                .isInstanceOf(BnplWaivers.WaiverExceedsDue.class);
        BnplWaiver whole = world.proposeWaiver(day, bought, standing.id(), 5_00, world.operator());
        world.approveWaiver(day, bought, whole.id(), world.operator());
        assertThatThrownBy(() -> world.proposeWaiver(day, bought, standing.id(), 1, world.operator()))
                .isInstanceOf(BnplWaivers.WaiverExceedsDue.class);
        assertThat(scalar("SELECT bnpl.fee_waiver_room('" + standing.id() + "')::text")).isEqualTo("0");
        assertThat(scalar("SELECT bnpl.fee_waiver_room('" + released.id() + "')::text")).isEqualTo("0");
    }

    @Test
    @DisplayName("R3 through a reversal, on PostgreSQL: 2.00 waived, the 3.00 left paid and its payment reversed - the fee is"
            + " due again and forgivable to exactly the 3.00 never forgiven: 3.01 refused by the domain and, for every"
            + " writer, by the trigger's bound; 3.00 waived; then nothing more")
    void aReopenedFeeIsForgivableOnlyToWhatWasNeverForgiven() throws SQLException {
        // The Phase 12 -> 13 transition gate, A6-F4: the path BnplWaiversTest proves hermetically, over bnpl's JDBC stores
        // and bnpl V016's bnpl.fee_waiver_room - the bound read from the rows a reversal re-opened, never the fake's copy.
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.base.feeBearing();
        BnplFeeAssessment fee = lateFee(world, bought);
        LocalDate day = bought.due(2).plusDays(5);
        BnplWaiver first = world.proposeWaiver(day, bought, fee.id(), 2_00, world.operator());
        world.approveWaiver(day, bought, first.id(), world.operator());
        BnplPurchaseStore.Repayment paid = world.base.repayOn(day.plusDays(1), bought, "the fee", 3_00).repayment();
        assertThat(world.due(bought, 2, AllocationComponent.FEES.name())).as("the fee settled: 2.00 waived, 3.00 paid")
                .isZero();
        assertThat(scalar("SELECT bnpl.fee_waiver_room('" + fee.id() + "')::text")).isEqualTo("0");
        LocalDate reversed = day.plusDays(2);
        BnplRepaymentReversal reversal = world.propose(reversed, bought, paid.id(), world.operator());
        world.approve(reversed, bought, paid.id(), reversal.id(), world.operator());
        assertThat(world.due(bought, 2, AllocationComponent.FEES.name())).as("the reversal re-opened the 3.00")
                .isEqualTo(3_00);
        assertThat(scalar("SELECT bnpl.fee_waiver_room('" + fee.id() + "')::text"))
                .as("the room: what is due and was never forgiven").isEqualTo("300");

        // The bound, both ranks: the domain's, and the trigger's for a raw writer of either role.
        assertThatThrownBy(() -> world.proposeWaiver(reversed, bought, fee.id(), 3_01, world.operator()))
                .isInstanceOf(BnplWaivers.WaiverExceedsDue.class);
        for (Connection writer : List.of(DatabaseRoles.migrator(), DatabaseRoles.application())) {
            try (Connection framed = BnplClockFrame.framed(writer, world.base.frameOn(reversed))) {
                refused(framed, rawWaiver(fee, 3_01, "beyond the room"), "P0001",
                        "bounded by what its fee still has due and was never forgiven");
            }
        }
        BnplWaiver second = world.proposeWaiver(reversed, bought, fee.id(), 3_00, world.operator());
        world.approveWaiver(reversed, bought, second.id(), world.operator());
        assertThat(world.due(bought, 2, AllocationComponent.FEES.name())).isZero();
        assertThat(scalar("SELECT bnpl.billing_component_billed(b.id, 'FEES')::text FROM bnpl.bnpl_billing b WHERE"
                + " b.instalment_id = '" + bought.instalment(2) + "'")).as("the whole 5.00 forgiven, none of it paid")
                .isEqualTo("0");
        assertThat(scalar("SELECT bnpl.fee_waiver_room('" + fee.id() + "')::text")).isEqualTo("0");
        assertThatThrownBy(() -> world.proposeWaiver(reversed, bought, fee.id(), 1, world.operator()))
                .isInstanceOf(BnplWaivers.WaiverExceedsDue.class);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_waiver_decision d JOIN bnpl.bnpl_waiver w ON w.id = d.waiver_id"
                + " WHERE w.fee_assessment_id = '" + fee.id() + "' AND d.outcome = 'APPROVED'")).isEqualTo(2);
    }

    @Test
    @DisplayName("a waiver needs a reason, for every writer: a raw proposal and a raw decision without one are refused by"
            + " bnpl V016's CHECKs (bnpl_waiver_proposal_reasoned, bnpl_waiver_decision_reasoned) - every other rank"
            + " satisfied, so the CHECK is what refuses")
    void aWaiverNeedsAReasonForEveryWriter() throws SQLException {
        // The Phase 12 -> 13 transition gate, A1-5: the domain's half is BnplWaiversTest#aWaiverNeedsAReason.
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.base.feeBearing();
        BnplFeeAssessment fee = lateFee(world, bought);
        LocalDate day = bought.due(2).plusDays(5);
        String longer = "r".repeat(BnplReasons.BOUND + 1);
        for (Connection writer : List.of(DatabaseRoles.migrator(), DatabaseRoles.application())) {
            try (Connection framed = BnplClockFrame.framed(writer, world.base.frameOn(day))) {
                // Within the room (5.00 due, nothing forgiven), no proposal pending: only the reason is wrong.
                refused(framed, rawWaiver(fee, 1_00, ""), "23514", "bnpl_waiver_proposal_reasoned");
                refused(framed, rawWaiver(fee, 1_00, longer), "23514", "bnpl_waiver_proposal_reasoned");
            }
        }
        BnplWaiver proposed = world.proposeWaiver(day, bought, fee.id(), 1_00, world.operator());
        String rawDecision = "INSERT INTO bnpl.bnpl_waiver_decision (id, waiver_id, proposed_by, outcome, decided_by,"
                + " decided_by_type, decided_at, decision_reason, journal_entry_id, correlation_id) SELECT"
                + " gen_random_uuid(), w.id, w.proposed_by, 'REJECTED', 'operator-z', 'EMPLOYEE', clock_timestamp(),"
                + " '%s', NULL, 'c' FROM bnpl.bnpl_waiver w WHERE w.id = '" + proposed.id() + "'";
        for (Connection writer : List.of(DatabaseRoles.migrator(), DatabaseRoles.application())) {
            try (Connection framed = BnplClockFrame.framed(writer, world.base.frameOn(day))) {
                // Another decider, a rejection posting nothing: only the reason is wrong.
                refused(framed, String.format(rawDecision, ""), "23514", "bnpl_waiver_decision_reasoned");
                refused(framed, String.format(rawDecision, longer), "23514", "bnpl_waiver_decision_reasoned");
            }
        }
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_waiver_decision WHERE waiver_id = '" + proposed.id() + "'"))
                .as("the proposal undecided").isZero();
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_waiver WHERE fee_assessment_id = '" + fee.id() + "'"))
                .as("no raw proposal stood").isEqualTo(1);
    }

    /** A raw FEE waiver of {@code fee} for {@code minor}, reasoned {@code reason} - every other column its own. */
    private static String rawWaiver(BnplFeeAssessment fee, long minor, String reason) {
        return "INSERT INTO bnpl.bnpl_waiver (id, obligation_id, kind, fee_assessment_id, instalment_id, currency, scale,"
                + " amount_minor, agreement_id, agreement_version, proposed_by, proposed_by_type, proposed_at,"
                + " proposal_reason, correlation_id) SELECT gen_random_uuid(), f.obligation_id, 'FEE', f.id,"
                + " f.instalment_id, f.currency, f.scale, " + minor + ", f.agreement_id, (SELECT max(a.version) FROM"
                + " bnpl.bnpl_agreement a WHERE a.obligation_id = f.obligation_id), 'someone', 'EMPLOYEE',"
                + " clock_timestamp(), '" + reason + "', 'c' FROM bnpl.bnpl_fee_assessment f WHERE f.id = '" + fee.id()
                + "'";
    }

    @Test
    @DisplayName("ten approvers on ten instances, one waiver - UNIQUE (waiver_id) beneath B4; one F7 entry")
    void tenApproversOneWaiver() throws Exception {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.base.feeBearing();
        BnplFeeAssessment fee = lateFee(world, bought);
        LocalDate day = bought.due(2).plusDays(5);
        BnplWaiver proposed = world.proposeWaiver(day, bought, fee.id(), 5_00, world.operator());
        List<String> outcomes = world.base.concurrently(10, index -> {
            try {
                world.approveWaiver(day, bought, proposed.id(), world.operator());
                return "approved";
            } catch (BnplWaivers.ProposalNotPending lost) {
                return "lost";
            }
        });
        assertThat(outcomes).filteredOn("approved"::equals).hasSize(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_waiver_decision WHERE waiver_id = '" + proposed.id() + "'"))
                .isEqualTo(1);
        assertThat(world.base.book.keys()).filteredOn(key -> key.contains(BnplWaivers.WAIVER_KEY_PREFIX)).hasSize(1);
    }

    @Test
    @DisplayName("a waiver racing a repayment of the fee: either order is exact - the repayment first makes the approval"
            + " WaiverExceedsDue; nothing is ever forgiven and paid at once")
    void waiverRacingARepaymentOfTheFee() throws Exception {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.base.feeBearing();
        BnplFeeAssessment fee = lateFee(world, bought);
        LocalDate day = bought.due(2).plusDays(6);
        BnplWaiver proposed = world.proposeWaiver(day, bought, fee.id(), 5_00, world.operator());
        world.base.concurrently(2, index -> {
            try {
                if (index == 0) {
                    world.approveWaiver(day, bought, proposed.id(), world.operator());
                } else {
                    world.base.repayOn(day, bought, "fee", 5_00);
                }
            } catch (BnplWaivers.WaiverExceedsDue exceeds) {
                return "refused";
            }
            return "done";
        });
        long waived = count("SELECT count(*) FROM bnpl.bnpl_waiver_decision WHERE waiver_id = '" + proposed.id()
                + "' AND outcome = 'APPROVED'");
        long paidFees = Long.parseLong(scalar("SELECT coalesce(sum(a.amount_minor), 0)::text FROM"
                + " bnpl.bnpl_repayment_allocation a WHERE a.instalment_id = '" + bought.instalment(2)
                + "' AND a.component = 'FEES'"));
        assertThat(waived * 5_00 + paidFees).as("the fee forgiven or paid, never both").isEqualTo(5_00);
        assertThat(world.due(bought, 2, "FEES")).isZero();
    }

    @Test
    @DisplayName("a waiver of the last receivable closes the obligation REPAID beside its closure row")
    void aWaiverOfTheLastReceivableCloses() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.base.purchaseUnder(BnplTermsFixtures.payIn4()
                .lateFee(eur(5_00)).allocationOrder(List.of(AllocationComponent.PRINCIPAL, AllocationComponent.INTEREST,
                        AllocationComponent.FEES)).build());
        world.base.serviceOn(bought.due(2), bought);
        world.base.repayOn(bought.due(2), bought, "i2", 150_00);
        world.base.serviceOn(bought.due(3), bought);
        world.base.repayOn(bought.due(3), bought, "i3", 150_00);
        world.base.serviceOn(bought.due(4), bought);
        world.base.serviceOn(bought.due(4).plusDays(5), bought);
        world.base.repayOn(bought.due(4).plusDays(5), bought, "i4", 150_00);
        BnplFeeAssessment last = world.fees(bought).get(0);
        LocalDate day = bought.due(4).plusDays(6);
        BnplWaiver proposed = world.proposeWaiver(day, bought, last.id(), 5_00, world.operator());
        assertThat(world.approveWaiver(day, bought, proposed.id(), world.operator()).closure()).isPresent();
        assertThat(scalar("SELECT status || ':' || closure_reason FROM bnpl.bnpl_obligation WHERE id = '" + bought.id()
                + "'")).isEqualTo("CLOSED:REPAID");
    }

    @Test
    @DisplayName("the decision's four-eyes CHECK and its UNIQUE (waiver_id), for every writer")
    void theDecisionsRanks() throws SQLException {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.base.feeBearing();
        BnplFeeAssessment fee = lateFee(world, bought);
        BnplWaiver proposed = world.proposeWaiver(bought.due(2).plusDays(5), bought, fee.id(), 5_00, world.operator());
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            refused(migrator, "INSERT INTO bnpl.bnpl_waiver_decision (id, waiver_id, proposed_by, outcome, decided_by,"
                    + " decided_by_type, decided_at, decision_reason, journal_entry_id, correlation_id) SELECT"
                    + " gen_random_uuid(), w.id, w.proposed_by, 'APPROVED', w.proposed_by, 'EMPLOYEE', clock_timestamp(),"
                    + " 'mine', gen_random_uuid(), 'c' FROM bnpl.bnpl_waiver w WHERE w.id = '" + proposed.id() + "'",
                    "23514");
            migrator.rollback();
        }
    }

    @Test
    @DisplayName("the waiver takes no profile row (no L0) - exposure does not move")
    void theWaiverTakesTheIncomeRowLast() {
        BnplCorrectionDatabaseWorld world = new BnplCorrectionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.base.feeBearing();
        BnplFeeAssessment fee = lateFee(world, bought);
        LocalDate day = bought.due(2).plusDays(5);
        BnplWaiver proposed = world.proposeWaiver(day, bought, fee.id(), 5_00, world.operator());
        world.profilesLocked.clear();
        world.approveWaiver(day, bought, proposed.id(), world.operator());
        assertThat(world.profilesLocked).isEmpty();
    }
}
