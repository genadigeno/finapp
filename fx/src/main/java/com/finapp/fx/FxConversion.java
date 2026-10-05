package com.finapp.fx;

import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * A wallet conversion, booked in ONE transaction - T-a (`P9-TSK-009`; PHASE_9_PLAN.md sections
 * 7 and 12.4; ADR-0076): the quote accepted on the database clock, the trade booked from its
 * frozen plan, the entry {@code fx-trade:<tradeId>} posted, the quote executed, the cover it wants
 * born DISPATCHED, the events and the audit record - all or nothing. The lock order is the plan's:
 * the quote, then the wallets sorted by id, then the projection rows (the posting, last).
 *
 * <p><strong>No provider is reachable from here</strong> ({@code INV-FX-09},
 * {@code NoProviderPortInConversionTest}): the customer's balances change at commit, against the
 * platform's own position, and never wait on a provider; the cover is sent later
 * (`P9-TSK-012`).
 *
 * <p><strong>Refusals roll back</strong> (they throw {@link ConversionRefusal.Refused}): the
 * quote stays ISSUED and reusable - insufficient funds included, and a destination wallet opened
 * by the attempt is undone with it. The one committed refusal is a quote found lapsed: the
 * acceptance performs the sweeper's own conditional expiry and writes its event
 * ({@code detectedBy ACCEPTANCE}), answered {@link Expired}.
 */
public final class FxConversion {

    public static final String ACCEPTED_EVENT = "fx.FxQuoteAccepted";
    public static final String EXECUTED_EVENT = "fx.FxTradeExecuted";
    public static final String TRADE_AGGREGATE = "fx_trade";
    public static final String POSTING_PREFIX = "fx-trade:";

