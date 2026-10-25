package com.finapp.risk;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Storage for the velocity windows (`P13-TSK-006`; {@code risk V005}; ADR-0033) - a port whose one write is the
 * one-statement upsert {@code risk V005} admits, every other method a read or a lock, all in the caller's unit of work.
 * No method takes a time from the caller: the clock is the database's ({@link #databaseNow}), read on the caller's
 * connection.
 */
public interface VelocityWindowStore {

    /** {@code clock_timestamp()} on {@code unitOfWork} - the database's instant now, after whatever it holds. */
    Instant databaseNow(Connection unitOfWork);

    /**
     * (R2a) one subject's serialisation: {@code pg_advisory_xact_lock(131, lockKey)}, blocking, held to the caller's
     * commit - every reader-then-writer of one subject's windows queues here, whether or not a row exists yet. The key
     * is {@link VelocityWindows#lockKey}'s, computed by the caller, which takes its keys in ascending order: the order is
     * the keys' own, so two subjects whose keys collide share one lock and can never be taken in two orders.
     */
    void serialise(Connection unitOfWork, int lockKey);

    /** The rows of {@code keys} that exist, no lock taken - a key with no row is absent from the answer. */
    List<VelocityWindow> find(Connection unitOfWork, Collection<VelocityWindowKey> keys);

    /**
     * (R2) one statement: {@code INSERT … ON CONFLICT (key) DO UPDATE SET count = count + 1, sum_minor = sum_minor +
     * EXCLUDED.sum_minor WHERE} the row holds {@code amount}'s currency and scale {@code RETURNING} the row - the window
     * after this movement. Empty when the existing row holds another currency or scale: nothing was counted.
     */
    Optional<VelocityWindow> upsert(Connection unitOfWork, VelocityWindowKey key, Money amount);

    /** Every live window - its end after the database clock now - for the proof; no lock taken. */
    List<VelocityWindow> live(Connection unitOfWork);

    /**
     * When {@code subjectRef} was first counted in {@code dimension} (`P13-TSK-013`): the start of its earliest
     * {@code H1} window - the first permitted movement it took part in, to the hour - or empty when it never was. No
     * lock taken. The novelty readings ({@code NEW_PAYEE}'s account destination, {@code NEW_DEVICE}) read it.
     */
    Optional<Instant> firstCounted(Connection unitOfWork, VelocityDimension dimension, String subjectRef);
}
