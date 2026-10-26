package com.finapp.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Why a sealed decision is what it is (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 9, 12.3; ADR-0108 decisions 3-4,
 * ADR-0109 decision 3; {@code INV-RSK-04}; {@code DESIGN-023} D6) - the explanation door's content, built <strong>from
 * the sealed rows only</strong>: the assessment's hits, score, decision, fail-safe cause and reason, challenge answer and
 * velocity outcome, and the pinned version's band list - which is immutable from its proposal on (`INV-RSK-05`). The
 * engine is never run again here: an explanation that re-evaluated would explain what the engine says <em>now</em>, not
 * what was decided - and would hide exactly the divergence the replay exists to find.
 *
 * <ul>
 *   <li><strong>The rules</strong> in their declared order: each one's outcome, the reading it judged (a token, never a
 *       threshold), its contribution and its hard decision when it fired.</li>
 *   <li><strong>The band reached</strong> - the pinned version's score band covering the sealed score, named <em>by its
 *       position and its decision, never its bounds</em>: a threshold is the policy's {@code CONFIDENTIAL} content, read
 *       by its administrators' door alone.</li>
 *   <li><strong>The step that decided</strong> ({@link Step}): the fail-safe; a fired rule's hard decision stricter than
 *       the band's (ADR-0108: a hard rule beats a score band) - with the rules that carried it; or the band.</li>
 *   <li><strong>The fail-safe</strong>, when it answered: its cause, its reason and its band - the pinned version's
 *       fail-safe band for the sealed amount by position (the open last band for an amount in another currency), or none
 *       when no version could be pinned and the compiled {@code ALLOW} answered.</li>
 * </ul>
 *
 * A band that cannot be read (the pinned version unreadable) is absent, never guessed.
 */
