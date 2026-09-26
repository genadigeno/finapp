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
     */
    public static final String IDEMPOTENCY_SCOPE = "payments.routing.version";

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

        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_SCOPE, idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(
                        canonicalForm(request, actor).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
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
        RoutingPolicyVersion version = mintVersion(uow, request, actor);
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
                                        + ", effectiveFrom=" + version.effectiveFrom())));
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
    public RailAvailability setAvailability(
            Connection unitOfWork, RailId rail, boolean available, String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(rail, "rail must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        if (!rails.declaredIds().contains(rail)) {
            throw new UnknownRailException(rail);
        }

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

    private RoutingPolicyVersion mintVersion(
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
            if (routing.insertVersionIfNumberIsFree(unitOfWork, candidate)) {
                return candidate;
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
