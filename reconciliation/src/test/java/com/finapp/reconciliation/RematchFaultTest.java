package com.finapp.reconciliation;

import static com.finapp.platform.testing.ScriptedListingConnection.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.persistence.SweepBackoff;
import com.finapp.platform.testing.RepositoryPaths;
import com.finapp.platform.testing.ScriptedListingConnection;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A rematch residual that faults every tick gains a durable, bounded exclusion and stays a break (`P16-TSK-017`;
 * {@code OPERATIONS_RUNBOOK.md} section 11.39 (c); the debt row "a rematch residual that faults every tick leaves no durable
 * record"). The real {@link Matching} and {@link JdbcMatchingStore}, hermetically: the leg over a store double and a scripted
 * connection, the statements over the scripted connection. What PostgreSQL makes of the statements is the owed database case.
 */
@DisplayName("a rematch residual faulting every tick is recorded under the claim, excluded until its retry, and stays a break (P16-TSK-017)")
class RematchFaultTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    // ------------------------------------------------------------------ the statements

    @Test
    @DisplayName("the rematch worklist's three reads leave out a residual whose fault's retry is still ahead, on the database's"
            + " clock; the run's pending-rematch count does not, so the residual stays pending")
    void theWorklistExcludesAndThePendingCountDoesNot() {
        JdbcMatchingStore store = new JdbcMatchingStore();
        ScriptedListingConnection jdbc = new ScriptedListingConnection(CLOCK.instant())
                .answering("SELECT count(*) FROM reconciliation.external_item", List.of(row("count", 0)));
        Connection uow = jdbc.connection();
        UUID source = IDS.next();
        store.sourcesWithRematchWork(uow);
        store.attributedSourcesWithRematchWork(uow, source);
        store.lockRematchCandidates(uow, source, List.of(), 50);
        store.pendingRematchOf(uow, source);
        String exclusion = "NOT EXISTS (SELECT 1 FROM reconciliation.rematch_fault rf"
                + " WHERE rf.external_item_id = i.id AND rf.retry_at > statement_timestamp())";
        List<ScriptedListingConnection.Sent> sent = jdbc.sent();
        assertThat(sent).hasSize(4);
        assertThat(sent.subList(0, 3)).as("the worklist's three reads").allSatisfy(read -> assertThat(read.sql()).contains(exclusion));
        assertThat(sent.get(3).sql()).as("the pending count: an excluded residual is still a pending rematch")
                .doesNotContain("rematch_fault").contains("i.status IN ('UNMATCHED', 'PARKED')");
    }

    @Test
    @DisplayName("the fault's record is one conditional, capped statement on the database's clock, counted once per window")
    void theRecordIsConditionalCappedAndTheDatabases() {
        UUID item = IDS.next();
        ScriptedListingConnection counted =
                new ScriptedListingConnection(CLOCK.instant()).updating("reconciliation.rematch_fault", 1);
        assertThat(new JdbcMatchingStore().recordRematchFault(counted.connection(), item, "IllegalStateException")).isTrue();
        ScriptedListingConnection inBackoff =
                new ScriptedListingConnection(CLOCK.instant()).updating("reconciliation.rematch_fault", 0);
        assertThat(new JdbcMatchingStore().recordRematchFault(inBackoff.connection(), item, "IllegalStateException"))
                .as("its retry still ahead: counted already").isFalse();
        ScriptedListingConnection.Sent sent = counted.sent().get(0);
        assertThat(sent.parameter(1)).isEqualTo(item);
        assertThat(sent.parameter(2)).isEqualTo(SweepBackoff.BASE.toSeconds());
        assertThat(sent.parameter(3)).isEqualTo("IllegalStateException");
        assertThat(sent.parameter(9)).as("the rematch cap").isEqualTo(SweepBackoff.REMATCH_CAP.toSeconds());
        assertThat(sent.parameters().values()).noneMatch(value -> value instanceof Timestamp || value instanceof Instant);
        assertThat(sent.sql()).contains("ON CONFLICT (external_item_id) DO UPDATE SET").contains("rf.faults + 1")
                .endsWith(" WHERE rf.retry_at <= statement_timestamp()");
    }

    @Test
    @DisplayName("only the rematch worklist and its record name the table: no break, suspense or item read is filtered by it")
    void onlyTheWorklistReadsTheTable() throws IOException {
        Path main = RepositoryPaths.locate("reconciliation/src/main/java/com/finapp/reconciliation/Matching.java").getParent();
        // A statement names the table inside a string literal; a comment that mentions it is not a read.
        java.util.regex.Pattern inSql = java.util.regex.Pattern.compile("\"[^\"\\n]*reconciliation\\.rematch_fault[^\"\\n]*\"");
        List<String> naming = new ArrayList<>();
        long statements = 0;
        try (Stream<Path> sources = Files.walk(main)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                long found = inSql.matcher(Files.readString(source)).results().count();
                if (found > 0) {
                    naming.add(source.getFileName().toString());
                    statements += found;
                }
            }
        }
        assertThat(naming).as("the table's one reader and writer").containsExactly("JdbcMatchingStore.java");
        assertThat(statements).as("the worklist's exclusion and the record, once each").isEqualTo(2);
    }

    // ------------------------------------------------------------------ the leg

    /** What the leg asked of the store, and how the store double answers. */
    private static final class World {
        final UUID source = IDS.next();
        final List<MatchingStore.ResidualItem> residuals = new ArrayList<>();
        final List<String> recorded = new ArrayList<>();
        final AtomicInteger listings = new AtomicInteger();
        final AtomicInteger counted = new AtomicInteger();
        boolean recordAnswers = true;
        RuntimeException recordFails;
        final ScriptedListingConnection jdbc = new ScriptedListingConnection(CLOCK.instant())
                .answering("pg_try_advisory_xact_lock", List.of(row("claimed", true)));

        MatchingStore.ResidualItem residual(long lineNo) {
            MatchingStore.ChunkItem item = new MatchingStore.ChunkItem(
                    IDS.next(), lineNo, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                    Money.ofMinorUnits(100, CurrencyCode.of("EUR")), Optional.empty(), Optional.empty(),
                    LocalDate.of(2026, 11, 12), Optional.empty(), Optional.empty(), new byte[] {1}, 1L, Map.of());
            MatchingStore.ResidualItem residual = new MatchingStore.ResidualItem(
                    item, IDS.next(), source, IDS.next(), CorrelationId.generate(IDS).value());
            residuals.add(residual);
            return residual;
        }

        MatchingStore store() {
            return (MatchingStore) Proxy.newProxyInstance(MatchingStore.class.getClassLoader(), new Class<?>[] {MatchingStore.class},
                    (self, method, arguments) -> switch (method.getName()) {
                        case "sourcesWithWork", "sourcesWithExpiredGrace", "sourcesWithOpenReprocess",
                                "attributedSourcesWithRematchWork" -> List.of();
                        case "sourcesWithRematchWork" -> List.of(source);
                        case "lockRematchCandidates" -> listings.getAndIncrement() == 0 ? List.copyOf(residuals) : List.of();
                        case "activeRuleSetId" -> IDS.next();
                        // The planted fault: every residual's rematch throws, every tick.
                        case "rematchReach" -> throw new IllegalStateException("a row the rematch reads is gone");
                        case "recordRematchFault" -> {
                            recorded.add(arguments[1] + " " + arguments[2]);
                            if (recordFails != null) {
                                throw recordFails;
                            }
                            yield recordAnswers;
                        }
                        default -> throw new AssertionError("the rematch leg does not call MatchingStore." + method.getName());
                    });
        }

        Matching matching() {
            PostingService posting = new PostingService(
                    new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                    new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(),
                    IDS, CLOCK, PostingObserver.NONE);
            ReconciliationTelemetry telemetry = new ReconciliationTelemetry() {
                @Override
                public void rematchFaulted() {
                    counted.incrementAndGet();
                }
            };
            TransactionRunner runner = new TransactionRunner() {
                @Override
                public <R> R inTransaction(Function<Connection, R> work) {
                    return work.apply(jdbc.connection());
                }
            };
            return new Matching(store(), new MatchingRules(),
                    proxy(BreakRegister.class), new Suspense(posting, new JdbcLedgerAccountStore(), IDS),
                    ResolutionFixtures.resolutions(IDS, CLOCK), proxy(InternalReferenceLookup.class),
                    new JdbcLedgerAccountStore(), new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS, CLOCK,
                    new Matching.Config(50, 2), runner, telemetry);
        }

        private static <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, arguments) -> {
                throw new AssertionError("the rematch leg does not call " + type.getSimpleName() + "." + method.getName());
            }));
        }
    }

    @Test
    @DisplayName("each faulting residual is rolled back to its savepoint, its fault recorded in the batch's transaction and"
            + " counted once, and the leg ends normally - the next residual still examined")
    void aFaultingResidualIsRecordedAndCounted() {
        World world = new World();
        MatchingStore.ResidualItem first = world.residual(1);
        MatchingStore.ResidualItem second = world.residual(2);
        Matching.SweepResult result = world.matching().sweep();
        assertThat(result.rematched()).isZero();
        assertThat(world.recorded).as("both residuals reached, each fault recorded by its class only")
                .containsExactly(first.item().id() + " IllegalStateException", second.item().id() + " IllegalStateException");
        assertThat(world.counted).as("counted once each, after the record counted it").hasValue(2);
        assertThat(world.jdbc.rolledBackToSavepoint()).as("each item's own work undone, nothing else").isEqualTo(2);
    }

    @Test
    @DisplayName("a fault another instance already counted is recorded nowhere twice: no second count")
    void aFaultCountedElsewhereIsNotCountedAgain() {
        World world = new World();
        world.residual(1);
        world.recordAnswers = false;
        world.matching().sweep();
        assertThat(world.recorded).hasSize(1);
        assertThat(world.counted).hasValue(0);
    }

    @Test
    @DisplayName("a record that fails rolls back to its own savepoint and is logged: the batch still ends normally")
    void aRecordThatFailsIsContained() {
        World world = new World();
        world.residual(1);
        world.residual(2);
        world.recordFails = new IllegalStateException("the record's table is unreachable");
        Matching.SweepResult result = world.matching().sweep();
        assertThat(result.rematched()).isZero();
        assertThat(world.recorded).as("both residuals still reached").hasSize(2);
        assertThat(world.counted).hasValue(0);
        assertThat(world.jdbc.rolledBackToSavepoint()).as("each item's savepoint and each record's own").isEqualTo(4);
    }
}
