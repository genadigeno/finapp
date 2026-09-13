package com.finapp.ledger;

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
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The posting command: one command, one financial effect, whatever the caller does
 * (`P3-TSK-006`, {@code INV-IDEM-01}) — and the one write path {@code INV-LED-04} permits.
 *
 * <h2>Everything commits together, on the caller's transaction</h2>
 *
 * <p>The entry, its lines, the audit record, the outbox row and the idempotency record all go
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
 * <h2>What is deliberately absent</h2>
 *
 * <p><strong>Account status is not checked here, and that is a recorded remainder with an
 * owner.</strong> Every production-reachable account is {@code ACTIVE} — no store writes a
 * status yet — and *posting to a closed account refused under the account lock* is
 * `P3-TSK-014`'s own design, because the refusal is only real inside the lock that closing
 * takes; a lock-free status read here would be the check that passes every test and loses the
 * race. Line-currency-versus-account-currency needs no check at all: {@code V005} binds it at
 * {@code DB-CONSTRAINT} for every writer. The adjustment variant, its permission and its
 * reason are `P3-TSK-017`'s; the projection update joins this transaction at `P3-TSK-009`.
 */
public final class PostingService {

    /** The idempotency scope (ADR-0004): one command type, one scope. */
    public static final String IDEMPOTENCY_SCOPE = "ledger.post";

    /** One fact, named once, in two registries — the audit action carries the same code. */
    static final String EVENT_TYPE = "ledger.JournalEntryPosted";

    static final String PRODUCER = "ledger";
    static final int EVENT_VERSION = 1;
    static final String TARGET_TYPE = "journal_entry";

    /** {@code journal_entry.idempotency_scope} is CHECK-bounded; the pair must fit it. */
    private static final int MAX_SCOPE_AND_KEY_LENGTH = 200;

    private final IdempotentExecutor executor;
    private final JournalEntryStore<Connection> journal;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public PostingService(
            IdempotentExecutor executor,
            JournalEntryStore<Connection> journal,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
                        actor.id(),
                        correlation,
                        scopeAndKey);

        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        fingerprint,
                        uow -> postOnce(uow, entry, attribution, actor, correlation));

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
        return new PostingResult(posted, outcome.replayed());
    }

    /** The effect, run at most once per key; everything on the one unit of work. */
    private CommandResult postOnce(
            Connection unitOfWork,
            JournalEntry entry,
            PostingAttribution attribution,
            Actor actor,
            Correlation correlation) {
        journal.append(unitOfWork, entry, attribution);

        Instant now = Instant.now(clock);
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        LedgerAuditAction.JOURNAL_ENTRY_POSTED,
                        TARGET_TYPE,
                        entry.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and counts - never an amount (INV-AUD-02).
                        Optional.of(
                                "entry=" + entry.id() + ", type="
                                        + attribution.entryType() + ", lines="
                                        + entry.lines().size())));

        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        entry.id(),
                        TARGET_TYPE,
                        now,
                        PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                // Enumerated names only, never an amount: a consumer needing the amount reads
                // the posting (INV-AUD-02, plan section 10).
                EventPayload.of().with("entryType", attribution.entryType().name()).toBytes(),
                EventPayload.MEDIA_TYPE);

        return CommandResult.succeeded(
                StoredResponse.of(
                        entry.id().value().toString().getBytes(StandardCharsets.UTF_8),
                        "text/plain"));
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
     */
    private static byte[] canonicalForm(PostingCommand command) {
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
