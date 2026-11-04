package com.finapp.accounting.reporting;

import java.sql.Connection;
import java.util.Set;

/**
 * A report definition - code, versioned (`P14-TSK-008`; ADR-0115 decision 1; {@code PHASE_14_PLAN.md} section 13.6): the
 * function from a run's pinned inputs and a read-only snapshot of the books to the report's body. The trial balance
 * (`P14-TSK-009`), the statements (`-016`) and the rest (`-017`) are implementations; this task builds the machinery and
 * proves it with a test-only definition. A production definition is a Spring bean the registry
 * ({@link ReportDefinitions}) collects.
 *
 * <p><strong>Reads only</strong> ({@code INV-ACC-14}): {@link #generate} runs on a separate connection whose transaction is
 * {@code REPEATABLE READ READ ONLY}, so a write is refused by the database; the definition is handed a
 * {@link ReadOnlySnapshot} view of it, which prepares reads only and refuses every mutator, so a write - or a second
 * statement that would end the transaction - never reaches it; and every implementation is report code in this package or
 * below, so {@code ReportingNeverWritesTest} refuses a writer on its reach before it is ever run.
 *
 * <p><strong>Reproducible</strong> ({@code INV-ACC-04}): the body is a function of {@link ReportInputs} and of what a
 * {@code FINAL} run's locked period makes immutable - the journal lines dated in its range, the pinned mapping version,
 * the pinned rate set - and nothing else: no clock, no random, no iteration order the code does not fix, no figure from a
 * projection, a cache or a gauge ({@code INV-BAL-05}). A change to what a definition produces is a new {@link #version},
 * with the old one kept in the build for regeneration.
 *
 * <p><strong>Or operational, and never a run</strong> (`P14-TSK-017`): a definition reading what keeps moving after a lock
 * says so ({@link #operational()}, {@link OperationalReportDefinition}); it is refused a run and answered live. Every other
 * definition keeps the promise above, and may read nothing that can change after its period's lock.
 */
public interface ReportDefinition {

    /** The definition's code - a {@code ReportKind} name for a production report; {@code ^[A-Z][A-Z0-9_]{2,63}$}. */
    String code();

    /** The definition's version, 1 or more - pinned by every run generated from it. */
    int version();

    /** The parameter names the definition takes; a run asked with any other is refused. None by default. */
    default Set<String> parameterNames() {
        return Set.of();
    }

    /**
     * Whether a run must pin a valuation rate set - a report that exists only translated. Never true of
     * {@code TRIAL_BALANCE}: the close asks it plain.
     */
    default boolean needsValuationRateSet() {
        return false;
    }

    /**
     * Whether a run may pin a valuation rate set and be translated (`P14-TSK-015`; ADR-0116 decision 3): <strong>a run
     * asks for translation by naming its period's approved set</strong> in its parameters. The set is in the parameters'
     * canonical text, so in the run's key: the translated run and the plain one are two questions and two runs. The claim
     * refuses a set that is not the period's approved one, whatever the basis ({@code ReportRuns.ValuationSetNotApproved});
     * the body reads the rates through {@link Translation.Rates#pinnedBy} and {@link Translation} - and, for a run naming
     * none, must write what it always wrote. A definition refusing a set overrides nothing; one that may take it overrides
     * this.
     */
    default boolean takesValuationRateSet() {
        return needsValuationRateSet();
    }

    /**
     * Whether a FINAL run of this definition stands only for a {@code CLOSED} period (`P14-TSK-016`; {@code INV-ACC-04}): it reads what the close itself decides - the
     * close record, born and decided <em>after</em> the lock - so at {@code LOCKED} or {@code CLOSE_PROPOSED} its bytes would still move under the very lock a FINAL run
     * reproduces under. {@code ReportRuns.claim} refuses it there, nothing written. Under {@code CLOSED} the only edge is a reopen, which supersedes the run. False by default.
     */
    default boolean issuedOnlyWhenClosed() {
        return false;
    }

    /**
     * The report's source, rule, date, currency semantics and freshness - abstract, so a definition cannot omit them, and
     * {@link ReportStatement} refuses a fact left empty (`P14-TSK-017`'s acceptance criterion).
     */
    ReportStatement statement();

    /**
     * Whether the report reads state that keeps moving after a period's lock, and so is never FINAL and never a run
     * ({@link OperationalReportDefinition}). False - the promise of a reproducible body - by default.
     */
    default boolean operational() {
        return false;
    }

    /**
     * The report's body for {@code inputs}, read through {@code snapshot} - a {@link ReadOnlySnapshot} view of a read-only
     * {@code REPEATABLE READ} transaction the caller opened, rolls back and never commits: it prepares single
     * {@code SELECT}/{@code WITH} statements and runs them by {@code executeQuery()} within the generation's time budget,
     * and refuses everything else (`P14-TSK-008`'s review, I-1, I-2).
     */
    CanonicalJson.Obj generate(Connection snapshot, ReportInputs inputs);
}
