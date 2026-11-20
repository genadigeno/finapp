package com.finapp.app.api;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * A connection for tests whose fakes never touch the database but whose desk reads the database's clock (`P17-TSK-039`: every
 * operator report's {@code asOf} is {@code DatabaseTime.now}). It answers exactly {@code SELECT statement_timestamp()} with a
 * fixed instant and refuses everything else, so a fake that does reach the connection still fails loudly.
 */
public final class ClockOnlyConnection {

    private ClockOnlyConnection() {}

    /** A connection whose database clock reads {@code now}, and which does nothing else. */
    public static Connection answering(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        ResultSet clock = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> true;
                    case "getTimestamp" -> Timestamp.from(now);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("the clock's answer has no " + method.getName());
                });
        Statement statement = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> {
                        if (!"SELECT statement_timestamp()".equals(args[0])) {
                            throw new UnsupportedOperationException("only the database's clock is read here: " + args[0]);
                        }
                        yield clock;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("the clock's statement has no " + method.getName());
                });
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("createStatement") && (args == null || args.length == 0)) {
                        return statement;
                    }
                    throw new UnsupportedOperationException("the fakes never touch the connection: " + method.getName());
                });
    }
}
