package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.FinalTrialBalance;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryReportRunStore;
import com.finapp.accounting.testing.TestReportDefinition;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The FINAL trial balance as the close reads it (`P14-TSK-011`; `P14-TSK-009`'s review, M-6; `P14-TSK-008`'s, M-7): claimed with no parameters,
 * under the definition's own constants; generated when held; read only when {@code FINAL} and of that code and version, each member exactly
 * {@code true}; every other standing named, never a pass. Hermetic; the path against PostgreSQL is
 * {@code CloseRunDatabaseTest}'s (owed, C5).
 */
@DisplayName("the FINAL trial balance, as the close reads it (P14-TSK-011)")
class FinalTrialBalanceReaderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryReportRunStore store = new InMemoryReportRunStore(CLOCK, periods);
    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return work.apply(UOW);
        }
    };
    private final ReportSnapshots snapshots = new ReportSnapshots() {
        @Override
        public <R> R read(Function<Connection, R> work) {
            if (snapshotDown) {
                throw new AccountingStorageException("opening a report snapshot: 08006");
            }
            return work.apply(UOW);
        }
    };
    private boolean snapshotDown;
    private Map<String, String> members = allTrue();
    private AccountingPeriodId march;

    @BeforeEach
    void marchIsLocked() {
        march = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(march, MARCH, CorrelationId.generate(IDS)));
        periods.markLocked(UOW, march, 1, Actor.SYSTEM, Optional.empty());
    }

    @Test
    @DisplayName("a locked period's run is claimed by the definition's constants with no parameters, generated, and read: FINAL, that code and"
            + " version, its stored hash and every flag")
    void aFinalRunIsRead() {
        FinalTrialBalance.Reading reading = reader(definition(TrialBalanceDefinition.VERSION)).read(march, Actor.SYSTEM, correlation());

        assertThat(reading).isInstanceOfSatisfying(FinalTrialBalance.Verdict.class, verdict -> {
            assertThat(verdict.holds()).isTrue();
            assertThat(verdict.fullyMapped()).isTrue();
            assertThat(verdict.definitionVersion()).isEqualTo(TrialBalanceDefinition.VERSION);
            assertThat(verdict.contentSha256()).isNotBlank();
        });
        ReportRunStore.RunRow run = store.all().get(0);
        assertThat(run.definition()).isEqualTo(TrialBalanceDefinition.CODE);
        assertThat(run.parameters()).isEqualTo(RunParameters.none());
        assertThat(run.status()).isEqualTo(RunStatus.FINAL);
    }

    @Test
    @DisplayName("each member is exactly true or false: a member false or absent fails that flag - a verdict never assumed")
    void aMemberAbsentIsFalse() {
        members = new HashMap<>(allTrue());
        members.remove(FinalTrialBalanceReader.PASSES);
        members.put("periodBalanced", "false");
        FinalTrialBalance.Reading reading = reader(definition(TrialBalanceDefinition.VERSION)).read(march, Actor.SYSTEM, correlation());
        assertThat(reading).isInstanceOfSatisfying(FinalTrialBalance.Verdict.class, verdict -> {
            assertThat(verdict.passes()).isFalse();
            assertThat(verdict.periodBalanced()).isFalse();
            assertThat(verdict.openingBalanced()).isTrue();
            assertThat(verdict.holds()).isFalse();
        });
    }

    @Test
    @DisplayName("a run held by a live generator is in progress - deferred by the caller, not a failure")
    void aLiveGeneratorsRunIsInProgress() {
        TestReportDefinition definition = definition(TrialBalanceDefinition.VERSION);
        ReportRuns runs = runs(definition);
        ReportRunStore.RunRow held = runs.claim(UOW, TrialBalanceDefinition.CODE, march, RunParameters.none(), Actor.SYSTEM, correlation()).run();
        assertThat(held.status()).isEqualTo(RunStatus.GENERATING);
        assertThat(reader(definition).read(march, Actor.SYSTEM, correlation())).isInstanceOf(FinalTrialBalance.InProgress.class);
    }

    @Test
    @DisplayName("a run of another version is not read: REPORT_RUN_NOT_FINAL - the check confirms code and version before the verdict")
    void aRunOfAnotherVersionIsNotRead() {
        FinalTrialBalance.Reading reading = reader(definition(TrialBalanceDefinition.VERSION + 1)).read(march, Actor.SYSTEM, correlation());
        assertThat(reading).isEqualTo(new FinalTrialBalance.Unavailable(FinalTrialBalanceReader.NOT_FINAL));
    }

    @Test
    @DisplayName("an open period's run is PROVISIONAL, never the close's: REPORT_RUN_NOT_FINAL")
    void aProvisionalRunIsNotRead() {
        AccountingPeriodId april = AccountingPeriodId.next(IDS);
        periods.closeForTest(MARCH);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(april, MARCH.plusMonths(1), correlation()));
        FinalTrialBalance.Reading reading = reader(definition(TrialBalanceDefinition.VERSION)).read(april, Actor.SYSTEM, correlation());
        assertThat(reading).isEqualTo(new FinalTrialBalance.Unavailable(FinalTrialBalanceReader.NOT_FINAL));
    }

    @Test
    @DisplayName("a run whose definition failed is REPORT_RUN_FAILED with its code; a new attempt births a new revision")
    void aFailedRunIsNamed() {
        TestReportDefinition throwing = new TestReportDefinition(TrialBalanceDefinition.CODE, TrialBalanceDefinition.VERSION, Set.of(),
                false, (snapshot, inputs) -> {
                    throw new IllegalStateException("a read it could not make");
                });
        FinalTrialBalanceReader reader = reader(throwing);
        assertThat(reader.read(march, Actor.SYSTEM, correlation()))
                .isEqualTo(new FinalTrialBalance.Unavailable(FinalTrialBalanceReader.RUN_FAILED + "DEFINITION_FAILED"));
        reader.read(march, Actor.SYSTEM, correlation());
        assertThat(store.all()).extracting(ReportRunStore.RunRow::revision).containsExactly(1, 2);
    }

    @Test
    @DisplayName("M-7: the snapshot's own failure is UNAVAILABLE:TRIAL_BALANCE and leaves the run GENERATING for the orphan leg - no RunFailure"
            + " code invented, never a pass")
    void aSnapshotsOwnFailureIsUnavailable() {
        snapshotDown = true;
        assertThat(reader(definition(TrialBalanceDefinition.VERSION)).read(march, Actor.SYSTEM, correlation()))
                .isEqualTo(new FinalTrialBalance.Unavailable(FinalTrialBalanceReader.UNAVAILABLE));
        assertThat(store.all().get(0).status()).isEqualTo(RunStatus.GENERATING);
    }

    @Test
    @DisplayName("the paths are the definition's own constant with its last member replaced; the statement only reads, one cast of the content")
    void thePathsAreTheConstants() {
        assertThat(FinalTrialBalanceReader.path(FinalTrialBalanceReader.PASSES)).isEqualTo(TrialBalanceDefinition.VERDICT_PATH);
        assertThat(FinalTrialBalanceReader.path("openingBalanced")).isEqualTo("{body,verdict,openingBalanced}");
        assertThat(FinalTrialBalanceReader.MEMBERS).containsExactly("passes", "openingBalanced", "periodBalanced", "balanced",
                "fullyMapped");
        assertThat(FinalTrialBalanceReader.VERDICT_SQL).startsWith("SELECT verdict.content #>> ?::text[]")
                .contains("(SELECT content::jsonb AS content FROM accounting.report_run WHERE id = ?)")
                .doesNotContain("FOR ").doesNotContain("UPDATE");
    }

    // ------------------------------------------------------------------ plumbing

    private static Map<String, String> allTrue() {
        Map<String, String> flags = new HashMap<>();
        FinalTrialBalanceReader.MEMBERS.forEach(member -> flags.put(member, "true"));
        return flags;
    }

    private TestReportDefinition definition(int version) {
        return new TestReportDefinition(TrialBalanceDefinition.CODE, version, Set.of(), false,
                (snapshot, inputs) -> CanonicalJson.object().put("verdict", CanonicalJson.object().put("passes", true)));
    }

    private ReportRuns runs(TestReportDefinition definition) {
        return new ReportRuns(store, periods, new InMemoryGlMappingStore(), ReportDefinitions.of(definition),
                ReportRuns.Leases.STANDARD, (uow, record) -> { }, (uow, envelope, payload, mediaType) -> { }, IDS, CLOCK);
    }

    private FinalTrialBalanceReader reader(TestReportDefinition definition) {
        ReportRuns runs = runs(definition);
        ReportDefinitions definitions = ReportDefinitions.of(definition);
        ReportRunGenerator generator = new ReportRunGenerator(runs, new ReportGeneration((snapshot, id) -> periods.find(snapshot, id)
                .map(row -> new ReportGeneration.PeriodState(row.status(), row.lockedAt())),
                ReportRuns.Leases.STANDARD.generationTimeout(), () -> 0L), snapshots, transactions,
                new ReportRunMetrics(new SimpleMeterRegistry(), definitions));
        return new FinalTrialBalanceReader(runs, generator, transactions, run -> members);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }
}
