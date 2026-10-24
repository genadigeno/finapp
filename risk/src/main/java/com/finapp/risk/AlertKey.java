package com.finapp.risk;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * An alert's identity - (subject reference, rule code, window start) (`P13-TSK-015`; {@code risk V010}'s
 * {@code risk_alert_born_once}; {@code INV-RSK-06}). Two raises with one key are one alert, whoever raises them and
 * whenever. Ordered: {@link Alerts#raiseAll} raises a transaction's alerts in this order, so two transactions raising
 * overlapping keys meet on them in one order and never wait on each other's uncommitted entries in a cycle (the
 * velocity windows' sorted-order argument, applied to the unique).
 */
public record AlertKey(String subjectRef, String ruleCode, Instant windowStart) implements Comparable<AlertKey> {

    private static final Comparator<AlertKey> ORDER = Comparator.comparing(AlertKey::subjectRef)
            .thenComparing(AlertKey::ruleCode)
            .thenComparing(AlertKey::windowStart);

    public AlertKey {
        Objects.requireNonNull(subjectRef, "subjectRef");
        Objects.requireNonNull(ruleCode, "ruleCode");
        Objects.requireNonNull(windowStart, "windowStart");
    }

    @Override
    public int compareTo(AlertKey other) {
        return ORDER.compare(this, other);
    }
}
