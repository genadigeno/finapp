package com.finapp.risk;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The sweep's and the profile recompute's readings (`P13-TSK-003`; {@code PHASE_13_PLAN.md} sections 12.5, 12.7;
 * `-020`, `-007`) - what the {@code MONITORING} kinds judge, each a value sealed by the reader: the days without a
 * movement before the window's first ({@link RuleKind#DORMANT_THEN_ACTIVE}), the in- and outflow per window
 * ({@link RuleKind#RAPID_IN_OUT}), the movement amounts per window ({@link RuleKind#STRUCTURING}), the refund and
 * chargeback shares per window ({@link RuleKind#REFUND_RATIO}, {@link RuleKind#CHARGEBACK_RATIO}) and the BNPL
 * applications per window ({@link RuleKind#BNPL_MULTI_APPLICATION}). A window a map does not hold is {@code ABSENT}
 * for the rule naming it. {@link #none()} for a judged operation, which carries none of these.
 *
 * <p>Every map is copied into an {@link EnumMap} - declaration order, the same in any JVM - never a
 * {@code Map.copyOf}, whose iteration order is salted per process.
 */
public record MonitoringReadings(
        Reading<Integer> dormantDays,
        Map<WindowKind, Flow> flows,
        Map<WindowKind, Amounts> amounts,
        Map<WindowKind, Ratio> refunds,
        Map<WindowKind, Ratio> chargebacks,
        Map<WindowKind, Long> bnplApplications) {

    public MonitoringReadings {
        Objects.requireNonNull(dormantDays, "dormantDays");
        dormantDays.value().ifPresent(days -> {
            if (days < 0 || days > EvaluationInputs.MAX_DAYS) {
                throw new IllegalArgumentException("dormant days are 0 to " + EvaluationInputs.MAX_DAYS);
            }
        });
        flows = ordered(flows, "flows");
        amounts = ordered(amounts, "amounts");
        refunds = ordered(refunds, "refunds");
        chargebacks = ordered(chargebacks, "chargebacks");
        bnplApplications = ordered(bnplApplications, "bnplApplications");
        for (long count : bnplApplications.values()) {
            if (count < 0 || count > EvaluationInputs.MAX_COUNT) {
                throw new IllegalArgumentException("a BNPL application count is 0 to " + EvaluationInputs.MAX_COUNT);
            }
        }
    }

    /** No monitoring reading at all - a judged operation's. */
    public static MonitoringReadings none() {
        return new MonitoringReadings(Reading.absent(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    static <V> Map<WindowKind, V> ordered(Map<WindowKind, V> map, String name) {
        Objects.requireNonNull(map, name);
        EnumMap<WindowKind, V> ordered = new EnumMap<>(WindowKind.class);
        for (Map.Entry<WindowKind, V> entry : map.entrySet()) {
            ordered.put(Objects.requireNonNull(entry.getKey(), name + " key"),
                    Objects.requireNonNull(entry.getValue(), name + " value"));
        }
        return Collections.unmodifiableMap(ordered);
    }

    @Override
    public String toString() {
        // Never a figure: the readings describe a party's money movements.
        return "MonitoringReadings[" + flows.size() + " flows, " + amounts.size() + " amount windows]";
    }
}
