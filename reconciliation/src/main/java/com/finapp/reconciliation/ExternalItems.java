package com.finapp.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.sharedkernel.correlation.CorrelationId;
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
 * The external items (`P8-TSK-009`, §5.4) — reconciliation's working copies of an accepted
 * batch's immutable settlement lines, born {@code PENDING} with disposition zero in the
 * acceptance transaction. Matching will never read another schema to allocate (ADR-0064):
 * everything a decision needs is copied here at intake.
 */
public interface ExternalItems {

    /**
     * Births every item of one batch with its typed keys and history rows — one actor (the
     * acceptance's) for the whole intake. {@code UNIQUE (settlement_line_id)} is the arbiter
     * behind the acceptance's own conditionals; a violation is a programming defect and
     * throws.
     */
    void birthAll(
            Connection unitOfWork, com.finapp.platform.security.Actor actor,
            List<NewItem> items);

    /**
     * One item at birth.
     *
     * <p>A report line stands in its source's position and is never attributed; a bank credit
     * or debit stands in its ATTRIBUTED source's position exactly when it is attributed — the
     * key scope its {@code REMITTANCE_REF} is judged in (`P8-TSK-016`, ADR-0068 §1); a bank fee
     * stands in neither (its effect is the recognition's {@code PROCESSING_COSTS} line). The
     * database's {@code external_item_position_rule} is the same rule's second rank.
     */
    record NewItem(
            UUID id,
            UUID runId,
            UUID sourceId,
            UUID settlementLineId,
            int lineNo,
            ExternalLineType lineType,
            ExpectationDirection direction,
            Money amount,
            Optional<AccountPurpose> positionPurpose,
            Optional<UUID> attributedSourceId,
            LocalDate businessDate,
            Optional<LocalDate> settlementDate,
            Optional<LocalDate> valueDate,
            byte[] canonicalFingerprint,
            Map<ItemKeyKind, String> keys,
            Instant at,
            CorrelationId correlation) {

        public NewItem {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(settlementLineId, "settlementLineId must not be null");
            Objects.requireNonNull(lineType, "lineType must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(positionPurpose, "positionPurpose must not be null");
            Objects.requireNonNull(attributedSourceId, "attributedSourceId must not be null");
            Objects.requireNonNull(businessDate, "businessDate must not be null");
            Objects.requireNonNull(settlementDate, "settlementDate must not be null");
            Objects.requireNonNull(valueDate, "valueDate must not be null");
            Objects.requireNonNull(canonicalFingerprint, "fingerprint must not be null");
            Objects.requireNonNull(keys, "keys must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (lineNo < 1) {
                throw new IllegalArgumentException("a line number is 1-based");
            }
            if (!amount.isPositive()) {
                throw new IllegalArgumentException(
                        "an item's amount is strictly positive - the sign is its direction"
                                + " (the ADR-0003 triple)");
            }
            if (canonicalFingerprint.length != 32) {
                throw new IllegalArgumentException("a canonical fingerprint is a SHA-256");
            }
            boolean positionRuleHolds =
                    switch (lineType) {
                        case BANK_CREDIT, BANK_DEBIT ->
                                positionPurpose.isPresent() == attributedSourceId.isPresent();
                        case BANK_FEE ->
                                positionPurpose.isEmpty() && attributedSourceId.isEmpty();
                        default -> positionPurpose.isPresent() && attributedSourceId.isEmpty();
                    };
            if (!positionRuleHolds) {
                throw new IllegalArgumentException(
                        "a " + lineType + " item's position and attribution disagree"
                                + " (P8-TSK-016's position rule)");
            }
            canonicalFingerprint = canonicalFingerprint.clone();
            keys = Map.copyOf(keys);
        }

        /** A report line's item — its source's position, never attributed. */
        public NewItem(
                UUID id,
                UUID runId,
                UUID sourceId,
                UUID settlementLineId,
                int lineNo,
                ExternalLineType lineType,
                ExpectationDirection direction,
                Money amount,
                AccountPurpose positionPurpose,
                LocalDate businessDate,
                Optional<LocalDate> settlementDate,
                Optional<LocalDate> valueDate,
                byte[] canonicalFingerprint,
                Map<ItemKeyKind, String> keys,
                Instant at,
                CorrelationId correlation) {
            this(id, runId, sourceId, settlementLineId, lineNo, lineType, direction, amount,
                    Optional.of(Objects.requireNonNull(positionPurpose,
                            "positionPurpose must not be null")),
                    Optional.empty(), businessDate, settlementDate, valueDate,
                    canonicalFingerprint, keys, at, correlation);
        }

        @Override
        public byte[] canonicalFingerprint() {
            return canonicalFingerprint.clone();
        }

        /** Identifiers only — an amount in a log line is `INV-AUD-02`'s to refuse. */
        @Override
        public String toString() {
            return "NewItem[" + settlementLineId + ", " + lineType + "]";
        }
    }
}
