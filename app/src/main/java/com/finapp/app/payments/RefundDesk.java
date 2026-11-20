package com.finapp.app.payments;

import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentsErrorCode;
import com.finapp.payments.RefundProposal;
import com.finapp.payments.RefundProposals;
import com.finapp.payments.RefundRequiresApprovalException;
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
 * The refund door's four-eyes half (`P17-TSK-031`, ADR-0154; finapp-admin-panel ADR-C135 decision 3: "Refund: four-eyes above
 * a threshold"). At or below the threshold the door refunds exactly as before. Above it - judged by the refund command
 * itself, under the attempt lock - the answer is a keyed proposal, {@code PROPOSED}; a holder of {@code PAYMENT_REFUND_APPROVE}
 * who did not propose it approves it through the same command, the decision written inside its dispatch transaction, or
 * rejects it, and nothing moves.
 *
 * <p><strong>Keys</strong> ({@code INV-IDEM-01}; the plan's scenario 4): the door's key keeps the refund's own scope; a retry
 * of a refund that became a proposal is judged above the threshold again and replays the proposal under
 * {@value #PROPOSAL_SCOPE}. The approval's key is the refund's own - so a lost approval, retried, replays the one refund. The
 * rejection's is {@value #REJECTION_SCOPE}.
 */
@RequiredArgsConstructor
public final class RefundDesk {

    static final String PROPOSAL_SCOPE = "payment.refund-proposal:";
    static final String REJECTION_SCOPE = "payment.refund-rejection:";

    @NonNull private final PaymentService payments;
    @NonNull private final RefundProposals proposals;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionTemplate paymentTransactions;
    @NonNull private final DataSource dataSource;

    /** The operator's refund: dispatched at or below the threshold, proposed above it. */
    public PaymentService.RefundView refund(PaymentIntentId intentId, RefundRequest body, String idempotencyKey) {
        try {
            return payments.refundPayment(intentId, body, idempotencyKey);
        } catch (RefundRequiresApprovalException above) {
            Money amount = payments.refundAmountOf(body);
            Actor actor = SecurityContext.require();
            IdempotencyKey key = new IdempotencyKey(PROPOSAL_SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
            RequestFingerprint fingerprint = RequestFingerprint.sha256(("PROPOSE|" + intentId.value() + "|" + amount.minorUnits()
                    + "|" + amount.currency().code() + "|" + digest(body.reason())).getBytes(StandardCharsets.UTF_8));
            return keyed(key, fingerprint, uow -> {
                RefundProposal proposed = proposals.propose(uow, intentId, amount, body.reason(), above);
                return view(proposed.id().value(), "PROPOSED", amount);
            });
        }
    }

    /** Approves the proposal: the refund dispatches through the door's own command, the decision in its transaction. */
    public PaymentService.RefundView approve(String idempotencyKey, String rawId, String reason) {
        RefundProposal.Id id = uuid(rawId).map(RefundProposal.Id::of).orElseThrow(RefundDesk::notFound);
        RefundProposal proposal = inOneTransaction(uow -> proposals.find(uow, id)).orElseThrow(RefundDesk::notFound);
        return guarded(() -> payments.approveRefund(proposal.intentId(), proposal.amount(), proposal.proposalReason(),
                idempotencyKey, proposals.approval(id, reason)));
    }

    /** Rejects the proposal: nothing moves. */
    public PaymentService.RefundView reject(String idempotencyKey, String rawId, String reason) {
        RefundProposal.Id id = uuid(rawId).map(RefundProposal.Id::of).orElseThrow(RefundDesk::notFound);
        Actor actor = SecurityContext.require();
        IdempotencyKey key = new IdempotencyKey(REJECTION_SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("REJECT|" + id.value() + "|" + digest(reason))
                .getBytes(StandardCharsets.UTF_8));
        return keyed(key, fingerprint, uow -> proposals.reject(uow, id, reason)
                .map(rejected -> view(rejected.id().value(), "REJECTED", rejected.amount()))
                .orElseThrow(RefundDesk::notFound));
    }

    private PaymentService.RefundView keyed(IdempotencyKey key, RequestFingerprint fingerprint,
            Function<Connection, PaymentService.RefundView> act) {
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> inOneTransaction(uow -> executor.execute(uow, key,
                fingerprint, work -> {
                    PaymentService.RefundView view = act.apply(work);
                    String stored = String.join("|", view.id(), view.status(), view.amount(), view.currency());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                })));
        String[] stored = new String(outcome.body().orElseThrow(
                () -> new IllegalStateException("a refund proposal act stores its answer")), StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new PaymentService.RefundView(stored[0], stored[1], stored[2], stored[3]);
    }

    private static PaymentService.RefundView view(UUID id, String status, Money amount) {
        return new PaymentService.RefundView(id.toString(), status, amount.toBigDecimal().toPlainString(),
                amount.currency().code());
    }

    /** payments' proposal refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (RefundProposal.Refused refused) {
            PaymentsErrorCode code = switch (refused.reason()) {
                case ALREADY_PROPOSED -> PaymentsErrorCode.REFUND_PROPOSAL_ALREADY_OPEN;
                case ALREADY_DECIDED -> PaymentsErrorCode.REFUND_PROPOSAL_ALREADY_DECIDED;
                case SELF_DECISION -> PaymentsErrorCode.REFUND_PROPOSAL_SELF_DECISION;
            };
            throw new ApiException(code, "A refund proposal act was refused", refused.getMessage());
        }
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return paymentTransactions.execute(status -> {
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
        return new ApiException(PlatformErrorCode.NOT_FOUND, "No refund proposal matches the requested identifier",
                "no such record.");
    }
}
