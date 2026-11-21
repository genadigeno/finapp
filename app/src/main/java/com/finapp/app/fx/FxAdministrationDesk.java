package com.finapp.app.fx;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.DecimalText;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.PendingDecisions;
import com.finapp.fx.AvailabilityStore;
import com.finapp.fx.AvailabilitySubject;
import com.finapp.fx.FxAvailability;
import com.finapp.fx.FxErrorCode;
import com.finapp.fx.Margin;
import com.finapp.fx.NotionalBounds;
import com.finapp.fx.PolicyPair;
import com.finapp.fx.PricingPair;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyId;
import com.finapp.fx.PricingPolicyProposal;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.fx.PricingPurpose;
import com.finapp.fx.TransactionRunner;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.MonetaryException;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the FX controller's doors (`P9-TSK-007`): parse exactly, key the proposals
 * per principal, run each command in one transaction, and translate the domain's refusals onto
 * the error contract. The four-eyes rule, the machines and the kill switch's asymmetry are the
 * domain's and {@code fx V004}'s; nothing here decides them.
 */
@RequiredArgsConstructor
public final class FxAdministrationDesk {

    static final String POLICY_SCOPE = "fx.pricing-policy:";
    static final String AVAILABILITY_SCOPE = "fx.availability:";

    /**
     * The versions' collection (`API_CONVENTIONS.md` §7, `P17-TSK-118`): version descending, the version number the
     * key - {@code UNIQUE}, so the order is total. The maximum is the list's old cap, so no page reads more than the
     * capped list did.
     */
    static final String PRICING_POLICIES = "fx.pricing-policies";

    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 100;

    @NonNull private final PricingPolicyAdministration policies;
    @NonNull private final FxAvailability availability;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final Clock clock;
    @NonNull private final Set<String> declaredProviders;

    // ------------------------------------------------------------------ responses

    /** A version's receipt. */
    public record PolicyReceipt(String id, int version, String status, String retiredId) {}

    /** A page of versions, newest first; {@code nextCursor} absent on the last one (§7, `P17-TSK-118`). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PolicyPage(List<PolicyView> items, String nextCursor) {}

    /**
     * One version and every pair it prices, and who proposed it (`P17-TSK-118`: an identity's id, so the console
     * disables the proposer's own approval - {@code fx.SelfApprovalRefused} stays the control). Never who decided it.
     */
    public record PolicyView(
            String id, int version, String status, int openQuoteCap, String proposedBy, String proposedAt,
            List<PairView> pairs) {}

    /** One (pair, purpose), every term as the version froze it. */
    public record PairView(
            String source, String destination, String purpose, List<String> providers,
            String spread, String markup, int rateScale, String rateRounding,
            String amountRounding, String marginRounding, long windowSeconds,
            long coverMarginSeconds, String band, long referenceMaxAgeSeconds,
            String sourceMinimum, String sourceMaximum, String destinationMinimum,
            String destinationMaximum) {}

    /** An availability post's outcome: disabled, unchanged, or an enable request opened. */
    public record AvailabilityReceipt(
            String subjectKind, String subject, String outcome, String requestId) {}

    /** An enable decision's receipt. */
    public record EnableReceipt(String requestId, String subjectKind, String subject, String status) {}

    /**
     * An enable request as its approver reads it (`P17-TSK-118`): the subject, the position, who proposed it, when and
     * why, and the decision's instant and reason once decided ({@code null} until then), and whether the reader proposed it
     * ({@code proposedByCaller}, `P17-TSK-127`). Never who decided it.
     */
    public record EnableRequestView(
            String requestId, String subjectKind, String subject, String status, String proposedBy, String proposedAt,
            String reason, String decidedAt, String decisionReason, boolean proposedByCaller) {}

    // ------------------------------------------------------------------ pricing policy

