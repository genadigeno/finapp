package com.finapp.fx;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The wanted-position rule (`P9-TSK-021`, ADR-0077 sections 7 and 8, PHASE_9_PLAN.md section 12.5): a quote wants
 * its cover iff it is {@code ACCEPTED} or {@code EXECUTED} and its trade is not {@code REVERSED}. When it no
 * longer does, an {@code EXECUTED} cover is unwound - its mirror created, buying back exactly what it sold from
 * the same provider - a {@code REJECTED} one is voided, and a {@code DISPATCHED} or {@code UNKNOWN} one waits for
 * knowledge: its applier evaluates the rule again when it executes.
 *
 * <p>A rule over state, not a command: the abandonment or reversal writer and the cover's applier both evaluate
 * it under the lock order quote -> trade -> cover, whichever moves first, and {@code UNIQUE (quote_id, kind)} -
 * no pre-check, the insert's conflict - makes exactly one unwind however many evaluate.
 */
@RequiredArgsConstructor
public final class CoverUnwinds {

    /** What an evaluation did. */
    public enum Effect {
        /** The quote still wants its cover, or has none, or it is already concluded: nothing to do. */
        NONE,
        /** The executed cover's unwind was created by this call. */
        UNWOUND,
        /** The rejected cover was voided by this call. */
        VOIDED,
        /** The cover is in flight: its applier evaluates the rule when it knows. */
        WAITING
    }

    @NonNull private final CoverStore covers;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * The writer's evaluation, the caller holding {@code quoteId}'s row lock: the quote's and its trade's statuses
     * re-read {@code FOR SHARE}, then its cover locked, then the rule applied.
     */
    public Effect evaluate(Connection unitOfWork, FxQuoteId quoteId, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(quoteId, "quoteId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        CoverStore.Wanted wanted = covers.lockWanted(unitOfWork, quoteId);
        if (wanted.wanted()) {
            return Effect.NONE;
        }
        Optional<CoverStore.CoverRow> cover = covers.lockByQuote(unitOfWork, quoteId, CoverKind.COVER);
        if (cover.isEmpty()) {
            return Effect.NONE;
        }
        CoverStore.CoverRow locked = cover.get();
        return switch (locked.status()) {
            case EXECUTED -> unwind(unitOfWork, locked, actor) ? Effect.UNWOUND : Effect.NONE;
            case REJECTED -> void_(unitOfWork, locked, wanted, actor) ? Effect.VOIDED : Effect.NONE;
            case DISPATCHED, UNKNOWN -> Effect.WAITING;
            case VOIDED -> Effect.NONE;
        };
    }

    /**
     * The unwind of {@code executed} - a {@code COVER} that executed for a quote no longer wanting it - created in the
     * caller's transaction, the caller holding the lock order. False when it already exists (the unique's conflict).
     */
    public boolean unwind(Connection unitOfWork, CoverStore.CoverRow executed, Actor actor) {
        Objects.requireNonNull(executed, "executed must not be null");
        if (executed.kind() != CoverKind.COVER || executed.status() != CoverStatus.EXECUTED) {
            throw new IllegalArgumentException("only an executed cover is unwound");
        }
        UUID unwind = ids.next();
        boolean created = covers.insertUnwind(unitOfWork, new CoverStore.UnwindDraft(
                unwind, executed.quoteId(), executed.providerCode(), executed.destination(), executed.source(),
                executed.fixedSide() == FixedSide.FIXED_SOURCE ? FixedSide.FIXED_DESTINATION : FixedSide.FIXED_SOURCE,
                executed.fixedAmount(), executed.causedByEventId(), executed.correlationId()));
        if (created) {
            audit.append(unitOfWork, new AuditRecord(
                    AuditId.next(ids), actor, Instant.now(clock), FxAuditAction.COVER_UNWOUND, FxCoverOutcomes.AGGREGATE_TYPE,
                    unwind.toString(), Optional.empty(), AuditOutcome.SUCCEEDED, CorrelationId.of(executed.correlationId()),
                    Optional.of("quote=" + executed.quoteId().value() + ", cover=" + executed.id()
                            + ", provider=" + executed.providerCode())));
        }
        return created;
    }

    private boolean void_(Connection unitOfWork, CoverStore.CoverRow locked, CoverStore.Wanted wanted, Actor actor) {
        if (!covers.transition(unitOfWork, locked.id(), locked.attempts(), CoverStatus.REJECTED, CoverStatus.VOIDED)) {
            return false;
        }
        audit.append(unitOfWork, new AuditRecord(
                AuditId.next(ids), actor, Instant.now(clock), FxAuditAction.COVER_VOIDED, FxCoverOutcomes.AGGREGATE_TYPE,
                locked.id().toString(), Optional.empty(), AuditOutcome.SUCCEEDED, CorrelationId.of(locked.correlationId()),
                Optional.of("quote=" + locked.quoteId().value() + ", quote " + wanted.quoteStatus()
                        + wanted.tradeStatus().map(status -> ", trade " + status).orElse(""))));
        return true;
    }
}
