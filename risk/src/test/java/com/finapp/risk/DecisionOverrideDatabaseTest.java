package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The override machine against a real database (`P13-TSK-012`; {@code risk V009}; {@code PHASE_13_PLAN.md} sections
 * 7.4, 14, scenarios 10 and 15; {@code INV-RSK-04}, {@code INV-RSK-07}, {@code INV-AUD-04}, {@code INV-IDEM-01}): ten
 * approvers of one override - one approval, one release; the proposer refused by the domain, and the four-eyes
 * {@code CHECK} refusing it alone; below the threshold one holder with a reason; an {@code ALLOW} never overridden by any
 * writer; the assessment never edited; a crash between the approval's commit and the release re-driven by ten legs to
 * one dispatch and one outcome; every other edge refused by the machine. Payments' doors are a counting fake keyed as
 * payments keys them ({@link OverrideWorld.Payments}) - the real doors are {@code WithdrawalHoldDatabaseTest}'s.
 * <strong>Written and owed</strong> (owner decision C5).
 *
 * <p>No policy version is activated: each case PROPOSES the version it judges under (a row the pin's foreign key and
 * the birth trigger's C7 read) and seals its assessments directly through the store, so the suite judges nothing of
 * any other suite's movements; every case keys on fresh ids.
 */
@Tag("database")
@DisplayName("the decision override against the database: one approval, one release, four eyes by CHECK (P13-TSK-012)")
class DecisionOverrideDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final long THRESHOLD = RiskPolicyFixtures.OVERRIDE_THRESHOLD_MINOR;
    private static final JdbcRiskPolicyStore POLICY_STORE = new JdbcRiskPolicyStore();
    private static final RiskPolicyAdministration ADMINISTRATION = new RiskPolicyAdministration(POLICY_STORE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
    private static final JdbcRiskAssessmentStore ASSESSMENTS = new JdbcRiskAssessmentStore();
    private static final JdbcDecisionOverrideStore OVERRIDES = new JdbcDecisionOverrideStore();
    private static final Map<String, UUID> PARTY_OF = new java.util.concurrent.ConcurrentHashMap<>();
    private static final DecisionOverrides MACHINE = new DecisionOverrides(OVERRIDES, ASSESSMENTS,
            new RiskPolicies(POLICY_STORE), (uow, actor) -> Optional.ofNullable(PARTY_OF.get(actor.id())),
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);

    private final OverrideWorld.Payments payments = new OverrideWorld.Payments();
    private final OverrideActions actions = new OverrideActions(OVERRIDES, payments, payments, new JdbcAuditWriter(),
            new JdbcOutboxWriter(), IDS, CLOCK);

    @Test
    @DisplayName("ten approvers of one override, each in its own transaction: one PROPOSED -> APPROVED edge, nine"
            + " OverrideStale; the winner's dispatch after its commit - one release, one DONE, one risk.ActionConcluded")
    void tenApproversOneRelease() throws Exception {
        RiskAssessment held = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, THRESHOLD);
        DecisionOverride proposed = inOneTransaction(uow -> MACHINE.propose(uow, held.id(), OverrideResolution.PROCEED,
                "FALSE_POSITIVE", "verified with the customer", officer(), correlation()));
        List<Object> answers = race(10, () -> {
            Actor approver = officer();
            try {
                DecisionOverrides.Decided decided = inOneTransaction(uow -> MACHINE.approve(uow, held.id(),
                        proposed.id(), "four eyes", approver, correlation(), Duration.ofMinutes(2)));
                // Committed: now, and only now, the action - no transaction open.
                OverrideActions.Issued issued = inOneTransaction(uow -> actions.pending(uow, proposed.id()))
                        .orElseThrow();
                ActionOutcome outcome = actions.dispatch(issued);
                inOneTransaction(uow -> actions.conclude(uow, issued, outcome, approver, correlation()));
                return decided;
            } catch (DecisionOverrides.OverrideStale stale) {
                return stale;
            }
        });
        assertThat(answers).filteredOn(DecisionOverrides.Decided.class::isInstance).hasSize(1);
        assertThat(answers).filteredOn(DecisionOverrides.OverrideStale.class::isInstance).hasSize(9);
        assertThat(column("SELECT status || ',' || action || ',' || action_outcome || ',' || (action_due_at IS NULL)"
                + " FROM risk.decision_override WHERE id = '" + proposed.id().value() + "'"))
                .containsExactly("APPROVED,RELEASE_HELD_WITHDRAWAL,DONE,true");
        assertThat(payments.calls).hasSize(1);
        assertThat(payments.effects()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.OverrideApproved'"
                + " AND target_id = '" + proposed.id().value() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ActionConcluded'"
                + " AND target_id = '" + proposed.id().value() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.RiskActionRequested'"
                + " AND aggregate_id = '" + proposed.id().value() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("scenario 15's override half: the proposer's approval at the threshold is refused by the domain and"
            + " recorded FAILED - and with the domain bypassed, the four-eyes CHECK refuses it alone (23514,"
            + " decision_override_four_eyes); a birth claiming no second person for a DENY is refused by the trigger")
    void theProposerCannotApprove() throws Exception {
        RiskAssessment held = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, THRESHOLD);
        Actor proposer = officer();
        DecisionOverride proposed = inOneTransaction(uow -> MACHINE.propose(uow, held.id(), OverrideResolution.PROCEED,
                "FALSE_POSITIVE", "verified", proposer, correlation()));
        assertThat(proposed.requiresSecondPerson()).isTrue();
        assertThatThrownBy(() -> inOneTransaction(uow -> MACHINE.approve(uow, held.id(), proposed.id(), "mine",
                proposer, correlation(), Duration.ofMinutes(2))))
                .isInstanceOf(DecisionOverrides.SelfApprovalRefused.class);

        // The domain bypassed: the statement the store issues, with the proposer as approver.
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            SQLException refused = sqlFailure(() -> {
                try (PreparedStatement update = app.prepareStatement(JdbcDecisionOverrideStore.APPROVE)) {
                    update.setString(1, proposer.id());
                    update.setString(2, proposer.type().name());
                    update.setString(3, "the CHECK alone");
                    update.setLong(4, 120_000L);
                    update.setObject(5, proposed.id().value());
                    update.executeQuery().close();
                }
            });
            assertThat(refused.getSQLState()).isEqualTo("23514");
            assertThat(refused.getMessage()).contains("decision_override_four_eyes");
            app.rollback();
        }
        assertThat(column("SELECT status FROM risk.decision_override WHERE id = '" + proposed.id().value() + "'"))
                .containsExactly("PROPOSED");

        // C7 at the birth: a DENY claiming no second person, inserted around the domain.
        RiskAssessment denied = sealed(RiskOperation.TRANSFER, RiskDecision.DENY, 10_00);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "INSERT INTO risk.decision_override (id, assessment_id, status, effect, reason_code, reason,"
                    + " requires_second_person, proposed_by, proposed_by_type, proposed_at, correlation_id) VALUES ('"
                    + IDS.next() + "', '" + denied.id().value() + "', 'PROPOSED', 'RESUBMISSION', 'FALSE_POSITIVE',"
                    + " 'x', false, 'someone', 'EMPLOYEE', clock_timestamp(), 'flow')", "23514");
            app.rollback();
        }
    }

    @Test
    @DisplayName("below the threshold one holder with a reason: the proposer's own approval commits, decided_by ="
            + " proposed_by, the CHECK satisfied because no second person was required")
    void belowTheThresholdOneHolderWithAReason() throws Exception {
        RiskAssessment held = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, THRESHOLD - 1);
        Actor proposer = officer();
        DecisionOverride proposed = inOneTransaction(uow -> MACHINE.propose(uow, held.id(), OverrideResolution.PROCEED,
                "CUSTOMER_VERIFIED", "verified", proposer, correlation()));
        assertThat(proposed.requiresSecondPerson()).isFalse();
        DecisionOverrides.Decided decided = inOneTransaction(uow -> MACHINE.approve(uow, held.id(), proposed.id(),
                "one holder, reasoned", proposer, correlation(), Duration.ofMinutes(2)));
        assertThat(decided.override().decidedByProposer()).isTrue();
        assertThat(column("SELECT (decided_by = proposed_by)::text || ',' || decision_reason FROM risk.decision_override"
                + " WHERE id = '" + proposed.id().value() + "'")).containsExactly("true,one holder, reasoned");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.OverrideApproved'"
                + " AND target_id = '" + proposed.id().value() + "' AND change_summary LIKE '%byProposer=true%'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an ALLOW is never overridden: the domain refuses it ActionNotProposable, and a writer around the"
            + " domain is refused by the birth trigger (23514)")
    void anAllowIsNeverOverridden() throws Exception {
        RiskAssessment allowed = sealed(RiskOperation.TRANSFER, RiskDecision.ALLOW, 10_00);
        assertThatThrownBy(() -> inOneTransaction(uow -> MACHINE.propose(uow, allowed.id(), OverrideResolution.PROCEED,
                "FALSE_POSITIVE", "x", officer(), correlation())))
                .isInstanceOf(DecisionOverrides.ActionNotProposable.class);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "INSERT INTO risk.decision_override (id, assessment_id, status, effect, reason_code, reason,"
                    + " requires_second_person, proposed_by, proposed_by_type, proposed_at, correlation_id) VALUES ('"
                    + IDS.next() + "', '" + allowed.id().value() + "', 'PROPOSED', 'RESUBMISSION', 'FALSE_POSITIVE',"
                    + " 'x', true, 'someone', 'EMPLOYEE', clock_timestamp(), 'flow')", "23514");
            app.rollback();
        }
        assertThat(count("SELECT count(*) FROM risk.decision_override WHERE assessment_id = '"
                + allowed.id().value() + "'")).isZero();
    }

    @Test
    @DisplayName("INV-RSK-04: an approved and concluded override leaves the assessment row exactly as sealed - the"
            + " decision, the digests, the seal holding - and no role may write it")
    void anOverrideNeverEditsTheAssessment() throws Exception {
        RiskAssessment held = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 2_000_00);
        String before = String.join("|", column("SELECT decision || ',' || verdict_sha256 || ',' || inputs_sha256"
                + " || ',' || assessed_at FROM risk.risk_assessment WHERE id = '" + held.id().value() + "'"));
        DecisionOverride proposed = inOneTransaction(uow -> MACHINE.propose(uow, held.id(), OverrideResolution.PROCEED,
                "FALSE_POSITIVE", "verified", officer(), correlation()));
        Actor approver = officer();
        inOneTransaction(uow -> MACHINE.approve(uow, held.id(), proposed.id(), "approved", approver, correlation(),
                Duration.ofMinutes(2)));
        OverrideActions.Issued issued = inOneTransaction(uow -> actions.pending(uow, proposed.id())).orElseThrow();
        ActionOutcome outcome = actions.dispatch(issued);
        inOneTransaction(uow -> actions.conclude(uow, issued, outcome, approver, correlation()));

        assertThat(String.join("|", column("SELECT decision || ',' || verdict_sha256 || ',' || inputs_sha256 || ','"
                + " || assessed_at FROM risk.risk_assessment WHERE id = '" + held.id().value() + "'")))
                .isEqualTo(before);
        assertThat(inOneTransaction(uow -> ASSESSMENTS.read(uow, held.id())).orElseThrow().sealHolds()).isTrue();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "UPDATE risk.risk_assessment SET decision = 'ALLOW' WHERE id = '" + held.id().value() + "'",
                    "42501");
            app.rollback();
        }
    }

    @Test
    @DisplayName("scenario 10's crash: the approval commits and the instance dies before the release; past the lease"
            + " ten re-drive legs race - one claim, one release under risk.action:<id>, one DONE, one"
            + " risk.ActionConcluded")
    void aCrashBetweenApprovalAndReleaseReDrivesOnce() throws Exception {
        RiskAssessment held = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 100_00);
        DecisionOverride proposed = inOneTransaction(uow -> MACHINE.propose(uow, held.id(), OverrideResolution.PROCEED,
                "FALSE_POSITIVE", "verified", officer(), correlation()));
        inOneTransaction(uow -> MACHINE.approve(uow, held.id(), proposed.id(), "approved", officer(), correlation(),
                Duration.ofMillis(300)));
        // ...the approving instance died here: no dispatch, no record.
        Thread.sleep(600);
        Actor platform = new Actor("risk-monitoring", ActorType.SYSTEM);
        List<Optional<ActionOutcome>> legs = race(10, () -> {
            Optional<OverrideActions.Issued> claimed = inOneTransaction(uow -> actions.claimNextDue(uow,
                    Duration.ofMinutes(2)));
            if (claimed.isEmpty() || !claimed.get().override().equals(proposed.id())) {
                return Optional.empty();
            }
            ActionOutcome outcome = actions.dispatch(claimed.get());
            inOneTransaction(uow -> actions.conclude(uow, claimed.get(), outcome, platform, correlation()));
            return Optional.of(outcome);
        });
        assertThat(legs).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(payments.calls).singleElement().asString().contains("risk.action:" + proposed.id().value());
        assertThat(column("SELECT action_outcome || ',' || action_attempts || ',' || (action_due_at IS NULL)"
                + " FROM risk.decision_override WHERE id = '" + proposed.id().value() + "'"))
                .containsExactly("DONE,2,true");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ActionConcluded'"
                + " AND target_id = '" + proposed.id().value() + "' AND actor_type = 'SYSTEM'")).isEqualTo(1);
    }

    @Test
    @DisplayName("ten proposers of one assessment: one standing override, nine ActionNotProposable")
    void tenProposersOneStanding() throws Exception {
        RiskAssessment held = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 100_00);
        List<Object> answers = race(10, () -> {
            try {
                return inOneTransaction(uow -> MACHINE.propose(uow, held.id(), OverrideResolution.PROCEED,
                        "FALSE_POSITIVE", "verified", officer(), correlation()));
            } catch (DecisionOverrides.ActionNotProposable refused) {
                return refused;
            }
        });
        assertThat(answers).filteredOn(DecisionOverride.class::isInstance).hasSize(1);
        assertThat(count("SELECT count(*) FROM risk.decision_override WHERE assessment_id = '" + held.id().value()
                + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the machine refuses every other edge for every writer: the proposal edited, a rejection moved, an"
            + " outcome recorded twice, an approval issuing another effect; no role deletes")
    void theMachineRefusesEveryOtherEdge() throws Exception {
        RiskAssessment held = sealed(RiskOperation.WITHDRAWAL, RiskDecision.HOLD, 100_00);
        DecisionOverride first = inOneTransaction(uow -> MACHINE.propose(uow, held.id(), OverrideResolution.PROCEED,
                "FALSE_POSITIVE", "verified", officer(), correlation()));
        String id = first.id().value().toString();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "UPDATE risk.decision_override SET status = 'APPROVED', decided_by = 'x', decided_by_type ="
                    + " 'EMPLOYEE', decision_reason = 'r', action = 'DENY_HELD_WITHDRAWAL', action_due_at ="
                    + " clock_timestamp(), action_attempts = 1 WHERE id = '" + id + "'", "23514");
            app.rollback();
            refused(app, "DELETE FROM risk.decision_override WHERE id = '" + id + "'", "42501");
            app.rollback();
            refused(app, "UPDATE risk.decision_override SET reason = 'edited' WHERE id = '" + id + "'", "42501");
            app.rollback();
        }
        inOneTransaction(uow -> MACHINE.reject(uow, held.id(), first.id(), "no", officer(), correlation()));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "UPDATE risk.decision_override SET status = 'APPROVED', decided_by = 'x' WHERE id = '" + id
                    + "'", "23514");
            app.rollback();
        }
        DecisionOverride second = inOneTransaction(uow -> MACHINE.propose(uow, held.id(), OverrideResolution.PROCEED,
                "FALSE_POSITIVE", "again", officer(), correlation()));
        inOneTransaction(uow -> MACHINE.approve(uow, held.id(), second.id(), "approved", officer(), correlation(),
                Duration.ofMinutes(2)));
        boolean recorded = inOneTransaction(uow -> OVERRIDES.conclude(uow, second.id(), ActionOutcome.DONE));
        boolean twice = inOneTransaction(uow -> OVERRIDES.conclude(uow, second.id(), ActionOutcome.ALREADY));
        assertThat(recorded).isTrue();
        assertThat(twice).as("an outcome is recorded once").isFalse();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "UPDATE risk.decision_override SET action_outcome = 'REFUSED' WHERE id = '"
                    + second.id().value() + "'", "23514");
            app.rollback();
        }
    }

    // -----------------------------------------------------------------

    /**
     * A {@code decision} of {@code operation} for {@code minor} EUR cents of a fresh party, sealed through the store
     * under a version PROPOSED for it (never activated) whose one score band decides it, threshold EUR 1,000.
     */
    private static RiskAssessment sealed(RiskOperation operation, RiskDecision decision, long minor) {
        RiskPolicy policy = OverrideWorld.policy(operation, decision, THRESHOLD);
        RiskPolicyAdministration.Proposed version = inOneTransaction(uow -> ADMINISTRATION.propose(uow, policy,
                "the override suite's version", officer(), correlation()));
        Money amount = Money.ofMinorUnits(minor, CurrencyCode.of("EUR"));
        RiskEvaluationRequest request = RiskEvaluationRequest.builder(operation, CommandRef.of(IDS.next()), amount,
                UUID.randomUUID(), correlation(), CausationId.of("override-suite")).sourceAccount(IDS.next()).build();
        EvaluationInputs inputs = request.judged().build();
        Verdict verdict = RiskEngineV1.evaluate(policy, inputs);
        RiskDecision decided = verdict.decision().orElseThrow();
        assertThat(decided).isEqualTo(decision);
        String inputsText = CanonicalEvaluation.render(inputs);
        RiskAssessment assessment = new RiskAssessment(RiskAssessmentId.next(IDS), operation, request.commandRef(),
                RiskAssessment.Subjects.of(request), amount,
                Optional.of(new RiskAssessment.Pin(version.id(), version.version())), verdict.engine(), verdict.score(),
                decided, Optional.empty(), decided == RiskDecision.CHALLENGE ? Optional.of(false) : Optional.empty(),
                decided == RiskDecision.ALLOW ? VelocityOutcome.UNCOUNTED : VelocityOutcome.NOT_PERMITTED,
                Optional.empty(), inputsText, CanonicalEvaluation.sha256(inputsText),
                CanonicalEvaluation.sha256(CanonicalEvaluation.render(verdict)), verdict.hits(), verdict.reasons(), 100,
                request.correlation().value(), "override-suite", Optional.empty());
        boolean inserted = inOneTransaction(uow -> ASSESSMENTS.insert(uow, assessment));
        assertThat(inserted).isTrue();
        return inOneTransaction(uow -> ASSESSMENTS.read(uow, assessment.id())).orElseThrow();
    }

    private static Actor officer() {
        Actor actor = new Actor("officer-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        PARTY_OF.put(actor.id(), UUID.randomUUID());
        return actor;
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private interface SqlWork {
        void run() throws SQLException;
    }

    private static SQLException sqlFailure(SqlWork work) {
        try {
            work.run();
        } catch (SQLException failure) {
            return failure;
        }
        throw new AssertionError("the statement was expected to be refused");
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static List<String> column(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            while (row.next()) {
                values.add(row.getString(1));
            }
        }
        return values;
    }

    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }

    private static <T> List<T> race(int racers, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
