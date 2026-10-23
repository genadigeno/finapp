package com.finapp.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the engine answered (`P13-TSK-003`; {@code PHASE_13_PLAN.md} section 12.3; ADR-0108, ADR-0109;
 * {@code INV-RSK-04}): the engine version, every rule's {@link RuleHit hit} in declared order, the {@link Score}, the
 * decision - for a judged operation, one the operation admits - or, for the {@code MONITORING} version, the rating
 * its rating bands give, the ordered reason codes and whether this is a recorded fail-safe. The content of `-004`'s
 * assessment, not its row; a value, immutable, canonically serialisable ({@link CanonicalEvaluation}).
 *
 * <p><strong>Held together by construction.</strong> A judged operation's verdict has a decision the operation
 * admits and no rating; the sweep's has a rating (never {@code PROHIBITED}, which only a disposition sets) and no
 * decision. A fail-safe verdict has no hits, score 0 and exactly one {@link ReasonCode.Source#FAIL_SAFE} reason;
 * every other verdict's reasons are exactly the fired hits' codes in hit order. {@link ReasonCode#CHALLENGE_REQUIRED}
 * is the seam's reason and never a verdict's.
 */
public record Verdict(
        RiskOperation operation,
        RiskEngine engine,
        List<RuleHit> hits,
        Score score,
        Optional<RiskDecision> decision,
        Optional<RiskRating> rating,
        List<ReasonCode> reasons,
        boolean failSafeApplied) {

    public Verdict {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(hits, "hits");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(rating, "rating");
        Objects.requireNonNull(reasons, "reasons");
        hits = List.copyOf(hits);
        reasons = List.copyOf(reasons);
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).ordinal() != i + 1) {
                throw new IllegalArgumentException("the hits are the rules in declared order, numbered from 1");
            }
        }
        boolean monitoring = operation == RiskOperation.MONITORING;
        if (monitoring) {
            if (decision.isPresent() || rating.isEmpty()) {
                throw new IllegalArgumentException("the sweep's verdict is a rating, never a decision");
            }
            if (rating.get().onlyByDisposition()) {
                throw new IllegalArgumentException("a rating band never sets " + rating.get());
            }
            if (failSafeApplied) {
                throw new IllegalArgumentException("the sweep has no fail-safe: it decides no movement");
            }
        } else {
            if (decision.isEmpty() || rating.isPresent()) {
                throw new IllegalArgumentException(operation + "'s verdict is a decision, never a rating");
            }
            if (!decision.get().isAdmittedBy(operation)) {
                throw new IllegalArgumentException(operation + " does not admit " + decision.get());
            }
        }
        if (failSafeApplied) {
            if (!hits.isEmpty() || score.value() != 0) {
                throw new IllegalArgumentException("a fail-safe verdict evaluated no rule");
            }
            if (reasons.size() != 1 || reasons.get(0).source() != ReasonCode.Source.FAIL_SAFE) {
                throw new IllegalArgumentException("a fail-safe verdict carries exactly its fail-safe reason");
            }
        } else {
            List<ReasonCode> expected = new ArrayList<>();
            for (RuleHit hit : hits) {
                if (hit.fired()) {
                    expected.add(hit.reason());
                }
            }
            if (!expected.equals(reasons)) {
                throw new IllegalArgumentException("the reasons are the fired rules' codes in evaluation order");
            }
            if (!Score.of(hits).equals(score)) {
                throw new IllegalArgumentException("the score is the sum of the fired contributions, clamped");
            }
        }
    }

    /** The hits that fired, in order. */
    public List<RuleHit> fired() {
        return hits.stream().filter(RuleHit::fired).toList();
    }

    @Override
    public String toString() {
        return "Verdict[" + operation + ", " + engine + ", " + (decision.isPresent() ? decision.get() : rating.get())
                + (failSafeApplied ? ", fail-safe" : "") + "]";
    }
}
