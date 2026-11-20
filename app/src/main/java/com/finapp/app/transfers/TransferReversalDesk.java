package com.finapp.app.transfers;

import com.finapp.app.telemetry.TransferMetrics;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.transfers.ReversalProposalStatus;
import com.finapp.transfers.TransferId;
import com.finapp.transfers.TransferReversalProposal;
import com.finapp.transfers.TransferReversalProposalId;
import com.finapp.transfers.TransferReversalProposals;
import com.finapp.transfers.TransferReversalRefusedException;
import com.finapp.transfers.TransfersErrorCode;
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
 * The boundary half of a transfer's reversal by four eyes (`P17-TSK-031`, ADR-0154): parse exactly, key each act per
 * principal, run it in one transaction, translate transfers' refusals onto the error contract, and tell the meters once it
 * committed. The rules - a completed transfer, one open proposal, four eyes - are transfers' and V005's.
 *
 * <p><strong>Keyed</strong> ({@code INV-IDEM-01}; ADR-C135 decision 2): the platform's {@link IdempotentExecutor} in the act's
 * own transaction, scoped {@value #SCOPE} and the principal, fingerprinted over the route's fields with the reason as its
 * digest. A retry after a lost answer replays the first receipt - the reversal posted once - and another request under the
 * same key is {@code 409} ({@code INV-IDEM-03}).
 */
@RequiredArgsConstructor
public final class TransferReversalDesk {

    static final String SCOPE = "transfers.reversal:";

    @NonNull private final TransferReversalProposals proposals;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionTemplate transferTransactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final TransferMetrics metrics;

    /** A reversal proposal's receipt: identifiers, its status and the transfer's status after the act. */
    public record ReversalReceipt(String proposalId, String transferId, String status, String transferStatus) {}

    /** Proposes the transfer's reversal. */
    public ReversalReceipt propose(String idempotencyKey, String rawTransferId, String reason) {
        TransferId transferId = uuid(rawTransferId).map(TransferId::of).orElseThrow(TransferReversalDesk::notFound);
        Actor actor = SecurityContext.require();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("PROPOSE|" + transferId.value() + "|" + digest(reason))
                .getBytes(StandardCharsets.UTF_8));
        return keyed(key, fingerprint, uow -> proposals.propose(uow, transferId, reason)
                .map(proposed -> receipt(proposed, "COMPLETED"))
                .orElseThrow(TransferReversalDesk::notFound)).receipt();
    }

    /** Approves or rejects the proposal; an approval reverses the transfer. */
    public ReversalReceipt decide(String idempotencyKey, String rawProposalId, ReversalProposalStatus outcome, String reason) {
        TransferReversalProposalId id = uuid(rawProposalId).map(TransferReversalProposalId::of)
                .orElseThrow(TransferReversalDesk::notFound);
        Actor actor = SecurityContext.require();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("DECIDE|" + id.value() + "|" + outcome.name() + "|"
                + digest(reason)).getBytes(StandardCharsets.UTF_8));
        Keyed decided = keyed(key, fingerprint, uow -> (outcome == ReversalProposalStatus.APPROVED
                        ? proposals.approve(uow, id, reason)
                        : proposals.reject(uow, id, reason))
                .map(done -> receipt(done.proposal(), done.transferStatus().name()))
                .orElseThrow(TransferReversalDesk::notFound));
        if (outcome == ReversalProposalStatus.APPROVED && !decided.replayed()) {
            // Post-commit and only for the acting call (P3-TSK-020's meter): a replay reversed nothing.
            metrics.reversed();
        }
        return decided.receipt();
    }

    private record Keyed(ReversalReceipt receipt, boolean replayed) {}

    private Keyed keyed(IdempotencyKey key, RequestFingerprint fingerprint, Function<Connection, ReversalReceipt> act) {
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> transferTransactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return executor.execute(unitOfWork, key, fingerprint, uow -> {
                    ReversalReceipt receipt = act.apply(uow);
                    String stored = String.join("|", receipt.proposalId(), receipt.transferId(), receipt.status(),
                            receipt.transferStatus());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                });
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        }));
        String[] stored = new String(outcome.body().orElseThrow(
                () -> new IllegalStateException("a reversal act stores its receipt")), StandardCharsets.UTF_8).split("\\|", -1);
        return new Keyed(new ReversalReceipt(stored[0], stored[1], stored[2], stored[3]), outcome.replayed());
    }

    private static ReversalReceipt receipt(TransferReversalProposal proposal, String transferStatus) {
        return new ReversalReceipt(proposal.id().value().toString(), proposal.transferId().value().toString(),
                proposal.status().name(), transferStatus);
    }

    /** transfers' refusals, in the API's words; a refused reversal counts once on the meter. */
    private <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (TransferReversalRefusedException refused) {
            TransfersErrorCode code = switch (refused.reason()) {
                case NOT_REVERSIBLE -> TransfersErrorCode.NOT_REVERSIBLE;
                case ALREADY_PROPOSED -> TransfersErrorCode.REVERSAL_ALREADY_PROPOSED;
                case ALREADY_DECIDED -> TransfersErrorCode.REVERSAL_ALREADY_DECIDED;
                case SELF_DECISION -> TransfersErrorCode.REVERSAL_SELF_DECISION;
            };
            if (code == TransfersErrorCode.NOT_REVERSIBLE) {
                metrics.refused();
            }
            throw new ApiException(code, "A transfer reversal act was refused", refused.getMessage());
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
        return new ApiException(PlatformErrorCode.NOT_FOUND, "No transfer or reversal proposal matches the requested identifier",
                "no such record.");
    }
}
