package com.finapp.fx;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An issued quote's life after birth (`P9-TSK-008`; the lifecycle document section 3.1): its
 * owner's read - {@code EXPIRED} once lapsed on the database clock, before the sweeper writes it -
 * the owner's cancellation, and the sweeper's expiry page. Each edge is the conditional on the
 * locked row that {@code fx V005}'s edge trigger also holds, so cancellation and expiry are
 * complementary: exactly one of them can move a quote, and its event is written once.
 */
public final class QuoteLifecycle {

    public static final String EXPIRED_EVENT = "fx.FxQuoteExpired";
    public static final String CANCELLED_EVENT = "fx.FxQuoteCancelled";

    private final QuoteStore quotes;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;

    public QuoteLifecycle(QuoteStore quotes, AuditWriter<Connection> audit, OutboxWriter<Connection> outbox, IdGenerator ids) {
        this.quotes = Objects.requireNonNull(quotes, "quotes must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    /** No quote of the caller's has this id: absent and another's are one answer. */
    public static final class QuoteNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public QuoteNotFound() {
            super("no such quote");
        }
    }

    /** The quote is not live: already closed, or lapsed. */
    public static final class QuoteNotCancellable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        QuoteNotCancellable(QuoteStatus status) {
            super("a quote in " + status + " cannot be cancelled");
        }
    }

    /** The owner's read: the quote, only if {@code owner} owns it. */
    public Optional<QuoteStore.QuoteRow> read(Connection unitOfWork, FxQuoteId id, UUID owner) {
        return quotes.findOwned(unitOfWork, id, owner);
    }

    /**
     * Cancels a live quote: under its row lock, {@code ISSUED -> CANCELLED} while
     * {@code expires_at > statement_timestamp()}, with its history, audit record and event.
     *
     * @throws QuoteNotFound when the caller does not own a quote with this id
     * @throws QuoteNotCancellable when it is closed or lapsed
     */
    public QuoteStore.QuoteRow cancel(Connection unitOfWork, FxQuoteId id, UUID owner, Actor actor, Instant now, CorrelationId correlation) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        QuoteStore.QuoteRow locked = quotes.lockOwned(unitOfWork, id, owner).orElseThrow(QuoteNotFound::new);
        if (locked.status() != QuoteStatus.ISSUED || !quotes.cancel(unitOfWork, id)) {
            throw new QuoteNotCancellable(locked.status());
        }
        quotes.appendEvent(unitOfWork, id, Optional.of(QuoteStatus.ISSUED), QuoteStatus.CANCELLED, actor.id(),
                actor.type().name(), Optional.empty(), correlation.value());
        audit.append(unitOfWork, new AuditRecord(
                AuditId.next(ids), actor, now, FxAuditAction.QUOTE_CANCELLED, QuoteIssuance.AGGREGATE_TYPE,
                id.value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("cancelled=" + id.value())));
        outbox.write(
                unitOfWork,
                new EventEnvelope(EventId.next(ids), CANCELLED_EVENT, QuoteIssuance.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION, id, QuoteIssuance.AGGREGATE_TYPE, now,
                        QuoteIssuance.PRODUCER, correlation, CausationId.of(correlation.value())),
                EventPayload.of().with("reason", "CUSTOMER").toBytes(),
                EventPayload.MEDIA_TYPE);
        return quotes.findOwned(unitOfWork, id, owner)
                .orElseThrow(() -> new IllegalStateException("a cancelled quote must read back"));
    }

    /**
     * Expires up to {@code limit} lapsed quotes in the caller's transaction - the conditional
     * judged on {@code statement_timestamp()}, rows another sweeper holds skipped - writing one
     * {@code fx.FxQuoteExpired} ({@code detectedBy SWEEP}) per moved row, under the quote's own
     * correlation and caused by its issue. {@code actor} is the platform's, from the caller's
     * established scope ({@code SecurityContext.enterSystem()}).
     */
    public List<QuoteStore.ExpiredRow> expirePage(Connection unitOfWork, int limit, Instant now, Actor actor) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        List<QuoteStore.ExpiredRow> expired = quotes.expirePage(unitOfWork, limit);
        for (QuoteStore.ExpiredRow row : expired) {
            quotes.appendEvent(unitOfWork, row.id(), Optional.of(QuoteStatus.ISSUED), QuoteStatus.EXPIRED,
                    actor.id(), actor.type().name(), Optional.of("SWEEP"), row.correlationId());
            outbox.write(
                    unitOfWork,
                    new EventEnvelope(EventId.next(ids), EXPIRED_EVENT, QuoteIssuance.EVENT_VERSION,
                            EventEnvelope.CURRENT_SCHEMA_VERSION, row.id(), QuoteIssuance.AGGREGATE_TYPE, now,
                            QuoteIssuance.PRODUCER, CorrelationId.of(row.correlationId()),
                            CausationId.of(row.issuedEventId().toString())),
                    EventPayload.of().with("detectedBy", "SWEEP").toBytes(),
                    EventPayload.MEDIA_TYPE);
        }
        return expired;
    }
}
