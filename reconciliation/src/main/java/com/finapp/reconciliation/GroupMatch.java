package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code GROUP_BY_VALUE_DATE} cardinality's pure seat (`P8-TSK-016`, ADR-0068 §3): no I/O,
 * no clock, no database — {@link MatchEngine#decide}'s sibling. A bank line whose reference
 * reached NO expectation in its key scope (never one whose reference hit something exhausted
 * or contradicted — that is the engine's own verdict) is judged against every untouched
 * expectation of the rule's kind that its attributed source promised for the line's value
 * date: it matches iff the line equals their total EXACTLY, and then discharges each of them
 * whole. There is no subset search — a total that differs by any amount is a wait, never a
 * partial choice ({@code INV-REC-08}: no amount tolerance exists).
 *
 * <p>The caller supplies the candidates already re-read under its locks, with any a prior
 * claimant in the same transaction touched showing their diminished remainder, and whether
 * the membership it locked is still the whole membership. The verdict is decided over the
 * candidates in id order, so the snapshot replays exactly ({@code INV-REC-04} as amended).
 */
public final class GroupMatch {

    private GroupMatch() {}

    public enum Kind {
        /** The line equals the candidates' total: allocate each candidate's whole amount. */
        MATCH,
        /** No untouched candidate stands on the date: the lookup types the remainder. */
        NO_CANDIDATES,
        /** Candidates stand, but their total is not the line's: the line waits. */
        TOTAL_DIFFERS,
        /**
         * A candidate committed after the caller's lock-free read, so the locked set is not
         * the whole group and cannot be judged: the line waits for a later judgement.
         */
        MEMBERSHIP_MOVED
    }

    /**
     * @param candidates the screened candidates in id order — the snapshot the decision
     *     stores, whatever the verdict
     * @param total their total, when any stands
     */
    public record Verdict(Kind kind, List<MatchEngine.HitFacts> candidates, Optional<Money> total) {

        public Verdict {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(candidates, "candidates must not be null");
            Objects.requireNonNull(total, "total must not be null");
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * @param groupDate the date the group is judged on: the line's value date, else its
     *     business day
     * @param expectationKind the rule's kind; empty admits any (v1's rules name
     *     {@code REMITTANCE})
     * @param lockedCandidates every expectation the caller locked as a candidate, re-read
     *     under its lock
     * @param membershipComplete false when the caller's re-read under the locks found a
     *     candidate it had not locked
     */
    public static Verdict decide(
            MatchEngine.ItemFacts item,
            LocalDate groupDate,
            Optional<ExpectationKind> expectationKind,
            List<MatchEngine.HitFacts> lockedCandidates,
            boolean membershipComplete) {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(groupDate, "groupDate must not be null");
        Objects.requireNonNull(expectationKind, "expectationKind must not be null");
        Objects.requireNonNull(lockedCandidates, "lockedCandidates must not be null");

        // Untouched means nothing allocated and nothing resolved: the whole amount remains
        // (a settled, partially settled or resolved expectation shows less), so an earlier
        // claimant's allocation in the same chunk takes the candidate out of every later
        // group.
        List<MatchEngine.HitFacts> candidates =
                lockedCandidates.stream()
                        .filter(hit ->
                                expectationKind.map(kind -> kind == hit.kind()).orElse(true))
                        .filter(hit -> hit.direction() == item.direction())
                        .filter(hit ->
                                hit.amount().currency().equals(item.amount().currency())
                                        && hit.amount().scale() == item.amount().scale())
                        .filter(hit -> hit.remainderMinor() > 0)
                        .filter(hit -> hit.remainderMinor() == hit.amount().minorUnits())
                        .filter(hit -> hit.expectedBy().equals(groupDate))
                        .sorted(Comparator.comparing(MatchEngine.HitFacts::expectationId))
                        .toList();

        Optional<Money> total =
                candidates.stream().map(MatchEngine.HitFacts::amount).reduce(Money::plus);
        // A moved membership first: a candidate the caller did not lock may be the very one
        // that owns this line, so nothing - not even "no candidates" - is concluded.
        if (!membershipComplete) {
            return new Verdict(Kind.MEMBERSHIP_MOVED, candidates, total);
        }
        if (total.isEmpty()) {
            return new Verdict(Kind.NO_CANDIDATES, List.of(), Optional.empty());
        }
        return new Verdict(
                total.get().equals(item.amount()) ? Kind.MATCH : Kind.TOTAL_DIFFERS,
                candidates,
                total);
    }
}
