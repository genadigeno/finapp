package com.finapp.app.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * One statement, on one pooled connection, for every schema together: platform's {@code applied_migration_versions()},
 * because the application role cannot read any {@code flyway_schema_history} ({@code DATA_MIGRATIONS.md} section 6).
 *
 * <p>The highest version is chosen here and compared as a version. The function returns every successful row because the
 * largest of the text is the wrong one.
 */
@RequiredArgsConstructor
public final class JdbcAppliedSchemaVersions implements AppliedSchemaVersions {

    /** Named so that a test can hold it against the migration that defines it. */
    static final String READ = "SELECT schema_name, version FROM platform.applied_migration_versions()";

    /**
     * How long this connection may go unanswered. The data source sets no {@code socketTimeout}, so without this a
     * black-holed read would hold its thread until a restart, and no later read would run. HikariCP puts the connection's
     * own value back when it is returned.
     */
    static final int NETWORK_TIMEOUT_MILLIS = 3_000;

    private static final Executor ON_THE_CALLERS_THREAD = Runnable::run;

    @NonNull private final DataSource dataSource;

    @Override
    public Map<String, SchemaVersion> read() throws SQLException {
        Map<String, SchemaVersion> highest = new TreeMap<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setNetworkTimeout(ON_THE_CALLERS_THREAD, NETWORK_TIMEOUT_MILLIS);
            try (PreparedStatement statement = connection.prepareStatement(READ);
                    ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    highest.merge(rows.getString(1), SchemaVersion.parse(rows.getString(2)),
                            (one, other) -> one.compareTo(other) >= 0 ? one : other);
                }
            }
        }
        return highest;
    }
}
