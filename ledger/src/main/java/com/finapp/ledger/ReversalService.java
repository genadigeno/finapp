package com.finapp.ledger;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The reversal command: a <strong>new</strong> financial effect referencing the original
 * (`P3-TSK-016`, {@code INV-REV-01}, {@code INV-REV-02}) — {@link PostingService}'s position
 * and discipline, for the correction that must never be an edit.
 *
 * <h2>Nothing here touches the original, structurally</h2>
 *
 * <p>The original is immutable at {@code DB-PRIVILEGE} (`P3-TSK-005`: no {@code UPDATE}, no
 * {@code DELETE}, the append-only trigger binding even the migrator), and this service holds
 * no path that could try — it reads the original, validates the compensating lines against
 * it, and appends. The original is byte-identical afterwards, asserted by test rather than
 * assumed.
 *
 * <h2>The bound has two layers, blind in different directions</h2>
 *
 * <p>{@link ReversalBound} refuses a bad command deterministically, before any idempotency
 * claim is consumed — reading <em>committed</em> prior reversals, so it races a concurrent
 * one. The race's arbiter is `V009`'s trigger, which takes an advisory transaction lock on
 * the original's identity for <strong>every</strong> writer and re-judges under it; a racer
 * this pre-check waves through is refused at the append and surfaces as the same named
 * {@link OverReversalException}, translated by the store from the trigger's marker.
 *
 * <p>No HTTP surface (plan §9 declares none — the URL-named correction surface is
 * `P3-TSK-017`'s adjustment, behind {@code LEDGER_ADJUST}); the callers are platform flows,
 * Phase 5's refunds foremost, inside their own transaction under the flow's actor.
 */
public final class ReversalService {

    /** The idempotency scope (ADR-0004): one command type, one scope. */
    public static final String IDEMPOTENCY_SCOPE = "ledger.reverse";

    /** {@code journal_entry.idempotency_scope} is CHECK-bounded; the pair must fit it. */
    private static final int MAX_SCOPE_AND_KEY_LENGTH = 200;

    private final IdempotentExecutor executor;
    private final JournalEntryStore<Connection> journal;
    private final PostingEffect effect;
    private final IdGenerator ids;
    private final Clock clock;
    private final PostingObserver observer;

    public ReversalService(
            IdempotentExecutor executor,
            JournalEntryStore<Connection> journal,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            BalanceProjection<Connection> projection,
            IdGenerator ids,
            Clock clock,
            PostingObserver observer) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.effect = new PostingEffect(journal, audit, outbox, projection, ids, clock);
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    /**
     * Reverses the command's original at most once for its key, or replays the outcome.
     *
     * <p>Validate, then claim, then effect (`P3-TSK-006`'s order): a refused reversal never
     * consumes its key, and a refusal thrown here rolls the caller's transaction back, so it
     * writes nothing structurally.
     *
     * @throws OverReversalException the bound refuses ({@code INV-REV-02}), or a line
     *     mirrors a pair the original does not have — from the pre-check deterministically,
     *     or from the trigger's translation when a concurrent reversal won the race
     * @throws IllegalArgumentException an unknown original, or an original that is itself a
     *     {@code REVERSAL} — caller defects on an internal API, loud and amount-free
     * @throws UnbalancedJournalEntryException before any claim: the key is not consumed
     */
    public PostingResult reverse(Connection unitOfWork, ReversalCommand command) {
        // Observed whatever the outcome (P3-TSK-020): a reversal is a journal-write
        // command like any other, and the write path's meter counts them all.
        Instant started = clock.instant();
        try {
            PostingResult result = doReverse(unitOfWork, command);
            observer.observe(
                    result.replayed()
                            ? PostingObserver.Outcome.REPLAYED
                            : PostingObserver.Outcome.POSTED,
                    Duration.between(started, clock.instant()));
            return result;
        } catch (RuntimeException refusal) {
            observer.observe(
                    PostingObserver.Outcome.REFUSED,
                    Duration.between(started, clock.instant()));
            throw refusal;
        }
    }

    private PostingResult doReverse(Connection unitOfWork, ReversalCommand command) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // Validation first: an unacceptable request never consumes its key. Balance is the
        // aggregate's own gate - a reversal is a journal entry like any other (INV-LED-01).
        JournalEntry entry =
                JournalEntry.balanced(
                        ids, clock, command.postingDate(), command.valueDate(),
                        command.lines());

        JournalEntryStore.PostedEntry original =
                journal.findById(unitOfWork, command.original())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "no journal entry " + command.original()
                                                        + " to reverse"));
        if (original.attribution().entryType() == JournalEntryType.REVERSAL) {
            // A correction of a correction is a new posting or adjustment: a chain would
            // make INV-REV-02's subject ambiguous. V009's entry trigger restates this for
            // the writers the domain never sees.
            throw new IllegalArgumentException(
                    "entry " + command.original() + " is itself a REVERSAL and cannot be"
                            + " reversed (P3-TSK-016)");
        }
        ReversalBound.validate(
                command.original(),
                original.entry().lines(),
                journal.reversalLinesOf(unitOfWork, command.original()),
                command.lines());

        // An unestablished actor is an error, never a default (ADR-0021).
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        String scopeAndKey = IDEMPOTENCY_SCOPE + ":" + command.idempotencyKey();
        if (scopeAndKey.length() > MAX_SCOPE_AND_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "the idempotency key must leave the scope-and-key pair within "
                            + MAX_SCOPE_AND_KEY_LENGTH
                            + " characters, because journal_entry.idempotency_scope records"
                            + " the pair that ties the entry to its command");
        }
        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_SCOPE, command.idempotencyKey());
        RequestFingerprint fingerprint = RequestFingerprint.sha256(canonicalForm(command));
        PostingAttribution attribution =
                new PostingAttribution(
                        JournalEntryType.REVERSAL,
                        command.reference(),
                        Optional.empty(),
                        Optional.of(command.original()),
                        actor.id(),
                        correlation,
                        scopeAndKey);

        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        fingerprint,
                        uow -> effect.record(uow, entry, attribution, actor, correlation));

        JournalEntryId reversal =
                JournalEntryId.of(
                        UUID.fromString(
                                new String(
                                        outcome.body()
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalStateException(
                                                                        "a recorded reversal"
                                                                            + " outcome always"
                                                                            + " carries the"
                                                                            + " entry id")),
                                        StandardCharsets.UTF_8)));
        return new PostingResult(reversal, outcome.replayed());
    }

    /** The flow's correlation with the cause resolved — the {@code PostingService} idiom. */
    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a reversal must run inside a correlation"
                                                        + " scope: the journal's causation"
                                                        + " column is NOT NULL"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }

    private static byte[] canonicalForm(ReversalCommand command) {
        StringBuilder canonical =
                new StringBuilder("ledger.reverse|REVERSAL|")
                        .append(command.original().value())
                        .append('|')
                        .append(command.postingDate())
                        .append('|')
                        .append(command.valueDate())
                        .append('|')
                        .append(command.reference())
                        .append('|');
        for (JournalLine line : command.lines()) {
            canonical
                    .append(line.account().value())
                    .append('>')
                    .append(line.direction())
                    .append('>')
                    .append(line.amount().minorUnits())
                    .append('>')
                    .append(line.amount().currency().code())
                    .append('>')
                    .append(line.amount().scale())
                    .append(';');
        }
        return canonical.toString().getBytes(StandardCharsets.UTF_8);
    }
}
