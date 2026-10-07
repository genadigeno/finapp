package com.finapp.app.reconciliation;

import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.payments.DisputeId;
import com.finapp.payments.DisputeStore;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.RailId;
import com.finapp.payments.RefundId;
import com.finapp.payments.UnmatchedConfirmationStore;
import com.finapp.payments.WithdrawalId;
import com.finapp.reconciliation.EvidenceTargetKind;
import com.finapp.reconciliation.EvidenceTargets;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.TraceEvidence;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementFileStore;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The case file's view beyond reconciliation (`P8-TSK-014`, ADR-0064's app-composed joins):
 * {@link EvidenceTargets} — does a link's settlement, ledger or payments target exist — and
 * {@link TraceEvidence} — a batch's file and recognition entry, a line's batch, and the
 * provider statements payments retained about an operation. Over the siblings' PUBLIC read
 * stores, lock-free, on the caller's connection; identifiers and metadata only, never a byte of
 * content (file content stays behind settlement's audited content read, payloads behind
 * payments' decrypt-and-verify read).
 *
 * <p>An operation reference is read the way its opener wrote it (RECONCILIATION_MODEL.md's
 * table): an attempt, refund or withdrawal id; a dispute id, whose statements arrive on the
 * disputed attempt; a parking's {@code <rail>:<schemeReference>}, found by payments' own
 * reference finder and then through the evidence's fifth subject. A merchant payout's
 * statements are merchant's ({@code merchant.payout_evidence}), reached through the payout's
 * own doors — the trace names the operation and stops (recorded).
 */
@RequiredArgsConstructor
public final class ComposedCaseFileEvidence implements EvidenceTargets, TraceEvidence {

    @NonNull private final SettlementFileStore<Connection> files;
    @NonNull private final SettlementBatchStore<Connection> batches;
    @NonNull private final JournalEntryStore<Connection> entries;
    @NonNull private final ProviderEvidenceStore<Connection> providerEvidence;
    @NonNull private final DisputeStore<Connection> disputes;
    @NonNull private final UnmatchedConfirmationStore<Connection> parkings;

    // ------------------------------------------------------------------ targets

    @Override
    public boolean exists(Connection unitOfWork, EvidenceTargetKind kind, UUID id) {
        return switch (kind) {
            case SETTLEMENT_FILE -> files.fileById(unitOfWork, id).isPresent();
            case SETTLEMENT_BATCH -> batches.batchById(unitOfWork, id).isPresent();
            case SETTLEMENT_LINE -> batches.batchOfLine(unitOfWork, id).isPresent();
            // A platform identifier is a UUIDv7 (ADR-0013): any other UUID names no entry,
            // and a typed id refuses to be built from it - the answer is "absent", not a 500.
            case JOURNAL_ENTRY ->
                    typed(id, JournalEntryId::of)
                            .flatMap(entry -> entries.findById(unitOfWork, entry))
                            .isPresent();
            case PROVIDER_EVIDENCE -> providerEvidence.evidenceExists(unitOfWork, id);
            case RUN, DECISION, OPERATION ->
                    throw new IllegalArgumentException(
                            kind + " is reconciliation's own target, verified by its store");
        };
    }

    // ------------------------------------------------------------------ trace

    @Override
    public Optional<BatchFacts> batch(Connection unitOfWork, UUID batchId) {
        return batches.batchById(unitOfWork, batchId)
                .map(
                        batch ->
                                new BatchFacts(
                                        batch.fileId(),
                                        batches.recognitionEntryOf(unitOfWork, batchId)));
    }

    @Override
    public Optional<UUID> batchOfLine(Connection unitOfWork, UUID lineId) {
        return batches.batchOfLine(unitOfWork, lineId);
    }

    @Override
    public List<UUID> providerEvidence(
            Connection unitOfWork, ExpectationKind kind, String operationRef) {
        List<ProviderEvidenceStore.EvidenceMetadata> found =
                switch (kind) {
                    case CARD_CAPTURE, PUSH_PAY_IN ->
                            uuid(operationRef)
                                    .flatMap(id -> typed(id, PaymentAttemptId::of))
                                    .map(id -> providerEvidence.evidenceMetadataFor(unitOfWork, id))
                                    .orElse(List.of());
                    case CARD_REFUND, PUSH_RETURN ->
                            uuid(operationRef)
                                    .flatMap(id -> typed(id, RefundId::of))
                                    .map(id -> providerEvidence.evidenceMetadataFor(unitOfWork, id))
                                    .orElse(List.of());
                    case PUSH_WITHDRAWAL ->
                            uuid(operationRef)
                                    .flatMap(id -> typed(id, WithdrawalId::of))
                                    .map(id -> providerEvidence.evidenceMetadataFor(unitOfWork, id))
                                    .orElse(List.of());
                    case CHARGEBACK, CHARGEBACK_REVERSAL, DISPUTE_FEE ->
                            // A dispute's statements arrive on the disputed attempt (the
                            // webhook door retains them under the attempt subject).
                            uuid(operationRef)
                                    .flatMap(id -> typed(id, DisputeId::of))
                                    .flatMap(id -> disputes.findById(unitOfWork, id))
                                    .map(dispute -> providerEvidence.evidenceMetadataFor(
                                            unitOfWork, dispute.dispute().attemptId()))
                                    .orElse(List.of());
                    case UNMATCHED_CONFIRMATION -> parkingEvidence(unitOfWork, operationRef);
                    case MERCHANT_PAYOUT, PAYOUT_RETURN, REMITTANCE -> List.of();
                    // A cover leg's provider evidence is the cover's own, retained by fx under
                    // its request reference - the trace joins it with the cover (P9-TSK-012).
                    case FX_SELL_LEG, FX_BUY_LEG -> List.of();
                    // An outbound credit's provider evidence is the credit's own (payments
                    // provider_evidence by outbound_credit_id) - the operator trace joins it with
                    // the credit (P9-TSK-027); the case file links none of it (the transition's
                    // read, 2026-10-07: the comment said "until then nothing opens these kinds").
                    case CROSSBORDER_PAYOUT, CROSSBORDER_RETURN -> List.of();
                };
        return found.stream().map(ProviderEvidenceStore.EvidenceMetadata::id).toList();
    }

    /** {@code <rail>:<schemeReference>} — the operation the parking's posting key names. */
    private List<ProviderEvidenceStore.EvidenceMetadata> parkingEvidence(
            Connection unitOfWork, String operationRef) {
        int colon = operationRef.indexOf(':');
        if (colon <= 0 || colon == operationRef.length() - 1) {
            return List.of();
        }
        return parkings.findByReference(
                        unitOfWork,
                        RailId.of(operationRef.substring(0, colon)),
                        new ProviderReference(operationRef.substring(colon + 1)))
                .map(parking -> providerEvidence.evidenceMetadataForUnmatched(
                        unitOfWork, parking.id()))
                .orElse(List.of());
    }

    /** A typed platform id, or empty when the UUID is not one (ADR-0013's UUIDv7). */
    private static <T> Optional<T> typed(UUID id, java.util.function.Function<UUID, T> factory) {
        try {
            return Optional.of(factory.apply(id));
        } catch (IllegalArgumentException notAPlatformId) {
            return Optional.empty();
        }
    }

    private static Optional<UUID> uuid(String operationRef) {
        try {
            return Optional.of(UUID.fromString(operationRef));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }
}
