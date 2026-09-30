package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code CORRECTION} cardinality's pure seat (`P8-TSK-012`, ADR-0068 §3, the plan's
 * §12.5): a counterparty's correction is NEW EVIDENCE. Same direction as the original's
 * expectation, it TOPS UP the open remainder (min(item, remainder), any excess parked by
 * the caller — a correction is evidence like any line, the design's C1); opposite in
 * direction and EXACTLY equal to the original item's parked remainder, it OFFSETS that
 * suspense — never a partial offset. Matching neither exactly, it is an unallocated
 * remainder like any other: the caller's lookup types it and it waits.
 *
 * <p>Precedence when both could apply (one original reference, an open remainder AND a
 * parked excess of the fitting sides): the TOP UP — the expectation's remainder is the
 * older, primary record; the parked excess keeps its own break and its own corrections.
 */
public final class CorrectionEngine {

    private CorrectionEngine() {}

    /** The original item's open parked value, snapshotted under the chunk's locks. */
    public record ParkedOriginal(
            UUID originalItemId,
            UUID suspenseItemId,
            UUID breakId,
            SuspenseSide side,
            long remainderMinor,
            CurrencyCode currency,
            int scale,
            UUID positionAccountId) {

        public ParkedOriginal {
            Objects.requireNonNull(originalItemId, "originalItemId must not be null");
            Objects.requireNonNull(suspenseItemId, "suspenseItemId must not be null");
            Objects.requireNonNull(breakId, "breakId must not be null");
            Objects.requireNonNull(side, "side must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(positionAccountId, "positionAccountId must not be null");
        }
    }

    public enum Kind {
        /** Allocate {@code allocation} to {@code candidate}; park {@code excess} if any. */
        TOP_UP,
        /** Release the {@code offset} suspense whole and resolve its break EVIDENCED. */
        OFFSET,
        /** Neither an open remainder nor an exact parked match: the lookup types it. */
        UNREACHED
    }

    public record Verdict(
            Kind kind,
            Optional<MatchEngine.HitFacts> candidate,
            Optional<Money> allocation,
            Optional<Money> excess,
            Optional<ParkedOriginal> offset) {}

    /**
     * @param sameKeyHits the expectations the original reference reached, locked, in the
     *     caller's deterministic kind order (capture-side first)
     * @param parked the original items' open parked values, in the caller's deterministic
     *     order (oldest suspense first)
     */
    public static Verdict decide(
            MatchEngine.ItemFacts item,
            List<MatchEngine.HitFacts> sameKeyHits,
            List<ParkedOriginal> parked) {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(sameKeyHits, "sameKeyHits must not be null");
        Objects.requireNonNull(parked, "parked must not be null");

        // TOP UP: the first live same-direction, same-currency remainder.
        Optional<MatchEngine.HitFacts> candidate =
                sameKeyHits.stream()
                        .filter(hit -> hit.direction() == item.direction())
                        .filter(hit ->
                                hit.amount().currency().equals(item.amount().currency()))
                        .filter(hit -> hit.remainderMinor() > 0)
                        .findFirst();
        if (candidate.isPresent()) {
            long allocated =
                    Math.min(item.amount().minorUnits(), candidate.get().remainderMinor());
            Optional<Money> excess =
                    item.amount().minorUnits() > allocated
                            ? Optional.of(
                                    Money.ofPersisted(
                                            item.amount().minorUnits() - allocated,
                                            item.amount().currency(),
                                            item.amount().scale()))
                            : Optional.empty();
            return new Verdict(
                    Kind.TOP_UP,
                    candidate,
                    Optional.of(
                            Money.ofPersisted(
                                    allocated,
                                    item.amount().currency(),
                                    item.amount().scale())),
                    excess,
                    Optional.empty());
        }

        // OFFSET: opposite direction, EXACTLY the parked remainder - never partial. An
        // INBOUND original parked a CREDIT item, so its claw-back arrives OUTBOUND.
        SuspenseSide offsettable =
                item.direction() == ExpectationDirection.OUTBOUND
                        ? SuspenseSide.CREDIT
                        : SuspenseSide.DEBIT;
        Optional<ParkedOriginal> offset =
                parked.stream()
                        .filter(original -> original.side() == offsettable)
                        .filter(original ->
                                original.currency().equals(item.amount().currency())
                                        && original.scale() == item.amount().scale())
                        .filter(original ->
                                original.remainderMinor() == item.amount().minorUnits())
                        .findFirst();
        if (offset.isPresent()) {
            return new Verdict(
                    Kind.OFFSET, Optional.empty(), Optional.empty(), Optional.empty(),
                    offset);
        }
        return new Verdict(
                Kind.UNREACHED, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }
}
