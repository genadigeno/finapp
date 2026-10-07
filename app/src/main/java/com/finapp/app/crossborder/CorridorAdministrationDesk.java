package com.finapp.app.crossborder;

import com.finapp.app.api.DecimalText;
import com.finapp.crossborder.CorridorAvailability;
import com.finapp.crossborder.CorridorDirectory;
import com.finapp.crossborder.CorridorKey;
import com.finapp.crossborder.CorridorPolicyAdministration;
import com.finapp.crossborder.CorridorPolicyId;
import com.finapp.crossborder.CorridorPolicyProposal;
import com.finapp.crossborder.CorridorPolicyStore;
import com.finapp.crossborder.CorridorTerms;
import com.finapp.crossborder.CrossborderErrorCode;
import com.finapp.crossborder.RequiredData;
import com.finapp.crossborder.TransactionRunner;
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
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.MonetaryException;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the corridor doors (`P9-TSK-015`): parse exactly, key the proposals and the
 * availability posts per principal, run each command in one transaction, translate the domain's
 * refusals onto the error contract, and serve the customer's discovery. The four-eyes rule, the
 * machines and the kill switch's asymmetry are the domain's and {@code crossborder V002}'s; nothing
 * here decides them.
 */
@RequiredArgsConstructor
public final class CorridorAdministrationDesk {

    static final String POLICY_SCOPE = "crossborder.corridor-policy:";
    static final String AVAILABILITY_SCOPE = "crossborder.corridor-availability:";

    /** A read's bound - a list is never unbounded (`PHASE_9_PLAN.md` §9). */
    static final int BOUND = 100;

    @NonNull private final CorridorPolicyAdministration policies;
    @NonNull private final CorridorAvailability availability;
    @NonNull private final CorridorDirectory directory;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ responses

    /** A version's receipt. */
    public record CorridorPolicyReceipt(String id, int version, String status, String retiredId) {}

    /** The versions, newest first. */
    public record CorridorPolicyList(List<CorridorPolicyView> versions, boolean truncated) {}

    /** One version and every corridor it holds. */
    public record CorridorPolicyView(String id, int version, String status, String proposedAt, List<CorridorView> corridors) {}

    /** One corridor, every term as the version froze it. */
    public record CorridorView(
            String corridor,
            String source,
            String destination,
            String country,
            List<String> rails,
            String feeFixed,
            String feeMargin,
            String feeRounding,
            String maximum,
            long screeningValidityHours,
            long deliveryEstimateHours,
            List<String> requiredData) {}

    /** An availability post's outcome: disabled, unchanged, or an enable request opened. */
    public record CorridorAvailabilityReceipt(String corridor, String outcome, String requestId) {}

    /** An enable decision's receipt. */
    public record CorridorEnableReceipt(String requestId, String corridor, String status) {}

    /** The corridors on offer to a customer now. */
    public record OfferedCorridors(List<OfferedCorridor> corridors, boolean truncated) {}

    /** One offered corridor - what a customer decides by; never a rail. */
    public record OfferedCorridor(
            String corridor,
            String source,
            String destination,
            String country,
            String feeFixed,
            String feeMargin,
            String maximum,
            long deliveryEstimateHours) {}

    // ------------------------------------------------------------------ the policy

    public CorridorPolicyList policies() {
        List<CorridorPolicyStore.VersionView> versions = transactions.inTransaction(uow -> policies.versions(uow, BOUND));
        return new CorridorPolicyList(versions.stream().map(CorridorAdministrationDesk::view).toList(), false);
    }

