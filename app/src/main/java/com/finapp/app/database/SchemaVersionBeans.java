package com.finapp.app.database;

import java.time.Clock;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The schema-version guard and its readiness contributor (`P15-TSK-005`; ADR-0122 decision 5, ADR-0016 kept). */
@Configuration(proxyBeanMethods = false)
class SchemaVersionBeans {

    @Bean
    ExpectedSchemaVersions expectedSchemaVersions() {
        return ExpectedSchemaVersions.embedded();
    }

    @Bean
    AppliedSchemaVersions appliedSchemaVersions(DataSource dataSource) {
        return new JdbcAppliedSchemaVersions(dataSource);
    }

    @Bean
    SchemaVersionGuard schemaVersionGuard(ExpectedSchemaVersions expected, AppliedSchemaVersions applied, Clock clock) {
        return new SchemaVersionGuard(expected, applied, clock, SchemaVersionGuard.ON_A_DAEMON_THREAD);
    }

    /** Named so that the contributor is {@code schemaVersion}, the name the readiness group includes. */
    @Bean
    SchemaVersionHealthIndicator schemaVersionHealthIndicator(SchemaVersionGuard guard) {
        return new SchemaVersionHealthIndicator(guard);
    }

    /**
     * The first read as the application starts, so that a replica on the right schema is ready at its first probe.
     * {@code finapp.schema-version.read-at-start} (default true) is for the test contexts, as {@code finapp.gauges.read-at-start}
     * is: without it every context would open a connection on a thread of its own. Off, a probe still asks.
     */
    @Bean
    FirstRead schemaVersionFirstRead(@Value("${finapp.schema-version.read-at-start:true}") boolean readAtStart, SchemaVersionGuard guard) {
        return new FirstRead(readAtStart, guard);
    }

    /** A named class, not a lambda: Spring reads the event type from its generic signature, which a lambda does not carry. */
    @RequiredArgsConstructor
    static final class FirstRead implements ApplicationListener<ApplicationStartedEvent> {

        private final boolean readAtStart;
        private final SchemaVersionGuard guard;

        @Override
        public void onApplicationEvent(ApplicationStartedEvent started) {
            if (readAtStart) {
                guard.ask();
            }
        }
    }
}
