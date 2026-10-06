package com.finapp.fx;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The cover's rows (`P9-TSK-012`; ADR-0077, {@code fx V006}/{@code V007}): the permit, the locks,
 * the edges and the execution fact. Every arbiter is the database's - the forward-only permit
 * stamped by {@code statement_timestamp()}, the row lock and the conditional edge, the attempt and
 * reference uniques, the execution fact's PK - so this port takes no clock.
 */
public interface CoverStore {

    /** A cover as stored. */
    record CoverRow(
            UUID id,
            FxQuoteId quoteId,
            CoverKind kind,
            CoverStatus status,
            String providerCode,
            CurrencyCode source,
            CurrencyCode destination,
            FixedSide fixedSide,
            Money fixedAmount,
            int attempts,
            Instant lastDispatchedAt,
            int requoteFailures,
            UUID causedByEventId,
            Instant createdAt,
            String correlationId) {

        public CoverRow {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(quoteId, "quoteId must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(providerCode, "providerCode must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(fixedSide, "fixedSide must not be null");
            Objects.requireNonNull(fixedAmount, "fixedAmount must not be null");
            Objects.requireNonNull(lastDispatchedAt, "lastDispatchedAt must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(correlationId, "correlationId must not be null");
        }
    }

    /** One attempt: our reference {@code T} and the provider quote it executes. */
    record AttemptRow(UUID coverId, int attempt, String clientReference, String providerQuoteReference) {}

    /** The cover's execution fact, as the acting applier inserts it. */
    record ExecutionDraft(
            UUID coverId,
            int attempt,
            String clientReference,
            String providerCode,
            String providerTradeReference,
            FixedSide fixedSide,
            Money sold,
            Money bought,
            ExchangeRate executedRate,
            LocalDate valueDate,
            CoverLines.Plan plan,
            String correlationId) {

        public ExecutionDraft {
            Objects.requireNonNull(coverId, "coverId must not be null");
            Objects.requireNonNull(clientReference, "clientReference must not be null");
            Objects.requireNonNull(providerCode, "providerCode must not be null");
            Objects.requireNonNull(providerTradeReference, "providerTradeReference must not be null");
            Objects.requireNonNull(fixedSide, "fixedSide must not be null");
            Objects.requireNonNull(sold, "sold must not be null");
            Objects.requireNonNull(bought, "bought must not be null");
            Objects.requireNonNull(executedRate, "executedRate must not be null");
            Objects.requireNonNull(valueDate, "valueDate must not be null");
            Objects.requireNonNull(plan, "plan must not be null");
            Objects.requireNonNull(correlationId, "correlationId must not be null");
        }
    }

    /** What the database stamped on the execution fact. */
    record Recorded(Instant recordedAt, LocalDate recordedOn) {}

    /** Whether the cover's quote still wants it (ADR-0077 section 7), read under the lock order. */
    record Wanted(QuoteStatus quoteStatus, Optional<TradeStatus> tradeStatus) {

        /** {@code ACCEPTED} or {@code EXECUTED}, and its trade - if any - not {@code REVERSED}. */
        public boolean wanted() {
            return (quoteStatus == QuoteStatus.ACCEPTED || quoteStatus == QuoteStatus.EXECUTED)
                    && tradeStatus.filter(status -> status == TradeStatus.REVERSED).isEmpty();
        }
    }

    /**
     * Renews the permit of every due cover this call can take - in ONE statement, rows another
     * sweeper holds skipped - and returns them as renewed. Due: {@code DISPATCHED} or
     * {@code UNKNOWN} whose permit is older than {@code resendAfter}; {@code REJECTED} whose permit
     * is older than {@code requoteBase} doubled per refused requote (at most {@code 2^6}). Judged on
     * {@code statement_timestamp()}, re-evaluated on the locked row.
     */
    List<CoverRow> claimDue(Connection unitOfWork, Duration resendAfter, Duration requoteBase, int limit);

    /** Renews one non-terminal cover's permit now (the nudge, a targeted advance), if it is still open. */
    Optional<CoverRow> claim(Connection unitOfWork, UUID coverId);

    /** The cover, unlocked. */
    Optional<CoverRow> find(Connection unitOfWork, UUID coverId);

    /** The cover {@code FOR UPDATE} - the subject rank's last row (quote, trade, cover). */
    Optional<CoverRow> lock(Connection unitOfWork, UUID coverId);

    /** The quote's and its trade's statuses {@code FOR SHARE}, in the lock order (quote, then trade). */
    Wanted lockWanted(Connection unitOfWork, FxQuoteId quoteId);

    /** One attempt of a cover. */
    Optional<AttemptRow> attempt(Connection unitOfWork, UUID coverId, int attempt);

    /**
     * The quote's cover of {@code kind} {@code FOR UPDATE} - the subject rank's last row, after the quote and its
     * trade (`P9-TSK-021`, the abandonment writer's evaluation of the wanted position).
     */
    Optional<CoverRow> lockByQuote(Connection unitOfWork, FxQuoteId quoteId, CoverKind kind);

    /** The quote's cover of {@code kind}, unlocked. */
    Optional<CoverRow> findByQuote(Connection unitOfWork, FxQuoteId quoteId, CoverKind kind);

    /** An executed cover's mirror, its quote no longer wanting the cover (`P9-TSK-021`, ADR-0077 section 8). */
    record UnwindDraft(
            UUID id,
            FxQuoteId quoteId,
            String providerCode,
            CurrencyCode source,
            CurrencyCode destination,
            FixedSide fixedSide,
            Money fixedAmount,
            UUID causedByEventId,
            String correlationId) {
        public UnwindDraft {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(quoteId, "quoteId must not be null");
            Objects.requireNonNull(providerCode, "providerCode must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(fixedSide, "fixedSide must not be null");
            Objects.requireNonNull(fixedAmount, "fixedAmount must not be null");
            Objects.requireNonNull(causedByEventId, "causedByEventId must not be null");
            Objects.requireNonNull(correlationId, "correlationId must not be null");
        }
    }

    /**
     * Inserts the unwind, born {@code DISPATCHED} at attempt 1 with no attempt row yet - its first dispatch prices it.
     * {@code UNIQUE (quote_id, kind)} is the arbiter: false when the quote's unwind already exists.
     */
    boolean insertUnwind(Connection unitOfWork, UnwindDraft draft);

    /**
     * An unwind's attempt 1 - our reference and the fresh firm quote it executes - stored before its first send;
     * false when another instance stored it first ({@code (cover_id, attempt)} the arbiter).
     */
    boolean insertFirstAttempt(Connection unitOfWork, UUID coverId, String clientReference, String providerQuoteReference);

    /** The attempt that minted {@code clientReference}, whichever cover and attempt it is. */
    Optional<AttemptRow> attemptByReference(Connection unitOfWork, String clientReference);

    /** The conditional edge {@code from -> to}; {@code false} when the row was elsewhere. */
    boolean transition(Connection unitOfWork, UUID coverId, int attempt, CoverStatus from, CoverStatus to);

    /**
     * The requote: attempt n+1's row with {@code T(n+1)} stored first, then the conditional
     * {@code REJECTED -> DISPATCHED} with the permit renewed and the failures reset. {@code false}
     * when another applier already advanced the cover (the attempt unique, then the conditional).
     */
    boolean requote(Connection unitOfWork, UUID coverId, int fromAttempt, String clientReference, String providerQuoteReference);

    /** One more refused requote of a still-{@code REJECTED} cover - the backoff. */
    boolean recordRequoteFailure(Connection unitOfWork, UUID coverId, int attempt);

    /** Inserts the execution fact; the database stamps and checks it. */
    Recorded insertExecution(Connection unitOfWork, ExecutionDraft draft);

    /** Attaches the cover entry to the execution fact, once. */
    void attachExecutionEntry(Connection unitOfWork, UUID coverId, UUID journalEntryId);

    /** {@code UNKNOWN} covers now, and the oldest one's age since its latest permit (the INV-LIFE-03 gauge). */
    record UnknownBoard(long active, Optional<Duration> oldestAge) {}

    UnknownBoard unknownBoard(Connection unitOfWork);

    /** The oldest non-terminal cover's age since its birth - the uncovered position's age. */
    Optional<Duration> oldestOpenAge(Connection unitOfWork);
}
