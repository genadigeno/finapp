package com.finapp.app.shedding;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The application's writer pool, read through HikariCP's own bean (`P16-TSK-016`, ADR-0137).
 *
 * <p><strong>Refused at start when the data source is not HikariCP.</strong> A door that could not read the queue would read zero for
 * ever and pass everything, which looks exactly like a door on an idle pool. The application's data source is Spring Boot's HikariCP,
 * wrapped for tracing ({@code TracedDataSource}), and the wrapper unwraps to it.
 *
 * <p>The waiting count is HikariCP's atomic count of threads blocked in a borrow: a read of one field, no lock, no connection. The
 * pool is lazy, so until its first borrow there is no pool bean and nothing can be waiting on it: zero then is the truth, not a
 * default.
 */
public final class HikariPoolQueue implements PoolQueue {

    private final HikariDataSource pool;

    private HikariPoolQueue(HikariDataSource pool) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
    }

    /**
     * @throws IllegalStateException if {@code dataSource} is not, and does not wrap, a HikariCP pool
     */
    public static HikariPoolQueue of(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                return new HikariPoolQueue(dataSource.unwrap(HikariDataSource.class));
            }
        } catch (SQLException unreadable) {
            throw new IllegalStateException("Refusing to start: the load-shedding door cannot read the writer pool's queue", unreadable);
        }
        throw new IllegalStateException("Refusing to start: the writer's data source is not a HikariCP pool, so the load-shedding door "
                + "could not read its queue and would admit everything (ADR-0137)");
    }

    @Override
    public int waiting() {
        HikariPoolMXBean started = pool.getHikariPoolMXBean();
        return started == null ? 0 : started.getThreadsAwaitingConnection();
    }

    @Override
    public int size() {
        return pool.getMaximumPoolSize();
    }

    @Override
    public Duration borrowWait() {
        return Duration.ofMillis(pool.getConnectionTimeout());
    }
}
