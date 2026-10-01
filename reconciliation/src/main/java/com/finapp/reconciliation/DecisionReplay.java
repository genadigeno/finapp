package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Decision replay, pure (`P8-TSK-022`, ADR-0068 §9.1; `INV-REC-04`, `INV-HIST-04`): one stored
 * decision's snapshot - what it judged, the rule it fired under its pinned version, every
 * candidate and parked original it saw - re-run through the pure function its verdict names,
 * and the conclusion compared with what was stored: the verdict, the allocations and, where the
 * pure function decides it, the outcome.
 *
 * <p><strong>What is re-run is the function, not the world.</strong> Which candidates a decision
 * saw depended on what had been recorded when it ran; that is why the snapshot is stored, and
 * why a perturbed function - a strategy constant changed - DIVERGES here while the world's
 * later state cannot. A person's {@code MANUAL_CHOICE} replays as the choice applied to its
 * snapshot: the chosen expectation was a candidate, and the allocation is the one its
 * cardinality gives. A contained {@code ERRORED} decision, and one written before `V012` stored
 * a verdict, have nothing pure to re-run and are counted apart, never as divergence.
 */
public final class DecisionReplay {

    private DecisionReplay() {}

    /** The rule a decision fired, read back from its pinned (immutable) version. */
    public record RuleFacts(
            int priority,
            Optional<KeyKind> keyKind,
            Optional<ExpectationKind> expectationKind,
            Cardinality cardinality,
            int graceHours) {

        public RuleFacts {
            Objects.requireNonNull(keyKind, "keyKind must not be null");
            Objects.requireNonNull(expectationKind, "expectationKind must not be null");
            Objects.requireNonNull(cardinality, "cardinality must not be null");
        }
    }

    /** One stored positive allocation: the expectation and the minor units. */
    public record Allocation(UUID expectationId, long amountMinor) {}

    /** A fee check's pinned inputs: the schedule, the stored tolerance and expected fee. */
    public record FeeFacts(
            Optional<FeeCheck.Schedule> schedule,
            long toleranceMinor,
            Optional<Long> grossMinor,
            long storedExpectedMinor) {

        public FeeFacts {
            Objects.requireNonNull(schedule, "schedule must not be null");
            Objects.requireNonNull(grossMinor, "grossMinor must not be null");
        }
    }

