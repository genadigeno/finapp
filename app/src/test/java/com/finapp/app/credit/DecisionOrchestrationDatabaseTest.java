package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.finapp.credit.CreditEvidenceCipher;
import com.finapp.credit.CreditPartyStanding;
import com.finapp.credit.CreditPolicy;
import com.finapp.credit.CreditPolicyAdministration;
import com.finapp.credit.CreditPolicyStatus;
import com.finapp.credit.CreditPolicyVersionId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.DecisionProgress;
import com.finapp.credit.DecisionRequest;
import com.finapp.credit.DecisionRequestId;
import com.finapp.credit.EngineVersions;
import com.finapp.credit.FinancialDataProvider;
import com.finapp.credit.JdbcCreditAssessmentStore;
import com.finapp.credit.JdbcCreditDataRequestStore;
import com.finapp.credit.JdbcCreditPolicyStore;
import com.finapp.credit.JdbcCreditProfiles;
import com.finapp.credit.JdbcDecisionRequestStore;
import com.finapp.credit.JdbcDecisionSnapshotStore;
import com.finapp.credit.JdbcPolicyEvaluationStore;
import com.finapp.credit.JdbcScorecardStore;
import com.finapp.credit.PolicyEffect;
import com.finapp.credit.PolicyEvaluations;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The decision request driven from submission to evaluation across instances (`P10-TSK-015`;
 * CREDIT_DECISIONING_LIFECYCLES.md section 3.1, {@code INV-CRD-06}, {@code INV-CRD-08}, {@code INV-CRD-10},
 * {@code INV-CRD-03}, {@code INV-HIST-04}): credit's real progress, collection, freeze, assessment and evaluation over a
 * real database, the ports at its edge - the sources, the consent gate, the party's standing - test doubles each case
 * steers. Separate {@link DecisionProgress} instances stand for separate instances; every count is read from the rows.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the suite claims every due request in the database
 * and brings policies into force, which no other suite's rows may disturb or be disturbed by.
 */
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("decision orchestration (P10-TSK-015)")
class DecisionOrchestrationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CreditPolicyVersionId LOAN_SEED =
            CreditPolicyVersionId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-00000000d001"));
    private static final CreditPolicyVersionId LINE_SEED =
            CreditPolicyVersionId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-00000000d002"));
    private static final ScorecardModelVersionId SCORECARD_SEED =
            ScorecardModelVersionId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-00000000c001"));
    private static final CreditDataCollection.Timing TIMING =
            new CreditDataCollection.Timing(Duration.ofMinutes(10), Duration.ofMinutes(30));

    private static final TransactionRunner TRANSACTIONS = new TransactionRunner() {
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

    private static final JdbcCreditPolicyStore POLICIES = new JdbcCreditPolicyStore();
    private static final JdbcScorecardStore SCORECARDS = new JdbcScorecardStore();
    private static final JdbcDecisionRequestStore REQUESTS = new JdbcDecisionRequestStore();
    private static final CreditPolicyAdministration POLICY_ADMINISTRATION =
            new CreditPolicyAdministration(POLICIES, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);

    // ------------------------------------------------------------------ the world at the ports, steered per case

    /** How a party's source answers. */
    private enum Answer { FRESH, STALE_ONCE, UNAVAILABLE, WITHDRAW_WHILE_ASKED, FAIL }

    private static final Set<UUID> SUSPENDED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Set<CreditSourceKind>> WITHDRAWN = new ConcurrentHashMap<>();
    private static final Map<UUID, Answer> BUREAU_ANSWERS = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> BUREAU_PULLS = new ConcurrentHashMap<>();

    private static final CreditConsentGate<Connection> GATE =
            (uow, party, kind) -> !WITHDRAWN.getOrDefault(party, Set.of()).contains(kind);

    private static final CreditPartyStanding<Connection> STANDING = new CreditPartyStanding<>() {
        @Override
        public PartyFacts facts(Connection unitOfWork, UUID partyId) {
            return new PartyFacts(Optional.empty(), Optional.empty(), 1);
        }

        @Override
        public boolean inGoodStanding(Connection unitOfWork, UUID partyId) {
            return !SUSPENDED.contains(partyId);
        }
    };

    private static final CreditBureau BUREAU = new CreditBureau() {
        @Override
        public String code() {
            return "bureau-test";
        }

        @Override
        public CreditDataAnswer pull(CreditDataPull request) {
            UUID party = UUID.fromString(request.subjectReference());
            int pull = BUREAU_PULLS.computeIfAbsent(party, ignored -> new AtomicInteger()).incrementAndGet();
            Answer answer = BUREAU_ANSWERS.getOrDefault(party, Answer.FRESH);
            return switch (answer) {
                case UNAVAILABLE -> new CreditDataAnswer.Unavailable(CreditDataAnswer.UnavailableCause.TIMEOUT, Optional.empty());
                case FAIL -> throw new IllegalStateException("the instance died mid-ask");
                case WITHDRAW_WHILE_ASKED -> {
                    WITHDRAWN.computeIfAbsent(party, ignored -> ConcurrentHashMap.newKeySet()).add(CreditSourceKind.BUREAU);
                    yield received(Instant.now(), bureau());
                }
                case STALE_ONCE -> received(pull == 1 ? Instant.now().minus(Duration.ofDays(40)) : Instant.now(), bureau());
                case FRESH -> received(Instant.now(), bureau());
            };
        }
    };

    private static final FinancialDataProvider FINDATA = new FinancialDataProvider() {
        @Override
        public String code() {
            return "findata-test";
        }

        @Override
        public CreditDataAnswer pull(CreditDataPull request) {
            AttributeProvenance provenance = new AttributeProvenance.Provider(CreditSourceKind.FINANCIAL_DATA, code(), 1);
            return received(Instant.now(), List.of(
                    new CreditAttribute(CreditAttributeCode.FINDATA_MONTHLY_INCOME,
                            new AttributeValue.MoneyValue(Money.ofMinorUnits(330_000, EUR)), provenance),
                    new CreditAttribute(CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE,
                            new AttributeValue.MoneyValue(Money.ofMinorUnits(120_000, EUR)), provenance)));
        }
    };

    private static List<CreditAttribute> bureau() {
        AttributeProvenance provenance = new AttributeProvenance.Provider(CreditSourceKind.BUREAU, "bureau-test", 1);
        return List.of(
                new CreditAttribute(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, new AttributeValue.IntegerValue(740), provenance),
                new CreditAttribute(CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS, new AttributeValue.IntegerValue(3), provenance),
                new CreditAttribute(CreditAttributeCode.BUREAU_DELINQUENCIES_24M, new AttributeValue.IntegerValue(0), provenance),
                new CreditAttribute(CreditAttributeCode.BUREAU_DEFAULTS_72M, new AttributeValue.IntegerValue(0), provenance),
                new CreditAttribute(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG, new AttributeValue.BooleanValue(false), provenance),
                new CreditAttribute(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS,
                        new AttributeValue.MoneyValue(Money.ofMinorUnits(35_000, EUR)), provenance),
                new CreditAttribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE,
                        new AttributeValue.MoneyValue(Money.ofMinorUnits(420_050, EUR)), provenance));
    }

    private static CreditDataAnswer received(Instant retrievedAt, List<CreditAttribute> attributes) {
        return new CreditDataAnswer.Received(attributes.get(0).provenance() instanceof AttributeProvenance.Provider provider
                ? provider.providerCode() : "test", 1, retrievedAt, attributes,
                new CreditEvidence("{\"answer\":\"test\"}".getBytes(StandardCharsets.UTF_8)));
    }

    /** An instance: credit's real progress over the stores, with this case's ports and {@code clock}. */
    private static DecisionProgress instance(CreditDataCollection.Timing timing, Clock clock) {
        CreditDataCollection collection = collection(timing, clock);
        SnapshotFreezer freezer = new SnapshotFreezer(new JdbcDecisionSnapshotStore(), collection, STANDING,
                new NotAssessedUntilPhase13(), new com.finapp.credit.JdbcReservedExposure(), new NoLoansUntilPhase11(), IDS);
        return new DecisionProgress(TRANSACTIONS, REQUESTS, POLICIES, SCORECARDS, new JdbcDecisionSnapshotStore(),
                collection, freezer,
                new CreditAssessments(new JdbcCreditAssessmentStore(), SCORECARDS, new JdbcOutboxWriter(), IDS, clock),
                new PolicyEvaluations(new JdbcPolicyEvaluationStore(), POLICIES, EngineVersions.STANDARD, IDS, clock),
                STANDING, GATE, new JdbcOutboxWriter(), IDS, clock,
                // The deciding step is CreditDecisionDatabaseTest's (P10-TSK-016); this suite drives to EVALUATED.
                (id, correlation) -> com.finapp.credit.Decider.Decided.NOTHING);
    }

    private static DecisionProgress instance() {
        return instance(TIMING, CLOCK);
    }

    private static CreditDataCollection collection(CreditDataCollection.Timing timing, Clock clock) {
        return new CreditDataCollection(new JdbcCreditDataRequestStore(),
                CreditDataCollection.Sources.of(BUREAU, timing, FINDATA, timing), GATE,
                new CreditEvidenceCipher(new byte[32], 1, new SecureRandom()), CreditDataObserver.NONE,
                new JdbcAuditWriter(), new JdbcOutboxWriter(), TRANSACTIONS, IDS, clock);
    }

    @BeforeAll
    static void inForce() {
        Actor officer = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
        TRANSACTIONS.inTransaction(uow -> {
            for (CreditPolicyVersionId seed : List.of(LOAN_SEED, LINE_SEED)) {
                if (POLICIES.policy(uow, seed).orElseThrow().row().status() == CreditPolicyStatus.PROPOSED) {
                    POLICY_ADMINISTRATION.approve(uow, seed, officer, "v1 reviewed", CorrelationId.generate(IDS));
                }
            }
            ScorecardAdministration scorecards = new ScorecardAdministration(SCORECARDS, new JdbcAuditWriter(),
                    new JdbcOutboxWriter(), IDS, CLOCK);
            if (SCORECARDS.model(uow, SCORECARD_SEED).orElseThrow().row().status() == ScorecardStatus.PROPOSED) {
                scorecards.approve(uow, SCORECARD_SEED, officer, "v1 reviewed", CorrelationId.generate(IDS));
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ the counted races

    @Test
    @DisplayName("ten sweepers drive one request to EVALUATED - each edge once, one snapshot, one assessment, one"
            + " evaluation, one data request and one pull per source kind, counted")
    void tenSweepersTakeOneStepPerState() throws Exception {
        UUID party = UUID.randomUUID();
        UUID id = request(party, CreditProduct.PERSONAL_LOAN.requestValidity());
        race(10, () -> {
            DecisionProgress sweeper = instance();
            for (int round = 0; round < 200 && !"EVALUATED".equals(status(id)); round++) {
                try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
                    for (DecisionRequestId claimed : sweeper.claimDue(20, Duration.ofMillis(100))) {
                        sweeper.step(claimed, CorrelationId.generate(IDS));
                    }
                }
                Thread.sleep(20);
            }
            return null;
        });
        assertThat(status(id)).isEqualTo("EVALUATED");
        assertThat(edges(id)).containsExactlyInAnyOrder("null->SUBMITTED", "SUBMITTED->COLLECTING", "COLLECTING->READY",
                "READY->EVALUATED");
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.credit_assessment WHERE decision_request_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.policy_evaluation WHERE decision_request_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ? AND source_kind = 'BUREAU'",
                id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ?"
                + " AND source_kind = 'FINANCIAL_DATA'", id)).isEqualTo(1);
        assertThat(BUREAU_PULLS.get(party).get()).as("one pull at the bureau").isEqualTo(1);
    }

    @Test
    @DisplayName("a crash after the data requests opened: the asks never made, the retry sweep asks, another instance"
            + " carries the request on")
    void aCrashAfterSubmissionIsDrivenByAnotherInstance() throws Exception {
        UUID party = UUID.randomUUID();
        UUID id = request(party, CreditProduct.PERSONAL_LOAN.requestValidity());
        BUREAU_ANSWERS.put(party, Answer.FAIL);
        CreditDataCollection.Timing quick = new CreditDataCollection.Timing(Duration.ofMillis(200), Duration.ofMinutes(30));
        assertThat(step(instance(quick, CLOCK), id)).isEqualTo(DecisionProgress.Step.COLLECTING);
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ? AND status = 'REQUESTED'"
                + " AND source_kind = 'BUREAU'", id)).as("opened, never answered").isEqualTo(1);
        BUREAU_ANSWERS.put(party, Answer.FRESH);
        awaitDatabase("SELECT bool_and(next_attempt_at <= statement_timestamp() - interval '3 seconds') FROM credit.data_request"
                + " WHERE decision_request_id = ? AND status = 'REQUESTED'", id);
        CreditDataCollection other = collection(quick, CLOCK);
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            for (var due : other.claimDue(50)) {
                other.retry(due, CorrelationId.generate(IDS));
            }
        }
        DecisionProgress another = instance();
        assertThat(step(another, id)).as(dump(id)).isEqualTo(DecisionProgress.Step.READY);
        assertThat(step(another, id)).isEqualTo(DecisionProgress.Step.EVALUATED);
        assertThat(edges(id)).containsExactlyInAnyOrder("null->SUBMITTED", "SUBMITTED->COLLECTING", "COLLECTING->READY",
                "READY->EVALUATED");
    }

    @Test
    @DisplayName("a crash after the data arrived: ten instances step the READY request at once - one freeze, one"
            + " evaluation, one edge")
    void aCrashAfterDataReceivedFreezesOnce() throws Exception {
        UUID id = request(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN.requestValidity());
        DecisionProgress first = instance();
        assertThat(step(first, id)).isEqualTo(DecisionProgress.Step.COLLECTING);
        assertThat(step(first, id)).as(dump(id)).isEqualTo(DecisionProgress.Step.READY);
        List<DecisionProgress.Step> steps = race(10, () -> step(instance(), id));
        assertThat(steps.stream().filter(step -> step == DecisionProgress.Step.EVALUATED)).hasSize(1);
        assertThat(steps).allSatisfy(step -> assertThat(step).isIn(DecisionProgress.Step.EVALUATED,
                DecisionProgress.Step.WAITING, DecisionProgress.Step.NOTHING));
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.policy_evaluation WHERE decision_request_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.decision_request_event WHERE decision_request_id = ?"
                + " AND from_status = 'READY'", id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ missing data, withdrawn consent, stale data

    @Test
    @DisplayName("a source unavailable past its deadline: READY with the source ABSENT, and the pinned policy's"
            + " fallback decides - REFER, then DECLINE - never APPROVE though every other rule would approve")
    void aSourceUnavailablePastItsDeadlineFallsBack() throws Exception {
        CreditDataCollection.Timing shortWindow = new CreditDataCollection.Timing(Duration.ofSeconds(1), Duration.ofSeconds(2));
        for (UnavailableFallback fallback : List.of(UnavailableFallback.REFER, UnavailableFallback.DECLINE)) {
            // No score rule: with the bureau missing every remaining rule reads ABSENT data and none triggers - "every
            // other rule would approve" - so the fallback alone decides (v1's floor would decline on the scorecard's
            // ABSENT bands first).
            CreditPolicyVersionId pinned = inForce(fallback, 900, false);
            UUID party = UUID.randomUUID();
            UUID id = request(party, CreditProduct.PERSONAL_LOAN.requestValidity());
            BUREAU_ANSWERS.put(party, Answer.UNAVAILABLE);
            DecisionProgress progress = instance(shortWindow, CLOCK);
            assertThat(step(progress, id)).isEqualTo(DecisionProgress.Step.COLLECTING);
            assertThat(step(progress, id)).as("unavailable, but before its deadline").isEqualTo(DecisionProgress.Step.WAITING);
            awaitDatabase("SELECT bool_and(deadline_at <= statement_timestamp() - interval '3 seconds') FROM credit.data_request"
                    + " WHERE decision_request_id = ?", id);
            assertThat(step(progress, id)).as(dump(id)).isEqualTo(DecisionProgress.Step.READY);
            assertThat(step(progress, id)).isEqualTo(DecisionProgress.Step.EVALUATED);
            assertThat(scalar("SELECT policy_version_id::text FROM credit.policy_evaluation WHERE decision_request_id = ?",
                    id)).isEqualTo(pinned.value().toString());
            assertThat(scalar("SELECT outcome FROM credit.policy_evaluation WHERE decision_request_id = ?", id))
                    .isEqualTo(fallback.name());
            assertThat(scalar("SELECT array_to_string(reason_codes, ',') FROM credit.policy_evaluation"
                    + " WHERE decision_request_id = ?", id)).isEqualTo("CRD-SOURCE-UNAVAILABLE");
        }
    }

    @Test
    @DisplayName("consent withdrawn mid-collection: the data request ends CONSENT_WITHDRAWN and the request is ABANDONED"
            + " with that reason - nothing frozen")
    void consentWithdrawnMidCollectionAbandonsTheRequest() throws Exception {
        UUID party = UUID.randomUUID();
        UUID id = request(party, CreditProduct.PERSONAL_LOAN.requestValidity());
        BUREAU_ANSWERS.put(party, Answer.WITHDRAW_WHILE_ASKED);
        DecisionProgress progress = instance();
        assertThat(step(progress, id)).isEqualTo(DecisionProgress.Step.COLLECTING);
        assertThat(scalar("SELECT status FROM credit.data_request WHERE decision_request_id = ? AND source_kind = 'BUREAU'",
                id)).isEqualTo("CONSENT_WITHDRAWN");
        assertThat(step(progress, id)).isEqualTo(DecisionProgress.Step.ABANDONED);
        assertClosed(id, "ABANDONED", "CONSENT_WITHDRAWN");
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", id)).isZero();
    }

    @Test
    @DisplayName("consent withdrawn after the source answered: the READY request is ABANDONED before the freeze -"
            + " nothing frozen, nothing evaluated")
    void consentWithdrawnAfterTheSourceAnsweredAbandonsBeforeTheFreeze() throws Exception {
        UUID party = UUID.randomUUID();
        UUID id = request(party, CreditProduct.PERSONAL_LOAN.requestValidity());
        DecisionProgress progress = instance();
        step(progress, id);
        assertThat(step(progress, id)).as(dump(id)).isEqualTo(DecisionProgress.Step.READY);
        WITHDRAWN.computeIfAbsent(party, ignored -> ConcurrentHashMap.newKeySet()).add(CreditSourceKind.FINANCIAL_DATA);
        assertThat(step(progress, id)).isEqualTo(DecisionProgress.Step.ABANDONED);
        assertClosed(id, "ABANDONED", "CONSENT_WITHDRAWN");
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM credit.policy_evaluation WHERE decision_request_id = ?", id)).isZero();
    }

    @Test
    @DisplayName("a record stale at the freeze re-collects under a new reference - READY -> COLLECTING - and the fresh"
            + " one decides")
    void aStaleRecordAtTheFreezeReCollects() throws Exception {
        UUID party = UUID.randomUUID();
        UUID id = request(party, CreditProduct.PERSONAL_LOAN.requestValidity());
        BUREAU_ANSWERS.put(party, Answer.STALE_ONCE);
        DecisionProgress progress = instance();
        step(progress, id);
        assertThat(step(progress, id)).as(dump(id)).isEqualTo(DecisionProgress.Step.READY);
        assertThat(step(progress, id)).isEqualTo(DecisionProgress.Step.RECOLLECTING);
        assertThat(count("SELECT count(*) FROM credit.decision_snapshot WHERE decision_request_id = ?", id)).isZero();
        assertThat(step(progress, id)).as(dump(id)).isEqualTo(DecisionProgress.Step.READY);
        assertThat(step(progress, id)).isEqualTo(DecisionProgress.Step.EVALUATED);
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ? AND source_kind = 'BUREAU'",
                id)).as("a new reference for the stale source").isEqualTo(2);
        assertThat(edges(id)).containsExactlyInAnyOrder("null->SUBMITTED", "SUBMITTED->COLLECTING", "COLLECTING->READY",
                "READY->COLLECTING", "COLLECTING->READY", "READY->EVALUATED");
    }

    // ------------------------------------------------------------------ the clock and the standing

    @Test
    @DisplayName("expiry races the evaluation step at the boundary: ten instances, exactly one edge out of READY")
    void expiryRacesTheEvaluationStepExactlyOne() throws Exception {
        UUID id = request(UUID.randomUUID(), Duration.ofSeconds(4));
        DecisionProgress progress = instance();
        step(progress, id);
        assertThat(step(progress, id)).as(dump(id)).isEqualTo(DecisionProgress.Step.READY);
        long remaining = Long.parseLong(scalar("SELECT (extract(epoch FROM expires_at - statement_timestamp()) * 1000)"
                + "::bigint::text FROM credit.decision_request WHERE id = ?", id));
        Thread.sleep(Math.max(0, remaining - 5));
        List<DecisionProgress.Step> steps = race(10, () -> step(instance(), id));
        assertThat(count("SELECT count(*) FROM credit.decision_request_event WHERE decision_request_id = ?"
                + " AND from_status = 'READY'", id)).as("exactly one of EVALUATED and EXPIRED, taken once").isEqualTo(1);
        assertThat(steps.stream().filter(step -> step == DecisionProgress.Step.EVALUATED
                || step == DecisionProgress.Step.EXPIRED)).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a party suspended mid-request is ABANDONED with STANDING_LOST - never EXPIRED, even when also expired")
    void aSuspendedPartyIsAbandonedStandingLostNeverExpired() throws Exception {
        UUID party = UUID.randomUUID();
        UUID id = request(party, Duration.ofSeconds(1));
        DecisionProgress progress = instance();
        assertThat(step(progress, id)).isEqualTo(DecisionProgress.Step.COLLECTING);
        SUSPENDED.add(party);
        awaitExpiry(id);
        assertThat(step(progress, id)).isEqualTo(DecisionProgress.Step.ABANDONED);
        assertClosed(id, "ABANDONED", "STANDING_LOST");
    }

    @Test
    @DisplayName("a skewed sweeper neither expires early nor late: the expiry is the database's, whatever the"
            + " instance's clock says")
    void aSkewedSweeperNeitherExpiresEarlyNorLate() throws Exception {
        UUID id = request(UUID.randomUUID(), Duration.ofSeconds(3));
        Clock ahead = Clock.offset(Clock.system(ZoneOffset.UTC), Duration.ofSeconds(5));
        Clock behind = Clock.offset(Clock.system(ZoneOffset.UTC), Duration.ofSeconds(-5));
        assertThat(step(instance(TIMING, ahead), id)).as("five seconds fast: not expired yet")
                .isEqualTo(DecisionProgress.Step.COLLECTING);
        awaitExpiry(id);
        String seen = scalar("SELECT status || ' expires=' || expires_at::text || ' now=' || statement_timestamp()::text"
                + " || ' expired=' || (expires_at <= statement_timestamp())::text FROM credit.decision_request WHERE id = ?", id);
        assertThat(step(instance(TIMING, behind), id)).as("five seconds slow: expired all the same - " + seen + " / after: "
                        + scalar("SELECT status || ' now=' || statement_timestamp()::text FROM credit.decision_request WHERE id = ?", id))
                .isEqualTo(DecisionProgress.Step.EXPIRED);
        assertClosed(id, "EXPIRED", null);
    }

    @Test
    @DisplayName("undue work never starves due work: a claim re-stamps its page, so the next claim takes the rest -"
            + " disjoint, oldest permit first, the undue left alone")
    void undueWorkNeverStarvesDueWork() throws Exception {
        try (Connection owner = DatabaseRoles.migrator(); PreparedStatement push = owner.prepareStatement(
                "UPDATE credit.decision_request SET next_step_at = statement_timestamp() + interval '1 hour'"
                        + " WHERE status IN ('SUBMITTED', 'COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW')")) {
            push.executeUpdate(); // every earlier case's leftovers out of the way
        }
        Set<UUID> due = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            due.add(request(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN.requestValidity()));
        }
        Set<UUID> undue = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            UUID later = request(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN.requestValidity());
            try (Connection app = DatabaseRoles.application(); PreparedStatement push = app.prepareStatement(
                    "UPDATE credit.decision_request SET next_step_at = statement_timestamp() + interval '1 hour' WHERE id = ?")) {
                push.setObject(1, later);
                push.executeUpdate();
            }
            undue.add(later);
        }
        DecisionProgress progress = instance();
        Set<UUID> first = ids(progress.claimDue(5, Duration.ofMinutes(5)));
        Set<UUID> second = ids(progress.claimDue(5, Duration.ofMinutes(5)));
        Set<UUID> third = ids(progress.claimDue(5, Duration.ofMinutes(5)));
        assertThat(first).hasSize(5);
        assertThat(second).hasSize(5).doesNotContainAnyElementsOf(first);
        Set<UUID> both = new HashSet<>(first);
        both.addAll(second);
        assertThat(both).containsExactlyInAnyOrderElementsOf(due);
        assertThat(third).as("nothing else is due").isEmpty();
    }

    // ------------------------------------------------------------------ the pin

    @Test
    @DisplayName("the pinned policy decides: an activation mid-collection changes nothing the request is evaluated under")
    void thePinnedPolicyDecidesAfterAnActivationMidCollection() throws Exception {
        CreditPolicyVersionId pinned = inForce(UnavailableFallback.REFER, 905, true);
        UUID id = request(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN.requestValidity());
        DecisionProgress progress = instance();
        assertThat(step(progress, id)).isEqualTo(DecisionProgress.Step.COLLECTING);
        CreditPolicyVersionId successor = inForce(UnavailableFallback.REFER, 915, true);
        assertThat(successor).isNotEqualTo(pinned);
        assertThat(step(progress, id)).as(dump(id)).isEqualTo(DecisionProgress.Step.READY);
        assertThat(step(progress, id)).isEqualTo(DecisionProgress.Step.EVALUATED);
        assertThat(scalar("SELECT pinned_policy_version_id::text FROM credit.decision_request WHERE id = ?", id))
                .isEqualTo(pinned.value().toString());
        assertThat(scalar("SELECT policy_version_id::text FROM credit.decision_snapshot WHERE decision_request_id = ?", id))
                .isEqualTo(pinned.value().toString());
        assertThat(scalar("SELECT policy_version_id::text FROM credit.policy_evaluation WHERE decision_request_id = ?", id))
                .as("evaluated under the retired, pinned version (INV-HIST-04)").isEqualTo(pinned.value().toString());
        assertThat(scalar("SELECT status FROM credit.credit_policy_version WHERE id = ?", pinned.value())).isEqualTo("RETIRED");
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * A PERSONAL_LOAN version in force with {@code fallback} and stress rate {@code rate} - v1's rules, without its three
     * score rules unless {@code scoreRules} - activated by two persons.
     */
    private static CreditPolicyVersionId inForce(UnavailableFallback fallback, int rate, boolean scoreRules) {
        return TRANSACTIONS.inTransaction(uow -> {
            CreditPolicy v1 = POLICIES.policy(uow, LOAN_SEED).orElseThrow().policy();
            List<CreditPolicy.PolicyRule> rules = new ArrayList<>(v1.rules());
            if (!scoreRules) {
                rules.removeIf(rule -> rule.subject() instanceof CreditPolicy.Subject.Figure figure
                        && figure.figure() == com.finapp.credit.PolicyFigure.SCORE);
            }
            CreditPolicy.PolicyRule fallbackRule = rules.get(0);
            rules.set(0, new CreditPolicy.PolicyRule(fallbackRule.ruleCode(), fallbackRule.subject(), fallbackRule.operator(),
                    fallbackRule.operand(), fallback == UnavailableFallback.DECLINE ? PolicyEffect.DECLINE : PolicyEffect.REFER,
                    fallbackRule.cap(), fallbackRule.reason()));
            CreditPolicy policy = new CreditPolicy(v1.product(), rate, v1.minimumDisposable(), v1.minimumPaymentRatioBps(),
                    v1.maximumExposure(), v1.maximumDataAge(), fallback, v1.autoApprovalCeiling(), rules);
            Actor proposer = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
            CreditPolicyAdministration.Proposed proposed =
                    POLICY_ADMINISTRATION.propose(uow, policy, "a case's version", proposer, CorrelationId.generate(IDS));
            POLICY_ADMINISTRATION.approve(uow, proposed.id(), new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE),
                    "a case's activation", CorrelationId.generate(IDS));
            return proposed.id();
        });
    }

    /** A request for {@code party} - born SUBMITTED through the store, valid for {@code validity}. */
    private static UUID request(UUID party, Duration validity) {
        return TRANSACTIONS.inTransaction(uow -> {
            var profile = new JdbcCreditProfiles(IDS).ensure(uow, party);
            DecisionRequestId id = DecisionRequestId.next(IDS);
            REQUESTS.insert(uow, id, party, profile.id(), new DecisionRequest.Application(CreditProduct.PERSONAL_LOAN,
                    Money.ofMinorUnits(1_000_000, EUR), Optional.of(36), Optional.empty(), Optional.empty()), validity,
                    "orchestration-case", new Actor(party.toString(), ActorType.CUSTOMER));
            return id.value();
        });
    }

    @SuppressWarnings("try")
    private static DecisionProgress.Step step(DecisionProgress progress, UUID id) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            return progress.step(DecisionRequestId.of(id), CorrelationId.generate(IDS));
        }
    }

    private static Set<UUID> ids(List<DecisionRequestId> claimed) {
        Set<UUID> ids = new HashSet<>();
        claimed.forEach(id -> ids.add(id.value()));
        return ids;
    }

    private static String status(UUID id) throws SQLException {
        return scalar("SELECT status FROM credit.decision_request WHERE id = ?", id);
    }

    /**
     * The request's edges, as a multiset: the trigger already forces their order, and the rows' occurred_at cannot -
     * a database clock corrected backwards (a Docker VM's, seen here) misorders rows written by separate transactions.
     */
    private static List<String> edges(UUID id) throws SQLException {
        List<String> edges = new ArrayList<>();
        try (Connection owner = DatabaseRoles.migrator(); PreparedStatement select = owner.prepareStatement(
                "SELECT from_status, to_status FROM credit.decision_request_event WHERE decision_request_id = ?"
                        + " ORDER BY occurred_at, (from_status IS NOT NULL)")) {
            select.setObject(1, id);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    edges.add(rows.getString(1) + "->" + rows.getString(2));
                }
            }
        }
        return edges;
    }

    /**
     * Waits until the database itself says {@code booleanQuery} - never the JVM's clock: the container's clock may run
     * behind the host's, and a Docker VM's is corrected in steps, sometimes backwards; every window here is the
     * database's (`P10-TSK-015`'s own finding). The queries carry a three-second margin, so two transactions in a row cannot
     * straddle the instant they wait for.
     */
    private static void awaitDatabase(String booleanQuery, Object... parameters) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!"t".equals(scalar(booleanQuery, parameters)) && !"true".equals(scalar(booleanQuery, parameters))) {
            assertThat(System.nanoTime()).as("the database never said: " + booleanQuery).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private static void awaitExpiry(UUID id) throws Exception {
        awaitDatabase("SELECT expires_at <= statement_timestamp() - interval '3 seconds' FROM credit.decision_request WHERE id = ?", id);
    }

    /** The request's data requests as the database holds them now - a readiness assertion's diagnostic. */
    private static String dump(UUID id) throws SQLException {
        return scalar("SELECT string_agg(source_kind || ':' || status || ':past=' || (deadline_at <= statement_timestamp())::text"
                + " || ':attempts=' || attempts::text, ', ' ORDER BY requested_at) FROM credit.data_request"
                + " WHERE decision_request_id = ?", id);
    }

    private static void assertClosed(UUID id, String status, String reason) throws SQLException {
        assertThat(status(id)).isEqualTo(status);
        assertThat(scalar("SELECT closure_reason FROM credit.decision_request WHERE id = ?", id)).isEqualTo(reason);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditDecisionRequestClosed'"
                + " AND aggregate_id = ?", id)).as("one closed event").isEqualTo(1);
    }

    private static long count(String sql, Object... parameters) throws SQLException {
        return Long.parseLong(scalar(sql, parameters));
    }

    private static String scalar(String sql, Object... parameters) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator(); PreparedStatement select = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
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
                outcomes.add(outcome.get(3, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
