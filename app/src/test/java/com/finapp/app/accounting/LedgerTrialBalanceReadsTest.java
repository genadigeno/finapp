package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.reporting.ReadOnlySnapshot;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The trial balance's port over the ledger's tables and accounting's own (`P14-TSK-009`; {@code INV-ACC-01},
 * {@code INV-ACC-06}, {@code INV-ACC-14}) - its statements' shape, which the database suite proves in behaviour
 * ({@code TrialBalanceRunDatabaseTest}, owed, C5): every one a single {@code SELECT} the definition's
 * {@link ReadOnlySnapshot} view admits, locking nothing, never reading a reason, actor or reference; the opening bounded
 * above only (from inception); the ledger's windows joining no account and taking their bounds from the period's row;
 * a PROVISIONAL run's cut filtered on {@code created_at}, never selected; and the definition registered.
 */
@DisplayName("the trial balance's port and its registration (P14-TSK-009)")
class LedgerTrialBalanceReadsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    private static List<String> statements() {
        List<String> all = new ArrayList<>(List.of(LedgerTrialBalanceReads.PERIOD));
        for (String cut : List.of("", LedgerTrialBalanceReads.AT_THE_CUT)) {
            all.add(LedgerTrialBalanceReads.BEFORE + cut + LedgerTrialBalanceReads.GL_GROUPS);
            all.add(LedgerTrialBalanceReads.WITHIN + cut + LedgerTrialBalanceReads.GL_GROUPS);
            all.add(LedgerTrialBalanceReads.LEDGER + cut + LedgerTrialBalanceReads.LEDGER_GROUPS);
        }
        return all;
    }

    @Test
    @DisplayName("every statement is one SELECT the restricted view admits - no write, no lock, never the entry's"
            + " reference, reason or actor_id; created_at only as the cut's filter")
    void everyStatementOnlyReads() {
        Connection view = new ReadOnlySnapshot(recording(new ArrayList<>(), new ArrayList<>()), Duration.ofSeconds(5),
                () -> 0L).connection();
        for (String sql : statements()) {
            String upper = sql.toUpperCase(Locale.ROOT);
            assertThat(upper).startsWith("SELECT ");
            for (String forbidden : List.of("INSERT", "UPDATE", "DELETE", "FOR SHARE", "FOR UPDATE", "PG_ADVISORY",
                    "REASON", "ACTOR_ID", "REFERENCE")) {
                assertThat(upper).as("%s in %s", forbidden, sql).doesNotContain(forbidden);
            }
            assertThat(upper.substring(0, upper.indexOf(" FROM "))).as("created_at is never selected")
                    .doesNotContain("CREATED_AT");
            try (PreparedStatement admitted = view.prepareStatement(sql)) {
                assertThat(admitted).isNotNull();
            } catch (java.sql.SQLException impossible) {
                throw new AssertionError(impossible);
            }
        }
        assertThat(LedgerTrialBalanceReads.AT_THE_CUT).isEqualTo(" AND entry.created_at <= ?");
    }

    @Test
    @DisplayName("the two sides: the opening bounded above only (inception), the movement both ways inclusive, grouped by"
            + " scale; the ledger's windows in TrialBalance.sweep's shape - no account joined - bounded by the period row")
    void theSidesAreIndependent() {
        assertThat(LedgerTrialBalanceReads.BEFORE).endsWith("WHERE entry.posting_date < ?")
                .contains("JOIN ledger.ledger_account account");
        assertThat(LedgerTrialBalanceReads.WITHIN).endsWith("WHERE entry.posting_date >= ? AND entry.posting_date <= ?");
        assertThat(LedgerTrialBalanceReads.GL_GROUPS)
                .startsWith(" GROUP BY account.purpose, account.owner_kind, line.currency, line.scale, line.direction");
        assertThat(LedgerTrialBalanceReads.LEDGER).doesNotContain("ledger_account").doesNotContain("account.")
                .contains("JOIN accounting.accounting_period bounds ON bounds.id = ?")
                .contains("FILTER (WHERE entry.posting_date < bounds.period_start)")
                .contains("FILTER (WHERE entry.posting_date >= bounds.period_start)")
                .endsWith("WHERE entry.posting_date <= bounds.period_end");
        assertThat(LedgerTrialBalanceReads.LEDGER_GROUPS)
                .isEqualTo(" GROUP BY line.currency, line.scale, line.direction ORDER BY line.currency, line.scale,"
                        + " line.direction");
    }

    @Test
    @DisplayName("the binding: the opening's bound and the cut, the movement's two days, the ledger's period id alone - its"
            + " bounds never passed in; a FINAL run's read carries no cut")
    void theBinding() {
        List<String> prepared = new ArrayList<>();
        List<Object> bound = new ArrayList<>();
        Connection snapshot = recording(prepared, bound);
        LedgerTrialBalanceReads reads = new LedgerTrialBalanceReads(new InMemoryGlMappingStore());
        Instant cut = Instant.parse("2097-06-20T10:00:00Z");
        AccountingPeriodId period = AccountingPeriodId.next(IDS);

        assertThat(reads.before(snapshot, LocalDate.of(2097, 6, 1), Optional.of(cut))).isEmpty();
        assertThat(prepared.get(0)).contains(LedgerTrialBalanceReads.AT_THE_CUT);
        assertThat(bound).containsExactly(java.sql.Date.valueOf("2097-06-01"), Timestamp.from(cut));

        prepared.clear();
        bound.clear();
        assertThat(reads.within(snapshot, JournalReads.Range.of(YearMonth.of(2097, 6)), Optional.empty())).isEmpty();
        assertThat(prepared.get(0)).doesNotContain("created_at");
        assertThat(bound).containsExactly(java.sql.Date.valueOf("2097-06-01"), java.sql.Date.valueOf("2097-06-30"));

        prepared.clear();
        bound.clear();
        assertThat(reads.ledger(snapshot, period, Optional.empty())).isEmpty();
        assertThat(bound).containsExactly(period.value());

        prepared.clear();
        bound.clear();
        assertThat(reads.period(snapshot, period)).isEmpty();
        assertThat(prepared).containsExactly(LedgerTrialBalanceReads.PERIOD);
    }

    @Test
    @DisplayName("registered: TrialBalanceBeans declares the TRIAL_BALANCE definition over this port, so the report-run"
            + " door's registry holds it")
    void theDefinitionIsRegistered() {
        TrialBalanceBeans beans = new TrialBalanceBeans();
        TrialBalanceDefinition definition = beans.trialBalanceDefinition(
                beans.trialBalanceReads(new InMemoryGlMappingStore()));
        ReportDefinitions registry = ReportDefinitions.of(definition);
        assertThat(registry.current("TRIAL_BALANCE")).containsSame(definition);
        assertThat(registry.codes()).containsExactly("TRIAL_BALANCE");
    }

    @Test
    @DisplayName("every read reaches the database through the definition's restricted view: the period, the journal's"
            + " three statements and the mapping store's own version read - each admitted, each run inside the bound")
    void everyReadPassesTheView() {
        List<String> prepared = new ArrayList<>();
        Connection view = new ReadOnlySnapshot(recording(prepared, new ArrayList<>()), Duration.ofSeconds(5),
                () -> 0L).connection();
        LedgerTrialBalanceReads reads = new LedgerTrialBalanceReads(new JdbcGlMappingStore());
        AccountingPeriodId period = AccountingPeriodId.next(IDS);
        assertThat(reads.period(view, period)).isEmpty();
        assertThat(reads.before(view, LocalDate.of(2097, 6, 1), Optional.of(Instant.EPOCH))).isEmpty();
        assertThat(reads.within(view, JournalReads.Range.of(YearMonth.of(2097, 6)), Optional.empty())).isEmpty();
        assertThat(reads.ledger(view, period, Optional.of(Instant.EPOCH))).isEmpty();
        assertThatThrownBy(() -> reads.version(view, GlMappingVersionId.next(IDS)))
                .as("the store's own read admitted and run - it then finds no version, never a refusal")
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(ReadOnlySnapshot.Refused.class)
                .hasMessageContaining("a version that exists");
        assertThat(prepared).filteredOn(sql -> sql.contains("set_config")).as("the bound armed before each read")
                .hasSize(5);
        assertThat(prepared).anyMatch(sql -> sql.contains("FROM accounting.gl_mapping_version"));
    }

    /** A connection whose statements record their SQL and bound values; the view's bound answers one row, all else none. */
    private static Connection recording(List<String> prepared, List<Object> bound) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        prepared.add((String) args[0]);
                        yield statement(bound, ((String) args[0]).contains("set_config"));
                    }
                    case "toString" -> "a recording connection";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PreparedStatement statement(List<Object> bound, boolean oneRow) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set") && args != null && args.length == 2) {
                        bound.add(args[1]);
                        return null;
                    }
                    return switch (name) {
                        case "executeQuery" -> rows(oneRow ? 1 : 0);
                        case "close" -> null;
                        case "toString" -> "a recording statement";
                        default -> throw new UnsupportedOperationException(name);
                    };
                });
    }

    private static ResultSet rows(int count) {
        int[] left = {count};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> left[0]-- > 0;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
