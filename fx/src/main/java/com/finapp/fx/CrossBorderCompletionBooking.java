package com.finapp.fx;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JournalLine;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * fx's half of a cross-border outbound credit's outcome (`P9-TSK-020`, PHASE_9_PLAN.md sections 12.4(g) and
 * (h), the lifecycle document 3.3): at the corridor provider's acceptance the frozen plan's lines for the
 * completion entry, then - in the same transaction, after payments posted that entry - the trade booked onto
 * it and the quote {@code ACCEPTED -> EXECUTED}; at a failure the quote {@code ACCEPTED -> ABANDONED}. Every
 * call runs on the outcome applier's connection and takes no claim of its own: the applier's acting
 * conditional and claim are the arbiters, and {@code UNIQUE (fx.trade.quote_id)} with the quote's single edge
 * beneath them.
 */
@RequiredArgsConstructor
public final class CrossBorderCompletionBooking {

    public static final String ABANDONED_EVENT = "fx.FxQuoteAbandoned";

    @NonNull private final QuoteStore quotes;
    @NonNull private final TradeStore trades;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * The completion entry's lines: the quote's frozen plan, its destination credited to {@code clearing}, and the
     * corridor fee charged from {@code wallet} into {@code FEE_REVENUE} - composed by {@link ConversionLines}, the
     * one place the FX books are named.
     */
    public List<JournalLine> lines(
            Connection unitOfWork, FxQuoteId quoteId, LedgerAccountId wallet, LedgerAccountId clearing, Money fee) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        QuoteStore.PlanRow plan = crossBorderPlan(unitOfWork, quoteId);
        ConversionLines.Accounts accounts = ConversionLines.accounts(chart, unitOfWork, plan, wallet, clearing);
        LedgerAccountId feeRevenue = chart.resolve(unitOfWork, AccountPurpose.FEE_REVENUE, fee.currency()).id();
        return ConversionLines.composeCrossBorder(plan, accounts, feeRevenue, fee);
    }

    /**
     * The trade booked onto {@code journalEntryId} - the completion entry payments just posted - and the quote
     * {@code ACCEPTED -> EXECUTED}, with {@code fx.FxTradeExecuted} naming the entry by {@code postingReference}
     * (an identifier, as the conversion's {@code fx-trade-<id>}).
     */
    public FxTradeId book(
            Connection unitOfWork, FxQuoteId quoteId, UUID journalEntryId, String postingReference, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
        Objects.requireNonNull(postingReference, "postingReference must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        QuoteStore.PlanRow plan = crossBorderPlan(unitOfWork, quoteId);
        QuoteStore.QuoteRow quote = quotes.lockOwned(unitOfWork, quoteId, plan.owner())
                .orElseThrow(() -> new IllegalStateException("a completing quote always reads back"));
        if (quote.status() != QuoteStatus.ACCEPTED) {
            throw new IllegalStateException("a completing cross-border quote is " + quote.status() + ", not ACCEPTED");
        }
        FxTradeId tradeId = FxTradeId.next(ids);
        trades.insert(unitOfWork, tradeId, plan, correlation.value());
        trades.attachEntry(unitOfWork, tradeId, journalEntryId);
        if (!quotes.execute(unitOfWork, quoteId)) {
            throw new IllegalStateException("the accepted, locked quote did not execute beside its trade");
        }
        quotes.appendEvent(unitOfWork, quoteId, Optional.of(QuoteStatus.ACCEPTED), QuoteStatus.EXECUTED,
                actor.id(), actor.type().name(), Optional.empty(), correlation.value());
        Money paid = plan.customerSource();
        Money received = plan.customerDestination();
        outbox.write(unitOfWork,
                new EventEnvelope(EventId.next(ids), FxConversion.EXECUTED_EVENT, QuoteIssuance.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION, tradeId, FxConversion.TRADE_AGGREGATE, Instant.now(clock),
                        QuoteIssuance.PRODUCER, correlation, CausationId.of(correlation.value())),
                EventPayload.of()
                        .with("purpose", plan.purpose().name())
                        .with("sourceMinor", Long.toString(paid.minorUnits()))
                        .with("sourceCurrency", paid.currency().code())
                        .with("sourceScale", Integer.toString(paid.scale()))
                        .with("destinationMinor", Long.toString(received.minorUnits()))
                        .with("destinationCurrency", received.currency().code())
                        .with("destinationScale", Integer.toString(received.scale()))
                        .with("postingReference", postingReference)
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return tradeId;
    }

    /**
     * The quote {@code ACCEPTED -> ABANDONED} - its subject failed before booking - with {@code fx.FxQuoteAbandoned}
     * naming why. False when the quote was not {@code ACCEPTED} (already abandoned: a converging duplicate).
     */
    public boolean abandon(Connection unitOfWork, FxQuoteId quoteId, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        QuoteStore.PlanRow plan = crossBorderPlan(unitOfWork, quoteId);
        quotes.lockOwned(unitOfWork, quoteId, plan.owner())
                .orElseThrow(() -> new IllegalStateException("an abandoning quote always reads back"));
        if (!quotes.abandon(unitOfWork, quoteId)) {
            return false;
        }
        quotes.appendEvent(unitOfWork, quoteId, Optional.of(QuoteStatus.ACCEPTED), QuoteStatus.ABANDONED,
                actor.id(), actor.type().name(), Optional.empty(), correlation.value());
        outbox.write(unitOfWork,
                new EventEnvelope(EventId.next(ids), ABANDONED_EVENT, QuoteIssuance.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION, quoteId, QuoteIssuance.AGGREGATE_TYPE, Instant.now(clock),
                        QuoteIssuance.PRODUCER, correlation, CausationId.of(correlation.value())),
                EventPayload.of().with("reason", reason).toBytes(),
                EventPayload.MEDIA_TYPE);
        return true;
    }

    private QuoteStore.PlanRow crossBorderPlan(Connection unitOfWork, FxQuoteId quoteId) {
        Objects.requireNonNull(quoteId, "quoteId must not be null");
        QuoteStore.PlanRow plan = quotes.plan(unitOfWork, quoteId)
                .orElseThrow(() -> new IllegalStateException("a cross-border outcome's quote always has its plan"));
        if (plan.purpose() != PricingPurpose.CROSS_BORDER) {
            throw new IllegalStateException("an outbound credit's quote is a " + plan.purpose() + " quote");
        }
        return plan;
    }
}
