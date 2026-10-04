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
 *
 * <p><strong>Two calls around the posting</strong> (`P8-TSK-016`): {@link #intake} before the
 * recognition posts (the run, the items, the breaks — every row the posting does not need), and
 * {@link #recognised} after it, for the rows that carry the recognition's entry id whole — a
 * bank statement's unattributed lines' suspense items ({@code suspense_item.entry_id NOT NULL}).
 * The posting stays the last CONTENDED write: what {@code recognised} inserts is this
 * transaction's own.
 */
public interface AcceptedBatchIntake {

    /** Hands the batch over; returns counts only (never an amount, `INV-AUD-02`). */
    Intaken intake(Connection unitOfWork, AcceptedBatch batch);

    /**
     * Records what needed the recognition's entry — called after the posting, on the same
     * connection, for every batch ({@code entryId} empty when the posting was honestly
     * omitted). A report writes nothing here.
     */
    void recognised(
            Connection unitOfWork, AcceptedBatch batch, Intaken intaken, Optional<UUID> entryId);

    /**
     * What the intake wrote: telemetry and the audit summary's material. {@code unattributed}
     * and {@code continuityBreaks} are a statement's (zero for a report).
     */
    record Intaken(
            UUID runId,
            int items,
            boolean remittanceOpened,
            int unattributed,
            int continuityBreaks) {

        /** A report's answer (`P8-TSK-009`'s shape). */
        public Intaken(UUID runId, int items, boolean remittanceOpened) {
            this(runId, items, remittanceOpened, 0, 0);
        }
    }

    /**
     * One accepted batch, as the acceptance transaction states it. A REPORT carries its
     * source's position and its remittance reference, and no statement; a bank STATEMENT
     * carries neither — its lines carry their attributed positions — and its continuity facts.
     */
    record AcceptedBatch(
            UUID batchId,
            UUID fileId,
            UUID sourceId,
            Optional<AccountPurpose> positionPurpose,
            LocalDate businessDate,
            LocalDate valueDate,
            LocalDate acceptedOn,
            long sourceSequence,
            Optional<String> remittanceReference,
            Money net,
            List<CanonicalLine> lines,
            Actor actor,
            Instant at,
            Correlation correlation,
            Optional<StatementContinuity> statement,
            Optional<String> settlementCycle,
            Optional<String> positionCounterparty) {

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
            Objects.requireNonNull(statement, "statement must not be null");
            Objects.requireNonNull(settlementCycle, "settlementCycle must not be null");
            Objects.requireNonNull(positionCounterparty, "positionCounterparty must not be null");
            if (positionCounterparty.isPresent()
                    != positionPurpose.map(purpose -> purpose.ownerKind()
                            == com.finapp.ledger.OwnerKind.COUNTERPARTY).orElse(false)) {
                throw new IllegalArgumentException(
                        "a report on a counterparty-owned position names its counterparty, and"
                                + " no other report does (P9-TSK-010, ADR-0078)");
            }
            if (settlementCycle.isPresent() && statement.isPresent()) {
                throw new IllegalArgumentException(
                        "a scheme cycle is a report's (P8-TSK-017), never a statement's");
            }
            if (sourceSequence < 1) {
                throw new IllegalArgumentException("a source sequence is 1-based");
            }
            boolean report = positionPurpose.isPresent() && remittanceReference.isPresent();
            boolean bank = positionPurpose.isEmpty() && remittanceReference.isEmpty();
            if (!(report && statement.isEmpty()) && !(bank && statement.isPresent())) {
                throw new IllegalArgumentException(
                        "an accepted batch is a report (a position and a remittance reference)"
                                + " or a statement (its continuity), exactly one");
            }
            lines = List.copyOf(lines);
        }

        /** A report's acceptance (`P8-TSK-009`'s shape), on a shared position. */
        public AcceptedBatch(
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
            this(batchId, fileId, sourceId, positionPurpose, businessDate, valueDate, acceptedOn,
                    sourceSequence, remittanceReference, net, lines, actor, at, correlation,
                    Optional.empty());
        }

        /**
         * A report's acceptance on its source's position - a counterparty's own when the source
         * names one (`P9-TSK-010`, ADR-0078): the counterparty rides to the remittance, which
         * opens on that counterparty's account.
         */
        public AcceptedBatch(
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
                Correlation correlation,
                Optional<String> positionCounterparty) {
            this(batchId, fileId, sourceId,
                    Optional.of(Objects.requireNonNull(positionPurpose,
                            "positionPurpose must not be null")),
                    businessDate, valueDate, acceptedOn, sourceSequence,
                    Optional.of(Objects.requireNonNull(remittanceReference,
                            "remittanceReference must not be null")),
                    net, lines, actor, at, correlation, Optional.empty(), Optional.empty(),
                    positionCounterparty);
        }

        /** A statement's or a cycle-less report's acceptance (`P8-TSK-016`'s shape). */
        public AcceptedBatch(
                UUID batchId,
                UUID fileId,
                UUID sourceId,
                Optional<AccountPurpose> positionPurpose,
                LocalDate businessDate,
                LocalDate valueDate,
                LocalDate acceptedOn,
                long sourceSequence,
                Optional<String> remittanceReference,
                Money net,
                List<CanonicalLine> lines,
                Actor actor,
                Instant at,
                Correlation correlation,
                Optional<StatementContinuity> statement) {
            this(batchId, fileId, sourceId, positionPurpose, businessDate, valueDate, acceptedOn,
                    sourceSequence, remittanceReference, net, lines, actor, at, correlation,
                    statement, Optional.empty(), Optional.empty());
        }

        /** A report settling a scheme cycle (`P8-TSK-017`): the cycle token rides with it. */
        public AcceptedBatch withSettlementCycle(String cycle) {
            return new AcceptedBatch(
                    batchId, fileId, sourceId, positionPurpose, businessDate, valueDate,
                    acceptedOn, sourceSequence, remittanceReference, net, lines, actor, at,
                    correlation, statement, Optional.of(cycle), positionCounterparty);
        }
    }

    /**
     * A bank statement's place in its account's chain (`P8-TSK-016`, {@code INV-SET-06}): its
     * own sequence and signed balances, and the ACCEPTED neighbours read under the source row
     * lock the accept leg holds — the predecessor it must stitch to, and the successor whose gap
     * it may fill.
     */
    record StatementContinuity(
            long sequence,
            Money opening,
            Money closing,
            Optional<Neighbour> predecessor,
            Optional<Neighbour> successor) {

        public StatementContinuity {
            Objects.requireNonNull(opening, "opening must not be null");
            Objects.requireNonNull(closing, "closing must not be null");
            Objects.requireNonNull(predecessor, "predecessor must not be null");
            Objects.requireNonNull(successor, "successor must not be null");
            if (sequence < 1) {
                throw new IllegalArgumentException("a statement sequence starts at 1");
            }
        }
    }

    /** An accepted neighbouring statement, as the chain sees it. */
    record Neighbour(UUID batchId, long sequence, Money opening, Money closing) {

        public Neighbour {
            Objects.requireNonNull(batchId, "batchId must not be null");
            Objects.requireNonNull(opening, "opening must not be null");
            Objects.requireNonNull(closing, "closing must not be null");
        }
    }

    /**
     * One canonical line, copied whole — the item the matcher will dispose of. A bank credit or
     * debit carries its attribution: the source whose remittance pattern matched and that
     * source's clearing position (both, or neither — then it is unattributed and parks owned).
     */
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
            Map<LineReferenceKind, String> references,
            Optional<UUID> attributedSourceId,
            Optional<AccountPurpose> attributedPosition) {

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
            Objects.requireNonNull(attributedSourceId, "attributedSourceId must not be null");
            Objects.requireNonNull(attributedPosition, "attributedPosition must not be null");
            if (lineNo < 1) {
                throw new IllegalArgumentException("a line number is 1-based");
            }
            if (canonicalFingerprint.length != 32) {
                throw new IllegalArgumentException("a canonical fingerprint is a SHA-256");
            }
            if (attributedSourceId.isPresent() != attributedPosition.isPresent()) {
                throw new IllegalArgumentException(
                        "an attributed line names its source and that source's position");
            }
            if (attributedSourceId.isPresent()
                    && type != SettlementLineType.BANK_CREDIT
                    && type != SettlementLineType.BANK_DEBIT) {
                throw new IllegalArgumentException(
                        "attribution is a bank credit's or debit's alone (ADR-0065 section 3)");
            }
            canonicalFingerprint = canonicalFingerprint.clone();
            references = Map.copyOf(references);
        }

        /** A report line — never attributed (`P8-TSK-009`'s shape). */
        public CanonicalLine(
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
            this(lineId, lineNo, type, direction, amount, businessDate, settlementDate, valueDate,
                    canonicalFingerprint, references, Optional.empty(), Optional.empty());
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
