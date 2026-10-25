package com.finapp.risk;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A {@link VelocityWindowStore} in memory for the hermetic suites (`P13-TSK-006`) - the upsert's arithmetic as
 * {@code risk V005} states it (born at 1, then +1 and + amount; another currency or scale refused), the database's clock
 * a value the test sets, and every call recorded in order so a test can read what was locked, read and written, and
 * when. Single-threaded by design: the concurrency is the owed {@code VelocityWindowDatabaseTest}'s.
 */
final class FakeVelocityWindowStore implements VelocityWindowStore {

    /** The database's instant {@link #databaseNow} answers. */
    Instant now;

    /** Every call, in order: {@code serialise}, {@code now}, {@code find n}, {@code upsert DIM KIND}, {@code live}. */
    final List<String> calls = new ArrayList<>();

    /** Every key upserted, in the order the statements ran. */
    final List<VelocityWindowKey> upserted = new ArrayList<>();

    final Map<VelocityWindowKey, VelocityWindow> rows = new TreeMap<>();

    FakeVelocityWindowStore(Instant now) {
        this.now = now;
    }

    @Override
    public Instant databaseNow(Connection unitOfWork) {
        calls.add("now");
        return now;
    }

    /** Every R2a key, in the order taken. */
    final List<Integer> serialised = new ArrayList<>();

    @Override
    public void serialise(Connection unitOfWork, int lockKey) {
        calls.add("serialise");
        serialised.add(lockKey);
    }

    @Override
    public List<VelocityWindow> find(Connection unitOfWork, Collection<VelocityWindowKey> keys) {
        calls.add("find " + keys.size());
        List<VelocityWindow> found = new ArrayList<>();
        for (VelocityWindowKey key : keys) {
            VelocityWindow row = rows.get(key);
            if (row != null) {
                found.add(row);
            }
        }
        return found;
    }

    @Override
    public Optional<VelocityWindow> upsert(Connection unitOfWork, VelocityWindowKey key, Money amount) {
        calls.add("upsert " + key.dimension() + " " + key.windowKind());
        upserted.add(key);
        VelocityWindow row = rows.get(key);
        if (row == null) {
            row = new VelocityWindow(key, 1, amount, now);
        } else if (!row.sameMoneyAs(amount)) {
            return Optional.empty();
        } else {
            row = new VelocityWindow(key, row.count() + 1, row.sum().plus(amount), now);
        }
        rows.put(key, row);
        return Optional.of(row);
    }

    @Override
    public List<VelocityWindow> live(Connection unitOfWork) {
        calls.add("live");
        List<VelocityWindow> live = new ArrayList<>();
        for (VelocityWindow row : rows.values()) {
            if (row.key().windowEnd().isAfter(now)) {
                live.add(row);
            }
        }
        return live;
    }

    @Override
    public Optional<Instant> firstCounted(Connection unitOfWork, VelocityDimension dimension, String subjectRef) {
        calls.add("first " + dimension);
        return rows.keySet().stream()
                .filter(key -> key.dimension() == dimension && key.subjectRef().equals(subjectRef)
                        && key.windowKind() == WindowKind.H1)
                .map(VelocityWindowKey::windowStart)
                .min(Instant::compareTo);
    }

    /** Plants a row as it stands - a drifted window for the proof, or another currency's for the refusal. */
    void plant(VelocityWindow row) {
        rows.put(row.key(), row);
    }

    /** True when no write ever reached this store. */
    boolean neverWritten() {
        return upserted.isEmpty();
    }
}