    /** A page of versions, newest first, each with its pairs; recording nothing, as the list never did. */
    public PolicyPage policies(String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<Integer> before = versionCursor(cursor);
        List<PricingPolicyStore.VersionView> versions =
                transactions.inTransaction(uow -> policies.versions(uow, before, size + 1));
        List<PricingPolicyStore.VersionView> served = versions.subList(0, Math.min(size, versions.size()));
        String next = versions.size() > size
                ? OperatorCollection.cursor(PRICING_POLICIES, Integer.toString(served.get(served.size() - 1).row().version()))
                : null;
        return new PolicyPage(served.stream().map(FxAdministrationDesk::view).toList(), next);
    }

    /** The version a cursor resumes below; a cursor this collection did not serve is refused (422). */
    static Optional<Integer> versionCursor(String cursor) {
        return OperatorCollection.key(PRICING_POLICIES, cursor, 1).map(key -> {
            try {
                int version = Integer.parseInt(key.get(0));
                if (version < 1) {
                    throw OperatorCollection.invalidCursor();
                }
                return version;
            } catch (NumberFormatException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    public PolicyReceipt propose(String idempotencyKey, FxAdministrationController.PricingPolicyRequest request) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        PricingPolicyProposal proposal = guarded(() -> proposal(request));
        IdempotencyKey key = new IdempotencyKey(POLICY_SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(String.valueOf(request).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome =
                guarded(
                        () ->
                                transactions.inTransaction(
                                        unitOfWork ->
                                                executor.execute(
                                                        unitOfWork,
                                                        key,
                                                        fingerprint,
                                                        uow -> {
                                                            PricingPolicyAdministration.Proposed proposed =
                                                                    policies.propose(uow, proposal, actor, now(), correlation);
                                                            String receipt = proposed.id().value() + "|" + proposed.version();
                                                            return CommandResult.succeeded(
                                                                    StoredResponse.of(
                                                                            receipt.getBytes(StandardCharsets.UTF_8),
                                                                            "text/plain"));
                                                        })));
        String[] stored =
                new String(outcome.body().orElseThrow(() -> new IllegalStateException("a proposal stores its receipt")),
                                StandardCharsets.UTF_8)
                        .split("\\|", -1);
        return new PolicyReceipt(stored[0], Integer.parseInt(stored[1]), "PROPOSED", null);
    }

    public PolicyReceipt approve(String rawId, String reason) {
        PricingPolicyId id = policyId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return receipt(guarded(() -> transactions.inTransaction(uow -> policies.approve(uow, id, actor, reason, now(), correlation))));
    }

    public PolicyReceipt reject(String rawId, String reason) {
        PricingPolicyId id = policyId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return receipt(guarded(() -> transactions.inTransaction(uow -> policies.reject(uow, id, actor, reason, now(), correlation))));
    }

    // ------------------------------------------------------------------ availability

    public AvailabilityReceipt pairAvailability(
            String idempotencyKey, String pair, FxAdministrationController.AvailabilityRequest request) {
        AvailabilitySubject subject;
        try {
            subject = AvailabilitySubject.pair(pair);
        } catch (IllegalArgumentException unknown) {
            throw notFound();
        }
        return availability(idempotencyKey, subject, request);
    }

    public AvailabilityReceipt providerAvailability(
            String idempotencyKey, String code, FxAdministrationController.AvailabilityRequest request) {
        AvailabilitySubject subject;
        try {
            subject = AvailabilitySubject.provider(code, declaredProviders);
        } catch (IllegalArgumentException undeclared) {
            throw new ApiException(
                    FxErrorCode.PROVIDER_NOT_DECLARED, "An FX availability post named an undeclared provider",
                    "The provider is not declared by this build.");
        }
        return availability(idempotencyKey, subject, request);
    }

    /** The enable request {@code rawId} as its approver reads it; unknown or malformed, 404. Unrecorded, as the list. */
    public EnableRequestView enableRequest(String rawId) {
        UUID id = uuid(rawId);
        AvailabilityStore.RequestView request =
                transactions.inTransaction(uow -> availability.request(uow, id)).orElseThrow(FxAdministrationDesk::notFound);
        return new EnableRequestView(
                request.id().toString(), request.subject().kind().name(), request.subject().subject(),
                request.status().name(), request.proposedBy(), request.proposedAt().toString(), request.reason(),
                request.decidedAt().map(Instant::toString).orElse(null), request.decisionReason().orElse(null),
                PendingDecisions.proposedByCaller(request.proposedBy()));
    }

    public EnableReceipt approveEnable(String rawId, String reason) {
        UUID id = uuid(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return enableReceipt(guarded(() -> transactions.inTransaction(uow -> availability.approveEnable(uow, id, actor, reason, now(), correlation))));
    }

    public EnableReceipt rejectEnable(String rawId, String reason) {
        UUID id = uuid(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return enableReceipt(guarded(() -> transactions.inTransaction(uow -> availability.rejectEnable(uow, id, actor, reason, now(), correlation))));
    }

    private AvailabilityReceipt availability(
            String idempotencyKey, AvailabilitySubject subject, FxAdministrationController.AvailabilityRequest request) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(AVAILABILITY_SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(
                        (subject.kind() + ":" + subject.subject() + "|" + request).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome =
                guarded(
                        () ->
                                transactions.inTransaction(
                                        unitOfWork ->
                                                executor.execute(
                                                        unitOfWork,
                                                        key,
                                                        fingerprint,
                                                        uow -> {
                                                            String receipt;
                                                            if (request.available()) {
                                                                FxAvailability.EnableProposed proposed =
                                                                        availability.proposeEnable(
                                                                                uow, subject, actor, request.reason(), now(), correlation);
                                                                receipt = "ENABLE_PROPOSED|" + proposed.requestId();
                                                            } else {
                                                                FxAvailability.Disabled disabled =
                                                                        availability.disable(
                                                                                uow, subject, actor, request.reason(), now(), correlation);
                                                                receipt = (disabled.changed() ? "DISABLED" : "UNCHANGED") + "|";
                                                            }
                                                            return CommandResult.succeeded(
                                                                    StoredResponse.of(
                                                                            receipt.getBytes(StandardCharsets.UTF_8),
                                                                            "text/plain"));
                                                        })));
        String[] stored =
                new String(outcome.body().orElseThrow(() -> new IllegalStateException("an availability post stores its receipt")),
                                StandardCharsets.UTF_8)
                        .split("\\|", -1);
        return new AvailabilityReceipt(
                subject.kind().name(), subject.subject(), stored[0], stored[1].isEmpty() ? null : stored[1]);
    }

    // ------------------------------------------------------------------ plumbing

    /** The request in the domain's words; any malformed term is the proposal's own defect. */
    private PricingPolicyProposal proposal(FxAdministrationController.PricingPolicyRequest request) {
        try {
            List<PolicyPair> pairs =
                    request.pairs().stream()
                            .map(
                                    pair -> {
                                        CurrencyCode source = CurrencyCode.of(pair.source());
                                        CurrencyCode destination = CurrencyCode.of(pair.destination());
                                        return new PolicyPair(
                                                PricingPurpose.valueOf(pair.purpose()),
                                                pair.providers(),
                                                new PricingPair(
                                                        source,
                                                        destination,
                                                        Margin.of(DecimalText.parse(pair.spread()).toPlainString()),
                                                        Margin.of(DecimalText.parse(pair.markup()).toPlainString()),
                                                        pair.rateScale(),
                                                        RoundingPolicy.valueOf(pair.rateRounding()),
                                                        RoundingPolicy.valueOf(pair.amountRounding()),
                                                        RoundingPolicy.valueOf(pair.marginRounding()),
                                                        new NotionalBounds(
                                                                Money.of(DecimalText.parse(pair.sourceMinimum()), source),
                                                                Money.of(DecimalText.parse(pair.sourceMaximum()), source)),
                                                        new NotionalBounds(
                                                                Money.of(DecimalText.parse(pair.destinationMinimum()), destination),
                                                                Money.of(DecimalText.parse(pair.destinationMaximum()), destination))),
                                                Duration.ofSeconds(pair.windowSeconds()),
                                                Duration.ofSeconds(pair.coverMarginSeconds()),
                                                DecimalText.parse(pair.band()),
                                                Duration.ofSeconds(pair.referenceMaxAgeSeconds()));
                                    })
                            .toList();
            return new PricingPolicyProposal(pairs, request.openQuoteCap(), request.reason());
        } catch (IllegalArgumentException | MonetaryException | ArithmeticException malformed) {
            throw new PricingPolicyAdministration.PricingPolicyInvalid(malformed.getMessage());
        }
    }

    private static PolicyView view(PricingPolicyStore.VersionView version) {
        return new PolicyView(
                version.row().id().value().toString(),
                version.row().version(),
                version.row().status().name(),
                version.row().openQuoteCap(),
                version.row().proposedBy(),
                version.proposedAt().toString(),
                version.pairs().stream()
                        .map(
                                pair -> {
                                    PricingPair p = pair.pricing();
                                    return new PairView(
                                            p.source().code(), p.destination().code(), pair.purpose().name(),
                                            pair.providers(), p.spread().value().toPlainString(),
                                            p.markup().value().toPlainString(), p.rateScale(),
                                            p.rateRounding().name(), p.amountRounding().name(),
                                            p.marginRounding().name(), pair.window().toSeconds(),
                                            pair.coverMargin().toSeconds(), pair.band().toPlainString(),
                                            pair.referenceMaxAge().toSeconds(),
                                            p.sourceBounds().minimum().toBigDecimal().toPlainString(),
                                            p.sourceBounds().maximum().toBigDecimal().toPlainString(),
                                            p.destinationBounds().minimum().toBigDecimal().toPlainString(),
                                            p.destinationBounds().maximum().toBigDecimal().toPlainString());
                                })
                        .toList());
    }

    private static PolicyReceipt receipt(PricingPolicyAdministration.Decided decided) {
        return new PolicyReceipt(
                decided.id().value().toString(), decided.version(), decided.status().name(),
                decided.retired().map(id -> id.value().toString()).orElse(null));
    }

    private static EnableReceipt enableReceipt(FxAvailability.EnableDecided decided) {
        return new EnableReceipt(
                decided.requestId().toString(), decided.subject().kind().name(), decided.subject().subject(),
                decided.status().name());
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (PricingPolicyAdministration.PricingPolicyNotFound | FxAvailability.EnableRequestNotFound unknown) {
            throw notFound();
        } catch (PricingPolicyAdministration.SelfApprovalRefused | FxAvailability.EnableSelfApprovalRefused self) {
            throw refused(FxErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (PricingPolicyAdministration.ProposalNotPending | FxAvailability.EnableRequestNotPending decided) {
            throw refused(FxErrorCode.PROPOSAL_NOT_PENDING, decided);
        } catch (PricingPolicyAdministration.ProposalPending | FxAvailability.EnablePending pending) {
            throw refused(FxErrorCode.PROPOSAL_PENDING, pending);
        } catch (FxAvailability.AlreadyAvailable available) {
            throw refused(FxErrorCode.ALREADY_AVAILABLE, available);
        } catch (PricingPolicyAdministration.ProviderNotDeclared undeclared) {
            throw refused(FxErrorCode.PROVIDER_NOT_DECLARED, undeclared);
        } catch (PricingPolicyAdministration.PricingPolicyInvalid | FxAvailability.AvailabilityInvalid invalid) {
            throw refused(FxErrorCode.PRICING_POLICY_INVALID, invalid);
        }
    }

    private static ApiException refused(FxErrorCode code, RuntimeException cause) {
        return new ApiException(code, "An FX controller's command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(
                FxErrorCode.NOT_FOUND, "No FX policy record matches the requested identifier", "no such record.");
    }

    private static PricingPolicyId policyId(String raw) {
        try {
            return PricingPolicyId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an FX command runs inside a correlation scope"))
                .correlationId();
    }
}
