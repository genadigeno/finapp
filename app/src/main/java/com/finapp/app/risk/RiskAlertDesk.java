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
import com.finapp.risk.Alerts;
import com.finapp.risk.RiskAlert;
import com.finapp.risk.RiskAlertId;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskReasons;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The suppression door (`P13-TSK-018`; `-015`'s machine, {@link Alerts}; {@code PHASE_13_PLAN.md} sections 9, 11;
 * {@code RISK_LIFECYCLES.md} section 4.5; {@code INV-RSK-07}, {@code INV-AUD-04}; `-016`'s {@code RiskCaseDesk} shape):
 * an analyst proposes a reasoned suppression of an open alert, a second holder approves it from a
 * {@code MULTI_FACTOR} session, anyone - the proposer's withdrawal included - rejects it. Each act keyed per principal
 * ({@code risk.alert:<type>:<id>}) and run in one transaction under the alert's row lock (R5, the machine's); a lost
 * response replays the stored alert id and the answer is the alert as it stands now.
 *
 * <p>The domain's refusals on the error contract: the proposer's approval {@code 403 risk.SelfApprovalRefused} and the
 * analyst's own party's alert - or one whose subject's party cannot be placed - {@code 403 risk.SelfDealingRefused},
 * each recorded {@code FAILED} in a transaction of its own after the act rolled back; an attached or suppressed alert
 * {@code 409 risk.AlertNotOpen} (the register's since `-015`); a proposal already standing, or none to decide,
 * {@code 409 risk.SuppressionStale}; a reason missing, unbounded or card-shaped, or a code outside the vocabulary,
 * {@code 422 risk.ReasonRequired}; an unknown or malformed alert the uniform {@code 404 risk.NotFound}. The persons are
 * the session's - {@code CUSTOMER}-typed as it establishes every identity, which {@code risk V014} admits - and the
 * system never suppresses: its dedupe is {@code ATTACHED}.
 */
public final class RiskAlertDesk {

    static final String SCOPE = "risk.alert:";

    private final Alerts alerts;
    private final IdempotentExecutor executor;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;

    public RiskAlertDesk(Alerts alerts, IdempotentExecutor executor, RiskTransactions transactions,
            RiskFlowScope flow) {
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    /** Proposes {@code rawAlertId}'s suppression with a reason code and a text. */
    public RiskQueueDesk.RiskAlertView propose(String idempotencyKey, String rawAlertId,
            RiskAlertController.RiskSuppressionProposalRequest request) {
        RiskAlertId alertId = alertId(rawAlertId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        String stored = flow.door(RiskSpans.ALERT_SUPPRESS, () -> keyed(actor, alertId, idempotencyKey,
                "propose|" + alertId.value() + "|" + request.reasonCode() + "|" + request.reason(),
                uow -> alerts.proposeSuppression(uow, alertId, request.reasonCode(), request.reason(), actor,
                        correlation).id().value().toString()));
        return receipt(alertId(stored));
    }

    /** Approves {@code rawAlertId}'s standing suppression - a second person's act. */
    public RiskQueueDesk.RiskAlertView approve(String idempotencyKey, String rawAlertId,
            RiskAlertController.RiskSuppressionDecisionRequest request) {
        RiskAlertId alertId = alertId(rawAlertId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        String stored = flow.door(RiskSpans.ALERT_SUPPRESS, () -> keyed(actor, alertId, idempotencyKey,
                "approve|" + alertId.value() + "|" + request.reason(),
                uow -> alerts.approveSuppression(uow, alertId, request.reason(), actor, correlation).alert().id()
                        .value().toString()));
        return receipt(alertId(stored));
    }

    /** Rejects {@code rawAlertId}'s standing suppression - anyone, the proposer's withdrawal included. */
    public RiskQueueDesk.RiskAlertView reject(String idempotencyKey, String rawAlertId,
            RiskAlertController.RiskSuppressionDecisionRequest request) {
        RiskAlertId alertId = alertId(rawAlertId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        String stored = flow.door(RiskSpans.ALERT_SUPPRESS, () -> keyed(actor, alertId, idempotencyKey,
                "reject|" + alertId.value() + "|" + request.reason(),
                uow -> alerts.rejectSuppression(uow, alertId, request.reason(), actor, correlation).alert().id()
                        .value().toString()));
        return receipt(alertId(stored));
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored alert id; a refusal stores nothing, and a
     * refused self-approval or self-dealing is recorded {@code FAILED} in a transaction of its own first.
     */
    private String keyed(Actor actor, RiskAlertId alertId, String idempotencyKey, String fingerprinted,
            Function<Connection, String> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> refusalsRecorded(actor, alertId,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow ->
                        CommandResult.succeeded(StoredResponse.of(act.apply(uow).getBytes(StandardCharsets.UTF_8),
                                "text/plain"))))));
        return new String(outcome.body().orElseThrow(() -> new IllegalStateException(
                "a suppression act stores its alert's id")), StandardCharsets.UTF_8);
    }

    /** The receipt of an act just performed or replayed - the alert as it stands now. */
    private RiskQueueDesk.RiskAlertView receipt(RiskAlertId id) {
        RiskAlert current = transactions.inTransaction(uow -> alerts.current(uow, id))
                .orElseThrow(() -> new IllegalStateException("an alert this door acted on is not found"));
        return RiskQueueDesk.view(current);
    }

    /**
     * {@code work}, a refused self-approval or self-dealing recorded {@code FAILED} in a transaction of its own after the
     * act rolled back ({@code INV-RSK-07}), then refused as before.
     */
    private <R> R refusalsRecorded(Actor actor, RiskAlertId alertId, Supplier<R> work) {
        try {
            return work.get();
        } catch (Alerts.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                alerts.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        } catch (RiskAssessments.SelfDealingRefused dealing) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                alerts.recordRefusal(uow, alertId, dealing, actor, correlation);
                return null;
            });
            throw dealing;
        }
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (Alerts.AlertNotFound unknown) {
            throw notFound();
        } catch (Alerts.SelfApprovalRefused self) {
            throw refused(RiskErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (RiskAssessments.SelfDealingRefused dealing) {
            throw new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A risk alert act was refused",
                    "a holder never acts on an alert of their own party, nor one whose subject the platform cannot"
                            + " place; nothing was done.");
        } catch (Alerts.AlertNotOpen notOpen) {
            throw refused(RiskErrorCode.ALERT_NOT_OPEN, notOpen);
        } catch (Alerts.SuppressionStale stale) {
            throw refused(RiskErrorCode.SUPPRESSION_STALE, stale);
        } catch (RiskReasons.ReasonRequired reason) {
            throw refused(RiskErrorCode.REASON_REQUIRED, reason);
        }
    }

    private static ApiException refused(RiskErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A risk alert act was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(RiskErrorCode.NOT_FOUND, "No risk record answers the request", "no such record.");
    }

    private static RiskAlertId alertId(String raw) {
        try {
            return RiskAlertId.of(UUID.fromString(Objects.requireNonNull(raw, "raw")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk alert act runs inside a correlation scope"))
                .correlationId();
    }
}
