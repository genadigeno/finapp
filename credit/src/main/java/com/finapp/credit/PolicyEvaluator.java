package com.finapp.credit;

/**
 * An evaluation engine (`P10-TSK-013`; PHASE_10_PLAN.md section 12.6, {@code INV-CRD-01}): a pure function of one
 * snapshot, its assessment and its pinned policy. It reads no clock, no locale, no time zone and no hash ordering, so
 * one input concludes one result on every instance, today and at any replay.
 *
 * <p>Each engine is one fixed set of semantics - the operators, the severity order, the dedup rule, the handling of a
 * value the snapshot holds {@code ABSENT}. Changing any of them is a new engine with a new version, registered beside
 * the old in {@link EngineVersions}; an engine is never edited once a decision has been made under it.
 */
public interface PolicyEvaluator {

    /** The version every result of this engine records. */
    int engineVersion();

    /**
     * Evaluates {@code policy} over {@code snapshot} and {@code assessment}.
     *
     * @throws MissingAttributeException when a rule reads an attribute the snapshot was never given - an evaluation
     *     error, never a default ({@code INV-CRD-07})
     * @throws IllegalArgumentException when the three do not belong together - another product, another snapshot,
     *     another pinned version
     */
    EvaluationResult evaluate(SnapshotContent snapshot, CreditAssessment assessment, CreditPolicy policy);
}
