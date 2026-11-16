package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.sharedkernel.id.IdGenerator;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The opening's split (`P16-REM-003`; {@code THREAT_MODEL.md} section 6 item 64; {@code INV-ACC-01}, {@code INV-ACC-06}): a locked
 * month's checkpoint plus the lines dated after it is the whole fold, group for group, on every side an opening read takes - the GL
 * side, the ledger's three windows and the counterparties - over a seeded journal crossing several months, several currencies and
 * scales, both sides and counterparty accounts. The whole fold is {@link InMemoryTrialBalanceReads}, the definitions' oracle.
 * Against PostgreSQL it is {@code OpeningCheckpointDatabaseTest}'s.
 */
@DisplayName("an opening checkpoint plus the lines after it is the whole fold (P16-REM-003)")
class OpeningCheckpointTest {

    private static final AccountingPeriodId PERIOD = AccountingPeriodId.of(new IdGenerator(Clock.systemUTC(), new SecureRandom()).next());
    private static final Instant LOCKED_AT = Instant.parse("2097-07-05T09:00:00Z");
    private static final List<String[]> ACCOUNTS = List.of(new String[] {"CUSTOMER_FUNDS", "CUSTOMER"},
            new String[] {"SETTLEMENT_CLEARING", "PLATFORM"}, new String[] {"FEE_REVENUE", "PLATFORM"},
            new String[] {"SETTLEMENT_RECEIVABLE", "COUNTERPARTY"}, new String[] {"SAFEGUARDING_CASH", "PLATFORM"});
    private static final List<String> COUNTERPARTIES = List.of("bank-a", "psp-b", "scheme-c");
    private static final List<Object[]> CURRENCIES = List.of(new Object[] {"USD", 2}, new Object[] {"JPY", 0}, new Object[] {"BHD", 3});

    /** A seeded journal over January to June 2097: balanced entries, each line created a few seconds after its day starts. */
    private static InMemoryTrialBalanceReads journal(long seed) {
        Random random = new Random(seed);
        InMemoryTrialBalanceReads journal = new InMemoryTrialBalanceReads();
        LocalDate day = LocalDate.of(2096, 12, 30);
        while (day.isBefore(LocalDate.of(2097, 7, 1))) {
            for (int entry = random.nextInt(4); entry > 0; entry--) {
                String[] debit = ACCOUNTS.get(random.nextInt(ACCOUNTS.size()));
                String[] credit = ACCOUNTS.get(random.nextInt(ACCOUNTS.size()));
                Object[] currency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
                Instant created = day.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(random.nextInt(86_400));
                journal.postFor(COUNTERPARTIES.get(random.nextInt(COUNTERPARTIES.size())), day, created, (String) currency[0],
                        (Integer) currency[1], 1 + random.nextInt(1_000_000), debit[0] + "/" + debit[1], credit[0] + "/" + credit[1]);
            }
            day = day.plusDays(1);
        }
        return journal;
    }

    /** The lines of {@code journal} dated after {@code through} - what a read folds after a checkpoint. */
    private static InMemoryTrialBalanceReads after(InMemoryTrialBalanceReads journal, LocalDate through) {
        InMemoryTrialBalanceReads after = new InMemoryTrialBalanceReads();
        journal.lines().stream().filter(line -> line.postingDate().isAfter(through)).forEach(after::line);
        return after;
    }

    /** {@code month}'s checkpoint folded whole: every line dated on or before its end. */
    private static OpeningCheckpoint whole(InMemoryTrialBalanceReads journal, YearMonth month) {
        LocalDate end = month.atEndOfMonth();
        TrialBalanceReads.Period period = new TrialBalanceReads.Period(PERIOD, month.atDay(1), end, PeriodStatus.LOCKED, Optional.empty());
        return new OpeningCheckpoint(PERIOD, end, LOCKED_AT, latestCreated(journal, end), journal.before(null, end.plusDays(1), Optional.empty()),
                totals(journal.ledger(null, period, Optional.empty())), journal.counterparties().before(null, end.plusDays(1), Optional.empty()));
    }

    /** {@code month}'s checkpoint folded from {@code previous}'s plus the month's lines - as the writer folds it. */
    private static OpeningCheckpoint incremental(InMemoryTrialBalanceReads journal, OpeningCheckpoint previous, YearMonth month) {
        LocalDate end = month.atEndOfMonth();
        InMemoryTrialBalanceReads since = after(journal, previous.through());
        TrialBalanceReads.Period period = new TrialBalanceReads.Period(PERIOD, month.atDay(1), end, PeriodStatus.LOCKED, Optional.empty());
        return new OpeningCheckpoint(PERIOD, end, LOCKED_AT, latestCreated(journal, end),
                previous.gl(since.before(null, end.plusDays(1), Optional.empty())),
                totals(previous.ledger(since.ledger(null, period, Optional.empty()))),
                previous.counterparties(since.counterparties().before(null, end.plusDays(1), Optional.empty())));
    }

