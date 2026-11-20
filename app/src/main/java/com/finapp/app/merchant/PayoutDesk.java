package com.finapp.app.merchant;

import com.finapp.app.api.RecordedReads;
import com.finapp.merchant.MerchantAuditAction;
import com.finapp.merchant.MerchantErrorCode;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.PayoutProposal;
import com.finapp.merchant.PayoutProposals;
import com.finapp.merchant.PayoutRequiresApprovalException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator payout door's four-eyes half (`P17-TSK-031`, ADR-0154; finapp-admin-panel ADR-C135 decision 3: "Operator merchant
 * payout: four-eyes above a threshold"). At or below the threshold the payout dispatches exactly as before; above it - judged
 * by the payout command itself, under the merchant's lock - the answer is a keyed proposal, {@code PROPOSED}; a holder of
 * {@code MERCHANT_PAYOUT_APPROVE} who did not propose it approves it through the same command, the decision written inside its
 * dispatch transaction, or rejects it, and nothing moves. A merchant's own payout, by its key, never meets this.
 */
@RequiredArgsConstructor
public final class PayoutDesk {

    static final String PROPOSAL_SCOPE = "merchant.payout-proposal:";
    static final String REJECTION_SCOPE = "merchant.payout-rejection:";

    @NonNull private final MerchantPayoutOperations payouts;
    @NonNull private final PayoutProposals proposals;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionTemplate payoutProposalTransactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final RecordedReads reads;

    /**
     * A payout proposal as its decider reads it (`P17-TSK-124`): the merchant it would pay - its name and status now - the
     * amount exact with its currency and the threshold's version, who proposed it, when and why, whether the caller did, and
     * once decided the decision's instant and reason and an approval's payout ({@code null} until then). Never who decided.
     */
    public record PayoutProposalView(
            String proposalId, String merchantId, String merchantName, String merchantStatus, String amount, String currency,
            int thresholdVersion, String payoutId, String status, String reason, String proposedBy, boolean proposedByCaller,
            String proposedAt, String decidedAt, String decisionReason) {}

    /**
     * Proposal {@code rawId} with its merchant, recorded {@code merchant.PayoutProposalRead} in the read's own transaction
     * (`P17-TSK-124`). An unknown or malformed identifier: 404, and nothing recorded.
     */
    public PayoutProposalView read(String rawId) {
        PayoutProposal.Id id = uuid(rawId).map(PayoutProposal.Id::of).orElseThrow(PayoutDesk::notFound);
        String caller = SecurityContext.require().id();
        PayoutProposals.Read read = inOneTransaction(uow -> proposals.read(uow, id).map(found -> {
            reads.record(uow, MerchantAuditAction.PAYOUT_PROPOSAL_READ, "PayoutProposal", id.value().toString(),
                    "merchant=" + found.proposal().merchantId().value() + ", status=" + found.proposal().status());
            return found;
        })).orElseThrow(PayoutDesk::notFound);
        PayoutProposal proposal = read.proposal();
        return new PayoutProposalView(proposal.id().value().toString(), proposal.merchantId().value().toString(),
                read.merchant().displayName(), read.merchant().status().name(),
                proposal.amount().toBigDecimal().toPlainString(), proposal.amount().currency().code(),
                proposal.thresholdVersion(), proposal.payoutId().map(payout -> payout.value().toString()).orElse(null),
                proposal.status().name(), proposal.proposalReason(), proposal.proposedBy(), proposal.proposedBy().equals(caller),
                proposal.proposedAt().toString(),
                proposal.decision().map(PayoutProposal.Decision::decidedAt).map(Instant::toString).orElse(null),
                proposal.decision().map(PayoutProposal.Decision::reason).orElse(null));
    }