    private final QuoteStore quotes;
    private final TradeStore trades;
    private final FxAvailability availability;
    private final ConversionParticipants participants;
    private final LedgerAccountStore<Connection> ledgerAccounts;
    private final AvailableBalance<Connection> balances;
    private final ChartOfAccounts<Connection> chart;
    private final PostingService postings;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public FxConversion(
            QuoteStore quotes,
            TradeStore trades,
            FxAvailability availability,
            ConversionParticipants participants,
            LedgerAccountStore<Connection> ledgerAccounts,
            AvailableBalance<Connection> balances,
            ChartOfAccounts<Connection> chart,
            PostingService postings,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock) {
        this.quotes = Objects.requireNonNull(quotes, "quotes must not be null");
        this.trades = Objects.requireNonNull(trades, "trades must not be null");
        this.availability = Objects.requireNonNull(availability, "availability must not be null");
        this.participants = Objects.requireNonNull(participants, "participants must not be null");
        this.ledgerAccounts = Objects.requireNonNull(ledgerAccounts, "ledgerAccounts must not be null");
        this.balances = Objects.requireNonNull(balances, "balances must not be null");
        this.chart = Objects.requireNonNull(chart, "chart must not be null");
        this.postings = Objects.requireNonNull(postings, "postings must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** What a conversion did: booked, or found its quote lapsed (committed). */
    public sealed interface Outcome permits Booked, Expired {}

    /** Booked: the trade, the cover it wants, and the plan's residual for the meters. */
    public record Booked(TradeStore.TradeRow trade, UUID coverId, String pair, long residualMinor) implements Outcome {}

    /** The quote was lapsed: its expiry and event are written; the conversion did not happen. */
    public record Expired(String pair) implements Outcome {}

    /**
     * Books the conversion of {@code quoteId} for its owner, in the caller's transaction.
     *
     * @throws ConversionRefusal.Refused for every refusal but a lapsed quote; the caller must roll
     *     back, and the quote stays ISSUED
     */
    public Outcome convert(Connection unitOfWork, FxQuoteId quoteId, UUID ownerParty, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(quoteId, "quoteId must not be null");
        Objects.requireNonNull(ownerParty, "ownerParty must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        UUID customer = participants.activeCustomer(unitOfWork, ownerParty)
                .orElseThrow(() -> new ConversionRefusal.Refused(ConversionRefusal.CUSTOMER_NOT_ELIGIBLE));

        // 1. The quote, under its row lock - the first rank of the lock order.
        QuoteStore.QuoteRow quote = quotes.lockOwned(unitOfWork, quoteId, ownerParty)
                .orElseThrow(() -> new ConversionRefusal.Refused(ConversionRefusal.QUOTE_NOT_FOUND));
        if (quote.purpose() != PricingPurpose.CONVERSION) {
            throw new ConversionRefusal.Refused(ConversionRefusal.QUOTE_KIND_MISMATCH);
        }
        QuoteStore.PlanRow plan = quotes.plan(unitOfWork, quoteId)
                .orElseThrow(() -> new IllegalStateException("a locked quote has its plan"));
        String pair = plan.source().code() + "-" + plan.destination().code();
        switch (quote.status()) {
            case ACCEPTED, EXECUTED -> throw new ConversionRefusal.Refused(ConversionRefusal.QUOTE_ALREADY_ACCEPTED);
            case CANCELLED, ABANDONED -> throw new ConversionRefusal.Refused(ConversionRefusal.QUOTE_NOT_ACCEPTABLE);
            case EXPIRED -> {
                // Lapsed on the database clock: expire it here unless the sweep already did.
                return expiredOnAcceptance(unitOfWork, plan, actor, pair);
            }
            case ISSUED -> { }
        }
        if (!availability.isAvailable(unitOfWork, AvailabilitySubject.pair(pair))) {
            throw new ConversionRefusal.Refused(ConversionRefusal.PAIR_SUSPENDED);
        }
        // 2. Accepted on the database clock - the conditional is the one judgement of validity.
        if (!quotes.accept(unitOfWork, quoteId)) {
            return expiredOnAcceptance(unitOfWork, plan, actor, pair);
        }

        // 3. The wallets: the source must exist; the destination opens if absent (D28), inside
        //    this transaction, so a rolled-back conversion leaves no wallet behind.
        LedgerAccountId source = participants.wallet(unitOfWork, customer, plan.source())
                .orElseThrow(() -> new ConversionRefusal.Refused(ConversionRefusal.SOURCE_WALLET_MISSING));
        LedgerAccountId destination = participants.openIfAbsent(unitOfWork, customer, plan.destination())
                .orElseThrow(() -> new ConversionRefusal.Refused(ConversionRefusal.SOURCE_WALLET_MISSING));
        lockWalletsInOrder(unitOfWork, source, destination);
        if (balances.underLock(unitOfWork, source).minus(plan.customerSource()).isNegative()) {
            throw new ConversionRefusal.Refused(ConversionRefusal.INSUFFICIENT_FUNDS);
        }

        // 4. The trade, its entry, the quote executed - the plan, posted exactly.
        FxTradeId tradeId = FxTradeId.next(ids);
        TradeStore.Booked booked = trades.insert(unitOfWork, tradeId, plan, correlation.value());
        ConversionLines.Accounts accounts = ConversionLines.accounts(chart, unitOfWork, plan, source, destination);
        String postingKey = POSTING_PREFIX + tradeId.value();
        PostingResult posted = postings.post(unitOfWork, new PostingCommand(
                postingKey, booked.bookedOn(), booked.bookedOn(), postingKey, ConversionLines.compose(plan, accounts)));
        trades.attachEntry(unitOfWork, tradeId, posted.entryId().value());
        if (!quotes.execute(unitOfWork, quoteId)) {
            throw new IllegalStateException("the accepted, locked quote did not execute beside its trade");
        }
        quotes.appendEvent(unitOfWork, quoteId, Optional.of(QuoteStatus.ISSUED), QuoteStatus.ACCEPTED,
                actor.id(), actor.type().name(), Optional.empty(), correlation.value());
        quotes.appendEvent(unitOfWork, quoteId, Optional.of(QuoteStatus.ACCEPTED), QuoteStatus.EXECUTED,
                actor.id(), actor.type().name(), Optional.empty(), correlation.value());

        // 5. The cover the position wants: born DISPATCHED, T1 stored before any send (ADR-0077),
        //    naming the acceptance its events are caused by (P9-TSK-012).
        EventId accepted = EventId.next(ids);
        UUID coverId = ids.next();
        trades.insertCover(unitOfWork, new TradeStore.CoverDraft(
                coverId, quoteId, CoverKind.COVER, plan.providerCode(), plan.source(), plan.destination(),
                plan.fixedSide(),
                plan.fixedSide() == FixedSide.FIXED_SOURCE ? plan.positionSource() : plan.positionDestination(),
                "T-" + ids.next().toString().replace("-", ""), plan.providerQuoteReference(), accepted.value(),
                correlation.value()));

        // 6. The facts: two events, one audit record.
        Instant now = Instant.now(clock);
        outbox.write(unitOfWork,
                new EventEnvelope(accepted, ACCEPTED_EVENT, QuoteIssuance.EVENT_VERSION, EventEnvelope.CURRENT_SCHEMA_VERSION,
                        quoteId, QuoteIssuance.AGGREGATE_TYPE, now, QuoteIssuance.PRODUCER, correlation,
                        CausationId.of(correlation.value())),
                EventPayload.of().with("subjectKind", "CONVERSION").with("subjectId", tradeId.value().toString()).toBytes(),
                EventPayload.MEDIA_TYPE);
        Money paid = plan.customerSource();
        Money received = plan.customerDestination();
        outbox.write(unitOfWork,
                new EventEnvelope(EventId.next(ids), EXECUTED_EVENT, QuoteIssuance.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION, tradeId, TRADE_AGGREGATE, now, QuoteIssuance.PRODUCER,
                        correlation, CausationId.of(accepted.value().toString())),
                EventPayload.of()
                        .with("purpose", plan.purpose().name())
                        .with("sourceMinor", Long.toString(paid.minorUnits()))
                        .with("sourceCurrency", paid.currency().code())
                        .with("sourceScale", Integer.toString(paid.scale()))
                        .with("destinationMinor", Long.toString(received.minorUnits()))
                        .with("destinationCurrency", received.currency().code())
                        .with("destinationScale", Integer.toString(received.scale()))
                        .with("postingReference", "fx-trade-" + tradeId.value())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        audit.append(unitOfWork, new AuditRecord(
                AuditId.next(ids), actor, now, FxAuditAction.FX_CONVERSION_EXECUTED, TRADE_AGGREGATE,
                tradeId.value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("quote=" + quoteId.value() + ", pair=" + pair)));
        TradeStore.TradeRow trade = trades.findOwned(unitOfWork, tradeId, ownerParty)
                .orElseThrow(() -> new IllegalStateException("a booked trade must read back"));
        return new Booked(trade, coverId, pair, plan.residualMinor());
    }

    /** The trade, only if {@code owner} owns it. */
    public Optional<TradeStore.TradeRow> trade(Connection unitOfWork, FxTradeId id, UUID owner) {
        return trades.findOwned(unitOfWork, id, owner);
    }

    // -----------------------------------------------------------------

    private Expired expiredOnAcceptance(Connection unitOfWork, QuoteStore.PlanRow plan, Actor actor, String pair) {
        if (quotes.expire(unitOfWork, plan.id())) {
            quotes.appendEvent(unitOfWork, plan.id(), Optional.of(QuoteStatus.ISSUED), QuoteStatus.EXPIRED, actor.id(),
                    actor.type().name(), Optional.of("ACCEPTANCE"), plan.correlationId());
            outbox.write(unitOfWork,
                    new EventEnvelope(EventId.next(ids), QuoteLifecycle.EXPIRED_EVENT, QuoteIssuance.EVENT_VERSION,
                            EventEnvelope.CURRENT_SCHEMA_VERSION, plan.id(), QuoteIssuance.AGGREGATE_TYPE,
                            Instant.now(clock), QuoteIssuance.PRODUCER, CorrelationId.of(plan.correlationId()),
                            CausationId.of(plan.issuedEventId().toString())),
                    EventPayload.of().with("detectedBy", "ACCEPTANCE").toBytes(),
                    EventPayload.MEDIA_TYPE);
        }
        return new Expired(pair);
    }

    /** The wallet rows FOR UPDATE, sorted by id - the plan's fifth rank - each open for postings. */
    private void lockWalletsInOrder(Connection unitOfWork, LedgerAccountId source, LedgerAccountId destination) {
        List<LedgerAccountId> ordered = Stream.of(source, destination)
                .sorted(Comparator.comparing(LedgerAccountId::value))
                .toList();
        for (LedgerAccountId id : ordered) {
            LedgerAccount locked = ledgerAccounts.lockForUpdate(unitOfWork, id)
                    .orElseThrow(() -> new IllegalStateException("a resolved wallet vanished"));
            if (locked.status() != LedgerAccountStatus.ACTIVE) {
                throw new ConversionRefusal.Refused(ConversionRefusal.WALLET_NOT_POSTABLE);
            }
        }
    }
}
