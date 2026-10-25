package com.finapp.risk;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One monitored transaction <strong>as a party sees it</strong> (`P13-TSK-020`; {@code PHASE_13_PLAN.md} section 12.7;
 * {@code DESIGN-020} F3, D4) - what the monitoring rules read of the journal's record: the journal entry it was born
 * from (the evidence an alert names), its sequence, its kind, the direction <em>from the party's own accounts</em>, the
 * judged amount with its currency and scale, and its value date (the date its windows are cut on).
 *
 * <p><strong>Why "as a party sees it".</strong> {@code -019}'s classification gives one row per journal entry and one
 * subject per row - the first customer line's account; in an internal transfer the receiving customer is only the
 * row's counterparty. So a party's movement is either a row whose subject is one of its accounts (the direction as
 * stored) or a row whose customer counterparty is one of its accounts (the direction inverted - the payer's {@code OUT}
 * is the payee's {@code IN}); a row with both sides on the party's own accounts moved none of its money and is not a
 * movement of it ({@link #seenBy}). The counterparty's figure is the row's judged amount - exact for a two-sided entry
 * (a transfer), the classification's recorded bound for an entry with more customer lines.
 *
 * <p><strong>What counts</strong> ({@link #COUNTED}): every kind but {@code REVERSAL} and {@code ADJUSTMENT} - a
 * correction of the record is not a movement of the party's (a reversed transfer stays counted as the movement it was;
 * its reversal does not add an inflow that would make it look like money passing through).
 */
public record MonitoredMovement(UUID journalEntryId, long journalSequence, MonitoredKind kind,
        MonitoredDirection direction, long amountMinor, int scale, CurrencyCode currency, LocalDate valueDate) {

    /** The kinds a party's readings count - every kind but the record's own corrections. */
    public static final Set<MonitoredKind> COUNTED = Collections.unmodifiableSet(
            EnumSet.complementOf(EnumSet.of(MonitoredKind.REVERSAL, MonitoredKind.ADJUSTMENT)));

    /** The order every reader walks movements in - by value date, then the journal's own order. */
    public static final Comparator<MonitoredMovement> ORDER = Comparator.comparing(MonitoredMovement::valueDate)
            .thenComparingLong(MonitoredMovement::journalSequence)
            .thenComparing(MonitoredMovement::journalEntryId);

    public MonitoredMovement {
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(valueDate, "valueDate");
        if (journalSequence < 0) {
            throw new IllegalArgumentException("a journal sequence is not negative");
        }
        if (amountMinor < 0) {
            throw new IllegalArgumentException("a judged amount is not negative");
        }
        if (scale < 0 || scale > 9) {
            throw new IllegalArgumentException("a scale is 0 to 9");
        }
    }

    /**
     * {@code row} as the party holding {@code accounts} sees it - empty when the row is not a counted movement of the
     * party: no side on its accounts, both sides on them, or a correction ({@link #COUNTED}).
     */
    public static Optional<MonitoredMovement> seenBy(MonitoredTransaction row, Set<UUID> accounts) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(accounts, "accounts");
        if (!COUNTED.contains(row.kind())) {
            return Optional.empty();
        }
        boolean subject = subjectAccount(row).filter(accounts::contains).isPresent();
        boolean counterparty = counterpartyAccount(row).filter(accounts::contains).isPresent();
        if (subject == counterparty) {
            return Optional.empty();
        }
        MonitoredDirection stored = row.direction().orElseThrow(() -> new IllegalStateException(
                "a customer counterparty is another customer account's row, which has a direction"));
        MonitoredDirection seen = subject ? stored
                : stored == MonitoredDirection.IN ? MonitoredDirection.OUT : MonitoredDirection.IN;
        return Optional.of(new MonitoredMovement(row.journalEntryId(), row.journalSequence(), row.kind(), seen,
                row.amountMinor(), row.scale(), row.currency(), row.valueDate()));
    }

    /** The customer account a row's subject is, when it is one. */
    public static Optional<UUID> subjectAccount(MonitoredTransaction row) {
        return row.subjectKind().filter(kind -> kind == MonitoredSubjectKind.ACCOUNT).flatMap(kind -> row.subjectRef());
    }

    /** The customer account a row's counterparty is, when it is one. */
    public static Optional<UUID> counterpartyAccount(MonitoredTransaction row) {
        return row.counterpartyKind() == MonitoredCounterpartyKind.CUSTOMER ? row.counterpartyRef() : Optional.empty();
    }

    /** True when this movement is in {@code currency} at that currency's present scale - the policy's money. */
    public boolean inMoneyOf(CurrencyCode policyCurrency) {
        return currency.equals(policyCurrency) && scale == policyCurrency.minorUnits();
    }

    @Override
    public String toString() {
        // The entry and the kind only - never the amount (INV-RSK-08: a party's movements are its own).
        return "MonitoredMovement[" + journalEntryId + ", " + kind + "]";
    }
}
