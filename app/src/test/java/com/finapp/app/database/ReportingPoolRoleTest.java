package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The reporting pool never connects as a role that writes (`P15-TSK-021`, the review's fix round; {@code THREAT_MODEL.md}
 * T7, T44).
 *
 * <p>Nothing else stops it: the grants pin what {@code finapp_reporting} may do, and a {@code FINAPP_DB_REPORTING_USER}
 * naming the application's role, the migrator or the superuser makes those grants irrelevant - report generation reads
 * with every privilege the pool exists to remove, and every other check stays green. Startup refuses it. Hermetic: the
 * pool is lazy, so building one opens nothing.
 */
@DisplayName("the reporting pool refuses a writing role (P15-TSK-021)")
class ReportingPoolRoleTest {

    @Test
    @DisplayName("the writer's own role is refused, in any case, whether the generic property or Hikari's names it")
    void theWritersRoleIsRefused() {
        assertThatThrownBy(() -> build(settings("finapp_app").withProperty("spring.datasource.username", "finapp_app")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Refusing to start")
                .hasMessageContaining("the application's own database role");
        assertThatThrownBy(() -> build(settings("FINAPP_APP").withProperty("spring.datasource.username", "finapp_app")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> build(settings("deployed_writer")
                        .withProperty("spring.datasource.username", "finapp_app")
                        .withProperty("spring.datasource.hikari.username", "deployed_writer")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the migrator and the superuser are refused whatever the writer is called")
    void aWritingRoleIsRefused() {
        assertThatThrownBy(() -> build(settings("finapp_migrator"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a role that writes (finapp_migrator)");
        assertThatThrownBy(() -> build(settings("postgres"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the reporting role itself starts")
    void theReportingRoleStarts() {
        try (ReportingPool pool = build(settings("finapp_reporting").withProperty("spring.datasource.username",
                "finapp_app"))) {
            assertThat(pool.username()).isEqualTo("finapp_reporting");
        }
    }

    private static ReportingPool build(MockEnvironment environment) {
        return new ReportingPoolBeans().reportingPool(environment);
    }

    private static MockEnvironment settings(String reportingUser) {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/finapp")
                .withProperty(ReportingPool.USERNAME_PROPERTY, reportingUser)
                .withProperty(ReportingPool.LOGIN_PROPERTY, "supplied-by-the-test")
                .withProperty(ReportingPool.POOL_SIZE_PROPERTY, "1");
    }
}