    private static List<JournalReads.Total> totals(List<TrialBalanceReads.LedgerWindows> windows) {
        return windows.stream().map(window -> new JournalReads.Total(window.currency(), window.scale(), window.side(), window.throughMinor(),
                window.throughLines())).toList();
    }

    private static Optional<Instant> latestCreated(InMemoryTrialBalanceReads journal, LocalDate through) {
        return journal.lines().stream().filter(line -> !line.postingDate().isAfter(through))
                .map(InMemoryTrialBalanceReads.JournalLine::createdAt).max(Comparator.naturalOrder());
    }

    private static List<JournalReads.Aggregate> sorted(List<JournalReads.Aggregate> aggregates) {
        List<JournalReads.Aggregate> sorted = new ArrayList<>(aggregates);
        sorted.sort(OpeningCheckpoint.GROUP_ORDER);
        return sorted;
    }

    @Test
    @DisplayName("over five seeded journals and every day of the months after it: the GL side, the ledger's windows and the counterparties"
            + " split equal the whole fold")
    void theSplitIsTheWholeFold() {
        for (long seed = 1; seed <= 5; seed++) {
            InMemoryTrialBalanceReads journal = journal(seed);
            for (YearMonth locked : List.of(YearMonth.of(2097, 1), YearMonth.of(2097, 2), YearMonth.of(2097, 4))) {
                OpeningCheckpoint checkpoint = whole(journal, locked);
                InMemoryTrialBalanceReads since = after(journal, checkpoint.through());
                for (LocalDate before = locked.atEndOfMonth().plusDays(1); !before.isAfter(LocalDate.of(2097, 7, 1)); before = before.plusDays(1)) {
                    assertThat(checkpoint.precedes(before)).isTrue();
                    assertThat(checkpoint.gl(since.before(null, before, Optional.empty())))
                            .as("seed %d, %s, the GL side before %s", seed, locked, before)
                            .isEqualTo(sorted(journal.before(null, before, Optional.empty())));
                    assertThat(checkpoint.counterparties(since.counterparties().before(null, before, Optional.empty())))
                            .as("seed %d, %s, the counterparties before %s", seed, locked, before)
                            .isEqualTo(sorted(journal.counterparties().before(null, before, Optional.empty())));
                }
                for (YearMonth month = locked.plusMonths(1); !month.isAfter(YearMonth.of(2097, 6)); month = month.plusMonths(1)) {
                    TrialBalanceReads.Period period = new TrialBalanceReads.Period(PERIOD, month.atDay(1), month.atEndOfMonth(),
                            PeriodStatus.OPEN, Optional.empty());
                    assertThat(checkpoint.ledger(since.ledger(null, period, Optional.empty())))
                            .as("seed %d, %s, the ledger's windows of %s", seed, locked, month)
                            .isEqualTo(journal.ledger(null, period, Optional.empty()).stream()
                                    .sorted(Comparator.comparing(TrialBalanceReads.LedgerWindows::currency)
                                            .thenComparingInt(TrialBalanceReads.LedgerWindows::scale)
                                            .thenComparing(windows -> windows.side().name()))
                                    .toList());
                }
            }
        }
    }

    @Test
    @DisplayName("a chain of checkpoints, each folded from the one before plus its month, is each month's whole fold - the writer's way")
    void theChainIsTheWholeFold() {
        for (long seed = 11; seed <= 13; seed++) {
            InMemoryTrialBalanceReads journal = journal(seed);
            OpeningCheckpoint chained = whole(journal, YearMonth.of(2097, 1));
            for (YearMonth month = YearMonth.of(2097, 2); !month.isAfter(YearMonth.of(2097, 6)); month = month.plusMonths(1)) {
                chained = incremental(journal, chained, month);
                OpeningCheckpoint folded = whole(journal, month);
                assertThat(chained.gl()).as("seed %d, %s's GL side", seed, month).isEqualTo(sorted(folded.gl()));
                assertThat(chained.ledger()).as("seed %d, %s's ledger side", seed, month).containsExactlyInAnyOrderElementsOf(folded.ledger());
                assertThat(chained.counterparties()).as("seed %d, %s's counterparties", seed, month).isEqualTo(sorted(folded.counterparties()));
            }
        }
    }

