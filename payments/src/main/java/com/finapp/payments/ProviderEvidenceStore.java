package com.finapp.payments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Retention of verbatim provider payloads ({@code P5-TSK-009}, over {@code V005},
 * {@code INV-HIST-02}): whatever the total mapping said, the bytes land here — encrypted,
 * checksummed, append-only for every writer. The store owns the at-rest ceremony (encrypt,
 * hash, insert; decrypt-and-verify on read) so a caller cannot retain evidence any other way —
 * the {@code kyc} store-owns-the-cipher shape.
 */
public interface ProviderEvidenceStore<T> {

    /**
     * The bound verbatim retention can honestly promise (`INV-HIST-02`): retention of an
     * unbounded stream is not a property anyone can keep, so both producers — the wire
     * client's answers and the webhook door (`P5-TSK-012`) — refuse an over-limit body at
     * their own boundary, and `V005`'s size {@code CHECK} reconciles against this one number.
     */
    int MAX_PAYLOAD_BYTES = 1_048_576;

    /**
     * Retains {@code payload} verbatim. At most one subject; both empty is legal — an
     * unattributable webhook is still retained ({@code V005}'s recorded rule).
     */
    void append(
            T unitOfWork,
            Optional<PaymentAttemptId> attempt,
            Optional<RefundId> refund,
            EvidenceKind kind,
            byte[] payload,
            Instant recordedAt);

    /**
     * Retains a withdrawal's scheme bytes verbatim (`P7-TSK-008`) — the third subject, its
     * own door so the two-subject callers above ripple nowhere. `V016` widens the
     * at-most-one-subject rule to the trio.
     */
    void appendForWithdrawal(
            T unitOfWork,
            WithdrawalId withdrawal,
            EvidenceKind kind,
            byte[] payload,
            Instant recordedAt);

    /**
     * Retains a dispute response's PSP bytes verbatim (`P7-TSK-014`) — the FOURTH subject, its own
     * door like the withdrawal's. `V022` widens the at-most-one-subject rule to four. The
     * REQUEST body is never retained here: a representment's request carries the evidence
     * itself, which rests once, under its own key, in {@code dispute_evidence}.
     */
    void appendForDisputeResponse(
            T unitOfWork,
            DisputeResponseId response,
            EvidenceKind kind,
            byte[] payload,
            Instant recordedAt);

    /**
     * The attempt's retained payloads, oldest first, decrypted and checksum-verified — a
     * mismatch is corruption and throws rather than yielding bytes that are not the evidence.
     */
    List<byte[]> payloadsFor(T unitOfWork, PaymentAttemptId attempt);
}
