package com.finapp.app.eventing;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * The consumers' readiness contributor, {@code kafkaConsumers} (`P15-TSK-026`).
 *
 * <p>DOWN while the broker refuses any of this instance's consumer loops ({@link
 * InboxConsumers#brokerAccessRefused()}), UP otherwise - and UP on an instance whose consumers are
 * switched off, where there is nothing to refuse. A member of the readiness group and of no other
 * ({@code application.yaml}): a refused credential is the fault a release with a wrong secret
 * carries, so the replica must not take traffic and the rollout must not proceed past it; killing
 * it would not help, so liveness never reads it.
 *
 * <p>It reports no detail. The health endpoint publishes a status and nothing else
 * ({@code show-details: never}), and the loop's log line names the refused group.
 *
 * <p><strong>The cost, stated.</strong> Every replica shares one credential, so a credential revoked
 * before the fleet has rolled onto its successor takes every replica's readiness down at once.
 * Rotation is therefore add, roll, then remove (ADR-0123 decision 5), never remove first
 * ({@code OPERATIONS_RUNBOOK.md} section 11.6).
 */
public final class KafkaConsumersHealthIndicator implements HealthIndicator {

    private final Supplier<Boolean> refused;

    /**
     * @param refused whether the broker refuses a consumer loop now; {@code false} when this
     *     instance runs no consumers
     */
    public KafkaConsumersHealthIndicator(Supplier<Boolean> refused) {
        this.refused = Objects.requireNonNull(refused, "refused must not be null");
    }

    @Override
    public Health health() {
        return Boolean.TRUE.equals(refused.get()) ? Health.down().build() : Health.up().build();
    }
}
