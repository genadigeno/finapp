package com.finapp.risk;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An alert's identity - (subject reference, rule code, window start) and, for a kind whose every alert speaks for its
 * one signal, that signal (`P13-TSK-015`; {@code risk V010}'s {@code risk_alert_born_once}, re-made by {@code risk V018}
 * with {@code fact_signal_id}, {@code NULLS NOT DISTINCT}; {@code INV-RSK-06}; {@link AlertKind#oneAlertPerSignal}).
 * Two raises with one key are one alert, whoever raises them and whenever. Ordered: {@link Alerts#raiseAll} raises a
 * transaction's alerts in this order, so two transactions raising overlapping keys meet on them in one order and never
 * wait on each other's uncommitted entries in a cycle (the velocity windows' sorted-order argument, applied to the
 * unique).
 *
 * @param factSignal the alert's one signal when its kind is {@link AlertKind#oneAlertPerSignal} - empty for every other
 *     kind, as {@code fact_signal_id} is {@code NULL}
 */
public record AlertKey(String subjectRef, String ruleCode, Instant windowStart, Optional<UUID> factSignal)
        implements Comparable<AlertKey> {

    private static final Comparator<AlertKey> ORDER = Comparator.comparing(AlertKey::subjectRef)
            .thenComparing(AlertKey::ruleCode)
            .thenComparing(AlertKey::windowStart)
            .thenComparing(key -> key.factSignal().map(UUID::toString).orElse(""));

    public AlertKey {
        Objects.requireNonNull(subjectRef, "subjectRef");
        Objects.requireNonNull(ruleCode, "ruleCode");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(factSignal, "factSignal");
    }

    /** The key of a kind with no signal in its identity - every kind but {@code SCREENING_HIT}. */
    public AlertKey(String subjectRef, String ruleCode, Instant windowStart) {
        this(subjectRef, ruleCode, windowStart, Optional.empty());
    }

    /** The key of an alert of {@code kind} with these facts - its one signal named when the kind is keyed by it. */
    static AlertKey of(AlertKind kind, String subjectRef, String ruleCode, Instant windowStart, AlertEvidence evidence) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(evidence, "evidence");
        return new AlertKey(subjectRef, ruleCode, windowStart, kind.oneAlertPerSignal()
                ? Optional.of(evidence.signals().getFirst().value()) : Optional.empty());
    }

    @Override
    public int compareTo(AlertKey other) {
        return ORDER.compare(this, other);
    }
}
