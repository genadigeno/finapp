package com.finapp.accounting;

import java.time.YearMonth;
import java.util.Objects;

/**
 * The first month the platform keeps books for (`P17-TSK-113`, ADR-0152; the owner's decision of 2026-11-19): when no accounting
 * period exists yet, {@link AccountingPeriods#openNext} opens its first one {@code monthsBack} months before today's month, then
 * every month through today's - each an empty, {@code OPEN} month, lockable at once since its grace has passed. Configuration
 * ({@code finapp.accounting.period.books-open-months-back}), {@code 0} by default: the books open in the month the platform first
 * runs, as they always have.
 *
 * <p><strong>Read once, at the books' first open, and never again.</strong> Once any period exists the next one is the month after
 * the latest, whatever this says, and {@code V003}'s trigger refuses a month before the first: the setting cannot open, reopen or
 * reach a month behind books already kept. A test stack sets it so a fresh database holds past months to lock, close and correct
 * (the console's end-to-end seed); a platform migrating books kept elsewhere would set it to the first month it takes over.
 *
 * <p>At most {@link #MAX_MONTHS_BACK}: a typo of years would open years of months, each of which must be locked and closed in
 * order before the next. Every instance reads the same configuration; one that read another value and opened today's month first
 * would leave the earlier months unopened - its siblings' earlier inserts refused by the trigger, the books starting at today's
 * month - which is the default's behaviour, never a gap.
 */
public record BooksOpening(int monthsBack) {

    /** The default: the books open in the month the platform first runs. */
    public static final int DEFAULT_MONTHS_BACK = 0;

    /** Two years: past it, a value is far more likely a mistake than a migration. */
    public static final int MAX_MONTHS_BACK = 24;

    /** The default opening, today's month. */
    public static final BooksOpening TODAY = new BooksOpening(DEFAULT_MONTHS_BACK);

    public BooksOpening {
        if (monthsBack < 0 || monthsBack > MAX_MONTHS_BACK) {
            throw new IllegalArgumentException("the books open 0 to " + MAX_MONTHS_BACK + " months back, not " + monthsBack);
        }
    }

    /** The books' first month, when they open in {@code today}'s month. */
    public YearMonth firstMonth(YearMonth today) {
        return Objects.requireNonNull(today, "today").minusMonths(monthsBack);
    }
}
