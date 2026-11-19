package com.finapp.app.fx;

import com.finapp.fx.FxAuditAction;
import com.finapp.fx.FxErrorCode;
import com.finapp.fx.FxTradeId;
import com.finapp.fx.TradeReversalStore;
import com.finapp.fx.TradeReversals;
import com.finapp.fx.TradeStore;
import com.finapp.fx.TransactionRunner;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the operator's FX trade reversal (`P9-TSK-025`, PHASE_9_PLAN.md section 9): the proposal keyed
 * per principal, the approval and the rejection each one transaction - the domain's refusals in the API's words. Since
 * `P17-TSK-118` a reversal is also read by its approver, with the conversion it would reverse, every serving recorded
 * ({@code fx.TradeReversalRead}) in the read's own transaction.
 */
@RequiredArgsConstructor
public final class FxTradeReversalDesk {

    static final String SCOPE = "fx.trade-reversal:";

    @NonNull private final TradeReversals reversals;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final FxQuoteMetrics metrics;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** A reversal as the operator sees it. */
    public record ReversalReceipt(String reversalId, String tradeId, String status, String reversalEntryId, String cover) {}

    /**
     * The conversion a reversal would undo: what its approver must see to judge the mirror - its purpose and status, the
     * side the customer fixed, both customer amounts, when it was booked and the entry the mirror reverses. Never the
     * customer's party, never a rate.
     */
    public record ReversedTradeView(
            String tradeId, String purpose, String status, String fixedSide, FxProvenanceDesk.AmountView customerSource,
            FxProvenanceDesk.AmountView customerDestination, String bookedAt, String journalEntryId) {}

    /**
     * A reversal as its approver reads it (`P17-TSK-118`): its position, who proposed it, when and why - the decision's
     * instant and reason and the mirror entry once decided ({@code null} until then) - and the trade. Never who decided.
     * Named for its module: the contract keys schemas by simple name, and lending's {@code ReversalView} is a loan's.
     */
    public record FxTradeReversalView(
            String reversalId, String tradeId, String status, String proposedBy, String proposedAt, String reason,
            String decidedAt, String decisionReason, String reversalEntryId, ReversedTradeView trade) {}

