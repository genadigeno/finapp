package com.finapp.payments;

import java.time.Instant;
import java.util.Objects;

/**
 * One capture's clearing evidence (`P7-TSK-005`, ADR-0059 §4): the network's own references
 * — the acquirer reference and the network transaction identifier — recorded once per
 * attempt when the PSP reports the capture cleared on the card network.
 *
 * <p><strong>Clearing agrees an obligation; it moves no money</strong> ({@code INV-SET-01}):
 * this record has no amount — the capture row already holds the money facts, and Phase 8
 * matches the settlement file's amount against the capture, never against a copy — no
 * machine state and no posting. It is born final and append-only for every writer
 * ({@code INV-HIST-02}'s regime): these references are exactly what a clearing or
 * settlement file is matched against, so a record that could be edited would corrupt the
 * match before it starts.
 *
 * <p>Both references take the provider-reference shape ({@link ProviderReference}): the
 * acquirer's and the network's names for one transaction, the same trust class as the PSP's
 * own operation references.
 */
public record ClearingRecord(
        ClearingRecordId id,
        PaymentAttemptId attemptId,
        ProviderReference acquirerReference,
        ProviderReference networkTransactionId,
        Instant recordedAt) {

    public ClearingRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(acquirerReference, "acquirerReference must not be null");
        Objects.requireNonNull(networkTransactionId, "networkTransactionId must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
    }
}