    /** The operator's payout: dispatched at or below the threshold, proposed above it. */
    public MerchantPayoutOperations.PayoutView initiateAsOperator(
            String rawMerchantId, OperatorPayoutRequest body, String idempotencyKey) {
        try {
            return payouts.initiateAsOperator(rawMerchantId, body, idempotencyKey);
        } catch (PayoutRequiresApprovalException above) {
            MerchantId merchant = payouts.operatorMerchantOf(rawMerchantId);
            Money amount = payouts.operatorAmountOf(body);
            Actor actor = SecurityContext.require();
            IdempotencyKey key = new IdempotencyKey(PROPOSAL_SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
            RequestFingerprint fingerprint = RequestFingerprint.sha256(("PROPOSE|" + merchant.value() + "|"
                    + amount.minorUnits() + "|" + amount.currency().code() + "|" + digest(body.reason()))
                    .getBytes(StandardCharsets.UTF_8));
            return keyed(key, fingerprint, uow -> view(proposals.propose(uow, merchant, amount, body.reason(), above),
                    "PROPOSED"));
        }
    }

    /** Approves the proposal: the payout dispatches through the operator door's own command, the decision in its transaction. */
    public MerchantPayoutOperations.PayoutView approve(String idempotencyKey, String rawId, String reason) {
        PayoutProposal.Id id = uuid(rawId).map(PayoutProposal.Id::of).orElseThrow(PayoutDesk::notFound);
        PayoutProposal proposal = inOneTransaction(uow -> proposals.find(uow, id)).orElseThrow(PayoutDesk::notFound);
        return guarded(() -> payouts.approvePayout(proposal.merchantId(), proposal.amount(), proposal.proposalReason(),
                idempotencyKey, proposals.approval(id, reason)));
    }

    /** Rejects the proposal: nothing moves. */
    public MerchantPayoutOperations.PayoutView reject(String idempotencyKey, String rawId, String reason) {
        PayoutProposal.Id id = uuid(rawId).map(PayoutProposal.Id::of).orElseThrow(PayoutDesk::notFound);
        Actor actor = SecurityContext.require();
        IdempotencyKey key = new IdempotencyKey(REJECTION_SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("REJECT|" + id.value() + "|" + digest(reason))
                .getBytes(StandardCharsets.UTF_8));
        return keyed(key, fingerprint, uow -> proposals.reject(uow, id, reason)
                .map(rejected -> view(rejected, "REJECTED"))
                .orElseThrow(PayoutDesk::notFound));
    }

    private MerchantPayoutOperations.PayoutView keyed(IdempotencyKey key, RequestFingerprint fingerprint,
            Function<Connection, MerchantPayoutOperations.PayoutView> act) {
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> inOneTransaction(uow -> executor.execute(uow, key,
                fingerprint, work -> {
                    MerchantPayoutOperations.PayoutView view = act.apply(work);
                    String stored = String.join("|", view.id(), view.status(), view.amount(), view.currency(),
                            view.createdAt());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                })));
        String[] stored = new String(outcome.body().orElseThrow(
                () -> new IllegalStateException("a payout proposal act stores its answer")), StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new MerchantPayoutOperations.PayoutView(stored[0], stored[1], stored[2], stored[3], null, null, stored[4]);
    }

    private static MerchantPayoutOperations.PayoutView view(PayoutProposal proposal, String status) {
        return new MerchantPayoutOperations.PayoutView(proposal.id().value().toString(), status,
                proposal.amount().toBigDecimal().toPlainString(), proposal.amount().currency().code(), null, null,
                proposal.proposedAt().toString());
    }

    /** merchant's proposal refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (PayoutProposal.Refused refused) {
            MerchantErrorCode code = switch (refused.reason()) {
                case ALREADY_PROPOSED -> MerchantErrorCode.PAYOUT_PROPOSAL_ALREADY_OPEN;
                case ALREADY_DECIDED -> MerchantErrorCode.PAYOUT_PROPOSAL_ALREADY_DECIDED;
                case SELF_DECISION -> MerchantErrorCode.PAYOUT_PROPOSAL_SELF_DECISION;
            };
            throw new ApiException(code, "A payout proposal act was refused", refused.getMessage());
        }
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return payoutProposalTransactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return work.apply(unitOfWork);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
    }

    /** A canonical UUIDv7 (ADR-0013), or nothing: anything else names nothing - a 404. */
    private static Optional<UUID> uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) && parsed.version() == 7 && parsed.variant() == 2
                    ? Optional.of(parsed)
                    : Optional.empty();
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return Optional.empty();
        }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }

    private static ApiException notFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "No payout proposal matches the requested identifier",
                "no such record.");
    }
}
