package com.finapp.app.accounting;

import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseChecks;
import com.finapp.accounting.CloseRuns;
import com.finapp.platform.security.Actor;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * One worker's T-check (`P14-TSK-011`; {@code PHASE_14_PLAN.md} section 8.1; {@code INV-ACC-09}): claim one attempt in
 * a short transaction; judge it <strong>holding no transaction</strong> ({@code AccountingChecks}' contract,
 * `P14-TSK-010`'s review, M-1), a heartbeat renewing the lease for as long as it lasts; conclude it in one transaction
 * of its own. Every instance runs this with no leader: the claim's {@code SKIP LOCKED} gives each attempt to one
 * worker, the fence refuses an overtaken one's conclusion.
 *
 * <p><strong>A kill at any point resumes</strong>: before the conclusion nothing of the attempt is recorded, every
 * break already raised stands, and the lease lapses; the next worker reclaims, finds those breaks known, and concludes.
 * A failure of the conclusion's own transaction is the same case - counted, logged by class, the lease left to lapse;
 * never a pass.
 *
 * <p>The heartbeat is liveness only: it never decides anything. A lost renewal costs at most the lease, and the fence
 * makes that harmless.
 */
@Slf4j
@RequiredArgsConstructor
public final class CloseCheckWorker {

    static final String SPAN = "accounting.close.check";

    /** Runs {@code beat} every {@code every} until it answers false or the returned stop runs - the schedule's executor. */
    @FunctionalInterface
    public interface Heartbeats {
        Runnable start(Duration every, BooleanSupplier beat);
    }

    @NonNull private final CloseRuns runs;
    @NonNull private final CloseChecks checks;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final CloseRunMetrics metrics;
    @NonNull private final AccountingFlowScope flow;
    @NonNull private final Heartbeats heartbeats;

    /**
     * One attempt, start to finish.
     *
     * @param actor the platform's - the schedule's system scope
     * @return whether an attempt was claimed - the leg stops at the first empty claim
     */
    public boolean workOne(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        Optional<CloseRuns.ClaimedCheck> held = transactions.inTransaction(uow -> runs.claim(uow, actor));
        if (held.isEmpty()) {
            return false;
        }
        CloseRuns.ClaimedCheck claimed = held.get();
        flow.inFlow(SPAN, claimed.correlation(), () -> {
            work(claimed);
            return null;
        });
        return true;
    }

    private void work(CloseRuns.ClaimedCheck claimed) {
        Runnable stop = heartbeats.start(runs.renewEvery(), () -> renew(claimed));
        try {
            Optional<CloseRuns.Concluded> concluded = conclude(claimed);
            if (concluded.isEmpty()) {
                metrics.deferred(claimed.check());
            } else if (!concluded.get().moved()) {
                metrics.overtaken(claimed.check());
            } else {
                metrics.concluded(claimed.check(), concluded.get().status());
                concluded.get().tookFor().ifPresent(metrics::tookFor);
            }
        } catch (RuntimeException failure) {
            // The lease runs out and another worker reclaims the attempt - nothing was concluded, so nothing passed.
            metrics.legFailed(AccountingCloseSchedule.CHECKS_LEG);
            log.warn("A close check could not be concluded - its lease will lapse: {} {}", claimed.check(),
                    failure.getClass().getName());
        } finally {
            stop.run();
        }
    }

    /** The attempt's conclusion; empty when it was given back. */
    private Optional<CloseRuns.Concluded> conclude(CloseRuns.ClaimedCheck claimed) {
        if (claimed.exhausted()) {
            return Optional.of(transactions.inTransaction(uow -> runs.concludeExhausted(uow, claimed)));
        }
        if (claimed.check() == CloseCheckCode.OPEN_ACCOUNTING_BREAKS) {
            return Optional.of(transactions.inTransaction(uow -> runs.concludeOpenBreaks(uow, claimed)));
        }
        Optional<CheckVerdict> verdict = checks.judge(claimed);
        if (verdict.isEmpty()) {
            transactions.inTransaction(uow -> runs.defer(uow, claimed));
            return Optional.empty();
        }
        return Optional.of(transactions.inTransaction(uow -> runs.conclude(uow, claimed, verdict.get())));
    }

    /** One beat: false stops the heartbeat - the attempt is no longer held, or is past the run's bound. A failure beats again. */
    private boolean renew(CloseRuns.ClaimedCheck claimed) {
        try {
            return transactions.inTransaction(uow -> runs.renew(uow, claimed));
        } catch (RuntimeException failure) {
            log.warn("A close check's lease could not be renewed: {}", failure.getClass().getName());
            return true;
        }
    }
}
