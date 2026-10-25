package com.finapp.app.risk;

import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionStatus;
import com.finapp.identity.SessionStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.AlertFolding;
import com.finapp.risk.AlertObserver;
import com.finapp.risk.AlertRaiser;
import com.finapp.risk.Alerts;
import com.finapp.risk.CaseOpener;
import com.finapp.risk.CustomerRiskProfiles;
import com.finapp.risk.EvaluationBudget;
import com.finapp.risk.ProfileInputs;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.Reading;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationObserver;
import com.finapp.risk.RiskInputs;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPartyIdentities;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskPolicyStore;
import com.finapp.risk.RiskPolicyVersion;
import com.finapp.risk.RiskPolicyVersionId;
import com.finapp.risk.RiskPolicyVersionStatus;
import com.finapp.risk.RiskSignal;
import com.finapp.risk.RiskSignalStore;
import com.finapp.risk.RiskStorageException;
import com.finapp.risk.RiskSubjectParties;
import com.finapp.risk.SessionAssurance;
import com.finapp.risk.SignalKind;
import com.finapp.risk.SignalReadings;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityWindow;
import com.finapp.risk.VelocityWindowKey;
import com.finapp.risk.VelocityWindowStore;
import com.finapp.risk.VelocityWindows;
import com.finapp.risk.WindowKind;
import com.finapp.risk.testing.InMemoryCustomerRiskProfileStore;
import com.finapp.risk.testing.InMemoryRiskAlertStore;
import com.finapp.risk.testing.InMemoryRiskAssessmentStore;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A real {@link RiskEvaluation} over in-memory stores, for {@code app}'s seam-adapter suites (`P13-TSK-008`; reusable by
 * `-009`…`-011`): the real engine, {@link RiskPolicies}, {@link VelocityWindows}, {@link SignalReadings} and
 * {@link CustomerRiskProfiles} over maps; the assessments in risk's {@link InMemoryRiskAssessmentStore} (the
 * {@code UNIQUE (operation, command_ref)} rule); a budget that opens and keeps nothing (the savepoint itself is the owed
 * database suites'); a recording outbox, case opener and observer; and {@link #inputs} the suite sets - by default the
 * session's assurance through a {@link SessionAssurance} the suite supplies, every other reading {@code ABSENT}.
 * Failures are scripted: {@link #failSerialise} pops one {@link RiskStorageException} per subject lock, so a
 * {@link #timeout()} there is the 250 ms budget's breach. Single-threaded by design: the concurrency is the database
 * suites'.
 *
 * <p>The unit of work it is handed is never touched by risk here: every store is in memory. A suite passes
 * {@link #UNTOUCHABLE} to prove the code under test writes nothing of its own on the caller's connection.
 */
public final class RiskEvaluationWorld {

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    public static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** A connection any use of which fails the suite - nothing under test may use the caller's connection itself. */
    public static final Connection UNTOUCHABLE = (Connection) Proxy.newProxyInstance(
            RiskEvaluationWorld.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "toString":
                        return "UNTOUCHABLE";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        throw new AssertionError("the caller's connection was used directly: " + method.getName());
                }
            });

    public final InMemoryPolicyStore policyStore = new InMemoryPolicyStore();
    public final InMemoryWindowStore windowStore = new InMemoryWindowStore();
    public final InMemorySignalStore signalStore = new InMemorySignalStore();
    public final InMemoryCustomerRiskProfileStore profileStore = new InMemoryCustomerRiskProfileStore();
    public final InMemoryRiskAssessmentStore assessmentStore = new InMemoryRiskAssessmentStore();
    public final List<EventEnvelope> envelopes = new ArrayList<>();
    public final List<Map<String, String>> payloads = new ArrayList<>();
    public final List<CaseOpener.Decided> cases = new ArrayList<>();
    public final List<RiskEvaluationObserver.Report> reports = new ArrayList<>();

    /** The alerts as born (`P13-TSK-013`) - the real {@link Alerts} over risk's in-memory store, the folding NONE. */
    public final InMemoryRiskAlertStore alertStore = new InMemoryRiskAlertStore();

    /** Every savepoint T-eval's budget opened, kept or undid, in order: {@code open 250ms}, {@code keep}, {@code undo}. */
    public final List<String> savepoints = new ArrayList<>();

    /** One scripted failure per alert raise, in order - the raise behind its own savepoint. */
    public final Deque<RuntimeException> failRaise = new ArrayDeque<>();

    /** Every raise T-eval reported unraised: the assessment's id and the cause's code. */
    public final List<String> unraised = new ArrayList<>();

    /** One scripted failure per subject lock taken (R2a), in order. */
    public final Deque<RuntimeException> failSerialise = new ArrayDeque<>();

    /** The session's assurance as the suite supplies it; none by default. */
    public SessionAssurance sessions = (unitOfWork, actor) -> Optional.empty();

    /** A screening hit for every party when set. */
    public Optional<Boolean> screeningHit = Optional.empty();

    /** What {@link RiskInputs} answers: the session's assurance from {@link #sessions}, the screening hit, nothing else. */
    public RiskInputs inputs = (unitOfWork, request, judged, now) -> new RiskInputs.Readings(
            reading(request.actor().flatMap(actor -> sessions.of(unitOfWork, actor))),
            reading(screeningHit), Reading.absent(), Reading.absent(), Map.of());

    public final RiskEvaluation evaluation;

    /** The alert machine T-eval raises through - real, over {@link #alertStore}. */
    public final Alerts alerts;

    public RiskEvaluationWorld() {
        EvaluationBudget budget = new EvaluationBudget() {
            @Override
            public Mark open(Connection unitOfWork, Optional<Duration> timeout) {
                savepoints.add("open " + timeout.map(t -> t.toMillis() + "ms").orElse("-"));
                return new Mark(new Object(), timeout.map(t -> "0"));
            }

            @Override
            public void keep(Connection unitOfWork, Mark mark) {
                savepoints.add("keep");
            }

            @Override
            public void undo(Connection unitOfWork, Mark mark) {
                savepoints.add("undo");
            }
        };
        OutboxWriter<Connection> outbox = (unitOfWork, envelope, payload, mediaType) -> {
            envelopes.add(envelope);
            payloads.add(EventPayload.fields(payload));
        };
        AuditWriter<Connection> noAudit = (unitOfWork, record) -> {
            throw new AssertionError("T-eval audits nothing: " + record);
        };
        CustomerRiskProfiles profiles = new CustomerRiskProfiles(profileStore, (unitOfWork, actor) -> Optional.empty(),
                noAudit, IDS, CLOCK);
        RiskInputs delegating = (unitOfWork, request, judged, now) -> inputs.read(unitOfWork, request, judged, now);
        alertStore.now = CLOCK.instant();
        alerts = new Alerts(alertStore, AlertFolding.NONE, (unitOfWork, actor) -> Optional.empty(),
                RiskSubjectParties.NONE, noAudit, outbox, IDS, CLOCK, Spans.NONE, AlertObserver.NONE);
        AlertRaiser raiser = (unitOfWork, raised) -> {
            if (!failRaise.isEmpty()) {
                throw failRaise.pop();
            }
            return alerts.raiseAll(unitOfWork, raised);
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
                new SignalReadings(signalStore), delegating, assessmentStore, budget,
                (unitOfWork, decided) -> cases.add(decided), raiser, outbox, observer,
                Spans.NONE, IDS, CLOCK);
    }

    /**
     * T-mon's profile leg over the same stores (`P13-TSK-014`): the real {@link ProfileRecompute} and
     * {@link ProfileInputs} - the party's signals and its committed windows, no screening, no identity placed - raising
     * through {@link #alerts} as T-eval does, {@link #failRaise} scripted alike.
     */
    public ProfileRecompute profileLeg() {
        ProfileInputs read = new ProfileInputs((unitOfWork, party) -> Optional.empty(), new SignalReadings(signalStore),
                RiskPartyIdentities.NONE, new VelocityWindows(windowStore));
        OutboxWriter<Connection> outbox = (unitOfWork, envelope, payload, mediaType) -> {
            envelopes.add(envelope);
            payloads.add(EventPayload.fields(payload));
        };
        AlertRaiser raiser = (unitOfWork, raised) -> {
            if (!failRaise.isEmpty()) {
                throw failRaise.pop();
            }
            return alerts.raiseAll(unitOfWork, raised);
        };
        return new ProfileRecompute(new RiskPolicies(policyStore), profileStore, read, raiser, outbox, IDS, CLOCK,
                Duration.ofHours(24));
    }

    /** The {@code risk.AlertRaised} events announced. */
    public long alertsAnnounced() {
        return envelopes.stream().filter(e -> e.eventType().equals(Alerts.EVENT_TYPE)).count();
    }

    /** Activates {@code policy} as its operation's version, retiring the one before. */
    public RiskPolicyVersionId activate(RiskPolicy policy) {
        return policyStore.activate(policy);
    }

    /** Activates the C8 v1 fixture of {@code operation}. */
    public RiskPolicyVersionId activate(RiskOperation operation) {
        return activate(RiskPolicyFixtures.v1(operation));
    }

    /** The count of {@code dimension}'s {@code subject} windows of {@code kind}, summed. */
    public long count(VelocityDimension dimension, Object subject, WindowKind kind) {
        return windowStore.rows.values().stream()
                .filter(w -> w.key().dimension() == dimension && w.key().subjectRef().equals(subject.toString())
                        && w.key().windowKind() == kind)
                .mapToLong(VelocityWindow::count).sum();
    }

    /** The {@code risk.RiskDecisionMade} events announced. */
    public long decisionsAnnounced() {
        return envelopes.stream().filter(e -> e.eventType().equals(RiskEvaluation.DECISION_MADE_EVENT)).count();
    }

    /** A timeout as every risk store reports one - SQLSTATE 57014. */
    public static RiskStorageException timeout() {
        return new RiskStorageException("risk storage failed taking a subject's lock: SQLSTATE "
                + RiskStorageException.QUERY_CANCELED);
    }

    public static Money eur(long minor) {
        return Money.ofMinorUnits(minor, RiskPolicyFixtures.EUR);
    }

    private static <T> Reading<T> reading(Optional<T> answered) {
        return answered.<Reading<T>>map(Reading::of).orElseGet(Reading::absent);
    }

    // ------------------------------------------------------------------ the acting session

    /** A live session of {@code identity} at {@code level}, opened from {@code device} (null for none). */
    public static Session session(UUID identity, AssuranceLevel level, String device) {
        Instant now = CLOCK.instant();
        return Session.rehydrate(SessionId.next(IDS), IdentityId.of(identity), Sensitive.of("token-hash"), level,
                SessionStatus.ACTIVE, now.minusSeconds(60), now.plusSeconds(900), now.plusSeconds(3_600), device, null);
    }

    /**
     * Binds a request presenting {@code session} to this thread, as the session interceptor leaves it - closed by the
     * suite, which unbinds it.
     */
    public static AutoCloseable presenting(Session session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION, session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return RequestContextHolder::resetRequestAttributes;
    }

    /** A session store whose live sessions are exactly {@code live}; every other operation fails the suite. */
    @SuppressWarnings("unchecked")
    public static SessionStore<Connection> liveSessions(List<String> asked, Session... live) {
        return (SessionStore<Connection>) Proxy.newProxyInstance(RiskEvaluationWorld.class.getClassLoader(),
                new Class<?>[] {SessionStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findLiveFor")) {
                        asked.add("findLiveFor");
                        return java.util.Arrays.stream(live).filter(s -> s.identityId().equals(args[1])).toList();
                    }
                    if (method.getName().equals("toString")) {
                        return "liveSessions";
                    }
                    throw new AssertionError("the session's assurance only reads the live sessions: " + method.getName());
                });
    }

    // ------------------------------------------------------------------ the stores

    /** The pin's reads only: one ACTIVE version per operation, by activation. */
    public static final class InMemoryPolicyStore implements RiskPolicyStore {
        private final Map<RiskOperation, RiskPolicyVersion> active = new EnumMap<>(RiskOperation.class);
        private final Map<RiskPolicyVersionId, RiskPolicyVersion> versions = new java.util.HashMap<>();
        private int next = 1;

        RiskPolicyVersionId activate(RiskPolicy policy) {
            RiskPolicyVersionId id = RiskPolicyVersionId.next(IDS);
            RiskPolicyVersion version = new RiskPolicyVersion(new VersionRow(id, policy.operation(), next++,
                    policy.engine().name(), RiskPolicyVersionStatus.ACTIVE, "officer-a", CLOCK.instant(),
                    Optional.of("officer-b"), Optional.of(CLOCK.instant()), Optional.of(CLOCK.instant()),
                    Optional.empty()), policy);
            active.put(policy.operation(), version);
            versions.put(id, version);
            return id;
        }

        /** No version active for {@code operation} - the production default. */
        public void retire(RiskOperation operation) {
            active.remove(operation);
        }

        @Override
        public Optional<RiskPolicyVersionId> shareActiveOnce(Connection unitOfWork, RiskOperation operation) {
            return Optional.ofNullable(active.get(operation)).map(RiskPolicyVersion::id);
        }

        @Override
        public void lockOperationShared(Connection unitOfWork, RiskOperation operation) {}

        @Override
        public Optional<RiskPolicyVersion> version(Connection unitOfWork, RiskPolicyVersionId id) {
            return Optional.ofNullable(versions.get(id));
        }

        @Override
        public Optional<RiskPolicyVersionId> active(Connection unitOfWork, RiskOperation operation) {
            return shareActiveOnce(unitOfWork, operation);
        }

        @Override
        public void lockOperation(Connection unitOfWork, RiskOperation operation) {
            throw new UnsupportedOperationException("T-eval never administers a policy");
        }

        @Override
        public int maxVersion(Connection unitOfWork, RiskOperation operation) {
            throw new UnsupportedOperationException("T-eval never administers a policy");
        }

        @Override
        public void insertProposal(Connection unitOfWork, NewProposal proposal) {
            throw new UnsupportedOperationException("T-eval never administers a policy");
        }

        @Override
        public Optional<VersionRow> find(Connection unitOfWork, RiskPolicyVersionId id) {
            return Optional.ofNullable(versions.get(id)).map(RiskPolicyVersion::row);
        }

        @Override
        public Optional<VersionRow> lock(Connection unitOfWork, RiskPolicyVersionId id) {
            throw new UnsupportedOperationException("T-eval never administers a policy");
        }

        @Override
        public Optional<VersionRow> lockActive(Connection unitOfWork, RiskOperation operation) {
            throw new UnsupportedOperationException("T-eval never administers a policy");
        }

        @Override
        public boolean retire(Connection unitOfWork, RiskPolicyVersionId id) {
            throw new UnsupportedOperationException("T-eval never administers a policy");
        }

        @Override
        public Optional<DecisionStamps> decide(Connection unitOfWork, RiskPolicyVersionId id,
                RiskPolicyVersionStatus status, Actor actor, String reason) {
            throw new UnsupportedOperationException("T-eval never administers a policy");
        }

        @Override
        public void appendEvent(Connection unitOfWork, HistoryRow row) {
            throw new UnsupportedOperationException("T-eval never administers a policy");
        }

        @Override
        public Map<RiskOperation, Integer> activeVersions(Connection unitOfWork) {
            Map<RiskOperation, Integer> numbers = new EnumMap<>(RiskOperation.class);
            active.forEach((operation, version) -> numbers.put(operation, version.version()));
            return numbers;
        }
    }

    /** {@code risk V005}'s upsert arithmetic in memory: born at 1, then +1 and + amount; the clock fixed. */
    public final class InMemoryWindowStore implements VelocityWindowStore {
        public final Map<VelocityWindowKey, VelocityWindow> rows = new TreeMap<>();
        public final List<VelocityWindowKey> upserted = new ArrayList<>();

        @Override
        public Instant databaseNow(Connection unitOfWork) {
            return CLOCK.instant();
        }

        @Override
        public void serialise(Connection unitOfWork, int lockKey) {
            if (!failSerialise.isEmpty()) {
                throw failSerialise.pop();
            }
        }

        @Override
        public List<VelocityWindow> find(Connection unitOfWork, Collection<VelocityWindowKey> keys) {
            List<VelocityWindow> found = new ArrayList<>();
            for (VelocityWindowKey key : keys) {
                Optional.ofNullable(rows.get(key)).ifPresent(found::add);
            }
            return found;
        }

        @Override
        public Optional<VelocityWindow> upsert(Connection unitOfWork, VelocityWindowKey key, Money amount) {
            upserted.add(key);
            VelocityWindow row = rows.get(key);
            if (row == null) {
                row = new VelocityWindow(key, 1, amount, CLOCK.instant());
            } else if (!row.sameMoneyAs(amount)) {
                return Optional.empty();
            } else {
                row = new VelocityWindow(key, row.count() + 1, row.sum().plus(amount), CLOCK.instant());
            }
            rows.put(key, row);
            return Optional.of(row);
        }

        @Override
        public List<VelocityWindow> live(Connection unitOfWork) {
            return rows.values().stream().filter(row -> row.key().windowEnd().isAfter(CLOCK.instant())).toList();
        }

        @Override
        public Optional<Instant> firstCounted(Connection unitOfWork, VelocityDimension dimension, String subjectRef) {
            return rows.keySet().stream()
                    .filter(key -> key.dimension() == dimension && key.subjectRef().equals(subjectRef)
                            && key.windowKind() == WindowKind.H1)
                    .map(VelocityWindowKey::windowStart)
                    .min(Comparator.naturalOrder());
        }

        /** A movement of {@code amount} counted for {@code subject} in {@code dimension}'s {@code H1} window at {@code at}. */
        public void countedAt(VelocityDimension dimension, String subject, Instant at, Money amount) {
            VelocityWindowKey key = new VelocityWindowKey(dimension, subject, WindowKind.H1,
                    com.finapp.risk.WindowStarts.start(WindowKind.H1, at));
            rows.put(key, new VelocityWindow(key, 1, amount, at));
        }
    }

    /** Signals in memory - none unless a suite adds one. */
    public static final class InMemorySignalStore implements RiskSignalStore {
        private final List<RiskSignal> rows = new ArrayList<>();

        public void add(RiskSignal signal) {
            rows.add(signal);
        }

        @Override
        public boolean insert(Connection unitOfWork, RiskSignal signal) {
            rows.add(signal);
            return true;
        }

        @Override
        public int countSince(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant since) {
            return (int) of(subject, kind).filter(row -> !row.observedAt().isBefore(since)).count();
        }

        @Override
        public int countByMerchantSince(Connection unitOfWork, UUID merchant, SignalKind kind, Instant since) {
            return (int) rows.stream().filter(row -> row.kind() == kind && !row.observedAt().isBefore(since)
                    && row.attributes().get("merchant_subject_ref").filter(merchant.toString()::equals).isPresent()).count();
        }

        @Override
        public Optional<RiskSignal> latest(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            return of(subject, kind).max(Comparator.comparing(RiskSignal::observedAt));
        }

        @Override
        public Optional<Instant> firstObservedAt(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            return of(subject, kind).map(RiskSignal::observedAt).min(Comparator.naturalOrder());
        }

        @Override
        public boolean seen(Connection unitOfWork, SignalSubject subject) {
            return rows.stream().anyMatch(row -> row.subject().equals(subject));
        }

        private java.util.stream.Stream<RiskSignal> of(SignalSubject subject, SignalKind kind) {
            return rows.stream().filter(row -> row.subject().equals(subject) && row.kind() == kind);
        }
    }
}
