package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.LockGrace;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.ReportRunId;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunFailure;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryReportRunStore;
import com.finapp.accounting.testing.TestReportDefinition;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.metrics.MetricNames;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The report run doors and T-report's legs (`P14-TSK-008`; ADR-0115; {@code PHASE_14_PLAN.md} sections 8.1, 8.4 (c), 10,
 * 12, 16): a generation keyed per principal and replayed as the same run, a generation never returning content, the read
 * audited before it is served, a regeneration compared byte for byte - IDENTICAL, or DIVERGED counted once and never
 * repaired - the failures sorted into their codes with the run left {@code GENERATING} when the snapshot itself failed,
 * the orphan leg reclaiming a dead generator's run, the routes on identity V025's partition, the meters on the allowed
 * tags. Hermetic: the stores, the claims and the snapshot are in memory; the snapshot's read-only-ness is
 * {@code JdbcReportSnapshotsTest}'s and the database's ({@code ReportRunDatabaseTest}, owed C5).
 */
@DisplayName("the report run doors (P14-TSK-008)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class ReportRunDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            ReportRunDeskTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-10T09:00:00Z"));
    private final InMemoryAccountingPeriodStore periodStore = new InMemoryAccountingPeriodStore(clock);
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final InMemoryReportRunStore store = new InMemoryReportRunStore(clock, periodStore);
    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicBoolean snapshotDown = new AtomicBoolean();
    private final List<String> snapshotsOpened = new ArrayList<>();
    /** What the leaky definition reads: a figure the lock does not freeze - its regeneration diverges. */
    private String leaked = "first";
    /** The generation's monotonic clock - moved by a slow definition past its budget. */
    private final java.util.concurrent.atomic.AtomicLong ticker = new java.util.concurrent.atomic.AtomicLong();
    /** Whether the sometimes-slow definition spends its budget on its next generation. */
    private boolean slow;

    private final TestReportDefinition echo = TestReportDefinition.echo();
    private final TestReportDefinition leaky = new TestReportDefinition("TEST_LEAKY", 1, java.util.Set.of("probe"),
            false, (snapshot, inputs) -> CanonicalJson.object().put("leaked", leaked));
    private final TestReportDefinition throwing = new TestReportDefinition("TEST_THROWING", 1, java.util.Set.of(),
            false, (snapshot, inputs) -> {
                throw new IllegalStateException("cannot execute UPDATE in a read-only transaction");
            });
    /** A definition that, when {@link #slow}, spends past its budget and then reads - the view refuses the read. */
    private final TestReportDefinition sometimesSlow = new TestReportDefinition("TEST_SLOW", 1, java.util.Set.of("probe"),
            false, (snapshot, inputs) -> {
                if (slow) {
                    ticker.addAndGet(ReportRuns.Leases.STANDARD.generationTimeout().plusSeconds(1).toNanos());
                    try {
                        snapshot.prepareStatement("SELECT 1").executeQuery();
                    } catch (java.sql.SQLException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                }
                return CanonicalJson.object().put("slow", false);
            });
    /** A definition a run of which may name a valuation rate set (`P14-TSK-015`). */
    private final TestReportDefinition translating = new TestReportDefinition("TEST_TRANSLATING", 1, java.util.Set.of(),
            false, (snapshot, inputs) -> CanonicalJson.object().put("translated", true)).takingARateSet();
    /** A definition reading what moves after a lock: never a run (`P14-TSK-017`). */
    private final com.finapp.accounting.reporting.OperationalReportDefinition moving =
            new com.finapp.accounting.reporting.OperationalReportDefinition() {
                @Override
                public String code() {
                    return "TEST_MOVING";
                }

                @Override
                public int version() {
                    return 1;
                }

                @Override
                public com.finapp.accounting.reporting.ReportStatement statement() {
                    return echo.statement();
                }

                @Override
                public CanonicalJson.Obj generate(java.sql.Connection snapshot, com.finapp.accounting.reporting.ReportInputs inputs) {
                    throw new AssertionError("an operational definition is never generated as a run");
                }
            };
    /** Whether the refusing definition finds what it cannot stand on its next generation. */
    private boolean refusing;
    /** A definition that refuses to answer (`P14-TSK-016`). */
    private final TestReportDefinition refuser = new TestReportDefinition("TEST_REFUSING", 1, java.util.Set.of(), false, (snapshot, inputs) -> {
        if (refusing) {
            throw new com.finapp.accounting.reporting.ReportRefused(com.finapp.accounting.reporting.ReportRefused.Reason.OUT_OF_BALANCE, List.of("EUR"));
        }
        return CanonicalJson.object().put("balanced", true);
    });
    /** A definition issued only when closed (`P14-TSK-016`). */
    private final com.finapp.accounting.reporting.ReportDefinition awaitingTheClose = new com.finapp.accounting.reporting.ReportDefinition() {
        @Override
        public String code() {
            return "TEST_AWAITING";
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public boolean issuedOnlyWhenClosed() {
            return true;
        }

        @Override
        public com.finapp.accounting.reporting.ReportStatement statement() {
            return echo.statement();
        }

        @Override
        public CanonicalJson.Obj generate(java.sql.Connection snapshot, com.finapp.accounting.reporting.ReportInputs inputs) {
            return CanonicalJson.object().put("closed", true);
        }
    };
    private final ReportDefinitions definitions = ReportDefinitions.of(echo, leaky, throwing, sometimesSlow, translating, moving, refuser, awaitingTheClose);

    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            Map<IdempotencyKey, IdempotencyRecord> held = new HashMap<>(claims);
            int records = audited.size();
            try {
                return work.apply(UOW);
            } catch (RuntimeException failed) {
                claims.clear();
                claims.putAll(held);
                audited.subList(records, audited.size()).clear();
                throw failed;
            }
        }
    };
    private final ReportSnapshots snapshots = new ReportSnapshots() {
        @Override
        public <R> R read(Function<Connection, R> work) {
            if (snapshotDown.get()) {
                throw new com.finapp.accounting.AccountingStorageException("opening a report snapshot: 08006");
            }
            snapshotsOpened.add("snapshot");
            return work.apply(RAW);
        }
    };
    private final ReportRuns runs = new ReportRuns(store, periodStore, mappings, definitions,
            ReportRuns.Leases.STANDARD, (uow, record) -> audited.add(record),
            (uow, envelope, payload, mediaType) -> events.add(envelope.eventType()), IDS, clock);
    private final ReportRunMetrics metrics = new ReportRunMetrics(registry, definitions);
    private final ReportRunGenerator generator = new ReportRunGenerator(runs, new ReportGeneration(
            (snapshot, id) -> periodStore.find(snapshot, id)
                    .map(row -> new ReportGeneration.PeriodState(row.status(), row.lockedAt())),
            ReportRuns.Leases.STANDARD.generationTimeout(), ticker::get), snapshots, transactions, metrics);
    private final ReportRunDesk desk = new ReportRunDesk(runs, generator, snapshots,
            new IdempotentExecutor(new Claims(), clock, Duration.ofDays(1), Duration.ofSeconds(30)), transactions,
            AccountingFlowScope.NONE);

    @Test
    @DisplayName("a generation claims, generates on a snapshot and completes FINAL for a locked period - never returning"
            + " content; a replayed key answers the same run and writes nothing; another key converges on the run")
    void aGenerationIsKeyedAndConverges() {
        String september = lockedSeptember();
        ReportRunDesk.ReportRunView run = as(ACCOUNTANT,
                () -> desk.generate("key-1", TestReportDefinition.CODE, september, Map.of("probe", "a"), null));
        assertThat(run.status()).isEqualTo("FINAL");
        assertThat(run.basis()).isEqualTo("FINAL");
        assertThat(run.period()).isEqualTo("2026-09");
        assertThat(run.parameters()).containsExactly(Map.entry("probe", "a"));
        assertThat(run.contentSha256()).hasSize(64);
        assertThat(as(ACCOUNTANT, () -> desk.generate("key-1", TestReportDefinition.CODE, september,
                Map.of("probe", "a"), null))).isEqualTo(run);
        assertThat(as(CONTROLLER, () -> desk.generate("key-2", TestReportDefinition.CODE, september,
                Map.of("probe", "a"), null)).id()).isEqualTo(run.id());
        assertThat(store.all()).hasSize(1);
        assertThat(events).containsExactly(ReportRuns.COMPLETED_EVENT);
        assertThat(audited).extracting(record -> record.operation().code()).containsExactly("accounting.ReportGenerated");
        assertThat(registry.get(ReportRunMetrics.RUN).tag("definition", TestReportDefinition.CODE)
                .tag("outcome", "final").counter().count()).isEqualTo(1.0);
        // The key binds the request: the same key for another question is a conflict, never a second run.
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.generate("key-1", TestReportDefinition.CODE, september,
                Map.of("probe", "b"), null))).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("a definition's refusal (P14-TSK-016): the run FAILED, DEFINITION_FAILED, the reason by code on its record, no event; the next ask a new revision, which answers once what it found has gone")
    void aRefusalIsAFailedRunWithItsReason() {
        String september = lockedSeptember();
        refusing = true;
        ReportRunDesk.ReportRunView failed = as(ACCOUNTANT, () -> desk.generate("key-1", "TEST_REFUSING", september, Map.of(), null));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failure()).isEqualTo("DEFINITION_FAILED");
        assertThat(failed.contentSha256()).isNull();
        assertThat(events).isEmpty();
        assertThat(audited).singleElement().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("accounting.ReportGenerated");
            assertThat(record.outcome()).isEqualTo(com.finapp.platform.audit.AuditOutcome.FAILED);
            assertThat(record.changeSummary()).hasValueSatisfying(summary -> assertThat(summary).contains("failure=DEFINITION_FAILED").contains("refusal=OUT_OF_BALANCE:EUR"));
        });
        assertThat(registry.get(ReportRunMetrics.RUN).tag("definition", "TEST_REFUSING").tag("outcome", "failed").counter().count()).isEqualTo(1.0);
        // P14-TSK-020 (P14-TSK-016's routed note): by class, the reason staying on the record above.
        assertThat(registry.get(ReportRunMetrics.REFUSED).tag("outcome", ReportRunMetrics.REFUSED_FINAL).counter().count()).as("a locked month's run is FINAL").isEqualTo(1.0);
        assertThat(registry.get(ReportRunMetrics.REFUSED).tag("outcome", ReportRunMetrics.REFUSED_PROVISIONAL).counter().count()).isZero();

        refusing = false;
        ReportRunDesk.ReportRunView again = as(ACCOUNTANT, () -> desk.generate("key-2", "TEST_REFUSING", september, Map.of(), null));
        assertThat(again.status()).isEqualTo("FINAL");
        assertThat(again.revision()).isEqualTo(failed.revision() + 1);
    }

    @Test
    @DisplayName("a FINAL run its definition now refuses (P14-TSK-016): the regeneration reads DIVERGED, with no hash to compare, counted once, the refusal by code on its record: the books moved under an issued statement, which is what this door exists to find")
    void aRefusedRegenerationIsADivergence() {
        String september = lockedSeptember();
        ReportRunDesk.ReportRunView run = as(ACCOUNTANT, () -> desk.generate("key-1", "TEST_REFUSING", september, Map.of(), null));
        assertThat(run.status()).isEqualTo("FINAL");
        refusing = true;
        ReportRunDesk.ReportRegenerationView compared = as(ACCOUNTANT, () -> desk.regenerate("key-regen", run.id()));
        assertThat(compared.result()).isEqualTo("DIVERGED");
        assertThat(compared.contentSha256()).isEqualTo(run.contentSha256());
        assertThat(compared.regeneratedSha256()).isEqualTo(ReportRunGenerator.NOT_PRODUCED).isEmpty();
        assertThat(registry.get(ReportRunMetrics.DIVERGED).tag("definition", "TEST_REFUSING").counter().count()).isEqualTo(1.0);
        assertThat(audited).filteredOn(record -> record.operation().code().equals("accounting.ReportRegenerated")).singleElement().satisfies(record -> assertThat(record.changeSummary())
                .as("the record is the one place the reason is: a claim of this question answers the standing FINAL, and generates nothing").hasValueSatisfying(summary -> assertThat(summary).contains("result=DIVERGED").contains("refusal=OUT_OF_BALANCE:EUR")));
        assertThat(as(ACCOUNTANT, () -> desk.generate("key-again", "TEST_REFUSING", september, Map.of(), null)).status()).as("asked again, the FINAL stands").isEqualTo("FINAL");
    }

    @Test
    @DisplayName("a refusal's class (P14-TSK-020): FINAL pages, PROVISIONAL does not, and a set's hole is configuration on any month; three classes, never the reason, which stays on the record")
    void aRefusalsClass() {
        metrics.refused(com.finapp.accounting.RunBasis.FINAL, com.finapp.accounting.reporting.ReportRefused.Reason.OUT_OF_BALANCE);
        metrics.refused(com.finapp.accounting.RunBasis.PROVISIONAL, com.finapp.accounting.reporting.ReportRefused.Reason.UNCLASSIFIED);
        metrics.refused(com.finapp.accounting.RunBasis.FINAL, com.finapp.accounting.reporting.ReportRefused.Reason.TRANSLATION_INCOMPLETE);
        metrics.refused(com.finapp.accounting.RunBasis.PROVISIONAL, com.finapp.accounting.reporting.ReportRefused.Reason.TRANSLATION_INCOMPLETE);
        assertThat(registry.get(ReportRunMetrics.REFUSED).tag("outcome", ReportRunMetrics.REFUSED_FINAL).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(ReportRunMetrics.REFUSED).tag("outcome", ReportRunMetrics.REFUSED_PROVISIONAL).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(ReportRunMetrics.REFUSED).tag("outcome", ReportRunMetrics.REFUSED_TRANSLATION).counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("an identical regeneration's record names no refusal (P14-TSK-016)")
    void anIdenticalRegenerationNamesNoRefusal() {
        String september = lockedSeptember();
        ReportRunDesk.ReportRunView run = as(ACCOUNTANT, () -> desk.generate("key-1", "TEST_REFUSING", september, Map.of(), null));
        assertThat(as(ACCOUNTANT, () -> desk.regenerate("key-regen", run.id())).result()).isEqualTo("IDENTICAL");
        assertThat(audited).filteredOn(record -> record.operation().code().equals("accounting.ReportRegenerated")).singleElement().satisfies(record -> assertThat(record.changeSummary()).hasValueSatisfying(summary -> assertThat(summary).contains("result=IDENTICAL").doesNotContain("refusal=")));
    }

    @Test
    @DisplayName("a statement of a period locked and not yet closed (P14-TSK-016): 409 accounting.ReportAwaitsClose, nothing claimed, naming the month and no figure")
    void aStatementOfALockedPeriodAwaitsTheClose() {
        String september = lockedSeptember();
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.generate("key-1", "TEST_AWAITING", september, Map.of(), null))).isInstanceOfSatisfying(com.finapp.platform.api.ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(com.finapp.accounting.AccountingErrorCode.REPORT_AWAITS_CLOSE);
            assertThat(refused.errorCode().status()).isEqualTo(409);
            assertThat(refused.getMessage()).doesNotContain("minor");
        });
        assertThat(store.all()).isEmpty();
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("the read is audited before it is served: the issued text, verifiable against its hash; an unknown run is"
            + " 404 and records nothing")
    void theReadIsAudited() {
        String september = lockedSeptember();
        ReportRunDesk.ReportRunView run = as(ACCOUNTANT,
                () -> desk.generate("key-1", TestReportDefinition.CODE, september, Map.of("probe", "r"), null));
        audited.clear();
        ReportRunDesk.ReportRunContentView read = as(ACCOUNTANT, () -> desk.read(run.id()));
        assertThat(read.run()).isEqualTo(run);
        assertThat(CanonicalJson.sha256(read.content())).isEqualTo(run.contentSha256());
        assertThat(audited).singleElement().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("accounting.ReportRead");
            assertThat(record.targetId()).isEqualTo(run.id());
            assertThat(record.actor()).isEqualTo(ACCOUNTANT);
            assertThat(record.changeSummary()).hasValueSatisfying(s -> assertThat(s).doesNotContain(read.content()));
        });
        refused(() -> as(ACCOUNTANT, () -> desk.read(ReportRunId.next(IDS).value().toString())),
                AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk.read("not-a-run")), AccountingErrorCode.NOT_FOUND, 404);
        assertThat(audited).hasSize(1);
    }

    @Test
    @DisplayName("a FINAL run regenerates IDENTICAL, recorded and keyed; a run whose definition read what the lock does not"
            + " freeze regenerates DIVERGED - counted once, audited, never repaired")
    void regenerationIsComparedAndDivergenceCounted() {
        String september = lockedSeptember();
        ReportRunDesk.ReportRunView run = as(ACCOUNTANT,
                () -> desk.generate("key-1", TestReportDefinition.CODE, september, Map.of("probe", "i"), null));
        ReportRunDesk.ReportRegenerationView identical = as(ACCOUNTANT, () -> desk.regenerate("regen-1", run.id()));
        assertThat(identical.result()).isEqualTo("IDENTICAL");
        assertThat(identical.regeneratedSha256()).isEqualTo(identical.contentSha256()).isEqualTo(run.contentSha256());
        ReportRunDesk.ReportRunView leakyRun = as(ACCOUNTANT,
                () -> desk.generate("key-2", "TEST_LEAKY", september, Map.of("probe", "l"), null));
        leaked = "second";
        ReportRunDesk.ReportRegenerationView diverged = as(ACCOUNTANT, () -> desk.regenerate("regen-2", leakyRun.id()));
        assertThat(diverged.result()).isEqualTo("DIVERGED");
        assertThat(diverged.regeneratedSha256()).isNotEqualTo(diverged.contentSha256());
        int opened = snapshotsOpened.size();
        assertThat(as(ACCOUNTANT, () -> desk.regenerate("regen-2", leakyRun.id()))).isEqualTo(diverged);
        assertThat(snapshotsOpened).as("the review's M-5: a replay answers the stored result, no snapshot opened")
                .hasSize(opened);
        assertThat(registry.get(ReportRunMetrics.DIVERGED).tag("definition", "TEST_LEAKY").counter().count())
                .as("counted once, the replay counting nothing").isEqualTo(1.0);
        assertThat(audited).filteredOn(record -> record.operation().code().equals("accounting.ReportRegenerated"))
                .extracting(record -> record.changeSummary().orElseThrow())
                .anySatisfy(s -> assertThat(s).endsWith("result=IDENTICAL"))
                .anySatisfy(s -> assertThat(s).endsWith("result=DIVERGED")).hasSize(2);
        assertThat(store.find(UOW, ReportRunId.of(UUID.fromString(leakyRun.id()))).orElseThrow().contentSha256())
                .as("never repaired").contains(leakyRun.contentSha256());
    }

    @Test
    @DisplayName("the review's I-1: a generation past its time bound fails GENERATION_TIMED_OUT - not DEFINITION_FAILED -"
            + " audited FAILED and counted, inside its lease; a regeneration past it is 503 accounting.ReadTimedOut and"
            + " records nothing under its key")
    void aGenerationPastItsBoundTimesOut() {
        String september = lockedSeptember();
        slow = true;
        ReportRunDesk.ReportRunView timedOut = as(ACCOUNTANT,
                () -> desk.generate("k1", "TEST_SLOW", september, Map.of("probe", "t"), null));
        assertThat(timedOut.status()).isEqualTo("FAILED");
        assertThat(timedOut.failure()).isEqualTo(RunFailure.GENERATION_TIMED_OUT.name());
        assertThat(audited).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary()).hasValueSatisfying(summary -> assertThat(summary)
                    .endsWith("failure=GENERATION_TIMED_OUT"));
        });
        assertThat(registry.get(ReportRunMetrics.RUN).tag("definition", "TEST_SLOW").tag("outcome", "failed").counter()
                .count()).isEqualTo(1.0);
        slow = false;
        ReportRunDesk.ReportRunView fin = as(ACCOUNTANT,
                () -> desk.generate("k2", "TEST_SLOW", september, Map.of("probe", "t"), null));
        assertThat(fin.status()).isEqualTo("FINAL");
        slow = true;
        refused(() -> as(ACCOUNTANT, () -> desk.regenerate("regen-slow", fin.id())), AccountingErrorCode.READ_TIMED_OUT,
                503);
        assertThat(audited).noneMatch(record -> record.operation().code().equals("accounting.ReportRegenerated"));
        // Nothing stored: the same key is still in flight - its lease runs out and it compares again, never a replay of
        // a refusal.
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.regenerate("regen-slow", fin.id())))
                .isInstanceOf(com.finapp.platform.idempotency.IdempotencyInProgressException.class);
        slow = false;
        assertThat(as(ACCOUNTANT, () -> desk.regenerate("regen-again", fin.id())).result()).isEqualTo("IDENTICAL");
    }

    @Test
    @DisplayName("a refusal the run decides is recorded under the key and replayed as itself")
    void aRefusalIsReplayedAsItself() {
        lockedSeptember();
        String october = periodStore.month(YearMonth.of(2026, 10)).orElseThrow().id().value().toString();
        ReportRunDesk.ReportRunView provisional = as(ACCOUNTANT,
                () -> desk.generate("key-1", TestReportDefinition.CODE, october, Map.of("probe", "r"), null));
        refused(() -> as(ACCOUNTANT, () -> desk.regenerate("same", provisional.id())),
                AccountingErrorCode.REPORT_RUN_STALE, 409);
        int opened = snapshotsOpened.size();
        refused(() -> as(ACCOUNTANT, () -> desk.regenerate("same", provisional.id())),
                AccountingErrorCode.REPORT_RUN_STALE, 409);
        assertThat(snapshotsOpened).as("replayed from the key").hasSize(opened);
    }

    @Test
    @DisplayName("only a FINAL run is regenerated: a PROVISIONAL one is ReportRunStale; an unknown one 404")
    void onlyAFinalRunIsRegenerated() {
        lockedSeptember();
        String october = periodStore.month(YearMonth.of(2026, 10)).orElseThrow().id().value().toString();
        ReportRunDesk.ReportRunView provisional = as(ACCOUNTANT,
                () -> desk.generate("key-1", TestReportDefinition.CODE, october, Map.of("probe", "p"), null));
        assertThat(provisional.status()).isEqualTo("PROVISIONAL");
        refused(() -> as(ACCOUNTANT, () -> desk.regenerate("regen", provisional.id())),
                AccountingErrorCode.REPORT_RUN_STALE, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.regenerate("regen-2", ReportRunId.next(IDS).value().toString())),
                AccountingErrorCode.NOT_FOUND, 404);
    }

    @Test
    @DisplayName("the refusals: an unknown definition 422, a parameter it does not take 422, a malformed rate set 422, an"
            + " unknown period 404 - nothing claimed")
    void theRefusals() {
        String september = lockedSeptember();
        // P14-TSK-017: an operational definition is never a run - refused at this door, nothing claimed, the key not spent.
        refused(() -> as(ACCOUNTANT, () -> desk.generate("k0", moving.code(), september, Map.of(), null)),
                AccountingErrorCode.REPORT_DOOR_MISMATCH, 409);
        assertThat(store.all()).isEmpty();
        refused(() -> as(ACCOUNTANT, () -> desk.generate("k1", "TRIAL_BALANCE", september, Map.of(), null)),
                AccountingErrorCode.REPORT_DEFINITION_UNKNOWN, 422);
        refused(() -> as(ACCOUNTANT, () -> desk.generate("k2", TestReportDefinition.CODE, september,
                Map.of("currency", "EUR"), null)), AccountingErrorCode.REPORT_PARAMETERS_INVALID, 422);
        refused(() -> as(ACCOUNTANT, () -> desk.generate("k3", TestReportDefinition.CODE, september,
                Map.of("probe", "4111 1111 1111 1111"), null)), AccountingErrorCode.REPORT_PARAMETERS_INVALID, 422);
        refused(() -> as(ACCOUNTANT, () -> desk.generate("k4", TestReportDefinition.CODE, september, Map.of(),
                "not-a-uuid")), AccountingErrorCode.REPORT_PARAMETERS_INVALID, 422);
        refused(() -> as(ACCOUNTANT, () -> desk.generate("k5", TestReportDefinition.CODE,
                AccountingPeriodId.next(IDS).value().toString(), Map.of(), null)), AccountingErrorCode.NOT_FOUND, 404);
        assertThat(store.all()).isEmpty();
        assertThat(claims).as("a refusal stores nothing under its key").isEmpty();
    }

    @Test
    @DisplayName("a definition that throws - a write the snapshot refused - fails the run DEFINITION_FAILED, audited FAILED"
            + " and counted; a new key asks a new revision")
    void aDefinitionFailureIsAFailedRun() {
        String september = lockedSeptember();
        ReportRunDesk.ReportRunView failed = as(ACCOUNTANT,
                () -> desk.generate("k1", "TEST_THROWING", september, Map.of(), null));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failure()).isEqualTo(RunFailure.DEFINITION_FAILED.name());
        assertThat(failed.contentSha256()).isNull();
        assertThat(audited).singleElement().satisfies(record -> assertThat(record.outcome())
                .isEqualTo(AuditOutcome.FAILED));
        assertThat(registry.get(ReportRunMetrics.RUN).tag("definition", "TEST_THROWING").tag("outcome", "failed")
                .counter().count()).isEqualTo(1.0);
        ReportRunDesk.ReportRunView next = as(ACCOUNTANT,
                () -> desk.generate("k2", "TEST_THROWING", september, Map.of(), null));
        assertThat(next.revision()).isEqualTo(2);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a translated run naming anything but its period's approved set is refused 409, nothing claimed - FINAL or"
            + " PROVISIONAL; a definition that takes no set still answers 422 (P14-TSK-015)")
    void aTranslatedRunWithNoApprovedSetIsRefused() {
        String september = lockedSeptember();
        String named = UUID.randomUUID().toString();
        refused(() -> as(ACCOUNTANT, () -> desk.generate("set-1", "TEST_TRANSLATING", september, Map.of(), named)),
                AccountingErrorCode.VALUATION_SET_NOT_APPROVED, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.generate("set-2", TestReportDefinition.CODE, september, Map.of(), named)),
                AccountingErrorCode.REPORT_PARAMETERS_INVALID, 422);
        assertThat(store.all()).isEmpty();
        assertThat(as(ACCOUNTANT, () -> desk.generate("set-3", "TEST_TRANSLATING", september, Map.of(), null)).status())
                .as("the same definition, asked plain").isEqualTo("FINAL");
    }

    @Test
    @DisplayName("a blank valuation rate set is no absent one (the review's M-3): the field means translate this, so a blank is"
            + " refused 422 at the door, nothing claimed - only null asks the plain run")
    void aBlankRateSetIsNotAPlainRun() {
        String september = lockedSeptember();
        for (String blank : java.util.List.of("", " ", "   ")) {
            refused(() -> as(ACCOUNTANT, () -> desk.generate("blank-" + blank.length(), "TEST_TRANSLATING", september,
                    Map.of(), blank)), AccountingErrorCode.REPORT_PARAMETERS_INVALID, 422);
        }
        assertThat(store.all()).isEmpty();
        assertThat(as(ACCOUNTANT, () -> desk.generate("absent", "TEST_TRANSLATING", september, Map.of(), null)).status())
                .isEqualTo("FINAL");
    }

    @Test
    @DisplayName("the snapshot's own failure is not the definition's: the run stays GENERATING under its lease; the replay"
            + " past the lease takes it over and completes it")
    void aSnapshotFailureLeavesTheRunToItsLease() {
        String september = lockedSeptember();
        snapshotDown.set(true);
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.generate("k1", TestReportDefinition.CODE, september,
                Map.of("probe", "s"), null))).isInstanceOf(com.finapp.accounting.AccountingStorageException.class);
        assertThat(store.all()).singleElement().satisfies(run -> assertThat(run.status().name())
                .isEqualTo("GENERATING"));
        snapshotDown.set(false);
        assertThat(as(ACCOUNTANT, () -> desk.generate("k1", TestReportDefinition.CODE, september,
                Map.of("probe", "s"), null)).status()).as("a live lease: answered as it stands").isEqualTo("GENERATING");
        clock.advance(ReportRuns.Leases.STANDARD.lease().plusSeconds(1));
        ReportRunDesk.ReportRunView replayed = as(ACCOUNTANT, () -> desk.generate("k1", TestReportDefinition.CODE,
                september, Map.of("probe", "s"), null));
        assertThat(replayed.status()).isEqualTo("FINAL");
        assertThat(store.all()).hasSize(1);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("the orphan leg: a dead generator's run reclaimed past its lease and completed as the platform; a live"
            + " run untouched")
    void theOrphanLegCompletesADeadGeneratorsRun() {
        AccountingPeriodId september = AccountingPeriodId.of(UUID.fromString(lockedSeptember()));
        ReportRunStore.RunRow dead = transactions.inTransaction(uow -> runs.claim(uow, TestReportDefinition.CODE,
                september, com.finapp.accounting.reporting.RunParameters.of(Map.of("probe", "o"), Optional.empty()),
                ACCOUNTANT, CorrelationId.generate(IDS))).run();
        assertThat(generator.completeOrphaned(10, Actor.SYSTEM)).as("a live lease").isZero();
        clock.advance(ReportRuns.Leases.STANDARD.lease().plusSeconds(1));
        assertThat(generator.completeOrphaned(10, Actor.SYSTEM)).isEqualTo(1);
        ReportRunStore.RunRow done = store.find(UOW, dead.id()).orElseThrow();
        assertThat(done.status().name()).isEqualTo("FINAL");
        assertThat(done.attempt()).isEqualTo(2);
        assertThat(audited).singleElement().satisfies(record -> assertThat(record.actor()).isEqualTo(Actor.SYSTEM));
        assertThat(generator.completeOrphaned(10, Actor.SYSTEM)).isZero();
    }

    @Test
    @DisplayName("the routes: generation and regeneration ACCOUNTING_REPORT_GENERATE and keyed; the read ACCOUNTING_READ"
            + " - identity V025's partition")
    void theRoutesAreThePartition() {
        Map<String, PermissionName> expected = Map.of(
                "generateReportRun", PermissionName.ACCOUNTING_REPORT_GENERATE,
                "regenerateReportRun", PermissionName.ACCOUNTING_REPORT_GENERATE,
                "readReportRun", PermissionName.ACCOUNTING_READ);
        int doors = 0;
        for (Method method : ReportRunController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                doors++;
                assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName())
                        .isEqualTo(expected.get(method.getName()));
                assertThat(method.isAnnotationPresent(RequiresIdempotencyKey.class)).as(method.getName())
                        .isEqualTo(!method.getName().startsWith("read"));
            }
        }
        assertThat(doors).isEqualTo(expected.size());
        // The negative: the controller's and the reporter's desks cannot generate - the permission is the accountant's.
        assertThat(expected.values()).doesNotContain(PermissionName.ACCOUNTING_CLOSE_APPROVE,
                PermissionName.REGULATORY_REPORT_PREPARE);
    }

    @Test
    @DisplayName("the meters: both series registered eagerly per definition held, their tag keys on the allowed list")
    void theMetersAreOnTheAllowedTags() {
        for (String definition : definitions.codes()) {
            for (String outcome : ReportRunMetrics.OUTCOMES) {
                assertThat(registry.find(ReportRunMetrics.RUN).tag("definition", definition).tag("outcome", outcome)
                        .counter()).as(definition + "/" + outcome).isNotNull();
            }
            assertThat(registry.find(ReportRunMetrics.DIVERGED).tag("definition", definition).counter()).isNotNull();
        }
        for (Meter meter : registry.getMeters()) {
            meter.getId().getTags().forEach(tag -> assertThat(MetricNames.ALLOWED_TAG_KEYS)
                    .as(meter.getId().getName()).contains(tag.getKey()));
            assertThat(MetricNames.isWellFormed(meter.getId().getName())).isTrue();
        }
    }

    // -----------------------------------------------------------------

    /** September opened and locked (its grace passed by the 10th of October), October open. */
    private String lockedSeptember() {
        Clock september = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneOffset.UTC);
        periodStore.onDatabaseDate(LocalDate.of(2026, 9, 15), () -> periods(september).openNext(UOW, Actor.SYSTEM, CorrelationId.generate(IDS)));
        periods(clock).openNext(UOW, Actor.SYSTEM, CorrelationId.generate(IDS));
        AccountingPeriodId id = periodStore.month(SEPTEMBER).orElseThrow().id();
        periods(clock).lock(UOW, id, ACCOUNTANT, null, CorrelationId.generate(IDS));
        return id.value().toString();
    }

    private AccountingPeriods periods(Clock at) {
        return new AccountingPeriods(periodStore, mappings, new PostingCutoff() {
            @Override
            public Optional<LocalDate> closedThrough(Connection unitOfWork) {
                return Optional.empty();
            }

            @Override
            public boolean advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate through, String reason) {
                return true;
            }

            @Override
            public boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
                return true;
            }
        }, LockGrace.standard(), (uow, record) -> {}, (uow, envelope, payload, mediaType) -> {}, IDS, at, runs);
    }

    private static void refused(Runnable act, AccountingErrorCode code, int status) {
        assertThatThrownBy(act::run).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(code);
            assertThat(refused.errorCode().status()).isEqualTo(status);
        });
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }

    /**
     * The snapshot's raw connection: it prepares any statement and answers one row - the view in front of it decides what
     * may be prepared, and spends the budget.
     */
    private static final Connection RAW = (Connection) Proxy.newProxyInstance(ReportRunDeskTest.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                if (!method.getName().equals("prepareStatement")) {
                    throw new UnsupportedOperationException("the stand-in snapshot only prepares");
                }
                return Proxy.newProxyInstance(ReportRunDeskTest.class.getClassLoader(),
                        new Class<?>[] {java.sql.PreparedStatement.class}, (statement, call, values) -> switch (call.getName()) {
                            case "setString", "close" -> null;
                            case "executeQuery" -> {
                                boolean[] read = {false};
                                yield Proxy.newProxyInstance(ReportRunDeskTest.class.getClassLoader(),
                                        new Class<?>[] {java.sql.ResultSet.class}, (row, at, none) -> switch (at.getName()) {
                                            case "next" -> {
                                                boolean first = !read[0];
                                                read[0] = true;
                                                yield first;
                                            }
                                            case "close" -> null;
                                            default -> throw new UnsupportedOperationException(at.getName());
                                        });
                            }
                            default -> throw new UnsupportedOperationException(call.getName());
                        });
            });

    /** The "database" clock, moved by the suite. */
    private static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final class Claims implements IdempotencyRecordStore<Connection> {

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint,
                CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS, StoredResponse.empty(),
                    correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState,
                StoredResponse response, Instant completedAt) {
            IdempotencyRecord held = claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
            claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response,
                    held.correlationId(), held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId,
                Instant now, Instant expiresAt, Duration lease) {
            return false;
        }
    }
}
