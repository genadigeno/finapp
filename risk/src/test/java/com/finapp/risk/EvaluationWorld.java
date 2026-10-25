package com.finapp.risk;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.testing.InMemoryCustomerRiskProfileStore;
import com.finapp.risk.testing.InMemoryRiskAssessmentStore;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * T-eval over fakes for the hermetic suites (`P13-TSK-004`): the real engine, the real {@link RiskPolicies},
 * {@link VelocityWindows}, {@link SignalReadings} and {@link CustomerRiskProfiles} over in-memory stores, a recording
 * budget, outbox, case opener and observer - and ONE journal every fake writes to in call order, so a test reads the
 * T-eval order as it ran: {@code find}, {@code open -}/{@code open 250ms}, {@code pin}, {@code keep}/{@code undo},
 * {@code serialise}, {@code now}, {@code windows}, {@code upsert}, {@code inputs}, {@code insert}, {@code event}...
 * Failures are scripted per call ({@link #failPin}, {@link #failSerialise}, {@link #failFind}, {@link #failInputs}).
 * The fake budget cannot roll a fake store back, so every scripted failure strikes before any write of its step.
 */
final class EvaluationWorld {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    static final Connection UOW = StandInConnections.unused();

    /** Every call any fake received, in order. */
    final List<String> journal = new ArrayList<>();

    final JournalledPolicies policyStore = new JournalledPolicies();
    final JournalledWindows windowStore = new JournalledWindows();
    final InMemoryCustomerRiskProfileStore profileStore = new InMemoryCustomerRiskProfileStore();
    final SignalReadingsTest.InMemoryStore signalStore = new SignalReadingsTest.InMemoryStore();
    final InMemoryRiskAssessmentStore assessmentStore = new InMemoryRiskAssessmentStore();
    final List<RiskEvaluationObserver.Report> reports = new ArrayList<>();
    final List<CaseOpener.Decided> cases = new ArrayList<>();
    final List<EventEnvelope> envelopes = new ArrayList<>();
    final List<Map<String, String>> payloads = new ArrayList<>();

    /** What {@link RiskInputs} answers; every reading absent by default. */
    RiskInputs.Readings external = RiskInputs.Readings.none();

    /** Scripted failures: each pops one failure for the next call of its kind. */
    final Deque<RuntimeException> failPin = new ArrayDeque<>();
    final Deque<RuntimeException> failSerialise = new ArrayDeque<>();
    final Deque<RuntimeException> failFind = new ArrayDeque<>();
    final Deque<RuntimeException> failInputs = new ArrayDeque<>();
    final Deque<RuntimeException> failRaise = new ArrayDeque<>();

    /** Every list of alerts T-eval asked to raise (`P13-TSK-013`), in order. */
    final List<List<NewAlert>> raised = new ArrayList<>();

    /** Every alert raise T-eval reported unraised: the assessment and the cause's code. */
    final List<String> unraised = new ArrayList<>();

    final RiskEvaluation evaluation;

    EvaluationWorld() {
        RiskInputs inputs = (unitOfWork, request, judged, now) -> {
            journal.add("inputs");
            if (!failInputs.isEmpty()) {
                throw failInputs.pop();
            }
            return external;
        };
        EvaluationBudget budget = new EvaluationBudget() {
            @Override
            public Mark open(Connection unitOfWork, Optional<Duration> timeout) {
                journal.add("open " + timeout.map(t -> t.toMillis() + "ms").orElse("-"));
                return new Mark(new Object(), timeout.map(t -> "0"));
            }

            @Override
            public void keep(Connection unitOfWork, Mark mark) {
                journal.add("keep");
            }

            @Override
            public void undo(Connection unitOfWork, Mark mark) {
                journal.add("undo");
            }
        };
        OutboxWriter<Connection> outbox = (unitOfWork, envelope, payload, mediaType) -> {
            journal.add("event " + envelope.eventType());
            envelopes.add(envelope);
            payloads.add(EventPayload.fields(payload));
        };
        AuditWriter<Connection> noAudit = (unitOfWork, record) -> {
            throw new AssertionError("T-eval audits nothing: " + record);
        };
        CustomerRiskProfiles profiles = new CustomerRiskProfiles(profileStore, (unitOfWork, actor) -> Optional.empty(),
                noAudit, IDS, CLOCK);
        RiskAssessmentStore journalled = new RiskAssessmentStore() {
            @Override
            public Optional<RiskAssessment> find(Connection unitOfWork, RiskOperation operation, CommandRef commandRef) {
                journal.add("find");
                return assessmentStore.find(unitOfWork, operation, commandRef);
            }

            @Override
            public Optional<RiskAssessment> read(Connection unitOfWork, RiskAssessmentId id) {
                return assessmentStore.read(unitOfWork, id);
            }

            @Override
            public boolean insert(Connection unitOfWork, RiskAssessment assessment) {
                journal.add("insert");
                return assessmentStore.insert(unitOfWork, assessment);
            }

            @Override
            public List<VelocityEvidence.Counted> countedSince(Connection unitOfWork, Instant from) {
                return assessmentStore.countedSince(unitOfWork, from);
            }

            @Override
            public Optional<JudgedPayment> judgedPayment(Connection unitOfWork, CommandRef commandRef) {
                return assessmentStore.judgedPayment(unitOfWork, commandRef);
            }
        };
        AlertRaiser raiser = (unitOfWork, alerts) -> {
            journal.add("raise " + alerts.size());
            if (!failRaise.isEmpty()) {
                throw failRaise.pop();
            }
            raised.add(List.copyOf(alerts));
            return List.of();
        };
        RiskEvaluationObserver observer = new RiskEvaluationObserver() {
            @Override
            public void evaluated(Report report) {
                reports.add(report);
            }

            @Override
            public void alertsUnraised(RiskAssessmentId assessment, RiskOperation operation, String cause) {
                unraised.add(assessment.value() + " " + cause);
            }
        };
        evaluation = new RiskEvaluation(new RiskPolicies(policyStore), new VelocityWindows(windowStore), profiles,
                new SignalReadings(signalStore), inputs, journalled, budget, (unitOfWork, decided) -> {
                    journal.add("case " + decided.decision());
                    cases.add(decided);
                }, raiser, outbox, observer, Spans.NONE, IDS, CLOCK);
    }

    /** Activates {@code policy} as its operation's version. */
    RiskPolicyVersionId activate(RiskPolicy policy) {
        return policyStore.activate(policy);
    }

    /** Activates the v1 fixture of {@code operation}. */
    RiskPolicyVersionId activate(RiskOperation operation) {
        return activate(RiskPolicyFixtures.v1(operation));
    }

    /** The journal since the last {@link #clear}. */
    void clear() {
        journal.clear();
    }

    static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    static RiskEvaluationRequest.Builder request(RiskOperation operation, Money amount, UUID party) {
        return RiskEvaluationRequest.builder(operation, CommandRef.of(IDS.next()), amount, party,
                CorrelationId.of("flow-" + UUID.randomUUID()), CausationId.of("command"));
    }

    /** A transfer of {@code amount} from a fresh party's fresh account to a fresh account. */
    static RiskEvaluationRequest transfer(Money amount) {
        return request(RiskOperation.TRANSFER, amount, IDS.next()).sourceAccount(IDS.next())
                .destinationAccount(IDS.next()).actor(new Actor("customer-1", ActorType.CUSTOMER)).build();
    }

    // ------------------------------------------------------------------ the journalled fakes

    final class JournalledPolicies extends RiskPolicyAdministrationTest.FakeRiskPolicyStore {

        RiskPolicyVersionId activate(RiskPolicy policy) {
            RiskPolicyVersionId id = RiskPolicyVersionId.next(IDS);
            int version = maxVersion(UOW, policy.operation()) + 1;
            insertProposal(UOW, new NewProposal(id, version, policy, new Actor("alice", ActorType.EMPLOYEE), "seed",
                    CorrelationId.of("seed"), IDS.next()));
            lockActive(UOW, policy.operation()).ifPresent(active -> retire(UOW, active.id()));
            decide(UOW, id, RiskPolicyVersionStatus.ACTIVE, new Actor("bob", ActorType.EMPLOYEE), "seed");
            return id;
        }

        @Override
        public Optional<RiskPolicyVersionId> shareActiveOnce(Connection unitOfWork, RiskOperation operation) {
            journal.add("pin");
            if (!failPin.isEmpty()) {
                throw failPin.pop();
            }
            return super.shareActiveOnce(unitOfWork, operation);
        }
    }

    final class JournalledWindows implements VelocityWindowStore {
        final FakeVelocityWindowStore delegate = new FakeVelocityWindowStore(CLOCK.instant());

        @Override
        public Instant databaseNow(Connection unitOfWork) {
            journal.add("now");
            return delegate.databaseNow(unitOfWork);
        }

        @Override
        public void serialise(Connection unitOfWork, int lockKey) {
            journal.add("serialise");
            if (!failSerialise.isEmpty()) {
                throw failSerialise.pop();
            }
            delegate.serialise(unitOfWork, lockKey);
        }

        @Override
        public List<VelocityWindow> find(Connection unitOfWork, Collection<VelocityWindowKey> keys) {
            journal.add("windows");
            if (!failFind.isEmpty()) {
                throw failFind.pop();
            }
            return delegate.find(unitOfWork, keys);
        }

        @Override
        public Optional<VelocityWindow> upsert(Connection unitOfWork, VelocityWindowKey key, Money amount) {
            journal.add("upsert");
            return delegate.upsert(unitOfWork, key, amount);
        }

        @Override
        public List<VelocityWindow> live(Connection unitOfWork) {
            return delegate.live(unitOfWork);
        }

        @Override
        public Optional<Instant> firstCounted(Connection unitOfWork, VelocityDimension dimension, String subjectRef) {
            journal.add("first " + dimension);
            return delegate.firstCounted(unitOfWork, dimension, subjectRef);
        }

        /** The windows counted for {@code dimension}'s {@code subject}, of {@code kind}. */
        long count(VelocityDimension dimension, String subject, WindowKind kind) {
            return delegate.rows.values().stream()
                    .filter(w -> w.key().dimension() == dimension && w.key().subjectRef().equals(subject)
                            && w.key().windowKind() == kind)
                    .mapToLong(VelocityWindow::count).sum();
        }

        /** How many window rows exist at all. */
        int rows() {
            return delegate.rows.size();
        }
    }

    /** A timeout as every risk store reports one. */
    static RiskStorageException timeout() {
        return new RiskStorageException("risk storage failed taking a subject's lock: SQLSTATE "
                + RiskStorageException.QUERY_CANCELED);
    }

    /** Any other storage failure. */
    static RiskStorageException broken() {
        return new RiskStorageException("risk storage failed reading the velocity windows: SQLSTATE 08006");
    }

    /** The journal's entries of {@code prefix}. */
    long count(String prefix) {
        return journal.stream().filter(entry -> entry.startsWith(prefix)).count();
    }
}
