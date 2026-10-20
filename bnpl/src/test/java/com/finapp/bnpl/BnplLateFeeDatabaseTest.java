package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.count;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.refused;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.scalar;
import static com.finapp.bnpl.BnplConditionDatabaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Late fees and their release against a real database (`P12-TSK-020`; {@code bnpl V013}; scenario 17; A13, OQ-14;
 * {@code INV-IDEM-02}, {@code INV-BNPL-06}): {@link BnplConditions}' late-fee step over bnpl's JDBC stores on a moved
 * database clock - once at the late-fee day, ten sweepers one fee, the cap, grace - the release on a refund that cancels the
 * instalment whole, and {@code bnpl V013}'s ranks for every writer: {@code UNIQUE (instalment_id, kind)}, the day, the fee,
 * the cap, the owing instalment, the fee joining its instalment's {@code FEES}, the release settled as {@code FEES} and
 * conserved at commit, INSERT-only. The book is the in-memory one behind a lock; the fee income row's real lock order is
 * {@code app}'s {@code BnplCollectionLedgerDatabaseTest#theFeeIncomeRowIsTakenAfterTheWallet}.
 *
 * <p>Written by `P12-TSK-020` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@DisplayName("late fees and their release against bnpl V013's ranks (P12-TSK-020)")
class BnplLateFeeDatabaseTest {

    @BeforeAll
    static void termsAndClock() {
        BnplBillingDatabaseTest.termsAndClock();
    }

    @Test
    @DisplayName("assessed once at the late-fee day: nothing at age 4, 5.00 at age 5 under bnpl.fee:<instalment>:LATE -"
            + " the row, the fee joined to the instalment's FEES, bnpl.BnplLateFeeAssessed - nothing the next day")
    void assessedOnceAtTheLateFeeDay() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.feeBearing();
        LocalDate due = bought.due(2);
        world.serviceOn(due.plusDays(4), bought);
        assertThat(fees(bought)).isEmpty();
        world.serviceOn(due.plusDays(5), bought);
        world.serviceOn(due.plusDays(6), bought);
        assertThat(fees(bought)).containsExactly("2:" + due.plusDays(5) + ":500");
        assertThat(world.book.keys()).contains(BnplFeeAssessment.postingKey(bought.instalment(2), BnplFeeKind.LATE));
        assertThat(scalar("SELECT bnpl.billing_component_billed(b.id, 'FEES')::text FROM bnpl.bnpl_billing b WHERE"
                + " b.instalment_id = '" + bought.instalment(2) + "'")).isEqualTo("500");
        assertThat(world.balance(bought, AccountPurpose.BNPL_FEES_DUE)).isEqualTo(eur(5_00));
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = '" + bought.id()
                + "' AND event_type = '" + BnplConditions.LATE_FEE_ASSESSED_EVENT + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("ten sweepers on the fee's day assess once - B4 orders them; beneath it UNIQUE (instalment_id, kind)"
            + " refuses a second row")
    void tenSweepersAssessOnce() throws Exception {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.feeBearing();
        LocalDate day = bought.due(2).plusDays(5);
        world.concurrently(10, index -> world.serviceOn(day, bought));
        assertThat(fees(bought)).containsExactly("2:" + day + ":500");
        assertThat(world.book.keys()).filteredOn(key -> key.startsWith(BnplConditions.FEE_KEY_PREFIX)).hasSize(1);
        try (Connection bootstrap = DatabaseRoles.bootstrap()) {
            try (Statement replica = bootstrap.createStatement()) {
                replica.execute("SET session_replication_role = replica");
            }
            bootstrap.setAutoCommit(false);
            refused(bootstrap, "INSERT INTO bnpl.bnpl_fee_assessment SELECT gen_random_uuid(), obligation_id,"
                    + " instalment_id, kind, business_date, currency, scale, amount_minor, agreement_id,"
                    + " agreement_version, gen_random_uuid(), clock_timestamp(), correlation_id FROM"
                    + " bnpl.bnpl_fee_assessment WHERE obligation_id = '" + bought.id() + "'", "23505");
        }
    }

