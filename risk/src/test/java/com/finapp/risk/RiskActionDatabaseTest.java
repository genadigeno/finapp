package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The action machine against a real database (`P13-TSK-017`; {@code risk V013}; {@code PHASE_13_PLAN.md} sections 7.4,
 * 13.2 scenarios 4, 7, 15; {@code INV-RSK-02}, {@code INV-RSK-06}, {@code INV-RSK-07}, {@code INV-IDEM-01}): ten
 * approvers of one action - one approval, one dispatch; ten sweepers over one approved action - one claim; a lost
 * response re-driven past the lease presenting the same key - one effect by the owner's key; an owner's refusal recorded
 * {@code FAILED} and never claimed again; the proposer refused by the domain, and {@code risk_action_four_eyes} refusing
 * it alone; no risk row held while the owner works (a lock-wait probe from the owner's door); a disposition waiting for a
 * standing proposal; the machine refusing every other edge for every writer; and the fold's own statement keeping a
 * pending override action's key. The owners are a synchronised fake keyed as the owners key (the real doors are
 * {@code app}'s {@code RiskActionPortsDatabaseTest}). <strong>Written and owed</strong> (owner decision C5).
 *
 * <p>No policy version is activated: the held movements' assessments are sealed directly through the store under a
 * version PROPOSED for them, so the suite judges nothing of any other suite's movements; every case keys on fresh ids.
 */
@Tag("database")
@DisplayName("the action machine against the database: one approval, one claim, one effect per key, four eyes by CHECK"
        + " (P13-TSK-017)")
class RiskActionDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcRiskPolicyStore POLICY_STORE = new JdbcRiskPolicyStore();
    private static final RiskPolicyAdministration ADMINISTRATION = new RiskPolicyAdministration(POLICY_STORE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
    private static final JdbcRiskAssessmentStore ASSESSMENTS = new JdbcRiskAssessmentStore();
    private static final JdbcRiskCaseStore CASE_STORE = new JdbcRiskCaseStore();
    private static final JdbcRiskActionStore ACTION_STORE = new JdbcRiskActionStore();
    private static final Map<String, UUID> PARTY_OF = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Cases CASES = new Cases(CASE_STORE, new JdbcRiskAlertStore(),
            (uow, actor) -> Optional.ofNullable(PARTY_OF.get(actor.id())), RiskSubjectParties.NONE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, CaseObserver.NONE);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final String CHECK_VIOLATION = "23514";

    private final Owners owners = new Owners();
    private final RiskActions actions = new RiskActions(ACTION_STORE, CASES, CASE_STORE, ASSESSMENTS,
            new ActionDispatch(owners, owners, owners, owners), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS,
            CLOCK);

    @Test
    @DisplayName("ten approvers of one proposed action, each in its own transaction: one PROPOSED -> APPROVED edge, the"
            + " other nine refused (a stale revision or the action no longer proposed); one dispatch, one DONE, one"
            + " risk.RiskActionRequested")
    void tenApproversOneDispatch() throws Exception {
        Held held = heldCase();
        RiskAction proposed = propose(held.taken(), ActionKind.RELEASE_HELD_WITHDRAWAL, Optional.of(held.movement()));
        int revision = revisionOf(held.taken().riskCase().id());
        List<Object> answers = race(10, () -> {
            Actor approver = analyst();
            try {
                return inOneTransaction(uow -> actions.approve(uow,
                        held.taken().riskCase().id(), proposed.id(), revision, "four eyes", approver, correlation()));
            } catch (Cases.CaseRevisionStale | Cases.CaseActNotAdmitted refused) {
                return refused;
            }
        });
        assertThat(answers).filteredOn(RiskActions.Decided.class::isInstance).hasSize(1);
        assertThat(answers).filteredOn(answer -> !(answer instanceof RiskActions.Decided)).hasSize(9);
        // The dispatch is the schedule's, after the approvals (P13-DOC-001's database pass): driven from inside the race,
        // the winner's SKIP LOCKED claim could meet a loser still holding the action row FOR UPDATE (lockedOf, before its
        // refusal rolls back) and claim nothing - the next tick's to take, never a defect, but not this test's subject.
        assertThat(drive(proposed.id())).as("the approved action's one dispatch").isPresent();
        assertThat(column("SELECT status || ',' || outcome || ',' || attempts FROM risk.risk_action WHERE id = '"
                + proposed.id().value() + "'")).containsExactly("DONE,DONE,1");
        assertThat(owners.calls()).hasSize(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.RiskActionRequested'"
                + " AND aggregate_id = '" + proposed.id().value() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_case_action WHERE case_id = '"
                + held.taken().riskCase().id().value() + "' AND kind = 'ACTION_APPROVED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("ten sweepers over one approved action: one claim (nine skip the locked row or find the permit moved),"
            + " one dispatch under risk.action:<id>, one record")
    void tenSweepersDispatchOnce() throws Exception {
        Held held = heldCase();
        RiskAction approved = approved(held, ActionKind.RELEASE_HELD_WITHDRAWAL);
        List<Optional<ActionOutcome>> sweeps = race(10, () -> drive(approved.id()));
        assertThat(sweeps).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(owners.calls()).singleElement().asString().contains("risk.action:" + approved.id().value());
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ActionConcluded'"
                + " AND target_id = '" + approved.id().value() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ActionDispatched'"
                + " AND target_id = '" + approved.id().value() + "' AND actor_type = 'SYSTEM'")).isEqualTo(1);
    }

    @Test
    @DisplayName("scenario 4, INV-IDEM-01: the owner's command commits and the response is lost - past the lease the"
            + " re-claim (attempt 2) presents the SAME key and the owner replays DONE: two calls, one effect, one record")
    void aReDriveReplays() throws Exception {
        Held held = heldCase();
        RiskAction approved = approved(held, ActionKind.DENY_HELD_WITHDRAWAL);
        RiskAction first = asSystem(() -> inOneTransaction(uow -> actions.claim(uow, approved.id(),
                Duration.ofMillis(300)))).orElseThrow();
        assertThat(actions.dispatch(first)).isEqualTo(ActionOutcome.DONE);
        // ...and the instance died before its record.
        Thread.sleep(600);
        RiskAction second = asSystem(() -> inOneTransaction(uow -> actions.claim(uow, approved.id(), LEASE)))
                .orElseThrow();
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(second.key()).isEqualTo(first.key());
        assertThat(actions.dispatch(second)).isEqualTo(ActionOutcome.DONE);
        Optional<RiskAction> recorded = asSystem(() -> inOneTransaction(uow -> actions.conclude(uow, second,
                ActionOutcome.DONE, correlation())));
        assertThat(recorded).isPresent();
        Optional<RiskAction> late = asSystem(() -> inOneTransaction(uow -> actions.conclude(uow, first,
                ActionOutcome.DONE, correlation())));
        assertThat(late).as("the late record loses").isEmpty();
        assertThat(owners.calls()).hasSize(2).allSatisfy(call -> assertThat(call).contains(first.key()));
        assertThat(owners.effects()).isEqualTo(1);
        assertThat(column("SELECT status || ',' || attempts FROM risk.risk_action WHERE id = '"
                + approved.id().value() + "'")).containsExactly("DONE,2");
    }

    @Test
    @DisplayName("scenario 7: an owner's refusal is a recorded outcome - FAILED(REFUSED) - and is never claimed again"
            + " however late; a new proposal of the same action is admitted (re-proposable)")
    void aDomainRefusalIsRecordedNotRetried() throws Exception {
        Held held = heldCase();
        owners.refuse(held.movement());
        RiskAction approved = approved(held, ActionKind.RELEASE_HELD_WITHDRAWAL);
        assertThat(drive(approved.id())).contains(ActionOutcome.REFUSED);
        assertThat(column("SELECT status || ',' || outcome FROM risk.risk_action WHERE id = '" + approved.id().value()
                + "'")).containsExactly("FAILED,REFUSED");
        assertThat(drive(approved.id())).isEmpty();
        assertThat(owners.calls()).hasSize(1);
        RiskAction again = propose(held.taken(), ActionKind.RELEASE_HELD_WITHDRAWAL, Optional.of(held.movement()));
        assertThat(again.id()).isNotEqualTo(approved.id());
        assertThat(again.status()).isEqualTo(ActionStatus.PROPOSED);
    }

    @Test
    @DisplayName("scenario 15's action half: the proposer's approval is refused by the domain - and with the domain"
            + " bypassed, risk_action_four_eyes refuses it alone (23514)")
    void theProposerCannotApprove() throws Exception {
        Held held = heldCase();
        RiskAction proposed = propose(held.taken(), ActionKind.RELEASE_HELD_WITHDRAWAL, Optional.of(held.movement()));
        Actor proposer = held.taken().investigator();
        RiskCase current = inOneTransaction(uow -> CASES.current(uow, held.taken().riskCase().id())).orElseThrow();
        assertThatThrownBy(() -> inOneTransaction(uow -> actions.approve(uow, current.id(), proposed.id(),
                current.revision(), "mine", proposer, correlation())))
                .isInstanceOf(RiskActions.SelfApprovalRefused.class);

        // The domain bypassed: the proposer's own ACTION_APPROVED act written, then the store's own approval statement.
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID decision = IDS.next();
            try (PreparedStatement act = app.prepareStatement("INSERT INTO risk.risk_case_action (id, case_id,"
                    + " ordinal, kind, actor_id, actor_type, text, evidence_signal_ids, evidence_assessment_ids,"
                    + " evidence_journal_entry_ids, prohibits, recorded_at, correlation_id) VALUES (?, ?, ?,"
                    + " 'ACTION_APPROVED', ?, ?, 'the CHECK alone', '{}', '{}', '{}', false, clock_timestamp(), 'flow')")) {
                act.setObject(1, decision);
                act.setObject(2, current.id().value());
                act.setInt(3, current.revision() + 1);
                act.setString(4, proposer.id());
                act.setString(5, proposer.type().name());
                act.executeUpdate();
            }
            SQLException refused = sqlFailure(() -> {
                try (PreparedStatement update = app.prepareStatement(JdbcRiskActionStore.APPROVE)) {
                    update.setString(1, proposer.id());
                    update.setString(2, proposer.type().name());
                    update.setObject(3, decision);
                    update.setObject(4, proposed.id().value());
                    update.executeQuery().close();
                }
            });
            assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(refused.getMessage()).contains("risk_action_four_eyes");
            app.rollback();
        }
        assertThat(column("SELECT status FROM risk.risk_action WHERE id = '" + proposed.id().value() + "'"))
                .containsExactly("PROPOSED");
    }

    @Test
    @DisplayName("R6: no risk row is held while the owner works - from inside the owner's door, the action row and the"
            + " case row are each locked NOWAIT by another connection")
    void noRiskRowIsHeldDuringTheDomainsCommand() throws Exception {
        Held held = heldCase();
        RiskAction approved = approved(held, ActionKind.RELEASE_HELD_WITHDRAWAL);
        List<String> probes = Collections.synchronizedList(new ArrayList<>());
        owners.during = () -> {
            try (Connection other = DatabaseRoles.application()) {
                other.setAutoCommit(false);
                for (String sql : List.of(
                        "SELECT id FROM risk.risk_action WHERE id = '" + approved.id().value() + "' FOR UPDATE NOWAIT",
                        "SELECT id FROM risk.risk_case WHERE id = '" + held.taken().riskCase().id().value()
                                + "' FOR UPDATE NOWAIT")) {
                    try (Statement statement = other.createStatement(); ResultSet row = statement.executeQuery(sql)) {
                        probes.add(row.next() ? "free" : "missing");
                    } catch (SQLException locked) {
                        probes.add("held:" + locked.getSQLState());
                    }
                }
                other.rollback();
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        };
        assertThat(drive(approved.id())).contains(ActionOutcome.DONE);
        assertThat(probes).as("the claim committed before the owner was called").containsExactly("free", "free");
    }

    @Test
    @DisplayName("a disposition waits for a standing proposal: the domain refuses it, and a writer around the domain is"
            + " refused by risk_case_disposes_no_standing_action; once decided, the case is disposed")
    void aDispositionWaitsForAStandingAction() throws Exception {
        Taken taken = partyCase(CaseKind.ATO);
        RiskAction proposed = propose(taken, ActionKind.REVOKE_SESSIONS, Optional.empty());
        RiskCase standing = inOneTransaction(uow -> CASES.current(uow, taken.riskCase().id())).orElseThrow();
        assertThatThrownBy(() -> inOneTransaction(uow -> CASES.proposeDisposition(uow, standing.id(),
                standing.revision(), "CLOSED_NO_ACTION", false, "nothing", taken.investigator(), correlation())))
                .isInstanceOf(Cases.CaseActNotAdmitted.class);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            SQLException refused = sqlFailure(() -> {
                try (Statement statement = app.createStatement()) {
                    statement.execute("UPDATE risk.risk_case SET status = 'DISPOSED', revision = revision + 1"
                            + " WHERE id = '" + standing.id().value() + "'");
                }
            });
            assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(refused.getMessage()).contains("awaits its decision");
            app.rollback();
        }
        inOneTransaction(uow -> actions.reject(uow, standing.id(), proposed.id(), standing.revision(), "withdrawn",
                taken.investigator(), correlation()));
        RiskCase decided = inOneTransaction(uow -> CASES.current(uow, taken.riskCase().id())).orElseThrow();
        inOneTransaction(uow -> CASES.proposeDisposition(uow, decided.id(), decided.revision(), "CLOSED_NO_ACTION",
                false, "nothing", taken.investigator(), correlation()));
        assertThat(column("SELECT status FROM risk.risk_case WHERE id = '" + decided.id().value() + "'"))
                .containsExactly("DISPOSED");
    }

    @Test
    @DisplayName("the machine refuses every other edge for every writer: a proposal edited, a claim before the permit,"
            + " PROPOSED -> DISPATCHED, a concluded action moved, a case's action targeting another party; no role"
            + " deletes")
    void theMachineRefusesEveryOtherEdge() throws Exception {
        Held held = heldCase();
        RiskAction proposed = propose(held.taken(), ActionKind.RELEASE_HELD_WITHDRAWAL, Optional.of(held.movement()));
        String id = proposed.id().value().toString();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "UPDATE risk.risk_action SET status = 'DISPATCHED', attempts = 1, next_attempt_at ="
                    + " clock_timestamp() + interval '1 minute' WHERE id = '" + id + "'", CHECK_VIOLATION);
            app.rollback();
            refused(app, "UPDATE risk.risk_action SET target_ref = '" + IDS.next() + "' WHERE id = '" + id + "'",
                    "42501");
            app.rollback();
            refused(app, "DELETE FROM risk.risk_action WHERE id = '" + id + "'", "42501");
            app.rollback();
        }
        RiskAction approved = approve(held.taken(), proposed);
        assertThat(drive(approved.id())).contains(ActionOutcome.DONE);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "UPDATE risk.risk_action SET status = 'DISPATCHED', attempts = attempts + 1, next_attempt_at"
                    + " = clock_timestamp() + interval '1 minute', outcome = NULL WHERE id = '" + id + "'",
                    CHECK_VIOLATION);
            app.rollback();
        }
        Held other = heldCase();
        RiskAction pending = approved(other, ActionKind.DENY_HELD_WITHDRAWAL);
        asSystem(() -> inOneTransaction(uow -> actions.claim(uow, pending.id(), LEASE))).orElseThrow();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "UPDATE risk.risk_action SET attempts = attempts + 1, next_attempt_at = clock_timestamp()"
                    + " + interval '5 minutes' WHERE id = '" + pending.id().value() + "'", CHECK_VIOLATION);
            app.rollback();
        }
        Taken party = partyCase(CaseKind.FRAUD);
        assertThatThrownBy(() -> propose(party, ActionKind.SUSPEND_IDENTITY, Optional.of(IDS.next())))
                .isInstanceOf(RiskActions.ActionNotProposable.class);
    }

    @Test
    @DisplayName("the fold keeps every key: V013's own copy statement, run over a planted V009-shaped override table,"
            + " issues an awaiting action DISPATCHED under the override's id with its permit and attempts, and a"
            + " concluded one DONE with its outcome - so the key payments saw, risk.action:<override id>, is the key")
    void anOverridesPendingActionKeepsItsKey() throws Exception {
        String fold = foldStatement().replace("risk.risk_action", "pg_temp.fold_action")
                .replace("risk.decision_override o", "pg_temp.fold_override o");
        UUID awaiting = IDS.next();
        UUID concluded = IDS.next();
        // The bootstrap role: the probe makes no privilege claim, and the migrator holds no TEMP on the database (the
        // privilege floor) - P13-TST-001's run of this owed suite met "permission denied to create temporary tables".
        try (Connection migrator = DatabaseRoles.bootstrap()) {
            migrator.setAutoCommit(false);
            try (Statement statement = migrator.createStatement()) {
                statement.execute("CREATE TEMP TABLE fold_override (id uuid, status text, effect text, target_id uuid,"
                        + " reason_code text, proposed_by text, proposed_by_type text, proposed_at timestamptz,"
                        + " decided_by text, decided_by_type text, decided_at timestamptz, action_outcome text,"
                        + " action_due_at timestamptz, action_attempts integer, action_concluded_at timestamptz,"
                        + " correlation_id text) ON COMMIT DROP");
                statement.execute("CREATE TEMP TABLE fold_action (LIKE risk.risk_action INCLUDING DEFAULTS"
                        + " INCLUDING CONSTRAINTS) ON COMMIT DROP");
                statement.execute("INSERT INTO pg_temp.fold_override VALUES ('" + awaiting + "', 'APPROVED',"
                        + " 'RELEASE_HELD_WITHDRAWAL', '" + IDS.next() + "', 'FALSE_POSITIVE', 'officer-a', 'EMPLOYEE',"
                        + " clock_timestamp() - interval '1 hour', 'officer-b', 'EMPLOYEE', clock_timestamp() -"
                        + " interval '50 minutes', NULL, clock_timestamp() - interval '48 minutes', 1, NULL, 'flow-a'),"
                        + " ('" + concluded + "', 'APPROVED', 'VOID_HELD_PAYMENT', '" + IDS.next() + "',"
                        + " 'RISK_CONFIRMED', 'officer-c', 'EMPLOYEE', clock_timestamp() - interval '2 hours',"
                        + " 'officer-d', 'EMPLOYEE', clock_timestamp() - interval '110 minutes', 'ALREADY', NULL, 2,"
                        + " clock_timestamp() - interval '100 minutes', 'flow-c')");
                statement.execute(fold);
                Map<String, String> folded = new HashMap<>();
                try (ResultSet row = statement.executeQuery("SELECT id::text, source || ',' || override_id::text"
                        + " || ',' || status || ',' || attempts || ',' || coalesce(outcome, '-') || ','"
                        + " || (next_attempt_at IS NOT NULL) FROM pg_temp.fold_action")) {
                    while (row.next()) {
                        folded.put(row.getString(1), row.getString(2));
                    }
                }
                assertThat(folded).containsEntry(awaiting.toString(), "OVERRIDE," + awaiting
                        + ",DISPATCHED,1,-,true").containsEntry(concluded.toString(), "OVERRIDE," + concluded
                        + ",DONE,2,ALREADY,false");
            }
            migrator.rollback();
        }
        assertThat(RiskActions.keyOf(RiskActionId.ofOverride(DecisionOverrideId.of(awaiting))))
                .isEqualTo("risk.action:" + awaiting);
    }

    // ------------------------------------------------------------------ fixtures

    private record Taken(RiskCase riskCase, Actor investigator) {}

    private record Held(Taken taken, RiskAssessment assessment) {
        UUID movement() {
            return UUID.fromString(assessment.commandRef().value());
        }
    }

    /** A party's case of {@code kind} opened by hand and taken. */
    private static Taken partyCase(CaseKind kind) {
        Actor investigator = analyst();
        RiskCase opened = inOneTransaction(uow -> CASES.open(uow, Cases.OpenRequest.of(kind,
                SignalSubject.of(SubjectKind.PARTY, IDS.next())), "worth a look", investigator, correlation()))
                .riskCase();
        return new Taken(inOneTransaction(uow -> CASES.take(uow, opened.id(), opened.revision(), investigator,
                correlation())).riskCase(), investigator);
    }

    /** A FRAUD case opened by a sealed withdrawal HOLD (the system's opening), taken by a fresh analyst. */
    private static Held heldCase() {
        UUID party = IDS.next();
        RiskAssessment held = sealedHold(party);
        RiskCase opened = asSystem(() -> inOneTransaction(uow -> CASES.openFor(uow, new CaseOpener.Decided(held.id(),
                RiskOperation.WITHDRAWAL, held.commandRef(), party, RiskDecision.HOLD, CaseKind.FRAUD,
                correlation())))).riskCase();
        Actor investigator = analyst();
        return new Held(new Taken(inOneTransaction(uow -> CASES.take(uow, opened.id(), opened.revision(),
                investigator, correlation())).riskCase(), investigator), held);
    }

    private RiskAction propose(Taken taken, ActionKind kind, Optional<UUID> target) {
        int revision = revisionOf(taken.riskCase().id());
        return inOneTransaction(uow -> actions.propose(uow, taken.riskCase().id(), revision, kind.name(), target,
                "the case's response", taken.investigator(), correlation())).action();
    }

    private RiskAction approve(Taken taken, RiskAction proposed) {
        int revision = revisionOf(taken.riskCase().id());
        return inOneTransaction(uow -> actions.approve(uow, taken.riskCase().id(), proposed.id(), revision,
                "four eyes", analyst(), correlation())).action();
    }

    private RiskAction approved(Held held, ActionKind kind) {
        return approve(held.taken(), propose(held.taken(), kind, Optional.of(held.movement())));
    }

    /** T-act for {@code id} as the driver runs it: the claim committed, the owner's door, the record. */
    private Optional<ActionOutcome> drive(RiskActionId id) {
        return asSystem(() -> {
            Optional<RiskAction> claimed = inOneTransaction(uow -> actions.claim(uow, id, LEASE));
            if (claimed.isEmpty()) {
                return Optional.empty();
            }
            ActionOutcome outcome = actions.dispatch(claimed.get());
            return inOneTransaction(uow -> actions.conclude(uow, claimed.get(), outcome, correlation()))
                    .map(recorded -> outcome);
        });
    }

    private static int revisionOf(RiskCaseId id) {
        return inOneTransaction(uow -> CASES.current(uow, id)).orElseThrow().revision();
    }

    /** A withdrawal HOLD of {@code party}, sealed through the store under a version PROPOSED for it. */
    private static RiskAssessment sealedHold(UUID party) {
        RiskPolicy policy = OverrideWorld.policy(RiskOperation.WITHDRAWAL, RiskDecision.HOLD,
                RiskPolicyFixtures.OVERRIDE_THRESHOLD_MINOR);
        RiskPolicyAdministration.Proposed version = inOneTransaction(uow -> ADMINISTRATION.propose(uow, policy,
                "the action suite's version", analyst(), correlation()));
        Money amount = Money.ofMinorUnits(100_00, CurrencyCode.of("EUR"));
        RiskEvaluationRequest request = RiskEvaluationRequest.builder(RiskOperation.WITHDRAWAL,
                CommandRef.of(IDS.next()), amount, party, correlation(), CausationId.of("action-suite"))
                .sourceAccount(IDS.next()).build();
        EvaluationInputs inputs = request.judged().build();
        Verdict verdict = RiskEngineV1.evaluate(policy, inputs);
        String inputsText = CanonicalEvaluation.render(inputs);
        RiskAssessment assessment = new RiskAssessment(RiskAssessmentId.next(IDS), RiskOperation.WITHDRAWAL,
                request.commandRef(), RiskAssessment.Subjects.of(request), amount,
                Optional.of(new RiskAssessment.Pin(version.id(), version.version())), verdict.engine(), verdict.score(),
                RiskDecision.HOLD, Optional.empty(), Optional.empty(), VelocityOutcome.NOT_PERMITTED, Optional.empty(),
                inputsText, CanonicalEvaluation.sha256(inputsText),
                CanonicalEvaluation.sha256(CanonicalEvaluation.render(verdict)), verdict.hits(), verdict.reasons(), 100,
                request.correlation().value(), "action-suite", Optional.empty());
        inOneTransaction(uow -> ASSESSMENTS.insert(uow, assessment));
        return inOneTransaction(uow -> ASSESSMENTS.read(uow, assessment.id())).orElseThrow();
    }

    /** V013's fold - the one INSERT INTO risk.risk_action ... FROM risk.decision_override, as the migration runs it. */
    private static String foldStatement() {
        try (InputStream in = RiskActionDatabaseTest.class.getClassLoader()
                .getResourceAsStream("db/migration/risk/V013__downstream_risk_actions.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.strip().startsWith("--")).collect(Collectors.joining("\n"));
            int start = sql.indexOf("INSERT INTO risk.risk_action (");
            return sql.substring(start, sql.indexOf(';', start));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static Actor analyst() {
        Actor actor = new Actor(IDS.next().toString(), ActorType.CUSTOMER);
        PARTY_OF.put(actor.id(), IDS.next());
        return actor;
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private static <T> T asSystem(java.util.function.Supplier<T> work) {
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            return work.get();
        }
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

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }

    private static SQLException sqlFailure(SqlWork work) {
        try {
            work.run();
        } catch (SQLException failure) {
            return failure;
        }
        throw new AssertionError("the statement was not refused");
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

    /** The owners' doors, synchronised: a key's answer replayed, one move per target, a scripted refusal. */
    private static final class Owners implements HeldWithdrawalActions, HeldPaymentActions, IdentityActions,
            MerchantActions {
        private final List<String> calls = new ArrayList<>();
        private final Map<String, ActionOutcome> answers = new HashMap<>();
        private final java.util.Set<UUID> moved = new java.util.HashSet<>();
        private final java.util.Set<UUID> refused = new java.util.HashSet<>();
        volatile Runnable during = () -> { };

        synchronized List<String> calls() {
            return List.copyOf(calls);
        }

        synchronized int effects() {
            return moved.size();
        }

        synchronized void refuse(UUID target) {
            refused.add(target);
        }

        @Override
        public ActionOutcome release(UUID withdrawalId, String idempotencyKey, String reason) {
            return door("release", withdrawalId, idempotencyKey);
        }

        @Override
        public ActionOutcome deny(UUID withdrawalId, String idempotencyKey, String reason) {
            return door("deny", withdrawalId, idempotencyKey);
        }

        @Override
        public ActionOutcome capture(UUID intentId, String idempotencyKey, String reason) {
            return door("capture", intentId, idempotencyKey);
        }

        @Override
        public ActionOutcome voidHold(UUID intentId, String idempotencyKey, String reason) {
            return door("void", intentId, idempotencyKey);
        }

        @Override
        public ActionOutcome suspend(UUID partyId, String decidedBy, String idempotencyKey, String reason) {
            return door("suspend-identity", partyId, idempotencyKey);
        }

        @Override
        public ActionOutcome reinstate(UUID partyId, String decidedBy, String idempotencyKey, String reason) {
            return door("reinstate-identity", partyId, idempotencyKey);
        }

        @Override
        public ActionOutcome revokeSessions(UUID partyId, String idempotencyKey, String reason) {
            return door("revoke-sessions", partyId, idempotencyKey);
        }

        @Override
        public ActionOutcome suspend(UUID merchantId, String idempotencyKey, String reason) {
            return door("suspend-merchant", merchantId, idempotencyKey);
        }

        private ActionOutcome door(String door, UUID target, String key) {
            during.run();
            synchronized (this) {
                calls.add(door + "|" + target + "|" + key);
                return answers.computeIfAbsent(key, k -> {
                    if (refused.contains(target)) {
                        return ActionOutcome.REFUSED;
                    }
                    return moved.add(target) ? ActionOutcome.DONE : ActionOutcome.ALREADY;
                });
            }
        }
    }
}
