package com.finapp.app.risk;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.MonitoringReprocessing;
import com.finapp.risk.MonitoringWatermark;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The risk officer's monitoring door (`P13-TSK-019`; {@code PHASE_13_PLAN.md} sections 9, 11, 12.7; bnpl's
 * {@code BnplTermsDesk} shape): a reprocessing of the intake ordered from an earlier watermark - keyed per principal
 * ({@code risk.reprocess:<type>:<id>}), one transaction, the stored receipt replayed on the same key; a refusal stores
 * nothing. The domain's refusals in the API's words: {@code 422 risk.ReasonRequired}, {@code 422 risk.ReprocessingNotBehind},
 * {@code 404 risk.NotFound}.
 */
public final class RiskMonitoringDesk {

    static final String SCOPE = "risk.reprocess:";

    private final MonitoringReprocessing reprocessing;
    private final IdempotentExecutor executor;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;

    public RiskMonitoringDesk(MonitoringReprocessing reprocessing, IdempotentExecutor executor,
            RiskTransactions transactions, RiskFlowScope flow) {
        this.reprocessing = Objects.requireNonNull(reprocessing, "reprocessing");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    /** The receipt: the source, the sequence restarted from and where the watermark stood. Never the reason. */
    public record ReprocessingReceipt(String source, long fromSequence, long previousSequence) {}

    /** Orders the re-run of the ledger journal's intake from {@code fromSequence} (a millisecond of the journal's ids). */
    public ReprocessingReceipt reprocess(String idempotencyKey, long fromSequence, String reason) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(
                ("reprocess|" + fromSequence + "|" + reason).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> flow.door(RiskSpans.MONITORING_REPROCESS,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    MonitoringReprocessing.Ordered ordered = reprocessing.order(uow, MonitoringWatermark.LEDGER_JOURNAL,
                            fromSequence, actor, reason, correlation);
                    String stored = ordered.source() + "|" + ordered.fromSequence() + "|" + ordered.previousSequence();
                    return CommandResult.succeeded(
                            StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                }))));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a reprocessing stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new ReprocessingReceipt(stored[0], Long.parseLong(stored[1]), Long.parseLong(stored[2]));
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (MonitoringReprocessing.ReasonRequired reason) {
            throw refused(RiskErrorCode.REASON_REQUIRED, reason);
        } catch (MonitoringReprocessing.NotBehind ahead) {
            throw refused(RiskErrorCode.REPROCESSING_NOT_BEHIND, ahead);
        } catch (MonitoringReprocessing.SourceNotFound unknown) {
            throw new ApiException(RiskErrorCode.NOT_FOUND, "No monitoring source answers the request",
                    "no such record.");
        }
    }

    private static ApiException refused(RiskErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A monitoring reprocessing was refused", cause.getMessage());
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk monitoring command runs inside a correlation scope"))
                .correlationId();
    }
}
