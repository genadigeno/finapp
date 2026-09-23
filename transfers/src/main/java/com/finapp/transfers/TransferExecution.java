package com.finapp.transfers;

import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountNotPostableException;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
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
 * The execution command (`P4-TSK-005`, the phase's High-risk task): one transfer judged and
 * committed with its money in <strong>one local transaction</strong> — ADR-0043's whole
 * decision. Claim → resolve → judge → source lock → availability in-lock → seams → post →
 * outcome, all on the caller's connection; a crash anywhere leaves <em>nothing</em>, a domain
 * refusal commits {@code FAILED} with its enumerated reason and no posting, and a boundary
 * mistake is thrown with nothing written — the rollback takes the claim with it, so the retry
 * re-attempts (`P3-TSK-006`'s property).
 *
 * <h2>The claim is at the financial boundary</h2>
 *
 * <p>Scope {@code transfer.execute}, the caller's key, and a fingerprint binding <strong>the
 * actor</strong> and the money's meaning — source, destination, amount, currency, scale,
 * reference ({@code INV-IDEM-03}'s subjects; the actor per ADR-0004's owning principal, so a
 * stranger replaying a logged key conflicts rather than reading somebody else's outcome). The
 * stored body replays the <strong>original judgement, success and failure both</strong>: a
 * {@code FAILED} transfer committed, so its claim survives and its retry learns the refusal.
 *
 * <h2>The reason precedence is the aggregate's, and the constructor teaches it</h2>
 *
 * <p>{@code SELF_TRANSFER} is judged first — any other reason committed with an equal account
 * pair is a shape {@link Transfer}'s constructor refuses, which is the coherence design doing
 * the teaching — then {@code SOURCE_NOT_POSTABLE}, {@code DESTINATION_NOT_POSTABLE},
 * {@code CURRENCY_MISMATCH} (either side's wallet is not in the amount's currency — resolution
 * is deliberately currency-blind so this row still carries the real accounts), and only then,
 * under the lock, {@code INSUFFICIENT_FUNDS}.
 *
 * <h2>The lock, and what runs inside it</h2>
 *
 * <p>{@code SELECT … FOR UPDATE} on <strong>both participants' account rows, in one fixed
 * order</strong> — ADR-0039's enumerated set, third member: the mode that conflicts with
 * every in-flight posting's {@code FOR KEY SHARE} and with every sibling drainer, so the
 * loser's fresh derivation sees the winner's committed movement. The source's postability is
 * judged from the <em>locked</em> row, availability from {@link AvailableBalance} (postings
 * and hold rows, never the projection — {@code INV-BAL-04/-05}), and the two seams are
 * consulted in-lock so Phase 13 inherits atomicity.
 *
 * <p>This paragraph read <em>"the destination is deliberately never locked: a credit needs no
 * availability answer"</em> until `P4-TST-001`. The claim was true of the explicit lock and
 * false about what happens — the posting's foreign key takes {@code FOR KEY SHARE} on the
 * destination regardless — and the uncontrolled order between the two was a deadlock that a
 * one-directional drain structurally cannot reach. See {@link #lockBothInFixedOrder}, where
 * the correction and its measurement are recorded.
 *
 * <h2>The posting, behind a savepoint</h2>
 *
 * <p>The money moves as one {@code POSTING} entry — debit the source wallet, credit the
 * destination wallet (both {@code LIABILITY}: the platform's debt to the source shrinks, to
 * the destination grows) — through {@link PostingService} on this same connection, with the
 * transfer's id as the entry's {@code reference} and {@code transfer:<id>} as the posting's
 * own idempotency key (the {@code ledger.adjust.approve:<id>} precedent), so a replayed
 * transfer never re-enters the posting. Behind a {@link Savepoint}, because `V007`'s
 * destination trigger aborts the transaction state when it refuses — and the refusal must
 * become a committed {@code FAILED(DESTINATION_NOT_POSTABLE)}, not a lost transaction. It
 * names the destination with certainty because the source was verified under our own lock.
 * Posting and value dates are both today by the injected clock — the explicit decision
 * {@code DOMAIN_MODEL.md} §Time requires of the caller, correct for an internally-settled
 * instant movement, revisited when scheduled transfers exist.
 */
@RequiredArgsConstructor
public final class TransferExecution {

    /** The idempotency scope (ADR-0004): one command type, one scope. */
    public static final String IDEMPOTENCY_SCOPE = "transfer.execute";

    static final String COMPLETED_EVENT_TYPE = "transfers.TransferCompleted";
    static final String FAILED_EVENT_TYPE = "transfers.TransferFailed";
    static final String PRODUCER = "transfers";
    static final int EVENT_VERSION = 1;
    static final String TARGET_TYPE = "transfer";

    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransferParticipants<Connection> participants;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final AvailableBalance<Connection> availability;
    @NonNull private final PostingService postings;
    @NonNull private final TransferStore<Connection> transfers;
    // Required, with no defaulted overload (the PostingObserver precedent): a skipped
    // control must not compile, so Phase 13's wiring is a decision (P4-TSK-010).
    @NonNull private final TransferLimitCheck<Connection> limits;
    @NonNull private final TransferRiskDecision<Connection> risk;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * Executes {@code command} at most once for its key, or replays the recorded judgement.
     *
     * @throws UnknownTransferSourceException nothing written — the caller's 4xx
     * @throws UnknownTransferDestinationException nothing written — the caller's 4xx
     * @throws com.finapp.platform.idempotency.IdempotencyConflictException the key was used
     *     for a materially different request ({@code INV-IDEM-03})
     */
    public TransferResult execute(Connection unitOfWork, TransferCommand command) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(command, "command must not be null");

        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_SCOPE, command.idempotencyKey());
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(canonicalForm(command, actor));

        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        fingerprint,
                        uow -> judge(uow, command, actor, correlation));

        String[] body =
                new String(
                                outcome.body()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "a recorded transfer outcome"
                                                                    + " always carries the id"
                                                                    + " and the judgement")),
                                StandardCharsets.UTF_8)
                        .split("\\|");
        return new TransferResult(
                TransferId.of(UUID.fromString(body[0])),
                TransferStatus.valueOf(body[1]),
                body.length > 2
                        ? Optional.of(FailureReason.valueOf(body[2]))
                        : Optional.empty(),
                outcome.replayed());
    }

    /** The judgement: everything between the claim and the recorded outcome, one transaction. */
    private CommandResult judge(
            Connection uow, TransferCommand command, Actor actor, Correlation correlation) {
        // A transfer is a person's act with their own money: the actor is the proven identity,
        // and its id is that identity's UUID. The platform never initiates one, so an
        // enterSystem() caller fails loudly here rather than storing "system" where V002
        // expects a person.
        UUID actorId = UUID.fromString(actor.id());
        TransferParticipants.Source source =
                participants
                        .sourceOwnedBy(uow, command.callerPartyId(), command.sourceProductRef())
                        .orElseThrow(UnknownTransferSourceException::new);
        TransferParticipants.Side destination =
                participants
                        .destination(uow, command.destinationProductRef())
                        .orElseThrow(UnknownTransferDestinationException::new);

        Transfer initiated =
                Transfer.initiate(
                        ids,
                        clock,
                        source.customerId(),
                        source.side().account(),
                        destination.account(),
                        command.amount(),
                        command.reference(),
                        actorId);

        // The pre-lock judgements, in the aggregate's own precedence (class javadoc). The
        // source's postability is re-judged under the lock below; this early answer covers the
        // product half the locked ledger row cannot see.
        Optional<FailureReason> refusal = preLockRefusal(command, source, destination);
        if (refusal.isPresent()) {
            return commit(uow, initiated.fail(refusal.get()), actor, correlation);
        }

        // The serialization point (ADR-0039's set, third member) - BOTH participants, in one
        // fixed order. See lockBothInFixedOrder: locking the source alone deadlocks the moment
        // money moves in both directions between one pair (P4-TST-001's finding).
        LedgerAccount lockedSource = lockBothInFixedOrder(uow, initiated);
        if (lockedSource.status() != LedgerAccountStatus.ACTIVE) {
            return commit(uow, initiated.fail(FailureReason.SOURCE_NOT_POSTABLE), actor,
                    correlation);
        }

        // Look, under the lock, from authoritative rows (INV-BAL-04/-05) - and then the seams,
        // in-lock so Phase 13 inherits atomicity (P4-TSK-010's contract).
        if (availability.underLock(uow, initiated.sourceAccount())
                .minus(command.amount())
                .isNegative()) {
            return commit(uow, initiated.fail(FailureReason.INSUFFICIENT_FUNDS), actor,
                    correlation);
        }
        // A seam's REFUSE is a committed domain outcome carrying that seam's own reserved
        // reason (P4-TSK-010) - one reason per seam, mapped HERE so a limit implementation can
        // never commit the risk vocabulary - judged in-lock on this same unit of work, which is
        // how Phase 13's durable counters commit atomically with the movement (INV-CON-03).
        if (limits.check(uow, initiated) == SeamVerdict.REFUSE) {
            return commit(uow, initiated.fail(FailureReason.LIMIT_REFUSED), actor, correlation);
        }
        if (risk.check(uow, initiated) == SeamVerdict.REFUSE) {
            return commit(uow, initiated.fail(FailureReason.RISK_REFUSED), actor, correlation);
        }

        // The money, behind a savepoint (class javadoc): V007's destination refusal aborts the
        // transaction state, and it must become a committed FAILED, not a lost transaction.
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Savepoint beforePosting = savepoint(uow);
        try {
            PostingResult posted =
                    postings.post(
                            uow,
                            new PostingCommand(
                                    "transfer:" + initiated.id().value(),
                                    today,
                                    today,
                                    initiated.id().value().toString(),
                                    List.of(
                                            new JournalLine(
                                                    initiated.sourceAccount(),
                                                    Direction.DEBIT,
                                                    command.amount()),
                                            new JournalLine(
                                                    initiated.destinationAccount(),
                                                    Direction.CREDIT,
                                                    command.amount()))));
            return commit(uow, initiated.complete(posted.entryId()), actor, correlation);
        } catch (LedgerAccountNotPostableException destinationRefused) {
            // The source was verified under our own lock, so a not-postable refusal from the
            // posting names the destination with certainty (P3-TSK-014's trigger, observed
            // mid-flight - the plan's scenario 7).
            rollback(uow, beforePosting);
            return commit(uow, initiated.fail(FailureReason.DESTINATION_NOT_POSTABLE), actor,
                    correlation);
        }
    }

    /** The pre-lock refusals, in the precedence the aggregate's pair rule forces. */
    /**
     * Locks <strong>both</strong> participating account rows {@code FOR UPDATE} in one fixed
     * order, and answers the source's locked row.
     *
     * <h2>Why the destination is locked, when a credit needs no availability answer</h2>
     *
     * <p>This method's javadoc said for three tasks that "the destination is deliberately
     * never locked", and that was true of the <em>explicit</em> lock and false about what
     * happens: every {@code journal_line} insert takes {@code FOR KEY SHARE} on its account
     * through the foreign key (`P3-TSK-014`'s recorded mechanism), so the destination's row
     * <em>is</em> locked — implicitly, and in whatever order the two participants happen to
     * be reached. {@code FOR UPDATE} conflicts with {@code FOR KEY SHARE}, so A→B holding
     * {@code FOR UPDATE(A)} and needing {@code KEY SHARE(B)} against B→A holding
     * {@code FOR UPDATE(B)} and needing {@code KEY SHARE(A)} is a cycle, and PostgreSQL
     * aborts one with {@code 40P01}.
     *
     * <p><strong>Found by measurement, not by reading</strong>: `P4-TST-001`'s storm —
     * ten instances moving money both ways between one pair — produced <strong>783
     * deadlocks against 203 domain outcomes</strong>. The one-directional drain
     * (`P4-TSK-005`) could not reach it, because a cycle needs two directions. Money was
     * never at risk (a deadlocked transaction writes nothing, so conservation held exactly),
     * but {@code INV-CON-02} requires the loser to fail with a <strong>domain outcome</strong>,
     * and an infrastructure abort is not one.
     *
     * <p>The remedy is the idiom this module's own sibling already names:
     * {@code LedgerAccountStore.lockOwnedForUpdate} orders its multi-account lock by id
     * "so two multi-account closers cannot deadlock" (`P3-TSK-009`'s precedent), and a
     * transfer is a multi-account operation that had not applied it. A global total order
     * over the locked rows makes a cycle impossible.
     *
     * <p><strong>The order need not be the database's</strong>, and deliberately is not:
     * {@code UUID.compareTo} compares signed longs while PostgreSQL compares bytewise, and
     * they disagree (`P3-TSK-008`'s recorded trap). That disagreement is harmless here,
     * because what a deadlock-free protocol requires is that every <em>instance</em> agree
     * on one order — not that the order match any particular sort.
     */
    private LedgerAccount lockBothInFixedOrder(Connection uow, Transfer initiated) {
        LedgerAccountId source = initiated.sourceAccount();
        LedgerAccountId destination = initiated.destinationAccount();
        boolean sourceFirst = source.value().compareTo(destination.value()) <= 0;
        LedgerAccount firstLocked =
                lockOrThrow(uow, sourceFirst ? source : destination);
        if (source.equals(destination)) {
            // Unreachable: an equal pair is refused as SELF_TRANSFER before the lock. Kept
            // so the ordering cannot become a double lock if that ever changes.
            return firstLocked;
        }
        LedgerAccount secondLocked =
                lockOrThrow(uow, sourceFirst ? destination : source);
        return sourceFirst ? firstLocked : secondLocked;
    }

    /**
     * A gone row under a resolved product is an invariant already broken — loud, never a
     * domain outcome.
     */
    private LedgerAccount lockOrThrow(Connection uow, LedgerAccountId account) {
        return ledgerAccounts
                .lockForUpdate(uow, account)
                .orElseThrow(
                        () ->
                                new TransfersStorageException(
                                        "account " + account + " resolved and then vanished -"
                                                + " an invariant is already broken"));
    }

    private static Optional<FailureReason> preLockRefusal(
            TransferCommand command,
            TransferParticipants.Source source,
            TransferParticipants.Side destination) {
        if (source.side().account().equals(destination.account())) {
            return Optional.of(FailureReason.SELF_TRANSFER);
        }
        if (!source.side().postable()) {
            return Optional.of(FailureReason.SOURCE_NOT_POSTABLE);
        }
        if (!destination.postable()) {
            return Optional.of(FailureReason.DESTINATION_NOT_POSTABLE);
        }
        if (!source.side().currency().equals(command.amount().currency())
                || !destination.currency().equals(command.amount().currency())) {
            return Optional.of(FailureReason.CURRENCY_MISMATCH);
        }
        return Optional.empty();
    }

    /** The judged transfer's write set: row, history, audit, terminal event — one commit. */
    private CommandResult commit(
            Connection uow, Transfer judged, Actor actor, Correlation correlation) {
        Instant now = Instant.now(clock);
        transfers.insert(uow, judged);
        transfers.recordTransition(
                uow, judged, TransferStatus.INITIATED, UUID.fromString(actor.id()), now);
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        TransfersAuditAction.TRANSFER_EXECUTED,
                        TARGET_TYPE,
                        judged.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and enumerated names - never an amount (INV-AUD-02).
                        Optional.of(
                                "transfer=" + judged.id()
                                        + ", status=" + judged.status()
                                        + (judged.failureReason() == null
                                                ? ""
                                                : ", reason=" + judged.failureReason())
                                        + (judged.journalEntryId() == null
                                                ? ""
                                                : ", entry=" + judged.journalEntryId()))));
        boolean completed = judged.status() == TransferStatus.COMPLETED;
        EventPayload payload =
                EventPayload.of().with("status", judged.status().name());
        if (!completed) {
            payload = payload.with("failureReason", judged.failureReason().name());
        }
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        completed ? COMPLETED_EVENT_TYPE : FAILED_EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        judged.id(),
                        TARGET_TYPE,
                        now,
                        PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);

        String body =
                judged.id().value()
                        + "|"
                        + judged.status()
                        + (judged.failureReason() == null ? "" : "|" + judged.failureReason());
        StoredResponse response =
                StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
        // A FAILED transfer is a definitive outcome, recorded as CommandResult.failed - the
        // type's own documented case ("a rejected transfer"): replayed to a retry, never
        // re-attempted.
        return completed ? CommandResult.succeeded(response) : CommandResult.failed(response);
    }

    /**
     * The fingerprint's canonical form: the actor and the money's meaning ({@code INV-IDEM-03}).
     * The correlation is deliberately excluded — a retry arrives on a new request and must
     * still replay.
     */
    private static byte[] canonicalForm(TransferCommand command, Actor actor) {
        return (IDEMPOTENCY_SCOPE
                        + "|" + actor.id()
                        + "|" + command.callerPartyId()
                        + "|" + command.sourceProductRef()
                        + "|" + command.destinationProductRef()
                        + "|" + command.amount().minorUnits()
                        + "|" + command.amount().currency().code()
                        + "|" + command.amount().scale()
                        + "|" + command.reference())
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The flow's correlation with the cause resolved — the {@code PostingService} idiom. */
    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a transfer must run inside a correlation"
                                                        + " scope: the row, the audit record"
                                                        + " and the event all carry the"
                                                        + " identifier"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }

    private static Savepoint savepoint(Connection uow) {
        try {
            return uow.setSavepoint("transfer_posting");
        } catch (SQLException failure) {
            throw new TransfersStorageException(
                    "could not set the posting savepoint; is the connection in a transaction?"
                            + " (auto-commit must be off - the execution is one transaction"
                            + " by design, ADR-0043)");
        }
    }

    private void rollback(Connection uow, Savepoint savepoint) {
        try {
            uow.rollback(savepoint);
        } catch (SQLException failure) {
            throw new TransfersStorageException(
                    "could not roll back to the posting savepoint (SQLState "
                            + failure.getSQLState() + ")");
        }
    }
}
