package com.finapp.app.accounting;

import static com.finapp.app.accounting.ScriptedStatements.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.PastTheCutoffPostings;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.accounting.SuspenseAsOfCutoff;
import com.finapp.accounting.reporting.ReadOnlySnapshot;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.reconciliation.RunKind;
import com.finapp.reconciliation.RunStatus;
import com.finapp.reconciliation.SuspenseSide;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The month's reads over reconciliation and the journal (`P14-TSK-017`; {@code INV-ACC-04}, {@code INV-ACC-05}; the owner's decision of
 * 2026-11-02; `P14-TSK-011`'s review, M-2): the runs of the month's business dates, what {@code -012}'s check will read; the suspense
 * register through the one read that cannot move after a lock - every other read of reconciliation's is refused here, so a FINAL body
 * cannot be pointed at what moves; and which past-the-cutoff entry is which.
 */
@DisplayName("the month's reads (P14-TSK-017)")
class ReconciliationMonthReadsTest {

    private static final YearMonth MARCH = YearMonth.of(2097, 3);
    private static final LocalDate CUTOFF = MARCH.atEndOfMonth();
    private static final Instant AS_OF = Instant.parse("2097-04-01T00:00:00Z");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Connection NO_CONNECTION = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("reconciliation's reads are scripted");
            });

    private final List<Object> asked = new ArrayList<>();
    private final List<ReportReadings.RunLine> runs = new ArrayList<>();
    private final List<ReportReadings.SuspenseAsOfLine> born = new ArrayList<>();

    /** Only the two reads these ports may make answer: anything else - everything that moves - fails the test. */
    private final ReportReadings readings = (ReportReadings) Proxy.newProxyInstance(ReportReadings.class.getClassLoader(),
            new Class<?>[] {ReportReadings.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "runsOfBusinessDates" -> {
                        asked.add(args[1]);
                        asked.add(args[2]);
                        return runs;
                    }
                    case "eachSuspenseItemBornAsOf" -> {
                        asked.add(args[1]);
                        asked.add(args[2]);
                        born.forEach(visitor(args[3]));
                        return null;
                    }
                    default -> throw new AssertionError("a read that moves after the lock: " + method.getName());
                }
            });

    @SuppressWarnings("unchecked")
    private static Consumer<ReportReadings.SuspenseAsOfLine> visitor(Object visitor) {
        return (Consumer<ReportReadings.SuspenseAsOfLine>) visitor;
    }

    @Test
    @DisplayName("the month's runs: its first to last business date, per source, kind, status and day - a count of what stands, with no verdict")
    void theMonthsRuns() {
        UUID source = UUID.randomUUID();
        runs.add(new ReportReadings.RunLine(source, RunKind.BATCH, RunStatus.COMPLETED, MARCH.atDay(30), 2));
        runs.add(new ReportReadings.RunLine(source, RunKind.REPROCESS, RunStatus.OPEN, MARCH.atDay(31), 1));

        ReconciliationRunCoverage.Coverage coverage = reads().ofMonth(NO_CONNECTION, MARCH);

        assertThat(asked).containsExactly(MARCH.atDay(1), CUTOFF);
        assertThat(coverage.month()).isEqualTo(MARCH);
        assertThat(coverage.lines()).containsExactly(
                new ReconciliationRunCoverage.Line(source, "BATCH", "COMPLETED", MARCH.atDay(30), 2),
                new ReconciliationRunCoverage.Line(source, "REPROCESS", "OPEN", MARCH.atDay(31), 1));
        assertThat(coverage.batchRuns()).isEqualTo(2);

        runs.clear();
        assertThat(reads().ofMonth(NO_CONNECTION, MARCH).batchRuns()).isZero();
    }

    @Test
    @DisplayName("the register as of the cutoff, through the frozen read alone: the first instant after the last day; CREDIT and DEBIT gross per"
            + " currency and scale, never netted; ageing in the definition's own bands, every band named; the oldest")
    void theRegisterAsOfTheCutoff() {
        born.add(line(SuspenseSide.CREDIT, 1_000, EUR, LocalDate.of(2097, 2, 1)));
        born.add(line(SuspenseSide.CREDIT, 500, EUR, LocalDate.of(2097, 3, 30)));
        born.add(line(SuspenseSide.DEBIT, 400, EUR, LocalDate.of(2097, 3, 25)));
        born.add(line(SuspenseSide.DEBIT, 7, USD, LocalDate.of(2097, 3, 31)));

        SuspenseAsOfCutoff.Register register = reads().ofCutoff(NO_CONNECTION, MARCH);

        assertThat(asked).containsExactly(CUTOFF, AS_OF);
        assertThat(register.cutoff()).isEqualTo(CUTOFF);
        assertThat(register.asOf()).isEqualTo(AS_OF);
        assertThat(register.groups()).extracting(SuspenseAsOfCutoff.Group::currency).containsExactly("EUR", "USD");
        SuspenseAsOfCutoff.Group euro = register.groups().get(0);
        assertThat(euro.scale()).isEqualTo(2);
        assertThat(euro.creditItems()).isEqualTo(2);
        assertThat(euro.creditUnreleasedMinor()).isEqualTo(BigInteger.valueOf(1_500));
        assertThat(euro.debitItems()).isEqualTo(1);
        assertThat(euro.debitUnreleasedMinor()).as("gross, never netted").isEqualTo(BigInteger.valueOf(400));
        assertThat(euro.ageing()).containsEntry("0-2", 1L).containsEntry("3-7", 1L).containsEntry("8-30", 0L)
                .containsEntry("over-30", 1L);
        assertThat(euro.oldestOpenedOn()).contains(LocalDate.of(2097, 2, 1));
    }

    @Test
    @DisplayName("nothing held is an empty register at its instant; a read that fails throws - never an empty answer for it")
    void aFailedReadIsNoAnswer() {
        assertThat(reads().ofCutoff(NO_CONNECTION, MARCH).groups()).isEmpty();
        ReportReadings failing = (ReportReadings) Proxy.newProxyInstance(ReportReadings.class.getClassLoader(),
                new Class<?>[] {ReportReadings.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("the register is away");
                });
        assertThatThrownBy(() -> new ReconciliationMonthReads(failing).ofCutoff(NO_CONNECTION, MARCH))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ReconciliationMonthReads(failing).ofMonth(NO_CONNECTION, MARCH))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("M-10: the ageing is SUSPENSE's own table, its edges pinned; that the frozen read depends on no part of the disclosure's is"
            + " ReportingNeverWritesTest's")
    void theBandsAreTheDefinitions() {
        assertThat(SuspenseAsOfCutoff.AGEING_BANDS).containsExactly("0-2", "3-7", "8-30", "over-30");
        assertThat(List.of(0L, 2L, 3L, 7L, 8L, 30L, 31L).stream().map(SuspenseAsOfCutoff::ageingBand))
                .containsExactly("0-2", "0-2", "3-7", "3-7", "8-30", "8-30", "over-30");
    }

    // ------------------------------------------------------------------ M-2

    @Test
    @DisplayName("M-2: per floored namespace the entries past the cutoff, split - floored by the entry's own audit mark, the rest the rule's own"
            + " dating: a batch accepted on the 1st for the 31st is not a floor; a namespace of no floored rule is not reported")
    void whichIsWhich() {
        ScriptedStatements script = new ScriptedStatements(sql -> List.of(
                row("namespace", "settlement-batch", "entries", 7L, "floored", 2L),
                row("namespace", "transfer", "entries", 99L, "floored", 0L)));
        LedgerFlooredPostings postings = new LedgerFlooredPostings(PostingRuleRegister.CURRENT.rules());

        SortedMap<String, PastTheCutoffPostings.Split> split = postings.pastTheCutoff(script.connection(), CUTOFF);

        assertThat(script.bound).containsExactly(Date.valueOf(CUTOFF), Date.valueOf(CUTOFF), "ledger.post:%");
        assertThat(split).containsOnlyKeys("settlement-batch");
        assertThat(split.get("settlement-batch").floored()).isEqualTo(2);
        assertThat(split.get("settlement-batch").ownValueDate()).isEqualTo(5);
    }

    @Test
    @DisplayName("the statement: one SELECT the report's view admits, no lock; the same selection as the disclosure's count; the floor read from the"
            + " entry's audit record by the target index, nothing but its existence - never a summary, actor or reference")
    void theStatement() throws java.sql.SQLException {
        String sql = LedgerFlooredPostings.PAST_THE_CUTOFF_SQL;
        assertThat(sql).contains("entry.posting_date > ? AND entry.value_date <= ?")
                .contains("entry.idempotency_scope LIKE ?")
                .contains("EXISTS (SELECT 1 FROM platform.audit_record audit")
                .contains("audit.target_type = 'journal_entry' AND audit.target_id = entry.id::text")
                .contains("audit.change_summary LIKE '%flooredFrom=%'");
        assertThat(LedgerFlooredPostings.BY_NAMESPACE_SQL).as("the disclosure's count is unchanged - the check is -011's")
                .contains("entry.posting_date > ? AND entry.value_date <= ?")
                .doesNotContain("audit_record");
        String upper = sql.toUpperCase(Locale.ROOT);
        for (String forbidden : List.of("INSERT", "UPDATE", "DELETE", "FOR SHARE", "FOR UPDATE", "PG_ADVISORY")) {
            assertThat(upper).doesNotContain(forbidden);
        }
        assertThat(upper.substring(0, upper.indexOf(" WHERE "))).doesNotContain("ACTOR_ID").doesNotContain("REASON");
        Connection view = new ReadOnlySnapshot(new ScriptedStatements(ignored -> List.of(row())).connection(),
                Duration.ofSeconds(5), () -> 0L).connection();
        try (var admitted = view.prepareStatement(sql)) {
            assertThat(admitted).isNotNull();
        }
    }

    // -----------------------------------------------------------------

    private ReconciliationMonthReads reads() {
        return new ReconciliationMonthReads(readings);
    }

    private static ReportReadings.SuspenseAsOfLine line(SuspenseSide side, long minor, CurrencyCode currency, LocalDate openedOn) {
        return new ReportReadings.SuspenseAsOfLine(UUID.randomUUID(), side, Money.ofMinorUnits(minor, currency), openedOn,
                UUID.randomUUID());
    }
}