public record Explanation(
        RiskAssessmentId assessment,
        RiskOperation operation,
        RiskEngine engine,
        Optional<RiskAssessment.Pin> pin,
        List<Rule> rules,
        int score,
        Optional<Band> band,
        Step step,
        List<Integer> decidingRules,
        RiskDecision decision,
        Optional<FailSafe> failSafe,
        Optional<Boolean> challengeSatisfied,
        boolean permits,
        VelocityOutcome velocity) {

    /** Which precedence step of the decision model decided. */
    public enum Step {
        /** The evaluation could not run; the recorded fail-safe answered (ADR-0108 decision 4). */
        FAIL_SAFE,
        /** A fired rule's hard decision, stricter than the band's, decided (ADR-0108 decision 3). */
        HARD_RULE,
        /** The score band decided - no fired hard decision was stricter. */
        SCORE_BAND
    }

    /** One rule as it was judged. */
    public record Rule(int ordinal, RuleKind kind, RuleOutcome outcome, String judged, int contribution,
            Optional<RiskDecision> hardDecision) {
        public Rule {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(judged, "judged");
            Objects.requireNonNull(hardDecision, "hardDecision");
        }
    }

    /** A band by its position among its version's bands (1-based) and its decision - never its bounds. */
    public record Band(int position, int of, RiskDecision decision) {
        public Band {
            Objects.requireNonNull(decision, "decision");
            if (position < 1 || position > of) {
                throw new IllegalArgumentException("a band's position is 1 to the bands' count");
            }
        }
    }

    /** The fail-safe that answered: why, under which reason, and its band when a version was pinned. */
    public record FailSafe(UnavailableCause cause, ReasonCode reason, Optional<Band> band) {
        public FailSafe {
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(band, "band");
        }
    }

    public Explanation {
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(pin, "pin");
        rules = List.copyOf(rules);
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(step, "step");
        decidingRules = List.copyOf(decidingRules);
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(failSafe, "failSafe");
        Objects.requireNonNull(challengeSatisfied, "challengeSatisfied");
        Objects.requireNonNull(velocity, "velocity");
        if ((step == Step.FAIL_SAFE) != failSafe.isPresent()) {
            throw new IllegalArgumentException("the fail-safe decided exactly when it answered");
        }
        if ((step == Step.HARD_RULE) == decidingRules.isEmpty()) {
            throw new IllegalArgumentException("a hard rule decided exactly when the rules that carried it are named");
        }
    }

    /**
     * {@code sealed} explained, with {@code pinned} the content of the version it pinned when that could be read - and
     * nothing else: no engine, no clock, no live table.
     */
    public static Explanation of(RiskAssessment sealed, Optional<RiskPolicy> pinned) {
        Objects.requireNonNull(sealed, "sealed");
        Objects.requireNonNull(pinned, "pinned");
        List<Rule> rules = new ArrayList<>(sealed.hits().size());
        for (RuleHit hit : sealed.hits()) {
            rules.add(new Rule(hit.ordinal(), hit.kind(), hit.outcome(), hit.judged(), hit.contribution(),
                    hit.hardDecision()));
        }
        RiskDecision decision = sealed.decision();
        if (sealed.unavailable().isPresent()) {
            UnavailableCause cause = sealed.unavailable().get();
            Optional<Band> band = cause.unpinned() ? Optional.empty()
                    : pinned.map(policy -> failSafeBand(policy, sealed));
            return new Explanation(sealed.id(), sealed.operation(), sealed.engine(), sealed.pin(), rules,
                    sealed.score().value(), Optional.empty(), Step.FAIL_SAFE, List.of(), decision,
                    Optional.of(new FailSafe(cause, cause.reason(), band)), sealed.challengeSatisfied(),
                    sealed.permits(), sealed.velocity());
        }
        Optional<Band> band = pinned.flatMap(policy -> scoreBand(policy, sealed.score().value()));
        List<Integer> deciding = new ArrayList<>();
        for (RuleHit hit : sealed.hits()) {
            if (hit.fired() && hit.hardDecision().filter(decision::equals).isPresent()) {
                deciding.add(hit.ordinal());
            }
        }
        boolean hardDecided = !deciding.isEmpty() && band.map(reached -> reached.decision() != decision
                && RiskDecision.strictest(reached.decision(), decision) == decision).orElse(true);
        return new Explanation(sealed.id(), sealed.operation(), sealed.engine(), sealed.pin(), rules,
                sealed.score().value(), band, hardDecided ? Step.HARD_RULE : Step.SCORE_BAND,
                hardDecided ? deciding : List.of(), decision, Optional.empty(), sealed.challengeSatisfied(),
                sealed.permits(), sealed.velocity());
    }

    /** The score band covering {@code score} by position - none when the version's bands do not cover it. */
    static Optional<Band> scoreBand(RiskPolicy policy, int score) {
        List<ScoreBand> bands = policy.scoreBands();
        for (int i = 0; i < bands.size(); i++) {
            if (bands.get(i).covers(score)) {
                return Optional.of(new Band(i + 1, bands.size(), bands.get(i).decision()));
            }
        }
        return Optional.empty();
    }

    /**
     * The fail-safe band the sealed amount falls in by position - the open last band for an amount in another currency
     * than the version's, which the bands cannot place (`RiskEngineV1.failSafeUnplaced`'s reading).
     */
    static Band failSafeBand(RiskPolicy policy, RiskAssessment sealed) {
        List<FailSafeBand> bands = policy.failSafeBands();
        if (sealed.amount().currency().equals(policy.currency())) {
            for (int i = 0; i < bands.size(); i++) {
                if (bands.get(i).covers(sealed.amount().minorUnits())) {
                    return new Band(i + 1, bands.size(), bands.get(i).decision());
                }
            }
        }
        return new Band(bands.size(), bands.size(), bands.get(bands.size() - 1).decision());
    }

    @Override
    public String toString() {
        return "Explanation[" + assessment.value() + ", " + operation + ", " + decision + " by " + step + "]";
    }
}
