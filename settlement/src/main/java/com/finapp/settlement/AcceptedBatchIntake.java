package com.finapp.settlement;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The seam through which an accepted batch becomes reconciliation's work (`P8-TSK-009`,
 * ADR-0064 §6): declared here, implemented in `app` — the one join two modules that cannot
 * see each other compose through — and called INSIDE the acceptance transaction, so the run,
 * the items, the remittance expectation and the recognition commit together or not at all
 * ({@code INV-SET-07}).
 *
 * <p>Everything the intake needs is COPIED into this record from settlement's own rows: the
 * matcher never reads another schema (ADR-0064), and the port's failure rolls the whole
 * acceptance back for any instance to re-claim — the accepted coupling, ADR-0067 §6's shape
 * at the batch.
 */
public interface AcceptedBatchIntake {

    /** Hands the batch over; returns counts only (never an amount, `INV-AUD-02`). */
    Intaken intake(Connection unitOfWork, AcceptedBatch batch);

    /** What the intake wrote: telemetry and the audit summary's material. */
    record Intaken(UUID runId, int items, boolean remittanceOpened) {}

    /** One accepted batch, as the acceptance transaction states it. */
    record AcceptedBatch(
            UUID batchId,
            UUID fileId,
            UUID sourceId,
            AccountPurpose positionPurpose,
            LocalDate businessDate,
            LocalDate valueDate,
            LocalDate acceptedOn,
            long sourceSequence,
            String remittanceReference,
            Money net,
            List<CanonicalLine> lines,
            Actor actor,
            Instant at,
            Correlation correlation) {

        public AcceptedBatch {
            Objects.requireNonNull(batchId, "batchId must not be null");
            Objects.requireNonNull(fileId, "fileId must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(positionPurpose, "positionPurpose must not be null");
            Objects.requireNonNull(businessDate, "businessDate must not be null");
            Objects.requireNonNull(valueDate, "valueDate must not be null");
            Objects.requireNonNull(acceptedOn, "acceptedOn must not be null");
            Objects.requireNonNull(remittanceReference, "remittanceReference must not be null");
            Objects.requireNonNull(net, "net must not be null");
            Objects.requireNonNull(lines, "lines must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (sourceSequence < 1) {
                throw new IllegalArgumentException("a source sequence is 1-based");
            }
            lines = List.copyOf(lines);
        }
    }

    /** One canonical line, copied whole — the item the matcher will dispose of. */
    record CanonicalLine(
            UUID lineId,
            int lineNo,
            SettlementLineType type,
            LineDirection direction,
            Money amount,
            LocalDate businessDate,
            Optional<LocalDate> settlementDate,
            Optional<LocalDate> valueDate,
            byte[] canonicalFingerprint,
            Map<LineReferenceKind, String> references) {

        public CanonicalLine {
            Objects.requireNonNull(lineId, "lineId must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(businessDate, "businessDate must not be null");
            Objects.requireNonNull(settlementDate, "settlementDate must not be null");
            Objects.requireNonNull(valueDate, "valueDate must not be null");
            Objects.requireNonNull(canonicalFingerprint, "fingerprint must not be null");
            Objects.requireNonNull(references, "references must not be null");
            if (lineNo < 1) {
                throw new IllegalArgumentException("a line number is 1-based");
            }
            if (canonicalFingerprint.length != 32) {
                throw new IllegalArgumentException("a canonical fingerprint is a SHA-256");
            }
            canonicalFingerprint = canonicalFingerprint.clone();
            references = Map.copyOf(references);
        }

        @Override
        public byte[] canonicalFingerprint() {
            return canonicalFingerprint.clone();
        }

        /** Identifiers only. */
        @Override
        public String toString() {
            return "CanonicalLine[" + lineId + ", " + type + "]";
        }
    }
}
