package com.finapp.app.accounting;

import com.finapp.accounting.ReportRunId;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunFailure;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.ReportRefused;
import com.finapp.platform.security.Actor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * T-report's three transactions, in order (`P14-TSK-008`; {@code PHASE_14_PLAN.md} section 8.1; ADR-0115 decision 2;
 * {@code INV-ACC-04}, {@code INV-ACC-14}): the claim has committed (the caller's); the content is generated on a
 * <strong>separate, read-only connection</strong> ({@link ReportSnapshots}) that holds nothing of the claim's; the
 * completion commits the content and its hash by the conditional edge ({@link ReportRuns#complete}). Nothing is held
 * across the generation - no lock, no row, no connection of the caller's - so a slow report blocks no one, and a generator
 * that dies leaves only a lease that runs out.
 *
 * <p><strong>Failures, sorted</strong>: the definition's own (a read it could not make, a write the snapshot refused,
 * {@code 25006}) is {@code DEFINITION_FAILED}; a FINAL run whose period moved is {@code PERIOD_MOVED}; a content the form
 * refuses or too large is {@code CONTENT_INVALID}; a generation past its time bound - its budget spent, or a statement
 * cancelled by the snapshot's {@code statement_timeout} ({@code 57014}) - is {@code GENERATION_TIMED_OUT}, inside its lease
 * (`P14-TSK-008`'s review, I-1); a definition no longer in the build is {@code DEFINITION_UNKNOWN} -
 * each a {@code FAILED} run, a new revision allowed. A definition's refusal ({@code ReportRefused}, `P14-TSK-016`) is {@code DEFINITION_FAILED} too, its reason on the record. The snapshot's own failure (the database unreachable) is none of
 * these: the run stays {@code GENERATING}, its lease runs out, and a retry or the orphan leg generates it again.
 *
 * <p><strong>The orphan leg</strong> ({@link #completeOrphaned}) is the callable `P14-TSK-011` schedules in
 * {@code AccountingCloseSchedule} (leg (c)): leaderless - every instance may run it; the {@code SKIP LOCKED} scan and the
 * conditional reclaim give each expired run to one of them.
 */
public final class ReportRunGenerator {

    private final ReportRuns runs;
    private final ReportGeneration generation;
    private final ReportSnapshots snapshots;
    private final AccountingTransactions transactions;
    private final ReportRunMetrics metrics;

    public ReportRunGenerator(ReportRuns runs, ReportGeneration generation, ReportSnapshots snapshots,
            AccountingTransactions transactions, ReportRunMetrics metrics) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** The regenerated hash when the definition refused to answer: there are no bytes to hash, and so no comparison but "not identical". */
    static final String NOT_PRODUCED = "";

    /** A FINAL run regenerated in a snapshot: its stored hash, the regenerated one, whether the bytes are identical, and why its definition refused to answer, by code, when it did. */
    public record Compared(ReportRunStore.RunRow run, String regeneratedSha256, boolean identical, Optional<String> refusal) {}

    /** The definition's own failure inside the snapshot - kept apart from the snapshot's. */
    private static final class DefinitionFailed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DefinitionFailed() {
            super("the report definition failed inside its snapshot");
        }
    }

    /**
     * Generates and completes {@code held} - a run the caller holds at its attempt (born by its claim, or reclaimed) - as
     * {@code actor}.
     *
     * @return the run as it now stands: concluded by this call, or by whoever completed it first
     */
    public ReportRunStore.RunRow generateAndComplete(ReportRunStore.RunRow held, Actor actor) {
        Objects.requireNonNull(held, "held");
        Objects.requireNonNull(actor, "actor");
        Optional<ReportDefinition> definition = runs.definitionOf(held);
        if (definition.isEmpty()) {
            return failed(held, RunFailure.DEFINITION_UNKNOWN, actor);
        }
        ReportGeneration.Generated generated;
        try {
            generated = snapshots.read(snapshot -> {
                try {
                    return generation.generate(snapshot, definition.get(), held.inputs());
                } catch (ReportGeneration.PeriodMoved | ReportGeneration.ContentInvalid
                        | ReportGeneration.GenerationTimedOut | ReportRefused sorted) {
                    throw sorted;
                } catch (RuntimeException definitionsOwn) {
                    throw new DefinitionFailed();
                }
            });
        } catch (ReportGeneration.PeriodMoved moved) {
            return failed(held, RunFailure.PERIOD_MOVED, actor);
        } catch (ReportGeneration.ContentInvalid invalid) {
            return failed(held, RunFailure.CONTENT_INVALID, actor);
        } catch (ReportGeneration.GenerationTimedOut timedOut) {
            return failed(held, RunFailure.GENERATION_TIMED_OUT, actor);
        } catch (ReportRefused refused) {
            return refused(held, refused, actor);
        } catch (DefinitionFailed definitionsOwn) {
            return failed(held, RunFailure.DEFINITION_FAILED, actor);
        }
        ReportRuns.Concluded concluded = transactions.inTransaction(
                unitOfWork -> runs.complete(unitOfWork, held.id(), held.attempt(), generated, actor));
        if (concluded.moved()) {
            metrics.concluded(concluded.run());
        }
        return concluded.run();
    }

    /**
     * Drives a run the caller named but does not hold - a replayed request: an abandoned {@code GENERATING} run (past its
     * lease) is taken over and generated; any other is answered as it stands.
     */
    public ReportRunStore.RunRow drive(ReportRunId id, Actor actor) {
        Objects.requireNonNull(id, "id");
        Optional<ReportRunStore.RunRow> held = transactions.inTransaction(
                unitOfWork -> runs.takeOver(unitOfWork, id, actor));
        if (held.isPresent()) {
            return generateAndComplete(held.get(), actor);
        }
        return transactions.inTransaction(unitOfWork -> runs.find(unitOfWork, id))
                .orElseThrow(() -> new IllegalStateException("a claimed report run is never deleted"));
    }

    /**
     * The orphan leg (`P14-TSK-011` schedules it; {@code PHASE_14_PLAN.md} section 8.4 (c)): up to {@code limit} runs past
     * their lease reclaimed - or failed past the attempts' bound - in one short transaction, then each generated and
     * completed as {@code actor} - the platform's, which the schedule establishes by its system scope
     * ({@code SecurityContext.enterSystem}) and passes in; this class never names the system actor itself.
     *
     * @return how many runs this call reclaimed
     */
    public int completeOrphaned(int limit, Actor actor) {
        Objects.requireNonNull(actor, "actor");
        List<ReportRunStore.RunRow> held = transactions.inTransaction(
                unitOfWork -> runs.reclaimExpired(unitOfWork, limit, actor));
        for (ReportRunStore.RunRow run : held) {
            generateAndComplete(run, actor);
        }
        return held.size();
    }

    /**
     * Regenerates a FINAL run from its pinned inputs in a snapshot and compares (`…/regeneration`, {@code INV-ACC-04}):
     * the run, its stored content and the regeneration are read in one snapshot, so the comparison is of one instant.
     *
     * @throws ReportRuns.RunNotFound when no run has this id
     * @throws RunNotFinal when the run is not FINAL, or its period no longer holds the lock it was generated under
     * @throws DefinitionNoLongerHeld when the build no longer holds the run's definition version
     */
    public Compared regenerate(ReportRunId id) {
        Objects.requireNonNull(id, "id");
        return snapshots.read(snapshot -> {
            ReportRunStore.RunRow run = runs.find(snapshot, id).orElseThrow(RunNotFound::new);
            if (run.status() != RunStatus.FINAL) {
                throw new RunNotFinal();
            }
            ReportDefinition definition = runs.definitionOf(run).orElseThrow(DefinitionNoLongerHeld::new);
            String stored = runs.content(snapshot, id).orElseThrow(
                    () -> new IllegalStateException("a FINAL report run holds its content")).text();
            ReportGeneration.Generated again;
            try {
                again = generation.generate(snapshot, definition, run.inputs());
            } catch (ReportGeneration.PeriodMoved moved) {
                throw new RunNotFinal();
            } catch (ReportRefused refused) {
                // Its definition refuses what it issued: the books moved under a FINAL run. The thing this door exists to find, so a divergence, its reason on the record. Only a refusal: a pin that no longer matches, or a journal whose two reads disagree, is a defect and surfaces as one.
                return new Compared(run, NOT_PRODUCED, false, Optional.of(refused.summary()));
            }
            return new Compared(run, again.sha256(), stored.equals(again.content())
                    && run.contentSha256().filter(again.sha256()::equals).isPresent(), Optional.empty());
        });
    }

    /** Counted once, after the regeneration's record committed. */
    void diverged(ReportRunStore.RunRow run) {
        metrics.diverged(run);
    }

    /** No report run has this id (in a snapshot). */
    static final class RunNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RunNotFound() {
            super("no report run answers this request");
        }
    }

    /** Only a FINAL run, under its lock, is regenerated. */
    static final class RunNotFinal extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RunNotFinal() {
            super("only a FINAL report run, its period still under the lock it was generated in, is regenerated");
        }
    }

    /** The build no longer holds the run's definition version. */
    static final class DefinitionNoLongerHeld extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DefinitionNoLongerHeld() {
            super("this build holds no report definition of the run's code and version");
        }
    }

    private ReportRunStore.RunRow refused(ReportRunStore.RunRow held, ReportRefused refused, Actor actor) {
        ReportRuns.Concluded concluded = transactions.inTransaction(unitOfWork -> runs.refuse(unitOfWork, held.id(),
                held.attempt(), refused.summary(), actor, held.correlation()));
        if (concluded.moved()) {
            metrics.concluded(concluded.run());
            metrics.refused(concluded.run().basis(), refused.reason());
        }
        return concluded.run();
    }

    private ReportRunStore.RunRow failed(ReportRunStore.RunRow held, RunFailure failure, Actor actor) {
        ReportRuns.Concluded concluded = transactions.inTransaction(unitOfWork -> runs.fail(unitOfWork, held.id(),
                held.attempt(), failure, actor, held.correlation()));
        if (concluded.moved()) {
            metrics.concluded(concluded.run());
        }
        return concluded.run();
    }
}
