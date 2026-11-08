package com.finapp.app.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Where report generation's connections come from (`P15-TSK-021`): {@link ReportingPool}, as {@code finapp_reporting},
 * in production. A port rather than the pool's class, so the wiring test can see which source a generation asked
 * without a database; it has one implementation, and the application's {@code DataSource} is never one.
 */
@FunctionalInterface
public interface ReportingConnections {

    /** A connection as the reporting role, or the reason none could be opened. */
    Connection open() throws SQLException;
}
