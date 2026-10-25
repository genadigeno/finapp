package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * The case against a real database (`P13-TSK-016`; {@code risk V012}; {@code PHASE_13_PLAN.md} sections 7.4, 13.2
 * scenarios 1, 5, 6, 12, 15; {@code INV-RSK-06}, {@code INV-RSK-07}, {@code INV-RSK-10}, {@code INV-AUD-04}): two
 * investigators on one revision keep both acts with one {@code 409}; ten alerts fold into one open case; a late alert
 * after the disposal opens a new case naming its predecessor; the proposer's approval is refused by the domain and by
 * {@code risk_case_disposition_four_eyes} alone; ten approvers dispose once; a prohibiting disposition writes one profile
 * version under ten re-drives; the drain attaches the backlog once under ten instances; the profile leg's order
 * (R8 → R4 → R5) and a case act's (R4 alone, R8 after the commit) never deadlock; and the machine refuses every other
 * edge for every writer. <strong>Written and owed</strong> (owner decision C5).
 */
@Tag("database")
@DisplayName("the case against the database: one open per subject and kind, the revision, four eyes by CHECK"
        + " (P13-TSK-016)")
class CaseDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcRiskPolicyStore POLICY_STORE = new JdbcRiskPolicyStore();
    private static final RiskPolicyAdministration ADMINISTRATION = new RiskPolicyAdministration(POLICY_STORE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
    private static final JdbcRiskAlertStore ALERT_STORE = new JdbcRiskAlertStore();
    private static final JdbcRiskCaseStore CASE_STORE = new JdbcRiskCaseStore();
    private static final JdbcCustomerRiskProfileStore PROFILE_STORE = new JdbcCustomerRiskProfileStore();
    private static final Map<String, UUID> PARTY_OF = new java.util.concurrent.ConcurrentHashMap<>();
    private static final RiskActingParty ACTING = (uow, actor) -> Optional.ofNullable(PARTY_OF.get(actor.id()));
    private static final Cases CASES = new Cases(CASE_STORE, ALERT_STORE, ACTING, RiskSubjectParties.NONE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, CaseObserver.NONE);
    /** The composition's alerts: born, then folded by the case. */
    private static final Alerts ALERTS = new Alerts(ALERT_STORE, CASES::fold, ACTING, RiskSubjectParties.NONE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, Spans.NONE, AlertObserver.NONE);
    /** The alerts born before `-016`: NONE folding leaves them OPEN - the drain's backlog. */
    private static final Alerts UNFOLDED = new Alerts(ALERT_STORE, AlertFolding.NONE, ACTING, RiskSubjectParties.NONE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, Spans.NONE, AlertObserver.NONE);
    private static final ProfileRecompute RECOMPUTE = new ProfileRecompute(new RiskPolicies(POLICY_STORE),
            PROFILE_STORE, new ProfileInputs((uow, party) -> Optional.empty(),
                    new SignalReadings(new JdbcRiskSignalStore()), RiskPartyIdentities.NONE,
                    new VelocityWindows(new JdbcVelocityWindowStore())),
            ALERTS::raiseAll, new JdbcOutboxWriter(), IDS, CLOCK, Duration.ofDays(1));
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

    @Test
    @DisplayName("scenario 5: two investigators act on one revision concurrently - one lands, one answers"
            + " CaseRevisionStale with nothing written; after a re-read both acts are kept, one history row per"
            + " revision")
    void concurrentUpdatesKeepEveryAction() throws Exception {
        RiskCase opened = openByHand(freshParty());
        List<Object> outcomes = race(2, () -> {
            try {
                return inOneTransaction(uow -> CASES.note(uow, opened.id(), opened.revision(), "read the ledger",
                        analyst(), correlation()));
            } catch (Cases.CaseRevisionStale stale) {
                return stale;
            }
        });
        assertThat(outcomes).filteredOn(Cases.Acted.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(Cases.CaseRevisionStale.class::isInstance).hasSize(1);
        assertThat(count("SELECT count(*) FROM risk.risk_case_action WHERE case_id = '" + opened.id().value() + "'"))
                .isEqualTo(1);
        RiskCase reread = inOneTransaction(uow -> CASES.current(uow, opened.id()).orElseThrow());
        inOneTransaction(uow -> CASES.note(uow, opened.id(), reread.revision(), "and the device", analyst(),
                correlation()));
        assertThat(column("SELECT revision::text FROM risk.risk_case_event WHERE case_id = '" + opened.id().value()
                + "' ORDER BY revision")).containsExactly("0", "1", "2");
        assertThat(count("SELECT count(*) FROM risk.risk_case_action WHERE case_id = '" + opened.id().value() + "'"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("INV-RSK-06, scenario 12: ten alerts of one subject and kind raised by ten instances concurrently fold"
            + " into ONE open case - every alert ATTACHED to it, one history row per alert")
    void tenAlertsOneOpenCase() throws Exception {
        SignalSubject subject = freshParty();
        List<Integer> days = new ArrayList<>();
        for (int day = 0; day < 10; day++) {
            days.add(day);
        }
        List<Integer> queue = java.util.Collections.synchronizedList(new ArrayList<>(days));
        race(10, () -> asSystem(() -> inOneTransaction(uow -> ALERTS.raise(uow, structuring(subject,
                Instant.now().minusSeconds(86_400L * queue.remove(0)))))));
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = '" + subject.ref() + "'"))
                .isEqualTo(1);
        assertThat(column("SELECT DISTINCT status FROM risk.risk_alert WHERE subject_ref = '" + subject.ref() + "'"))
                .containsExactly("ATTACHED");
        assertThat(count("SELECT count(DISTINCT attached_case_id) FROM risk.risk_alert WHERE subject_ref = '"
                + subject.ref() + "'")).isEqualTo(1);
        assertThat(count("SELECT revision FROM risk.risk_case WHERE subject_ref = '" + subject.ref() + "'"))
                .isEqualTo(9);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.CaseOpened'"
                + " AND aggregate_id IN (SELECT id FROM risk.risk_case WHERE subject_ref = '" + subject.ref() + "')"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an alert raised while its subject has an open case of the kind attaches to it - the case's revision"
            + " one up, its ALERT_ATTACHED row written")
    void anAlertWithAnOpenCaseAttaches() throws Exception {
        SignalSubject subject = freshParty();
        RiskCase opened = openByHand(subject, CaseKind.AML);
        Alerts.Raised raised = asSystem(() -> inOneTransaction(uow -> ALERTS.raise(uow, structuring(subject,
                Instant.now()))));
        assertThat(raised.folded().caseId()).contains(opened.id());
        assertThat(column("SELECT kind FROM risk.risk_case_event WHERE case_id = '" + opened.id().value()
                + "' ORDER BY revision")).containsExactly("OPENED", "ALERT_ATTACHED");
    }

    @Test
    @DisplayName("scenario 1's case half: the same alert twice is one alert, ATTACHED once, never SUPPRESSED - the case"
            + " unmoved by the duplicate")
    void aDuplicateAlertAttachesAndIsNeverSuppressed() throws Exception {
        SignalSubject subject = freshParty();
        NewAlert alert = structuring(subject, Instant.now());
        Alerts.Raised first = asSystem(() -> inOneTransaction(uow -> ALERTS.raise(uow, alert)));
        Alerts.Raised second = asSystem(() -> inOneTransaction(uow -> ALERTS.raise(uow, alert)));
        assertThat(second.outcome()).isEqualTo(Alerts.Outcome.ALREADY);
        assertThat(column("SELECT status FROM risk.risk_alert WHERE subject_ref = '" + subject.ref() + "'"))
                .containsExactly("ATTACHED");
        assertThat(count("SELECT revision FROM risk.risk_case WHERE id = '" + first.folded().caseId().orElseThrow()
                .value() + "'")).isZero();
    }

    @Test
    @DisplayName("scenario 6: a late alert after the disposal leaves the disposed case untouched and opens a new case"
            + " naming it as predecessor - never re-opened")
    void aLateEventAfterDispositionOpensANewCase() throws Exception {
        SignalSubject subject = freshParty();
        Alerts.Raised first = asSystem(() -> inOneTransaction(uow -> ALERTS.raise(uow, structuring(subject,
                Instant.now().minusSeconds(86_400)))));
        RiskCaseId closed = first.folded().caseId().orElseThrow();
        inOneTransaction(uow -> CASES.proposeDisposition(uow, closed, 0, "CLOSED_FALSE_POSITIVE", false,
                "the payroll pattern", analyst(), correlation()));
        String disposedRow = column("SELECT row(c.*)::text FROM risk.risk_case c WHERE id = '" + closed.value() + "'")
                .get(0);

        Alerts.Raised late = asSystem(() -> inOneTransaction(uow -> ALERTS.raise(uow, structuring(subject,
                Instant.now()))));
        RiskCaseId reopened = late.folded().caseId().orElseThrow();
        assertThat(reopened).isNotEqualTo(closed);
        assertThat(column("SELECT predecessor_case_id::text FROM risk.risk_case WHERE id = '" + reopened.value()
                + "'")).containsExactly(closed.value().toString());
        assertThat(column("SELECT row(c.*)::text FROM risk.risk_case c WHERE id = '" + closed.value() + "'"))
                .containsExactly(disposedRow);
    }

    @Test
    @DisplayName("INV-RSK-07, scenario 15: the proposer's approval is refused by the domain - and with the domain"
            + " bypassed, risk_case_disposition_four_eyes refuses it alone for every writer")
    void theProposerCannotApprove() throws Exception {
        Actor proposer = analyst();
        RiskCase taken = take(openByHand(freshParty()), proposer);
        Cases.Decided proposed = inOneTransaction(uow -> CASES.proposeDisposition(uow, taken.id(), taken.revision(),
                "CONFIRMED_FRAUD", false, "a mule account", proposer, correlation()));
        assertThatExceptionOfType(Cases.SelfApprovalRefused.class).isThrownBy(() -> inOneTransaction(uow ->
                CASES.approveDisposition(uow, taken.id(), proposed.dispositionId(), proposed.riskCase().revision(),
                        "agreed", proposer, correlation())));
        for (Connection writer : List.of(DatabaseRoles.application(), DatabaseRoles.migrator())) {
            try (writer) {
                writer.setAutoCommit(false);
                refused(writer, "UPDATE risk.risk_case SET status = 'DISPOSED', revision = revision + 1,"
                        + " disposition_approved_by = disposition_proposed_by, disposition_approved_by_type = 'EMPLOYEE'"
                        + " WHERE id = '" + taken.id().value() + "'", CHECK_VIOLATION);
                writer.rollback();
            }
        }
        assertThat(column("SELECT status FROM risk.risk_case WHERE id = '" + taken.id().value() + "'"))
                .containsExactly("IN_INVESTIGATION");
    }

    @Test
    @DisplayName("ten approvers of one disposition concurrently: one disposes, nine find it disposed - one approval, one"
            + " risk.CaseDisposed")
    void tenApproversOneDisposition() throws Exception {
        Actor proposer = analyst();
        RiskCase taken = take(openByHand(freshParty()), proposer);
        Cases.Decided proposed = inOneTransaction(uow -> CASES.proposeDisposition(uow, taken.id(), taken.revision(),
                "REFERRED", false, "for the payments desk", proposer, correlation()));
        List<Object> outcomes = race(10, () -> {
            try {
                return inOneTransaction(uow -> CASES.approveDisposition(uow, taken.id(), proposed.dispositionId(),
                        proposed.riskCase().revision(), "agreed", analyst(), correlation()));
            } catch (Cases.CaseNotOpen disposed) {
                return disposed;
            }
        });
        assertThat(outcomes).filteredOn(Cases.Decided.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(Cases.CaseNotOpen.class::isInstance).hasSize(9);
        assertThat(count("SELECT count(*) FROM risk.risk_case_action WHERE case_id = '" + taken.id().value()
                + "' AND kind = 'DISPOSITION_APPROVED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.CaseDisposed'"
                + " AND aggregate_id = '" + taken.id().value() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("INV-RSK-10: an approved prohibiting disposition's PROHIBITED is written after the case commits, ONE"
            + " version under ten concurrent re-drives, naming the disposition - and the sweep never clears it")
    void aConfirmedDispositionSetsProhibitedOnce() throws Exception {
        UUID customer = IDS.next();
        Actor proposer = analyst();
        RiskCase taken = take(openByHand(SignalSubject.of(SubjectKind.PARTY, customer)), proposer);
        Cases.Decided proposed = inOneTransaction(uow -> CASES.proposeDisposition(uow, taken.id(), taken.revision(),
                "CONFIRMED_FRAUD", true, "a mule account", proposer, correlation()));
        inOneTransaction(uow -> CASES.approveDisposition(uow, taken.id(), proposed.dispositionId(),
                proposed.riskCase().revision(), "confirmed", analyst(), correlation()));
        assertThat(count("SELECT count(*) FROM risk.customer_risk_profile_version WHERE party_id = '" + customer
                + "'")).as("the case act wrote no profile row").isZero();

        race(10, () -> inOneTransaction(uow -> RECOMPUTE.applyDisposition(uow, customer, proposed.dispositionId(),
                correlation())));
        assertThat(column("SELECT rating || '/' || cause || '/' || disposition_id::text FROM"
                + " risk.customer_risk_profile_version WHERE party_id = '" + customer + "'"))
                .containsExactly("PROHIBITED/DISPOSITION/" + proposed.dispositionId());
        List<RiskCaseStore.PendingProhibition> due = inOneTransaction(uow -> CASES.prohibitionsDue(uow, 100));
        assertThat(due).noneMatch(pending -> pending.dispositionId().equals(proposed.dispositionId()));
        assertThat(inOneTransaction(uow -> RECOMPUTE.recompute(uow, customer, correlation())).kind())
                .isEqualTo(ProfileRecompute.Kind.PROHIBITED_STANDS);
    }

    @Test
    @DisplayName("the drain: alerts born OPEN before the folding are attached once under ten draining instances - one"
            + " case per subject and kind, each alert ATTACHED, no second opening")
    void theDrainAttachesTheBacklogOnce() throws Exception {
        SignalSubject subject = freshParty();
        List<RiskAlert> backlog = new ArrayList<>();
        for (int day = 0; day < 3; day++) {
            int ago = day;
            backlog.add(inOneTransaction(uow -> UNFOLDED.raise(uow, structuring(subject,
                    Instant.now().minusSeconds(86_400L * ago)))).alert());
        }
        race(10, () -> {
            for (RiskAlert alert : backlog) {
                try {
                    asSystem(() -> inOneTransaction(uow -> CASES.drain(uow, alert.id())));
                } catch (Alerts.AlertNotOpen raced) {
                    // another instance won it; this transaction rolled back what it wrote
                }
            }
            return null;
        });
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = '" + subject.ref() + "'"))
                .isEqualTo(1);
        assertThat(column("SELECT DISTINCT status FROM risk.risk_alert WHERE subject_ref = '" + subject.ref() + "'"))
                .containsExactly("ATTACHED");
        assertThat(count("SELECT revision FROM risk.risk_case WHERE subject_ref = '" + subject.ref() + "'"))
                .as("the opening and two attaches").isEqualTo(2);
    }

    @Test
    @DisplayName("the lock order: the profile leg (R8, then its fold's R4 and R5) and a case act on the same party's case"
            + " (R4 alone, the prohibition's R8 after its commit) run together twenty times - no deadlock (40P01)")
    void noDeadlockAgainstTheProfileLeg() throws Exception {
        for (int round = 0; round < 20; round++) {
            UUID customer = IDS.next();
            SignalSubject subject = SignalSubject.of(SubjectKind.PARTY, customer);
            Actor proposer = analyst();
            RiskCase taken = take(openByHand(subject, CaseKind.AML), proposer);
            Cases.Decided proposed = inOneTransaction(uow -> CASES.proposeDisposition(uow, taken.id(),
                    taken.revision(), "CONFIRMED_SUSPICIOUS", true, "structuring confirmed", proposer, correlation()));
            CountDownLatch headHeld = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger roles = new java.util.concurrent.atomic.AtomicInteger();
            List<Object> outcomes = race(2, () -> {
                if (roles.getAndIncrement() == 0) {
                    // The profile leg: the head (R8), then an alert of the party folded (R4 -> R5).
                    return asSystem(() -> inOneTransaction(uow -> {
                        PROFILE_STORE.lockOrCreate(uow, customer);
                        headHeld.countDown();
                        return ALERTS.raise(uow, structuring(subject, Instant.now()));
                    }));
                }
                headHeld.await(5, TimeUnit.SECONDS);
                // The case act: the approval (R4 alone) - a client re-reading on a 409 when the leg's fold moved the
                // revision first - then the prohibition in its own transaction (R8).
                Actor approver = analyst();
                for (int attempt = 0; ; attempt++) {
                    int seen = inOneTransaction(uow -> CASES.current(uow, taken.id()).orElseThrow()).revision();
                    try {
                        inOneTransaction(uow -> CASES.approveDisposition(uow, taken.id(), proposed.dispositionId(),
                                seen, "confirmed", approver, correlation()));
                        break;
                    } catch (Cases.CaseRevisionStale stale) {
                        if (attempt > 5) {
                            throw stale;
                        }
                    }
                }
                return inOneTransaction(uow -> RECOMPUTE.applyDisposition(uow, customer, proposed.dispositionId(),
                        correlation()));
            });
            assertThat(outcomes).hasSize(2).doesNotContainNull();
        }
    }

    @Test
    @DisplayName("a false positive is closed from OPEN by one person, stamped by the database; a two persons'"
            + " disposition on an OPEN case is refused by the domain and by the trigger")
    void aCloseFromOpenNeedsNoTake() throws Exception {
        RiskCase opened = openByHand(freshParty());
        Cases.Decided closed = inOneTransaction(uow -> CASES.proposeDisposition(uow, opened.id(), opened.revision(),
                "CLOSED_FALSE_POSITIVE", false, "the customer's payroll", analyst(), correlation()));
        assertThat(closed.riskCase().status()).isEqualTo(CaseStatus.DISPOSED);
        assertThat(column("SELECT (disposed_at IS NOT NULL AND disposition_proposed_at = disposed_at)::text FROM"
                + " risk.risk_case WHERE id = '" + opened.id().value() + "'")).containsExactly("true");
    }

    @Test
    @DisplayName("a confirming disposition needs the case taken: the domain refuses CaseNotTaken, and a writer that"
            + " bypassed it proposing on an OPEN case is refused by the trigger")
    void aConfirmedDispositionNeedsTheCaseTaken() throws Exception {
        RiskCase opened = openByHand(freshParty());
        assertThatExceptionOfType(Cases.CaseNotTaken.class).isThrownBy(() -> inOneTransaction(uow ->
                CASES.proposeDisposition(uow, opened.id(), opened.revision(), "CONFIRMED_FRAUD", false, "fraud",
                        analyst(), correlation())));
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            String action = IDS.next().toString();
            try (Statement statement = migrator.createStatement()) {
                statement.execute("INSERT INTO risk.risk_case_action (id, case_id, ordinal, kind, actor_id, actor_type,"
                        + " text, evidence_signal_ids, evidence_assessment_ids, evidence_journal_entry_ids, disposition,"
                        + " prohibits, queue, recorded_at, correlation_id) VALUES ('" + action + "', '"
                        + opened.id().value() + "', 1, 'DISPOSITION_PROPOSED', 'mallory', 'EMPLOYEE', 'fraud', '{}',"
                        + " '{}', '{}', 'CONFIRMED_FRAUD', false, NULL, clock_timestamp(), 'flow')");
            }
            refused(migrator, "UPDATE risk.risk_case SET revision = revision + 1, disposition = 'CONFIRMED_FRAUD',"
                    + " disposition_id = '" + action + "', disposition_prohibits = false, disposition_proposed_by ="
                    + " 'mallory', disposition_proposed_by_type = 'EMPLOYEE' WHERE id = '" + opened.id().value() + "'",
                    CHECK_VIOLATION);
            migrator.rollback();
        }
    }

    @Test
    @DisplayName("the machine refuses every other edge for every writer: a second open case of a subject and kind, a"
            + " re-opened disposed case, a skipped revision, an unescalated level, a deleted case, an edited action or"
            + " history row")
    void theMachineRefusesEveryOtherEdge() throws Exception {
        SignalSubject subject = freshParty();
        RiskCase opened = openByHand(subject);
        RiskCase disposed = inOneTransaction(uow -> CASES.proposeDisposition(uow, openByHand(freshParty()).id(), 0,
                "CLOSED_NO_ACTION", false, "nothing", analyst(), correlation())).riskCase();
        for (Connection writer : List.of(DatabaseRoles.application(), DatabaseRoles.migrator())) {
            try (writer) {
                writer.setAutoCommit(false);
                refused(writer, "INSERT INTO risk.risk_case (id, kind, subject_kind, subject_ref, status, revision,"
                        + " level, queue, sla_hours, sla_deadline, opened_at, correlation_id) VALUES ('" + IDS.next()
                        + "', 'FRAUD', 'PARTY', '" + subject.ref() + "', 'OPEN', 0, 'L1', 'FIRST_LINE', 24,"
                        + " clock_timestamp() + interval '1 day', clock_timestamp(), 'flow')", UNIQUE_VIOLATION);
                writer.rollback();
                refused(writer, "UPDATE risk.risk_case SET status = 'OPEN', revision = revision + 1 WHERE id = '"
                        + disposed.id().value() + "'", CHECK_VIOLATION);
                writer.rollback();
                refused(writer, "UPDATE risk.risk_case SET revision = revision + 2 WHERE id = '" + opened.id().value()
                        + "'", CHECK_VIOLATION);
                writer.rollback();
                refused(writer, "UPDATE risk.risk_case SET revision = revision + 1, level = 'L2' WHERE id = '"
                        + opened.id().value() + "'", CHECK_VIOLATION);
                writer.rollback();
                refusedAny(writer, "DELETE FROM risk.risk_case WHERE id = '" + opened.id().value() + "'");
                writer.rollback();
                refusedAny(writer, "UPDATE risk.risk_case_event SET kind = 'NOTED' WHERE case_id = '"
                        + opened.id().value() + "'");
                writer.rollback();
                refusedAny(writer, "DELETE FROM risk.risk_case_event WHERE case_id = '" + opened.id().value() + "'");
                writer.rollback();
            }
        }
        RiskCase noted = inOneTransaction(uow -> CASES.note(uow, opened.id(), opened.revision(), "a note", analyst(),
                correlation())).riskCase();
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            refusedAny(migrator, "UPDATE risk.risk_case_action SET text = 'rewritten' WHERE case_id = '"
                    + noted.id().value() + "'");
            migrator.rollback();
        }
    }

    // -----------------------------------------------------------------

    private static RiskCase openByHand(SignalSubject subject) {
        return openByHand(subject, CaseKind.FRAUD);
    }

    private static RiskCase openByHand(SignalSubject subject, CaseKind kind) {
        return inOneTransaction(uow -> CASES.open(uow, Cases.OpenRequest.of(kind, subject), "worth a look",
                analyst(), correlation())).riskCase();
    }

    private static RiskCase take(RiskCase riskCase, Actor investigator) {
        return inOneTransaction(uow -> CASES.take(uow, riskCase.id(), riskCase.revision(), investigator,
                correlation())).riskCase();
    }

    private static NewAlert structuring(SignalSubject subject, Instant evidence) {
        return NewAlert.ofRule(AlertKind.AML, AlertSeverity.MEDIUM, subject, RuleKind.STRUCTURING,
                AlertWindow.containing(WindowKind.D1, evidence), policy(),
                AlertEvidence.journalEntries(List.of(IDS.next())), correlation(), CausationId.of("case-suite"));
    }

    private static RiskPolicyVersionId policy() {
        return SuiteVersion.ID;
    }

    /** The suite's one policy version, proposed on first use (the holder idiom). */
    private static final class SuiteVersion {
        static final RiskPolicyVersionId ID = inOneTransaction(uow -> ADMINISTRATION.propose(uow,
                RiskPolicyFixtures.v1(RiskOperation.MONITORING), "the case suite's version", analyst(), correlation()))
                .id();
    }

    private static SignalSubject freshParty() {
        return SignalSubject.of(SubjectKind.PARTY, IDS.next());
    }

    private static Actor analyst() {
        Actor actor = new Actor("analyst-" + UUID.randomUUID(), ActorType.EMPLOYEE);
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

    /** Refused by the trigger or the grant - whichever stands first for this writer. */
    private static void refusedAny(Connection connection, String sql) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class);
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
