package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The risk reads and the retirement of `P15-TSK-037` with a stamp written the wrong way round, on PostgreSQL (`THREAT_MODEL.md` T13). A stepped clock is planted by
 * moving a stamp after the fact in the same transaction, the bootstrap connection with triggers off for that statement only ({@code SET LOCAL
 * session_replication_role}), so the clock functions stay out of it and {@code P15-TSK-040}'s qualification of them cannot make this fixture lie. Every case runs
 * in one transaction rolled back at its end, so nothing it plants is seen by another suite. Each asserts its precondition.
 *
 * <p><strong>Written and owed</strong> (C14): {@code P15-TST-002}.
 */
@Tag("database")
@DisplayName("risk's reads and the retirement rank by the rows, whatever a stepped clock wrote (P15-TSK-037)")
class RiskStampOrderDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcRiskPolicyStore POLICY_STORE = new JdbcRiskPolicyStore();
    private static final RiskPolicyAdministration ADMINISTRATION = new RiskPolicyAdministration(POLICY_STORE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
    private static final JdbcVelocityWindowStore WINDOW_STORE = new JdbcVelocityWindowStore();
    private static final JdbcRiskAssessmentStore ASSESSMENT_STORE = new JdbcRiskAssessmentStore();
    private static final JdbcRiskCaseStore CASE_STORE = new JdbcRiskCaseStore();
    private static final JdbcRiskSignalStore SIGNAL_STORE = new JdbcRiskSignalStore();
    private static final RiskEvaluation EVALUATION = new RiskEvaluation(new RiskPolicies(POLICY_STORE),
            new VelocityWindows(WINDOW_STORE), new CustomerRiskProfiles(new JdbcCustomerRiskProfileStore(),
                    (uow, actor) -> Optional.empty(), new JdbcAuditWriter(), IDS, CLOCK),
            new SignalReadings(SIGNAL_STORE), RiskInputs.NONE, ASSESSMENT_STORE,
            new JdbcEvaluationBudget(), CaseOpener.NONE, AlertRaiser.NONE, new JdbcOutboxWriter(), RiskEvaluationObserver.NONE,
            Spans.NONE, IDS, CLOCK);

    @Test
    @DisplayName("a retirement ends after its latest assessment though the clock stepped back since it, and the successor starts exactly there")
    void aRetirementFollowsItsLatestAssessment() throws SQLException {
        rolledBack(uow -> {
            RiskPolicyVersionId first = activate(uow, RiskOperation.TRANSFER);
            RiskEvaluation.Assessed assessed = EVALUATION.evaluate(uow, transfer(IDS.next()));
            String assessment = assessed.assessmentId().value().toString();
            // The clock steps back after the assessment: its stamp stands ahead of the clock.
            plant(uow, "UPDATE risk.risk_assessment SET assessed_at = clock_timestamp() + interval '1 hour' WHERE id = '" + assessment + "'");
            assertThat(value(uow, "SELECT assessed_at > clock_timestamp() FROM risk.risk_assessment WHERE id = '" + assessment + "'"))
                    .as("precondition: the assessment's stamp stands ahead of the clock").isEqualTo("t");

            RiskPolicyVersionId second = activate(uow, RiskOperation.TRANSFER);

            assertThat(value(uow, "SELECT v.effective_to > a.assessed_at FROM risk.risk_policy_version v, risk.risk_assessment a WHERE v.id = '" + first.value()
                    + "' AND a.id = '" + assessment + "'")).as("the version ends after its latest assessment").isEqualTo("t");
            assertThat(value(uow, "SELECT p.effective_to = s.effective_from FROM risk.risk_policy_version p, risk.risk_policy_version s WHERE p.id = '" + first.value()
                    + "' AND s.id = '" + second.value() + "'")).as("the successor starts exactly where its predecessor ends").isEqualTo("t");
        });
    }

    @Test
    @DisplayName("an assessment made under a version whose start stands ahead of the clock is never dated before it")
    void anAssessmentIsNeverBeforeItsVersion() throws SQLException {
        rolledBack(uow -> {
            // The version starts ahead of the clock, as a stepped retirement leaves its successor.
            RiskPolicyVersionId version = activate(uow, RiskOperation.TRANSFER);
            plant(uow, "UPDATE risk.risk_policy_version SET effective_from = clock_timestamp() + interval '1 hour' WHERE id = '" + version.value() + "'");
            assertThat(value(uow, "SELECT effective_from > clock_timestamp() FROM risk.risk_policy_version WHERE id = '" + version.value() + "'"))
                    .as("precondition: the version's start stands ahead of the clock").isEqualTo("t");

            RiskEvaluation.Assessed assessed = EVALUATION.evaluate(uow, transfer(IDS.next()));

            assertThat(value(uow, "SELECT a.assessed_at >= v.effective_from FROM risk.risk_assessment a JOIN risk.risk_policy_version v ON v.id = a.policy_version_id"
                    + " WHERE a.id = '" + assessed.assessmentId().value() + "'")).as("the floor holds whatever the assessment's clock read").isEqualTo("t");
        });
    }

    @Test
    @DisplayName("a case's predecessor is the chain's tail whatever disposed_at says: the older case's later stamp does not make it the latest")
    void aCasesPredecessorIsTheTail() throws SQLException {
        rolledBack(uow -> {
            SignalSubject subject = SignalSubject.of(SubjectKind.PARTY, IDS.next());
            UUID older = IDS.next();
            UUID newer = IDS.next();
            plant(uow, disposedCase(older, subject, null) + "; " + disposedCase(newer, subject, older)
                    + "; UPDATE risk.risk_case SET disposed_at = clock_timestamp() + interval '1 hour' WHERE id = '" + older + "'"
                    + "; UPDATE risk.risk_case SET disposed_at = clock_timestamp() WHERE id = '" + newer + "'");
            assertThat(value(uow, "SELECT older.disposed_at > newer.disposed_at FROM risk.risk_case older, risk.risk_case newer WHERE older.id = '" + older
                    + "' AND newer.id = '" + newer + "'")).as("precondition: the older case carries the later stamp").isEqualTo("t");

            assertThat(CASE_STORE.latestDisposed(uow, subject, CaseKind.FRAUD)).contains(RiskCaseId.of(newer));
        });
    }

    @Test
    @DisplayName("the latest of two signals observed at one instant is the higher id, not the one the database stamped last")
    void anEqualInstantIsBrokenByTheId() throws SQLException {
        rolledBack(uow -> {
            SignalSubject subject = SignalSubject.of(SubjectKind.IDENTITY, IDS.next());
            UUID lower = UUID.fromString("00000000-0000-7000-8000-000000000001");
            UUID higher = UUID.fromString("ffffffff-ffff-7fff-8fff-ffffffffffff");
            plant(uow, signal(higher, subject) + "; " + signal(lower, subject)
                    + "; UPDATE risk.risk_signal SET recorded_at = clock_timestamp() - interval '1 hour' WHERE id = '" + higher + "'");
            assertThat(value(uow, "SELECT l.recorded_at > h.recorded_at FROM risk.risk_signal l, risk.risk_signal h WHERE l.id = '" + lower + "' AND h.id = '" + higher + "'"))
                    .as("precondition: the lower id was recorded later").isEqualTo("t");

            assertThat(SIGNAL_STORE.latest(uow, subject, SignalKind.CREDENTIAL_CHANGED)).map(signal -> signal.id().value()).contains(higher);
        });
    }

    private static String disposedCase(UUID id, SignalSubject subject, UUID predecessor) {
        return "INSERT INTO risk.risk_case (id, kind, subject_kind, subject_ref, status, revision, level, queue, sla_hours, sla_deadline, predecessor_case_id,"
                + " opened_at, disposition, disposition_id, disposition_prohibits, disposition_proposed_by, disposition_proposed_by_type, disposition_proposed_at,"
                + " disposition_approved_by, disposition_approved_by_type, disposed_at, correlation_id) VALUES ('" + id + "', 'FRAUD', 'PARTY', '" + subject.ref()
                + "', 'DISPOSED', 1, 'L1', 'FIRST_LINE', 1, clock_timestamp() + interval '1 hour', "
                + (predecessor == null ? "NULL" : "'" + predecessor + "'")
                + ", clock_timestamp(), 'CLOSED_NO_ACTION', '" + UUID.randomUUID() + "', false, 'a', 'EMPLOYEE', clock_timestamp(), 'b', 'EMPLOYEE', clock_timestamp(), 'c')";
    }

    private static String signal(UUID id, SignalSubject subject) {
        return "INSERT INTO risk.risk_signal (id, kind, subject_kind, subject_ref, source_event_id, source_event_type, source_event_version, producer,"
                + " observed_at, recorded_at, correlation_id, causation_id) VALUES ('" + id + "', 'CREDENTIAL_CHANGED', 'IDENTITY', '" + subject.ref() + "', '"
                + UUID.randomUUID() + "', 'x', 1, 'x', TIMESTAMPTZ '2030-01-01 00:00:00+00', clock_timestamp(), 'c', 'c')";
    }

    private static RiskPolicyVersionId activate(Connection uow, RiskOperation operation) {
        RiskPolicyAdministration.Proposed proposed = ADMINISTRATION.propose(uow, RiskPolicyFixtures.v1(operation), "v1 for " + operation, employee(), correlation());
        ADMINISTRATION.activate(uow, proposed.id(), employee(), "activated", correlation());
        return proposed.id();
    }

    private static RiskEvaluationRequest transfer(UUID party) {
        return RiskEvaluationRequest.builder(RiskOperation.TRANSFER, CommandRef.of(IDS.next()), Money.ofMinorUnits(1_00, CurrencyCode.of("EUR")), party, correlation(),
                CausationId.of("cause")).sourceAccount(IDS.next()).destinationAccount(IDS.next()).actor(new Actor("customer-" + party, ActorType.CUSTOMER)).build();
    }

    /** A planted stamp: triggers off for the statements, the transaction rolled back by its caller. */
    private static void plant(Connection uow, String sql) throws SQLException {
        try (Statement statement = uow.createStatement()) {
            statement.execute("SET LOCAL session_replication_role = replica; " + sql + "; SET LOCAL session_replication_role = origin");
        }
    }

    private static String value(Connection uow, String sql) throws SQLException {
        try (Statement statement = uow.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }

    private interface Work {
        void run(Connection uow) throws SQLException;
    }

    /** The case on one bootstrap connection, rolled back: planted stamps and a version started ahead of the clock are seen by no other suite. */
    private static void rolledBack(Work work) throws SQLException {
        try (Connection uow = DatabaseRoles.bootstrap()) {
            uow.setAutoCommit(false);
            try {
                work.run(uow);
            } finally {
                uow.rollback();
            }
        }
    }

    private static Actor employee() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }
}
