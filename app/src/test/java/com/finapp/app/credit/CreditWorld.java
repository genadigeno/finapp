package com.finapp.app.credit;

import com.finapp.credit.AttributeProvenance;
import com.finapp.credit.AttributeValue;
import com.finapp.credit.CreditActingParty;
import com.finapp.credit.CreditAssessments;
import com.finapp.credit.CreditAttribute;
import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditBureau;
import com.finapp.credit.CreditConsentGate;
import com.finapp.credit.CreditDataAnswer;
import com.finapp.credit.CreditDataCollection;
import com.finapp.credit.CreditDataObserver;
import com.finapp.credit.CreditDataPull;
import com.finapp.credit.CreditEvidence;
import com.finapp.credit.CreditEvidenceCipher;
import com.finapp.credit.CreditPartyStanding;
import com.finapp.credit.CreditPolicy;
import com.finapp.credit.CreditPolicyAdministration;
import com.finapp.credit.CreditPolicyStatus;
import com.finapp.credit.CreditPolicyVersionId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.Decider;
import com.finapp.credit.DecisionMaking;
import com.finapp.credit.DecisionObserver;
import com.finapp.credit.DecisionProgress;
import com.finapp.credit.DecisionRequest;
import com.finapp.credit.DecisionRequestId;
import com.finapp.credit.EngineVersions;
import com.finapp.credit.FinancialDataProvider;
import com.finapp.credit.JdbcCreditAssessmentStore;
import com.finapp.credit.JdbcCreditDataRequestStore;
import com.finapp.credit.JdbcCreditDecisions;
import com.finapp.credit.JdbcCreditPolicyStore;
import com.finapp.credit.JdbcCreditProfiles;
import com.finapp.credit.JdbcDecisionRequestStore;
import com.finapp.credit.JdbcDecisionSnapshotStore;
import com.finapp.credit.JdbcPolicyEvaluationStore;
import com.finapp.credit.JdbcReservedExposure;
import com.finapp.credit.JdbcScorecardStore;
import com.finapp.credit.JdbcUnderwritingCaseStore;
import com.finapp.credit.PlatformCreditExposure;
import com.finapp.credit.PolicyEvaluations;
import com.finapp.credit.PolicyFigure;
import com.finapp.credit.ScorecardAdministration;
import com.finapp.credit.ScorecardModelVersionId;
import com.finapp.credit.ScorecardStatus;
import com.finapp.credit.SnapshotFreezer;
import com.finapp.credit.TransactionRunner;
import com.finapp.credit.UnavailableFallback;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A credit world for the decision suites (`P10-TSK-016`): credit's real progress and deciding transaction over a real
 * database, the ports at its edge - the sources, the consent gate, the party's standing - test doubles each case steers.
 * Each {@link #progress} or {@link #deciding} call stands for an instance of its own.
 */
final class CreditWorld {

    static final Clock CLOCK = Clock.systemUTC();
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    static final CreditPolicyVersionId LOAN_SEED =
            CreditPolicyVersionId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-00000000d001"));
    static final CreditPolicyVersionId LINE_SEED =
            CreditPolicyVersionId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-00000000d002"));
    static final ScorecardModelVersionId SCORECARD_SEED =
            ScorecardModelVersionId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-00000000c001"));
    static final CreditDataCollection.Timing TIMING =
            new CreditDataCollection.Timing(Duration.ofMinutes(10), Duration.ofMinutes(30));

    static final TransactionRunner TRANSACTIONS = new TransactionRunner() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
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
    };

    static final JdbcCreditPolicyStore POLICIES = new JdbcCreditPolicyStore();
    static final JdbcScorecardStore SCORECARDS = new JdbcScorecardStore();
    static final JdbcDecisionRequestStore REQUESTS = new JdbcDecisionRequestStore();
    static final CreditPolicyAdministration POLICY_ADMINISTRATION =
            new CreditPolicyAdministration(POLICIES, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
    static final ScorecardAdministration SCORECARD_ADMINISTRATION =
            new ScorecardAdministration(SCORECARDS, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);

    /** The evidence cipher the world's collection encrypts under - a zero key unless a case installs the application's. */
    static volatile CreditEvidenceCipher cipher = new CreditEvidenceCipher(new byte[32], 1, new SecureRandom());

    static final Set<UUID> SUSPENDED = ConcurrentHashMap.newKeySet();
    static final Map<UUID, Set<CreditSourceKind>> WITHDRAWN = new ConcurrentHashMap<>();
    /** A party's bureau total balance, in minor units; 4,200.50 unless a case says otherwise. */
    static final Map<UUID, Long> BALANCES = new ConcurrentHashMap<>();
    /**
     * The parties whose bureau total balance is reported in a currency not the product's - frozen {@code ABSENT} with
     * {@code CURRENCY_NOT_SUPPORTED}, never converted (INV-CRD-12) - the rest of the file present and in euros.
     */
    static final Set<UUID> NO_BALANCE = ConcurrentHashMap.newKeySet();
    /** How long before its pull a party's bureau answer says it was retrieved; a minute unless a case says otherwise. */
    static final Map<UUID, Duration> RETRIEVED_AGO = new ConcurrentHashMap<>();
    /**
     * A party's outstanding platform credit, in minor units, when a case gives one (the Phase 10 to 11 transition: a
     * consumption's exposure moving between an evaluation and its decision); otherwise Phase 10's zero.
     */
    static final Map<UUID, Long> OUTSTANDING = new ConcurrentHashMap<>();
    /** Each test actor's party - a fresh one per actor id unless a case seats an actor in a party of its own choosing. */
    static final Map<String, UUID> ACTOR_PARTIES = new ConcurrentHashMap<>();

    /** The acting person's party (the transition's self-dealing guard): {@link #ACTOR_PARTIES}, minted on first use. */
    static final CreditActingParty<Connection> ACTING_PARTIES =
            (uow, actor) -> Optional.of(ACTOR_PARTIES.computeIfAbsent(actor.id(), id -> UUID.randomUUID()));

    /** Phase 10's zero - or {@link #OUTSTANDING}'s figure, read afresh on every call - under {@code NoLoansUntilPhase11}'s version. */
    static final PlatformCreditExposure<Connection> PLATFORM_EXPOSURE = new PlatformCreditExposure<>() {
        private final NoLoansUntilPhase11 phase10 = new NoLoansUntilPhase11();

        @Override
        public int version() {
            return phase10.version();
        }

        @Override
        public Money outstandingFor(Connection unitOfWork, UUID partyId, CurrencyCode currency) {
            Long outstanding = OUTSTANDING.get(partyId);
            return outstanding == null ? phase10.outstandingFor(unitOfWork, partyId, currency)
                    : Money.ofMinorUnits(outstanding, currency);
        }
    };

    static final CreditConsentGate<Connection> GATE =
            (uow, party, kind) -> !WITHDRAWN.getOrDefault(party, Set.of()).contains(kind);

    static final CreditPartyStanding<Connection> STANDING = new CreditPartyStanding<>() {
        @Override
        public PartyFacts facts(Connection unitOfWork, UUID partyId) {
            return new PartyFacts(Optional.empty(), Optional.empty(), 1);
        }

        @Override
        public boolean inGoodStanding(Connection unitOfWork, UUID partyId) {
            return !SUSPENDED.contains(partyId);
        }
    };

    static final CreditBureau BUREAU = new CreditBureau() {
        @Override
        public String code() {
            return "bureau-test";
        }

        @Override
        public CreditDataAnswer pull(CreditDataPull request) {
            UUID party = UUID.fromString(request.subjectReference());
            AttributeProvenance provenance = new AttributeProvenance.Provider(CreditSourceKind.BUREAU, code(), 1);
            return received(code(), RETRIEVED_AGO.getOrDefault(party, Duration.ofSeconds(60)), List.of(
                    new CreditAttribute(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, new AttributeValue.IntegerValue(740), provenance),
                    new CreditAttribute(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS, new AttributeValue.IntegerValue(3), provenance),
                    new CreditAttribute(CreditAttributeCode.BUREAU_DELINQUENCIES_24M, new AttributeValue.IntegerValue(0), provenance),
                    new CreditAttribute(CreditAttributeCode.BUREAU_DEFAULTS_72M, new AttributeValue.IntegerValue(0), provenance),
                    new CreditAttribute(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG, new AttributeValue.BooleanValue(false), provenance),
                    new CreditAttribute(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS,
                            new AttributeValue.MoneyValue(Money.ofMinorUnits(35_000, EUR)), provenance),
                    new CreditAttribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE, NO_BALANCE.contains(party)
                            ? new AttributeValue.MoneyValue(Money.ofMinorUnits(420_050L, CurrencyCode.of("USD")))
                            : new AttributeValue.MoneyValue(Money.ofMinorUnits(BALANCES.getOrDefault(party, 420_050L), EUR)),
                            provenance)));
        }
    };

    static final FinancialDataProvider FINDATA = new FinancialDataProvider() {
        @Override
        public String code() {
            return "findata-test";
        }

        @Override
        public CreditDataAnswer pull(CreditDataPull request) {
            AttributeProvenance provenance = new AttributeProvenance.Provider(CreditSourceKind.FINANCIAL_DATA, code(), 1);
            return received(code(), List.of(
                    new CreditAttribute(CreditAttributeCode.FINDATA_MONTHLY_INCOME,
                            new AttributeValue.MoneyValue(Money.ofMinorUnits(330_000, EUR)), provenance),
                    new CreditAttribute(CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE,
                            new AttributeValue.MoneyValue(Money.ofMinorUnits(120_000, EUR)), provenance)));
        }
    };

    private CreditWorld() {}

    private static CreditDataAnswer received(String provider, List<CreditAttribute> attributes) {
        return received(provider, Duration.ofSeconds(60), attributes);
    }

    private static CreditDataAnswer received(String provider, Duration ago, List<CreditAttribute> attributes) {
        return new CreditDataAnswer.Received(provider, 1, Instant.now().minus(ago), attributes,
                new CreditEvidence("{\"answer\":\"test\"}".getBytes(StandardCharsets.UTF_8)));
    }

    static SnapshotFreezer freezer() {
        return freezer(collection());
    }

    /** The freezer over {@code collection} - a case's own sources (`P10-TSK-021`). */
    static SnapshotFreezer freezer(CreditDataCollection collection) {
        return new SnapshotFreezer(new JdbcDecisionSnapshotStore(), collection, STANDING, new NotAssessedUntilPhase13(),
                new JdbcReservedExposure(), PLATFORM_EXPOSURE, IDS);
    }

    static CreditDataCollection collection() {
        return collection(CreditDataCollection.Sources.of(BUREAU, TIMING, FINDATA, TIMING));
    }

    /** A collection over {@code sources} - a case's own providers and their order (`P10-TSK-021`). */
    static CreditDataCollection collection(CreditDataCollection.Sources sources) {
        return new CreditDataCollection(new JdbcCreditDataRequestStore(), sources, GATE,
                cipher, CreditDataObserver.NONE, new JdbcAuditWriter(), new JdbcOutboxWriter(), TRANSACTIONS, IDS, CLOCK);
    }

    /** An instance's deciding transaction, on {@code clock}. */
    static DecisionMaking deciding(Clock clock) {
        return deciding(clock, collection());
    }

    /** An instance's deciding transaction, on {@code clock}, freezing over {@code collection} - the one constructor call. */
    static DecisionMaking deciding(Clock clock, CreditDataCollection collection) {
        return new DecisionMaking(TRANSACTIONS, REQUESTS, new JdbcCreditProfiles(IDS), POLICIES, SCORECARDS, freezer(collection),
                new CreditAssessments(new JdbcCreditAssessmentStore(), SCORECARDS, new JdbcOutboxWriter(), IDS, clock),
                new JdbcCreditAssessmentStore(),
                new PolicyEvaluations(new JdbcPolicyEvaluationStore(), POLICIES, EngineVersions.STANDARD, IDS, clock),
                new JdbcPolicyEvaluationStore(), new JdbcCreditDecisions(), new JdbcUnderwritingCaseStore(), STANDING, GATE,
                new JdbcOutboxWriter(), new JdbcAuditWriter(), DecisionObserver.NONE, IDS, clock);
    }

    static DecisionMaking deciding() {
        return deciding(CLOCK);
    }

    /** An instance's progress on {@code clock}, handing an evaluated request to {@code decider}. */
    static DecisionProgress progress(Clock clock, Decider decider) {
        return progress(clock, decider, collection());
    }

    /** An instance's progress over {@code collection} - the one constructor call (`P10-TSK-021`). */
    static DecisionProgress progress(Clock clock, Decider decider, CreditDataCollection collection) {
        return new DecisionProgress(TRANSACTIONS, REQUESTS, POLICIES, SCORECARDS, new JdbcDecisionSnapshotStore(),
                collection, freezer(collection),
                new CreditAssessments(new JdbcCreditAssessmentStore(), SCORECARDS, new JdbcOutboxWriter(), IDS, clock),
                new PolicyEvaluations(new JdbcPolicyEvaluationStore(), POLICIES, EngineVersions.STANDARD, IDS, clock),
                STANDING, GATE, new JdbcOutboxWriter(), IDS, clock, decider, new JdbcUnderwritingCaseStore());
    }

    /** A progress that stops at {@code EVALUATED} - the "crash" before the deciding step. */
    static DecisionProgress progressToEvaluation() {
        return progress(CLOCK, (id, correlation) -> Decider.Decided.NOTHING);
    }

    @SuppressWarnings("try")
    static DecisionProgress.Step step(DecisionProgress progress, UUID id) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            return progress.step(DecisionRequestId.of(id), CorrelationId.generate(IDS));
        }
    }

    @SuppressWarnings("try")
    static Decider.Decided decide(Decider decider, UUID id) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            return decider.decide(DecisionRequestId.of(id), CorrelationId.generate(IDS));
        }
    }

    /** {@code party}'s request for {@code product} - born SUBMITTED through the store, valid for {@code validity}. */
    static UUID request(UUID party, CreditProduct product, Money amount, Duration validity) {
        return TRANSACTIONS.inTransaction(uow -> {
            var profile = new JdbcCreditProfiles(IDS).ensure(uow, party);
            DecisionRequestId id = DecisionRequestId.next(IDS);
            REQUESTS.insert(uow, id, party, profile.id(), new DecisionRequest.Application(product, amount,
                    product.revolving() ? Optional.empty() : Optional.of(36), Optional.empty(), Optional.empty()), validity,
                    "decision-case", new Actor(party.toString(), ActorType.CUSTOMER));
            return id.value();
        });
    }

    /** A request driven to EVALUATED by a progress that never decides. */
    static UUID evaluated(UUID party, CreditProduct product, Money amount, Duration validity) {
        UUID id = request(party, product, amount, validity);
        DecisionProgress progress = progressToEvaluation();
        for (int i = 0; i < 6 && !"EVALUATED".equals(scalar("SELECT status FROM credit.decision_request WHERE id = ?", id)); i++) {
            step(progress, id);
        }
        String status = scalar("SELECT status FROM credit.decision_request WHERE id = ?", id);
        if (!"EVALUATED".equals(status)) {
            throw new IllegalStateException("the request stopped at " + status);
        }
        return id;
    }

    static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    /** Brings both products' seeds and the scorecard's into force, if a person has not already. */
    static void seedsInForce() {
        Actor officer = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
        TRANSACTIONS.inTransaction(uow -> {
            for (CreditPolicyVersionId seed : List.of(LOAN_SEED, LINE_SEED)) {
                if (POLICIES.policy(uow, seed).orElseThrow().row().status() == CreditPolicyStatus.PROPOSED) {
                    POLICY_ADMINISTRATION.approve(uow, seed, officer, "v1 reviewed", CorrelationId.generate(IDS));
                }
            }
            if (SCORECARDS.model(uow, SCORECARD_SEED).orElseThrow().row().status() == ScorecardStatus.PROPOSED) {
                SCORECARD_ADMINISTRATION.approve(uow, SCORECARD_SEED, officer, "v1 reviewed", CorrelationId.generate(IDS));
            }
            return null;
        });
    }

    /**
     * {@code product}'s version in force: its seed's rules - without the three score rules unless {@code scoreRules} - and
     * stress rate {@code rate}, activated by two persons.
     */
    static CreditPolicyVersionId inForce(CreditProduct product, int rate, boolean scoreRules) {
        CreditPolicyVersionId seed = product == CreditProduct.PERSONAL_LOAN ? LOAN_SEED : LINE_SEED;
        return TRANSACTIONS.inTransaction(uow -> {
            CreditPolicy v1 = POLICIES.policy(uow, seed).orElseThrow().policy();
            List<CreditPolicy.PolicyRule> rules = new ArrayList<>(v1.rules());
            if (!scoreRules) {
                rules.removeIf(rule -> rule.subject() instanceof CreditPolicy.Subject.Figure figure
                        && figure.figure() == PolicyFigure.SCORE);
            }
            CreditPolicy policy = new CreditPolicy(v1.product(), rate, v1.minimumDisposable(), v1.minimumPaymentRatioBps(),
                    v1.maximumExposure(), v1.maximumDataAge(), UnavailableFallback.REFER, v1.autoApprovalCeiling(), rules);
            CreditPolicyAdministration.Proposed proposed = POLICY_ADMINISTRATION.propose(uow, policy, "a case's version",
                    new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE), CorrelationId.generate(IDS));
            POLICY_ADMINISTRATION.approve(uow, proposed.id(), new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE),
                    "a case's activation", CorrelationId.generate(IDS));
            return proposed.id();
        });
    }

    /** A new scorecard version in force - the seed's table again, activated by two persons. */
    static ScorecardModelVersionId scorecardInForce() {
        return TRANSACTIONS.inTransaction(uow -> {
            var table = SCORECARDS.model(uow, SCORECARD_SEED).orElseThrow().scorecard();
            ScorecardAdministration.Proposed proposed = SCORECARD_ADMINISTRATION.propose(uow,
                    com.finapp.credit.ScorecardFamily.RETAIL_SCORECARD, table, "a case's version",
                    new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE), CorrelationId.generate(IDS));
            SCORECARD_ADMINISTRATION.approve(uow, proposed.id(), new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE),
                    "a case's activation", CorrelationId.generate(IDS));
            return proposed.id();
        });
    }

    /** Waits until the database itself says {@code booleanQuery}, never the JVM's clock (`P10-TSK-015`'s finding). */
    static void awaitDatabase(String booleanQuery, Object... parameters) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!"t".equals(scalar(booleanQuery, parameters)) && !"true".equals(scalar(booleanQuery, parameters))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the database never said: " + booleanQuery);
            }
            Thread.sleep(50);
        }
    }

    static long count(String sql, Object... parameters) {
        return Long.parseLong(scalar(sql, parameters));
    }

    static String scalar(String sql, Object... parameters) {
        try (Connection owner = DatabaseRoles.migrator(); PreparedStatement select = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static void execute(String sql, Object... parameters) {
        try (Connection app = DatabaseRoles.application(); PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
