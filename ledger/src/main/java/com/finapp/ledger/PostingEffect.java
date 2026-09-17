package com.finapp.ledger;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
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

/**
 * The one write set a journal entry commits with: entry and lines, the audit record, the
 * outbox row, and the projection — together or not at all (`P3-TSK-006`, extracted by
 * `P3-TSK-016` when the reversal became its second caller, the {@code CheckOutcomeTrail}
 * rule: a write set copied per command is one that drifts in exactly one of its copies).
 *
 * <p><strong>One fact, one vocabulary</strong>: whatever kind of entry this records — a
 * posting, a reversal, an adjustment — the act is <em>a journal entry was posted</em>, so
 * the audit action and the event type stay {@code ledger.JournalEntryPosted} and the kind
 * travels as data ({@code entryType} in the payload and the change summary). A separate
 * reversal vocabulary would name the same fact twice; the reversal's own distinguishing
 * fact — which original it compensates — is the entry row's {@code reverses_entry_id}
 * ({@code INV-REV-01}), where an investigator joins it.
 *
 * <p>Package-private deliberately: the callers are the ledger's own commands
 * ({@link PostingService}, {@link ReversalService}, and `P3-TSK-017`'s adjustment), which is
 * the one write path {@code INV-LED-04} permits.
 */
final class PostingEffect {

    /** One fact, named once, in two registries — the audit action carries the same code. */
    static final String EVENT_TYPE = "ledger.JournalEntryPosted";

    static final String PRODUCER = "ledger";
    static final int EVENT_VERSION = 1;
    static final String TARGET_TYPE = "journal_entry";

    private final JournalEntryStore<Connection> journal;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final BalanceProjection<Connection> projection;
    private final IdGenerator ids;
    private final Clock clock;

    PostingEffect(
            JournalEntryStore<Connection> journal,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            BalanceProjection<Connection> projection,
            IdGenerator ids,
            Clock clock) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.projection = Objects.requireNonNull(projection, "projection must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** The effect, run at most once per key by the caller's executor; one unit of work. */
    CommandResult record(
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
                                        + entry.lines().size()
                                        + attribution
                                                .reverses()
                                                .map(original -> ", reverses=" + original)
                                                .orElse(""))));

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

        // The projection, LAST (P3-TSK-009, ADR-0041): its UPDATE takes the account's
        // projection row lock and holds it to commit - the contended lock ADR-0041 accepts -
        // so it is taken after every other write, keeping the window other postings to the
        // same account block as short as this transaction allows. A replay never re-enters
        // this method, so a replay never double-applies.
        projection.apply(unitOfWork, entry);

        return CommandResult.succeeded(
                StoredResponse.of(
                        entry.id().value().toString().getBytes(StandardCharsets.UTF_8),
                        "text/plain"));
    }
}
