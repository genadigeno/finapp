package com.finapp.platform.testing.kafka;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Kafka broker per test JVM, for the {@code kafka} tier (`P2-TSK-001`).
 *
 * <p>The {@code DatabaseUnderTest} pattern, applied to the second piece of infrastructure a test
 * can need: gated on an image property only the {@code kafkaTest} task sets, so a hermetic JVM —
 * or a database-tier JVM — starts no broker; the image comes from the version catalog through the
 * Gradle task, so the broker tests run against is the broker {@code compose.yaml} runs and
 * {@code verifyInfrastructureVersions} guards.
 *
 * <p>Publishes {@code finapp.kafka.bootstrap} for tests to read. An externally supplied
 * {@code finapp.kafka.bootstrap} (the compose stack, port 29092) is left alone — the same
 * deliberate escape hatch the database harness documents, for investigating messages that should
 * outlive the run.
 *
 * <p>Lives in its own {@code testing.kafka} package, not beside the database harness:
 * {@code TestTier}'s tier-detection signatures are package prefixes, and a class in
 * {@code testing.database} is evidence a test needs PostgreSQL — which a Kafka harness is not.
 */
public final class KafkaUnderTest implements LauncherSessionListener {

    /** Set by the Gradle task from the version catalog, so the image has one definition. */
    private static final String IMAGE_PROPERTY = "finapp.kafka.image";

    private static final String BOOTSTRAP_PROPERTY = "finapp.kafka.bootstrap";

    /** An instance field, not static — the {@code DatabaseUnderTest} reasoning (ADR-0024). */
    private KafkaContainer container;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (System.getProperty(BOOTSTRAP_PROPERTY) != null) {
            // Deliberately pointed somewhere - the compose stack, usually. Leave it alone.
            return;
        }
        String image = System.getProperty(IMAGE_PROPERTY);
        if (image == null) {
            // Not a kafka-tier run. Only the kafkaTest task sets the image; every other JVM
            // must not pay for a broker it will never talk to.
            return;
        }
        container = new KafkaContainer(DockerImageName.parse(image));
        container.start();
        System.setProperty(BOOTSTRAP_PROPERTY, container.getBootstrapServers());
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        if (container != null) {
            container.stop();
        }
    }
}
