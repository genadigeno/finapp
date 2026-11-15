package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.sharedkernel.id.IdGenerator;
import java.math.BigInteger;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The openings leg's writer (`P16-REM-003`): it folds only with the writing claimed, the oldest month whose present lock lacks one,
 * by the very reads a report takes - the GL side before the next month, the ledger's through-the-end window, the counterparties - with
 * the latest creation instant of everything it folds, and stores nothing a moved lock refused. Its statements on PostgreSQL are
 * {@code OpeningCheckpointDatabaseTest}'s.
 */
@DisplayName("the openings leg's writer folds the oldest locked month once, by the reports' own reads (P16-REM-003)")
class OpeningCheckpointsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final AccountingPeriodId MARCH = AccountingPeriodId.of(IDS.next());
    private static final Instant LOCKED_AT = Instant.parse("2097-04-05T09:00:00Z");

    /** The leg's transaction: nothing here reaches it - the store and the reads are stand-ins. */
    private static final Connection UNIT_OF_WORK = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });

    /** A store answering as told and recording what it was asked. */
    private static final class Store implements OpeningCheckpointStore {

        boolean claimed = true;
        Optional<Pending> pending = Optional.of(new Pending(MARCH, LocalDate.of(2097, 3, 31), LOCKED_AT));
        Optional<OpeningCheckpoint> previous = Optional.empty();
        boolean lockStands = true;
        final List<OpeningCheckpoint> stored = new ArrayList<>();
        final List<String> asked = new ArrayList<>();

        @Override
        public Optional<OpeningCheckpoint> latest(Connection snapshot, LocalDate before, Optional<Instant> cut) {
            asked.add("latest " + before + " " + cut.isPresent());
            return previous.filter(checkpoint -> checkpoint.precedes(before));
        }

        @Override
        public boolean claim(Connection unitOfWork) {
            asked.add("claim");
            return claimed;
        }

        @Override
        public Optional<Pending> pending(Connection unitOfWork) {
            asked.add("pending");
            return pending;
        }

        @Override
        public boolean store(Connection unitOfWork, UUID id, OpeningCheckpoint checkpoint) {
            asked.add("store");
            if (lockStands) {
                stored.add(checkpoint);
            }
            return lockStands;
        }
    }

    private final Store store = new Store();
    private final InMemoryTrialBalanceReads journal = new InMemoryTrialBalanceReads()
            .period(new TrialBalanceReads.Period(MARCH, LocalDate.of(2097, 3, 1), LocalDate.of(2097, 3, 31), PeriodStatus.LOCKED, Optional.empty()))
            .post(LocalDate.of(2097, 2, 10), Instant.parse("2097-02-10T10:00:00Z"), "USD", 2, 1_000, "CUSTOMER_FUNDS/CUSTOMER", "SETTLEMENT_CLEARING/PLATFORM")
            .post(LocalDate.of(2097, 3, 31), Instant.parse("2097-04-01T00:00:02Z"), "USD", 2, 250, "SETTLEMENT_RECEIVABLE/COUNTERPARTY", "FEE_REVENUE/PLATFORM")
            .post(LocalDate.of(2097, 4, 1), Instant.parse("2097-04-01T09:00:00Z"), "USD", 2, 99, "CUSTOMER_FUNDS/CUSTOMER", "FEE_REVENUE/PLATFORM");
    private final List<String> creationsAsked = new ArrayList<>();
    private final JournalCreations creations = (unitOfWork, after, through) -> {
        creationsAsked.add(after.map(LocalDate::toString).orElse("inception") + ".." + through);
        return journal.lines().stream().filter(line -> !line.postingDate().isAfter(through))
                .filter(line -> after.isEmpty() || line.postingDate().isAfter(after.get()))
                .map(InMemoryTrialBalanceReads.JournalLine::createdAt).max(Instant::compareTo);
    };
    private final OpeningCheckpoints writer = new OpeningCheckpoints(store, journal, journal.counterparties(), creations, IDS);

    @Test
    @DisplayName("another instance holding the writing: nothing read, nothing folded, nothing stored")
    void unclaimedItFoldsNothing() {
        store.claimed = false;

        assertThat(writer.storeNext(UNIT_OF_WORK)).isEmpty();
        assertThat(store.asked).containsExactly("claim");
        assertThat(creationsAsked).isEmpty();
    }

    @Test
    @DisplayName("every locked month already checkpointed under its present lock: nothing folded")
    void nothingPendingFoldsNothing() {
        store.pending = Optional.empty();

        assertThat(writer.storeNext(UNIT_OF_WORK)).isEmpty();
        assertThat(store.asked).containsExactly("claim", "pending");
    }

    @Test
    @DisplayName("the month folded from inception: every line dated on or before its end, the next month's untouched, under the lock it"
            + " read, with the latest creation of what it folds")
    void theMonthIsFoldedThroughItsEnd() {
        assertThat(writer.storeNext(UNIT_OF_WORK)).contains(MARCH);

        OpeningCheckpoint checkpoint = store.stored.getFirst();
        assertThat(checkpoint.period()).isEqualTo(MARCH);
        assertThat(checkpoint.through()).isEqualTo(LocalDate.of(2097, 3, 31));
        assertThat(checkpoint.lockedAt()).isEqualTo(LOCKED_AT);
        assertThat(checkpoint.gl()).isEqualTo(journal.before(null, LocalDate.of(2097, 4, 1), Optional.empty()));
        assertThat(checkpoint.gl()).extracting(JournalReads.Aggregate::lines).containsOnly(1L);
        assertThat(checkpoint.ledger()).containsExactlyInAnyOrder(
                new JournalReads.Total("USD", 2, Side.CREDIT, BigInteger.valueOf(1_250), 2),
                new JournalReads.Total("USD", 2, Side.DEBIT, BigInteger.valueOf(1_250), 2));
        assertThat(checkpoint.counterparties()).singleElement().satisfies(group -> {
            assertThat(group.counterparty()).contains(InMemoryTrialBalanceReads.THE_COUNTERPARTY);
            assertThat(group.minorUnits()).isEqualTo(BigInteger.valueOf(250));
        });
        assertThat(checkpoint.latestCreated()).as("the month's last day, created after midnight by a posting instance's clock")
                .contains(Instant.parse("2097-04-01T00:00:02Z"));
        assertThat(creationsAsked).containsExactly("inception..2097-03-31");
        assertThat(store.asked).containsExactly("claim", "pending", "latest 2097-04-01 false", "store");
    }

    @Test
    @DisplayName("after the month before's checkpoint: the creations asked only since it, the latest of the two kept")
    void theLatestCreationIsCarriedFromThePreviousCheckpoint() {
        Instant later = Instant.parse("2097-03-02T23:59:59Z");
        store.previous = Optional.of(new OpeningCheckpoint(AccountingPeriodId.of(IDS.next()), LocalDate.of(2097, 2, 28), LOCKED_AT.minusSeconds(2_600_000),
                Optional.of(later), List.of(), List.of(), List.of()));

        writer.storeNext(UNIT_OF_WORK);

        assertThat(creationsAsked).containsExactly("2097-02-28..2097-03-31");
        assertThat(store.stored.getFirst().latestCreated()).contains(Instant.parse("2097-04-01T00:00:02Z"));
        store.stored.clear();
        store.previous = Optional.of(new OpeningCheckpoint(AccountingPeriodId.of(IDS.next()), LocalDate.of(2097, 2, 28), LOCKED_AT.minusSeconds(2_600_000),
                Optional.of(Instant.parse("2097-05-01T00:00:00Z")), List.of(), List.of(), List.of()));
        writer.storeNext(UNIT_OF_WORK);
        assertThat(store.stored.getFirst().latestCreated()).as("a skewed clock's later stamp, carried").contains(Instant.parse("2097-05-01T00:00:00Z"));
    }

    @Test
    @DisplayName("the month moved back while it was folded: the store refuses it under the lock it read, and the writer reports nothing stored")
    void aMovedLockStoresNothing() {
        store.lockStands = false;

        assertThat(writer.storeNext(UNIT_OF_WORK)).isEmpty();
        assertThat(store.stored).isEmpty();
        assertThat(store.asked).last().isEqualTo("store");
    }
}
