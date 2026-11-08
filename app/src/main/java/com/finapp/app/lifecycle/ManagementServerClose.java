package com.finapp.app.lifecycle;

import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;

/**
 * The management server closes at once, not gracefully (`P15-TSK-009`, from `P15-TSK-008`'s review; ADR-0120 decision 4).
 *
 * <p>Spring Boot gives the management server's child context the API's {@code server.shutdown: graceful}, so a scrape still
 * running when that context closes held the close for its own phase timeout: thirty seconds the grace period had not counted.
 * Nothing on 8081 needs draining. A probe or a scrape cut off is asked again, or the pod is already gone from the answer.
 * Tomcat's own stop still waits a moment for a servlet in flight ({@code StandardWrapper}'s two-second unload delay), which
 * the grace period counts.
 *
 * <p><strong>Deliberately not annotated.</strong> {@code @ManagementContextConfiguration} is a meta-{@code @Configuration}, so
 * the application's component scan would pick it up and apply this to the API server's factory too: the accepted payment
 * request cut off that {@code server.shutdown: graceful} exists to prevent. Named only in
 * {@code META-INF/spring/...ManagementContextConfiguration.imports}, it reaches the child context alone: the selector treats
 * an unannotated class as {@code ANY}, and with a management port of its own (which {@code ManagementAddressGuard} requires)
 * only the child context selects at all. Last in the customizers' order, after the one that copies {@code server.shutdown}
 * in. {@code ShutdownOrderTest} holds both: a held scrape does not hold the close, and a held API request still finishes.
 */
public final class ManagementServerClose
        implements WebServerFactoryCustomizer<ConfigurableWebServerFactory>, Ordered {

    @Override
    public void customize(ConfigurableWebServerFactory factory) {
        factory.setShutdown(Shutdown.IMMEDIATE);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
