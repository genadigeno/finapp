package com.finapp.accounting;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One locked month's opening checkpoint (`P16-REM-003`; {@code THREAT_MODEL.md} section 6 item 64; ADR-0142's follow-up;
 * {@code INV-ACC-01}, {@code INV-ACC-06}): every journal line dated on or before {@link #through()}, summed in the three shapes an
 * opening read takes, folded under the lock {@link #lockedAt()} names.
 *
 * <p><strong>The shapes.</strong> {@link #gl()} is the trial balance's GL side - purpose, owner kind, currency, scale and side, no
 * counterparty. {@link #ledger()} is the ledger's own side - currency, scale and side, <em>no account joined</em>, folded by the
 * ledger's own statement, so the two sides a trial balance compares never come from one sum. {@link #counterparties()} is the
 * counterparty accounts' lines per counterparty code, which a report splits the GL side's counterparty rows by and checks against.
 * None names a GL account: the mapping is applied on the read, so no version change can stale a checkpoint.
 *
 * <p><strong>The split.</strong> A read dated before a day takes the latest valid checkpoint before it and adds the lines dated after
 * it: {@link #gl(List)}, {@link #counterparties(List)} and {@link #ledger(List)} add the checkpoint's sums to what the read folded
 * after it, group by group. The result is the whole fold's, group for group, ordered as the whole fold's statements order theirs.
 *
 * <p><strong>The cut.</strong> {@link #latestCreated()} is the latest creation instant of the entries it folds (empty when none): a
 * PROVISIONAL read cut at an instant takes it only when the cut is at or after it, so every entry it folds is one the cut keeps.
 */
public record OpeningCheckpoint(
        AccountingPeriodId period,
        LocalDate through,
        Instant lockedAt,
        Optional<Instant> latestCreated,
        List<JournalReads.Aggregate> gl,
        List<JournalReads.Total> ledger,
        List<JournalReads.Aggregate> counterparties) {

    /** The opening reads' group order: the statements' {@code ORDER BY}, a counterparty with no code last. */
    static final Comparator<JournalReads.Aggregate> GROUP_ORDER = Comparator.comparing(JournalReads.Aggregate::purpose)
            .thenComparing(JournalReads.Aggregate::ownerKind)
            .thenComparing(aggregate -> aggregate.counterparty().orElse(null), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(JournalReads.Aggregate::currency)
            .thenComparingInt(JournalReads.Aggregate::scale)
            .thenComparing(aggregate -> aggregate.side().name());

    private static final Comparator<TrialBalanceReads.LedgerWindows> WINDOW_ORDER =
            Comparator.comparing(TrialBalanceReads.LedgerWindows::currency)
                    .thenComparingInt(TrialBalanceReads.LedgerWindows::scale)
                    .thenComparing(windows -> windows.side().name());

    public OpeningCheckpoint {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(through, "through");
        Objects.requireNonNull(lockedAt, "lockedAt");
        Objects.requireNonNull(latestCreated, "latestCreated");
        gl = List.copyOf(gl);
        ledger = List.copyOf(ledger);
        counterparties = List.copyOf(counterparties);
        for (JournalReads.Aggregate group : gl) {
            if (group.counterparty().isPresent()) {
                throw new IllegalArgumentException("a checkpoint's GL side names no counterparty");
            }
        }
        for (JournalReads.Aggregate group : counterparties) {
            if (!"COUNTERPARTY".equals(group.ownerKind())) {
                throw new IllegalArgumentException("a checkpoint's counterparty side holds only counterparty accounts");
            }
        }
    }

    /** Whether a read dated before {@code before} may start from this checkpoint: the month ends before that day. */
    public boolean precedes(LocalDate before) {
        return through.isBefore(before);
    }

    /** The GL side of a read before a day: this checkpoint's groups plus the lines {@code after} it, group by group. */
    public List<JournalReads.Aggregate> gl(List<JournalReads.Aggregate> after) {
        return add(gl, after);
    }

    /** The counterparty side of a read before a day: this checkpoint's groups plus the lines {@code after} it, group by group. */
    public List<JournalReads.Aggregate> counterparties(List<JournalReads.Aggregate> after) {
        return add(counterparties, after);
    }

    /**
     * The ledger's windows of a period after this checkpoint: its sums added to the before-the-period and through-its-end windows of
     * what the read folded after it - the within window is the period's own lines and none of them is the checkpoint's.
     */
    public List<TrialBalanceReads.LedgerWindows> ledger(List<TrialBalanceReads.LedgerWindows> after) {
        Map<String, TrialBalanceReads.LedgerWindows> windows = new LinkedHashMap<>();
        for (TrialBalanceReads.LedgerWindows window : after) {
            if (windows.put(key(window.currency(), window.scale(), window.side()), window) != null) {
                throw new IllegalArgumentException("a ledger window is read once per currency, scale and side");
            }
        }
        for (JournalReads.Total total : ledger) {
            String key = key(total.currency(), total.scale(), total.side());
            TrialBalanceReads.LedgerWindows read = windows.get(key);
            windows.put(key, read == null
                    ? new TrialBalanceReads.LedgerWindows(total.currency(), total.scale(), total.side(), total.minorUnits(),
                            total.lines(), BigInteger.ZERO, 0, total.minorUnits(), total.lines())
                    : new TrialBalanceReads.LedgerWindows(read.currency(), read.scale(), read.side(),
                            read.beforeMinor().add(total.minorUnits()), read.beforeLines() + total.lines(),
                            read.withinMinor(), read.withinLines(),
                            read.throughMinor().add(total.minorUnits()), read.throughLines() + total.lines()));
        }
        List<TrialBalanceReads.LedgerWindows> merged = new ArrayList<>(windows.values());
        merged.sort(WINDOW_ORDER);
        return List.copyOf(merged);
    }

    private static List<JournalReads.Aggregate> add(List<JournalReads.Aggregate> checkpoint, List<JournalReads.Aggregate> after) {
        Map<String, JournalReads.Aggregate> groups = new LinkedHashMap<>();
        for (JournalReads.Aggregate group : checkpoint) {
            put(groups, group);
        }
        for (JournalReads.Aggregate group : after) {
            put(groups, group);
        }
        List<JournalReads.Aggregate> merged = new ArrayList<>(groups.values());
        merged.sort(GROUP_ORDER);
        return List.copyOf(merged);
    }

    private static void put(Map<String, JournalReads.Aggregate> groups, JournalReads.Aggregate group) {
        groups.merge(String.join("\u0000", group.purpose(), group.ownerKind(), group.counterparty().map(code -> "=" + code).orElse("-"),
                        key(group.currency(), group.scale(), group.side())), group,
                (held, more) -> new JournalReads.Aggregate(held.purpose(), held.ownerKind(), held.counterparty(), held.currency(),
                        held.scale(), held.side(), held.minorUnits().add(more.minorUnits()), held.lines() + more.lines()));
    }

    private static String key(String currency, int scale, Side side) {
        return currency + "\u0000" + scale + "\u0000" + side.name();
    }
}
