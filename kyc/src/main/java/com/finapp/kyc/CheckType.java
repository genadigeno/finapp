package com.finapp.kyc;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The question a {@link VerificationCheck} asks (`P2-TSK-009`, ADR-0038).
 *
 * <p><strong>All five types are declared although only two have adapters</strong>, and that is a
 * decision with a reason rather than scope creep: {@code PHASE_2_PLAN.md} §4 states the five
 * outright as the type's design — the deliberately-few licence's own test — and declaring two now
 * would force a constraint-<em>replacement</em> migration one task later, because an applied
 * migration is history (`P2-TSK-004`, ADR-0011). What bounds a verification run is never this
 * enum but the set of registered {@link VerificationProvider}s; a type without a provider cannot
 * be dispatched.
 *
 * <p>Screening ({@code SANCTIONS}, {@code PEP}, {@code ADVERSE_MEDIA}) is a check of this same
 * machine, not a second one ({@code PHASE_2_PLAN.md} §5) — `P2-TSK-010` adds those adapters and
 * the HIT→review routing.
 */
public enum CheckType {
    IDENTITY,
    DOCUMENT,
    SANCTIONS,
    PEP,
    ADVERSE_MEDIA;

    /** The quoted, comma-separated value list the migration's {@code CHECK} constraint uses. */
    public static String sqlValueList() {
        return Stream.of(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
