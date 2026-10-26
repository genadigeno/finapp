package com.finapp.risk;

import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * A {@link Connection} the hermetic suites hand to code that only passes it on to a fake store (`P13-TSK-006`) - every
 * method refuses, so a test can prove the code under test never used the connection itself, except the isolation level
 * a stand-in may declare.
 */
final class StandInConnections {

    private StandInConnections() {}

    /** A connection nothing may use. */
    static Connection unused() {
        return withIsolation(-1);
    }

    /** A connection that answers {@code isolation} and refuses everything else. */
    static Connection withIsolation(int isolation) {
        return (Connection) Proxy.newProxyInstance(StandInConnections.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    if (isolation >= 0 && method.getName().equals("getTransactionIsolation")) {
                        return isolation;
                    }
                    if (method.getName().equals("toString")) {
                        return "StandInConnection";
                    }
                    throw new UnsupportedOperationException("a stand-in connection is never used: " + method.getName());
                });
    }

    /** A read-only {@code REPEATABLE READ} snapshot in a transaction (`P13-TSK-023`) - the proofs' connection. */
    static Connection snapshot() {
        return declaring(true, false, Connection.TRANSACTION_REPEATABLE_READ);
    }

    /** A connection declaring {@code readOnly}, {@code autoCommit} and {@code isolation}, refusing everything else. */
    static Connection declaring(boolean readOnly, boolean autoCommit, int isolation) {
        return (Connection) Proxy.newProxyInstance(StandInConnections.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isReadOnly" -> readOnly;
                    case "getAutoCommit" -> autoCommit;
                    case "getTransactionIsolation" -> isolation;
                    case "toString" -> "StandInSnapshot";
                    default -> throw new UnsupportedOperationException(
                            "a stand-in snapshot is never used: " + method.getName());
                });
    }
}
