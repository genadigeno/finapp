package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.LedgerStorageException;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The posting command against a live PostgreSQL (`P3-TSK-006`): one command, one financial
 * effect, whatever the caller does — with the effect <strong>counted in the tables</strong>,
 * never inferred from a return value.
 *
 * <p>Wired the way production will wire it — the real executor over the real record store, the
 * real writers — because the claim under test is the composition: entry, lines, audit record,
 * outbox row and idempotency record committing together or not at all.
 */
@Tag("database")
@DisplayName("the posting command under retries, races and failure (P3-TSK-006)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PostingServiceDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 13);

    private final LedgerAccountStore<Connection> accounts = new JdbcLedgerAccountStore();

    private PostingService service() {
        return service(new JdbcOutboxWriter());
    }

    private PostingService service(OutboxWriter<Connection> outbox) {
        return new PostingService(
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        CLOCK,
                        Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                outbox,
                new JdbcBalanceProjection(),
                IDS,
                CLOCK, PostingObserver.NONE);
    }

    @Test
    @DisplayName("one posting commits the entry, lines, audit record and outbox row together")
    void onePostingCommitsEverythingTogether() throws Exception {
        CorrelationId correlation = CorrelationId.generate(IDS);
        PostingResult result;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(correlation))) {
            app.setAutoCommit(false);
            LedgerAccount a = account(app, USD);
            LedgerAccount b = account(app, USD);
            result = service().post(app, command(key(), a, b, 1500));
            app.commit();
        }

        assertThat(result.replayed()).isFalse();
        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app,
                    "SELECT count(*) FROM ledger.journal_entry WHERE id = ?",
                    result.entryId().value()))
                    .isEqualTo(1);
            assertThat(count(app,
                    "SELECT count(*) FROM ledger.journal_line WHERE entry_id = ?",
                    result.entryId().value()))
                    .isEqualTo(2);
            assertThat(count(app,
                    "SELECT count(*) FROM platform.audit_record WHERE operation ="
                            + " 'ledger.JournalEntryPosted' AND target_id = ?",
                    result.entryId().value().toString()))
                    .as("audited, against the entry, in the same transaction (INV-AUD-01)")
                    .isEqualTo(1);
            assertThat(count(app,
                    "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                            + " 'ledger.JournalEntryPosted' AND aggregate_id = ?",
                    result.entryId().value()))
                    .as("announced in the posting's own transaction (INV-EVT-01); the relay's"
                            + " redelivery-with-the-same-eventId over this table is P0-TST-005's"
                            + " proven property, cited rather than re-proven")
                    .isEqualTo(1);
            assertThat(count(app,
                    "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND"
                            + " correlation_id = ?",
                    result.entryId().value(), correlation.value()))
                    .as("the event carries the flow's correlation")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a retry replays the original: one entry, one audit record, one outbox row")
    void aRetryReplaysTheOriginal() throws Exception {
        String key = key();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount a = account(app, USD);
            LedgerAccount b = account(app, USD);
            PostingResult first = service().post(app, command(key, a, b, 2500));
            app.commit();

            // The retry: same key, same request - the crash-after-commit shape, where the
            // caller never saw the first answer.
            PostingResult retry = service().post(app, command(key, a, b, 2500));
            app.commit();

            assertThat(retry.entryId()).isEqualTo(first.entryId());
            assertThat(retry.replayed()).isTrue();
            assertThat(count(app,
                    "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                    PostingService.IDEMPOTENCY_SCOPE + ":" + key))
                    .as("one financial effect, counted in the table")
                    .isEqualTo(1);
            assertThat(count(app,
                    "SELECT count(*) FROM platform.audit_record WHERE target_id = ?",
                    first.entryId().value().toString()))
                    .isEqualTo(1);
            assertThat(count(app,
                    "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?",
                    first.entryId().value()))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("ten instances, one key: one effect, counted in the database")
    void tenConcurrentIdenticalKeysProduceOneEffect() throws Exception {
        String key = key();
        LedgerAccount a;
        LedgerAccount b;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            a = account(app, USD);
            b = account(app, USD);
            app.commit();
        }
        LedgerAccount accountA = a;
        LedgerAccount accountB = b;

        int instances = 10;
        CyclicBarrier start = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        List<PostingResult> results = new ArrayList<>();
        try {
            List<Callable<PostingResult>> racers = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                racers.add(
                        () -> {
                            try (Connection app = DatabaseRoles.application();
                                    SecurityContext.Scope actor =
                                            SecurityContext.enterSystem();
                                    CorrelationContext.Scope flow = flow()) {
                                app.setAutoCommit(false);
                                start.await();
                                PostingResult result =
                                        service().post(
                                                app, command(key, accountA, accountB, 4200));
                                app.commit();
                                return result;
                            }
                        });
            }
            for (Future<PostingResult> outcome : pool.invokeAll(racers)) {
                results.add(outcome.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).hasSize(instances);
        assertThat(results.stream().map(PostingResult::entryId).distinct())
                .as("every racer was told about the same one entry")
                .hasSize(1);
        assertThat(results.stream().filter(result -> !result.replayed()))
                .as("exactly one executed; nine replayed")
                .hasSize(1);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app,
                    "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                    PostingService.IDEMPOTENCY_SCOPE + ":" + key))
                    .as("one financial effect, counted in the table")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a different request on a known key is refused, and writes nothing")
    void aDifferentRequestOnAKnownKeyIsRefused() throws Exception {
        String key = key();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount a = account(app, USD);
            LedgerAccount b = account(app, USD);
            service().post(app, command(key, a, b, 100));
            app.commit();

            // Same key, different amount: INV-IDEM-03's distinct conflict - silently replaying
            // the first response would hide a client defect, and a second effect is worse.
            assertThatThrownBy(() -> service().post(app, command(key, a, b, 999)))
                    .isInstanceOf(IdempotencyConflictException.class);
            app.rollback();

            assertThat(count(app,
                    "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                    PostingService.IDEMPOTENCY_SCOPE + ":" + key))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an injected failure at the last write leaves nothing at all")
    void anInjectedFailureAtTheLastWriteLeavesNothing() throws Exception {
        String key = key();
        LedgerAccount a;
        LedgerAccount b;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            a = account(app, USD);
            b = account(app, USD);
            app.commit();
        }

        // The outbox write is the command's last write; failing there is the sharpest probe of
        // atomicity, because everything else has already succeeded on the connection.
        OutboxWriter<Connection> failing =
                (uow, envelope, payload, mediaType) -> {
                    throw new IllegalStateException("injected: the last write fails");
                };
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            assertThatThrownBy(() -> service(failing).post(app, command(key, a, b, 700)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("injected");
            app.rollback();
        }

        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app,
                    "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                    PostingService.IDEMPOTENCY_SCOPE + ":" + key))
                    .as("no entry")
                    .isZero();
            assertThat(count(app,
                    "SELECT count(*) FROM platform.idempotency_record WHERE scope = ? AND"
                            + " idempotency_key = ?",
                    PostingService.IDEMPOTENCY_SCOPE, key))
                    .as("no claim either: the retry re-attempts rather than replaying a"
                            + " failure that never committed")
                    .isZero();
        }
    }

    @Test
    @DisplayName("a rolled-back posting leaves no outbox row: publication is never separate")
    void aRolledBackPostingLeavesNoOutboxRow() throws Exception {
        String key = key();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount a = account(app, USD);
            LedgerAccount b = account(app, USD);
            app.commit();
            PostingResult result = service().post(app, command(key, a, b, 300));
            app.rollback();

            assertThat(count(app,
                    "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?",
                    result.entryId().value()))
                    .as("a fact that rolled back must not be announced (INV-EVT-01)")
                    .isZero();
            assertThat(count(app,
                    "SELECT count(*) FROM ledger.journal_entry WHERE id = ?",
                    result.entryId().value()))
                    .isZero();
        }
    }

    @Test
    @DisplayName("an unestablished actor is refused before anything is written")
    void anUnestablishedActorIsRefused() throws Exception {
        try (Connection app = DatabaseRoles.application();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount a = account(app, USD);
            LedgerAccount b = account(app, USD);
            String key = key();
            // No SecurityContext scope: a posting nobody can be asked about is what
            // INV-LED-05 forbids, and the refusal must come before any claim or write.
            assertThatThrownBy(() -> service().post(app, command(key, a, b, 100)))
                    .isInstanceOf(IllegalStateException.class);
            app.rollback();
            assertThat(count(app,
                    "SELECT count(*) FROM platform.idempotency_record WHERE scope = ? AND"
                            + " idempotency_key = ?",
                    PostingService.IDEMPOTENCY_SCOPE, key))
                    .isZero();
        }
    }

    @Test
    @DisplayName("a line in the wrong currency is refused by V005, for every writer")
    void aWrongCurrencyLineIsRefusedByTheSchema() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount usd = account(app, USD);
            LedgerAccount jpy = account(app, CurrencyCode.of("JPY"));
            app.commit();

            // A balanced USD entry whose second leg lands on a JPY account: the domain cannot
            // see the mismatch (the line holds an identifier, deliberately), and the composite
            // FK is what refuses it - for this writer and for raw SQL alike.
            PostingCommand command =
                    new PostingCommand(
                            key(), DATE, DATE, "probe-event",
                            List.of(
                                    new JournalLine(usd.id(), Direction.DEBIT,
                                            Money.ofMinorUnits(500, USD)),
                                    new JournalLine(jpy.id(), Direction.CREDIT,
                                            Money.ofMinorUnits(500, USD))));
            assertThatThrownBy(() -> service().post(app, command))
                    // The 23503 surfaced as a generic storage failure until P3-TSK-017's
                    // boundary needed the caller's 422: the store now translates it to the
                    // named domain refusal (the V007 pattern), superseding this test's
                    // original LedgerStorageException expectation - the stopgap-superseded
                    // precedent. The refusal is amount-free and still the schema's own.
                    .isInstanceOf(com.finapp.ledger.UnknownPostingAccountException.class)
                    .hasMessageNotContaining("500");
            app.rollback();
        }
    }

    // ------------------------------------------------------------------ fixtures

    private LedgerAccount account(Connection app, CurrencyCode currency) {
        return accounts
                .createOrConverge(
                        app,
                        LedgerAccount.owned(
                                IDS, CLOCK, AccountType.LIABILITY,
                                AccountPurpose.CUSTOMER_WALLET, currency, IDS.next()))
                .account();
    }

    private static PostingCommand command(
            String key, LedgerAccount debit, LedgerAccount credit, long minorUnits) {
        return new PostingCommand(
                key, DATE, DATE, "probe-event",
                List.of(
                        new JournalLine(debit.id(), Direction.DEBIT,
                                Money.ofMinorUnits(minorUnits, USD)),
                        new JournalLine(credit.id(), Direction.CREDIT,
                                Money.ofMinorUnits(minorUnits, USD))));
    }

    private static String key() {
        return "posting-" + IDS.next();
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)));
    }

    private static long count(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }
}
