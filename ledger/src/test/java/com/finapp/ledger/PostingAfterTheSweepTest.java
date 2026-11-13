package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The posting and the reversal once their idempotency record has been swept (`P16-TSK-011`, ADR-0141; {@code INV-IDEM-01},
 * {@code INV-IDEM-03}): the hermetic proof of the mapping. The claim is always fresh here, as it is once the record has gone, and
 * the journal is an in-memory store that honours {@link JournalEntryStore#append}'s contract: one entry per scope, a second refused
 * with {@link PostingScopeTakenException} and nothing written. What the unique index and {@code ON CONFLICT} do on PostgreSQL is
 * {@code PostingScopeUniquenessDatabaseTest}'s (written, owed).
 */
@DisplayName("a posting and a reversal after their record is swept: the stored outcome, or the conflict, never a second entry (P16-TSK-011)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PostingAfterTheSweepTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate STATED = LocalDate.of(2026, 10, 30);
    private static final LocalDate FLOORED = LocalDate.of(2026, 11, 1);

    private final LedgerAccountId debit = LedgerAccountId.next(IDS);
    private final LedgerAccountId credit = LedgerAccountId.next(IDS);
    private final Journal journal = new Journal();
    private final List<String> completed = new ArrayList<>();
    private final List<String> claimed = new ArrayList<>();
    private final List<String> written = new ArrayList<>();
    private final List<PostingObserver.Outcome> outcomes = new ArrayList<>();

    @Test
    @DisplayName("a first attempt finds no entry under its scope and posts: the entry, its audit, its event and its projection")
    void aFirstAttemptPosts() {
        PostingResult result = post(command("first", 1500, PostingCommand.Dating.AS_STATED));

        assertThat(result.replayed()).isFalse();
        assertThat(journal.byScope).hasSize(1);
        assertThat(written).containsExactly("audit", "outbox", "projection");
        assertThat(outcomes).containsExactly(PostingObserver.Outcome.POSTED);
    }

    @Test
    @DisplayName("a retry whose record was swept is answered with the recorded entry, its id the stored bytes, and writes nothing")
    void aRetryWhoseRecordWasSweptIsAnsweredByTheEntry() {
        PostingCommand command = command("swept", 1500, PostingCommand.Dating.AS_STATED);
        JournalEntryId recorded = journal.holds(PostingService.IDEMPOTENCY_SCOPE + ":swept", JournalEntryType.POSTING,
                Optional.empty(), STATED, command.reference(), command.lines());

        PostingResult result = post(command);

        assertThat(result.entryId()).isEqualTo(recorded);
        assertThat(result.replayed()).isTrue();
        assertThat(journal.attempts).as("answered by the lookup, before any insert is tried: no cutoff read, no index met").isZero();
        assertThat(written).as("no audit, event or projection: no second effect").isEmpty();
        assertThat(completed).as("the fresh claim completes with the first execution's response").containsExactly(recorded.value().toString());
        assertThat(outcomes).containsExactly(PostingObserver.Outcome.REPLAYED);
    }

    @Test
    @DisplayName("a different request under a swept key is INV-IDEM-03's conflict, whatever field differs, and writes nothing")
    void aDifferentRequestUnderASweptKeyIsTheConflict() {
        PostingCommand command = command("other", 1500, PostingCommand.Dating.AS_STATED);
        Map<String, Function<PostingAfterTheSweepTest, JournalEntryId>> recordings = Map.of(
                "the amount", test -> test.journal.holds(scope("other"), JournalEntryType.POSTING, Optional.empty(), STATED,
                        command.reference(), test.lines(1600)),
                "the reference", test -> test.journal.holds(scope("other"), JournalEntryType.POSTING, Optional.empty(), STATED,
                        "another event", command.lines()),
                "the posting date", test -> test.journal.holds(scope("other"), JournalEntryType.POSTING, Optional.empty(),
                        STATED.minusDays(1), command.reference(), command.lines()),
                "the lines' order", test -> test.journal.holds(scope("other"), JournalEntryType.POSTING, Optional.empty(), STATED,
                        command.reference(), List.of(command.lines().get(1), command.lines().get(0))),
                "the kind", test -> test.journal.holds(scope("other"), JournalEntryType.ADJUSTMENT, Optional.empty(), STATED,
                        command.reference(), command.lines()));
        recordings.forEach((difference, recording) -> {
            PostingAfterTheSweepTest fresh = new PostingAfterTheSweepTest();
            recording.apply(fresh);
            assertThatThrownBy(() -> fresh.post(command)).as(difference).isInstanceOf(IdempotencyConflictException.class);
            assertThat(fresh.journal.appends).as(difference).isZero();
            assertThat(fresh.written).as(difference).isEmpty();
            assertThat(fresh.outcomes).as(difference).containsExactly(PostingObserver.Outcome.REFUSED);
        });
    }

    @Test
    @DisplayName("an entry the cutoff floored answers a floor request for its stated date, and nothing else: an as-stated retry, an earlier date")
    void aFlooredEntryAnswersTheFloorRequestOnly() {
        PostingCommand floor = command("floored", 1500, PostingCommand.Dating.FLOOR_AT_FIRST_OPEN);
        JournalEntryId recorded = journal.holds(scope("floored"), JournalEntryType.POSTING, Optional.empty(), FLOORED,
                floor.reference(), floor.lines());

        assertThat(post(floor).entryId()).isEqualTo(recorded);
        assertThatThrownBy(() -> post(command("floored", 1500, PostingCommand.Dating.AS_STATED)))
                .as("as stated: the date it asks is not the date it got").isInstanceOf(IdempotencyConflictException.class);

        PostingAfterTheSweepTest earlier = new PostingAfterTheSweepTest();
        earlier.journal.holds(scope("floored"), JournalEntryType.POSTING, Optional.empty(), STATED.minusDays(3), floor.reference(),
                floor.lines());
        assertThatThrownBy(() -> earlier.post(floor)).as("a floor never moves a date back").isInstanceOf(IdempotencyConflictException.class);
        assertThat(journal.appends + earlier.journal.appends).isZero();
    }

    @Test
    @DisplayName("a writer that claimed nothing commits the scope while this one posts: the index refuses ours, and the rival's entry is the answer")
    void aRivalWriterIsAnsweredByItsEntry() {
        PostingCommand command = command("rival", 1500, PostingCommand.Dating.AS_STATED);
        journal.rival = () -> journal.holds(scope("rival"), JournalEntryType.POSTING, Optional.empty(), STATED, command.reference(),
                command.lines());

        PostingResult result = post(command);

        assertThat(result.replayed()).isTrue();
        assertThat(result.entryId()).isEqualTo(journal.byScope.get(scope("rival")).entry().id());
        assertThat(journal.byScope).hasSize(1);
        assertThat(written).isEmpty();
        assertThat(completed).containsExactly(result.entryId().value().toString());
    }

    @Test
    @DisplayName("a reversal the journal holds under its key is answered before its bound is read and before any claim; a different one is the conflict")
    void aRecordedReversalIsAnsweredBeforeTheBound() {
        JournalEntryId original = journal.holds(scope("original"), JournalEntryType.POSTING, Optional.empty(), STATED, "an event",
                lines(1000));
        List<JournalLine> swapped = List.of(
                new JournalLine(credit, Direction.DEBIT, Money.ofMinorUnits(1000, USD)),
                new JournalLine(debit, Direction.CREDIT, Money.ofMinorUnits(1000, USD)));
        ReversalCommand full = new ReversalCommand("full", original, STATED, STATED, "its reversal", swapped);
        JournalEntryId recorded = journal.holds(ReversalService.IDEMPOTENCY_SCOPE + ":full", JournalEntryType.REVERSAL,
                Optional.of(original), STATED, "its reversal", swapped);

        PostingResult again = reverse(full);

        assertThat(again.entryId()).isEqualTo(recorded);
        assertThat(again.replayed()).isTrue();
        assertThat(journal.boundReads).as("the bound would count this very reversal and refuse it").isZero();
        assertThat(claimed).isEmpty();
        assertThat(written).isEmpty();

        ReversalCommand other = new ReversalCommand("full", original, STATED, STATED, "another reversal", swapped);
        assertThatThrownBy(() -> reverse(other)).isInstanceOf(IdempotencyConflictException.class);
        assertThat(journal.appends).isZero();
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static String scope(String key) {
        return PostingService.IDEMPOTENCY_SCOPE + ":" + key;
    }

    private List<JournalLine> lines(long minor) {
        return List.of(
                new JournalLine(debit, Direction.DEBIT, Money.ofMinorUnits(minor, USD)),
                new JournalLine(credit, Direction.CREDIT, Money.ofMinorUnits(minor, USD)));
    }

    private PostingCommand command(String key, long minor, PostingCommand.Dating dating) {
        return new PostingCommand(key, STATED, STATED, "the event", lines(minor), dating);
    }

    private PostingResult post(PostingCommand command) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return new PostingService(executor(), journal, audit(), outbox(), projection(), IDS, CLOCK, observer())
                    .post(unusable(), command);
        }
    }

    private PostingResult reverse(ReversalCommand command) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return new ReversalService(executor(), journal, audit(), outbox(), projection(), IDS, CLOCK, observer())
                    .reverse(unusable(), command);
        }
    }

    private PostingObserver observer() {
        return (outcome, elapsed) -> outcomes.add(outcome);
    }

    /** The claim is always fresh, as it is once a record has been swept; what it completes with is recorded. */
    @SuppressWarnings("unchecked")
    private IdempotentExecutor executor() {
        IdempotencyRecordStore<Connection> claims = (IdempotencyRecordStore<Connection>) Proxy.newProxyInstance(
                PostingAfterTheSweepTest.class.getClassLoader(), new Class<?>[] {IdempotencyRecordStore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "claim" -> {
                        claimed.add(args[1].toString());
                        yield IdempotencyRecordStore.ClaimOutcome.CLAIMED;
                    }
                    case "complete" -> {
                        completed.add(new String(((StoredResponse) args[3]).bodyBytes().orElseThrow(), StandardCharsets.UTF_8));
                        yield true;
                    }
                    default -> throw new AssertionError("the claim store's " + method.getName());
                });
        return new IdempotentExecutor(claims, CLOCK, Duration.ofDays(1), Duration.ofMinutes(5));
    }

    private AuditWriter<Connection> audit() {
        return (uow, record) -> written.add("audit");
    }

    private OutboxWriter<Connection> outbox() {
        return (uow, envelope, payload, mediaType) -> written.add("outbox");
    }

    @SuppressWarnings("unchecked")
    private BalanceProjection<Connection> projection() {
        return (BalanceProjection<Connection>) Proxy.newProxyInstance(PostingAfterTheSweepTest.class.getClassLoader(),
                new Class<?>[] {BalanceProjection.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("apply")) {
                        throw new AssertionError("the projection's " + method.getName());
                    }
                    written.add("projection");
                    return null;
                });
    }

    private static Connection unusable() {
        return (Connection) Proxy.newProxyInstance(PostingAfterTheSweepTest.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    throw new AssertionError("the unit of work itself was used: " + method.getName());
                });
    }

    /** The journal, in memory, under {@link JournalEntryStore#append}'s contract: one entry per scope, a second refused, nothing written. */
    private static final class Journal implements JournalEntryStore<Connection> {
        private final Map<String, PostedEntry> byScope = new HashMap<>();
        private final Map<JournalEntryId, PostedEntry> byId = new HashMap<>();
        private int appends;
        /** Every call of append, refused or not: a retry the journal answers never reaches the insert. */
        private int attempts;
        private int boundReads;
        /** When set, a writer that claimed nothing commits this scope just before our insert reaches the index. */
        private Runnable rival;

        JournalEntryId holds(String scope, JournalEntryType type, Optional<JournalEntryId> reverses, LocalDate posted,
                String reference, List<JournalLine> lines) {
            JournalEntry entry = JournalEntry.balanced(IDS, CLOCK, posted, STATED, lines);
            PostingAttribution attribution = new PostingAttribution(type, reference,
                    type == JournalEntryType.ADJUSTMENT ? Optional.of("a reason") : Optional.empty(), reverses, "system",
                    Correlation.startingWith(CorrelationId.generate(IDS)).causing(CausationId.of("cause")), scope);
            PostedEntry stored = new PostedEntry(entry, attribution);
            byScope.put(scope, stored);
            byId.put(entry.id(), stored);
            return entry.id();
        }

        @Override
        public void append(Connection unitOfWork, JournalEntry entry, PostingAttribution attribution) {
            attempts++;
            if (rival != null) {
                Runnable now = rival;
                rival = null;
                now.run();
            }
            if (byScope.containsKey(attribution.idempotencyScope())) {
                throw new PostingScopeTakenException("journal entry " + entry.id() + " was not appended");
            }
            appends++;
            PostedEntry stored = new PostedEntry(entry, attribution);
            byScope.put(attribution.idempotencyScope(), stored);
            byId.put(entry.id(), stored);
        }

        @Override
        public Optional<PostedEntry> findById(Connection unitOfWork, JournalEntryId entryId) {
            return Optional.ofNullable(byId.get(entryId));
        }

        @Override
        public List<JournalLine> reversalLinesOf(Connection unitOfWork, JournalEntryId original) {
            boundReads++;
            return byId.values().stream().filter(posted -> posted.attribution().reverses().equals(Optional.of(original)))
                    .flatMap(posted -> posted.entry().lines().stream()).toList();
        }

        @Override
        public Optional<JournalEntryId> findByIdempotencyScope(Connection unitOfWork, String scope) {
            return Optional.ofNullable(byScope.get(scope)).map(posted -> posted.entry().id());
        }

        @Override
        public List<LineKey> lineKeysOn(Connection unitOfWork, Collection<LedgerAccountId> accounts) {
            throw new AssertionError("not read here");
        }

        @Override
        public List<PostedEntry> entriesAfter(Connection unitOfWork, UUID after, Duration settleHorizon, int limit) {
            throw new AssertionError("not read here");
        }

        @Override
        public long countBeyond(Connection unitOfWork, UUID after, long cap) {
            throw new AssertionError("not read here");
        }
    }
}
