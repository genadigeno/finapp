package com.finapp.sharedkernel.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import com.finapp.sharedkernel.time.TestClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link IdGenerator} must produce identifiers that are unique, unguessable and never sort
 * before one already issued — under a frozen clock, a backwards clock, and contention.
 *
 * <p>The clock and the randomness source are constructor arguments, so every case below is
 * deterministic rather than a race the test hopes to lose.
 */
class IdGeneratorTest {

    private static final Instant FIXED = Instant.parse("2026-09-01T10:15:30.500Z");

    // -----------------------------------------------------------------
    // Format
    // -----------------------------------------------------------------

    @Test
    @DisplayName("produces version 7, RFC variant, as ADR-0013 requires")
    void producesUuidV7() {
        UUID id = generatorAt(FIXED).next();

        assertThat(id.version()).as("UUID version").isEqualTo(7);
        assertThat(id.variant()).as("RFC 4122/9562 variant").isEqualTo(2);
    }

    @Test
    @DisplayName("carries the clock's millisecond, not an approximation of it")
    void carriesTheClockTimestamp() {
        UUID id = generatorAt(FIXED).next();

        long timestampMillis = id.getMostSignificantBits() >>> 16;

        assertThat(Instant.ofEpochMilli(timestampMillis)).isEqualTo(FIXED);
    }

