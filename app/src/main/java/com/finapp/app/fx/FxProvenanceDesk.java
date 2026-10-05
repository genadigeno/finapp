package com.finapp.app.fx;

import com.finapp.fx.FxAuditAction;
import com.finapp.fx.FxErrorCode;
import com.finapp.fx.FxProvenanceStore;
import com.finapp.fx.FxTradeId;
import com.finapp.fx.TransactionRunner;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The investigator's FX door (`P9-TSK-013`; PHASE_9_PLAN.md sections 11 and 12.3): an FX trade's
 * provenance - every link of its rate chain, the entry, the cover and its execution - read in one
 * transaction with its audit record ({@code fx.TradeProvenanceRead}): a read of a customer's
 * trade is recorded at every serving. Read-only; amounts as decimal strings with their currency.
 */
public final class FxProvenanceDesk {

    /** One amount: the decimal string at its currency's scale, and the currency. */
    public record AmountView(String amount, String currency) {
        static AmountView of(Money money) {
            return new AmountView(money.toBigDecimal().toPlainString(), money.currency().code());
        }
    }

    /** The cover's execution. */
    public record ExecutionView(
            String providerTradeReference,
            AmountView sold,
            AmountView bought,
            String executedRate,
            String valueDate,
            AmountView realisedSold,
            AmountView realisedBought,
            boolean executedOffPlan,
            String journalEntryId) {}

    /** The cover. */
    public record CoverView(String id, String status, int attempts, ExecutionView execution) {}

    /** The rate chain - reference, provider, internal, customer - and every amount of the plan. */
    public record ProvenanceView(
            String tradeId,
            String quoteId,
            String purpose,
            String status,
            String fixedSide,
            String pricingPolicyVersionId,
            String referenceSnapshotId,
            String referenceRate,
            String providerCode,
            String providerQuoteReference,
            String providerRate,
            String providerValueDate,
            String internalRate,
            String customerRate,
            String disclosedMargin,
            AmountView customerSource,
            AmountView customerDestination,
            AmountView positionSource,
            AmountView positionDestination,
            AmountView margin,
            AmountView spreadMargin,
            AmountView markupMargin,
            AmountView residual,
            String bookedAt,
            String journalEntryId,
            CoverView cover) {}

    private final FxProvenanceStore store;
    private final AuditWriter<Connection> audit;
    private final TransactionRunner transactions;
    private final IdGenerator ids;
    private final Clock clock;

    public FxProvenanceDesk(
            FxProvenanceStore store, AuditWriter<Connection> audit, TransactionRunner transactions, IdGenerator ids,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** The trade's provenance, audited; 404 when no trade matches. */
    public ProvenanceView provenance(String id) {
        FxTradeId tradeId = parsed(id).orElseThrow(FxProvenanceDesk::notFound);
        Actor actor = SecurityContext.require();
        return transactions.inTransaction(unitOfWork -> {
            FxProvenanceStore.Provenance found = store.provenance(unitOfWork, tradeId).orElseThrow(FxProvenanceDesk::notFound);
            audit.append(unitOfWork, new AuditRecord(
                    AuditId.next(ids), actor, Instant.now(clock), FxAuditAction.TRADE_PROVENANCE_READ, "fx_trade",
                    tradeId.value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED,
                    CorrelationContext.current()
                            .orElseThrow(() -> new IllegalStateException("an FX read runs inside a correlation scope"))
                            .correlationId(),
                    Optional.of("quote=" + found.quoteId().value())));
            return view(found);
        });
    }

    private static ProvenanceView view(FxProvenanceStore.Provenance p) {
        CoverView cover = p.cover().map(c -> new CoverView(c.id().toString(), c.status().name(), c.attempts(),
                c.execution().map(e -> new ExecutionView(e.providerTradeReference(), AmountView.of(e.sold()),
                        AmountView.of(e.bought()), plain(e.executedRate()), e.valueDate().toString(),
                        AmountView.of(e.realisedSold()), AmountView.of(e.realisedBought()), e.executedOffPlan(),
                        e.journalEntryId() == null ? null : e.journalEntryId().toString())).orElse(null))).orElse(null);
        return new ProvenanceView(
                p.tradeId().value().toString(), p.quoteId().value().toString(), p.purpose().name(), p.status().name(),
                p.fixedSide().name(), p.policyVersion().value().toString(), p.referenceSnapshotId().toString(),
                plain(p.referenceRate()), p.providerCode(), p.providerQuoteReference(), plain(p.providerRate()),
                p.providerValueDate().toString(), plain(p.internalRate()), plain(p.customerRate()),
                plain(p.disclosedMargin()), AmountView.of(p.customerSource()), AmountView.of(p.customerDestination()),
                AmountView.of(p.positionSource()), AmountView.of(p.positionDestination()), AmountView.of(p.margin()),
                AmountView.of(p.spreadMargin()), AmountView.of(p.markupMargin()), AmountView.of(p.residual()),
                p.bookedAt().toString(), p.journalEntryId() == null ? null : p.journalEntryId().toString(), cover);
    }

    private static String plain(BigDecimal value) {
        return value.toPlainString();
    }

    private static Optional<FxTradeId> parsed(String id) {
        try {
            return Optional.of(FxTradeId.of(UUID.fromString(id)));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    private static ApiException notFound() {
        return new ApiException(FxErrorCode.TRADE_NOT_FOUND, "No FX trade matches the requested identifier",
                "no such trade.");
    }
}
