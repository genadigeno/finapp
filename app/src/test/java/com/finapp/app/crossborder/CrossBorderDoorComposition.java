package com.finapp.app.crossborder;

import com.finapp.app.risk.CrossBorderRiskJudgement;
import com.finapp.app.risk.RiskCrossBorderLimitCheck;
import com.finapp.app.risk.RiskCrossBorderRiskDecision;
import com.finapp.app.risk.RiskCrossBorderStepUpAdvice;
import com.finapp.app.risk.RiskEvaluationWorld;
import com.finapp.app.telemetry.CrossBorderMetrics;
import com.finapp.crossborder.BeneficiaryId;
import com.finapp.crossborder.BeneficiaryStatus;
import com.finapp.crossborder.BeneficiaryStore;
import com.finapp.crossborder.BeneficiaryVocabulary;
import com.finapp.crossborder.CancellationStore;
import com.finapp.crossborder.CorridorAvailabilityStore;
import com.finapp.crossborder.CorridorKey;
import com.finapp.crossborder.CorridorPolicyId;
import com.finapp.crossborder.CorridorPolicyStatus;
import com.finapp.crossborder.CorridorPolicyStore;
import com.finapp.crossborder.CorridorTerms;
import com.finapp.crossborder.CounterpartyScreening;
import com.finapp.crossborder.CrossBorderExecution;
import com.finapp.crossborder.CrossBorderFx;
import com.finapp.crossborder.CrossBorderInstruction;
import com.finapp.crossborder.OfferStore;
import com.finapp.crossborder.PaymentAuthorization;
import com.finapp.crossborder.PaymentCancellation;
import com.finapp.crossborder.PaymentStore;
import com.finapp.crossborder.RequiredData;
import com.finapp.crossborder.TransactionRunner;
import com.finapp.identity.Identity;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStatus;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The cross-border payment door composed with nothing behind it (`P13-TSK-011`, for {@code CrossBorderDoorAdviceTest}):
 * the real {@link CrossBorderPaymentDesk} and the real {@link PaymentAuthorization}, the real risk adapters and door
 * advice over a real {@code RiskEvaluation} on in-memory stores ({@link RiskEvaluationWorld}), crossborder's stores as
 * in-memory answers, an in-memory claim store under the real {@link IdempotentExecutor}, and fx's acceptance refusing -
 * so a permitted authorization ends at the acceptance, rolled back to its savepoint, and nothing past it ever runs. Every
 * transaction is one recording connection: the savepoint set and any rollback to it are observable, and the one SQL
 * statement the authorization runs itself - the database clock - is answered with the fixed clock.
 */
final class CrossBorderDoorComposition {

    static final Clock CLOCK = RiskEvaluationWorld.CLOCK;
    static final CurrencyCode EUR = CurrencyCode.of("EUR");

    final RiskEvaluationWorld world = new RiskEvaluationWorld();
    final UUID party = RiskEvaluationWorld.IDS.next();
    final IdentityId identity = IdentityId.of(RiskEvaluationWorld.IDS.next());
    final UUID sourceAccount = RiskEvaluationWorld.IDS.next();
    final Map<UUID, OfferStore.OfferRow> offers = new HashMap<>();
    final Map<String, IdempotencyRecord> records = new HashMap<>();
    /** One entry per transaction opened ("tx"), then the connection's savepoint calls in order. */
    final List<String> journal = new ArrayList<>();

    boolean enrolled;
    boolean walletHeld = true;

    private final UUID screening = RiskEvaluationWorld.IDS.next();
    private final CorridorPolicyId corridorPolicy = CorridorPolicyId.of(RiskEvaluationWorld.IDS.next());
    private final Map<BeneficiaryId, CorridorKey> beneficiaries = new HashMap<>();
    final CrossBorderPaymentDesk desk;

