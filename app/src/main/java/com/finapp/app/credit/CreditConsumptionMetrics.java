package com.finapp.app.credit;

import com.finapp.app.telemetry.AfterCommit;
import com.finapp.credit.ConsumptionObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * The consumption's counter (`P11-TSK-001`; PHASE_11_PLAN.md section 15): {@code finapp.credit.consumption{outcome}},
 * one series per answer of credit's consumption port - {@code consumed}, {@code replayed}, {@code not_approved},
 * {@code lapsed}, {@code already_consumed}, {@code not_found} - a closed set, never a decision, party, consumer or amount
 * (ADR-0072).
 *
 * <p><strong>Counted after the commit</strong>: the port answers inside its caller's transaction, so the count rides
 * {@link AfterCommit} - a consumption the caller rolls back (any answer but {@code consumed} rolls an acceptance back,
 * ADR-0091 point 4) counts nothing. <strong>Published from startup</strong>: every outcome registered at construction,
 * so the series exist before the first consumption.
 */
public final class CreditConsumptionMetrics implements ConsumptionObserver {

    public static final String CONSUMPTION = "finapp.credit.consumption";

    private final MeterRegistry registry;

    public CreditConsumptionMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Outcome outcome : Outcome.values()) {
            counter(outcome);
        }
    }

    @Override
    public void answered(Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        AfterCommit.run(() -> counter(outcome).increment());
    }

    private Counter counter(Outcome outcome) {
        return Counter.builder(CONSUMPTION)
                .description("Answers of credit's decision consumption port, by outcome - counted after commit")
                .tags("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(registry);
    }
}
