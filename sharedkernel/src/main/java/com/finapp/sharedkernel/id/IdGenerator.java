package com.finapp.sharedkernel.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

/**
 * Generates time-ordered UUIDv7 values (RFC 9562 §5.7), monotonically and under contention.
 *
 * <p><strong>Why time-ordered rather than random.</strong> A random (v4) primary key writes to
 * a uniformly random position in the index on every insert, so the working set is the whole
 * index rather than its rightmost pages. At ledger volume that turns an append-only workload
 * into a random-write one: page splits, cache misses and index bloat that grow with table size.
 * A time-ordered key appends. ADR-0013 records the decision and the alternatives.
 *
 * <p><strong>Layout.</strong> 48 bits of Unix milliseconds, 4 version bits, 12 bits of
 * counter, 2 variant bits, 62 bits of randomness.
 *
 * <p><strong>Monotonicity is not a side effect of reading the clock.</strong> Three things
 * would otherwise break it, and each is handled explicitly:
 *
 * <ul>
 *   <li><em>Many identifiers in one millisecond.</em> The clock cannot separate them, so the
 *       12-bit field is a counter rather than randomness, incremented within the millisecond.
 *   <li><em>More than the counter can hold.</em> When it is exhausted the generator borrows
 *       the next millisecond rather than wrapping — wrapping would emit a value that sorts
 *       before one already issued, and could repeat it.
 *   <li><em>The clock going backwards.</em> NTP corrections and leap-second smearing move
 *       wall-clock time backwards, and a generator that trusted it would issue identifiers
 *       that sort before existing rows. Time never moves backwards here; a regressed clock is
 *       treated as the current millisecond and the counter carries the ordering.
 * </ul>
 *
 * <p><strong>What this deliberately leaks.</strong> A UUIDv7 exposes its creation time to
 * anyone holding it. That is inherent to the format, not an oversight: it is the same property
 * that gives index locality. The 62 random bits still make identifiers unguessable, so an
 * identifier cannot be enumerated — but it must never be treated as a secret or as a
 * capability, and an entity whose <em>existence time</em> is sensitive needs a different
 * identifier scheme rather than this one.
 *
 * <p>Thread-safe. Instances are cheap; one per application is the expected use.
 */
public final class IdGenerator {

    private static final int VERSION_7 = 0x7;
    private static final long VARIANT_RFC = 0b10L << 62;

    private static final int COUNTER_BITS = 12;
    private static final int MAX_COUNTER = (1 << COUNTER_BITS) - 1;

    /**
     * The counter is seeded in the lower half of its range, leaving at least 2048 increments
     * before exhaustion. Seeding randomly rather than at zero keeps identifiers issued in
     * consecutive milliseconds from sharing a predictable prefix; seeding low keeps the
     * headroom that makes borrowing from the next millisecond rare.
     */
    private static final int COUNTER_SEED_BOUND = 1 << (COUNTER_BITS - 1);

    /** 48 bits. Exceeded in the year 10889, at which point the format itself is the problem. */
    private static final long MAX_MILLIS = (1L << 48) - 1;

    private static final long RANDOM_B_MASK = (1L << 62) - 1;

    private final Clock clock;
    private final RandomGenerator random;
    private final AtomicReference<Tick> tick;

    /**
     * @param clock the time source; injected so generation is reproducible in tests and so
     *     this class does not read ambient time (P0-TSK-013)
     * @param random the randomness source for the 62 unguessable bits
     */
    public IdGenerator(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
        this.tick = new AtomicReference<>(new Tick(Long.MIN_VALUE, MAX_COUNTER));
    }

    /**
     * The ordinary configuration: UTC system time and a cryptographically strong random source.
     *
     * <p>{@link SecureRandom} rather than a plain PRNG because identifiers reach URLs, logs and
     * support tools. A predictable identifier is an enumeration primitive.
     */
    public static IdGenerator systemDefault() {
        return new IdGenerator(Clock.systemUTC(), new SecureRandom());
    }

    /**
     * The next identifier value. Never equal to, and never sorting before, one already
     * returned by this instance.
     */
    public UUID next() {
        Tick current;
        Tick next;
        do {
            current = tick.get();
            next = advance(current, clock.millis());
        } while (!tick.compareAndSet(current, next));

        return compose(next.millis(), next.counter(), random.nextLong() & RANDOM_B_MASK);
    }

    /**
     * The state transition, kept pure so the three monotonicity cases are testable as one
     * piece of reasoning rather than spread through a retry loop.
     */
    private Tick advance(Tick current, long now) {
        if (now > current.millis()) {
            return new Tick(now, random.nextInt(COUNTER_SEED_BOUND));
        }
        if (current.counter() < MAX_COUNTER) {
            // Same millisecond, or the clock moved backwards. Either way, time does not
            // regress here; the counter carries the ordering.
            return new Tick(current.millis(), current.counter() + 1);
        }
        // Counter exhausted. Borrowing the next millisecond keeps identifiers unique and
        // ordered; wrapping the counter would do neither.
        return new Tick(current.millis() + 1, random.nextInt(COUNTER_SEED_BOUND));
    }

    private static UUID compose(long millis, int counter, long randomB) {
        if (millis < 0L || millis > MAX_MILLIS) {
            throw new IllegalStateException(
                    "Timestamp " + millis + " does not fit the 48 bits a UUIDv7 allows");
        }
        long mostSignificant =
                (millis << 16) | ((long) VERSION_7 << COUNTER_BITS) | (counter & MAX_COUNTER);
        long leastSignificant = VARIANT_RFC | randomB;
        return new UUID(mostSignificant, leastSignificant);
    }

    /** The generator's whole mutable state, swapped atomically. */
    private record Tick(long millis, int counter) {}
}
