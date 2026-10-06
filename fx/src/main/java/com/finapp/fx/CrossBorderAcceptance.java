package com.finapp.fx;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A cross-border quote accepted inside the payment's authorization (`P9-TSK-019`, PHASE_9_PLAN.md section
 * 12.8; ADR-0077, {@code INV-FX-04}): the quote locked and judged - the owner's, {@code CROSS_BORDER},
 * {@code ISSUED} - moved {@code ISSUED -> ACCEPTED} on the database clock, and the cover the position will
 * want born {@code DISPATCHED} with its {@code T1} stored before any send. No claim of its own: the caller's
 * claim covers the transaction. <strong>No trade and no posting</strong> - a cross-border payment's
 * customer effect is a hold until the corridor provider accepts; the trade is booked at that completion
 * (`P9-TSK-020`). Every refusal throws and the caller's transaction rolls back - a lapsed quote included,
 * which the expiry sweep closes.
 */
@RequiredArgsConstructor
public final class CrossBorderAcceptance {

    @NonNull private final QuoteStore quotes;
    @NonNull private final TradeStore trades;
    @NonNull private final FxAvailability availability;
    @NonNull private final ConversionParticipants participants;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The accepted quote: its cover, what the customer pays and receives, and the wallet the hold sits on. */
    public record Accepted(UUID coverId, Money customerPays, Money customerReceives, LedgerAccountId sourceWallet) {
        public Accepted {
            Objects.requireNonNull(coverId, "coverId must not be null");
            Objects.requireNonNull(customerPays, "customerPays must not be null");
            Objects.requireNonNull(customerReceives, "customerReceives must not be null");
            Objects.requireNonNull(sourceWallet, "sourceWallet must not be null");
        }
    }

    /**
     * Accepts {@code quoteId} for {@code ownerParty}'s payment {@code paymentId}, in the caller's unit of work.
     *
     * @throws ConversionRefusal.Refused {@code CUSTOMER_NOT_ELIGIBLE}, {@code QUOTE_NOT_FOUND},
     *     {@code QUOTE_KIND_MISMATCH} (a wallet conversion's quote), {@code QUOTE_ALREADY_ACCEPTED},
     *     {@code QUOTE_NOT_ACCEPTABLE}, {@code QUOTE_EXPIRED}, {@code PAIR_SUSPENDED},
     *     {@code SOURCE_WALLET_MISSING}
     */
    public Accepted acceptWithin(
            Connection unitOfWork, FxQuoteId quoteId, UUID ownerParty, UUID paymentId, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(quoteId, "quoteId must not be null");
        Objects.requireNonNull(ownerParty, "ownerParty must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        UUID customer = participants.activeCustomer(unitOfWork, ownerParty)
                .orElseThrow(() -> new ConversionRefusal.Refused(ConversionRefusal.CUSTOMER_NOT_ELIGIBLE));
        QuoteStore.QuoteRow quote = quotes.lockOwned(unitOfWork, quoteId, ownerParty)
                .orElseThrow(() -> new ConversionRefusal.Refused(ConversionRefusal.QUOTE_NOT_FOUND));
        if (quote.purpose() != PricingPurpose.CROSS_BORDER) {
            throw new ConversionRefusal.Refused(ConversionRefusal.QUOTE_KIND_MISMATCH);
        }
        switch (quote.status()) {
            case ACCEPTED, EXECUTED -> throw new ConversionRefusal.Refused(ConversionRefusal.QUOTE_ALREADY_ACCEPTED);
            case CANCELLED, ABANDONED -> throw new ConversionRefusal.Refused(ConversionRefusal.QUOTE_NOT_ACCEPTABLE);
            case EXPIRED -> throw new ConversionRefusal.Refused(ConversionRefusal.QUOTE_EXPIRED);
            case ISSUED -> { }
        }
        QuoteStore.PlanRow plan = quotes.plan(unitOfWork, quoteId)
                .orElseThrow(() -> new IllegalStateException("a locked quote has its plan"));
        String pair = plan.source().code() + "-" + plan.destination().code();
        if (!availability.isAvailable(unitOfWork, AvailabilitySubject.pair(pair))) {
            throw new ConversionRefusal.Refused(ConversionRefusal.PAIR_SUSPENDED);
        }
        LedgerAccountId wallet = participants.wallet(unitOfWork, customer, plan.source())
                .orElseThrow(() -> new ConversionRefusal.Refused(ConversionRefusal.SOURCE_WALLET_MISSING));
        // Accepted on the database clock - the conditional is the one judgement of validity.
        Optional<Instant> acceptedAt = quotes.accept(unitOfWork, quoteId);
        if (acceptedAt.isEmpty()) {
            throw new ConversionRefusal.Refused(ConversionRefusal.QUOTE_EXPIRED);
        }
        quotes.appendEventAt(unitOfWork, quoteId, Optional.of(QuoteStatus.ISSUED), QuoteStatus.ACCEPTED,
                actor.id(), actor.type().name(), Optional.empty(), correlation.value(), acceptedAt.get());
        EventId accepted = EventId.next(ids);
        UUID coverId = ids.next();
        trades.insertCover(unitOfWork, new TradeStore.CoverDraft(
                coverId, quoteId, CoverKind.COVER, plan.providerCode(), plan.source(), plan.destination(), plan.fixedSide(),
                plan.fixedSide() == FixedSide.FIXED_SOURCE ? plan.positionSource() : plan.positionDestination(),
                "T-" + ids.next().toString().replace("-", ""), plan.providerQuoteReference(), accepted.value(),
                correlation.value()));
        outbox.write(unitOfWork,
                new EventEnvelope(accepted, FxConversion.ACCEPTED_EVENT, QuoteIssuance.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION, quoteId, QuoteIssuance.AGGREGATE_TYPE, Instant.now(clock),
                        QuoteIssuance.PRODUCER, correlation, CausationId.of(correlation.value())),
                EventPayload.of().with("subjectKind", "CROSS_BORDER_PAYMENT").with("subjectId", paymentId.toString()).toBytes(),
                EventPayload.MEDIA_TYPE);
        return new Accepted(coverId, plan.customerSource(), plan.customerDestination(), wallet);
    }
}
