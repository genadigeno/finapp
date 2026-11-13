package com.finapp.ledger;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
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
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The posting command: one command, one financial effect, whatever the caller does
 * (`P3-TSK-006`, {@code INV-IDEM-01}) — and the one write path {@code INV-LED-04} permits.
 *
 * <h2>Everything commits together, on the caller's transaction</h2>
 *
 * <p>The entry, its lines, the audit record, the outbox row, the balance projection update
 * (`P3-TSK-009`) and the idempotency record all go
 * on {@code unitOfWork} — Phase 4's stated benefit is that a transfer's state transition and
 * its posting commit together, which only works if this command <em>joins</em> a transaction
 * rather than opening one. "The ledger owns the posting transaction"
 * ({@code MODULE_ARCHITECTURE.md}) means the ledger decides what is in the posting's write
 * set; the boundary is the caller's.
 *
 * <h2>Validate, then claim, then effect</h2>
 *
 * <p>{@link JournalEntry#balanced} runs <em>before</em> the idempotency claim, so an
 * unbalanced request never consumes its key — the caller fixes the request and retries under
 * the same key. On a replay the freshly built entry is discarded and the <strong>original</strong>
 * entry's identifier comes back from the stored response; a minted-and-discarded identifier
 * costs nothing (ADR-0013's recorded stance).
 *
 * <h2>Once for ever, whatever becomes of the record (`P16-TSK-011`, ADR-0141)</h2>
 *
 * <p>The idempotency record answers retries until it is swept (platform {@code V016}); the
 * journal answers them for ever, because {@code journal_entry.idempotency_scope} is unique
 * ({@code V037}). Inside the claim, the scope is looked up first: an entry there is a retry
 * whose record has gone, answered with that entry's identifier when it is this request, refused
 * as {@code INV-IDEM-03}'s conflict when it is not, and nothing is written either way. A writer
 * that claimed nothing and committed the scope meanwhile meets the index, which writes nothing
 * and aborts nothing ({@link PostingScopeTakenException}), and is answered the same way.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p><strong>Account status is not checked here, and that is a recorded remainder with an
 * owner.</strong> Every production-reachable account is {@code ACTIVE} — no store writes a
 * status yet — and *posting to a closed account refused under the account lock* is
 * `P3-TSK-014`'s own design, because the refusal is only real inside the lock that closing
 * takes; a lock-free status read here would be the check that passes every test and loses the
 * race. Line-currency-versus-account-currency needs no check at all: {@code V005} binds it at
 * {@code DB-CONSTRAINT} for every writer. The adjustment variant, its permission and its
 * reason are `P3-TSK-017`'s.
 */
public final class PostingService {

    /** The idempotency scope (ADR-0004): one command type, one scope. */
    public static final String IDEMPOTENCY_SCOPE = "ledger.post";

    /** {@code journal_entry.idempotency_scope} is CHECK-bounded; the pair must fit it. */
    private static final int MAX_SCOPE_AND_KEY_LENGTH = 200;

    private final IdempotentExecutor executor;
    private final JournalEntryStore<Connection> journal;
    private final PostingEffect effect;
    private final IdGenerator ids;
    private final Clock clock;
    private final PostingObserver observer;
    private final BalanceProjection<Connection> projection;

    public PostingService(
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
        // The write set is PostingEffect's (extracted by P3-TSK-016 when the reversal became
        // its second caller); the constructor keeps taking the stores so wiring stays honest.
        this.effect =
                new PostingEffect(journal, audit, outbox, projection, ids, clock);
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.projection = Objects.requireNonNull(projection, "projection must not be null");
    }

    /**
     * Takes the projection rows of {@code accounts} in the one order every entry takes them
     * (the Phase 7 -&gt; 8 transition, {@link BalanceProjection#lockInOrder}): for a caller about
     * to post several entries over the platform's shared rows in one transaction, called before
     * the first of them.
     */
    public void lockBalancesInOrder(
            Connection unitOfWork, java.util.Collection<LedgerAccountId> accounts) {
        projection.lockInOrder(unitOfWork, accounts);
    }

    /**
     * Posts the command's entry at most once for its key, or replays the recorded outcome.
     *
     * @throws com.finapp.platform.idempotency.IdempotencyConflictException the key was used
     *     for a materially different request ({@code INV-IDEM-03})
     * @throws com.finapp.platform.idempotency.IdempotencyInProgressException the command is
     *     running elsewhere and its outcome is genuinely unknown
     * @throws UnbalancedJournalEntryException before any claim: the key is not consumed
     */
    public PostingResult post(Connection unitOfWork, PostingCommand command) {
        // Observed whatever the outcome (P3-TSK-020): the elapsed time comes from the
        // injected clock, never nanoTime() - ambient time is a build failure, and the
        // P0-TSK-029 precedent already rejected the tempting exception.
        Instant started = clock.instant();
        try {
            PostingResult result = doPost(unitOfWork, command);
            observer.observe(
                    result.replayed()
                            ? PostingObserver.Outcome.REPLAYED
                            : PostingObserver.Outcome.POSTED,
                    Duration.between(started, clock.instant()));
            return result;
        } catch (RuntimeException refusal) {
            // A caller's refusal and an infrastructure failure alike: both are the
            // command not posting, which is what a failure rate is for.
            observer.observe(
                    PostingObserver.Outcome.REFUSED,
                    Duration.between(started, clock.instant()));
            if (refusal instanceof PeriodClosedException) {
                // P14-TSK-004: a back-dated posting into a locked period, counted apart.
                observer.refusedAtTheCutoff(PostingObserver.Command.POSTING);
            }
            throw refusal;
        }
    }

    private PostingResult doPost(Connection unitOfWork, PostingCommand command) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // Validation first: an unbalanced request never consumes its key.
        JournalEntry entry =
                JournalEntry.balanced(
                        ids, clock, command.postingDate(), command.valueDate(),
                        command.lines());

        // An unestablished actor is an error, never a default (ADR-0021): a posting nobody
        // can be asked about is exactly what INV-LED-05 forbids.
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
                        JournalEntryType.POSTING,
                        command.reference(),
                        Optional.empty(),
                        Optional.empty(),
                        actor.id(),
                        correlation,
                        scopeAndKey);

        boolean[] answeredByTheJournal = {false};
        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        fingerprint,
                        uow -> {
                            // The journal first (P16-TSK-011): the claim was fresh, which is a first attempt or a
                            // retry whose record has been swept. The second has an entry under this scope, and
                            // answers it - before any cutoff is read or any insert is tried, so a period closed
                            // since is no refusal of a posting that already happened.
                            Optional<JournalEntryId> recorded =
                                    journal.findByIdempotencyScope(uow, scopeAndKey);
                            if (recorded.isPresent()) {
                                answeredByTheJournal[0] = true;
                                return recorded(uow, recorded.get(), command, key);
                            }
                            try {
                                return recordDated(uow, command, entry, attribution, actor, correlation);
                            } catch (PostingScopeTakenException taken) {
                                // A writer that claimed nothing committed this scope while we posted: V037's
                                // index refused ours, wrote nothing and aborted nothing.
                                answeredByTheJournal[0] = true;
                                return recorded(
                                        uow,
                                        journal.findByIdempotencyScope(uow, scopeAndKey)
                                                .orElseThrow(() -> taken),
                                        command,
                                        key);
                            }
                        });

        JournalEntryId posted =
                JournalEntryId.of(
                        UUID.fromString(
                                new String(
                                        outcome.body()
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalStateException(
                                                                        "a recorded posting"
                                                                            + " outcome always"
                                                                            + " carries the"
                                                                            + " entry id")),
                                        StandardCharsets.UTF_8)));
        return new PostingResult(posted, outcome.replayed() || answeredByTheJournal[0]);
    }

    /**
     * The stored outcome of a posting the journal already holds under this command's scope (`P16-TSK-011`): the same bytes
     * the first execution recorded, its entry's identifier, when the entry is this request; {@code INV-IDEM-03}'s conflict
     * when it is not. With the idempotency record swept, the entry is the request's only witness, so it is compared field
     * by field with what the fingerprint covers: the kind, the reference, the value date, the lines in order, and the
     * posting date - equal, or later for a {@link PostingCommand.Dating#FLOOR_AT_FIRST_OPEN} request, which the cutoff may
     * have moved forward and never back. A floored entry retried {@code AS_STATED} is refused here, where its record would
     * have replayed it: a refusal, never a second entry. Nothing is written: no entry, audit, event or projection.
     */
    private CommandResult recorded(
            Connection unitOfWork, JournalEntryId recorded, PostingCommand command, IdempotencyKey key) {
        JournalEntryStore.PostedEntry posted =
                journal.findById(unitOfWork, recorded)
                        .orElseThrow(
                                () ->
                                        new LedgerStorageException(
                                                "journal entry " + recorded
                                                        + " was found by its scope and then not by its id"));
        LocalDate stated = command.postingDate();
        LocalDate booked = posted.entry().postingDate();
        boolean dated =
                booked.equals(stated)
                        || (command.dating() == PostingCommand.Dating.FLOOR_AT_FIRST_OPEN
                                && booked.isAfter(stated));
        boolean same =
                dated
                        && posted.attribution().entryType() == JournalEntryType.POSTING
                        && posted.attribution().reference().equals(command.reference())
                        && posted.entry().valueDate().equals(command.valueDate())
                        && posted.entry().lines().equals(command.lines());
        if (!same) {
            throw new IdempotencyConflictException(key);
        }
        return CommandResult.succeeded(
                StoredResponse.of(
                        recorded.value().toString().getBytes(StandardCharsets.UTF_8), "text/plain"));
    }

    /**
     * The effect, run at most once per key: the entry as stated, or - for a
     * {@link PostingCommand.Dating#FLOOR_AT_FIRST_OPEN} request (`P14-TSK-006`, owner decision C3) - posted on the
     * first open date when the cutoff covers its stated date, the value date, lines and key unchanged.
     *
     * <p><strong>Here, and not before the claim</strong>: the floor is read under the cutoff's shared key, held to
     * commit, immediately before the journal insert whose trigger re-takes the same key - so no cutoff move commits
     * between the read and the insert, and the lock order is the insert's own (after the caller's rows and the claim,
     * before the projection rows). A replay never reaches this method: it returns the first entry, whatever date the
     * first execution floored it to.
     */
    private CommandResult recordDated(
            Connection unitOfWork,
            PostingCommand command,
            JournalEntry entry,
            PostingAttribution attribution,
            Actor actor,
            Correlation correlation) {
        if (command.dating() != PostingCommand.Dating.FLOOR_AT_FIRST_OPEN) {
            return effect.record(unitOfWork, entry, attribution, actor, correlation);
        }
        LocalDate posting = PostingCutoffs.firstOpenOnOrAfter(unitOfWork, command.postingDate());
        if (posting.equals(command.postingDate())) {
            return effect.record(unitOfWork, entry, attribution, actor, correlation);
        }
        CommandResult recorded =
                effect.record(
                        unitOfWork,
                        entry.postedOn(posting),
                        attribution,
                        actor,
                        correlation,
                        // The stated date, named on the entry's own audit record: the floor is disclosed, never
                        // silent (C3). A date, never an amount (INV-AUD-02).
                        Optional.of("flooredFrom=" + command.postingDate()));
        observer.flooredAtTheCutoff(PostingObserver.Command.POSTING);
        return recorded;
    }

    /**
     * The flow's correlation, with the cause resolved: at a flow root the request is the cause
     * (`P1-TSK-006`'s answer, the {@code OrganisationRegistration} idiom), and the journal's
     * causation column is {@code NOT NULL}.
     */
    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a posting must run inside a correlation scope:"
                                                    + " the entry, the audit record and the"
                                                    + " event all carry the identifier"
                                                    + " (INV-LED-05)"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }

    /**
     * The fingerprint's canonical form: the money and its meaning, nothing volatile. A retry
     * that changed any line, date or the reference is a materially different request
     * ({@code INV-IDEM-03}); the actor and correlation are deliberately excluded, because a
     * retry arrives on a new request with a new correlation and must still replay.
     *
     * <p><strong>The stated date, and not the dating</strong> (`P14-TSK-006`): a
     * {@link PostingCommand.Dating#FLOOR_AT_FIRST_OPEN} command's form is its stated posting date's,
     * byte for byte the form of the same command {@code AS_STATED} - the date it was floored to is
     * a consequence of the cutoff at its first execution, not part of the request. So a replay after
     * the cutoff moved again converges on the first entry instead of conflicting, and an entry posted
     * before a poster asked for the floor is the same request as the floored one. Package-private for
     * {@code PostingFloorTest}.
     */
    static byte[] canonicalForm(PostingCommand command) {
        StringBuilder canonical =
                new StringBuilder("ledger.post|POSTING|")
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
