package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The durable hints' sweep (`P15-TSK-036`, ADR-0083): the door's inquiry and the sweep are one path; a hint the provider
 * would not answer stays; a conditional permit decides who asks; and a hint a door could not finish is finished by whichever
 * instance comes next. The race between instances is the database's (the permit's and the appliers' conditionals), owed to
 * {@code CallbackHintDatabaseTest}.
 */
@DisplayName("the callback-hint sweep (P15-TSK-036)")
class CallbackHintResolutionTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CallbackHintResolution.Config CONFIG =
            new CallbackHintResolution.Config(
                    Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofDays(7), 50);

    private final Connection connection =
            (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        throw new AssertionError("no database here");
                    });

    private final List<CallbackHint> rows = new ArrayList<>();
    private final Map<UUID, HintedOutcome> concluded = new HashMap<>();
    private final List<String> permits = new ArrayList<>();
    private boolean permitWon = true;
    private int expired;

    private final CallbackHintStore<Connection> store = new CallbackHintStore<>() {
        @Override
        public CallbackHint insert(
                Connection uow, CallbackHint.Kind kind, Optional<PaymentAttemptId> attempt, String askedAbout,
                Duration firstAsk) {
            throw new AssertionError("the sweep inserts nothing");
        }

        @Override
        public List<CallbackHint> findDue(Connection uow, int batchSize) {
            return rows.stream().filter(row -> !concluded.containsKey(row.id())).limit(batchSize).toList();
        }

        @Override
        public boolean renewPermit(Connection uow, CallbackHint hint, Duration retryAfter) {
            permits.add(hint.askedAbout());
            return permitWon;
        }

        @Override
        public boolean conclude(Connection uow, UUID hint, HintedOutcome conclusion) {
            return concluded.putIfAbsent(hint, conclusion) == null;
        }

        @Override
        public int expire(Connection uow, Duration giveUpAfter, int batchSize) {
            return expired;
        }
    };

    private final TransactionRunner runner = new TransactionRunner() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return work.apply(connection);
        }
    };

    private final Map<String, HintedOutcome> answers = new HashMap<>();
    private final List<String> inquired = new ArrayList<>();

    private final HintedInquiry card = new HintedInquiry() {
        @Override
        public Set<CallbackHint.Kind> kinds() {
            return Set.of(CallbackHint.Kind.CARD_CLEARING);
        }

        @Override
        public HintedOutcome inquire(CallbackHint hint) {
            inquired.add(hint.askedAbout());
            assertThat(SecurityContext.current()).as("the sweep asks as the platform").isPresent();
            if ("throws".equals(hint.askedAbout())) {
                throw new IllegalStateException("the adapter broke");
            }
            return answers.getOrDefault(hint.askedAbout(), HintedOutcome.UNDECIDED);
        }
    };

    private final CallbackHintResolution resolution =
            new CallbackHintResolution(store, List.of(card), CONFIG, IDS, runner);

    private CallbackHint hint(CallbackHint.Kind kind, String askedAbout) {
        CallbackHint hint = new CallbackHint(IDS.next(), kind, Optional.empty(), askedAbout, Instant.now(CLOCK));
        rows.add(hint);
        return hint;
    }

    @Test
    @DisplayName("an answered hint concludes; an unanswered one stays, for the next tick to ask again")
    void anAnsweredHintConcludesAndAnUnansweredOneStays() {
        CallbackHint answered = hint(CallbackHint.Kind.CARD_CLEARING, "answered");
        CallbackHint unanswered = hint(CallbackHint.Kind.CARD_CLEARING, "unanswered");
        answers.put("answered", HintedOutcome.ADOPTED);

        CallbackHintResolution.SweepResult result = resolution.sweep();

        assertThat(result.asked()).isEqualTo(2);
        assertThat(result.concluded()).isEqualTo(1);
        assertThat(concluded).containsEntry(answered.id(), HintedOutcome.ADOPTED);
        assertThat(concluded).doesNotContainKey(unanswered.id());
    }

    @Test
    @DisplayName("a refuted or contradicted hint concludes: an answer that says no is still an answer")
    void aRefutedHintConcludes() {
        CallbackHint refuted = hint(CallbackHint.Kind.CARD_CLEARING, "refuted");
        answers.put("refuted", HintedOutcome.REFUTED);

        resolution.sweep();

        assertThat(concluded).containsEntry(refuted.id(), HintedOutcome.REFUTED);
    }

    @Test
    @DisplayName("an instance that loses the permit does not ask")
    void aLostPermitAsksNothing() {
        hint(CallbackHint.Kind.CARD_CLEARING, "one");
        permitWon = false;

        CallbackHintResolution.SweepResult result = resolution.sweep();

        assertThat(permits).containsExactly("one");
        assertThat(inquired).isEmpty();
        assertThat(result.asked()).isZero();
    }

    @Test
    @DisplayName("one hint's failure does not starve the rest")
    void oneFailureDoesNotStarveTheRest() {
        hint(CallbackHint.Kind.CARD_CLEARING, "throws");
        CallbackHint second = hint(CallbackHint.Kind.CARD_CLEARING, "second");
        answers.put("second", HintedOutcome.ADOPTED);

        resolution.sweep();

        assertThat(concluded).containsOnlyKeys(second.id());
    }

    @Test
    @DisplayName("a kind no rail on this instance answers stays undecided, concluded by nobody")
    void aKindWithNoInquiryStays() {
        CallbackHint orphan = hint(CallbackHint.Kind.CARD_DISPUTE, "no-rail-here");

        assertThat(resolution.resolve(orphan)).isEqualTo(HintedOutcome.UNDECIDED);

        assertThat(concluded).isEmpty();
    }

    @Test
    @DisplayName("the abandoned are counted in the tally")
    void theAbandonedAreCounted() {
        expired = 3;

        assertThat(resolution.sweep().expired()).isEqualTo(3);
    }

    @Test
    @DisplayName("a zero or negative bound is refused at construction")
    void aZeroBoundIsRefused() {
        assertThatThrownBy(() -> new CallbackHintResolution.Config(Duration.ZERO, Duration.ofMinutes(1), Duration.ofDays(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallbackHintResolution.Config(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofDays(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
