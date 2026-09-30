package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The one pure decision function (`P8-TSK-011`, ADR-0068 §3): no I/O, no clock, no
 * database. A chunk transaction supplies its inputs — the item's facts, the fired rule and
 * the hits its key reached (already re-read under lock), the pinned tolerance, and whether
 * an identical fingerprint stands earlier in claimant order — and commits what the verdict
 * says. Purity is what makes the shuffled-order property and stored-snapshot replay exact
 * ({@code INV-REC-04} as amended).
 *
 * <p>The verdict never consults live payments state: {@code NO_CANDIDATES} hands the
 * remainder to the caller, whose {@code InternalReferenceLookup} TYPES the break and never
 * chooses an allocation (ADR-0068 §1). When several hit-failures coexist, precedence
 * follows ADR-0069 §2's listed order of the definitive specifics — {@code REVERSAL_MISMATCH}
 * (direction), then {@code CURRENCY_MISMATCH}, then {@code DUPLICATE_EXTERNAL} (exhausted) —
 * a recorded reading of that list.
 */
public final class MatchEngine {

    private MatchEngine() {}

    /** The item, as the chunk read it — keys resolved by the caller, facts frozen here. */
    public record ItemFacts(
            UUID itemId,
            ExternalLineType lineType,
            ExpectationDirection direction,
            Money amount,
            LocalDate businessDate,
            Optional<LocalDate> settlementDate,
            boolean fingerprintSeenEarlier) {

        public ItemFacts {
            Objects.requireNonNull(itemId, "itemId must not be null");
            Objects.requireNonNull(lineType, "lineType must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(businessDate, "businessDate must not be null");
            Objects.requireNonNull(settlementDate, "settlementDate must not be null");
        }

        /** The date timing judges: the counterparty's settlement day, else the business day. */
        public LocalDate effectiveSettlementDate() {
            return settlementDate.orElse(businessDate);
        }
    }

    /**
     * One expectation a key reached, snapshotted under the chunk's lock. {@code reachedBy} is
     * empty exactly for a value-date group's candidate (`P8-TSK-016`, {@link GroupMatch}): it
     * was reached by its date, never by a key, and its snapshot row carries no key kind
     * (`V008`).
     */
    public record HitFacts(
            UUID expectationId,
            ExpectationKind kind,
            ExpectationDirection direction,
            Money amount,
            long remainderMinor,
            Instant openedAt,
            LocalDate expectedBy,
            Optional<KeyKind> reachedBy,
            String operationRef) {

        public HitFacts {
            Objects.requireNonNull(expectationId, "expectationId must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(openedAt, "openedAt must not be null");
            Objects.requireNonNull(expectedBy, "expectedBy must not be null");
            Objects.requireNonNull(reachedBy, "reachedBy must not be null");
            Objects.requireNonNull(operationRef, "operationRef must not be null");
        }

        /** A key's hit — the `P8-TSK-011` shape. */
        public HitFacts(
                UUID expectationId,
                ExpectationKind kind,
                ExpectationDirection direction,
                Money amount,
                long remainderMinor,
                Instant openedAt,
                LocalDate expectedBy,
                KeyKind reachedBy,
                String operationRef) {
            this(expectationId, kind, direction, amount, remainderMinor, openedAt, expectedBy,
                    Optional.of(Objects.requireNonNull(reachedBy, "reachedBy must not be null")),
                    operationRef);
        }
    }

    /** The rule that fired: the first, in priority order, whose key reached anything. */
    public record FiredRule(
            int priority,
            KeyKind keyKind,
            Optional<ExpectationKind> expectationKind,
            Cardinality cardinality,
            int graceHours,
            List<HitFacts> hits) {

        public FiredRule {
            Objects.requireNonNull(keyKind, "keyKind must not be null");
            Objects.requireNonNull(expectationKind, "expectationKind must not be null");
            Objects.requireNonNull(cardinality, "cardinality must not be null");
            Objects.requireNonNull(hits, "hits must not be null");
            hits = List.copyOf(hits);
        }
    }

    /** The verdict's shape — what the chunk transaction must now make true. */
    public enum VerdictKind {
        /** Allocate {@code allocation} to the one candidate; park {@code excess} if any. */
        ALLOCATE,
        /** Two or more candidates: park the whole amount, {@code AMBIGUOUS_MATCH}. */
        AMBIGUOUS,
        /** A hit failed only on direction: park, {@code REVERSAL_MISMATCH}. */
        DIRECTION_CONTRADICTED,
        /** A hit failed only on currency: park, {@code CURRENCY_MISMATCH}. */
        CURRENCY_CONTRADICTED,
        /** The expectation is exhausted, or the fingerprint stands earlier: park,
         * {@code DUPLICATE_EXTERNAL}. */
        DUPLICATE,
        /** No rule's key reached anything: the caller's lookup types waiting or parking. */
        NO_CANDIDATES,
        /** No landed rule serves this line type: wait with no grace clock (`-012`+). */
        NO_RULE
    }

    public record Verdict(
            VerdictKind kind,
            Optional<HitFacts> candidate,
            Optional<Money> allocation,
            Optional<Money> excess,
            Optional<Money> underRemainder,
            Optional<Timing> timing) {

        public record Timing(int deviationDays, int toleranceDays) {}
    }

    /**
     * @param settlementDateToleranceDays the pinned {@code SETTLEMENT_DATE_DAYS} tolerance
     * @param firedRule empty when no rule of the item's line type yielded any hit; the
     *     caller distinguishes "no landed rule" from "keys reached nothing" by
     *     {@code anyLandedRule}
     * @param anyLandedRule whether any landed (ONE_TO_ONE) rule exists for the line type
     */
    public static Verdict decide(
            ItemFacts item,
            Optional<FiredRule> firedRule,
            boolean anyLandedRule,
            int settlementDateToleranceDays) {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(firedRule, "firedRule must not be null");

        // An identical line earlier in claimant order is definitive whatever its keys
        // still reach: the counterparty said the same thing twice (ADR-0068 §4).
        if (item.fingerprintSeenEarlier()) {
            return new Verdict(
                    VerdictKind.DUPLICATE, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (!anyLandedRule) {
            return new Verdict(
                    VerdictKind.NO_RULE, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (firedRule.isEmpty() || firedRule.get().hits().isEmpty()) {
            return new Verdict(
                    VerdictKind.NO_CANDIDATES, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        FiredRule rule = firedRule.get();
        List<HitFacts> candidates =
                rule.hits().stream()
                        .filter(hit ->
                                rule.expectationKind().map(kind -> kind == hit.kind())
                                        .orElse(true))
                        .filter(hit -> hit.direction() == item.direction())
                        .filter(hit ->
                                hit.amount().currency().equals(item.amount().currency()))
                        .filter(hit -> hit.remainderMinor() > 0)
                        .toList();

        if (candidates.size() >= 2) {
            return new Verdict(
                    VerdictKind.AMBIGUOUS, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        if (candidates.isEmpty()) {
            // ADR-0069 §2's listed precedence over the definitive specifics.
            boolean direction =
                    rule.hits().stream()
                            .anyMatch(hit -> hit.direction() != item.direction());
            boolean currency =
                    rule.hits().stream()
                            .anyMatch(hit ->
                                    !hit.amount().currency()
                                            .equals(item.amount().currency()));
            boolean exhausted =
                    rule.hits().stream().anyMatch(hit -> hit.remainderMinor() == 0);
            VerdictKind kind =
                    direction
                            ? VerdictKind.DIRECTION_CONTRADICTED
                            : currency
                                    ? VerdictKind.CURRENCY_CONTRADICTED
                                    : exhausted
                                            ? VerdictKind.DUPLICATE
                                            : VerdictKind.NO_CANDIDATES;
            return new Verdict(
                    kind, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty());
        }

        HitFacts candidate = candidates.get(0);
        long allocatedMinor = Math.min(item.amount().minorUnits(), candidate.remainderMinor());
        Money allocation =
                Money.ofPersisted(
                        allocatedMinor, item.amount().currency(), item.amount().scale());
        Optional<Money> excess =
                item.amount().minorUnits() > candidate.remainderMinor()
                        ? Optional.of(
                                Money.ofPersisted(
                                        item.amount().minorUnits()
                                                - candidate.remainderMinor(),
                                        item.amount().currency(),
                                        item.amount().scale()))
                        : Optional.empty();
        Optional<Money> underRemainder =
                item.amount().minorUnits() < candidate.remainderMinor()
                        ? Optional.of(
                                Money.ofPersisted(
                                        candidate.remainderMinor()
                                                - item.amount().minorUnits(),
                                        item.amount().currency(),
                                        item.amount().scale()))
                        : Optional.empty();

        // Timing is judged on every match, in stored dates only (INV-SET-03: late is a
        // zero-value observation, never a refusal).
        long deviation =
                java.time.temporal.ChronoUnit.DAYS.between(
                        candidate.expectedBy(), item.effectiveSettlementDate());
        Optional<Verdict.Timing> timing =
                deviation > settlementDateToleranceDays
                        ? Optional.of(
                                new Verdict.Timing(
                                        (int) deviation, settlementDateToleranceDays))
                        : Optional.empty();

        return new Verdict(
                VerdictKind.ALLOCATE,
                Optional.of(candidate),
                Optional.of(allocation),
                excess,
                underRemainder,
                timing);
    }
}
