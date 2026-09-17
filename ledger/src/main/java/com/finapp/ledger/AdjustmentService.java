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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The adjustment command (`P3-TSK-017`, {@code INV-REV-04}): a person posts a balanced
 * entry the system would not have posted by itself — {@link PostingService}'s discipline,
 * with the reason regime the invariant demands.
 *
 * <p><strong>The permission is the boundary's, the reason is everyone's.</strong>
 * {@code LEDGER_ADJUST} gates the HTTP surface (ADR-0031; `P3-TSK-007`'s vocabulary meeting
 * its first real check site); this service takes the acting person from the established
 * context and refuses a reason-less command at construction, with `V004`'s implication
 * {@code CHECK} and {@code AuditRecord}'s own refusal beneath — three layers, one bound.
 *
 * <p><strong>The fingerprint binds the actor</strong> (ADR-0004's owning principal, the
 * {@code AccountService} precedent): an idempotency key is not a secret, and a second
 * operator replaying a key they saw in a log must get a conflict — never a replay of
 * somebody else's adjustment. The same operator's retry replays the original outcome.
 *
 * <p><strong>Four-eyes is recorded debt, not implied</strong> ({@code INV-AUD-04},
 * ADR-0010): no threshold check exists here, deliberately.
 */
public final class AdjustmentService {

    /** The idempotency scope (ADR-0004): one command type, one scope. */
    public static final String IDEMPOTENCY_SCOPE = "ledger.adjust";

    /** {@code journal_entry.idempotency_scope} is CHECK-bounded; the pair must fit it. */
    private static final int MAX_SCOPE_AND_KEY_LENGTH = 200;

    private final IdempotentExecutor executor;
    private final PostingEffect effect;
    private final IdGenerator ids;
    private final Clock clock;

    public AdjustmentService(
            IdempotentExecutor executor,
            JournalEntryStore<Connection> journal,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            BalanceProjection<Connection> projection,
            IdGenerator ids,
            Clock clock) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.effect = new PostingEffect(journal, audit, outbox, projection, ids, clock);
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Posts the adjustment at most once for its key, or replays the outcome.
     *
     * <p>Validate, then claim, then effect: an unbalanced or otherwise refused request never
     * consumes its key, so the operator fixes the request and retries under the same key.
     *
     * @throws UnbalancedJournalEntryException before any claim ({@code INV-LED-01})
     * @throws UnknownPostingAccountException a line names an unknown account or a foreign
     *     currency — the store's translation of the schema's own refusal
     * @throws LedgerAccountNotPostableException an account stopped accepting postings
     */
    public PostingResult adjust(Connection unitOfWork, AdjustmentCommand command) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // Validation first: an unbalanced request never consumes its key.
        JournalEntry entry =
                JournalEntry.balanced(
                        ids, clock, command.postingDate(), command.valueDate(),
                        command.lines());

        // An unestablished actor is an error, never a default (ADR-0021) - and for an
        // adjustment the actor IS the authorising person INV-REV-04 demands.
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
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(canonicalForm(command, actor));
        PostingAttribution attribution =
                new PostingAttribution(
                        JournalEntryType.ADJUSTMENT,
                        command.reference(),
                        Optional.of(command.reason()),
                        Optional.empty(),
                        actor.id(),
                        correlation,
                        scopeAndKey);

        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        fingerprint,
                        uow -> effect.record(uow, entry, attribution, actor, correlation));

        JournalEntryId adjusted =
                JournalEntryId.of(
                        UUID.fromString(
                                new String(
                                        outcome.body()
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalStateException(
                                                                        "a recorded adjustment"
                                                                            + " outcome always"
                                                                            + " carries the"
                                                                            + " entry id")),
                                        StandardCharsets.UTF_8)));
        return new PostingResult(adjusted, outcome.replayed());
    }

    /** The flow's correlation with the cause resolved — the {@code PostingService} idiom. */
    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an adjustment must run inside a correlation"
                                                        + " scope: the journal's causation"
                                                        + " column is NOT NULL"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }

    /**
     * The canonical form the fingerprint hashes. <strong>Includes the actor</strong> — see
     * the class note — and the reason, because a key reused with a different justification
     * is a materially different request ({@code INV-IDEM-03}): the reason is what makes an
     * adjustment defensible, so two requests differing only there must never silently
     * collapse into one record.
     */
    private static byte[] canonicalForm(AdjustmentCommand command, Actor actor) {
        StringBuilder canonical =
                new StringBuilder("ledger.adjust|ADJUSTMENT|")
                        .append(actor.id())
                        .append('|')
                        .append(command.postingDate())
                        .append('|')
                        .append(command.valueDate())
                        .append('|')
                        .append(command.reference())
                        .append('|')
                        .append(command.reason())
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
