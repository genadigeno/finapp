package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.security.DatabaseEndpoint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Report generation's pool reaches the writer's own address and no other (`P16-TSK-012`, ADR-0142; {@code PHASE_GATES.md} §5
 * Phase 16 criterion 6, its first branch: no replica is routed, so one primary serves every door).
 *
 * <p>The reporting pool is the one read path a replica could ever serve (ADR-0129 decision 7). Today it is built on
 * {@link DatabaseEndpoint#url}, the address the writer's pool connects with, Hikari's own property preferred as the pool prefers
 * it. A route to a replica would be a change here; this test is where such a change goes red, and ADR-0142 says what must be built
 * before it may go green again. Hermetic: the pool is lazy, so building it opens nothing.
 */
@DisplayName("the reporting pool reaches the writer's own address, never a second one (P16-TSK-012)")
class ReportingPoolEndpointTest {

    private static final String WRITER = "jdbc:postgresql://primary.internal:5432/finapp";
    private static final String ELSEWHERE = "jdbc:postgresql://replica.internal:5432/finapp";

    /** Names a replica route could plausibly be given: set to another server, each must be ignored while no replica is routed. */
    private static final List<String> REPLICA_NAMES = List.of("finapp.database.reporting.url", "finapp.database.reporting.jdbc-url",
            "finapp.database.replica.url", "finapp.database.reporting.host", "spring.datasource.replica.url",
            "spring.datasource.reporting.url");

    @Test
    @DisplayName("the pool's address is the writer's: the generic URL, or Hikari's own where it is set, as the writer's pool reads them")
    void theReportingPoolReachesTheWritersEndpoint() {
        MockEnvironment generic = settings().withProperty("spring.datasource.url", WRITER);
        try (ReportingPool pool = new ReportingPoolBeans().reportingPool(generic, new SimpleMeterRegistry())) {
            assertThat(pool.jdbcUrl()).isEqualTo(WRITER).isEqualTo(DatabaseEndpoint.url(generic));
        }

        MockEnvironment hikari = settings()
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/finapp")
                .withProperty("spring.datasource.hikari.jdbc-url", WRITER);
        try (ReportingPool pool = new ReportingPoolBeans().reportingPool(hikari, new SimpleMeterRegistry())) {
            assertThat(pool.jdbcUrl()).as("Hikari's own URL wins for the writer, so it is the reporting pool's too").isEqualTo(WRITER);
        }
    }

    @Test
    @DisplayName("an address set for reporting under any name a replica route might take is not read: the pool stays on the writer's")
    void noSecondAddressIsRead() {
        MockEnvironment environment = settings().withProperty("spring.datasource.url", WRITER);
        REPLICA_NAMES.forEach(name -> environment.setProperty(name, ELSEWHERE));
        try (ReportingPool pool = new ReportingPoolBeans().reportingPool(environment, new SimpleMeterRegistry())) {
            assertThat(pool.jdbcUrl()).isEqualTo(WRITER).doesNotContain("replica");
        }
    }

    private static MockEnvironment settings() {
        return new MockEnvironment()
                .withProperty(ReportingPool.USERNAME_PROPERTY, "finapp_reporting")
                .withProperty(ReportingPool.LOGIN_PROPERTY, "supplied-by-the-test")
                .withProperty(ReportingPool.POOL_SIZE_PROPERTY, "1");
    }
}
