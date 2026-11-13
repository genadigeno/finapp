package com.finapp.app.api;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;

/**
 * {@code SheddingIsWiredTest}'s reading of the context's writer pool, as built (`P16-TSK-016`): the HikariCP pool behind the
 * application's data source, whose size and borrow wait the shedding door must have read. A top-level helper rather than a part of the
 * suite, for {@code HeldConnectionSource}'s reason: {@code TestTier}'s database signature reads {@code javax.sql.DataSource} in a test
 * class's tree as "obtains a database", which is false here - the pool's settings are read and nothing connects.
 */
final class WriterPool {

    private WriterPool() {}

    static HikariDataSource of(ApplicationContext context) throws SQLException {
        return context.getBean(DataSource.class).unwrap(HikariDataSource.class);
    }
}
