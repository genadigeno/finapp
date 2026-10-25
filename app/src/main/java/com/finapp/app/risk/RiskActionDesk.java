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
import com.finapp.risk.Cases;
import com.finapp.risk.RiskAction;
import com.finapp.risk.RiskActionId;
import com.finapp.risk.RiskActions;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskCase;
import com.finapp.risk.RiskCaseId;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskReasons;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The boundary half of the risk action doors (`P13-TSK-017`; {@code PHASE_13_PLAN.md} sections 9, 11, 12.8;
 * {@code RISK_LIFECYCLES.md} section 4.7; ADR-0112 decisions 3-4; the {@code RiskCaseDesk} shape): each act keyed per
 * principal under the case desk's scope ({@code risk.case:<type>:<id>}) and run in one transaction under its span; the
 * domain's refusals translated onto the error contract - a refused self-approval or self-dealing recorded {@code FAILED}
 * in a transaction of its own first; and, after an approval's transaction has committed - and again on every replay of
 * its key - the action driven through its owning domain ({@link RiskActionDriver#driveIfDue}: the claim committed, the
 * owner's keyed door on no transaction <strong>as the platform</strong>, the record). The approval answers {@code 202}:
 * the effect is the platform's, recorded on the action - usually already by this door's own drive, else by
 * {@code RiskActionSchedule}. The machine, the four eyes, what a case may propose and the self-dealing rule are the
 * domain's and {@code risk V013}'s; nothing here decides them.
 *
 * <p><strong>The key's answer</strong> is the action's id: a replayed act answers the action as it stands now. Persons
 * are never served; the target is - this is the analyst's desk, behind {@code RISK_INVESTIGATE} /
 * {@code RISK_CASE_APPROVE}, never logged.
 */
public final class RiskActionDesk {

    private final RiskActions actions;
    private final Cases cases;
    private final RiskActionDriver driver;
    private final IdempotentExecutor executor;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;

    public RiskActionDesk(RiskActions actions, Cases cases, RiskActionDriver driver, IdempotentExecutor executor,
            RiskTransactions transactions, RiskFlowScope flow) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    /**
     * A risk action as an act answers it: its id (what the approval and rejection name - and its key's), its case, the
     * kind, what it acts on, the state and the owner's outcome, the attempts T-act made, the case's revision (what the
     * next act on the case must carry) and the database's stamps. Instants ISO-8601 UTC; no person.
     */
    public record RiskActionView(String actionId, String caseId, String kind, String targetKind, String targetRef,
            String status, String outcome, int attempts, int caseRevision, String proposedAt, String decidedAt,
            String dispatchedAt, String concludedAt) {}

    // ------------------------------------------------------------------ acts

    public RiskActionView propose(String idempotencyKey, String rawCaseId,
            RiskActionController.RiskActionProposalRequest request) {
        RiskCaseId caseId = caseId(rawCaseId);
        Optional<UUID> target = Optional.ofNullable(request.targetRef()).map(raw -> uuid(raw, "the target"));
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        String stored = flow.door(RiskSpans.ACTION_PROPOSE, () -> keyed(actor, caseId.value().toString(),
                idempotencyKey, "action-propose|" + caseId.value() + "|" + request.revision() + "|" + request.kind()
                        + "|" + request.targetRef() + "|" + request.reason(),
                uow -> actions.propose(uow, caseId, request.revision(), request.kind(), target, request.reason(), actor,
                        correlation).action().id().value().toString()));
        return receipt(caseId, actionId(stored));
    }

    public RiskActionView approve(String idempotencyKey, String rawCaseId, String rawActionId,
            RiskCaseController.RiskCaseDecisionRequest request) {
        RiskCaseId caseId = caseId(rawCaseId);
        RiskActionId actionId = actionId(rawActionId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        flow.door(RiskSpans.ACTION_DECIDE, () -> keyed(actor, caseId.value().toString(), idempotencyKey,
                "action-approve|" + caseId.value() + "|" + actionId.value() + "|" + request.revision() + "|"
                        + request.reason(),
                uow -> actions.approve(uow, caseId, actionId, request.revision(), request.reason(), actor, correlation)
                        .action().id().value().toString()));
        // The approval has committed (or its key replayed it): now, outside every transaction and AS THE PLATFORM, the
        // action through its owner's door - again on every retry, so a crash between the two is re-driven by the door's
        // own retry as well as by the schedule. A failure is the driver's to count; the approval stands.
        driver.driveIfDue(actionId);
        return receipt(caseId, actionId);
    }

    public RiskActionView reject(String idempotencyKey, String rawCaseId, String rawActionId,
            RiskCaseController.RiskCaseDecisionRequest request) {
        RiskCaseId caseId = caseId(rawCaseId);
        RiskActionId actionId = actionId(rawActionId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        flow.door(RiskSpans.ACTION_DECIDE, () -> keyed(actor, caseId.value().toString(), idempotencyKey,
                "action-reject|" + caseId.value() + "|" + actionId.value() + "|" + request.revision() + "|"
                        + request.reason(),
                uow -> actions.reject(uow, caseId, actionId, request.revision(), request.reason(), actor, correlation)
                        .action().id().value().toString()));
        return receipt(caseId, actionId);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored action id; a refusal stores nothing, and a
     * refused self-approval or self-dealing is recorded {@code FAILED} in a transaction of its own first.
     */
    private String keyed(Actor actor, String caseTarget, String idempotencyKey, String fingerprinted,
            Function<Connection, String> act) {
        IdempotencyKey key = new IdempotencyKey(RiskCaseDesk.SCOPE + actor.type().name() + ":" + actor.id(),
                idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> refusalsRecorded(actor, caseTarget,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow ->
                        CommandResult.succeeded(StoredResponse.of(act.apply(uow).getBytes(StandardCharsets.UTF_8),
                                "text/plain"))))));
        return new String(outcome.body().orElseThrow(() -> new IllegalStateException(
                "a risk action act stores its action's id")), StandardCharsets.UTF_8);
    }

    /** The receipt of an act just performed or replayed - the action and its case as they stand now. */
    private RiskActionView receipt(RiskCaseId caseId, RiskActionId actionId) {
        return transactions.inTransaction(uow -> {
            RiskAction action = actions.current(uow, actionId).orElseThrow(() -> new IllegalStateException(
                    "an action this door acted on is not found"));
            RiskCase riskCase = cases.current(uow, caseId).orElseThrow(() -> new IllegalStateException(
                    "a case this door acted on is not found"));
            return view(action, riskCase.revision());
        });
    }

    /**
     * {@code work}, a refused self-approval or self-dealing recorded {@code FAILED} in a transaction of its own after the
     * act rolled back ({@code INV-RSK-07}), then refused as before.
     */
    private <R> R refusalsRecorded(Actor actor, String caseTarget, Supplier<R> work) {
        try {
            return work.get();
        } catch (RiskActions.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                actions.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        } catch (RiskAssessments.SelfDealingRefused dealing) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                cases.recordRefusal(uow, caseTarget, dealing, actor, correlation);
                return null;
            });
            throw dealing;
        }
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (Cases.CaseNotFound unknown) {
            throw notFound();
        } catch (RiskActions.SelfApprovalRefused self) {
            throw refused(RiskErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (RiskAssessments.SelfDealingRefused dealing) {
            throw new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A risk action act was refused",
                    "a holder never acts on a case of their own party, nor one whose subject the platform cannot place;"
                            + " nothing was done.");
        } catch (RiskActions.ActionNotProposable notProposable) {
            throw refused(RiskErrorCode.ACTION_NOT_PROPOSABLE, notProposable);
        } catch (Cases.CaseRevisionStale stale) {
            throw refused(RiskErrorCode.CASE_REVISION_STALE, stale);
        } catch (Cases.CaseNotOpen disposed) {
            throw refused(RiskErrorCode.CASE_NOT_OPEN, disposed);
        } catch (Cases.CaseActNotAdmitted notAdmitted) {
            throw refused(RiskErrorCode.CASE_ACT_NOT_ADMITTED, notAdmitted);
        } catch (Cases.CaseNotTaken notTaken) {
            throw refused(RiskErrorCode.CASE_NOT_TAKEN, notTaken);
        } catch (Cases.CaseRequestInvalid invalid) {
            throw refused(RiskErrorCode.CASE_REQUEST_INVALID, invalid);
        } catch (RiskReasons.ReasonRequired reason) {
            throw refused(RiskErrorCode.REASON_REQUIRED, reason);
        }
    }

    static RiskActionView view(RiskAction action, int caseRevision) {
        return new RiskActionView(
                action.id().value().toString(),
                action.caseId().map(id -> id.value().toString()).orElse(null),
                action.kind().name(),
                action.targetKind().name(),
                action.target().toString(),
                action.status().name(),
                action.outcome().map(Enum::name).orElse(null),
                action.attempts(),
                caseRevision,
                action.proposedAt().map(Instant::toString).orElse(null),
                action.decidedAt().map(Instant::toString).orElse(null),
                action.dispatchedAt().map(Instant::toString).orElse(null),
                action.concludedAt().map(Instant::toString).orElse(null));
    }

    private static UUID uuid(String raw, String what) {
        try {
            return UUID.fromString(Objects.requireNonNull(raw, "raw"));
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(RiskErrorCode.CASE_REQUEST_INVALID, "A risk action command was refused",
                    what + " is named by its identifier");
        }
    }

    private static ApiException refused(RiskErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A risk action command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(RiskErrorCode.NOT_FOUND, "No risk record answers the request", "no such record.");
    }

    private static RiskCaseId caseId(String raw) {
        try {
            return RiskCaseId.of(UUID.fromString(Objects.requireNonNull(raw, "raw")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static RiskActionId actionId(String raw) {
        try {
            return RiskActionId.of(UUID.fromString(Objects.requireNonNull(raw, "raw")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk action act runs inside a correlation scope"))
                .correlationId();
    }
}
