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
import com.finapp.risk.DecisionOverride;
import com.finapp.risk.DecisionOverrideId;
import com.finapp.risk.DecisionOverrides;
import com.finapp.risk.OverrideResolution;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskReasons;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The boundary half of the override doors (`P13-TSK-012`; `-002`'s {@code RiskPolicyDesk} shape): each act keyed per
 * principal ({@code risk.override:<type>:<id>}) and run in one transaction under its span; the domain's refusals
 * translated onto the error contract - a refused self-approval or self-dealing recorded {@code FAILED} in a transaction
 * of its own first; and, after an approval's transaction has committed - and again on every retry of it - the action it
 * issued driven through payments' door ({@link OverrideActionDriver}), on no transaction. The four-eyes rule, the
 * machine, the effect and C7 are the domain's and {@code risk V009}'s; nothing here decides them.
 *
 * <p><strong>The key's answer</strong> is the override's id: a replayed act answers the override as it stands now -
 * its issued action's outcome included, which a re-drive may since have recorded. A read is not keyed: a repeated read
 * is a second serving, recorded again ({@code risk.AssessmentRead}). Persons are never served; the reasons are - this is
 * the desk the second officer decides at ({@code RESTRICTED-PII}, never logged).
 */
public final class RiskOverrideDesk {

    static final String SCOPE = "risk.override:";

    private final DecisionOverrides overrides;
    private final OverrideActionDriver driver;
    private final IdempotentExecutor executor;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;
    private final RiskOverrideMetrics metrics;

    public RiskOverrideDesk(DecisionOverrides overrides, OverrideActionDriver driver, IdempotentExecutor executor,
            RiskTransactions transactions, RiskFlowScope flow, RiskOverrideMetrics metrics) {
        this.overrides = Objects.requireNonNull(overrides, "overrides");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * One override as the officer reads it: its state, the effect proposed and what it targets, the reason code and the
     * proposer's text, whether a second holder is required, the decision's reason, and the action issued with its
     * outcome, permit and attempts. Instants ISO-8601 UTC; no person.
     */
    public record RiskOverrideView(String overrideId, String assessmentId, String status, String effect,
            String targetKind, String targetId, String reasonCode, String reason, boolean requiresSecondPerson,
            String proposedAt, String decidedAt, String decisionReason, String action, String actionOutcome,
            String actionDueAt, int actionAttempts, String actionConcludedAt) {}

    /** The overridden assessment, whole, and its overrides, oldest first. */
    public record RiskOverriddenView(RiskAssessmentDesk.RiskAssessmentView assessment,
            List<RiskOverrideView> overrides) {}

    // ------------------------------------------------------------------ reads

    public RiskOverriddenView read(String rawAssessmentId) {
        RiskAssessmentId assessmentId = assessmentId(rawAssessmentId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return served(assessmentId, actor, correlation, () -> flow.door(RiskSpans.EXPLAIN,
                () -> transactions.inTransaction(uow -> overrides.read(uow, assessmentId, actor, correlation))));
    }

    public RiskOverriddenView readOne(String rawAssessmentId, String rawOverrideId) {
        RiskAssessmentId assessmentId = assessmentId(rawAssessmentId);
        DecisionOverrideId overrideId = overrideId(rawOverrideId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return served(assessmentId, actor, correlation, () -> flow.door(RiskSpans.EXPLAIN,
                () -> transactions.inTransaction(uow -> overrides.readOne(uow, assessmentId, overrideId, actor,
                        correlation))));
    }

    // ------------------------------------------------------------------ acts

    public RiskOverrideView propose(String idempotencyKey, String rawAssessmentId,
            RiskOverrideController.RiskOverrideProposalRequest request) {
        RiskAssessmentId assessmentId = assessmentId(rawAssessmentId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        OverrideResolution resolution = request.resolution() == null
                ? OverrideResolution.PROCEED
                : OverrideResolution.valueOf(request.resolution());
        Keyed act = flow.door(RiskSpans.OVERRIDE_PROPOSE, () -> keyed(actor, assessmentId, idempotencyKey,
                "propose|" + assessmentId.value() + "|" + resolution + "|" + request.reasonCode() + "|"
                        + request.reason(),
                uow -> overrides.propose(uow, assessmentId, resolution, request.reasonCode(), request.reason(), actor,
                        correlation).id()));
        act.count(metrics, RiskOverrideMetrics.Act.PROPOSED);
        return receipt(act.id());
    }

    public RiskOverrideView approve(String idempotencyKey, String rawAssessmentId, String rawOverrideId,
            String reason) {
        RiskAssessmentId assessmentId = assessmentId(rawAssessmentId);
        DecisionOverrideId overrideId = overrideId(rawOverrideId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        Keyed act = flow.door(RiskSpans.OVERRIDE_DECIDE, () -> keyed(actor, assessmentId, idempotencyKey,
                "approve|" + assessmentId.value() + "|" + overrideId.value() + "|" + reason,
                uow -> overrides.approve(uow, assessmentId, overrideId, reason, actor, correlation, driver.lease())
                        .override().id()));
        act.count(metrics, RiskOverrideMetrics.Act.APPROVED);
        // The approval has committed (or its key replayed it): now, outside every transaction, the action it issued -
        // again on every retry, so a crash between the commit and the dispatch is re-driven by the door's own retry.
        driver.driveIfPending(act.id(), actor, correlation);
        return receipt(act.id());
    }

    public RiskOverrideView reject(String idempotencyKey, String rawAssessmentId, String rawOverrideId, String reason) {
        RiskAssessmentId assessmentId = assessmentId(rawAssessmentId);
        DecisionOverrideId overrideId = overrideId(rawOverrideId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        Keyed act = flow.door(RiskSpans.OVERRIDE_DECIDE, () -> keyed(actor, assessmentId, idempotencyKey,
                "reject|" + assessmentId.value() + "|" + overrideId.value() + "|" + reason,
                uow -> overrides.reject(uow, assessmentId, overrideId, reason, actor, correlation).override().id()));
        act.count(metrics, RiskOverrideMetrics.Act.REJECTED);
        return receipt(act.id());
    }

    // ------------------------------------------------------------------ plumbing

    /** A keyed act's override, and whether this call ran it (a replay is counted once, by the call that ran it). */
    private record Keyed(DecisionOverrideId id, boolean executed) {
        void count(RiskOverrideMetrics metrics, RiskOverrideMetrics.Act act) {
            if (executed) {
                metrics.act(act);
            }
        }
    }

    /**
     * One keyed act in one transaction - a lost response replays the stored override id; a refusal stores nothing, and a
     * refused self-approval or self-dealing is recorded {@code FAILED} in a transaction of its own first.
     */
    private Keyed keyed(Actor actor, RiskAssessmentId assessmentId, String idempotencyKey,
            String fingerprinted, Function<Connection, DecisionOverrideId> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> refusalsRecorded(actor, assessmentId,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow ->
                        CommandResult.succeeded(StoredResponse.of(act.apply(uow).value().toString()
                                .getBytes(StandardCharsets.UTF_8), "text/plain"))))));
        String stored = new String(outcome.body().orElseThrow(() -> new IllegalStateException(
                "an override act stores its override's id")), StandardCharsets.UTF_8);
        return new Keyed(DecisionOverrideId.of(UUID.fromString(stored)), outcome.executed());
    }

    /** {@code read} - a refused self-dealing recorded {@code FAILED} first - and the served rows in the API's words. */
    private RiskOverriddenView served(RiskAssessmentId assessmentId, Actor actor, CorrelationId correlation,
            Supplier<DecisionOverrides.Served> read) {
        DecisionOverrides.Served served = guarded(() -> refusalsRecorded(actor, assessmentId, read));
        return new RiskOverriddenView(RiskAssessmentDesk.view(served.assessment()),
                served.overrides().stream().map(RiskOverrideDesk::view).toList());
    }

    /** The receipt of an act just performed or replayed - the override as it stands now. */
    private RiskOverrideView receipt(DecisionOverrideId id) {
        DecisionOverride current = transactions.inTransaction(uow -> overrides.current(uow, id))
                .orElseThrow(() -> new IllegalStateException("an override this door acted on is not found"));
        return view(current);
    }

    /**
     * {@code work}, a refused self-approval or self-dealing recorded {@code FAILED} in a transaction of its own after
     * the act rolled back ({@code INV-RSK-07}), then refused as before.
     */
    private <R> R refusalsRecorded(Actor actor, RiskAssessmentId assessmentId, Supplier<R> work) {
        try {
            return work.get();
        } catch (DecisionOverrides.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                overrides.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            metrics.act(RiskOverrideMetrics.Act.REFUSED);
            throw self;
        } catch (RiskAssessments.SelfDealingRefused dealing) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                overrides.recordRefusal(uow, assessmentId, dealing, actor, correlation);
                return null;
            });
            metrics.act(RiskOverrideMetrics.Act.REFUSED);
            throw dealing;
        }
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (DecisionOverrides.OverrideNotFound unknown) {
            throw notFound();
        } catch (DecisionOverrides.SelfApprovalRefused self) {
            throw refused(RiskErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (RiskAssessments.SelfDealingRefused dealing) {
            throw new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A risk override act was refused",
                    "a holder never overrides, or reads, a risk assessment of their own party; nothing was done.");
        } catch (DecisionOverrides.ActionNotProposable notProposable) {
            throw refused(RiskErrorCode.ACTION_NOT_PROPOSABLE, notProposable);
        } catch (DecisionOverrides.OverrideStale stale) {
            throw refused(RiskErrorCode.OVERRIDE_STALE, stale);
        } catch (RiskReasons.ReasonRequired reason) {
            throw refused(RiskErrorCode.REASON_REQUIRED, reason);
        }
    }

    static RiskOverrideView view(DecisionOverride override) {
        return new RiskOverrideView(
                override.id().value().toString(),
                override.assessment().value().toString(),
                override.status().name(),
                override.effect().name(),
                override.effect().targetKind().orElse(null),
                override.target().map(UUID::toString).orElse(null),
                override.reasonCode().name(),
                override.reason(),
                override.requiresSecondPerson(),
                override.proposedAt().map(Instant::toString).orElse(null),
                override.decidedAt().map(Instant::toString).orElse(null),
                override.decisionReason().orElse(null),
                override.action().map(Enum::name).orElse(null),
                override.actionOutcome().map(Enum::name).orElse(null),
                override.actionDueAt().map(Instant::toString).orElse(null),
                override.actionAttempts(),
                override.actionConcludedAt().map(Instant::toString).orElse(null));
    }

    private static ApiException refused(RiskErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A risk override command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(RiskErrorCode.NOT_FOUND, "No risk record answers the request", "no such record.");
    }

    private static RiskAssessmentId assessmentId(String raw) {
        try {
            return RiskAssessmentId.of(UUID.fromString(Objects.requireNonNull(raw, "raw")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static DecisionOverrideId overrideId(String raw) {
        try {
            return DecisionOverrideId.of(UUID.fromString(Objects.requireNonNull(raw, "raw")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk override act runs inside a correlation scope"))
                .correlationId();
    }
}
