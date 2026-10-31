package com.finapp.app.accounting;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in JDBC session for the GL's snapshot (`P14-TSK-007`): it remembers what the code set - autocommit, the
 * isolation level, read-only - and answers {@link GlSnapshots#VERIFY} the way PostgreSQL would from those settings
 * ({@code repeatable read} only after {@code TRANSACTION_REPEATABLE_READ}, {@code on} only after
 * {@code setReadOnly(true)}), recording every call in order. Any other statement is refused: the desk's suites run the
 * journal through {@code InMemoryJournal}, never through this connection.
 */
final class SnapshotSession {

    final List<String> calls = new ArrayList<>();
    private boolean autoCommit = true;
    private int isolation = Connection.TRANSACTION_READ_COMMITTED;
    private boolean readOnly;

    /** A connection over this session. */
    Connection connection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    switch (name) {
                        case "setAutoCommit" -> {
                            autoCommit = (Boolean) args[0];
                            calls.add("autoCommit=" + autoCommit);
                            return null;
                        }
                        case "setTransactionIsolation" -> {
                            isolation = (Integer) args[0];
                            calls.add("isolation=" + isolation);
                            return null;
                        }
                        case "setReadOnly" -> {
                            readOnly = (Boolean) args[0];
                            calls.add("readOnly=" + readOnly);
                            return null;
                        }
                        case "rollback", "close", "commit" -> {
                            calls.add(name);
                            return null;
                        }
                        case "prepareStatement" -> {
                            String sql = (String) args[0];
                            if (!sql.equals(GlSnapshots.VERIFY)) {
                                throw new IllegalStateException("the stand-in session serves the verification only");
                            }
                            calls.add("verify");
                            return statement();
                        }
                        case "toString" -> {
                            return "SnapshotSession";
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        default -> throw new UnsupportedOperationException(name);
                    }
                });
    }

    private PreparedStatement statement() {
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> row();
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private ResultSet row() {
        boolean[] served = {false};
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        boolean first = !served[0];
                        served[0] = true;
                        yield first;
                    }
                    case "getString" -> (Integer) args[0] == 1
                            ? (!autoCommit && isolation == Connection.TRANSACTION_REPEATABLE_READ ? "repeatable read"
                                    : "read committed")
                            : (!autoCommit && readOnly ? "on" : "off");
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
