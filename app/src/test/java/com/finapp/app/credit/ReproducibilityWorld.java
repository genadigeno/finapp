package com.finapp.app.credit;

import com.finapp.app.credit.ReproducibilityApplicants.Answer;
import com.finapp.app.credit.ReproducibilityApplicants.Applicant;
import com.finapp.credit.AttributeProvenance;
import com.finapp.credit.AttributeValue;
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
import com.finapp.credit.CreditPartyStanding;
import com.finapp.credit.CreditPolicy;
import com.finapp.credit.CreditPolicy.Operand;
import com.finapp.credit.CreditPolicy.PolicyRule;
import com.finapp.credit.CreditPolicy.Subject;
import com.finapp.credit.CreditPolicyAdministration;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditRiskSignal;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.Decider;
import com.finapp.credit.DecisionMaking;
import com.finapp.credit.DecisionObserver;
import com.finapp.credit.DecisionOutcome;
import com.finapp.credit.DecisionProgress;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.DecisionRequest;
import com.finapp.credit.DecisionRequestId;
import com.finapp.credit.EngineVersions;
import com.finapp.credit.FinancialDataProvider;
import com.finapp.credit.JdbcCreditAssessmentStore;
import com.finapp.credit.JdbcCreditDataRequestStore;
import com.finapp.credit.JdbcCreditDecisions;
import com.finapp.credit.JdbcCreditProfiles;
import com.finapp.credit.JdbcDecisionSnapshotStore;
import com.finapp.credit.JdbcPolicyEvaluationStore;
import com.finapp.credit.JdbcReservedExposure;
import com.finapp.credit.JdbcUnderwritingCaseStore;
import com.finapp.credit.PlatformCreditExposure;
import com.finapp.credit.PolicyEffect;
import com.finapp.credit.PolicyEvaluations;
import com.finapp.credit.PolicyFigure;
import com.finapp.credit.PolicyOperator;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.Scorecard;
import com.finapp.credit.ScorecardAdministration;
import com.finapp.credit.ScorecardFamily;
import com.finapp.credit.SnapshotFreezer;
import com.finapp.credit.TransactionRunner;
import com.finapp.credit.UnavailableFallback;
import com.finapp.credit.UnderwritingCaseId;
import com.finapp.credit.UnderwritingCaseStatus;
import com.finapp.credit.UnderwritingCases;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The battery's credit world (`P10-TST-002`): credit's real progress, deciding transaction and review cases over a real
 * database, every port at its edge a double reading the seeded applicant ({@link ReproducibilityApplicants}) - the
 * bureau, the financial-data provider, the party's facts, the risk seam and the platform's outstanding credit - so ten
 * thousand applicants travel every path the engine has, through production's code alone.
 *
 * <p><strong>Three epochs.</strong> Each brings its own policies for both products and its own scorecard into force -
 * the seeds and scorecard 1; the battery's coverage policies (every operator, every effect, every adverse reason code
 * on a rule, an unavailable fallback that declines) and scorecard 2; a third pair (a fallback that refers, a line
 * reading the bureau alone) and scorecard 3 - and a cohort of each epoch's requests is pinned or evaluated before the
 * next activation and decided after it.
 *
 * <p><strong>Workers.</strong> Applicants are driven by eight threads, each party wholly by one - the pipeline is
 * production's multi-instance one, so nothing here coordinates them; activations happen only between epochs, when no
 * worker runs. A pooled {@link TransactionRunner} stands for production's connection pool.
 */
final class ReproducibilityWorld {

    static final CurrencyCode USD = CurrencyCode.of("USD");

    /** Each epoch's maximum exposure per product, in minor units - {@code [epoch - 1][product ordinal]}. */
    static final long[][] MAXIMUM_EXPOSURE = {{4_000_000, 2_000_000}, {3_500_000, 1_500_000}, {4_500_000, 1_800_000}};

    /** The decisions carried across each activation: pinned or evaluated before it, decided after. */
    static final int CARRIED = 150;

    static final int WORKERS = 8;

    /** A short collection window, so an unavailable source is past its deadline within seconds. */
    static final CreditDataCollection.Timing TIMING =
            new CreditDataCollection.Timing(Duration.ofSeconds(1), Duration.ofSeconds(2));

    static final Map<UUID, Applicant> APPLICANTS = new ConcurrentHashMap<>();

    static final PooledTransactions TRANSACTIONS = new PooledTransactions();

    static final CreditConsentGate<Connection> GATE = (uow, party, kind) -> true;

    static final CreditPartyStanding<Connection> STANDING = new CreditPartyStanding<>() {
        @Override
        public PartyFacts facts(Connection unitOfWork, UUID partyId) {
            Applicant applicant = applicant(partyId);
            return new PartyFacts(applicant.ageYears(), applicant.residence().map(CountryCode::of), 1);
        }

        @Override
        public boolean inGoodStanding(Connection unitOfWork, UUID partyId) {
            return true;
        }
    };

    static final CreditRiskSignal<Connection> RISK =
            (uow, party) -> new CreditRiskSignal.RiskSignal(applicant(party).riskSignal(), 1);

    static final PlatformCreditExposure<Connection> PLATFORM = new PlatformCreditExposure<>() {
        @Override
        public int version() {
            return 1;
        }

        @Override
        public Money outstandingFor(Connection unitOfWork, UUID partyId, CurrencyCode currency) {
            return Money.ofMinorUnits(applicant(partyId).platformOutstandingMinor(), currency);
        }
    };

