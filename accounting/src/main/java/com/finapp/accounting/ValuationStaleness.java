package com.finapp.accounting;

import java.time.Duration;
import java.time.Instant;

/**
 * How old a reference observation may be, before a period's end, and still value it (`P14-TSK-014`; ADR-0116 decision
 * 2's "a staleness bound refusing older"; {@code INV-ACC-11}): an observation is admitted when it was observed strictly
 * before the set's {@code asOf} and no more than {@code hours} before it. Judged against {@code asOf} - the period's end,
 * data - never against the capture's clock, so ten instances capturing at different moments judge the same rows alike.
 *
 * <p>The default, {@value #DEFAULT_HOURS} hours, covers a month that ends on a weekend after a bank holiday: a
 * business-day reference's last fixing before such an end (Thursday afternoon, the month ending on Sunday) is about 82
 * hours old at the end - an ordinary weekend's about 58. Configuration ({@code finapp.accounting.valuation.max-age-hours}),
 * 1 to {@value #MAX_HOURS} hours (two weeks: older than that is no longer the period end's rate). The default awaits the
 * owner's ratification.
 */
public record ValuationStaleness(int hours) {

    /** Four days. */
    public static final int DEFAULT_HOURS = 96;

    /** Two weeks. */
    public static final int MAX_HOURS = 336;

    public ValuationStaleness {
        if (hours < 1 || hours > MAX_HOURS) {
            throw new IllegalArgumentException("the valuation staleness bound is 1 to " + MAX_HOURS + " hours, not "
                    + hours);
        }
    }

    /** The default bound. */
    public static ValuationStaleness standard() {
        return new ValuationStaleness(DEFAULT_HOURS);
    }

    /** The earliest admitted observation instant for a set as of {@code asOf} - itself admitted. */
    public Instant oldestAdmitted(Instant asOf) {
        return asOf.minus(Duration.ofHours(hours));
    }

    /** True when {@code observedAt} is strictly before {@code asOf} and no older than the bound. */
    public boolean admits(Instant observedAt, Instant asOf) {
        return observedAt.isBefore(asOf) && !observedAt.isBefore(oldestAdmitted(asOf));
    }
}
