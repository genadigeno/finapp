package com.finapp.fx;

import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.ReversalCommand;
import com.finapp.ledger.ReversalService;
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
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The operator's FX trade reversal (`P9-TSK-025`, the lifecycle document section 3.3; {@code INV-REV-01},
 * {@code INV-REV-02}, {@code INV-AUD-04}): an erroneous wallet conversion corrected by compensation alone, by two
 * persons. A proposal is reasoned; a different person approves it - and the approval's transaction executes it: the
 * exact mirror of the conversion's entry through the ledger's {@link ReversalService} (its {@code V009} bound refuses
 * a second), the trade {@code BOOKED -> REVERSED}, and the cover's consequence by the wanted-position rule (an
 * executed cover unwound, a rejected one voided) - or a different person rejects it.
 *
 * <p>The approval takes the conversion's own lock order - the quote, the trade, then the reversal, then the wallets
 * sorted - so ten approvers serialise on the quote: one approves, every other finds the proposal decided. The
 * destination wallet's available balance is judged under its lock: the mirror takes back what the conversion gave.
 */
@RequiredArgsConstructor
public final class TradeReversals {

    public static final String REVERSED_EVENT = "fx.FxTradeReversed";

    @NonNull private final TradeReversalStore reversals;
    @NonNull private final TradeStore trades;
    @NonNull private final QuoteStore quotes;
    @NonNull private final ConversionParticipants participants;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final AvailableBalance<Connection> balances;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final ReversalService ledgerReversals;
    @NonNull private final CoverUnwinds unwinds;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final com.finapp.sharedkernel.id.IdGenerator ids;
    @NonNull private final Clock clock;

    /** A reversal as decided or proposed. */
    public record Decided(UUID reversalId, FxTradeId tradeId, String status, Optional<UUID> reversalEntryId,
            CoverUnwinds.Effect cover) {}

