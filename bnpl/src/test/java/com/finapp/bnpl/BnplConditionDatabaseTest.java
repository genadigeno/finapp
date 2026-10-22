package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.count;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.refused;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.scalar;
import static org.assertj.core.api.Assertions.assertThat;

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
 * Delinquency and default against a real database (`P12-TSK-020`; {@code bnpl V013}; scenario 13; {@code INV-LND-12} and
 * {@code INV-LND-08} by analogy, {@code INV-HIST-01}): {@link BnplConditions}' condition step over bnpl's own JDBC stores,
 * on a database clock moved by {@link BnplClockFrame} - each bucket and the cure, the default at 90 cleared at cure, a
 * re-run day writing nothing, ten sweepers one change, a cure between sweeps recorded, a missed day computed from the
 * dates, grace delaying only the fee, no condition after closure with a closing repayment racing the worker - and
 * {@code bnpl V013}'s ranks for every writer: {@code UNIQUE (obligation_id, business_date, kind)}, DPD, bucket and flag
 * derived from the rows, only on a change, never on a closed obligation, INSERT-only for every role.
 *
 * <p>Written by `P12-TSK-020` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@DisplayName("delinquency and default against bnpl V013's ranks (P12-TSK-020)")
class BnplConditionDatabaseTest {

    @BeforeAll
    static void termsAndClock() {
        BnplBillingDatabaseTest.termsAndClock();
    }

