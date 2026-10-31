package com.finapp.app.architecture;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * {@code ReportingNeverWritesTest}'s two planted probes for a connection of a report's own - a {@code DataSource} and
 * the {@code DriverManager} ({@code P14-TSK-008}'s review, I-2). They live here, outside the suite, because
 * {@code TestTaxonomyTest} folds every class nested in a suite into that suite's tier and reads either facility as
 * acquiring a database; the suite needs none, and this holder runs nothing.
 */
final class ReportingWritablePathProbes {

    /** The facility {@link PlantedReportDataSource} reaches. */
    static final Class<?> DATA_SOURCE = DataSource.class;

    /** The facility {@link PlantedReportDriverManager} reaches. */
    static final Class<?> DRIVER_MANAGER = DriverManager.class;

    private ReportingWritablePathProbes() {}

    /** A report class holding a data source - a connection of its own. */
    static class PlantedReportDataSource {
        DataSource pool;
    }

    /** A report class opening a connection of its own through the DriverManager. */
    static class PlantedReportDriverManager {
        Connection open(String url) throws SQLException {
            return DriverManager.getConnection(url);
        }
    }
}
