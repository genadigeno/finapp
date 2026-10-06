package com.finapp.app.fx;

import com.finapp.fx.FxErrorCode;
import com.finapp.fx.FxTradeId;
import com.finapp.fx.TradeReversals;
import com.finapp.fx.TransactionRunner;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the operator's FX trade reversal (`P9-TSK-025`, PHASE_9_PLAN.md section 9): the proposal keyed
 * per principal, the approval and the rejection each one transaction - the domain's refusals in the API's words.
 */
@RequiredArgsConstructor
public final class FxTradeReversalDesk {

    static final String SCOPE = "fx.trade-reversal:";
    public static final String REVERSED_METER = "finapp.fx.trade.reversed";

    @NonNull private final TradeReversals reversals;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final MeterRegistry meters;

    /** A reversal as the operator sees it. */
    public record ReversalReceipt(String reversalId, String tradeId, String status, String reversalEntryId, String cover) {}

    public ReversalReceipt propose(String rawTradeId, String idempotencyKey, String reason) {
        FxTradeId tradeId = FxTradeId.of(id(rawTradeId, FxErrorCode.TRADE_NOT_FOUND));
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
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

    public ReversalReceipt approve(String rawReversalId, String reason) {
        UUID reversalId = id(rawReversalId, FxErrorCode.NOT_FOUND);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        TradeReversals.Decided approved = guarded(() -> transactions.inTransaction(unitOfWork ->
                reversals.approve(unitOfWork, reversalId, actor, reason, correlation)));
        // Telemetry, never the count of record: the trade's REVERSED status is.
        meters.counter(REVERSED_METER).increment();
        return receipt(approved);
    }

    public ReversalReceipt reject(String rawReversalId, String reason) {
        UUID reversalId = id(rawReversalId, FxErrorCode.NOT_FOUND);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return receipt(guarded(() -> transactions.inTransaction(unitOfWork ->
                reversals.reject(unitOfWork, reversalId, actor, reason, correlation))));
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
