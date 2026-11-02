package com.finapp.app.accounting;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccountingReasons;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.CloseRuns;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the close run's doors (`P14-TSK-011`; {@code AccountingBreakDesk}'s shape): parse the request
 * exactly, key the re-run per principal ({@code accounting.closerun:<type>:<id>}), run it in one transaction under its
 * span, translate the domain's refusals onto the error contract; a read recorded {@code accounting.CloseRunRead} in a
 * transaction of its own before the answer. The machine, the attempts and the lease are the domain's and {@code
 * accounting V007}'s; nothing here judges a check and nothing here posts.
 *
 * <p>What leaves: each check's attempts - status, what failed, the versions read, the evidence (canonical JSON text,
 * aggregates at most) and their instants. Never a person, never a reason.
 */
@RequiredArgsConstructor
public final class CloseRunDesk {

    static final String SCOPE = "accounting.closerun:";

    static final String READ_SPAN = "accounting.closerun.read";
    static final String RERUN_SPAN = "accounting.closerun.rerun";

    @NonNull private final CloseRuns runs;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AccountingFlowScope flow;

    /** A re-run's receipt: the check's new attempt, {@code PENDING} - a worker claims it. */
    public record CloseCheckRerunReceipt(String runId, String period, String check, int attempt, String status) {}

    /** One attempt as the operator reads it - no person, no reason. */
    public record CloseCheckAttemptView(
            int attempt,
            String status,
            List<String> failures,
            String judgedBy,
            String evidence,
            int reclaims,
            String bornAt,
            String concludedAt) {}

    /** One check: its attempts in all, the latest and the newest few, newest first. */
    public record CloseCheckView(String check, int attempts, String status, List<CloseCheckAttemptView> history) {}

    /**
     * A period's latest close run. {@code current} when the period still holds the run's lock; {@code complete} when a
     * close may be proposed on it - every check's latest attempt {@code PASSED} or {@code DISCLOSED}, and, read live,
     * no break of the month open.
     */
    public record CloseRunView(
            String runId,
            String period,
            String periodStatus,
            int periodRevision,
            String lockedAt,
            String startedAt,
            String checksConcludedAt,
            boolean current,
            boolean complete,
            long openBreaks,
            List<CloseCheckView> checks) {}

    /** The period's latest run. A period with none - not yet locked, or locked within a poll - is {@code accounting.NotFound}. */
    public CloseRunView closeRun(String rawPeriodId) {
        AccountingPeriodId id = periodId(rawPeriodId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        CloseRuns.RunView view = flow.door(READ_SPAN, () -> guarded(() -> transactions.inTransaction(uow ->
                runs.view(uow, id).orElseThrow(CloseRunDesk::notFound))));
        transactions.inTransaction(uow -> {
            runs.recordRead(uow, view, actor, correlation);
            return null;
        });
        return view(view);
    }

    /** A check's next attempt - keyed per principal; a lost response replays the stored receipt. */
    public CloseCheckRerunReceipt rerun(String idempotencyKey, String rawPeriodId, String rawCheck, String reason) {
        AccountingPeriodId id = periodId(rawPeriodId);
        CloseCheckCode check = check(rawCheck);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("rerun|" + id.value() + "|" + check.name() + "|" + reason)
                .getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = flow.door(RERUN_SPAN, () -> guarded(() ->
                transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    CloseRuns.Rerun rerun = runs.rerun(uow, id, check, actor, reason, correlation);
                    String stored = rerun.run().value() + "|" + rerun.month() + "|" + rerun.check().name() + "|"
                            + rerun.attempt();
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                }))));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a re-run stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new CloseCheckRerunReceipt(stored[0], stored[1], stored[2], Integer.parseInt(stored[3]), "PENDING");
    }

    // ------------------------------------------------------------------ views

    private static CloseRunView view(CloseRuns.RunView view) {
        CloseRunStore.RunRow run = view.run();
        return new CloseRunView(run.id().value().toString(), run.month().toString(), view.periodStatus().name(),
                run.periodRevision(), run.lockedAt().toString(), run.startedAt().toString(),
                run.checksConcludedAt().map(Instant::toString).orElse(null), view.current(), view.complete(),
                view.openBreaks(), view.checks().stream().map(CloseRunDesk::view).toList());
    }

    private static CloseCheckView view(CloseRuns.CheckView check) {
        return new CloseCheckView(check.check().name(), check.attempts(), check.latest().status().name(),
                check.history().stream().map(CloseRunDesk::view).toList());
    }

    private static CloseCheckAttemptView view(CloseRunStore.AttemptRow attempt) {
        return new CloseCheckAttemptView(attempt.attempt(), attempt.status().name(), attempt.failures(),
                attempt.judgedBy().orElse(null), attempt.evidence().orElse(null), attempt.reclaims(),
                attempt.bornAt().toString(), attempt.concludedAt().map(Instant::toString).orElse(null));
    }

    // ------------------------------------------------------------------ plumbing

    /** The domain's refusals, on the contract. */
    private static <T> T guarded(Supplier<T> work) {
        try {
            return work.get();
        } catch (AccountingPeriods.PeriodNotFound | CloseRuns.RunNotStarted unknown) {
            throw notFound();
        } catch (AccountingPeriods.PeriodStale stale) {
            throw refused(AccountingErrorCode.PERIOD_STALE, stale);
        } catch (CloseRuns.CheckPending pending) {
            throw refused(AccountingErrorCode.CLOSE_CHECK_PENDING, pending);
        } catch (AccountingReasons.ReasonRequired reason) {
            throw refused(AccountingErrorCode.REASON_REQUIRED, reason);
        }
    }

    private static ApiException refused(AccountingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A close run command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(AccountingErrorCode.NOT_FOUND, "No close run answers the request",
                "no such record.");
    }

    private static AccountingPeriodId periodId(String raw) {
        try {
            return AccountingPeriodId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    /** A check's name on the path - an unknown one is simply no such resource. */
    private static CloseCheckCode check(String raw) {
        try {
            return CloseCheckCode.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a close run command runs inside a correlation scope"))
                .correlationId();
    }
}
