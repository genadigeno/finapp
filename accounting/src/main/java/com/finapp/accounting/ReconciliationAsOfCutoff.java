package com.finapp.accounting;

import java.math.BigInteger;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Reconciliation's breaks standing open at a period's cutoff, and the ids behind its suspense register, <strong>through reads that cannot move after the lock</strong>
 * (`P14-TSK-016`, on `P14-TSK-017`'s note; {@code INV-ACC-04}, {@code INV-ACC-05}): what a FINAL statement's notes hold, and {@link ReconciliationDisclosure#asOfCutoff} is not, because its
 * {@code raisedSinceStillOpen} and {@code pastTheCutoff} move after the lock. A break counts by its raise, resolution and value at issue alone, each frozen: never its present status, type or
 * severity, which keep moving. An item counts by the read {@link SuspenseAsOfCutoff} uses. The instant is at least the lock's grace in the past, so no transaction straddles it: a break resolved
 * after it still stood open at it.
 *
 * <p>Read-only, on the caller's snapshot; a read that fails throws, never an empty answer. Disclosed, never copied into accounting's breaks and never resolved from here (C11). Gross per
 * currency and scale; a value at issue is never netted.
 */
@FunctionalInterface
public interface ReconciliationAsOfCutoff {

    /** The statements' version 1's ageing bands, in days from a break's raise to the cutoff: their own table, for the same reason as {@link SuspenseAsOfCutoff#AGEING_BANDS}. */
    List<String> AGEING_BANDS = List.of("0-2", "3-7", "8-30", "over-30");

    /** The band of a break {@code days} old, from {@link #AGEING_BANDS}: raised on the last day is zero days. */
    static String ageingBand(long days) {
        if (days <= 2) {
            return AGEING_BANDS.get(0);
        }
        if (days <= 7) {
            return AGEING_BANDS.get(1);
        }
        return days <= 30 ? AGEING_BANDS.get(2) : AGEING_BANDS.get(3);
    }

    /** What stood at {@code period}'s cutoff. */
    Standing ofCutoff(Connection snapshot, YearMonth period);

    /**
     * The breaks standing open at the instant, one currency and scale.
     *
     * @param ageing in {@link #AGEING_BANDS}
     * @param breakIds each break, oldest raise first: the note's route to a case file
     */
    record OpenBreaks(String currency, int scale, long breaks, BigInteger valueAtIssueMinor, SortedMap<String, Long> ageing, LocalDate oldestRaisedOn, List<UUID> breakIds) {

        public OpenBreaks {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(valueAtIssueMinor, "valueAtIssueMinor");
            ageing = new TreeMap<>(ageing);
            Objects.requireNonNull(oldestRaisedOn, "oldestRaisedOn");
            breakIds = List.copyOf(breakIds);
        }
    }

    /** A suspense item the register holds at the instant, and the break that owns it: the note's route to its journal entries. */
    record ParkedItem(UUID item, UUID owningBreak) {

        public ParkedItem {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(owningBreak, "owningBreak");
        }
    }

    /** The cutoff, the instant it is as of, the breaks per currency and scale, sorted, and the items, oldest first. */
    record Standing(LocalDate cutoff, Instant asOf, List<OpenBreaks> breaks, List<ParkedItem> items) {

        public Standing {
            Objects.requireNonNull(cutoff, "cutoff");
            Objects.requireNonNull(asOf, "asOf");
            breaks = List.copyOf(breaks);
            items = List.copyOf(items);
        }
    }
}
