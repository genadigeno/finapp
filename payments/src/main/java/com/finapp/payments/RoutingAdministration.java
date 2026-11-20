package com.finapp.payments;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The operator's routing acts (`P7-TSK-003`, ADR-0060 §1/§4), each under
 * {@code PAYMENT_ROUTING_ADMINISTER} at the surface and reasoned here ({@code INV-AUD-03}):
 * creating an immutable policy version, recording a rail's availability, and reading a
 * payment's routing explanation — a privileged read of somebody else's payment, audited
 * like one.
 *
 * <h2>Minting a version number</h2>
 *
 * <p>The fee schedule's discipline verbatim ({@code FeeSchedules#mintVersion} says why there
 * is no lock): the arbiter is `V013`'s {@code UNIQUE (version)}, which is also the queue —
 * a racer waits on the index, is refused on the winner's commit, re-reads and takes the
 * next. Ten instances produce ten distinct, gapless numbers.
 *
 * <h2>Every named rail must be declared — at the write, not the decision</h2>
 *
 * <p>A version naming an undeclared rail, or an availability act on one, is refused as
 * {@link UnknownRailException} before anything is written: an operator's typo should fail
 * the operator, not the payments that later judge the policy. The decision side stays
 * tolerant of the OTHER direction (a policy outliving a build's declarations —
 * {@code RoutingRejection#UNDECLARED_BY_BUILD}), because history may lawfully name rails a
 * later build retired.
 */
@RequiredArgsConstructor
public final class RoutingAdministration {

    /** The fee schedule's bound, for the fee schedule's reason. */
    static final int MAX_MINTING_ATTEMPTS = 16;

    /**
     * Version creation is keyed (`INV-IDEM-01`; the backlog's own line) — where the fee
     * schedule's is deliberately not, on the recorded argument that a duplicated version
     * prices identically. The divergence is deliberate: routing versions are ONE global
     * stream, so a retried creation minting two adjacent identical versions would leave the
     * newer one in force under a number nobody chose — harmless to money, confusing to an
     * explanation — and the platform's one keyed mechanism absorbs exactly that.
     *
     * <p>The claim names its operator ({@code payments.routing.version:<keyedType>:<actorId>},
     * `X-TSK-003`); this bare value is the scope it moved from, consulted until `X-TSK-028`.
     */
    public static final String IDEMPOTENCY_SCOPE = "payments.routing.version";

    /**
     * The rail switch's claim (`P17-TSK-032`, finapp-admin-panel ADR-C135 decision 2): {@code
     * payments.rail.availability:<keyedType>:<actorId>}. The switch converged on the state a rail already had, but its RECORD
     * did not: a lost response retried wrote a second act on the trail and moved the fact's instant. Keyed, the retry answers
     * the original act and records nothing.
     */
    public static final String AVAILABILITY_IDEMPOTENCY_SCOPE = "payments.rail.availability";

    static final String VERSION_TARGET_TYPE = "routing_policy_version";
    static final String RAIL_TARGET_TYPE = "rail";

    /** The operator's new-version request, parsed and bounded by the surface. */
    public record NewVersion(
            List<RoutingPolicyVersion.NewRule> rules,
            Optional<Instant> effectiveFrom,
            String reason) {}

    /** A decision and the version it pinned — the explanation, whole. */
    public record Explanation(RoutingDecision decision, RoutingPolicyVersion version) {}

    /** The keyed creation's answer: the version, and whether this call replayed it. */
    public record CreatedVersion(RoutingPolicyVersion version, boolean replayed) {}

    /** The keyed switch's answer: the act's fact, and whether this call replayed it (`P17-TSK-032`). */
    public record SwitchedAvailability(RailAvailability fact, boolean replayed) {}

    @NonNull private final IdempotentExecutor executor;
    @NonNull private final RoutingStore<Connection> routing;
    @NonNull private final PaymentRails rails;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * Creates an immutable version, effective forward, its number minted under the unique
     * index.
     *
     * @throws UnknownRailException if a rule names a rail this build does not declare
     * @throws BackdatedRoutingPolicyVersionException if it would take effect in the past
     */
    public CreatedVersion createVersion(
            Connection unitOfWork, NewVersion request, String idempotencyKey) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(request.reason(), "reason must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        // Refused before the claim, so a typo consumes no key (the PaymentCreation order).
        for (RoutingPolicyVersion.NewRule rule : request.rules()) {
            for (RailId candidate : rule.rails()) {
                if (!rails.declaredIds().contains(candidate)) {
                    throw new UnknownRailException(candidate);
                }
            }
        }

        // The claim names its principal, the operator (X-TSK-003): another operator's key value
        // no longer refuses this one's.
        IdempotencyKey key =
                new IdempotencyKey(
                        IDEMPOTENCY_SCOPE + ":" + actor.keyedType().name() + ":" + actor.id(),
                        idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(
                        canonicalForm(request, actor).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        // The old scope is consulted for X-TSK-003's transition and goes with X-TSK-028.
                        key.movedFrom(IDEMPOTENCY_SCOPE),
                        fingerprint,
                        uow -> accept(uow, request, actor, correlation));

        UUID versionId =
                UUID.fromString(
                        new String(
                                outcome.body()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "a recorded version creation"
                                                                        + " always names its"
                                                                        + " row")),
                                StandardCharsets.UTF_8));
        RoutingPolicyVersion version =
                routing.findVersionById(unitOfWork, RoutingPolicyVersionId.of(versionId))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "version " + versionId + " was recorded and"
                                                        + " has no row: versions are"
                                                        + " immutable, so this is a wiring"
                                                        + " fault"));
        return new CreatedVersion(version, outcome.replayed());
    }

    /** The acceptance: the minted version and its audit record, one commit under the key. */
    private CommandResult accept(
            Connection uow, NewVersion request, Actor actor, Correlation correlation) {
        Minted minted = mintVersion(uow, request, actor);
        RoutingPolicyVersion version = minted.stored();
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        PaymentsAuditAction.PAYMENT_ROUTING_VERSION_CREATED,
                        VERSION_TARGET_TYPE,
                        version.id().value().toString(),
                        // The operator's own words, verbatim (INV-AUD-03).
                        Optional.of(request.reason()),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and counts - never a ceiling amount (INV-AUD-02).
                        Optional.of(
                                "version=" + version.version()
                                        + ", rules=" + version.rules().size()
                                        + ", " + effectiveFromForAudit(version.effectiveFrom(), minted.asked()))));
        return CommandResult.succeeded(
                StoredResponse.of(
                        version.id().value().toString().getBytes(StandardCharsets.UTF_8),
                        "text/plain"));
    }

    /**
     * What one key must always mean (`INV-IDEM-01`): the actor and the whole stated request.
     * {@code effectiveFrom} enters as stated — empty is the word "now", not the instant it
     * resolves to — so a replay matches its original byte for byte.
     */
    private static String canonicalForm(NewVersion request, Actor actor) {
        return IDEMPOTENCY_SCOPE
                + "|" + actor.id()
                + "|" + request.effectiveFrom().map(Object::toString).orElse("now")
                + "|" + request.reason()
                + "|" + request.rules().stream()
                        .map(rule -> rule.direction()
                                + ";" + rule.instrumentKind()
                                + ";" + rule.currency().map(Object::toString).orElse("*")
                                + ";" + rule.ceiling()
                                        .map(ceiling -> ceiling.minorUnits()
                                                + ":" + ceiling.currency()
                                                + ":" + ceiling.scale())
                                        .orElse("-")
                                + ";" + rule.rails().stream()
                                        .map(RailId::value)
                                        .collect(Collectors.joining(",")))
                        .collect(Collectors.joining("//"));
    }

    /**
     * Records the rail's availability — the newest act wins the row, every act is audited,
     * and setting the state a rail already has converges on that state (the administrative
     * -move idiom's cousin: here the act itself is worth the trail even when nothing moved,
     * because "I checked and held it out of service" is operational evidence).
     *
     * @throws UnknownRailException if this build declares no such rail
     */
    public SwitchedAvailability setAvailability(
            Connection unitOfWork, RailId rail, boolean available, String reason, String idempotencyKey) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(rail, "rail must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        // Refused before the claim, so a typo consumes no key (createVersion's order).
        if (!rails.declaredIds().contains(rail)) {
            throw new UnknownRailException(rail);
        }

        IdempotencyKey key =
                new IdempotencyKey(
                        AVAILABILITY_IDEMPOTENCY_SCOPE + ":" + actor.keyedType().name() + ":" + actor.id(),
                        idempotencyKey);
        // What one key must always mean: the operator, the rail, the state and the reason (INV-IDEM-03).
        String canonical = AVAILABILITY_IDEMPOTENCY_SCOPE + "|" + actor.id() + "|" + rail.value() + "|" + available
                + "|" + reason;
        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        RequestFingerprint.sha256(canonical.getBytes(StandardCharsets.UTF_8)),
                        uow -> {
                            RailAvailability recorded = switchAvailability(uow, rail, available, reason, actor, correlation);
                            // The act's instant is all a replay needs: the rest is the request the key binds.
                            return CommandResult.succeeded(
                                    StoredResponse.of(
                                            recorded.changedAt().toString().getBytes(StandardCharsets.UTF_8),
                                            "text/plain"));
                        });
        Instant changedAt =
                Instant.parse(
                        new String(
                                outcome.body()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "a recorded availability switch always"
                                                                        + " names its instant")),
                                StandardCharsets.UTF_8));
        return new SwitchedAvailability(
                new RailAvailability(rail, available, reason, actor.id(), changedAt), outcome.replayed());
    }

    /** The act under the key: the newest fact on the row and its audit record, one commit. */
    private RailAvailability switchAvailability(
            Connection unitOfWork,
            RailId rail,
            boolean available,
            String reason,
            Actor actor,
            Correlation correlation) {
        RailAvailability fact =
                new RailAvailability(rail, available, reason, actor.id(), Instant.now(clock));
        routing.recordAvailability(unitOfWork, fact);

        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        fact.changedAt(),
                        PaymentsAuditAction.RAIL_AVAILABILITY_CHANGED,
                        RAIL_TARGET_TYPE,
                        rail.value(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of("rail=" + rail.value() + ", available=" + available)));
        return fact;
    }

    /**
     * The payment's routing explanation: the newest decision and the version it pinned. A
     * privileged read of somebody else's payment — audited ({@code INV-AUD-01}).
     *
     * @throws UnknownPaymentException when no decision exists — unknown intent and unrouted
     *     intent answer identically (the one-shape 404: no oracle over other people's
     *     payments)
     */
    public Explanation explain(Connection unitOfWork, PaymentIntentId intent) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(intent, "intent must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        RoutingDecision decision =
                routing.findLatestDecisionForIntent(unitOfWork, intent)
                        .orElseThrow(UnknownPaymentException::new);
        RoutingPolicyVersion version =
                routing.findVersionById(unitOfWork, decision.policyVersionId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "decision " + decision.id() + " pins version "
                                                        + decision.policyVersionId()
                                                        + " and no such row exists: the pin is"
                                                        + " a foreign key, so this is a wiring"
                                                        + " fault"));

        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        PaymentsAuditAction.PAYMENT_ROUTING_EXPLANATION_READ,
                        PaymentCreation.TARGET_TYPE,
                        intent.value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of("intent=" + intent + ", decision=" + decision.id())));
        return new Explanation(decision, version);
    }

    /** A minted version as the insert stored it, and the instant the request asked for it to start. */
    private record Minted(RoutingPolicyVersion stored, Instant asked) {}

    /**
     * The audit's start (`P15-TSK-037`): the instant stored; and where the insert's floor moved it, the instant asked for too, so
     * the record says both. Compared past the column's microsecond, whose rounding is not a floor.
     */
    static String effectiveFromForAudit(Instant stored, Instant asked) {
        return "effectiveFrom=" + stored
                + (stored.isAfter(asked.plus(1, java.time.temporal.ChronoUnit.MICROS)) ? ", askedEffectiveFrom=" + asked : "");
    }

    private Minted mintVersion(
            Connection unitOfWork, NewVersion request, Actor actor) {
        for (int attempt = 1; attempt <= MAX_MINTING_ATTEMPTS; attempt++) {
            RoutingPolicyVersion candidate =
                    RoutingPolicyVersion.create(
                            ids,
                            routing.nextVersionNumber(unitOfWork),
                            request.rules(),
                            request.effectiveFrom(),
                            actor.id(),
                            request.reason(),
                            clock);
            Optional<RoutingPolicyVersion> stored = routing.insertVersionIfNumberIsFree(unitOfWork, candidate);
            if (stored.isPresent()) {
                return new Minted(stored.get(), candidate.effectiveFrom());
            }
        }
        throw new PaymentsStorageException(
                "a routing policy version lost "
                        + MAX_MINTING_ATTEMPTS
                        + " consecutive races for its number; the contention is not a workload");
    }

    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a routing command must run inside a correlation"
                                                + " scope"));
    }
}