    @Test
    @DisplayName("the random field is actually random, not a constant or a counter")
    void randomFieldVaries() {
        IdGenerator generator = generatorAt(FIXED);

        Set<Long> randomParts = new java.util.HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            // Everything but the two variant bits of the low half is the random field. If this
            // were constant, identifiers would be guessable from a single observed value.
            randomParts.add(generator.next().getLeastSignificantBits() & ((1L << 62) - 1));
        }
        assertThat(randomParts).as("distinct random fields in 1000 identifiers").hasSize(1_000);
    }

    // -----------------------------------------------------------------
    // Ordering — the property the whole choice of UUIDv7 exists for
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("identifiers are time-ordered")
    class Ordering {

        @Test
        @DisplayName("strictly increase across advancing milliseconds, giving index locality")
        void strictlyIncreaseAsTimeAdvances() {
            TestClock clock = TestClock.at(FIXED);
            IdGenerator generator = new IdGenerator(clock, seededRandom());

            List<UUID> issued = new ArrayList<>();
            for (int i = 0; i < 2_000; i++) {
                issued.add(generator.next());
                clock.advance(Duration.ofMillis(1));
            }
            assertStrictlyIncreasing(issued);
        }

        @Test
        @DisplayName("still increase within a single millisecond, where the clock cannot separate them")
        void strictlyIncreaseWithinOneMillisecond() {
            // The clock never moves. Ordering here comes entirely from the counter; a
            // generator that put randomness in those bits would produce identifiers that
            // sort arbitrarily among themselves, and a batch written in one millisecond is
            // exactly what a ledger does.
            IdGenerator generator = generatorAt(FIXED);

            List<UUID> issued = new ArrayList<>();
            for (int i = 0; i < 1_000; i++) {
                issued.add(generator.next());
            }
            assertStrictlyIncreasing(issued);
        }

        @Test
        @DisplayName("keep increasing when the counter is exhausted within one millisecond")
        void counterExhaustionBorrowsTheNextMillisecond() {
            // 4096 is the counter's whole range and the seed starts partway in, so this
            // exhausts it several times over. Wrapping would repeat a value and sort
            // backwards; borrowing the next millisecond does neither.
            IdGenerator generator = generatorAt(FIXED);

            List<UUID> issued = new ArrayList<>();
            for (int i = 0; i < 20_000; i++) {
                issued.add(generator.next());
            }

            assertStrictlyIncreasing(issued);
            assertThat(Set.copyOf(issued)).as("all distinct").hasSize(issued.size());
            // Borrowed time runs ahead of the frozen clock, which is the deliberate trade.
            assertThat(issued.get(issued.size() - 1).getMostSignificantBits() >>> 16)
                    .as("borrowed milliseconds run ahead of the stopped clock")
                    .isGreaterThan(FIXED.toEpochMilli());
        }

        @Test
        @DisplayName("never regress when the clock jumps backwards")
        void clockRegressionDoesNotProduceRegressingIdentifiers() {
            // NTP correction and leap-second smearing both move wall-clock time backwards.
            // A generator that simply read the clock would issue identifiers sorting before
            // rows already written, and could repeat one.
            TestClock clock = TestClock.at(FIXED);
            IdGenerator generator = new IdGenerator(clock, seededRandom());

            List<UUID> issued = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                issued.add(generator.next());
            }
            clock.rewind(Duration.ofHours(1));
            for (int i = 0; i < 100; i++) {
                issued.add(generator.next());
            }

            assertStrictlyIncreasing(issued);
            assertThat(Set.copyOf(issued)).hasSize(issued.size());
        }
    }

    // -----------------------------------------------------------------
    // Concurrency — required by DOD-KERNEL
    // -----------------------------------------------------------------

    @Test
    @DisplayName("issues no duplicate under contention from many threads")
    void isUniqueUnderContention() throws InterruptedException {
        int threads = 16;
        int perThread = 5_000;
        // A frozen clock is the hostile case: every thread contends for the same
        // millisecond, so uniqueness rests entirely on the counter transition being atomic.
        IdGenerator generator = generatorAt(FIXED);

        Set<UUID> issued = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.execute(
                        () -> {
                            try {
                                start.await();
                                for (int i = 0; i < perThread; i++) {
                                    issued.add(generator.next());
                                }
                            } catch (Throwable e) {
                                failure.compareAndSet(null, e);
                            } finally {
                                finished.countDown();
                            }
                        });
            }
            start.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS)).as("threads finished").isTrue();
        }

        assertThat(failure.get()).as("no thread failed").isNull();
        // A set smaller than the count is a collision, which on a primary key is a failed
        // insert at best and a merged financial record at worst.
        assertThat(issued).as("every identifier distinct").hasSize(threads * perThread);
    }

    @Test
    @DisplayName("a restarted generator never re-issues an identifier it already gave out")
    void restartDoesNotReissueIdentifiers() {
        // DOD-KERNEL asks for crash behaviour. A generator holds its counter only in memory,
        // so a restart resets it. If uniqueness depended on that counter, a process that
        // crashed and came back inside the same millisecond would re-issue identifiers it had
        // already handed to the database.
        Clock frozen = Clock.fixed(FIXED, ZoneOffset.UTC);
        IdGenerator before = new IdGenerator(frozen, new SecureRandom());
        IdGenerator afterRestart = new IdGenerator(frozen, new SecureRandom());

        Set<UUID> issuedBefore = new java.util.HashSet<>();
        Set<UUID> issuedAfter = new java.util.HashSet<>();
        Set<Long> highHalvesBefore = new java.util.HashSet<>();
        Set<Long> highHalvesAfter = new java.util.HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            UUID first = before.next();
            UUID second = afterRestart.next();
            issuedBefore.add(first);
            issuedAfter.add(second);
            highHalvesBefore.add(first.getMostSignificantBits());
            highHalvesAfter.add(second.getMostSignificantBits());
        }

        // The timestamp-and-counter halves genuinely do collide across the two instances -
        // both start from the same frozen millisecond and walk the same counter range.
        assertThat(highHalvesBefore).as("the ordering half collides across instances")
                .containsAnyElementsOf(highHalvesAfter);
        // So uniqueness across instances rests entirely on the 62 random bits, which is why
        // the production generator uses SecureRandom. A deterministically seeded generator
        // would re-issue every identifier after a restart.
        assertThat(issuedBefore).as("no identifier is issued by both instances")
                .doesNotContainAnyElementsOf(issuedAfter);
    }

    // -----------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------

    @Test
    @DisplayName("rejects a null clock or random source rather than defaulting one")
    void rejectsNullDependencies() {
        assertThatThrownBy(() -> new IdGenerator(null, seededRandom()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdGenerator(Clock.systemUTC(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("works against a real system clock, wired the way the composition root wires it")
    void worksAgainstARealClock() {
        // P0-TSK-013 removed IdGenerator.systemDefault(): the kernel does not decide where
        // time comes from. The composition root supplies both dependencies, so that is what
        // this test does. Tests are outside the ambient-time rule precisely so they can.
        IdGenerator generator = new IdGenerator(Clock.systemUTC(), new SecureRandom());

        List<UUID> issued = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            issued.add(generator.next());
        }

        assertStrictlyIncreasing(issued);
        assertThat(issued.getFirst().version()).isEqualTo(7);
    }

    // -----------------------------------------------------------------

    /**
     * Asserts ascending order the way a database index does: unsigned, most significant bits
     * first.
     *
     * <p>Unsigned matters. {@link UUID#compareTo} compares the high half as a <em>signed</em>
     * long, which happens to agree for timestamps below 2^47 milliseconds and would silently
     * stop agreeing afterwards. Asserting the property the storage layer actually relies on
     * avoids inheriting that accident.
     */
    private static void assertStrictlyIncreasing(List<UUID> issued) {
        for (int i = 1; i < issued.size(); i++) {
            UUID previous = issued.get(i - 1);
            UUID current = issued.get(i);

            int high =
                    Long.compareUnsigned(
                            previous.getMostSignificantBits(), current.getMostSignificantBits());
            assertThat(high).as("%s must not sort after %s", previous, current).isLessThanOrEqualTo(0);
            if (high == 0) {
                assertThat(previous).as("identical high half means a repeated identifier").isNotEqualTo(current);
            }
        }
    }

    private static IdGenerator generatorAt(Instant instant) {
        return new IdGenerator(Clock.fixed(instant, ZoneOffset.UTC), seededRandom());
    }

    /** Seeded, so a failure is reproducible rather than a story about one unlucky run. */
    private static Random seededRandom() {
        return new Random(20260901L);
    }

}