    /** One decision as stored - everything its replay reads, nothing it does not. */
    public record StoredDecision(
            UUID decisionId,
            DecisionOrigin origin,
            Optional<DecisionVerdict> verdict,
            Optional<JudgedStatus> judgedStatus,
            long judgedMinor,
            DecisionOutcome outcome,
            MatchEngine.ItemFacts item,
            LocalDate groupDate,
            Optional<RuleFacts> rule,
            boolean anyLandedRule,
            int settlementDateToleranceDays,
            List<MatchEngine.HitFacts> candidates,
            List<CorrectionEngine.ParkedOriginal> parkedOriginals,
            Optional<Boolean> groupMembershipComplete,
            Optional<FeeFacts> fee,
            List<Allocation> allocations) {

        public StoredDecision {
            Objects.requireNonNull(decisionId, "decisionId must not be null");
            Objects.requireNonNull(origin, "origin must not be null");
            Objects.requireNonNull(verdict, "verdict must not be null");
            Objects.requireNonNull(judgedStatus, "judgedStatus must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(item, "item must not be null");
            Objects.requireNonNull(groupDate, "groupDate must not be null");
            Objects.requireNonNull(rule, "rule must not be null");
            Objects.requireNonNull(groupMembershipComplete, "groupMembershipComplete must not be null");
            Objects.requireNonNull(fee, "fee must not be null");
            candidates = List.copyOf(candidates);
            parkedOriginals = List.copyOf(parkedOriginals);
            allocations = List.copyOf(allocations);
        }
    }

    public enum Result {
        IDENTICAL,
        DIVERGED,
        NOT_REPLAYED
    }

    /** One decision's replay: the result, and in words what differed (never an amount). */
    public record Replayed(UUID decisionId, Result result, String detail) {}

    /** What the pure function concludes from the snapshot. */
    private record Conclusion(
            DecisionVerdict verdict,
            List<Allocation> allocations,
            Set<DecisionOutcome> outcomes,
            Optional<String> mismatch) {

        Conclusion(
                DecisionVerdict verdict,
                List<Allocation> allocations,
                Set<DecisionOutcome> outcomes) {
            this(verdict, allocations, outcomes, Optional.empty());
        }
    }

    public static Replayed replay(StoredDecision stored) {
        Objects.requireNonNull(stored, "stored must not be null");
        if (stored.verdict().isEmpty() || stored.judgedStatus().isEmpty()) {
            return new Replayed(stored.decisionId(), Result.NOT_REPLAYED, "no stored verdict");
        }
        DecisionVerdict verdict = stored.verdict().get();
        Conclusion conclusion;
        switch (verdict.engine()) {
            case MATCH -> conclusion = matchEngine(stored);
            case GROUP -> conclusion = group(stored);
            case CORRECTION -> conclusion = correction(stored);
            case FEE -> conclusion = fee(stored);
            case MANUAL -> conclusion = manual(stored);
            case CONTAINED -> {
                return new Replayed(stored.decisionId(), Result.NOT_REPLAYED, "contained");
            }
            default -> throw new IllegalStateException("unhandled engine " + verdict.engine());
        }
        if (conclusion.mismatch().isPresent()) {
            return diverged(stored, conclusion.mismatch().get());
        }
        if (conclusion.verdict() != verdict) {
            return diverged(stored, "verdict " + verdict + " replays as " + conclusion.verdict());
        }
        if (!sameAllocations(conclusion.allocations(), stored.allocations())) {
            return diverged(stored, "the allocations differ");
        }
        if (!conclusion.outcomes().contains(stored.outcome())) {
            return diverged(
                    stored, "outcome " + stored.outcome() + " is not " + conclusion.outcomes());
        }
        return new Replayed(stored.decisionId(), Result.IDENTICAL, "");
    }

    private static Replayed diverged(StoredDecision stored, String detail) {
        return new Replayed(stored.decisionId(), Result.DIVERGED, detail);
    }

    // ------------------------------------------------------------------ the engines

    private static Conclusion matchEngine(StoredDecision stored) {
        Optional<MatchEngine.FiredRule> fired =
                stored.rule()
                        .filter(rule -> rule.keyKind().isPresent())
                        .map(rule ->
                                new MatchEngine.FiredRule(
                                        rule.priority(),
                                        rule.keyKind().get(),
                                        rule.expectationKind(),
                                        rule.cardinality(),
                                        rule.graceHours(),
                                        stored.candidates()));
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        stored.item(), fired, stored.anyLandedRule(),
                        stored.settlementDateToleranceDays());
        DecisionVerdict concluded = DecisionVerdict.of(verdict.kind());
        JudgedStatus judged = stored.judgedStatus().orElseThrow();
        if (verdict.kind() != MatchEngine.VerdictKind.ALLOCATE) {
            return new Conclusion(concluded, List.of(), unchangedOutcomes(stored, judged));
        }
        MatchEngine.HitFacts candidate = verdict.candidate().orElseThrow();
        if (judged == JudgedStatus.PARKED) {
            // A parked value is allocated whole or not at all (the design's G2 refinement).
            boolean whole =
                    candidate.remainderMinor() >= stored.judgedMinor()
                            && candidate.amount().currency()
                                    .equals(stored.item().amount().currency());
            return whole
                    ? new Conclusion(
                            concluded,
                            List.of(new Allocation(candidate.expectationId(), stored.judgedMinor())),
                            EnumSet.of(DecisionOutcome.MATCHED))
                    : new Conclusion(concluded, List.of(), EnumSet.of(DecisionOutcome.PARKED));
        }
        return new Conclusion(
                concluded,
                List.of(
                        new Allocation(
                                candidate.expectationId(),
                                verdict.allocation().map(Money::minorUnits).orElseThrow())),
                verdict.excess().isPresent()
                        ? EnumSet.of(DecisionOutcome.PARKED)
                        : EnumSet.of(DecisionOutcome.MATCHED));
    }

    private static Conclusion group(StoredDecision stored) {
        GroupMatch.Verdict verdict =
                GroupMatch.decide(
                        stored.item(),
                        stored.groupDate(),
                        stored.rule().flatMap(RuleFacts::expectationKind),
                        stored.candidates(),
                        stored.groupMembershipComplete().orElseThrow());
        DecisionVerdict concluded = DecisionVerdict.of(verdict.kind());
        if (verdict.kind() == GroupMatch.Kind.MATCH) {
            // Untouched by construction: each member allocated its whole remainder.
            return new Conclusion(
                    concluded,
                    verdict.candidates().stream()
                            .map(member ->
                                    new Allocation(member.expectationId(), member.remainderMinor()))
                            .toList(),
                    EnumSet.of(DecisionOutcome.MATCHED));
        }
        return new Conclusion(
                concluded, List.of(),
                unchangedOutcomes(stored, stored.judgedStatus().orElseThrow()));
    }

