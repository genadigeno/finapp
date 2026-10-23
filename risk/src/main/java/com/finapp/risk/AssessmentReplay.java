package com.finapp.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A sealed assessment replayed (`P13-TSK-004`; {@code PHASE_13_PLAN.md} section 9; ADR-0109 decision 4;
 * {@code INV-RSK-01}; credit's {@code DecisionReplayer} and bnpl's {@code BnplReplayProof} shape): the engine the
 * assessment names, run again over its <strong>sealed</strong> inputs - parsed strictly from their canonical text,
 * never read from a live table - under its <strong>pinned</strong> policy version, and compared with what was sealed.
 * {@code IDENTICAL} when the replayed verdict renders to the sealed digest and equals the sealed rows; otherwise
 * {@code DIVERGED} with the kinds that diverged and the first rule that did - never a figure. A fail-safe replays too:
 * its band is re-derived from the pinned version and the sealed amount by the cause the row records.
 *
 * <p>Pure and stateless: the caller reads the assessment and the pinned version (on one snapshot) and records the
 * replay. A {@code DIVERGED} is a defect of the engine or a corrupted row - the policy is pinned by id and never
 * changes - and is reported, never repaired.
 */
public final class AssessmentReplay {

    private AssessmentReplay() {}

    /** The replay's verdict. */
    public enum Outcome {
        IDENTICAL,
        DIVERGED
    }

    /** What diverged - a kind, never a figure. */
    public enum Divergence {
        /** The sealed inputs text does not parse as the canonical form it claims to be. */
        INPUTS,
        /** The pinned policy version cannot be read. */
        POLICY,
        /** The engine refused the sealed inputs it once judged. */
        EVALUATION,
        /** The rows do not re-render to the sealed verdict's digest. */
        SEAL,
        DECISION,
        SCORE,
        HITS,
        REASONS,
        FAIL_SAFE,
        CHALLENGE
    }

    /** One replay's outcome. */
    public record Replay(RiskAssessmentId assessment, Outcome verdict, List<Divergence> diverged,
            OptionalInt firstDivergedRule) {
        public Replay {
            Objects.requireNonNull(assessment, "assessment");
            Objects.requireNonNull(verdict, "verdict");
            diverged = List.copyOf(Objects.requireNonNull(diverged, "diverged"));
            Objects.requireNonNull(firstDivergedRule, "firstDivergedRule");
            if ((verdict == Outcome.IDENTICAL) != diverged.isEmpty()) {
                throw new IllegalArgumentException("a replay is IDENTICAL exactly when nothing diverged");
            }
        }

        @Override
        public String toString() {
            return "Replay[" + assessment.value() + ", " + verdict + ", " + diverged + "]";
        }
    }

    /** {@code sealed} replayed under {@code pinned} - its pinned version's content, when it names one and it is read. */
    public static Replay replay(RiskAssessment sealed, Optional<RiskPolicy> pinned) {
        Objects.requireNonNull(sealed, "sealed");
        Objects.requireNonNull(pinned, "pinned");
        EvaluationInputs inputs;
        try {
            inputs = CanonicalEvaluation.parseInputs(sealed.inputsCanonical());
        } catch (IllegalArgumentException unreadable) {
            return diverged(sealed, List.of(Divergence.INPUTS), OptionalInt.empty());
        }
        if (sealed.pin().isPresent() && pinned.isEmpty()) {
            return diverged(sealed, List.of(Divergence.POLICY), OptionalInt.empty());
        }
        Verdict verdict;
        Optional<Boolean> challenge = Optional.empty();
        try {
            verdict = switch (sealed.engine()) {
                case RISK_ENGINE_V1 -> v1(sealed, inputs, pinned);
            };
        } catch (EvaluationFailed refused) {
            return diverged(sealed, List.of(Divergence.EVALUATION), OptionalInt.empty());
        }
        if (!verdict.failSafeApplied() && pinned.isPresent()) {
            challenge = RiskEvaluation.challenge(verdict.decision().orElseThrow(), inputs, pinned.get());
        }
        List<Divergence> diverged = new ArrayList<>();
        OptionalInt first = OptionalInt.empty();
        Verdict rows;
        try {
            rows = sealed.verdict();
        } catch (IllegalArgumentException incoherent) {
            // The rows are no verdict at all - hits and reasons that disagree: the seal cannot hold.
            return diverged(sealed, List.of(Divergence.SEAL), OptionalInt.empty());
        }
        if (!CanonicalEvaluation.sha256(CanonicalEvaluation.render(rows)).equals(sealed.verdictSha256())) {
            diverged.add(Divergence.SEAL);
        }
        if (!verdict.decision().equals(rows.decision())) {
            diverged.add(Divergence.DECISION);
        }
        if (!verdict.score().equals(rows.score())) {
            diverged.add(Divergence.SCORE);
        }
        if (!verdict.hits().equals(rows.hits())) {
            diverged.add(Divergence.HITS);
            first = firstDifference(verdict.hits(), rows.hits());
        }
        if (!verdict.reasons().equals(rows.reasons())) {
            diverged.add(Divergence.REASONS);
        }
        if (verdict.failSafeApplied() != rows.failSafeApplied()) {
            diverged.add(Divergence.FAIL_SAFE);
        }
        if (!challenge.equals(sealed.challengeSatisfied())) {
            diverged.add(Divergence.CHALLENGE);
        }
        if (diverged.isEmpty()
                && !CanonicalEvaluation.sha256(CanonicalEvaluation.render(verdict)).equals(sealed.verdictSha256())) {
            diverged.add(Divergence.SEAL);
        }
        return new Replay(sealed.id(), diverged.isEmpty() ? Outcome.IDENTICAL : Outcome.DIVERGED, diverged, first);
    }

    /** {@code RISK_ENGINE_V1}'s verdict for the sealed row: the evaluation, or the fail-safe its cause names. */
    private static Verdict v1(RiskAssessment sealed, EvaluationInputs inputs,
            Optional<RiskPolicy> pinned) {
        if (sealed.unavailable().isEmpty()) {
            return RiskEngineV1.evaluate(pinned.orElseThrow(), inputs);
        }
        return switch (sealed.unavailable().get()) {
            case NO_POLICY -> RiskEngineV1.failSafe(sealed.operation());
            case PIN_UNREADABLE -> RiskEngineV1.failSafeUnpinned(sealed.operation());
            case READ_FAILED, TIMEOUT, ENGINE_REFUSED -> RiskEvaluation.band(pinned.orElseThrow(),
                    inputs.amount().value().orElseThrow());
        };
    }

    private static OptionalInt firstDifference(List<RuleHit> replayed, List<RuleHit> rows) {
        int shorter = Math.min(replayed.size(), rows.size());
        for (int i = 0; i < shorter; i++) {
            if (!replayed.get(i).equals(rows.get(i))) {
                return OptionalInt.of(i + 1);
            }
        }
        return OptionalInt.of(shorter + 1);
    }

    private static Replay diverged(RiskAssessment sealed, List<Divergence> kinds, OptionalInt first) {
        return new Replay(sealed.id(), Outcome.DIVERGED, kinds, first);
    }
}
