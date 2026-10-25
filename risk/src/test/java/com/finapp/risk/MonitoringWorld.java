package com.finapp.risk;

import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.testing.InMemoryCustomerRiskProfileStore;
import com.finapp.risk.testing.InMemoryMonitoredMovementStore;
import com.finapp.risk.testing.InMemoryRiskAlertStore;
import com.finapp.risk.testing.InMemoryRiskCaseStore;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * T-mon's rule leg over fakes for the hermetic suites (`P13-TSK-020`): the real {@link MonitoringRun},
 * {@link ProfileRecompute}, {@link ProfileInputs} and {@link MonitoredMovements}, the real {@link Alerts} folding into the
 * real {@link Cases} - over in-memory stores: the monitored transactions ({@link InMemoryMonitoredMovementStore}), the
 * velocity windows ({@link FakeVelocityWindowStore}), the profile, the alerts and the cases; the account → party join a
 * map; the meters recorded. The leg runs as the platform ({@link #judge}), as the schedule runs it.
 */
final class MonitoringWorld {

    static final Connection UOW = StandInConnections.unused();
    static final CurrencyCode EUR = RiskPolicyFixtures.EUR;
    static final CurrencyCode USD = CurrencyCode.of("USD");
    /** The database's instant - "today" for the profile's own recompute is 2026-10-23. */
    static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    static final LocalDate TODAY = LocalDate.parse("2026-10-23");
    static final Clock CLOCK = EvaluationWorld.CLOCK;

    final InMemoryMonitoredMovementStore movements = new InMemoryMonitoredMovementStore();
    final FakeVelocityWindowStore velocity = new FakeVelocityWindowStore(NOW);
    final InMemoryCustomerRiskProfileStore profiles = new InMemoryCustomerRiskProfileStore();
    final InMemoryRiskAlertStore alertStore = new InMemoryRiskAlertStore();
    final InMemoryRiskCaseStore caseStore = new InMemoryRiskCaseStore();
    final CustomerRiskProfileTest.PolicyStore policies = new CustomerRiskProfileTest.PolicyStore();
    final Map<UUID, UUID> partyOfAccount = new HashMap<>();
    final Map<UUID, List<UUID>> accountsOfParty = new HashMap<>();
    final Map<UUID, Instant> openedAt = new HashMap<>();
    final List<EventEnvelope> events = new ArrayList<>();
    final Map<MonitoringObserver.SubjectOutcome, Integer> outcomes = new EnumMap<>(MonitoringObserver.SubjectOutcome.class);
    final List<Class<? extends Throwable>> failures = new ArrayList<>();
    final Set<RuleKind> hits = EnumSet.noneOf(RuleKind.class);
    final List<Duration> latencies = new ArrayList<>();
    int unplaced;
    final List<String> legLog = new ArrayList<>();

    /** A party whose leg throws - the poison the run must survive. */
    UUID poisoned;

    final MonitoredParties parties = new MonitoredParties() {
        @Override
        public Optional<UUID> partyOfAccount(Connection unitOfWork, UUID customerAccountId) {
            return Optional.ofNullable(partyOfAccount.get(customerAccountId));
        }

        @Override
        public Accounts accountsOf(Connection unitOfWork, UUID partyId) {
            if (partyId.equals(poisoned)) {
                throw new IllegalStateException("the accounts could not be read");
            }
            List<UUID> ids = accountsOfParty.getOrDefault(partyId, List.of());
            return Accounts.of(ids, ids.stream().map(openedAt::get).toList());
        }
    };

    final MonitoringObserver observer = new MonitoringObserver() {
        @Override
        public void subject(SubjectOutcome outcome) {
            outcomes.merge(outcome, 1, Integer::sum);
        }

        @Override
        public void subjectFailed(Class<? extends Throwable> cause) {
            failures.add(cause);
        }

        @Override
        public void unplaced(int accounts) {
            unplaced += accounts;
        }

        @Override
        public void judged(Duration latency) {
            latencies.add(latency);
        }

        @Override
        public void fired(Set<RuleKind> fired) {
            hits.addAll(fired);
        }
    };

    final Cases cases;
    final Alerts alerts;
    final ProfileRecompute recompute;
    final MonitoringRun run;

    MonitoringWorld() {
        OutboxWriter<Connection> outbox = (unitOfWork, envelope, payload, mediaType) -> events.add(envelope);
        RiskActingParty acting = (unitOfWork, actor) -> Optional.empty();
        RiskSubjectParties subjects = MonitoringWorld::placedAsProduction;
        cases = new Cases(caseStore, alertStore, acting, subjects, (unitOfWork, record) -> { }, outbox,
                EvaluationWorld.IDS, CLOCK, CaseObserver.NONE);
        alerts = new Alerts(alertStore, cases::fold, acting, subjects, (unitOfWork, record) -> { }, outbox,
                EvaluationWorld.IDS, CLOCK, Spans.NONE, AlertObserver.NONE);
        VelocityWindows windows = new VelocityWindows(velocity);
        ProfileInputs inputs = new ProfileInputs(new CustomerRiskProfileTest.Screening(),
                new SignalReadings(new SignalReadingsTest.InMemoryStore()), RiskPartyIdentities.NONE, windows,
                new MonitoredMovements(movements, parties, windows));
        recompute = new ProfileRecompute(new RiskPolicies(policies), profiles, inputs, alerts::raiseAll, outbox,
                EvaluationWorld.IDS, CLOCK, Duration.ofDays(1), observer);
        run = new MonitoringRun(new RiskPolicies(policies), parties, recompute, EvaluationWorld.IDS, CLOCK, Spans.NONE,
                observer, new MonitoringRun.Isolation() {
                    @Override
                    public <T> T alone(Connection unitOfWork, Supplier<T> work) {
                        legLog.add("savepoint");
                        try {
                            T done = work.get();
                            legLog.add("released");
                            return done;
                        } catch (RuntimeException failure) {
                            legLog.add("rolled back");
                            throw failure;
                        }
                    }
                });
    }

    /** The production join's shape (`IdentitySubjectParties`): a {@code PARTY} is itself, nothing else here placed. */
    static Optional<UUID> placedAsProduction(Connection unitOfWork, SignalSubject subject) {
        return subject.kind() == SubjectKind.PARTY ? Optional.of(UUID.fromString(subject.ref())) : Optional.empty();
    }

    /** The monitoring v1 fixture active. */
    RiskPolicyVersionId monitoringActive() {
        return policies.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
    }

    /** {@code policy} active. */
    RiskPolicyVersionId active(RiskPolicy policy) {
        return policies.activate(policy);
    }

    // ------------------------------------------------------------------ parties and accounts

    /** A fresh party holding one customer account opened long ago; the account. */
    UUID accountOfNewParty() {
        return accountOf(EvaluationWorld.IDS.next(), Instant.parse("2025-01-01T09:00:00Z"));
    }

    /** A new customer account of {@code party}, opened at {@code opened}. */
    UUID accountOf(UUID party, Instant opened) {
        UUID account = EvaluationWorld.IDS.next();
        partyOfAccount.put(account, party);
        accountsOfParty.computeIfAbsent(party, p -> new ArrayList<>()).add(account);
        openedAt.put(account, opened);
        return account;
    }

    UUID partyOf(UUID account) {
        return partyOfAccount.get(account);
    }

    // ------------------------------------------------------------------ movements

    private long sequence = 1_000;

    /** A transfer of {@code minor} EUR from one customer account to another on {@code day}: the payer's row. */
    MonitoredTransaction transfer(UUID from, UUID to, long minor, LocalDate day) {
        return row(MonitoredKind.TRANSFER, from, MonitoredDirection.OUT, MonitoredCounterpartyKind.CUSTOMER,
                Optional.of(to), minor, EUR, day);
    }

    /** Money arriving on {@code account} from outside (a loan disbursement) on {@code day}. */
    MonitoredTransaction inflow(UUID account, long minor, LocalDate day) {
        return row(MonitoredKind.LOAN_DISBURSEMENT, account, MonitoredDirection.IN,
                MonitoredCounterpartyKind.LOAN, Optional.of(EvaluationWorld.IDS.next()), minor, EUR, day);
    }

    /** Money leaving {@code account} for outside (a withdrawal) on {@code day}. */
    MonitoredTransaction withdrawal(UUID account, long minor, LocalDate day) {
        return row(MonitoredKind.WITHDRAWAL, account, MonitoredDirection.OUT, MonitoredCounterpartyKind.OPERATIONAL,
                Optional.empty(), minor, EUR, day);
    }

    /** A withdrawal in another currency. */
    MonitoredTransaction withdrawal(UUID account, long minor, CurrencyCode currency, LocalDate day) {
        return row(MonitoredKind.WITHDRAWAL, account, MonitoredDirection.OUT, MonitoredCounterpartyKind.OPERATIONAL,
                Optional.empty(), minor, currency, day);
    }

    MonitoredTransaction row(MonitoredKind kind, UUID subject, MonitoredDirection direction,
            MonitoredCounterpartyKind counterpartyKind, Optional<UUID> counterparty, long minor, CurrencyCode currency,
            LocalDate day) {
        boolean reversal = kind == MonitoredKind.REVERSAL;
        return new MonitoredTransaction(MonitoredTransactionId.next(EvaluationWorld.IDS), EvaluationWorld.IDS.next(),
                sequence++, reversal ? MonitoredEntry.EntryType.REVERSAL
                        : kind == MonitoredKind.ADJUSTMENT ? MonitoredEntry.EntryType.ADJUSTMENT
                        : MonitoredEntry.EntryType.POSTING,
                kind.namespaces().isEmpty() ? "ledger:" : kind.namespaces().getFirst(), kind,
                Optional.of(MonitoredSubjectKind.ACCOUNT), Optional.of(subject), Optional.of(direction),
                counterpartyKind, counterparty, Optional.empty(), minor, currency.minorUnits(), currency, day,
                day.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC),
                reversal ? Optional.of(EvaluationWorld.IDS.next()) : Optional.empty());
    }

    /** {@code rows} recorded and judged by the run, as the platform - one claim's batch. */
    MonitoringRun.Judged judge(MonitoredTransaction... rows) {
        List<MonitoredTransaction> batch = List.of(rows);
        movements.rows.addAll(batch);
        return asSystem(() -> run.judge(UOW, batch));
    }

    /** {@code rows} judged again - a reprocessing: nothing recorded anew. */
    MonitoringRun.Judged rejudge(List<MonitoredTransaction> rows) {
        return asSystem(() -> run.judge(UOW, rows));
    }

    /** A committed {@code PARTY} velocity window of {@code party}: {@code count} movements summing {@code sumMinor}. */
    void partyWindow(UUID party, WindowKind kind, LocalDate day, long count, long sumMinor, CurrencyCode currency) {
        VelocityWindowKey key = new VelocityWindowKey(VelocityDimension.PARTY, party.toString(), kind,
                WindowStarts.start(kind, day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        velocity.rows.put(key, new VelocityWindow(key, count, Money.ofMinorUnits(sumMinor, currency), NOW));
    }

    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    static <T> T asSystem(Supplier<T> work) {
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            return work.get();
        }
    }

    // ------------------------------------------------------------------ reading the outcome

    /** The AML alerts raised. */
    List<RiskAlert> amlAlerts() {
        return alertStore.all().stream().filter(alert -> alert.kind() == AlertKind.AML).toList();
    }

    /** The AML alerts of {@code party} by rule. */
    List<RiskAlert> amlAlerts(UUID party, RuleKind rule) {
        return amlAlerts().stream().filter(alert -> alert.subject().equals(SignalSubject.of(SubjectKind.PARTY, party)))
                .filter(alert -> alert.rule().code().equals(rule.name())).toList();
    }

    /** The cases of {@code party} of {@code kind}. */
    List<RiskCase> cases(UUID party, CaseKind kind) {
        return caseStore.all().stream().filter(c -> c.kind() == kind)
                .filter(c -> c.subject().equals(SignalSubject.of(SubjectKind.PARTY, party))).toList();
    }

    int outcome(MonitoringObserver.SubjectOutcome outcome) {
        return outcomes.getOrDefault(outcome, 0);
    }

    /** A monitoring policy of exactly {@code rules}, with the fixture's rating bands. */
    static RiskPolicy monitoringOf(RiskPolicyFixtures.Rules rules) {
        return new RiskPolicy(RiskOperation.MONITORING, RiskEngine.RISK_ENGINE_V1, EUR, rules.list(), List.of(),
                List.of(new RatingBand(0, 299, RiskRating.LOW), new RatingBand(300, 699, RiskRating.MEDIUM),
                        new RatingBand(700, 1000, RiskRating.HIGH)),
                List.of(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
