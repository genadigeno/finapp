package com.finapp.app.risk;

import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicies;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.risk.policy.active{operation}} (`P13-TSK-002`, {@code PHASE_13_PLAN.md} section 15; bnpl's
 * {@code BnplTermsMetrics}): the version number of the operation's {@code ACTIVE} policy version, 0 when it has none,
 * NaN when unreadable - never zero for a read that failed. No migration activates a version (C8), so until a person
 * other than its proposer activates one the operation is not judged: production reads 0 for every operation - the
 * closed state made visible, and the alert {@code RiskPolicyNoneActive}'s input where production is meant to judge
 * (the runbook's section 9). The plan's {@code policy_version} tag is answered by the value: a version number as a tag
 * would open a series per activation.
 *
 * <p>Registered eagerly per operation that carries a policy, so every operation has its series from startup; read from
 * the database, so every instance reports the same value - aggregate with {@code max()}, never {@code sum()}. Cached
 * for 15 s behind a per-instance refresh floor - never truth. A version number names no threshold or person.
 */
@Slf4j
public final class RiskPolicyMetrics {

    /** Spelled with dots - exported as {@code finapp_risk_policy_active}, the plan's name. */
    public static final String ACTIVE = "finapp.risk.policy.active";

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final RiskPolicies policies;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Map<RiskOperation, Integer>> activeVersions) {}

    public RiskPolicyMetrics(RiskPolicies policies, Connections connections, Clock clock, MeterRegistry registry) {
        this.policies = policies;
        this.connections = connections;
        this.clock = clock;
        for (RiskOperation operation : RiskOperation.values()) {
            if (operation == RiskOperation.CREDIT_SIGNAL) {
                continue; // carries no policy: it answers the profile's rating
            }
            Gauge.builder(ACTIVE, this, self -> self.activeVersionOf(operation))
                    .tag("operation", operation.name())
                    .description(
                            "The version number of this operation's ACTIVE risk policy - 0 when it has none, and the"
                                    + " operation is not judged until a person other than its proposer activates one."
                                    + " NaN when unreadable, never zero. Fleet-wide from every instance: aggregate with"
                                    + " max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double activeVersionOf(RiskOperation operation) {
        return refreshed().activeVersions()
                .map(versions -> (double) versions.getOrDefault(operation, 0))
                .orElse(Double.NaN);
    }

    private Cached refreshed() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            fresh = new Cached(clock.instant(), Optional.of(Map.copyOf(policies.activeVersions(connection))));
        } catch (SQLException | RuntimeException unreadable) {
            // NaN, not zero: a read that failed must not read as "no version active" (the bnpl precedent).
            log.warn("risk policy gauge unreadable: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.compareAndSet(current, fresh);
        return fresh;
    }
}
