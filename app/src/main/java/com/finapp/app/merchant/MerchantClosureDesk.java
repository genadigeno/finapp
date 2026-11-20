package com.finapp.app.merchant;

import com.finapp.app.api.RecordedReads;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.merchant.ClosureProposalStatus;
import com.finapp.merchant.MerchantAuditAction;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantClosureProposal;
import com.finapp.merchant.MerchantClosureProposalId;
import com.finapp.merchant.MerchantClosures;
import com.finapp.merchant.MerchantClosureRefusedException;
import com.finapp.merchant.MerchantNotSettledException;
import com.finapp.merchant.MerchantErrorCode;
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
 * The boundary half of a merchant's closure by four eyes (`P17-TSK-031`, ADR-0154): parse exactly, key each act per
 * principal, run it in one transaction, translate merchant's refusals onto the error contract. The rules - an ACTIVE,
 * settled merchant, one open proposal, four eyes - are merchant's and V019's.
 *
 * <p><strong>Keyed</strong> ({@code INV-IDEM-01}; ADR-C135 decision 2): the platform's {@link IdempotentExecutor} in the act's
 * own transaction, scoped {@value #SCOPE} and the principal, fingerprinted over the route's fields with the reason as its
 * digest. A retry after a lost answer replays the first receipt - the merchant closed once - and another request under the
 * same key is {@code 409} ({@code INV-IDEM-03}).
 */
@RequiredArgsConstructor
public final class MerchantClosureDesk {

    static final String SCOPE = "merchant.closure:";

    @NonNull private final MerchantClosures proposals;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionTemplate merchantClosureTransactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final RecordedReads reads;

    /** A closure proposal's receipt: identifiers, its status and the merchant's status after the act. */
    public record ClosureReceipt(String proposalId, String merchantId, String status, String merchantStatus) {}

    /**
     * A closure proposal as its decider reads it (`P17-TSK-124`): the merchant it would close - its name and status now; a
     * closure moves no amount - who proposed it, when and why, whether the caller did, and once decided the decision's instant
     * and reason ({@code null} until then). Never who decided.
     */
    public record MerchantClosureProposalView(
            String proposalId, String merchantId, String merchantName, String merchantStatus, String status, String reason,
            String proposedBy, boolean proposedByCaller, String proposedAt, String decidedAt, String decisionReason) {}

    /**
     * Proposal {@code rawProposalId} with its merchant, recorded {@code merchant.ClosureProposalRead} in the read's own
     * transaction (`P17-TSK-124`). An unknown or malformed identifier: 404, and nothing recorded.
     */
    public MerchantClosureProposalView read(String rawProposalId) {
        MerchantClosureProposalId id = uuid(rawProposalId).map(MerchantClosureProposalId::of)
                .orElseThrow(MerchantClosureDesk::notFound);
        String caller = SecurityContext.require().id();
        MerchantClosures.Read read = merchantClosureTransactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return proposals.read(unitOfWork, id).map(found -> {
                    reads.record(unitOfWork, MerchantAuditAction.CLOSURE_PROPOSAL_READ, "MerchantClosureProposal",
                            id.value().toString(), "merchant=" + found.proposal().merchantId().value() + ", status="
                                    + found.proposal().status());
                    return found;
                });
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        }).orElseThrow(MerchantClosureDesk::notFound);
        MerchantClosureProposal proposal = read.proposal();
        return new MerchantClosureProposalView(proposal.id().value().toString(), proposal.merchantId().value().toString(),
                read.merchant().displayName(), read.merchant().status().name(), proposal.status().name(),
                proposal.proposalReason(), proposal.proposedBy(), proposal.proposedBy().equals(caller),
                proposal.proposedAt().toString(),
                proposal.decision().map(MerchantClosureProposal.Decision::decidedAt).map(Instant::toString).orElse(null),
                proposal.decision().map(MerchantClosureProposal.Decision::reason).orElse(null));
    }

    /** Proposes the merchant's closure. */
    public ClosureReceipt propose(String idempotencyKey, String rawMerchantId, String reason) {
        MerchantId merchantId = uuid(rawMerchantId).map(MerchantId::of).orElseThrow(MerchantClosureDesk::notFound);
        Actor actor = SecurityContext.require();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("PROPOSE|" + merchantId.value() + "|" + digest(reason))
                .getBytes(StandardCharsets.UTF_8));
        return keyed(key, fingerprint, uow -> proposals.propose(uow, merchantId, reason)
                .map(proposed -> receipt(proposed, "ACTIVE"))
                .orElseThrow(MerchantClosureDesk::notFound)).receipt();
    }

    /** Approves or rejects the proposal; an approval closes the merchant. */
    public ClosureReceipt decide(String idempotencyKey, String rawProposalId, ClosureProposalStatus outcome, String reason) {
        MerchantClosureProposalId id = uuid(rawProposalId).map(MerchantClosureProposalId::of)
                .orElseThrow(MerchantClosureDesk::notFound);
        Actor actor = SecurityContext.require();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("DECIDE|" + id.value() + "|" + outcome.name() + "|"
                + digest(reason)).getBytes(StandardCharsets.UTF_8));
        Keyed decided = keyed(key, fingerprint, uow -> (outcome == ClosureProposalStatus.APPROVED
                        ? proposals.approve(uow, id, reason)
                        : proposals.reject(uow, id, reason))
                .map(done -> receipt(done.proposal(), done.merchantStatus().name()))
                .orElseThrow(MerchantClosureDesk::notFound));
        return decided.receipt();
    }

    private record Keyed(ClosureReceipt receipt, boolean replayed) {}

    private Keyed keyed(IdempotencyKey key, RequestFingerprint fingerprint, Function<Connection, ClosureReceipt> act) {
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> merchantClosureTransactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return executor.execute(unitOfWork, key, fingerprint, uow -> {
                    ClosureReceipt receipt = act.apply(uow);
                    String stored = String.join("|", receipt.proposalId(), receipt.merchantId(), receipt.status(),
                            receipt.merchantStatus());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                });
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        }));
        String[] stored = new String(outcome.body().orElseThrow(
                () -> new IllegalStateException("a closure act stores its receipt")), StandardCharsets.UTF_8).split("\\|", -1);
        return new Keyed(new ClosureReceipt(stored[0], stored[1], stored[2], stored[3]), outcome.replayed());
    }

    private static ClosureReceipt receipt(MerchantClosureProposal proposal, String merchantStatus) {
        return new ClosureReceipt(proposal.id().value().toString(), proposal.merchantId().value().toString(),
                proposal.status().name(), merchantStatus);
    }

    /** merchant's refusals, in the API's words. */
    private <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (MerchantClosureRefusedException refused) {
            MerchantErrorCode code = switch (refused.reason()) {
                case NOT_CLOSABLE -> MerchantErrorCode.ILLEGAL_TRANSITION;
                case ALREADY_PROPOSED -> MerchantErrorCode.CLOSURE_ALREADY_PROPOSED;
                case ALREADY_DECIDED -> MerchantErrorCode.CLOSURE_ALREADY_DECIDED;
                case SELF_DECISION -> MerchantErrorCode.CLOSURE_SELF_DECISION;
            };
            throw new ApiException(code, "A merchant closure act was refused", refused.getMessage());
        } catch (MerchantNotSettledException unsettled) {
            // The Phase 6 -> 7 transition's refusal, judged at the approval on the state then: pay out what the merchant is
            // owed and let payments in flight finish, then approve again - the proposal stays open.
            throw new ApiException(MerchantErrorCode.NOT_SETTLED,
                    "A closure was refused: the merchant is owed money or has money in flight",
                    "pay out what this merchant is owed and let payments in flight finish before closing it.");
        }
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
        return new ApiException(PlatformErrorCode.NOT_FOUND, "No merchant or closure proposal matches the requested identifier",
                "no such record.");
    }
}
