package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cutoff's refusal reaches every caller as one named outcome, counted (`P14-TSK-004`, {@code INV-ACC-03}):
 * {@code V028}'s SQLSTATE {@code LDCUT} becomes {@link PeriodClosedException} at the one journal store every surface
 * appends through, matched as the state and never the prose; and the posting command observes it twice - as a refusal
 * on {@code finapp.ledger.posting} and on its own {@code finapp.accounting.cutoff.refused}. Without a database: the
 * trigger itself is {@code PostingCutoffDatabaseTest}'s (owed, C5).
 */
@DisplayName("a posting dated into a locked period is ledger.PeriodClosed, counted (P14-TSK-004)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PeriodClosedRefusalTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(1999, 1, 20);

    @Test
    @DisplayName("SQLSTATE LDCUT is PeriodClosedException - the entry and its date named, never an amount")
    void theSqlStateIsTranslated() {
        JournalEntry entry = entry();
        Connection refusing = refusing(new SQLException(
                "ERROR: ledger_posting_cutoff: entry is dated 1999-01-20 - on or before the posting cutoff 1999-01-31",
                PostingCutoffs.PERIOD_CLOSED_SQLSTATE));
        assertThatThrownBy(() -> new JdbcJournalEntryStore(IDS).append(refusing, entry, attribution()))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining(entry.id().toString())
                .hasMessageContaining("1999-01-20")
                .hasMessageNotContaining("1500");
    }

    @Test
    @DisplayName("the state is matched, not the prose: a 23514 naming the cutoff is not a PeriodClosed")
    void theStateIsMatchedNotTheProse() {
        Connection other = refusing(new SQLException("ERROR: ledger_posting_cutoff something", "23514"));
        assertThatThrownBy(() -> new JdbcJournalEntryStore(IDS).append(other, entry(), attribution()))
                .isNotInstanceOf(PeriodClosedException.class)
                .isInstanceOf(LedgerStorageException.class);
    }

    @Test
    @DisplayName("the posting command rethrows it and counts it twice: refused, and refused at the cutoff")
    void thePostingCommandCountsTheRefusal() throws Exception {
        Recording observer = new Recording();
        PostingService service = service(journalThrowing(new PeriodClosedException("refused")), observer);
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThatThrownBy(() -> service.post(unused(Connection.class), command()))
                    .isInstanceOf(PeriodClosedException.class);
        }
        assertThat(observer.outcomes).containsExactly(PostingObserver.Outcome.REFUSED);
        assertThat(observer.cutoffRefusals).containsExactly(PostingObserver.Command.POSTING);
    }

    @Test
    @DisplayName("any other refusal is not counted at the cutoff")
    void anotherRefusalIsNotACutoffRefusal() throws Exception {
        Recording observer = new Recording();
        PostingService service = service(journalThrowing(new LedgerStorageException("down")), observer);
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThatThrownBy(() -> service.post(unused(Connection.class), command()))
                    .isInstanceOf(LedgerStorageException.class);
        }
        assertThat(observer.outcomes).containsExactly(PostingObserver.Outcome.REFUSED);
        assertThat(observer.cutoffRefusals).isEmpty();
    }

    // ------------------------------------------------------------------

    private static final class Recording implements PostingObserver {
        final List<Outcome> outcomes = new ArrayList<>();
        final List<Command> cutoffRefusals = new ArrayList<>();

        @Override
        public void observe(Outcome outcome, Duration elapsed) {
            outcomes.add(outcome);
        }

        @Override
        public void refusedAtTheCutoff(Command command) {
            cutoffRefusals.add(command);
        }
    }

    @SuppressWarnings("unchecked")
    private static PostingService service(JournalEntryStore<Connection> journal, PostingObserver observer) {
        IdempotencyRecordStore<Connection> claims = (IdempotencyRecordStore<Connection>) Proxy.newProxyInstance(
                PeriodClosedRefusalTest.class.getClassLoader(), new Class<?>[] {IdempotencyRecordStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("claim")) {
                        return IdempotencyRecordStore.ClaimOutcome.CLAIMED;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new PostingService(
                new IdempotentExecutor(claims, CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                journal,
                unused(AuditWriter.class),
                unused(OutboxWriter.class),
                unused(BalanceProjection.class),
                IDS,
                CLOCK,
                observer);
    }

    @SuppressWarnings("unchecked")
    private static JournalEntryStore<Connection> journalThrowing(RuntimeException refusal) {
        return (JournalEntryStore<Connection>) Proxy.newProxyInstance(
                PeriodClosedRefusalTest.class.getClassLoader(), new Class<?>[] {JournalEntryStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("append")) {
                        throw refusal;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A collaborator the refused path never reaches - any call is a test failure. */
    @SuppressWarnings("unchecked")
    private static <T> T unused(Class<?> type) {
        return (T) Proxy.newProxyInstance(PeriodClosedRefusalTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    throw new AssertionError("not reached on a refused posting: " + type.getSimpleName() + "."
                            + method.getName());
                });
    }

    /** A connection whose every statement fails with {@code failure} at execution. */
    private static Connection refusing(SQLException failure) {
        return (Connection) Proxy.newProxyInstance(PeriodClosedRefusalTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("prepareStatement")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return Proxy.newProxyInstance(PeriodClosedRefusalTest.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class}, (statement, call, values) -> switch (call.getName()) {
                                case "executeUpdate", "executeBatch" -> throw failure;
                                case "close" -> null;
                                default -> null; // the binds
                            });
                });
    }

    private static JournalEntry entry() {
        return JournalEntry.balanced(IDS, CLOCK, DATE, DATE, lines());
    }

    private static PostingCommand command() {
        return new PostingCommand("cutoff-refusal-" + IDS.next(), DATE, DATE, "a back-dated posting", lines());
    }

    private static List<JournalLine> lines() {
        return List.of(
                new JournalLine(LedgerAccountId.next(IDS), Direction.DEBIT, Money.ofMinorUnits(1500, USD)),
                new JournalLine(LedgerAccountId.next(IDS), Direction.CREDIT, Money.ofMinorUnits(1500, USD)));
    }

    private static PostingAttribution attribution() {
        Correlation correlation = Correlation.startingWith(CorrelationId.generate(IDS)).causing(CausationId.of("cause"));
        return new PostingAttribution(JournalEntryType.POSTING, "a back-dated posting", Optional.empty(),
                Optional.empty(), "system", correlation, PostingService.IDEMPOTENCY_SCOPE + ":k");
    }
}