    /** No trade (or reversal) has this identifier. Nothing written. */
    public static final class NotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public NotFound(String detail) {
            super(detail);
        }
    }

    /** The trade cannot be reversed - see {@link FxErrorCode#TRADE_NOT_REVERSIBLE}. Nothing written. */
    public static final class NotReversible extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public NotReversible(String detail) {
            super(detail);
        }
    }

    /** The decider proposed it: four eyes is two persons. Nothing written. */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public SelfApprovalRefused() {
            super("a trade reversal is decided by someone other than its proposer");
        }
    }

    /** The reversal was already decided. Nothing written. */
    public static final class ProposalNotPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ProposalNotPending(String status) {
            super("the trade reversal is " + status);
        }
    }

    /** The trade already carries a live proposal. Nothing written. */
    public static final class ProposalPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ProposalPending() {
            super("the trade already carries a reversal awaiting a decision");
        }
    }

    /** Proposes reversing {@code tradeId}, reasoned, in the caller's unit of work. */
    public Decided propose(Connection unitOfWork, FxTradeId tradeId, Actor actor, String reason, CorrelationId correlation) {
        requireReason(reason);
        TradeStore.TradeRow seen = trades.find(unitOfWork, tradeId).orElseThrow(() -> new NotFound("no such trade"));
        // The lock order's first two ranks: the quote, then the trade.
        quotes.lockOwned(unitOfWork, seen.quoteId(), seen.owner())
                .orElseThrow(() -> new IllegalStateException("a trade's quote always exists"));
        TradeStore.TradeRow trade = trades.lock(unitOfWork, tradeId).orElseThrow(() -> new NotFound("no such trade"));
        requireReversible(trade);
        UUID id = ids.next();
        if (!reversals.propose(unitOfWork, id, tradeId, actor.id(), reason, correlation.value())) {
            throw new ProposalPending();
        }
        reversals.appendEvent(unitOfWork, ids.next(), id, Optional.empty(), "PROPOSED", actor.id(), reason);
        audit(unitOfWork, actor, FxAuditAction.FX_TRADE_REVERSAL_PROPOSED, id, reason, correlation,
                "trade=" + tradeId.value());
        return new Decided(id, tradeId, "PROPOSED", Optional.empty(), CoverUnwinds.Effect.NONE);
    }

    /**
     * Approves reversal {@code reversalId} - a different person's reasoned act - and executes it in the caller's unit
     * of work: the mirror entry, the trade {@code REVERSED}, the cover's consequence.
     */
    public Decided approve(Connection unitOfWork, UUID reversalId, Actor actor, String reason, CorrelationId correlation) {
        requireReason(reason);
        TradeReversalStore.Row seen = reversals.find(unitOfWork, reversalId)
                .orElseThrow(() -> new NotFound("no such trade reversal"));
        TradeStore.TradeRow unlocked = trades.find(unitOfWork, seen.tradeId())
                .orElseThrow(() -> new IllegalStateException("a reversal's trade always exists"));
        // The lock order: the quote, the trade, the reversal - then the wallets, sorted.
        quotes.lockOwned(unitOfWork, unlocked.quoteId(), unlocked.owner())
                .orElseThrow(() -> new IllegalStateException("a trade's quote always exists"));
        TradeStore.TradeRow trade = trades.lock(unitOfWork, seen.tradeId())
                .orElseThrow(() -> new IllegalStateException("a reversal's trade always exists"));
        TradeReversalStore.Row reversal = reversals.lock(unitOfWork, reversalId)
                .orElseThrow(() -> new NotFound("no such trade reversal"));
        if (!reversal.status().equals("PROPOSED")) {
            throw new ProposalNotPending(reversal.status());
        }
        if (reversal.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused();
        }
        requireReversible(trade);

        QuoteStore.PlanRow plan = quotes.plan(unitOfWork, trade.quoteId())
                .orElseThrow(() -> new IllegalStateException("a locked quote has its plan"));
        UUID customer = participants.activeCustomer(unitOfWork, trade.owner())
                .orElseThrow(() -> new NotReversible("the conversion's customer is no longer active"));
        LedgerAccountId source = participants.wallet(unitOfWork, customer, plan.source())
                .orElseThrow(() -> new NotReversible("the conversion's source wallet is gone"));
        LedgerAccountId destination = participants.wallet(unitOfWork, customer, plan.destination())
                .orElseThrow(() -> new NotReversible("the conversion's destination wallet is gone"));
        lockWalletsInOrder(unitOfWork, source, destination);
        if (balances.underLock(unitOfWork, destination).minus(plan.customerDestination()).isNegative()) {
            throw new NotReversible("the destination wallet's available balance is short of what the mirror takes back");
        }

        // The exact mirror: the conversion's own lines, recomposed from its frozen plan, each direction swapped.
        List<JournalLine> mirror = ConversionLines.compose(plan, ConversionLines.accounts(chart, unitOfWork, plan, source,
                        destination)).stream()
                .map(line -> new JournalLine(line.account(),
                        line.direction() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT, line.amount()))
                .toList();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        PostingResult posted = ledgerReversals.reverse(unitOfWork, new ReversalCommand(
                FxConversion.POSTING_PREFIX + trade.id().value(), JournalEntryId.of(trade.journalEntryId()), today, today,
                "fx-trade-" + trade.id().value(), mirror));
        if (!trades.reverse(unitOfWork, trade.id())) {
            throw new IllegalStateException("the locked, booked trade did not reverse");
        }
        if (!reversals.decide(unitOfWork, reversalId, "APPROVED", actor.id(), reason, Optional.of(posted.entryId().value()))) {
            throw new IllegalStateException("the locked, proposed reversal did not approve");
        }
        reversals.appendEvent(unitOfWork, ids.next(), reversalId, Optional.of("PROPOSED"), "APPROVED", actor.id(), reason);
        // The cover's consequence, the quote's lock held: the trade REVERSED, the quote no longer wants its cover.
        CoverUnwinds.Effect effect = unwinds.evaluate(unitOfWork, trade.quoteId(), actor);
        Instant now = Instant.now(clock);
        // Identifiers only - never an amount or a name (INV-AUD-02).
        outbox.write(unitOfWork,
                new EventEnvelope(EventId.next(ids), REVERSED_EVENT, QuoteIssuance.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION, trade.id(), FxConversion.TRADE_AGGREGATE, now,
                        QuoteIssuance.PRODUCER, correlation, CausationId.of(correlation.value())),
                EventPayload.of()
                        .with("reversal", reversalId.toString())
                        .with("reversalEntry", posted.entryId().value().toString())
                        .with("cover", effect.name())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        audit(unitOfWork, actor, FxAuditAction.FX_TRADE_REVERSAL_APPROVED, reversalId, reason, correlation,
                "trade=" + trade.id().value() + ", cover=" + effect);
        return new Decided(reversalId, trade.id(), "APPROVED", Optional.of(posted.entryId().value()), effect);
    }

    /** Rejects reversal {@code reversalId} - a different person's reasoned act; nothing moves. */
    public Decided reject(Connection unitOfWork, UUID reversalId, Actor actor, String reason, CorrelationId correlation) {
        requireReason(reason);
        TradeReversalStore.Row reversal = reversals.lock(unitOfWork, reversalId)
                .orElseThrow(() -> new NotFound("no such trade reversal"));
        if (!reversal.status().equals("PROPOSED")) {
            throw new ProposalNotPending(reversal.status());
        }
        if (reversal.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused();
        }
        if (!reversals.decide(unitOfWork, reversalId, "REJECTED", actor.id(), reason, Optional.empty())) {
            throw new IllegalStateException("the locked, proposed reversal did not reject");
        }
        reversals.appendEvent(unitOfWork, ids.next(), reversalId, Optional.of("PROPOSED"), "REJECTED", actor.id(), reason);
        audit(unitOfWork, actor, FxAuditAction.FX_TRADE_REVERSAL_REJECTED, reversalId, reason, correlation,
                "trade=" + reversal.tradeId().value());
        return new Decided(reversalId, reversal.tradeId(), "REJECTED", Optional.empty(), CoverUnwinds.Effect.NONE);
    }

    private static void requireReversible(TradeStore.TradeRow trade) {
        if (trade.purpose() != PricingPurpose.CONVERSION) {
            // A cross-border trade is never reversed: its customer effect is the corridor's (INV-REV-03).
            throw new NotReversible("only a wallet conversion is reversed");
        }
        if (trade.status() != TradeStatus.BOOKED) {
            throw new NotReversible("the trade is " + trade.status());
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("every act on a trade reversal carries a reason");
        }
    }

    private void lockWalletsInOrder(Connection unitOfWork, LedgerAccountId source, LedgerAccountId destination) {
        for (LedgerAccountId id : Stream.of(source, destination).sorted(Comparator.comparing(LedgerAccountId::value)).toList()) {
            LedgerAccount locked = ledgerAccounts.lockForUpdate(unitOfWork, id)
                    .orElseThrow(() -> new IllegalStateException("a resolved wallet vanished"));
            if (locked.status() != LedgerAccountStatus.ACTIVE) {
                throw new NotReversible("a wallet the mirror posts to is not open");
            }
        }
    }

    private void audit(Connection unitOfWork, Actor actor, FxAuditAction action, UUID reversalId, String reason,
            CorrelationId correlation, String summary) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock), action, "FxTradeReversal",
                reversalId.toString(), Optional.of(reason), AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }
}