    CrossBorderDoorComposition() {
        CrossBorderMovements movements = movements();
        CrossBorderRiskJudgement judgement = new CrossBorderRiskJudgement(world.evaluation, movements);
        CrossBorderFx fx = refusing(CrossBorderFx.class, "acceptWithin",
                args -> {
                    throw new CrossBorderFx.FxRefused("QUOTE_EXPIRED");
                });
        CrossBorderExecution execution = refusing(CrossBorderExecution.class, null, null);
        PaymentAuthorization authorization = new PaymentAuthorization(payments(), offerStore(), beneficiaryStore(),
                corridorPolicies(), refusing(CorridorAvailabilityStore.class, "isAvailable", args -> true),
                refusing(CounterpartyScreening.class, "clearance",
                        args -> Optional.of(new CounterpartyScreening.Clearance(true, Optional.of(CLOCK.instant()),
                                false))),
                new RiskCrossBorderRiskDecision(judgement), new RiskCrossBorderLimitCheck(judgement), fx, execution,
                refusing(AuditWriter.class, null, null), refusing(OutboxWriter.class, null, null),
                RiskEvaluationWorld.IDS);
        IdempotentExecutor executor = new IdempotentExecutor(claims(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5));
        TransactionRunner transactions = new TransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                journal.add("tx");
                return work.apply(connection());
            }
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CrossBorderMetrics metrics = new CrossBorderMetrics(List.of(), unitOfWork -> Map.of(), () -> {
            throw new SQLException("no database here");
        }, CLOCK, meters);
        CrossBorderDoorStepUp stepUp = new CrossBorderDoorStepUp(
                new RiskCrossBorderStepUpAdvice(world.evaluation, world.assessmentStore), claims(), movements,
                enrolments());
        desk = new CrossBorderPaymentDesk(authorization, fx, execution, identities(), enrolments(), executor,
                transactions, meters, CLOCK,
                new PaymentCancellation(refusing(PaymentStore.class, null, null),
                        refusing(CancellationStore.class, null, null), execution, refusing(OutboxWriter.class, null, null),
                        refusing(AuditWriter.class, null, null), RiskEvaluationWorld.IDS),
                refusing(CancellationStore.class, null, null), metrics, stepUp);
    }

    /** A frozen offer of the caller's on {@code corridor}: the quote's {@code source} and {@code destination}. */
    UUID offer(CorridorKey corridor, Money source, Money destination) {
        UUID quote = RiskEvaluationWorld.IDS.next();
        BeneficiaryId beneficiary = BeneficiaryId.of(RiskEvaluationWorld.IDS.next());
        beneficiaries.put(beneficiary, corridor);
        Money fee = Money.ofMinorUnits(2_50, corridor.source());
        offers.put(quote, new OfferStore.OfferRow(RiskEvaluationWorld.IDS.next(), RiskEvaluationWorld.IDS.next(), quote,
                party, beneficiary, corridorPolicy, corridor, fee, source, source.plus(fee), destination, 24,
                CLOCK.instant()));
        return quote;
    }

    /** The door's claim key for {@code clientKey}, as the desk builds it for the acting customer. */
    IdempotencyKey key(String clientKey) {
        return new IdempotencyKey(CrossBorderPaymentDesk.SCOPE + "CUSTOMER:" + identity.value(), clientKey);
    }

    /** A completed record of {@code key}: the claim's response of record, {@code ERR|XB|code}. */
    void recorded(IdempotencyKey key, UUID quote, String code) {
        records.put(key.scope() + "|" + key.key(), new IdempotencyRecord(key,
                RequestFingerprint.sha256(("crossborder.payment|" + party + "|" + quote).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)), IdempotencyState.FAILED,
                StoredResponse.of(("ERR|XB|" + code).getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain"),
                CorrelationId.of("recorded"), CLOCK.instant(), CLOCK.instant().plusSeconds(86_400)));
    }

    // ------------------------------------------------------------------ the collaborators

    private CrossBorderMovements movements() {
        return new CrossBorderMovements() {
            @Override
            public Movement locked(Connection unitOfWork, CrossBorderInstruction instruction) {
                return movement(offers.get(instruction.quoteId()));
            }

            @Override
            public Optional<Movement> owned(Connection unitOfWork, UUID owner, UUID quote) {
                return Optional.ofNullable(offers.get(quote)).filter(offer -> offer.owner().equals(owner))
                        .map(offer -> movement(offer));
            }
        };
    }

    private CrossBorderMovements.Movement movement(OfferStore.OfferRow offer) {
        return new CrossBorderMovements.Movement(offer.owner(), offer.quoteId(), offer.corridor(),
                offer.beneficiary().value(), offer.source(), offer.destination(),
                walletHeld ? Optional.of(sourceAccount) : Optional.empty());
    }

    private PaymentStore payments() {
        return refusing(PaymentStore.class, "byDispatchKey", args -> Optional.empty());
    }

    private OfferStore offerStore() {
        return refusing(OfferStore.class, "offerOwned", args -> Optional.ofNullable(offers.get((UUID) args[1]))
                .filter(offer -> offer.owner().equals(args[2])));
    }

    private BeneficiaryStore beneficiaryStore() {
        return refusing(BeneficiaryStore.class, "lockOwnedForShare", args -> {
            BeneficiaryId id = (BeneficiaryId) args[1];
            CorridorKey corridor = beneficiaries.get(id);
            return Optional.of(new BeneficiaryStore.BeneficiaryRow(id, (UUID) args[2], RiskEvaluationWorld.IDS.next(),
                    "corridor-sim-a", "acct-1", "0001", BeneficiaryVocabulary.PayeeCheck.MATCH, false,
                    corridor.country(), corridor.destination(), BeneficiaryVocabulary.EntityType.INDIVIDUAL, "Osaka",
                    BeneficiaryStatus.ACTIVE, screening, CLOCK.instant(), Optional.empty()));
        });
    }

    private CorridorPolicyStore corridorPolicies() {
        return refusing(CorridorPolicyStore.class, "version", args -> Optional.of(new CorridorPolicyStore.VersionView(
                new CorridorPolicyStore.VersionRow(corridorPolicy, 1, CorridorPolicyStatus.ACTIVE, "controller-a",
                        Optional.of("controller-b")),
                CLOCK.instant(), offers.values().stream().map(OfferStore.OfferRow::corridor).distinct()
                        .map(CrossBorderDoorComposition::terms).toList())));
    }

    private static CorridorTerms terms(CorridorKey corridor) {
        return new CorridorTerms(corridor, List.of("corridor-sim-a"), Money.ofMinorUnits(2_50, corridor.source()),
                BigDecimal.ZERO, RoundingPolicy.HALF_EVEN, Money.ofMinorUnits(100_000_00, corridor.destination()),
                Duration.ofDays(7), Duration.ofDays(1), Set.of(RequiredData.BENEFICIARY_NAME, RequiredData.ENTITY_TYPE));
    }

    private IdempotencyRecordStore<Connection> claims() {
        return new IdempotencyRecordStore<>() {
            @Override
            public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint,
                    CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
                String id = key.scope() + "|" + key.key();
                if (records.containsKey(id)) {
                    return ClaimOutcome.ALREADY_CLAIMED;
                }
                records.put(id, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS,
                        StoredResponse.empty(), correlationId, now, expiresAt));
                return ClaimOutcome.CLAIMED;
            }

            @Override
            public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
                return Optional.ofNullable(records.get(key.scope() + "|" + key.key()));
            }

            @Override
            public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState,
                    StoredResponse response, Instant completedAt) {
                IdempotencyRecord claimed = records.get(key.scope() + "|" + key.key());
                records.put(key.scope() + "|" + key.key(), new IdempotencyRecord(key, claimed.fingerprint(),
                        terminalState, response, claimed.correlationId(), claimed.createdAt(), claimed.expiresAt()));
                return true;
            }

            @Override
            public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key,
                    CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
                return false;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private IdentityStore<Connection> identities() {
        return refusing(IdentityStore.class, "findById", args -> Optional.of(Identity.rehydrate(identity, party,
                new LoginIdentifier("door-tester"), IdentityStatus.ACTIVE, CLOCK.instant(), CLOCK.instant())));
    }

    @SuppressWarnings("unchecked")
    private MfaEnrolmentStore<Connection> enrolments() {
        return refusing(MfaEnrolmentStore.class, "findActive",
                args -> enrolled ? Optional.of("an active TOTP factor") : Optional.empty());
    }

    /** The one connection every transaction is handed: savepoints recorded, the database clock answered. */
    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setSavepoint" -> {
                        journal.add("savepoint");
                        yield refusing(Savepoint.class, null, null);
                    }
                    case "rollback" -> {
                        journal.add(args == null ? "rollback" : "rollback to the savepoint");
                        yield null;
                    }
                    case "createStatement" -> clockStatement();
                    case "toString" -> "the door's connection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("the door's connection was used: " + method.getName());
                });
    }

    /** {@code SELECT statement_timestamp()} - {@code DatabaseTime.now}, the authorization's lapse clock. */
    private Statement clockStatement() {
        ResultSet row = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> true;
                    case "getTimestamp" -> Timestamp.from(CLOCK.instant());
                    case "close" -> null;
                    default -> throw new AssertionError("the clock's row is read once: " + method.getName());
                });
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> {
                        if (!"SELECT statement_timestamp()".equals(args[0])) {
                            throw new AssertionError("the only statement is the database clock: " + args[0]);
                        }
                        yield row;
                    }
                    case "close" -> null;
                    default -> throw new AssertionError("the clock's statement runs once: " + method.getName());
                });
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(Object[] args);
    }

    /** {@code type} answering {@code name} by {@code answer}; every other call fails the suite. */
    @SuppressWarnings("unchecked")
    private static <T> T refusing(Class<?> type, String name, Answer answer) {
        return (T) Proxy.newProxyInstance(CrossBorderDoorComposition.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getName().equals(name)) {
                        return answer.answer(args);
                    }
                    return switch (method.getName()) {
                        case "toString" -> type.getSimpleName();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new AssertionError(type.getSimpleName() + "." + method.getName()
                                + " is never reached here");
                    };
                });
    }
}
