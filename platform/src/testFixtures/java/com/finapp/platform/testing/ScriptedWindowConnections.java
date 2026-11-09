package com.finapp.platform.testing;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A JDBC double answering {@code WindowCounter}'s one statement (`P15-TSK-022`), so what the counter does with its connection, and what
 * a door does with the answer, can be tested with no database.
 *
 * <p>It stands in for PostgreSQL as one shared store: every connection sees the same counts, and each statement decides atomically, as the
 * row lock does. What the SQL actually does is held on a real database by {@code WindowCounterDatabaseTest}, owed to the exit's pass;
 * this records what was sent and how the connection was handled.
 */
public final class ScriptedWindowConnections {

    /** One statement's arguments, and how its connection was left. */
    public record Count(String statement, long windowSeconds, String scope, String key, int limit, boolean autoCommit, boolean closed) {}

    private static final class Observed {
        String statement;
        long windowSeconds;
        String scope;
        String key;
        int limit;
        boolean autoCommit;
        boolean closed;

        Count frozen() {
            return new Count(statement, windowSeconds, scope, key, limit, autoCommit, closed);
        }
    }

    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final List<Observed> observed = new CopyOnWriteArrayList<>();
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger peakOpen = new AtomicInteger();
    private volatile long retryAfterSeconds = 120;
    private volatile String failingWith;
    private volatile Runnable beforeEachCount = () -> {};

    /** Every count from now on fails with {@code sqlState}, the store being unavailable. */
    public ScriptedWindowConnections failingWith(String sqlState) {
        this.failingWith = sqlState;
        return this;
    }

    /** What the database reports as the time left in the window. */
    public ScriptedWindowConnections retryAfter(long seconds) {
        this.retryAfterSeconds = seconds;
        return this;
    }

    /** Runs inside each statement, while its connection is held: how a test holds counts in flight. */
    public ScriptedWindowConnections beforeEachCount(Runnable hook) {
        this.beforeEachCount = hook;
        return this;
    }

    public List<Count> counts() {
        return observed.stream().map(Observed::frozen).toList();
    }

    /** The most connections ever open at once. */
    public int peakOpen() {
        return peakOpen.get();
    }

    public int admittedFor(String scope, String key) {
        AtomicInteger count = counts.get(scope + "/" + key);
        return count == null ? 0 : count.get();
    }

    /** The next connection: usable as a method reference for a counter's connection source. */
    public Connection open() throws SQLException {
        if (failingWith != null) {
            throw new SQLException("the store is unavailable", failingWith);
        }
        int now = open.incrementAndGet();
        peakOpen.accumulateAndGet(now, Math::max);
        Observed count = new Observed();
        observed.add(count);
        InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
            case "setAutoCommit" -> {
                count.autoCommit = (boolean) arguments[0];
                yield null;
            }
            case "getAutoCommit" -> count.autoCommit;
            case "prepareStatement" -> {
                count.statement = (String) arguments[0];
                yield statement(count);
            }
            case "close" -> {
                count.closed = true;
                open.decrementAndGet();
                yield null;
            }
            case "isClosed" -> count.closed;
            default -> throw new UnsupportedOperationException("Connection." + method.getName());
        };
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    private PreparedStatement statement(Observed count) {
        InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
            case "setLong" -> {
                count.windowSeconds = (long) arguments[1];
                yield null;
            }
            case "setString" -> {
                if ((int) arguments[0] == 2) {
                    count.scope = (String) arguments[1];
                } else {
                    count.key = (String) arguments[1];
                }
                yield null;
            }
            case "setInt" -> {
                count.limit = (int) arguments[1];
                yield null;
            }
            case "executeQuery" -> result(decide(count));
            case "close" -> null;
            default -> throw new UnsupportedOperationException("PreparedStatement." + method.getName());
        };
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, handler);
    }

    /** The row's atomic decision: under the limit, the count moves; at it, nothing does. */
    private boolean decide(Observed count) {
        beforeEachCount.run();
        AtomicInteger counter = counts.computeIfAbsent(count.scope + "/" + count.key, ignored -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= count.limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private ResultSet result(boolean admitted) {
        boolean[] advanced = {false};
        InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
            case "next" -> {
                boolean first = !advanced[0];
                advanced[0] = true;
                yield first;
            }
            case "getInt" -> admitted ? 1 : 0;
            case "wasNull" -> !admitted;
            case "getLong" -> retryAfterSeconds;
            case "close" -> null;
            default -> throw new UnsupportedOperationException("ResultSet." + method.getName());
        };
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class}, handler);
    }
}
