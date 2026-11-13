package com.finapp.app.architecture;

import com.finapp.app.database.ReportingConnections;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;

/**
 * The planted probes of {@link OnePrimaryServesEveryDoorTest} (`P16-TSK-012`, ADR-0142): each way a second server could be reached,
 * planted so the guard is seen refusing it, and one door on the writer it must pass. Never called. A class of their own, holding no
 * test: they acquire connections by {@code DriverManager} and a {@code DataSource}, which {@code TestTaxonomyTest} rightly reads as a
 * database tier's wherever a test class or its nested classes hold them, and the guard itself needs none.
 */
final class OnePrimaryPlants {

    private OnePrimaryPlants() {}

    /** A replica's URL written into a class. */
    static class PlantedReplicaUrl {
        static String url() {
            return "jdbc:postgresql://replica.internal:5432/finapp";
        }
    }

    /** A replica's address read from a property. */
    static class PlantedReplicaProperty {
        static String key() {
            return "finapp.database.reporting.url";
        }
    }

    /** A replica's address injected by a placeholder. */
    static class PlantedReplicaValue {
        @org.springframework.beans.factory.annotation.Value("${finapp.database.replica.url:}")
        String url;
    }

    /** A replica's address read from the environment. */
    static class PlantedReplicaVariable {
        static String variable() {
            return "FINAPP_DB_REPLICA_URL";
        }
    }

    /** A second pool of its own. */
    static class PlantedSecondPool {
        HikariDataSource pool() {
            return new HikariDataSource();
        }
    }

    /** A connection opened by the JDK's driver manager. */
    static class PlantedDriverConnection {
        Connection open(String url) throws SQLException {
            return DriverManager.getConnection(url);
        }
    }

    /** A door on the writer's pool: the shape every money-moving door has. */
    static class PlantedDoorOnTheWriter {
        private final DataSource writer;

        PlantedDoorOnTheWriter(DataSource writer) {
            this.writer = writer;
        }

        Connection unitOfWork() throws SQLException {
            return writer.getConnection();
        }
    }

    /** A transfer door reading a balance through report generation's pool. */
    static class PlantedTransferDoor {
        private final ReportingConnections reporting;

        PlantedTransferDoor(ReportingConnections reporting) {
            this.reporting = reporting;
        }

        Connection balanceCheck() throws SQLException {
            return reporting.open();
        }
    }

    /** A wiring class shaped like {@link AccountingBeans}: its generation method may take the pool; a door's bean may not. */
    static class PlantedGenerationBeans {
        Object generationSnapshots(ReportingConnections reporting) {
            return (java.util.function.Supplier<Object>) () -> reporting;
        }

        Object transferExecution(ReportingConnections reporting) {
            return reporting;
        }
    }

    /** Two beans that each add a pool beside the writer's. */
    static class PlantedDataSourceBeans {
        @Bean
        DataSource replica() {
            return null;
        }

        @Bean
        HikariDataSource hikari() {
            return null;
        }

        @Bean
        Object notAPool() {
            return null;
        }
    }
}
