package com.finapp.app.shedding;

import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * {@code LoadSheddingTest}'s data sources, none of which connects (`P16-TSK-016`): a HikariCP pool behind a wrapper that unwraps as
 * {@code TracedDataSource} does, and a data source that is no HikariCP pool. A top-level helper rather than a part of the suite, for
 * {@code HeldConnectionSource}'s reason: {@code TestTier}'s database signature reads {@code javax.sql.DataSource} in a test class's
 * tree as "obtains a database", which is false here - the pool is never started and nothing opens a connection.
 */
final class PoolSources {

    private PoolSources() {}

    /** The queue read from {@code hikari} through a wrapper, as the application's traced data source is read. */
    static PoolQueue throughAWrapper(HikariDataSource hikari) {
        return HikariPoolQueue.of(wrapping(hikari));
    }

    /** The queue asked of a data source that is not, and wraps no, HikariCP pool: refused. */
    static PoolQueue fromAnotherPool() {
        DataSource other = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isWrapperFor" -> false;
                    case "unwrap" -> throw new SQLException("not a wrapper");
                    case "toString" -> "another pool";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return HikariPoolQueue.of(other);
    }

    /** A wrapper that unwraps as {@code TracedDataSource} does: itself, or its delegate. */
    private static DataSource wrapping(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy) || delegate.isWrapperFor((Class<?>) args[0]);
                    case "unwrap" -> ((Class<?>) args[0]).isInstance(proxy) ? proxy : delegate.unwrap((Class<?>) args[0]);
                    case "getLogWriter" -> (PrintWriter) null;
                    case "toString" -> "traced";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
