package com.finapp.transfers;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.ReversalCommand;
import com.finapp.ledger.ReversalService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The reversal command (`P4-TSK-009`, M4.5): the privileged, reasoned correction —
 * {@code COMPLETED -> REVERSED} with the new referencing entry posted through the ledger's
 * {@link ReversalService}, in <strong>one local transaction</strong> (ADR-0043's discipline
 * applied to the correction). The original entry is never touched ({@code INV-REV-01} — it is
 * immutable at {@code DB-PRIVILEGE} and this command holds no path that could try), and the
 * reversal is the original's full amount with the directions swapped, validated against the
 * original by {@code ReversalBound} ({@code INV-REV-02}; partials are not a transfer-level
 * concept).
 *
 * <h2>Lock-then-look on the transfer row is the arbiter</h2>
 *
 * <p>{@link TransferStore#lockById} takes {@code FOR UPDATE} on the transfer row
 * <em>before any ledger work</em>, and the machine is judged from the locked read
 * ({@code INV-LIFE-02}'s one vocabulary: {@code canTransitionTo(REVERSED)}). The loser of two
 * concurrent reversals blocks on the lock, resumes on the winner's commit, sees
 * {@code REVERSED}, and is refused with <strong>nothing posted</strong> — a clean 409 rather
 * than a ledger-level over-reversal surfacing from `V009`'s advisory bound. The conditional
 * {@code UPDATE}'s row count ({@link TransferStore#markReversed}) is the recorded belt, the
 * aggregate's own {@code reverse()} check and `V002`'s trigger edges the layers beneath, and
 * the ledger bound the deepest — four mechanisms, blind in different directions, so ten
 * instances produce exactly one reversal entry and one state move.
 *
 * <h2>The machine is the idempotency, deliberately</h2>
 *
 * <p>No idempotency key (the `P3-TSK-021` approval precedent): {@code COMPLETED -> REVERSED}
 * happens at most once ever, so a retry cannot create a second effect — {@code INV-IDEM-01}
 * through state. A retry after a lost response gets the 409 naming the state, and the
 * transfer view carries the reversal. The ledger posting inside does claim — scope
 * {@code ledger.reverse}, key {@code transfer:<id>} — which can never legitimately replay,
 * because the claim and the state move commit together.
 *
 * <h2>Two financial decisions, stated rather than discovered</h2>
 *
 * <p><strong>The reversal posts unconditionally — no availability judgement on the
 * destination.</strong> It debits the destination wallet; if that customer has spent the
 * money, the wallet goes negative, which is the true position (`P3-TSK-008`: negative is a
 * legal state). Gating a privileged correction on the recipient's spending would let spending
 * make a correction impossible. <strong>A destination product closed since the transfer</strong>
 * makes the debit hit `V007`'s not-postable trigger: the transaction rolls back whole, the
 * transfer stays {@code COMPLETED}, and the surface answers the already-catalogued
 * {@code ledger.AccountNotPostable} — the recorded corner, no repair path here.
 */
@RequiredArgsConstructor
public final class TransferReversal {

    static final String REVERSED_EVENT_TYPE = "transfers.TransferReversed";

    @NonNull private final TransferStore<Connection> transfers;
    @NonNull private final ReversalService reversals;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * Reverses the transfer, or refuses with nothing written.
     *
     * @param reason the operator's justification — required ({@code PHASE_4_PLAN.md} §11) and
     *     carried on the audit record, whose action makes it structurally mandatory; the
     *     boundary bounds it, and a blank one here is an internal caller defect, loud
     * @return the reversed transfer, or empty when no transfer answers to {@code transfer} —
     *     the surface's one 404, with nothing locked and nothing written
     * @throws IllegalTransferTransitionException the machine refuses ({@code FAILED},
     *     already-{@code REVERSED}, or the loser of a concurrent race after the winner's
     *     commit) — thrown before any ledger work, so the rollback has nothing to take
     */
    public Optional<Transfer> reverse(Connection unitOfWork, TransferId transfer, String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(transfer, "transfer must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            // The boundary refuses this with a 422 before the command is reached; here it is
            // an internal caller defect, refused before any lock is taken.
            throw new IllegalArgumentException(
                    "a reversal requires a reason (PHASE_4_PLAN.md section 11)");
        }

        // An unestablished actor is an error, never a default (ADR-0021). The operator is a
        // person: a platform-initiated reversal does not exist this phase, and enterSystem()
        // reaching this line fails loudly at the UUID parse rather than storing "system"
        // where V002 expects a person.
        Actor actor = SecurityContext.require();
        UUID actorId = UUID.fromString(actor.id());
        Correlation correlation = resolvedCorrelation();

        // The serialisation point: FOR UPDATE, then look - the locked read IS the fresh look
        // (P2-TSK-015). Unknown answers empty with nothing locked.
        Optional<Transfer> locked = transfers.lockById(unitOfWork, transfer);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        Transfer current = locked.get();

        // The machine, judged from the locked row BEFORE any ledger work - derived from
        // permittedTransitions(), never a status literal, so a state added later is judged
        // by the machine (INV-LIFE-02's vocabulary; the NOT_ACTIVE naming lesson).
        if (!current.status().canTransitionTo(TransferStatus.REVERSED)) {
            throw new IllegalTransferTransitionException(
                    transfer, current.status(), TransferStatus.REVERSED);
        }

        // The referencing entry: the original's lines with the directions swapped, full
        // amount - debit the destination wallet, credit the source wallet (both LIABILITY:
        // the platform's debt to the destination shrinks back, to the source grows back).
        // Posting and value dates are today by the injected clock - a correction posts today
        // about then (DOMAIN_MODEL.md section Time; the execution's own explicit decision).
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        PostingResult posted =
                reversals.reverse(
                        unitOfWork,
                        new ReversalCommand(
                                "transfer:" + current.id().value(),
                                current.journalEntryId(),
                                today,
                                today,
                                current.id().value().toString(),
                                List.of(
                                        new JournalLine(
                                                current.destinationAccount(),
                                                Direction.DEBIT,
                                                current.amount()),
                                        new JournalLine(
                                                current.sourceAccount(),
                                                Direction.CREDIT,
                                                current.amount()))));

        // The transition through the aggregate's own door (INV-LIFE-02, defence in depth
        // behind the pre-check above), then onto the row - the conditional's row count can
        // only be 1 under the held lock.
        Transfer reversed = current.reverse(posted.entryId(), actorId, clock);
        transfers.markReversed(unitOfWork, reversed, current.status());

        Instant now = Instant.now(clock);
        transfers.recordTransition(unitOfWork, reversed, current.status(), actorId, now);
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        TransfersAuditAction.TRANSFER_REVERSED,
                        TransferExecution.TARGET_TYPE,
                        reversed.id().value().toString(),
                        // The justification enters the trail at the moment the operator
                        // writes it - the action's requiresReason() makes absence a refusal
                        // at construction, never a quiet null.
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers only - never an amount (INV-AUD-02).
                        Optional.of(
                                "transfer=" + reversed.id()
                                        + ", entry=" + reversed.journalEntryId()
                                        + ", reversalEntry=" + reversed.reversalEntryId())));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        REVERSED_EVENT_TYPE,
                        TransferExecution.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        reversed.id(),
                        TransferExecution.TARGET_TYPE,
                        now,
                        TransferExecution.PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                EventPayload.of().with("status", reversed.status().name()).toBytes(),
                EventPayload.MEDIA_TYPE);
        return Optional.of(reversed);
    }

    /** The flow's correlation with the cause resolved — the {@code PostingService} idiom. */
    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a reversal must run inside a correlation"
                                                        + " scope: the row, the audit record"
                                                        + " and the event all carry the"
                                                        + " identifier"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }
}
