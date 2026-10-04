package com.finapp.fx;

import java.util.List;
import java.util.Objects;

/**
 * The independent reference source this build reads (`P9-TSK-005`, ADR-0075 §1): its code and
 * the canonical pairs it publishes.
 *
 * <p><strong>Deliberately a different party than the FX provider</strong>, so the plausibility
 * band is an independent check: a compromised or stuck provider cannot pass its own rate as
 * plausible. The ten pairs are every unordered pair of the five postable currencies, each in one
 * market direction; {@code fx V002}'s {@code rate_snapshot_pair_is_canonical} CHECK holds the same
 * ten, so a pair outside this list has no row ({@code RateSnapshotDatabaseTest} keeps the two
 * equal).
 */
public final class ReferenceSourceDeclaration {

    /** The simulated reference's code - its snapshots' {@code source}. */
    public static final String SOURCE = "simulated-reference";

    /** The canonical pairs, market direction, in the order the migration's CHECK lists them. */
    public static final List<ReferencePair> PAIRS =
            List.of(
                    ReferencePair.of("EUR", "GBP"),
                    ReferencePair.of("EUR", "USD"),
                    ReferencePair.of("EUR", "JPY"),
                    ReferencePair.of("EUR", "BHD"),
                    ReferencePair.of("GBP", "USD"),
                    ReferencePair.of("GBP", "JPY"),
                    ReferencePair.of("GBP", "BHD"),
                    ReferencePair.of("USD", "JPY"),
                    ReferencePair.of("USD", "BHD"),
                    ReferencePair.of("BHD", "JPY"));

    private ReferenceSourceDeclaration() {}

    /** Whether {@code pair} is one the source publishes, in its canonical direction. */
    public static boolean declares(ReferencePair pair) {
        return PAIRS.contains(Objects.requireNonNull(pair, "pair must not be null"));
    }
}
