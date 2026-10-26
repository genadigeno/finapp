package com.finapp.risk;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What {@link VelocityProof} recomputes the windows from (`P13-TSK-006`; {@code PHASE_13_PLAN.md} section 12.4;
 * {@code INV-RSK-03}) - the movements the windows should have counted, each with the instant it was counted at and its
 * subjects, and the instant through which that record is complete. A port, because which record is exact evidence for
 * which window is a question of <em>when</em> each operation's money posts, not of the proof.
 *
 * <p><strong>The evidence this task ships, and its bound.</strong> {@link JdbcVelocityEvidence} reads the monitored
 * transactions - the journal (`-019`). A window counts a movement when it is <em>judged</em>; the journal records it
 * when it <em>posts</em>. The two coincide only where the judged command posts in its own transaction: the
 * <strong>transfer</strong> ({@code TransferExecution} judges after its locks and posts before it commits). A
 * withdrawal posts at its outcome, a card payment at its capture, a cross-border payment at its outbound credit - later,
 * or never - and the journal names no party and no device. So the journal is exact evidence for the
 * {@link VelocityDimension#SOURCE_ACCOUNT} and {@link VelocityDimension#DESTINATION} windows of transfers, and those
 * are its {@link #provable} dimensions; the rest are reported unprovable, never judged. Before a deferred-posting
 * operation's increments are wired (`-009`…`-011`), the evidence moves to the permitting assessments (`-004`'s sealed
 * inputs: the same transaction and database clock as the count, every dimension) - <em>moved by `P13-TSK-004`:
 * {@link AssessmentVelocityEvidence}, the composition's evidence of record; this one stays the journal's cross-check.</em>
 */
public interface VelocityEvidence {

    /** The dimensions this evidence names exactly - a window of any other dimension is reported unprovable. */
    Set<VelocityDimension> provable();

    /** Every counted movement since {@code from} and the instant the record is complete through - one snapshot's read. */
    Record read(Connection snapshot, Instant from);

    /**
     * One movement the windows should have counted, the instant it was counted at, and - when the evidence knows it - the
     * judged operation it was (`P13-TSK-023`: the record names it, so the journal's cross-check can tell a window of
     * transfers alone from one that also counts what posts later or never).
     */
    record Counted(VelocityMovement movement, Instant at, Optional<RiskOperation> operation) {
        public Counted {
            Objects.requireNonNull(movement, "movement");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(operation, "operation");
        }

        /** A movement whose operation the evidence does not name. */
        public Counted(VelocityMovement movement, Instant at) {
            this(movement, at, Optional.empty());
        }

        @Override
        public String toString() {
            return "Counted[" + movement + " at " + at + operation.map(named -> ", " + named).orElse("") + "]";
        }
    }

    /**
     * The evidence: the counted movements, the instant through which no counted movement is missing from them, and -
     * the record's own account (`P13-TSK-023`) - the movements it <em>permitted without counting</em>
     * ({@link VelocityOutcome#UNCOUNTED}: the fail-safe's "the money moves uncounted"), at their assessment's instant
     * (an uncounted transfer posts and is rightly absent from every window, so a link judging windows against postings
     * subtracts its figures from what the postings expect), and {@code firstTransferAt} - the instant of the first
     * transfer the record <em>ever</em> holds, whatever its decision: the transfer seam's first judgement, its go-live,
     * fixed however far back the read reaches (the review's second round: a floor taken from the read itself slid with
     * it). Empty when the record has never held a transfer.
     */
    record Record(List<Counted> movements, Instant completeThrough, List<Counted> uncounted,
            Optional<Instant> firstTransferAt) {
        public Record {
            movements = List.copyOf(Objects.requireNonNull(movements, "movements"));
            Objects.requireNonNull(completeThrough, "completeThrough");
            uncounted = List.copyOf(Objects.requireNonNull(uncounted, "uncounted"));
            Objects.requireNonNull(firstTransferAt, "firstTransferAt");
        }

        /**
         * A record whose read is its whole history - the suites' worlds: the first transfer is the earliest among
         * {@code movements} and {@code uncounted}.
         */
        public Record(List<Counted> movements, Instant completeThrough, List<Counted> uncounted) {
            this(movements, completeThrough, uncounted, earliestTransfer(movements, uncounted));
        }

        /** Evidence that names no uncounted movement - the journal's, and every evidence before `-023`. */
        public Record(List<Counted> movements, Instant completeThrough) {
            this(movements, completeThrough, List.of());
        }

        private static Optional<Instant> earliestTransfer(List<Counted> movements, List<Counted> uncounted) {
            Instant earliest = null;
            for (List<Counted> each : List.of(movements, uncounted)) {
                for (Counted counted : each) {
                    if (counted.operation().filter(operation -> operation == RiskOperation.TRANSFER).isPresent()
                            && (earliest == null || counted.at().isBefore(earliest))) {
                        earliest = counted.at();
                    }
                }
            }
            return Optional.ofNullable(earliest);
        }
    }

    /**
     * The movement a monitored transaction is evidence of, when it is: a {@link MonitoredKind#TRANSFER} on a customer
     * account counts its debited account as the {@link VelocityDimension#SOURCE_ACCOUNT} and its credited customer
     * account as the {@link VelocityDimension#DESTINATION} - whichever side the classification chose as the subject -
     * at the entry's own instant, for its amount. Every other kind, and a transfer with no customer subject, is none.
     */
    static Optional<Counted> transferOf(MonitoredTransaction row) {
        Objects.requireNonNull(row, "row");
        if (row.kind() != MonitoredKind.TRANSFER
                || row.subjectKind().filter(kind -> kind == MonitoredSubjectKind.ACCOUNT).isEmpty()) {
            return Optional.empty();
        }
        Optional<UUID> counterparty = row.counterpartyKind() == MonitoredCounterpartyKind.CUSTOMER
                ? row.counterpartyRef() : Optional.empty();
        VelocityMovement.Builder movement = VelocityMovement.of(
                Money.ofPersisted(row.amountMinor(), row.currency(), row.scale()));
        UUID subject = row.subjectRef().orElseThrow();
        if (row.direction().orElseThrow() == MonitoredDirection.OUT) {
            movement.sourceAccount(subject);
            counterparty.ifPresent(movement::destinationAccount);
        } else {
            movement.destinationAccount(subject);
            counterparty.ifPresent(movement::sourceAccount);
        }
        return Optional.of(new Counted(movement.build(), row.postedAt(), Optional.of(RiskOperation.TRANSFER)));
    }
}
