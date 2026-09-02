package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.FinappApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The guard is wired into startup, not merely correct in isolation.
 *
 * <p>{@link DatabaseCredentialGuardTest} proves the decision. This proves it is <em>reached</em> -
 * a distinction worth a separate test, because a control that is right and unreachable is the
 * defect this project has already met twice: a rule that could not fail ({@code P0-TST-008}) and
 * an override that never ran ({@code P0-TSK-025}). Both compiled, both read correctly.
 *
 * <p>The real application class is used, not a slice. Whether component scanning reaches
 * {@code com.finapp.app.security} is exactly the thing being asserted, so a test context that
 * registered the bean by hand would prove the opposite of what is wanted.
 */
class DatabaseCredentialGuardStartupTest {

    @Test
    @DisplayName("the application refuses to start on the marked default against a remote database")
    void startupIsRefused() {
        assertThatThrownBy(() -> start("jdbc:postgresql://db.internal:5432/finapp"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining(DatabaseCredentialGuard.REQUIRED_ENVIRONMENT_VARIABLE);
    }

    @Test
    @DisplayName("the pool's own properties cannot route around the guard")
    void hikariPropertiesDoNotBypassTheGuard() {
        // A real bypass, not a theoretical one. `spring.datasource.hikari.jdbc-url` is bound after
        // the generic property and wins, so with the generic URL left on loopback the application
        // started, and the pool's own jdbcUrl was the remote host - verified by unwrapping the
        // HikariDataSource. A guard that reads a value nothing connects with is not a guard.
        assertThatThrownBy(
                        () ->
                                new SpringApplicationBuilder(FinappApplication.class)
                                        .web(WebApplicationType.NONE)
                                        .run(
                                                "--spring.datasource.hikari.jdbc-url=jdbc:postgresql://db.internal:5432/x"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start");
    }

    @Test
    @DisplayName("and starts against a local one, so the refusal above is not simply a broken app")
    void startupSucceedsLocally() {
        // The positive control. Without it, the assertion above passes for any reason the context
        // fails at all - a missing bean, a bad property, anything - and would keep passing after
        // the guard was deleted.
        try (ConfigurableApplicationContext context =
                start("jdbc:postgresql://127.0.0.1:1/absent")) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getBean(DatabaseCredentialGuard.class)).isNotNull();
        }
    }

    /**
     * No password property is set, deliberately: the point is the value {@code application.yaml}
     * falls back to when the environment supplies nothing, which is the situation being defended
     * against. Setting it here would test a different scenario and pass regardless.
     *
     * <p>The URL arrives as a <strong>command-line argument</strong>, not through
     * {@code SpringApplicationBuilder.properties(...)}. That method populates Boot's
     * <em>default</em> property source, which ranks <em>below</em> {@code application.yaml} - so
     * the first version of this test silently kept the committed URL and never used the one it
     * passed. It showed up as the negative case not throwing; the positive case had been green
     * throughout while asserting nothing about its own argument. A command-line source outranks
     * the config file, which is what makes both cases test the URL they name.
     */
    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(FinappApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + url);
    }
}
