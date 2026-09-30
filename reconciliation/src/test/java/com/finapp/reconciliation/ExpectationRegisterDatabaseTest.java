package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The register against the real schema (`P8-TSK-004`, ADR-0067, ADR-0068): rule set v1
 * seeded whole and frozen, an amount tolerance unstorable, the opening convergent under ten
 * instances, collisions recorded and skipped, the machine and the grants binding every
 * writer.
 *
 * <p>Everything here runs as {@code finapp_app}; the migrator's own binding is the gate's
 * probe territory.
 */
@Tag("database")
@DisplayName("the expectation register and rule set v1 (P8-TSK-004)")
class ExpectationRegisterDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID PSP_SOURCE =
            UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    private static final UUID PSP_RULE_SET =
            UUID.fromString("01a0e2bd-8300-7001-8000-000000000001");

    private static Connection application;
    private static JdbcExpectationRegister register;
    private static JdbcRuleSets ruleSets;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        register = new JdbcExpectationRegister(IDS);
        ruleSets = new JdbcRuleSets();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    // -----------------------------------------------------------------
    // The seed: one ACTIVE version per source, the decided values, frozen.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("version 1 is seeded ACTIVE for each of the four sources with the decided"
            + " values, and the active read resolves it with its lags")
    void versionOneIsSeededActivePerSource() throws SQLException {
        // Scoped to the migration's own rows: another suite may commit a private rule
        // set of its own (MatchingDatabaseTest does), and this guard is V002's seed.
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE status ="
                + " 'ACTIVE' AND correlation_id = 'p8-tsk-004-migration'"))
                .isEqualTo(4);
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE"
                + " correlation_id = 'p8-tsk-004-migration'"))
                .as("only version 1 exists")
                .isEqualTo(4);

        RuleSets.ActiveRuleSet psp = ruleSets.activeFor(application, PSP_SOURCE);
        assertThat(psp.id()).isEqualTo(PSP_RULE_SET);
        assertThat(psp.version()).isEqualTo(1);
        assertThat(psp.fundingLagDays()).isEqualTo(2);
        assertThat(psp.lagDaysFor(ExpectationKind.CARD_CAPTURE)).isEqualTo(3);
        assertThat(psp.lagDaysFor(ExpectationKind.CARD_REFUND)).isEqualTo(3);

        assertThat(count("SELECT count(*) FROM reconciliation.tolerance WHERE comparison ="
                + " 'SETTLEMENT_DATE_DAYS' AND days = 2 AND rule_set_id IN (SELECT id"
                + " FROM reconciliation.rule_set WHERE correlation_id ="
                + " 'p8-tsk-004-migration')"))
                .isEqualTo(4);
        assertThat(count("SELECT count(*) FROM reconciliation.provider_fee_schedule WHERE"
                + " rule_set_id = ? AND line_type = 'PROCESSING_FEE' AND rate = 0.015000"
                + " AND fixed_minor = 25", PSP_RULE_SET))
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM reconciliation.severity_threshold WHERE"
                + " high_value_minor = 100000 AND rule_set_id IN (SELECT id FROM"
                + " reconciliation.rule_set WHERE correlation_id ="
                + " 'p8-tsk-004-migration')"))
                .isEqualTo(12);
        assertThat(count("SELECT count(*) FROM reconciliation.rule WHERE operation_anchored"
                + " AND line_type = 'PAYOUT_RETURNED' AND grace_hours = 72"))
                .as("the operation-anchored payout return (the transition's A4)")
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM reconciliation.rule WHERE operation_anchored"
                + " AND line_type <> 'PAYOUT_RETURNED'"))
                .as("v1's ONE anchored rule")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("an amount tolerance is unstorable (INV-REC-08 at the database rank), and"
            + " the seed is frozen for the application role AND the migrator")
    void amountTolerancesAreUnstorableAndTheSeedIsFrozen() throws SQLException {
        assertThat(refusal(
                        "INSERT INTO reconciliation.tolerance (rule_set_id, comparison,"
                                + " currency, absolute_minor, days) VALUES (?,"
                                + " 'PRINCIPAL_AMOUNT', 'EUR', 100, NULL)",
                        PSP_RULE_SET))
                .as("the comparison list has no amount member")
                .contains("tolerance_comparison");

        // The application role: refused at the grant rank (no UPDATE or DELETE exists) -
        // every rule-set member table, so no seeded value is quietly editable.
        assertThat(refusal(
                        "UPDATE reconciliation.rule_set SET gain_min_age_days = 0 WHERE"
                                + " id = ?",
                        PSP_RULE_SET))
                .contains("permission denied");
        for (String sql :
                List.of(
                        "DELETE FROM reconciliation.rule WHERE grace_hours = 72",
                        "UPDATE reconciliation.rule_set_lag SET lag_days = 0",
                        "DELETE FROM reconciliation.rule_set_lag",
                        "UPDATE reconciliation.tolerance SET days = 30",
                        "UPDATE reconciliation.provider_fee_schedule SET rate = 0",
                        "DELETE FROM reconciliation.provider_fee_schedule",
                        "UPDATE reconciliation.severity_threshold SET high_value_minor = 0",
                        "DELETE FROM reconciliation.severity_threshold")) {
            assertThat(refusal(sql))
                    .as("no seeded policy is editable by the application role: %s", sql)
                    .contains("permission denied");
        }

        // The migrator, whom no grant binds: the trigger is the every-writer rank.
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try (PreparedStatement raw =
                    migrator.prepareStatement(
                            "UPDATE reconciliation.rule_set SET gain_min_age_days = 0"
                                    + " WHERE id = ?")) {
                raw.setObject(1, PSP_RULE_SET);
                assertThatThrownBy(raw::executeUpdate)
                        .as("content frozen by trigger for EVERY writer (ADR-0068 §8)")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("immutable");
            } finally {
                migrator.rollback();
            }
        }
    }

    // -----------------------------------------------------------------
    // Opening: convergent, keys registered, collisions recorded and skipped.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an opening lands the row, its OPENED event and its keys; the same identity"
            + " converges; a colliding key is skipped and recorded")
    void openingsConvergeAndCollisionsRecord() throws SQLException {
        String operation = "op-" + UUID.randomUUID();
        String sharedKey = "psp-" + UUID.randomUUID();

        assertThat(register.open(application, expectation(operation, sharedKey)))
                .isEqualTo(ExpectationRegister.OpenResult.OPENED);
        application.commit();
        UUID first = expectationIdOf(operation);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                + " expectation_id = ? AND event_type = 'OPENED'", first))
                .isEqualTo(1);
        assertThat(keyOwner(sharedKey)).isEqualTo(first);

        assertThat(register.open(application, expectation(operation, sharedKey)))
                .as("the identity uniques converge for any writer")
                .isEqualTo(ExpectationRegister.OpenResult.CONVERGED);
        application.commit();
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE"
                + " operation_ref = ?", operation))
                .isEqualTo(1);

        String collidingOperation = "op-" + UUID.randomUUID();
        assertThat(register.open(application, expectation(collidingOperation, sharedKey)))
                .as("a collision never refuses the opening")
                .isEqualTo(ExpectationRegister.OpenResult.OPENED);
        application.commit();
        UUID second = expectationIdOf(collidingOperation);
        assertThat(keyOwner(sharedKey)).as("the first writer stands").isEqualTo(first);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                + " expectation_id = ? AND event_type = 'KEY_COLLISION'", second))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ten instances opening one expectation: one row, one OPENED, nine converged")
    void tenRacingOpenersConverge() throws Exception {
        String operation = "race-" + UUID.randomUUID();
        String key = "psp-race-" + UUID.randomUUID();
        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ExpectationRegister.OpenResult> results =
                new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            threads.add(
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try (SimulatedInstance instance =
                                                SimulatedInstance.inAgreementWithTheServer()) {
                                            start.await();
                                            results.add(
                                                    register.open(
                                                            instance.connection(),
                                                            expectation(operation, key)));
                                            instance.commit();
                                        } catch (Exception failure) {
                                            failures.add(failure);
                                        }
                                    }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(60_000);
        }
        assertThat(failures).isEmpty();
        assertThat(results.stream()
                        .filter(ExpectationRegister.OpenResult.OPENED::equals)
                        .count())
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE"
                + " operation_ref = ?", operation))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event ev JOIN"
                + " reconciliation.expectation e ON ev.expectation_id = e.id WHERE"
                + " e.operation_ref = ? AND ev.event_type = 'OPENED'", operation))
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // The machine and the grants, for the writer the code is not.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the machine's edges hold by trigger, the birth statement is frozen, the"
            + " terminal is terminal, and nothing here is deletable or over-updatable")
    void theMachineAndTheGrantsBindRawSql() throws SQLException {
        String operation = "machine-" + UUID.randomUUID();
        register.open(application, expectation(operation, "psp-m-" + UUID.randomUUID()));
        application.commit();
        UUID id = expectationIdOf(operation);

        // A machine edge moves - the whole amount resolved is OPEN -> SETTLED. The raw
        // fixture rides resolved_minor: since V005 a raw allocated_minor without its
        // allocation rows is REFUSED at commit by the deferred sum trigger for every
        // writer (INV-REC-07, proven in MatchingDatabaseTest) - this suite proves the
        // MACHINE's edges, not the sum discipline.
        execute("UPDATE reconciliation.expectation SET status = 'SETTLED', resolved_minor ="
                + " amount_minor, status_changed_at = now() WHERE id = ?", id);
        application.commit();

        // The repudiation's reopening is an edge; a resolution out of SETTLED is not.
        assertThat(refusal(
                        "UPDATE reconciliation.expectation SET status ="
                                + " 'RESOLVED_BY_ADJUSTMENT' WHERE id = ?",
                        id))
                .as("SETTLED -> RESOLVED_BY_ADJUSTMENT: nothing remains to resolve")
                .contains("invalid expectation transition");

        execute("UPDATE reconciliation.expectation SET status = 'OPEN', resolved_minor = 0,"
                + " status_changed_at = now() WHERE id = ?", id);
        execute("UPDATE reconciliation.expectation SET status = 'RESOLVED_BY_ADJUSTMENT',"
                + " resolved_minor = amount_minor, status_changed_at = now() WHERE id = ?",
                id);
        application.commit();
        assertThat(refusal(
                        "UPDATE reconciliation.expectation SET status = 'OPEN' WHERE id = ?",
                        id))
                .as("RESOLVED_BY_ADJUSTMENT is terminal")
                .contains("invalid expectation transition");

        assertThat(refusal(
                        "UPDATE reconciliation.expectation SET amount_minor = amount_minor"
                                + " + 1 WHERE id = ?",
                        id))
                .as("outside the narrowed grant - and the trigger freezes it besides")
                .isNotBlank();

        for (String sql :
                List.of(
                        "DELETE FROM reconciliation.expectation WHERE id = '" + id + "'",
                        "UPDATE reconciliation.expectation_key SET key_value = 'x' WHERE"
                                + " expectation_id = '" + id + "'",
                        "DELETE FROM reconciliation.expectation_event WHERE expectation_id ="
                                + " '" + id + "'",
                        "UPDATE reconciliation.reference_alias SET anchor_value = 'x'",
                        "DELETE FROM reconciliation.reference_alias")) {
            assertThat(refusal(sql))
                    .as("append-only or undeletable for the application role: %s", sql)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("an alias registers once per source; the loser is recorded with no owning"
            + " expectation")
    void aliasesRegisterOnceAndCollisionsRecord() throws SQLException {
        String arn = "arn-" + UUID.randomUUID();
        long collisionsBefore = count("SELECT count(*) FROM"
                + " reconciliation.expectation_event WHERE event_type = 'KEY_COLLISION' AND"
                + " expectation_id IS NULL");

        register.registerAlias(application, alias(arn, "anchor-a"));
        application.commit();
        register.registerAlias(application, alias(arn, "anchor-b"));
        application.commit();

        assertThat(count("SELECT count(*) FROM reconciliation.reference_alias WHERE"
                + " key_value = ?", arn))
                .isEqualTo(1);
        assertThat(text("SELECT anchor_value FROM reconciliation.reference_alias WHERE"
                + " key_value = ?", arn))
                .as("the first writer stands")
                .isEqualTo("anchor-a");
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                + " event_type = 'KEY_COLLISION' AND expectation_id IS NULL"))
                .isEqualTo(collisionsBefore + 1);
    }

    // -----------------------------------------------------------------

    private static NewExpectation expectation(String operation, String key) {
        return new NewExpectation(
                ExpectationKind.CARD_CAPTURE,
                operation,
                "payment-capture:" + operation,
                PSP_SOURCE,
                AccountPurpose.SETTLEMENT_CLEARING,
                UUID.randomUUID(),
                ExpectationDirection.INBOUND,
                Money.ofMinorUnits(12_00, EUR),
                Optional.of(UUID.randomUUID()),
                LocalDate.parse("2026-09-29"),
                Optional.empty(),
                LocalDate.parse("2026-10-02"),
                PSP_RULE_SET,
                List.of(new NewExpectation.ExpectationKey(KeyKind.PSP_CAPTURE_REF, key)),
                PLATFORM,
                CLOCK.instant(),
                CorrelationId.of("p8-tsk-004-register-test"));
    }

    private static ExpectationRegister.NewAlias alias(String arn, String anchor) {
        return new ExpectationRegister.NewAlias(
                PSP_SOURCE,
                KeyKind.ACQUIRER_REF,
                arn,
                KeyKind.CARD_ATTEMPT,
                anchor,
                PLATFORM,
                CLOCK.instant(),
                CorrelationId.of("p8-tsk-004-register-test"));
    }

    private static UUID expectationIdOf(String operation) throws SQLException {
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT id FROM reconciliation.expectation WHERE operation_ref = ?")) {
            read.setString(1, operation);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject("id", UUID.class);
            }
        }
    }

    private static UUID keyOwner(String value) throws SQLException {
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT expectation_id FROM reconciliation.expectation_key WHERE"
                                + " source_id = ? AND key_kind = 'PSP_CAPTURE_REF' AND"
                                + " key_value = ?")) {
            read.setObject(1, PSP_SOURCE);
            read.setString(2, value);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? row.getObject("expectation_id", UUID.class) : null;
            }
        }
    }

    private static String text(String sql, Object argument) throws SQLException {
        try (PreparedStatement read = application.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (PreparedStatement read = application.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static void execute(String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    /**
     * Executes expecting a refusal and returns its message — rolling back either way, so a
     * failed expectation aborts one assertion and never the shared connection.
     */
    private static String refusal(String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
            org.assertj.core.api.Assertions.fail("expected a refusal: %s", sql);
            return null;
        } catch (SQLException refused) {
            return refused.getMessage();
        } finally {
            application.rollback();
        }
    }
}