    @Test
    @DisplayName("under a cut: at or after the checkpoint's latest creation the split is the cut whole fold; before it, the checkpoint holds"
            + " entries the cut drops - which is why the store serves it only at or after (V017's latest_created)")
    void theCutTakesACheckpointOnlyAtOrAfterItsLatestCreation() {
        InMemoryTrialBalanceReads journal = journal(21);
        OpeningCheckpoint checkpoint = whole(journal, YearMonth.of(2097, 3));
        InMemoryTrialBalanceReads since = after(journal, checkpoint.through());
        LocalDate before = LocalDate.of(2097, 6, 1);
        Instant latest = checkpoint.latestCreated().orElseThrow();
        for (Instant cut : List.of(latest, latest.plusSeconds(1), Instant.parse("2097-06-15T00:00:00Z"))) {
            assertThat(checkpoint.gl(since.before(null, before, Optional.of(cut)))).as("cut at %s", cut)
                    .isEqualTo(sorted(journal.before(null, before, Optional.of(cut))));
        }
        Instant early = latest.minusSeconds(1);
        assertThat(checkpoint.gl(since.before(null, before, Optional.of(early))))
                .as("a cut before the checkpoint's latest creation: the checkpoint would count an entry the cut drops")
                .isNotEqualTo(sorted(journal.before(null, before, Optional.of(early))));
    }

    @Test
    @DisplayName("the ledger's windows: the checkpoint is added before the period and through its end, never within it; a currency only"
            + " the checkpoint holds has an empty within window")
    void theLedgerWindowsTakeTheCheckpointBeforeAndThrough() {
        OpeningCheckpoint checkpoint = new OpeningCheckpoint(PERIOD, LocalDate.of(2097, 3, 31), LOCKED_AT, Optional.empty(), List.of(),
                List.of(new JournalReads.Total("USD", 2, Side.DEBIT, BigInteger.valueOf(500), 5),
                        new JournalReads.Total("JPY", 0, Side.CREDIT, BigInteger.valueOf(70), 7)),
                List.of());

        List<TrialBalanceReads.LedgerWindows> windows = checkpoint.ledger(List.of(new TrialBalanceReads.LedgerWindows("USD", 2, Side.DEBIT,
                BigInteger.valueOf(10), 1, BigInteger.valueOf(30), 3, BigInteger.valueOf(40), 4)));

        assertThat(windows).containsExactly(
                new TrialBalanceReads.LedgerWindows("JPY", 0, Side.CREDIT, BigInteger.valueOf(70), 7, BigInteger.ZERO, 0,
                        BigInteger.valueOf(70), 7),
                new TrialBalanceReads.LedgerWindows("USD", 2, Side.DEBIT, BigInteger.valueOf(510), 6, BigInteger.valueOf(30), 3,
                        BigInteger.valueOf(540), 9));
    }

    @Test
    @DisplayName("a checkpoint serves only a read dated after its month, holds no counterparty on its GL side and only counterparty"
            + " accounts on its counterparty side")
    void aCheckpointHoldsItsShapes() {
        OpeningCheckpoint checkpoint = new OpeningCheckpoint(PERIOD, LocalDate.of(2097, 3, 31), LOCKED_AT, Optional.empty(), List.of(),
                List.of(), List.of());
        assertThat(checkpoint.precedes(LocalDate.of(2097, 4, 1))).isTrue();
        assertThat(checkpoint.precedes(LocalDate.of(2097, 3, 31))).as("a read before the month's last day").isFalse();
        JournalReads.Aggregate named = new JournalReads.Aggregate("SETTLEMENT_RECEIVABLE", "COUNTERPARTY", Optional.of("bank-a"), "USD", 2,
                Side.DEBIT, BigInteger.TEN, 1);
        JournalReads.Aggregate platform = new JournalReads.Aggregate("FEE_REVENUE", "PLATFORM", Optional.empty(), "USD", 2, Side.CREDIT,
                BigInteger.TEN, 1);
        assertThatThrownBy(() -> new OpeningCheckpoint(PERIOD, LocalDate.of(2097, 3, 31), LOCKED_AT, Optional.empty(), List.of(named),
                List.of(), List.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("GL side");
        assertThatThrownBy(() -> new OpeningCheckpoint(PERIOD, LocalDate.of(2097, 3, 31), LOCKED_AT, Optional.empty(), List.of(),
                List.of(), List.of(platform))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("counterparty");
    }
}
