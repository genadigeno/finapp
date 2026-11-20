package com.finapp.app.bnpl;

import com.finapp.app.api.RecordedReads;
import com.finapp.merchant.BnplEnablement;
import com.finapp.merchant.BnplEnablementId;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplFeeAssignmentProposal;
import com.finapp.merchant.BnplFeeAssignments;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeCurrencyMismatchException;
import com.finapp.merchant.FeeScheduleId;
import com.finapp.merchant.MerchantAuditAction;
import com.finapp.merchant.MerchantBnplProduct;
import com.finapp.merchant.MerchantClosedException;
import com.finapp.merchant.MerchantErrorCode;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.UnknownFeeScheduleException;
import com.finapp.merchant.UnknownMerchantException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the merchant BNPL doors (`P12-TSK-006`; {@code BnplTermsDesk}'s shape): parse the identifiers
 * (malformed is absent - one {@code 404}), key every enablement act per principal
 * ({@code merchant.bnpl-enablement:<type>:<id>}), run each in one transaction, translate merchant's refusals onto the
 * error contract, and record a refused self-approval {@code FAILED} in a transaction of its own after the act rolled back
 * (the Phase 11 gate's R4). The four-eyes rule, the machine and the pricing rules are merchant's and `V010`'s; nothing
 * here decides them.
 *
 * <p><strong>The key's type</strong> is the session actor's - {@code CUSTOMER} for an operator session today (the
 * platform does not yet issue {@code EMPLOYEE} actors to operator sessions; {@code BnplTermsDesk}'s note), so the
 * backlog's {@code merchant.bnpl-enablement:EMPLOYEE:<id>} reads {@code ...:CUSTOMER:<id>} until that debt is repaid, with
 * no change here.
 *
 * <p><strong>The BNPL fee assignment by four eyes</strong> (`P17-TSK-116`, ADR-0154's shape; finapp-admin-panel ADR-C135
 * decision 3): the {@code PUT} no longer assigns - it judges what it can now (the merchant, the schedule, its currency,
 * every version returning) and answers a keyed proposal, {@code PROPOSED} ({@value #FEE_PROPOSAL_SCOPE}), or
 * {@code ASSIGNED} with nothing proposed when the pointer is there already. A holder of {@code FEE_SCHEDULE_APPROVE} who did
 * not propose it approves ({@value #FEE_APPROVAL_SCOPE}) - {@code BnplPricing.assign} unchanged, under the approver, in
 * the decision's transaction - or rejects ({@value #FEE_REJECTION_SCOPE}). It was unkeyed, converging through state; it is
 * keyed now, a proposal being a new row (the `P17-TSK-031` precedent, replaced in place).
 */
@RequiredArgsConstructor
public final class MerchantBnplDesk {

    static final String SCOPE = "merchant.bnpl-enablement:";

    /** The BNPL fee assignment's proposal: its key's scope (`P17-TSK-116`). */
    static final String FEE_PROPOSAL_SCOPE = "merchant.bnpl-fee-assignment:";

    /** The BNPL fee assignment's approval (`P17-TSK-116`). */
    static final String FEE_APPROVAL_SCOPE = "merchant.bnpl-fee-assignment-approval:";

    /** The BNPL fee assignment's rejection (`P17-TSK-116`). */
    static final String FEE_REJECTION_SCOPE = "merchant.bnpl-fee-assignment-rejection:";

    @NonNull private final BnplEnablements enablements;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final BnplTransactions transactions;
    @NonNull private final BnplFeeAssignments feeAssignments;
    @NonNull private final RecordedReads reads;

    /**
     * A BNPL fee assignment proposal as its decider reads it (`P17-TSK-124`): the merchant it would price - its name and
     * status now - the product and the schedule proposed (a price pointer: no amount), who proposed it, when and why, whether
     * the caller did, and once decided the decision's instant and reason ({@code null} until then). Never who decided.
     */
    public record BnplFeeAssignmentProposalView(
            String proposalId, String merchantId, String merchantName, String merchantStatus, String product,
            String feeScheduleId, String status, String reason, String proposedBy, boolean proposedByCaller,
            String proposedAt, String decidedAt, String decisionReason) {}

    /**
     * BNPL fee assignment proposal {@code rawId} with its merchant, recorded {@code merchant.BnplFeeAssignmentProposalRead} in
     * the read's own transaction (`P17-TSK-124`). An unknown or malformed identifier: 404, and nothing recorded.
     */
    public BnplFeeAssignmentProposalView readFeeAssignment(String rawId) {
        BnplFeeAssignmentProposal.Id id = feeProposalOrAbsent(rawId);
        String caller = SecurityContext.require().id();
        BnplFeeAssignments.Read read = transactions.inTransaction(uow -> feeAssignments.read(uow, id).map(found -> {
            reads.record(uow, MerchantAuditAction.BNPL_FEE_ASSIGNMENT_PROPOSAL_READ, "BnplFeeAssignmentProposal",
                    id.value().toString(), "merchant=" + found.proposal().merchantId().value() + ", product="
                            + found.proposal().product() + ", status=" + found.proposal().status());
            return found;
        })).orElseThrow(MerchantBnplDesk::notFound);
        BnplFeeAssignmentProposal proposal = read.proposal();
        return new BnplFeeAssignmentProposalView(proposal.id().value().toString(), proposal.merchantId().value().toString(),
                read.merchant().displayName(), read.merchant().status().name(), proposal.product().name(),
                proposal.feeScheduleId().value().toString(), proposal.status().name(), proposal.proposalReason(),
                proposal.proposedBy(), proposal.proposedBy().equals(caller), proposal.proposedAt().toString(),
                proposal.decision().map(BnplFeeAssignmentProposal.Decision::decidedAt).map(Instant::toString).orElse(null),
                proposal.decision().map(BnplFeeAssignmentProposal.Decision::reason).orElse(null));
    }

    /** An enablement act's receipt: the enablement, its merchant and its status. Never a reason or a person. */
    public record BnplEnablementReceipt(String id, String merchantId, String status) {}

    /**
     * Which schedule prices - or, {@code PROPOSED}, is proposed to price - a merchant's BNPL product (`P17-TSK-116`):
     * {@code ASSIGNED} when the pointer is there, {@code PROPOSED} or {@code REJECTED} for a proposal, {@code APPROVED} for
     * one approved (the pointer moved), each naming its {@code proposalId}.
     */
    public record BnplFeeScheduleView(String merchantId, String product, String feeScheduleId, String status,
            String proposalId) {}

    /**
     * A merchant's BNPL enablement as its approver reads it (`P17-TSK-116`): its status, who proposed and decided it, when -
     * never a reason ({@code RESTRICTED-PII}).
     */
    public record BnplEnablementView(String id, String merchantId, String status, String proposedBy, String proposedAt,
            String decidedBy, String decidedAt, String withdrawnBy, String withdrawnAt) {}

    // ------------------------------------------------------------------ the enablement

    public BnplEnablementReceipt propose(String idempotencyKey, String rawMerchant, String reason) {
        MerchantId merchant = merchantOrAbsent(rawMerchant);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "propose|" + merchant.value() + "|" + reason,
                uow -> receipt(enablements.propose(uow, merchant, reason, actor, correlation)));
    }

    public BnplEnablementReceipt approve(String idempotencyKey, String rawMerchant, String rawId, String reason) {
        MerchantId merchant = merchantOrAbsent(rawMerchant);
        BnplEnablementId id = enablementOrAbsent(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "approve|" + merchant.value() + "|" + id.value() + "|" + reason,
                uow -> receipt(enablements.approve(uow, merchant, id, reason, actor, correlation).enablement()));
    }

    public BnplEnablementReceipt reject(String idempotencyKey, String rawMerchant, String rawId, String reason) {
        MerchantId merchant = merchantOrAbsent(rawMerchant);
        BnplEnablementId id = enablementOrAbsent(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "reject|" + merchant.value() + "|" + id.value() + "|" + reason,
                uow -> receipt(enablements.reject(uow, merchant, id, reason, actor, correlation).enablement()));
    }

    public BnplEnablementReceipt withdraw(String idempotencyKey, String rawMerchant, String rawId, String reason) {
        MerchantId merchant = merchantOrAbsent(rawMerchant);
        BnplEnablementId id = enablementOrAbsent(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "withdraw|" + merchant.value() + "|" + id.value() + "|" + reason,
                uow -> receipt(enablements.withdraw(uow, merchant, id, reason, actor, correlation).enablement()));
    }

    // ------------------------------------------------------------------ the BNPL fee schedule

    /**
     * Proposes pointing the merchant's BNPL product at a schedule, reasoned and keyed (`P17-TSK-116`): judged now, answered
     * {@code PROPOSED}; {@code ASSIGNED} with nothing proposed when the pointer is there already.
     */
    public BnplFeeScheduleView assignFeeSchedule(String idempotencyKey, String rawMerchant, String rawProduct,
            UUID feeScheduleId, String reason) {
        MerchantId merchant = merchantOrAbsent(rawMerchant);
        MerchantBnplProduct product = productOrAbsent(rawProduct);
        Objects.requireNonNull(feeScheduleId, "feeScheduleId must not be null");
        FeeScheduleId schedule;
        try {
            schedule = FeeScheduleId.of(feeScheduleId);
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return feeKeyed(FEE_PROPOSAL_SCOPE, actor, idempotencyKey, String.join("|", "propose", merchant.value().toString(),
                product.name(), schedule.value().toString(), digest(reason)), uow -> {
                    BnplFeeAssignments.Proposed proposed = feeAssignments.propose(uow, merchant, product, schedule, reason,
                            actor, correlation);
                    return proposed.proposal().map(MerchantBnplDesk::feeView).orElseGet(() -> new BnplFeeScheduleView(
                            merchant.value().toString(), product.name(), schedule.value().toString(), "ASSIGNED", null));
                });
    }

    /** Approves a BNPL fee assignment proposal the caller did not propose (`P17-TSK-116`): the pointer moves. */
    public BnplFeeScheduleView approveFeeAssignment(String idempotencyKey, String rawId, String reason) {
        BnplFeeAssignmentProposal.Id id = feeProposalOrAbsent(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return feeKeyed(FEE_APPROVAL_SCOPE, actor, idempotencyKey, "approve|" + id.value() + "|" + digest(reason),
                uow -> feeAssignments.approve(uow, id, reason, actor, correlation).map(MerchantBnplDesk::feeView)
                        .orElseThrow(MerchantBnplDesk::notFound));
    }

    /** Rejects a BNPL fee assignment proposal the caller did not propose (`P17-TSK-116`): nothing changes. */
    public BnplFeeScheduleView rejectFeeAssignment(String idempotencyKey, String rawId, String reason) {
        BnplFeeAssignmentProposal.Id id = feeProposalOrAbsent(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return feeKeyed(FEE_REJECTION_SCOPE, actor, idempotencyKey, "reject|" + id.value() + "|" + digest(reason),
                uow -> feeAssignments.reject(uow, id, reason, actor, correlation).map(MerchantBnplDesk::feeView)
                        .orElseThrow(MerchantBnplDesk::notFound));
    }

    // ------------------------------------------------------------------ the enablement read (`P17-TSK-116`)

    /**
     * The merchant's BNPL enablement by its identifier, as the approver reads it before deciding - the merchant in the
     * path is in the statement, so another merchant's enablement is the uniform {@code 404}. A read: unkeyed, unaudited
     * (no party's data; the enablement's acts are audited where they happen).
     */
    public BnplEnablementView readEnablement(String rawMerchant, String rawId) {
        MerchantId merchant = merchantOrAbsent(rawMerchant);
        BnplEnablementId id = enablementOrAbsent(rawId);
        BnplEnablement enablement = transactions.inTransaction(uow -> enablements.find(uow, merchant, id))
                .orElseThrow(MerchantBnplDesk::notFound);
        return new BnplEnablementView(enablement.id().value().toString(), enablement.merchantId().value().toString(),
                enablement.status().name(), enablement.proposedBy(), enablement.proposedAt().toString(),
                enablement.decidedBy().orElse(null), enablement.decidedAt().map(Instant::toString).orElse(null),
                enablement.withdrawnBy().orElse(null), enablement.withdrawnAt().map(Instant::toString).orElse(null));
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing; a
     * self-approval is recorded {@code FAILED} in a transaction of its own.
     */
    private BnplEnablementReceipt keyed(Actor actor, String idempotencyKey, String fingerprinted,
            Function<Connection, BnplEnablementReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    BnplEnablementReceipt receipt = act.apply(uow);
                    String stored = receipt.id() + "|" + receipt.merchantId() + "|" + receipt.status();
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8),
                            "text/plain"));
                }))));
        String[] stored = new String(outcome.body()
                        .orElseThrow(() -> new IllegalStateException("an enablement act stores its receipt")),
                StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplEnablementReceipt(stored[0], stored[1], stored[2]);
    }

    /**
     * One keyed BNPL fee assignment act in one transaction (`P17-TSK-116`) - a lost response replays the stored answer; a
     * refusal stores nothing.
     */
    private BnplFeeScheduleView feeKeyed(String scope, Actor actor, String idempotencyKey, String fingerprinted,
            Function<Connection, BnplFeeScheduleView> act) {
        IdempotencyKey key = new IdempotencyKey(scope + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> transactions.inTransaction(unitOfWork ->
                executor.execute(unitOfWork, key, fingerprint, uow -> {
                    BnplFeeScheduleView view = act.apply(uow);
                    String stored = String.join("|", view.merchantId(), view.product(), view.feeScheduleId(), view.status(),
                            view.proposalId() == null ? "" : view.proposalId());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8),
                            "text/plain"));
                })));
        String[] stored = new String(outcome.body()
                        .orElseThrow(() -> new IllegalStateException("a BNPL fee assignment act stores its answer")),
                StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplFeeScheduleView(stored[0], stored[1], stored[2], stored[3], stored[4].isEmpty() ? null : stored[4]);
    }

    static BnplFeeScheduleView feeView(BnplFeeAssignmentProposal proposal) {
        return new BnplFeeScheduleView(proposal.merchantId().value().toString(), proposal.product().name(),
                proposal.feeScheduleId().value().toString(), proposal.status().name(), proposal.id().value().toString());
    }

    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (BnplEnablements.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                enablements.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        }
    }

    /** merchant's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (UnknownMerchantException | BnplEnablements.Unknown | UnknownFeeScheduleException unknown) {
            throw notFound();
        } catch (BnplFeeAssignmentProposal.Refused refused) {
            MerchantErrorCode code = switch (refused.reason()) {
                case ALREADY_PROPOSED -> MerchantErrorCode.BNPL_FEE_PROPOSAL_ALREADY_OPEN;
                case ALREADY_DECIDED -> MerchantErrorCode.BNPL_FEE_PROPOSAL_ALREADY_DECIDED;
                case SELF_DECISION -> MerchantErrorCode.BNPL_FEE_PROPOSAL_SELF_DECISION;
            };
            throw new ApiException(code, "A BNPL fee assignment proposal act was refused", refused.getMessage());
        } catch (BnplEnablements.SelfApprovalRefused self) {
            throw new ApiException(MerchantErrorCode.SELF_APPROVAL_REFUSED,
                    "A merchant BNPL enablement's proposer tried to approve it", self.getMessage());
        } catch (BnplEnablements.Pending pending) {
            throw new ApiException(MerchantErrorCode.BNPL_ENABLEMENT_PENDING,
                    "A merchant BNPL enablement is already live", pending.getMessage());
        } catch (BnplEnablements.NotOpen notOpen) {
            throw new ApiException(MerchantErrorCode.BNPL_ENABLEMENT_NOT_OPEN,
                    "A merchant BNPL enablement decision was refused by the machine", notOpen.getMessage());
        } catch (MerchantClosedException closed) {
            throw new ApiException(MerchantErrorCode.ILLEGAL_TRANSITION,
                    "A BNPL enablement was asked of a closed merchant",
                    "the merchant's current status does not permit this change.");
        } catch (BnplPricing.ScheduleNotReturning retained) {
            throw new ApiException(MerchantErrorCode.BNPL_SCHEDULE_NOT_RETURNING,
                    "A fee schedule that retains its fee was named for BNPL", retained.getMessage());
        } catch (FeeCurrencyMismatchException mismatch) {
            throw new ApiException(MerchantErrorCode.FEE_CURRENCY_MISMATCH,
                    "A fee schedule was used for a currency it does not price",
                    "the fee schedule's currency does not match.");
        } catch (com.finapp.merchant.BnplEnablement.ReasonRefused reason) {
            // The Phase 12 -> 13 gate's A5-F5: a reason the bean bounds admit but the instrument screen refuses - 422,
            // never a 5xx; the detail never repeats the reason (INV-AUD-02).
            throw new ApiException(com.finapp.platform.api.PlatformErrorCode.VALIDATION_FAILED, "A reason was refused",
                    "a reason of 1 to " + com.finapp.merchant.BnplEnablement.MAX_REASON_LENGTH + " characters, holding"
                            + " no card or account number, is required.");
        }
    }

    private static BnplEnablementReceipt receipt(BnplEnablement enablement) {
        return new BnplEnablementReceipt(enablement.id().value().toString(),
                enablement.merchantId().value().toString(), enablement.status().name());
    }

    private static MerchantId merchantOrAbsent(String raw) {
        try {
            return MerchantId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static BnplEnablementId enablementOrAbsent(String raw) {
        try {
            return BnplEnablementId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    /** A canonical UUIDv7 (ADR-0013), or the uniform 404. */
    private static BnplFeeAssignmentProposal.Id feeProposalOrAbsent(String raw) {
        try {
            UUID parsed = UUID.fromString(raw);
            if (parsed.toString().equals(raw) && parsed.version() == 7 && parsed.variant() == 2) {
                return BnplFeeAssignmentProposal.Id.of(parsed);
            }
        } catch (IllegalArgumentException | NullPointerException malformed) {
            // the uniform 404 below
        }
        throw notFound();
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }

    private static MerchantBnplProduct productOrAbsent(String raw) {
        try {
            return MerchantBnplProduct.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "A merchant BNPL read found nothing at the identifier",
                "the requested resource does not exist.");
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a merchant BNPL command runs inside a correlation scope"))
                .correlationId();
    }
}