    /**
     * Reversal {@code rawReversalId} OF trade {@code rawTradeId}, with the trade, recorded in the read's own transaction:
     * a read of a customer's conversion amounts. Another trade's reversal, an unknown or malformed identifier: 404, and
     * nothing recorded.
     */
    public FxTradeReversalView read(String rawTradeId, String rawReversalId) {
        FxTradeId tradeId = FxTradeId.of(id(rawTradeId, FxErrorCode.NOT_FOUND));
        UUID reversalId = id(rawReversalId, FxErrorCode.NOT_FOUND);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return guarded(() -> transactions.inTransaction(unitOfWork -> {
            TradeReversals.Read read = reversals.read(unitOfWork, tradeId, reversalId);
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock),
                    FxAuditAction.TRADE_REVERSAL_READ, "FxTradeReversal", reversalId.toString(), Optional.empty(),
                    AuditOutcome.SUCCEEDED, correlation,
                    Optional.of("trade=" + tradeId.value() + ", status=" + read.reversal().row().status())));
            return view(read);
        }));
    }

    public ReversalReceipt propose(String rawTradeId, String idempotencyKey, String reason) {
        FxTradeId tradeId = FxTradeId.of(id(rawTradeId, FxErrorCode.TRADE_NOT_FOUND));
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(
                ("fx.trade-reversal|" + tradeId.value() + "|" + reason).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> transactions.inTransaction(unitOfWork ->
                executor.execute(unitOfWork, key, fingerprint, uow -> {
                    TradeReversals.Decided proposed = reversals.propose(uow, tradeId, actor, reason, correlation);
                    return CommandResult.succeeded(StoredResponse.of(
                            (proposed.reversalId() + "|" + proposed.tradeId().value()).getBytes(StandardCharsets.UTF_8),
                            "text/plain"));
                })));
        String[] stored = new String(outcome.body().orElseThrow(() -> new IllegalStateException("a proposal stores its receipt")),
                StandardCharsets.UTF_8).split("\\|", -1);
        return new ReversalReceipt(stored[0], stored[1], "PROPOSED", null, null);
    }

    /**
     * Approves reversal {@code rawReversalId} OF trade {@code rawTradeId} - the route names both, and a reversal of
     * another trade is 404 with nothing written (the Phase 9 to 10 transition).
     */
    public ReversalReceipt approve(String rawTradeId, String rawReversalId, String reason) {
        FxTradeId tradeId = FxTradeId.of(id(rawTradeId, FxErrorCode.NOT_FOUND));
        UUID reversalId = id(rawReversalId, FxErrorCode.NOT_FOUND);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        TradeReversals.Decided approved = guarded(() -> transactions.inTransaction(unitOfWork ->
                reversals.approve(unitOfWork, tradeId, reversalId, actor, reason, correlation)));
        // Telemetry, never the count of record: the trade's REVERSED status is. After its transaction committed.
        metrics.reversed(approved.pair());
        return receipt(approved);
    }

    /** Rejects reversal {@code rawReversalId} OF trade {@code rawTradeId}; another trade's reversal is 404. */
    public ReversalReceipt reject(String rawTradeId, String rawReversalId, String reason) {
        FxTradeId tradeId = FxTradeId.of(id(rawTradeId, FxErrorCode.NOT_FOUND));
        UUID reversalId = id(rawReversalId, FxErrorCode.NOT_FOUND);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return receipt(guarded(() -> transactions.inTransaction(unitOfWork ->
                reversals.reject(unitOfWork, tradeId, reversalId, actor, reason, correlation))));
    }

    private static FxTradeReversalView view(TradeReversals.Read read) {
        TradeReversalStore.Detail reversal = read.reversal();
        TradeStore.TradeRow trade = read.trade();
        return new FxTradeReversalView(
                reversal.row().id().toString(), reversal.row().tradeId().value().toString(), reversal.row().status(),
                reversal.row().proposedBy(), reversal.proposedAt().toString(), reversal.proposedReason(),
                reversal.decidedAt().map(Instant::toString).orElse(null), reversal.decidedReason().orElse(null),
                reversal.row().reversalEntryId().map(UUID::toString).orElse(null),
                new ReversedTradeView(
                        trade.id().value().toString(), trade.purpose().name(), trade.status().name(),
                        trade.fixedSide().name(), FxProvenanceDesk.AmountView.of(trade.customerSource()),
                        FxProvenanceDesk.AmountView.of(trade.customerDestination()),
                        trade.bookedAt().toString(), trade.journalEntryId() == null ? null : trade.journalEntryId().toString()));
    }

    private static ReversalReceipt receipt(TradeReversals.Decided decided) {
        return new ReversalReceipt(decided.reversalId().toString(), decided.tradeId().value().toString(), decided.status(),
                decided.reversalEntryId().map(UUID::toString).orElse(null),
                decided.status().equals("APPROVED") ? decided.cover().name() : null);
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (TradeReversals.NotFound unknown) {
            throw new ApiException(FxErrorCode.NOT_FOUND, "No FX record matches the requested identifier", "no such record.");
        } catch (TradeReversals.SelfApprovalRefused self) {
            throw refused(FxErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (TradeReversals.ProposalNotPending decided) {
            throw refused(FxErrorCode.PROPOSAL_NOT_PENDING, decided);
        } catch (TradeReversals.ProposalPending pending) {
            throw refused(FxErrorCode.PROPOSAL_PENDING, pending);
        } catch (TradeReversals.NotReversible notReversible) {
            throw refused(FxErrorCode.TRADE_NOT_REVERSIBLE, notReversible);
        } catch (TradeReversals.ReasonInvalid invalid) {
            // "The proposal or the decision is malformed" - the reason screen's refusal, as every FX act's (422).
            throw refused(FxErrorCode.PRICING_POLICY_INVALID, invalid);
        }
    }

    private static ApiException refused(FxErrorCode code, RuntimeException cause) {
        return new ApiException(code, "An FX trade reversal was refused", cause.getMessage());
    }

    private static UUID id(String raw, FxErrorCode absent) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(absent, "No FX record matches the requested identifier", "no such record.");
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an FX trade reversal runs inside a correlation scope"))
                .correlationId();
    }
}
