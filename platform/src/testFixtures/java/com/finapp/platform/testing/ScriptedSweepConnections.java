package com.finapp.platform.testing;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A JDBC double answering a retention sweep's one statement from a script (`P15-TSK-029`), so the tick loop, the transaction
 * and the connection accounting can be tested with no database. What the statement selects is the migration's, tested
 * elsewhere: this records what was sent and how the connection was handled.
 */
public final class ScriptedSweepConnections {

    /** One batch's outcome: a row count, or a failure carrying a SQLState. */
    private record Answer(int deleted, String sqlState) {}

    /** What one batch did with its connection. */
    public record Batch(String statement, long boundMillis, int batchSize, boolean committed, boolean rolledBack, boolean closed) {}

    private static final class Observed {
        String statement;
        long bound;
        int size;
        boolean committed;
        boolean rolledBack;
        boolean closed;
    }

    private final Deque<Answer> script = new ArrayDeque<>();
    private final List<Observed> observed = new ArrayList<>();
    private int open;
    private int peakOpen;

    /** Queues batches that each delete {@code deleted} rows. */
    public ScriptedSweepConnections deleting(int... deleted) {
        for (int count : deleted) {
            script.add(new Answer(count, null));
        }
        return this;
    }

    /** Queues a batch that fails with {@code sqlState}. */
    public ScriptedSweepConnections failingWith(String sqlState) {
        script.add(new Answer(0, sqlState));
        return this;
    }

    /** The next connection: usable as a method reference for a sweep's connection source. */
    public Connection open() {
        if (script.isEmpty()) {
            throw new AssertionError("a batch ran that the script did not expect");
        }
        Answer answer = script.remove();
        Observed batch = new Observed();
        observed.add(batch);
        open++;
        peakOpen = Math.max(peakOpen, open);
        return proxy(Connection.class, (self, method, arguments) -> switch (method.getName()) {
            case "setAutoCommit" -> null;
            case "prepareStatement" -> {
                batch.statement = (String) arguments[0];
                yield statement(batch, answer);
            }
            case "commit" -> {
                batch.committed = true;
                yield null;
            }
            case "rollback" -> {
                batch.rolledBack = true;
                yield null;
            }
            case "close" -> {
                batch.closed = true;
                open--;
                yield null;
            }
            default -> throw new AssertionError("a sweep does not call Connection." + method.getName());
        });
    }

    private static PreparedStatement statement(Observed batch, Answer answer) {
        return proxy(PreparedStatement.class, (self, method, arguments) -> switch (method.getName()) {
            case "setLong" -> {
                batch.bound = (Long) arguments[1];
                yield null;
            }
            case "setInt" -> {
                batch.size = (Integer) arguments[1];
                yield null;
            }
            case "executeQuery" -> {
                if (answer.sqlState() != null) {
                    throw new SQLException("the script's failure", answer.sqlState());
                }
                yield result(answer.deleted());
            }
            case "close" -> null;
            default -> throw new AssertionError("a sweep does not call PreparedStatement." + method.getName());
        });
    }

    private static ResultSet result(int deleted) {
        return proxy(ResultSet.class, (self, method, arguments) -> switch (method.getName()) {
            case "next" -> true;
            case "getInt" -> deleted;
            case "close" -> null;
            default -> throw new AssertionError("a sweep does not call ResultSet." + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    /** Every batch run so far, in order. */
    public List<Batch> batches() {
        return observed.stream()
                .map(b -> new Batch(b.statement, b.bound, b.size, b.committed, b.rolledBack, b.closed))
                .toList();
    }

    /** The most connections held at one moment. */
    public int peakOpen() {
        return peakOpen;
    }
}