    @Test
    @DisplayName("scenario 13: ten servicing steps and the customer's closing repayment race on the final due date - B4"
            + " orders them; once CLOSED no condition row is written, and the trigger refuses one for every writer")
    void noConditionAfterClosure() throws Exception {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate last = bought.due(4);
        world.serviceOn(bought.due(2).plusDays(1), bought);
        world.concurrently(11, index -> index == 10
                ? world.repayOn(last, bought, "closing", 450_00)
                : world.serviceOn(last, bought));
        world.serviceOn(last, bought);
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'")).isEqualTo("CLOSED");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_condition_event e JOIN bnpl.bnpl_obligation_closure c ON"
                + " c.obligation_id = e.obligation_id WHERE e.obligation_id = '" + bought.id() + "' AND e.recorded_order >"
                + " c.recorded_order")).as("nothing after the closure, in the record's order (V017) - never a clock").isZero();
        assertRecordOrdered(bought);
        try (Connection app = BnplClockFrame.framed(DatabaseRoles.application(), world.frameOn(last.plusDays(1)))) {
            refused(app, "INSERT INTO bnpl.bnpl_condition_event (id, obligation_id, business_date, kind, days_past_due,"
                    + " bucket, previous_bucket, defaulted, agreement_id, agreement_version, created_at, correlation_id)"
                    + " SELECT gen_random_uuid(), o.id, DATE '" + last.plusDays(1) + "', 'DEFAULT', 0, 'CURRENT', NULL,"
                    + " false, o.agreement_id, 1, clock_timestamp(), 'raw' FROM bnpl.bnpl_obligation o WHERE o.id = '"
                    + bought.id() + "'", "P0001", "a BNPL condition is recorded only for an ACTIVE obligation");
        }
    }

    @Test
    @DisplayName("scenario 13, the payoff during the worker (P12-TSK-022): ten servicing steps and a payoff race on a"
            + " delinquent, fee-bearing obligation - B4 orders them; the payoff pays the assessed fee with the rest, and once"
            + " CLOSED (PAID_OFF_EARLY) no condition or fee row is written")
    void noConditionAfterAPayoff() throws Exception {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.feeBearing();
        LocalDate day = bought.due(2).plusDays(5);
        world.serviceOn(day, bought);
        assertThat(world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_FEES_DUE).minorUnits())
                .as("the late fee assessed").isEqualTo(5_00);
        world.concurrently(11, index -> {
            if (index == 10) {
                return world.payOffOn(day, bought, "payoff");
            }
            return world.serviceOn(day, bought);
        });
        assertThat(scalar("SELECT status || ':' || closure_reason FROM bnpl.bnpl_obligation WHERE id = '" + bought.id()
                + "'")).isEqualTo("CLOSED:PAID_OFF_EARLY");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_condition_event e JOIN bnpl.bnpl_obligation_closure c ON"
                + " c.obligation_id = e.obligation_id WHERE e.obligation_id = '" + bought.id() + "' AND e.recorded_order >"
                + " c.recorded_order")).as("nothing after the closure, in the record's order (V017) - never a clock").isZero();
        assertRecordOrdered(bought);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_fee_assessment WHERE obligation_id = '" + bought.id() + "'"))
                .isEqualTo(1);
        for (com.finapp.ledger.AccountPurpose purpose : BnplAccounts.purposes()) {
            assertThat(world.balance(bought, purpose).isZero()).as(purpose.name()).isTrue();
        }
        world.serviceOn(day.plusDays(10), bought);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_fee_assessment WHERE obligation_id = '" + bought.id() + "'"))
                .as("no fee after the payoff").isEqualTo(1);
    }

    @Test
    @DisplayName("current to each bucket and cure, recorded once per change from the rows (DPD 1, 30, 60, then CURRENT)")
    void currentToEachBucketAndCure() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate due = bought.due(2);
        world.serviceOn(due.plusDays(1), bought);
        world.serviceOn(due.plusDays(13), bought);
        world.repayOn(due.plusDays(13), bought, "cure", 150_00);
        world.serviceOn(due.plusDays(14), bought);
        assertThat(history(bought)).containsExactly(due.plusDays(1) + ":DELINQUENCY:1:B1_1_29:false:CURRENT",
                due.plusDays(14) + ":DELINQUENCY:0:CURRENT:false:B1_1_29");
        BnplConditionDatabaseWorld.Purchased late = world.plain();
        LocalDate first = late.due(2);
        for (int age : List.of(30, 60)) {
            world.serviceOn(first.plusDays(age), late);
        }
        assertThat(history(late)).containsExactly(first.plusDays(30) + ":DELINQUENCY:30:B2_30_59:false:CURRENT",
                first.plusDays(60) + ":DELINQUENCY:60:B3_60_89:false:B2_30_59");
    }

    @Test
    @DisplayName("default at the pinned 90 days in the same step as B4 - two rows, bnpl.BnplDefaulted in the outbox")
    void defaultAtNinetyClearedAtCure() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate due = bought.due(2);
        world.serviceOn(due.plusDays(90), bought);
        assertThat(history(bought)).containsExactly(due.plusDays(90) + ":DELINQUENCY:90:B4_90_PLUS:true:CURRENT",
                due.plusDays(90) + ":DEFAULT:90:B4_90_PLUS:true:-");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = '" + bought.id()
                + "' AND event_type = '" + BnplConditions.DEFAULTED_EVENT + "'")).isEqualTo(1);
        // The cure of a pay-in-4 obligation 90 days late is its closure: all four instalments are billed by then.
        world.repayOn(due.plusDays(91), bought, "everything", 450_00);
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'")).isEqualTo("CLOSED");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_condition_event WHERE obligation_id = '" + bought.id() + "'"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("default posts nothing and accelerates nothing, and a real cure clears it once (the review's finding: the"
            + " pay-in-4 case above cures only by closing): a pay-in-6 obligation, instalment 1 unpaid 90 days - the"
            + " default step adds no ledger line or key beyond a billing due that day, no schedule version and bills nothing"
            + " early; the past-due amount repaid while ACTIVE, DPD back to 0 - exactly one bnpl.BnplDefaultCleared, the"
            + " obligation still ACTIVE")
    void defaultPostsNothingAndACureWhileActiveClearsItOnce() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.payInN(BnplPlan.PAY_IN_6_MONTHS);
        List<LocalDate> dues = bought.schedule().instalments().stream()
                .map(BnplPurchaseStore.StoredInstalment::dueDate).toList();
        LocalDate defaultDay = bought.due(1).plusDays(90);
        // Every instalment due before the default day billed on its due date, its period accrued first (T-acc, then T-svc).
        for (LocalDate due : dues) {
            if (due.isBefore(defaultDay)) {
                world.catchUpOn(due, bought);
                world.serviceOn(due, bought);
            }
        }
        world.catchUpOn(defaultDay, bought);
        String billings = "SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = '" + bought.id() + "'";
        long billedBefore = count(billings);
        List<String> keysBefore = world.book.keys();
        int linesBefore;
        synchronized (world.book) {
            linesBefore = world.fakes.book.lines.size();
        }

        world.serviceOn(defaultDay, bought);

        assertThat(history(bought)).contains(defaultDay + ":DEFAULT:90:B4_90_PLUS:true:-")
                .anyMatch(row -> row.startsWith(defaultDay + ":DELINQUENCY:90:B4_90_PLUS:true:"));
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = '" + bought.id()
                + "' AND event_type = '" + BnplConditions.DEFAULTED_EVENT + "'")).isEqualTo(1);
        List<String> keysAfter = world.book.keys();
        assertThat(keysAfter.subList(keysBefore.size(), keysAfter.size())).as("the default step posts nothing of its own")
                .allMatch(key -> key.startsWith(BnplServicing.BILLING_KEY_PREFIX));
        long dueThatDay = dues.stream().filter(defaultDay::equals).count();
        assertThat(count(billings)).as("nothing accelerated: only an instalment due that day is billed")
                .isEqualTo(billedBefore + dueThatDay);
        if (dueThatDay == 0) {
            synchronized (world.book) {
                assertThat(world.fakes.book.lines).as("no ledger line at all").hasSize(linesBefore);
            }
        }
        assertThat(scalar("SELECT max(schedule_version)::text FROM bnpl.bnpl_schedule WHERE obligation_id = '"
                + bought.id() + "'")).as("no new schedule version").isEqualTo("1");
        assertThat(scalar("SELECT max(version)::text FROM bnpl.bnpl_agreement WHERE obligation_id = '" + bought.id()
                + "'")).as("no new agreement version").isEqualTo("1");

        // The cure: the whole past-due amount repaid the next day, the obligation still owing unbilled instalments.
        LocalDate cure = defaultDay.plusDays(1);
        world.catchUpOn(cure, bought);
        long owed = world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_PRINCIPAL_DUE).minorUnits()
                + world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_INTEREST_DUE).minorUnits()
                + world.balance(bought, com.finapp.ledger.AccountPurpose.BNPL_FEES_DUE).minorUnits();
        world.repayOn(cure, bought, "cure", owed);
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'"))
                .as("cured, not closed").isEqualTo("ACTIVE");
        world.serviceOn(cure, bought);
        world.serviceOn(cure.plusDays(1), bought);
        assertThat(scalar("SELECT bnpl.days_past_due('" + bought.id() + "', DATE '" + cure + "')::text")).isEqualTo("0");
        assertThat(history(bought)).contains(cure + ":DELINQUENCY:0:CURRENT:false:B4_90_PLUS",
                cure + ":DEFAULT:0:CURRENT:false:-");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_condition_event WHERE obligation_id = '" + bought.id()
                + "' AND kind = 'DEFAULT' AND NOT defaulted")).as("cleared once").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = '" + bought.id()
                + "' AND event_type = '" + BnplConditions.DEFAULT_CLEARED_EVENT + "'")).as("one BnplDefaultCleared")
                .isEqualTo(1);
        assertThat(scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = '" + bought.id() + "'"))
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("re-running a day writes nothing: three steps on one date, one row")
    void reRunningADayWritesNothing() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate day = bought.due(2).plusDays(2);
        for (int step = 0; step < 3; step++) {
            world.serviceOn(day, bought);
        }
        assertThat(history(bought)).containsExactly(day + ":DELINQUENCY:2:B1_1_29:false:CURRENT");
    }

    @Test
    @DisplayName("ten sweepers on one date record one condition change - B4 orders them, the unique beneath")
    void tenSweepersOneConditionChange() throws Exception {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate day = bought.due(2).plusDays(3);
        world.concurrently(10, index -> world.serviceOn(day, bought));
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_condition_event WHERE obligation_id = '" + bought.id() + "'"))
                .isEqualTo(1);
        try (Connection bootstrap = DatabaseRoles.bootstrap()) {
            try (java.sql.Statement replica = bootstrap.createStatement()) {
                replica.execute("SET session_replication_role = replica");
            }
            bootstrap.setAutoCommit(false);
            refused(bootstrap, "INSERT INTO bnpl.bnpl_condition_event SELECT gen_random_uuid(), obligation_id,"
                    + " business_date, kind, days_past_due, bucket, previous_bucket, defaulted, agreement_id,"
                    + " agreement_version, clock_timestamp(), correlation_id FROM bnpl.bnpl_condition_event WHERE"
                    + " obligation_id = '" + bought.id() + "'", "23505");
        }
    }

    @Test
    @DisplayName("a cure between sweeps is recorded, not overwritten - both rows kept")
    void aCureBetweenSweepsIsRecordedNotOverwritten() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate due = bought.due(2);
        world.serviceOn(due.plusDays(4), bought);
        world.repayOn(due.plusDays(5), bought, "cure", 150_00);
        world.serviceOn(due.plusDays(6), bought);
        assertThat(history(bought)).containsExactly(due.plusDays(4) + ":DELINQUENCY:4:B1_1_29:false:CURRENT",
                due.plusDays(6) + ":DELINQUENCY:0:CURRENT:false:B1_1_29");
    }

    @Test
    @DisplayName("a missed day computes from the dates: day 1, then day 34 - DPD 34 in one row; bnpl.days_past_due agrees")
    void aMissedDayComputesFromTheDates() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate due = bought.due(2);
        world.serviceOn(due.plusDays(1), bought);
        world.serviceOn(due.plusDays(34), bought);
        assertThat(history(bought)).containsExactly(due.plusDays(1) + ":DELINQUENCY:1:B1_1_29:false:CURRENT",
                due.plusDays(34) + ":DELINQUENCY:34:B2_30_59:false:B1_1_29");
        assertThat(scalar("SELECT bnpl.days_past_due('" + bought.id() + "', DATE '" + due.plusDays(50) + "')::text"))
                .isEqualTo("50");
    }

    @Test
    @DisplayName("grace delays only the late fee, never DPD: DPD 1 recorded the day after the due date on fee-bearing"
            + " terms, no fee until age 5")
    void graceDelaysOnlyTheLateFeeNeverDpd() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.feeBearing();
        LocalDate due = bought.due(2);
        world.serviceOn(due.plusDays(1), bought);
        assertThat(history(bought)).containsExactly(due.plusDays(1) + ":DELINQUENCY:1:B1_1_29:false:CURRENT");
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_fee_assessment WHERE obligation_id = '" + bought.id() + "'"))
                .isZero();
    }

    @Test
    @DisplayName("the history is append-only for every role: a writer's DPD, a change that is none, a back-dated row -"
            + " refused; UPDATE and DELETE refused to the application (grants) and the migrator (trigger); never"
            + " truncated")
    void theHistoryIsAppendOnlyForEveryRole() throws SQLException {
        BnplConditionDatabaseWorld world = new BnplConditionDatabaseWorld();
        BnplConditionDatabaseWorld.Purchased bought = world.plain();
        LocalDate day = bought.due(2).plusDays(10);
        world.serviceOn(day, bought);
        String insert = "INSERT INTO bnpl.bnpl_condition_event (id, obligation_id, business_date, kind, days_past_due,"
                + " bucket, previous_bucket, defaulted, agreement_id, agreement_version, created_at, correlation_id)"
                + " SELECT gen_random_uuid(), o.id, DATE '%s', '%s', %d, '%s', %s, %s, o.agreement_id, 1,"
                + " clock_timestamp(), 'raw' FROM bnpl.bnpl_obligation o WHERE o.id = '" + bought.id() + "'";
        try (Connection app = BnplClockFrame.framed(DatabaseRoles.application(), world.frameOn(day.plusDays(1)))) {
            // A writer's DPD: 40 is not the rows' 11.
            refused(app, String.format(insert, day.plusDays(1), "DELINQUENCY", 40, "B2_30_59", "'B1_1_29'", "false"),
                    "P0001", "days past due and bucket are derived from its billed and settled rows");
            // No change: the flag "changed" from false to false.
            refused(app, String.format(insert, day.plusDays(1), "DEFAULT", 11, "B1_1_29", "NULL", "false"), "P0001",
                    "recorded only on a change, from the latest recorded one");
        }
        try (Connection app = BnplClockFrame.framed(DatabaseRoles.application(), world.frameOn(day.minusDays(1)))) {
            // Dated before the recorded change.
            refused(app, String.format(insert, day.minusDays(1), "DEFAULT", 9, "B1_1_29", "NULL", "true"), "P0001",
                    "never dated before a change already recorded");
        }
        try (Connection app = DatabaseRoles.application(); Connection migrator = DatabaseRoles.migrator()) {
            app.setAutoCommit(false);
            migrator.setAutoCommit(false);
            refused(app, "UPDATE bnpl.bnpl_condition_event SET days_past_due = 0 WHERE obligation_id = '" + bought.id()
                    + "'", "42501");
            refused(app, "DELETE FROM bnpl.bnpl_condition_event", "42501");
            refused(migrator, "UPDATE bnpl.bnpl_condition_event SET days_past_due = 0 WHERE obligation_id = '"
                    + bought.id() + "'", "P0001", "a BNPL condition change is immutable from insert");
            refused(migrator, "DELETE FROM bnpl.bnpl_condition_event WHERE obligation_id = '" + bought.id() + "'",
                    "P0001", "a BNPL condition change is immutable from insert");
            refused(migrator, "TRUNCATE bnpl.bnpl_condition_event", "P0001", "are never truncated");
        }
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The "after the closure" comparison is not vacuous: the closure and every condition row of the obligation carry the
     * record's order (bnpl V017), so a NULL never silently compares false.
     */
    private static void assertRecordOrdered(BnplConditionDatabaseWorld.Purchased bought) throws SQLException {
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_obligation_closure WHERE obligation_id = '" + bought.id()
                + "' AND recorded_order IS NOT NULL")).as("the closure, ordered").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_condition_event WHERE obligation_id = '" + bought.id()
                + "' AND recorded_order IS NULL")).as("every condition row, ordered").isZero();
    }

    private static List<String> history(BnplConditionDatabaseWorld.Purchased bought) throws SQLException {
        String rows = scalar("SELECT string_agg(business_date || ':' || kind || ':' || days_past_due || ':' || bucket"
                + " || ':' || defaulted || ':' || coalesce(previous_bucket, '-'), ',' ORDER BY business_date, kind DESC)"
                + " FROM bnpl.bnpl_condition_event WHERE obligation_id = '" + bought.id() + "'");
        return rows == null ? List.of() : List.of(rows.split(","));
    }
}