    static final CreditBureau BUREAU = new CreditBureau() {
        @Override
        public String code() {
            return "bureau-battery";
        }

        @Override
        public CreditDataAnswer pull(CreditDataPull request) {
            Applicant applicant = applicant(UUID.fromString(request.subjectReference()));
            ReproducibilityApplicants.Bureau bureau = applicant.bureau();
            if (bureau.answer() == Answer.UNAVAILABLE) {
                return new CreditDataAnswer.Unavailable(CreditDataAnswer.UnavailableCause.TIMEOUT, Optional.empty());
            }
            AttributeProvenance from = new AttributeProvenance.Provider(CreditSourceKind.BUREAU, code(), 1);
            CurrencyCode balanceCurrency = bureau.answer() == Answer.FOREIGN ? USD : CreditWorld.EUR;
            List<CreditAttribute> attributes = new ArrayList<>();
            attributes.add(attribute(CreditAttributeCode.BUREAU_EXTERNAL_SCORE,
                    new AttributeValue.IntegerValue(bureau.externalScore()), bureau.absent(), from));
            attributes.add(attribute(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS,
                    new AttributeValue.IntegerValue(bureau.activeAccounts()), bureau.absent(), from));
            attributes.add(attribute(CreditAttributeCode.BUREAU_DELINQUENCIES_24M,
                    new AttributeValue.IntegerValue(bureau.delinquencies()), bureau.absent(), from));
            attributes.add(attribute(CreditAttributeCode.BUREAU_DEFAULTS_72M,
                    new AttributeValue.IntegerValue(bureau.defaults()), bureau.absent(), from));
            attributes.add(attribute(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG,
                    new AttributeValue.BooleanValue(bureau.insolvent()), bureau.absent(), from));
            attributes.add(attribute(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS,
                    new AttributeValue.MoneyValue(CreditWorld.eur(bureau.monthlyObligationsMinor())), bureau.absent(), from));
            attributes.add(attribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE,
                    new AttributeValue.MoneyValue(Money.ofMinorUnits(bureau.totalBalanceMinor(), balanceCurrency)),
                    bureau.absent(), from));
            return answer(code(), applicant, attributes);
        }
    };

    static final FinancialDataProvider FINANCIAL_DATA = new FinancialDataProvider() {
        @Override
        public String code() {
            return "findata-battery";
        }

        @Override
        public CreditDataAnswer pull(CreditDataPull request) {
            Applicant applicant = applicant(UUID.fromString(request.subjectReference()));
            ReproducibilityApplicants.FinancialData data = applicant.financialData();
            if (data.answer() == Answer.UNAVAILABLE) {
                return new CreditDataAnswer.Unavailable(CreditDataAnswer.UnavailableCause.TIMEOUT, Optional.empty());
            }
            AttributeProvenance from = new AttributeProvenance.Provider(CreditSourceKind.FINANCIAL_DATA, code(), 1);
            CurrencyCode incomeCurrency = data.answer() == Answer.FOREIGN ? USD : CreditWorld.EUR;
            List<CreditAttribute> attributes = new ArrayList<>();
            attributes.add(attribute(CreditAttributeCode.FINDATA_MONTHLY_INCOME,
                    new AttributeValue.MoneyValue(Money.ofMinorUnits(data.incomeMinor(), incomeCurrency)), data.absent(),
                    from));
            attributes.add(attribute(CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE,
                    new AttributeValue.MoneyValue(CreditWorld.eur(data.committedMinor())), data.absent(), from));
            return answer(code(), applicant, attributes);
        }
    };

    private ReproducibilityWorld() {}

    private static Applicant applicant(UUID party) {
        Applicant applicant = APPLICANTS.get(party);
        if (applicant == null) {
            throw new IllegalStateException("no battery applicant for a party the pipeline asked about");
        }
        return applicant;
    }

    private static CreditAttribute attribute(
            CreditAttributeCode code, AttributeValue value, Set<CreditAttributeCode> absent, AttributeProvenance from) {
        return new CreditAttribute(code, absent.contains(code) ? new AttributeValue.Absent() : value, from);
    }

    private static CreditDataAnswer answer(String provider, Applicant applicant, List<CreditAttribute> attributes) {
        Instant retrieved = Instant.now().minusSeconds(60);
        CreditEvidence evidence = new CreditEvidence(("{\"battery\":\"" + applicant.index() + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        boolean partial = attributes.stream().anyMatch(CreditAttribute::absent);
        return partial
                ? new CreditDataAnswer.Partial(provider, 1, retrieved, attributes, evidence)
                : new CreditDataAnswer.Received(provider, 1, retrieved, attributes, evidence);
    }

    // ------------------------------------------------------------------ production's components, composed

    static CreditDataCollection collection() {
        return new CreditDataCollection(new JdbcCreditDataRequestStore(),
                CreditDataCollection.Sources.of(BUREAU, TIMING, FINANCIAL_DATA, TIMING), GATE, CreditWorld.cipher,
                CreditDataObserver.NONE, new JdbcAuditWriter(), new JdbcOutboxWriter(), TRANSACTIONS, CreditWorld.IDS,
                CreditWorld.CLOCK);
    }

    static SnapshotFreezer freezer(CreditDataCollection collection) {
        return new SnapshotFreezer(new JdbcDecisionSnapshotStore(), collection, STANDING, RISK, new JdbcReservedExposure(),
                PLATFORM, CreditWorld.IDS);
    }

    static CreditAssessments assessments() {
        return new CreditAssessments(new JdbcCreditAssessmentStore(), CreditWorld.SCORECARDS, new JdbcOutboxWriter(),
                CreditWorld.IDS, CreditWorld.CLOCK);
    }

    static PolicyEvaluations evaluations() {
        return new PolicyEvaluations(new JdbcPolicyEvaluationStore(), CreditWorld.POLICIES, EngineVersions.STANDARD,
                CreditWorld.IDS, CreditWorld.CLOCK);
    }

    static DecisionMaking deciding(CreditDataCollection collection) {
        return new DecisionMaking(TRANSACTIONS, CreditWorld.REQUESTS, new JdbcCreditProfiles(CreditWorld.IDS),
                CreditWorld.POLICIES, CreditWorld.SCORECARDS, freezer(collection), assessments(),
                new JdbcCreditAssessmentStore(), evaluations(), new JdbcPolicyEvaluationStore(), new JdbcCreditDecisions(),
                new JdbcUnderwritingCaseStore(), STANDING, GATE, new JdbcOutboxWriter(), new JdbcAuditWriter(),
                DecisionObserver.NONE, CreditWorld.IDS, CreditWorld.CLOCK);
    }

    static DecisionProgress progress(CreditDataCollection collection, Decider decider) {
        return new DecisionProgress(TRANSACTIONS, CreditWorld.REQUESTS, CreditWorld.POLICIES, CreditWorld.SCORECARDS,
                new JdbcDecisionSnapshotStore(), collection, freezer(collection), assessments(), evaluations(), STANDING,
                GATE, new JdbcOutboxWriter(), CreditWorld.IDS, CreditWorld.CLOCK, decider, new JdbcUnderwritingCaseStore());
    }

    static UnderwritingCases reviewing(DecisionMaking deciding) {
        return new UnderwritingCases(new JdbcUnderwritingCaseStore(), CreditWorld.REQUESTS,
                new JdbcCreditProfiles(CreditWorld.IDS), deciding, new JdbcDecisionSnapshotStore(),
                new JdbcPolicyEvaluationStore(), CreditWorld.POLICIES, new JdbcAuditWriter(), CreditWorld.IDS,
                CreditWorld.CLOCK);
    }

    /** A replayer over the battery's database holding {@code engines} - the one {@code DecisionReplayDatabaseTest} builds. */
    static DecisionReplayer replayer(EngineVersions engines) {
        return new DecisionReplayer(new JdbcCreditDecisions(), new JdbcDecisionSnapshotStore(), CreditWorld.POLICIES,
                CreditWorld.SCORECARDS, new JdbcCreditAssessmentStore(), new JdbcPolicyEvaluationStore(),
                new JdbcUnderwritingCaseStore(), engines);
    }

    // ------------------------------------------------------------------ building the world

    /** What the build left behind. */
    record Built(long seed, List<Applicant> applicants, int requests, int personDecided, int fourEyes) {}

    /** Generates {@code count} applicants from {@code seed} and drives every one to its decision, epoch by epoch. */
    @SuppressWarnings("try")
    static Built build(long seed, int count) throws Exception {
        List<Applicant> applicants = ReproducibilityApplicants.generate(seed, count, MAXIMUM_EXPOSURE);
        applicants.forEach(applicant -> APPLICANTS.put(applicant.party(), applicant));
        Driver driver = new Driver();
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            List<Carried> carried = List.of();
            for (int epoch = 1; epoch <= 3; epoch++) {
                activate(epoch);
                int current = epoch;
                List<Applicant> ofEpoch = applicants.stream().filter(applicant -> applicant.epoch() == current).toList();
                int bulk = epoch < 3 ? ofEpoch.size() - CARRIED : ofEpoch.size();
                List<Runnable> work = new ArrayList<>();
                for (Carried previous : carried) {
                    work.add(() -> driver.finish(previous.applicant(), previous.requests()));
                }
                for (Applicant applicant : ofEpoch.subList(0, bulk)) {
                    work.add(() -> driver.whole(applicant));
                }
                run(pool, work);
                List<Carried> next = new CopyOnWriteArrayList<>();
                List<Runnable> preparing = new ArrayList<>();
                for (Applicant applicant : ofEpoch.subList(bulk, ofEpoch.size())) {
                    preparing.add(() -> next.add(new Carried(applicant, driver.prepare(applicant))));
                }
                run(pool, preparing);
                carried = List.copyOf(next);
            }
        } finally {
            pool.shutdownNow();
        }
        return new Built(seed, applicants, driver.requests.get(), driver.personDecided.get(), driver.fourEyes.get());
    }

    private record Carried(Applicant applicant, List<UUID> requests) {}

    private static void run(ExecutorService pool, List<Runnable> work) throws Exception {
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : work) {
            futures.add(pool.submit(task));
        }
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.MINUTES);
        }
    }

    /** Drives applicants' requests through production's pipeline - one instance of it, shared by every worker. */
    static final class Driver {
        private final CreditDataCollection collection = collection();
        private final DecisionMaking deciding = deciding(collection);
        private final DecisionProgress progress = progress(collection, deciding);
        private final DecisionProgress toEvaluation = progress(collection, (id, correlation) -> Decider.Decided.NOTHING);
        private final UnderwritingCases reviewing = reviewing(deciding);
        final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger personDecided = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger fourEyes = new java.util.concurrent.atomic.AtomicInteger();

        /** One applicant from submission to decision - a companion evaluated beside it before either is decided. */
        void whole(Applicant applicant) {
            List<UUID> submitted = submitAll(applicant);
            if (submitted.size() > 1) {
                submitted.forEach(request -> advance(request, true));
            }
            finish(applicant, submitted);
        }

        /** Submitted and pinned - evaluated, for every second applicant - but not decided. */
        List<UUID> prepare(Applicant applicant) {
            List<UUID> submitted = submitAll(applicant);
            submitted.forEach(request -> advance(request, applicant.index() % 2 == 0));
            return submitted;
        }

        /** Every request of {@code applicant} decided, in order. */
        void finish(Applicant applicant, List<UUID> submitted) {
            submitted.forEach(request -> decide(applicant, request));
        }

        private List<UUID> submitAll(Applicant applicant) {
            List<UUID> submitted = new ArrayList<>();
            submitted.add(submit(applicant, applicant.product(), applicant.requested(), applicant.termMonths()));
            applicant.companion().ifPresent(amount -> {
                CreditProduct other = ReproducibilityApplicants.other(applicant.product());
                submitted.add(submit(applicant, other, amount, other.revolving() ? Optional.empty() : Optional.of(24)));
            });
            return submitted;
        }

        private UUID submit(Applicant applicant, CreditProduct product, Money amount, Optional<Integer> term) {
            requests.incrementAndGet();
            return TRANSACTIONS.inTransaction(uow -> {
                var profile = new JdbcCreditProfiles(CreditWorld.IDS).ensure(uow, applicant.party());
                DecisionRequestId id = DecisionRequestId.next(CreditWorld.IDS);
                CreditWorld.REQUESTS.insert(uow, id, applicant.party(), profile.id(), new DecisionRequest.Application(
                        product, amount, term, applicant.declaredIncome(), applicant.declaredExpenditure()),
                        Duration.ofDays(7), "battery-" + applicant.index(),
                        new Actor(applicant.party().toString(), ActorType.CUSTOMER));
                return id.value();
            });
        }

        /** Steps {@code request} until it is evaluated - or, when not {@code toEvaluated}, until it is pinned. */
        private void advance(UUID request, boolean toEvaluated) {
            for (int i = 0; i < 200; i++) {
                DecisionProgress.Step step = step(toEvaluation, request);
                switch (step) {
                    case EVALUATED -> {
                        return;
                    }
                    case COLLECTING -> {
                        if (!toEvaluated) {
                            return;
                        }
                    }
                    case WAITING -> pause();
                    case READY, RECOLLECTING -> { }
                    default -> throw new IllegalStateException("a battery request stopped at " + step + " short of evaluation");
                }
            }
            throw new IllegalStateException("a battery request never reached its evaluation");
        }

        /** Steps {@code request} to its decision; a referral is decided by a person. */
        private void decide(Applicant applicant, UUID request) {
            for (int i = 0; i < 200; i++) {
                DecisionProgress.Step step = step(progress, request);
                switch (step) {
                    case DECIDED -> {
                        return;
                    }
                    case REFERRED -> {
                        person(applicant, request);
                        return;
                    }
                    case WAITING -> pause();
                    case COLLECTING, READY, RECOLLECTING, EVALUATED -> { }
                    default -> throw new IllegalStateException("a battery request stopped at " + step);
                }
            }
            throw new IllegalStateException("a battery request was never decided");
        }

        /**
         * A person decides the referral: approves the requested amount when the applicant's draw says so and the bounds
         * allow it - a second person seconding an approval above the four-eyes threshold - and declines otherwise, citing
         * the drawn adverse code.
         */
        private void person(Applicant applicant, UUID request) {
            UnderwritingCaseId id = UnderwritingCaseId.of(UUID.fromString(CreditWorld.scalar(
                    "SELECT id::text FROM credit.underwriting_case WHERE decision_request_id = ?", request)));
            Actor first = new Actor("underwriter-" + applicant.index(), ActorType.EMPLOYEE);
            TRANSACTIONS.inTransaction(uow -> reviewing.assign(uow, id, first, correlation()));
            List<ReasonCode> adverse = EnumSet.allOf(ReasonCode.class).stream().filter(ReasonCode::adverse).toList();
            List<ReasonCode> reasons = List.of(adverse.get(applicant.personReason() % adverse.size()));
            UnderwritingCases.Acted acted = null;
            if (applicant.personApproves()) {
                Money amount = CreditWorld.scalar("SELECT product FROM credit.decision_request WHERE id = ?", request)
                        .equals(applicant.product().name()) ? applicant.requested() : applicant.companion().orElseThrow();
                try {
                    acted = TRANSACTIONS.inTransaction(uow -> reviewing.decide(uow, id, new UnderwritingCases.Judgement(
                            DecisionOutcome.APPROVED, Optional.of(amount), reasons, "the battery's approval"), first,
                            correlation()));
                } catch (UnderwritingCases.ExposureLimitExceeded | UnderwritingCases.JudgementInvalid outOfBounds) {
                    acted = null; // the bounds refused it, nothing recorded: the person declines instead
                }
            }
            if (acted == null) {
                acted = TRANSACTIONS.inTransaction(uow -> reviewing.decide(uow, id, new UnderwritingCases.Judgement(
                        DecisionOutcome.DECLINED, Optional.empty(), reasons, "the battery's decline"), first, correlation()));
            }
            if (acted.reviewCase().status() == UnderwritingCaseStatus.AWAITING_SECOND) {
                Actor second = new Actor("second-" + applicant.index(), ActorType.EMPLOYEE);
                acted = TRANSACTIONS.inTransaction(uow -> reviewing.approveSecond(uow, id, Optional.empty(), second,
                        correlation()));
                fourEyes.incrementAndGet();
            }
            if (acted.reviewCase().status() != UnderwritingCaseStatus.DECIDED) {
                throw new IllegalStateException("a battery case ended " + acted.reviewCase().status());
            }
            personDecided.incrementAndGet();
        }

        @SuppressWarnings("try")
        private static DecisionProgress.Step step(DecisionProgress progress, UUID request) {
            try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
                return progress.step(DecisionRequestId.of(request), correlation());
            }
        }

        private static void pause() {
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", interrupted);
            }
        }
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(CreditWorld.IDS);
    }

    // ------------------------------------------------------------------ the epochs' versions

    /** Brings {@code epoch}'s policies and scorecard into force, each activated by two persons. */
    static void activate(int epoch) {
        if (epoch == 1) {
            CreditWorld.seedsInForce();
            return;
        }
        Scorecard scorecard = epoch == 2 ? scorecardTwo() : scorecardThree();
        TRANSACTIONS.inTransaction(uow -> {
            for (CreditProduct product : CreditProduct.values()) {
                CreditPolicy policy = epoch == 2 ? coveragePolicy(product) : referringPolicy(product);
                CreditPolicyAdministration.Proposed proposed = CreditWorld.POLICY_ADMINISTRATION.propose(uow, policy,
                        "the battery's epoch " + epoch, officer(), correlation());
                CreditWorld.POLICY_ADMINISTRATION.approve(uow, proposed.id(), officer(), "epoch " + epoch, correlation());
            }
            ScorecardAdministration.Proposed proposed = CreditWorld.SCORECARD_ADMINISTRATION.propose(uow,
                    ScorecardFamily.RETAIL_SCORECARD, scorecard, "the battery's epoch " + epoch, officer(), correlation());
            CreditWorld.SCORECARD_ADMINISTRATION.approve(uow, proposed.id(), officer(), "epoch " + epoch, correlation());
            return null;
        });
    }

    private static Actor officer() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    private static Money eur(long minor) {
        return CreditWorld.eur(minor);
    }

    private static long limit(int epoch, CreditProduct product) {
        return MAXIMUM_EXPOSURE[epoch - 1][product.ordinal()];
    }

    /**
     * Epoch 2's policy: every operator, every effect and every adverse reason code on a rule, an unavailable fallback that
     * DECLINES - and the exposure rule, the replay's sentinel, the one rule citing {@code CRD-EXPOSURE-LIMIT}.
     */
    static CreditPolicy coveragePolicy(CreditProduct product) {
        boolean loan = product == CreditProduct.PERSONAL_LOAN;
        long unit = loan ? 100_000 : 25_000;
        List<PolicyRule> rules = List.of(
                rule("B_FALLBACK", attribute(CreditAttributeCode.SOURCE_UNAVAILABLE), PolicyOperator.IS_PRESENT, none(),
                        PolicyEffect.DECLINE, ReasonCode.SOURCE_UNAVAILABLE),
                rule("B_UNDERAGE", attribute(CreditAttributeCode.PARTY_AGE_YEARS), PolicyOperator.LT, integer(18),
                        PolicyEffect.HARD_DECLINE, ReasonCode.AGE_INELIGIBLE),
                rule("B_RESIDENCY", attribute(CreditAttributeCode.PARTY_RESIDENCY_COUNTRY), PolicyOperator.NOT_IN,
                        codes(ReproducibilityApplicants.ACCEPTED_RESIDENCES), PolicyEffect.DECLINE,
                        ReasonCode.RESIDENCY_INELIGIBLE),
                rule("B_INSOLVENT", attribute(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG), PolicyOperator.EQ,
                        new Operand.BooleanOperand(true), PolicyEffect.HARD_DECLINE, ReasonCode.INSOLVENCY),
                rule("B_DEFAULTED", attribute(CreditAttributeCode.BUREAU_DEFAULTS_72M), PolicyOperator.GT, integer(0),
                        PolicyEffect.DECLINE, ReasonCode.PRIOR_DEFAULT),
                rule("B_DELINQUENT", attribute(CreditAttributeCode.BUREAU_DELINQUENCIES_24M), PolicyOperator.GE,
                        integer(2), PolicyEffect.DECLINE, ReasonCode.RECENT_DELINQUENCY),
                rule("B_THIN_FILE", attribute(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS), PolicyOperator.LE, integer(0),
                        PolicyEffect.DECLINE, ReasonCode.INSUFFICIENT_CREDIT_HISTORY),
                rule("B_SCORE_FLOOR", figure(PolicyFigure.SCORE), PolicyOperator.LT, integer(420), PolicyEffect.DECLINE,
                        ReasonCode.SCORE_INSUFFICIENT),
                rule("B_UNAFFORDABLE", figure(PolicyFigure.AFFORDABLE), PolicyOperator.EQ,
                        new Operand.BooleanOperand(false), PolicyEffect.DECLINE, ReasonCode.AFFORDABILITY_INSUFFICIENT),
                rule("B_OVER_LIMIT", figure(PolicyFigure.EXPOSURE_HEADROOM), PolicyOperator.LT, money(0),
                        PolicyEffect.DECLINE, ReasonCode.EXPOSURE_LIMIT),
                rule("B_UNVERIFIED", attribute(CreditAttributeCode.FINDATA_MONTHLY_INCOME), PolicyOperator.IS_ABSENT,
                        none(), PolicyEffect.DECLINE, ReasonCode.INCOME_UNVERIFIED),
                rule("B_FOREIGN", attribute(CreditAttributeCode.CURRENCY_NOT_SUPPORTED), PolicyOperator.IS_PRESENT,
                        none(), PolicyEffect.DECLINE, ReasonCode.CURRENCY_NOT_SUPPORTED),
                rule("B_RISK_HIGH", attribute(CreditAttributeCode.RISK_SIGNAL), PolicyOperator.IN,
                        codes(List.of("HIGH", "SEVERE")), PolicyEffect.DECLINE, ReasonCode.RISK_REFERRAL),
                rule("B_RISK_WATCH", attribute(CreditAttributeCode.RISK_SIGNAL), PolicyOperator.EQ, codes(List.of("WATCH")),
                        PolicyEffect.REFER, ReasonCode.RISK_REFERRAL),
                rule("B_ACCOUNTS_SEVEN", attribute(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS), PolicyOperator.EQ,
                        integer(7), PolicyEffect.REFER, ReasonCode.INSUFFICIENT_CREDIT_HISTORY),
                rule("B_TIGHT_BUDGET", figure(PolicyFigure.DISPOSABLE_INCOME), PolicyOperator.LT, money(40_000),
                        PolicyEffect.REFER, ReasonCode.AFFORDABILITY_INSUFFICIENT),
                rule("B_DECLARED_HIGH", attribute(CreditAttributeCode.DECLARED_MONTHLY_INCOME), PolicyOperator.GE,
                        money(1_000_000), PolicyEffect.REFER, ReasonCode.INCOME_UNVERIFIED),
                cap("B_RISK_SEEN", attribute(CreditAttributeCode.RISK_SIGNAL), PolicyOperator.NE,
                        codes(List.of("NOT_ASSESSED")), 6 * unit, ReasonCode.RISK_REFERRAL),
                cap("B_HEAVY_EXPOSURE", figure(PolicyFigure.EXPOSURE), PolicyOperator.GT, money(limit(2, product) / 2),
                        7 * unit, ReasonCode.AFFORDABILITY_INSUFFICIENT),
                cap("B_SCORE_CAP", figure(PolicyFigure.SCORE), PolicyOperator.LT, integer(560), 9 * unit,
                        ReasonCode.SCORE_INSUFFICIENT),
                cap("B_SOME_DELINQUENCY", attribute(CreditAttributeCode.BUREAU_DELINQUENCIES_24M), PolicyOperator.NE,
                        integer(0), 5 * unit, ReasonCode.RECENT_DELINQUENCY));
        return new CreditPolicy(product, 1_200, eur(15_000), loan ? 300 : 400, eur(limit(2, product)),
                Map.of(CreditSourceKind.BUREAU, Duration.ofDays(30), CreditSourceKind.FINANCIAL_DATA, Duration.ofDays(30)),
                UnavailableFallback.DECLINE, eur(loan ? 800_000 : 200_000), rules);
    }

    /**
     * Epoch 3's policy: an unavailable fallback that REFERS; the loan reads both sources, the line the bureau alone (its
     * fallback the {@code IS_ABSENT} shape on a bureau attribute) - and the exposure sentinel again, alone in citing
     * {@code CRD-EXPOSURE-LIMIT}.
     */
    static CreditPolicy referringPolicy(CreditProduct product) {
        boolean loan = product == CreditProduct.PERSONAL_LOAN;
        List<PolicyRule> rules = new ArrayList<>();
        if (loan) {
            rules.add(rule("C_FALLBACK", attribute(CreditAttributeCode.SOURCE_UNAVAILABLE), PolicyOperator.IS_PRESENT,
                    none(), PolicyEffect.REFER, ReasonCode.SOURCE_UNAVAILABLE));
            rules.add(rule("C_INSOLVENT", attribute(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG), PolicyOperator.NE,
                    new Operand.BooleanOperand(false), PolicyEffect.HARD_DECLINE, ReasonCode.INSOLVENCY));
            rules.add(rule("C_SENIOR", attribute(CreditAttributeCode.PARTY_AGE_YEARS), PolicyOperator.GE, integer(75),
                    PolicyEffect.REFER, ReasonCode.AGE_INELIGIBLE));
            rules.add(rule("C_MINOR", attribute(CreditAttributeCode.PARTY_AGE_YEARS), PolicyOperator.LE, integer(17),
                    PolicyEffect.HARD_DECLINE, ReasonCode.AGE_INELIGIBLE));
            rules.add(rule("C_REFUSED_RESIDENCE", attribute(CreditAttributeCode.PARTY_RESIDENCY_COUNTRY), PolicyOperator.IN,
                    codes(ReproducibilityApplicants.REFUSED_RESIDENCES), PolicyEffect.DECLINE,
                    ReasonCode.RESIDENCY_INELIGIBLE));
            rules.add(rule("C_DEFAULTS", attribute(CreditAttributeCode.BUREAU_DEFAULTS_72M), PolicyOperator.GE, integer(2),
                    PolicyEffect.DECLINE, ReasonCode.PRIOR_DEFAULT));
            rules.add(rule("C_ONE_DEFAULT", attribute(CreditAttributeCode.BUREAU_DEFAULTS_72M), PolicyOperator.EQ,
                    integer(1), PolicyEffect.REFER, ReasonCode.PRIOR_DEFAULT));
            rules.add(rule("C_DELINQUENT", attribute(CreditAttributeCode.BUREAU_DELINQUENCIES_24M), PolicyOperator.GT,
                    integer(2), PolicyEffect.DECLINE, ReasonCode.RECENT_DELINQUENCY));
            rules.add(rule("C_SCORE_FLOOR", figure(PolicyFigure.SCORE), PolicyOperator.LE, integer(440),
                    PolicyEffect.DECLINE, ReasonCode.SCORE_INSUFFICIENT));
            rules.add(rule("C_OVER_LIMIT", figure(PolicyFigure.EXPOSURE_HEADROOM), PolicyOperator.LT, money(0),
                    PolicyEffect.DECLINE, ReasonCode.EXPOSURE_LIMIT));
            rules.add(rule("C_UNAFFORDABLE", figure(PolicyFigure.DISPOSABLE_INCOME), PolicyOperator.LT, money(10_000),
                    PolicyEffect.DECLINE, ReasonCode.AFFORDABILITY_INSUFFICIENT));
            rules.add(rule("C_FOREIGN", attribute(CreditAttributeCode.CURRENCY_NOT_SUPPORTED), PolicyOperator.IS_PRESENT,
                    none(), PolicyEffect.REFER, ReasonCode.CURRENCY_NOT_SUPPORTED));
            rules.add(rule("C_UNVERIFIED", attribute(CreditAttributeCode.FINDATA_MONTHLY_INCOME), PolicyOperator.IS_ABSENT,
                    none(), PolicyEffect.REFER, ReasonCode.INCOME_UNVERIFIED));
            rules.add(rule("C_RISK", attribute(CreditAttributeCode.RISK_SIGNAL), PolicyOperator.NOT_IN,
                    codes(List.of("LOW", "NOT_ASSESSED")), PolicyEffect.DECLINE, ReasonCode.RISK_REFERRAL));
            rules.add(rule("C_THIN_FILE", attribute(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS), PolicyOperator.LT,
                    integer(1), PolicyEffect.DECLINE, ReasonCode.INSUFFICIENT_CREDIT_HISTORY));
            rules.add(cap("C_SCORE_CAP", figure(PolicyFigure.SCORE), PolicyOperator.LT, integer(600), 750_000,
                    ReasonCode.SCORE_INSUFFICIENT));
            rules.add(cap("C_BALANCE_CAP", attribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE), PolicyOperator.GE,
                    money(2_000_000), 650_000, ReasonCode.INSUFFICIENT_CREDIT_HISTORY));
        } else {
            rules.add(rule("C_FALLBACK", attribute(CreditAttributeCode.BUREAU_EXTERNAL_SCORE), PolicyOperator.IS_ABSENT,
                    none(), PolicyEffect.REFER, ReasonCode.SOURCE_UNAVAILABLE));
            rules.add(rule("C_INSOLVENT", attribute(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG), PolicyOperator.EQ,
                    new Operand.BooleanOperand(true), PolicyEffect.HARD_DECLINE, ReasonCode.INSOLVENCY));
            rules.add(rule("C_MINOR", attribute(CreditAttributeCode.PARTY_AGE_YEARS), PolicyOperator.LT, integer(18),
                    PolicyEffect.HARD_DECLINE, ReasonCode.AGE_INELIGIBLE));
            rules.add(rule("C_RESIDENCY", attribute(CreditAttributeCode.PARTY_RESIDENCY_COUNTRY), PolicyOperator.NOT_IN,
                    codes(ReproducibilityApplicants.ACCEPTED_RESIDENCES), PolicyEffect.DECLINE,
                    ReasonCode.RESIDENCY_INELIGIBLE));
            rules.add(rule("C_DEFAULTS", attribute(CreditAttributeCode.BUREAU_DEFAULTS_72M), PolicyOperator.GT, integer(0),
                    PolicyEffect.DECLINE, ReasonCode.PRIOR_DEFAULT));
            rules.add(rule("C_DELINQUENT", attribute(CreditAttributeCode.BUREAU_DELINQUENCIES_24M), PolicyOperator.GE,
                    integer(3), PolicyEffect.DECLINE, ReasonCode.RECENT_DELINQUENCY));
            rules.add(rule("C_SCORE_FLOOR", figure(PolicyFigure.SCORE), PolicyOperator.LT, integer(450),
                    PolicyEffect.DECLINE, ReasonCode.SCORE_INSUFFICIENT));
            rules.add(rule("C_OVER_LIMIT", figure(PolicyFigure.EXPOSURE_HEADROOM), PolicyOperator.LT, money(0),
                    PolicyEffect.DECLINE, ReasonCode.EXPOSURE_LIMIT));
            rules.add(rule("C_FOREIGN", attribute(CreditAttributeCode.CURRENCY_NOT_SUPPORTED), PolicyOperator.IS_PRESENT,
                    none(), PolicyEffect.DECLINE, ReasonCode.CURRENCY_NOT_SUPPORTED));
            rules.add(rule("C_RISK", attribute(CreditAttributeCode.RISK_SIGNAL), PolicyOperator.IN, codes(List.of("SEVERE")),
                    PolicyEffect.HARD_DECLINE, ReasonCode.RISK_REFERRAL));
            rules.add(rule("C_WATCH", attribute(CreditAttributeCode.RISK_SIGNAL), PolicyOperator.IN,
                    codes(List.of("HIGH", "WATCH")), PolicyEffect.REFER, ReasonCode.RISK_REFERRAL));
            rules.add(cap("C_SCORE_CAP", figure(PolicyFigure.SCORE), PolicyOperator.LT, integer(620), 180_000,
                    ReasonCode.SCORE_INSUFFICIENT));
        }
        Map<CreditSourceKind, Duration> ages = loan
                ? Map.of(CreditSourceKind.BUREAU, Duration.ofDays(30), CreditSourceKind.FINANCIAL_DATA, Duration.ofDays(30))
                : Map.of(CreditSourceKind.BUREAU, Duration.ofDays(30));
        return new CreditPolicy(product, 700, eur(10_000), loan ? 300 : 600, eur(limit(3, product)), ages,
                UnavailableFallback.REFER, eur(loan ? 1_200_000 : 220_000), rules);
    }

    /** Scorecard 2: the bureau's score, the party's age, the risk seam's signal and insolvency. */
    static Scorecard scorecardTwo() {
        return new Scorecard(480, List.of(
                new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, -50, List.of(
                        new Scorecard.Range(null, 500L, -80), new Scorecard.Range(500L, 600L, -10),
                        new Scorecard.Range(600L, 700L, 30), new Scorecard.Range(700L, null, 70))),
                new Scorecard.AttributeBands(CreditAttributeCode.PARTY_AGE_YEARS, -10, List.of(
                        new Scorecard.Range(null, 25L, -20), new Scorecard.Range(25L, 60L, 20),
                        new Scorecard.Range(60L, null, 0))),
                new Scorecard.AttributeBands(CreditAttributeCode.RISK_SIGNAL, 0, List.of(
                        new Scorecard.Codes(Set.of("LOW", "NOT_ASSESSED"), 10), new Scorecard.Codes(Set.of("WATCH"), -30),
                        new Scorecard.Codes(Set.of("HIGH", "SEVERE"), -60))),
                new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG, -20, List.of(
                        new Scorecard.Codes(Set.of("false"), 0), new Scorecard.Codes(Set.of("true"), -150)))));
    }

    /** Scorecard 3: the bureau's score, delinquencies, defaults and accounts, on other bands. */
    static Scorecard scorecardThree() {
        return new Scorecard(520, List.of(
                new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, -30, List.of(
                        new Scorecard.Range(null, 580L, -50), new Scorecard.Range(580L, 720L, 0),
                        new Scorecard.Range(720L, null, 50))),
                new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_DELINQUENCIES_24M, -10, List.of(
                        new Scorecard.Range(null, 1L, 20), new Scorecard.Range(1L, null, -40))),
                new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_DEFAULTS_72M, -10, List.of(
                        new Scorecard.Range(null, 1L, 10), new Scorecard.Range(1L, null, -100))),
                new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS, 0, List.of(
                        new Scorecard.Range(null, 2L, -15), new Scorecard.Range(2L, 7L, 15),
                        new Scorecard.Range(7L, null, -5)))));
    }

    private static PolicyRule rule(String code, Subject subject, PolicyOperator operator, Operand operand,
            PolicyEffect effect, ReasonCode reason) {
        return new PolicyRule(code, subject, operator, operand, effect, Optional.empty(), reason);
    }

    private static PolicyRule cap(String code, Subject subject, PolicyOperator operator, Operand operand, long capMinor,
            ReasonCode reason) {
        return new PolicyRule(code, subject, operator, operand, PolicyEffect.CAP_AMOUNT, Optional.of(eur(capMinor)), reason);
    }

    private static Subject attribute(CreditAttributeCode code) {
        return new Subject.Attribute(code);
    }

    private static Subject figure(PolicyFigure figure) {
        return new Subject.Figure(figure);
    }

    private static Operand none() {
        return new Operand.None();
    }

    private static Operand integer(long value) {
        return new Operand.IntegerOperand(value);
    }

    private static Operand money(long minor) {
        return new Operand.MoneyOperand(eur(minor));
    }

    private static Operand codes(List<String> codes) {
        return new Operand.CodesOperand(codes);
    }

    // ------------------------------------------------------------------ a pool for the workers

    /**
     * Production's connection pool, in miniature: each thread keeps its idle connections and borrows one per
     * transaction - a second one when a transaction opens inside another (the progress asks its providers after its
     * commit, but a collection records inside its own) - so ten thousand requests do not pay a connection each step.
     */
    static final class PooledTransactions implements TransactionRunner {

        private final ThreadLocal<Deque<Connection>> idle = ThreadLocal.withInitial(ArrayDeque::new);
        private final List<Connection> opened = new CopyOnWriteArrayList<>();

        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            Deque<Connection> mine = idle.get();
            Connection connection = mine.poll();
            try {
                if (connection == null) {
                    connection = DatabaseRoles.application();
                    opened.add(connection);
                }
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
            } finally {
                if (connection != null) {
                    mine.push(connection);
                }
            }
        }

        /** Closes every connection the pool opened. */
        void close() {
            for (Connection connection : opened) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // closing a test pool
                }
            }
            opened.clear();
        }
    }
}
