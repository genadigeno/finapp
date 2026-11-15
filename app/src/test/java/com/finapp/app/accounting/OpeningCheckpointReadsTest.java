package com.finapp.app.accounting;

import static com.finapp.app.accounting.ScriptedStatements.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcOpeningCheckpointStore;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.Side;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.ReadOnlySnapshot;
import com.finapp.sharedkernel.id.IdGenerator;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The opening reads start from the latest valid checkpoint and fold only the lines after its month (`P16-REM-003`;
 * {@code THREAT_MODEL.md} section 6 item 64): the real adapters through the view a definition is really handed, so
 * {@code ReadOnlySnapshot} screens every statement, over a stand-in database. What each read prepares and binds, and what it returns -
 * the checkpoint's sums added to what it folded after - are held here; that the split equals the whole fold on PostgreSQL is
 * {@code OpeningCheckpointDatabaseTest}'s.
 */
@DisplayName("the opening reads start from the latest valid checkpoint and fold only the lines after it (P16-REM-003)")
class OpeningCheckpointReadsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final UUID CHECKPOINT = IDS.next();
    private static final UUID MARCH = IDS.next();
    private static final UUID JUNE = IDS.next();
    private static final LocalDate THROUGH = LocalDate.of(2097, 3, 31);
    private static final LocalDate BEFORE = LocalDate.of(2097, 6, 1);
    private static final Instant LOCKED_AT = Instant.parse("2097-04-05T09:00:00Z");
    private static final Instant LATEST_CREATED = Instant.parse("2097-04-01T00:00:02Z");
    private static final Instant CUT = Instant.parse("2097-06-02T12:00:00Z");
    private static final String LATEST = JdbcOpeningCheckpointStore.LATEST + JdbcOpeningCheckpointStore.NEWEST;
    private static final String LATEST_UNDER_THE_CUT =
            JdbcOpeningCheckpointStore.LATEST + JdbcOpeningCheckpointStore.UNDER_THE_CUT + JdbcOpeningCheckpointStore.NEWEST;

    private static Map<String, Object> group(String shape, String purpose, String ownerKind, String counterparty, String currency, int scale,
            String direction, long total, long lines) {
        Map<String, Object> group = row("shape", shape, "currency", currency, "scale", scale, "direction", direction, "total",
                BigDecimal.valueOf(total), "lines", lines);
        group.put("purpose", purpose);
        group.put("owner_kind", ownerKind);
        group.put("counterparty", counterparty);
        return group;
    }

    /** March's checkpoint, still under its lock: two GL groups, the ledger's two sides, one counterparty. */
    private static List<Map<String, Object>> checkpoint(String sql) {
        if (sql.contains("set_config")) {
            return List.of(row("1", "the bound"));
        }
        if (sql.equals(LATEST) || sql.equals(LATEST_UNDER_THE_CUT)) {
            return List.of(row("id", CHECKPOINT, "period_id", MARCH, "period_end", Date.valueOf(THROUGH), "period_locked_at",
                    Timestamp.from(LOCKED_AT), "latest_created", Timestamp.from(LATEST_CREATED)));
        }
        if (sql.equals(JdbcOpeningCheckpointStore.GROUPS)) {
            return List.of(group("GL", "CUSTOMER_FUNDS", "CUSTOMER", null, "USD", 2, "DEBIT", 1_000, 4),
                    group("GL", "SETTLEMENT_RECEIVABLE", "COUNTERPARTY", null, "USD", 2, "CREDIT", 1_000, 4),
                    group("LEDGER", null, null, null, "USD", 2, "DEBIT", 1_000, 4),
                    group("LEDGER", null, null, null, "USD", 2, "CREDIT", 1_000, 4),
                    group("COUNTERPARTY", "SETTLEMENT_RECEIVABLE", "COUNTERPARTY", "bank-a", "USD", 2, "CREDIT", 1_000, 4));
        }
        return List.of();
    }

    private static Function<String, List<Map<String, Object>>> answering(String after, List<Map<String, Object>> rows) {
        return sql -> sql.equals(after) ? rows : checkpoint(sql);
    }

    private static Connection view(ScriptedStatements script) {
        return new ReadOnlySnapshot(script.connection(), Duration.ofSeconds(5), () -> 0L).connection();
    }

    /** The reads' statements, the view's own bound ({@code set_config}) set aside. */
    private static List<String> reads(ScriptedStatements script) {
        return script.prepared.stream().filter(sql -> !sql.contains("set_config")).toList();
    }

    /** The reads' parameters, the view's own bound - a duration, in text - set aside. */
    private static List<Object> bound(ScriptedStatements script) {
        return script.bound.stream().filter(value -> !(value instanceof String)).toList();
    }

    private static LedgerTrialBalanceReads reads() {
        return new LedgerTrialBalanceReads(new JdbcGlMappingStore(), new JdbcOpeningCheckpointStore());
    }

    @Test
    @DisplayName("the GL opening: the checkpoint read, then only the lines dated after its month - its sums added group by group")
    void theGlOpeningStartsFromTheCheckpoint() {
        String after = LedgerTrialBalanceReads.BEFORE + LedgerTrialBalanceReads.AFTER + LedgerTrialBalanceReads.GL_GROUPS;
        ScriptedStatements script = new ScriptedStatements(answering(after, List.of(
                row("purpose", "CUSTOMER_FUNDS", "owner_kind", "CUSTOMER", "currency", "USD", "scale", 2, "direction", "DEBIT",
                        "total", BigDecimal.valueOf(25), "lines", 1L),
                row("purpose", "FEE_REVENUE", "owner_kind", "PLATFORM", "currency", "USD", "scale", 2, "direction", "CREDIT",
                        "total", BigDecimal.valueOf(25), "lines", 1L))));

        List<JournalReads.Aggregate> opening = reads().before(view(script), BEFORE, Optional.empty());

        assertThat(opening).containsExactly(
                new JournalReads.Aggregate("CUSTOMER_FUNDS", "CUSTOMER", Optional.empty(), "USD", 2, Side.DEBIT, BigInteger.valueOf(1_025), 5),
                new JournalReads.Aggregate("FEE_REVENUE", "PLATFORM", Optional.empty(), "USD", 2, Side.CREDIT, BigInteger.valueOf(25), 1),
                new JournalReads.Aggregate("SETTLEMENT_RECEIVABLE", "COUNTERPARTY", Optional.empty(), "USD", 2, Side.CREDIT,
                        BigInteger.valueOf(1_000), 4));
        assertThat(reads(script)).containsExactly(LATEST, JdbcOpeningCheckpointStore.GROUPS, after);
        assertThat(bound(script)).containsExactly(Date.valueOf(BEFORE), CHECKPOINT, Date.valueOf(BEFORE), Date.valueOf(THROUGH));
    }

    @Test
    @DisplayName("under a PROVISIONAL cut: the checkpoint asked under the cut, and the lines after it cut too")
    void theCutReachesBothStatements() {
        String after = LedgerTrialBalanceReads.BEFORE + LedgerTrialBalanceReads.AFTER + LedgerTrialBalanceReads.AT_THE_CUT
                + LedgerTrialBalanceReads.GL_GROUPS;
        ScriptedStatements script = new ScriptedStatements(answering(after, List.of()));

        assertThat(reads().before(view(script), BEFORE, Optional.of(CUT))).extracting(JournalReads.Aggregate::lines).containsExactly(4L, 4L);

        assertThat(reads(script)).containsExactly(LATEST_UNDER_THE_CUT, JdbcOpeningCheckpointStore.GROUPS, after);
        assertThat(bound(script)).containsExactly(Date.valueOf(BEFORE), Timestamp.from(CUT), CHECKPOINT, Date.valueOf(BEFORE),
                Date.valueOf(THROUGH), Timestamp.from(CUT));
    }

    @Test
    @DisplayName("no valid checkpoint: the opening folds from inception, as it always did")
    void noCheckpointFoldsFromInception() {
        ScriptedStatements script = new ScriptedStatements(sql -> sql.contains("set_config") ? List.of(row("1", "the bound")) : List.of());

        reads().before(view(script), BEFORE, Optional.empty());

        assertThat(reads(script)).containsExactly(LATEST, LedgerTrialBalanceReads.BEFORE + LedgerTrialBalanceReads.GL_GROUPS);
    }

    @Test
    @DisplayName("the ledger's windows: only the lines after the checkpoint, its sums added before the period and through its end; the"
            + " statement still names no accounting table")
    void theLedgerWindowsStartFromTheCheckpoint() {
        String after = LedgerTrialBalanceReads.LEDGER + LedgerTrialBalanceReads.AFTER + LedgerTrialBalanceReads.LEDGER_GROUPS;
        ScriptedStatements script = new ScriptedStatements(answering(after, List.of(row("currency", "USD", "scale", 2, "direction", "DEBIT",
                "before_total", BigDecimal.valueOf(25), "before_lines", 1L, "within_total", BigDecimal.valueOf(7), "within_lines", 1L,
                "through_total", BigDecimal.valueOf(32), "through_lines", 2L))));
        TrialBalanceReads.Period june = new TrialBalanceReads.Period(AccountingPeriodId.of(JUNE), BEFORE, LocalDate.of(2097, 6, 30),
                PeriodStatus.OPEN, Optional.empty());

        List<TrialBalanceReads.LedgerWindows> windows = reads().ledger(view(script), june, Optional.empty());

        assertThat(windows).containsExactly(
                new TrialBalanceReads.LedgerWindows("USD", 2, Side.CREDIT, BigInteger.valueOf(1_000), 4, BigInteger.ZERO, 0,
                        BigInteger.valueOf(1_000), 4),
                new TrialBalanceReads.LedgerWindows("USD", 2, Side.DEBIT, BigInteger.valueOf(1_025), 5, BigInteger.valueOf(7), 1,
                        BigInteger.valueOf(1_032), 6));
        assertThat(reads(script)).containsExactly(LATEST, JdbcOpeningCheckpointStore.GROUPS, after);
        assertThat(bound(script)).containsExactly(Date.valueOf(BEFORE), CHECKPOINT, Date.valueOf(BEFORE), Date.valueOf(LocalDate.of(2097, 6, 30)),
                Date.valueOf(THROUGH));
        assertThat(after).doesNotContain("accounting.");
    }

    @Test
    @DisplayName("the counterparties' opening: the checkpoint's counterparty sums plus the lines after its month")
    void theCounterpartiesStartFromTheCheckpoint() {
        String after = LedgerCounterpartyPositionReads.BEFORE + LedgerCounterpartyPositionReads.AFTER + LedgerCounterpartyPositionReads.GROUPS;
        ScriptedStatements script = new ScriptedStatements(answering(after, List.of(row("purpose", "SETTLEMENT_RECEIVABLE", "owner_kind",
                "COUNTERPARTY", "counterparty", "psp-b", "currency", "USD", "scale", 2, "direction", "CREDIT", "total", BigDecimal.valueOf(3),
                "lines", 1L))));

        List<JournalReads.Aggregate> opening = new LedgerCounterpartyPositionReads(new JdbcOpeningCheckpointStore())
                .before(view(script), BEFORE, Optional.empty());

        assertThat(opening).extracting(aggregate -> aggregate.counterparty().orElseThrow() + "=" + aggregate.minorUnits())
                .containsExactly("bank-a=1000", "psp-b=3");
        assertThat(reads(script)).containsExactly(LATEST, JdbcOpeningCheckpointStore.GROUPS, after);
        assertThat(bound(script)).containsExactly(Date.valueOf(BEFORE), CHECKPOINT, Date.valueOf(BEFORE), Date.valueOf(THROUGH));
    }

    @Test
    @DisplayName("built without a store - the proofs' and the tests' whole folds - the reads never ask for a checkpoint")
    void theWholeFoldReadsNoCheckpoint() {
        ScriptedStatements script = new ScriptedStatements(OpeningCheckpointReadsTest::checkpoint);
        TrialBalanceReads.Period june = new TrialBalanceReads.Period(AccountingPeriodId.of(JUNE), BEFORE, LocalDate.of(2097, 6, 30),
                PeriodStatus.OPEN, Optional.empty());

        new LedgerTrialBalanceReads(new JdbcGlMappingStore()).before(view(script), BEFORE, Optional.empty());
        new LedgerTrialBalanceReads(new JdbcGlMappingStore()).ledger(view(script), june, Optional.empty());
        new LedgerCounterpartyPositionReads().before(view(script), BEFORE, Optional.empty());

        assertThat(reads(script)).noneSatisfy(sql -> assertThat(sql).contains("opening_checkpoint"))
                .noneSatisfy(sql -> assertThat(sql).contains(LedgerTrialBalanceReads.AFTER));
    }

    @Test
    @DisplayName("the journal's creations: the latest created_at over the entries dated in the span - from inception, or after a day")
    void theCreationsReadIsBounded() {
        ScriptedStatements script = new ScriptedStatements(sql -> List.of(row("latest", Timestamp.from(LATEST_CREATED))));

        assertThat(new LedgerJournalCreations().latestCreated(script.connection(), Optional.empty(), THROUGH)).contains(LATEST_CREATED);
        assertThat(new LedgerJournalCreations().latestCreated(script.connection(), Optional.of(LocalDate.of(2097, 2, 28)), THROUGH))
                .contains(LATEST_CREATED);

        assertThat(reads(script)).containsExactly(LedgerJournalCreations.LATEST, LedgerJournalCreations.LATEST + LedgerJournalCreations.AFTER);
        assertThat(bound(script)).containsExactly(Date.valueOf(THROUGH), Date.valueOf(THROUGH), Date.valueOf(LocalDate.of(2097, 2, 28)));
        assertThat(LedgerJournalCreations.LATEST).doesNotContain("accounting.");
    }
}
