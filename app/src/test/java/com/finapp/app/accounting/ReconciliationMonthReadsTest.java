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
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The month's reads over reconciliation and the journal (`P14-TSK-017`, `P14-TSK-012`; {@code INV-ACC-04}, {@code INV-ACC-05}; the owner's decisions of
 * 2026-11-02 and 2026-11-03; `P14-TSK-011`'s review, M-2): the runs of the month's business dates and the source register, what the check judges; the suspense
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
        runs.add(new ReportReadings.RunLine(source, RunKind.BATCH, RunStatus.COMPLETED, MARCH.atDay(30), false, 2));
        runs.add(new ReportReadings.RunLine(source, RunKind.BATCH, RunStatus.COMPLETED, MARCH.atDay(30), true, 1));
        runs.add(new ReportReadings.RunLine(source, RunKind.REPROCESS, RunStatus.OPEN, MARCH.atDay(31), false, 1));

        ReconciliationRunCoverage.Coverage coverage = reads().ofMonth(sources().connection(), MARCH);

        assertThat(asked).containsExactly(MARCH.atDay(1), CUTOFF);
        assertThat(coverage.month()).isEqualTo(MARCH);
        assertThat(coverage.lines()).containsExactly(
                new ReconciliationRunCoverage.Line(source, "BATCH", "COMPLETED", MARCH.atDay(30), false, 2),
                new ReconciliationRunCoverage.Line(source, "BATCH", "COMPLETED", MARCH.atDay(30), true, 1),
                new ReconciliationRunCoverage.Line(source, "REPROCESS", "OPEN", MARCH.atDay(31), false, 1));
        assertThat(coverage.batchRuns()).isEqualTo(3);

        runs.clear();
        assertThat(reads().ofMonth(sources().connection(), MARCH).batchRuns()).isZero();
    }

    @Test
    @DisplayName("what makes a source active (P14-TSK-012): its row ACTIVE and this build declaring it; a RETIRED row is retired; an ACTIVE row no build declares has no door; a declared code with no row is a deployment's defect, which throws - never an empty pass")
    void whichSourcesAreActive() {
        ReconciliationRunCoverage.Coverage coverage = reads().ofMonth(sources().connection(), MARCH);
        assertThat(coverage.sources()).extracting(ReconciliationRunCoverage.Source::code, ReconciliationRunCoverage.Source::state).containsExactly(
                org.assertj.core.groups.Tuple.tuple("simulated-bank.statement", ReconciliationRunCoverage.SourceState.ACTIVE),
                org.assertj.core.groups.Tuple.tuple("old-psp.settlement", ReconciliationRunCoverage.SourceState.RETIRED),
                org.assertj.core.groups.Tuple.tuple("nobody-declares.this", ReconciliationRunCoverage.SourceState.UNDECLARED));

        ReconciliationMonthReads missing = new ReconciliationMonthReads(readings, Set.of("simulated-bank.statement", "never-seeded.settlement"));
        assertThatThrownBy(() -> missing.ofMonth(sources().connection(), MARCH)).isInstanceOf(IllegalStateException.class).hasMessageContaining("deployment");
    }

    @Test
    @DisplayName("P14-TSK-025, the statement: one SELECT over settlement's source and reconciliation's rule set, each source with its two days, whether V002 seeded its rule set"
            + " and the first activation of a version ACTIVE or RETIRED; no lock, no write, and the proposer only compared, never selected")
    void theSourcesStatement() {
        String sql = ReconciliationMonthReads.SOURCES_SQL;
        assertThat(sql).startsWith("SELECT s.id, s.code, s.status, s.admitted_on, s.retired_on,")
                .contains("EXISTS (SELECT 1 FROM reconciliation.rule_set r WHERE r.source_id = s.id AND r.status IN ('ACTIVE', 'RETIRED')"
                        + " AND r.proposed_by = 'migration:V002') AS rule_set_seeded")
                .contains("(SELECT min(r.decided_at) FROM reconciliation.rule_set r WHERE r.source_id = s.id AND r.status IN ('ACTIVE', 'RETIRED'))"
                        + " AS rule_set_first_activated")
                .endsWith("FROM settlement.source s ORDER BY s.code");
        String upper = sql.toUpperCase(Locale.ROOT);
        for (String forbidden : List.of("INSERT", "UPDATE", "DELETE", "FOR SHARE", "FOR UPDATE", "PG_ADVISORY", "R.PROPOSED_BY,", "R.DECIDED_BY")) {
            assertThat(upper).as(forbidden).doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("P14-TSK-025, the mapping: the two days as stored; a seeded rule set stands from the source's admission; otherwise the first activation's UTC day,"
            + " on either side of midnight; never activated, none - so the source is required only from that day")
    void eachSourcesDays() {
        UUID seeded = UUID.nameUUIDFromBytes(new byte[] {1});
        UUID activated = UUID.nameUUIDFromBytes(new byte[] {2});
        UUID lateEvening = UUID.nameUUIDFromBytes(new byte[] {3});
        UUID never = UUID.nameUUIDFromBytes(new byte[] {4});
        UUID retired = UUID.nameUUIDFromBytes(new byte[] {5});
        ScriptedStatements script = new ScriptedStatements(sql -> List.of(
                source(seeded, "simulated-psp.settlement", "ACTIVE", LocalDate.EPOCH, null, true, OffsetDateTime.parse("2026-09-29T12:00:00Z")),
                source(activated, "fx-sim-a.trade-report", "ACTIVE", LocalDate.EPOCH, null, false, OffsetDateTime.parse("2097-03-31T23:59:59Z")),
                source(lateEvening, "fx-sim-b.trade-report", "ACTIVE", LocalDate.EPOCH, null, false, OffsetDateTime.parse("2097-03-31T23:30:00-02:00")),
                source(never, "corridor-sim-a.settlement", "ACTIVE", LocalDate.EPOCH, null, false, null),
                source(retired, "old-psp.settlement", "RETIRED", LocalDate.of(2001, 1, 1), LocalDate.of(2097, 3, 1), false, OffsetDateTime.parse("2001-01-01T00:00:00Z"))));
        ReconciliationRunCoverage.Coverage coverage = new ReconciliationMonthReads(readings,
                Set.of("simulated-psp.settlement", "fx-sim-a.trade-report", "fx-sim-b.trade-report", "corridor-sim-a.settlement")).ofMonth(script.connection(), MARCH);

        assertThat(coverage.sources()).extracting(ReconciliationRunCoverage.Source::code, ReconciliationRunCoverage.Source::ruleSetActiveOn).containsExactly(
                org.assertj.core.groups.Tuple.tuple("simulated-psp.settlement", Optional.of(LocalDate.EPOCH)),
                org.assertj.core.groups.Tuple.tuple("fx-sim-a.trade-report", Optional.of(LocalDate.of(2097, 3, 31))),
                org.assertj.core.groups.Tuple.tuple("fx-sim-b.trade-report", Optional.of(LocalDate.of(2097, 4, 1))),
                org.assertj.core.groups.Tuple.tuple("corridor-sim-a.settlement", Optional.empty()),
                org.assertj.core.groups.Tuple.tuple("old-psp.settlement", Optional.of(LocalDate.of(2001, 1, 1))));
        assertThat(coverage.sources().get(4).retiredOn()).contains(LocalDate.of(2097, 3, 1));
        assertThat(coverage.sources().get(4).admittedOn()).isEqualTo(LocalDate.of(2001, 1, 1));
        assertThat(coverage.required()).extracting(ReconciliationRunCoverage.Source::code)
                .as("the seed, activated on the last day, and retired within the month: required; activated the next UTC day, or never: not")
                .containsExactly("simulated-psp.settlement", "fx-sim-a.trade-report", "old-psp.settlement");
        assertThat(ReconciliationMonthReads.ruleSetActiveOn(LocalDate.of(1999, 1, 1), true, Optional.empty())).contains(LocalDate.of(1999, 1, 1));
    }

    private static Map<String, Object> source(UUID id, String code, String status, LocalDate admittedOn, LocalDate retiredOn, boolean seeded,
            OffsetDateTime firstActivated) {
        Map<String, Object> row = row("id", id, "code", code, "status", status, "admitted_on", admittedOn, "rule_set_seeded", seeded);
        row.put("retired_on", retiredOn);
        row.put("rule_set_first_activated", firstActivated);
        return row;
    }

    private static ScriptedStatements sources() {
        return new ScriptedStatements(sql -> List.of(
                source(UUID.nameUUIDFromBytes(new byte[] {1}), "simulated-bank.statement", "ACTIVE", LocalDate.EPOCH, null, true, null),
                source(UUID.nameUUIDFromBytes(new byte[] {2}), "old-psp.settlement", "RETIRED", LocalDate.EPOCH, LocalDate.of(2097, 1, 31), true, null),
                source(UUID.nameUUIDFromBytes(new byte[] {3}), "nobody-declares.this", "ACTIVE", LocalDate.EPOCH, null, false, null)));
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
        assertThatThrownBy(() -> new ReconciliationMonthReads(failing, Set.of()).ofCutoff(NO_CONNECTION, MARCH))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ReconciliationMonthReads(failing, Set.of()).ofMonth(NO_CONNECTION, MARCH))
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
    @DisplayName("M-2, the owner's (b): per floored namespace the entries past the cutoff, split - floored by the posting's own audit mark, unrecorded where no such record stands, the rest"
            + " the rule's own dating: a batch accepted on the 1st for the 31st is not a floor; a missing record is never its own dating; a namespace of no floored rule is not reported")
    void whichIsWhich() {
        ScriptedStatements script = new ScriptedStatements(sql -> List.of(
                row("namespace", "settlement-batch", "entries", 7L, "floored", 2L, "unrecorded", 1L),
                row("namespace", "transfer", "entries", 99L, "floored", 0L, "unrecorded", 0L)));
        LedgerFlooredPostings postings = new LedgerFlooredPostings(PostingRuleRegister.CURRENT.rules());

        SortedMap<String, PastTheCutoffPostings.Split> split = postings.pastTheCutoff(script.connection(), CUTOFF);

        assertThat(script.bound).containsExactly(Date.valueOf(CUTOFF), Date.valueOf(CUTOFF), "ledger.post:%");
        assertThat(split).containsOnlyKeys("settlement-batch");
        assertThat(split.get("settlement-batch").floored()).isEqualTo(2);
        assertThat(split.get("settlement-batch").unrecorded()).isEqualTo(1);
        assertThat(split.get("settlement-batch").countsAsFloored()).isEqualTo(3);
        assertThat(split.get("settlement-batch").ownValueDate()).isEqualTo(4);
    }

    @Test
    @DisplayName("the statement: one SELECT the report's view admits, no lock; 'record present, no mark' told from 'no record' in one read, the posting's own record alone - a GL read of the entry"
            + " shares its target type and says nothing about a floor; nothing but existence - never a summary, actor or reference")
    void theStatement() throws java.sql.SQLException {
        String sql = LedgerFlooredPostings.PAST_THE_CUTOFF_SQL;
        assertThat(sql).contains("entry.posting_date > ? AND entry.value_date <= ?")
                .contains("entry.idempotency_scope LIKE ?")
                .contains("EXISTS (SELECT 1 FROM platform.audit_record audit")
                .contains("audit.target_type = 'journal_entry' AND audit.target_id = entry.id::text")
                .contains("audit.operation IN ('ledger.JournalEntryPosted', 'ledger.AdjustmentPosted')")
                .contains(") AS recorded,")
                .contains("audit.change_summary LIKE '%flooredFrom=%') AS floored")
                .contains("count(*) FILTER (WHERE NOT past.recorded) AS unrecorded");
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
        return new ReconciliationMonthReads(readings, Set.of("simulated-bank.statement"));
    }

    private static ReportReadings.SuspenseAsOfLine line(SuspenseSide side, long minor, CurrencyCode currency, LocalDate openedOn) {
        return new ReportReadings.SuspenseAsOfLine(UUID.randomUUID(), side, Money.ofMinorUnits(minor, currency), openedOn,
                UUID.randomUUID());
    }
}
