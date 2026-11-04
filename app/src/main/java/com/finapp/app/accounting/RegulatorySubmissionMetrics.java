package com.finapp.app.accounting;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code finapp.accounting.regulatory.submission{outcome}} (`P14-TSK-018`; {@code PHASE_14_PLAN.md} section 16; alert {@code RegulatorySubmissionRejected}, `P14-TSK-020`'s): one count per act and per answer, after its commit. The tag is a closed vocabulary
 * in the code: never an id, a form, a jurisdiction, a reason or a code a provider sent. Registered eagerly, so a dashboard sees a zero rather than an absence.
 *
 * <p>{@code rejected} is the <em>provider's</em>; {@code refused} a <em>controller's</em>. {@code run_bytes_differ} is an integrity failure, and {@code contrary} an answer disagreeing with a conclusion: each means someone should look.
 */
public final class RegulatorySubmissionMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_regulatory_submission_total}. */
    public static final String SUBMISSION = "finapp.accounting.regulatory.submission";

    /** The {@code outcome} tag's values. */
    public static final List<String> OUTCOMES = List.of("drafted", "validated", "validation_failed", "approved", "refused", "withdrawn", "submitted", "acknowledged", "rejected", "undelivered", "unknown", "not_received",
            "resent", "resends_exhausted", "call_failed", "record_failed", "late", "contrary", "answer_anomaly", "stale", "self_approval_refused", "run_bytes_differ", "figure_unresolved", "source_not_final");

    private final Map<String, Counter> counters = new LinkedHashMap<>();

    public RegulatorySubmissionMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (String outcome : OUTCOMES) {
            counters.put(outcome, Counter.builder(SUBMISSION).tag("outcome", outcome)
                    .description("Regulatory submissions' acts and the provider's answers, after their commit").register(registry));
        }
    }

    /** One {@code outcome} of the closed list. */
    public void count(String outcome) {
        Counter counter = counters.get(outcome);
        if (counter == null) {
            throw new IllegalArgumentException("not an outcome of the closed list: " + outcome);
        }
        counter.increment();
    }
}