    public CorridorPolicyReceipt propose(String idempotencyKey, CorridorAdministrationController.CorridorPolicyRequest request) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        CorridorPolicyProposal proposal = guarded(() -> proposal(request));
        IdempotencyKey key = new IdempotencyKey(POLICY_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.valueOf(request).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> transactions.inTransaction(
                unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    CorridorPolicyAdministration.Proposed proposed = policies.propose(uow, proposal, actor, now(), correlation);
                    String receipt = proposed.id().value() + "|" + proposed.version();
                    return CommandResult.succeeded(StoredResponse.of(receipt.getBytes(StandardCharsets.UTF_8), "text/plain"));
                })));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a proposal stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new CorridorPolicyReceipt(stored[0], Integer.parseInt(stored[1]), "PROPOSED", null);
    }

    public CorridorPolicyReceipt approve(String rawId, String reason) {
        CorridorPolicyId id = policyId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return receipt(guarded(() -> transactions.inTransaction(uow -> policies.approve(uow, id, actor, reason, now(), correlation))));
    }

    public CorridorPolicyReceipt reject(String rawId, String reason) {
        CorridorPolicyId id = policyId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return receipt(guarded(() -> transactions.inTransaction(uow -> policies.reject(uow, id, actor, reason, now(), correlation))));
    }

    // ------------------------------------------------------------------ availability

    public CorridorAvailabilityReceipt availability(
            String idempotencyKey, String rawCorridor, CorridorAdministrationController.CorridorAvailabilityRequest request) {
        CorridorKey corridor;
        try {
            corridor = CorridorKey.parse(rawCorridor);
        } catch (IllegalArgumentException unknown) {
            throw notFound();
        }
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(AVAILABILITY_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256((corridor.code() + "|" + request).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> transactions.inTransaction(
                unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    String receipt;
                    if (request.available()) {
                        CorridorAvailability.EnableProposed proposed =
                                availability.proposeEnable(uow, corridor, actor, request.reason(), now(), correlation);
                        receipt = "ENABLE_PROPOSED|" + proposed.requestId();
                    } else {
                        CorridorAvailability.Disabled disabled =
                                availability.disable(uow, corridor, actor, request.reason(), now(), correlation);
                        receipt = (disabled.changed() ? "DISABLED" : "UNCHANGED") + "|";
                    }
                    return CommandResult.succeeded(StoredResponse.of(receipt.getBytes(StandardCharsets.UTF_8), "text/plain"));
                })));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("an availability post stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new CorridorAvailabilityReceipt(corridor.code(), stored[0], stored[1].isEmpty() ? null : stored[1]);
    }

    public CorridorEnableReceipt approveEnable(String rawId, String reason) {
        UUID id = uuid(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return enableReceipt(guarded(() -> transactions.inTransaction(
                uow -> availability.approveEnable(uow, id, actor, reason, now(), correlation))));
    }

    public CorridorEnableReceipt rejectEnable(String rawId, String reason) {
        UUID id = uuid(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return enableReceipt(guarded(() -> transactions.inTransaction(
                uow -> availability.rejectEnable(uow, id, actor, reason, now(), correlation))));
    }

    // ------------------------------------------------------------------ discovery

    /**
     * The active version's corridors that are available now and that at least one candidate rail this
     * build declares can carry (it covers the corridor's country and destination currency) - bounded at
     * {@value #BOUND}. No active version offers nothing.
     */
    public OfferedCorridors offered() {
        return transactions.inTransaction(uow -> {
            Optional<CorridorPolicyStore.VersionView> active = policies.active(uow);
            if (active.isEmpty()) {
                return new OfferedCorridors(List.of(), false);
            }
            List<OfferedCorridor> offered = active.get().corridors().stream()
                    .filter(this::carriedByTheBuild)
                    .filter(terms -> availability.isAvailable(uow, terms.key()))
                    .map(CorridorAdministrationDesk::offered)
                    .toList();
            return new OfferedCorridors(offered.stream().limit(BOUND).toList(), offered.size() > BOUND);
        });
    }

    private boolean carriedByTheBuild(CorridorTerms terms) {
        return terms.rails().stream()
                .map(directory::declared)
                .flatMap(Optional::stream)
                .anyMatch(rail -> rail.covers(terms.key().country(), terms.key().destination()));
    }

    // ------------------------------------------------------------------ plumbing

    /** The request in the domain's words; any malformed term is the proposal's own defect. */
    private CorridorPolicyProposal proposal(CorridorAdministrationController.CorridorPolicyRequest request) {
        try {
            List<CorridorTerms> corridors = request.corridors().stream()
                    .map(corridor -> {
                        CurrencyCode source = CurrencyCode.of(corridor.source());
                        CurrencyCode destination = CurrencyCode.of(corridor.destination());
                        return new CorridorTerms(
                                new CorridorKey(source, destination, CountryCode.of(corridor.country())),
                                corridor.rails(),
                                Money.of(DecimalText.parse(corridor.feeFixed()), source),
                                DecimalText.parse(corridor.feeMargin()),
                                RoundingPolicy.valueOf(corridor.feeRounding()),
                                Money.of(DecimalText.parse(corridor.maximum()), destination),
                                Duration.ofHours(corridor.screeningValidityHours()),
                                Duration.ofHours(corridor.deliveryEstimateHours()),
                                corridor.requiredData().stream().map(RequiredData::valueOf)
                                        .collect(Collectors.toUnmodifiableSet()));
                    })
                    .toList();
            return new CorridorPolicyProposal(corridors, request.reason());
        } catch (IllegalArgumentException | MonetaryException | ArithmeticException malformed) {
            throw new CorridorPolicyAdministration.CorridorPolicyInvalid(malformed.getMessage());
        }
    }

    private static CorridorPolicyView view(CorridorPolicyStore.VersionView version) {
        return new CorridorPolicyView(
                version.row().id().value().toString(),
                version.row().version(),
                version.row().status().name(),
                version.proposedAt().toString(),
                version.corridors().stream()
                        .map(terms -> new CorridorView(
                                terms.key().code(),
                                terms.key().source().code(),
                                terms.key().destination().code(),
                                terms.key().country().code(),
                                terms.rails(),
                                terms.feeFixed().toBigDecimal().toPlainString(),
                                terms.feeMargin().toPlainString(),
                                terms.feeRounding().name(),
                                terms.maximum().toBigDecimal().toPlainString(),
                                terms.screeningValidity().toHours(),
                                terms.deliveryEstimate().toHours(),
                                terms.requiredData().stream().map(Enum::name).sorted().toList()))
                        .toList());
    }

    private static OfferedCorridor offered(CorridorTerms terms) {
        return new OfferedCorridor(
                terms.key().code(),
                terms.key().source().code(),
                terms.key().destination().code(),
                terms.key().country().code(),
                terms.feeFixed().toBigDecimal().toPlainString(),
                terms.feeMargin().toPlainString(),
                terms.maximum().toBigDecimal().toPlainString(),
                terms.deliveryEstimate().toHours());
    }

    private static CorridorPolicyReceipt receipt(CorridorPolicyAdministration.Decided decided) {
        return new CorridorPolicyReceipt(
                decided.id().value().toString(), decided.version(), decided.status().name(),
                decided.retired().map(id -> id.value().toString()).orElse(null));
    }

    private static CorridorEnableReceipt enableReceipt(CorridorAvailability.EnableDecided decided) {
        return new CorridorEnableReceipt(decided.requestId().toString(), decided.corridor().code(), decided.status().name());
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (CorridorPolicyAdministration.CorridorPolicyNotFound | CorridorAvailability.EnableRequestNotFound unknown) {
            throw notFound();
        } catch (CorridorPolicyAdministration.SelfApprovalRefused | CorridorAvailability.EnableSelfApprovalRefused self) {
            throw refused(CrossborderErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (CorridorPolicyAdministration.ProposalNotPending | CorridorAvailability.EnableRequestNotPending decided) {
            throw refused(CrossborderErrorCode.PROPOSAL_NOT_PENDING, decided);
        } catch (CorridorPolicyAdministration.ProposalPending | CorridorAvailability.EnablePending pending) {
            throw refused(CrossborderErrorCode.PROPOSAL_PENDING, pending);
        } catch (CorridorAvailability.AlreadyAvailable available) {
            throw refused(CrossborderErrorCode.ALREADY_AVAILABLE, available);
        } catch (CorridorPolicyAdministration.RailNotDeclared undeclared) {
            throw refused(CrossborderErrorCode.RAIL_NOT_DECLARED, undeclared);
        } catch (CorridorPolicyAdministration.RequiredDataUnsatisfiable unsatisfiable) {
            throw refused(CrossborderErrorCode.REQUIRED_DATA_UNSATISFIABLE, unsatisfiable);
        } catch (CorridorPolicyAdministration.CorridorPolicyInvalid | CorridorAvailability.AvailabilityInvalid invalid) {
            throw refused(CrossborderErrorCode.CORRIDOR_POLICY_INVALID, invalid);
        }
    }

    private static ApiException refused(CrossborderErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A corridor administrator's command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(
                CrossborderErrorCode.NOT_FOUND, "No corridor policy record matches the requested identifier", "no such record.");
    }

    private static CorridorPolicyId policyId(String raw) {
        try {
            return CorridorPolicyId.of(UUID.fromString(raw));
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
                .orElseThrow(() -> new IllegalStateException("a corridor command runs inside a correlation scope"))
                .correlationId();
    }
}
