package com.finapp.migrator;

import java.util.Map;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The migrator's whole configuration: the three variables the build's Flyway tasks read, in the Job's environment.
 *
 * <p>There are no defaults for the URL or the password. The build's defaults point at a developer's compose stack; a
 * Job that fell back on them would be connecting somewhere nobody chose.
 *
 * @param url {@code FINAPP_DB_URL}, verified by {@link DatabaseUrl}
 * @param user {@code FINAPP_DB_MIGRATOR_USER}, {@code finapp_migrator} unless set, as in the build
 * @param password {@code FINAPP_DB_MIGRATOR_PASSWORD}; never in {@link #toString()}
 */
record MigratorEnvironment(String url, String user, String password) {

    /** The marked local credential: published in the repository, so it is no credential anywhere else. */
    static final String MARKED_LOCAL_DEFAULT = "local-development-only-not-a-secret";

    private static final String DEFAULT_USER = "finapp_migrator";

    static MigratorEnvironment from(Map<String, String> environment) {
        String url = environment.get("FINAPP_DB_URL");
        boolean loopback = DatabaseUrl.verify(url);
        String password = environment.get("FINAPP_DB_MIGRATOR_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new RefusedConfiguration("FINAPP_DB_MIGRATOR_PASSWORD is required");
        }
        if (!loopback && MARKED_LOCAL_DEFAULT.equals(password)) {
            throw new RefusedConfiguration("FINAPP_DB_MIGRATOR_PASSWORD is the marked local credential, off this machine");
        }
        String user = environment.get("FINAPP_DB_MIGRATOR_USER");
        return new MigratorEnvironment(url, user == null || user.isBlank() ? DEFAULT_USER : user, password);
    }

    /** Every connection this opens is named, and carries the lock bound from its first byte. */
    DataSource dataSource() {
        return dataSource(url, user, password, MigrationPlan.APPLICATION_NAME);
    }

    static DataSource dataSource(String url, String user, String password, String applicationName) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        dataSource.setApplicationName(applicationName);
        dataSource.setOptions(MigrationPlan.SESSION_OPTIONS);
        return dataSource;
    }

    @Override
    public String toString() {
        return "MigratorEnvironment[user=" + user + "]";
    }
}
