package com.finapp.merchant;

import java.time.Instant;

/**
 * Append-only retention of the payout provider's answers, verbatim and encrypted
 * ({@code INV-HIST-02}, `P6-TSK-012`). Nothing here is ever read to make a decision: the row's
 * state is the decision; this is what the provider said, kept for reconciliation and dispute.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface PayoutEvidenceStore<T> {

    /** The provider's own bound on a retained answer, mirrored by `V007`'s CHECK. */
    int MAX_PAYLOAD_BYTES = 1_048_576;

    /** Retains one answer's bytes against the payout it answered. */
    void append(
            T unitOfWork,
            MerchantPayoutId payout,
            PayoutEvidenceKind kind,
            byte[] payload,
            Instant recordedAt);
}
