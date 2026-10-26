package com.finapp.risk;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * How long a case of each kind may stay open before it is overdue (`P13-TSK-016`; ADR-0110's follow-up "the SLA per
 * kind is a policy figure the owner sets at activation; until then the default is the task's"; {@code DESIGN-016} D14).
 * Stamped at the opening by {@code risk V012}'s trigger on the database clock - {@code sla_deadline = opened_at +
 * sla_hours} - never an instance's clock; nothing transitions on it: the gauge and the alert on an overdue case are
 * `-018`'s.
 *
 * <ul>
 *   <li>{@code ATO} 4 hours - the customer's account is being taken from them now;</li>
 *   <li>{@code FRAUD} 24 hours - inside a held withdrawal's 72-hour validity with room to act;</li>
 *   <li>{@code MERCHANT_ABUSE} 72 hours - a merchant's ratio is a trend, not a seizure;</li>
 *   <li>{@code AML} 720 hours (30 days) - the investigation a filing decision rests on;</li>
 *   <li>{@code COLLECTIONS} 168 hours - a default's case (`P13-TSK-025`, C3): a credit fact to be worked within the
 *       week, the queue's routine, not a seizure; C3 fixes no figure (owner item).</li>
 * </ul>
 */
public final class SlaDeadlines {

    /** The longest SLA a case may carry - the {@code CHECK}'s 2160 hours (90 days). */
    public static final int MAX_HOURS = 2160;

    private static final Map<CaseKind, Integer> HOURS = new EnumMap<>(Map.of(
            CaseKind.ATO, 4,
            CaseKind.FRAUD, 24,
            CaseKind.MERCHANT_ABUSE, 72,
            CaseKind.AML, 720,
            CaseKind.COLLECTIONS, 168));

    static {
        for (CaseKind kind : CaseKind.values()) {
            Integer hours = HOURS.get(kind);
            if (hours == null || hours < 1 || hours > MAX_HOURS) {
                throw new ExceptionInInitializerError("no SLA within bounds for " + kind);
            }
        }
    }

    private SlaDeadlines() {}

    /** The SLA of {@code kind}, in whole hours. */
    public static int hours(CaseKind kind) {
        return HOURS.get(Objects.requireNonNull(kind, "kind"));
    }

    /** The SLA of {@code kind}. */
    public static Duration of(CaseKind kind) {
        return Duration.ofHours(hours(kind));
    }
}
