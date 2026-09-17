package com.finapp.transfers;

import java.util.Objects;
import java.util.Optional;

/**
 * The judgement, as the caller learns it — including on a replay, where it is the
 * <strong>original</strong> judgement ({@code INV-IDEM-01}: a retry learns what its request
 * did, success and failure both, not what the world looks like now).
 */
public record TransferResult(
        TransferId transferId,
        TransferStatus status,
        Optional<FailureReason> failureReason,
        boolean replayed) {

    public TransferResult {
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(failureReason, "failureReason must not be null");
    }
}
