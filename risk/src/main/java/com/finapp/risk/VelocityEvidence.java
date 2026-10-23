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
 * inputs: the same transaction and database clock as the count, every dimension) - recorded for the coordinator.
 */
public interface VelocityEvidence {

    /** The dimensions this evidence names exactly - a window of any other dimension is reported unprovable. */
    Set<VelocityDimension> provable();

    /** Every counted movement since {@code from} and the instant the record is complete through - one snapshot's read. */
    Record read(Connection snapshot, Instant from);

    /** One movement the windows should have counted, and the instant it was counted at. */
    record Counted(VelocityMovement movement, Instant at) {
        public Counted {
            Objects.requireNonNull(movement, "movement");
            Objects.requireNonNull(at, "at");
        }

        @Override
        public String toString() {
            return "Counted[" + movement + " at " + at + "]";
        }
    }

    /** The evidence: the counted movements, and the instant through which no counted movement is missing from them. */
    record Record(List<Counted> movements, Instant completeThrough) {
        public Record {
            movements = List.copyOf(Objects.requireNonNull(movements, "movements"));
            Objects.requireNonNull(completeThrough, "completeThrough");
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
        return Optional.of(new Counted(movement.build(), row.postedAt()));
    }
}
