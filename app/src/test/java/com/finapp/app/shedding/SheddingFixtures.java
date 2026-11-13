package com.finapp.app.shedding;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.random.RandomGenerator;

/** The shedding tests' doubles: a pool whose queue a test sets, a clock it moves, and a jitter it scripts. */
final class SheddingFixtures {

    private SheddingFixtures() {}

    /** The writer pool as built: 7 connections, a 5 s borrow wait; its queue is the test's. */
    static final class Pool implements PoolQueue {

        volatile int waiting;
        private final int size;
        private final Duration borrowWait;

        Pool() {
            this(7, Duration.ofSeconds(5));
        }

        Pool(int size, Duration borrowWait) {
            this.size = size;
            this.borrowWait = borrowWait;
        }

        @Override
        public int waiting() {
            return waiting;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public Duration borrowWait() {
            return borrowWait;
        }
    }

    /** A clock a test moves forward. */
    static final class MovingClock extends Clock {

        private volatile Instant now = Instant.parse("2026-11-13T12:00:00Z");

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

    /** Always the same draw: {@code 0} the least, {@code bound - 1} the most a jitter can add. */
    static RandomGenerator draws(boolean most) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException("only bounded draws are taken");
            }

            @Override
            public long nextLong(long bound) {
                return most ? bound - 1 : 0;
            }
        };
    }

    /** The application's defaults: 7 waiters, 5 command permits, a 10 s deadline, Retry-After 1 s plus up to 2. */
    static LoadShedding.Bounds defaults() {
        return new LoadShedding.Bounds(7, 5, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(2));
    }
}
