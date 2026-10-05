package com.finapp.app.crossborder;

import com.finapp.crossborder.CorridorAvailability;
import com.finapp.crossborder.CorridorAvailabilityStore;
import com.finapp.crossborder.CorridorDirectory;
import com.finapp.crossborder.CorridorPolicyAdministration;
import com.finapp.crossborder.CorridorPolicyStore;
import com.finapp.crossborder.JdbcCorridorAvailabilityStore;
import com.finapp.crossborder.JdbcCorridorPolicyStore;
import com.finapp.crossborder.TransactionRunner;
import com.finapp.payments.RailOperations;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The crossborder module's composition (`P9-TSK-015`): its transaction runner, the corridor policy
 * and availability stores and administrations, the {@link CorridorDirectory} port over payments'
 * declared corridor rails, and the desk behind {@link CorridorAdministrationController} and
 * {@link CorridorDiscoveryController}. crossborder has no build edge to payments (ADR-0079): the
 * directory is implemented here, from {@code RailOperations}' declarations, and handed in.
 */
@Configuration
public class CrossborderBeans {

    /** crossborder's transactions: default isolation (READ COMMITTED), the fx runner's shape. */
    @Bean
    TransactionRunner crossborderTransactionRunner(PlatformTransactionManager transactionManager, DataSource dataSource) {
        TransactionTemplate crossborderTransactions = new TransactionTemplate(transactionManager);
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                return crossborderTransactions.execute(
                        status -> {
                            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                            try {
                                return work.apply(unitOfWork);
                            } finally {
                                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                            }
                        });
            }
        };
    }

    /**
     * The corridor rails this build declares, with their coverage - read off every declared corridor
     * rail's {@code CorridorDeclaration}, whether or not its adapter is configured (`P9-TSK-015`).
     */
    @Bean
    CorridorDirectory corridorDirectory(RailOperations railOperations) {
        Set<CorridorDirectory.DeclaredRail> declared = railOperations.corridorDeclarations().stream()
                .map(declaration -> new CorridorDirectory.DeclaredRail(
                        declaration.rail().value(),
                        declaration.coverage().stream()
                                .map(coverage -> new CorridorDirectory.Coverage(coverage.country(), coverage.currency()))
                                .collect(Collectors.toUnmodifiableSet())))
                .collect(Collectors.toUnmodifiableSet());
        return () -> declared;
    }

    @Bean
    CorridorPolicyStore corridorPolicyStore(IdGenerator idGenerator) {
        return new JdbcCorridorPolicyStore(idGenerator);
    }

    @Bean
    CorridorAvailabilityStore corridorAvailabilityStore(IdGenerator idGenerator) {
        return new JdbcCorridorAvailabilityStore(idGenerator);
    }

    @Bean
    CorridorPolicyAdministration corridorPolicyAdministration(
            CorridorPolicyStore corridorPolicyStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            CorridorDirectory corridorDirectory) {
        return new CorridorPolicyAdministration(
                corridorPolicyStore, auditWriter, outboxWriter, idGenerator, corridorDirectory);
    }

    @Bean
    CorridorAvailability corridorAvailability(
            CorridorAvailabilityStore corridorAvailabilityStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator) {
        return new CorridorAvailability(corridorAvailabilityStore, auditWriter, outboxWriter, idGenerator);
    }

    @Bean
    CorridorAdministrationDesk corridorAdministrationDesk(
            CorridorPolicyAdministration corridorPolicyAdministration,
            CorridorAvailability corridorAvailability,
            CorridorDirectory corridorDirectory,
            IdempotentExecutor idempotentExecutor,
            TransactionRunner crossborderTransactionRunner,
            Clock clock) {
        return new CorridorAdministrationDesk(
                corridorPolicyAdministration, corridorAvailability, corridorDirectory, idempotentExecutor,
                crossborderTransactionRunner, clock);
    }
}