    private static Conclusion correction(StoredDecision stored) {
        CorrectionEngine.Verdict verdict =
                CorrectionEngine.decide(
                        stored.item(), stored.candidates(), stored.parkedOriginals());
        DecisionVerdict concluded = DecisionVerdict.of(verdict.kind());
        return switch (verdict.kind()) {
            case TOP_UP ->
                    new Conclusion(
                            concluded,
                            List.of(
                                    new Allocation(
                                            verdict.candidate().orElseThrow().expectationId(),
                                            verdict.allocation().orElseThrow().minorUnits())),
                            verdict.excess().isPresent()
                                    ? EnumSet.of(DecisionOutcome.PARKED)
                                    : EnumSet.of(DecisionOutcome.MATCHED));
            case OFFSET -> new Conclusion(concluded, List.of(), EnumSet.of(DecisionOutcome.OFFSET));
            case UNREACHED ->
                    new Conclusion(
                            concluded, List.of(),
                            EnumSet.of(DecisionOutcome.UNMATCHED, DecisionOutcome.PARKED));
        };
    }

    private static Conclusion fee(StoredDecision stored) {
        FeeFacts fee =
                stored.fee()
                        .orElseThrow(() -> new IllegalStateException("a fee decision's facts"));
        Money reported = stored.item().amount();
        FeeCheck.Verdict verdict =
                FeeCheck.check(
                        reported,
                        fee.grossMinor()
                                .map(minor ->
                                        Money.ofPersisted(
                                                minor, reported.currency(), reported.scale())),
                        fee.schedule(),
                        fee.toleranceMinor());
        // The expected fee is the conclusion's own number: one priced differently diverges even
        // when it lands on the same side of the tolerance.
        return new Conclusion(
                DecisionVerdict.fee(verdict.beyondTolerance()),
                List.of(),
                EnumSet.of(DecisionOutcome.CHECKED),
                verdict.expectedMinor() == fee.storedExpectedMinor()
                        ? Optional.empty()
                        : Optional.of("the expected fee is priced differently"));
    }

    private static Conclusion manual(StoredDecision stored) {
        // The person's choice is the stored allocation; replay proves it was a candidate and
        // that the amount is the one ONE_TO_ONE gives over the judged parked value.
        if (stored.allocations().size() != 1) {
            return new Conclusion(null, List.of(), EnumSet.noneOf(DecisionOutcome.class));
        }
        UUID chosen = stored.allocations().get(0).expectationId();
        Optional<MatchEngine.HitFacts> candidate =
                stored.candidates().stream()
                        .filter(hit -> hit.expectationId().equals(chosen))
                        .findFirst();
        if (candidate.isEmpty()) {
            return new Conclusion(null, List.of(), EnumSet.noneOf(DecisionOutcome.class));
        }
        long amount = Math.min(stored.judgedMinor(), candidate.get().remainderMinor());
        return new Conclusion(
                DecisionVerdict.MANUAL_CHOICE,
                List.of(new Allocation(chosen, amount)),
                EnumSet.of(DecisionOutcome.MATCHED));
    }

    /**
     * The outcomes a decision that allocated nothing may carry. The pure function does not
     * decide whether a line with no candidate waits or is typed and parked - the lookup does,
     * after grace - so both are admitted where the leg could reach either; a reprocess
     * examination leaves the item exactly as it judged it.
     */
    private static Set<DecisionOutcome> unchangedOutcomes(
            StoredDecision stored, JudgedStatus judged) {
        if (stored.origin() == DecisionOrigin.REPROCESS || stored.origin() == DecisionOrigin.REMATCH) {
            return EnumSet.of(
                    judged == JudgedStatus.PARKED
                            ? DecisionOutcome.PARKED
                            : DecisionOutcome.UNMATCHED);
        }
        DecisionVerdict verdict = stored.verdict().orElseThrow();
        return switch (verdict) {
            case AMBIGUOUS, DIRECTION_CONTRADICTED, CURRENCY_CONTRADICTED, DUPLICATE ->
                    EnumSet.of(DecisionOutcome.PARKED);
            default -> EnumSet.of(DecisionOutcome.UNMATCHED, DecisionOutcome.PARKED);
        };
    }

    private static boolean sameAllocations(List<Allocation> concluded, List<Allocation> stored) {
        return tally(concluded).equals(tally(stored));
    }

    private static Map<UUID, Long> tally(List<Allocation> allocations) {
        return allocations.stream()
                .collect(Collectors.groupingBy(
                        Allocation::expectationId,
                        TreeMap::new,
                        Collectors.summingLong(Allocation::amountMinor)));
    }

    /** The first divergent decision of a list replayed in order, if any. */
    public static Optional<UUID> firstDivergent(List<Replayed> replayed) {
        return replayed.stream()
                .filter(one -> one.result() == Result.DIVERGED)
                .findFirst()
                .map(Replayed::decisionId);
    }
}