    @Test
    @DisplayName("the cap holds per agreement: 5.00 fees under a 12.00 cap - 5.00, 5.00 and the 2.00 the cap admits;"
            + " nothing more; and a raw fee beyond the cap is refused")
    void theCapHolds() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.purchaseUnder(BnplTermsFixtures.payIn4().lateFee(eur(5_00))
                .lateFeeCap(eur(12_00)).build());
        LocalDate day = bought.due(4).plusDays(5);
        world.serviceOn(day, bought);
        world.serviceOn(day.plusDays(20), bought);
        assertThat(fees(bought)).containsExactly("2:" + day + ":500", "3:" + day + ":500", "4:" + day + ":200");
        assertThat(count("SELECT sum(amount_minor) FROM bnpl.bnpl_fee_assessment WHERE obligation_id = '" + bought.id()
                + "'")).isEqualTo(12_00);
    }

    @Test
    @DisplayName("grace delays the fee: none at ages 1 to 4 (grace 3, day 5), the fee at 5")
    void graceDelaysTheFee() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.feeBearing();
        LocalDate due = bought.due(2);
        for (int age = 1; age <= 4; age++) {
            world.serviceOn(due.plusDays(age), bought);
        }
        assertThat(fees(bought)).isEmpty();
        world.serviceOn(due.plusDays(5), bought);
        assertThat(fees(bought)).hasSize(1);
    }

    @Test
    @DisplayName("scenario 17's release half (OQ-14): a refund that cancels the fee-bearing instalment whole releases its"
            + " fee in the refund's entry - adjustment and refund rows say 500, FEES settled, BNPL_FEES_DUE zero - and a"
            + " partial cancellation releases nothing")
    void aCancelledInstalmentsLateFeeIsReleased() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.feeBearing();
        LocalDate day = bought.due(2).plusDays(5);
        world.serviceOn(day, bought);
        BnplRefund refund = world.refundOn(day.plusDays(1), bought, "the rest", 450_00).refund();
        assertThat(refund.lateFeeReleased()).isEqualTo(eur(5_00));
        assertThat(scalar("SELECT late_fee_released_minor::text FROM bnpl.bnpl_refund WHERE id = '" + refund.id() + "'"))
                .isEqualTo("500");
        assertThat(scalar("SELECT string_agg(sequence || ':' || kind || ':' || whole || ':' || late_fee_released_minor,"
                + " ',' ORDER BY sequence DESC) FROM bnpl.bnpl_refund_adjustment WHERE refund_id = '" + refund.id() + "'"))
                .isEqualTo("4:UNBILLED:true:0,3:UNBILLED:true:0,2:BILLED_UNPAID:true:500");
        assertThat(scalar("SELECT (bnpl.billing_component_billed(b.id, 'FEES') - bnpl.billing_component_paid(b.id,"
                + " 'FEES'))::text FROM bnpl.bnpl_billing b WHERE b.instalment_id = '" + bought.instalment(2) + "'"))
                .isEqualTo("0");
        assertThat(world.balance(bought, AccountPurpose.BNPL_FEES_DUE)).isEqualTo(eur(0));

        BnplConditionDatabaseWorld.Purchased partly = world.feeBearing();
        world.serviceOn(partly.due(2).plusDays(5), partly);
        BnplRefund part = world.refundOn(partly.due(2).plusDays(6), partly, "part", 330_00).refund();
        assertThat(part.lateFeeReleased()).isEqualTo(eur(0));
        assertThat(world.balance(partly, AccountPurpose.BNPL_FEES_DUE)).isEqualTo(eur(5_00));
    }

    @Test
    @DisplayName("the fee's and the release's ranks for every writer: before the day, above the fee, on a settled"
            + " instalment - refused; INSERT-only, never truncated")
    void theRanksHoldForEveryWriter() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.feeBearing();
        LocalDate due = bought.due(2);
        world.serviceOn(due, bought);
        String insert = "INSERT INTO bnpl.bnpl_fee_assessment (id, obligation_id, instalment_id, kind, business_date,"
                + " currency, scale, amount_minor, agreement_id, agreement_version, journal_entry_id, created_at,"
                + " correlation_id) SELECT gen_random_uuid(), o.id, '" + bought.instalment(2) + "', 'LATE', DATE '%s',"
                + " 'EUR', 2, %d, o.agreement_id, 1, gen_random_uuid(), clock_timestamp(), 'raw' FROM"
                + " bnpl.bnpl_obligation o WHERE o.id = '" + bought.id() + "'";
        try (Connection app = BnplClockFrame.framed(DatabaseRoles.application(), world.frameOn(due.plusDays(4)))) {
            refused(app, String.format(insert, due.plusDays(4), 5_00), "P0001");
        }
        try (Connection app = BnplClockFrame.framed(DatabaseRoles.application(), world.frameOn(due.plusDays(6)))) {
            refused(app, String.format(insert, due.plusDays(6), 5_01), "P0001");
        }
        world.repayOn(due.plusDays(6), bought, "settled", 150_00);
        try (Connection app = BnplClockFrame.framed(DatabaseRoles.application(), world.frameOn(due.plusDays(7)))) {
            refused(app, String.format(insert, due.plusDays(7), 5_00), "P0001");
        }
        assertThat(fees(bought)).isEmpty();
        BnplConditionDatabaseWorld.Purchased owing = world.feeBearing();
        world.serviceOn(owing.due(2).plusDays(5), owing);
        try (Connection app = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            app.setAutoCommit(false);
            migrator.setAutoCommit(false);
            refused(app, "UPDATE bnpl.bnpl_fee_assessment SET amount_minor = 1 WHERE obligation_id = '" + owing.id()
                    + "'", "42501");
            refused(app, "DELETE FROM bnpl.bnpl_fee_assessment", "42501");
            refused(migrator, "UPDATE bnpl.bnpl_fee_assessment SET amount_minor = 1 WHERE obligation_id = '"
                    + owing.id() + "'", "P0001");
            refused(migrator, "DELETE FROM bnpl.bnpl_fee_assessment WHERE obligation_id = '" + owing.id() + "'", "P0001");
            // Since bnpl V016 (P12-TSK-024) bnpl_waiver references the assessment, so a plain TRUNCATE meets that
            // foreign key first (0A000); a CASCADE reaches the truncate triggers, and the trigger still refuses it - the
            // credit V007 / lending V016 convention (P12-TSK-027).
            refused(migrator, "TRUNCATE bnpl.bnpl_fee_assessment", "0A000");
            refused(migrator, "TRUNCATE bnpl.bnpl_fee_assessment CASCADE", "P0001");
        }
    }

    private static List<String> fees(BnplConditionDatabaseWorld.Purchased bought) throws SQLException {
        String rows = scalar("SELECT string_agg(i.sequence || ':' || f.business_date || ':' || f.amount_minor, ','"
                + " ORDER BY i.sequence) FROM bnpl.bnpl_fee_assessment f JOIN bnpl.bnpl_instalment i ON i.id ="
                + " f.instalment_id WHERE f.obligation_id = '" + bought.id() + "'");
        return rows == null ? List.of() : List.of(rows.split(","));
    }
}
