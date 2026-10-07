package com.finapp.credit;

import com.finapp.sharedkernel.money.Money;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The exposure assessment (`P10-TSK-010`; PHASE_10_PLAN.md section 12.4, ADR-0088 section 2; {@code INV-CRD-09},
 * {@code INV-CRD-12}, {@code INV-CRD-07}) - engine version 1's arithmetic, read from the snapshot alone, exact in the
 * product's one currency.
 *
 * <pre>
 * exposure = the bureau's total balance          -- external
 *          + platform outstanding credit         -- PlatformCreditExposure: zero in Phase 10, recorded
 *          + reserved exposure                   -- the party's approvals still valid and unconsumed
 *          + the requested amount                -- for a CREDIT_LINE, the limit
 * within   &lt;=&gt; exposure &lt;= maximum exposure
 * headroom = maximum exposure - exposure         -- may be negative
 * </pre>
 *
 * <p>Sums and differences of {@link Money} only - nothing rounds. The reserved term is the snapshot's; the deciding
 * transaction (`P10-TSK-016`) re-reads it under the party's profile lock and, if it moved, assesses a successor
 * snapshot - this class never reads anything but the snapshot it is given.
 *
 * <p><strong>Absence is never a zero.</strong> The bureau's total balance absent (unavailable, or in another currency)
 * makes the figure {@link Assessment.Unassessable} naming it, for the policy to reason about as {@code ABSENT}; the
 * platform's two terms are always present, answered by the ports.
 */
public final class ExposureAssessment {

    /** The engine version whose arithmetic this is. */
    public static final int ENGINE_VERSION = 1;

    private ExposureAssessment() {}

    /** What the assessment found. */
    public sealed interface Assessment permits Assessment.Assessed, Assessment.Unassessable {

        /** Every figure, in the product's currency. */
        record Assessed(Money exposure, Money headroom, boolean within) implements Assessment {
            /** No figure renders. */
            @Override
            public String toString() {
                return "Assessed[within=" + within + "]";
            }
        }

        /** A required input was absent - named, never a zero. */
        record Unassessable(Set<CreditAttributeCode> absent) implements Assessment {
            public Unassessable {
                absent = Set.copyOf(absent);
            }
        }
    }

    /** Assesses the snapshot's request against the pinned policy's maximum exposure. */
    public static Assessment assess(SnapshotContent snapshot, Money maximumExposure) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(maximumExposure, "maximumExposure");
        if (!maximumExposure.currency().equals(snapshot.product().currency())) {
            throw new IllegalArgumentException("the maximum exposure is in the product's currency (INV-CRD-12)");
        }
        Set<CreditAttributeCode> absent = EnumSet.noneOf(CreditAttributeCode.class);
        Optional<Money> bureau = money(snapshot, CreditAttributeCode.BUREAU_TOTAL_BALANCE, absent);
        Optional<Money> outstanding = money(snapshot, CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT, absent);
        Optional<Money> reserved = money(snapshot, CreditAttributeCode.PLATFORM_RESERVED_EXPOSURE, absent);
        if (!absent.isEmpty()) {
            return new Assessment.Unassessable(absent);
        }
        Money exposure = bureau.get().plus(outstanding.get()).plus(reserved.get()).plus(snapshot.requestedAmount());
        Money headroom = maximumExposure.minus(exposure);
        return new Assessment.Assessed(exposure, headroom, exposure.compareTo(maximumExposure) <= 0);
    }

    private static Optional<Money> money(SnapshotContent snapshot, CreditAttributeCode code, Set<CreditAttributeCode> absent) {
        if (snapshot.attribute(code).value() instanceof AttributeValue.MoneyValue money) {
            return Optional.of(money.value());
        }
        absent.add(code);
        return Optional.empty();
    }
}
