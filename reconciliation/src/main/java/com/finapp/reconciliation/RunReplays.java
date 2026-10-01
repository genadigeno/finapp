package com.finapp.reconciliation;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Decision replay over a run (`P8-TSK-022`, ADR-0068 §9.1; `INV-HIST-04`, `INV-REC-04`): every
 * decision on the run's items - and every decision written under it, for a {@code REPROCESS}
 * run - replayed through {@link DecisionReplay} from ONE repeatable-read snapshot, then ONE short
 * append: the {@code run_replay} verdict, a CRITICAL {@code PROCESSING_ERROR}
 * ({@code REPLAY_DIVERGED}) break on the first divergent decision when any diverged, and the
 * audit record. It writes nothing else - a replay explains, it never repairs.
 *
 * <p><strong>Ten replays at once</strong> append ten verdicts and contend on nothing; their
 * divergence breaks converge on {@code break_one_open_per_decision} (`V012`). Items whose
 * rematch is merely pending are counted apart ({@code PENDING_REMATCH}), never as divergence:
 * a decision that has not been taken yet cannot diverge.
 */
@RequiredArgsConstructor
public class RunReplays {

    @NonNull private final TransactionRunner transactions;
    @NonNull private final RunReplayStore store;
    @NonNull private final MatchingStore matching;
    @NonNull private final BreakRegister breaks;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final ReplayObserver observer;

    /** No reconciliation run has this id. */
    public static final class RunNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public RunNotFound() {
            super("no reconciliation run has this identifier");
        }
    }

    /** One replay's verdict, as appended. */
    public record Replay(
            UUID replayId,
            UUID runId,
            String verdict,
            int replayed,
            int notReplayed,
            int divergences,
            int pendingRematch,
            Optional<UUID> firstDivergentDecision) {}

    private record Computed(
            MatchingStore.RunRow run, List<DecisionReplay.Replayed> replayed, int pendingRematch) {}

    public Replay replay(UUID runId, Actor actor, CorrelationId correlation) {
        Computed computed =
                transactions.inTransaction(
                        unitOfWork -> {
                            snapshot(unitOfWork);
                            MatchingStore.RunRow run =
                                    matching.run(unitOfWork, runId).orElseThrow(RunNotFound::new);
                            List<DecisionReplay.Replayed> replayed =
                                    store.storedDecisions(unitOfWork, runId).stream()
                                            .map(DecisionReplay::replay)
                                            .toList();
                            return new Computed(
                                    run, replayed, matching.pendingRematchOf(unitOfWork, runId));
                        });
        Replay replay =
                transactions.inTransaction(
                        unitOfWork -> append(unitOfWork, computed, actor, correlation));
        // Counted once the verdict is committed - never for an append that rolled back.
        observer.replayed(replay.divergences() > 0 ? "diverged" : "identical");
        return replay;
    }

    /** One consistent read of the run's decisions, however many the matcher writes meanwhile. */
    private static void snapshot(Connection unitOfWork) {
        try (Statement set = unitOfWork.createStatement()) {
            set.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not take the replay's snapshot", failure);
        }
    }

    private Replay append(
            Connection unitOfWork, Computed computed, Actor actor, CorrelationId correlation) {
        Instant now = clock.instant();
        int divergences = count(computed, DecisionReplay.Result.DIVERGED);
        int replayed = divergences + count(computed, DecisionReplay.Result.IDENTICAL);
        int notReplayed = count(computed, DecisionReplay.Result.NOT_REPLAYED);
        Optional<UUID> first = DecisionReplay.firstDivergent(computed.replayed());
        String verdict = divergences > 0 ? "DIVERGED" : "IDENTICAL";
        UUID replayId = ids.next();
        MatchingStore.RunRow run = computed.run();
        store.insertReplay(
                unitOfWork,
                new RunReplayStore.NewReplay(
                        replayId, run.id(), actor, verdict, replayed, notReplayed, divergences,
                        computed.pendingRematch(), first, now, correlation));
        first.ifPresent(
                decisionId ->
                        breaks.raise(
                                unitOfWork,
                                new BreakRegister.NewBreak(
                                        ids.next(),
                                        BreakType.PROCESSING_ERROR,
                                        BreakCause.REPLAY_DIVERGED,
                                        BreakRegister.Subject.decision(decisionId),
                                        run.sourceId(),
                                        run.ruleSetId(),
                                        // A divergence holds no money; the run row carries no
                                        // currency, so the zero rides the presentation currency.
                                        Money.ofPersisted(0, CurrencyCode.of("EUR"), 2),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        actor,
                                        now,
                                        correlation)));
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        ReconciliationAuditAction.RUN_REPLAYED,
                        "reconciliation_run",
                        run.id().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(
                                "replay=" + replayId + ", verdict=" + verdict + ", replayed="
                                        + replayed + ", notReplayed=" + notReplayed
                                        + ", divergences=" + divergences + ", pendingRematch="
                                        + computed.pendingRematch())));
        return new Replay(
                replayId, run.id(), verdict, replayed, notReplayed, divergences,
                computed.pendingRematch(), first);
    }

    private static int count(Computed computed, DecisionReplay.Result result) {
        return (int) computed.replayed().stream().filter(one -> one.result() == result).count();
    }
}
